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
