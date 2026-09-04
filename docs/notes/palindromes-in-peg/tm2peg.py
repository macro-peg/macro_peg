"""Compile a real-time multitape Turing machine into a plain PEG.

Why this works.  A packrat PEG assigns to each (rule, position) one value: failure
or a position no earlier than the current one.  Reading the input w from the left,
the rules at position i may only depend on rules at positions >= i, so the memo table
is filled right to left — which is exactly a machine reading reverse(w) left to right.
Fix the correspondence

    PEG position i   <->   the configuration after the machine has read
                           reverse(w)[0 .. n-1-i], i.e. after reading w[i]

so a rule at position i is computed from rules at position i+1 (the previous
configuration) and the character w[i] (the symbol just read), and position n (past
the end of the input, recognisable by `!.`) carries the initial configuration.  The
machine accepts iff the state at position 0 is accepting.

Each tape is a zipper (left stack, focus symbol, right stack), as in Kim & Park's
TM -> SCA compiler, so no position or identity comparison is ever needed:

    Lt_j    value = the position at which the current top cell of tape j's left
            stack was pushed; fails when the stack is empty
    Rt_j    the same for the right stack
    Lsym_j_s / Rsym_j_s   predicate: the cell pushed here carries symbol s
    Sc_j_s  predicate: the focus symbol of tape j is s
    St_q    predicate: the control state is q

and the stack operations become

    unchanged      Lt_j  <-  . Lt_j                     (the value at position i+1)
    push here      Lt_j  <-  ""                         (this position)
    pop            Lt_j  <-  . Lt_j . Lt_j              (the cell below the top)

because the cell below the top of the stack at position p is the top of the stack at
position p+1.  Reading the symbol of the top cell is `. Lt_j Lsym_j_s`.

The machine is real time: one input symbol per step, a bounded number of head moves
per step.  `TM.delta[(state, scanned)] = (state', [(write, move), ...])` where
`scanned` is the tuple of focus symbols *and* the input symbol, and move is in
{'L', 'S', 'R'}.
"""
from itertools import product

BLANK = '_'


class TM:
    def __init__(self, ntapes, states, initial, accepting, delta, input_alphabet='ab',
                 tape_alphabet=None, blank=BLANK):
        self.ntapes = ntapes
        self.states = list(states)
        self.initial = initial
        self.accepting = set(accepting)
        self.delta = delta                 # (state, focus_tuple, input_char) -> (state', ops)
        self.input_alphabet = input_alphabet
        self.blank = blank
        syms = {blank}
        for (q, foc, c), (q2, ops) in delta.items():
            syms.update(foc); syms.update(w for w, m in ops)
        self.tape_alphabet = sorted(tape_alphabet if tape_alphabet else syms)

    # ---------------------------------------------------------------- simulation
    def run(self, w):
        """returns the list of outputs (1 = accepting) after each symbol of reverse(w),
        and the final acceptance for the whole input."""
        x = w[::-1]
        state = self.initial
        tapes = [([], self.blank, []) for _ in range(self.ntapes)]   # left, focus, right
        for c in x:
            foc = tuple(t[1] for t in tapes)
            key = (state, foc, c)
            if key not in self.delta:
                raise KeyError(f'no transition for {key}')
            state, ops = self.delta[key]
            new = []
            for (left, f, right), (write, move) in zip(tapes, ops):
                if move == 'S':
                    new.append((left, write, right))
                elif move == 'R':
                    nf = right[-1] if right else self.blank
                    new.append((left + [write], nf, right[:-1]))
                elif move == 'L':
                    nf = left[-1] if left else self.blank
                    new.append((left[:-1], nf, right + [write]))
                else:
                    raise ValueError(move)
            tapes = new
        return state in self.accepting

    # ---------------------------------------------------------------- compilation
    def compile(self):
        Q, T = self.states, range(self.ntapes)
        A = self.tape_alphabet
        rules = []

        def prev(expr):                       # the value of `expr` at position i+1
            return f'. {expr}'

        def guard(expr):
            return f'&({expr})'

        idx0 = {s: k for k, s in enumerate(A)}

        # conditions identifying one delta entry, evaluated at position i
        def cond(key):
            q, foc, c = key
            parts = [f'&("{c}")', guard(prev(f'St_{q}'))]
            for j, s in enumerate(foc):
                parts.append(guard(prev(f'Sc_{j}_{sym_id(s)}')))
            return ' '.join(parts)

        idx = {s: k for k, s in enumerate(A)}

        def sym_id(s):
            return f's{idx[s]}'

        entries = list(self.delta.items())

        # ---- state rules
        for q in Q:
            alts = []
            if q == self.initial:
                alts.append('&(!.)')
            for key, (q2, ops) in entries:
                if q2 == q:
                    alts.append(cond(key))
            rules.append((f'St_{q}', alts))

        # ---- focus symbol rules
        for j in T:
            for s in A:
                alts = []
                if s == self.blank:
                    alts.append('&(!.)')          # all tapes start blank
                for key, (q2, ops) in entries:
                    write, move = ops[j]
                    if move == 'S':
                        if write == s:
                            alts.append(cond(key))
                    elif move == 'R':
                        # new focus = symbol of the right stack top, or blank if empty
                        alts.append(cond(key) + ' ' + guard(f'{prev(f"Rt_{j}")} Rsym_{j}_{sym_id(s)}'))
                        if s == self.blank:
                            alts.append(cond(key) + ' ' + guard(f'!({prev(f"Rt_{j}")})'))
                    else:  # 'L'
                        alts.append(cond(key) + ' ' + guard(f'{prev(f"Lt_{j}")} Lsym_{j}_{sym_id(s)}'))
                        if s == self.blank:
                            alts.append(cond(key) + ' ' + guard(f'!({prev(f"Lt_{j}")})'))
                rules.append((f'Sc_{j}_{sym_id(s)}', alts))

        # ---- stack top rules and pushed-symbol rules
        for j in T:
            for side, mv, other in (('L', 'R', 'L'), ('R', 'L', 'R')):
                # a push onto the `side` stack happens when the head moves `mv`
                alts = []
                for key, (q2, ops) in entries:
                    write, move = ops[j]
                    if move == mv:
                        alts.append(cond(key) + ' ""')                       # this position
                    elif move == 'S':
                        alts.append(cond(key) + f' {prev(f"{side}t_{j}")}')   # unchanged
                    else:                                                    # pop
                        alts.append(cond(key) + f' {prev(f"{side}t_{j}")} {prev(f"{side}t_{j}")}')
                rules.append((f'{side}t_{j}', alts))
                for s in A:
                    alts = []
                    for key, (q2, ops) in entries:
                        write, move = ops[j]
                        if move == mv and write == s:
                            alts.append(cond(key))
                    rules.append((f'{side}sym_{j}_{sym_id(s)}', alts))

        # ---- start rule
        acc = ' / '.join(f'&(St_{q})' for q in sorted(self.accepting)) or '!("")'
        cls = '[' + self.input_alphabet + ']'
        out = [f'S = ({acc}) {cls}* !.;']
        for name, alts in rules:
            body = ' / '.join(alts) if alts else '!("")'
            out.append(f'{name} = {body};')
        return '\n'.join(out)


# ------------------------------------------------------------------ example machines

def tm_even_a():
    """Regular demo: an even number of 'a's.  One tape, never moved."""
    delta = {}
    for q in (0, 1):
        for f in (BLANK,):
            delta[(q, (f,), 'a')] = (1 - q, [(BLANK, 'S')])
            delta[(q, (f,), 'b')] = (q, [(BLANK, 'S')])
    return TM(1, [0, 1], 0, {0}, delta, input_alphabet='ab')


# Counter tape: the head position is the counter.  Cells 0,1,2 carry the markers
# '$', '1', '2' so that the focus tells the machine when the counter is small; the
# state carries k = min(counter, 3) and is corrected by the marker it reads.
MARK = {0: '$', 1: '1', 2: '2'}
FROM_MARK = {'$': 0, '1': 1, '2': 2}


def _k_after(focus, k):
    """the true min(counter,3) at the start of a step, given the state's guess."""
    return FROM_MARK.get(focus, k)


def tm_an_bn():
    """{ a^n b^n }.  Reading reverse(w) = b^n a^n: push on b, pop on a."""
    delta = {}
    syms = [BLANK, '$', '1', '2', 'x']
    for mode in ('P', 'M', 'D'):
        for k in (0, 1, 2, 3):
            for f in syms:
                kk = _k_after(f, k)
                write = MARK.get(kk, 'x')
                q = f'{mode}{k}'
                if mode == 'D':
                    delta[(q, (f,), 'a')] = ('D0', [(write, 'S')])
                    delta[(q, (f,), 'b')] = ('D0', [(write, 'S')])
                    continue
                if mode == 'P':
                    # pushing while reading b's
                    delta[(q, (f,), 'b')] = (f'P{min(kk + 1, 3)}', [(write, 'R')])
                    delta[(q, (f,), 'a')] = (('D0' if kk == 0 else f'M{3 if kk == 3 else kk - 1}'),
                                             [(write, 'S' if kk == 0 else 'L')])
                else:
                    delta[(q, (f,), 'b')] = ('D0', [(write, 'S')])
                    delta[(q, (f,), 'a')] = (('D0' if kk == 0 else f'M{3 if kk == 3 else kk - 1}'),
                                             [(write, 'S' if kk == 0 else 'L')])
    states = [f'{m}{k}' for m in 'PMD' for k in (0, 1, 2, 3)]
    return TM(1, states, 'P0', {'M0', 'P0'}, delta, input_alphabet='ab',
              tape_alphabet=syms)
