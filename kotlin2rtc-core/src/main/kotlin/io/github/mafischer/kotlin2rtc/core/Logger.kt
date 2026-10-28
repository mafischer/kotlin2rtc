package io.github.mafischer.kotlin2rtc.core

/**
 * Where the library's diagnostic lines go. The library never logs credentials, and URLs it logs carry no user info; it
 * logs little (a backchannel opened or dropped, a camera refusing a command). The default is [None]: silent.
 */
public fun interface Logger {
    /** One line at [level] */
    public fun log(level: Level, message: String)

    /** How important a line is */
    public enum class Level { DEBUG, INFO, WARN, ERROR }

    public fun debug(message: String): Unit = log(Level.DEBUG, message)

    public fun info(message: String): Unit = log(Level.INFO, message)

    public fun warn(message: String): Unit = log(Level.WARN, message)

    public fun error(message: String): Unit = log(Level.ERROR, message)

    public companion object {
        /** Discards every line */
        public val None: Logger = Logger { _, _ -> }

        /** Writes every line at [minimum] or above to standard error (the command line sample, debugging) */
        public fun stderr(minimum: Level = Level.INFO): Logger = Logger { level, message ->
            if (level >= minimum) System.err.println("kotlin2rtc ${level.name}: $message")
        }
    }
}
