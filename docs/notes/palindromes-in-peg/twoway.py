"""Two-way string matching (Crochemore & Perrin 1991) and what it buys for FPP.

Why this file exists.  The blocker at the top of PROGRESS.md is "the longest border
of a non-periodic palindrome W in O(|W|) machine steps with heads only".  The KMP
route needs random access to fail[]; the naive outward-in scan (lps_halving.py) is
superlinear on inputs with long runs.  But a border is a period:

    W has a border of length b   <=>   p = |W| - b is a period of W
                                <=>   W occurs in W·W at position p   (0 < p <= |W|)

so *all* borders of W are the occurrence positions of W in W·W, in increasing order
of p (decreasing border length), and the longest border is the first occurrence.
Two-way matching reports all occurrences of a pattern of length n in a text of
length 2n in O(n) time with O(1) extra space: a critical factorization of the
pattern (two maximal-suffix scans) and a search that keeps four integers.  Every
integer is a position or a distance between positions — a head, or two heads moved
in lockstep — so the whole thing is a multitape Turing machine with local moves.

Galil's algorithm calls FPP twice; both become this:
  * nonchain `move`: longest border of the window  = first occurrence;
  * `dp(C, r)`: all palindromes ending at C = all borders of the window, emitted in
    decreasing length, which is exactly the order in which Galil marks their left
    ends on a tape before scanning with two heads at speeds 2 and 4.

Correctness of `two_way_search` is checked against brute force on random and
exhaustive inputs; `period` and `borders` are checked on all binary strings up to
length 14; the comparison count is checked to be linear.
"""
from itertools import product


def _maximal_suffix(x, greater):
    """Crochemore-Perrin: (ms, p) with x[ms+1:] the maximal suffix for the order
    (a < b iff greater(a, b) is False and a != b) and p its period.  ms may be -1."""
    n = len(x)
    ms, j, k, p = -1, 0, 1, 1
    while j + k < n:
        a, b = x[j + k], x[ms + k]
        if (a < b) if not greater else (a > b):
            j += k
            k = 1
            p = j - ms
        elif a == b:
            if k != p:
                k += 1
            else:
                j += p
                k = 1
        else:
            ms = j
            j = ms + 1
            k = p = 1
    return ms, p


def critical_factorization(x):
    """(suffix, period): x[:suffix] · x[suffix:] is a critical factorization and
    `period` is the local period there (= the global period when x is periodic)."""
    ms1, p1 = _maximal_suffix(x, False)
    ms2, p2 = _maximal_suffix(x, True)
    if ms2 > ms1:
        return ms2 + 1, p2
    return ms1 + 1, p1


def two_way_search(needle, haystack, counter=None):
    """All occurrence positions of needle in haystack, increasing.  Comparisons are
    counted in counter[0] when given."""
    n, m = len(needle), len(haystack)
    out = []
    if n == 0:
        return list(range(m + 1))
    if n > m:
        return out

    def cmp_eq(i, j):
        if counter is not None:
            counter[0] += 1
        return needle[i] == haystack[j]

    suffix, period = critical_factorization(needle)

    # periodic pattern iff the prefix of length `suffix` repeats with `period`
    periodic = suffix + period <= n and all(needle[i] == needle[i + period] for i in range(suffix))
    if periodic:
        memory = 0
        j = 0
        while j <= m - n:
            i = max(suffix, memory)
            while i < n and cmp_eq(i, i + j):
                i += 1
            if i >= n:
                i = suffix - 1
                while i + 1 > memory and cmp_eq(i, i + j):
                    i -= 1
                if i + 1 <= memory:
                    out.append(j)
                j += period
                memory = n - period
            else:
                j += i - suffix + 1
                memory = 0
    else:
        shift = max(suffix, n - suffix) + 1
        j = 0
        while j <= m - n:
            i = suffix
            while i < n and cmp_eq(i, i + j):
                i += 1
            if i >= n:
                i = suffix - 1
                while i >= 0 and cmp_eq(i, i + j):
                    i -= 1
                if i < 0:
                    out.append(j)
                j += shift
            else:
                j += i - suffix + 1
    return out


# WARNING (2026-09-05): the reduction below is WRONG.  W occurs in W·W at position p
# iff W is invariant under rotation by p, which is strictly stronger than "p is a
# period of W" (e.g. 'aba' has period 2 but 'abaaba'[2:5] == 'aab').  Verified:
# 320 failures on all binary strings up to length 8.  `two_way_search` itself is
# correct (31,682 exhaustive + 3,000 random, 0 mismatches); only periods/period/
# borders/longest_border are broken.  See HANDOFF.md §3a/§3b for the fix direction.
def periods(W, counter=None):
    """BROKEN — see warning above.  Kept for the record."""
    n = len(W)
    if n == 0:
        return []
    occ = two_way_search(W, W + W, counter)
    return [p for p in occ if 1 <= p <= n]


def period(W, counter=None):
    ps = periods(W, counter)
    return ps[0] if ps else 0


def borders(W, counter=None):
    """All proper border lengths of W, decreasing."""
    n = len(W)
    return [n - p for p in periods(W, counter) if p < n]


def longest_border(W, counter=None):
    n = len(W)
    return n - period(W, counter) if n else 0


# ------------------------------------------------------------------ references
def brute_occurrences(needle, haystack):
    n, m = len(needle), len(haystack)
    return [j for j in range(m - n + 1) if haystack[j:j + n] == needle]


def brute_borders(W):
    n = len(W)
    return [b for b in range(n - 1, 0, -1) if W[:b] == W[n - b:]]


if __name__ == '__main__':
    import random
    random.seed(7)
    # 1. search correctness: exhaustive small, random larger
    bad = tot = 0
    for nl in range(1, 6):
        for hl in range(0, 9):
            for nt in product('ab', repeat=nl):
                needle = ''.join(nt)
                for ht in product('ab', repeat=hl):
                    hay = ''.join(ht); tot += 1
                    if two_way_search(needle, hay) != brute_occurrences(needle, hay):
                        bad += 1
                        if bad <= 3: print('BAD search', needle, hay, two_way_search(needle, hay), brute_occurrences(needle, hay))
    print(f'two-way search: {tot} exhaustive (needle<=5, haystack<=8) -> mismatches {bad}')
    bad = 0
    for _ in range(3000):
        alpha = random.choice(['ab', 'ab', 'abc'])
        needle = ''.join(random.choice(alpha) for _ in range(random.randint(1, 12)))
        hay = ''.join(random.choice(alpha) for _ in range(random.randint(0, 60)))
        if random.random() < 0.5:
            k = random.randint(1, 4); hay = (needle * k + hay)[:60]
        if two_way_search(needle, hay) != brute_occurrences(needle, hay):
            bad += 1
            if bad <= 3: print('BAD search', needle, hay)
    print(f'two-way search: 3000 random -> mismatches {bad}')

    # 2. borders / period via W·W, all binary strings up to length 14
    bad = tot = 0
    for n in range(1, 15):
        for t in product('ab', repeat=n):
            W = ''.join(t); tot += 1
            if borders(W) != brute_borders(W):
                bad += 1
                if bad <= 3: print('BAD borders', W, borders(W), brute_borders(W))
    print(f'borders via two-way on W·W: {tot} strings -> mismatches {bad}')

    # 3. the actual blocker: longest border of every palindrome up to length 24
    bad = tot = 0
    for n in range(1, 25):
        for t in product('ab', repeat=(n + 1) // 2):
            h = ''.join(t); W = h + (h[:-1] if n % 2 else h)[::-1]
            tot += 1
            want = brute_borders(W)
            if longest_border(W) != (want[0] if want else 0):
                bad += 1
                if bad <= 3: print('BAD lb', W)
    print(f'longest border of palindromes up to length 24: {tot} -> mismatches {bad}')

    # 4. linearity: comparisons per |W| on adversarial and random inputs
    worst = (0, '')
    fams = [lambda n: 'b' * (n // 2) + 'a' + 'b' * (n // 4) + 'aa' + 'b' * (n - n // 2 - n // 4 - 3),
            lambda n: 'a' * (n // 2) + 'b' + 'a' * (n // 2),
            lambda n: ('aaab' * n)[:n],
            lambda n: ''.join('ab'[bin(i).count('1') % 2] for i in range(n)),
            lambda n: (lambda h: h + h[::-1])(''.join(random.choice('ab') for _ in range(n // 2))),
            lambda n: ''.join(random.choice('ab') for _ in range(n))]
    for n in (64, 256, 1024, 4096):
        for mk in fams:
            W = mk(n); c = [0]; borders(W, c)
            r = c[0] / len(W)
            if r > worst[0]: worst = (r, W[:24] + '...', n)
    print(f'comparisons per |W| for all borders, worst over families at n=64..4096: '
          f'{worst[0]:.2f} on {worst[1]!r} (n={worst[2]})')
    # hill climb
    best = (0, '')
    for n in (48, 96, 192):
        for restart in range(4):
            W = ''.join(random.choice('ab') for _ in range(n)); cur = None
            for it in range(300):
                i = random.randrange(n); Z = W[:i] + ('a' if W[i] == 'b' else 'b') + W[i + 1:]
                c = [0]; borders(Z, c); cz = c[0] / n
                if cur is None or cz >= cur: W, cur = Z, cz
            if cur > best[0]: best = (cur, W[:24] + '...', n)
    print(f'hill-climb worst comparisons per |W|: {best[0]:.2f} on {best[1]!r} (n={best[2]})')
