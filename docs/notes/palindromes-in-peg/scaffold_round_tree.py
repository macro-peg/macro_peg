"""Factor a fixed service round into binary compositions of the same packer.

Identity stages complete the binary tree. They do not advance the source or
read input. The number of source service transitions remains exactly service.
"""
from scaffold_round import pack_round
from symbolic_sca2peg import Scaffold, old, pointer, symbol, TRUE, FALSE, share_expressions


def pack_service_tree(wrapper, service):
  if type(service) is not int or service < 1:
    raise ValueError("positive finite source service required")
  if tuple(wrapper.alphabet) != tuple("ab."):
    raise ValueError("expected a buffered source event alphabet")
  with share_expressions():
    arrival = pack_round([wrapper], [{"a": symbol("a"), "b": symbol("b"), ".": FALSE}], "ab")
    work = pack_round([wrapper], [{"a": FALSE, "b": FALSE, ".": TRUE}], "ab")
  identity = Scaffold(work.initial, {key: old((), key) for key in work.labels},
                       {key: pointer((key,)) for key in work.pointers},
                       work.accepting, "ab")
  width = 1 << service.bit_length()
  layer = [arrival] + [work] * service + [identity] * (width - service - 1)
  while len(layer) > 1:
    cache, next_layer = {}, []
    for index in range(0, len(layer), 2):
      pair = layer[index], layer[index + 1]
      key = tuple(map(id, pair))
      if key not in cache:
        with share_expressions():
          cache[key] = pack_round(pair)
      next_layer.append(cache[key])
    layer = next_layer
  return layer[0]
