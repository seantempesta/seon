# One calibration for both states: the plan-focused pages (judge-out*.json, chances shown on the
# merge-change scale c) and the trajectory moments (traj-judge-out-*.json, chances shown on the judged
# scale p, mapped back to c through the shipped sigmoid p = s(a ln c + b)). Per kind: sigmoid over
# ln c (and, to test whether the states need their own, ln c plus a state term), cross-fitted by
# halves of the moments; the result printed as knots over the shipped logit grid.
import json, glob, math, numpy as np, warnings; warnings.filterwarnings('ignore')
from sklearn.linear_model import LogisticRegression
PL = {'function': (1.317, 4.141), 'section': (1.856, 2.885), 'schema': (1.582, 3.738)}
rows = []
for f in sorted(glob.glob('judge-out*.json')):
    for t in json.load(open(f)):
        for it in t['items']: rows.append((t['task'], 0, it['kind'], max(it['pct'], 0.5) / 100, it['label'] == 'useful'))
for f in sorted(glob.glob('traj-judge-out-*.json')):
    for t in json.load(open(f)):
        for it in t['items']:
            a, b = PL[it['kind']]; p = min(max(it['pct'] / 100, 0.005), 0.995)
            c = math.exp((math.log(p / (1 - p)) - b) / a)
            rows.append((t['task'], 1, it['kind'], c, it['label'] == 'useful'))
works = sorted({r[0] for r in rows})
KN = {'function': [-6.51, -3.83, -2.78, -1.12, -0.07, 1.32, 3.81], 'section': [-6.16, -3.80, -2.81, -2.45, -1.92, -1.19, -0.69, -0.40, 4.65], 'schema': [-8.67, -4.49, -3.79, -2.93, -1.84, -1.52, 0.29, 1.00]}
KY = {'function': [0.0, 0.011, 0.026, 0.056, 0.115, 0.333, 0.433], 'section': [0.0, 0.012, 0.023, 0.056, 0.138, 0.245, 0.333, 0.538, 1.0], 'schema': [0.0, 0.013, 0.027, 0.067, 0.19, 0.303, 0.5, 0.5]}
for k in KN:
    rs = [r for r in rows if r[2] == k]
    X = np.array([[math.log(r[3])] for r in rs]); S = np.array([r[1] for r in rs]); Y = np.array([r[4] for r in rs])
    W = np.array([works.index(r[0]) % 2 for r in rs])
    for label, Xf in (('one table', X), ('state term', np.hstack([X, S[:, None]]))):
        P = np.zeros(len(Y))
        for h in (0, 1):
            m = LogisticRegression().fit(Xf[W == h], Y[W == h]); P[W != h] = m.predict_proba(Xf[W != h])[:, 1]
        line = []
        for st, nm in ((0, 'plan'), (1, 'work')):
            for lo, hi in ((0.6, 1.01), (0.4, 0.6), (0, 0.4)):
                sel = (S == st) & (P >= lo) & (P < hi)
                if sel.any(): line.append(f'{nm}[{lo},{hi}) {sel.sum()}: {P[sel].mean():.2f}->{Y[sel].mean():.2f}')
        print(f'{k:8s} {label:10s} ' + '  '.join(line))
    m = LogisticRegression().fit(X, Y)
    xs = sorted(set(KN[k]) | {(a + b) / 2 for a, b in zip(KN[k], KN[k][1:])})
    knots = [[round(x, 2), round(float(m.predict_proba([[math.log(max(float(np.interp(x, KN[k], KY[k])), 0.005))]])[0, 1]), 3)] for x in xs]
    print(f'   :{k} [' + ' '.join(f'[{x} {y}]' for x, y in knots) + ']')
    print(f'   work moments: useful {Y[S == 1].mean():.2f} of {int((S == 1).sum())}; plan pages: {Y[S == 0].mean():.2f} of {int((S == 0).sum())}')
