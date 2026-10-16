import sys, collections; sys.path.insert(0,'.')
from common import *
import torch
from seon_clm import Engine
eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors'))
forms=parse(); names=sorted(set(f['cls'] for f in forms)); y=np.array([names.index(f['cls']) for f in forms])
att=np.array([f['attempt'] for f in forms])
A,_,_=eng.project('action',[f['src'][:600] for f in forms]); S,_,_=eng.project('state',[state(forms,i,4) for i in range(len(forms))])
# raw (unprojected) 4096-d encoder embeddings of the forms: what finetune.py's heads train on
E_,_=eng.embedder.embed([f['src'][:600] for f in forms]); E_=np.asarray(E_,dtype=np.float32)
print('raw embedding dim',E_.shape)
def cv(X,name):
    accs=[];base=[]
    for a in sorted(set(att)):
        tr=att!=a; te=~tr
        Xt=torch.tensor(X[tr],dtype=torch.float32); yt=torch.tensor(y[tr])
        mu=Xt.mean(0); sd=Xt.std(0)+1e-6
        W=torch.nn.Linear(X.shape[1],len(names)); opt=torch.optim.Adam(W.parameters(),1e-2,weight_decay=1e-3)
        for _ in range(300):
            opt.zero_grad(); l=torch.nn.functional.cross_entropy(W((Xt-mu)/sd),yt); l.backward(); opt.step()
        p=W((torch.tensor(X[te],dtype=torch.float32)-mu)/sd).argmax(1).numpy()
        accs.append((p==y[te]).mean()); base.append((y[te]==collections.Counter(y[tr]).most_common(1)[0][0]).mean())
    print(name,'leave-one-attempt-out linear probe acc %.3f (majority %.3f)'%(np.mean(accs),np.mean(base)))
cv(A,'action-proj512'); cv(S,'state-proj512(k4)'); cv(np.hstack([A,S]),'action+state'); cv(E_,'raw4096 action')
