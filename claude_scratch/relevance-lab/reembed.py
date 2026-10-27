# Re-embed every item titled (the research report's best text): a form 'title: <var> | text: <src>',
# a section 'title: <heading> | text: <text>', a schema 'title: <key> | text: <form>', a plan as a
# 'search result' query.
import pickle, numpy as np
from emb import emb
items, V = pickle.load(open('corpus.pkl', 'rb'))
def text(it):
    k = it['kind']
    if k == 'task': return 'task: search result | query: ' + it['text']
    title = {'form': it['name'], 'section': it.get('heading') or it['name'], 'schema': it['name']}[k]
    return f'title: {title} | text: ' + (it.get('text') or '')
T = [text(it) for it in items]
out = []
for i in range(0, len(T), 512):
    out.append(emb(T[i:i+512])); print(i, flush=True)
pickle.dump(np.concatenate(out), open('V-titled.pkl', 'wb'))
