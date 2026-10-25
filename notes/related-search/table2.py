import re,glob,sys,collections
D=sys.argv[1]
txt=''.join(open(f).read() for f in sorted(glob.glob(D+'/results2-*.edn'), key=lambda f:int(re.search(r'-(\d+)\.edn',f).group(1))))
tasks=re.split(r'\{:task "',txt)[1:]
agg=collections.defaultdict(list); nb=collections.defaultdict(list); fnb=collections.defaultdict(list)
for t in tasks:
    at=re.search(r':plan-at "(\w+)"',t).group(1)
    for m in re.finditer(r'\[:([\w+-]+) :(\w+) (\d+)\] \{:recall ([0-9.E-]+), :tokens (\d+)\}',t):
        meth,kind,n,r,tok=m.groups(); agg[(kind,meth,int(n))].append((float(r),int(tok),at))
    mm=re.search(r':neighbours \{(.*?)\}\}?, :fn-neighbours',t,re.S)
    if mm:
        for k,body in re.findall(r':(attr|config|record) \{([^}]*)\}',mm.group(1)):
            d=dict((a,float(b)) for a,b in re.findall(r':([\w+-]+) ([0-9.E-]+)',body)); nb[k].append(d)
    mm=re.search(r':fn-neighbours \{([^}]*)\}',t)
    if mm: fnb['fns'].append(dict((a,float(b)) for a,b in re.findall(r':([\w+-]+) ([0-9.E-]+)',mm.group(1))))
print('per kind: method R@5 R@10 tok@5 tok@10 (n)')
for kind in ['fns','sections','attr','config','record','tasks']:
    print(kind)
    for meth in ['embedding','graph','graph+embedding']:
        a5=agg.get((kind,meth,5)); a10=agg.get((kind,meth,10))
        if a5: print(f'  {meth:16s} {sum(x[0] for x in a5)/len(a5):.3f} {sum(x[0] for x in a10)/len(a10):.3f} {sum(x[1] for x in a5)/len(a5):5.0f} {sum(x[1] for x in a10)/len(a10):5.0f} ({len(a5)})')
print('\nneighbours of the shown top 5 (keys reached from the shown 5 functions), mean')
for k,ds in nb.items():
    m=lambda key: sum(d.get(key,0) for d in ds)/len(ds)
    print(f'  {k}: fused5 {m("fused5"):.3f}  fused5+next5 {m("fused5+next5"):.3f}  fused5+fn-neighbours {m("fused5+fn-neighbours"):.3f}  neighbours {m("neighbour-count"):.1f} costing {m("neighbour-tokens"):.0f} tok (n={len(ds)})')
ds=fnb['fns']; m=lambda key: sum(d.get(key,0) for d in ds)/len(ds)
print(f'  fns: fused5 {m("fused5"):.3f}  fused5+next5 {m("fused5+next5"):.3f}  fused5+key-neighbours5 {m("fused5+key-neighbours5"):.3f}  neighbours5 cost {m("neighbour-tokens"):.0f} tok (n={len(ds)})')
