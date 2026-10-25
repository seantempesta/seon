# Ground truth for the related-search comparison: each merge of jolt/<task> on main whose task entry
# (resources/seon/tasks/*.edn at the merge's first parent) has a plan; what the merge changed:
# vars (top-level def forms whose text differs in src/), wiki sections (docs/*.md headings whose
# section text differs), schema keys (resources/seon/schemas/*.edn entries that differ), and other
# task entries it added or changed. Writes EDN for the agent-side script.
import subprocess, re, json, sys
def git(*a): return subprocess.run(['git',*a],capture_output=True,text=True).stdout
def show(rev,path): return subprocess.run(['git','show',f'{rev}:{path}'],capture_output=True,text=True).stdout
def anchor(h):
    a=re.sub(r'[^\w\- ]','',h.strip().lower()).replace(' ','-'); return a
def defs(text):
    out={}; ns=None; cur=None; buf=[]
    for line in text.split('\n'):
        m=re.match(r'\(ns ([\w.\-]+)',line)
        if m: ns=m.group(1)
        m=re.match(r'\((?:defn-?|def|defmacro|defmulti|defmethod|defprotocol|defrecord|deftest)\s+(?:\^\S+\s+)?([^\s\[\]()]+)',line)
        if m:
            if cur: out[cur]='\n'.join(buf)
            cur=f'{ns}/{m.group(1)}'; buf=[line]
        elif cur: buf.append(line)
    if cur: out[cur]='\n'.join(buf)
    return out
def sections(path,text):
    out={}; cur=None; buf=[]; seen={}
    for line in text.split('\n'):
        m=re.match(r'#{1,6} (.*)',line)
        if m:
            if cur: out[cur]='\n'.join(buf)
            a=anchor(m.group(1)); n=seen.get(a,0); seen[a]=n+1
            cur=f'{path}#{a}'+(f'-{n}' if n else ''); buf=[]
        elif cur: buf.append(line)
    if cur: out[cur]='\n'.join(buf)
    return out
def schema_keys(text):
    out={}; cur=None; buf=[]
    for line in text.split('\n'):
        m=re.match(r'\s?\{?\s?(:[\w.\-]+/[\w.\-?!*]+)\s',line)
        if m and not line.startswith('  '):
            if cur: out[cur]='\n'.join(buf)
            cur=m.group(1); buf=[line]
        elif cur: buf.append(line)
    if cur: out[cur]='\n'.join(buf)
    return out
def tasks_in(text):
    return {m.group(1):m.group(0) for m in re.finditer(r':seon\.task/name "([^"]+)".*?(?=:seon\.task/name "|\Z)',text,re.S)}
def changed(fn,a,b): return {k for k in set(a)|set(b) if a.get(k)!=b.get(k) and k in b}
rows=[]
for line in git('log','main','--merges','--format=%H %P|%s').splitlines():
    shas,subj=line.split('|',1)
    m=re.match(r"Merge (?:branch ')?jolt/([\w\-]+)",subj)
    if not m: continue
    name=m.group(1); h,p1=shas.split()[0],shas.split()[1]
    files=[f for f in git('diff','--name-only',p1,h).splitlines()]
    plan=None; tid=None; at=None
    for rev,where in [(p1,'parent'),(h,'merge')]:
        for nm in [name, re.sub(r'-\d+$','',name)]:
            for tf in [f for f in git('ls-tree','--name-only',rev,'resources/seon/tasks/').splitlines() if f.endswith('.edn')]:
                blk=tasks_in(show(rev,tf)).get(nm)
                mm=blk and re.search(r':seon\.task/plan "((?:[^"\\]|\\.)*)"',blk)
                if mm:
                    plan=mm.group(1).replace('\\"','"').replace('\\\\','\\'); tid=tf.split('/')[-1][:-4]+'/'+nm; at=where; break
            if plan: break
        if plan: break
    if not plan: continue
    fns=set(); secs=set(); sch=set(); tks=set()
    for f in files:
        a,b=show(p1,f),show(h,f)
        if f.startswith('src/') and f.endswith('.clj'): fns|=changed(f,defs(a),defs(b))
        elif f.startswith('docs/') and f.endswith('.md'): secs|=changed(f,sections(f,a),sections(f,b))
        elif f.startswith('resources/seon/schemas/'): sch|=changed(f,schema_keys(a),schema_keys(b))
        elif f.startswith('resources/seon/tasks/'):
            ns=f.split('/')[-1][:-4]; tks|={ns+'/'+n for n in changed(f,tasks_in(a),tasks_in(b)) if n!=name}
    if not (fns or secs or sch): continue
    rows.append({'task':tid,'merge':h[:9],'plan-at':at,'plan':plan,'fns':sorted(fns),'sections':sorted(secs),'schemas':sorted(sch),'tasks':sorted(tks)})
by={}
for r in rows:
    if r['task'] in by:
        o=by[r['task']]
        for k in ['fns','sections','schemas','tasks']: o[k]=sorted(set(o[k])|set(r[k]))
    else: by[r['task']]=r
rows=list(by.values())
def edn(x):
    if isinstance(x,dict): return '{'+' '.join(f':{k} {edn(v)}' for k,v in x.items())+'}'
    if isinstance(x,list): return '['+' '.join(edn(v) for v in x)+']'
    return json.dumps(x)
open(sys.argv[1],'w').write('['+'\n'.join(edn(r) for r in rows)+']')
import collections
print(len(rows),'tasks;', 'with fns',sum(1 for r in rows if r['fns']),'sections',sum(1 for r in rows if r['sections']),'schemas',sum(1 for r in rows if r['schemas']),'tasks',sum(1 for r in rows if r['tasks']))
print('mean sizes', *[round(sum(len(r[k]) for r in rows)/len(rows),1) for k in ['fns','sections','schemas','tasks']])
