"""Census 10: namespaced keyword vocabulary. Writes kw.json."""
import sys, os, re, json, collections; sys.path.insert(0,'.')
import clj
from model import *
def walk_kw(P, ns, aliases, group, counts, sites, files):
    def addkw(k, n):
        counts[k][group]+=1
        sites[k].add((P.path,n.l0))
        files[k].add(P.path)
    def visit(n, nsprefix=None):
        if n.kind=='kw':
            t=n.text
            if t.startswith('::'):
                body=t[2:]
                if '/' in body:
                    a,b=body.split('/',1); k=':'+aliases.get(a,a)+'/'+b
                else: k=':'+ns+'/'+body if ns else None
            elif '/' in t and not t.startswith(':/'): k=t
            elif nsprefix and t.count('/')==0 and t!=':' : k=':'+nsprefix+'/'+t[1:]
            else: k=None
            if k: addkw(k,n)
            return
        if n.kind=='map':
            nsp=None
            if n.prefix.startswith('#::'):
                a=n.prefix[3:]; nsp=aliases.get(a,a) if a else ns
            elif n.prefix.startswith('#:'): nsp=n.prefix[2:]
            ks=n.kids
            for i,k in enumerate(ks):
                visit(k, nsp if i%2==0 else None)
                # :keys destructuring: symbols with ns count as uses
            for i in range(0,len(ks)-1,2):
                if ks[i].kind=='kw' and (ks[i].text==':keys' or ks[i].text.endswith('/keys')) and ks[i+1].kind=='vec':
                    nsk=ks[i].text[1:-5] if ks[i].text.endswith('/keys') else None
                    for s in ks[i+1].kids:
                        if s.kind=='sym':
                            if '/' in s.text: addkw(':'+s.text,s)
                            elif nsk: addkw(':'+nsk+'/'+s.text,s)
            for m in n.meta: visit(m)
            return
        for m in n.meta: visit(m)
        for k in n.kids: visit(k)
    for f in P.forms: visit(f)
counts=collections.defaultdict(collections.Counter); sites=collections.defaultdict(set); files=collections.defaultdict(set)
def run(paths, group):
    for p in paths:
        P=clj.load(p); ns=clj.ns_of(P) or ''; aliases,_=clj.ns_aliases(P)
        walk_kw(P,ns,aliases,group,counts,sites,files)
run(SRC,'src'); run(clj.src_files(root,'test'),'test'); run(clj.src_files(root,'script'),'script')
edn=[]
for d,_,fs in os.walk(os.path.join(root,'resources')):
    for f in fs:
        if f.endswith('.edn'): edn.append(os.path.join(d,f))
for p in edn:
    sub='schema' if '/schemas/' in p else 'resedn'
    P=clj.load(p); walk_kw(P,'',{}, sub,counts,sites,files)
out={k:dict(c=dict(v),sites=len(sites[k]),files=sorted(os.path.relpath(x,root) for x in files[k])[:3],nfiles=len(files[k])) for k,v in counts.items()}
json.dump(out,open('kw.json','w'))
print(len(out))
