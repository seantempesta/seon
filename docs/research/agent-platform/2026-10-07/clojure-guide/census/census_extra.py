"""Extra breakdowns for docstrings, comments, schemas (src + resources/seon/schemas). Writes extra.json."""
import sys, os, re, json, collections; sys.path.insert(0,'.')
import clj
from model import *
res={}
doc_by=collections.Counter(); doc_n=collections.Counter(); buckets=collections.Counter(); bucket_lines=collections.Counter()
docs=[]
cm_types=collections.Counter(); sec_headings=collections.Counter()
sch_forms=[]  # (file,l0,l1,text)
sch_parts=collections.Counter()
nsdoc=collections.Counter()
for p in SRC:
    P=clj.load(p); r=rel(p)
    for l in P.comment_lines:
        t=P.lines[l-1].strip()
        if re.match(r'^;;;\s*-{5,}',t) or re.match(r'^;+\s*[-=]{8,}\s*$',t): cm_types['banner rule']+=1
        elif t.startswith(';;;'): cm_types[';;; runtime-structure/section']+=1
        elif t.startswith(';;'): cm_types[';; form comment']+=1
        else: cm_types['; prose']+=1
    for n0 in P.forms:
        for n in n0.walk():
            if n.kind=='list' and n.kids and n.kids[0].kind=='sym':
                h=n.kids[0].text
                if h in ('defn','defn-','defmacro','def','defonce','defmulti','ns') and len(n.kids)>2 and n.kids[2].kind=='str' and (h=='ns' or len(n.kids)>3):
                    s=n.kids[2]; L=s.l1-s.l0+1
                    doc_by[h]+=L; doc_n[h]+=1
                    b='1' if L==1 else '2-3' if L<=3 else '4-6' if L<=6 else '7-12' if L<=12 else '13+'
                    buckets[b]+=1; bucket_lines[b]+=L
                    docs.append((r,s.l0,L,h))
                    for m in re.finditer(r'^\s*(Examples|Edge cases|Costs|Recommendations|Errors|Known issues)\s*$',s.text,re.M): sec_headings[m.group(1)]+=1
                    # lines beyond first 4 / paragraphs
                if n.kids and n.kids[0].text in ('defn','defn-','defmacro','def'):
                    pass
            if n.kind=='map':
                ks=n.kids
                for i in range(0,len(ks)-1,2):
                    if ks[i].kind=='kw' and ks[i].text==':malli/schema':
                        v=ks[i+1]; txt=re.sub(r'\s+',' ',' '.join(P.lines[v.l0-1:v.l1])).strip()
                        sch_forms.append((r,ks[i].l0,v.l1-ks[i].l0+1,txt))
                        for l in range(ks[i].l0,v.l1+1):
                            lt=P.lines[l-1]
                            if 'seon.schema.admission/' in lt: sch_parts['admission exemption/reason lines']+=1
                            if ':gen/' in lt: sch_parts['generator hint lines']+=1
                            if re.search(r'-error\b',lt): sch_parts['lines naming a -error alternative']+=1
                            if ':seon.error/value' in lt: sch_parts['lines naming :seon.error/value']+=1
                            if ':error/message' in lt or ':error{' in lt or '#:error' in lt: sch_parts['custom :fn error-message lines']+=1
                            if ':db/database-value' in lt or 'database-value' in lt: sch_parts['lines naming database-value']+=1
res['doc_by']=dict(doc_by); res['doc_n']=dict(doc_n); res['buckets']=dict(buckets); res['bucket_lines']=dict(bucket_lines)
res['sections']=dict(sec_headings); res['cm_types']=dict(cm_types); res['sch_parts']=dict(sch_parts)
# schema duplicates
g=collections.defaultdict(list)
for r,l,L,t in sch_forms: g[t].append((r,l,L))
dup_save=0; dup_groups=0; dup_forms=0
for t,v in g.items():
    if len(v)>1:
        dup_groups+=1; dup_forms+=len(v); dup_save+=sum(max(0,x[2]-1) for x in v[1:])+0
res['schema_forms']=len(sch_forms); res['schema_lines']=sum(x[2] for x in sch_forms)
res['schema_dup_groups']=dup_groups; res['schema_dup_forms']=dup_forms; res['schema_dup_lines_saved']=dup_save
Ls=[x[2] for x in sch_forms]
res['schema_len_hist']={'1':sum(1 for x in Ls if x==1),'2-5':sum(1 for x in Ls if 2<=x<=5),'6-15':sum(1 for x in Ls if 6<=x<=15),'16+':sum(1 for x in Ls if x>15)}
res['schema_len_hist_lines']={'1':sum(x for x in Ls if x==1),'2-5':sum(x for x in Ls if 2<=x<=5),'6-15':sum(x for x in Ls if 6<=x<=15),'16+':sum(x for x in Ls if x>15)}
top=sorted(g.items(),key=lambda kv:-len(kv[1])*max(x[2] for x in kv[1]))[:5]
res['top_dups']=[(len(v),v[0][2],t[:160]) for t,v in top]
# private docstring lines
# EDN
edn=[]
for d,_,fs in os.walk(os.path.join(root,'resources/seon/schemas')):
    for f in fs: edn.append(os.path.join(d,f))
rows=[];errlines=0;errdecls=0;comment_edn=0;doc_edn=0;tot=0;ent_lines=0
for p in edn:
    P=clj.load(p); n=P.text.count('\n'); tot+=n
    el=set()
    for f in P.forms:
        for x in f.walk():
            if x.kind=='map':
                ks=x.kids
                for i in range(0,len(ks)-1,2):
                    if ks[i].kind=='kw' and ks[i].text.endswith('-error'):
                        el.update(range(ks[i].l0,ks[i+1].l1+1)); errdecls+=1
    errlines+=len(el)
    cl=len(P.comment_lines); comment_edn+=cl
    rows.append((rel(p),n,len(el),cl))
res['edn']=dict(total=tot,files=len(edn),errlines=errlines,errdecls=errdecls,comment=comment_edn,top=sorted(rows,key=lambda r:-r[1])[:10],toperr=sorted(rows,key=lambda r:-r[2])[:5])
# how many EDN schema keys total
json.dump(res,open('extra.json','w'),indent=1,default=list)
print(json.dumps(res,indent=1,default=list)[:6000])
