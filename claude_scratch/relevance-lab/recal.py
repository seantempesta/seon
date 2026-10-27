# Recalibrate relevance to what a judge found useful: per kind, an isotonic map from the shipped chance
# (calibrated to "the merge changed it") to P(useful), fitted on half the tasks and judged on the other
# half, then refitted on all and composed with the shipped knots: logit -> P(useful).
import json, glob, sys, collections, numpy as np
from sklearn.isotonic import IsotonicRegression
items = []
for f in sorted(glob.glob('judge-out*.json')):
    for t in json.load(open(f)):
        q = t['task'].startswith('query: ')
        for it in t['items']:
            items.append((t['task'], q, it['kind'], it['pct'] / 100.0, it['label'] == 'useful'))
print(len(items), 'labels from', len({i[0] for i in items}), 'tasks and queries')
tasks = sorted({i[0] for i in items}); half = set(tasks[::2])
KN = {'function': [[-6.51, 0.0], [-3.83, 0.011], [-2.78, 0.026], [-1.12, 0.056], [-0.07, 0.115], [1.32, 0.333], [3.81, 0.433]],
      'section': [[-6.16, 0.0], [-3.80, 0.012], [-2.81, 0.023], [-2.45, 0.056], [-1.92, 0.138], [-1.19, 0.245], [-0.69, 0.333], [-0.40, 0.538], [4.65, 1.0]],
      'schema': [[-8.67, 0.0], [-4.49, 0.013], [-3.79, 0.027], [-2.93, 0.067], [-1.84, 0.19], [-1.52, 0.303], [0.29, 0.5], [1.00, 0.5]]}
def pct(knots, l): return float(np.interp(l, [x for x, _ in knots], [y for _, y in knots]))
new = {}
for k in ('function', 'section', 'schema'):
    rows = [(t, q, p, u) for t, q, kk, p, u in items if kk == k]
    for scope, sel in (('tasks', lambda r: not r[1]), ('all', lambda r: True)):
        rs = [r for r in rows if sel(r)]
        fit = [r for r in rs if r[0] in half]; test = [r for r in rs if r[0] not in half]
        iso = IsotonicRegression(out_of_bounds='clip', y_min=0, y_max=1).fit([r[2] for r in fit], [r[3] for r in fit])
        p = iso.predict([r[2] for r in test]); y = np.array([r[3] for r in test])
        bins = [(0.5, 1.01), (0.3, 0.5), (0.15, 0.3), (0, 0.15)]
        print(f'{k:8s} {scope:5s} n={len(rs):3d} useful {np.mean([r[3] for r in rs]):.2f} | held-out half: ' +
              ' '.join(f'[{a},{b}) {((p >= a) & (p < b)).sum()}: {p[(p >= a) & (p < b)].mean() if ((p >= a) & (p < b)).any() else 0:.2f}->{y[(p >= a) & (p < b)].mean() if ((p >= a) & (p < b)).any() else 0:.2f}' for a, b in bins))
    iso = IsotonicRegression(out_of_bounds='clip', y_min=0, y_max=1).fit([r[2] for r in rows], [r[3] for r in rows])
    xs = sorted({x for x, _ in KN[k]} | {(a + b) / 2 for (a, _), (b, _) in zip(KN[k], KN[k][1:])})
    new[k] = [[round(x, 2), round(float(iso.predict([pct(KN[k], x)])[0]), 3)] for x in xs]
    # precision and kept at candidate cuts, on the useful scale
    pu = iso.predict([r[2] for r in rows]); uu = np.array([r[3] for r in rows])
    print('   cuts:', ' '.join(f'>={c}: {(pu >= c).sum()} P {uu[pu >= c].mean() if (pu >= c).any() else 0:.2f}' for c in (0.2, 0.3, 0.4, 0.5, 0.6)))
print('{' + '\n '.join(f':{k} [' + ' '.join(f'[{x} {y}]' for x, y in v) + ']' for k, v in new.items()) + '}')
