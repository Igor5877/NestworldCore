import sys; p=sys.argv[1]; s=open(p).read()
a="String reason = nw$invalid(be, life);"; assert s.count(a)==1
s=s.replace(a,"String reason = null; // NEGATIVE: no revalidation inside the lock"); open(p,'w').write(s)
