# Candidate features for the judge's useful/noise, beside the shipped five: same namespace as a seed,
# the query's words in the item's namespace, cosine to the seeds' mean vector (z over the pool).
import json, glob, pickle, warnings, collections, numpy as np, sets, exp3, run11, run12
warnings.filterwarnings('ignore')
from exp3 import *
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold
run11.rows = lambda t, touched, eps, S=100: run12.rows_r(t, touched, eps, S, 'rare')
sets.PROMPT['v'] = sets.QPS
VT0 = exp3.VT
tall = sets.tall(); q20 = sets.q20()
by_plan = {t['row'].get('task'): t for t in tall}; by_q = {t['q']: t for t in q20}
by_commit = {t['q']: t for t in tall if t['row']['plan-at'] == 'commits'}
def ns_of(i):
    it = items[i]; n = it['name']
    if it['kind'] == 'section': return n.split('#')[0]
    return n.lstrip(':').split('/')[0]
KMAP = {'function': 'form', 'section': 'section', 'schema': 'schema'}
rows = collections.defaultdict(list)
cache = {}
for f in sorted(glob.glob('judge-out*.json')):
    for w in json.load(open(f)):
        tid = w['task']
        t = by_q.get(tid[7:]) if tid.startswith('query: ') else by_commit.get(tid[7:]) if tid.startswith('merge: ') else by_plan.get(tid)
        if t is None: continue
        if tid not in cache:
            feats = run11.rows(t, [], 1e-3)[0]
            seeds = list(t['cited'])
            sns = {ns_of(s) for s in seeds}
            cen = None
            if seeds:
                c = VT0[seeds].mean(0); cen = VT0 @ (c / np.linalg.norm(c))
            qt = set(toks(t['q']))
            cache[tid] = (feats, sns, cen, qt)
        feats, sns, cen, qt = cache[tid]
        for it in w['items']:
            k = KMAP[it['kind']]; Xk, pool = feats[k]; i = idx.get(it['name'])
            if i is None or i not in set(pool.tolist()): continue
            j = list(pool).index(i)
            ns = ns_of(i)
            zs = 0.0
            if cen is not None:
                v = cen[pool]; zs = (cen[i] - v.mean()) / (v.std() + 1e-9)
            extra = [float(ns in sns), len(set(toks(ns)) & qt) / max(1, len(set(toks(ns)))), zs]
            rows[k].append((tid, list(Xk[j][[0, 1, 2, 3, 5]]) + extra, it['label'] == 'useful'))
names = ['zc', 'zb', 'zt', 'g1', 'ppr', 'same-ns', 'ns-words', 'z-seeds']
for k in ('form', 'section', 'schema'):
    g = np.array([r[0] for r in rows[k]]); x = np.array([r[1] for r in rows[k]]); y = np.array([r[2] for r in rows[k]])
    for label, cols in (('shipped five', [0, 1, 3, 4]), ('+ same-ns', [0, 1, 3, 4, 5]), ('+ ns-words', [0, 1, 3, 4, 6]), ('+ z-seeds', [0, 1, 3, 4, 7]), ('all', [0, 1, 3, 4, 5, 6, 7])):
        p = np.zeros(len(y))
        for tr, te in GroupKFold(5).split(x, y, g):
            p[te] = LogisticRegression(max_iter=2000).fit(x[tr][:, cols], y[tr]).decision_function(x[te][:, cols])
        m = LogisticRegression(max_iter=2000).fit(x[:, cols], y)
        print(f'{k:8s} {label:13s} AUC {roc_auc_score(y, p):.3f}  ' + ' '.join(f'{names[c]}={w:.2f}' for c, w in zip(cols, m.coef_[0])) + f' b={m.intercept_[0]:.2f}')
pickle.dump(dict(rows), open('judged-rows.pkl', 'wb'))
