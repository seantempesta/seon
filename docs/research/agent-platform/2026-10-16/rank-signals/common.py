"""Shared loading for the rank-signals experiment."""
import os, glob, pickle, re
def query_text(inst):
    if inst.get('set') == 'task' and inst.get('query_tasks'):
        return '\n\n'.join(f"Task {k}. {v['plan'] if isinstance(v, dict) else v}" for k, v in sorted(inst['query_tasks'].items()))
    if inst.get('set') == 'live':
        return inst['query']
    return inst['message'].strip()
def load_instances(S, filtered=True, max_gt=15):
    out = []
    for p in sorted(glob.glob(os.path.join(S, 'inst', '*.pkl'))):
        inst = pickle.load(open(p, 'rb'))
        inst['id'] = inst['commit'][:12] if inst.get('set') != 'live' else inst['id']
        if filtered:
            fqs = {d['fq'] for d in inst['defs']}
            gt = [g for g in inst['gt'] if g in fqs]
            if not gt or len(inst['gt']) > max_gt: continue
            inst['gt'] = gt
        out.append(inst)
    return out
def must_read(inst):
    """The task's authored must-read symbols (older task format), read at the parent."""
    return sorted({s for v in (inst.get('query_tasks') or {}).values() if isinstance(v, dict) for s in v.get('must-read', [])})
