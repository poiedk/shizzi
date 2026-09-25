package dev.shizzi

data class HotspotClientLease(
    val address: String,
    val mac: String,
    val predictedAddress: String?,
) {
    val isPredictionMatch: Boolean get() = predictedAddress == address
}

class DownstreamInspector(private val inspector: UpstreamInspector = UpstreamInspector()) {

    private var cachedCount = 0
    private var lastReadAt = 0L

    private var loggedCount = 0

    fun findTetheredDownstream(): String? {
        val observation = inspector.observe()
        if (observation.didTimeout) return "dumpsys tethering timed out; downstream state unknown"

        val tethered = observation.rawOutput.lineSequence()
            .mapNotNull { line -> TETHERED_STATE_PATTERN.find(line)?.groupValues?.get(1) }
            .distinct()
            .toList()

        return when {
            tethered.isEmpty() -> null
            else -> "downstream still tethered: $tethered"
        }
    }

    fun ipv4Address(): String? {
        val observation = inspector.observe()
        if (observation.didTimeout) return null
        return parseIpv4Address(observation.rawOutput)
    }

    fun clientLeases(): List<HotspotClientLease> {
        val observation = inspector.observe()
        if (observation.didTimeout) return emptyList()

        val hotspotAddress = parseIpv4Address(observation.rawOutput)
        return CLIENT_PATTERN.findAll(observation.rawOutput)
            .map { match ->
                val address = match.groupValues[1]
                val mac = match.groupValues[2].lowercase()
                HotspotClientLease(
                    address = address,
                    mac = mac,
                    predictedAddress = predictAddress(mac, hotspotAddress),
                )
            }
            .distinctBy { it.mac }
            .toList()
    }

    fun countDevices(): Int {
        val now = System.currentTimeMillis()
        if (now - lastReadAt < REFRESH_INTERVAL_MS) return cachedCount

        val observation = inspector.observeWifi()
        if (observation.didTimeout) return cachedCount

        lastReadAt = now
        cachedCount = parseDeviceCount(observation.rawOutput)
        logCountChange(cachedCount)
        return cachedCount
    }

    private fun parseIpv4Address(output: String): String? {
        val downstreamBlock = output
            .substringAfter("mDownstreams:", missingDelimiterValue = "")
            .substringBefore("mCachedAddresses:", missingDelimiterValue = "")

        return IPV4_PREFIX_PATTERN.find(downstreamBlock)?.groupValues?.get(1)
    }

    private fun predictAddress(mac: String, hotspotAddress: String?): String? {
        val cidr = hotspotAddress ?: return null
        val slash = cidr.lastIndexOf('/')
        if (slash < 0 || cidr.substring(slash + 1) != "24") return null

        val gateway = cidr.substring(0, slash).split(".")
        if (gateway.size != 4) return null

        val host = mac.split(":")
            .takeIf { it.size == 6 }
            ?.mapNotNull { it.toIntOrNull(16) }
            ?.takeIf { it.size == 6 }
            ?.sum()
            ?.and(0xff)
            ?: return null

        return "${gateway[0]}.${gateway[1]}.${gateway[2]}.$host"
    }

    private fun logCountChange(count: Int) {
        if (count == loggedCount) return

        val direction = if (count > loggedCount) "connected" else "disconnected"
        loggedCount = count
        SessionLog.info("client $direction: $count now on the hotspot")
    }

    private fun parseDeviceCount(output: String): Int =
        CONNECTED_CLIENTS_PATTERN.findAll(output)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?: 0

    private companion object {
        private val TETHERED_STATE_PATTERN =
            Regex("""^\s*(\w+)\s+-\s+TetheredState\s+-""")
        private val CONNECTED_CLIENTS_PATTERN =
            Regex("""getConnectedClientList\(\)\.size\(\):\s*(\d+)""")
        private val IPV4_PREFIX_PATTERN =
            Regex("""\b((?:10|172|192)\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2})\b""")
        private val CLIENT_PATTERN =
            Regex("""client:\s*/((?:\d{1,3}\.){3}\d{1,3})\s*\(([0-9a-fA-F:]{17})\)""")
        private const val REFRESH_INTERVAL_MS = 10_000L
    }
}