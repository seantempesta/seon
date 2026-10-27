# The relevance lab: the corpus (main's vectors and graph, exported from my worktree root),
# eval sets, and the rankers compared.
import pickle, re, math, os, json, collections
import numpy as np, scipy.sparse as sp
SP = os.path.dirname(os.path.abspath(__file__))
items, V = pickle.load(open(SP + '/corpus.pkl', 'rb'))
N = len(items)
kind = np.array([i['kind'] for i in items])
# name -> index (a form under each var it defines)
idx = {}
for n, it in enumerate(items):
    for nm in (it.get('names') if it['kind'] == 'form' and it.get('names') else [it['name']]):
        idx.setdefault(nm, n)
is_test = np.array([it.get('vk') == 'test' or (it.get('ns') or '').endswith('-test') for it in items])
is_fn = (kind == 'form') & ~is_test

# ---- the graph: undirected adjacency over items, typed edges ---------------------------------
def edges():
    E = []
    for n, it in enumerate(items):
        k = it['kind']
        if k == 'form':
            for r in it.get('refs', []) + it.get('quoted', []):
                m = idx.get(r)
                if m is not None and m != n: E.append((n, m, 'calls'))
            for kw in it.get('keywords', []):
                m = idx.get(kw)
                if m is not None: E.append((n, m, 'key'))
        elif k == 'section':
            for r in it.get('names', []):
                m = idx.get(r)
                if m is not None: E.append((n, m, 'cites'))
            for r in it.get('sec-refs', []):
                m = idx.get(r)
                if m is not None and m != n: E.append((n, m, 'links'))
            if it.get('parent') in idx: E.append((n, idx[it['parent']], 'parent'))
        elif k == 'task':
            for r in it.get('task-refs', []) + it.get('task-secs', []):
                m = idx.get(r)
                if m is not None: E.append((n, m, 'task'))
    return E
E = edges()
def adjacency(types=None, weights=None):
    rows, cols, w = [], [], []
    for a, b, t in E:
        if types and t not in types: continue
        x = (weights or {}).get(t, 1.0)
        rows += [a, b]; cols += [b, a]; w += [x, x]
    A = sp.csr_matrix((w, (rows, cols)), shape=(N, N))
    A.data[:] = np.minimum(A.data, 1.0) if weights is None else A.data
    return A
A = adjacency()
deg = np.asarray(A.sum(1)).ravel()

# ---- lexical: identifier-split tokens, BM25 ------------------------------------------------
def toks(s):
    s = re.sub(r'([a-z])([A-Z])', r'\1 \2', s or '')
    return [t for t in re.split(r'[^a-zA-Z0-9]+', s.lower()) if len(t) > 1]
def item_text(it):
    return ' '.join([it['name'], it['name'], it.get('doc') or '', it.get('heading') or '', it.get('text') or ''])
vocab = {}
def bm25_index(texts, k1=1.2, b=0.75):
    rows, cols, vals = [], [], []
    lens = []
    for n, t in enumerate(texts):
        c = collections.Counter(toks(t)); lens.append(sum(c.values()))
        for w, f in c.items():
            j = vocab.setdefault(w, len(vocab)); rows.append(n); cols.append(j); vals.append(f)
    tf = sp.csr_matrix((vals, (rows, cols)), shape=(len(texts), len(vocab)), dtype=np.float32)
    lens = np.array(lens, dtype=np.float32); avg = lens.mean()
    df = np.bincount(tf.indices, minlength=len(vocab)); idf = np.log(1 + (len(texts) - df + .5) / (df + .5))
    tf = tf.tocoo()
    s = tf.data * (k1 + 1) / (tf.data + k1 * (1 - b + b * lens[tf.row] / avg))
    return sp.csr_matrix((s * idf[tf.col], (tf.row, tf.col)), shape=tf.shape), idf
BM, IDF = bm25_index([item_text(it) for it in items])
def bm25(q):
    qi = [vocab[w] for w in set(toks(q)) if w in vocab]
    if not qi: return np.zeros(N, np.float32)
    return np.asarray(BM[:, qi].sum(1)).ravel()

# ---- vectors: Matryoshka prefixes ------------------------------------------------------------
def prefix(X, d):
    Y = X[..., :d]; return Y / np.linalg.norm(Y, axis=-1, keepdims=True)
Vd = {d: prefix(V, d) for d in (64, 128, 256, 512, 768)}

# ---- eval ----------------------------------------------------------------------------------
def recall_at(rank, want, k): return len(set(rank[:k]) & want) / len(want)
def ndcg(rank, want, k=10):
    g = sum(1 / math.log2(i + 2) for i, x in enumerate(rank[:k]) if x in want)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(k, len(want))))
    return g / ideal
def mrr(rank, want):
    for i, x in enumerate(rank):
        if x in want: return 1 / (i + 1)
    return 0
