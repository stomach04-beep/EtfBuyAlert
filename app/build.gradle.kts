plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.etfbuyalert"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.etfbuyalert"
        minSdk = 31
        targetSdk = 36
        // 2026-07-29: グループ表示→月次ライン→判定見直し→まとめ通知→週足RSI→MA200端末内計算
        // →4タブ化→★ブックマーク と8回の機能追加を経て 1.0 のままだったので実態へ。
        versionCode = 25
        // 2026-08-16: 価格チェックの遅延（最大13時間の空白）を修正
        // ＝アラーム自己連鎖へ移行・ネットワーク制約の撤去・電池最適化除外の導線を設定画面へ
        // 2026-08-20 v1.22: 履歴の頑健化（分配金調整済みを優先・偽の値飛びを除去）。
        // 売り時判定とNotionへ書き戻すMA200線の土台なので汚染がPC側へ伝播していた
        // 2026-08-29 v1.23: 値飛び除去を「単発スパイクのみ」へ修正（本物の水準シフト以降の
        // 履歴が黙って打ち切られていた）／ライン書き戻し失敗の分離リトライ／朝サマリの要約化
        // （5,120字上限で先頭しか読めなかった）／監視タブに検索／設定の健全性チェック／
        // 通知タップで銘柄へ直行／閉場中スキップ／ホーム画面ウィジェット（2x2）
        // 2026-09-25 v1.24: 読めない保存ファイルを日時付きの別名へ退避（固定名.bakの上書きで元データが消えるのを防ぐ）
        // 2026-09-25 v1.25: 沈黙警告は通知を出せたときだけ「警告済み」と履歴に記録
        versionName = "1.25"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // OkHttp (network)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson (JSON parsing + data persistence)
    implementation("com.google.code.gson:gson:2.10.1")

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

