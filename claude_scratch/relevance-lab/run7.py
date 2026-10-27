# Cheap variants of the two text features, each judged by the cold scorer's R@5 and its
# calibration on held-out plans:
#   zc: exact per-kind z (a scan) | moments from the kind's mean vector and covariance (no scan:
#       q.mu and sqrt(q S q), S rank 32) | the 128-dim coarse scan's moments | raw cosine
#   zb: BM25 over whole texts (needs postings) | BM25 over names alone (identifier tokens of each
#       var name, section address+heading, schema key)
import pickle, warnings, sets, exp3; warnings.filterwarnings('ignore')
from exp3 import *
import run6
VT0 = exp3.VT
exp3.ppr = lambda seeds, **kw: run6.push_ppr(seeds, eps=1e-4)[0]
ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']; train = [t for t in ts if t['row']['plan-at'] == 'commits']
mom = {}
for k in KINDS:
    X = VT0[(kind == k) & ~is_test]; mu = X.mean(0); C = np.cov(X.T)
    w, U = np.linalg.eigh(C); U = U[:, -32:] * np.sqrt(w[-32:]); mom[k] = (mu, U, w[:-32].mean())
names = [it['name'] + ' ' + (it.get('heading') or '') for it in items]
BMn, _ = (lambda: None, None)
import lab
saved = dict(lab.vocab); lab.vocab.clear()
BMN, _ = lab.bm25_index(names); vocabN = dict(lab.vocab); lab.vocab.clear(); lab.vocab.update(saved)
def bm25n(q):
    qi = [vocabN[w] for w in set(toks(q)) if w in vocabN]
    return np.asarray(BMN[:, qi].sum(1)).ravel() if qi else np.zeros(N)
V128 = Vd[128]
variants = {
  'exact z, bm25 text': None,
  'moment z, bm25 text': ('mom', 'text'),
  'coarse z, bm25 text': ('coarse', 'text'),
  'raw cos, bm25 text': ('raw', 'text'),
  'moment z, bm25 names': ('mom', 'names'),
  'moment z, no lexical': ('mom', 'none'),
}
orig = exp3.features
for label, v in variants.items():
    def feats(t, touched=(), v=v):
        F, c = orig(t, touched)
        if v is None: return F, c
        zmode, lex = v; q = t['qv']; c128 = V128 @ prefix(q, 128)
        bn = bm25n(t['q'])
        for k in KINDS:
            if zmode == 'mom':
                mu, U, rest = mom[k]; sd = np.sqrt(((q @ U) ** 2).sum() + rest * (q @ q))
                F[k][:, 0] = (c - q @ mu) / sd
            elif zmode == 'coarse':
                x = c128[(kind == k) & ~is_test]; F[k][:, 0] = (c - x.mean()) / x.std()
            elif zmode == 'raw': F[k][:, 0] = c * 10
            if lex == 'names': F[k][:, 1] = zk(bn, k)
            elif lex == 'none': F[k][:, 1] = 0
        return F, c
    exp3.features = feats
    Da, Db = dataset(train, 0), dataset(test, 0, seed=5)
    cols = [0, 1, 3, 4, 5]; M = fit(Da, cols); report(Db, M, cols, label)
