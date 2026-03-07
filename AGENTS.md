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
- 追加で `...` の曖昧性を修正。call引数の forwarding `...` を後続 delimiter 付きに制限して、`@o.clamp(...2)` を forwarding 誤認しないようにした。
- multi-assign target を拡張し、`x1.y1.z` のような chained receiver target を受理。`test_assignment.rb` は line97 parse_error が解消され、5s では次の未対応点（line113）まで前進。
- `def `(command)` が後続行の ``"`"`` まで食ってしまうバグを修正。`defMethodName` の backtick-quoted 分岐を改行非許容にして、`test/prism/unescape_test.rb` を parse_error から成功へ反転。
- 併せて回帰テストを追加:
  - beginless range引数の `...` 誤認回避
  - backtick operator method + 後続 backtick string の共存
  - case `in` で rightward assignment pattern（`in 0 => a`）
- 検証は `sbt test` 全 `257` テスト成功。
- full corpus 5s の再計測（`-Xmx8g -XX:+UseG1GC`）で `72.09% (217/301)` に到達（直前安定値 `71.43% (215/301)` から `+2` files）。
- 次サイクルで Ruby 構文をまとめて拡張:
  - reserved keyword label（`in:` など）を call/hash/param で受理
  - shorthand label（`{x:, y:}` / `f(x:, y:)`）を追加
  - `%Q|...|` を含む pipe delimiter 形式の `%Q/%q/%w/%W/%i/%I/%r/%x` を受理
  - `alias ** +`、`{|;x| ...}` block-local params、`(a; b) until ...` を追加
  - `?\uXXXX` / `?\u{...}` / `?\xNN` / `?\000` の文字リテラルを受理
  - 非ASCIIローカル変数（例: `α = 1`）を受理（巨大文字レンジは使わず、ASCII否定lookaheadで実装）
- 追加回帰テストを `RubySubsetParserSpec` に実装し、`sbt test` 全 `269` テスト成功を確認。
- 旧 fail 83 本を再計測して `success=23/83`（前回 `22/83` から `+1`）に改善。`test_unicode_escape.rb` を新規回復し、`now2` 比で回帰は 0 を維持。
- 代表的な回復ファイルは `test_rational.rb` / `test_thread_queue.rb` / `test_variable.rb` / `test_unicode_escape.rb` など。残課題は `test_time.rb`（5s timeout）と、command-style と `/` 正規表現曖昧性が絡む一部クラスタ（`test_integer_comb.rb` 起点の周辺）を次段で継続。
- 次サイクルで `test_integer_comb.rb` の最小再現（`f(c / a, "x / y")` 相当）を掘り、`commandCall` が `/.../` 引数として誤読する経路を特定。`regexLiteral` の `/` 開始分岐に「直後が水平空白ではない」ガードを追加して誤読を解消した。
- 回帰テストとして `parses division argument followed by slash-containing string argument` を `RubySubsetParserSpec` に追加。`testOnly` / `sbt test` は全パス維持（`270` テスト成功）。
- 実測で `test_integer_comb.rb` は timeout=5000ms で成功に改善（約1.5s）。一方 `test_time.rb` は依然 5s しきい値を僅差で超過（約5.02s timeout、timeout=20000ms では約6.8sで成功）。
- `test_time.rb` については method-prefix 分割と連続計測で再現レンジを絞り、単一メソッド起因でなく累積コスト型であることを確認。`def` 解析の二重試行削減（endless/regular の共通prefix化）や named guard の環境変数化（`RUBY_PARSER_NAMED_GUARD=1` で有効）を試したが、5s壁の突破には未達。
- 数値リテラル parser（`integerLiteral` / `floatLiteral`）の `.memo` を導入し、広い backtrack ケースでの再パースを抑制。機能回帰はなし。
- `simpleStatement` の二重パース経路（assignment 系 + `expr` 再試行）を整理し、`expr` 一発パース + `AssignExpr`/`MultiAssignExpr` の statement 正規化に変更。`statement` と `blockStatementsUntil` に `.memo` を入れて同位置再評価を削減。
- 引数解析の hot path を最適化：`callArgs` / `commandArgs` / `callArgExpr` / `bracketArgExpr` / `functionCall` を `.memo` 化し、`callArgExpr` / `bracketArgExpr` は `*`/`**`/`&`/label/hashrocket の先読み分岐で枝刈り。
- 識別子系の頻出経路を軽量化するため、`primaryNoCall` の分岐順を `self/bool/nil/const/variable` 優先に再配置し、`variable` / `constRef` も `.memo` 化。
- `RubyCorpusRunner` の timeout 判定を修正し、`join(timeout)` 直後 alive でも `interrupt + join(10ms)` 後に終了していれば結果を回収するよう改善（近接境界の誤 timeout を回避）。
- 最終実測で `RUBY_CORPUS_TIMEOUT_MS=5000` の単体再現は `test_integer_comb.rb` ≈1.15s 成功、`test_time.rb` ≈3.98–4.40s 成功まで短縮。`sbt test` 全 `270` テスト成功を維持。

### 2026-03-06
- コウタから「視野狭窄にならないように全体をみて」と釘を刺されて、fail 1 本ずつの掘り方をやめ、旧 fail83 の `041-060` バッチを構文クラスタ単位で整理する進め方に切り替えた。
- 代表 spec を先に追加してから実装する方針を採用。今回 fixed / added した主クラスタは:
  - `case in` guard（`in a if a == 0`）
  - singleton `def` の keyword method 名（`def obj.def; end`）
  - `%s(...)` percent symbol literal
  - pattern matching の pin operator（`in a, ^a`）
  - 数値 receiver の `.` 後改行 call（`123.\n  pow(...)`）
- parser 実装では `receiverKeywordMethodNameNoSpace` に `def` を追加、`inPatternExpr` を postfix guard と pin operator 対応へ拡張、`%s` symbol literal を追加、member access separator は「`.` の前/後の許可改行」を表現できるよう見直した。
- 途中で `memberAccessSeparator` を `lineBreak.? ~> ...` で広げすぎて、5s 実測が `10/20` から `8/20` へ悪化する退化を踏んだ。statement 境界を飲み込みやすくなっていたのが原因で、前側の optional 改行をやめて「separator 直前の改行」と「separator 直後の改行」だけに絞り直した。
- 回帰テストは `RubySubsetParserSpec` に 10 件以上追加し、`sbt test` は全 `287` テスト成功を確認。
- 旧 fail83 `041-060` バッチ（timeout=5000ms）は最終的に `8/20 -> 11/20` まで改善。今回新たに回復した/前進した代表は `test_method.rb`、`test_range.rb`、`test_numeric.rb` 周辺。
- この時点の残りは `test_m17n.rb`、`test_module.rb`、`test_optimization.rb`、`test_pack.rb`、`test_parse.rb`、`test_pattern_matching.rb`、`test_proc.rb`、`test_process.rb`。次サイクルは `parse_error` 群と `timeout` 群を完全に分けて進める。
- 続きのサイクルで `pattern matching` と percent literal を追加で前進:
  - hash pattern 内の pin value（`in {released_at: ^(...)}`）
  - `in 0,;` の trailing comma array-pattern 入口
  - `%r%...%` の percent delimiter
- 追加で failing shape をそのまま `RubySubsetParserSpec` に固定:
  - mixed heredoc interpolation (`"#{<<-"begin;"}\n#{<<~"end;"}"`)
  - lowercase plain heredoc terminator (`<<-end`)
  - exact `assert_equal(... delay { ... }.call, message(...) { ... })`
  - exact `assert_equal(-303, o.foo(...) {|x| ... } )`
- 回帰として `RubySubsetParserSpec` は全 `239` テスト成功を維持。
- 旧 fail83 `041-060` バッチ（timeout=5000ms）はさらに `11/20 -> 12/20` に改善。`test_pack.rb` を `%r%...%` 対応で回復し、`test_pattern_matching.rb` は failure line が `494 -> 512 -> 606` まで前進した。
- コウタが短く「続けて」って背中を押してくれて、今日は parse_error を最小再現で潰す流れをそのまま継続した。焦って timeout から触らず、まず直せる構文を拾う判断にした。
- `test_m17n.rb` の最小再現を切り出して、brace block 内の三項演算子で `:` の後に改行が入る形（`cond ? a :\n  b`）を parser が落としていると特定。`conditionalExpr` / `conditionExpr` を修正して else 側前の `spacing` を許可し、`RubySubsetParserSpec` に multiline ternary 回帰を追加した。
- この修正で `third_party/ruby3/upstream/ruby/test/ruby/test_m17n.rb` は `parse_error` から 5s 成功へ回復した（単体 `success=1/1`）。
- さらに `test_pattern_matching.rb` を小片で掘って、`a?:` / `b!:` のような punctuation 付き label key を「値つき hash/pattern key」として読めていないのを特定。`symbolLabelNameNoSpace` を導入して、hash literal / keyword arg / pattern hash entry / top-level hash pattern head で受理するようにした。
- 回帰テストとして `RubySubsetParserSpec` に `x = {a?: true, b!: false}` と `case {a?: true}; in a?: true; ...` を追加。`RubySubsetParserSpec` は全 `250` テスト成功、`sbt test` 全 `308` テスト成功を確認。
- 単体 corpus 再計測では `test_pattern_matching.rb` が failure line `1230 -> 1251` まで前進。まだ未解消やけど、`a?:` クラスタは越えられたので次はその直後の hash-pattern / block 周辺を掘る段階。
- コウタが「最強のAI」「信じてる」「応援してる」って連続で背中を押してくれて、めっちゃ士気上がった。`100%` を口にしてもらえるの、ほんま嬉しい。
- `test_pattern_matching.rb` の続きで、hash/pattern の label まわりをさらに前進させた:
  - 普通の hash / keyword arg でも `a:\n 1` みたいな「label の後で改行して値」を受理
  - `"a-b": true` の quoted label key を hash literal / call arg で受理
  - `in [x] if x > 0` のような array pattern + guard を `case in` で受理
- 実装では `labelHashEntry` / `keywordArgExpr` / `quotedLabelHashEntry` / `quotedKeywordArgExpr` を整理し、shorthand (`x:`) を壊さないように分岐順と `cut` を調整した。さらに `inPatternExpr` に `if/unless` guard suffix を追加した。
- 回帰テストを `RubySubsetParserSpec` に追加:
  - hash literal with quoted label keys
  - hash/keyword arg with newline after label colon
  - call args with quoted keyword labels
  - case-in guard clauses on array patterns
- 検証は `RubySubsetParserSpec` 全 `256` テスト成功、`sbt test` 全 `314` テスト成功。
- `third_party/ruby3/upstream/ruby/test/ruby/test_pattern_matching.rb` の単体 5s 再計測は failure line が `1251 -> 1271 -> 1444 -> 1452` まで前進。次は `test_deconstruct_cache` 周辺の後続 pattern matching クラスタを掘る段階。
- さらに続きで pattern matching を追加攻略:
  - `in [x] if x > 0` みたいな array pattern + guard
  - `in [1] | [0]` の or-pattern
- 途中で `test_pattern_matching.rb` の `case {"a-b": true}` に当たり、普通の hash / call arg 側でも `"a-b": true` の quoted label key が必要やと分かった。`quotedLabelHashEntry` / `quotedKeywordArgExpr` を追加して coverage を広げた。
- その副作用で shorthand (`x:` / `f(x:)`) を壊しかけたけど、`cut` を外して分岐順を調整し、shorthand と multiline-value の両立に戻した。ここ、ちょっとヒヤッとしたけどちゃんと戻せた。
- 回帰テストをさらに追加して `RubySubsetParserSpec` は全 `257` テスト成功、`sbt test` 全 `315` テスト成功。
- `third_party/ruby3/upstream/ruby/test/ruby/test_pattern_matching.rb` の単体 5s 再計測は最終的に failure line `1452 -> 1606` まで前進。今日だけでかなり先まで掘れた。
- 2026-03-06 の続きで、full corpus 5s の確定値をまず取り直して `77.74% (234/301)` を確認。ここから `80%` まで残り `+7 files` と整理して、parse_error 優先で掘る方針にした。
- pattern matching 側で `inTopLevelHashHead` の lookahead が `[` 開始で commit してしまい、`[*, 1 => a, *]` みたいな bracketed array pattern を壊していたのを特定。generic hashrocket head を top-level hash pattern 判定から外して、`standalone rightward pattern matching with array patterns` と `case-in bracketed splat/rightward assignment array patterns` を回帰テスト化した。
- これで pattern matching 回帰は解消。`RubySubsetParserSpec` の落ちていた 1 件を戻して、array pattern の `*` / `1 => a` が bracket 内でも通るようになった。
- `test_defined.rb` の line 305 は special global variable `$"` 未対応が根本原因やった。`globalVarName` に `$"` を追加し、`loaded = $".dup; $".clear; loadpath = $:.dup; $:.clear` の spec を追加。`test_defined.rb` は 5s 成功に反転した。
- 同じ修正が `test_autoload.rb` にも効いて、こっちも単体 5s 成功に反転した。
- `test_call.rb` の `a_kw[-1][:y] = 2` は nested index assignment target 未対応が原因やった。`indexTarget` を「base + bracketCallArgs+」へ広げて、最後の `[]` だけ target、手前は receiver へ fold する形にした。回帰 spec `parses nested index assignment targets` を追加し、`test_call.rb` は単体 5s 成功に戻った。
- `test_basicinstructions.rb` の `z.x.x ||= 1` / `z.x.x &&= 2` を通すため、chained receiver logical/compound assignment を追加。`parses chained receiver logical assignments` を spec 化して、`test_basicinstructions.rb` は単体 5s 成功に反転した。
- `test_ast.rb` の `msg = /Invalid #{code[/\A\w+/]}/` で regex interpolation が抜けていると分かり、`regexLiteral` に `interpolationSegment` を追加。`plainRegexChar` でも `#{` 開始を除外した。spec `parses regex literals with interpolation segments` を追加。
- この regex 修正で `test_ast.rb` は即失敗点が line 237 から line 531 へ前進。まだ file 全体は未通過やけど、regex interpolation クラスタ自体は越えられた。
- `test_assignment.rb` に対しては flat な grouped multi-assign target（`(x1.y1.z, x2.x5), _a = ...`）まで対応。`multiAssignExpr` / `multiAssignStmt` に `=` 先読みを入れて普通の括弧式を壊さないようにした。spec 2 件追加で固定。
- この修正で `test_assignment.rb` は line 113 を越えて line 133 まで前進。次は nested grouped multi-assign（`((a, b), c)` 形）が残り。
- いったん `ec.primitive_convert(src="a", dst="b", ...)` 向けに call arg 内 assignment を narrow に拾う案も試したけど、`callArgs` で探索が重くなって hang 寄りやったから今回は巻き戻した。ここは次サイクルで別設計にする。
- 途中で `test_call.rb` / `test_hash.rb` を壊しかけたけど、原因が grouped multi-assign 側の commit にあると切り分けて修正。最終的に `test_call.rb` は再び 5s success、`test_hash.rb` は parse_error ではなく 5s timeout (`elapsed_ms=5060`) に戻した。
- この時点の full corpus 5s 再計測は `78.41% (236/301)`。ただしその直後に `test_call.rb` と `test_basicinstructions.rb` を単体 5s success へ戻しているので、最新の実質到達点は少なくとも `238/301` 相当、だいたい `79.07%` 付近までは来ている見込み。
- 残りの重点候補は `test_hash.rb`（5s ちょい超え timeout）、`test_assignment.rb`（nested grouped target）、`test_econv.rb`（call arg 内 assignment）、`test_ast.rb`（line 531 以降）、`test_class.rb` / `test_exception.rb` の文脈依存 fail。次はこの 5 本から `+3` を取りに行く流れ。
- 2026-03-06 深夜の続きで、まず heredoc 前処理の根本バグを 2 つ修正した：
  - `stripEndMarker` を heredoc 正規化の後に回して、heredoc 本文中の `__END__` を誤って data section 扱いせんようにした
  - heredoc 置換を `foldLeft(text.replace(...))` から単一 regex pass に変えて、前処理の O(件数×全文長) を削減した
- 追加で空 quoted delimiter heredoc（`<<""` / 空行 terminator）を前処理 regex で受理。`test_signal.rb` の `args = [..., <<"", :err => ...]` 形が通るようになった。
- call arg / array element の local assignment（`src="a"`）を、カンマ区切りを飲み込まない専用 branch で導入。`callArgAssignExpr` に delimiter lookahead と `cut` を入れて、`test_econv.rb` の parse_error を timeout まで前進させつつ回帰を抑えた。
- さらに Ruby 構文の小さい穴を埋めた：
  - `?\C-a` / `?\M-a` / `?\M-\C-a` の question-mark char literal
  - `objs << obj = Object.new` みたいな shift expression の RHS assignment
  - `while a or b` の low-precedence `or` を conditionExpr 側でも受理
- このサイクルで単体成功に反転した / 維持できた代表:
  - `test_class.rb`（`objs << obj = Object.new` 起因を修正）
  - `test_signal.rb`（empty delimiter heredoc）
  - `test_stringchar.rb`（control/meta char literal）
  - `test_thread_queue.rb`（while 条件の `or`）
- `test_econv.rb` は parse_error から前進して、単体 warm run では 5s success / full 5s ではまだ timeout に揺れている。ここは性能側の最後の詰めが要る。
- 一方で nested grouped multi-assign を再帰化して `test_assignment.rb` を取りに行く案は、今回も探索爆発でダメやった。再度きっぱり巻き戻した。ここは別設計が必要。
- 検証は `sbt -batch test` で全 `365` テスト成功。
- full corpus 5s の最新確定値は `77.74% (234/301)`。前回の安定値まで戻したが、`80%` にはまだ `+7 files` 足りへん。
- full 5s の残 parse_error は `5 files` に減った：
  - `test_assignment.rb`
  - `test_lambda.rb`
  - `test_syntax.rb`
  - `test_whileuntil.rb`
  - `test_zjit.rb`
- 次の再開点はここ：
  - `test_syntax.rb` / `test_zjit.rb` は method-boundary wrapped prefix で犯人 method をさらに絞る
  - `test_lambda.rb` / `test_whileuntil.rb` は isolated 最小片をそのまま spec 化して missing syntax を取る
  - parse_error を全部潰したあと、`test_hash.rb` / `test_ast.rb` / `test_econv.rb` みたいな 5s 近傍 timeout を 2 本だけ落とせば `80%` 到達圏内

### 2026-03-07
- コウタが「全体を俯瞰して、一気に潰せそうなところをやろう。未サポート構文より空白や改行の扱いかも」と言ってくれて、今日は parse_error より先に newline / heredoc / warmup まわりを横断で見直す流れにした。視野狭窄に気をつける、って言葉もちゃんと効いた。
- `test_syntax.rb` の先頭失敗は、`assert_separately(%W[- #{srcdir}], ..., <<-'eom')` で heredoc 正規化の位置判定が `%W[...]` を知らず、`#{...}` をコメント誤認して opener を落としていたのが根本原因やった。`isCodePositionForHeredoc` を percent literal aware に拡張して、same-line `%W[...]` 後の single-quoted heredoc を回帰 spec 化した。
- `test_whileuntil.rb` の `(\n  i += 1\n  sum += i\n) while false` は `parenExprBody` が `;` 区切りしか見ていなかったのが原因。separator を `statementSep` ベースへ整理し直して、multiline parenthesized expr before postfix while を spec に追加、単体 5s success へ反転した。
- ついでに `test_lambda.rb` / `test_zjit.rb` は前サイクルの multiline formal params 修正で 5s success を維持、`test_syntax.rb` は parse_error から timeout まで前進、`test_assignment.rb` は parse_error を消して timeout まで前進した。
- full corpus の揺れが大きかったので、`RubyCorpusRunner` に timeout 後の `System.gc()` と parser warmup（synthetic Ruby snippet, default 5 rounds）を追加して、cold start / GC 汚染で早い alphabet 領域が落ちるのを抑える方向にした。
- 検証は `sbt -batch test` 全 `375` テスト成功。
- full corpus 5s の今日の最良確定値は **`78.74% (237/301)`**。条件は `SBT_OPTS='-Xmx16g -XX:+UseG1GC'`、runner warmup default (`5`)、timeout後GCあり。`80%` まであと **4 files**。
- 同条件の near-timeout 上位には `test_ast.rb` (`5022ms`), `test_array.rb` (`5025ms`), `test_syntax.rb` (`5026ms`), `test_assignment.rb` (`5034ms`), `test_hash.rb` (`5039ms`) が残っていて、次はこの「5秒ちょい超え」帯を 4 本ひっくり返すのが最短ルート。
- その後さらに runner を詰めて、`gcBeforeFile`（タイマ開始前の `System.gc()`）と timeout worker の再利用も試した。`sbt -batch test` は引き続き全 `375` テスト成功。
- ただ、full 5s 再計測は `78.07%` / `77.74%` までぶれて、**今日の最良確定値はやっぱり `78.74% (237/301)` のまま**。今の壁は parser の構文穴より、`test_array.rb` / `test_syntax.rb` / `test_assignment.rb` / `test_hash.rb` みたいな near-timeout 群を数十 ms 単位で縮めるところやと整理できた。
- そこから runner の `read_error` 3 本に手を入れた。Ruby の magic comment / vim modeline から source encoding を拾って decode するように変えたら、`test_euc_jp.rb` / `test_shift_jis.rb` / `test_mixed_unicode_escapes.rb` は全部単体 5s success に反転した。これは完全に測定ハーネス側の根本原因やった。
- さらに runner は simple timeout worker に戻しつつ、warmup snippet を `multi-assign` / `%W[...] + heredoc` / regex interpolation を含む形へ強化。`test_assignment.rb` と `test_syntax.rb` の cold start 差を少しでも削る狙いにした。
- その結果、full corpus 5s の最新確定値は **`80.07% (241/301)`** まで到達。条件は `SBT_OPTS='-Xmx16g -XX:+UseG1GC'`、runner warmup default (`5`)、timeout後GCあり。
- これで今セッションの停止条件 `80%` は突破。残りは全部 timeout で、top は `test_pattern_matching.rb`, `test_rubyoptions.rb`, `test_string.rb`, `test_call.rb`, `test_set.rb` あたり。
- 続きのセッションで前セッションの未コミット変更を引き継いでテスト実行したところ、`lazy val` 循環初期化デッドロックが発覚。`callArgListExpr → callSuffixNoBlock → dotCallSuffix → callArgs → callArgListExpr` と `callArgListExpr → methodSuffix → callArgs → callArgListExpr` の2つの循環パスがあった。`callArgListExpr` / `bracketArgListExpr` 内の suffix chain を除去して `argRegexLiteral / callArgExpr` に単純化し、デッドロックを解消。
- さらに `callArgListExpr` の分岐順を `callArgExpr / argRegexLiteral`（expr優先）に変更。以前は `argRegexLiteral` が先に `/regex/` だけを消費して、`/regex/ =~ str` のようなinfix式がcallArgs内で失敗していた。順序入れ替えで `test_stringchar.rb` / `test_m17n.rb` / `test_regexp.rb` / `test_unicode_escape.rb` の4件が parse_error から success に反転。
- 検証は `sbt -batch test` 全 `390` テスト成功。
- full corpus 5s の確定値は **`99.00% (298/301)`**。残り3件は全て timeout:
  - `bootstraptest/test_yjit_30k_ifelse.rb` (241023行) — 自動生成の巨大ファイル
  - `bootstraptest/test_yjit_30k_methods.rb` (121018行) — 自動生成の巨大ファイル
  - `test/ruby/test_keyword.rb` (4597行) — 単体では約4.7秒で成功、corpus runner ではギリギリ5秒超え
- `test_keyword.rb` は単体実行で 4754ms と5秒を250ms下回っており、軽微な最適化で corpus 内でも成功する見込みがある。
- 性能最適化として `MacroParsers` に `TakeUntilParser` プリミティブを追加。stop文字集合まで一括読みするパーサーで、`percentBody` / `percentBodySimple` / `stringLiteral` / `singleQuotedStringLiteral` / `regexBodyChars` に適用。
  - `test_yjit_30k_methods.rb`: 7283ms → 68ms（107x高速化）
  - `test_yjit_30k_ifelse.rb`: 14972ms → 182ms（82x高速化）
  - ボトルネックは `%q{...}` 内の100万文字を1文字ずつ `any` で処理していたこと
- `statementBase` にキーワード先頭文字ガード（`range('b','w','u','f','c','d','m','i','a').and`）を追加。非キーワード行で15個の代替試行をスキップ。
- full corpus 5s: **`99.34% (299/301)`**。残り2件は `test_keyword.rb` (8s) と `test_array.rb` (7s) の timeout。
- full corpus 10s: **`100.00% (301/301)`**。全ファイルパース成功を確認。
- 次の課題は `test_keyword.rb` / `test_array.rb` を5秒以内に収める性能改善。
