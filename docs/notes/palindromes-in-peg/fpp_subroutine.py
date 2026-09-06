"""Offline FPP subroutine: plain input to a tape of palindrome-prefix marks.

Only SOURCE is initially populated (^ word $). All other tapes start blank.
The finite controller prepares the two copies of word # reverse(word),
initializes work tapes and produces ^ bit_1 ... bit_n $ on MARKS. Every
preparation/marking move counts. Runtime control uses no observer integers.
Scratch tapes are fresh: reuse/cleanup is a separate calling-convention task.
"""
from dataclasses import dataclass

from fpp_finite import Program, build_program, A, B, C, S, T, BACK, FRONT
from fpp_finite import LEFT, END, BLANK

SOURCE, MARKS = 7, 8


@dataclass
class MarkedRun:
  marks: tuple
  prepared: str
  steps: int
  tapes: tuple = ()
  positions: tuple = ()


class MarkedProgram(Program):
  def run(self, word):
    if any(symbol not in self.source_alphabet for symbol in word):
      raise ValueError("input outside source alphabet")
    tapes = [{} for _ in range(self.ntapes)]
    tapes[SOURCE] = dict(enumerate(LEFT + word + END))
    result = self.execute(tapes, [0] * self.ntapes, 1000 * (len(word) + 1))
    # External output decoding only. The controller already wrote these bits.
    marks, i = [], 1
    while result.tapes[MARKS].get(i, BLANK) != END:
      marks.append(int(result.tapes[MARKS][i]))
      i += 1
    prepared, i = [], 1
    while result.tapes[A].get(i, BLANK) != END:
      prepared.append(result.tapes[A][i])
      i += 1
    return MarkedRun(tuple(marks), "".join(prepared), result.steps,
                     result.tapes, result.positions)


def build_marked_program(alphabet="ab"):
  if "#" in alphabet:
    raise ValueError("# is the private separator")
  kernel = build_program(alphabet + "#")
  p = MarkedProgram(alphabet + "#", ntapes=9)
  p.source_alphabet = tuple(alphabet)
  p.code = list(kernel.code)
  # Expand every candidate move to a corresponding MARKS move. This
  # transformation touches kernel instructions only, not setup instructions.
  for q, row in enumerate(kernel.code):
    if row[0] == "move" and row[1] == A:
      p.code[q] = ("move", A, row[2], p.move(MARKS, row[2], row[3]))
    elif row[0] == "emit":
      p.code[q] = ("write", MARKS, "1", row[1])

  def rewind(tape, symbols, k):
    loop = p.reserve()
    p.branch(tape, {LEFT: k, **{
      symbol: p.move(tape, -1, loop) for symbol in symbols}}, loop)
    return loop

  def write_both(symbol, k):
    return p.write(A, symbol, p.write(B, symbol, k))

  def next_both(k):
    return p.move(A, 1, p.move(B, 1, k))

  # B must scan the first prepared symbol; A and MARKS must scan ^.
  ready = rewind(A, alphabet + "#" + END,
          rewind(B, alphabet + "#" + END,
          rewind(MARKS, "0" + END, p.move(B, 1, kernel.start))))
  finish = write_both(END, ready)
  forward, backward = p.reserve(), p.reserve()
  p.branch(SOURCE, {LEFT: finish, **{
    symbol: write_both(symbol, next_both(p.move(SOURCE, -1, backward)))
    for symbol in alphabet}}, backward)
  turn = p.write(MARKS, END,
         write_both("#", next_both(p.move(SOURCE, -1, backward))))
  p.branch(SOURCE, {END: turn, **{
    symbol: write_both(symbol, p.write(MARKS, "0",
            next_both(p.move(MARKS, 1, p.move(SOURCE, 1, forward)))))
    for symbol in alphabet}}, forward)
  start_copy = next_both(p.move(MARKS, 1, p.move(SOURCE, 1, forward)))
  # Initialize C's delta(-1) encoding 010, then restore its head.
  init_c = p.write(C, "0", p.move(C, 1, p.write(C, "1",
           p.move(C, 1, p.write(C, "0", p.move(C, -1,
           p.move(C, -1, start_copy)))))))
  init = init_c
  for tape in (A, B, MARKS, S, T, BACK, FRONT):
    init = p.write(tape, LEFT, init)
  p.start = init
  p.validate()
  return p
