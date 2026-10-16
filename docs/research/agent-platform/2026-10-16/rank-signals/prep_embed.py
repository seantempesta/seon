"""Write the embedding inputs: every distinct parent form text (forms.jsonl, id = text digest), every distinct
docstring (docs.jsonl), and every instance query (queries.jsonl). Usage: python3 prep_embed.py SCRATCH"""
import os, sys, pickle, json, glob, hashlib
S = sys.argv[1]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import load_instances, query_text
forms, docs, qs = {}, {}, {}
for inst in load_instances(S, filtered=False):
    for d in inst['defs']:
        if d['digest'] not in forms:
            forms[d['digest']] = open(os.path.join(S, 'texts', d['digest'][:2], d['digest'])).read()[:12000]
        if d['doc']:
            docs[hashlib.sha256(d['doc'].encode()).hexdigest()[:24]] = d['doc']
    q = query_text(inst); qs[inst['id']] = q
for name, m in (('forms', forms), ('docs', docs), ('queries', qs)):
    with open(os.path.join(S, name + '.jsonl'), 'w') as w:
        for k, v in m.items(): w.write(json.dumps({'id': k, 'text': v}) + '\n')
    print(name, len(m), sum(len(v) for v in m.values()) // 4, 'tokens~')
