import sys, resource; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
import collections
t0=time.time(); eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors')); load=time.time()-t0
print('load s',load,flush=True)
CLASSES={
 'targeted-read':'The form reads the source, doc or value of a named function, schema or namespace the agent has not read before.',
 're-read-after-elision':'The form reads again exactly something the agent already read earlier in this attempt (same source or same name), because the earlier reply is no longer in its history.',
 'probe-own-fix':'The form calls or tests the function the agent just defined or changed, with example inputs, to check the fix works.',
 'in-ns-navigation':'The form only switches the current namespace with in-ns.',
 'search-discovery':'The form searches for names: search, dir, callers, ns-decls, apropos, or lists what exists.',
 'progress':'The form defines or changes code: a defn or a deftest that implements the fix or the proof.',
 'contract-refusal':'The form calls a function with the wrong argument shape, guessed arguments or a wrong type, and is refused.',
 'task-read':'The form reads the current task, its plan or the next task.',
 'cleanup':'The form removes or unmaps a definition or scratch namespace.',
 'invented-name':'The form uses a function or namespace name the agent guessed that does not exist.',
 'other':'The form is something else that does not fit the other descriptions.'}
forms=parse()
Q={'c':{'type':'choice','instructions':"What kind of step is the agent's latest form?",'criteria':CLASSES}}
res={}
for k in (0,4,8):
    preds=[];ms=[];toks=[]
    for i in range(len(forms)):
        s=state(forms,i,k)
        t=time.perf_counter(); r=eng.answer(s,Q); ms.append((time.perf_counter()-t)*1000)
        a=r['answers']['c']; preds.append((a['choice'],a['confidence'],a['probabilities'])); toks.append(r['usage']['input_tokens'])
    res[k]=dict(preds=preds,ms=ms,toks=toks)
    ok=sum(p[0]==f['cls'] for p,f in zip(preds,forms))
    print('k',k,'acc',ok/len(forms),'ms mean',np.mean(ms),'median',np.median(ms),'tokens mean',np.mean(toks),flush=True)
    conf=collections.Counter((f['cls'],p[0]) for p,f in zip(preds,forms) if p[0]!=f['cls'])
    print(' top confusions',conf.most_common(8),flush=True)
    # per-class recall
    for c in CLASSES:
        n=[p[0]==c for p,f in zip(preds,forms) if f['cls']==c]
        if n: print('  ',c,sum(n),'/',len(n))
json.dump({str(k):v for k,v in res.items()},open('exp1_results.json','w'))
print('rss MB',resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1e6)
