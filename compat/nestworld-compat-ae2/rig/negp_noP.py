import sys; p=sys.argv[1]; s=open(p).read()
a="    public static <T> T phase(Grid g, boolean drain, Supplier<T> body) {\n"
s=s.replace(a, a+"        if (true) return body.get(); // NEGATIVE: no P(G) around the phase\n",1)
a="    public static <T> T topology(Supplier<List<Grid>> affected, Supplier<T> body) {\n"
s=s.replace(a, a+"        if (true) return body.get(); // NEGATIVE: no P(G) for topology\n",1)
open(p,'w').write(s)
