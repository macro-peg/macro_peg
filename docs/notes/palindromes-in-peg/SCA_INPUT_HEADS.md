# Clonable online readonly input heads

`scaffold_input.InputHead` composes the existing scaffold stacks and real-time
queue into an online readonly head. This is an input-access component for a
possible SCA composition path, not an implementation of the complete PAL
machine, a TM multihead simulation, or a PEG emitter.

Every head receives each new input cell once. Its state is a focus pointer,
a persistent left stack, a persistent right stack, and a real-time incoming
queue. The left stack contains the visited prefix (including the origin);
the right stack contains cells traversed leftward since the current forward
frontier; the incoming queue contains later arrivals. Their concatenation
with the focus partitions the input seen so far.

Moving left pushes the focus on the right stack and pops the left stack.
Moving right pushes the focus on the left stack and takes the next cell
from the right stack, or from the incoming queue when that stack is empty.
The origin is None; the live right boundary is tested using stack/queue
emptiness. No input coordinate, timestamp, length or node equality is used.

Copying a head aliases its focus, stack roots, all seven queue stacks,
the two unary counters' roots, and the finite queue phase. A pending queue
rotation is part of that state and is copied too. Immutable cells retain
their creator names/slots, so future changes by either head are independent.
Both old scaffold cells and cells being created through SELF can be copied.

## Arrival and step contract

For every arrival, broadcast the same input cell to all heads before client
operations. Every head must participate in every such broadcast. Copies use
views of the same VM and Builder at the same arrival frontier. Finite
per-view flags reject duplicate appends and copies between different current
arrival phases; a context check rejects cross-builder/VM copies. These checks
do not detect a caller that omitted a head from a previous broadcast.

The initially found counterexample was A.append(cell), B.copy_from(A), then
B.append(cell), which duplicated the arrival in B. That sequence now fails
at the incompatible copy, and duplicate append is separately rejected.

Calls within each physical scaffold step must have a fixed bound determined
by the outer finite controller. Queue rotation is run before/after an incoming
pop so a bounded number of client moves can share one physical step. The
tested configuration has three heads and at most three client operations
per step. The existing VM additionally checks reachability, backward/self
pointers and finite-shaped labels; its slot/name encoding limits still apply.

## Resource accounting for the tested schedule

One queue rotation unit performs at most ten pointer reads and five stack
pushes. Each work call runs three units. A stack push creates two pointer
fields. Head initialization reads fourteen root pointers. At most two work
calls are needed per incoming right move; append and finalize each add one.
There are fourteen finalized pointer roots per head, including focus.

For H heads and at most N client operations in a broadcast step, these give
conservative bounds of `76H + 66N` pointer reads and `78H + 66N` pointer
fields, before any unrelated outer-machine fields. Copies need no new
pointer reads after both views have been loaded. For H=N=3, the bounds are
426 reads and 432 pointer fields. This is a local operation bound, not a
claim about the unfinished recognizer's total work per input character.

All creator names come from the fixed head/stack set, and the bounded
number of pushes bounds slots in each physical node. Input symbols and
rotation phases are finite. Seeing a new complete label on every input in
a long run does not by itself establish an infinite alphabet: the product
of the fixed fields' finite domains is large. Conversely, empirical label
counts alone are not offered as proof of finiteness.

The 1,000-step run observed at most 182 pointer reads and 354 pointer fields;
the 10,000-step run observed 210 and 414. Both matched an independent indexed
input oracle. Unit tests also cover origin/live boundary behavior, copying
during rotations and within newly created cells, and arrival-order rejection.
Independent review additionally passed 15,000 adversarial steps with four
heads, deliberately copying idle/reversal/copy queue states, and 2,000
steps copying newly arrived SELF cells. It confirmed the same resource
bounds and reported no remaining findings under the stated protocol. All
53 Python tests passed; required sbt test selected zero cached tests and
succeeded. The latest actual full Scala verification remains 646 passing
tests from the phase compiler work. Larger client-operation budgets require
a separate check against the VM slot-label bound, even though they are finite.

Remaining work is to connect these heads to the global finite control,
represent any required mutable annotations separately, and compile the
resulting bounded scaffold operations into ordinary PEG. The current
`symbolic_tm2peg.py` consumes independent TM tapes; InputHead is not a
drop-in extra tape for that compiler.
