# H2 negative control: P2PDepth.PROTECT = false -> AE2's shared accessDepth int.
import sys, re
p = '/root/nestworldcore/compat/nestworld-compat-ae2/src/main/java/net/nestworld/compat/ae2/glue/P2PDepth.java'
s = open(p).read()
mode = sys.argv[1] if len(sys.argv) > 1 else 'apply'
a, b = 'public static final boolean PROTECT = true;', 'public static final boolean PROTECT = false;'
if mode == 'apply':
    assert a in s; s = s.replace(a, b)
else:
    assert b in s; s = s.replace(b, a)
open(p, 'w').write(s); print(mode, 'ok')
