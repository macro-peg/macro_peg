"""Components of the palindrome machine on the scaffolding VM (block 2 parts).

Places.  Input symbol i sits at place 2i+1 (node i, half 0); the space after it is
place 2i+2 (node i, half 1); place 0 is the marker.  A place is `Place(node, half)`;
`pleft` is one place to the left; `psym` reads its symbol (SPACE for half 1).

KMP over a window.  `KmpWindow` builds the KMP failure structure of the place
string read from `hi` leftwards down to `lo` (the palindromic window is its own
reverse), one unit per elementary operation, with the cursor zipper Lz / Rz / A of
block 1.  Cells: fail, wn (window place: node + half), pred (previous cell = the
position below), all as (node, slot) references.  Result: the cell of the full
window (`ncell`); the failure chain from it lists the palindromic suffixes of the
window in decreasing length, i.e. the palindromes ending at `hi`.

Marks and the double-palindrome check.  `Marks` walks the failure chain and the
pred chain of the cells in lockstep, creating one mark cell per position with a
bit "on the chain" — Galil's marks of the left ends of initial palindromes; the
mark cells link to the previous position, so `DpCheck` can walk them with two
cursors at speeds 2 and 4 (positions 2k+1 and 4k+1) and report the smallest k
above a threshold with both bits set.  Positions are never compared as numbers:
the cursors are aligned by lockstep walks from the top.

Everything here is unit-stepped: `step()` does O(1) hops and returns True while
work remains.  State lives in the Builder under a name prefix.
"""
from scavm import SELF
from scavm_structs import Builder, StackView, RTQueueView, emit

SPACE = '_'


class Place:
    __slots__ = ('node', 'half')

    def __init__(self, node, half):
        self.node, self.half = node, half

    def same(self, o):
        return o is not None and self.node is o.node and self.half == o.half


class Ctx:
    """per-step context: vm, previous top, builder, this step's symbol."""

    def __init__(self, vm, prev, b, cnew):
        self.vm, self.prev, self.b, self.cnew = vm, prev, b, cnew

    # node fields (SELF-aware)
    def get(self, node, field):
        return self.b.ptr.get(field) if node is SELF else self.vm.get(node, field)

    def lab(self, node, field):
        return self.b.label.get(field) if node is SELF else self.vm.label(node)[field]

    def sym(self, node):
        return self.cnew if node is SELF else self.vm.label(node)['c']

    # places
    def pleft(self, p):
        if p is None:
            return None
        if p.half == 1:
            return Place(p.node, 0)
        pn = self.get(p.node, 'prev')
        return None if pn is None else Place(pn, 1)

    def psym(self, p):
        if p is None:
            return None                    # the marker
        return SPACE if p.half == 1 else self.sym(p.node)

    # persisted place fields
    def load_place(self, name):
        if self.prev is None:
            return None
        n = self.vm.get(self.prev, name)
        return None if n is None else Place(n, self.vm.label(self.prev)[name + '.h'])

    def save_place(self, name, p):
        self.b.ptr[name] = None if p is None else p.node
        self.b.label[name + '.h'] = 0 if p is None else p.half

    # persisted cell references
    def load_cell(self, name):
        if self.prev is None:
            return None
        n = self.vm.get(self.prev, name)
        return None if n is None else Cell(n, self.vm.label(self.prev)[name + '.s'])

    def save_cell(self, name, c):
        self.b.ptr[name] = None if c is None else c.node
        self.b.label[name + '.s'] = 0 if c is None else c.slot

    def load_lab(self, name, default):
        return default if self.prev is None else self.vm.label(self.prev)[name]


class Cell:
    __slots__ = ('node', 'slot')

    def __init__(self, node, slot):
        self.node, self.slot = node, slot

    def same(self, o):
        return o is not None and self.node is o.node and self.slot == o.slot


class Cells:
    """a family of cells under a prefix; fields: pointers and labels."""

    def __init__(self, ctx, prefix, ptr_fields, lab_fields):
        self.ctx, self.prefix = ctx, prefix
        self.ptr_fields, self.lab_fields = ptr_fields, lab_fields

    def key(self, cell, f):
        return f'{self.prefix}.{cell.slot}.{f}'

    def get(self, cell, f):
        k = self.key(cell, f)
        if f in self.ptr_fields:
            return self.ctx.get(cell.node, k)
        return self.ctx.lab(cell.node, k)

    def get_cell(self, cell, f):
        n = self.get(cell, f)
        return None if n is None else Cell(n, self.get(cell, f + '.s'))

    def get_place(self, cell, f):
        n = self.get(cell, f)
        return None if n is None else Place(n, self.get(cell, f + '.h'))

    def new(self, **kw):
        k = self.ctx.b.slot(self.prefix)
        c = Cell(SELF, k)
        for f, v in kw.items():
            if isinstance(v, Cell):
                self.ctx.b.ptr[self.key(c, f)] = v.node
                self.ctx.b.label[self.key(c, f + '.s')] = v.slot
            elif isinstance(v, Place):
                self.ctx.b.ptr[self.key(c, f)] = v.node
                self.ctx.b.label[self.key(c, f + '.h')] = v.half
            elif f in self.ptr_fields:
                self.ctx.b.ptr[self.key(c, f)] = v
                if f + '.s' in self.lab_fields:
                    self.ctx.b.label[self.key(c, f + '.s')] = 0
                if f + '.h' in self.lab_fields:
                    self.ctx.b.label[self.key(c, f + '.h')] = 0
            else:
                self.ctx.b.label[self.key(c, f)] = v
        return c


# ------------------------------------------------------------------ KMP over a window
class KmpWindow:
    """Failure structure of the place string hi, hi-1, ..., lo (read leftwards).
    Cursor zipper as in block 1.  `start(lo, hi)` then `step()` until it returns
    False; then `ncell` is the cell of the full window."""
    PTR = ('fail', 'wn', 'pred')
    LAB = ('fail.s', 'wn.h', 'pred.s')

    def __init__(self, ctx, name):
        self.ctx, self.name = ctx, name
        self.cells = Cells(ctx, name + '.K', self.PTR, self.LAB)
        self.Lz = StackView(ctx.vm, ctx.prev, ctx.b, name + '.Lz')
        self.Rz = StackView(ctx.vm, ctx.prev, ctx.b, name + '.Rz')
        self.A = RTQueueView(ctx.vm, ctx.prev, ctx.b, name + '.A')
        self.active = ctx.load_lab(name + '.active', False)
        self.zero = ctx.load_lab(name + '.zero', True)
        self.jumping = ctx.load_lab(name + '.jumping', False)
        self.lo = ctx.load_place(name + '.lo')
        self.wj = ctx.load_place(name + '.wj')
        self.last = ctx.load_cell(name + '.last')      # last created cell (position j-1)
        self.jt = ctx.load_cell(name + '.jt')
        self.ncell = ctx.load_cell(name + '.ncell')

    def start(self, lo, hi):
        self.lo = lo
        self.Lz.top = None; self.Rz.top = None; self.A.clear()
        c1 = self.cells.new(fail=None, wn=hi, pred=None)
        self._spush(self.Lz, c1); self.zero = True; self.jumping = False; self.jt = None
        self.last = c1; self.ncell = None
        if hi.same(lo):
            self.ncell = c1; self.active = False
        else:
            self.wj = self.ctx.pleft(hi); self.active = True

    # stack/queue helpers for cells
    def _spush(self, S, c): S.push(c.node, c.slot)
    def _spop(self, S):     n, s = S.pop2(); return Cell(n, s)
    def _speek(self, S):    n, s = S.peek2(); return Cell(n, s)
    def _sbelow(self, S):
        r = S.below_of_top(); return None if r is None else Cell(r[0], r[1])
    def _qpush(self, c):    self.A.push(c.node, c.slot); self.A.work()
    def _qpop(self):        n, s = self.A.pop2(); self.A.work(); return Cell(n, s)

    def step(self):
        """one unit; returns True while the scan continues."""
        if not self.active:
            return False
        ctx = self.ctx
        cj = ctx.psym(self.wj)
        s_cell = self._speek(self.Lz)
        if ctx.psym(self.cells.get_place(s_cell, 'wn')) == cj:
            newc = self.cells.new(fail=s_cell, wn=self.wj, pred=self.last)
            self._qpush(newc)
            if not self.Rz.empty():
                self._spush(self.Lz, self._spop(self.Rz))
            else:
                self._spush(self.Lz, self._qpop())
            self.zero = False
        elif self.zero:
            newc = self.cells.new(fail=None, wn=self.wj, pred=self.last)
            self._qpush(newc)
        else:
            if not self.jumping:
                self.jt = self.cells.get_cell(self._sbelow(self.Lz), 'fail'); self.jumping = True
            bel = self._sbelow(self.Lz)
            if bel is None:
                self.zero = True; self.jumping = False
            elif bel.same(self.jt):
                self.jumping = False
            else:
                self._spush(self.Rz, self._spop(self.Lz))
            return True
        self.last = newc
        if self.wj.same(self.lo):
            self.ncell = newc; self.active = False
            return False
        self.wj = ctx.pleft(self.wj)
        return True

    def save(self):
        ctx = self.ctx; n = self.name
        self.Lz.finalize(); self.Rz.finalize(); self.A.finalize()
        ctx.b.label[n + '.active'] = self.active; ctx.b.label[n + '.zero'] = self.zero
        ctx.b.label[n + '.jumping'] = self.jumping
        ctx.save_place(n + '.lo', self.lo); ctx.save_place(n + '.wj', self.wj)
        ctx.save_cell(n + '.last', self.last); ctx.save_cell(n + '.jt', self.jt)
        ctx.save_cell(n + '.ncell', self.ncell)

    def fail_of(self, cell):
        return self.cells.get_cell(cell, 'fail')

    def wn_of(self, cell):
        return self.cells.get_place(cell, 'wn')

    def pred_of(self, cell):
        return self.cells.get_cell(cell, 'pred')


# ------------------------------------------------------------------ marks + dp check
class Marks:
    """Walk the failure chain F and the pred chain P of a KmpWindow from its ncell
    in lockstep, creating one mark cell per position (descending) with bit `on` =
    the position is on the failure chain (a palindrome ending at hi starts there).
    Mark cells: ptr `below` (next lower position's mark), `kc` (the KMP cell), lab `on`."""
    PTR = ('below', 'kc')
    LAB = ('below.s', 'kc.s', 'on')

    def __init__(self, ctx, name, kmp):
        self.ctx, self.name, self.kmp = ctx, name, kmp
        self.cells = Cells(ctx, name + '.M', self.PTR, self.LAB)
        self.active = ctx.load_lab(name + '.active', False)
        self.F = ctx.load_cell(name + '.F')       # next chain cell to be met
        self.P = ctx.load_cell(name + '.P')       # current position cell
        self.last = ctx.load_cell(name + '.last') # last mark created
        self.top = ctx.load_cell(name + '.top')   # mark of the highest position

    def start(self):
        n = self.kmp.ncell
        self.F = n; self.P = n; self.last = None; self.top = None; self.active = True

    def step(self):
        if not self.active:
            return False
        on = self.F is not None and self.P.same(self.F)
        m = self.cells.new(below=None, kc=self.P, on=on)
        # link the previous (higher) mark down to this one: impossible (immutable) —
        # so we link upwards instead: this mark points to the previous mark as `below`?
        # No: descending creation means the *newer* mark is the lower position; keep
        # `below` = None and let `up` = previous mark; cursors walk downwards via
        # the chain of creation, i.e. the later-created marks.  We therefore store
        # `up` (older = higher position) and walk from the *bottom* mark upwards
        # when a descending traversal is needed.  See DpCheck.
        self.cells.ctx.b.ptr[self.cells.key(m, 'below')] = None if self.last is None else self.last.node
        self.cells.ctx.b.label[self.cells.key(m, 'below.s')] = 0 if self.last is None else self.last.slot
        if self.top is None:
            self.top = m
        self.last = m
        if on:
            self.F = self.kmp.fail_of(self.F)
        self.P = self.kmp.pred_of(self.P)
        if self.P is None:
            self.active = False
            return False
        return True

    def save(self):
        ctx = self.ctx; n = self.name
        ctx.b.label[n + '.active'] = self.active
        ctx.save_cell(n + '.F', self.F); ctx.save_cell(n + '.P', self.P)
        ctx.save_cell(n + '.last', self.last); ctx.save_cell(n + '.top', self.top)

    def up_of(self, m):            # the mark of the next higher position
        return self.cells.get_cell(m, 'below')

    def on(self, m):
        return self.cells.get(m, 'on')

    def kc_of(self, m):
        return self.cells.get_cell(m, 'kc')


class DpCheck:
    """Given the marks (bottom = position 1 = `last`, chain upwards via `up_of`),
    find the smallest k > r such that positions 2k+1 and 4k+1 are both marked.
    Cursor P2 walks 2 marks per unit, P4 walks 4 marks per unit, both upwards
    from the bottom (position 1), so at unit k they sit at 2k+1 and 4k+1.  The
    threshold r is given as the mark at position 2r+1 (`rmark`, None for r = 0):
    k > r is enforced by skipping until P2 has passed rmark.  Stops when P4 runs
    out (4k+1 > n)."""

    def __init__(self, ctx, name, marks):
        self.ctx, self.name, self.marks = ctx, name, marks
        self.active = ctx.load_lab(name + '.active', False)
        self.P2 = ctx.load_cell(name + '.P2'); self.P4 = ctx.load_cell(name + '.P4')
        self.rmark = ctx.load_cell(name + '.rmark')
        self.passed = ctx.load_lab(name + '.passed', False)
        self.found = ctx.load_cell(name + '.found')   # mark of position 2k+1 when found
        self.found4 = ctx.load_cell(name + '.found4')

    def start(self, rmark):
        self.P2 = self.marks.last; self.P4 = self.marks.last
        self.rmark = rmark; self.passed = rmark is None
        self.found = None; self.found4 = None; self.active = True

    def step(self):
        if not self.active:
            return False
        up = self.marks.up_of
        p2 = self.P2; p4 = self.P4
        for _ in range(2):
            p2 = None if p2 is None else up(p2)
        for _ in range(4):
            p4 = None if p4 is None else up(p4)
        if p2 is None or p4 is None:
            self.active = False
            return False
        self.P2, self.P4 = p2, p4
        if not self.passed:
            if self.rmark is not None and p2.same(self.rmark):
                self.passed = True
            return True
        if self.marks.on(p2) and self.marks.on(p4):
            self.found = p2; self.found4 = p4; self.active = False
            return False
        return True

    def save(self):
        ctx = self.ctx; n = self.name
        ctx.b.label[n + '.active'] = self.active; ctx.b.label[n + '.passed'] = self.passed
        ctx.save_cell(n + '.P2', self.P2); ctx.save_cell(n + '.P4', self.P4)
        ctx.save_cell(n + '.rmark', self.rmark); ctx.save_cell(n + '.found', self.found)
        ctx.save_cell(n + '.found4', self.found4)


# ------------------------------------------------------------------ tests
def _place_of(nodes, place):
    """integer place -> Place over a list of nodes (place 2i+1 = node i)."""
    i = (place - 1) // 2
    return Place(nodes[i], 0 if place % 2 == 1 else 1)


def test_kmp_and_dp(x, lo, hi, r=0, verbose=False):
    """Feed x into the VM (one node per symbol), then run KmpWindow over places
    [lo, hi], Marks and DpCheck across further steps; compare with brute force."""
    from scavm import VM
    from stage3_tm_galil_chain import kmp_suffix_palindromes  # brute reference below instead
    vm = VM()
    nodes = []
    # feed the input
    for c in x:
        vm.begin(); b = Builder(); b.ptr['prev'] = vm.top; b.label['c'] = c
        n = emit(vm, b); nodes.append(n)
    # then drive the components over extra steps with dummy symbols
    phase = 'kmp'; result = None; units = 0
    for extra in range(4 * (hi - lo + 1) + 20):
        vm.begin(); prev = vm.top; b = Builder(); ctx = Ctx(vm, prev, b, 'a')
        b.ptr['prev'] = prev; b.label['c'] = 'a'
        K = KmpWindow(ctx, 'K'); M = Marks(ctx, 'M', K); D = DpCheck(ctx, 'D', M)
        if extra == 0:
            K.start(_place_of(nodes, lo), _place_of(nodes, hi))
            if not K.active:
                phase = 'marks'; M.start()
        else:
            if phase == 'kmp':
                if not K.step():
                    phase = 'marks'; M.start()
            elif phase == 'marks':
                if not M.step():
                    phase = 'dp'
                    rmark = None
                    if r > 0:
                        # mark of position 2r+1: walk up 2r from the bottom
                        rm = M.last
                        for _ in range(2 * r):
                            rm = M.up_of(rm)
                        rmark = rm
                    D.start(rmark)
            elif phase == 'dp':
                if not D.step():
                    phase = 'done'
                    if D.found is not None:
                        # k from the found mark: count its position by walking down
                        pos = 1; m = D.found
                        # position of a mark = 1 + number of `below` hops to the bottom
                        # (bottom = position 1): walk via kc->pred count instead
                        kc = M.kc_of(D.found); cnt = 0
                        while kc is not None:
                            kc = K.pred_of(kc); cnt += 1
                        result = (cnt - 1) // 2
                    else:
                        result = None
        units += 1
        K.save(); M.save(); D.save()
        emit(vm, b)
        if phase == 'done':
            break
    # brute force: places string
    places = '#' + ''.join(c + SPACE for c in x)
    win = places[lo:hi + 1]
    pal_lengths = {ell for ell in range(1, len(win) + 1) if win[-ell:] == win[-ell:][::-1]}
    hmax = (hi - lo) // 4
    exp = next((k for k in range(r + 1, hmax + 1) if (2*k+1) in pal_lengths and (4*k+1) in pal_lengths), None)
    return result, exp, vm.stats()


if __name__ == '__main__':
    import random
    random.seed(11)
    bad = 0; tot = 0; worst = 0
    for trial in range(300):
        n = random.randint(2, 14)
        x = ''.join(random.choice('ab') for _ in range(n))
        if trial % 3 == 0:
            p = ''.join(random.choice('ab') for _ in range(random.randint(1, 3))); x = (p * 8)[:n]
        hi = random.randint(1, 2 * n)
        lo = random.randint(1, hi)
        r = random.choice([0, 0, 1, 2])
        got, exp, st = test_kmp_and_dp(x, lo, hi, r)
        tot += 1
        if got != exp:
            bad += 1
            if bad <= 5: print('BAD', x, lo, hi, r, 'got', got, 'exp', exp)
        worst = max(worst, st['radius'])
    print(f'kmp+marks+dp: {tot} trials, bad {bad}, max hops/step {worst}')
