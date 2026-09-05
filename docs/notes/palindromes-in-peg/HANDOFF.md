# 引き継ぎ: 素の PEG で二値 PAL を書く（2026-09-05 時点）

宛先: 次に続ける人（Codex / 別セッション）。このファイルだけ読めば再開できるように書く。

## 0. ゴールと現状を一行で

- **ゴール**: `PAL = { w ∈ {a,b}* | w = wᴿ }` を記述する**素の PEG**（マクロなし）を、明示的に、実行可能な形で出す。
- **現状**: **未達**。存在は定理で保証されてる（下記 §1）が、文法そのものは無い。残りは**一点**（§3）。それが埋まれば `tm2peg.py` が自動で文法を吐く。

## 1. 理論の枠（これは確定、疑わなくてよい）

- LMR: `L ∈ PEG ⟺ Lᴿ ∈ SCA`（scaffolding automaton）。
- Kim–Park 2026 (arXiv:2608.29592, Lean artifact zenodo 22099762): **実時間**多テープ TM → SCA コンパイラ。
- Galil 1978 (JCSS 16(2) 140–157): 実時間多テープ TM で全 initial palindrome を認識。
- よって PAL ∈ PEG。**素の PEG for PAL ＝ 実時間オンライン回文検出器を規則で書いたもの**であり、それ以外の形は無い。小さい文法は存在しない（§4 の探索結果）。

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

## 3. 残る一点（ここから始める）

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

1. **two-way の探索フェーズを「末尾での最長部分一致」を報告するよう改造する。** two-way は完全一致しか報告しないが、内部で「位置 j で何文字一致したか」を持っている。テキストを W、パターンを W にして、`j + 一致長 == n` となる最初の j を返せば border。critical factorization に基づくシフトが O(n) 総量を保証するかは要確認（Galil–Seiferas の定数空間 border 計算がこの筋）。
2. **Fischer–Paterson の線形時間 initial-palindrome 手続き**（Galil が引用する原典）を読んで、それが畳み込みでなく組合せ的に書けるか確認する。Galil.pdf はリポジトリ直下（**著作権物、絶対に commit しない**、`.git/info/exclude` 済み）。`galil.txt` に pdftotext 済み（scratchpad、消えてるかも）。
3. `lps_halving.py` の長い側走査を、ラン圧縮（同一文字のランを1ステップで飛ばす）で線形化できるか。敵対例が全部ラン由来なので、効く可能性はあるが証明は無い。

### 3c. 埋まったら

1. その手続きを TM（ヘッド・マーク）として `stage3_tm_galil_chain.py` の nonchain move と dp 探索に差す。
2. `tm2peg.py` に symbolic δ と read-only 入力ヘッド（入力チェーン上の zipper）を足す。
3. compile → macro_peg の Interpreter で全数検証（≤16）→ `docs/notes/palindromes-in-peg.md` の Status を書き換える。

## 4. 「小さい文法は無いか」は済んでる

構造化探索 約12.9万個＋ランダム2規則文法 40万個（26個の判別文字列でスクリーニング）、**通過0**。ここに時間を使わないこと。

## 5. 運用上の注意

- Galil.pdf は commit しない。`third_party/ruby3/upstream/ruby` の submodule 差分は最初からあるもの、stage しない。
- 作業ログは `PROGRESS.md`（先頭に再開点を pin してある）。潰した道は必ずそこに理由つきで書く。
- 未 commit: `lps_halving.py`, `twoway.py`（後者は §3a の通り `periods` 以下が誤り。探索関数は使える）。
- 関連 memory: `~/.claude/projects/-home-mizushima-repo-macro-peg/memory/peg-palindromes-observation.md`。
