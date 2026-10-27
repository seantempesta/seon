import edn_format, re, numpy as np, collections
from sets import py
d = py(edn_format.loads(open('acc-out.edn').read()))
NAME = re.compile(r'(?:docs/[\w\-]+\.md#[\w\-]+|:?[\w.\-]+/[\w.\-?!*<>=+]+)')
def kind(n): return 'section' if n.startswith('docs/') else 'schema' if n.startswith(':') else 'function'
for label, rows, use_cited in (('tasks', d['tasks'], True), ('queries', d['queries'], False)):
    print('\n' + label)
    for k in ('function', 'section', 'schema'):
        print(' ', k, 'top-1 chance quartiles', np.round(np.percentile([max([p for n, p in r['scored'].items() if kind(n) == k] or [0]) for r in rows], [25, 50, 75]), 3))
        for cut in (0.0, 0.01, 0.02, 0.03, 0.05, 0.1):
            for cap in (5,):
                h = n = w = 0; rec = []
                for r in rows:
                    cited = set(NAME.findall(r.get('plan') or '')) if use_cited else set()
                    want = {x for x in set(r['want']) - cited if kind(x) == k}
                    top = [x for x, p in sorted(r['scored'].items(), key=lambda kv: -kv[1]) if kind(x) == k and p >= cut and x not in cited][:cap]
                    h += len(set(top) & want); n += len(top); w += len(want)
                    if want: rec.append(len(set(top) & want) / len(want))
                print(f'    cut {cut:.2f} cap {cap}: shown {n / len(rows):4.1f}/task P {h / max(n, 1):.2f} R(mean) {np.mean(rec) if rec else 0:.2f}')
