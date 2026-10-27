# local-push PPR (Andersen, Chung, Lang 2006) against the full iteration; then Platt recalibration
# of the scorer's logit on plan-shaped pairs, cross-fitted (fit on one half of the plans, judged on the other)
import pickle, warnings, time, sets, exp3; warnings.filterwarnings('ignore')
from exp3 import *
VT0 = pickle.load(open('V-titled.pkl', 'rb')); exp3.use_vectors(VT0); sets.PROMPT['v'] = sets.QPS
Acsr = A.tocsr(); dg = np.maximum(deg, 1)
def push_ppr(seeds, alpha=0.15, eps=1e-4):
    p = collections.defaultdict(float); r = collections.defaultdict(float)
    for s in seeds: r[s] += 1 / len(seeds)
    queue = list(r); pushes = 0
    while queue:
        u = queue.pop()
        ru = r[u]
        if ru < eps * dg[u]: continue
        p[u] += alpha * ru; r[u] = 0; pushes += 1
        lo, hi = Acsr.indptr[u], Acsr.indptr[u + 1]
        share = (1 - alpha) * ru / dg[u]
        for v, w in zip(Acsr.indices[lo:hi], Acsr.data[lo:hi]):
            r[v] += share * w
            if r[v] >= eps * dg[v]: queue.append(v)
    out = np.zeros(N); 
    for k, v in p.items(): out[k] = v
    return out, pushes
ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']
for eps in (1e-3, 1e-4):
    agree = []; pushes = []
    for t in test[:30]:
        seeds = list(t['cited']) or []
        if not seeds: continue
        full = ppr(seeds); loc, n = push_ppr(seeds, eps=eps); pushes.append(n)
        top = set(np.argsort(-full)[:50]); agree.append(len(top & set(np.argsort(-loc)[:50])) / 50)
    print(f'eps {eps}: top-50 overlap with full PPR {np.mean(agree):.3f}, pushes/query {np.mean(pushes):.0f} (edges {A.nnz})')
# swap the full PPR feature for push PPR at eps 1e-4 and rerun the warm comparison
exp3.ppr = lambda seeds, **kw: push_ppr(seeds, eps=1e-4)[0]
train = [t for t in ts if t['row']['plan-at'] == 'commits']
Da, Db = dataset(train, 3), dataset(test, 3, seed=5)
cols = [0, 1, 2, 3, 4, 5, 6]
M = fit(Da, cols); report(Db, M, cols, 'warm=3 all, push PPR eps 1e-4')
Da0, Db0 = dataset(train, 0), dataset(test, 0, seed=5)
cols0 = [0, 1, 3, 4, 5]
M0 = fit(Da0, cols0); report(Db0, M0, cols0, 'cold zc+zb+g1+g2+ppr, push PPR')
pickle.dump((Da, Db, Da0, Db0), open('D-push.pkl', 'wb'))
# Platt recalibration on plans: logit -> a*logit+b, fit on even plan queries, judged on odd
from sklearn.linear_model import LogisticRegression
def recal(D, M, cols, label):
    print(f'\n{label}: recalibrated on plans (fit on even queries, judged on odd)')
    for k in KINDS:
        X, Y, G = D[k]
        lg = M[k].decision_function(X[:, cols])[:, None]
        fitm = G % 2 == 0; P = LogisticRegression().fit(lg[fitm], Y[fitm])
        p = P.predict_proba(lg[~fitm])[:, 1]; y = Y[~fitm]; nq = len(np.unique(G[~fitm]))
        rows = []
        for lo, hi in ((0.5, 1), (0.3, 0.5), (0.15, 0.3), (0.05, 0.15)):
            m = (p >= lo) & (p < hi)
            rows.append(f'[{lo:.2f},{hi:.2f}) n/q {m.sum() / nq:5.1f} pred {p[m].mean() if m.any() else 0:.2f} obs {y[m].mean() if m.any() else 0:.2f}')
        print(f'  {k:8s} a={P.coef_[0][0]:.2f} b={P.intercept_[0]:.2f} | ' + ' | '.join(rows))
recal(Db0, M0, cols0, 'cold'); recal(Db, M, cols, 'warm')
