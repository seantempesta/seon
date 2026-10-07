"""Census 1+2: docstring / comment / inline Malli schema lines per file in src. Output JSON + markdown tables."""
import sys, json, os, glob; sys.path.insert(0,'.')
import clj
root='/Users/sean/src/seon'
rows=[]
for p in clj.src_files(root):
    P=clj.load(p)
    rel=os.path.relpath(p,root)
    nl=P.text.count('\n')   # wc -l basis
    doc=set(); schema=set(); cm=set(); ctrl=set()
    for f in P.forms:
        for n in f.walk():
            if n.kind=='list' and n.kids:
                h=clj.strip_prefix(n.head())
                if h in ('defn','defn-','defmacro','def','defonce','defmulti','ns','defprotocol') and len(n.kids)>2 and n.kids[2].kind=='str':
                    if h in ('def','defonce','defmulti') and len(n.kids)<4: continue
                    s=n.kids[2]; doc.update(range(s.l0,s.l1+1))
                if h=='comment':
                    ctrl.update(range(n.l0,n.l1+1))
            if n.kind=='map':
                ks=n.kids
                for i in range(0,len(ks)-1,2):
                    if ks[i].kind=='kw' and ks[i].text==':malli/schema':
                        schema.update(range(ks[i].l0,ks[i+1].l1+1))
    cm=set(P.comment_lines)
    blank={i+1 for i,l in enumerate(P.lines) if l.strip()==''}
    # remove overlap: comment-form block lines count as 'comment form' separately; docstring wins
    rows.append(dict(file=rel,lines=nl,doc=len(doc-schema),comment=len(cm-doc),inline=len(P.inline_comment_lines),
        commentform=len(ctrl-doc),discard=sum(b-a+1 for a,b in P.discard_spans),schema=len(schema-doc),blank=len(blank)))
json.dump(rows,open('census1.json','w'))
T={k:sum(r[k] for r in rows) for k in rows[0] if k!='file'}
print(T)
for k in ('doc','comment','schema','commentform','inline','blank'):
    print('\n##',k,T[k],'%.2f%%'%(100*T[k]/T['lines']))
    for r in sorted(rows,key=lambda r:-r[k])[:10]:
        print(' ',r['file'],r[k],'of',r['lines'])
