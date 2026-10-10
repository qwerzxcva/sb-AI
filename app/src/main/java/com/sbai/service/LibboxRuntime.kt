package com.sbai.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.security.SecureRandom
import java.util.Locale

private const val COMMAND_SERVER_CONFIG_FILE = "sb_command_server.json"

/**
 * libbox 运行时：负责 Libbox.setup 初始化与 CommandServer 生命周期。
 *
 * :core 进程启动时（LibboxServiceRuntime.start）在 SetupOptions 中设置固定端口
 * `COMMAND_SERVER_PORT`，并将 port+secret 写入 app 私有文件（同 app UID 下双进程可读）。
 * 主进程通过 readCommandServerConfig 读取配置，再调 newRemoteCommandClient 跨进程
 * 连接 :core 内核，从而让 HomeScreen 节点切换 / 测速 / VpnControlReceiver.switchNode
 * 真正生效（之前因连的是空内核，全是 no-op）。
 */
object LibboxRuntime {

    /** :core 进程 CommandServer 监听端口；主进程通过 127.0.0.1:<port> 跨进程连接。 */
    const val COMMAND_SERVER_PORT = 19307

    @Volatile
    private var initialized = false

    @Synchronized
    fun setup(context: Context, exposeCommandServerPort: Int = 0) {
        if (initialized) return
        val appContext = context.applicationContext
        val baseDir = File(appContext.filesDir, "sing-box").apply { mkdirs() }
        val workingDir = File(baseDir, "runtime").apply { mkdirs() }
        val tempDir = File(appContext.cacheDir, "sing-box").apply { mkdirs() }
        val debuggable =
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        val portToExpose = if (exposeCommandServerPort > 0) exposeCommandServerPort else 0
        // 通过文件持久化 port+secret：同 app UID 的 /data/data/<pkg>/files/sb_command_server.json
        // :core 写，主进程读。文件每次 read 都从磁盘读，无 SharedPreferences 跨进程陈旧问题。
        var secret: String? = null
        if (portToExpose > 0) {
            secret = generateSecret(context)
            writeCommandServerConfig(context, portToExpose, secret)
        }

        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        Libbox.setup(
            SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = debuggable || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                logMaxLines = 3_000
                debug = debuggable
                crashReportSource = "sb-AI"
                if (portToExpose > 0 && secret != null) {
                    setCommandServerListenPort(portToExpose)
                    setCommandServerSecret(secret)
                }
            },
        )
        initialized = true
        Log.i(TAG, "libbox initialized (commandServerPort=$portToExpose)")
    }

    /** 返回当前安装中 CommandServer 配置的 (port, secret)，无则为 null。每次调用实时读磁盘。 */
    fun readCommandServerConfig(context: Context): Pair<Int, String>? {
        val file = File(context.filesDir, COMMAND_SERVER_CONFIG_FILE)
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            if (lines.size < 2) return null
            val port = lines[0].toIntOrNull() ?: return null
            if (port <= 0) return null
            port to lines[1]
        } catch (e: Exception) {
            Log.w(TAG, "readCommandServerConfig failed", e)
            null
        }
    }

    fun configFile(context: Context): File =
        File(context.filesDir, "sing-box/config.json")

    private fun writeCommandServerConfig(
        context: Context,
        port: Int,
        secret: String,
    ) {
        val file = File(context.filesDir, COMMAND_SERVER_CONFIG_FILE)
        runCatching {
            file.writeText("$port\n$secret\n")
            Log.d(TAG, "wrote command server config: $port")
        }.onFailure { Log.w(TAG, "writeCommandServerConfig failed", it) }
    }

    private fun generateSecret(context: Context): String {
        val existing = readSecret(context)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun readSecret(context: Context): String? =
        readCommandServerConfig(context)?.second

    private const val TAG = "LibboxRuntime"
}

/**
 * CommandServer 生命周期管理。
 */
class LibboxServiceRuntime(
    private val platformInterface: SbPlatformInterface,
    private val onStopRequested: () -> Unit,
) : CommandServerHandler {

    private var commandServer: CommandServer? = null

    @Synchronized
    fun start(configContent: String) {
        check(commandServer == null) { "libbox service is already running" }
        require(configContent.isNotBlank()) { "sing-box configuration is empty" }

        val server = CommandServer(this, platformInterface)
        runCatching {
            server.start()
            server.startOrReloadService(configContent, OverrideOptions().apply {
                // 全局模式：默认排除自身
                excludePackage = listOf(platformInterface.selfPackageName).toStringIterator()
            })
        }.onFailure {
            runCatching { server.closeService() }
            runCatching { server.close() }
            platformInterface.closeTun()
            throw it
        }
        commandServer = server
        Log.i(TAG, "libbox service started")
    }

    @Synchronized
    fun stop() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        platformInterface.closeTun()
    }

    override fun serviceStop() {
        android.util.Log.w("LibboxServiceRuntime", "serviceStop: kernel requested stop")
        onStopRequested()
    }

    override fun serviceReload() = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun connectSSHAgent(): Int = -1

    override fun triggerNativeCrash() {
        error("Native crash requested by sing-box")
    }

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }

    private companion object {
        const val TAG = "LibboxServiceRuntime"
    }
}
