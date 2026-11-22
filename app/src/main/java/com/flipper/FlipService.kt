package com.flipper

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.Executors

/**
 * 旋转核心（终版：**单权威三件套**）。本机（小米/Android 11）实测结论：
 *
 *   - 悬浮窗改向机制不可靠：方向锁死在首次 addView 的值，updateViewLayout / 拆掉重挂 /
 *     重建 View 全都无效（WMS 旋转历史实证）——彻底废弃；
 *   - `wm fixed-to-user-rotation enabled` + `accelerometer_rotation=1`（不锁）：
 *     显示永远等于 user_rotation，传感器与应用请求都只是"声明"，不再驱动显示；
 *     同时应用看到"自动旋转开启"，该发请求的应用（bilibili 全屏 SENSOR_LANDSCAPE）
 *     行为与原生一致，请求经 dumpsys mCurrentAppOrientation 可见（声明值不被掩盖）；
 *   - user_rotation 写 0/1/2/3 任意角，显示精确跟手（多次实验全中）。
 *
 * 于是引擎 = 唯一权威，声明直接读：`dumpsys activity activities` 里栈顶 Activity 的
 * requestedOrientation 不被 fixed=enabled 掩盖（实测），轮询即得，无需释放 fixed。
 * （旧方案是"探测"：fixed 暂还 default 一拍让 mCurrentAppOrientation 显形——探测
 * 窗口内 accel=1 + fixed=default，原生旋转裸奔，完全绕过防抖，表现为每次启动应用
 * 时连续翻转 2-4 轮、间隔远小于 2 秒，已废弃。）
 *   探测读到横族声明（全屏视频等）→ 横轴跟手（90/270）；竖族/UNSPECIFIED → 竖轴（0/180）；
 *   锁屏永远竖轴；应用退出即回竖轴（≤一个轮询周期）。
 * 决策唯一入口 apply()：目标角变了才写一条 user_rotation，无震荡源。
 * 幂等铁律：engage 再次进入且已接管时直接返回，不碰任何系统状态。
 */
class FlipService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 物理姿态（Surface.ROTATION_*），传感器回调更新；-1 = 平放/未知。 */
    @Volatile
    private var tilt = -1

    /** 最近一次已知的物理姿态：平放时轴切换的最近角参考。 */
    @Volatile
    private var lastKnownTilt = Surface.ROTATION_0

    /** 前台应用的方向声明（SCREEN_ORIENTATION_XXX），dumpsys 轮询更新。 */
    @Volatile
    private var appOrientation = ""

    @Volatile
    private var keyguardLocked = false

    /** 已提交的 user_rotation；-1 = 未接管。 */
    @Volatile
    private var target = -1

    /** 防抖窗口内挂起的旋转决定；-1 = 无挂起。窗口期满才提交到 target。 */
    @Volatile
    private var pendingDesired = -1

    /** 本次接管是否已有防抖落笔：false = 启动期（用启动防抖），true = 运行期（用运行防抖）。 */
    @Volatile
    private var committedOnce = false

    /** 当前执行通道是否 root：root 模式下 dumpsys 轮询降频（每次都是独立 su 进程）。 */
    @Volatile
    private var rootMode = false

    /** dumpsys 轮询在途标记：单次耗时可能超过 tick，防单线程 worker 队列积压。 */
    @Volatile
    private var pollInFlight = false

    private var tickCount = 0L
    private var mismatchSince = 0L
    private var tiltSensor: TiltSensor? = null
    private var lastTip = ""

    /** 上次生效的开关组合（"F"/"R"/"FR"）：变化时同步 accel/fixed 底座。 */
    @Volatile
    private var lastModeKey = ""

    /** 栈顶应用（"包/Activity"）：变化 = 应用切换 → 决策。 */
    @Volatile
    private var topActivity = ""

    private val prefs by lazy { getSharedPreferences("flipper", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------ 事件源

    private val onTilt: (Int) -> Unit = { phys ->
        tilt = phys
        lastKnownTilt = phys
        EngineState.tilt = phys
        main.post { apply("tilt=" + phys) }
    }

    /** 周期 tick：开关组合切换时同步底座状态 + 锁屏同步 + 前台方向轮询（仅翻转需要）
     *  + 权威自愈 + 决策。 */
    private val tick = object : Runnable {
        override fun run() {
            if (target >= 0) {
                tickCount++
                val mk = (if (flipOn()) "F" else "") + (if (rotateOn()) "R" else "")
                if (mk != lastModeKey) {
                    lastModeKey = mk
                    if (mk.isNotEmpty()) apply("mode=" + mk)
                }
                val locked = keyguardLocked()
                EngineState.keyguard = locked
                if (locked != keyguardLocked) {
                    keyguardLocked = locked
                    apply("keyguard=" + locked)
                }
                if (flipOn() && !locked && (!rootMode || tickCount % 2 == 0L)) pollOrientation()
                reassertIfOverridden(locked)
                apply("tick")
            }
            main.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /** 接管失败快速重试（权限通道恢复后 3 秒内自动接管，不用等 15 分钟看门狗）。 */
    private val engageRetry = Runnable {
        if (target < 0 && wanted()) worker.execute { engage() }
    }

    /** 系统状态守卫：accelerometer_rotation 恒 1（应用才肯发请求）、
     *  user_rotation 被谁改写都立即写回。先读后写防自触发反馈环。 */
    private val systemStateObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (target < 0) return
            worker.execute {
                if (runGetInt("system", "accelerometer_rotation", 1) != 1) {
                    Log.i(TAG, "accelerometer_rotation drifted -> pin 1")
                    Exec.run(this@FlipService, listOf("settings put system accelerometer_rotation 1"))
                }
                val current = runGetInt("system", "user_rotation", -1)
                if (target >= 0 && current != target) {
                    Log.i(TAG, "user_rotation drifted " + current + " -> reassert " + target)
                    Exec.run(this@FlipService, listOf("settings put system user_rotation " + target))
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> tiltSensor?.unregister()
                Intent.ACTION_SCREEN_ON -> {
                    tiltSensor?.register()
                    apply("screen on")
                }
            }
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        worker.execute { engage() }
        return START_STICKY
    }

    override fun onDestroy() {
        val taken = target >= 0
        stopPieces()
        if (taken) worker.execute { restoreSnapshot() }
        super.onDestroy()
    }

    /** 任务被划掉（ROM 杀后台）：起一个 2 秒后的精确闹钟把前台服务拉回来。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!wanted()) return
        val am = getSystemService(AlarmManager::class.java)
        val trigger = SystemClock.elapsedRealtime() + 2000L
        runCatching {
            if (canExactAlarm(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, alarmIntent())
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, alarmIntent())
            }
        }
        Log.i(TAG, "task removed, watchdog armed")
    }

    // ------------------------------------------------------------ 接管 / 还原

    private fun engage() {
        startForegroundCompat()
        // 幂等铁律：已接管就不碰任何系统状态
        if (target >= 0) {
            Log.i(TAG, "engage: already engaged, skip")
            return
        }
        if (!wanted()) {
            // 看门狗/自启拉起但用户已全关：只负责把可能残留的接管状态清干净
            restoreSnapshot()
            stopSelf()
            return
        }

        // 翻转模式种子归竖轴；旋转模式保留四向种子
        val rawSeed = currentRotation()
        val seed = if (flipOn() && (rawSeed == Surface.ROTATION_90 || rawSeed == Surface.ROTATION_270)) {
            Surface.ROTATION_0
        } else {
            rawSeed
        }
        // 一次调用完成"读快照 → 落笔"：快照值在同一 shell 里先 echo 回传（落笔前的
        // 真实底座），三条接管命令紧随其后。此前是 3 读 + 1 写共 4 次独立权限进程，
        // 接管窗口好几秒——窗口内系统原生旋转照转（防抖还没接管）；合并后亚秒级。
        // 命令顺序：先写种子（= 当前显示角，无视觉跳变）再上 fixed，杜绝中间态。
        val result = Exec.run(
            this,
            listOf(
                "echo A=\$(settings get system accelerometer_rotation);" +
                    "echo U=\$(settings get system user_rotation);" +
                    "echo F=\$(wm fixed-to-user-rotation);" +
                    "settings put system user_rotation " + seed + ";" +
                    "wm fixed-to-user-rotation enabled;" +
                    "settings put system accelerometer_rotation 1",
            ),
        )
        rootMode = result.via == Exec.Mode.ROOT
        EngineState.via = result.via.name.lowercase()
        Log.i(TAG, "engage ok=" + result.ok + " via=" + result.via + " seed=" + seed)

        // 快照是落笔前在同一 shell 内读出的，落笔后持久化（还原用）
        prefs.edit()
            .putInt(KEY_BASE_AUTO, echoInt(result.message, "A"))
            .putInt(KEY_BASE_USER, echoInt(result.message, "U"))
            .putString(KEY_BASE_FIXED, echoToken(result.message, "F"))
            .apply()

        // 一次性校验：三条命令都读回确认（仅 engage 做，不是逐次写校验，无震荡源）
        val verified = result.ok && verifyEngaged(seed)
        if (!verified) {
            EngineState.engaged = false
            EngineState.lastError = if (!result.ok) {
                "写入失败(" + result.via + "): " + result.message.take(100)
            } else {
                "校验失败 accel=" + EngineState.accel + " fixed=" + EngineState.fixed +
                    "（权限通道无效？或与其他旋转工具冲突？）"
            }
            Log.w(TAG, "engage not verified: " + EngineState.lastError)
            notifyTip("等待权限 · 恢复后自动接管")
            armWatchdog()
            scheduleEngageRetry()
            return
        }

        EngineState.lastError = ""
        EngineState.engaged = true
        committedOnce = false
        target = seed
        EngineState.target = seed
        lastModeKey = (if (flipOn()) "F" else "") + (if (rotateOn()) "R" else "")
        startPieces()
        main.post { apply("engage") }
        notifyTip(tip())
    }

    /** 接管校验：一次调用读回三项，确认权威真正建立。 */
    private fun verifyEngaged(seed: Int): Boolean {
        val out = Exec.run(
            this,
            listOf(
                "echo A=\$(settings get system accelerometer_rotation);" +
                    "echo U=\$(settings get system user_rotation);" +
                    "echo F=\$(wm fixed-to-user-rotation)",
            ),
        ).message
        val accel = echoInt(out, "A")
        val user = echoInt(out, "U")
        val fixed = echoToken(out, "F")
        EngineState.accel = accel
        EngineState.fixed = fixed
        return accel == 1 && user == seed && fixed == "enabled"
    }

    /** 从合并 shell 调用的 echo 标记输出取整数值（"null"/缺标记 → -1，与 runGetInt 语义一致）。 */
    private fun echoInt(message: String, tag: String): Int =
        Regex("(?m)^" + tag + "=(\\S+)").find(message)?.groupValues?.get(1)
            ?.takeIf { it != "null" }?.toIntOrNull() ?: -1

    /** 同上，取 token（fixed 状态 default/enabled/disabled）。 */
    private fun echoToken(message: String, tag: String): String =
        Regex("(?m)^" + tag + "=(\\S+)").find(message)?.groupValues?.get(1) ?: "?"

    private fun scheduleEngageRetry() {
        main.removeCallbacks(engageRetry)
        main.postDelayed(engageRetry, ENGAGE_RETRY_MS)
    }

    /** 一次性交还权威：还原进入前的三项系统状态。 */
    private fun restoreSnapshot() {
        stopPieces()
        target = -1
        EngineState.engaged = false
        EngineState.target = -1
        val commands = ArrayList<String>()
        commands.add("wm fixed-to-user-rotation " + (prefs.getString(KEY_BASE_FIXED, "default") ?: "default"))
        val baseUser = prefs.getInt(KEY_BASE_USER, -1)
        if (baseUser >= 0) {
            commands.add("settings put system user_rotation " + baseUser)
        } else {
            commands.add("settings delete system user_rotation")
        }
        val baseAuto = prefs.getInt(KEY_BASE_AUTO, -1)
        if (baseAuto >= 0) commands.add("settings put system accelerometer_rotation " + baseAuto)
        val result = Exec.run(this, commands)
        Log.i(TAG, "restore ok=" + result.ok + " via=" + result.via)
        runCatching { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun startPieces() {
        main.removeCallbacks(engageRetry)
        runCatching {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor("accelerometer_rotation"),
                false,
                systemStateObserver,
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor("user_rotation"),
                false,
                systemStateObserver,
            )
        }
        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        if (tiltSensor == null) tiltSensor = TiltSensor(this, onTilt)
        tiltSensor?.register()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun stopPieces() {
        main.removeCallbacks(engageRetry)
        main.removeCallbacks(debounceWrite)
        pendingDesired = -1
        tiltSensor?.unregister()
        runCatching { contentResolver.unregisterContentObserver(systemStateObserver) }
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacks(tick)
    }

    // ------------------------------------------------------------ 决策（唯一入口）

    /** 唯一决策函数：任何事件（姿态/前台声明/锁屏/开关）最终都汇到这里。
     *  目标角变了才写一条 user_rotation——无震荡源。 */
    private fun apply(reason: String) {
        if (target < 0) return
        val allowed = when {
            rotateOn() && !flipOn() -> AXIS_ALL // 仅旋转：四向跟手
            landscapeWanted() -> AXIS_LANDSCAPE // 前台声明横族：横轴跟手
            else -> AXIS_PORTRAIT // 竖族/UNSPECIFIED/锁屏：竖轴跟手
        }
        writeTarget(pick(allowed, reason), reason)
    }

    /** 落点计算：姿态 ∈ 允许集 ? 姿态 : 当前角 ∈ 允许集 ? 当前角（跨轴握持保持不动）
     *  : 离姿态最近的一角；外加最高原则防御（绝不允许与姿态相反）。 */
    private fun pick(allowed: List<Int>, reason: String): Int {
        var desired = when {
            tilt in allowed -> tilt
            target in allowed -> target
            else -> nearest(allowed)
        }
        if (tilt >= 0 && desired == opposite(tilt)) {
            desired = perpendicular(tilt, allowed) ?: desired
            Log.w(TAG, "opposite display blocked(" + reason + ") -> " + desired)
        }
        return desired
    }

    /** 防抖落笔：窗口内堆积的旋转决定只认最后一次——窗口内来了新决定就重置
     *  计时，窗口静默期满才真正写一条 user_rotation。窗口内决定又回到已提交角，
     *  直接撤销挂起（连写都不用写）。窗口长短分两档：启动期（本次接管还没有过
     *  落笔）用启动防抖，之后运行期用运行防抖；间隔均由主界面配置。 */
    private fun writeTarget(desired: Int, reason: String) {
        if (desired == target) {
            if (pendingDesired >= 0) {
                main.removeCallbacks(debounceWrite)
                pendingDesired = -1
                EngineState.lastEvent = "debounce cancel(" + reason + ")"
            }
            return
        }
        if (desired == pendingDesired) return // 同一决定已挂起，计时器不动（防饿死）
        pendingDesired = desired
        val interval = if (committedOnce) runningDebounceMs() else startupDebounceMs()
        EngineState.lastEvent = reason + " (debounce " + interval + "ms)"
        Log.i(TAG, "apply(" + reason + ") pending -> " + desired)
        main.removeCallbacks(debounceWrite)
        main.postDelayed(debounceWrite, interval)
    }

    /** 防抖期满：执行最后一次决定，此刻才更新已提交角。 */
    private val debounceWrite = Runnable {
        val desired = pendingDesired
        pendingDesired = -1
        if (desired < 0 || desired == target) return@Runnable
        target = desired
        EngineState.target = desired
        committedOnce = true
        Log.i(TAG, "debounced write -> " + desired)
        worker.execute {
            Exec.run(this@FlipService, listOf("settings put system user_rotation " + desired))
        }
        notifyTip(tip())
    }

    /** 前台是否要横屏：应用方向声明是横族 且 当前没锁屏（锁屏永远竖轴）。 */
    private fun landscapeWanted(): Boolean =
        !keyguardLocked && appOrientation.endsWith("LANDSCAPE")

    /** 轴切换落点：允许集里离参考姿态最近的一角（圆周距离，平放用最近已知姿态）；
     *  距离打平时取更"顺手"的一角（0°拿→90，180°拿→270，横拿→0/180）。 */
    private fun nearest(allowed: List<Int>): Int {
        val ref = if (tilt >= 0) tilt else lastKnownTilt
        val pref = if (ref % 2 == 0) (ref + 1) % 4 else (ref + 3) % 4
        return allowed.minWithOrNull(
            compareBy({ dist(ref, it) }, { if (it == pref) 0 else 1 }),
        ) ?: allowed.first()
    }

    private fun dist(a: Int, b: Int): Int {
        val diff = kotlin.math.abs(a - b) / 90
        return minOf(diff, 4 - diff)
    }

    /** 180° 对角。 */
    private fun opposite(angle: Int): Int = (angle + 2) % 4

    /** 与姿态垂直、且在允许集内的角（用于挡下相反角后的兜底落点）。 */
    private fun perpendicular(tiltAngle: Int, allowed: List<Int>): Int? =
        allowed.filter { it != tiltAngle && it != opposite(tiltAngle) }
            .minByOrNull { dist(tiltAngle, it) }

    /** 权威自愈：显示角持续偏离目标 = 有东西压过了我们（fixed 被改/写入丢失），
     *  连续超过阈值就重新压上三条接管命令。宽限期盖住旋转动画窗口，避免误判。 */
    private fun reassertIfOverridden(locked: Boolean) {
        if (locked) {
            mismatchSince = 0L
            return
        }
        val display = currentRotation()
        if (display == target) {
            mismatchSince = 0L
            return
        }
        if (mismatchSince == 0L) {
            mismatchSince = SystemClock.elapsedRealtime()
            return
        }
        if (SystemClock.elapsedRealtime() - mismatchSince < REASSERT_MS) return
        mismatchSince = 0L
        Log.w(TAG, "authority lost: display=" + display + " target=" + target + " -> reassert")
        EngineState.lastEvent = "自愈 reassert(" + display + "->" + target + ")"
        worker.execute {
            Exec.run(
                this@FlipService,
                listOf(
                    "settings put system accelerometer_rotation 1",
                    "wm fixed-to-user-rotation enabled",
                    "settings put system user_rotation " + target,
                ),
            )
        }
    }

    /** 前台方向轮询：直接读栈顶 Activity 自己声明的 requestedOrientation——该字段不被
     *  fixed=enabled 掩盖（实测），无需释放 fixed 探测，显示全程钉死在 user_rotation。
     *  Display #0 从栈顶往下列，dump 里第一个 requestedOrientation 就是栈顶声明。
     *  栈顶应用切换或声明变化 → 决策。 */
    private fun pollOrientation() {
        if (pollInFlight) return
        pollInFlight = true
        worker.execute {
            try {
                val out = Exec.run(
                    this@FlipService,
                    listOf("dumpsys activity activities"),
                ).message
                val top = Regex("topResumedActivity=ActivityRecord\\{\\w+ u0 ([^ }]+)").find(out)
                    ?.groupValues?.getOrNull(1).orEmpty()
                val orient = Regex("requestedOrientation=SCREEN_ORIENTATION_(\\w+)").find(out)
                    ?.groupValues?.getOrNull(1).orEmpty()
                main.post {
                    var changed = false
                    if (top.isNotEmpty() && top != topActivity) {
                        topActivity = top
                        changed = true
                    }
                    if (orient.isNotEmpty() && orient != appOrientation) {
                        appOrientation = orient
                        EngineState.appOrientation = orient
                        changed = true
                    }
                    if (changed) apply("orient=" + appOrientation)
                }
            } finally {
                pollInFlight = false
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun rotateOn(): Boolean = prefs.getBoolean(KEY_ROTATE_ON, false)
    private fun flipOn(): Boolean = prefs.getBoolean(KEY_FLIP_ON, false)
    private fun wanted(): Boolean = rotateOn() || flipOn()

    /** 防抖间隔（主界面可配，改完即生效，下次落笔取新值）。 */
    private fun startupDebounceMs(): Long =
        prefs.getLong(KEY_DEBOUNCE_STARTUP, DEFAULT_DEBOUNCE_STARTUP_MS)

    private fun runningDebounceMs(): Long =
        prefs.getLong(KEY_DEBOUNCE_RUNNING, DEFAULT_DEBOUNCE_RUNNING_MS)

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    private fun currentRotation(): Int = runCatching {
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun runGetInt(namespace: String, key: String, fallback: Int): Int {
        val output = Exec.run(this, listOf("settings get " + namespace + " " + key)).message
        val value = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("exit=") && !it.startsWith("settings ") }
            .lastOrNull() ?: return fallback
        return value.toIntOrNull() ?: fallback
    }

    private fun tip(): String = when {
        flipOn() -> if (landscapeWanted()) "横屏翻转中 · 90°/270°" else "竖屏翻转中 · 0°/180°"
        else -> "四向旋转 · 强制接管中"
    }

    // ------------------------------------------------------------ 保活

    /** 看门狗：精确闹钟（有权限时）每 15 分钟把服务 onStartCommand 一遍——engage 幂等，
     *  等于周期性自检"还在接管吗 / 权限恢复了吗"。还原时撤掉。 */
    private fun armWatchdog() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = alarmIntent()
        val trigger = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
        runCatching {
            if (canExactAlarm(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, WATCHDOG_INTERVAL_MS, pi)
            }
        }
    }

    private fun canExactAlarm(am: AlarmManager): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    private fun alarmIntent(): PendingIntent = PendingIntent.getForegroundService(
        this,
        1001,
        Intent(this, FlipService::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------ 通知

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "翻转运行", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, buildNotification("flipper 运行中"))
    }

    private fun notifyTip(text: String) {
        if (text == lastTip) return
        lastTip = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("flipper")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "flipper"
        private const val CHANNEL_ID = "flip"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 300L
        private const val ENGAGE_RETRY_MS = 3000L
        private const val REASSERT_MS = 1500L
        private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000

        private val AXIS_PORTRAIT = listOf(Surface.ROTATION_0, Surface.ROTATION_180)
        private val AXIS_LANDSCAPE = listOf(Surface.ROTATION_90, Surface.ROTATION_270)
        private val AXIS_ALL = listOf(
            Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270,
        )

        const val KEY_FLIP_ON = "flip_on"
        const val KEY_ROTATE_ON = "rotate_on"
        const val KEY_BASE_AUTO = "base_auto"
        const val KEY_BASE_USER = "base_user"
        const val KEY_BASE_FIXED = "base_fixed"
        const val KEY_DEBOUNCE_STARTUP = "debounce_startup_ms"
        const val KEY_DEBOUNCE_RUNNING = "debounce_running_ms"
        const val DEFAULT_DEBOUNCE_STARTUP_MS = 2000L
        const val DEFAULT_DEBOUNCE_RUNNING_MS = 500L

        fun wanted(context: Context): Boolean {
            val prefs = context.getSharedPreferences("flipper", Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FLIP_ON, false) || prefs.getBoolean(KEY_ROTATE_ON, false)
        }
    }
}

/** 引擎诊断快照（进程内）：主界面轮询显示，出问题时一眼定位卡在哪一环。 */
object EngineState {
    @Volatile var engaged: Boolean = false
    @Volatile var via: String = "-"
    @Volatile var fixed: String = "-"
    @Volatile var accel: Int = -1
    @Volatile var target: Int = -1
    @Volatile var tilt: Int = -1
    @Volatile var appOrientation: String = ""
    @Volatile var keyguard: Boolean = false
    @Volatile var lastEvent: String = ""
    @Volatile var lastError: String = ""
}
