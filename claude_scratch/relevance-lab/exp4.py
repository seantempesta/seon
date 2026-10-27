# Experiment 4: (a) the query-side adapter trained on query-shaped pairs (commit subjects -> the
# items the merge changed; held-out plans untouched), its cosine as the score and as the scorer's
# feature; (b) a gradient-boosted scorer on the same features, for the headroom the logistic leaves.
import pickle, sys, sets, exp3, exp2
from exp3 import *
from sklearn.ensemble import HistGradientBoostingClassifier
VT0 = pickle.load(open('V-titled.pkl', 'rb')); exp3.use_vectors(VT0); sets.PROMPT['v'] = sets.QPS
ts = sets.tall()
train = [t for t in ts if t['row']['plan-at'] == 'commits']
test = [t for t in ts if t['row']['plan-at'] != 'commits']

def train_q(Q, D, r=32, tau=0.05, epochs=30, B=128, lr=2e-3, wd=1e-3, seed=0):
    # InfoNCE of query vectors Q[i] against doc rows D[i] (indices into VT0), in-batch negatives
    # plus the 16 nearest non-positive docs of each query
    rng = np.random.default_rng(seed); d = VT0.shape[1]
    C = rng.normal(0, 0.01, (r, d)).astype(np.float32); Bm = np.zeros((d, r), np.float32)
    m = [0 * C, 0 * Bm]; v2 = [0 * C, 0 * Bm]; step = 0
    pos = collections.defaultdict(set)
    for i, j in zip(map(bytes, Q), D): pos[i].add(j)
    order = np.arange(len(D))
    for ep in range(epochs):
        rng.shuffle(order); tot = 0; nb = 0
        for s in range(0, len(order) - B + 1, B):
            b = order[s:s+B]; q = Q[b]; dp = D[b]
            z = q + (q @ C.T) @ Bm.T; nz = np.linalg.norm(z, axis=1, keepdims=True); zn = z / nz
            sims = zn @ VT0.T; sims[:, is_test] = -9
            hn = []
            for j in range(B):
                top = np.argpartition(-sims[j], 30)[:30]
                hn += [x for x in top if x not in pos[bytes(q[j])]][:16]
            cand = list(dp) + hn; Dm = VT0[cand]
            lg = zn @ Dm.T / tau
            # a doc that is another positive of the same query is not a negative
            for j in range(B):
                for c in range(B):
                    if c != j and dp[c] in pos[bytes(q[j])]: lg[j, c] = -1e9
            lg -= lg.max(1, keepdims=True); p = np.exp(lg); p /= p.sum(1, keepdims=True)
            tot += -np.log(p[np.arange(B), np.arange(B)] + 1e-9).mean(); nb += 1
            g = p; g[np.arange(B), np.arange(B)] -= 1; g /= (B * tau)
            gzn = g @ Dm; gz = (gzn - zn * (gzn * zn).sum(1, keepdims=True)) / nz
            h = q @ C.T; gB = gz.T @ h + wd * Bm; gC = (gz @ Bm).T @ q + wd * C
            step += 1
            for k, (w, gw) in enumerate(((C, gC), (Bm, gB))):
                m[k] = .9 * m[k] + .1 * gw; v2[k] = .999 * v2[k] + .001 * gw * gw
                w -= lr * (m[k] / (1 - .9 ** step)) / (np.sqrt(v2[k] / (1 - .999 ** step)) + 1e-8)
        if ep % 5 == 4: print(f'  epoch {ep} loss {tot / nb:.3f}', file=sys.stderr)
    return C, Bm

if __name__ == '__main__':
    Q, D = [], []
    for t in train:
        for w in t['want']:
            if not is_test[w]: Q.append(t['qv']); D.append(w)
    # plans of tasks in the store -> the vars and sections they cite (none of the held-out tasks)
    held = {t['row']['task'] for t in test}
    for a, b, ty in E:
        if ty == 'task' and items[a]['name'] not in held and not is_test[b]: Q.append(VT0[a]); D.append(b)
    Q = np.array(Q, np.float32); D = np.array(D); print('pairs', len(D), file=sys.stderr)
    import exp1
    exp1.pool = ~is_test & (kind != 'task')
    results = {}
    for r, ep in ((32, 20), (64, 20)):
        C, Bm = train_q(Q, D, r=r, epochs=ep)
        pickle.dump((C, Bm), open(f'adapterq-r{r}.pkl', 'wb'))
        base = exp1.rankers
        def rk(t):
            o = base(t); qa = exp2.adapt(t['qv'], C, Bm)
            o['adapted768'] = VT0 @ qa; o['adapted256'] = Vd[256] @ prefix(qa, 256)
            o['rrf(bm25,adapted)'] = exp1.rrf(o['bm25'], o['adapted768'])
            return {k: o[k] for k in ('cos768', 'adapted768', 'adapted256', 'bm25', 'rrf(bm25,cos)', 'rrf(bm25,adapted)')}
        exp1.rankers = rk
        exp1.evaluate_kinds(test, f'r{r} plans'); exp1.evaluate_kinds(sets.q20(), f'r{r} q20', ('form', 'section'))
        exp1.rankers = base
