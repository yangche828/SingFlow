package com.singflow.android

import android.app.Application
import android.util.Log
import com.singflow.android.core.probe.LatencyHistoryStore
import com.singflow.android.core.subscription.SubscriptionWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SingFlow 应用入口。
 *
 * 职责：
 * 1. 初始化应用级工作目录（供 libbox 使用）；
 * 2. 注册全局未捕获异常日志，写入 `files/crash/singflow_crash.log`。
 */
@HiltAndroidApp
class SingFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        prepareWorkingDirectories()
        installRuleSets()
        installCrashHandler()
        LatencyHistoryStore.attach(File(filesDir, "singflow"))
        SubscriptionWorkScheduler.schedule(this)
    }

    /**
     * 把内置规则集释放到 files 目录。
     *
     * 远程 `.srs` 在启动阶段同步下载，国内网络访问 GitHub 会失败并导致内核无法启动，
     * 因此把 geosite/geoip 随 APK 分发，配置里直接引用本地路径。
     */
    private fun installRuleSets() {
        val target = ruleSetDir
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "无法创建规则集目录: ${target.absolutePath}")
            return
        }
        val marker = File(target, ".version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (marker.exists() && runCatching { marker.readText().trim() }.getOrDefault("") == version) {
            return
        }
        val names = runCatching {
            assets.list(RULESET_ASSET_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        names.forEach { name ->
            runCatching {
                assets.open("$RULESET_ASSET_DIR/$name").use { input ->
                    File(target, name).outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { error ->
                Log.w(TAG, "释放规则集失败: $name", error)
            }
        }
        runCatching { marker.writeText(version) }
    }

    private fun prepareWorkingDirectories() {
        val base = File(filesDir, "singflow")
        listOf(base, File(base, "work"), File(base, "temp")).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "无法创建工作目录: ${dir.absolutePath}")
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val crashDir = File(filesDir, "crash")
                if (!crashDir.exists()) crashDir.mkdirs()
                val file = File(crashDir, "singflow_crash.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val stack = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                file.appendText("==== $timestamp thread=${thread.name} ====\n$stack\n")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "SingFlowApp"
        private const val RULESET_ASSET_DIR = "ruleset"

        /** 全局 Application 实例（仅用于无 Context 注入场景，如 TileService 冷启动）。 */
        lateinit var instance: SingFlowApp
            private set

        /** libbox 基础目录。 */
        val baseDir: File get() = File(instance.filesDir, "singflow")

        /** libbox 工作目录。 */
        val workingDir: File get() = File(baseDir, "work")

        /** libbox 临时目录。 */
        val tempDir: File get() = File(baseDir, "temp")

        /** 内置规则集释放目录（geosite-cn.srs / geoip-cn.srs）。 */
        val ruleSetDir: File get() = File(baseDir, "ruleset")
    }
}
