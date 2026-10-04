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
import java.util.Locale

/**
 * libbox 运行时：负责 Libbox.setup 初始化与 CommandServer 生命周期。
 */
object LibboxRuntime {

    @Volatile
    private var initialized = false

    @Synchronized
    fun setup(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val baseDir = File(appContext.filesDir, "sing-box").apply { mkdirs() }
        val workingDir = File(baseDir, "runtime").apply { mkdirs() }
        val tempDir = File(appContext.cacheDir, "sing-box").apply { mkdirs() }
        val debuggable =
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
            },
        )
        initialized = true
        Log.i(TAG, "libbox initialized")
    }

    fun configFile(context: Context): File =
        File(context.filesDir, "sing-box/config.json")

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

    override fun serviceStop() = onStopRequested()

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
