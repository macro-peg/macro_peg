"""Verified algorithmic core for an explicit plain PEG for PAL (work in progress).

Online Manacher over positions. Reading x left to right (this is the SCA direction; the
PEG reads reverse(x)), it maintains the longest palindromic suffix [s, k] of x[0..k] with
centre code C = s + k (code 2p = single position p, code 2p+1 = between p and p+1) and a
table LE of finalized left ends.

Invariant that makes this a pointer-machine algorithm: every centre code < C is already
recorded, and codes are recorded in strictly increasing order, each exactly once.  So the
scan performed at a mismatch is a walk backwards along the chain of records, one hop per
step — exactly what a scaffolding automaton (hence a PEG memo table) can do.

What is NOT yet pointer-machine friendly, and is the remaining work:
  * the comparison  LE[mc] <= s
  * the mirror arithmetic  LE[cc] = C - mc + LE[mc]  and  s' = k + 2s - mc
All three are reflections about the active centre C; a Turing-machine tape realizes them
by head movement (Galil 1978), a scaffold has to realize them with delayed lockstep walks.

Checked against brute force on all binary strings up to length 14.
"""
from itertools import product


def brute_lps_start(x, k):
    return next(s for s in range(k + 1) if x[s:k + 1] == x[s:k + 1][::-1])


def online(x):
    n = len(x)
    LE = {-1: 0}                 # code -1: the empty palindrome left of position 0
    C, s = 0, 0
    order = []                   # recording order of codes
    starts, hops = [0], []
    for k1 in range(1, n):       # k1 = index of the new character, k = k1 - 1
        k = k1 - 1
        c = x[k1]
        h = 0
        if s >= 1 and x[s - 1] == c:          # the active palindrome extends
            s -= 1
            hops.append(h)
            starts.append(s)
            continue
        LE[C] = s                             # finalize the active centre
        order.append(C)
        mc = C - 1                            # scan mirror centres, nearest first
        while True:
            h += 1
            le = LE[mc]
            cc = 2 * C - mc                   # mirror of mc about C
            if le > s:                        # strictly inside: the mirror is final too
                LE[cc] = C - mc + le
                order.append(cc)
                mc -= 1
                continue
            sp = k + 2 * s - mc               # candidate suffix palindrome [sp, k]
            if sp >= 1 and x[sp - 1] == c:    # it extends with the new character
                C, s = cc, sp - 1
                break
            LE[cc] = sp                       # finalized
            order.append(cc)
            if mc == 2 * s - 1:               # that was the empty suffix: fall back to "c"
                C, s = 2 * k1, k1
                break
            mc -= 1
        hops.append(h)
        starts.append(s)
    return starts, hops, order


if __name__ == "__main__":
    bad = total = 0
    worst = 0
    for n in range(1, 15):
        for t in product("ab", repeat=n):
            x = "".join(t)
            total += 1
            starts, hops, order = online(x)
            want = [brute_lps_start(x, k) for k in range(n)]
            ok = starts == want and order == sorted(order) and len(order) == len(set(order))
            if not ok:
                bad += 1
                if bad <= 3:
                    print("BAD", x, starts, want, order)
            worst = max(worst, max(hops) if hops else 0)
    print(f"strings {total}  bad {bad}  max scan hops in one step {worst}")
