# Eval sets, each a list of {q, qv, want (item indices), cited (indices), at}
import edn_format, re, os, pickle, numpy as np
from lab import *
from emb import emb
QP = 'task: code retrieval | query: '
QPS = 'task: search result | query: '
PROMPT = {'v': QP}
def py(x):
    if isinstance(x, edn_format.Keyword): return x.name
    if isinstance(x, (edn_format.ImmutableDict, dict)): return {py(k): py(v) for k, v in x.items()}
    if isinstance(x, (list, tuple, edn_format.ImmutableList)): return [py(v) for v in x]
    return x
NAME = re.compile(r'(?:docs/[\w\-]+\.md#[\w\-]+|:?[\w.\-]+/[\w.\-?!*<>=+]+)')
def cited(text):
    return {idx[m] for m in NAME.findall(text) if m in idx}
def load(name, rows, qtext, wantf):
    if PROMPT['v'] != QP: name += '-s'
    f = SP + f'/set-{name}.pkl'
    if os.path.exists(f): return pickle.load(open(f, 'rb'))
    qs = [qtext(r) for r in rows]
    qv = emb([PROMPT['v'] + q for q in qs])
    out = []
    for r, q, v in zip(rows, qs, qv):
        c = cited(q)
        want = {idx[w] for w in wantf(r) if w in idx} - c
        if want: out.append(dict(q=q, qv=v, want=want, cited=c, row=r))
    pickle.dump(out, open(f, 'wb'))
    return out
def q20():
    rows = py(edn_format.loads(open('/private/tmp/claude-501/-Users-sean-src-seon-next/f2c40a8d-7863-4aec-9182-ac89469d1f48/scratchpad/emb/truth.edn').read()))
    return load('q20', rows, lambda r: r['q'], lambda r: r['want'])
def t31():
    rows = py(edn_format.loads(open('/Users/sean/src/seon-next/.worktrees/relevance-lab/notes/related-search/truth2.edn').read()))
    return load('t31', rows, lambda r: r['plan'], lambda r: r['fns'] + r['sections'] + r['schemas'])
def tall():
    rows = py(edn_format.loads(open(SP + '/truth-all.edn').read()))
    for i, r in enumerate(rows): r['age'] = i      # 0 the newest merge
    return load('tall', rows, lambda r: r['plan'], lambda r: r['fns'] + r['sections'] + r['schemas'])
