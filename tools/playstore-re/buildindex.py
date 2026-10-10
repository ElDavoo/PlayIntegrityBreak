import struct, pickle, time
# usage: buildindex.py <dir with classes*.dex> <output.pkl>
import sys
from dexset import DexSet
from dexrd import decode

t0 = time.time()
ds = DexSet.from_dir(sys.argv[1])
news = {}   # typedesc -> [(dex, cls, method, proto, pc)]
invs = {}   # (cls, name) -> [(dex, cls, method, proto, pc)]
fields = {} # (cls, field) -> [(dex, cls, method, proto, pc, op)]
for di, dex in enumerate(ds.dexes):
    for desc in dex.class_by_name:
        for name, proto, access, code_off in dex.class_methods(desc):
            if not code_off:
                continue
            insns_size = struct.unpack_from('<I', dex.data, code_off + 12)[0]
            insns = list(struct.unpack_from('<' + 'H' * insns_size, dex.data, code_off + 16))
            for pc, op, nm, fmt, ln in decode(insns):
                if nm == 'new-instance':
                    t = dex.types[insns[pc + 1]]
                    news.setdefault(t, []).append((di + 1, desc, name, proto, pc))
                elif nm.startswith('invoke'):
                    cls, mname, _ = dex.methods[insns[pc + 1]]
                    invs.setdefault((cls, mname), []).append((di + 1, desc, name, proto, pc))
                elif nm.startswith(('iget', 'iput', 'sget', 'sput')):
                    cls, typ, fname = dex.fields[insns[pc + 1]]
                    fields.setdefault((cls, fname), []).append((di + 1, desc, name, proto, pc, nm))
pickle.dump({'news': news, 'invs': invs, 'fields': fields}, open(sys.argv[2], 'wb'))
print('done', round(time.time() - t0), 's; news', len(news), 'invoke keys', len(invs), 'field keys', len(fields))
