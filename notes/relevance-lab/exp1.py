# Experiment 1: what ranks best from text alone. Cosine at each Matryoshka width, BM25 over
# identifier-split tokens, and hybrids (BM25 candidates reranked by cosine; z-score sum; rrf).
import sys
from sets import *
def z(s, m): x = s[m]; return (s - x.mean()) / (x.std() + 1e-9)
def rrf(*scores, k=10):
    out = np.zeros(N)
    for s in scores:
        r = np.empty(N); r[np.argsort(-s)] = np.arange(N); out += 1 / (k + 1 + r)
    return out
def rankers(t):
    q = t['qv']; cos = {d: Vd[d] @ prefix(q, d) for d in (64, 128, 256, 768)}
    b = bm25(t['q'])
    c = cos[768]
    out = {f'cos{d}': cos[d] for d in (64, 128, 256, 768)}
    out['bm25'] = b
    out['bm25+cos z'] = z(c, pool) + 0.5 * z(b, pool)
    out['rrf(bm25,cos)'] = rrf(b, c)
    # bm25 shortlist 200, ranked by cosine
    sl = np.argsort(-b)[:200]; s = np.full(N, -9.0); s[sl] = c[sl]; out['bm25 200 -> cos'] = s
    # matryoshka: 128 shortlist 100, ranked at 768
    sl = np.argsort(-cos[128])[:100]; s = np.full(N, -9.0); s[sl] = c[sl]; out['cos128 100 -> 768'] = s
    return out
pool = ~is_test   # tests are never shown as related; rank the rest
def evaluate(ts, label):
    agg = collections.defaultdict(list)
    for t in ts:
        for name, s in rankers(t).items():
            s = s.copy(); s[~pool] = -1e9; s[list(t['cited'])] = -1e9
            r = list(np.argsort(-s)[:50])
            agg[name].append((recall_at(r, t['want'], 5), recall_at(r, t['want'], 10), ndcg(r, t['want']), mrr(r, t['want'])))
    print(f'\n{label} ({len(ts)})        R@5    R@10   nDCG@10 MRR')
    for name, xs in agg.items():
        a = np.mean(xs, 0); print(f'  {name:20s} ' + '  '.join(f'{x:.3f}' for x in a))
if __name__ == '__main__':
    for nm in sys.argv[1:]:
        evaluate(globals()[nm](), nm)
def evaluate_kinds(ts, label, kinds=('form', 'section', 'schema')):
    for k in kinds:
        agg = collections.defaultdict(list)
        for t in ts:
            want = {w for w in t['want'] if kind[w] == k}
            if not want: continue
            for name, s in rankers(t).items():
                s = s.copy(); s[~pool | (kind != k)] = -1e9; s[list(t['cited'])] = -1e9
                r = list(np.argsort(-s)[:50])
                agg[name].append((recall_at(r, want, 5), recall_at(r, want, 10), ndcg(r, want), mrr(r, want)))
        n = len(next(iter(agg.values()))) if agg else 0
        print(f'\n{label} {k} ({n})   R@5    R@10   nDCG@10 MRR')
        for name, xs in agg.items():
            a = np.mean(xs, 0); print(f'  {name:20s} ' + '  '.join(f'{x:.3f}' for x in a))
