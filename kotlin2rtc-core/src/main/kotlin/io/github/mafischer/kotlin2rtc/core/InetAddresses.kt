package io.github.mafischer.kotlin2rtc.core

import java.net.InetAddress

/** Address helpers that never touch DNS */
public object InetAddresses {
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** An IPv4 or IPv6 literal (brackets allowed) as an address, else null (a host name: no DNS lookup) */
    public fun literal(host: String): InetAddress? {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (!ipv4.matches(bare) && ':' !in bare) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }
}
