import sys; p=sys.argv[1]; s=open(p).read()
a="""                for (int i = got.size() - 1; i >= 0; i--) release(got.get(i));
                COUNTERS.pNestedUnresolved.increment();
                throw KERNEL.unaudited("P(G) unavailable for a grid first touched inside a topology section (after T0)");"""
assert s.count(a)==1
s=s.replace(a,"""                COUNTERS.pNestedUnresolved.increment();
                KERNEL.unaudited("P(G) unavailable (NEGATIVE: old fail-open, continue)");""")
open(p,'w').write(s)
