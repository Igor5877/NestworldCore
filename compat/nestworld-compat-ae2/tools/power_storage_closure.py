"""Transitive IAEPowerStorage implementation closure over a mods dir (incl. jar-in-jar).
Prototype of the M5c startup gate (AE2-COMPAT-DESIGN-001.2 §1a): class -> (super, interfaces)."""
import sys, zipfile, os, io, struct, hashlib, re
TARGET = "appeng/api/networking/energy/IAEPowerStorage"

def header(data):
    if data[:4] != b'\xca\xfe\xba\xbe': return None
    n = struct.unpack('>H', data[8:10])[0]; i = 10; cp = [None]*n; k = 1
    while k < n:
        t = data[i]
        if t == 1:
            l = struct.unpack('>H', data[i+1:i+3])[0]; cp[k] = ('u', data[i+3:i+3+l].decode('utf-8', 'replace')); i += 3+l
        elif t in (7, 8, 16, 19, 20): cp[k] = ('c', struct.unpack('>H', data[i+1:i+3])[0]); i += 3
        elif t == 15: i += 4
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        else: return None
        k += 1
    cls = lambda idx: cp[cp[idx][1]][1] if idx else None
    this_, sup = struct.unpack('>HH', data[i+2:i+6]); ic = struct.unpack('>H', data[i+6:i+8])[0]
    itf = [cls(struct.unpack('>H', data[i+8+2*j:i+10+2*j])[0]) for j in range(ic)]
    return cls(this_), cls(sup), itf

def walk(z, origin):
    for e in z.namelist():
        if e.endswith('.class'):
            h = header(z.read(e))
            if h: yield h, origin
        elif e.startswith('META-INF/jarjar/') and e.endswith('.jar'):
            try: yield from walk(zipfile.ZipFile(io.BytesIO(z.read(e))), origin + '!/' + e)
            except Exception: pass

def modinfo(z):
    try: t = z.read('META-INF/mods.toml').decode('utf-8', 'replace')
    except KeyError: return '?', '?'
    m = re.search(r'modId\s*=\s*"([^"]+)"', t); v = re.search(r'version\s*=\s*"([^"]+)"', t)
    ver = v.group(1) if v else '?'
    if ver == '${file.jarVersion}':
        try:
            mf = z.read('META-INF/MANIFEST.MF').decode(); mv = re.search(r'Implementation-Version:\s*(\S+)', mf); ver = mv.group(1) if mv else ver
        except KeyError: pass
    return (m.group(1) if m else '?'), ver

d = sys.argv[1]; info = {}; jarmeta = {}
for n in sorted(os.listdir(d)):
    if not n.endswith('.jar'): continue
    p = os.path.join(d, n); data = open(p, 'rb').read(); z = zipfile.ZipFile(io.BytesIO(data))
    jarmeta[n] = (hashlib.sha256(data).hexdigest(),) + modinfo(z)
    for (c, s, itf), origin in walk(z, n):
        info.setdefault(c, (s, itf, origin))
memo = {}
def reaches(c, depth=0):
    if c is None or depth > 64: return False
    if c == TARGET: return True
    if c in memo: return memo[c]
    memo[c] = False
    s, itf, _ = info.get(c, (None, [], None))
    r = any(reaches(i, depth+1) for i in itf) or reaches(s, depth+1)
    memo[c] = r; return r
for c in sorted(info):
    if c != TARGET and reaches(c):
        s, itf, origin = info[c]; jar = origin.split('!/')[0]; sha, mod, ver = jarmeta[jar]
        print(f"{c}\t{mod}\t{ver}\t{origin}\t{sha}")
