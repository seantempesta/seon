# Replay of recorded agent runs (the standing store, read only): at each turn t, the page's other
# context ranked by the shipped relevance (five features, shipped weights, judged calibration) with
# the query varied, scored against what the agent named in its code at turns t+1..t+3 and had not
# named before (or seen cited by its plan). One list across kinds, ordered by chance.
import pickle, re, math, collections, numpy as np, warnings; warnings.filterwarnings('ignore')
import exp3, run6, run8
from exp3 import *
VT0 = exp3.VT; V128 = Vd[128]
VR = pickle.load(open('V-rendered.pkl', 'rb'))
MODE = {'v': 'data'}
runs = pickle.load(open('replay-runs.pkl', 'rb')); QV = pickle.load(open('replay-vecs.pkl', 'rb'))
pv = {r['id']: QV[i] for i, r in enumerate(runs)}
k = len(runs); tv = {}
for r in runs:
    for t in r['turns']: tv[(r['id'], t['id'])] = QV[k]; k += 1
NAME = re.compile(r'(?:docs/[\w\-]+\.md#[\w\-]+|:[a-z][\w.\-]*/[\w.\-?!*]+|[a-z][\w.\-]*/[\w\-?!*<>=+]+)')
# the agent's own API and workflow (its tools, its turn, its handles), never what a page surfaces
TOOL = re.compile(r'^(my\.|seon\.turn/|seon\.agent/(db|state)$|seon\.task/task$|seon\.config/value$|:seon\.(error|task|turn|run)/)')
def named(text): return {idx[m] for m in NAME.findall(text) if m in idx and not TOOL.match(m) and kind[idx[m]] != 'task'}
KN = {'form': [[-6.51, 0.055], [-5.17, 0.062], [-3.83, 0.142], [-3.3, 0.247], [-2.78, 0.34], [-1.95, 0.484], [-1.12, 0.586], [-0.6, 0.712], [-0.07, 0.785], [0.62, 0.898], [1.32, 0.937], [2.56, 0.947], [3.81, 0.954]],
      'section': [[-6.16, 0.001], [-4.98, 0.001], [-3.8, 0.005], [-3.3, 0.01], [-2.81, 0.016], [-2.63, 0.043], [-2.45, 0.078], [-2.19, 0.191], [-1.92, 0.312], [-1.55, 0.454], [-1.19, 0.568], [-0.94, 0.641], [-0.69, 0.699], [-0.54, 0.793], [-0.4, 0.85], [2.12, 0.917], [4.65, 0.947]],
      'schema': [[-8.67, 0.01], [-6.58, 0.014], [-4.49, 0.042], [-4.14, 0.08], [-3.79, 0.122], [-3.36, 0.25], [-2.93, 0.369], [-2.39, 0.621], [-1.84, 0.752], [-1.68, 0.821], [-1.52, 0.864], [-0.61, 0.908], [0.29, 0.934], [0.65, 0.934], [1.0, 0.934]]}
W = {'form': [0.382, 0.568, 0.267, 1.527, 0.112, -3.881], 'section': [0.162, 0.816, 0.101, 0.690, 0.255, -4.100], 'schema': [0.517, 0.318, 0.332, 0.382, 0.435, -4.991]}
indeg = np.asarray((A > 0).sum(0)).ravel(); rw = 1 / (1 + np.minimum(indeg, 16))
def chances(qv, text, seeds, touched):
    q = qv / np.linalg.norm(qv)
    cd, cr = VT0 @ q, VR @ q
    c = {'data': cd, 'rendered': cr, 'both': (cd - cd.mean()) / cd.std() + (cr - cr.mean()) / cr.std()}[MODE['v']]
    c128 = c
    s = np.zeros(N); s[list(seeds)] = 1 if seeds else 0
    g1 = A @ s if seeds else np.zeros(N); pr = run6.push_ppr(list(seeds), eps=1e-3)[0] if seeds else np.zeros(N)
    ct = None
    if touched:
        cen = VT0[list(touched)].mean(0); ct = VT0 @ (cen / np.linalg.norm(cen))
    out = {}
    for kd in ('form', 'section', 'schema'):
        m = (kind == kd) & ~is_test if kd == 'form' else (kind == kd)
        ik = np.where(m)[0]
        pool = np.union1d(ik[np.argsort(-c128[ik])[:100]], ik[(pr[ik] > 0) | (g1[ik] > 0)])
        pool = np.setdiff1d(pool, list(touched))
        if not len(pool): continue
        z = lambda v: (v - v[pool].mean()) / (v[pool].std() + 1e-9)
        b = run8.local_bm25(text, pool)
        f = [z(c)[pool], z(b)[pool], (z(ct)[pool] if ct is not None else np.zeros(len(pool))), np.log1p(g1[pool] * rw[pool] * 17), np.log1p(pr[pool] * 1e4)]
        w = W[kd]; l = sum(wi * fi for wi, fi in zip(w[:5], f)) + w[5]
        p = np.interp(l, [x for x, _ in KN[kd]], [y for _, y in KN[kd]])
        for i, pi in zip(pool, p): out[i] = pi
    return out
def tokens_of(i):
    it = items[i]; lead = (it.get('doc') or it.get('lead') or '').split('. ')[0]
    return max(8, (len(it['name']) + len(lead)) // 4)
def query(r, t, mix, h=1.0):
    ts = r['turns'][:t + 1]
    if mix == 'q1' or not ts: return pv[r['id']], r['plan']
    ev = [tv[(r['id'], x['id'])] for x in ts]
    codes = ' '.join(x['code'] for x in ts[-3:])
    if mix == 'q2': return ev[-1], ts[-1]['code']
    wsum = sum(0.5 ** ((len(ev) - 1 - j) / h) * v for j, v in enumerate(ev)); wsum = wsum / np.linalg.norm(wsum)
    if mix == 'q3': return wsum, codes
    p = pv[r['id']]; return (p / np.linalg.norm(p) + wsum), r['plan'] + ' ' + codes
import sys
MODE['v'] = sys.argv[1]
CONFIGS = [('q1 plan ' + MODE['v'], 'q1', 1, False), ('q4 plan + q3 (hl 1) ' + MODE['v'], 'q4', 1, False)]
results = {}
for label, mix, h, warm in CONFIGS:
    rec5 = []; rec10 = []; p6 = [0, 0]; budget = collections.defaultdict(list); oos = [0, 0]; shown_by_turn = []
    for r in runs:
        cited = named(r['plan'])
        seen = set(cited)
        hist = []
        for t in range(len(r['turns'])):
            seen |= named(r['turns'][t]['code'])
            nxt = set().union(*[named(x['code']) for x in r['turns'][t + 1:t + 4]]) if t + 1 < len(r['turns']) else set()
            want_all = nxt - seen
            oos[0] += sum(1 for w in want_all if is_test[w] or kind[w] == 'task'); oos[1] += len(want_all)
            want = {w for w in want_all if not is_test[w] and kind[w] != 'task'}
            qv, text = query(r, t, mix, h)
            touched = (seen - cited) if warm else set()
            ch = chances(qv, text, cited | touched, touched)
            ranked = [i for i, _ in sorted(ch.items(), key=lambda kv: -kv[1]) if i not in seen]
            hist.append({i: ch[i] for i in ranked})
            if not want: continue
            rec5.append(len(set(ranked[:5]) & want) / len(want)); rec10.append(len(set(ranked[:10]) & want) / len(want))
            hi = [i for i in ranked if ch[i] >= 0.6]; p6[0] += len(set(hi) & want); p6[1] += len(hi)
            shown_by_turn.append(len(hi))
            tok = 0; got = set()
            for i in ranked:
                tok += tokens_of(i); got.add(i)
                for B in (100, 200, 400, 800, 1600, 3200):
                    if tok <= B: pass
            for B in (100, 200, 400, 800, 1600, 3200):
                acc = 0; g = set()
                for i in ranked:
                    acc += tokens_of(i)
                    if acc > B: break
                    g.add(i)
                budget[B].append(len(g & want) / len(want))
        results[(label, r['id'])] = hist
    print(f'{label:26s} turns {len(rec5):3d}  R@5 {np.mean(rec5):.3f}  R@10 {np.mean(rec10):.3f}  P@0.6 {p6[0] / max(p6[1], 1):.2f} ({np.mean(shown_by_turn):.1f}/turn)  ' +
          ' '.join(f'{B}t:{np.mean(v):.2f}' for B, v in budget.items()) + f'  | labels out of scope (tests, tasks) {oos[0]}/{oos[1]}')
pickle.dump(results, open('replay-results-' + MODE['v'] + '.pkl', 'wb'))
