import sys, collections; sys.path.insert(0,'.')
from common import *
from seon_clm import Engine
eng=Engine(checkpoint=os.path.expanduser('~/.cache/clm/CLM_v0.1-8B.safetensors'))
forms=parse(); y=np.array([f['cls'] for f in forms])
t=time.perf_counter(); A,_,_=eng.project('action',[f['src'][:600] for f in forms]); ta=time.perf_counter()-t
S,_,_=eng.project('state',[state(forms,i,4) for i in range(len(forms))])
print('action embed total s %.1f (%.0f ms/form, unique %d)'%(ta,ta/len(forms)*1000,len(set(f['src'] for f in forms))))
def kmeans(X,k,seed=0,it=50):
    r=np.random.RandomState(seed); C=X[r.choice(len(X),k,replace=False)]
    for _ in range(it):
        a=(X@C.T).argmax(1)
        for j in range(k):
            if (a==j).any(): C[j]=X[a==j].mean(0); C[j]/=np.linalg.norm(C[j])
    return a
def report(name,X):
    # leave-one-out 1-NN and 5-NN class agreement
    sim=X@X.T; np.fill_diagonal(sim,-9)
    nn1=y[sim.argmax(1)]; acc1=(nn1==y).mean()
    knn=[collections.Counter(y[np.argsort(-sim[i])[:5]]).most_common(1)[0][0] for i in range(len(y))]
    print(name,'LOO 1-NN class acc %.3f 5-NN %.3f (majority %.3f)'%(acc1,(np.array(knn)==y).mean(),collections.Counter(y).most_common(1)[0][1]/len(y)))
    for k in (8,12):
        a=kmeans(X.copy(),k)
        pur=sum(collections.Counter(y[a==j]).most_common(1)[0][1] for j in range(k) if (a==j).any())/len(y)
        print('  k=%d purity %.3f'%(k,pur))
        if k==8:
            for j in range(k):
                m=np.where(a==j)[0]
                if len(m)==0: continue
                print('   cluster',j,'n',len(m),dict(collections.Counter(y[m]).most_common(3)),'| e.g.',[forms[i]['src'][:55] for i in m[:3]])
report('ACTION',A); report('STATE(k=4)',S)
