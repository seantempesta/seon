import pickle, numpy as np, warnings; warnings.filterwarnings('ignore')
from sklearn.isotonic import IsotonicRegression
eps, cols, Ms, Dte = pickle.load(open('final.pkl', 'rb'))
F = ['zc', 'zb', 'zt', 'g1', 'g2', 'ppr']; names = [F[c] for c in cols]
KEY = {'form': ':function', 'section': ':section', 'schema': ':schema'}
w_out, c_out = [], []
for k, M in Ms.items():
    w = ' '.join(f':{n} {c:.3f}' for n, c in zip(names, M.coef_[0])) + f' :intercept {M.intercept_[0]:.3f}'
    w_out.append(f'{KEY[k]} {{{w}}}')
    lg = np.concatenate([M.decision_function(X[:, cols]) for w_ in (0, 3) for X, Y, nw, _ in Dte[w_][k]])
    y = np.concatenate([Y for w_ in (0, 3) for X, Y, nw, _ in Dte[w_][k]])
    iso = IsotonicRegression(out_of_bounds='clip', y_min=0, y_max=1).fit(lg, y)
    # knots: the logit at which the fitted precision first reaches each level
    xs, ys = iso.X_thresholds_, iso.y_thresholds_
    knots = []
    for lvl in (0.0, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.5):
        i = np.searchsorted(ys, lvl)
        if i < len(xs) and (not knots or xs[i] > knots[-1][0]): knots.append((xs[i], ys[i]))
    knots.append((xs[-1], ys[-1]))
    c_out.append(f'{KEY[k]} [' + ' '.join(f'[{x:.2f} {y:.3f}]' for x, y in knots) + ']')
    print(k, 'positives', int(y.sum()), 'rows', len(y))
print('{' + '\n '.join(w_out) + '}')
print('{' + '\n '.join(c_out) + '}')
