# 引き継ぎ: 素の PEG で二値 PAL を書く（2026-09-07 時点）

宛先: 次に続ける人（Codex / 別セッション）。このファイルだけ読めば再開できるように書く。

## 2026-09-07: 全体PEGの未加工入力79例が一致

全体生成は完了した。原文法 `/tmp/pal-window-original.peg` は
59,169,304規則 / 2,118,673,778 bytes。汎用Rust PEG実行器に未加工の
`""`, `a`, `b`, `ab`, `aa`, `aba`, `abba`, `abab` を直接渡し、全8例が
回文oracleと一致した。`--repeat` は使っていない。
ログは `/tmp/pal-window-raw-smoke.log`。これは初めての全体の実マッチである。

`rust-peg/src/bin/compact-scaffold-peg.rs` にPythonと同じ通常PEGの圧縮を
実装した。89万規則の部品で出力SHAがPython版と完全一致し、8例の意味も一致。
全体では200.577秒で **13,248,052規則 / 672,208,000 bytes** になった。
出力は `/tmp/pal-window-fast.peg`、SHA-256は
`ab891da29e1959360f247e5b9b3f5d3dfec336da1f13376aa3e6b935bf3e211f`。
共有式と再帰境界を残し、入力長を限定せずに規則を短縮している。

`verify_window_pal.py` で圧縮版の79例（全二進語の長さ5まで、長さ33
までの選択例、非二進文字）がすべて一致した。29受理・50拒否、入力変換なし。
ログは `/tmp/pal-window-fast-verify.log`、同名JSONは `status=passed`。
`generated/window-pal-verification.{json,log}` にも完了記録を保存した。
構成・再現方法・有限テストと任意長の証明の区別は `PLAIN_PAL_ARTIFACT.md`。
Rustの9単体テスト、部品の圧縮前後照合、`sbt test` が成功した（Scalaはcache利用）。
native checkpoint `/tmp/pal-window-original.sca` も完成済みなので再生成不要。
以下の「再生成中」「PEG未出力」は前段の記録。

## 2026-09-07 未明: 構築後の最適化がメモリ不足、直接出力へ切替

全4ワーカーの固定展開とsource型検査は一度完了し、41,298 Boolean欄 /
4,291 pointer欄になった。ただし、その後の定数畳込みで22GiBの上限に達し、
rewrite memoの追加中にMemoryErrorになった。旧実行は終了済みで、PEGも
全体sourceのcheckpointも保存されなかった。元入力のPAL実行はまだ未検証。

現在は `generate_window_pal.py /tmp/pal-window-original.peg --checkpoint
/tmp/pal-window-original.sca --skip-optimize` と同じ経路で再生成している。
構築・検査したsourceを先にデータ形式で保存し、定数畳込みを省いて同じ式を
普通PEGへ直接出す。再開は `--resume /tmp/pal-window-original.sca`。
保存したsourceはPEGではない。PEGは出力完了後にRustで未加工入力を試す。

`compact_scaffold_peg.py --short-names --inline-private` は単一参照のE規則を
括弧付きで展開し、再帰するS/B/Pと共有E規則を保つ。深さ16で展開を止めた
参照先は規則として残すので、入力長の制限にはならない。3命令GS部品では
891,595規則 / 30,427,106 bytesから **74,549規則 / 10,118,379 bytes** へ縮み、
Rustの8例がすべて一致。`/tmp/window-gs-live-3-private-rust.log` に記録した。
圧縮3テスト、およびキャッシュ解放修正を含む変換器10テストが成功した。

`scaffold_optimize.py` は到達性のseenと書き換えmemoを次段のScaffold検査前に
解放する。ただし旧実行の失敗はrewrite中なので、現在の全体生成では省く。
`symbolic_sca2peg.iter_rules` は一時表へ整数IDと式参照だけを保持する。
GCを生成・変換中は止め、構築cycleの解放時に明示実行する。3命令GS部品は
出力SHA-256が以前と完全一致し、全工程が107秒から70秒、最大メモリは
約783MBから469MBになった。`test_scaffold_window_pal_clock.py` は人工worker
で期限直前の完了を与え、実際の段階回路の切替・受け渡しを130文字分検証した。
これはGSや全体PALの実マッチの代わりにはしない。
実物が出たらまず直接実行し、次に上の圧縮を適用して
`verify_window_pal.py FILE --runner RUNNER --log LOG` で検証する。

## 2026-09-06 深夜: 未加工入力の全体sourceを生成中

**最終PAL PEGはまだ検証を終えていない。** 新しい全体sourceは
`scaffold_window_pal.py`、再現用generatorは `generate_window_pal.py`。
`gs_batch_clock.DEFAULT_BATCH` のmatcher 512、flags 1,024命令を、実際の
入力1文字の有限回路に直接入れる。入力反復やwork文字を要求するsourceではない。
現在は実サイズとPEG実行結果の確認が残っているため、完成とは扱わない。

`scaffold_window_registers.py` / `scaffold_window_live.py` が、全head-pair保存を
liveな符号付き距離registerの再利用へ置き換えた。命令中は旧商のorigin・向き・
有限low wordだけ更新し、heapの読み書きはround境界へ寄せる。
`scaffold_rom.py` は固定batch移動とregister転送もROMへ落とせる。
`scaffold_window_gs_live.py` の3命令burstは891,595規則 / 30,427,106 bytes。
旧全pair版の2命令83,523,719 bytesより小さく、Rustの8例はすべて一致した。
これらは `word!dots` による部品試験で、PALそのものの証拠ではない。

`scaffold_window_workers.py` は両GS workerを元入力のblock streamへ接続する。
flagsの区間先頭はstage birth時のraw endをsnapshotし、全headはその後も
元入力全体を受け取り続ける。凍結区間はheadの境界で制限するため、streamの
リセットや人工文字を挟まない。逆向きheadはboundary cursorの直前を読む。
低quantumのnative照合で、全PC・live距離・flag stackがobserverに一致した。

二viewのflag探索は次のEndがLower未満なら終了する。batch版は492状態、
matcherは294状態。`GS_LOCAL_CLOCK.md` にbatch countingを追加した。
全体の別実装observerで、長さ8までの全二進語および長い/random入力の全prefix、
flag期限、正のmatchの到着時点が512/1,024の固定rateで一致した。

新テスト: register 1件、GS live 2件、worker 2件、batch clock 1件、batch PAL 1件。
既存ROM+dual flags 3件も成功。旧WindowGS 1件も275秒で終了し成功した。
Scalaの `sbt test` は成功（cache 100%、Scalaテスト再実行0件）。
以下は前段の経緯。巨大なphase付きround packingへ戻らないこと。

## 2026-09-06 夜: 現在の再開点

**未加工の入力を認識するPAL PEGは、まだ完成していない。** 古いGalil全展開や
`pack_round_lazy` の巨大ラウンドへ戻らない。後者は現行PAL sourceでも2段約14 MB、
4段約56 MBと増えた。現在の変更は `WINDOW_ROUNDS.md` / `GS_LOCAL_CLOCK.md`。

`scaffold_delayed_pal.py` は二段階PALの全体controllerまで局所SCAに接続済み。
旧mirror版は16,423内部step/文字で、生成した普通PEGをRustの固定入力反復で
長さ16までの選択例に実行して一致した。これは入力変換付きの検証である。
新しい既定版は `gs_dual_flags.py` の順向き/逆向き二viewを使い、8,231step/文字。
前処理長を `2b+1` から `b` に減らし、native 4,158語・12,474区間と局所SCAで検証。
新全体microstep PEGは72,442規則 / 2,761,662 bytes。入力反復8,231付きで
15例がRust実行と回文oracleに一致した（これも生入力の最終成果ではない）。

巨大なphase付きセルを除く部品を実装し、それぞれ普通PEGで検証した:

- `scaffold_window_counter.py`: 開始時の商と有限の増減量を保持し、最後に一度だけ正規化。
- `scaffold_window_positions.py`: 旧head origin + 有限変位で命令中の全headを表し、距離保存は最後だけ。
- `scaffold_window_stream.py`: 元入力を固定長blockで保持し、各旧headの隣接3blockを一度準備。命令中はorigin/offsetだけ更新。Rustの未展開入力106例が一致。
- `scaffold_flag_packets.py`: 一文字の処理中に出たflagを一packetへ格納。コピーとpopも検証済み。
- `scaffold_rom.py`: 有限命令表を共有Boolean DAGへ変換。GSの全746行を検証した。

`scaffold_window_gs.py` はこれらをGS命令burstへ接続する新しい実験。
`word!` の後の各 `.` で複数命令を実行し、通常の一文字SCAへ直接落とす。
この接続版は検証中であり、全体PALへの統合と最終PEG生成はまだ残る。
旧GS head/flag/matcher部品やそのテストを消さず、比較対象として使うこと。

Scala要件の `env XDG_RUNTIME_DIR=/tmp/macro-peg-pal-runtime sbt --server --batch test`
は成功した（cache利用、Scala再実行0件）。新しいPython部品の検証結果は
`WINDOW_ROUNDS.md` を参照。以下の21時台の節は過去の段階の記録。

## 2026-09-06 21時台: 二段階PALと固定ヘッドGS

現在の構成は `DELAYED_PAL.md` / `GS_OVERLAP.md`。巨大なGalil全展開へ戻らない。
`delayed_pal.py` は二つの重なる段階だけを用い、全prefixのPAL回答を出すindexed
reference。33,237入力の全prefixとindexed期限検査が成功した。PAL PEGは未完成。

GS border (`gs_heads.py`)、逐次matcher (`gs_match_heads.py`)、回文prefix flags
(`gs_flag_heads.py`) は、全分岐を閉じた有限ヘッド命令表になった。単位命令数は
746 / 457 / 768状態。制御側には入力長に応じて増える整数やtableがない。
`scaffold_head_distances.py` が固定ヘッド間の距離を持ち、pointer identityなしで
順序/一致を判定する。`scaffold_gs_heads.py` はborder表全体を実SCAへ変換。

`/tmp/gs-heads.peg` は75,818規則 / 2,445,841 bytes。Rust汎用PEG実行器で8例一致。
ただし `word!` に読み込み/計算用の `.` を付けた逆順traceを認識する部品テストで、
生入力PALではない。GS_OVERLAPのindexed clockを局所head clockと混同しない。
局所snapshot/mirror view、growing-input heads、段階controller、局所clockと
一文字SCAへのコンパクトな接続が残る。

直近の解析では、ヘッド距離の後向きlivenessを有限命令表上で求めると、borderは
91全ペア中27種類、最大同時live 18本。matcherは28種類/18本、flagsは37種類/22本。
entryに必要な距離もborderはOrigin–OriginalEndだけ。これは現在の全ペア保存を
小さな有限register bankへ置き換える具体的な削減案で、まだ未実装。

検証は `test_gs*`、`test_delayed_pal`、`test_scaffold_head_distances`、
`test_scaffold_gs_heads`。Scala要件の `sbt test` も終了前に再実行すること。

## 2026-09-06 13:36 方針訂正: コンパクトな構成へ戻る

続行で、任意長の中点を返す普通PEGを構成した。
`midpoint_peg.py` → `generated/midpoint.peg` は9,949規則 / 269,633 bytes。
`HalfFloor` は残り長のfloor半分、`HalfCeil` はceil半分だけ消費する。
1文字1遷移の小さなFIFO構成で、巨大な全体展開は使わない。
[MIDPOINT.md](MIDPOINT.md) に規則の契約と長さに依存しない理由を記述した。
これは中点部品であり、PAL判定は未完成。`S` は `ab` も受理する。
先輩の「GB級はおかしい」は翻訳設計を疑う判断として受け止める。
一般的なサイズ保証の但し書きを繰り返して、この論点から逸れないこと。

先輩が「筋が悪い。もし、本当にPEGでpal表現できるならもっとそれはコンパクトに
なるはず」と指摘。巨大な全展開の続行と微小なメモリ最適化を主軸から外す。
PEG/SCA同値性はGalilの特定算法を使う必然性を示さない。親文書の「それ以外では
書けない」という記述を訂正した。コンパクトな直接PEG、またはPAL向けの小さな
SCAの構成を検討する。小さい文法の存在はまだ示しておらず、既存探索の失敗も
その不存在を意味しない。以降の巨大生成を優先する過去の記述より本節を優先する。

## 2026-09-06 更新: 計画の実装と全体生成の規模

最新の明示依頼は全体計画への **Implement the plan.**。最終PAL PEGは未生成。
以下の過去の未実装表示に優先して、現状は次の通り。

- `galil_contracts.py` がmain入口、DP結果、中心移動、replay復帰、chain shift、
  各prefix出力の外部監査を実装。数値座標やoracleは監査側だけで使う。
- `fpp_cost.py` / `FPP_COST.md` に命令表と資源計数からの境界を実装。
  marked FPPは `296m+190`、DPは `327m+224`。有限例の最大値ではない。
- `galil_clock.py` / `GALIL_CLOCK.md` が量子qから探索clockと予測可能性定数を導く。
  q=64ではM=256、c=3169、サービス量2c=6338。任意長のsource契約はなお
  全体論証の前提で、監査テストだけで証明済みとはしない。
- `galil_realtime.py`、`scaffold_event_buffer.py`、`scaffold_round.py` に実時間入口、
  有限FIFO、複数遷移の一ノード化を実装。`build_online` はread/work/outputの
  flagsもloweringする。[PACKED_ROUNDS.md](PACKED_ROUNDS.md) を参照。
- 遅延出力を持つ別言語の小例は、元入力の普通PEGに出力しRustで11例一致。
  PALの実マッチではない。全体用の生成CLIは `generate_online_peg.py`。
- 初回の全体生成はsource構築を通過（33,874 labels / 7,412 pointers）したが、
  FIFO接続中に300秒のホストtimeoutで停止。最終PEGはない。
- `Expr` をdict付きtuple subclassからslot付き不変オブジェクトに変更。
  既存2生成例のSHA256が一致し、関連22 Pythonテスト成功。現在は共有セルと
  無効分岐の展開省略でコンパイラの規模を下げている。

共有セルを適用した全体sourceは7,133 labels / 1,686 pointersまで減った。
FIFO後は7,429 / 1,791。元の機械との全体状態比較も成功したが、600秒の生成は
whole input round中に停止した。現行の全展開は194,683,368 labelsと
11,353,149 pointersを宣言する計算で、通常の式ルールはさらに必要になる。
不要欄だけの構築、定数伝播、二分木状の合成も小例で測ったが、全体の規模を
解決する削減にはならなかった。これらを成功したPAL生成として扱わない。

再構築を繰り返さずに済むよう、`scaffold_artifact.py` にデータだけの有限DAG保存を
実装。生成済みFIFOは12,732,790式 / 273,939,743 bytes。
`/home/mizushima/.codex/artifacts/pal-peg/pal-derived-fifo.sca` と
`/tmp/pal-derived-fifo.sca` にある。PEGではなく変換途中の有限機械。
source fingerprintとq/共有セル設定が違う場合は再利用を拒否する。
`generate_online_peg.py --wrapper-cache ... --wrapper-only` で前段保存、
`--wrapper-only` を外すとノード統合から続ける。保存に445秒、peak RSS約6.4GiB。
再現コマンドとhashは `PACKED_ROUNDS.md`。元入力PALの最終PEGはまだない。

保存済みFIFOからの再実行は、時間枠を1,800秒にしても、whole input roundで
**16GiBのコンパイル上限によるMemoryError**。driver記録415秒、cleanup込み451秒、
peak RSS 16,615,240 KiB。最終PEGファイルは存在しない。
失敗reportと最終検証logも同じartifactディレクトリへ保存した。
最終の関連41 Pythonテストは385秒で全件成功。`sbt test` もキャッシュ利用で成功
（Scala再実行0件）。すべての生成・検証プロセスは終了している。
現在地は計画の第3段階の全体生成であり、第4段階のraw PAL実マッチには未到達。

現在の焦点は第3→4段階の全体生成。旧2048展開系には戻らない。
証明を生成前の追加関門にはしない。全体PEGが出たら元入力で実マッチし、
その後に任意長の議論と再現手順を仕上げる。commit/pushはまだ行っていない。

## 現行方針: 変換戦略を組み直し、一段ずつ進める

先輩の最新指示は「改めて変換戦略をたてて、それを一歩一歩すすめよう。
既に実装した部品でももちろん使えるところはつかえばいい」。
**まず [TRANSLATION_STRATEGY.md](TRANSLATION_STRATEGY.md) を読む。**
これが以下の過去の「次の作業」より優先する。

1. 原論文のオンライン算法を、入力・内部計算・出力の明示された機械にする。
2. その命令数から得る予測可能性の境界を使い、buffer付きで実時間化する。
3. 一文字分の内部遷移を有限slotへまとめ、一文字一ノードのSCAにする。
4. 既存SCA→PEG変換器で出し、元の入力をRustで直接マッチする。

Galil原論文§2はオンライン算法と実時間化を区別している。現行の経験的
budgetをこの変換の代わりにしない。内部MATCH_DELAY/FPP_QUANTUMも、
試した入力で動いたことと時間境界が得られたことを区別する。

最初のsource境界確認として、`run(word, budget=None)` の空文字/a/ab/aba/abba/abab
全prefix出力がoracleと一致。記録は `/tmp/pal-online-boundary-audit.json`。
これはオンラインsourceの六例だけの結果で、実時間性やPAL PEGの結果ではない。
続きで [ONLINE_EVENTS.md](ONLINE_EVENTS.md) の入力/出力境界を実装した。
`OnlineGalil.read(a)` と文字を受け取らない `work()` が、出力イベントと
input-readyを別々に返す。報告後のgap処理を次の入力なしで進められる。
当初の「emitすると次の入力待ちに戻る」という仕様は訂正した。
普通の継続で選ぶ中心は最長の真の回文接尾辞に対応することを説明し、
長さ5まで全二進列のprefix出力と中心/左右head位置、およびchain継続等の
新規5テストが成功。旧step/runは旧circuitとの比較用に保持した。
旧controller7件と全体circuit比較2件も成功。`sbt test` は終了0、キャッシュ利用で
実行0件。新sourceの5件は約73秒、旧系の9件は約162秒で実行した。
次はmain(C,r)/move/main1の契約とchain条件の照合。実時間scheduler、
複数tickの一ノード化、およびsource protocol flagsのloweringはまだ未実装。
既存の巨大PEGへ逆展開を適用したり、2048を調整する作業には戻らない。

## 過去の結果: 実PEG生成とRust上のマッチ（2026-09-05 23:58）

### 2026-09-06 00:10: 入力機械の取り違えを訂正

先輩の指摘: 形式言語のtranslationへ、元機械から導出されていないmagic numberを
持ち込むのはおかしい。**2048を調整・増量して解決した扱いにしない。**
一般SCA→PEG変換器には予算引数はない。入力側の `scaffold_circuit_galil.build`
が内部stepをSCAの一遷移へ写し、arrival.phaseにより2048回に一度しか入力を
取り込まない機械を作っていた。元の `run` は一文字ごとに内部stepを複数回呼ぶ。
同じQ8/read-block設定で元のrun("ab",budget=2048)は[1,0]を返す一方、
内部stepを入力一文字ごとに一回だけ呼ぶとbのnew_input=Falseで[1,1]を返す。
今回のab誤受理はSCA→PEG以前に存在する。変換元の形式的機械の一遷移と
SCAへの対応を明示し直すことが先であり、経験的予算はその代わりにならない。

先輩の最新方針は **候補PEGの生成・実マッチを先に、証明はその後**。
証明や部品テストを出力の関門にしない。下記の古いlowering未着手という記述は更新済み。
`scaffold_circuit_galil.py` に全体の有限Boolean/pointer式へのloweringがある。
`generate_galil_peg.py` で実際の普通PEGを出せる。

- quantum=64, coarse=True の生成は19GiBの上限でMemoryError。
- quantum=8, coarse=True, match_delay=256, budget=2048 の全体PEGは
  `/tmp/pal-galil-q8-expanded.peg`（4,613,332規則、154,783,432 bytes）。
- StackPool.scalarの相互排他的タグ選択を積和形にし、内部不変条件モニタを
  `--omit-invariant-monitors` で外した候補は
  `/tmp/pal-galil-q8-compact-expanded.peg`（3,314,086規則、120,773,040 bytes）。
  これはデバッグモニタを外す実験設定。デフォルトのbuildはモニタを維持する。
- `rust-peg/` は標準ライブラリのみの普通PEG evaluator。
  四つのRustテストと、既存8生成文法428例のPythonとの照合に成功。
  実行ファイルは `/tmp/macro-peg-rust-target/release/plain-peg-runner`。
- 新候補について `--repeat 2048` で14例を実マッチし、全て回文oracleと一致。
  記録は `/tmp/pal-rust-matches.tsv` と `/tmp/pal-rust-matches.json`。
  a=0.119s, aa=1.560s, aba=2.104s, abba=4.322s、ab/abab/abaab/abaaabを拒否。
  abbbba/abaabaも受理。読み込み約5.18s。これは任意長正しさの証明ではない。

**固定展開は長さ制限ではないが、外部変換である。最終PAL PEGではない。**
23:59に同じ121MBのPEGへ展開なしで入力し、空文字/a/ab/aba/abba/ababを全て受理した。
したがってこの具体的なPEGは、素の入力上ではPALを認識していないことが実測で確定。
PEGとしては既に入力を判定する。「途中なので認識できない」という説明は誤りで、
違うのは認識言語である。先輩の指摘を受けて明確に訂正した。
今は各文字を2048個にしてからPEGへ渡している。`phase_peg.inverse_repeat`
による外部展開の除去はまだ大規模候補には適用していない。
現在の逆変換は再帰的な登録、全位相対の列挙など、大規模化の問題がある。
この大規模候補への逆変換を次の本題とした方針は、上記の戦略再構築で撤回した。

生成器には式共有と逐次出力を追加。Python PEGローダの全文コピーを除去し、
`peg_file.py` の規則の遅延読込みもあるが、巨大例の評価はRustを使う。
`sbt test` は環境変数 `XDG_RUNTIME_DIR=/tmp/macro-peg-pal-runtime` が必要で、
直近はキャッシュヒットで0テスト実行・終了0。新規Rust/Python検証と混同しない。

## 最新: 全体chain接続とSCA出力インターフェース

`SCA_GALIL.md` を先に読む。`scaffold_galil.py` / `scaffold_chain.py` が実DP/FPP、
周期確認、実中心移動、nonchain後のreplayを接続。73 Pythonテストが117秒で成功。
固定2048 ticks/letterの候補はあるが、任意長保証の時間会計はまだ検証途中。
DP量子64命令あたり各テープ最大32移動は全CFG経路上の静的解析で確認済み。

`symbolic_sca2peg.py` は有限Boolean式と条件付きpointer式から直接普通のPEGを出力。
`generate_scaffold_examples.py` は35規則607bytesの区切り付き回文例を生成する。
新規5 Pythonテスト成功（長さ6まで全列挙、向き、null分岐、phase逆変換など）。
これはGalilのPython制御全体を変換したものではない。そのloweringが次の接続点。

先輩の最新指示: 「メタ認知を意識して俯瞰しつつな」。部品やテストの増加と、
任意長の時間保証・全体PEG出力という二つの核心の解消を混同しない。
次の作業がどの未証明事項を消すかを明示する。最終PAL PEGは未完成。

## 最新接続: SCA上の全体オンライン制御と段階DP探索

まず `SCA_ONLINE_CONTROL.md` を読む。`scaffold_pal.py` が照合から有限FPP呼出し、
候補選択、入力head移動、scratch再利用まで全prefixの答えをつないだ。
一命令の静的境界は391 pointer reads / 396 fields。ただし各入力後に仕事をdrainする
baselineであり実時間PALではない。`aa`, budget=3 は `[1,0]` になって陽性を落とす。

`scaffold_search.py` は実DP命令、単項span/debt、match半径ell/4での待ち合わせと
窓倍増を接続。締切ちょうどの段階終了に余分な待ちが必要だった不具合を回帰から修正。
十分遅いmatch clockとmain(C,r)の入口条件は依然caller側の前提で、全体上限は未証明。
`scaffold_places.py` は入力をpaddingせずletter/gapを有限phaseで表し、DPへ接続済み。
`scaffold_program.py` は名前空間とprivate tape root resetを備える。

全66 Pythonテスト成功、独立最終レビュー指摘なし。sbt testも成功したが今回はcachedの0件。
直近の実Scala全件実行は下記646件。次は半周期準備/right-dp/chain維持、実際のcenter移動と
再開/main1、全体実時間境界、そのSCAから素PEGへの出力。最終PAL PEGはまだ未完成。

追加指示: 先輩が `Later, use $token-sieve:token-sieve` と指定した。
`/home/mizushima/.codex/plugins/cache/token-sieve/token-sieve/0.1.0/skills/token-sieve/SKILL.md`
を読み、後段の大きい出力は同pluginの `scripts/sieve.py` で全文を残して絞る。
短い出力は直接読む。既存66件ログのsummarize実行まで確認済み。

## 更新 2026-09-05: §3 の offline FPP に具体的な実装ができた

- 入力アクセス側は `SCA_INPUT_HEADS.md` / `scaffold_input.py` に新しい接続候補。
  persistent stackと実時間queueでreadonly入力headを複製・前後移動できる。
  3head10,000stepと独立4head15,000step・SELF複製2,000stepが成功。
  配布段階の異なるcopy・同一arrivalの二重appendを拒否する。全53 Pythonテスト成功。
  これはSCA上の部品で、既存TM→PEG出力器にそのまま渡せる完成機械ではない。

- さらに `MOVE_CENTER.md` / `move_center_finite.py`: nonchain moveの候補選択、実際の中心への
  WINDOW移動、全scratch消去を734状態13テープへ接続。8,690区間の範囲外アクセス禁止検査、
  同じscratchの再利用、JSON直接実行、独立レビューが通った。main1再開・他head移動はまだ別途必要。

**最新の出力側の接続点は `PHASE_PEG.md`、探索部品は `CHAIN_AND_COMPILER.md` /
`FPP_CALLING_CONVENTION.md`。以下の「残る一点」は旧状況。**

- `phase_peg.py` が固定k倍の仮想文字列展開を逆変換し、元の入力のまま複数microstepを
  実行する普通のPEGを出力する。k=4で一文字内のpush/pop、k=2で文字間のpush/popが
  Scala実Interpreterで通った。戻りphaseを分離し、優先選択・反復のcommit条件を保つ。
  到達不能phaseの枝を有限解析で除去し、文法検査自体は変更・迂回していない。
- Python43件、新Scala3件、最後の全646 Scalaテストが成功。独立再レビューも指摘なし。
  開始規則はInterpreterに合わせてSを既定とし、
  明示overrideも可能。再生成は `generate_phase_examples.py`。固定kの有限機械が
  あれば出力側へ接続できるが、Galil全体の実時間機械・その命令上限はまだ未完成。

- さらに最新の接続は `CHAIN_AND_COMPILER.md`。`chain_finite.py` は実DP結果から
  半周期をコピーして往復し、right-dp確認と三者照合を有限制御化した（4,510状態18テープ）。
  中断可能版は6,450状態19テープ。1,080追加ケースと独立レビューが通り、一place最大9命令。
  `outcomes` のrestart/shift/moveはまだ分岐判断であり、実際の中心移動・再開処理ではない。
- `dp_search_reuse.py` を追加テープ・scalar PORTに対応させた。通常到達可能3,885状態の
  中断入口を確認。探索のみの新命令表は1,401状態17テープ（以前の1,458は旧版の記録）。
- `symbolic_tm2peg.py` で全focus組合せの列挙を避ける出力器を追加した。
  19テープのmarked-palindrome例は461規則21,187bytes。これはまだ1入力1遷移のTM用で、
  offline部品や複数microstepをそのまま流せるものではない。Scala実Interpreterの3検査と
  全643テストが成功。immutableな大域文法環境のhashを一度だけ計算するようにし、
  同じsparse検査は約98秒から約1.7秒へ短縮した（環境内での観測）。

- 最終追加 `dp_search_reuse.py`: 倍増探索全体の中断・再利用を **1,458状態・17テープ**へ接続。
  `generated/dp-search-reusable-controller.json` を直接実行できる。窓コピー、倍増、内側の掃除、
  半周期印付けの途中も中断可能。WINDOW/LOWERを元どおり保存し、全scratchを物理消去して
  全ヘッドを入口位置へ戻す（WINDOWだけC）。外側の掃除自体の再中断は対象外。
- 消去印 `erased:_` と原点印の保持で、内側掃除途中の空白穴による取り残しを防ぐ。
  追加の単項DISTANCEテープと有限中断continuationで、窓移動の途中でも位置対応を回復する。
  15,836二値ケース、600ランダム中断、Python27件が成功。独立レビュー指摘なし、追加15,039
  中断点と168通常実行も一致。931到達可能通常状態すべてに中断入口あり。
- 次はmatch待ち合わせと段階境界印、right-dp、chain維持、nonchain move/replay。
  探索全体の中断はできたが、まだoffline線形の部品であり実時間スケジュールではない。

- 最新は `dp_search_finite.py` / `generated/dp-search-controller.json`。
  **898状態・16テープ**で、中心印Cからの局所窓転送、窓長の倍増、内側DPの掃除と再呼出し、
  最小hの単項出力、C-h/C-2h/C-3h/C-4hへの印付けまで接続した。入力窓の逆転・複製に
  ホスト文字列操作を使わない。32,220二値prefix/下限組と1,000長めのplace入力で一致。
- 独立レビュー指摘なし（追加819実行）。中心を19/1009/100009へ移しても同じ1,315命令で
  近傍offset -9〜0だけを読む回帰テストも通る。Python全24件。
- この倍増は **offline即時倍増**。原論文のmatchとの待ち合わせ・段階境界印はまだない。
  外側からの中断・全体再利用は上記17テープwrapperで対応した。内側kernelのcancel mapを、
  窓コピー中や倍増中にそのまま使ってはいけない。

- 最新追加: `fpp_reuse.py` で中断と掃除を有限命令化した。二値FPPは497状態、二値DPは
  608状態、place版DPは702状態。テープ数は増やさず、SOURCE/LOWERを保存し、作業テープを
  全消去して全ヘッドを原点へ戻す。出力を消費後、同じ作業テープで次の呼び出しができる。
- `Program.execution().step()` は一命令だけ実行。job/bootstrapの任意の命令境界で
  `cancel_entries[state]` へ移れる。掃除中の再中断や、任意の汚れたテープは対象外。
  dense-prefix不変条件により掃除は窓長に線形。Galil側の中断方針・スケジュールはまだ未実装。
- 全二値入力長≤6の77,103中断点と、同じ作業テープを使う500連続呼び出しが成功。
  独立レビューは指摘なし（9テープ3,481中断点と旧VM比較381実行も確認）。
  Python回帰は19件。次はGalil窓の局所転送と外側の有限制御へ進む。

- `fpp_subroutine.py`: 264状態・9テープ。素の二値入力から `word # reverse(word)` の
  2コピーを局所移動で準備し、全回文接頭辞を出力テープの0/1として印付けする。
  入力準備・答えの整数化をホストに任せる部分を、この部品では除去した。
  命令表は `generated/fpp-marked-controller.json`、長さ≤16 全131,071入力で一致。
- `dp_finite.py`: Galil の1窓ぶんの double-palindrome 探索。`abs`（sは文字間のplace）で
  373状態・12テープ。下限rは単項入力、答えhも単項出力。2本の印テープを2マス・4マスずつ読み、
  `h>r` かつ長さ `2h+1` と `4h+1` が回文となる最小hを返す。Python集合・座標比較なし。
  命令表は `generated/dp-place-controller.json`。27,304組の窓／下限で一致。
- 追加の独立レビューは全severity指摘なし。全14 Pythonテストで保存JSONの直接実行も確認。
- 元の命令表は **fresh scratch tapes** 前提のoffline部品。再利用時の掃除は上記wrapperで
  対応した。次はGalil窓からの局所転送、doubling／中断方針／背景探索／chain確認／replay
  の有限制御を接続する。
- **重要**: stage3 の `run_realtime` はgeneratorのyield数を数え、`m.tick(h)`のh単位を
  全て数えていない。旧budget512は、この新しい機械の実時間性の根拠にはできない。

- Fischer–Paterson 原論文（MIT MAC TM-41、section 3、pp. 6–11）を取得し、
  Algorithm Y の差分テープを実装した。`delta(i)=1+P(i)-P(i+1)` を単項で保持する。
  失敗関数のランダムアクセスもポインタ同一性比較も使わない。
- `border_machine` は原論文の局所ヘッド版、`single_head_border_machine` は
  append-only な共有テープを2スタックFIFO＋読み手のテープへ分解した7本の独立テープ版。
  各キュー要素は高々一度転送されるので、offline 手続きの線形時間性を保つ。
- `initial_palindromes(w, single_head=True)` で全回文接頭辞も得られる。
  入力の作成・複製は offline setup、返す整数は外側の observer によるヘッド位置の復号。
  アルゴリズム内部で任意整数や座標比較を使うものではない。
- `fpp_finite.py` はその kernel を **188状態・7テープ** の有限制御に落としたもの。
  命令表は `generated/fpp-offline-controller.json`。実行時 callback や可変長 call stack はなく、
  局所移動・読み書き・分岐・observer 用 emit のみ。これも長さ≤16 全131,071文字列で
  全回文接頭辞が一致した。これは **offline FPP の命令表** であり PAL の PEG ではない。
- 再実行: `python -m unittest discover -s docs/notes/palindromes-in-peg -p test_fpp_tape.py`。
  原論文版の FPP は長さ≤16 全131,071文字列、非周期回文の最長境界は長さ≤21 の
  5,456対象で一致。単独ヘッド版の検証結果は `PROGRESS.md` に記録する。
- **PAL の素の PEG は依然未達**。残りは Galil 全体の有限制御への lowering、FPP との接続、
  入力準備・出力マーク・背景探索まで含む実時間の計数、symbolic δ と PEG 出力。
  stage3 の既存 budget=512 を、この新しい機械の定数として流用してはいけない。
- §1 の「それ以外の形は無い」、§4 の「小さい文法は存在しない」は証明されていない。
  有限探索の不成功は、その探索範囲で見つからなかった証拠に限られる。
  今回は具体的な構成が進んだため TM ルートを続ける。
- 原論文 PDF は `/tmp/macro-peg-fischer-paterson.pdf` のみ。Git に入れない。

## 0. ゴールと現状を一行で

- **ゴール**: `PAL = { w ∈ {a,b}* | w = wᴿ }` を記述する**素の PEG**（マクロなし）を、明示的に、実行可能な形で出す。
- **現状**: **未達**。offline FPP は局所ヘッドで実装できたが、Galil の実時間制御と有限遷移への変換、PEG 出力が残る。Python 手続きをそのまま `tm2peg.py` に渡せるわけではない。

## 1. 理論の枠（これは確定、疑わなくてよい）

- LMR: `L ∈ PEG ⟺ Lᴿ ∈ SCA`（scaffolding automaton）。
- Kim–Park 2026 (arXiv:2608.29592, Lean artifact zenodo 22099762): **実時間**多テープ TM → SCA コンパイラ。
- Galil 1978 (JCSS 16(2) 140–157): 実時間多テープ TM で全 initial palindrome を認識。
- よって PAL ∈ PEG。この実時間オンライン検出器からの構成を現在の実装ルートとする。別形式の構成や小さい文法の不存在は証明していない（§4）。

**SCA/PEG で出来ないこと**（設計上の制約、毎回ここに当たる）:
- 位置やポインタの**同一性比較**（`p == q`）は不可。ラベル（有限）だけ見える。
- 「別の場所で測った長さ分だけ進む」は不可（length transfer 無し）。
- ノードは不変。後から既存ノードに子ポインタを書き足すのは不可（eertree が死ぬ理由）。
- 1文字あたり O(1) ポインタホップ（real-time）。O(n log n) は Galil の predictability 会計を壊すので不可。

## 2. 出来てるもの（全部 PR #208 / branch `docs/palindrome-peg-examples`）

| 成果 | 場所 | 検証 |
|---|---|---|
| PAL の Macro PEG（2規則） | `examples/PalindromePegs.scala`, `docs/notes/palindromes-in-peg.md` | 長さ≤16 全131,071 + ランダム400、誤り0 |
| 長さ ≤ N の PAL を厳密に記述する素の PEG（O(N²)） | 同上 `bounded(N)` | 全数 |
| PAL を内外から挟む素の PEG 族 `inner(K)`/`outer(K)` | 同上 | 全数 |
| **実時間 TM → 素の PEG コンパイラ** | `docs/notes/palindromes-in-peg/tm2peg.py` | 正規 / aⁿbⁿ / `{u # uᴿ}` の3機械、macro_peg 本体の Interpreter でも通る（`GeneratedFromTmSpec`） |
| Galil の chain case 用 定数空間周期計算 (Crochemore–Perrin) | `fpp.py: period_if_periodic` | 長さ≤14 全32,766、誤り0 |
| Galil stage 3（chain case）の実時間シミュレーション | `stage3_tm_galil_chain.py` | budget 512 で real-time 確認済み |
| SCA VM + 実時間キュー | `scavm.py`, `scavm_structs.py`, `scavm_pal.py` | — |

Macro PEG（参考）:
```
S = P("") !.;
P(r) = "a" P("a" r) / "b" P("b" r) / [ab] r / r;
```

`tm2peg.py` の使い方: `TM(ntapes, states, initial, accepting, delta, ...)`、`delta[(state, focus_tuple, input_char)] = (state', [(write, move), ...])`、`move ∈ {L,S,R}`。`tm.run(w)` でシミュレーション、`tm.compile()` で PEG 文字列。テープは zipper（push=`""`, unchanged=`. Lt_j`, pop=`. Lt_j . Lt_j`）。**δ はテーブル列挙なので |Σ|^k で爆発する**——Galil 機械を載せるときは symbolic/guarded δ への拡張が要る（未着手）。

## 3. 以前の難所（上の更新で offline 実装に進展あり）

**周期が n/2 を超える回文 W（長さ n）の最長 proper border を、O(n) 機械ステップ（ヘッド・マーク・有限制御のみ、乱アクセス無し）で求める。**

Galil の nonchain case が要求する値。chain case（周期 ≤ n/2）は `period_if_periodic` で済んでる。

### 3a. 潰した道（同じ穴に落ちないために）

| 道 | 潰れた理由 | 記録 |
|---|---|---|
| KMP 失敗関数 | カーソルが同一セルに `next` と `fail` を要求。左→右は fail だけ、右→左は next だけ書ける。zipper にするとジャンプ停止判定がポインタ比較になる。**fail[] へのランダムアクセスそのもの** | `fpp.py` docstring |
| 単項ギャップのスタックで fail を表現 | 一致時に必要な連鎖 `[k+1, fail[k+1], …]` が手元の `[k, fail[k], …]` から導けない | PROGRESS.md |
| eertree | 既存ノードへの子ポインタ書き込みが必要。仮想ノードで回避しようとしたが入れ子が深さ1で止まらない | PROGRESS.md |
| Manacher | 2つの保存位置の比較が必要 | `online_manacher.py` |
| 前半への再帰 | W の border は回文接頭辞と一致するが、半分に切った Y は回文でないので同じ等式が使えず、問題が「任意文字列の最長回文接頭辞」＝FPP そのものに戻る。`Y # Yᴿ` にすると長さが 2h+1 に伸びて発散 | PROGRESS.md |
| affine prefix set（Bathie–Ellert–Starikovskaya ISAAC 2025）| 回文接頭辞を O(log n) 個の等差数列に分割する構造は正しいが、各数列に周期検証ランナーを立てると同時活性が O(log n) 本 → 1文字 O(log n)。彼らのモデルは read-only 小空間であって real-time ではない | PROGRESS.md |
| **two-way (Crochemore–Perrin) で W を W·W の中で探す** | **還元が誤り**。W·W の位置 p に W が現れる ⟺ W が回転 p で不変（例: `aba` は周期2を持つが `abaaba[2:5]="aab"`）。border ⟺ 周期 は正しいが、周期 ⟺ W·W 内の出現 は偽。`twoway.py` の探索自体は正しい（全数31,682＋ランダム3,000で誤り0）が、`periods/borders/longest_border` は**間違ってる**（未 commit） | `twoway.py`（要修正） |
| LPS を長い側から素朴に走査して半分に再帰 | `lps_halving.py`。正しさは全数検証済み（長さ≤16、非周期回文3,552個誤り0）だが、**線形でない**: 長いランを含む入力で比較回数/|Y| が n=160 で 9.9 まで伸びる（hill-climb）。`b^k a b^j aa b^m` 型が敵対的 | `lps_halving.py`（未 commit） |

### 3b. まだ試してない、有望と思う順

0. **（2026-09-05 に試して潰れた）two-way の探索フェーズを部分一致に改造する。** `twoway_border.py`。
   線形性は出る（比較/文字 ≈ 1.0）が正しさが壊れる：two-way のシフト量は「パターン全体との
   不一致」から導かれるので、末尾で切れた一致に対しては飛びすぎる（`abaa` で border 1 を
   見逃す）。残り長 L ≤ suffix の尾部を素朴照合にすると正しさは半分戻るが `a^n` で O(n²)。
   **部分一致には two-way のシフト保証が乗らない。** この筋を続けるなら Galil–Seiferas の
   定数空間 border 計算をそのまま実装することになる（数日規模）。

1. **Galil–Seiferas (1983) の定数空間 border / period 計算を直接実装する。** two-way は完全一致しか報告しないが、内部で「位置 j で何文字一致したか」を持っている。テキストを W、パターンを W にして、`j + 一致長 == n` となる最初の j を返せば border。critical factorization に基づくシフトが O(n) 総量を保証するかは要確認（Galil–Seiferas の定数空間 border 計算がこの筋）。
2. **Fischer–Paterson の線形時間 initial-palindrome 手続き**（Galil が引用する原典）を読んで、それが畳み込みでなく組合せ的に書けるか確認する。Galil.pdf はリポジトリ直下（**著作権物、絶対に commit しない**、`.git/info/exclude` 済み）。`galil.txt` に pdftotext 済み（scratchpad、消えてるかも）。
3. `lps_halving.py` の長い側走査を、ラン圧縮（同一文字のランを1ステップで飛ばす）で線形化できるか。敵対例が全部ラン由来なので、効く可能性はあるが証明は無い。

### 3c. 埋まったら

1. その手続きを TM（ヘッド・マーク）として `stage3_tm_galil_chain.py` の nonchain move と dp 探索に差す。
2. `tm2peg.py` に symbolic δ と read-only 入力ヘッド（入力チェーン上の zipper）を足す。
3. compile → macro_peg の Interpreter で全数検証（≤16）→ `docs/notes/palindromes-in-peg.md` の Status を書き換える。

## 4. 小さい文法の探索履歴

構造化探索 約12.9万個＋ランダム2規則文法 40万個（26個の判別文字列でスクリーニング）、**通過0**。これは有限の探索結果で、不存在証明ではない。今は FPP の具体的な進展を受けて TM ルートを優先する。

## 5. 運用上の注意

- Galil.pdf は commit しない。`third_party/ruby3/upstream/ruby` の submodule 差分は最初からあるもの、stage しない。
- 作業ログは `PROGRESS.md`（先頭に再開点を pin してある）。潰した道は必ずそこに理由つきで書く。
- 未 commit: `lps_halving.py`, `twoway.py`（後者は §3a の通り `periods` 以下が誤り。探索関数は使える）。
- 関連 memory: `~/.claude/projects/-home-mizushima-repo-macro-peg/memory/peg-palindromes-observation.md`。
