import sys; p=sys.argv[1]; s=open(p).read()
a="boolean self = ctx != null && ctx.be() == be && ctx.gen() == be.nw$cbGen();"; assert s.count(a)==1
s=s.replace(a,"boolean self = false; // NEGATIVE: plain clear-before (no self suppression)"); open(p,'w').write(s)
