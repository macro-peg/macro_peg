"""Stage 1: online LPS tracking where the chain of palindromic suffixes of the active
window [s,k] is obtained by KMP over the window (borders of a palindrome = its
palindromic suffixes).  Positions are ints here, but every access is one the pointer
model allows: reading x[p] for a p we hold, p-1 (prev), and KMP fail links.
We count 'work' = number of such elementary operations per input character."""
from itertools import product

def brute_lps_start(x, k):
    return next(s for s in range(k+1) if x[s:k+1] == x[s:k+1][::-1])

def kmp_borders(x, s, k, work):
    """fail[j] for window positions j=1..m (m=k-s+1) where window char j = x[k-j+1]
    (scanning the palindrome backwards = forwards).  fail[j] = length of the longest
    proper border of the window prefix of length j.  Returns fail list (index 1..m)."""
    m = k - s + 1
    fail = [0]*(m+1)
    pi = 0
    for j in range(2, m+1):
        cj = x[k-j+1]; work[0] += 1
        while pi > 0 and x[k-(pi+1)+1] != cj:
            pi = fail[pi]; work[0] += 1
        if x[k-(pi+1)+1] == cj:
            pi += 1
        fail[j] = pi
    return fail

def online(x, work):
    n = len(x); s = 0; k = 0; starts = [0]
    for k1 in range(1, n):
        c = x[k1]; k = k1 - 1
        if s >= 1 and x[s-1] == c:
            s -= 1; work[0] += 1; starts.append(s); continue
        # mismatch: chain of palindromic suffixes of [s,k] = border chain
        fail = kmp_borders(x, s, k, work)
        m = k - s + 1
        ell = fail[m]                     # longest proper border length
        new_s = None
        while ell > 0:
            st = k - ell + 1              # start of that suffix palindrome
            work[0] += 1
            if st >= 1 and x[st-1] == c:
                new_s = st - 1; break
            ell = fail[ell]
        if new_s is None:                 # empty border: "cc" or "c"
            new_s = k if x[k] == c else k1
        s = new_s; starts.append(s)
    return starts

bad = 0; tot = 0; worst = 0; totwork = 0
for n in range(1, 15):
    for t in product('ab', repeat=n):
        x = ''.join(t); tot += 1; work = [0]
        starts = online(x, work)
        want = [brute_lps_start(x, k) for k in range(n)]
        if starts != want:
            bad += 1
            if bad <= 3: print('BAD', x, starts, want)
        worst = max(worst, work[0]/n); totwork += work[0]
print(f'strings {tot} bad {bad}  worst work/n {worst:.1f}')
# periodic stress
for m in (50, 100, 200):
    x = 'ab'*m; work=[0]; online(x, work); print(f'(ab)^{m}: work/n = {work[0]/len(x):.1f}')
