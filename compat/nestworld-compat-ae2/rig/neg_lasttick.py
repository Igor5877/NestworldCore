import sys; p=sys.argv[1]; s=open(p).read()
a="if (tt == nw$suspTt) tt.setLastTick(currentTick);"; assert s.count(a)==1
s=s.replace(a,"// NEGATIVE: no lastTick reset"); open(p,'w').write(s)
