import pickle, warnings, collections; warnings.filterwarnings('ignore')
import numpy as np
from lab import items, kind
eps, cols, Ms, Dte = pickle.load(open('final.pkl', 'rb'))
plumb = {"seon.contract/checked", "seon.contract/on?", "seon.contract/error?", "seon.config/value", "seon.agent/db", "seon.db/eid", "seon.contract/defn", "seon.contract/error"}
for w in (0, 3):
    c = collections.Counter(); lines = 0
    for X, Y, nw, pool in Dte[w]['form']:
        top = pool[np.argsort(-Ms['form'].decision_function(X[:, cols]))[:5]]
        for i in top: c[items[i]['name']] += 1
        lines += len(top)
    print(f'warm={w}: {lines} lines; plumbing {sum(v for k, v in c.items() if k in plumb)}; most shown:', c.most_common(6))
