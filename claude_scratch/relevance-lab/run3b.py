import pickle, sets, exp3
from exp3 import *
VT0 = pickle.load(open('V-titled.pkl', 'rb')); exp3.use_vectors(VT0); sets.PROMPT['v'] = sets.QPS
ts = sets.tall()
train = [t for t in ts if t['row']['plan-at'] == 'commits']
test = [t for t in ts if t['row']['plan-at'] != 'commits']
print(len(train), len(test))
for warm in (0, 3):
    Da, Db = dataset(train, warm), dataset(test, warm, seed=5)
    pickle.dump((Da, Db), open(f'D-warm{warm}.pkl', 'wb'))
    for name, cols in [('zc', [0]), ('zc+zb', [0, 1]), ('text+graph', [0, 1, 3, 4, 5, 6]), ('all', [0, 1, 2, 3, 4, 5, 6])]:
        if warm == 0 and name == 'all': continue
        M = fit(Da, cols); report(Db, M, cols, f'warm={warm} {name} (train commits, test plans)')
        if name == 'all' or (warm == 0 and name == 'text+graph'):
            for k in KINDS: print('   coef', k, dict(zip([FEATS[c] for c in cols], np.round(M[k].coef_[0], 2))), round(M[k].intercept_[0], 2))
