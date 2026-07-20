# KanjiQuiz Web

Firefox上でYomitanを使いながら遊べる、KanjiQuizのWeb版です。

## 主な機能

- JSON / CSV / TSV / TXTのデッキ読み込み
- 通常、サバイバル、タイムアタック、3回正解、苦手語、デイリー
- 間違いを全問正解するまで繰り返す定着復習
- 逆4択と類似したダミー選択肢
- 1問ごとの最大挑戦回数
- 履歴、正答率グラフ、苦手度、連続日数
- めくりモード
- オフラインPWA
- 問題文と正解文を通常のHTMLテキストで表示し、Firefox/Yomitanで選択可能

## 対応データ

### KanjiQuiz JSON

```json
{
  "format": "KanjiQuizWebDeck",
  "version": 1,
  "deck": {
    "name": "難読漢字",
    "fields": ["漢字", "読み", "メモ"],
    "notes": [
      {"id": "1", "fields": ["薔薇", "ばら", "植物"]}
    ]
  }
}
```

### CSV / TSV / TXT

先頭行をフィールド名として読み込めます。Anki Desktopのテキスト書き出しはTSVとして読み込めます。AnkiDroidから移す場合は、Android版v1.3のデッキ設定画面で「Web版用JSONを保存」を押してください。

## GitHub Pages

`.github/workflows/pages.yml`が`web/`をGitHub Pagesへ公開します。
リポジトリの Settings → Pages → Source を `GitHub Actions` に設定してください。

公開URLは通常、次の形式です。

`https://<GitHubユーザー名>.github.io/<リポジトリ名>/`
