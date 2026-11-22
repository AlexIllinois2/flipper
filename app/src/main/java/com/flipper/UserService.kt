package com.flipper

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务：实例运行在 Shizuku 拉起的独立进程里，Linux 身份是 shell(2000)，
 * 执行的命令与 `adb shell` 等价。
 */
class UserService : IUserService.Stub {

    @Suppress("unused")
    constructor(context: Context) : super()

    @Suppress("unused")
    constructor() : super()

    override fun exit() {
        System.exit(0)
    }

    override fun runCommand(command: String): String {
        if (command != "dumpsys window") Log.i(TAG, "run: " + command)
        return try {
            val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            builder.redirectErrorStream(true)
            builder.environment()["PATH"] = "/system/bin:/system/xbin"
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else -1
            if (!finished) runCatching { process.destroyForcibly() }
            "exit=" + code + "\n" + output.trim()
        } catch (error: Throwable) {
            Log.w(TAG, "command failed: " + error)
            "exit=-1\n" + error
        }
    }

    private companion object {
        const val TAG = "flipper"
    }
}
