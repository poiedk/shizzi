package dev.shizzi

import android.content.Context
import org.json.JSONObject

enum class Capability {
    TEST_NETWORK,
    PREFER_TEST_NETWORKS,
    NETWORK_STACK_BINDER,
    NETWORK_STACK_PERMISSION,
}

data class CapabilityResult(
    val capability: Capability,
    val isPresent: Boolean,
    val detail: String,
)

class CompatibilityCheck(private val context: Context) {

    fun run(): List<CapabilityResult> = Capability.entries.map { capability ->
        when (capability) {
            Capability.TEST_NETWORK -> checkTestNetwork()
            Capability.PREFER_TEST_NETWORKS -> checkPreferTestNetworks()
            Capability.NETWORK_STACK_BINDER -> checkNetworkStackBinder()
            Capability.NETWORK_STACK_PERMISSION -> checkNetworkStackPermission()
        }
    }

    private fun checkTestNetwork(): CapabilityResult {
        val api = TestNetworkApi(context)

        return CapabilityResult(
            capability = Capability.TEST_NETWORK,
            isPresent = api.isAvailable,
            detail = when {
                api.isAvailable -> "getSystemService(\"test_network\") returned an instance"
                else -> "test_network service or TestNetworkManager class unavailable"
            },
        )
    }

    private fun checkPreferTestNetworks(): CapabilityResult {
        val failure = TetheringPreferenceApi(context).resolutionFailure()

        return CapabilityResult(
            capability = Capability.PREFER_TEST_NETWORKS,
            isPresent = failure == null,
            detail = failure ?: "TetheringManager.setPreferTestNetworks resolved",
        )
    }

    private fun checkNetworkStackBinder(): CapabilityResult {
        val outcome = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "network_stack") as? android.os.IBinder
                ?: error("ServiceManager.getService(network_stack) returned null")
            binder.interfaceDescriptor
        }

        return CapabilityResult(
            capability = Capability.NETWORK_STACK_BINDER,
            isPresent = outcome.isSuccess,
            detail = outcome.fold(
                onSuccess = { descriptor -> "network_stack binder found: $descriptor" },
                onFailure = { failure ->
                    "${failure.javaClass.simpleName}: ${failure.message}"
                },
            ),
        )
    }

    private fun checkNetworkStackPermission(): CapabilityResult {
        val permission = "android.permission.NETWORK_STACK"
        val granted = context.checkPermission(
            permission,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return CapabilityResult(
            capability = Capability.NETWORK_STACK_PERMISSION,
            isPresent = granted,
            detail = if (granted) {
                "$permission granted to uid=${android.os.Process.myUid()}"
            } else {
                "$permission NOT granted to uid=${android.os.Process.myUid()}"
            },
        )
    }
}

fun List<CapabilityResult>.toJson(): String = JSONObject().apply {
    this@toJson.forEach { result ->
        put(
            result.capability.name,
            JSONObject().apply {
                put("present", result.isPresent)
                put("detail", result.detail)
            },
        )
    }
}.toString()

fun parseCapabilities(report: String): List<CapabilityResult> {
    val parsed = runCatching { JSONObject(report) }.getOrNull()

    return Capability.entries.map { capability ->
        val entry = parsed?.optJSONObject(capability.name)

        CapabilityResult(
            capability = capability,
            isPresent = entry?.optBoolean("present") == true,
            detail = entry?.optString("detail").orEmpty()
                .ifEmpty { "not reported by the privileged process" },
        )
    }
}
