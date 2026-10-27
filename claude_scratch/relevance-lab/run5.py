import pickle, warnings; warnings.filterwarnings('ignore')
from exp3 import *
from sklearn.ensemble import HistGradientBoostingClassifier
# FEATS = ['zc','zb','zt','g1','g2','ppr','deg']
for warm in (0, 3):
    Da, Db = pickle.load(open(f'D-warm{warm}.pkl', 'rb'))
    combos = [('zc+zb+g1', [0, 1, 3]), ('zc+zb+g1+g2', [0, 1, 3, 4]), ('zc+zb+ppr', [0, 1, 5]), ('zc+zb+g1+g2+deg', [0, 1, 3, 4, 6])]
    if warm: combos += [('zc+zb+zt+g1+g2+deg', [0, 1, 2, 3, 4, 6]), ('all', [0, 1, 2, 3, 4, 5, 6])]
    for name, cols in combos:
        M = fit(Da, cols); report(Db, M, cols, f'warm={warm} logistic {name}')
    cols = [0, 1, 2, 3, 4, 5, 6] if warm else [0, 1, 3, 4, 5, 6]
    G = {k: HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, max_leaf_nodes=15).fit(Da[k][0][:, cols], Da[k][1]) for k in KINDS}
    report(Db, G, cols, f'warm={warm} GBM all')
