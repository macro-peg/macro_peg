"""Longest palindromic suffix by halving — the candidate that unblocks FPP.

The blocker recorded at the top of PROGRESS.md is: given a palindrome `W` of length
`n` whose smallest period exceeds `n/2`, find its longest proper border in O(n)
machine steps, with heads and marks only (no random access, no arrays of numbers).

Reduction.  For such a `W`, the longest proper border `u` is a palindrome of length
`b < n/2` (borders of a palindrome are its palindromic prefixes, hence also its
palindromic suffixes).  Every palindromic suffix of `W` shorter than `n/2` lies
inside the second half `Y = W[n - ceil(n/2) ..]`, and conversely every palindromic
suffix of `Y` is a proper palindromic suffix of `W`.  Hence

    longest proper border of W  =  longest palindromic suffix of the second half of W.

So the blocker becomes: LPS(Y) for an arbitrary string Y.  That halves:

    * if |LPS(Y)| <= |Y|/2 it is a palindromic suffix of the second half of Y, and by
      the same argument it *is* the LPS of that half — recurse on |Y|/2;
    * otherwise it is longer than |Y|/2, and the "long case" search finds it.

Running the long-case search first and recursing only when it fails costs
T(m) = long(m) + T(m/2), so the whole thing is linear as soon as the long case is.

`long_case_scan` is the naive version of the long case: slide the start `s` from 0
upwards and test `Y[s..]` for being a palindrome by comparing outwards-in, stopping
at the first success.  Two heads moving towards each other plus a restart head: a
machine can do it with no memory at all.  The question this file answers
experimentally is whether its total comparison count is linear.
"""
from itertools import product


def long_case_scan(Y, counter=None):
    """Largest l > len(Y)//2 with Y[len(Y)-l:] a palindrome, else None.
    Comparisons are counted in counter[0] when given."""
    m = len(Y)
    limit = m // 2                       # we only look for l > m//2, i.e. s < m - m//2
    for s in range(0, m - limit):
        i, j = s, m - 1
        ok = True
        while i < j:
            if counter is not None:
                counter[0] += 1
            if Y[i] != Y[j]:
                ok = False
                break
            i += 1
            j -= 1
        if ok:
            return m - s
    return None


def lps(Y, counter=None):
    """Length of the longest palindromic suffix of Y (0 for the empty string)."""
    m = len(Y)
    if m <= 1:
        return m
    got = long_case_scan(Y, counter)
    if got is not None:
        return got
    half = (m + 1) // 2                  # the last ceil(m/2) characters
    return lps(Y[m - half:], counter)


def longest_border_of_palindrome(W, counter=None):
    """Longest proper border of a palindrome W whose period exceeds |W|/2."""
    n = len(W)
    half = (n + 1) // 2
    return lps(W[n - half:], counter)


# ------------------------------------------------------------------ references
def brute_lps(Y):
    m = len(Y)
    for l in range(m, 0, -1):
        if Y[m - l:] == Y[m - l:][::-1]:
            return l
    return 0


def brute_longest_border(W):
    n = len(W)
    for b in range(n - 1, 0, -1):
        if W[:b] == W[n - b:]:
            return b
    return 0


def smallest_period(W):
    n = len(W)
    return next(p for p in range(1, n + 1) if all(W[i] == W[i + p] for i in range(n - p)))


if __name__ == '__main__':
    # 1. lps correctness on all binary strings up to length 16
    bad = 0
    for n in range(0, 17):
        for t in product('ab', repeat=n):
            Y = ''.join(t)
            if lps(Y) != brute_lps(Y):
                bad += 1
                if bad <= 3:
                    print('BAD lps', Y, lps(Y), brute_lps(Y))
        if n >= 14 and bad:
            break
    print(f'lps: all binary strings up to length 16 -> mismatches {bad}')

    # 2. the reduction, on every palindrome with period > n/2
    bad = tested = 0
    for n in range(2, 21):
        for t in product('ab', repeat=(n + 1) // 2):
            half = ''.join(t)
            W = half + (half[:-1] if n % 2 else half)[::-1]
            if len(W) != n or W != W[::-1]:
                continue
            if smallest_period(W) * 2 <= n:
                continue
            tested += 1
            if longest_border_of_palindrome(W) != brute_longest_border(W):
                bad += 1
                if bad <= 3:
                    print('BAD border', W, longest_border_of_palindrome(W), brute_longest_border(W))
    print(f'longest border of non-periodic palindromes: {tested} tested -> mismatches {bad}')

    # 3. cost: is the long-case scan linear?
    worst = (0, '')
    for n in range(1, 19):
        for t in product('ab', repeat=n):
            Y = ''.join(t)
            c = [0]
            lps(Y, c)
            ratio = c[0] / max(n, 1)
            if ratio > worst[0]:
                worst = (ratio, Y)
    print(f'lps cost: worst comparisons/|Y| over all strings up to length 18 = '
          f'{worst[0]:.2f} on {worst[1]!r}')

    import random
    random.seed(1)
    worst_long = (0, '')
    for _ in range(400):
        n = random.randint(50, 400)
        kind = random.randrange(4)
        if kind == 0:
            Y = ''.join(random.choice('ab') for _ in range(n))
        elif kind == 1:
            p = ''.join(random.choice('ab') for _ in range(random.randint(1, 5)))
            Y = (p * n)[:n]
        elif kind == 2:
            h = ''.join(random.choice('ab') for _ in range(n // 2))
            Y = h + h[::-1]
        else:
            Y = 'a' * (n // 2) + 'b' + 'a' * (n // 2 - 1)
        c = [0]
        lps(Y, c)
        ratio = c[0] / len(Y)
        if ratio > worst_long[0]:
            worst_long = (ratio, Y[:30] + '...')
    print(f'lps cost on 400 long strings (len 50-400): worst comparisons/|Y| = '
          f'{worst_long[0]:.2f} on {worst_long[1]!r}')
