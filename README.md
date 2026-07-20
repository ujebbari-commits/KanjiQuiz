# KanjiQuiz — Android版 + Firefox Web版

AnkiDroidのデッキを使って遊ぶ漢字クイズ。Android版に加え、Firefox/Yomitan向けのWeb版を同梱。

## このパッケージの中身
- `HANDOFF.md` … **最初に読む引き継ぎ資料**（目的・構成・設計判断・現状・残タスク・注意点）
- `app/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` … Android版
- `web/` … Firefoxで動くPWA版
- `.github/workflows/build.yml` … APKビルド
- `.github/workflows/pages.yml` … Web版のGitHub Pages公開
- 全ロジックは `app/src/main/java/com/example/kanjiquiz/MainActivity.kt`（1ファイル）

## ビルド方法
手元にコンパイラが無くても、GitHub に push すれば `.github/workflows/build.yml` が
debug APK を自動ビルドし、Actions の Artifacts に `KanjiQuiz-apk` として出力する。
詳細は `HANDOFF.md` の「3. ビルド／配布パイプライン」を参照。

## 前提（アプリ利用側）
AnkiDroid を一度起動し、設定 → 高度な設定 →「AnkiDroid API」を有効化しておくこと。


## 2026-07-20 追加機能
- サバイバル／タイムアタックの循環出題
- 定着するまで復習
- 苦手語チャレンジ
- 逆モードの類似選択肢
- デイリーチャレンジ
- タイムアタックの時間加算・コンボ演出
- デッキ／モード／出題方向別の履歴フィルター

## v1.1
- 各カードが合計3回正解するまで循環する「3回正解」モード
- クイズ／めくり画面のカード本文を長押しで選択可能（Yomitanなどの選択メニュー用）
- APKメタデータとGitHub Actions成果物名をv1.1に更新

## v1.2
- 1問ごとの最大挑戦回数を設定できる機能を追加（初期値3回、1〜99回）
- 途中の誤答では正解を表示せず、同じカードへ再挑戦
- 全挑戦を使い切った場合だけ、そのカードを不正解として履歴・苦手度・サバイバル残機へ反映
- APKメタデータとGitHub Actions成果物名をv1.2に更新


## v1.3 / Web v0.1.0
- Android版に「Web版用JSONを保存」を追加
- Firefoxで動くWeb版を追加
- Web版はJSON / CSV / TSV / TXTを読み込み、デッキを端末内に保存
- Android版と同等のゲーム形式、履歴、苦手度、連続日数、めくりモードを実装
- 問題文・正解文は通常のHTMLテキストなのでYomitanで選択可能
- 文字選択中はタイマーを停止する設定を追加
