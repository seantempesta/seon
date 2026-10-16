import sys; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
from clm.schema import state_text
eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors'))
forms=parse()
PROD={'targeted-read','probe-own-fix','progress'}
y=np.array([f['cls'] in PROD for f in forms])
def auc(s,y):
    s=np.asarray(s); pos=s[y]; neg=s[~y]
    return float(np.mean([(p>n)+0.5*(p==n) for p in pos for n in neg]))
INS={'noul-progress':"This form moves the agent closer to making its task's proof green, given what it already knew.",
     'noul-new':"This form gets the agent information or makes a change that it did not already have or make earlier in this attempt.",
     'noul-waste':"This form repeats work the agent already did or cannot succeed."}
k=8
for name,ins in INS.items():
    p=[];ms=[]
    for i in range(len(forms)):
        t=time.perf_counter(); r=eng.answer(state(forms,i,k),{'q':{'type':'noul','instructions':ins}}); ms.append((time.perf_counter()-t)*1000)
        p.append(r['answers']['q']['noul'])
    p=np.array(p); a=auc(p,y)
    if name=='noul-waste': a=1-a
    print(name,'AUC(productive vs wasteful) %.3f'%a,'mean P prod %.3f wasteful %.3f'%(p[y].mean(),p[~y].mean()),'ms %.0f'%np.mean(ms),flush=True)
    # re-read detector
    rr=np.array([f['cls']=='re-read-after-elision' for f in forms]); print('   AUC re-read as low-novelty: %.3f'%auc(-p if name!='noul-waste' else p,rr))
# exp2b: action ranking: score the true next form among the other forms of the same attempt (state excludes the form)
def st_nofrm(i,k):
    h=history(forms,i,k); return f"{TASK}\n\nForms the agent already ran (oldest first):\n{h or '(none yet)'}"
ranks=[];ms=[]
cands=[f['src'][:600] for f in forms]
for i,f in enumerate(forms):
    pool=[j for j,g in enumerate(forms) if g['attempt']==f['attempt'] and g['src']!=f['src']]
    idx=[i]+pool
    t=time.perf_counter(); sc=eng.score(st_nofrm(i,k),[cands[j] for j in idx],instructions="What should the agent run next?"); ms.append((time.perf_counter()-t)*1000)
    order=[r['index'] for r in sc['scored']]; ranks.append(order.index(0)+1); 
    f['pool']=len(idx)
ranks=np.array(ranks); pools=np.array([f['pool'] for f in forms])
print('exp2b true-form rank among same-attempt forms: mean rank %.1f (random %.1f), top1 %.3f top3 %.3f, mean pct %.3f'%(ranks.mean(),((pools+1)/2).mean(),(ranks==1).mean(),(ranks<=3).mean(),np.mean((ranks-1)/(pools-1))),'ms %.0f'%np.mean(ms))
# productive vs wasteful rank percentile
pct=(ranks-1)/(pools-1)
print('  mean percentile (0=best): productive %.3f wasteful %.3f'%(pct[y].mean(),pct[~y].mean()))
print('  by class:',{c:round(float(pct[[f['cls']==c for f in forms]].mean()),3) for c in sorted(set(f['cls'] for f in forms))})
