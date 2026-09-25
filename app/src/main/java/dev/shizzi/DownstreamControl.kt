package dev.shizzi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private val TETHER_ERROR_NAMES = mapOf(
    0 to "NO_ERROR",
    1 to "UNKNOWN_IFACE",
    2 to "SERVICE_UNAVAIL",
    3 to "UNSUPPORTED",
    4 to "UNAVAIL_IFACE",
    5 to "INTERNAL_ERROR",
    6 to "TETHER_IFACE_ERROR",
    7 to "UNTETHER_IFACE_ERROR",
    8 to "ENABLE_FORWARDING_ERROR",
    9 to "DISABLE_FORWARDING_ERROR",
    10 to "IFACE_CFG_ERROR",
    11 to "PROVISIONING_FAILED",
    12 to "DHCPSERVER_ERROR",
    13 to "ENTITLEMENT_UNKNOWN",
    14 to "NO_CHANGE_TETHERING_PERMISSION",
    15 to "NO_ACCESS_TETHERING_PERMISSION",
    16 to "UNKNOWN_TYPE",
    17 to "UNKNOWN_REQUEST",
    18 to "DUPLICATE_REQUEST",
    19 to "BLUETOOTH_SERVICE_PENDING",
    20 to "SOFT_AP_CALLBACK_PENDING",
)

private fun describeTetherError(code: Int?): String {
    val name = TETHER_ERROR_NAMES[code] ?: "UNKNOWN"
    return "error=$code TETHER_ERROR_$name"
}

class DownstreamControl(private val context: Context) {

    private val tetheringManager: Any
        get() = context.getSystemService("tethering")
            ?: error("tethering service unavailable")

    val opPackageName: String get() = context.opPackageName

    private val managerClass: Class<*> get() = Class.forName("android.net.TetheringManager")

    private val wifiTetheringType = 0

    fun stopWifiTethering(): Boolean = runCatching {
        val method = managerClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
        method.invoke(tetheringManager, wifiTetheringType)
        Thread.sleep(STOP_SETTLE_MS)
    }.isSuccess

    fun startWifiTethering(
        customSubnet: String = "",
        manualClientIp: String = "",
    ): Pair<Boolean, String> {
        val staticConfig = when {
            manualClientIp.isBlank() -> null
            else -> runCatching { staticIpv4Config(customSubnet, manualClientIp) }
                .getOrElse { return false to "static IPv4: ${it.message}" }
        }

        val viaTethering = startViaTetheringManager(staticConfig)
        if (viaTethering.first) return viaTethering

        if (staticConfig != null) return viaTethering

        val viaWifiManager = startViaWifiManager()
        return when {
            viaWifiManager.first -> viaWifiManager
            else -> false to "tethering[${viaTethering.second}]; wifiManager[${viaWifiManager.second}]"
        }
    }

    private fun startViaWifiManager(): Pair<Boolean, String> = runCatching {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE)
            ?: return false to "wifi service unavailable"

        val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val method = wifiManager.javaClass.getMethod("startTetheredHotspot", configClass)
        val accepted = method.invoke(wifiManager, null) as? Boolean ?: false

        when {
            accepted -> true to "startTetheredHotspot accepted"
            else -> false to "startTetheredHotspot returned false"
        }
    }.getOrElse { failure ->
        false to "startTetheredHotspot: ${failure.cause?.message ?: failure.message}"
    }

    private fun startViaTetheringManager(
        staticConfig: Pair<String, String>?,
    ): Pair<Boolean, String> {
        val exempt = startTetheringRequest(true, staticConfig)
        if (exempt.first) return exempt

        val plain = startTetheringRequest(false, staticConfig)
        return when {
            plain.first -> plain
            else -> false to "exempt[${exempt.second}]; plain[${plain.second}]"
        }
    }

    private fun startTetheringRequest(
        isExemptFromEntitlement: Boolean,
        staticConfig: Pair<String, String>?,
    ): Pair<Boolean, String> {
        val request = runCatching { buildTetheringRequest(isExemptFromEntitlement, staticConfig) }
            .getOrElse { return false to "buildTetheringRequest: ${it.message}" }

        val latch = CountDownLatch(1)
        val result = arrayOf("no callback")

        val callback = createStartCallback(latch, result)
            ?: return false to "could not build StartTetheringCallback proxy"

        return runCatching {
            invokeStartTethering(request, callback)
            val didComplete = latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val didSucceed = didComplete && result[0] == SUCCESS
            didSucceed to result[0]
        }.getOrElse { false to "startTethering: ${it.message}" }
    }

    private fun buildTetheringRequest(
        isExemptFromEntitlement: Boolean,
        staticConfig: Pair<String, String>?,
    ): Any {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(wifiTetheringType)

        staticConfig?.let { (server, client) ->
            val linkAddressClass = Class.forName("android.net.LinkAddress")
            val constructor = linkAddressClass.getConstructor(String::class.java)
            val serverAddress = constructor.newInstance(server)
            val clientAddress = constructor.newInstance(client)
            builderClass
                .getMethod("setStaticIpv4Addresses", linkAddressClass, linkAddressClass)
                .invoke(builder, serverAddress, clientAddress)
        }

        if (isExemptFromEntitlement) {
            runCatching {
                builderClass
                    .getDeclaredMethod("setExemptFromEntitlementCheck", Boolean::class.java)
                    .invoke(builder, true)
            }
        }
        return builderClass.getMethod("build").invoke(builder)!!
    }

    private fun staticIpv4Config(
        customSubnet: String,
        manualClientIp: String,
    ): Pair<String, String> {
        val parts = customSubnet.trim().split("/")
        require(parts.size == 2 && parts[1] == "24") {
            "manual client IP requires a custom /24 subnet"
        }

        val network = parts[0].split(".").map {
            it.toIntOrNull()?.takeIf { octet -> octet in 0..255 }
                ?: error("invalid custom subnet")
        }
        require(network.size == 4 && network[3] == 0) {
            "custom subnet must be a /24 network address"
        }

        val client = manualClientIp.trim().split(".").map {
            it.toIntOrNull()?.takeIf { octet -> octet in 0..255 }
                ?: error("invalid manual client IP")
        }
        require(client.size == 4) { "invalid manual client IP" }
        require(client.take(3) == network.take(3)) {
            "manual client IP must be inside ${parts[0]}/24"
        }
        require(client[3] in 2..254) {
            "manual client IP must use host .2 through .254"
        }

        val server = "${network[0]}.${network[1]}.${network[2]}.1/24"
        val clientCidr = "${client.joinToString(".")}/24"
        return server to clientCidr
    }

    private fun invokeStartTethering(request: Any, callback: Any) {
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val method = managerClass.getMethod(
            "startTethering",
            requestClass,
            Executor::class.java,
            callbackClass,
        )
        method.invoke(tetheringManager, request, mainExecutor(), callback)
    }

    private fun createStartCallback(latch: CountDownLatch, result: Array<String>): Any? =
        runCatching {
            val callbackClass =
                Class.forName("android.net.TetheringManager\$StartTetheringCallback")

            java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                recordCallback(method.name, args, result, latch)
                null
            }
        }.getOrNull()

    private fun recordCallback(
        name: String,
        args: Array<Any?>?,
        result: Array<String>,
        latch: CountDownLatch,
    ) {
        when (name) {
            "onTetheringStarted" -> {
                result[0] = SUCCESS
                latch.countDown()
            }

            "onTetheringFailed" -> {
                val code = args?.firstOrNull() as? Int
                result[0] = "onTetheringFailed(${describeTetherError(code)})"
                latch.countDown()
            }
        }
    }

    private fun mainExecutor(): Executor {
        val handler = Handler(Looper.getMainLooper())
        return Executor { command -> handler.post(command) }
    }

    private companion object {
        const val SUCCESS = "onTetheringStarted"
        const val STOP_SETTLE_MS = 3_000L
        const val START_TIMEOUT_MS = 15_000L
    }
}
