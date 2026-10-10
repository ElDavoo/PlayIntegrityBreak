import sys
from dexrd import Dex, decode, switch_table, branch_target

def find_switches(d, insns):
    return [pc for pc, op, nm, fmt, ln in decode(insns) if nm in ('packed-switch', 'sparse-switch')]

def keys_reaching(d, desc, name, pred):
    """Switch keys (per switch in the method) whose case block reaches an instruction matching pred."""
    code = d.code(desc, name)
    if code is None:
        return None
    regs, insns, proto = code
    index = {pc: (op, nm, fmt, ln) for pc, op, nm, fmt, ln in decode(insns)}
    out = {}
    for sw in find_switches(d, insns):
        if index[sw][1] != 'packed-switch':
            continue
        table = switch_table(insns, sw)
        hits = []
        for key, tgt in sorted(table.items()):
            reach = d.reachable(insns, tgt, stop=frozenset([sw]))
            matched = [pc for pc in reach if pred(insns, pc, index[pc][1], index[pc][2], d)]
            if matched:
                hits.append((key, tgt, matched))
        out[sw] = (table, hits)
    return out

def is_new_instance(t):
    return lambda insns, pc, nm, fmt, d: nm == 'new-instance' and d.types[insns[pc + 1]] == t

if __name__ == '__main__':
    # usage: analyze_switch.py <classesN.dex>   (prints the switch keys of Lrmw.o() that reach xpe.<init>)
    d = Dex(sys.argv[1])
    res = keys_reaching(d, 'Lrmw;', 'o', is_new_instance('Lxpe;'))
    for sw, (table, hits) in res.items():
        print('switch at', hex(sw), 'cases', len(table), 'keys', min(table), '..', max(table))
        for key, tgt, matched in hits:
            print('  key', key, 'case target', hex(tgt), 'xpe new-instance at', [hex(x) for x in matched])
