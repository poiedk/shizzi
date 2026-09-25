package dev.shizzi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.shizzi.ui.theme.AccentChoice
import dev.shizzi.ui.theme.DesignLanguage
import dev.shizzi.ui.theme.ThemeChoice
import dev.shizzi.ui.theme.parseAccent
import dev.shizzi.ui.theme.parseAccents
import dev.shizzi.ui.theme.serialize
import dev.shizzi.ui.theme.serializeAccents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val design: DesignLanguage = DesignLanguage.MATERIAL_EXPRESSIVE,
    val accent: AccentChoice = AccentChoice.Default,
    val customAccents: List<Int> = emptyList(),
    val isLogging: Boolean = true,

    val vpnMode: VpnMode = VpnMode.AUTO,
    val hotspotRange: HotspotRange = HotspotRange.PRIVATE_172,
    val hotspotSubnet: String = "172.16.0.0/24",
    val manualClientIp: String = "",
    val clientDesiredIps: Map<String, String> = emptyMap(),
    val clientPriority: List<String> = emptyList(),

    val hasCompletedOnboarding: Boolean = false,

    val isAutomationEnabled: Boolean = false,
    val automationToken: String = "",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(RenamedKeys.migration()) },
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map(::toSettings)

    suspend fun backfillTokenIfEnabled() {
        context.dataStore.edit { preferences ->
            if (preferences[AUTOMATION] != true) return@edit

            preferences.mintTokenIfAbsent()
        }
    }

    suspend fun setTheme(choice: ThemeChoice) {
        context.dataStore.edit { it[THEME] = choice.name }
    }

    suspend fun setDesign(design: DesignLanguage) {
        context.dataStore.edit { it[DESIGN] = design.name }
    }

    suspend fun setAccent(accent: AccentChoice) {
        context.dataStore.edit { it[ACCENT] = accent.serialize() }
    }

    suspend fun addCustomAccent(argb: Int) {
        context.dataStore.edit { preferences ->
            val existing = parseAccents(preferences[CUSTOM_ACCENTS])
            if (argb in existing) return@edit

            preferences[CUSTOM_ACCENTS] = serializeAccents(existing + argb)
        }
    }

    suspend fun setLogging(enabled: Boolean) {
        context.dataStore.edit { it[LOGGING] = enabled }
    }

    suspend fun setVpnMode(mode: VpnMode) {
        context.dataStore.edit { it[VPN_MODE] = mode.name }
    }

    suspend fun setHotspotRange(range: HotspotRange) {
        context.dataStore.edit { it[HOTSPOT_RANGE] = range.name }
    }

    suspend fun setHotspotSubnet(subnet: String) {
        context.dataStore.edit { it[HOTSPOT_SUBNET] = subnet }
    }

    suspend fun setManualClientIp(address: String) {
        context.dataStore.edit { it[MANUAL_CLIENT_IP] = address }
    }

    suspend fun setClientDesiredIp(mac: String, address: String) {
        val normalizedMac = mac.lowercase()
        context.dataStore.edit { preferences ->
            val assignments = parseClientDesiredIps(preferences[CLIENT_DESIRED_IPS]).toMutableMap()
            if (address.isBlank()) assignments.remove(normalizedMac) else assignments[normalizedMac] = address.trim()
            preferences[CLIENT_DESIRED_IPS] = serializeClientDesiredIps(assignments)

            val priority = parseClientPriority(preferences[CLIENT_PRIORITY]).toMutableList()
            priority.remove(normalizedMac)
            if (address.isNotBlank()) priority += normalizedMac
            preferences[CLIENT_PRIORITY] = priority.joinToString("\n")
        }
    }

    suspend fun moveClientPriority(mac: String, delta: Int) {
        val normalizedMac = mac.lowercase()
        context.dataStore.edit { preferences ->
            val priority = parseClientPriority(preferences[CLIENT_PRIORITY]).toMutableList()
            val from = priority.indexOf(normalizedMac)
            if (from < 0) return@edit
            val to = (from + delta).coerceIn(0, priority.lastIndex)
            if (to == from) return@edit
            priority.removeAt(from)
            priority.add(to, normalizedMac)
            preferences[CLIENT_PRIORITY] = priority.joinToString("\n")
        }
    }

    suspend fun removeClientDesiredIp(mac: String) {
        setClientDesiredIp(mac, "")
    }

    suspend fun setOnboardingComplete(hasCompleted: Boolean) {
        context.dataStore.edit { it[ONBOARDED] = hasCompleted }
    }

    suspend fun setAutomationEnabled(isEnabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTOMATION] = isEnabled
            if (isEnabled) preferences.mintTokenIfAbsent()
        }
    }

    suspend fun setAutomationToken(token: String) {
        context.dataStore.edit { it[AUTOMATION_TOKEN] = token }
    }

    private fun MutablePreferences.mintTokenIfAbsent() {
        if (!this[AUTOMATION_TOKEN].isNullOrEmpty()) return

        this[AUTOMATION_TOKEN] = AutomationToken.generate()
    }

}

internal val THEME = stringPreferencesKey("theme")
internal val DESIGN = stringPreferencesKey("design")
internal val ACCENT = stringPreferencesKey("accent")
internal val CUSTOM_ACCENTS = stringPreferencesKey("custom_accents")
internal val LOGGING = booleanPreferencesKey("logging")
internal val VPN_MODE = stringPreferencesKey("vpn_mode")
internal val HOTSPOT_RANGE = stringPreferencesKey("hotspot_range")
internal val HOTSPOT_SUBNET = stringPreferencesKey("hotspot_subnet")
internal val MANUAL_CLIENT_IP = stringPreferencesKey("manual_client_ip")
internal val CLIENT_DESIRED_IPS = stringPreferencesKey("client_desired_ips")
internal val CLIENT_PRIORITY = stringPreferencesKey("client_priority")
internal val ONBOARDED = booleanPreferencesKey("onboarded")
internal val AUTOMATION = booleanPreferencesKey("automation")
internal val AUTOMATION_TOKEN = stringPreferencesKey("automation_token")

internal fun toSettings(preferences: Preferences) = Settings(
    theme = runCatching { ThemeChoice.valueOf(preferences[THEME].orEmpty()) }
        .getOrDefault(ThemeChoice.SYSTEM),
    design = runCatching { DesignLanguage.valueOf(preferences[DESIGN].orEmpty()) }
        .getOrDefault(DesignLanguage.MATERIAL_EXPRESSIVE),
    accent = parseAccent(preferences[ACCENT]),
    customAccents = parseAccents(preferences[CUSTOM_ACCENTS]),
    isLogging = preferences[LOGGING] ?: true,
    vpnMode = parseVpnMode(preferences[VPN_MODE]),
    hotspotRange = parseHotspotRange(preferences[HOTSPOT_RANGE]),
    hotspotSubnet = preferences[HOTSPOT_SUBNET] ?: "172.16.0.0/24",
    manualClientIp = preferences[MANUAL_CLIENT_IP].orEmpty(),
    clientDesiredIps = parseClientDesiredIps(preferences[CLIENT_DESIRED_IPS]),
    clientPriority = parseClientPriority(preferences[CLIENT_PRIORITY]),
    hasCompletedOnboarding = preferences[ONBOARDED] ?: false,
    isAutomationEnabled = preferences[AUTOMATION] ?: false,
    automationToken = preferences[AUTOMATION_TOKEN].orEmpty(),
)


private fun parseClientDesiredIps(raw: String?): Map<String, String> =
    raw.orEmpty().lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
            val mac = line.substring(0, separator).trim().lowercase()
            val address = line.substring(separator + 1).trim()
            if (mac.isBlank() || address.isBlank()) null else mac to address
        }
        .toMap()

private fun serializeClientDesiredIps(assignments: Map<String, String>): String =
    assignments.entries
        .sortedBy { it.key }
        .joinToString("\n") { (mac, address) -> "$mac=$address" }

private fun parseClientPriority(raw: String?): List<String> =
    raw.orEmpty().lineSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
