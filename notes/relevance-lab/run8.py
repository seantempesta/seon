# The scorer restricted to a candidate pool per query and kind: the S nearest by cosine (near's
# shortlist) plus the push-PPR support of the seeds; BM25 computed on the pool's own texts, idf
# local to the pool (no postings index). z from the coarse 128-dim moments. Judged as before.
import pickle, warnings, sets, exp3; warnings.filterwarnings('ignore')
from exp3 import *
import run6, lab
VT0 = exp3.VT; V128 = Vd[128]
ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']; train = [t for t in ts if t['row']['plan-at'] == 'commits']
texts = [lab.item_text(it) for it in items]
tok = [collections.Counter(toks(x)) for x in texts]
def local_bm25(q, pool, k1=1.2, b=0.75):
    qt = set(toks(q)); n = len(pool)
    df = collections.Counter(w for i in pool for w in qt if w in tok[i])
    L = np.array([sum(tok[i].values()) for i in pool], float); avg = L.mean()
    out = np.zeros(N)
    for j, i in enumerate(pool):
        s = 0.0
        for w in qt:
            f = tok[i].get(w)
            if f: s += math.log(1 + (n - df[w] + .5) / (df[w] + .5)) * f * (k1 + 1) / (f + k1 * (1 - b + b * L[j] / avg))
        out[i] = s
    return out
def make(S, lexical='local'):
    def features(t, touched=()):
        q = t['qv']; c = VT0 @ q; c128 = V128 @ prefix(q, 128)
        seeds = set(t['cited']) | set(touched)
        if seeds:
            s = np.zeros(N); s[list(seeds)] = 1; g1 = A @ s; g2 = A @ (rar * (A @ s)); pr = run6.push_ppr(list(seeds), eps=1e-4)[0]
        else: g1 = g2 = pr = np.zeros(N)
        if touched:
            cen = VT0[list(touched)].mean(0); cen /= np.linalg.norm(cen); ct = VT0 @ cen
        F = {}; t['_pool'] = {}
        for k in KINDS:
            m = (kind == k) & ~is_test
            idxk = np.where(m)[0]
            near = idxk[np.argsort(-c128[idxk])[:S]]                     # coarse shortlist
            pool = np.union1d(near, idxk[(pr[idxk] > 0) | (g1[idxk] > 0)])
            t['_pool'][k] = set(pool.tolist())
            x = c128[idxk]; zc = (c - x.mean()) / x.std()
            b = local_bm25(t['q'], pool) if lexical == 'local' else bm25(t['q'])
            zb = (b - b[pool].mean()) / (b[pool].std() + 1e-9)
            zt = ((ct - ct[idxk].mean()) / ct[idxk].std()) if touched else np.zeros(N)
            F[k] = np.stack([zc, zb, zt, np.log1p(g1), np.log1p(g2) / 4, np.log10(pr + 1e-7) + 7, np.log1p(deg) / 3], 1)
        return F, c
    return features
orig_cand = exp3.candidates
for S, lex in [(100, 'local'), (200, 'local'), (400, 'local'), (200, 'global')]:
    exp3.features = make(S, lex)
    cur = {}
    def feats_wrap(t, touched=(), f=exp3.features):
        F, c = f(t, touched); cur['t'] = t; return F, c
    def cands(t, k, touched=()):
        cs = orig_cand(t, k, touched); pool = t['_pool'][k]
        return np.array([i for i in cs if i in pool], int)
    exp3.candidates = cands
    for warm in (0, 3):
        Da, Db = dataset(train, warm), dataset(test, warm, seed=5)
        cols = [0, 1, 2, 3, 4, 5] if warm else [0, 1, 3, 4, 5]
        M = fit(Da, cols)
        # R@5 must count wants outside the pool as misses: recompute against the full want
        print(f'\npool S={S} lexical={lex} warm={warm}')
        for k in KINDS:
            X, Y, G = Db[k]; p = M[k].predict_proba(X[:, cols])[:, 1]; r5 = []
            r = np.random.default_rng(5)
            for qi, t in enumerate(test):
                want = sorted(t['want']); touched = list(r.choice(want, min(warm, len(want) - 1), replace=False)) if warm and len(want) > 1 else []
                wk = {w for w in set(want) - set(touched) if kind[w] == k}
                if not wk: continue
                m = G == qi
                cs = cands(t, k, touched) if False else None
                top = np.argsort(-p[m])[:5]; r5.append(Y[m][top].sum() / len(wk))
            inpool = Y.sum()
            print(f'  {k:8s} R@5 {np.mean(r5):.3f}  pool/query {len(Y) / len(test):.0f}')
        pickle.dump(M, open(f'M-S{S}-{lex}-w{warm}.pkl', 'wb'))
