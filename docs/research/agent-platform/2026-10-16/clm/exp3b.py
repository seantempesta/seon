import sys, collections; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors'))
forms=parse(); y=np.array([f['cls'] for f in forms]); src=[f['src'][:600] for f in forms]
A,_,_=eng.project('action',src)
# char-3gram baseline
vocab={}; rows=[]
for s in src:
    c=collections.Counter(s[i:i+3] for i in range(len(s)-2)); rows.append(c)
    for g in c: vocab.setdefault(g,len(vocab))
B=np.zeros((len(src),len(vocab)))
for i,c in enumerate(rows):
    for g,n in c.items(): B[i,vocab[g]]=n
B/=np.linalg.norm(B,axis=1,keepdims=True)
def loo(X,dedupe):
    sim=X@X.T; same=np.array([[a==b for b in src] for a in src])
    sim[same if dedupe else np.eye(len(src),dtype=bool)]=-9
    return (y[sim.argmax(1)]==y).mean()
for n,X in (('CLM action',A),('char-3gram',B)):
    print(n,'1-NN no self %.3f ; excluding identical source %.3f'%(loo(X,False),loo(X,True)))
