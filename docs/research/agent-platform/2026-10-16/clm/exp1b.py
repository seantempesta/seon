import sys; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
import collections
eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors'))
forms=parse()
V={'long':None,
'example':{
 'targeted-read':"The agent reads the source or docs of one named function: (my.program/source 'ns/name) or (doc ns/name).",
 're-read-after-elision':"The agent reads the same source again that it already read earlier in this attempt: a repeated (my.program/source 'ns/name) of a name already in the forms above.",
 'probe-own-fix':"The agent calls the function it just defined with example arguments to see whether the fix works, e.g. (span-candidate #{} \"x/*\").",
 'in-ns-navigation':"The agent switches the current namespace: (in-ns 'some.namespace).",
 'search-discovery':"The agent searches for names: (my.program/search ...), (dir ns), (callers ...), (ns-decls ...).",
 'progress':"The agent defines code: (defn name [args] body) or (deftest name ...) that implements the fix or the proof.",
 'contract-refusal':"The agent calls a function with guessed or wrongly shaped arguments, such as a string where a symbol is required, and the call is refused.",
 'task-read':"The agent reads its task: (my.task/current) and the plan.",
 'cleanup':"The agent deletes a scratch definition: (ns-unmap ...) or (remove-ns ...).",
 'invented-name':"The agent calls a function that does not exist, a guessed name that does not resolve.",
 'other':"Some other kind of REPL form."}}
from clm.schema import state_text
import importlib
exp1=open('exp1_choice.py').read()
V['long']=eval(exp1[exp1.index('CLASSES=')+8:exp1.index("forms=parse()")])
names=list(V['long'])
ins="What kind of step is the agent's latest form?"
out={}
for vn in V:
  for k in (4,8):
    L=[];ms=[]
    for i in range(len(forms)):
        s=state(forms,i,k)
        t=time.perf_counter()
        sc=eng.score(s,[V[vn][n] for n in names],instructions=ins)
        ms.append((time.perf_counter()-t)*1000)
        v=np.zeros(len(names))
        for r in sc['scored']: v[r['index']]=r['logit']
        L.append(v)
    L=np.array(L); y=np.array([names.index(f['cls']) for f in forms])
    raw=(L.argmax(1)==y).mean()
    cal=L-L.mean(0)   # unsupervised contextual calibration: subtract each class's mean logit over the corpus
    calacc=(cal.argmax(1)==y).mean()
    # per-class-recall of calibrated
    conf=collections.Counter((names[a],names[b]) for a,b in zip(y,cal.argmax(1)) if a!=b)
    print(vn,'k',k,'raw acc %.3f calibrated acc %.3f ms %.0f'%(raw,calacc,np.mean(ms)),flush=True)
    print('  cal confusions',conf.most_common(6),flush=True)
    rec={n:'%d/%d'%(((cal.argmax(1)==y)&(y==j)).sum(),(y==j).sum()) for j,n in enumerate(names) if (y==j).any()}
    print('  recall',rec,flush=True)
    out[f'{vn}-{k}']=L.tolist()
json.dump(out,open('exp1b_logits.json','w'))
