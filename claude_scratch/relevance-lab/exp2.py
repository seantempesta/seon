# Experiment 2: a contrastive adapter over the frozen vectors. The query side only: q' = norm(q + B (C q))
# (low rank r), docs as stored, so nothing is re-embedded and a doc's Matryoshka prefix is unchanged.
# Trained by InfoNCE on graph edges (plan -> its cited vars and sections, section -> the vars it
# names, form -> the forms it calls, form -> the schema keys it names), in-batch negatives plus
# hard negatives (near in cosine, not an edge). The truth tasks are held out.
import sys
from exp1 import *

def pairs(held):
    P = []
    for a, b, t in E:
        if t == 'calls' and not (is_fn[a] and is_fn[b]): continue
        if t == 'task' and items[a]['name'] in held: continue
        if t in ('task', 'cites', 'calls', 'key'): P.append((a, b))
        if t in ('calls', 'key'): P.append((b, a))
    return np.array(P)

def train(P, r=64, tau=0.05, epochs=8, B=256, lr=2e-3, wd=1e-4, hard=8, seed=0):
    rng = np.random.default_rng(seed)
    d = V.shape[1]
    C = rng.normal(0, 0.01, (r, d)).astype(np.float32); Bm = np.zeros((d, r), np.float32)
    m = [np.zeros_like(C), np.zeros_like(Bm)]; v2 = [np.zeros_like(C), np.zeros_like(Bm)]; step = 0
    edge = set(map(tuple, P))
    for ep in range(epochs):
        rng.shuffle(P); tot = 0
        for i in range(0, len(P) - B + 1, B):
            q, dpos = V[P[i:i+B, 0]], P[i:i+B, 1]
            # hard negatives: the nearest docs by stored cosine of the query that are not its edge
            z = q + (q @ C.T) @ Bm.T
            nz = np.linalg.norm(z, axis=1, keepdims=True); zn = z / nz
            cand = dpos.tolist()
            if hard:
                sims = zn @ V.T
                for j in range(B):
                    top = np.argpartition(-sims[j], 40)[:40]
                    hn = [x for x in top if (P[i+j, 0], x) not in edge and x != P[i+j, 0]][:hard]
                    cand += hn
            D = V[cand]
            logits = zn @ D.T / tau
            logits -= logits.max(1, keepdims=True)
            p = np.exp(logits); p /= p.sum(1, keepdims=True)
            tot += -np.log(p[np.arange(B), np.arange(B)] + 1e-9).mean()
            g = p; g[np.arange(B), np.arange(B)] -= 1; g /= (B * tau)       # dL/dlogits * tau
            gzn = g @ D                                                       # dL/dzn
            gz = (gzn - zn * (gzn * zn).sum(1, keepdims=True)) / nz           # through the norm
            h = q @ C.T
            gB = gz.T @ h + wd * Bm
            gC = (gz @ Bm).T @ q + wd * C
            step += 1
            for k, (w, gw) in enumerate(((C, gC), (Bm, gB))):
                m[k] = .9 * m[k] + .1 * gw; v2[k] = .999 * v2[k] + .001 * gw * gw
                w -= lr * (m[k] / (1 - .9 ** step)) / (np.sqrt(v2[k] / (1 - .999 ** step)) + 1e-8)
        print(f'  epoch {ep} loss {tot / (len(P) // B):.3f}', file=sys.stderr)
    return C, Bm

def adapt(q, C, Bm):
    z = q + (q @ C.T) @ Bm.T
    return z / np.linalg.norm(z, axis=-1, keepdims=True)

if __name__ == '__main__':
    import exp1
    exp1.pool = ~is_test & (kind != 'task')
    sets = {nm: globals()[nm]() for nm in sys.argv[1:]}
    held = {t['row'].get('task') for ts in sets.values() for t in ts}
    P = pairs(held); print('pairs', len(P), file=sys.stderr)
    for r in (64,):
        C, Bm = train(P.copy(), r=r)
        pickle.dump((C, Bm), open(SP + f'/adapter-r{r}.pkl', 'wb'))
        base = exp1.rankers
        def rk(t, C=C, Bm=Bm):
            out = base(t); qa = adapt(t['qv'], C, Bm)
            out['adapted768'] = V @ qa
            out['adapted128'] = Vd[128] @ prefix(qa, 128)
            out['rrf(bm25,adapted)'] = rrf(out['bm25'], out['adapted768'])
            return {k: out[k] for k in ('cos768', 'cos128', 'adapted768', 'adapted128', 'bm25', 'rrf(bm25,cos)', 'rrf(bm25,adapted)')}
        exp1.rankers = rk
        for nm, ts in sets.items():
            exp1.evaluate(ts, f'{nm} r{r}'); exp1.evaluate_kinds(ts, f'{nm} r{r}')
        exp1.rankers = base
