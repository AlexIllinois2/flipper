package com.flipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：任一开关是开着的就在开机完成后把前台服务拉起来。
 * Shizuku 开机后往往需要重新激活，开机接管可能要等看门狗重试——
 * 服务保持期望状态，Exec 一恢复就自动接管。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!FlipService.wanted(context)) return
        runCatching {
            context.startForegroundService(Intent(context, FlipService::class.java))
        }.onFailure { Log.w(TAG, "boot start failed: " + it) }
    }

    private companion object {
        const val TAG = "flipper"
    }
}
