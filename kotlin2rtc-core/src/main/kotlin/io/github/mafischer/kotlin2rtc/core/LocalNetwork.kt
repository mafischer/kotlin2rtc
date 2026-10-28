package io.github.mafischer.kotlin2rtc.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** One address of this device on its network with its prefix (e.g. Android's LinkAddress), e.g. 192.168.1.23/24 */
public data class LinkSubnet(val address: InetAddress, val prefixLength: Int) {
    /** [other] is in this subnet: same family, the first [prefixLength] bits equal */
    public fun contains(other: InetAddress): Boolean {
        val mine = address.address
        val theirs = other.address
        if (mine.size != theirs.size || prefixLength !in 1..mine.size * 8) return false
        // An IPv6 link-local address is only meaningful on its own link: the scope must match when both carry one
        if (address is Inet6Address && other is Inet6Address && address.isLinkLocalAddress && other.scopeId != 0 && address.scopeId != 0 &&
            other.scopeId != address.scopeId
        ) {
            return false
        }
        val fullBytes = prefixLength / 8
        for (index in 0 until fullBytes) if (mine[index] != theirs[index]) return false
        val rest = prefixLength % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (mine[fullBytes].toInt() and mask) == (theirs[fullBytes].toInt() and mask)
    }

    override fun toString(): String = "${address.hostAddress}/$prefixLength"

    public companion object {
        /** "192.168.1.23/24" */
        public fun parse(text: String): LinkSubnet = LinkSubnet(InetAddress.getByName(text.substringBefore('/')), text.substringAfter('/').toInt())
    }
}

/**
 * The subnets of this device's current network, and where cleartext camera protocols (ONVIF's plain HTTP, RTSP) may go
 * ([allowsCamera]): an address in one of these subnets or in a private range (a camera answering on another subnet,
 * through a VPN or an emulator's NAT, is still a camera on a private network); never the internet. Whether the device is
 * on a camera's subnet ([allows]) tells discovery's answers apart. Loopback, multicast and unspecified addresses never
 * qualify, whatever the subnets.
 *
 * The library does not watch the platform's network: the caller feeds [subnetsChanged] (on Android from
 * ConnectivityManager's default-network callback and LinkProperties). Thread-safe for reads while it changes.
 */
public class LocalNetwork(initial: List<LinkSubnet> = emptyList()) {
    /** The current subnets */
    @Volatile
    public var subnets: List<LinkSubnet> = initial
        private set

    /** Incremented on every change, so callers can re-check their cameras */
    @Volatile
    public var generation: Int = 0
        private set

    /** Replaces the subnets; the same list again is no change */
    @Synchronized
    public fun subnetsChanged(subnets: List<LinkSubnet>) {
        if (subnets == this.subnets) return
        this.subnets = subnets
        generation += 1
    }

    /** The device has a network */
    public val isConnected: Boolean get() = subnets.isNotEmpty()

    /** For messages: the IPv4 subnets ("192.168.1.23/24"), else the IPv6 ones */
    public fun describe(): String = subnets.filter { it.address is Inet4Address }.ifEmpty { subnets }.joinToString(", ")

    /** [address] is inside one of the current subnets */
    public fun allows(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) return false
        return subnets.any { it.contains(address) }
    }

    /**
     * [address] is a camera this device may talk to in plain HTTP or RTSP: on one of its subnets, or in a private range
     * ([isPrivate]) while the device has a network. Every other address (the internet) is refused.
     */
    public fun allowsCamera(address: InetAddress): Boolean = allows(address) || (isConnected && isPrivate(address))

    /** The first of [addresses] (one DNS answer) that is on this network, or null */
    public fun pick(addresses: List<InetAddress>): InetAddress? = addresses.firstOrNull(::allows)

    /** The first of [addresses] a camera may have ([allowsCamera]), on this network first, or null */
    public fun pickCamera(addresses: List<InetAddress>): InetAddress? = pick(addresses) ?: addresses.firstOrNull(::allowsCamera)

    public companion object {
        /**
         * A private address: IPv4 10/8, 172.16/12, 192.168/16, link-local 169.254/16 and shared 100.64/10 (carrier NAT,
         * VPNs such as Tailscale); IPv6 unique local fc00::/7 and link-local fe80::/10
         */
        public fun isPrivate(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) return false
            val bytes = address.address.map { it.toInt() and 0xFF }
            return if (address is Inet4Address) {
                bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168) ||
                    (bytes[0] == 169 && bytes[1] == 254) || (bytes[0] == 100 && bytes[1] in 64..127)
            } else {
                (bytes[0] and 0xFE) == 0xFC || (bytes[0] == 0xFE && (bytes[1] and 0xC0) == 0x80)
            }
        }
    }
}
