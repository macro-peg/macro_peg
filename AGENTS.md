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

### 2026-03-04
- コウタから「根本的にカバレッジを上げる」「節目で `parse.y` も見て全体構造を把握する」という方針をもらって、まず `parse.y` の `stmt/expr/command/arg/primary/lambda` を再確認してから対応順を決めた。
- `commandArgs` 周りの回帰（`spacing` を広く取りすぎることでバックトラックが増える問題）を修正して、corpus 成功率を `22.92% (69/301)` から `25.58% (77/301)` に戻した。
- その後、literal と演算系をまとめて底上げした：
  - `%i/%I` symbol array
  - 基数付き・アンダースコア付き整数（`0b/0o/0d/0x`, `1_000`）
  - `IntLiteral` を `BigInt` 化して巨大整数での `NumberFormatException` を解消
  - `**`, `&`, `|`, `^`, `~`, `===`, `<=>` などの式演算を拡張
  - 演算子/サフィックス付き symbol literal（`:!=`, `:<=`, `:frozen?`）と補間付き symbol（`:"test_#{path}"`）を追加
- `kw(...)` の単語境界不足で `define_method` を `def` と誤認する根本バグを修正（`<~ !identCont`）。`do ... end` の `end` 吸い込みが減り、`dump_test.rb` などの落ち方を改善。
- `name:` と `::` の競合（`Test::Unit` を keyword label 誤認）を `labelColon` で分離し、deep const path 引数（`extend Test::Unit::Assertions`）を通るようにした。
- 最終的に `sbt test` は全 `165` テスト成功、corpus は `27.91% (84/301)` まで改善（前回 `24.25%` から `+11` ファイル）。
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
- 次の掘りで `parse.y` を見直しつつ、`Dir[glob_pattern, base: BASE]` のような bracket call 引数（keyword label含む）を parser に追加。
- `def method_missing(name, *)` 用に nameless rest parameter（`*` / `**`）を formal parameter で受理。
- `items[1..]` / `items[..limit]` みたいな open-ended range を追加し、`0...0x100` が `..` に先食いされる不具合を `rangeOp` の優先順（`...` 先）で修正。
- 配列要素の splat（`[*head, 1]`）を受理するため `arrayElementExpr` を導入。
- 回帰として `RubySubsetParserSpec` に上記ケースのテストを追加し、`testOnly com.github.kmizu.macro_peg.ruby.RubySubsetParserSpec` は全117件成功を維持。
- full corpus (`RubyCorpusRunner`, timeout=1000ms) は timeout 依存の揺れが大きく、今回は速度改善まで届かず。次段は `parse.y` の statement/command 分岐を再照合しつつ、`runner.rb` / `parse_test.rb` 系の timeout 原因を構文単位で分割して潰す方針。
- 途中経過を `AGENTS.md` に随時残す運用を再確認して、このセッション分も追記しながら進行。
- 連続文字列リテラル連結（`"a" "b"`）を parser に追加。`RubySubsetParserSpec` に `parses adjacent string literals as one argument` を追加。
- ternary と symbol literal の曖昧性を修正。`part ? part.unescaped : "1"` が `part.unescaped :"1"` と誤解釈される経路を、`symbolLiteral` の `:` 後スペース禁止（`symbolPrefix` 導入）で解消。
- これにより `test/prism/heredoc_dedent_test.rb` の失敗（`node.parts.map { ... ? ... : "1" }`）を解消し、単体実行で成功を確認。
- block suffix の誤バックトラック抑制のため `blockAttachSuffix` に block 開始 lookahead（`do` / `{`）を追加して失敗位置の診断を改善。
- 検証は `sbt test` 全 `173` テスト成功、`RubySubsetParserSpec` 全 `120` テスト成功を確認。
- full corpus 再計測（timeout=1000ms）は `21.59% (65/301)`。`bootstraptest/runner.rb` は依然 `BT = Class.new(bt) do` 近辺で `expected end`/timeout が残っており、次段の掘り対象として継続。
- `parse.y` を再確認して、`primary` が `tLPAREN compstmt ')'` を受理する構造（括弧内で modifier 付き式が来る）を踏まえ、`parenExpr` の不足を特定。
- `exprPostfixModifierSuffix` / `exprWithPostfixModifier` を追加し、`(expr if cond)` / `(expr unless cond)` / `(expr while cond)` / `(expr until cond)` を括弧内式として受理できるよう拡張。
- 回帰テスト `parses parenthesized postfix if expression in splat array element` を追加。最小失敗片 `x = [*((params.rest.name || :*) if ...)]` が成功に反転。
- `test/prism/locals_test.rb` 単体（timeout=10000ms）が `success=1/1` に改善し、`sbt test` 全 `174` テストも成功。
- full corpus 再計測（timeout=1000ms）は `22.59% (68/301)` で前回 `21.59% (65/301)` から `+3`。一方で `bootstraptest/runner.rb` は引き続き `expected end`（`BT = Class.new(bt) do` 近辺）で未解消。
- `runner.rb` をセグメント分割して再計測し、ボトルネックを `647-791` 行（`assert_normal_exit` / `assert_finish` 周辺）に絞り込み。特に `assert_normal_exit` 単体（`702-750`）で `timeout=10000ms` を再現。
- さらに最小化すると、`assert_normal_exit` の前半（IO起動〜join）は通る一方、後半のネストした条件分岐と `!~` 正規表現判定を含む塊を足した時に探索が急増する傾向を確認。次段はこの領域の曖昧性を `parse.y` の `arg/stmt` 優先順に合わせて削る方針。
- コウタから「`compaction` 後に忘れないよう、途中経過は随時 `AGENTS.md` に書く」方針を改めて指示された。以降もこの運用を継続する。
- コウタから「一気に coverage を上げる作戦」を求められたので、次サイクルは `parse.y` 照合ベースの高収益バンドル実装で進める。
- 優先は「未対応構文の追加」より先に「timeout 減（探索爆発抑制）」を置く。`assert_normal_exit` 系の hotspot に `cut`/先読みでコミット点を作り、1s枠での corpus 完走率を先に引き上げる。
- 実装は 1 機能ずつではなく、同じ失敗クラスタに効くセット（例: 正規表現条件式 + modifier + brace block 周辺）をまとめ打ちして、1コミットあたりの成功ファイル増分を最大化する。

### 2026-03-05
- コウタから「timeout は探索爆発より nullable 規則の再帰呼び出し（`A(pos=1)` の再出現）を先に疑うべき」という指針をもらって、実装の中心をそこに切り替えた。
- `MacroParsers` に `guard(name)(parser)` を追加し、同一 `(rule,pos)` の無消費再入を検出して `ParseFailure` に落とす仕組みを導入した。`RUBY_PARSER_TRACE_RECURSION` / `RUBY_PARSER_TRACE_PATH` / `RUBY_PARSER_RECURSION_HARD_FAIL` も利用可能にした。
- 回帰テストとして `MacroParsersAdvancedSpec` に direct/indirect な non-consuming recursion 検出ケースを追加した。
- Ruby 側では `statement` / `blockStatementsUntil` / `ifTail` に `guard` を適用し、nullable 再帰のホットスポットに直接ガードを入れた。
- `x = case ... end` の式文脈受理を戻すため、`primaryNoCall` に `caseStmt`（加えて `unlessStmt`）を式として取り込んだ。
- `while (node = queue.shift); return node if node; end` が落ちる回帰を修正。`node if ...` が command-style call の引数として `if ... end` を誤吸収する経路を、`commandArgHeadGuard`（`if/unless/while/...` 始まりを command 引数先頭として拒否）で遮断した。
- `RubyCorpusRunner` を拡張して `RUBY_CORPUS_FAIL_OUT` / `RUBY_CORPUS_CLUSTER` / `RUBY_CORPUS_PROFILE` を追加し、失敗TSV出力・クラスタ集計・遅延上位表示ができるようにした。
- `RubyCorpusRunner` の timeout 実装も見直し、worker thread を使ったタイムアウト処理（interrupt + 最終手段 stop）に変更して、計測時の積み残し抑制を試した。
- 検証は `sbt test` 全 `193` テスト成功。full corpus の 5s 完走計測はこの時点で長時間化が残り、改善途中として継続する。
- full corpus の 5s 完走を再計測し、`45.51% (137/301)` に到達して当面の停止条件（45%）を超えた。
- 追加サイクルで `return if ...` と `if ...` 式競合を整理。`return` の値パースに postfix modifier 先頭ガードを入れ、`commandArgHeadGuard` も `if/unless/while/until/rescue` のみに絞った。
- さらに `do ... rescue ... end`（block 内 rescue 節）を parser に追加し、`encodings_test.rb` の失敗を単体で成功へ反転させた。
- サブセット再計測（`RUBY_CORPUS_MAX_FILES=60`, timeout=5000ms）は `75.00% (45/60)` から `80.00% (48/60)` に改善。
- コウタから「5秒超えは文法バグ前提」「HARD_FAIL/guardを過信しないで進める」と再確認を受けて、`/tmp/ruby_corpus_fail_full_5s_after.tsv` の上位 fail 群を基準に再攻略した。
- `assert_equal (+\"ア\").force_encoding(...), slice` が `functionCall` に先食いされる不具合を修正（`functionCall` を no-space 前提化）し、回帰テスト `parses command-style call with parenthesized first arg and trailing args` を追加。
- `class_node` を `class` に誤分割する問題を、bare/receiver keyword method の単語境界化（`!identCont`）で修正。`method_node = class_node.body.body.first` の回帰テストを追加。
- `while iseq = queue.shift` 用に condition で代入式を受理（`conditionAssignExpr` 追加）。`while` の unparenthesized assignment 条件テストを追加。
- heredoc 正規化で `#{` が補間実行される副作用を修正（`encodeDoubleQuoted` で `\#{` へエスケープ）。`lex_test` / `comments_test` 系の失敗改善に効いた。
- `... ||\\n!expr` を通すため、`||`/`&&` の改行継続を許可する `infixLogicalSymbol` を導入し、`parses multiline || continuation with unary rhs` を追加。
- これで `test/prism/lex_test.rb` / `newline_test.rb` / `result/comments_test.rb` / `result/overlap_test.rb` は失敗から通過に反転。
- 5s fail 上位20本は `8/20` から `13/20` に改善。残りは `bootstraptest/runner.rb` の parse_error 1件と timeout 6件（`test_yjit*`, `regular_expression_encoding_test.rb`, `errors_test.rb`, `source_location_test.rb`）。
- timeout クラスタの最小化も実施し、`regular_expression_encoding_test.rb` の hotspot が nested `do` 内の `assert_regular_expression_encoding_flags(..., regexp_sources.product([...]))` 周辺で再現することを確認。
- 安全性優先で性能最適化の試行（広すぎる cut）は回帰を起こしたため巻き戻し、最終的に安定版へ戻して `sbt test` 全件成功を維持。
- 続きで「guardが全Ruleに無い前提」を受け、`MacroParsers.ReferenceParser` 側にも再帰検知を入れて、`refer(...)` 経由の無限再帰を rule個別guardなしで捕捉できるようにした。
- さらに `RUBY_PARSER_VISIT_THRESHOLD` を追加し、同一 `(rule,pos)` の過剰再訪を hard fail できる診断を導入。`TRACE_PATH=1` 時は path 付きで出力するようにした。
- 性能面では `MemoParser`（`parser.memo`）を実装し、Ruby 側の `expr` / `conditionExpr` / `commandExpr` / `postfix*` / `chainedCallExpr` に適用。`MacroParsersAdvancedSpec` に memo 回帰テストを追加。
- `callArgs` / `bracketCallArgs` に `(` / `[` 後の `cut` を入れて、`f(...` を途中失敗したときに「引数なし call」へ戻る再探索を抑制した。
- `regular_expression_encoding_test.rb` の timeout を解消（単体で 5s timeout -> 約0.7s）。最小再現片も約3.8s -> 約0.3s まで短縮。
- 5s fail 上位20本の再計測は `13/20` から `15/20` へ改善（+2）。残りは `runner.rb` parse_error 1件と timeout 4件（`test_yjit*` 3件 + `source_location_test.rb` 1件）。
- `source_location_test.rb` は単体だと約1.6sで通る一方、top20連続実行ではGC圧で timeout になる揺れを確認（runner出力にGC警告あり）。
- 検証は `sbt test` 全 `212` テスト成功を確認。
- ここから coverage 引き上げの追加バンドルを実装:
  - `%w/%W/%i/%I` の quote delimiter（`%w"..."`, `%w'...'`）を追加
  - `a = b = c` 連鎖代入の RHS を受理
  - `*a = ...` / `ENV[n0], e0 = ...` を含む multi-assign を拡張
  - `o&.x = 6` の safe-nav assignment、`def `(command)`、`?a` 文字リテラル、`:'@'` symbol、`{**a}` hash splat を追加
  - `&method(:sleep).to_proc` のような call-chain block pass を受理
  - `case ... in ...` の `in` 節、`for x,y in ...` 複数束縛、`class << o; ...; end.class_eval ...` の chain を追加
- 回帰テストを `RubySubsetParserSpec` に大量追加（`170` 件まで拡張）し、`sbt test` 全 `228` テスト成功を確認。
- full corpus（timeout=5000ms）は揺れがあるものの、ピークで `65.45% (197/301)`、直近再計測で `64.12% (193/301)`。
- 追加で coverage 改善サイクルを継続し、`expected==temp` / `expected==temp` を含む postfix modifier 文脈の失敗を修正。`methodSuffixChar` の `=` を `==/=>/=~` と衝突しない形に制限した。
- `do ... end&.foo 1` のような chain 末尾 command-arg を式として取り込むため、`methodCommandSuffix` を導入（`callSuffix`/`nonIndexCallSuffix`/`callSuffixNoBlock` に反映）。
- no-space 演算子の誤字句化を追加修正し、`!` サフィックスを `!=` / `!~` と衝突しないようにした。回帰として no-space `!=` と `!~` の spec を追加。
- 改行継続の `+` 演算（`"... " +\n"...")` を通すため、`infix(op)` の後続空白を `spacing` に拡張。`test_emoji_breaks.rb` の失敗を単体で成功へ反転。
- RHS 連鎖代入を `receiver`/`index` に拡張し、`expected[3] = actual[3] = nil` を受理。`ripper_test.rb` は単体で通過に改善。
- `parser.diagnostics.all_errors_are_fatal = true` のような多段 receiver assignment を受理するため、`chainedReceiverAssignableHead` / `chainedReceiverAssignExpr` を追加（既存 receiver assignment 回帰なしを確認）。
- `while (left, right = queue.shift)` 向けに `MultiAssignExpr` を AST+parser に追加し、括弧内 multi-assign 条件を式として受理。
- 回帰テストを `RubySubsetParserSpec` に追加（no-space `!=`/`!~`、multiline `+` continuation、chained index assignment、chained receiver assignment、while 条件 multi-assign など）。`RubySubsetParserSpec` は `181` 件成功、`sbt test` 全 `239` 件成功。
- full corpus 再計測:
  - timeout=5000ms: `67.11% (202/301)`（前回 `65.78%` から +4 files）
  - timeout=1000ms: `55.81% (168/301)`（timeout圧で前回 `56.81%` から -3 files）
- 未解決の主クラスタは `bootstraptest/runner.rb`（`BT = Class.new(bt) do` 近辺 parse_error）、`test_case_comprehensive.rb`（`and class` 文脈）、`parser_test.rb` 後半の parse_error、および `test_yjit*` 系 timeout。
- 続きで `class/module` を式文脈でも受理（`primaryNoCall` で `NilLiteral` 正規化）し、`ready and class C; end` を回帰テスト化。`test_case_comprehensive.rb` は失敗から通過へ反転した。
- `class` を bare keyword call と receiver keyword method で分離し、`private/public/protected` の既存挙動（bare call）を維持したまま `and class ...` を両立させた。
- full corpus（timeout=5000ms）を再計測して `68.11% (205/301)` に到達（直前 `67.11%` から +3 files）。
- 次サイクルで block parameter の `|` 食い込みを修正。`|b, c=42|` 形式に加えて block keyword default（`|str: "foo"|`）と trailing comma（`|x,|`）を受理するように拡張した。
- singleton `def` の receiver を拡張して `def (o = Object.new).each` / `def nil.test_binding` を受理。`defReceiverName` に parenthesized expr と `nil/true/false` receiver を追加した。
- `a, b = expr,\n  expr` の multiline RHS が落ちる問題を修正し、`assignValueExpr` を「カンマ後だけ `spacing` 許可」に再設計した（文境界を壊さない形）。
- 匿名 keyword forwarding を式側にも追加し、`[b, **]` / `foo.(**{})` を受理。あわせて dot-call shorthand（`m.(...)` => `call`）suffix を追加した。
- `ruby2_keywords def foo(*args)` を statement として受理し、decorated def を parser に追加した。
- `alias [] new` が未実装だったため `aliasStmt` を追加。`class << self; alias [] new; end` の最小再現を通過に反転した。
- 回帰テストを `RubySubsetParserSpec` に追加し、`testOnly` は `194` 件成功、`sbt test` 全 `253` 件成功を維持。
- full corpus 5s 再計測（`-Xmx8g -XX:+UseG1GC`）で `71.43% (215/301)` を確認。今回開始時点 `69.10% (208/301)` から `+7` files。
- なお timeout は `42` 件（parse_error `41`, read_error `3`）で依然多く、`test_keyword.rb` / `test_lazy_enumerator.rb` / `bootstraptest/runner.rb` / `test/prism/unescape_test.rb` が次の重点掘りポイント。
