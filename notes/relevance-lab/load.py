import edn_format, numpy as np, pickle, sys, os
SP=os.path.dirname(os.path.abspath(__file__))
def kw(x):
    if isinstance(x,edn_format.Keyword): return x.name
    return x
def py(x):
    if isinstance(x,(edn_format.ImmutableDict,dict)): return {kw(k):py(v) for k,v in x.items()}
    if isinstance(x,(list,tuple,edn_format.ImmutableList)): return [py(v) for v in x]
    return kw(x)
items=[py(edn_format.loads(l)) for l in open(SP+'/export/items.edn')]
raw=[np.array(edn_format.loads(l),dtype=np.int16).astype(np.uint8) for l in open(SP+'/export/vecs.edn')]
V=np.stack([r.tobytes() and np.frombuffer(r.tobytes(),dtype='<f2') for r in raw]).astype(np.float32)
print(V.shape, np.linalg.norm(V[:5],axis=1))
pickle.dump((items,V),open(SP+'/corpus.pkl','wb'))
from collections import Counter; print(Counter(i['kind'] for i in items))
