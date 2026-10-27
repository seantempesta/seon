# Today's related (seon.context/related) on the same 57 held-out plans: rrf(k 10) of the call graph
# from the cited vars (1-hop, by rarity 1/(1+in-degree)) and stored-vector cosine with today's
# prompt; tasks' recall at 5 per kind, cold. Then the chosen scorer's reliability (Platt on plans,
# cross-fitted), and precision/recall/kept at the depth cutoffs.
import pickle, warnings, sets, exp3; warnings.filterwarnings('ignore')
from exp3 import *
from sklearn.linear_model import LogisticRegression
import lab
Vstored = lab.V; Vst = lab.Vd  # stored vectors, before exp3 swapped them? exp3.use_vectors replaced Vd
VT0 = exp3.VT
sets.PROMPT['v'] = sets.QP; te_old = [t for t in sets.tall() if t['row']['plan-at'] != 'commits']
sets.PROMPT['v'] = sets.QPS; te_new = [t for t in sets.tall() if t['row']['plan-at'] != 'commits']
old_by = {t['row']['task']: t for t in te_old}
def rrf_rank(*rs, k=10):
    s = collections.defaultdict(float)
    for r in rs:
        for i, x in enumerate(r): s[x] += 1 / (k + i + 1)
    return [x for x, _ in sorted(s.items(), key=lambda kv: -kv[1])]
indeg = np.asarray((A > 0).sum(0)).ravel()
out = collections.defaultdict(list)
for t in te_new:
    to = old_by[t['row']['task']]
    cs = Vstored @ to['qv']
    seeds = list(t['cited'])
    for k in KINDS:
        m = (kind == k) & ~is_test; m[seeds] = False
        wk = {w for w in t['want'] if kind[w] == k}
        if not wk: continue
        meaning = [i for i in np.argsort(-cs) if m[i]][:50]
        nb = collections.Counter()
        for s in seeds:
            for j in A[s].indices:
                if m[j]: nb[j] += 1 / (1 + indeg[j])
        graph = [x for x, _ in nb.most_common(50)]
        top = rrf_rank(graph, meaning)[:5]
        out[k].append(len(set(top) & wk) / len(wk))
for k in KINDS: print(f'today related {k:8s} cold R@5 {np.mean(out[k]):.3f} ({len(out[k])})')
# reliability of the chosen scorer (coarse z), Platt-recalibrated on plans, cross-fitted
M9 = pickle.load(open('M9.pkl', 'rb')); Dtr, Dte = pickle.load(open('D9-coarse.pkl', 'rb'))
for w in (0, 3):
    print(f'\nwarm={w}: bins of the recalibrated %, judged on the other half of the plans')
    for k in KINDS:
        M = M9[('coarse', k)]; Xs, Ys, Gs = Dte[w][k]
        lg = [M.decision_function(X) for X in Xs]; qs = np.arange(len(Xs))
        P = LogisticRegression().fit(np.concatenate([l for l, q in zip(lg, qs) if q % 2 == 0])[:, None], np.concatenate([y for y, q in zip(Ys, qs) if q % 2 == 0]))
        p = np.concatenate([P.predict_proba(l[:, None])[:, 1] for l, q in zip(lg, qs) if q % 2]); y = np.concatenate([yy for yy, q in zip(Ys, qs) if q % 2])
        nq = sum(1 for q in qs if q % 2); tot = sum(g[1] for g, q in zip(Gs, qs) if q % 2)
        line = []
        for lo in (0.3, 0.1, 0.03):
            m = p >= lo; line.append(f'>={lo:.2f}: {m.sum() / nq:4.1f}/q P {y[m].mean() if m.any() else 0:.2f} R {y[m].sum() / max(tot, 1):.2f}')
        bins = [(0.3, 1), (0.1, 0.3), (0.03, 0.1), (0, 0.03)]
        rel = ' '.join(f'[{a},{b}) {p[(p >= a) & (p < b)].mean() if ((p >= a) & (p < b)).any() else 0:.2f}->{y[(p >= a) & (p < b)].mean() if ((p >= a) & (p < b)).any() else 0:.2f}' for a, b in bins)
        print(f'  {k:8s} a={P.coef_[0][0]:.2f} b={P.intercept_[0]:.2f} | ' + ' | '.join(line) + ' | pred->obs ' + rel)
