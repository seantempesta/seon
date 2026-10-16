"""Compute every ranking signal per instance at its parent commit, evaluate held-out, train a combination.
Run with the embed venv python (numpy, sklearn, safetensors). Usage: python -I signals.py SCRATCH OUTDIR"""
import os, sys, re, json, math, random, hashlib, collections, pickle
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from common import load_instances, query_text, must_read
S, OUT = sys.argv[1], sys.argv[2]

FQ_BT = re.compile(r'`([a-z][\w.\-]*/[^\s`/]+)`')
FQ_ANY = re.compile(r'(?<![\w:/.\-])([a-z][\w\-]*(?:\.[\w\-]+)+/[\w\-?!*<>=+\']+)')
NS_ANY = re.compile(r'(?<![\w:/.\-])((?:seon|my)(?:\.[\w\-]+)*)(?=[/\s`\'),.;:]|$)')
KW = re.compile(r':([a-z][\w.\-]*/[\w.\-?!*<>=]+)')

def strip_end(s): return s.rstrip('.,;:)')

# ---------- embeddings
def load_vecs(name):
    from safetensors.numpy import load_file
    p = os.path.join(S, f'emb_{name}.safetensors')
    if not os.path.exists(p): return None
    v = load_file(p)['vectors'].astype(np.float32)
    ids = [json.loads(l)['id'] for l in open(os.path.join(S, f'emb_{name}.ids.jsonl'))]
    return {i: v[k] for k, i in enumerate(ids)}
EMB = {n: load_vecs(n) for n in ('forms', 'docs', 'queries_code', 'queries_search')}

# ---------- co-change history (hunk-header declarations per earlier commit)
CC = [json.loads(l) for l in open(os.path.join(S, 'cochange.jsonl'))]
CC = [c for c in CC if len(c['decls']) <= 30]
CC_INDEX = collections.defaultdict(list)
for k, c in enumerate(CC):
    for d in c['decls']: CC_INDEX[d].append(k)
# parent ancestry: a commit counts only if it is an ancestor of the parent (no leakage); approximated by commit time
# strictly earlier than the parent's own commit time, and never the instance's commit.

def adjacency(inst):
    out_e, in_e = collections.defaultdict(set), collections.defaultdict(set)
    for a, b in inst['edges']: out_e[a].add(b); in_e[b].add(a)
    return out_e, in_e

def signals(inst):
    defs = inst['defs']; fqs = {d['fq'] for d in defs}; byfq = {d['fq']: d for d in defs}
    q = query_text(inst)
    out_e, in_e = adjacency(inst)
    sig = {}
    bt = {strip_end(m) for m in FQ_BT.findall(q)} & fqs
    anyref = ({strip_end(m) for m in FQ_ANY.findall(q)} | bt) & fqs
    mr = set(must_read(inst)) & fqs
    sig['task_must_read'] = {f: 1.0 for f in mr}
    anyref |= mr
    sig['plan_ref_bt'] = {f: 1.0 for f in bt}
    sig['plan_ref_any'] = {f: 1.0 for f in anyref}
    nsc = collections.Counter(strip_end(m) for m in NS_ANY.findall(q))
    for m in FQ_ANY.findall(q): nsc[m.split('/')[0]] += 1
    sig['plan_ns'] = {d['fq']: nsc[d['ns']] for d in defs if nsc.get(d['ns'])}
    # graph neighbourhood of plan refs
    def neigh(seeds, w2=0.25):
        sc = collections.Counter()
        for s in seeds:
            n1 = out_e[s] | in_e[s]
            for x in n1: sc[x] += 1.0
            for x in n1:
                for y in out_e[x] | in_e[x]: sc[y] += w2 / max(1, len(out_e[x] | in_e[x])) * 10
        for s in seeds: sc[s] += 5
        return dict(sc)
    sig['graph_plan'] = neigh(anyref)
    tc = set(inst['test_calls'])
    sig['test_calls'] = {f: 1.0 for f in tc}
    # test reach: transitive callees of what the new tests call, decayed by depth
    reach = {}; frontier = set(tc); depth = 0
    while frontier and depth < 6:
        for f in frontier: reach.setdefault(f, 1.0 / (1 + depth))
        frontier = {y for x in frontier for y in out_e[x]} - set(reach); depth += 1
    sig['test_reach'] = reach
    sig['graph_test'] = neigh(tc)
    # callers of test reach restricted to the same namespaces (who else feeds those)
    # schema flow
    qk = {strip_end(k) for k in KW.findall(q)}
    sig['schema_contract'] = {d['fq']: len(qk & set(d['schema'])) for d in defs if qk & set(d['schema'])}
    # schema mention anywhere in the form text
    sm = {}
    if qk:
        for d in defs:
            t = TEXT(d['digest']); n = sum(1 for k in qk if ':' + k in t)
            if n: sm[d['fq']] = n
    sig['schema_text'] = sm
    # embeddings
    for qn in ('queries_code', 'queries_search'):
        if EMB[qn] and EMB['forms'] and inst['id'] in EMB[qn]:
            qv = EMB[qn][inst['id']]
            sig['embed_src_' + qn.split('_')[1]] = {d['fq']: float(EMB['forms'][d['digest']] @ qv) for d in defs if d['digest'] in EMB['forms']}
            if EMB['docs']:
                sig['embed_doc_' + qn.split('_')[1]] = {d['fq']: float(EMB['docs'][hd(d['doc'])] @ qv) for d in defs if d['doc'] and hd(d['doc']) in EMB['docs']}
    # co-change with seeds (plan refs + test calls), history before the parent only
    seeds = anyref | tc
    cc = collections.Counter(); pt = inst['parent_time']
    for s in seeds:
        for k in CC_INDEX.get(s, ()):
            c = CC[k]
            if c['time'] >= pt or c['commit'] == inst['commit']: continue
            for d in c['decls']:
                if d != s and d in fqs: cc[d] += 1.0 / (len(c['decls']) - 1)
    sig['cochange'] = dict(cc)
    # recency: declarations changed in the last 20 commits before the parent (history only)
    rec = collections.Counter()
    prior = [c for c in CC if c['time'] < pt][:20]  # CC is newest-first
    for i, c in enumerate(prior):
        for d in c['decls']:
            if d in fqs: rec[d] += 1.0 / (1 + i)
    sig['recency'] = dict(rec)
    # unique: the declarations whose source cites the task by its reference ns/task-name (CLAUDE.md: a definition
    # cites its tasks in its docstring), read at the parent
    cite = {}
    names = list((inst.get('query_tasks') or {}).keys())
    if names:
        for d in defs:
            t = TEXT(d['digest'])
            if any(n in t or n.split('/', 1)[1] in t for n in names): cite[d['fq']] = 1.0
    sig['task_citation'] = cite
    return sig

_TC = {}
def TEXT(dg):
    if dg not in _TC: _TC[dg] = open(os.path.join(S, 'texts', dg[:2], dg)).read()
    return _TC[dg]
def hd(s): return hashlib.sha256(s.encode()).hexdigest()[:24]

def rank(scores, defs, seed):
    r = random.Random(seed); tb = {d['fq']: r.random() for d in defs}
    return sorted((d['fq'] for d in defs), key=lambda f: (-scores.get(f, -1e9) if f in scores else 1e9, tb[f]))

def metrics(order, gt, tok):
    g = set(gt); m = {}
    for k in (5, 10, 20): m[f'R@{k}'] = len(g & set(order[:k])) / len(g)
    rr = 0.0
    for i, f in enumerate(order):
        if f in g: rr = 1.0 / (i + 1); break
    m['MRR'] = rr
    for b in (2000, 4000, 8000):
        used = 0; got = 0
        for f in order:
            used += tok[f]
            if used > b: break
            got += f in g
        m[f'R@{b//1000}k'] = got / len(g)
    return m

def main():
    insts = load_instances(S)
    pt = json.load(open(os.path.join(S, 'parent_times.json')))
    for i in insts: i['parent_time'] = pt[i['parent']]
    insts.sort(key=lambda i: i['parent_time'])
    rows = []; allsig = {}
    for inst in insts:
        sg = signals(inst); allsig[inst['id']] = sg
    names = sorted({k for s in allsig.values() for k in s})
    # split: by time, oldest 60% train, newest 40% test; the live set is always test
    # split by time within each set (oldest 60% train, newest 40% test): task instances are recent, so a global
    # time split would leave almost none to train on. Each task is retired once, so the split is also by task.
    split = {}
    for st in ('task', 'message'):
        reg = [i for i in insts if i['set'] == st]; cut = int(len(reg) * 0.6)
        for k, i in enumerate(reg): split[i['id']] = 'train' if k < cut else 'test'
    for i in insts:
        if i['set'] == 'live': split[i['id']] = 'live'
    # per-signal evaluation
    res = collections.defaultdict(lambda: collections.defaultdict(list))
    reach = collections.defaultdict(lambda: collections.defaultdict(list))
    for inst in insts:
        tok = {d['fq']: d['tokens'] for d in inst['defs']}
        for n in names + ['random']:
            sc = allsig[inst['id']].get(n, {}) if n != 'random' else {}
            o = rank(sc, inst['defs'], hash(inst['id']) & 0xffff)
            m = metrics(o, inst['gt'], tok)
            key = (split[inst['id']], inst['set'])
            for k, v in m.items(): res[(n,) + key][k].append(v)
            reach[(n,) + key]['reach'].append(len(set(inst['gt']) & {f for f, v in sc.items() if v > 0}) / len(inst['gt']))
            reach[(n,) + key]['cand'].append(sum(1 for v in sc.values() if v > 0))
    # ---- untrained reciprocal-rank fusion of four signals
    for inst in insts:
        sg = allsig[inst['id']]; fused = collections.Counter()
        for n in ('plan_ref_any', 'graph_plan', 'embed_src_code', 'test_reach'):
            sc = sg.get(n, {})
            for k, f in enumerate(sorted(sc, key=lambda f: -sc[f])[:200]): fused[f] += 1.0 / (10 + k)
        tok = {d['fq']: d['tokens'] for d in inst['defs']}
        o = rank(dict(fused), inst['defs'], 1)
        for k, v in metrics(o, inst['gt'], tok).items(): res[('RRF4_untrained', split[inst['id']], inst['set'])][k].append(v)
    # ---- combination
    feats = names
    _FR = {}
    def featrows(inst):
        if inst['id'] in _FR: return _FR[inst['id']]
        sg = allsig[inst['id']]; X = []; fq = []
        ranks = {}
        for n in feats:
            s = sg.get(n, {})
            if not s: ranks[n] = {}; continue
            o = sorted(s, key=lambda f: -s[f]); ranks[n] = {f: k for k, f in enumerate(o)}
        nd = len(inst['defs'])
        for d in inst['defs']:
            f = d['fq']; row = []
            for n in feats:
                v = sg.get(n, {}).get(f)
                row += [0.0 if v is None else float(v), 0.0 if f not in ranks[n] else 1.0 / (1 + ranks[n][f])]
            row += [math.log(d['tokens']), float(d['private']), float('test' in d['ns'])]
            X.append(row); fq.append(f)
        _FR[inst['id']] = (np.array(X, dtype=np.float32), fq); return _FR[inst['id']]
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import HistGradientBoostingClassifier
    def train(exclude=(), model='lr'):
        Xs, ys = [], []
        for inst in insts:
            if split[inst['id']] != 'train': continue
            X, fq = featrows(inst); g = set(inst['gt']); y = np.array([f in g for f in fq])
            # sample negatives: all positives + 300 random negatives per instance
            r = np.random.RandomState(0); neg = np.where(~y)[0]; pos = np.where(y)[0]
            keep = np.concatenate([pos, r.choice(neg, min(300, len(neg)), replace=False)])
            Xs.append(X[keep]); ys.append(y[keep])
        X = np.concatenate(Xs); y = np.concatenate(ys)
        mask = np.ones(X.shape[1], bool)
        for n in exclude:
            j = feats.index(n); mask[2 * j] = mask[2 * j + 1] = False
        if model == 'lr':
            from sklearn.preprocessing import StandardScaler
            from sklearn.pipeline import make_pipeline
            clf = make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000, C=1.0, class_weight='balanced'))
        else:
            clf = HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, max_leaf_nodes=15)
        clf.fit(X[:, mask], y); return clf, mask
    def evalc(clf, mask, label):
        for inst in insts:
            X, fq = featrows(inst); p = clf.predict_proba(X[:, mask])[:, 1]
            sc = dict(zip(fq, p)); o = rank(sc, inst['defs'], 1)
            tok = {d['fq']: d['tokens'] for d in inst['defs']}
            for k, v in metrics(o, inst['gt'], tok).items(): res[(label, split[inst['id']], inst['set'])][k].append(v)
    for model in ('lr', 'gbt'):
        clf, mask = train(model=model); evalc(clf, mask, f'COMBINED_{model}')
    clf, mask = train(model='gbt')
    groups = {'refs': ['plan_ref_bt', 'plan_ref_any', 'plan_ns'], 'graph': ['graph_plan', 'graph_test'],
              'test': ['test_calls', 'test_reach', 'graph_test'], 'schema': ['schema_contract', 'schema_text'],
              'embed': [n for n in names if n.startswith('embed')], 'cochange': ['cochange'], 'recency': ['recency'],
              'task_citation': ['task_citation'], 'must_read': ['task_must_read']}
    for g, ns in groups.items():
        ns = [n for n in ns if n in feats]
        if not ns: continue
        c, m = train(exclude=ns, model='gbt'); evalc(c, m, f'gbt_minus_{g}')
    # write tables
    table = []
    for key, m in sorted(res.items()):
        rowd = {'signal': key[0], 'split': key[1], 'set': key[2], 'n': len(m['MRR'])}
        rowd.update({k: round(float(np.mean(v)), 3) for k, v in m.items()})
        bs = np.random.RandomState(0); a = np.array(m['R@10'])
        if len(a) > 5:
            b = [a[bs.randint(0, len(a), len(a))].mean() for _ in range(500)]
            rowd['R@10_ci'] = [round(float(np.percentile(b, 2.5)), 3), round(float(np.percentile(b, 97.5)), 3)]
        rk = reach.get(key)
        if rk: rowd['reachable'] = round(float(np.mean(rk['reach'])), 3); rowd['cands_median'] = float(np.median(rk['cand']))
        table.append(rowd)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'results.jsonl'), 'w') as w:
        for r in table: w.write(json.dumps(r) + '\n')
    with open(os.path.join(OUT, 'split.json'), 'w') as w: json.dump(split, w)
    print('instances', collections.Counter((split[i['id']], i['set']) for i in insts))
    for st in ('test',):
        for se in ('task', 'message'):
            print(f'\n== {st} / {se}')
            print('signal'.ljust(26), 'n  R@5   R@10  R@20  MRR   R@2k  R@4k  R@8k  reach')
            for r in sorted([r for r in table if r['split'] == st and r['set'] == se], key=lambda r: -r['R@10']):
                print(r['signal'].ljust(26), r['n'], *(f"{r[k]:.3f}" for k in ('R@5', 'R@10', 'R@20', 'MRR', 'R@2k', 'R@4k', 'R@8k')), r.get('reachable', ''), r.get('R@10_ci', ''))
    for r in table:
        if r['split'] == 'live': print('LIVE', r['signal'], r['R@5'], r['R@10'], r['MRR'], r.get('reachable'))
if __name__ == '__main__': main()
