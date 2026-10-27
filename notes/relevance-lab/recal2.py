# Platt recalibration per kind: P(useful) = sigmoid(a log(chance) + b), cross-fitted by task halves;
# then fitted on all and written as knots over the shipped logit grid.
import json, glob, numpy as np, warnings; warnings.filterwarnings('ignore')
from sklearn.linear_model import LogisticRegression
exec(open('recal.py').read().split("def pct")[0].split("items = []")[0])
items = []
for f in sorted(glob.glob('judge-out*.json')):
    for t in json.load(open(f)):
        for it in t['items']: items.append((t['task'], t['task'].startswith('query: '), it['kind'], max(it['pct'], 0.5) / 100.0, it['label'] == 'useful'))
KN = {'function': [[-6.51, 0.0], [-3.83, 0.011], [-2.78, 0.026], [-1.12, 0.056], [-0.07, 0.115], [1.32, 0.333], [3.81, 0.433]],
      'section': [[-6.16, 0.0], [-3.80, 0.012], [-2.81, 0.023], [-2.45, 0.056], [-1.92, 0.138], [-1.19, 0.245], [-0.69, 0.333], [-0.40, 0.538], [4.65, 1.0]],
      'schema': [[-8.67, 0.0], [-4.49, 0.013], [-3.79, 0.027], [-2.93, 0.067], [-1.84, 0.19], [-1.52, 0.303], [0.29, 0.5], [1.00, 0.5]]}
tasks = sorted({i[0] for i in items})
out = {}
for k in KN:
    rows = [r for r in items if r[2] == k]
    X = np.log([[r[3]] for r in rows]); Y = np.array([r[4] for r in rows]); T = np.array([r[0] for r in rows])
    P = np.zeros(len(rows))
    for h in (0, 1):
        tr = np.array([tasks.index(t) % 2 == h for t in T])
        m = LogisticRegression().fit(X[tr], Y[tr]); P[~tr] = m.predict_proba(X[~tr])[:, 1]
    bins = [(0.6, 1.01), (0.45, 0.6), (0.3, 0.45), (0, 0.3)]
    print(f'{k:8s} n={len(rows)} cross-fitted: ' + ' '.join(f'[{a},{b}) {((P >= a) & (P < b)).sum()}: {P[(P >= a) & (P < b)].mean() if ((P >= a) & (P < b)).any() else 0:.2f}->{Y[(P >= a) & (P < b)].mean() if ((P >= a) & (P < b)).any() else 0:.2f}' for a, b in bins))
    print('   cuts (cross-fitted):', ' '.join(f'>={c}: {(P >= c).sum()} P {Y[P >= c].mean() if (P >= c).any() else 0:.2f}' for c in (0.3, 0.4, 0.5, 0.6)))
    m = LogisticRegression().fit(X, Y)
    xs = sorted({x for x, _ in KN[k]} | {(a + b) / 2 for (a, _), (b, _) in zip(KN[k], KN[k][1:])})
    pc = lambda l: max(float(np.interp(l, [x for x, _ in KN[k]], [y for _, y in KN[k]])), 0.005)
    out[k] = [[round(x, 2), round(float(m.predict_proba(np.log([[pc(x)]]))[0, 1]), 3)] for x in xs]
    print('   a b', np.round(m.coef_[0], 3), np.round(m.intercept_, 3))
print('{' + '\n '.join(f':{k} [' + ' '.join(f'[{x} {y}]' for x, y in v) + ']' for k, v in out.items()) + '}')
