# Scores acc-out.edn (the agent's answers) against what each held-out task's merge changed.
import edn_format, re, sys, numpy as np, collections
from sets import py
d = py(edn_format.loads(open(sys.argv[1] if len(sys.argv) > 1 else 'acc-out.edn').read()))
NAME = re.compile(r'(?:docs/[\w\-]+\.md#[\w\-]+|:?[\w.\-]+/[\w.\-?!*<>=+]+)')
def kind(n): return 'section' if n.startswith('docs/') else 'schema' if n.startswith(':') else 'function'
def report(rows, label, shown_of):
    agg = collections.defaultdict(lambda: [0, 0])   # depth -> [hits, shown]
    rec, cov, nq = [], [], 0
    perkind = collections.defaultdict(lambda: [0, 0, 0])  # kind -> [hit shown, shown, want]
    for r in rows:
        cited = set(NAME.findall(r.get('plan') or r.get('q') or '')) if 'plan' in r else set()
        want = set(r['want']) - cited
        scored = r['scored']
        shown = shown_of(r)
        if not want: continue
        nq += 1
        for n in shown:
            p = scored.get(n, 0)
            depth = 'doc >=20%' if p >= 0.2 else 'line 5-20%' if p >= 0.05 else 'below 5%'
            agg[depth][0] += n in want; agg[depth][1] += 1
            perkind[kind(n)][0] += n in want; perkind[kind(n)][1] += 1
        for w in want: perkind[kind(w)][2] += 1
        rec.append(len(want & set(shown)) / len(want)); cov.append(len(want & set(scored)) / len(want))
    print(f'\n{label}: {nq} with truth; recall of what is shown {np.mean(rec):.3f}; of all scored (the pool) {np.mean(cov):.3f}')
    for depth in ('doc >=20%', 'line 5-20%', 'below 5%'):
        h, n = agg[depth]
        if n: print(f'  {depth:11s} shown {n:4d} ({n / nq:4.1f} each), in truth {h:3d}: precision {h / n:.2f}')
    for k, (h, n, w) in perkind.items(): print(f'  {k:9s} shown {n:4d} in truth {h:3d} (P {h / max(n, 1):.2f}); truth {w}, found {h} (R {h / max(w, 1):.2f})')
report(d['tasks'], 'held-out tasks, the page (related)', lambda r: r['shown'])
def top(r, k=10):
    return [n for n, p in sorted(r['scored'].items(), key=lambda kv: -kv[1]) if p >= 0.05][:k]
report(d['queries'], '20 queries, relevance >=5%, first 10', top)
ms = [q['ms'] for q in d['queries']]
print(f'\nms per query (relevance, three kinds): median {np.median(ms):.0f}, max {max(ms):.0f}')
