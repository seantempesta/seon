"""Assemble census.md and TSV lists from the JSON outputs of the other census_*.py scripts."""
import sys, json, collections, os, re, csv; sys.path.insert(0,'.')
import clj
from model import *
TOT=90950
def pct(x): return '%.2f%%'%(100*x/TOT)
c1=json.load(open('census1.json')); P=json.load(open('patterns.json')); R=json.load(open('refs.json'))
D=json.load(open('dups.json')); K=json.load(open('kw.json')); X=json.load(open('extra.json'))
out=[]; w=out.append
def table(head,rows):
    w('| '+' | '.join(head)+' |'); w('|'+'|'.join(['---']*len(head))+'|')
    for r in rows: w('| '+' | '.join(str(x) for x in r)+' |')
    w('')
def byfile(pred_key, items=P, f=lambda p:len(p)):
    c=collections.Counter()
    for p in items: c[p['file']]+=f(p[pred_key])
    return c
def top10(c,total=None,unit='lines'):
    tot={x['file']:x['lines'] for x in c1}
    w('Top 10 files: '+'; '.join('`%s` %d (%.0f%%)'%(fl.replace('src/seon/','').replace('src/',''),n,100*n/tot[fl]) for fl,n in c.most_common(10))+'.'); w('')

w('# Seon src census: where the 91k lines go'); w('')
w('Base: `src/` = %d `.clj/.cljc` files, **%d lines** (`wc -l` basis; every %% below is of this number unless it says otherwise). Snapshot: branch refactor/agent-platform at HEAD a8e3bde5b plus dirty tree, 2026-10-07. Research only: nothing outside `tmp/clojure-guide/` was edited, no JVM/test/bin/seon was run.'%(len(c1),TOT)); w('')
w('## Method (reproducible)'); w('')
w('All scripts are in `tmp/clojure-guide/census/` and run with plain `python3` (3.x, stdlib only) from that directory:'); w('')
w('- `clj.py`: a ~200-line Clojure reader (strings, chars, regex, `#_`, `#:ns{}`, `^meta`, reader conditionals) producing a tree with exact line spans; cross-checked: all 107 files parse to their last form, 4040 top-level defining forms found.')
w('- `model.py`: finds top-level `def/defn/defn-/defmacro/defmulti/defonce/deftest/defmethod` (also inside top-level `do`/`when`).')
w('- `census1.py` (items 1, 2): docstring lines = lines spanned by the docstring string node of a def/defn/defmacro/ns; whole-line `;` comments counted per line; `:malli/schema` lines = lines spanned from the key to the end of its value in any map (meta or attr-map). The three sets are disjoint.')
w('- `census_patterns.py` (items 3, 4, 5): per-function AST rules, defined in each section below.')
w('- `census_dup.py` (item 6), `census_refs.py` (items 7, 8, 9), `census_kw.py` (item 10), `census_extra.py` (breakdowns), `census_report.py` (this file). Raw outputs: `census1.json patterns.json dups.json refs.json kw.json extra.json`; full lists: `dead.tsv onecaller.tsv longfns.tsv dups.tsv`.')
w('')
w('Every number is a heuristic count of lines. Categories can overlap (a `try` inside a function that also projects keys); the final table says where overlaps matter. Line spans include the lines of nested forms, so "lines of X" means "lines the X form occupies".')
w('')
# ---- Section 1
T={k:sum(r[k] for r in c1) for k in c1[0] if k!='file'}
w('## Headline'); w('')
table(['bucket','lines','% of src'],[
 ['docstrings (def/defn/defn-/defmacro/ns)',T['doc'],pct(T['doc'])],
 ['whole-line comments',T['comment'],pct(T['comment'])],
 ['inline `:malli/schema` (function metadata)',T['schema'],pct(T['schema'])],
 ['blank lines',T['blank'],pct(T['blank'])],
 ['everything else (code proper, ns forms, signatures)',TOT-T['doc']-T['comment']-T['schema']-T['blank'],pct(TOT-T['doc']-T['comment']-T['schema']-T['blank'])],
 ['total',TOT,'100%']])
w('Documentation prose + inline contracts are %s of src before a single line of logic. Outside src, `resources/seon/schemas` adds %d more lines of schema EDN (%s of src-size).'%(pct(T['doc']+T['comment']+T['schema']),X['edn']['total'],pct(X['edn']['total'])))
w('')
w('## 1. Docstrings and comments'); w('')
w('Method: `census1.py`. Docstring = the string node in docstring position (after the name; `def` only when a value follows). Comment = a line whose first non-blank char is `;`.'); w('')
table(['measure','lines','% of src'],[['docstring lines',T['doc'],pct(T['doc'])],['whole-line comment lines',T['comment'],pct(T['comment'])],['trailing (inline) comments (lines)',T['inline'],pct(T['inline'])],['`#_` discarded forms',T['discard'],pct(T['discard'])],['`(comment ...)` forms',T['commentform'],pct(T['commentform'])]])
w('### Top 10 files by docstring lines'); w(''); top10({r['file']:r['doc'] for r in c1} and collections.Counter({r['file']:r['doc'] for r in c1}))
w('### Top 10 files by comment lines'); w(''); top10(collections.Counter({r['file']:r['comment'] for r in c1}))
w('### Docstring anatomy'); w('')
db=X['doc_by']; dn=X['doc_n']
table(['owner','docstrings','lines','avg lines'],[[k,dn[k],db[k],'%.1f'%(db[k]/dn[k])] for k in ('defn','defn-','def','defmacro','ns')])
bk=X['buckets']; bl=X['bucket_lines']
table(['docstring length','count','lines','% of docstring lines'],[[k,bk[k],bl[k],'%.0f%%'%(100*bl[k]/T['doc'])] for k in ('1','2-3','4-6','7-12','13+')])
w('- **Private functions (`defn-`) carry %d docstring lines (%s of src)** in %d docstrings; the project style says privates need contracts, evidently also prose.'%(db['defn-'],pct(db['defn-']),dn['defn-']))
w('- Docstrings of 7+ lines: %d docstrings, %d lines (%s of src, %.0f%% of all docstring lines). Docstrings of 13+ lines are %d docstrings / %d lines.'%(bk['7-12']+bk['13+'],bl['7-12']+bl['13+'],pct(bl['7-12']+bl['13+']),100*(bl['7-12']+bl['13+'])/T['doc'],bk['13+'],bl['13+']))
w('- Docstring-bearing public defns: %d of %d public defns (the rest have none).'%(dn['defn'],sum(1 for r in R if r['kind']=='defn'))); w('')
cm=X['cm_types']
w('### Comment anatomy'); w('')
table(['kind','lines'],[[k,v] for k,v in sorted(cm.items(),key=lambda kv:-kv[1])])
w('`;;` form comments are %d lines (the repo convention puts `;;` above a form), `;;;` section headers/banner rules are %d. Prose after `;` is almost absent. Sampled (`seon/turn.clj` 3088-3127): the comments are multi-paragraph design rationale above forms, i.e. a second docstring written in `;;`.'%(cm[';; form comment'],cm['banner rule']+cm[';;; runtime-structure/section']))
w('')
# ---- Section 2
w('## 2. Inline Malli schemas and schema EDN'); w('')
w('Method: every map pair whose key is `:malli/schema` (function metadata or attr-map); lines = key line through end of value. `census_extra.py` splits the forms by shape. Schema EDN: `wc -l` over `resources/seon/schemas/*.edn` (%d files).'%X['edn']['files']); w('')
table(['measure','forms','lines','% of src'],[['inline `:malli/schema` forms',X['schema_forms'],X['schema_lines'],pct(X['schema_lines'])],['schema EDN (resources/seon/schemas, outside src)','',X['edn']['total'],pct(X['edn']['total'])],['inline + EDN','',X['schema_lines']+X['edn']['total'],pct(X['schema_lines']+X['edn']['total'])]])
w('### Top 10 files by inline schema lines'); w(''); top10(collections.Counter({r['file']:r['schema'] for r in c1}))
w('### Top 10 schema EDN files'); w('')
table(['file','lines','lines declaring `*-error` entries','comment lines'],[[r[0],r[1],r[2],r[3]] for r in X['edn']['top']])
w('### Shape of the inline schemas'); w('')
h=X['schema_len_hist']; hl=X['schema_len_hist_lines']
table(['form length','forms','lines'],[[k,h[k],hl[k]] for k in ('1','2-5','6-15','16+')])
sp=X['sch_parts']
w('Lines (inside inline schemas) that carry a recognizable repeated element (a line can count in several):'); w('')
table(['element','lines'],[[k,v] for k,v in sorted(sp.items(),key=lambda kv:-kv[1])])
w('- %d `:malli/schema` forms are textually identical to an earlier one (%d groups): 45 copies of the polymorphic-boundary predicate wrapper `[:=> [:cat [:any {:seon.schema.admission/exemption ... :reason "A total predicate ..."}]] :boolean]` and 33 copies of `[:=> [:cat :seon.schema/value] :boolean]`. Naming them once saves only %d lines (they are mostly one-liners) but the exemption-reason prose repeats.'%(X['schema_dup_forms']-X['schema_dup_groups'],X['schema_dup_groups'],X['schema_dup_lines_saved']))
w('- `*-error` entries in schema EDN: %d entries on %d lines (%.1f%% of EDN). Each is a `[:map {:seon.render/ai ... :seon.render/html ... :error/message "..."} [:k :k] [:seon.error/message ...]]` declaration; their companion cost is the `[:or ... :x-error]` unions in function schemas (%d inline-schema lines name a `-error`).'%(X['edn']['errdecls'],X['edn']['errlines'],100*X['edn']['errlines']/X['edn']['total'],sp.get('lines naming a -error alternative',0)))
w('')
# ---- Section 3
w('## 3. Map re-keying / projection'); w('')
w('Method (`census_patterns.py`, per `defn`/`defn-`/`defmacro`, AST based). A **simple read** is `(:k m)`, `(:k m default)`, `(get m :k)`, `(get-in m [.. :k])`, `(m :k)`, a wrapper around one (`or`, `some->`, `not-empty`, `vec`, `set`, `boolean`, `str`), or a local bound by `{:keys [...]}` / `{sym :k}` destructuring. Two measures:'); w('')
w('- **projection entries**: a `{:k <simple read>}` pair in a map literal, or a `(assoc ... :k <simple read>)` pair; "rekey" when the written key differs from the read key. Lines = lines the pairs span.')
w('- **packaging maps**: map literals or `assoc` chains with >=3 pairs where >=60% of values are bare symbols or simple reads (a map assembled from locals). Heavy function = packaging lines >= 50% of its non-docstring lines.'); w('')
pl=sum(len(p['plines']) for p in P); pk=sum(len(p['pack']) for p in P)
both=sum(len(set(p['plines'])|set(p['pack'])) for p in P)
heavy=[p for p in P if p['packpairs']>=3 and len(p['pack'])/max(1,p['lines']-p['doclines'])>=0.5]
heavy2=[p for p in P if p['entries']>=4 and len(p['plines'])/max(1,p['lines']-p['doclines'])>=0.4]
hv=sum(len(set(p['plines'])|set(p['pack'])) for p in P if p in heavy or p in heavy2)
table(['measure','count','lines','% of src'],[
 ['simple-read projection entries (key copied from a read)',sum(p['entries'] for p in P),pl,pct(pl)],
 ['  of which rekey (written key != read key)',sum(p['rekey'] for p in P),'',''],
 ['packaging maps / assoc chains from locals+reads',sum(p['packpairs'] for p in P),pk,pct(pk)],
 ['union of the two',' ',both,pct(both)],
 ['functions that are mostly packaging (>=50% of code lines)',len(heavy),sum(p['lines'] for p in heavy),pct(sum(p['lines'] for p in heavy))],
 ['calls to select-keys / rename-keys / zipmap / update-keys / update-vals',sum(p['rename_calls'] for p in P),'','']])
w('Reading: pure projection is **small** (~%s of src as entry lines): Seon mostly reads and writes the same fully-qualified keys, so re-keying is rarer than the style-guide hypothesis assumed. The larger cost is *assembly*: %d lines build maps out of locals, often error or request maps (the same `:seon.error/*`, `:seon.db/*`, `:seon.cluster.eval/*` keys re-listed in each producer).'%(pct(pl),pk)); w('')
w('### Top 10 files by union of projection + packaging lines'); w('')
c=collections.Counter()
for p in P: c[p['file']]+=len(set(p['plines'])|set(p['pack']))
top10(c)
w('### Examples (mostly-packaging functions, `file:line name lines/packaging-lines`)'); w('')
for p in sorted(heavy,key=lambda p:-len(p['pack']))[:12]:
    w('- `%s:%d %s` %d/%d lines, %d pairs, %d rekeyed reads'%(p['file'],p['l0'],p['name'],p['lines'],len(p['pack']),p['packpairs'],p['rekey']))
w('')
def excerpt(file,l0,n=14):
    L=open(os.path.join(root,file)).read().split('\n')
    return '\n'.join(L[l0-1:l0-1+n])
for file,l0,n in [('src/seon/fs/jvm.clj',127,10),('src/seon/render/transcript.clj',258,15)]:
    w('`%s:%d`'%(file,l0)); w(''); w('```clojure'); w(excerpt(file,l0,n)); w('```'); w('')
w('Both are the same shape: destructure or read N namespaced keys, then restate each as a key of a new map. The producer of the input and the consumer of the output usually already agree on the key set; the fix is to pass the original map (or `select-keys` it once) rather than hand-copy.')
w('')
# ---- Section 4
w('## 4. Error construction'); w('')
w('Method (`census_patterns.py`; spans are line sets, unions per function so nothing is counted twice within a measure):'); w('')
w('- **error maps**: map literals with at least one direct key starting `:seon.error/` (lines the literal spans).')
w('- **try/catch**: `try` forms (whole span, includes the protected body) and, separately, only the `catch`/`finally` clause lines.')
w('- **throw/ex-info**: lines of `(throw ...)` and `(ex-info ...)` forms.')
w('- **union plumbing**: the *test* expressions of `if/when/cond/and/or/case` that mention a `:seon.error/` keyword or a predicate named `*error?`/`*refus*?`/`*failure?`.')
w('- **error producer functions**: defns whose name contains `error`, `refus` or `failure` (whole defn lines, including docstring + schema).'); w('')
em=sum(len(p['errmap']) for p in P); tl=sum(len(p['trylines']) for p in P); cl=sum(len(p['catchlines']) for p in P); th=sum(len(p['throws']) for p in P); pb=sum(len(p['plumb']) for p in P)
uni=sum(len(set(p['errmap'])|set(p['catchlines'])|set(p['throws'])|set(p['plumb'])) for p in P)
prod=[p for p in P if re.search(r'error|refus|failure',p['name'])]
raw=0
for pth in SRC:
    raw+=sum(1 for l in open(pth).read().split('\n') if ':seon.error/' in l)
table(['measure','lines','% of src'],[
 ['error-map literals',em,pct(em)],['try forms (whole span)',tl,pct(tl)],['catch/finally clauses only',cl,pct(cl)],['throw / ex-info forms',th,pct(th)],['union-plumbing test expressions',pb,pct(pb)],
 ['**union of error maps + catch + throw + plumbing**',uni,pct(uni)],
 ['error-producer functions (name contains error/refus/failure): %d fns'%len(prod),sum(p['lines'] for p in prod),pct(sum(p['lines'] for p in prod))],
 ['raw: every src line containing a `:seon.error/` keyword',raw,pct(raw)],
 ['schema-side: `*-error` entries in schema EDN',X['edn']['errlines'],pct(X['edn']['errlines'])+' (of src-size)'],
 ['schema-side: inline-schema lines naming a `-error` alternative',sp.get('lines naming a -error alternative',0),pct(sp.get('lines naming a -error alternative',0))]])
w('Functions containing: a `try` %d, an error-map literal %d, a throw/ex-info %d (of %d defn/defn-/defmacro).'%(sum(1 for p in P if p['trylines']),sum(1 for p in P if p['errmap']),sum(1 for p in P if p['throws']),len(P)))
w('')
w('### Top 10 files by union error lines'); w('')
c=collections.Counter()
for p in P: c[p['file']]+=len(set(p['errmap'])|set(p['catchlines'])|set(p['throws'])|set(p['plumb']))
top10(c)
w('### Top 10 files by catch/finally clause lines'); w(''); top10(byfile('catchlines'))
w('Reading: error handling costs ~%s of src as lines that exist *only* to build, throw, catch or branch on errors, plus the declaration cost in schemas (about %s more when inline `-error` alternatives and the EDN entries are added). `try` spans are %s because a `try` encloses the real work; the incremental ceremony is the %d catch lines.'%(pct(uni),pct(X['edn']['errlines']+sp.get('lines naming a -error alternative',0)),pct(tl),cl)); w('')
# ---- Section 5
w('## 5. Defensive checks'); w('')
w('Method (`census_patterns.py`): a **guard** is an `if/when/when-not/if-not/if-some/when-some/if-let/when-let/cond/cond->/assert` whose test is (or `and/or/not`-combines) a predicate from {nil? some? map? string? seq? sequential? vector? coll? keyword? symbol? number? int? integer? fn? ifn? boolean? set? instance? contains? empty? not-empty blank? seqable? char? ...}. `or`-default = `(or x <literal/empty coll/nil>)`. The raw table counts every call of the predicate anywhere in src (regex over source text).'); w('')
df=sum(len(p['defensive']) for p in P); gf=sum(p['guardforms'] for p in P); gfl=sum(len(p['guardform_lines']) for p in P); od=sum(len(p['ordef']) for p in P)
table(['measure','count','lines','% of src'],[['guard forms',gf,'',''],['lines holding a guard test',' ',df,pct(df)],['lines of small guard forms (<8 lines each): test + both branches',' ',gfl,pct(gfl)],['`(or x <literal default>)` forms',sum(1 for p in P if p['ordef']),od,pct(od)]])
cnt=collections.Counter()
rx=re.compile(r'\((nil\?|some\?|map\?|string\?|seq\?|sequential\?|vector\?|coll\?|keyword\?|symbol\?|number\?|int\?|integer\?|fn\?|ifn\?|boolean\?|set\?|instance\?|contains\?|empty\?|not-empty|blank\?|seqable\?)[\s)]')
linesraw=set()
for pth in SRC:
    for i,l in enumerate(open(pth).read().split('\n')):
        m=rx.findall(l)
        if m: linesraw.add((pth,i)); cnt.update(m)
table(['predicate call','occurrences in src'],[[k,v] for k,v in cnt.most_common(14)])
w('Distinct source lines containing any of these predicate calls: **%d (%s of src)**; total predicate calls: %d.'%(len(linesraw),pct(len(linesraw)),sum(cnt.values())))
w(''); w('### Top 10 files by guard-form lines'); w(''); top10(byfile('guardform_lines'))
w('### Top 10 files by lines holding a guard test'); w(''); top10(byfile('defensive'))
w('Reading: `contains?`(%d) and `map?`(%d) lead; these are mostly *map-shape dispatch* ("is this the error alternative or the value?") and *argument shape guards* inside functions that already carry a Malli contract. The contract rule (every function, private ones included, armed at call time) makes in-body `map?`/`string?` argument checks redundant; the dispatch checks are the error-union plumbing of section 4 seen from the other side.'%(cnt['contains?'],cnt['map?'])); w('')
# ---- Section 6
w('## 6. Near-duplicate functions'); w('')
w('Method (`census_dup.py`): every defn/defn-/defmacro with >=60 body tokens (%d functions). Tokens come from the body only (docstring, attr-map and metadata removed). **Structural** tokens: delimiters + `K` for keywords, `STR`, `N`, core-vocabulary symbols kept, every other symbol -> `S`. **Lexical** tokens keep all symbols and keywords. Both are sets of 4-token shingles; similarity = Jaccard. All pairs with structural similarity >= 0.35 are kept (%d); ranking below is by the mean of structural and lexical (structural alone has a ~0.4 baseline between unrelated Clojure functions, so a high structural score with low lexical score means "same shape, different content" -- a `let`/`cond` skeleton, not copy-paste).'%(1779,len(D))); w('')
for p in D: p['score']=round((p['sa']+p['sb'])/2,3)
D2=sorted(D,key=lambda p:-p['score'])
table(['#','struct','lexical','a (file:line name lines)','b (file:line name lines)'],[[i+1,p['sa'],p['sb'],'%s:%d `%s` %d'%tuple(p['a'][:4]),'%s:%d `%s` %d'%tuple(p['b'][:4])] for i,p in enumerate(D2[:40])])
real=[p for p in D2 if p['sa']>=0.5 and p['sb']>=0.3]
w('- Pairs with struct>=0.5 and lexical>=0.3 (genuinely similar content): **%d pairs**; sum of the smaller member of each = %d lines (upper bound on what a shared helper would remove, counting each pair once; chains overlap).'%(len(real),sum(p['small'] for p in real)))
w('- Pairs with struct>=0.35: %d; most are generic shape matches.'%len(D))
w('- Exact structural twins (struct = 1.0): `db/pull` vs `db/pull-many` (47 vs 49 lines, same skeleton), `edit/prefix-end-within` vs `suffix-start-within`, `render/render-ai` vs `render-html` (lexical 0.76).')
w('- Strongest lexical copies: `render-ai`/`render-html` (`src/seon/render.clj:1386/1404`), `edit/jvm filesystem-refusal` vs `fs/jvm error-value` (lexical 0.64, in two namespaces), `sci/eval success-evaluation`/`failed-evaluation` (0.52), `transcript render-run-ai`/`render-run-html` (0.55), `effect settle-call`/`interrupt-call` (0.47), `schema/edn directory-resource?`/`schema-resource-paths` (0.57).')
w('- The `render-*-html` / `render-*-ai` pairing is structural by design (two faces); the duplication pattern worth a rule is the **same function body written twice with one face-specific line** (`render-ai`/`render-html` at lines 1386/1404).')
w('')
w('Honest conclusion: Seon has little classic copy-paste. Duplication is in *shape* (the same destructure/validate/build-error skeleton), which sections 3-5 measure directly. Full pair list: `dups.tsv`.'); w('')
with open('dups.tsv','w') as f:
    f.write('struct\tlexical\ta\tb\n')
    for p in D2: f.write('%s\t%s\t%s:%d %s\t%s:%d %s\n'%(p['sa'],p['sb'],p['a'][0],p['a'][1],p['a'][2],p['b'][0],p['b'][1],p['b'][2]))
# ---- Section 7
w('## 7. Dead or nearly-dead code'); w('')
w('Method (`census_refs.py`): all `def/defn/defn-/defmacro/defmulti/defonce` in src (4037). References are resolved by namespace: every symbol node in src, test, script and `resources/**.clj` is resolved through the file\'s `:require :as`/`:refer` (a qualified `a/b` -> (ns of a, b); an unqualified `b` -> (this ns, b) or its `:refer` ns); references inside `:malli/schema` forms, strings, `resources/**` (EDN included), `bin/`, `deps.edn`, `docs/` and `.agents/` are matched by regex `ns/name`. References inside the function\'s own span (recursion, its own schema) are excluded. A definition can be live through a dynamic path no regex sees (e.g. `(resolve (symbol ns n))` with built strings); the bare-name check below flags where that is plausible.'); w('')
def tier(r):
    c=r['cnt']; srcc=len(r['callers'])+r['n_top']
    if srcc: return 'referenced from other src code'
    if r['scallers'] or c.get('schema'): return 'only from schemas (:fn predicates)'
    if c.get('resource') or c.get('resclj') or c.get('script'): return 'only from resources/scripts'
    if c.get('test'): return 'only from tests'
    if c.get('string') or c.get('doc'): return 'only from strings/docs'
    return 'no reference at all'
cn=collections.Counter(); ln=collections.Counter()
for r in R: t=tier(r); cn[t]+=1; ln[t]+=r['lines']
table(['reference tier','defs','lines','% of src'],[[k,cn[k],ln[k],pct(ln[k])] for k in ['no reference at all','only from strings/docs','only from tests','only from schemas (:fn predicates)','only from resources/scripts','referenced from other src code']])
D0={(r['ns'],r['name']):r for r in R}
def prodroot(r):
    c=r['cnt']; return bool(c.get('resource') or c.get('resclj') or c.get('script') or c.get('string') or r['n_top'] or r.get('nonsrc_schema') or r['ns'].startswith('my.') or r['name']=='-main')
def reach(rootf):
    st=[k for k,r in D0.items() if rootf(r)]; callees=collections.defaultdict(set)
    for k,r in D0.items():
        for c in r['callers']+r['scallers']: callees[tuple(c)].add(k)
    seen=set(st)
    while st:
        x=st.pop()
        for y in callees.get(x,()):
            if y not in seen: seen.add(y); st.append(y)
    return seen
allr=reach(lambda r: prodroot(r) or r['cnt'].get('test')); prd=reach(prodroot)
un=[D0[k] for k in D0 if k not in allr]; tonly=[D0[k] for k in D0 if k in allr and k not in prd]
w('Call-graph reachability (callee edges from src bodies and schema forms; roots = referenced from resources/scripts/strings/top-level forms, any `my.*` def (agent-facing API), `-main`):'); w('')
table(['set','defs','lines','% of src'],[['**unreachable from any root, tests included (dead, including dead clusters)**',len(un),sum(r['lines'] for r in un),pct(sum(r['lines'] for r in un))],['reachable **only through tests** (production roots cannot reach them)',len(tonly),sum(r['lines'] for r in tonly),pct(sum(r['lines'] for r in tonly))]])
w('### Dead list (unreachable from any root), by size'); w('')
table(['file:line','name','kind','lines','refs seen (doc=docs only)','same bare name elsewhere'],[['%s:%d'%(r['file'],r['l0']),r['name'],r['kind'],r['lines'],json.dumps(r['cnt']) if r['cnt'] else 'none',r['weak']] for r in sorted(un,key=lambda r:-r['lines'])])
w('Note: %d of the %d unreachable lines are `seon.eval.drive` (`run-sample!` -> `run-episode!` and helpers), referenced only from docs: an eval driver with no caller.'%(sum(r['lines'] for r in un if r['file']=='src/seon/eval/drive.clj'),sum(r['lines'] for r in un))); w('')
w('Caveat for every list in this section: Seon agents call `seon.*` and `my.*` functions by name from the REPL (SCI), which no static scan sees. A function with a test reference but no src caller may be an agent-facing entry; confirm before deleting.'); w('')
w('### Referenced only from tests (not a call from any src function)'); w('')
to=[r for r in R if tier(r)=='only from tests']
table(['file:line','name','lines','test refs'],[['%s:%d'%(r['file'],r['l0']),r['name'],r['lines'],r['cnt'].get('test',0)] for r in sorted(to,key=lambda r:-r['lines'])[:12]])
w('(%d defs, %d lines in total; top 12 shown. %d are in `src/my`, which is the agent API and lives by being called from agents at the REPL, so treat those as live.)'%(len(to),sum(r['lines'] for r in to),sum(1 for r in to if r['ns'].startswith('my.')))); w('')
w('### Reachable only through tests (top 12 by lines; full list in `dead.tsv`)'); w('')
table(['file:line','name','lines'],[['%s:%d'%(r['file'],r['l0']),r['name'],r['lines']] for r in sorted(tonly,key=lambda r:-r['lines'])[:12]])
with open('dead.tsv','w') as f:
    f.write('class\tfile\tline\tname\tkind\tlines\n')
    for r in un: f.write('dead\t%s\t%d\t%s\t%s\t%d\n'%(r['file'],r['l0'],r['name'],r['kind'],r['lines']))
    for r in tonly: f.write('test-reach-only\t%s\t%d\t%s\t%s\t%d\n'%(r['file'],r['l0'],r['name'],r['kind'],r['lines']))
w('Reading: static dead code is **not** where the lines go: %d zero-reference defs (%d lines) and %d unreachable (%d lines), i.e. well under 1%% of src. The code is densely and intentionally used. The near-dead mass is the %d defs (%d lines, %s) that production roots cannot reach but tests can: machinery kept alive by its own tests.'%(cn['no reference at all'],ln['no reference at all'],len(un),sum(r['lines'] for r in un),len(tonly),sum(r['lines'] for r in tonly),pct(sum(r['lines'] for r in tonly)))); w('')
# ---- Section 8
w('## 8. Functions with exactly one caller (inlining candidates)'); w('')
w('Method: defn/defn-/defmacro, exactly one distinct calling src function, no references from schemas, resources, scripts, strings or top-level forms. "No test" = additionally no test file references it (a test reference would have to move with an inline). Overhead = defn line + docstring + `:malli/schema` lines + 1 blank, which is what inlining deletes outright; the body moves.'); w('')
def overhead(r):
    # recompute doc & schema lines for the def
    return None
pm={(p['ns'],p['name']):p for p in P}
# schema lines per fn
sch={}
for pth in SRC:
    Pp=clj.load(pth); ns=clj.ns_of(Pp)
    for kind,name,node in clj.top_defs(Pp):
        s=set()
        for x in node.walk():
            if x.kind=='map':
                ks=x.kids
                for i in range(0,len(ks)-1,2):
                    if ks[i].kind=='kw' and ks[i].text==':malli/schema': s.update(range(ks[i].l0,ks[i+1].l1+1))
        sch[(ns,name,node.l0)]=len(s)
def one(r):
    c=r['cnt']
    if r['kind'] not in ('defn','defn-','defmacro'): return False
    if len(r['callers'])!=1 or r['scallers'] or r['n_top']: return False
    return not any(c.get(k) for k in ('resource','resclj','script','string'))
O=[r for r in R if one(r)]
for r in O:
    p=pm.get((r['ns'],r['name']))
    r['over']=(p['doclines'] if p else 0)+sch.get((r['ns'],r['name'],r['l0']),0)+2
ON=[r for r in O if not r['cnt'].get('test')]
same=[r for r in ON if r['callers'][0][0]==r['ns']]
table(['set','fns','lines','overhead lines (header+doc+schema)'],[
 ['exactly one src caller',len(O),sum(r['lines'] for r in O),sum(r['over'] for r in O)],
 ['  and no test reference',len(ON),sum(r['lines'] for r in ON),sum(r['over'] for r in ON)],
 ['  and caller in the same namespace',len(same),sum(r['lines'] for r in same),sum(r['over'] for r in same)],
 ['  no test ref, <=15 lines',sum(1 for r in ON if r['lines']<=15),sum(r['lines'] for r in ON if r['lines']<=15),sum(r['over'] for r in ON if r['lines']<=15)],
 ['  no test ref, public (`defn`)',sum(1 for r in ON if r['kind']=='defn'),sum(r['lines'] for r in ON if r['kind']=='defn'),'']])
w('%.0f%% of all %d functions have exactly one caller (%s of src).'%(100*len(O)/len(R),len(R),pct(sum(r['lines'] for r in O)))); w('')
c=collections.Counter()
for r in ON: c[r['file']]+=r['lines']
w('### Top 10 files by single-caller (no-test) function lines'); w('')
table(['file','lines','fns'],[[f,n,sum(1 for r in ON if r['file']==f)] for f,n in c.most_common(10)])
w('### The 15 largest single-caller functions with no test reference'); w('')
table(['file:line','name','lines','caller'],[['%s:%d'%(r['file'],r['l0']),r['name'],r['lines'],'/'.join(r['callers'][0])] for r in sorted(ON,key=lambda r:-r['lines'])[:15]])
with open('onecaller.tsv','w') as f:
    f.write('file\tline\tname\tlines\toverhead\ttest_refs\tcaller\n')
    for r in sorted(O,key=lambda r:-r['lines']): f.write('%s\t%d\t%s\t%d\t%d\t%d\t%s\n'%(r['file'],r['l0'],r['name'],r['lines'],r['over'],r['cnt'].get('test',0),'/'.join(r['callers'][0])))
w('Reading: many single-caller helpers are fine (a name documents a step). The target is the *small* ones whose header+docstring+contract cost more than the body: among the <=15-line group, overhead is %d of %d lines.'%(sum(r['over'] for r in ON if r['lines']<=15),sum(r['lines'] for r in ON if r['lines']<=15))); w('')
# ---- Section 9
w('## 9. Long functions (>60 lines)'); w('')
w('Method: `defn/defn-/defmacro` form span (docstring, schema and body included).'); w('')
Lg=[r for r in R if r['kind'] in ('defn','defn-','defmacro') and r['lines']>60]
w('**%d functions, %d lines = %s of src.** Of those, >100 lines: %d fns / %d lines; >200 lines: %d fns / %d lines.'%(len(Lg),sum(r['lines'] for r in Lg),pct(sum(r['lines'] for r in Lg)),sum(1 for r in Lg if r['lines']>100),sum(r['lines'] for r in Lg if r['lines']>100),sum(1 for r in Lg if r['lines']>200),sum(r['lines'] for r in Lg if r['lines']>200))); w('')
pmap=pm
def doc_of(r):
    p=pmap.get((r['ns'],r['name'])); return p['doclines'] if p else 0
table(['file:line','name','lines','of which docstring+schema'],[['%s:%d'%(r['file'],r['l0']),r['name'],r['lines'],doc_of(r)+sch.get((r['ns'],r['name'],r['l0']),0)] for r in sorted(Lg,key=lambda r:-r['lines'])[:30]])
c=collections.Counter()
for r in Lg: c[r['file']]+=r['lines']
w('### Top 10 files by lines in long functions'); w('')
table(['file','lines in >60-line fns','fns'],[[f,n,sum(1 for r in Lg if r['file']==f)] for f,n in c.most_common(10)])
with open('longfns.tsv','w') as f:
    f.write('file\tline\tname\tlines\n')
    for r in sorted(Lg,key=lambda r:-r['lines']): f.write('%s\t%d\t%s\t%d\n'%(r['file'],r['l0'],r['name'],r['lines']))
w('The full list of %d is in `longfns.tsv`. Seven functions exceed 250 lines; `seon.test/select`, `seon.test/run` and `seon.sci.eval/evaluate` exceed 350.'%len(Lg)); w('')
# ---- Section 10
w('## 10. Namespaced keyword vocabulary'); w('')
w('Method (`census_kw.py`): every namespaced keyword token (`:a/b`, `::b`, `::alias/b`, and bare keys under `#:ns{}` / `#::{}` map prefixes) in src, test, script and `resources/**/*.edn`; `{:keys [a/b]}` destructuring counts as a use of `:a/b`. "Occurrences" = tokens; "places" = distinct (file, line) sites. Dynamically built keywords (`(keyword ns n)`) and keywords in docs are invisible, so the low-use counts are upper bounds on true disuse.'); w('')
tot=lambda v:sum(v['c'].values())
N=len(K)
oc=collections.Counter(min(tot(v),6) for v in K.values()); st=collections.Counter(min(v['sites'],6) for v in K.values())
table(['uses','by occurrences (tokens)','by places (lines)'],[[('6+' if i==6 else i),oc[i],st[i]] for i in range(1,7)])
w('- Distinct namespaced keywords: **%d**.'%N)
one2=[k for k,v in K.items() if v['sites']<=2]
w('- Used in only one or two places: **%d (%.0f%%)**; exactly one place: %d (%.0f%%).'%(len(one2),100*len(one2)/N,sum(1 for v in K.values() if v['sites']==1),100*sum(1 for v in K.values() if v['sites']==1)/N))
def where(v):
    g=set(v['c']); return g
cat=collections.Counter()
for k in one2:
    g=where(K[k])
    if g<= {'schema','resedn'}: cat['declared in schema/EDN only (no src/test use found)']+=1
    elif g<= {'src'}: cat['src only']+=1
    elif g<= {'test'}: cat['test only']+=1
    elif g<= {'src','test'}: cat['src + test']+=1
    elif 'schema' in g and 'src' in g: cat['schema + src']+=1
    else: cat['other mix']+=1
w('')
table(['the <=2-place keywords are...','count'],[[k,v] for k,v in cat.most_common()])
nsc=collections.Counter(); nsone=collections.Counter()
for k,v in K.items():
    n=k[1:].split('/')[0]; nsc[n]+=1
    if v['sites']<=2: nsone[n]+=1
w('### Top 10 keyword namespaces by distinct keywords'); w('')
w('; '.join('`%s` %d (%d)'%(n,c,nsone[n]) for n,c in nsc.most_common(10))+'. (distinct keywords, in parentheses those used in <=2 places)'); w('')
w('### Top 10 keyword namespaces by <=2-place keywords'); w('')
w('; '.join('`%s` %d of %d'%(n,c,nsc[n]) for n,c in nsone.most_common(10))+'.'); w('')
w('### Top 10 files containing the most single-use keywords (src files only)'); w('')
fc=collections.Counter()
for k,v in K.items():
    if v['sites']==1 and v['files'] and v['files'][0].startswith('src/'): fc[v['files'][0]]+=1
w('; '.join('`%s` %d'%(f.replace('src/seon/',''),c) for f,c in fc.most_common(10))+'.'); w('')
w('Reading: roughly %.0f%% of the vocabulary is touched in at most two places. A schema-declared key that a reader never reads is a stored fact without a consumer (project rule: "every stored fact names its consumer or is deleted"). Counts here do not prove disuse but give the audit list: `kw.json` has every keyword with its per-group counts.'%(100*len(one2)/N)); w('')
# ---- Final table
w('## Ranked summary'); w('')
w('Removable-line estimates are judgments, each stated with its reasoning; they are deliberately conservative and **not additive** (docstring, schema, error and projection lines overlap when a single function has all of them).'); w('')
privdoc=db['defn-']
long_doc=bl['7-12']+bl['13+']
rem_doc=int(0.5*privdoc+0.25*(db['defn']+db['def']+db['defmacro']))
rows=[
 ['Docstrings',T['doc'],pct(T['doc']),rem_doc,'private-fn docstrings cut to nothing/one line (50%% of %d) + 25%% of the rest (long public docstrings restating contract/body)'%privdoc,'docstrings only where a name+contract does not already say it; none on `defn-`; <=3 lines, longer content goes to one Markdown page'],
 ['Inline Malli schemas',T['schema'],pct(T['schema']),int(0.35*T['schema']),'derive/shorthand: 1-line predicate contracts, shared named input/output schemas instead of `[:or X :x-error]` repeats, drop exemption-reason/`:gen/` prose (%d+%d lines)'%(sp.get('admission exemption/reason lines',0),sp.get('generator hint lines',0)),'one contract line per function: named schemas in EDN, never restated shapes or reasons inline'],
 ['Schema EDN (outside src)',X['edn']['total'],pct(X['edn']['total']),int(0.15*X['edn']['total']),'`*-error` entries (%d lines) generated from one error-family declaration; keywords declared but never used'%X['edn']['errlines'],'declare an error family once; delete keys without a consumer'],
 ['Whole-line comments',T['comment'],pct(T['comment']),int(0.5*T['comment']),'banner rules + `;;;` headers (%d) and second-docstring `;;` paragraphs (merge into docstring or delete)'%(cm['banner rule']+cm[';;; runtime-structure/section']),'`;;` only for a non-obvious why of one form; no banners; no commented-out code'],
 ['Long functions (>60 lines)',sum(r['lines'] for r in Lg),pct(sum(r['lines'] for r in Lg)),0,'not removable by itself; they host most of the error/guard/packaging lines counted above and are the place to look for them','functions <=40 lines, one role each'],
 ['Error construction/handling (maps+catch+throw+plumbing)',uni,pct(uni),int(0.4*uni),'one error constructor from declared fields replaces hand-listed `:seon.error/*` maps; boundary-only try/catch instead of local catch+rethrow','build errors only through the one constructor; try/catch only at entrant boundaries; no local throw/catch pairs'],
 ['Defensive guards (guard-form lines)',gfl,pct(gfl),int(0.5*gfl),'`map?`/`string?`/`nil?` checks of arguments the contract already validates; keep union-dispatch only','trust the contract: no argument-shape checks inside armed functions; `or` defaults only where the schema says optional'],
 ['Packaging/projection maps',both,pct(both),int(0.4*both),'pass the original map or `select-keys` once; do not restate N keys as N pairs','one key set per concept, carried unchanged; build derived maps with `select-keys`/`assoc` of the changed keys only'],
 ['Single-caller helpers (overhead only)',sum(r['over'] for r in ON),pct(sum(r['over'] for r in ON)),int(0.6*sum(r['over'] for r in ON if r['lines']<=15)),'inline helpers <=15 lines: header+docstring+schema lines vanish (60% assumed inlinable)','extract only for reuse or a named concept with its own test; otherwise `let`'],
 ['Reachable only via tests',sum(r['lines'] for r in tonly),pct(sum(r['lines'] for r in tonly)),int(0.5*sum(r['lines'] for r in tonly)),'machinery nothing in production reaches; delete with its tests (50%: some are intentional test fixtures/harness)','a function earns its place by a production caller; test-only helpers live in test/'],
 ['Dead (unreachable)',sum(r['lines'] for r in un),pct(sum(r['lines'] for r in un)),sum(r['lines'] for r in un),'`seon.eval.drive` cluster + a few helpers','delete on sight; the linter flag + root sweep'],
 ['Near-duplicate functions',sum(p['small'] for p in real),pct(sum(p['small'] for p in real)),int(0.5*sum(p['small'] for p in real)),'pairs with struct>=0.5 and lexical>=0.3; shared helper keeps one copy','one function parameterized by face/operation, not two copies (render-ai/render-html pattern)'],
 ['Single-use keywords (>=1 key line each)',len(one2),'(count, not lines)',int(len(one2)*0.3),'est. 30% of <=2-place keywords are undeclared-consumer facts; each is ~1 schema line + 1 write line','every stored fact names its consumer (already a law); audit script flags <=2-place keys'],
]
rows.sort(key=lambda r:-r[3] if isinstance(r[3],int) else 0)
table(['pattern','lines','% of src','realistic removable lines','how estimated','style rule that prevents it'],rows)
tot_rem=sum(r[3] for r in rows if r[0] not in ('Single-use keywords (>=1 key line each)','Long functions (>60 lines)'))
w('Sum of the removable column: %d lines; even after discounting 30%% for overlap that is ~%d lines (%.0f%% of src). Documentation, contract and error ceremony (first 6 rows) is where the mass is; classic dead code and copy-paste are small.'%(tot_rem,int(0.7*tot_rem),100*0.7*tot_rem/TOT))
open('/Users/sean/src/seon/tmp/clojure-guide/census.md','w').write('\n'.join(out)+'\n')
print(len(out))
