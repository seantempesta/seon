import pickle, sets, exp1
from lab import *
VT = pickle.load(open('V-titled.pkl', 'rb')); print(VT.shape)
exp1.pool = ~is_test & (kind != 'task')
base = exp1.rankers
def rk(t):
    o = base(t); return {k: o[k] for k in ('cos128', 'cos256', 'cos768', 'bm25', 'bm25+cos z', 'rrf(bm25,cos)')}
exp1.rankers = rk
for label, vecs, prompt in [('stored', V, sets.QP), ('titled', VT, sets.QPS)]:
    Vd.update({d: prefix(vecs, d) for d in (64, 128, 256, 512, 768)})
    sets.PROMPT['v'] = prompt
    tp = [t for t in sets.tall() if t['row']['plan-at'] != 'commits']
    exp1.evaluate_kinds(sets.q20(), f'{label} q20', ('form', 'section'))
    exp1.evaluate_kinds(tp, f'{label} task plans', ('form', 'section', 'schema'))
