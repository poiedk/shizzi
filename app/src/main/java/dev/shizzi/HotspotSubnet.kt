package dev.shizzi

import java.net.InetAddress

data class HotspotSubnet(
    val network: Int,
    val prefixLength: Int = 24,
) {
    val cidr: String get() = "${ipv4(network)}/$prefixLength"

    fun conflictMarkers(): List<Pair<String, Int>> {
        val pools = PRIVATE_POOLS
        val pool = pools.firstOrNull { contains(it.first, it.second, network) }
            ?: error("Subnet must be inside RFC1918 private address space")

        require(prefixLength == 24) { "Only /24 hotspot subnets are supported" }
        require(network == masked(network, prefixLength)) { "Subnet must be a network address" }

        val markers = mutableListOf<Pair<Int, Int>>()

        pools.filterNot { it == pool }.forEach { markers += it }

        var currentBase = pool.first
        for (prefix in pool.second until prefixLength) {
            val childPrefix = prefix + 1
            val splitBit = 1 shl (32 - childPrefix)
            val targetUsesUpperHalf = (network and splitBit) != 0

            val siblingBase = if (targetUsesUpperHalf) {
                currentBase
            } else {
                currentBase or splitBit
            }
            markers += siblingBase to childPrefix

            if (targetUsesUpperHalf) currentBase = currentBase or splitBit
        }

        return markers.map { (base, prefix) ->
            markerHost(base, prefix) to prefix
        }
    }

    companion object {
        fun parse(value: String): HotspotSubnet {
            val parts = value.trim().split("/")
            require(parts.size == 2) { "Use CIDR format, e.g. 172.16.0.0/24" }

            val prefix = parts[1].toIntOrNull()
                ?: error("Invalid prefix length")
            require(prefix == 24) { "Only /24 hotspot subnets are supported" }

            val address = parseIpv4(parts[0])
            val network = masked(address, prefix)
            require(address == network) { "Use the network address, e.g. 172.16.0.0/24" }

            val isPrivate = PRIVATE_POOLS.any { contains(it.first, it.second, network) }
            require(isPrivate) { "Subnet must be inside 10/8, 172.16/12, or 192.168/16" }

            return HotspotSubnet(network, prefix)
        }

        private val PRIVATE_POOLS = listOf(
            parseIpv4("10.0.0.0") to 8,
            parseIpv4("172.16.0.0") to 12,
            parseIpv4("192.168.0.0") to 16,
        )

        private fun parseIpv4(value: String): Int {
            val octets = value.split(".")
            require(octets.size == 4) { "Invalid IPv4 address" }

            return octets.fold(0) { acc, part ->
                val octet = part.toIntOrNull() ?: error("Invalid IPv4 address")
                require(octet in 0..255) { "Invalid IPv4 address" }
                (acc shl 8) or octet
            }
        }

        private fun masked(value: Int, prefix: Int): Int {
            if (prefix == 0) return 0
            val mask = -1 shl (32 - prefix)
            return value and mask
        }

        private fun contains(base: Int, prefix: Int, address: Int): Boolean =
            masked(base, prefix) == masked(address, prefix)

        private fun markerHost(base: Int, prefix: Int): String {
            val host = if (prefix < 32) base + 1 else base
            return ipv4(host)
        }

        private fun ipv4(value: Int): String =
            listOf(
                (value ushr 24) and 0xff,
                (value ushr 16) and 0xff,
                (value ushr 8) and 0xff,
                value and 0xff,
            ).joinToString(".")
    }
}
