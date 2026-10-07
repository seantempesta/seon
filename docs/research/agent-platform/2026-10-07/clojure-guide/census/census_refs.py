"""Census 7+8+9: dead code, single-caller functions, long functions. Writes refs.json."""
import sys, os, re, json, collections; sys.path.insert(0,'.')
import clj, model
from model import *
parsed,fns=build()
CAND={'defn','defn-','def','defmacro','defmulti','defonce'}
# defs per file (for enclosing lookup), including test/script files
defspans=collections.defaultdict(list)
for p,P in parsed.items():
    for kind,name,node in clj.top_defs(P):
        defspans[p].append((node.l0,node.l1,(clj.ns_of(P),name),kind))
    defspans[p].sort()
def enclosing(p,line):
    best=None
    for l0,l1,key,kind in defspans[p]:
        if l0<=line<=l1: best=key   # nested -> last (innermost start) wins
    return best
# schema lines per file
schlines={}
for p,P in parsed.items():
    s=set()
    for f in P.forms:
        for n in f.walk():
            if n.kind=='map':
                ks=n.kids
                for i in range(0,len(ks)-1,2):
                    if ks[i].kind=='kw' and ks[i].text==':malli/schema':
                        s.update(range(ks[i].l0,ks[i+1].l1+1))
    schlines[p]=s
byfile={rel(p):p for p in parsed}
name_nodes=set()
for f in fns: name_nodes.add(id(f.node.kids[1]))
# refs: key -> list of dict
refs=collections.defaultdict(list)
bare=collections.defaultdict(list)   # bare name -> list of (file,line)
for p,P in parsed.items():
    ns=clj.ns_of(P); aliases,refers=clj.ns_aliases(P)
    kindf='test' if p.startswith(root+'/test') else 'script' if p.startswith(root+'/script') else 'resclj' if p.startswith(root+'/resources') else 'src'
    for f in P.forms:
        for n in f.walk():
            if n.kind=='sym' and id(n) not in name_nodes:
                t=n.text
                if t.endswith('.') or t.startswith('.'): continue
                if '/' in t and t!='/':
                    a,b=t.split('/',1)
                    keys=[(aliases.get(a,a),b)]
                    bare[b].append((rel(p),n.l0))
                else:
                    keys=[(ns,t)]
                    if t in refers: keys.append((refers[t],t))
                    bare[t].append((rel(p),n.l0))
                enc=enclosing(p,n.l0)
                insch=n.l0 in schlines[p]
                for k in keys:
                    refs[k].append(dict(file=rel(p),line=n.l0,enc=enc,kind=('schema' if insch else kindf)))
            elif n.kind=='str':
                for m in QSYM.finditer(n.text):
                    refs[(aliases.get(m.group(1),m.group(1)),m.group(2))].append(dict(file=rel(p),line=n.l0,enc=enclosing(p,n.l0),kind='string'))
# raw text files
for p in raw_files():
    if p.endswith(('.clj','.cljc')) and p in parsed: continue
    try: txt=open(p,encoding='utf-8',errors='replace').read()
    except Exception: continue
    r=rel(p)
    kind='doc' if (r.startswith('docs') or r.startswith('.agents') or r.endswith('.md')) else 'resource'
    for m in QSYM.finditer(txt):
        line=txt.count('\n',0,m.start())+1
        refs[(m.group(1),m.group(2))].append(dict(file=r,line=line,enc=None,kind=kind))
# also resources .clj parsed: counted as resclj (resource)
out=[]
for f in fns:
    if f.kind not in CAND: continue
    key=(f.ns,f.name)
    rs=refs.get(key,[])
    own=[r for r in rs if r['file']==f.file and f.l0<=r['line']<=f.l1]
    ext=[r for r in rs if not (r['file']==f.file and f.l0<=r['line']<=f.l1)]
    # a ref in the metadata of the def itself is already inside span.
    cnt=collections.Counter(r['kind'] for r in ext)
    callers=sorted({r['enc'] for r in ext if r['kind']=='src' and r['enc'] is not None and r['enc']!=key},key=str)
    scallers=sorted({r['enc'] for r in ext if r['kind']=='schema' and r['enc'] is not None and r['enc']!=key},key=str)
    nonsrc_schema=len([r for r in ext if r['kind']=='schema' and not r['file'].startswith('src/')])
    topref=[r for r in ext if r['kind']=='src' and r['enc'] is None]
    weak=len([1 for (fl,ln) in bare.get(f.name,[]) if not (fl==f.file and f.l0<=ln<=f.l1)])
    out.append(dict(ns=f.ns,name=f.name,kind=f.kind,file=f.file,l0=f.l0,l1=f.l1,lines=f.l1-f.l0+1,
        n_self=len(own),cnt=dict(cnt),callers=[list(c) for c in callers],scallers=[list(c) for c in scallers],n_top=len(topref),nonsrc_schema=nonsrc_schema,weak=weak,
        ext=[(r['file'],r['line'],r['kind']) for r in ext][:6]))
json.dump(out,open('refs.json','w'))
print(len(out))
