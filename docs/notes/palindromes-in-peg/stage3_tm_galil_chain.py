"""Stage 3: Galil's algorithm with the chain case, on the stage-2 machinery.

Encoding (Galil): places.  Place 0 = left marker; input symbol i (1-based) sits at
place 2i-1; place 2i is a space symbol.  Every palindrome of the symbol string is an
odd-length palindrome of the place string centred at a place (a space for even
symbol-palindromes).  L, R, C, D are places; h is the period of the active
palindrome in places (always even); Galil's chain step is h_G = h/2.

One iteration per new symbol (R advances by two places):
  * extension: a_{L-2} = c  ->  [L-2, R+2].  While a chain is alive, head D at
    place L-2+h predicts the extension symbol; if a_D != c the chain ends (Galil
    case 1) and a search for a coarser chain (step > CH_last - C) is started.
  * mismatch with a_D = c (Galil case 2b, chain case): C += h/2, L += h-2, R += 2.
    Cost O(h), paid because the centre moves h/2.
  * mismatch otherwise: `move` = KMP over the window (O(R-L)), paid because the
    centre moves >= (R-C)/4 (nonchain case); then the search for the new centre is
    replayed off-line (main1) with RATE*(R-C) units, then continues in the background.
  * background search dp(C, r): doubling stages over windows ending at C; a stage
    finds all palindromes ending at C via KMP and looks for a double palindrome
    (lengths 2h_G+1 and 4h_G+1) with step h_G > r; paced at RATE units per symbol.
    A found chain is used once its right half fits: R >= C + 4 h_G.

Correctness is checked online (unbounded time); real-time-ness by the budgeted
driver: its output must equal the truth although the algorithm may lag.
"""
from itertools import product
import sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from stage2_tm_galil import Tape, MARK

SPACE = '_'
RATE = 64         # search units per input symbol (stage i must finish before R-C ~ |u_i|/4)


class Machine:
    def __init__(self, x):
        self.x = x
        self.tape = Tape()
        self.tape.set(0, MARK)
        self.avail = 0            # number of symbols fed
        self.last = 0             # last readable place
        self.steps = 0
        self.out = []

    def feed(self):
        i = self.avail + 1
        self.tape.set(2 * i - 1, self.x[i - 1])
        self.tape.set(2 * i, SPACE)
        self.avail = i
        self.last = 2 * i

    def rd(self, place):
        assert 0 <= place <= self.last, f'online violation at place {place}'
        self.steps += 1
        return self.tape.sym(place)

    def tick(self, n=1):
        self.steps += n


def kmp_suffix_palindromes(m, lo, hi):
    """Lengths (in places, decreasing) of all palindromes ending at place `hi` that
    start at or after place `lo`: KMP with pattern = reverse(window), text = window.
    Cost O(hi - lo).  Generator."""
    n = hi - lo + 1
    P = lambda j: m.rd(hi - j)         # pattern = window reversed
    T = lambda i: m.rd(lo + i)         # text = window
    fail = [0] * (n + 1)
    q = 0
    for j in range(1, n):
        cj = P(j); yield
        while q > 0 and P(q) != cj:
            q = fail[q]; yield
        if P(q) == cj:
            q += 1
        fail[j + 1] = q; yield
    q = 0
    for i in range(n):
        ti = T(i); yield
        while q > 0 and P(q) != ti:
            q = fail[q]; yield
        if P(q) == ti:
            q += 1
        yield
    lengths = []
    while q > 0:
        lengths.append(q)
        q = fail[q]; yield
    return lengths


def dp_search(m, C, r):
    """Smallest h_G > r such that [C-4h_G, C] is a double palindrome, by doubling
    stages; None if there is none.  Generator (one yield per unit)."""
    i = 1
    while True:
        ell = (8 * max(r, 1)) << (i - 1)
        lo = max(1, C - ell)
        final = lo == 1
        lengths = yield from kmp_suffix_palindromes(m, lo, C)
        have = set(lengths)
        hmax = (C - lo) // 4
        for hg in range(r + 1, hmax + 1):
            m.tick(); yield
            if (2 * hg + 1) in have and (4 * hg + 1) in have:
                return hg
        if final:
            return None
        i += 1


def galil(m):
    n = len(m.x)
    m.feed()
    L = R = C = 1
    m.tick(); yield
    m.out.append(1)
    h = None; D = None
    pending = None                        # h_G found, waiting for R >= C + 4 h_G
    verify = None                         # verification pointer while confirming
    search = dp_search(m, C, 0); search_done = False; found = [None]

    def advance(units):
        nonlocal search_done
        for _ in range(units):
            if search_done:
                return
            try:
                next(search)
            except StopIteration as e:
                search_done = True; found[0] = e.value

    def chain_last(C, R, hg):
        i = (R - C) // hg - 1
        return C + hg * max(i, 0)

    while m.avail < n:
        m.feed()
        c = m.rd(R + 2); yield                     # new symbol (R+1 is a space)
        # --- background chain search (paced) ---------------------------------
        if not search_done:
            advance(RATE)
            for _ in range(RATE): yield
            if search_done:
                pending = found[0]
        # --- confirming a found chain: its mirror half [C, C+4h_G] holds once
        #     R >= C + 4h_G; beyond that, verify periodicity up to R at 4 places
        #     per symbol (Galil's right-dp, then D would have watched) -----------
        if h is None and pending is not None:
            hh = 2 * pending
            if verify is None and R >= C + 4 * pending:
                verify = C + 4 * pending + 1
            if verify is not None:
                for _ in range(4):
                    if verify > R:
                        break
                    a1 = m.rd(verify); a2 = m.rd(verify - hh); yield
                    if a1 != a2:                          # chain ended before R
                        r = chain_last(C, verify - 1, pending) - C
                        pending = None; verify = None
                        search = dp_search(m, C, r); search_done = False
                        break
                    verify += 1
                if pending is not None and verify > R:
                    h = hh; D = L - 2 + h; pending = None; verify = None
                    m.tick(h); yield
        # --- extension ---------------------------------------------------------
        if L >= 3 and m.rd(L - 2) == c:
            yield
            L -= 2; R += 2
            if h is not None:
                if m.rd(D) != c:                    # chain ends (Galil case 1)
                    r = chain_last(C, R - 2, h // 2) - C
                    h = None; D = None; pending = None; verify = None
                    search = dp_search(m, C, r); search_done = False
                else:
                    D -= 2
                yield
            m.out.append(1 if L == 1 else 0)
            continue
        yield
        # --- mismatch, chain case (Galil case 2b) --------------------------------
        if h is not None and m.rd(D) == c:
            yield
            C += h // 2; L += h - 2; R += 2; D = L - 2 + h
            m.tick(h); yield
            m.out.append(1 if L == 1 else 0)
            continue
        # --- mismatch, nonchain: move -------------------------------------------
        lengths = yield from kmp_suffix_palindromes(m, L, R)
        newL = None
        for ell in lengths:
            st = R - ell + 1
            if st >= 3 and m.rd(st - 2) == c:
                newL = st - 2; break
            yield
        if newL is None:
            newL = R if m.rd(R) == c else R + 2
            yield
        R += 2; L = newL; C = (L + R) // 2
        h = None; D = None; pending = None; verify = None
        search = dp_search(m, C, 0); search_done = False
        units = RATE * (R - C)                     # main1: off-line replay, paid by the move
        advance(units)
        for _ in range(units): yield
        if search_done:
            pending = found[0]
        m.out.append(1 if L == 1 else 0)


def run_online(x):
    m = Machine(x); gen = galil(m); cost = []; last = 0
    try:
        while True:
            next(gen)
            while len(m.out) > len(cost):
                cost.append(m.steps - last); last = m.steps
    except StopIteration:
        pass
    while len(cost) < len(m.out):
        cost.append(m.steps - last); last = m.steps
    return m.out, cost


def run_realtime(x, budget):
    m = Machine(x); gen = galil(m); outs = []; done = False
    for t in range(len(x)):
        spent = 0
        while not done and spent < budget and len(m.out) <= t:
            try:
                next(gen); spent += 1
            except StopIteration:
                done = True
        outs.append(m.out[t] if len(m.out) > t else 0)
    return outs


def brute(x):
    return [1 if x[:t + 1] == x[:t + 1][::-1] else 0 for t in range(len(x))]


if __name__ == '__main__':
    bad = 0; tot = 0; worst = 0; worst_x = ''
    for nlen in range(1, 13):
        for t in product('ab', repeat=nlen):
            x = ''.join(t); tot += 1
            out, cost = run_online(x)
            if out != brute(x):
                bad += 1
                if bad <= 5: print('BAD online', x, out, brute(x))
            if max(cost) > worst: worst, worst_x = max(cost), x
    print(f'online: strings {tot} bad {bad} worst cost/symbol {worst} at {worst_x}')
    for x in ('ab' * 100, 'a' * 200, 'aab' * 60, 'abba' * 50, 'aabab' * 40):
        out, cost = run_online(x)
        ok = out == brute(x)
        print(f'  {x[:8]}...: ok={ok} steps/n = {sum(cost)/len(x):.1f}, max step {max(cost)}')
    for budget in (64, 128, 256):
        bad = 0
        for nlen in range(1, 13):
            for t in product('ab', repeat=nlen):
                x = ''.join(t)
                if run_realtime(x, budget) != brute(x): bad += 1
        print(f'realtime budget {budget}: mismatching strings {bad}/{tot}')
