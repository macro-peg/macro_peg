"""Longest border by two-way *partial* matching — HANDOFF.md §3b item 1.

b is a border of W  <=>  the prefix W[:b] matches at position n-b in W, i.e. the
two-way search of pattern W in text W, started at shift j = n-b, matches until it
runs off the end of the text.  Two-way normally reports full occurrences only; here
each alignment j is tested for "matches up to the end of the text" and the first
such j gives the longest border n-j.  The critical-factorization shifts stay valid
(they only use the pattern's structure), so the cost bound of two-way should carry
over.  This file measures whether it does.
"""
from itertools import product
from twoway import critical_factorization, two_way_search, brute_borders


def longest_border_twoway(W, counter=None):
    n = len(W)
    if n <= 1:
        return 0
    needle = W
    text = W

    def eq(i, j):
        if counter is not None:
            counter[0] += 1
        return needle[i] == text[j]

    suffix, period = critical_factorization(needle)
    periodic = suffix + period <= n and all(needle[i] == needle[i + period] for i in range(suffix))
    memory = 0
    j = 1
    while j < n:
        L = n - j                                  # text has L chars left from j
        # forward part: compare needle[suffix..L) against text[j+suffix..n)
        i = max(suffix, memory) if periodic else suffix
        while i < L and eq(i, j + i):
            i += 1
        if i >= L:
            # backward part: needle[0..suffix) vs text[j..j+suffix)
            k = min(suffix, L) - 1
            lo = memory if periodic else 0
            while k >= lo and eq(k, j + k):
                k -= 1
            if k < lo:
                return n - j
            if periodic:
                j += period; memory = n - period
            else:
                j += max(suffix, n - suffix) + 1
            continue
        if periodic:
            j += i - suffix + 1; memory = 0
        else:
            j += i - suffix + 1
    return 0


if __name__ == '__main__':
    import random
    random.seed(11)
    bad = tot = 0
    for n in range(1, 17):
        for t in product('ab', repeat=n):
            W = ''.join(t); tot += 1
            want = brute_borders(W); want = want[0] if want else 0
            if longest_border_twoway(W) != want:
                bad += 1
                if bad <= 5: print('BAD', W, longest_border_twoway(W), want)
    print(f'longest border via two-way partial match: {tot} strings <=16 -> mismatches {bad}')
    worst = (0, '', 0)
    fams = [lambda n: 'b' * (n // 2) + 'a' + 'b' * (n // 4) + 'aa' + 'b' * (n - n // 2 - n // 4 - 3),
            lambda n: 'a' * (n // 2) + 'b' + 'a' * (n // 2),
            lambda n: ('aaab' * n)[:n],
            lambda n: ''.join('ab'[bin(i).count('1') % 2] for i in range(n)),
            lambda n: (lambda h: h + h[::-1])(''.join(random.choice('ab') for _ in range(n // 2))),
            lambda n: ''.join(random.choice('ab') for _ in range(n)),
            lambda n: 'a' * n]
    for n in (64, 256, 1024, 4096):
        for mk in fams:
            W = mk(n); c = [0]; longest_border_twoway(W, c)
            r = c[0] / len(W)
            if r > worst[0]: worst = (r, W[:24], n)
    print(f'families: worst comparisons/|W| = {worst[0]:.2f} on {worst[1]!r}... n={worst[2]}')
    best = (0, '', 0)
    for n in (64, 128, 256):
        for restart in range(5):
            W = ''.join(random.choice('ab') for _ in range(n)); cur = None
            for it in range(400):
                i = random.randrange(n); Z = W[:i] + ('a' if W[i] == 'b' else 'b') + W[i + 1:]
                c = [0]; longest_border_twoway(Z, c); cz = c[0] / n
                if cur is None or cz >= cur: W, cur = Z, cz
            if cur > best[0]: best = (cur, W[:24], n)
    print(f'hill-climb: worst comparisons/|W| = {best[0]:.2f} on {best[1]!r}... n={best[2]}')
