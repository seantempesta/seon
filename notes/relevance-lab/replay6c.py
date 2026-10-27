# Intents and situations as features of the one scorer, on the replay (10 runs, 99 turns), tests now
# candidates. At each turn: each kind's pool (as relevance makes it, tests' pool the 100 nearest tests
# and those linked to the seeds), features (A) the shipped five, (B) + the similarity to four
# instructed intent queries built from the latest eval, (C) + situations (threw, defined, red, first)
# and their products with the intents. A logistic per kind, trained leaving one run out (no run judges
# itself); every kind's chance in one list, taken in order within 800 tokens; recall of what the agent
# named in its next three turns, with a bootstrap interval over turns.
import pickle, re, collections, numpy as np, warnings; warnings.filterwarnings('ignore')
exec(open('replay4.py').read().split("CONFIGS =")[0].replace("MODE['v'] = sys.argv[1]", ""))
from sklearn.linear_model import LogisticRegression
MODE['v'] = 'data'
IV = pickle.load(open('intent-vecs.pkl', 'rb')); INT = ['error', 'similar', 'tests', 'docs']
KINDS4 = ('form', 'section', 'schema', 'test')
def kind4(i): return 'test' if (kind[i] == 'form' and is_test[i]) else kind[i]
def rows_for(r, t, cited, seen):
    qv, text = query(r, t, 'q5', 1.0); q = qv / np.linalg.norm(qv); c = VT0 @ q
    s = np.zeros(N); seeds = list(cited)
    if seeds: s[seeds] = 1
    g1 = A @ s if seeds else np.zeros(N); pr = run6.push_ppr(seeds, eps=1e-3)[0] if seeds else np.zeros(N)
    iv = {k: VT0 @ (IV[(r['id'], r['turns'][t]['id'], k)] / np.linalg.norm(IV[(r['id'], r['turns'][t]['id'], k)])) for k in INT}
    tt = r['turns'][t]
    printed = named(tt.get('out', '')) | (named(r['turns'][t - 1].get('out', '')) if t > 0 else set())
    sit = [float(tt.get('threw', False)), float(tt.get('defined', False)), float(tt.get('red', False)), float(t == 0)]
    out = {}
    for kd in KINDS4:
        m = ((kind == 'form') & ~is_test) if kd == 'form' else ((kind == 'form') & is_test) if kd == 'test' else (kind == kd)
        ik = np.where(m)[0]
        pool = np.union1d(ik[np.argsort(-c[ik])[:100]], ik[(pr[ik] > 0) | (g1[ik] > 0)])
        pool = np.array([i for i in pool if i not in seen])
        if not len(pool): continue
        z = lambda v: (v - v[pool].mean()) / (v[pool].std() + 1e-9)
        b = run8.local_bm25(text, pool)
        base = [z(c)[pool], z(b)[pool], np.log1p(g1[pool] * rw[pool] * 17), np.log1p(pr[pool] * 1e4)]
        ints = [z(iv[k])[pool] for k in INT]
        sits = [np.full(len(pool), x) for x in sit]
        inter = [sits[0] * ints[0], sits[1] * ints[1], sits[1] * ints[2], sits[2] * ints[2], sits[2] * ints[0], sits[1] * ints[3]]
        pool = np.union1d(pool, np.array([i for i in printed if (kind4(i) == kd) and i not in seen], dtype=int)).astype(int)
        base = [z(c)[pool], z(run8.local_bm25(text, pool))[pool], np.log1p(g1[pool] * rw[pool] * 17), np.log1p(pr[pool] * 1e4)]
        ints = [z(iv[k])[pool] for k in INT]
        sits = [np.full(len(pool), x) for x in sit]
        inter = [sits[0] * ints[0], sits[1] * ints[1], sits[1] * ints[2], sits[2] * ints[2], sits[2] * ints[0], sits[1] * ints[3]]
        shown_out = np.isin(pool, list(printed)).astype(float)[:, None]
        out[kd] = (pool, np.hstack([np.stack(base, 1), shown_out]), np.stack(ints, 1), np.stack(sits + inter, 1))
    return out
data = []   # (run, turn, kind, pool, base, ints, sits, labels, want)
for r in runs:
    cited = named(r['plan']); seen = set(cited)
    for t in range(len(r['turns'])):
        seen |= named(r['turns'][t]['code'])
        nxt = set().union(*[named(x['code']) for x in r['turns'][t + 1:t + 4]]) if t + 1 < len(r['turns']) else set()
        want = {w for w in nxt - seen if kind[w] != 'task'}
        if not want: continue
        for kd, (pool, B, I, S) in rows_for(r, t, cited, seen).items():
            data.append((r['id'], t, kd, pool, B, I, S, np.isin(pool, list(want)), want))
print('labelled turns', len({(d[0], d[1]) for d in data}), 'labels', sum(len(w) for w in {(d[0], d[1]): d[8] for d in data}.values()))
SETS = {'A shipped five (refit)': lambda d: d[4][:, :4], 'D five + printed by the last evals': lambda d: d[4], 'B + intents': lambda d: np.hstack([d[4], d[5]]), 'C + intents + situations': lambda d: np.hstack([d[4], d[5], d[6]])}
run_ids = sorted({d[0] for d in data})
turns = sorted({(d[0], d[1]) for d in data})
res = {}
for name, feat in SETS.items():
    prob = {}
    for held in run_ids:
        for kd in KINDS4:
            tr = [d for d in data if d[0] != held and d[2] == kd]; te = [d for d in data if d[0] == held and d[2] == kd]
            if not te: continue
            X = np.vstack([feat(d) for d in tr]); Y = np.concatenate([d[7] for d in tr])
            m = LogisticRegression(max_iter=3000, C=0.05).fit(X, Y) if Y.any() and not Y.all() else None
            for d in te:
                p = m.predict_proba(feat(d))[:, 1] if m is not None else np.zeros(len(d[3]))
                for i, pi in zip(d[3], p): prob.setdefault((d[0], d[1]), {})[i] = pi
    rec = {}
    for (rid, t) in turns:
        want = [d[8] for d in data if d[0] == rid and d[1] == t][0]
        acc = 0; got = set()
        for i, p in sorted(prob[(rid, t)].items(), key=lambda kv: -kv[1]):
            acc += tokens_of(i)
            if acc > 800: break
            got.add(i)
        rec[(rid, t)] = len(got & want) / len(want)
    res[name] = rec
rng = np.random.default_rng(0)
keys = list(res['A shipped five (refit)'].keys())
for name, rec in res.items():
    v = np.array([rec[k] for k in keys]); a = np.array([res['A shipped five (refit)'][k] for k in keys])
    boots = [np.mean((v - a)[rng.integers(0, len(v), len(v))]) for _ in range(2000)]
    print(f'{name:28s} recall within 800 tokens {v.mean():.3f}   vs A: {np.mean(v - a):+.3f} (95% {np.percentile(boots, 2.5):+.3f} to {np.percentile(boots, 97.5):+.3f})')
pickle.dump((data, res), open('replay6.pkl', 'wb'))
