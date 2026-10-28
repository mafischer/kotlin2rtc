package io.github.mafischer.kotlin2rtc.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet6Address
import java.net.InetAddress

/**
 * the subnets of the current network (IPv4 /24 and other prefixes, no network, IPv6 global and
 * link-local with its scope), and plain HTTP and RTSP to cameras only there or at private addresses, never the internet.
 */
class LocalNetworkTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `an IPv4 slash 24 holds its own addresses only`() {
        val network = LocalNetwork(listOf(LinkSubnet.parse("192.168.1.23/24")))
        assertTrue(network.allows(ip("192.168.1.64")))
        assertTrue(network.allows(ip("192.168.1.1")))
        // Other subnets, also private ones, and the internet
        for (other in listOf("192.168.2.64", "10.20.30.204", "172.16.0.5", "8.8.8.8", "169.254.1.1")) assertFalse(network.allows(ip(other)), other)
        // Never loopback, multicast or the unspecified address, whatever the subnets
        val loose = LocalNetwork(listOf(LinkSubnet.parse("127.0.0.1/8"), LinkSubnet.parse("224.0.0.1/4"), LinkSubnet.parse("0.0.0.0/1")))
        for (special in listOf("127.0.0.1", "239.255.255.250", "0.0.0.0")) assertFalse(loose.allows(ip(special)), special)
        assertEquals("192.168.1.23/24", network.describe())
    }

    @Test
    fun `other prefixes cut where they say`() {
        val wide = LinkSubnet.parse("10.20.30.74/16")
        assertTrue(wide.contains(ip("10.20.255.1")))
        assertFalse(wide.contains(ip("10.21.0.1")))
        val odd = LinkSubnet.parse("172.20.5.9/20")
        assertTrue(odd.contains(ip("172.20.0.1")))
        assertTrue(odd.contains(ip("172.20.15.254")))
        assertFalse(odd.contains(ip("172.20.16.1")))
        val small = LinkSubnet.parse("192.168.1.130/25")
        assertTrue(small.contains(ip("192.168.1.200")))
        assertFalse(small.contains(ip("192.168.1.64")))
        assertTrue(LinkSubnet.parse("192.168.1.5/32").contains(ip("192.168.1.5")))
        // Nonsense prefixes and other families never match
        assertFalse(LinkSubnet.parse("192.168.1.5/0").contains(ip("192.168.1.5")))
        assertFalse(LinkSubnet.parse("192.168.1.5/33").contains(ip("192.168.1.5")))
        assertFalse(LinkSubnet.parse("192.168.1.5/24").contains(ip("fe80::1")))
    }

    @Test
    fun `IPv6 global and link-local subnets, the link-local scope must match`() {
        val network = LocalNetwork(listOf(LinkSubnet.parse("2001:db8:1:2::10/64"), LinkSubnet.parse("fe80::10/64")))
        assertTrue(network.allows(ip("2001:db8:1:2::abcd")))
        assertFalse(network.allows(ip("2001:db8:1:3::1")))
        assertTrue(network.allows(ip("fe80::99")))
        val wlan = Inet6Address.getByAddress(null, ip("fe80::10").address, 3)
        val other = Inet6Address.getByAddress(null, ip("fe80::99").address, 4)
        val same = Inet6Address.getByAddress(null, ip("fe80::99").address, 3)
        assertFalse(LinkSubnet(wlan, 64).contains(other))
        assertTrue(LinkSubnet(wlan, 64).contains(same))
        assertEquals("2001:db8:1:2:0:0:0:10/64, fe80:0:0:0:0:0:0:10/64", network.describe())
    }

    @Test
    fun `no network allows nothing, changes count generations, one DNS answer is picked from`() {
        val network = LocalNetwork()
        assertFalse(network.isConnected)
        assertFalse(network.allows(ip("192.168.1.64")))
        network.subnetsChanged(listOf(LinkSubnet.parse("192.168.1.23/24")))
        assertEquals(1, network.generation)
        // The same subnets again are no change
        network.subnetsChanged(listOf(LinkSubnet.parse("192.168.1.23/24")))
        assertEquals(1, network.generation)
        assertEquals(ip("192.168.1.64"), network.pick(listOf(ip("10.0.0.1"), ip("192.168.1.64"))))
        assertNull(network.pick(listOf(ip("10.0.0.1"))))
        network.subnetsChanged(emptyList())
        assertEquals(2, network.generation)
        assertNull(network.pick(listOf(ip("192.168.1.64"))))
    }

    @Test
    fun `cameras may also be at private addresses of other subnets, never on the internet`() {
        val network = LocalNetwork(listOf(LinkSubnet.parse("10.0.2.16/24")))
        // The emulator's NAT, a VPN: the camera's home address is still private
        for (private in listOf("10.20.30.204", "172.16.0.5", "172.31.255.1", "192.168.1.64", "169.254.1.1", "100.64.0.7", "fd00::5", "fe80::1")) {
            assertTrue(network.allowsCamera(ip(private)), private)
            assertTrue(LocalNetwork.isPrivate(ip(private)), private)
        }
        for (public in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "127.0.0.1", "239.255.255.250", "0.0.0.0", "::1")) {
            assertFalse(network.allowsCamera(ip(public)), public)
        }
        // A public address on the device's own subnet stays allowed (IPv6 LANs)
        val v6 = LocalNetwork(listOf(LinkSubnet.parse("2001:db8:1:2::10/64")))
        assertTrue(v6.allowsCamera(ip("2001:db8:1:2::abcd")))
        // On the subnet first, then any private address
        assertEquals(ip("10.0.2.9"), network.pickCamera(listOf(ip("10.20.30.204"), ip("10.0.2.9"))))
        assertEquals(ip("10.20.30.204"), network.pickCamera(listOf(ip("8.8.8.8"), ip("10.20.30.204"))))
        // Without a network nothing
        assertFalse(LocalNetwork().allowsCamera(ip("10.20.30.204")))
    }
}
