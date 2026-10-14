import sys; p=sys.argv[1]; s=open(p).read()
a="        life.nw$life().lock();\n        try {\n            life.nw$markRemoved();"; assert s.count(a)==1
s=s.replace(a,"        if (true) { life.nw$markRemoved(); op.call(be); return; } // NEGATIVE: remover without nw$life\n"+a); open(p,'w').write(s)
