# Learn to rank on what the judge called useful: for each judged (work, item) pair, the features the
# shipped scorer computes (pool z of cosine, pool BM25, links by rarity, push PageRank), compared:
# the shipped logit (fitted to what merges changed) against a logistic refitted on the judge's
# labels, cross-validated by work items (grouped 5-fold). AUC per kind; then the refit's weights.
import json, glob, pickle, warnings, collections, numpy as np, sets, exp3, run11, run12
warnings.filterwarnings('ignore')
from exp3 import *
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold
run11.rows = lambda t, touched, eps, S=100: run12.rows_r(t, touched, eps, S, 'rare')
sets.PROMPT['v'] = sets.QPS
tall = sets.tall(); q20 = sets.q20()
by_plan = {t['row'].get('task'): t for t in tall}
by_q = {t['q']: t for t in q20}
by_commit = {t['q']: t for t in tall if t['row']['plan-at'] == 'commits'}
labels = []
for f in sorted(glob.glob('judge-out*.json')):
    for w in json.load(open(f)):
        tid = w['task']
        t = by_q.get(tid[7:]) if tid.startswith('query: ') else by_commit.get(tid[7:]) if tid.startswith('merge: ') else by_plan.get(tid)
        if t is None: continue
        for it in w['items']:
            labels.append((tid, t, it['kind'], it['name'], it['label'] == 'useful'))
print('labels joined', len(labels), 'of works', len({l[0] for l in labels}))
KMAP = {'function': 'form', 'section': 'section', 'schema': 'schema'}
W = {'form': [0.382, 0.568, 0.267, 1.527, 0.112, -3.881], 'section': [0.162, 0.816, 0.101, 0.690, 0.255, -4.100], 'schema': [0.517, 0.318, 0.332, 0.382, 0.435, -4.991]}
cache = {}
X = collections.defaultdict(list); Y = collections.defaultdict(list); G = collections.defaultdict(list); L0 = collections.defaultdict(list)
for tid, t, kind_, name, u in labels:
    k = KMAP[kind_]
    if tid not in cache: cache[tid] = run11.rows(t, [], 1e-3)[0]
    Xk, pool = cache[tid][k]
    i = idx.get(name if not name.startswith(':') else name)
    if i is None or i not in set(pool.tolist()): continue
    f = Xk[list(pool).index(i)][[0, 1, 2, 3, 5]]
    X[k].append(f); Y[k].append(u); G[k].append(tid)
    L0[k].append(float(np.dot(W[k][:5], f) + W[k][5]))
for k in ('form', 'section', 'schema'):
    x = np.array(X[k]); y = np.array(Y[k]); g = np.array(G[k]); l0 = np.array(L0[k])
    p = np.zeros(len(y))
    for tr, te in GroupKFold(5).split(x, y, g):
        m = LogisticRegression(max_iter=2000).fit(x[tr], y[tr]); p[te] = m.decision_function(x[te])
    m = LogisticRegression(max_iter=2000).fit(x, y)
    print(f'{k:8s} n={len(y)} useful {y.mean():.2f}  AUC shipped {roc_auc_score(y, l0):.3f}  refit (cross-validated) {roc_auc_score(y, p):.3f}  weights {np.round(m.coef_[0], 3)} b {m.intercept_[0]:.3f}')
pickle.dump((dict(X), dict(Y), dict(G), dict(L0)), open('judged-features.pkl', 'wb'))
