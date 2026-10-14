import sys; p=sys.argv[1]; s=open(p).read()
a="currentlyTicking = null;                          // suppression must not survive the suspension"; assert s.count(a)==1
s=s.replace(a,"// NEGATIVE: keep AE2 currentlyTicking suppression")
a="if (t != null && node == t.getNode() && awake.get(node) == t"; assert s.count(a)==1
s=s.replace(a,"if (false && t != null && node == t.getNode() && awake.get(node) == t")
a="            if (nw$isSelf(node)) {\n                COUNTERS.t2SelfCall.increment();\n                return false;\n            }\n            TickTracker t"; assert s.count(a)==1
s=s.replace(a,"            TickTracker t"); open(p,'w').write(s)
