# Android版の解像度交渉

このフォークでは、Androidの `VideoCapabilities` が返す対応範囲だけを理由に、希望解像度を事前に縮小しない。最初は画面サイズ・向き・Resolution設定から算出したサイズで配信を要求し、実際のデコード結果から次のサイズを決める。

機種名・メーカー名・SoC名による新しい特例はない。申告値の縦横を無条件に交換して「対応済み」と見なす処理もない。元アプリにある低遅延向けのvendor設定は維持している。

## 交渉と復旧

1. 希望サイズを偶数・デコーダーのアライメントに揃え、最大辺4096・最大4096²画素以内に収めて要求する。16ビットのプロトコル寸法と入力バッファの計算に、無制限な値を渡さない。
2. ホストが返した実解像度とコーデック、stream IDで試行を管理する。ホストが上限やミラー表示のためにサイズを変えた場合も、その実サイズを使う。
3. `configure()` / `start()` の成功だけでは確認済みにしない。実際の映像入力に対応する最初のデコード出力を確認して成功を記録する。
4. 初期化できない、回復不能なデコードエラー、または映像入力から5秒経っても最初の出力がない場合に、より小さいサイズを要求する。期限は既存の1秒周期tickで監視するため、実際の検出には最大約1秒の遅れがある。
5. 最初の縮小では実応答の約90%を試す。その次は申告範囲内の候補を探し、最後はさらに小さくする。比率とアライメントを維持し、異なる実サイズは1系列につき最大4候補まで。ホストが失敗済みのサイズを繰り返す場合は停止する。
6. 一時的な資源不足やデコーダーの回収は、解像度の非対応と判断せず理由付きで終了する。成功・失敗の機種別永続キャッシュは作らない。

映像入力そのものが来ていない間は、初出力の期限を開始しない。デコーダーへ渡していない非キーフレームにも開始しない。最初の出力が得られた後に画面が静止しても縮小しない。

Surfaceを破棄したら出力待ちを解除する。回転・設定変更・接続のやり直しでは新しい系列を開始する。CONFIG_REQは1つずつ送り、回転前の要求へのCONFIGを新しい要求の応答と取り違えない。過去streamの遅延エラーや出力は、新しい系列を変更しない。同じサイズのホスト再設定だけで4候補の予算を消費しない。

同じstreamでも、MediaCodecを再初期化するたびに別のデコーダーIDを割り当て、最初の入力から出力を待ち直す。現在のSurfaceに接続したデコーダーだけがその起動を通知できる。古いデコーダーの入力・出力・失敗通知は、同じstream IDであっても新しい試行へ反映しない。入力処理・出力バッファ解放・非同期エラーのすべてで、一時的な資源不足の分類を共通にする。

## ソースの役割

- `video/DecodeNegotiation.kt`：Androidに依存しないサイズ・試行・期限の状態機械、CONFIG要求の直列化。
- `device/DeviceCaps.kt`：申告能力の収集、希望サイズの算出。
- `session/Session.kt`：既存CONFIG_REQ/CONFIGによる再交渉、streamと系列の管理、tickでの期限確認。
- `video/VideoDecoder.kt`：初期化・入力・出力・エラーの報告。再初期化の無限ループを防ぐ。
- `ui/StreamActivity.kt`：デコーダーからSessionへの通知。

Mac/Windowsホストとネットワークプロトコルに変更はない。解像度が動いたことは、指定フレームレートを持続できることや、すべての機種で正常動作することを保証しない。

## ビルドと自動テスト

JDK 17とAndroid SDK 36を用意して、リポジトリ直下から実行する。

```sh
android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug
```

開発版は `dev.anydisplay.client.dev`、表示名は `AnyDisplay Dev`。元の配布APKと別にインストールできる。releaseのアプリIDは変更していない。

`DecodeNegotiationTest` では、申告が過小/過大な場合、縦横、上限、アライメント、最大4候補、初出力待ちの5秒境界、入力なし、静止画面、旧stream、Surface再生成、ホストの縮小、同じ実サイズの繰り返し、資源不足を検証する。時刻を引数として与えるため、時間待ちをせず期限の境界を確認できる。

## 実接続の任意テスト

以下のテストは、Mac/Windowsホストがその端末を自動検出・接続できる状態を必要とする。通常のCIでは `liveHost` を渡さずスキップする。

```sh
android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e liveHost true \
  -e class dev.anydisplay.client.video.DecodeNegotiationDeviceTest \
  dev.anydisplay.client.dev.test/androidx.test.runner.AndroidJUnitRunner
```

テスト内から通常の失敗通知APIを呼び、実ホストとの縮小再交渉・描画、旧streamの通知無視、縦横の変更、4候補での終了、資源不足で縮小しない終了、再接続を確認する。製品コードに失敗注入の設定や端末専用のフラグは追加しない。無出力の期限は純粋状態機械のテストで検証する。

## 現在の実機確認範囲

2026-11-08、T1201のLineageOS 23.2とmacOSホストで、1600×2560 HEVCの受信・実デコード出力、論理800×1280/実1600×2560/2倍描画を確認した。縦横の再交渉と、上記の実接続テストも確認した。T1201専用の条件を実装したことを意味しない。

他のAndroid端末とWindowsホストの実機動作、長時間の連続再生、指定60fpsの持続性能は未検証。これらは能力判定や単体テストの成功から推定して確認済みとは扱わない。

## 参考

- [Android VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities)
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
