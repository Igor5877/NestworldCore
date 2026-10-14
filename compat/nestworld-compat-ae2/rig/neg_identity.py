import sys; p=sys.argv[1]; s=open(p).read()
a="nw$t2Valid = live && same;"; assert s.count(a)==1
s=s.replace(a,"nw$t2Valid = true; // NEGATIVE: AE2 containsKey only"); open(p,'w').write(s)
