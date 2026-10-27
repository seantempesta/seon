import sys,re
for f in sys.argv[1:]:
    L=open(f).read().split('\n'); starts=[i for i,l in enumerate(L) if re.match(r'\((defn-?|def|defmacro|defmulti|defmethod|declare) ',l)]+[len(L)]
    for a,b in zip(starts,starts[1:]):
        m=re.match(r'\((?:defn-?|def|defmacro|defmulti|defmethod|declare)\s+(?:\^:\S+\s+)?(\S+)',L[a]); print(f"{f.split('/')[-1]}\t{m.group(1) if m else L[a][:30]}\t{b-a}")
