import re,glob,collections,sys
rows=[]
for f in sorted(glob.glob(sys.argv[1]+'/results-*.edn')):
    s=open(f).read()
    for m in re.finditer(r'\{:task "([^"]+)", :plan-at "(\w+)", :want \{([^}]*)\}, :dropped \{[^}]*\}, :scores \{(.*?)\}\}(?=,? ?\{:task|\]$)',s,re.S):
        t,at,want,sc=m.groups()
        cells={}
        for mm in re.finditer(r'\[:([\w+-]+) :(\w+) (\d+)\] \{:recall ([0-9.E-]+), :tokens (\d+)\}',sc):
            meth,kind,n,r,tok=mm.groups(); cells[(meth,kind,int(n))]=(float(r),int(tok))
        rows.append((t,at,cells))
print(len(rows),'tasks;', sum(1 for r in rows if r[1]=='parent'),'with the plan the worker got')
meths=['embedding','graph','grep','graph+embedding','seeds-then-embedding','all-three']
for subset,keep in [('all',lambda a:True),('plan as given (parent)',lambda a:a=='parent')]:
    print('\n###',subset)
    for kind in ['fns','sections','schemas','tasks']:
        n=sum(1 for t,a,c in rows if keep(a) and ('graph',kind,5) in c)
        print(f'\n{kind} (n={n})  method: R@5 R@10 tok@5 tok@10')
        for m in meths:
            vals=[c[(m,kind,k)] for t,a,c in rows if keep(a) for k in [5,10] if (m,kind,k) in c]
            r5=[c[(m,kind,5)][0] for t,a,c in rows if keep(a) and (m,kind,5) in c]
            r10=[c[(m,kind,10)][0] for t,a,c in rows if keep(a) and (m,kind,10) in c]
            t5=[c[(m,kind,5)][1] for t,a,c in rows if keep(a) and (m,kind,5) in c]
            t10=[c[(m,kind,10)][1] for t,a,c in rows if keep(a) and (m,kind,10) in c]
            if r5: print(f'  {m:22s} {sum(r5)/len(r5):.3f} {sum(r10)/len(r10):.3f} {sum(t5)/len(t5):5.0f} {sum(t10)/len(t10):5.0f}')
