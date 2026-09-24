package dev.shizzi

enum class HotspotRange {
    DEFAULT_192,
    PRIVATE_172,
    PRIVATE_10,
    CUSTOM,
}

fun parseHotspotRange(value: String?): HotspotRange =
    runCatching { HotspotRange.valueOf(value.orEmpty()) }
        .getOrDefault(HotspotRange.PRIVATE_172)
