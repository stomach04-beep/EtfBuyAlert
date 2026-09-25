package com.example.etfbuyalert.data.repository

import android.content.Context
import android.util.Log
import com.example.etfbuyalert.data.model.AppData
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 更新の種類（アラームから渡される）
enum class UpdateType {
    PRICE_CHECK,     // 定期の価格チェック＋ライン到達アラート
    MORNING_SUMMARY  // 毎朝の値サマリ通知
}

// JSON永続化ストレージ — アプリの全データをJSONファイル1枚に保存・読込。
// 一時ファイル→rename のアトミック保存と、読込失敗時の破損ファイル退避で
// 「一瞬読めなかっただけで全データ消失」を防ぐ（BargainCheckerの教訓を継承）。
class JsonStorage(private val context: Context) {
    private val gson = Gson()
    private val file: File get() = File(context.filesDir, "etf_data.json")

    companion object {
        // プロセス内の全インスタンス（ViewModel・Worker）で共有するファイルロック。
        // ロックなしだと書き込み途中の読み取りや同時書き込みでファイルが壊れる。
        private val fileLock = Any()
    }

    fun load(): AppData = synchronized(fileLock) {
        try {
            if (file.exists()) {
                gson.fromJson(file.readText(), AppData::class.java)
                    ?: throw IllegalStateException("JSONのパース結果がnull")
            } else {
                AppData()  // 初回起動：空データ（保存はしない＝最初の同期で作る）
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 【重要】ここで初期データを保存してはいけない。一時的に読めなかっただけで
            // 全監視状態・通知済みフラグが消えるのを防ぐ。壊れたファイルは日時付きの別名へ退避し、
            // メモリ上の空データだけ返す（次の正常なsaveまでディスクに触らない）。
            backupCorruptedFile()
            AppData()
        }
    }

    fun save(data: AppData) = synchronized(fileLock) {
        try {
            val tmp = File(context.filesDir, "etf_data.json.tmp")
            tmp.writeText(gson.toJson(data))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(data))
                tmp.delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 壊れたファイルを etf_data.json.corrupt-yyyyMMddHHmmss に退避する（LESSON-72）。
    // 旧実装は固定名 .bak を毎回 delete してから改名していたため、2回目の破損で
    // 1回目に退避した本物のデータ（通知済みフラグ・更新ログ）が消えていた。日時付きで上書きしない
    private fun backupCorruptedFile() {
        try {
            if (file.exists()) {
                val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
                val dst = File(context.filesDir, "${file.name}.corrupt-$stamp")
                // 改名できない端末ではコピーで残す（次の save が本番を上書きしても元データは残る）
                if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
                Log.w("JsonStorage", "保存ファイルが読めないため ${dst.name} へ退避しました")
            }
        } catch (e: Exception) {
            Log.w("JsonStorage", "壊れた保存ファイルの退避に失敗しました", e)
        }
    }
}
