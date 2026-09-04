"""FPP: all palindromic prefixes of a string, in linear time — reference implementation
and an analysis of what it costs in the machine model that compiles to a PEG.

Galil's real-time recogniser calls FPP (Fischer & Paterson) twice: in the nonchain case
(the longest initial palindrome of the window) and in every stage of the double-palindrome
search (all initial palindromes of the window, marked on a tape).  So FPP is the last
missing component of the machine whose compilation yields a plain PEG for PAL.

Reference algorithm.  `u[0..j]` is a palindrome iff `u[0..j]` equals its reverse, and the
palindromic prefixes of `u` are exactly the borders of `s = u # reverse(u)`: a border of
length L means `u[0..L-1] = reverse(u)[m-L..m-1] = reverse(u[0..L-1])`.  So one KMP
failure function over `s` gives all of them, in O(|u|) time.

What the machine model can and cannot do with it (this is the point of the file):

  * The failure function's values are positions, so they cannot live in tape cells.  They
    can live in *pointer-carrying cells* — a cell written by the head may store a pointer
    to any node already created — which the PEG encoding supports (a cell's fields are
    rules at the position where the cell was written).
  * The KMP cursor needs three operations: read `P[k+1]`, move to `k+1` on a match, and
    jump to `fail[k]` on a mismatch.
      - `P[k+1]` and `k+1` are easy if the cells carry a `next` pointer, which a
        right-to-left pass can build (a cell written later may point to one written
        earlier, so scanning right to left yields forward links).
      - `fail[k]` is easy if the cell for `k` carries a `fail` pointer, which a
        left-to-right pass can build (`fail[k] < k`, already created).
      - **Both at once is the obstacle.**  A left-to-right pass cannot store `next`
        (the target does not exist yet) and a right-to-left pass cannot store `fail`
        (the target does not exist yet), and the cursor needs both on the *same* cell.
        Splitting into two chains does not help: keeping a cursor into both chains in
        step requires moving forward in the fail chain, which is the same wall.
  * A zipper (a stack of the cells below the cursor plus a queue of those above) gives
    `+1` without pointers, but then the jump has to pop "until the top is the target",
    which is a pointer comparison — and the model has none.  Persistent snapshots remove
    the comparison but lose the cells created after the snapshot.

`kmp_fail` and `palindromic_prefixes` below are the reference; `analysis()` reports, for
random strings, how much of the work each attempted encoding can actually perform, which
is the measurement the next attempt should improve on.
"""
from itertools import product


def kmp_fail(s):
    """standard failure function; fail[j] = length of the longest proper border of s[0..j-1]."""
    m = len(s)
    fail = [0] * (m + 1)
    k = 0
    for j in range(1, m):
        while k > 0 and s[k] != s[j]:
            k = fail[k]
        if s[k] == s[j]:
            k += 1
        fail[j + 1] = k
    return fail


def palindromic_prefixes(u, sep='#'):
    """lengths L >= 1 with u[0..L-1] a palindrome, via the borders of u # reverse(u)."""
    assert sep not in u
    s = u + sep + u[::-1]
    fail = kmp_fail(s)
    out = []
    L = fail[len(s)]
    while L > 0:
        if L <= len(u):
            out.append(L)
        L = fail[L]
    return sorted(out)


def brute(u):
    return sorted(L for L in range(1, len(u) + 1) if u[:L] == u[:L][::-1])


def cursor_trace(s):
    """The KMP cursor's moves, classified: '+1' (needs a successor), 'jump' (needs a
    stored fail pointer), 'read' (needs the symbol one to the right of the cursor)."""
    m = len(s)
    fail = [0] * (m + 1)
    k = 0
    moves = {'read': 0, 'jump': 0, 'plus1': 0}
    for j in range(1, m):
        moves['read'] += 1
        while k > 0 and s[k] != s[j]:
            k = fail[k]
            moves['jump'] += 1
            moves['read'] += 1
        if s[k] == s[j]:
            k += 1
            moves['plus1'] += 1
        fail[j + 1] = k
    return moves


def analysis(trials=200, seed=5):
    import random
    random.seed(seed)
    worst = None
    total = {'read': 0, 'jump': 0, 'plus1': 0, 'len': 0}
    for _ in range(trials):
        n = random.randint(4, 200)
        kind = random.randrange(3)
        if kind == 0:
            u = ''.join(random.choice('ab') for _ in range(n))
        elif kind == 1:
            p = ''.join(random.choice('ab') for _ in range(random.randint(1, 4)))
            u = (p * n)[:n]
        else:
            h = ''.join(random.choice('ab') for _ in range(n // 2))
            u = h + h[::-1]
        s = u + '#' + u[::-1]
        mv = cursor_trace(s)
        for k in mv:
            total[k] += mv[k]
        total['len'] += len(s)
        r = (mv['jump'] + mv['plus1']) / len(s)
        if worst is None or r > worst[0]:
            worst = (r, u[:20])
    print(f"cursor moves over {trials} strings: "
          f"reads {total['read']}, jumps {total['jump']}, +1s {total['plus1']} "
          f"for {total['len']} characters "
          f"({(total['jump'] + total['plus1']) / total['len']:.2f} cursor moves per character)")
    print(f"  worst ratio {worst[0]:.2f} on {worst[1]!r}...")


if __name__ == '__main__':
    bad = 0
    for n in range(0, 13):
        for t in product('ab', repeat=n):
            u = ''.join(t)
            if palindromic_prefixes(u) != brute(u):
                bad += 1
                if bad <= 3:
                    print('BAD', u, palindromic_prefixes(u), brute(u))
    print(f'FPP reference: all binary strings up to length 12 -> mismatches {bad}')
    analysis()


# ---------------------------------------------------------------- the way in: periods
# The KMP route needs random access to fail[].  A *constant-space* route does not:
# Crochemore-Perrin's maximal-suffix computation finds the period of a string with a
# fixed number of indices (i, j, k, p) and one left-to-right scan, and indices are what
# a Turing machine has — heads.  The border chain then comes out as O(log m) arithmetic
# progressions: the longest border is m - period, the borders longer than m/2 are exactly
# m - t*period, and the rest is the border chain of a string at most half as long, so the
# total work telescopes to O(m).

def maximal_suffix(x, greater=True):
    """(index just before the maximal suffix, its period) — Crochemore-Perrin.
    Uses four indices and one left-to-right scan: exactly what heads give a machine."""
    n = len(x)
    ms, j, k, p = -1, 0, 1, 1
    while j + k < n:
        a, b = x[j + k], x[ms + k]
        if (a < b) if greater else (a > b):
            j += k; k = 1; p = j - ms
        elif a == b:
            if k == p:
                j += p; k = 1
            else:
                k += 1
        else:
            ms = j; j = ms + 1; k = p = 1
    return ms, p


def critical_factorization(x):
    """(critical position ell, candidate period p) by the two maximal suffixes."""
    i1, p1 = maximal_suffix(x, True)
    i2, p2 = maximal_suffix(x, False)
    return (i1, p1) if i1 >= i2 else (i2, p2)


def period_if_periodic(x):
    """the smallest period of x when it is at most |x|/2 (the 'periodic' case, which is
    exactly Galil's chain case); None otherwise.  Constant space, linear time."""
    n = len(x)
    if n == 0:
        return None
    ell, p = critical_factorization(x)
    if p <= n // 2 and x[:p] == x[p:2 * p][:p] and x[:ell + 1] == x[p:p + ell + 1]:
        # verify p is a period of the whole word (one scan)
        if all(x[i] == x[i + p] for i in range(n - p)):
            return p
    return None


def borders_via_periods(x):
    """all border lengths of x, in decreasing order, by peeling arithmetic progressions."""
    out = []
    cur = x
    while cur:
        n = len(cur)
        p = period(cur)
        if p == n:                      # no nontrivial border
            break
        b = n - p                       # longest border
        # borders longer than n/2 are exactly n - t*p while positive and > n/2
        t = 1
        while n - t * p > 0 and n - t * p >= n - b - 0 and n - t * p > n // 2:
            out.append(n - t * p)
            t += 1
        if not out or out[-1] != b:
            pass
        # continue with the longest border of length <= n/2 in the chain
        nxt = n - t * p
        if nxt <= 0:
            cur = ''
        else:
            out.append(nxt)
            cur = cur[:nxt]
            # the remaining chain is the border chain of cur, handled by the next round
            cur = cur
            # avoid duplicating `nxt` when the next round reports it
            out_marker = None
        if len(out) > 4 * len(x) + 8:
            raise RuntimeError('runaway')
    seen = []
    for b in out:
        if b > 0 and (not seen or seen[-1] != b):
            seen.append(b)
    return seen


def borders_reference(x):
    f = kmp_fail(x)
    out = []
    L = f[len(x)]
    while L > 0:
        out.append(L)
        L = f[L]
    return out
