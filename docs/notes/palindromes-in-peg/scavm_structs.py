"""Library structures on the scaffolding VM: stacks, unary counters and a real-time
queue (Hood & Melville 1981), all built from the one-node-per-step discipline.

Every structure keeps its state in the fields of the current top node under a name
prefix.  Within one step, a structure is manipulated through a *view* that reads
the previous top (via vm.get, one hop each) and writes the new node's fields into a
Builder; `finalize` records the new top of the structure.  Cells created this step
live in the new node and are addressed with SELF; several cells of the same stack
may be created in one step, distinguished by a slot index.

Stack cell (node, k): pointer `<n>.<k>.val`, pointer `<n>.<k>.below`, label
`<n>.<k>.bslot`.  Stack top: pointer `<n>.top`, label `<n>.tslot` (None = empty).

Counter: two stacks `pos`, `neg` (values unused); value = |pos| - |neg|.

RTQueue: front stack F, rear stack B, and during a rotation the reversed copies
Fr, Br, the rotation walkers WF, WB over the immutable F and B chains, the new rear
B2, and counters m (elements of F not yet popped live) and c (lenF - lenB, with
elements in rotation counted as front).  ROT units of rotation work per step.
"""
from collections import deque
import random
from scavm import VM, SELF


class Builder:
    def __init__(self):
        self.label = {}
        self.ptr = {}
        self.slots = {}       # name -> next free slot index in the new node

    def slot(self, name):
        k = self.slots.get(name, 0)
        self.slots[name] = k + 1
        return k


class StackView:
    """A stack manipulated during one step.  `top` is (node, slot) or None; node may be
    SELF for cells created this step."""

    def __init__(self, vm, prev, b, name):
        self.vm, self.prev, self.b, self.name = vm, prev, b, name
        # a cell reference is (node, creator-name, slot); cells keep the field names
        # of the stack that created them, so aliasing chains (copy_from) is sound
        if prev is None:
            self.top = None
        else:
            node = vm.get(prev, name + '.top')
            lab = vm.label(prev)
            self.top = None if node is None else (node, lab[name + '.tname'], lab[name + '.tslot'])

    def _cell(self, cell, field):
        node, cname, k = cell
        key = f'{cname}.{k}.{field}'
        if node is SELF:
            return self.b.ptr[key] if field in ('val', 'below') else self.b.label[key]
        if field in ('val', 'below'):
            return self.vm.get(node, key)
        return self.vm.label(node)[key]

    def empty(self):
        return self.top is None

    def peek(self):
        return self._cell(self.top, 'val')

    def pop(self):
        v = self._cell(self.top, 'val')
        below = self._cell(self.top, 'below')
        self.top = None if below is None else \
            (below, self._cell(self.top, 'bname'), self._cell(self.top, 'bslot'))
        return v

    def push(self, v):
        k = self.b.slot(self.name)
        self.b.ptr[f'{self.name}.{k}.val'] = v
        if self.top is None:
            self.b.ptr[f'{self.name}.{k}.below'] = None
            self.b.label[f'{self.name}.{k}.bname'] = ''
            self.b.label[f'{self.name}.{k}.bslot'] = 0
        else:
            self.b.ptr[f'{self.name}.{k}.below'] = self.top[0]
            self.b.label[f'{self.name}.{k}.bname'] = self.top[1]
            self.b.label[f'{self.name}.{k}.bslot'] = self.top[2]
        self.top = (SELF, self.name, k)

    def copy_from(self, other):
        """make this stack an alias of another stack's current chain (O(1))."""
        self.top = other.top

    def finalize(self):
        if self.top is None:
            self.b.ptr[self.name + '.top'] = None
            self.b.label[self.name + '.tname'] = ''
            self.b.label[self.name + '.tslot'] = 0
        else:
            self.b.ptr[self.name + '.top'] = self.top[0]
            self.b.label[self.name + '.tname'] = self.top[1]
            self.b.label[self.name + '.tslot'] = self.top[2]


class CounterView:
    def __init__(self, vm, prev, b, name):
        self.pos = StackView(vm, prev, b, name + '.pos')
        self.neg = StackView(vm, prev, b, name + '.neg')

    def inc(self):
        if not self.neg.empty(): self.neg.pop()
        else: self.pos.push(None)

    def dec(self):
        if not self.pos.empty(): self.pos.pop()
        else: self.neg.push(None)

    def sign(self):
        if not self.pos.empty(): return 1
        if not self.neg.empty(): return -1
        return 0

    def reset(self):
        self.pos.top = None; self.neg.top = None

    def finalize(self):
        self.pos.finalize(); self.neg.finalize()


ROT = 3     # rotation units per step


class RTQueueView:
    NAMES = ('F', 'B', 'Fr', 'Br', 'WF', 'WB', 'B2')

    def __init__(self, vm, prev, b, name):
        self.vm, self.b, self.name = vm, b, name
        self.s = {n: StackView(vm, prev, b, f'{name}.{n}') for n in self.NAMES}
        self.m = CounterView(vm, prev, b, name + '.m')
        self.c = CounterView(vm, prev, b, name + '.c')
        self.phase = 'idle' if prev is None else vm.label(prev)[name + '.phase']

    # --- client operations (at most one push and one pop per step) ---------------
    def push(self, v):
        if self.phase == 'idle':
            self.s['B'].push(v)
        else:
            self.s['B2'].push(v)
        self.c.dec()

    def pop(self):
        F = self.s['F']
        assert not F.empty(), 'pop on an empty front: real-time invariant violated'
        v = F.pop()
        self.c.dec()
        if self.phase != 'idle':
            self.m.dec()
            self._maybe_finish()
        return v

    def empty(self):
        return self.s['F'].empty() and self.s['B'].empty() and self.phase == 'idle'

    # --- rotation ---------------------------------------------------------------
    def _start(self):
        s = self.s
        s['WF'].copy_from(s['F']); s['WB'].copy_from(s['B'])
        s['Fr'].top = None; s['Br'].top = None; s['B2'].top = None
        self.m.reset()
        self.phase = 'rev'

    def _unit(self):
        s = self.s
        if self.phase == 'rev':
            progressed = False
            if not s['WB'].empty():
                s['Br'].push(s['WB'].pop()); self.c.inc(); self.c.inc(); progressed = True
            if not s['WF'].empty():
                s['Fr'].push(s['WF'].pop()); self.m.inc(); progressed = True
            if not progressed:
                self.phase = 'copy'
                self._maybe_finish()
        elif self.phase == 'copy':
            if self.m.sign() > 0 and not s['Fr'].empty():
                s['Br'].push(s['Fr'].pop()); self.m.dec()
            self._maybe_finish()

    def _maybe_finish(self):
        if self.phase == 'copy' and self.m.sign() == 0:
            s = self.s
            s['F'].copy_from(s['Br']); s['B'].copy_from(s['B2'])
            s['Br'].top = None; s['B2'].top = None; s['Fr'].top = None
            self.phase = 'idle'

    def work(self):
        for _ in range(ROT):
            if self.phase == 'idle' and self.c.sign() < 0:
                self._start()
            if self.phase != 'idle':
                self._unit()

    def finalize(self):
        for v in self.s.values(): v.finalize()
        self.m.finalize(); self.c.finalize()
        self.b.label[self.name + '.phase'] = self.phase


def emit(vm, b):
    return vm.emit(b.label, **b.ptr)


# ------------------------------------------------------------------------- tests
def test_stack(n=200, seed=1):
    random.seed(seed)
    vm = VM(); model = []
    for t in range(n):
        vm.begin(); b = Builder()
        S = StackView(vm, vm.top, b, 'S')
        # values: pointers to the previous top (an older node) or None
        for _ in range(random.randint(0, 3)):
            op = random.choice('pp o')
            if op == 'p':
                v = vm.top; S.push(v); model.append(v)
            elif op == 'o' and model:
                got = S.pop(); exp = model.pop()
                assert got is exp, (t, got, exp)
        assert S.empty() == (not model)
        S.finalize(); emit(vm, b)
    return vm.stats()


def test_queue(n=3000, seed=2, verbose=False):
    random.seed(seed)
    vm = VM(); model = deque()
    for t in range(n):
        vm.begin(); b = Builder()
        Q = RTQueueView(vm, vm.top, b, 'Q')
        r = random.random()
        if r < 0.5 or not model:
            v = vm.top                      # push a pointer to an older node
            Q.push(v); model.append(v)
        else:
            got = Q.pop(); exp = model.popleft()
            assert got is exp, (t, got, exp)
        Q.work()
        assert Q.empty() == (not model), (t, Q.phase, len(model))
        Q.finalize(); emit(vm, b)
    return vm.stats()


if __name__ == '__main__':
    print('stack  ', test_stack())
    print('queue  ', test_queue())
    # adversarial: long push phase then long pop phase, repeated
    vm = VM(); model = deque(); ops = []
    for rep in range(6):
        ops += ['push'] * (50 * (rep + 1)) + ['pop'] * (50 * (rep + 1))
    for t, op in enumerate(ops):
        vm.begin(); b = Builder(); Q = RTQueueView(vm, vm.top, b, 'Q')
        if op == 'push':
            v = vm.top; Q.push(v); model.append(v)
        else:
            got = Q.pop(); exp = model.popleft(); assert got is exp, t
        Q.work(); Q.finalize(); emit(vm, b)
    print('queue adversarial', vm.stats())
