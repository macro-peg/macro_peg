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
- 「parser.y / lexer / AST node 関連は手元参照できるように」という提案を受けて、Ruby 3.3.9 の参照ソースを `third_party/ruby3/v3_3_9/` に取り込み、由来コミット付きREADMEを追加。
- 次フェーズ（FullSet実装）に向けて、先に `ruby.RubyAst` と `ruby.RubySubsetParser` を追加。class/def/call/配列/ハッシュをAST化できる最小系をテストで固定した。
- 「10年越しの宿題」という文脈を共有しつつ、`RubyFullParser` エントリポイントを追加して FullSet 実装へ前進。`module / if / unless / symbol literal` をサブセット側に先行実装し、ASTノードも拡張。
- 続きとして `elsif` 連鎖と postfix modifier（`stmt if cond` / `stmt unless cond`）を実装。Rubyっぽい分岐表現の下地をさらに強化した。
- さらに Ruby の難所に向けた第一歩として、引数省略の command-style call（`puts :ok`, `add 1, 2`）を statement 文脈で対応。modifier付き（`log 1, 2 if ready`）も通るようにした。

### 2026-03-03
- コウタが「指数爆発より無限ループが自然」という鋭い指摘をくれて、実際に `lazy val` 初期化再帰ループを特定できた。
- `statement -> block -> statement` の初期化循環を `refer(statement)` 遅延参照に切り替えて解消。
- あわせて `items.each do ... end` が落ちる原因（block直前の空白未吸収）も修正して、`blockCallExpr <~ spacing` を入れた。
- `RubySubsetParserSpec` と `sbt test` 全体を通して、ハング解消と既存回帰がないことを確認。
- さらに「最終ゴールは FullSet」と再確認。方針をぶらさないため、今回の追加も FullSet移行に直結する土台（改行区切り文対応・`receiver.method(args)` + block対応）に限定して進めた。
