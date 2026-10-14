import sys; p=sys.argv[1]; s=open(p).read()
a="} else if (ph == RUNNING && !self) {\n                next = seq | DIRTY;"; assert s.count(a)==1
s=s.replace(a,"} else if (ph == RUNNING && !self) {\n                next = seq | RUNNING; // NEGATIVE: vanilla clear-after (no DIRTY)"); open(p,'w').write(s)
