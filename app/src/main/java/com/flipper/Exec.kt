package com.flipper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 命令执行层：Shizuku 优先（shell 身份，免 root），Shizuku 不可用或执行失败时回退 Root。
 * 两条通道执行的命令完全一致，上层不感知通道差异。
 */
object Exec {

    private const val TAG = "flipper"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val BIND_TIMEOUT_SECONDS = 30L
    const val REQUEST_CODE = 5611

    enum class Mode { NONE, ROOT, SHIZUKU }

    data class Result(val ok: Boolean, val message: String, val via: Mode)

    // ------------------------------------------------------------ 状态

    fun shizukuStatus(context: Context): ShellStatus {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!installed) return ShellStatus.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive || runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return ShellStatus.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        return if (granted == PackageManager.PERMISSION_GRANTED) ShellStatus.READY else ShellStatus.NO_PERMISSION
    }

    enum class ShellStatus { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    @Volatile
    private var rootAvailable: Boolean? = null

    /** 探测 root：跑一次 `su -c id`，输出含 uid=0 即可用（结果缓存）。 */
    fun probeRoot(context: Context): Boolean {
        rootAvailable?.let { return it }
        val ok = suBinary()?.let { bin ->
            runCatching {
                val process = ProcessBuilder(bin, "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(5, TimeUnit.SECONDS)
                process.destroyForcibly()
                output.contains("uid=0")
            }.getOrDefault(false)
        } ?: false
        Log.i(TAG, "root probe=" + ok)
        rootAvailable = ok
        return ok
    }

    /** 当前可用的最高权限通道：Shizuku 优先，其次 Root。 */
    fun mode(context: Context): Mode = when (shizukuStatus(context)) {
        ShellStatus.READY -> Mode.SHIZUKU
        else -> if (rootAvailable == true || probeRoot(context)) Mode.ROOT else Mode.NONE
    }

    fun requestShizukuPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed: " + it) }
    }

    fun openShizuku(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun statusLabel(context: Context): String = when (mode(context)) {
        Mode.SHIZUKU -> "Shizuku 已就绪（shell 身份）"
        Mode.ROOT -> "Root 已就绪（Shizuku 不可用，已回退）"
        Mode.NONE -> when (shizukuStatus(context)) {
            ShellStatus.NOT_INSTALLED -> "未检测到可用权限：安装并启动 Shizuku，或授予 Root"
            ShellStatus.NOT_RUNNING -> "Shizuku 未运行（先启动一次），也未检测到 Root"
            ShellStatus.NO_PERMISSION -> "等待 Shizuku 授权"
            ShellStatus.READY -> "Shizuku 已就绪"
        }
    }

    // ------------------------------------------------------------ 执行

    /** 以当前最高权限通道执行一批命令（阻塞，调用方放后台线程）。 */
    fun run(context: Context, commands: List<String>): Result {
        val via = mode(context)
        if (via == Mode.SHIZUKU) {
            val result = runShizuku(context, commands)
            if (result != null) return Result(result.first, result.second, Mode.SHIZUKU)
            // Shizuku 半路断开：当场回退 Root，不让一次写入悬空
            Log.w(TAG, "shizuku run failed mid-way, fallback root")
        }
        if (via == Mode.ROOT || probeRoot(context)) return runRoot(commands)
        return Result(false, "no executor available", Mode.NONE)
    }

    private fun runShizuku(context: Context, commands: List<String>): Pair<Boolean, String>? {
        var service = ensureService(context)
        if (service == null) {
            cachedConnection = null
            service = ensureService(context)
        }
        if (service == null) return null
        val journal = StringBuilder()
        var allOk = true
        var needRebind = false
        commands.forEach { command ->
            val output = runCatching { service.runCommand(command) }
                .getOrElse {
                    needRebind = true
                    "exit=-1\n" + it
                }
            val code = Regex("exit=(-?\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (code != 0) allOk = false
            journal.append(command).append('\n').append(output.trim()).append('\n')
        }
        if (needRebind) cachedConnection = null
        return if (needRebind && !allOk) null else allOk to journal.toString().trim()
    }

    private fun runRoot(commands: List<String>): Result {
        val bin = suBinary() ?: return Result(false, "no su binary", Mode.ROOT)
        val script = commands.joinToString("; ")
        return try {
            val process = ProcessBuilder(bin, "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val code = if (finished) process.exitValue() else -1
            Result(code == 0, commands.joinToString("\n") + "\nexit=" + code + "\n" + output.trim(), Mode.ROOT)
        } catch (error: Throwable) {
            rootAvailable = false
            Result(false, commands.joinToString("\n") + "\n" + error, Mode.ROOT)
        }
    }

    @Volatile
    private var suPath: String? = null

    private fun suBinary(): String? {
        suPath?.let { return it }
        for (candidate in listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/magisk/.core/bin/su")) {
            val works = runCatching {
                val process = ProcessBuilder(candidate, "-c", "true")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
                process.destroyForcibly()
                process.exitValue() == 0
            }.getOrDefault(false)
            if (works) {
                suPath = candidate
                return candidate
            }
        }
        return null
    }

    // ------------------------------------------------------------ Shizuku UserService

    fun warmup(context: Context) {
        runCatching { ensureService(context) }
            .onFailure { Log.w(TAG, "warmup failed: " + it) }
    }

    @Volatile
    private var cachedConnection: Connection? = null

    private fun ensureService(context: Context): IUserService? {
        cachedConnection?.service?.let { return it }
        val connection = Connection()
        val component = ComponentName(context.packageName, UserService::class.java.name)
        val args = Shizuku.UserServiceArgs(component)
            // 守护模式：shell 进程跨应用重启存活，冷启动免去 2~3 秒的 app_process 拉起——
            // 这段拉起时间正是"打开开关后原生旋转裸奔"窗口的大头。
            .daemon(true)
            .processNameSuffix("shell")
            .debuggable(false)
            // UserService 代码变更时递增，逼 Shizuku 杀掉旧代码的守护进程换新的
            .version(3)
        return try {
            Shizuku.bindUserService(args, connection)
            val bound = connection.latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.i(TAG, "bindUserService bound=" + bound)
            val service = connection.service
            if (bound && service != null) cachedConnection = connection
            service
        } catch (error: Throwable) {
            Log.w(TAG, "bindUserService failed: " + error)
            null
        }
    }

    private class Connection : ServiceConnection {
        val latch = CountDownLatch(1)

        @Volatile
        var service: IUserService? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IUserService.Stub.asInterface(binder) else null
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }
}
