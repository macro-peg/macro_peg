"""Stage 2: Galil's real-time palindrome recogniser as a multitape Turing machine,
built incrementally.  This file = the machinery + `match` + the nonchain `move`
(FPP replaced by KMP on work tapes).  Everything the machine does is a generator
that yields once per elementary tape operation, so cost is counted mechanically.

Model.  Cells hold a symbol and a set of marks.  Heads move by one cell per step.
The input tape receives one symbol per real time unit; a head may not read a cell
that has not arrived yet (the online constraint).  `run_online` drives the
algorithm with unbounded time per symbol and records the cost per symbol;
`run_realtime` gives it a fixed budget per symbol and prints 0 while it lags
(Galil's on-line -> real-time transformation, valid under the predictability
condition, which we check empirically).

Output: y[t] = 1 iff x[0..t] is a palindrome (all initial palindromes, incl. the
trivial one at t = 0).
"""
from itertools import product

MARK = '#'          # left end marker symbol (never matches an input symbol)


class Tape:
    def __init__(self):
        self.cells = []            # list of [symbol, set(marks)]

    def ensure(self, i):
        while len(self.cells) <= i:
            self.cells.append([None, set()])

    def sym(self, i):
        self.ensure(i); return self.cells[i][0]

    def set(self, i, s):
        self.ensure(i); self.cells[i][0] = s

    def marks(self, i):
        self.ensure(i); return self.cells[i][1]


class Machine:
    """One input tape plus work tapes.  `avail` = number of input symbols that have
    arrived (the online constraint).  `steps` counts elementary operations."""
    def __init__(self, x):
        self.x = x
        self.inp = Tape()
        self.inp.set(0, MARK)                  # place 0 = left end marker
        self.avail = 0
        self.steps = 0
        self.out = []

    # --- input feeding -------------------------------------------------------
    def feed(self):
        """make the next input symbol available (place = index+1)."""
        if self.avail < len(self.x):
            self.inp.set(self.avail + 1, self.x[self.avail])
            self.avail += 1

    def read_input(self, place):
        assert place <= self.avail, f'online violation: place {place} not yet available'
        return self.inp.sym(place)

    def tick(self):
        self.steps += 1


# ---------------------------------------------------------------------------- KMP on tapes
def kmp_borders_of_palindrome(m, k, s):
    """All borders of the palindrome at input places [s, k] (length n = k-s+1),
    as a list of lengths in decreasing order.  Reads the window backwards (a
    palindrome read backwards is itself), builds the failure table on a work tape,
    then follows the failure chain from state n.  Linear number of steps.
    Yields once per elementary operation."""
    n = k - s + 1
    fail = Tape()                    # cell j holds fail(j) for j = 1..n
    fail.set(1, 0); m.tick(); yield
    pi = 0
    for j in range(2, n + 1):
        cj = m.read_input(k - j + 1); m.tick(); yield
        while pi > 0 and m.read_input(k - pi) != cj:
            m.tick(); yield
            pi = fail.sym(pi); m.tick(); yield
        m.tick(); yield
        if m.read_input(k - pi) == cj:
            pi += 1
        fail.set(j, pi); m.tick(); yield
    lengths = []
    ell = fail.sym(n); m.tick(); yield
    while ell > 0:
        lengths.append(ell)
        ell = fail.sym(ell); m.tick(); yield
    return lengths


# ---------------------------------------------------------------------------- the algorithm
def galil_nonchain_only(m):
    """Online algorithm: match outwards from a tentative centre; on mismatch, find the
    longest palindromic suffix that extends with the new symbol by KMP (this is the
    nonchain `move` applied always; the chain case comes in stage 3).
    Places: input symbol i (0-based) sits at place i+1; place 0 is the marker.
    Active palindrome = input places [L, R]; it is a palindrome at all times."""
    L = R = 1                                  # first symbol: trivial palindrome
    m.feed(); m.tick(); yield
    m.out.append(1)                            # x[0..0] is a palindrome
    while m.avail < len(m.x):
        m.feed()
        c = m.read_input(R + 1); m.tick(); yield
        # extension test: symbol left of L must equal c
        left = m.read_input(L - 1); m.tick(); yield
        if left == c and left != MARK:
            L -= 1; R += 1
        else:
            # mismatch: borders of the palindrome [L, R], longest first
            lengths = yield from kmp_borders_of_palindrome(m, R, L)
            newL = None
            for ell in lengths:
                st = R - ell + 1
                m.tick(); yield
                if m.read_input(st - 1) == c:
                    newL = st - 1; break
            if newL is None:
                # empty border: "cc" if x[R] == c else "c"
                newL = R if m.read_input(R) == c else R + 1
                m.tick(); yield
            L, R = newL, R + 1
        m.out.append(1 if L == 1 else 0)


# ---------------------------------------------------------------------------- drivers
def run_online(x, algo=galil_nonchain_only):
    m = Machine(x)
    gen = algo(m)
    cost = []                                  # steps spent per output symbol
    last = 0
    try:
        while True:
            next(gen)
            if len(m.out) > len(cost):
                cost.append(m.steps - last); last = m.steps
    except StopIteration:
        pass
    while len(cost) < len(m.out):
        cost.append(m.steps - last); last = m.steps
    return m.out, cost


def run_realtime(x, budget, algo=galil_nonchain_only):
    """Feed one symbol per real step; allow `budget` elementary steps per real step;
    print the algorithm's output when it is caught up, else 0."""
    m = Machine(x)
    gen = algo(m)
    produced = 0
    outs = []
    done = False
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
    bad = 0; tot = 0; worst = 0
    for n in range(1, 13):
        for t in product('ab', repeat=n):
            x = ''.join(t); tot += 1
            out, cost = run_online(x)
            if out != brute(x):
                bad += 1
                if bad <= 3: print('BAD online', x, out, brute(x))
            worst = max(worst, max(cost))
    print(f'online: strings {tot} bad {bad} worst cost/symbol {worst}')
    for x in ('ab' * 100, 'a' * 200, 'aab' * 60, 'abba' * 50):
        out, cost = run_online(x)
        print(f'  {x[:6]}...: total steps/n = {sum(cost)/len(x):.1f}, max step {max(cost)}')
    # real-time with budget: correct only where the predictability condition holds
    for budget in (8, 16, 32):
        bad = 0
        for n in range(1, 13):
            for t in product('ab', repeat=n):
                x = ''.join(t)
                if run_realtime(x, budget) != brute(x): bad += 1
        print(f'realtime budget {budget}: mismatching strings {bad}/{tot}')
