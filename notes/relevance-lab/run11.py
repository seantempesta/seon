# The implementable feature set, final: pool z (no corpus scan), local BM25, g1 (links to the seeds),
# push PPR at eps; with and without g2; eps 1e-4 vs 1e-3. Fit on commit queries (cold and warm rows).
import pickle, warnings, sets, exp3, sys; warnings.filterwarnings('ignore')
from exp3 import *
import run6, run8
from sklearn.linear_model import LogisticRegression
from sklearn.isotonic import IsotonicRegression
VT0 = exp3.VT; V128 = Vd[128]
ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']; train = [t for t in ts if t['row']['plan-at'] == 'commits']
def rows(t, touched, eps, S=100):
    q = t['qv']; c = VT0 @ q; c128 = V128 @ prefix(q, 128)
    seeds = set(t['cited']) | set(touched)
    if seeds:
        s = np.zeros(N); s[list(seeds)] = 1; g1 = A @ s; g2 = A @ (rar * (A @ s)); pr, pushes = run6.push_ppr(list(seeds), eps=eps)
    else: g1 = g2 = pr = np.zeros(N); pushes = 0
    ct = None
    if touched:
        cen = VT0[list(touched)].mean(0); cen /= np.linalg.norm(cen); ct = VT0 @ cen
    out = {}
    for k in KINDS:
        idxk = np.where((kind == k) & ~is_test)[0]
        near = idxk[np.argsort(-c128[idxk])[:S]]
        pool = np.setdiff1d(np.union1d(near, idxk[(pr[idxk] > 0) | (g1[idxk] > 0)]), list(seeds))
        z = lambda v: (v - v[pool].mean()) / (v[pool].std() + 1e-9)
        b = run8.local_bm25(t['q'], pool)
        X = np.stack([z(c), z(b), z(ct) if ct is not None else np.zeros(N), np.log1p(g1), np.log1p(g2), np.log1p(pr * 1e4)], 1)[pool]
        out[k] = (X, pool)
    return out, pushes
def data(ts, warms, eps, seed=0):
    r = np.random.default_rng(seed); D = {k: [] for k in KINDS}; P = []
    for t in ts:
        for warm in warms:
            want = sorted(t['want'])
            touched = list(r.choice(want, min(warm, len(want) - 1), replace=False)) if warm and len(want) > 1 else []
            w = set(want) - set(touched)
            o, pushes = rows(t, touched, eps); P.append(pushes)
            for k, (X, pool) in o.items():
                D[k].append((X, np.isin(pool, list(w)), len({x for x in w if kind[x] == k}), pool))
    return D, np.mean(P)
def r5(D, M, cols):
    xs = [Y[np.argsort(-M.predict_proba(X[:, cols])[:, 1])[:5]].sum() / nw for X, Y, nw, _ in D if nw]
    return np.mean(xs)
F = ['zc', 'zb', 'zt', 'g1', 'g2', 'ppr']
best = None
for eps in (1e-3, 1e-4):
    Dtr, ptr = data(train, (0, 3), eps); Dte = {w: data(test, (w,), eps, seed=5)[0] for w in (0, 3)}
    print(f'eps {eps}: pushes/query {ptr:.0f}')
    for name, cols in (('with g2', [0, 1, 2, 3, 4, 5]), ('no g2', [0, 1, 2, 3, 5])):
        line = []
        Ms = {}
        for k in KINDS:
            X = np.concatenate([d[0] for d in Dtr[k]])[:, cols]; Y = np.concatenate([d[1] for d in Dtr[k]])
            M = LogisticRegression(max_iter=3000).fit(X, Y); Ms[k] = M
            line.append(f'{k} {r5(Dte[0][k], M, cols):.3f}/{r5(Dte[3][k], M, cols):.3f}')
        print(f'  {name:8s} cold/warm R@5: ' + '  '.join(line))
        if eps == 1e-3 and name == 'no g2': best = (eps, cols, Ms, Dte)
pickle.dump(best, open('final.pkl', 'wb'))
