"""Stage 5, block 1: Galil's `match` + the nonchain `move` (KMP over the palindromic
window) as a scaffolding-automaton program on scavm, with the lag built in.

The one genuinely pointer-hostile operation is KMP's "state += 1": the cell for
state s+1 was created after the cell for s, so no pointer leads there.  A Turing
machine has two heads on the pattern tape (one appending at the end, one at the
state); making that real time is exactly a real-time deque problem (Chuang &
Goldberg 1993).  We do not need a full deque: the cursor's right part is the stack
Rz (cells pushed back by failure jumps) followed by the queue A (cells appended by
the scan); every position in Rz precedes every position in A, so "+1" pops Rz if
nonempty else A's front, a failure jump pops Lz into Rz until the cell below the
top is the jump target (pointer equality), and appending pushes onto A.

State (fields of the top node):
  c        label   input symbol of this node;   prev  ptr  previous input node
  Q.*      real-time queue of input nodes not yet processed by the simulation
  mode     label   'match' | 'kmp' | 'chain'
  L        ptr     leftmost node of the active palindrome [L, simR]
  simR     ptr     rightmost node the simulation has processed
  cur      ptr     node whose symbol is being processed (popped from Q)
  wj       ptr     KMP: window node of the position being scanned
  Lz.* Rz.* A.*    KMP cursor zipper (stacks / queue of cells)
  n        cell    cell of the full window once built (chain walk start)
  K.k.*    KMP cells created this step: fail (ptr) + fslot (lab), wn (ptr)

Window positions count from simR leftwards: position 1 = simR, position q+1 =
prev of position q; cell q stores wn = node of position q, so "pattern symbol at
q+1" = label(prev(wn(cell q))) and "start of the border of length q" = wn(cell q).
The cursor holds s = matched length + 1 = the position to compare; Lz = cells
1..s (top = cell s); state 0 is "Lz has one cell and we are before it", tracked by
the flag `zero`.

Units: one unit = O(1) hops.  `run(x, budget)` executes at most `budget` units per
real step; `budget=None` = unbounded (online correctness).  Output at real step t:
1 iff the simulation is caught up and L is node 0.
"""
from itertools import product
from scavm import VM, SELF
from scavm_structs import Builder, StackView, RTQueueView, emit
DEBUG = False


class Cell:
    __slots__ = ('node', 'slot')

    def __init__(self, node, slot):
        self.node, self.slot = node, slot

    def same(self, other):
        return other is not None and self.node is other.node and self.slot == other.slot


def run(x, budget=None):
    vm = VM()
    outs = []
    for t, cnew in enumerate(x):
        vm.begin()
        prev = vm.top
        b = Builder()
        Q = RTQueueView(vm, prev, b, 'Q')
        A = RTQueueView(vm, prev, b, 'A')
        Lz = StackView(vm, prev, b, 'Lz'); Rz = StackView(vm, prev, b, 'Rz')
        b.ptr['prev'] = prev; b.label['c'] = cnew         # the new node's own fields
        Q.push(SELF); Q.work()                          # the new input node

        # ---- load state -----------------------------------------------------
        if prev is None:
            mode, L, simR, cur, wj, ncell, zero = 'match', None, None, None, None, None, True
        else:
            lab = vm.label(prev)
            mode = lab['mode']; zero = lab['zero']
            L = vm.get(prev, 'L'); simR = vm.get(prev, 'simR'); cur = vm.get(prev, 'cur')
            wj = vm.get(prev, 'wj')
            nn = vm.get(prev, 'n'); ncell = None if nn is None else Cell(nn, lab['nslot'])

        # ---- cell helpers -------------------------------------------------------
        def cget(cell, field):
            key = f'K.{cell.slot}.{field}'
            if cell.node is SELF:
                return b.ptr[key] if field in ('fail', 'wn') else b.label[key]
            if field in ('fail', 'wn'):
                return gt(cell.node, key)
            return vm.label(cell.node)[key]

        def cfail(cell):
            f = cget(cell, 'fail')
            return None if f is None else Cell(f, cget(cell, 'fslot'))

        def new_cell(fail, wn):
            k = b.slot('K')
            b.ptr[f'K.{k}.fail'] = None if fail is None else fail.node
            b.label[f'K.{k}.fslot'] = 0 if fail is None else fail.slot
            b.ptr[f'K.{k}.wn'] = wn
            return Cell(SELF, k)

        def sym(node):
            return cnew if node is SELF else vm.label(node)['c']

        def gt(node, field):
            return b.ptr.get(field) if node is SELF else vm.get(node, field)

        def spush(S, cell): S.push(cell.node, cell.slot)
        def spop(S):        n_, s_ = S.pop2(); return Cell(n_, s_)
        def speek(S):       n_, s_ = S.peek2(); return Cell(n_, s_)
        def sbelow(S):
            r = S.below_of_top(); return None if r is None else Cell(r[0], r[1])
        def qpush(Qx, cell): Qx.push(cell.node, cell.slot); Qx.work()
        def qpop(Qx):        n_, s_ = Qx.pop2(); Qx.work(); return Cell(n_, s_)

        # ---- units --------------------------------------------------------------
        units = 0
        while budget is None or units < budget:
            units += 1
            if mode == 'match':
                if Q.empty():
                    break
                cur = Q.pop(); Q.work()
                c = sym(cur)
                if simR is None:                          # first symbol ever
                    L = cur; simR = cur
                    continue
                Lp = gt(L, 'prev')
                if Lp is not None and sym(Lp) == c:
                    L = Lp; simR = cur                    # extend
                    continue
                # mismatch: failure structure of the window read from simR leftwards
                cell1 = new_cell(None, simR)              # position 1, fail(1) = 0
                Lz.top = None; Rz.top = None; A.clear()
                spush(Lz, cell1); zero = True             # cursor at position 1, matched 0
                if simR is L:                             # window of length 1
                    mode = 'chain'; ncell = cell1
                else:
                    mode = 'kmp'; wj = gt(simR, 'prev')   # position 2
            elif mode == 'kmp':
                cj = sym(wj)
                s_cell = speek(Lz)                        # position s to compare
                if DEBUG: print(f'   kmp unit: pos wj.t={getattr(wj,"t","SELF")} cj={cj} s_cell=slot{s_cell.slot} wn.t={getattr(cget(s_cell,"wn"),"t","SELF")} sym={sym(cget(s_cell,"wn"))} zero={zero} Rz_empty={Rz.empty()}')
                if sym(cget(s_cell, 'wn')) == cj:
                    # match at position s: fail(j) = s (matched length), s += 1
                    newc = new_cell(s_cell, wj)
                    if DEBUG: print('   kmp match: pos wj.t', getattr(wj,'t','SELF'), 'fail-> cell slot', s_cell.slot, 'wn.t', getattr(cget(s_cell,'wn'),'t','SELF'))
                    qpush(A, newc)
                    if not Rz.empty():
                        spush(Lz, spop(Rz))
                    else:
                        spush(Lz, qpop(A))
                    zero = False
                elif zero:
                    # matched length 0 and mismatch: fail(j) = 0, stay
                    newc = new_cell(None, wj)
                    qpush(A, newc)
                else:
                    # failure jump: matched length s-1 -> fail(s-1); new position =
                    # fail(s-1)+1, i.e. pop Lz into Rz until the cell below the top
                    # is cell(fail(s-1)) (None -> until one cell is left, then zero)
                    target = cfail(sbelow(Lz))
                    while True:
                        bel = sbelow(Lz)
                        if bel is None:
                            zero = True; break
                        if bel.same(target):
                            break
                        spush(Rz, spop(Lz))
                    continue                              # retry the compare
                if wj is L:
                    mode = 'chain'; ncell = newc
                else:
                    wj = gt(wj, 'prev')
            elif mode == 'chain':
                c = sym(cur)
                cand = cfail(ncell)                       # longest proper border
                if DEBUG: print('   chain: ncell', ncell.node if ncell.node is not SELF else 'SELF', ncell.slot, '-> cand', None if cand is None else (cand.slot, getattr(cget(cand,'wn'),'t','SELF')))
                if cand is None:
                    L = simR if sym(simR) == c else cur   # "cc" or "c"
                    simR = cur; mode = 'match'
                    continue
                start = cget(cand, 'wn')
                before = gt(start, 'prev')
                if before is not None and sym(before) == c:
                    L = before; simR = cur; mode = 'match'
                else:
                    ncell = cand
            else:
                raise RuntimeError(mode)

        caught_up = Q.empty() and mode == 'match'
        out = 1 if (caught_up and L is not None and gt(L, 'prev') is None) else 0
        Q.work(); A.work()
        Q.finalize(); A.finalize(); Lz.finalize(); Rz.finalize()
        b.label['mode'] = mode; b.label['out'] = out; b.label['zero'] = zero
        b.ptr['L'] = L; b.ptr['simR'] = simR; b.ptr['cur'] = cur; b.ptr['wj'] = wj
        b.ptr['n'] = None if ncell is None else ncell.node
        b.label['nslot'] = 0 if ncell is None else ncell.slot
        emit(vm, b)
        outs.append(out)
    return outs, vm.stats()


def brute(x):
    return [1 if x[:t + 1] == x[:t + 1][::-1] else 0 for t in range(len(x))]


if __name__ == '__main__':
    bad = 0; tot = 0; worst = None
    for n in range(1, 11):
        for tup in product('ab', repeat=n):
            x = ''.join(tup); tot += 1
            outs, st = run(x)
            if outs != brute(x):
                bad += 1
                if bad <= 5: print('BAD', x, outs, brute(x))
            if worst is None or st['radius'] > worst['radius']: worst = st
    print(f'online (unbounded budget): strings {tot} bad {bad} worst stats {worst}')
