"""Census 6: near-duplicate defns by token-shingle Jaccard. Writes dups.json."""
import sys, json, collections, itertools; sys.path.insert(0,'.')
import clj
from model import *
parsed,fns=build()
CORE=set("""if if-not when when-not let letfn loop recur do fn fn* cond cond-> cond->> condp case and or not try catch finally throw def defn quote var
assoc assoc-in dissoc update update-in get get-in contains? merge select-keys keys vals map mapv filter filterv remove reduce reduce-kv into conj cons first rest next last
count empty? seq vec set vector list hash-map str pr-str name keyword symbol nil? some? map? vector? string? keyword? sequential? coll? fn? identity partial comp juxt apply
mapcat concat take drop take-while drop-while sort sort-by group-by frequencies distinct some every? not-any? not-every? range repeat for doseq dotimes while ->  ->> as-> some-> some->>
= == < > <= >= + - * / inc dec max min not= true? false? zero? pos? neg? deref swap! reset! atom volatile! nil true false format subs str/join str/blank? str/starts-with? str/ends-with? str/includes?
if-let when-let if-some when-some ex-info ex-data ex-message instance? satisfies? boolean double long int meta with-meta vary-meta rand-int update-vals update-keys set/union set/difference set/subset? set/rename-keys
""".split())
def toks_struct(n, out):
    if n.kind in ('list','vec','map','set','fn'):
        out.append({'list':'(','vec':'[','map':'{','set':'#{','fn':'#('}[n.kind])
        for k in n.kids: toks_struct(k,out)
        out.append(')')
    elif n.kind=='kw': out.append('K')
    elif n.kind=='str': out.append('STR')
    elif n.kind=='num': out.append('N')
    elif n.kind=='sym': out.append(n.text if n.text in CORE else 'S')
    else: out.append('O')
def toks_lex(n, out):
    if n.kind in ('list','vec','map','set','fn'):
        out.append({'list':'(','vec':'[','map':'{','set':'#{','fn':'#('}[n.kind])
        for k in n.kids: toks_lex(k,out)
        out.append(')')
    elif n.kind=='str': out.append('STR')
    else: out.append(n.text)
K=4
def shingles(t): return {hash(tuple(t[i:i+K])) for i in range(len(t)-K+1)}
items=[]
for f in fns:
    if f.kind not in ('defn','defn-','defmacro'): continue
    k=f.node.kids; i=2
    if i<len(k) and k[i].kind=='str' and len(k)>3: i+=1
    if i<len(k) and k[i].kind=='map': i+=1
    body=k[i:]
    ts=[]; tl=[]
    for b in body: toks_struct(b,ts); toks_lex(b,tl)
    codelines=len({l for b in body for l in range(b.l0,b.l1+1)})
    if len(ts)<60: continue
    items.append(dict(f=f,sa=shingles(ts),sb=shingles(tl),lines=f.l1-f.l0+1,codelines=codelines,ntok=len(ts)))
print(len(items))
inv=collections.defaultdict(list)
for idx,it in enumerate(items):
    for s in it['sa']: inv[s].append(idx)
pairs=[]
n=len(items)
for i in range(n):
    A=items[i]; la=len(A['sa'])
    for j in range(i+1,n):
        B=items[j]; lb=len(B['sa'])
        if min(la,lb)<0.35*max(la,lb): continue
        inter=len(A['sa']&B['sa'])
        sa=inter/(la+lb-inter)
        if sa<0.35: continue
        il=len(A['sb']&B['sb']); ul=len(A['sb'])+len(B['sb'])-il
        pairs.append(dict(a=[A['f'].file,A['f'].l0,A['f'].name,A['lines']],b=[B['f'].file,B['f'].l0,B['f'].name,B['lines']],sa=round(sa,3),sb=round(il/ul,3),small=min(A['lines'],B['lines'])))
pairs.sort(key=lambda p:-p['sa'])
json.dump(pairs,open('dups.json','w'))
print(len(pairs))
for p in pairs[:5]: print(p)
