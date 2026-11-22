package com.flipper

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 单主界面，两个开关：
 *  - 翻转：全局竖轴 0°/180° 自动，前台应用要横屏时切 90°/270° 轴（两开关同开时翻转优先）
 *  - 旋转：代替系统旋转开关，强制四向 0°/90°/180°/270°
 * 开关状态即用户意图（落 pref），服务不在也记着；打开界面自动把服务拉起来（幂等）。
 */
class MainActivity : ComponentActivity() {

    private var flipOn by mutableStateOf(false)
    private var rotateOn by mutableStateOf(false)
    private var startupDebounce by mutableStateOf(
        FlipService.DEFAULT_DEBOUNCE_STARTUP_MS.toString(),
    )
    private var runningDebounce by mutableStateOf(
        FlipService.DEFAULT_DEBOUNCE_RUNNING_MS.toString(),
    )
    private var statusText by mutableStateOf("检查权限通道…")
    private var diagText by mutableStateOf("")

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            statusText = "等待 Shizuku 授权…"
            Exec.requestShizukuPermission()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatus()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == Exec.REQUEST_CODE) {
                refreshStatus()
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED && wanted()) {
                    startFlip()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        loadState()
        if (FlipService.wanted(this)) {
            // 进程被杀/覆盖安装后 pref 仍是"开"但服务没跑：打开界面即拉起（幂等）
            startFlip()
        }
        setContent {
            // 自动跟随系统深色模式，无手动开关
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun loadState() {
        val prefs = getSharedPreferences("flipper", MODE_PRIVATE)
        flipOn = prefs.getBoolean(FlipService.KEY_FLIP_ON, false)
        rotateOn = prefs.getBoolean(FlipService.KEY_ROTATE_ON, false)
        startupDebounce = prefs
            .getLong(FlipService.KEY_DEBOUNCE_STARTUP, FlipService.DEFAULT_DEBOUNCE_STARTUP_MS)
            .toString()
        runningDebounce = prefs
            .getLong(FlipService.KEY_DEBOUNCE_RUNNING, FlipService.DEFAULT_DEBOUNCE_RUNNING_MS)
            .toString()
    }

    /** 防抖输入：合法（0~10000ms）即落 pref，服务下次落笔自动取新值；非法只改文本。 */
    private fun onDebounce(key: String, text: String) {
        text.toLongOrNull()?.takeIf { it in 0..10000 }?.let { ms ->
            prefs().edit().putLong(key, ms).apply()
        }
    }

    private fun wanted(): Boolean = flipOn || rotateOn

    private fun refreshStatus() {
        lifecycleScope.launch(Dispatchers.Default) {
            // probeRoot 有磁盘/进程探测，放后台；结果写回 UI 线程
            val label = Exec.statusLabel(this@MainActivity)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                statusText = if (wanted()) "$label · 运行中" else label
            }
        }
    }

    private fun onFlip(on: Boolean) {
        flipOn = on
        prefs().edit().putBoolean(FlipService.KEY_FLIP_ON, on).apply()
        syncService()
    }

    private fun onRotate(on: Boolean) {
        rotateOn = on
        prefs().edit().putBoolean(FlipService.KEY_ROTATE_ON, on).apply()
        syncService()
    }

    private fun prefs() = getSharedPreferences("flipper", MODE_PRIVATE)

    /** 开关变化后的唯一同步点：想要就拉服务（服务内部幂等/等待权限），都关就停服还原。 */
    private fun syncService() {
        if (wanted()) {
            when (Exec.shizukuStatus(this)) {
                Exec.ShellStatus.NO_PERMISSION -> Exec.requestShizukuPermission()
                Exec.ShellStatus.NOT_INSTALLED, Exec.ShellStatus.NOT_RUNNING ->
                    lifecycleScope.launch(Dispatchers.Default) { Exec.probeRoot(this@MainActivity) }
                Exec.ShellStatus.READY -> Unit
            }
            startFlip()
        } else {
            stopService(Intent(this, FlipService::class.java))
        }
        refreshStatus()
    }

    private fun startFlip() {
        lifecycleScope.launch(Dispatchers.Default) { Exec.warmup(this@MainActivity) }
        val intent = Intent(this, FlipService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + packageName)),
            )
        }
    }

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            val am = getSystemService(android.app.AlarmManager::class.java)
            if (am?.canScheduleExactAlarms() != true) {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                )
            }
        }
    }

    @Composable
    private fun Screen() {
        LaunchedEffect(Unit) {
            while (isActive) {
                diagText = diagLine()
                delay(1000)
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ToggleRow("翻转", "自动翻转", flipOn) { onFlip(it) }
                Spacer(Modifier.height(20.dp))
                ToggleRow("旋转", "自动旋转", rotateOn) { onRotate(it) }
                Spacer(Modifier.height(20.dp))
                DebounceField("启动防抖 (ms)", "服务接管后首次翻转前生效", startupDebounce) {
                    startupDebounce = it
                    onDebounce(FlipService.KEY_DEBOUNCE_STARTUP, it)
                }
                Spacer(Modifier.height(12.dp))
                DebounceField("运行防抖 (ms)", "首次翻转后生效", runningDebounce) {
                    runningDebounce = it
                    onDebounce(FlipService.KEY_DEBOUNCE_RUNNING, it)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { requestIgnoreBatteryOptimizations() }) {
                        Text("加入电池白名单", fontSize = 13.sp)
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        TextButton(onClick = { requestExactAlarm() }) {
                            Text("允许闹钟精度", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    /** 引擎实时状态：接管/通道/fixed/accel/target/姿态/前台请求/最近事件。 */
    private fun diagLine(): String {
        val s = EngineState
        return if (s.engaged) {
            "通道=" + s.via + "  fixed=" + s.fixed + "  accel=" + s.accel + "\n" +
                "target=" + s.target + "  姿态=" + s.tilt + "  锁屏=" + s.keyguard + "\n" +
                "前台请求=" + s.appOrientation.ifEmpty { "?" } + "  事件=" + s.lastEvent +
                (if (s.lastError.isEmpty()) "" else "\n异常=" + s.lastError)
        } else if (FlipService.wanted(this)) {
            "等待接管… " + s.lastError
        } else {
            "未接管（开关全关）"
        }
    }

    /** 防抖间隔输入框：数字键盘，单行，随输入持久化。 */
    @Composable
    private fun DebounceField(label: String, desc: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(label, fontSize = 13.sp) },
            supportingText = { Text(desc, fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp)
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.size(width = 64.dp, height = 36.dp),
            )
        }
    }

    private companion object {
        const val TAG = "flipper"
    }
}
