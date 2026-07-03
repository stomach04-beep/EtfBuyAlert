package com.example.etfbuyalert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.etfbuyalert.ui.navigation.AppNavigation
import com.example.etfbuyalert.ui.theme.EtfBuyAlertTheme
import com.example.etfbuyalert.ui.theme.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// メインアクティビティ — アプリの起動点
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // onResumeでバックグラウンド更新されたデータを読み直すために保持
    private var viewModel: MainViewModel? = null

    override fun onResume() {
        super.onResume()
        // バックグラウンドWorkerがJSONを書き換えているため、
        // フォアグラウンド復帰のたびにディスクから読み直す
        viewModel?.reloadFromDisk()
        // 取りこぼし自動リカバリ：予定時刻を過ぎたが未成功のスロットを即時再実行。
        // JSONのload（ファイル読み＋Gsonパース）を含むためIOスレッドへ逃がす
        //（旧実装はメインスレッドで毎onResume実行しておりジャンクの原因だった）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                com.example.etfbuyalert.alarm.CatchUpHelper.runCatchUp(this@MainActivity)
            } catch (_: Exception) {}
        }
        // 電池最適化の許可ダイアログから戻ってきたタイミングで結果を判定する
        //（旧実装はonCreate内でダイアログ表示直後に即判定していたため、
        //  ユーザーが応答する前に拒否扱いになり得た）
        val prefs = getSharedPreferences("etf_settings", 0)
        if (prefs.getBoolean("battery_opt_asked", false)) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                prefs.edit().putBoolean("battery_opt_denied", true).apply()
            }
            prefs.edit().remove("battery_opt_asked").apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知権限の確認（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 電池の最適化から除外されていなければダイアログで案内
        //（応答結果の判定はダイアログから戻った後のonResumeで行う）
        requestBatteryOptimizationExemption()

        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        this.viewModel = viewModel

        // バックグラウンドWorkerの完了を監視し、アプリ表示中でも画面へ自動反映する。
        // WorkerはViewModelと別のRepositoryインスタンスでJSONを書くため、これがないと
        // 16:00定刻更新などがウィジェットだけ反映され、開きっぱなしの画面は古いままになる
        val workManager = androidx.work.WorkManager.getInstance(this)
        for (workName in listOf("etf_price_check", "update_MORNING_SUMMARY")) {
            workManager.getWorkInfosForUniqueWorkLiveData(workName).observe(this) { infos ->
                if (infos?.any { it.state == androidx.work.WorkInfo.State.SUCCEEDED } == true) {
                    this.viewModel?.reloadFromDisk()
                }
            }
        }

        setContent {
            val ctx = LocalContext.current
            val themePrefs = remember { ThemePrefs.prefs(ctx) }
            var themeMode by remember { mutableStateOf(ThemePrefs.get(ctx)) }
            var paletteId by remember { mutableStateOf(ThemePrefs.getPalette(ctx)) }
            DisposableEffect(Unit) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        ThemePrefs.KEY -> themeMode = ThemePrefs.get(ctx)
                        ThemePrefs.KEY_PALETTE -> paletteId = ThemePrefs.getPalette(ctx)
                    }
                }
                themePrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { themePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            EtfBuyAlertTheme(themeMode = themeMode, paletteId = paletteId) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }

    // 電池の最適化を無視するリクエスト（1回だけ表示、拒否後は再表示しない）
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return  // 既に除外済み

        val prefs = getSharedPreferences("etf_settings", 0)
        if (prefs.getBoolean("battery_opt_denied", false)) return  // 拒否済み

        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS で直接許可ダイアログを表示
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
            // 次回起動時に再チェック: もし除外されていなければ拒否扱いとする
            prefs.edit().putBoolean("battery_opt_asked", true).apply()
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            } catch (_: Exception) { }
        }
    }
}
