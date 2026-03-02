# コントリビューター向けガイド

このリポジトリはScalaで実装されたMacro PEGを管理しています。ビルドにはsbtを使用します。言語はScala 3を使います。

テストを実行する際は `sbt test` を実行してください。

- コードのインデントは 2 スペースとします。
- テストコードは `src/test/scala` に配置します。コードを変更したら必ずテストを実行してください。
- コミットメッセージは現在形の簡潔な表現を推奨します（例: `Fix: parser bug`）。
- `build.sbt`を書き換えたら、必ず再度 `sbt test` を実行してください。

## ディレクトリ構成

```text
├── build.sbt # sbt のビルド設定
├── project/
│   └── build.properties # sbt のバージョン設定
|   └── plugins.sbt # sbt プラグインの設定
├── src/main/scala # Scalaのコード本体　
│   ├── com/github/macro_peg # Macro PEGのメイン実装
├── src/test/scala Scalaのコード本体　
│   ├── com/github/macro_peg # Macro PEGのテスト
|
```

## 注意事項

プルリクエストを送る前にテストを通しておくことを強く推奨します。CI でも `sbt test` を実行しているため、ローカルで失敗すると CI でも失敗します。

## P$作成時の指示

PRタイトルのフォーマット：`[<project_name_>] <タイトル>`

## 対話メモ

### 2026-03-02
- コウタから「macro-peg を実用的にするには何が必要か」を相談された。
- 論点は parser generator / parser combinator / 親切なエラーメッセージなど、実運用向け機能の優先順位づけ。
- そのまま 1〜6 を全部実装する流れになって、診断API・文法検証・メモ化・combinator拡張・parser generator・テスト追加まで一気に完了。
- 追加で「higher-order macro PEG は実用的やと思う。generator と interpreter も対応してほしい」という方向性を確認して、generator に higher-order fallback（Interpreterベース）を実装、interpreter 側にも higher-order 回帰テストを追加。
- さらに「Higher-Order Macro PEG の良いサンプル」として JSON parser を書く話になって、`Token` / `SepBy` みたいな高階マクロを使った JSON 文法サンプルをテストとして追加。
- 「それ first-order でもいけるやん」という指摘を受けて、ほんまに higher-order が効くサンプル（関数引数で拡張点を注入）を検討する流れに。
- 拡張版として `S`(strict JSON) と `S5`(NaN/Infinity許可) を同一骨格で切り替える higher-order JSON 方言サンプルを追加。first-orderみたいな文法複製なしで差分注入できる形にした。
- Scala 3 前提ならメタプログラミング（compile-time codegen / compile -> execute）でも実用化できるのでは、という方向性が出た。parser generator は簡易用途向けで併存させる案。
- Rubyコミッター遠藤さんとの文脈を踏まえて「Ruby 3 grammar を完全表現し、AST まで取れるか」を本気で検討する話に進んだ。Higher-Order PEG + compile-time基盤での実装可能性と段階的ロードマップを詰める。
- 「stateful lexerを使わず pure higher-order macro PEG でいけるか？」の問いに対して、動的delimiter捕捉（heredoc風）を `CallByValueSeq` で成立させる実証を追加。compile-time API と generator でも戦略指定できるように揃えた。
