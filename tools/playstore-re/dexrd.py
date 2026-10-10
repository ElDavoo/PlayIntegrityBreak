#!/usr/bin/env python3
"""Minimal DEX reader for analysing the Play Store's obfuscated DI graph.

Only what the analysis needs: strings, types, protos, methods, fields, class data, code items,
Dalvik instruction decoding (all opcodes, with sizes checked against dexdump), and switch tables.
"""
import struct
import sys

# opcode -> (name, format). Formats: sizes in 16-bit code units.
SIZES = {
    '10x': 1, '10t': 1, '11n': 1, '11x': 1, '12x': 1,
    '20t': 2, '21c': 2, '21h': 2, '21s': 2, '21t': 2, '22b': 2, '22c': 2, '22s': 2, '22t': 2, '22x': 2, '23x': 2,
    '30t': 3, '31c': 3, '31i': 3, '31t': 3, '32x': 3, '35c': 3, '3rc': 3,
    '45cc': 4, '4rcc': 4, '51l': 5,
}

OPS = {}


def _op(code, name, fmt):
    OPS[code] = (name, fmt)


for code, name, fmt in [
    (0x00, 'nop', '10x'), (0x01, 'move', '12x'), (0x02, 'move/from16', '22x'), (0x03, 'move/16', '32x'),
    (0x04, 'move-wide', '12x'), (0x05, 'move-wide/from16', '22x'), (0x06, 'move-wide/16', '32x'),
    (0x07, 'move-object', '12x'), (0x08, 'move-object/from16', '22x'), (0x09, 'move-object/16', '32x'),
    (0x0a, 'move-result', '11x'), (0x0b, 'move-result-wide', '11x'), (0x0c, 'move-result-object', '11x'),
    (0x0d, 'move-exception', '11x'), (0x0e, 'return-void', '10x'), (0x0f, 'return', '11x'),
    (0x10, 'return-wide', '11x'), (0x11, 'return-object', '11x'), (0x12, 'const/4', '11n'),
    (0x13, 'const/16', '21s'), (0x14, 'const', '31i'), (0x15, 'const/high16', '21h'),
    (0x16, 'const-wide/16', '21s'), (0x17, 'const-wide/32', '31i'), (0x18, 'const-wide', '51l'),
    (0x19, 'const-wide/high16', '21h'), (0x1a, 'const-string', '21c'), (0x1b, 'const-string/jumbo', '31c'),
    (0x1c, 'const-class', '21c'), (0x1d, 'monitor-enter', '11x'), (0x1e, 'monitor-exit', '11x'),
    (0x1f, 'check-cast', '21c'), (0x20, 'instance-of', '22c'), (0x21, 'array-length', '12x'),
    (0x22, 'new-instance', '21c'), (0x23, 'new-array', '22c'), (0x24, 'filled-new-array', '35c'),
    (0x25, 'filled-new-array/range', '3rc'), (0x26, 'fill-array-data', '31t'), (0x27, 'throw', '11x'),
    (0x28, 'goto', '10t'), (0x29, 'goto/16', '20t'), (0x2a, 'goto/32', '30t'),
    (0x2b, 'packed-switch', '31t'), (0x2c, 'sparse-switch', '31t'),
    (0x2d, 'cmpl-float', '23x'), (0x2e, 'cmpg-float', '23x'), (0x2f, 'cmpl-double', '23x'),
    (0x30, 'cmpg-double', '23x'), (0x31, 'cmp-long', '23x'),
    (0x32, 'if-eq', '22t'), (0x33, 'if-ne', '22t'), (0x34, 'if-lt', '22t'), (0x35, 'if-ge', '22t'),
    (0x36, 'if-gt', '22t'), (0x37, 'if-le', '22t'), (0x38, 'if-eqz', '21t'), (0x39, 'if-nez', '21t'),
    (0x3a, 'if-ltz', '21t'), (0x3b, 'if-gez', '21t'), (0x3c, 'if-gtz', '21t'), (0x3d, 'if-lez', '21t'),
    (0x44, 'aget', '23x'), (0x45, 'aget-wide', '23x'), (0x46, 'aget-object', '23x'), (0x47, 'aget-boolean', '23x'),
    (0x48, 'aget-byte', '23x'), (0x49, 'aget-char', '23x'), (0x4a, 'aget-short', '23x'), (0x4b, 'aput', '23x'),
    (0x4c, 'aput-wide', '23x'), (0x4d, 'aput-object', '23x'), (0x4e, 'aput-boolean', '23x'),
    (0x4f, 'aput-byte', '23x'), (0x50, 'aput-char', '23x'), (0x51, 'aput-short', '23x'),
    (0x52, 'iget', '22c'), (0x53, 'iget-wide', '22c'), (0x54, 'iget-object', '22c'), (0x55, 'iget-boolean', '22c'),
    (0x56, 'iget-byte', '22c'), (0x57, 'iget-char', '22c'), (0x58, 'iget-short', '22c'), (0x59, 'iput', '22c'),
    (0x5a, 'iput-wide', '22c'), (0x5b, 'iput-object', '22c'), (0x5c, 'iput-boolean', '22c'),
    (0x5d, 'iput-byte', '22c'), (0x5e, 'iput-char', '22c'), (0x5f, 'iput-short', '22c'),
    (0x60, 'sget', '21c'), (0x61, 'sget-wide', '21c'), (0x62, 'sget-object', '21c'), (0x63, 'sget-boolean', '21c'),
    (0x64, 'sget-byte', '21c'), (0x65, 'sget-char', '21c'), (0x66, 'sget-short', '21c'), (0x67, 'sput', '21c'),
    (0x68, 'sput-wide', '21c'), (0x69, 'sput-object', '21c'), (0x6a, 'sput-boolean', '21c'),
    (0x6b, 'sput-byte', '21c'), (0x6c, 'sput-char', '21c'), (0x6d, 'sput-short', '21c'),
    (0x6e, 'invoke-virtual', '35c'), (0x6f, 'invoke-super', '35c'), (0x70, 'invoke-direct', '35c'),
    (0x71, 'invoke-static', '35c'), (0x72, 'invoke-interface', '35c'),
    (0x74, 'invoke-virtual/range', '3rc'), (0x75, 'invoke-super/range', '3rc'),
    (0x76, 'invoke-direct/range', '3rc'), (0x77, 'invoke-static/range', '3rc'),
    (0x78, 'invoke-interface/range', '3rc'),
    (0x7b, 'neg-int', '12x'), (0x7c, 'not-int', '12x'), (0x7d, 'neg-long', '12x'), (0x7e, 'not-long', '12x'),
    (0x7f, 'neg-float', '12x'), (0x80, 'neg-double', '12x'), (0x81, 'int-to-long', '12x'),
    (0x82, 'int-to-float', '12x'), (0x83, 'int-to-double', '12x'), (0x84, 'long-to-int', '12x'),
    (0x85, 'long-to-float', '12x'), (0x86, 'long-to-double', '12x'), (0x87, 'float-to-int', '12x'),
    (0x88, 'float-to-long', '12x'), (0x89, 'float-to-double', '12x'), (0x8a, 'double-to-int', '12x'),
    (0x8b, 'double-to-long', '12x'), (0x8c, 'double-to-float', '12x'), (0x8d, 'int-to-byte', '12x'),
    (0x8e, 'int-to-char', '12x'), (0x8f, 'int-to-short', '12x'),
    (0xfa, 'invoke-polymorphic', '45cc'), (0xfb, 'invoke-polymorphic/range', '4rcc'),
    (0xfc, 'invoke-custom', '35c'), (0xfd, 'invoke-custom/range', '3rc'),
    (0xfe, 'const-method-handle', '21c'), (0xff, 'const-method-type', '21c'),
]:
    _op(code, name, fmt)

_BIN = ['add', 'sub', 'mul', 'div', 'rem', 'and', 'or', 'xor', 'shl', 'shr', 'ushr']
_BIN_TYPES = ['int'] * 11 + ['long'] * 11 + ['float'] * 5 + ['double'] * 5
_BIN_OPS = (_BIN + _BIN + _BIN[:5] + _BIN[:5])
for i in range(32):
    name = f"{_BIN_OPS[i]}-{_BIN_TYPES[i]}"
    _op(0x90 + i, name, '23x')
    _op(0xb0 + i, name + '/2addr', '12x')
for i, name in enumerate(['add', 'rsub', 'mul', 'div', 'rem', 'and', 'or', 'xor']):
    _op(0xd0 + i, f'{name}-int/lit16', '22s')
for i, name in enumerate(['add', 'rsub', 'mul', 'div', 'rem', 'and', 'or', 'xor', 'shl', 'shr', 'ushr']):
    _op(0xd8 + i, f'{name}-int/lit8', '22b')
for code in list(range(0x3e, 0x44)) + [0x73, 0x79, 0x7a] + list(range(0xe3, 0xfa)):
    if code not in OPS:
        _op(code, 'unused', '10x')
for code in range(256):
    if code not in OPS:
        raise SystemExit(f'opcode {code:#x} missing from table')


def s8(v):
    return v - 256 if v & 0x80 else v


def s16(v):
    return v - 0x10000 if v & 0x8000 else v


def s32(lo, hi):
    v = (hi << 16) | lo
    return v - 0x100000000 if v & 0x80000000 else v


def payload_size(insns, pc):
    """Size in units of a pseudo-instruction payload at pc, or None if pc is not a payload."""
    ident = insns[pc]
    if ident == 0x0100:
        return 4 + 2 * insns[pc + 1]
    if ident == 0x0200:
        return 2 + 4 * insns[pc + 1]
    if ident == 0x0300:
        width = insns[pc + 1]
        count = insns[pc + 2] | (insns[pc + 3] << 16)
        return 4 + (width * count + 1) // 2
    return None


def decode(insns):
    """Yields (pc, opcode, name, fmt, length_in_units) for every instruction and payload in order."""
    pc = 0
    while pc < len(insns):
        size = payload_size(insns, pc) if insns[pc] in (0x0100, 0x0200, 0x0300) else None
        if size is not None:
            yield pc, 'payload', 'payload', 'payload', size
            pc += size
            continue
        op = insns[pc] & 0xff
        name, fmt = OPS[op]
        length = SIZES[fmt]
        yield pc, op, name, fmt, length
        pc += length


def branch_target(insns, pc, op, fmt):
    """Target of a branch/switch at pc, or None."""
    if fmt == '10t':
        return pc + s8(insns[pc] >> 8)
    if fmt == '20t':
        return pc + s16(insns[pc + 1])
    if fmt == '30t':
        return pc + s32(insns[pc + 1], insns[pc + 2])
    if fmt in ('21t', '22t'):
        return pc + s16(insns[pc + 1])
    return None


def switch_table(insns, pc):
    """{key: target_pc} for the packed-switch or sparse-switch at pc."""
    offset = s32(insns[pc + 1], insns[pc + 2])
    base = pc + offset
    cases = {}
    if insns[base] == 0x0100:
        size = insns[base + 1]
        first = s32(insns[base + 2], insns[base + 3])
        for i in range(size):
            target = s32(insns[base + 4 + 2 * i], insns[base + 5 + 2 * i])
            cases[first + i] = pc + target
    elif insns[base] == 0x0200:
        size = insns[base + 1]
        for i in range(size):
            key = s32(insns[base + 2 + 2 * i], insns[base + 3 + 2 * i])
            target = s32(insns[base + 2 + 2 * size + 2 * i], insns[base + 3 + 2 * size + 2 * i])
            cases[key] = pc + target
    else:
        raise ValueError(f'no switch payload at {base:#x}')
    return cases


class Dex:
    def __init__(self, path):
        with open(path, 'rb') as f:
            self.data = f.read()
        d = self.data
        (self.string_ids_size, self.string_ids_off) = struct.unpack_from('<II', d, 56)
        (self.type_ids_size, self.type_ids_off) = struct.unpack_from('<II', d, 64)
        (self.proto_ids_size, self.proto_ids_off) = struct.unpack_from('<II', d, 72)
        (self.field_ids_size, self.field_ids_off) = struct.unpack_from('<II', d, 80)
        (self.method_ids_size, self.method_ids_off) = struct.unpack_from('<II', d, 88)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from('<II', d, 96)
        self._strings = {}
        self.types = [self.string(struct.unpack_from('<I', d, self.type_ids_off + 4 * i)[0])
                      for i in range(self.type_ids_size)]
        self.protos = []
        for i in range(self.proto_ids_size):
            shorty, ret, params = struct.unpack_from('<III', d, self.proto_ids_off + 12 * i)
            plist = self._type_list(params)
            self.protos.append((self.types[ret], [self.types[t] for t in plist]))
        self.fields = []
        for i in range(self.field_ids_size):
            cls, typ, name = struct.unpack_from('<HHI', d, self.field_ids_off + 8 * i)
            self.fields.append((self.types[cls], self.types[typ], self.string(name)))
        self.methods = []
        for i in range(self.method_ids_size):
            cls, proto, name = struct.unpack_from('<HHI', d, self.method_ids_off + 8 * i)
            self.methods.append((self.types[cls], self.string(name), self.protos[proto]))
        self.class_by_name = {}
        for i in range(self.class_defs_size):
            rec = struct.unpack_from('<IIIIIIII', d, self.class_defs_off + 32 * i)
            self.class_by_name[self.types[rec[0]]] = rec

    def string(self, idx):
        if idx not in self._strings:
            off = struct.unpack_from('<I', self.data, self.string_ids_off + 4 * idx)[0]
            _, pos = self._uleb(off)
            end = self.data.index(b'\0', pos)
            # Modified UTF-8: NUL is stored as C0 80, which strict UTF-8 rejects.
            self._strings[idx] = self.data[pos:end].replace(b'\xc0\x80', b'\x00').decode('utf-8', 'surrogatepass')
        return self._strings[idx]

    def _uleb(self, pos):
        result = 0
        shift = 0
        while True:
            b = self.data[pos]
            pos += 1
            result |= (b & 0x7f) << shift
            if not b & 0x80:
                return result, pos
            shift += 7

    def _sleb(self, pos):
        result = 0
        shift = 0
        while True:
            b = self.data[pos]
            pos += 1
            result |= (b & 0x7f) << shift
            shift += 7
            if not b & 0x80:
                if b & 0x40:
                    result -= 1 << shift
                return result, pos

    def _type_list(self, off):
        if off == 0:
            return []
        size = struct.unpack_from('<I', self.data, off)[0]
        return [struct.unpack_from('<H', self.data, off + 4 + 2 * i)[0] for i in range(size)]

    def has_class(self, desc):
        return desc in self.class_by_name

    def class_methods(self, desc):
        """[(name, proto, access, code_off)] for the declared methods of desc."""
        rec = self.class_by_name.get(desc)
        if rec is None or rec[6] == 0:
            return []
        pos = rec[6]
        sf, pos = self._uleb(pos)
        inf, pos = self._uleb(pos)
        dm, pos = self._uleb(pos)
        vm, pos = self._uleb(pos)
        for _ in range(sf + inf):
            _, pos = self._uleb(pos)
            _, pos = self._uleb(pos)
        resolved = []
        for count in (dm, vm):
            cur = 0
            for _ in range(count):
                diff, pos = self._uleb(pos)
                access, pos = self._uleb(pos)
                code_off, pos = self._uleb(pos)
                cur += diff
                _, mname, mproto = self.methods[cur]
                resolved.append((mname, mproto, access, code_off))
        return resolved

    def code(self, desc, name):
        """(registers, insns) of the method name declared in desc, or None if it has no code."""
        for mname, proto, access, code_off in self.class_methods(desc):
            if mname == name and code_off:
                regs, ins, outs, tries, dbg, insns_size = struct.unpack_from('<HHHHII', self.data, code_off)
                insns = struct.unpack_from('<' + 'H' * insns_size, self.data, code_off + 16)
                return regs, list(insns), proto
        return None

    def describe(self, pc, insns, name, fmt):
        """Readable one-line form of an instruction."""
        if name == 'payload' or fmt == 'payload':
            return f'{pc:04x}: payload'
        if fmt == '35c' or fmt == '3rc' or fmt == '45cc' or fmt == '4rcc':
            idx = insns[pc + 1]
            if name.startswith('invoke'):
                cls, mname, (ret, params) = self.methods[idx]
                return f'{pc:04x}: {name} {cls}->{mname}({"".join(params)}){ret}'
            if name.startswith('filled-new-array'):
                return f'{pc:04x}: {name} {self.types[idx]}'
        if fmt == '21c' or fmt == '31c':
            idx = insns[pc + 1]
            if name in ('new-instance', 'const-class', 'check-cast'):
                return f'{pc:04x}: {name} {self.types[idx]}'
            if name.startswith('sget') or name.startswith('sput'):
                cls, typ, fname = self.fields[idx]
                return f'{pc:04x}: {name} {cls}.{fname}:{typ}'
            if name == 'const-string' or name == 'const-string/jumbo':
                return f'{pc:04x}: {name} "{self.string(idx)}"'
        if fmt == '22c':
            idx = insns[pc + 1]
            if name.startswith('iget') or name.startswith('iput'):
                cls, typ, fname = self.fields[idx]
                return f'{pc:04x}: {name} {cls}.{fname}:{typ}'
            if name in ('instance-of', 'new-array'):
                return f'{pc:04x}: {name} {self.types[idx]}'
        if fmt in ('10t', '20t', '30t', '21t', '22t', '31t'):
            tgt = branch_target(insns, pc, name, fmt)
            if tgt is not None:
                return f'{pc:04x}: {name} -> {tgt:04x}'
        return f'{pc:04x}: {name}'

    def reachable(self, insns, start, stop=frozenset()):
        """Instruction pcs reachable from start without entering a pc in stop."""
        seen = set()
        work = [start]
        index = {}
        for pc, op, name, fmt, length in decode(insns):
            index[pc] = (op, name, fmt, length)
        while work:
            pc = work.pop()
            if pc in seen or pc in stop or pc not in index:
                continue
            seen.add(pc)
            op, name, fmt, length = index[pc]
            if name == 'payload':
                continue
            if name in ('return-void', 'return', 'return-wide', 'return-object', 'throw'):
                continue
            if name == 'goto' or name == 'goto/16' or name == 'goto/32':
                work.append(branch_target(insns, pc, name, fmt))
                continue
            if name in ('packed-switch', 'sparse-switch'):
                for tgt in switch_table(insns, pc).values():
                    work.append(tgt)
                work.append(pc + length)
                continue
            if fmt in ('21t', '22t'):
                work.append(branch_target(insns, pc, name, fmt))
            work.append(pc + length)
        return seen


if __name__ == '__main__':
    dex = Dex(sys.argv[1])
    print('types', len(dex.types), 'methods', len(dex.methods), 'classes', len(dex.class_by_name))
