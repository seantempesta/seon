"""Census 3,4,5: projection, error construction, defensive checks. Writes patterns.json (per-file line sets summarized)."""
import sys, os, re, json, collections; sys.path.insert(0,'.')
import clj
from model import *
parsed,fns=build()
srcfiles={rel(p):p for p in SRC}
NILP={'nil?','some?','map?','string?','seq?','sequential?','vector?','coll?','keyword?','symbol?','number?','int?','integer?','fn?','ifn?','boolean?','set?','instance?','contains?','empty?','not-empty','blank?','str/blank?','string/blank?','seqable?','char?','pos-int?','double?','uuid?','list?','nat-int?','true?','false?','ident?','qualified-keyword?'}
GUARDHEADS={'if','when','when-not','if-not','if-some','when-some','if-let','when-let','cond','cond->','cond->>','and','or','assert','case'}

def span(n): return range(n.l0,n.l1+1)

def fn_parts(node):
    k=node.kids; i=2
    doc=None; attr=None
    if i<len(k) and k[i].kind=='str' and len(k)>3: doc=k[i]; i+=1
    if i<len(k) and k[i].kind=='map': attr=k[i]; i+=1
    return doc,attr,k[i:]

def collect_pat(p, out):
    if p.kind=='map':
        ks=p.kids
        for i in range(0,len(ks)-1,2):
            k,v=ks[i],ks[i+1]
            if k.kind=='kw' and (k.text==':keys' or k.text.endswith('/keys')) and v.kind=='vec':
                nsp=k.text[1:-5] if k.text.endswith('/keys') else ''
                for s in v.kids:
                    if s.kind in ('sym','kw'):
                        t=s.text.lstrip(':')
                        out[t.split('/')[-1]]=(':'+t) if '/' in t else (':'+nsp+'/'+t if nsp else ':'+t)
            elif k.kind=='sym' and v.kind=='kw':
                out[k.text]=v.text
            elif k.kind in ('map','vec'):
                collect_pat(k,out)
    elif p.kind=='vec':
        for c in p.kids: collect_pat(c,out)

def readsyms_of(body):
    out={}
    for n in body:
        for x in n.walk():
            if x.kind=='vec' and x.kids:
                # params or binding vectors: patterns at even positions for let-like, all for params
                for j,c in enumerate(x.kids):
                    if c.kind=='map': collect_pat(c,out)
    return out

WRAP={'or','some->','not-empty','vec','set','boolean','str','when','some->>','seq'}
def read_kw(n,rs):
    if n.kind=='sym':
        return rs.get(n.text)
    if n.kind!='list' or not n.kids: return None
    h=n.kids[0]
    if h.kind=='kw' and 2<=len(n.kids)<=3: return h.text
    if h.kind=='sym':
        t=h.text.split('/')[-1]
        if t=='get' and len(n.kids)>=3 and n.kids[2].kind=='kw': return n.kids[2].text
        if t=='get-in' and len(n.kids)>=3 and n.kids[2].kind=='vec' and n.kids[2].kids and n.kids[2].kids[-1].kind=='kw': return n.kids[2].kids[-1].text
        if t in WRAP and len(n.kids)>=2: return read_kw(n.kids[1],rs)
        if len(n.kids)==2 and n.kids[1].kind=='kw' : return n.kids[1].text   # (m :k)
    return None

def analyse(f):
    node=f.node
    doc,attr,body=fn_parts(node)
    rs=readsyms_of(body)
    entries=[]   # (l0,l1,rekey)
    for b in body:
        for x in b.walk():
            if x.kind=='map':
                ks=x.kids
                for i in range(0,len(ks)-1,2):
                    k,v=ks[i],ks[i+1]
                    if k.kind=='kw':
                        r=read_kw(v,rs)
                        if r is not None: entries.append((k.l0,v.l1,k.text!=r))
            elif x.kind=='list' and x.kids and x.kids[0].kind=='sym' and x.kids[0].text.split('/')[-1]=='assoc':
                kk=x.kids; st=1 if len(kk)%2==1 else 2
                for i in range(st,len(kk)-1,2):
                    k,v=kk[i],kk[i+1]
                    if k.kind=='kw':
                        r=read_kw(v,rs)
                        if r is not None: entries.append((k.l0,v.l1,k.text!=r))
    # packaging maps: >=3 pairs, >=60% values are bare symbols or simple reads
    pack=set(); packpairs=0; srcrename=0
    def isval(v):
        return (v.kind=='sym' and v.text not in ('nil','true','false')) or read_kw(v,rs) is not None
    for b in body:
        for x in b.walk():
            if x.kind=='map' and len(x.kids)>=6:
                vs=x.kids[1::2]; ks=x.kids[0::2]
                good=sum(1 for k,v in zip(ks,vs) if k.kind=='kw' and isval(v))
                if good>=3 and good>=0.6*len(vs): pack.update(span(x)); packpairs+=good
            elif x.kind=='list' and x.kids and x.kids[0].kind=='sym':
                hb=x.kids[0].text.split('/')[-1]
                if hb=='assoc':
                    kk=x.kids; st=1 if len(kk)%2==1 else 2
                    prs=[(kk[i],kk[i+1]) for i in range(st,len(kk)-1,2)]
                    good=sum(1 for k,v in prs if k.kind=='kw' and isval(v))
                    if good>=3 and good>=0.6*len(prs): pack.update(range(kk[st].l0,kk[-1].l1+1)); packpairs+=good
                if hb in ('select-keys','rename-keys','zipmap','update-keys','update-vals','map-invert'): srcrename+=1
    # dedupe by key start line+end
    ent=sorted(set(entries))
    plines=set()
    for a,b,_ in ent: plines.update(range(a,b+1))
    # error construction
    errmap=set(); trylines=set(); catchlines=set(); throws=set(); plumb=set(); defensive=set(); guardforms=set(); guardform_lines=set(); ordef=set()
    pred_counter=collections.Counter()
    for b in body:
        for x in b.walk():
            if x.kind=='map':
                ks=x.kids
                if any(ks[i].kind=='kw' and ks[i].text.startswith(':seon.error/') for i in range(0,len(ks),2)):
                    errmap.update(span(x))
            if x.kind=='list' and x.kids and x.kids[0].kind=='sym':
                h=x.kids[0].text; hb=h.split('/')[-1]
                if h=='try':
                    trylines.update(span(x))
                    for c in x.kids[1:]:
                        if c.kind=='list' and c.kids and c.kids[0].text in ('catch','finally'): catchlines.update(span(c))
                if h in ('throw',) or hb=='ex-info': throws.update(span(x))
                # plumbing: test mentions :seon.error/ or error predicate
                if hb in ('if','when','when-not','if-not','if-let','when-let','cond','and','or','some?','case') and len(x.kids)>1:
                    tests=[]
                    if hb=='cond': tests=x.kids[1::2]
                    elif hb in ('and','or'): tests=x.kids[1:]
                    else: tests=[x.kids[1]]
                    for t in tests:
                        txt_nodes=list(t.walk())
                        if any((y.kind=='kw' and y.text.startswith(':seon.error/')) or (y.kind=='sym' and re.search(r'(^|[/.])(error|refus|failure|fault)[a-z\-]*\?$|error\?$|refusal\?$',y.text)) for y in txt_nodes):
                            plumb.update(span(t))
                # defensive guards
                if hb in GUARDHEADS and len(x.kids)>1:
                    tests=[]
                    if hb=='cond': tests=x.kids[1::2]
                    elif hb in ('and','or','case'): tests=[]
                    elif hb in ('cond->','cond->>'): tests=x.kids[2::2]
                    elif hb=='assert': tests=[x.kids[1]]
                    else: tests=[x.kids[1]]
                    for t in tests:
                        preds=[y for y in t.walk() if y.kind=='list' and y.kids and y.kids[0].kind=='sym' and y.kids[0].text in NILP]
                        if preds and (t.kind=='list' and (t.kids[0].text in NILP or t.kids[0].text in ('not','and','or'))):
                            guardforms.add((x.l0,x.l1,hb))
                            defensive.update(span(t))
                            if x.l1-x.l0<8: guardform_lines.update(span(x))
                            for y in preds: pred_counter[y.kids[0].text]+=1
                if hb=='or' and len(x.kids)>=3:
                    last=x.kids[-1]
                    if last.kind in ('num','str','kw') or (last.kind in ('map','vec','set') and not last.kids) or (last.kind=='sym' and last.text in ('nil','false','true')):
                        ordef.update(span(x))
                if h in NILP and h!='some?' : pass
    return dict(pack=sorted(pack),packpairs=packpairs,rename_calls=srcrename,entries=len(ent),rekey=sum(1 for e in ent if e[2]),plines=sorted(plines),errmap=sorted(errmap),trylines=sorted(trylines),catchlines=sorted(catchlines),
        throws=sorted(throws),plumb=sorted(plumb),defensive=sorted(defensive),guardforms=len(guardforms),guardform_lines=sorted(guardform_lines),ordef=sorted(ordef),preds=dict(pred_counter),
        doclines=(doc.l1-doc.l0+1) if doc else 0)

out=[]
for f in fns:
    if f.kind not in ('defn','defn-','defmacro'): continue
    try: a=analyse(f)
    except Exception as e:
        print('ERR',f.file,f.name,e); continue
    a.update(file=f.file,ns=f.ns,name=f.name,l0=f.l0,l1=f.l1,lines=f.l1-f.l0+1,kind=f.kind)
    out.append(a)
json.dump(out,open('patterns.json','w'))
print(len(out))
