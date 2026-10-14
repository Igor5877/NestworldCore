import sys; p=sys.argv[1]; s=open(p).read()
a="""        long t0 = System.nanoTime();
        p.lock();
        COUNTERS.pWaitNs.add(System.nanoTime() - t0);
        try {"""
assert s.count(a)==1
s=s.replace(a,"""        long t0 = System.nanoTime();
        // NEGATIVE: no P(G) lock around the phase (drain and hooks kept)
        try {""")
a2="""        } finally {
            p.unlock();
        }
    }

    // ---------------------------------------------------------------- topology"""
assert s.count(a2)==1
s=s.replace(a2,"""        } finally {
            // NEGATIVE: no unlock
        }
    }

    // ---------------------------------------------------------------- topology""")
a3="    public static <T> T topology(Supplier<List<Grid>> affected, Supplier<T> body) {\n"
s=s.replace(a3, a3+"        if (true) return body.get(); // NEGATIVE: no P(G) for topology\n",1)
open(p,'w').write(s)
