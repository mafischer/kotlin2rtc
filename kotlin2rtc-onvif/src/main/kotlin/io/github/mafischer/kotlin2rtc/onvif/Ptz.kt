package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The directions of a PTZ pad (generic velocity space, half speed: smooth enough for a touch pad) */
public enum class PtzDirection(public val velocity: PtzVelocity) {
    LEFT(PtzVelocity(pan = -PTZ_SPEED)),
    RIGHT(PtzVelocity(pan = PTZ_SPEED)),
    UP(PtzVelocity(tilt = PTZ_SPEED)),
    DOWN(PtzVelocity(tilt = -PTZ_SPEED)),
    ZOOM_IN(PtzVelocity(zoom = PTZ_SPEED)),
    ZOOM_OUT(PtzVelocity(zoom = -PTZ_SPEED)),
}

private const val PTZ_SPEED = 0.5

/** How long a nudge ([PtzSession.nudge]) moves the camera */
public const val NUDGE_MILLIS: Long = 400L

/**
 * What is known of a camera's optical zoom: [available] (GetNodes lists a zoom space) and the lens's [level] from 0
 * (widest) to 1 (closest), null when the camera does not report it (GetStatus)
 */
public data class OpticalZoom(val available: Boolean, val level: Double? = null) {
    /** The lens is at its widest */
    val atWidest: Boolean get() = level != null && level <= EDGE

    /** The lens is at its closest */
    val atClosest: Boolean get() = level != null && level >= 1 - EDGE

    public companion object {
        /** No zoom space: zoom buttons can only zoom the picture (digitally) */
        public val NONE: OpticalZoom = OpticalZoom(false)

        /** Not known yet, or the camera refused GetNodes: zoom commands are sent to the lens anyway */
        public val UNKNOWN: OpticalZoom = OpticalZoom(true)

        /** How close to an end of the range counts as the end (lenses stop a little short) */
        public const val EDGE: Double = 0.02
    }
}

/**
 * Pan, tilt and zoom of one ONVIF camera (the PTZ service at [ptzUrl], the media profile [profileToken]): holding a
 * direction sends ContinuousMove ([move]), releasing it Stop ([stop]). Commands run in order on [scope] (a Stop never
 * overtakes its move); the camera's clock is read once for the WS-Security timestamp. A refused command is reported to
 * [onError]. Commands are sent only while [enabled] says so (e.g. while the camera's identity is verified); otherwise
 * they are dropped.
 */
public class PtzSession(
    private val client: OnvifClient,
    private val ptzUrl: String,
    private val profileToken: String,
    private val scope: CoroutineScope,
    private val onError: (OnvifError) -> Unit = {},
    private val logger: Logger = Logger.None,
    private val enabled: () -> Boolean = { true },
) {
    private var queue: Job? = null
    private var zoomSupport: PtzZoomSupport? = null
    private var probing = false
    private val optical = MutableStateFlow(OpticalZoom.UNKNOWN)

    /** What is known of the optical zoom ([OpticalZoom.UNKNOWN] until [probeZoom] answered) */
    public val opticalZoom: StateFlow<OpticalZoom> = optical.asStateFlow()

    /**
     * Once: GetNodes says whether the camera has an optical zoom, then GetStatus where the lens is. Quiet: a camera that
     * refuses them keeps [OpticalZoom.UNKNOWN] and is asked again on the next call.
     */
    public fun probeZoom() {
        if (zoomSupport != null || probing) return
        probing = true
        val queued = enqueue(quiet = { probing = false }) {
            val support = client.ptzZoomSupport(ptzUrl)
            zoomSupport = support
            probing = false
            optical.value = if (support.optical) OpticalZoom(true) else OpticalZoom.NONE
            if (support.optical) readZoom(support)
        }
        // Not sent (not enabled): asked again next time
        if (!queued) probing = false
    }

    /** Reads the lens's zoom position again (while and after the optical zoom moves) */
    public fun refreshZoom() {
        val support = zoomSupport?.takeIf { it.optical } ?: return
        enqueue(quiet = {}) { readZoom(support) }
    }

    private suspend fun readZoom(support: PtzZoomSupport) {
        val position = client.ptzZoomPosition(ptzUrl, profileToken)
        optical.value = OpticalZoom(true, position?.let(support::normalized))
    }

    /** Starts moving in [direction] (until [stop]) */
    public fun move(direction: PtzDirection) {
        move(direction.velocity)
    }

    /** Starts moving at [velocity] (until [stop]) */
    public fun move(velocity: PtzVelocity) {
        enqueue { client.continuousMove(ptzUrl, profileToken, velocity) }
    }

    /** Stops pan, tilt and zoom */
    public fun stop() {
        enqueue { client.stop(ptzUrl, profileToken) }
    }

    /** A short step (for taps instead of holds, e.g. accessibility services): move, wait [NUDGE_MILLIS], stop */
    public fun nudge(direction: PtzDirection) {
        move(direction)
        enqueue { delay(NUDGE_MILLIS) }
        stop()
    }

    /** Cancels queued commands and forgets the zoom probe */
    public fun reset() {
        queue?.cancel()
        queue = null
        zoomSupport = null
        probing = false
        optical.value = OpticalZoom.UNKNOWN
    }

    /** Runs [command] after the earlier ones; a refusal goes to [onError], or only to [quiet] when given. False when not enabled. */
    private fun enqueue(quiet: (() -> Unit)? = null, command: suspend () -> Unit): Boolean {
        if (!enabled()) {
            logger.info("PTZ skipped: not enabled")
            return false
        }
        val previous = queue
        queue = scope.launch {
            previous?.join()
            try {
                if (!client.clockSynced) client.syncClock()
                command()
            } catch (e: OnvifError) {
                if (quiet != null) {
                    logger.info("PTZ zoom query refused: ${e.message}")
                    quiet()
                    return@launch
                }
                logger.warn("PTZ command refused: ${e.message}")
                onError(e)
            }
        }
        return true
    }
}
