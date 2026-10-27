# The implemented feature set, exactly: per kind, pool = S nearest at the coarse level + the seeds'
# 1-hop neighbours + push-PPR support; zc = cos768 z-scored against the pool (option a) or the
# kind's coarse moments (option b); zb = BM25 local to the pool, z over the pool; g1, g2 hop counts;
# ppr = push PPR (eps 1e-4); zt = cos to the touched centroid, z over the pool. Mixed training
# (cold and warm rows), so one set of weights serves a context with or without touched items.
import pickle, warnings, sets, exp3, json; warnings.filterwarnings('ignore')
from exp3 import *
import run6, run8
from sklearn.linear_model import LogisticRegression
VT0 = exp3.VT; V128 = Vd[128]
ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']; train = [t for t in ts if t['row']['plan-at'] == 'commits']
F = ['zc', 'zb', 'zt', 'g1', 'g2', 'ppr']
def rows(t, touched, S, zmode):
    q = t['qv']; c = VT0 @ q; c128 = V128 @ prefix(q, 128)
    seeds = set(t['cited']) | set(touched)
    if seeds:
        s = np.zeros(N); s[list(seeds)] = 1; g1 = A @ s; g2 = A @ (rar * (A @ s)); pr = run6.push_ppr(list(seeds), eps=1e-4)[0]
    else: g1 = g2 = pr = np.zeros(N)
    ct = None
    if touched:
        cen = VT0[list(touched)].mean(0); cen /= np.linalg.norm(cen); ct = VT0 @ cen
    out = {}
    for k in KINDS:
        idxk = np.where((kind == k) & ~is_test)[0]
        near = idxk[np.argsort(-c128[idxk])[:S]]
        pool = np.union1d(near, idxk[(pr[idxk] > 0) | (g1[idxk] > 0)])
        pool = np.setdiff1d(pool, list(seeds))
        z = lambda v, ref: (v - ref.mean()) / (ref.std() + 1e-9)
        zc = z(c, c[pool]) if zmode == 'pool' else z(c, c128[idxk])
        b = run8.local_bm25(t['q'], pool); zb = z(b, b[pool])
        zt = z(ct, ct[pool]) if ct is not None else np.zeros(N)
        X = np.stack([zc, zb, zt, np.log1p(g1), np.log1p(g2), np.log1p(pr * 1e4)], 1)[pool]
        out[k] = (X, pool)
    return out
def data(ts, warms, S, zmode, seed=0):
    r = np.random.default_rng(seed); D = {k: ([], [], []) for k in KINDS}; qi = 0
    for t in ts:
        for warm in warms:
            want = sorted(t['want'])
            touched = list(r.choice(want, min(warm, len(want) - 1), replace=False)) if warm and len(want) > 1 else []
            w = set(want) - set(touched)
            for k, (X, pool) in rows(t, touched, S, zmode).items():
                wk = {x for x in w if kind[x] == k}
                D[k][0].append(X); D[k][1].append(np.isin(pool, list(w))); D[k][2].append(np.full(len(pool), qi))
                D[k][2][-1] = (D[k][2][-1], len(wk))
            qi += 1
    return D
def r5(D, M, k):
    xs = []
    for X, Y, (g, nw) in zip(*D[k]):
        if nw == 0: continue
        p = M.predict_proba(X)[:, 1]; xs.append(Y[np.argsort(-p)[:5]].sum() / nw)
    return np.mean(xs)
res = {}
for zmode in ('pool', 'coarse'):
    Dtr = data(train, (0, 3), 100, zmode)
    Dte = {w: data(test, (w,), 100, zmode, seed=5) for w in (0, 3)}
    for k in KINDS:
        X = np.concatenate(Dtr[k][0]); Y = np.concatenate(Dtr[k][1])
        M = LogisticRegression(max_iter=3000).fit(X, Y); res[(zmode, k)] = M
        print(f'{zmode:6s} {k:8s} cold R@5 {r5(Dte[0], M, k):.3f}  warm R@5 {r5(Dte[3], M, k):.3f}  coef ' + ' '.join(f'{f}={c:.2f}' for f, c in zip(F, M.coef_[0])) + f' b={M.intercept_[0]:.2f}')
    pickle.dump((Dtr, Dte), open(f'D9-{zmode}.pkl', 'wb'))
pickle.dump(res, open('M9.pkl', 'wb'))
