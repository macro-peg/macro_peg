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
- 追加で dot-call を一般化して、`user.profile.name` みたいな no-arg chain と `foo.bar(1).baz do ... end` の chain + block を通せるように拡張。FullSetで必須になる call chain 表現力を前倒しで確保した。
- FullSet寄せの次段として `return` / `self` / 定数パス（`A::B`）を AST と parser に追加。`self.log 1` の receiver command、`module A::B` / `class C::D` の名前解釈も対応して、Rubyの基本構文土台をさらに拡張した。
- 昇格判断を早めるため、Ruby本家リポジトリから `test/ruby` `bootstraptest` `test/prism` を sparse checkout して `.rb` corpus を実際に流すランナー（`RubyCorpusRunner`）を追加。現状の成功率が低いことを数値で把握できる状態にした。
- corpusの先頭落ちを潰すために、single-quote文字列・percent quote（`%q{}` / `%{}`）・`[]` 添字呼び出し・`class C < Base` ヘッダを追加。Ruby本家 `.rb` corpus の通過率が `2.33% (7/301)` から `4.98% (15/301)` に改善。
- `next * 3` で FullSet向けの3機能を連続実装。`begin/rescue/ensure`、`class << self`、`@ivar/@@cvar/$gvar` の AST+parser+テストを追加して、構文土台を拡張した（この段階では corpus 成功率は 4.98% のまま）。
- さらに次の改善で、`for ... in` / `1..5` / `+=` / `retry` / `&block` / 式後置block（`lambda{...}.call` 形式）を実装。corpus 成功率は `4.98% (15/301)` から `5.32% (16/301)` へ小幅改善。
- 続けて、`respond_to?` などのメソッド名記号（`?`/`!`/`=`）と、比較・論理・単項演算（`==` `!=` `<` `>` `&&` `||` `!` `and` `or`）を追加。corpus 成功率は `5.32% (16/301)` から `6.98% (21/301)` へ改善。
- さらに `%q(...)` など paired delimiter のネスト対応を入れ、正規表現リテラル `/.../` と `:$a` / `:@x` / `:@@y` の symbol も対応。corpus 成功率が `6.98% (21/301)` から `12.62% (38/301)` へ大きく改善。
- 追加で `%Q{...}` と `%w[...]` / `%W[...]` を parser に導入し、`=~` / `!~` の match 演算子と `=begin ... =end` ブロックコメントも対応。`RubySubsetParserSpec` に回帰テストを追加して `sbt test` 全パスを確認。
- Ruby本家 corpus を再計測して、成功率が `12.62% (38/301)` → `13.95% (42/301)` に改善。`test_massign.rb` の先頭詰まり（`=begin`）を解消できた。
- さらに `%r{...}` / `%r"..."` 正規表現リテラル、`or` の改行継続、`or` 右辺での command-style call を追加。`RubySubsetParserSpec` に multiline `or` と `%r` のテストを足して回帰確認。
- corpus 成功率を `13.95% (42/301)` から `14.95% (45/301)` へ更新。未対応構文まで進んだ結果、`test_eval.rb` は即失敗から timeout に遷移したので次段で絞り込み予定。
- 次段で keyword 系を拡張し、`foo: 1` 形式の label hash entry と call 引数の keyword label（`f(x: 1)` / `f x: 1`）を追加。Prism系で多い `command_line: "p"` 形に対応する土台を作った。
- `RubySubsetParserSpec` に label hash / keyword arg の回帰テストを追加し、corpus 成功率を `14.95% (45/301)` から `15.61% (47/301)` へ改善。
- `%r"..."` の同一delimiterネストで発生していた探索爆発を修正。非ネスト専用の `percentBodySimple` を導入して `bootstraptest/test_eval.rb` の timeout を解消（1秒以内で通常判定に復帰）。
- squiggly heredoc（`<<~TAG`）を parser前処理で文字列リテラル化し、`assert_equal "x", <<~RUBY, ...` のような trailing args 付き呼び出しを通せるようにした。`test_eval.rb` 単体が通過。
- ASTとparserを拡張して `while` / `until`、代入式（`AssignExpr`）、単項 `+/-`、forwarding symbol literal（`:*` / `:**` / `:&`）を追加。`test/prism/api/parse_test.rb` 単体が通過。
- `RubyCorpusRunner` に `RUBY_CORPUS_FULL_ERROR=1` オプションを追加して失敗サンプルの詳細表示を可能化。デバッグ効率を上げた。
- `-x` 付きスクリプト前置き（shell preamble）を parse 前に除去する処理を追加。`runner.rb` の先頭失敗が line1 から line19 へ前進。
- 再計測で corpus 成功率を `15.61% (47/301)` から `17.94% (54/301)` に改善（+7）。
- 続けて `bootstraptest/test_flow.rb` の line 505 失敗を調査し、配列/呼び出し引数の「カンマ後改行」を許可するよう `arrayLiteral` / `callArgs` / `indexSuffix` に `spacing` 吸収を追加。
- 回帰テストとして multiline 配列要素・multiline 親付き引数を `RubySubsetParserSpec` に追加し、`test_flow.rb` 単体が通過。
- corpus 成功率をさらに `17.94% (54/301)` から `18.60% (56/301)` へ改善。
- 次段で `case/when/else`、singleton `def Dir.mktmpdir(...)`、`||=` を追加し、`RubySubsetParserSpec` に回帰テストを拡充。`case` 節の改行処理バグも修正した。
- Ruby本家 corpus を再計測して `23.59% (71/301)` まで上昇。続けて `<<` / `>>` 演算子と no-arg punctuation call（`block_given?` など）を導入した。
- `self.columns = ...` / `self.columns ||= ...` / `self.columns += ...` 形式の receiver attribute assignment を式として扱えるよう拡張し、`w -= 1` を含む複合代入も一般化（`+=` 以外に `-=` `*=` `/=` `%=` `<<=` `>>=` など）した。
- `wn > 0 ? wn : 1024` 向けに三項演算子を追加。性能回帰を避けるため、`conditionalExpr` は再パースを避ける左因子化実装にした。
- `timeout&.*(timeout_scale)` のために safe navigation `&.` と演算子メソッド suffix（`*` など）を追加。対応ケースを `RubySubsetParserSpec` へ追加済み。
- 回帰確認として `sbt test` 全パス、corpus は最新で `24.25% (73/301)`。`bootstraptest/runner.rb` は依然 `BT = Class.new(bt) do ...` 起点で失敗しており、次の掘りポイントとして継続調査中。
- `runner.rb` 掘りの続きとして、`BT = ...` 定数代入を追加しつつ、誤爆しないように `constAssignStmt` を `=` 先読み付きに修正。`BT.tty = ... if ...` のような定数receiver代入も回帰テストで固定した。
- heredoc前処理を `<<~` だけでなく `<<-` / `<<ID` まで拡張し、dash heredoc の回帰テストを追加。`not` 単項演算子、`nil?` のような予約語ベースpunctuated method 受理、`[]` への `=`/`||=` も対応した。
- 補間付きダブルクォート文字列（`#{...}` 内に `"` を含むケース）と backtick 文字列（`` `...` ``）を追加。`writer.write_object({...})` 向けに multiline hash entry の改行処理も修正した。
- `do ... ensure ... end` / `def ... ensure ... end` の bare ensure を `BeginRescue(..., ensureBody=...)` へ正規化する形で実装。加えて `faildesc, t = super` 用に `MultiAssign` AST+parser、`&:kill` の symbol block-pass、bare def params、`*args` / `**opts` と call 側 splat も追加した。
- `(@count += 1)` のような式文脈での複合代入を `AssignExpr` 化して通過。`sbt test` は全件成功を維持した一方、full corpus は今回再計測でも `24.25% (73/301)` のままで、`bootstraptest/runner.rb` / `part_after_main` は未対応構文（class `Assertion` 周辺）で引き続き詰まっている。
- 続きで `BT.columns > 0` / `e and BT.columns > 0` が落ちる回帰を修正。演算子前空白の扱いを見直し、`RubySubsetParserSpec` に比較式・`and` 連鎖の回帰テストを追加した。
- `puts tests.map {|path| File.basename(path) }.inspect` のような「空白あり brace-block suffix + chain」を通すため、`blockAttachSuffix` の空白吸収と `braceBlock` の閉じ波括弧手前空白を対応。関連テスト（command arg / `Thread.new{ r.read }`）を追加。
- `sbt test` と `RubySubsetParserSpec` は全パス維持。ただし full corpus は現時点で `21.26% (64/301, timeout=1000ms)` まで低下しており、`runner.rb` は依然 timeout。性能回帰と `class Assertion` 以降の解析継続が次タスク。
- `runner.rb` 再攻略で、`def ... rescue/else/ensure ... end` を `defStmt` 側でも正式対応。さらに postfix `rescue` modifier（`r.close rescue nil`）と `%` 二項演算子を追加して、`with_stderr` / `show_progress` 周辺の詰まりを解消した。
- `blockStatementsUntil` を再帰型に作り直して、複数 `rescue` 節で改行をまたぐケースを安定化。`def f ... rescue E1 ... rescue E2 ...` の回帰テストを追加した。
- `-> as do ... end` の lambda literal を `LambdaLiteral` AST として追加し、`add_assertion testsrc, -> as do ... end` を通過可能にした。加えて keyword parameter（`timeout: ...`）と特殊グローバル `$?` も対応。
- `sleep 0.1` で詰まっていたため `FloatLiteral` を導入。postfix `while/until` を statement 全体へ適用できるようにして `end while true` も受理するよう拡張した。
- 検証結果は `RubySubsetParserSpec` 98テスト + `sbt test` 全体151テスト全パス。full corpus は `24.92% (75/301)` から `26.91% (81/301)` へ改善（+6）。`runner.rb` は構文的には通るが `timeout=1000ms` では約1.05秒で依然 timeout。
