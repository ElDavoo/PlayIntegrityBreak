"""Multi-dex index over dexrd.Dex with register-level, readable instruction output.

Use DexSet.from_dir(<dir with classes*.dex>) to index a whole app. See README.md.
"""
import glob
import os
import struct

from dexrd import Dex, decode, switch_table, branch_target


class DexSet:
    def __init__(self, paths):
        self.dexes = [Dex(p) for p in paths]
        self.where = {}
        for dex in self.dexes:
            for desc in dex.class_by_name:
                self.where.setdefault(desc, dex)

    @classmethod
    def from_dir(cls, directory):
        paths = sorted(glob.glob(os.path.join(directory, 'classes*.dex')))
        return cls(paths)

    def dex_of(self, desc):
        return self.where.get(desc)

    def has_class(self, desc):
        return desc in self.where

    def class_methods(self, desc):
        dex = self.dex_of(desc)
        return dex.class_methods(desc) if dex else []

    def code(self, desc, name, proto_params=None):
        """(dex, regs, insns, proto) for the method name of desc, or None."""
        dex = self.dex_of(desc)
        if dex is None:
            return None
        for mname, proto, access, code_off in dex.class_methods(desc):
            if mname != name or not code_off:
                continue
            if proto_params is not None and proto[1] != proto_params:
                continue
            regs, ins, outs, tries, dbg, insns_size = struct.unpack_from('<HHHHII', dex.data, code_off)
            insns = list(struct.unpack_from('<' + 'H' * insns_size, dex.data, code_off + 16))
            return dex, regs, insns, proto
        return None

    def methods_named(self, desc, name):
        return [(mname, proto, access) for mname, proto, access, code_off in self.class_methods(desc) if mname == name]

    def fields_of(self, desc):
        dex = self.dex_of(desc)
        if dex is None:
            return []
        return [(f[1], f[2]) for f in dex.fields if f[0] == desc]


def regs_of(insns, pc, fmt):
    u0 = insns[pc]
    if fmt == '10x':
        return ''
    if fmt in ('11x',):
        return f'v{(u0 >> 8) & 0xff}'
    if fmt == '11n':
        return f'v{(u0 >> 8) & 0xf}, #{((u0 >> 12) & 0xf) - (16 if (u0 >> 12) & 8 else 0)}'
    if fmt in ('12x',):
        return f'v{(u0 >> 8) & 0xf}, v{(u0 >> 12) & 0xf}'
    if fmt in ('21c', '21s', '21h', '21t'):
        return f'v{(u0 >> 8) & 0xff}'
    if fmt in ('22c', '22s', '22b', '22t'):
        return f'v{(u0 >> 8) & 0xf}, v{(u0 >> 12) & 0xf}'
    if fmt == '22x':
        return f'v{(u0 >> 8) & 0xff}, v{insns[pc + 1]}'
    if fmt == '23x':
        return f'v{(u0 >> 8) & 0xff}, v{insns[pc + 1] & 0xff}, v{insns[pc + 1] >> 8}'
    if fmt in ('31i', '31c', '31t', '51l'):
        return f'v{(u0 >> 8) & 0xff}'
    if fmt == '35c':
        count = (u0 >> 12) & 0xf
        g = (u0 >> 8) & 0xf
        f = insns[pc + 2]
        regs = [f & 0xf, (f >> 4) & 0xf, (f >> 8) & 0xf, (f >> 12) & 0xf, g]
        return '{' + ', '.join(f'v{r}' for r in regs[:count]) + '}'
    if fmt == '3rc':
        count = (u0 >> 8) & 0xff
        first = insns[pc + 2]
        return f'{{v{first}..v{first + count - 1}}}' if count else '{}'
    return ''


def describe(ds, dex, pc, insns, name, fmt):
    """Readable instruction text, resolving references in dex."""
    u0 = insns[pc]
    regs = regs_of(insns, pc, fmt)
    if fmt == 'payload':
        return f'{pc:04x}: payload'
    if fmt in ('35c', '3rc', '45cc', '4rcc'):
        idx = insns[pc + 1]
        if name.startswith('invoke'):
            cls, mname, (ret, params) = dex.methods[idx]
            return f'{pc:04x}: {name} {regs}, {cls}->{mname}({"".join(params)}){ret}'
        if name.startswith('filled-new-array'):
            return f'{pc:04x}: {name} {regs}, {dex.types[idx]}'
    if fmt in ('21c', '31c', '22c'):
        idx = insns[pc + 1]
        if name in ('new-instance', 'const-class', 'check-cast', 'new-array', 'instance-of'):
            return f'{pc:04x}: {name} {regs}, {dex.types[idx]}'
        if name.startswith('sget') or name.startswith('sput'):
            cls, typ, fname = dex.fields[idx]
            return f'{pc:04x}: {name} {regs}, {cls}.{fname}:{typ}'
        if name.startswith('iget') or name.startswith('iput'):
            cls, typ, fname = dex.fields[idx]
            return f'{pc:04x}: {name} {regs}, {cls}.{fname}:{typ}'
        if name in ('const-string', 'const-string/jumbo'):
            return f'{pc:04x}: {name} {regs}, "{dex.string(idx)}"'
    if fmt in ('10t', '20t', '30t', '21t', '22t'):
        tgt = branch_target(insns, pc, name, fmt)
        return f'{pc:04x}: {name} {regs} -> {tgt:04x}'
    if fmt == '31t':
        return f'{pc:04x}: {name} {regs}, switch@{pc + (insns[pc + 1] | (insns[pc + 2] << 16) if insns[pc + 2] < 0x8000 else 0):04x}'
    return f'{pc:04x}: {name} {regs}'


def listing(ds, desc, name, lo=None, hi=None, proto_params=None):
    got = ds.code(desc, name, proto_params)
    if got is None:
        return None
    dex, regs, insns, proto = got
    lines = []
    for pc, op, nm, fmt, ln in decode(insns):
        if lo is not None and pc < lo:
            continue
        if hi is not None and pc > hi:
            break
        lines.append(describe(ds, dex, pc, insns, nm, fmt) if nm != 'payload' else f'{pc:04x}: payload')
    return f'{desc}->{name}{proto} regs={regs}\n' + '\n'.join(lines)
