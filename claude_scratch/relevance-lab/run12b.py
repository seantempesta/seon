# g1 weighted by rarity: each link to a seed weighs 1/(1 + the forms naming the item, to a cap of 16)
import pickle, warnings, collections, sets, exp3; warnings.filterwarnings('ignore')
from exp3 import *
import run6, run8, run11
from sklearn.linear_model import LogisticRegression
indeg = np.asarray((A > 0).sum(0)).ravel()
rw = 1 / (1 + np.minimum(indeg, 16))
plumb = {"seon.contract/checked", "seon.contract/on?", "seon.contract/error?", "seon.config/value", "seon.agent/db", "seon.db/eid", "seon.contract/defn", "seon.contract/error"}
orig_rows = run11.rows
def rows_r(t, touched, eps, S=100, mode='rare'):
    o, pushes = orig_rows(t, touched, eps, S)
    seeds = set(t['cited']) | set(touched)
    s = np.zeros(N); s[list(seeds)] = 1; g1 = A @ s
    for k, (X, pool) in o.items():
        X[:, 3] = np.log1p(g1[pool] * (rw[pool] * 17 if mode == 'rare' else 1))
    return o, pushes
ALL = {}
for mode in ('rare',):
    run11.rows = lambda t, touched, eps, S=100, mode=mode: rows_r(t, touched, eps, S, mode)
    ts = sets.tall(); test = [t for t in ts if t['row']['plan-at'] != 'commits']; train = [t for t in ts if t['row']['plan-at'] == 'commits']
    Dtr, _ = run11.data(train, (0, 3), 1e-3); Dte = {w: run11.data(test, (w,), 1e-3, seed=5)[0] for w in (0, 3)}
    cols = [0, 1, 2, 3, 5]; line = []
    for k in KINDS:
        X = np.concatenate([d[0] for d in Dtr[k]])[:, cols]; Y = np.concatenate([d[1] for d in Dtr[k]])
        M = LogisticRegression(max_iter=3000).fit(X, Y); ALL[k] = M
        line.append(f'{k} {run11.r5(Dte[0][k], M, cols):.3f}/{run11.r5(Dte[3][k], M, cols):.3f}')
        if k == 'form':
            pl = sum(items[i]['name'] in plumb for w in (0, 3) for X_, Y_, nw, pool in Dte[w][k] for i in pool[np.argsort(-M.decision_function(X_[:, cols]))[:5]])
            line.append(f'plumbing lines {pl}/570')
            if mode == 'rare': pickle.dump((M, Dte, cols), open('rare.pkl', 'wb'))
        if mode == 'rare': print('   ', k, np.round(M.coef_[0], 3), round(M.intercept_[0], 3))
    print(mode, '  '.join(line))

pickle.dump((1e-3, cols, ALL, Dte), open('final.pkl', 'wb'))
