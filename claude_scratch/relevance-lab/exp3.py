# Experiment 3: one relevance score for (context, item). The context is a task's plan vector, the
# vars it cites and (warm) items it already touched. Features per item, each cheap:
#   zc   cosine to the context's query vector, z-scored per kind against a fixed reference sample
#        (256 rows a kind: no scan of the corpus to normalize)
#   zb   BM25 of the plan's identifier tokens, z-scored the same way
#   zt   cosine to the touched items' centroid (warm only), z-scored
#   g1   weighted 1-hop links to the seeds (rarity: 1/(1+degree)), g2 the 2-hop sum
#   ppr  personalized PageRank from the seeds (log)
# A logistic model per kind maps them to P(relevant): the relevance %. Compared against z alone
# (isotonic) and rrf; trained on older merges, tested on newer (and on q20/t31).
import sys
from sklearn.linear_model import LogisticRegression
from sklearn.isotonic import IsotonicRegression
from exp1 import *
import exp1
VT = None
def use_vectors(X):
    global VT, Vd
    VT = X; Vd.update({d: prefix(X, d) for d in (64, 128, 256, 512, 768)})
use_vectors(V)
KINDS = ('form', 'section', 'schema')
rng = np.random.default_rng(1)
ref = {k: rng.choice(np.where((kind == k) & ~is_test)[0], 256, replace=False) for k in KINDS}
def zk(s, k):  # z against the kind's reference sample
    x = s[ref[k]]; return (s - x.mean()) / (x.std() + 1e-9)
Arow = sp.diags(1 / np.maximum(deg, 1)) @ A
rar = 1 / (1 + deg)
def ppr(seeds, alpha=0.15, it=20):
    p0 = np.zeros(N); p0[list(seeds)] = 1 / len(seeds); p = p0.copy()
    for _ in range(it): p = alpha * p0 + (1 - alpha) * (Arow.T @ p)
    return p
def features(t, touched=()):
    q = t['qv']; c = VT @ q
    seeds = set(t['cited']) | set(touched)
    b = bm25(t['q'])
    if touched:
        cen = VT[list(touched)].mean(0); cen /= np.linalg.norm(cen); ct = VT @ cen
    else: ct = None
    if seeds:
        s = np.zeros(N); s[list(seeds)] = 1
        g1 = A @ s                                          # links to seeds
        g2 = A @ (rar * (A @ s))                            # 2 hops, through rare middles
        pr = ppr(seeds)
    else:
        g1 = g2 = pr = np.zeros(N)
    F = {}
    for k in KINDS:
        F[k] = np.stack([zk(c, k), zk(b, k), zk(ct, k) if ct is not None else np.zeros(N),
                         np.log1p(g1), np.log1p(g2) / 4, np.log10(pr + 1e-7) + 7, np.log1p(deg) / 3], 1)
    return F, c
FEATS = ['zc', 'zb', 'zt', 'g1', 'g2', 'ppr', 'deg']
def candidates(t, k, touched=()):
    m = (kind == k) & ~is_test
    m[list(t['cited'])] = False; m[list(touched)] = False
    return np.where(m)[0]
def dataset(ts, warm=0, seed=0):
    X = {k: [] for k in KINDS}; Y = {k: [] for k in KINDS}; G = {k: [] for k in KINDS}
    r = np.random.default_rng(seed)
    for qi, t in enumerate(ts):
        want = sorted(t['want'])
        touched = list(r.choice(want, min(warm, len(want) - 1), replace=False)) if warm and len(want) > 1 else []
        F, _ = features(t, touched)
        w = set(want) - set(touched)
        for k in KINDS:
            cs = candidates(t, k, touched)
            X[k].append(F[k][cs]); Y[k].append(np.isin(cs, list(w))); G[k].append(np.full(len(cs), qi))
    return {k: (np.concatenate(X[k]), np.concatenate(Y[k]), np.concatenate(G[k])) for k in KINDS}
def fit(D, cols):
    return {k: LogisticRegression(C=1.0, max_iter=2000, class_weight=None).fit(D[k][0][:, cols], D[k][1]) for k in KINDS}
def report(D, M, cols, label):
    print(f'\n{label}')
    for k in KINDS:
        X, Y, G = D[k]
        if Y.sum() == 0: continue
        p = M[k].predict_proba(X[:, cols])[:, 1]
        # ranking quality: R@5 per query
        r5 = [];
        for g in np.unique(G):
            m = G == g; yy = Y[m]
            if yy.sum() == 0: continue
            top = np.argsort(-p[m])[:5]; r5.append(yy[top].sum() / yy.sum())
        # calibration: expected calibration error over 10 bins of p
        bins = np.minimum((p * 10).astype(int), 9)
        ece = sum(abs(p[bins == b].mean() - Y[bins == b].mean()) * (bins == b).sum() for b in range(10) if (bins == b).any()) / len(p)
        cuts = []
        for c in (0.5, 0.3, 0.15, 0.05):
            keep = p >= c; nq = len(np.unique(G))
            cuts.append(f'{c:.2f}: kept {keep.sum() / nq:5.1f} P {Y[keep].mean() if keep.any() else 0:.2f} R {Y[keep].sum() / Y.sum():.2f}')
        print(f'  {k:8s} R@5 {np.mean(r5):.3f} ECE {ece:.4f} | ' + ' | '.join(cuts))
