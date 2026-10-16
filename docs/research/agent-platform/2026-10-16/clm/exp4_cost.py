import sys, resource; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
t=time.time(); eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors')); print('cold load s %.1f'%(time.time()-t))
forms=parse()
big=" ".join(f['src'] for f in forms)*6
def mk(n):  # text of about n tokens by chars/3.2
    return "".join([big[j:j+1] for j in range(int(n*3.2))])
eng.embedder.embed([mk(50)])
for n in (100,300,1000,2048):
    tx=[mk(n)+f" variant {i}" for i in range(5)]
    ts=[]
    for x in tx:
        t=time.perf_counter(); _,tok=eng.embedder.embed([x]); ts.append((time.perf_counter()-t)*1000)
    print('state %d tok: %.0f ms median (tokens counted %d)'%(n,np.median(ts),tok))
for b in (8,32):
    tx=[forms[i]['src'][:300]+f' {j}' for j,i in enumerate(range(b))]
    t=time.perf_counter(); eng.embedder.embed(tx); print('batch %d short actions: %.0f ms total, %.1f ms/each'%(b,(time.perf_counter()-t)*1000,(time.perf_counter()-t)*1000/b))
# 117 compact states uncached (k=4) timing, then cached
st=[state(forms,i,4) for i in range(len(forms))]
t=time.perf_counter(); eng.project('state',st); a=time.perf_counter()-t
t=time.perf_counter(); eng.project('state',st); b=time.perf_counter()-t
print('117 compact states (k=4, ~236 tok): cold %.1f s = %.0f ms each; cached %.3f s'%(a,a/117*1000,b))
print('peak RSS GB %.1f'%(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1e9))
print('10,000 turns at 150 ms/state + 5 forms*12 ms: %.0f min'%((10000*(0.15+0.06))/60))
