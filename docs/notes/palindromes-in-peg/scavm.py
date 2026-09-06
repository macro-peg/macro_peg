"""A scaffolding-automaton virtual machine (stage 4 target for the palindrome PEG).

Model (Kim & Park, `Common/Model/Scaffolding.lean`): reading one input symbol per
step, the machine creates exactly one node.  A node has a finite label and a bounded
number of pointer fields; pointers may only go to *older* nodes.  The transition
sees the previous top node's neighbourhood of bounded radius; the new node's
pointers must be nodes reached inside that neighbourhood during the step.  The
machine accepts by its finite state (here: the top label's `out` component).

This VM enforces exactly that:
  * `get(node, field)` is the only way to reach a node; it counts one hop and
    records the node as touched this step;
  * `emit(label, **ptrs)` creates the new top; every pointer must be None, the
    previous top, or a node touched this step;
  * labels must be drawn from a finite set (checked: the label must be a tuple of
    small ints / one-character strings / None, and the number of distinct labels
    seen must stay bounded — reported, so a run over long inputs exposes leaks);
  * per-step hop counts are recorded; `radius` = the maximum.

Slots.  One physical node per step is too coarse for structures that must grow
several cells per symbol (the KMP tables run at RATE units per symbol), so a
logical cell is (node, slot) with slot < SLOTS; a slot is finite data and lives in
the label.  Library structures below use that convention.

Library: Stack (a chain of cells, one push per structure per step) and a
real-time Queue (Hood & Melville 1981 style: two stacks plus an incremental
reversal, O(1) worst-case per operation), because "revisit marks left-to-right"
needs a queue while "revisit right-to-left" needs a stack.
"""


class Node:
    __slots__ = ('t', 'label', 'ptr')

    def __init__(self, t, label, ptr):
        self.t = t; self.label = label; self.ptr = ptr

    def __repr__(self):
        return f'N{self.t}{self.label}'


SELF = object()      # pointer target "the node being created" (Lean: LocalTarget.self)


def _finite_label(label):
    if label is None:
        return True
    if isinstance(label, dict):
        return all(isinstance(k, str) and _finite_label(v) for k, v in label.items())
    if isinstance(label, tuple):
        return all(_finite_label(x) for x in label)
    if isinstance(label, bool):
        return True
    if isinstance(label, int):
        return -64 <= label <= 64
    if isinstance(label, str):
        return len(label) <= 16         # short tags, field names, single characters
    return False


class VM:
    def __init__(self):
        self.t = -1
        self.top = None
        self.touched = set()
        self.hops = 0
        self.radius = 0
        self.labels = set()
        self.max_fields = 0

    # ---- reading ---------------------------------------------------------
    def get(self, node, field):
        """follow a pointer field of a node reached this step (one hop)."""
        assert node is None or node is self.top or id(node) in self.touched, \
            f'{node} not reachable this step'
        if node is None:
            return None
        self.hops += 1
        target = node.ptr.get(field)
        if target is not None:
            assert target.t <= node.t, 'pointer to a newer node'
            self.touched.add(id(target))
        return target

    def label(self, node):
        assert node is None or node is self.top or id(node) in self.touched
        return None if node is None else node.label

    # ---- one step ----------------------------------------------------------
    def begin(self):
        self.t += 1
        self.touched = set()
        self.hops = 0

    def emit(self, label, **ptr):
        assert _finite_label(label), f'label not finite-shaped: {label!r}'
        for k, v in ptr.items():
            assert v is None or v is SELF or v is self.top or id(v) in self.touched, \
                f'pointer {k} -> {v} was not reached this step'
        node = Node(self.t, label, ptr)
        for k, v in ptr.items():
            if v is SELF:
                ptr[k] = node
        self.labels.add(repr(label) if isinstance(label, dict) else label)
        self.max_fields = max(self.max_fields, len(ptr))
        self.radius = max(self.radius, self.hops)
        self.top = node
        return node

    def stats(self):
        return dict(steps=self.t + 1, radius=self.radius, fields=self.max_fields,
                    labels=len(self.labels))


# ---------------------------------------------------------------- library structures
# A structure lives in the label/pointer fields of the top node under a name prefix.
# Each is a pure function of (vm, top) -> values to put in the next node, so that the
# transition of a machine composes several of them into one `emit`.

class Stack:
    """Cells are (node, slot).  The stack is represented by a pointer `<name>_top`
    to the physical node of its top cell and a label entry `<name>_slot`; each cell
    stores its value in the label as `<name>_v<slot>` and its predecessor as the
    pointer `<name>_below<slot>` plus label `<name>_bslot<slot>`.  At most one push
    per step is needed by our algorithms; several pushes per step could use slots."""

    def __init__(self, name):
        self.name = name

    def empty(self, vm, top):
        return vm.get(top, self.name + '_top') is None if top is not None else True


# The generic structures above are placeholders for the port; the concrete machine
# below shows the discipline on the simplest piece of Galil's algorithm: the match
# loop with heads L (moving left, one hop) and R (the top).  It recognises exactly
# the palindromes whose characters pair up from the outside without ever needing a
# `move` — i.e. it is stage-2 minus the nonchain move — and is here to validate the
# VM discipline (hops, reachability, finite labels) before the real port.

def demo_extension_only(x):
    """Galil's `match` alone: keep the left neighbour `L` of the active suffix
    palindrome; extend while x[L] == c, otherwise restart from the single symbol.
    Output 1 iff the active palindrome starts at 0.  (Not a palindrome recogniser —
    it lacks `move` — but every operation is one the scaffold allows: prev
    pointers, one label per node, O(1) hops per step.)"""
    vm = VM()
    outs = []
    for c in x:
        vm.begin()
        top = vm.top
        if top is None:
            vm.emit(('sym', c, 1), prev=None, L=None)      # window [0,0]; L = marker
            outs.append(1)
            continue
        L = vm.get(top, 'L')                                # node left of the window
        if L is not None and vm.label(L)[1] == c:
            newL = vm.get(L, 'prev')                        # extend both ways
            out = 1 if newL is None else 0
            vm.emit(('sym', c, out), prev=top, L=newL)
        else:
            out = 0
            vm.emit(('sym', c, out), prev=top, L=top)       # restart: window [t,t]
        outs.append(out)
    return outs, vm.stats()


if __name__ == '__main__':
    from itertools import product
    # the demo only tracks the palindrome centred at the middle of the whole string
    # once L reaches the marker; it is not a palindrome recogniser, just a discipline test
    outs, st = demo_extension_only('abba')
    print('demo abba ->', outs, st)
    outs, st = demo_extension_only('ab' * 50)
    print('demo (ab)^50 ->', outs[-4:], st)
