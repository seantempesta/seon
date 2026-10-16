"""Build benchmark instances. For each selected commit: the parent's top-level src declarations (clj-kondo analysis of
`git archive <parent> src`), the call edges among them, the ground truth (parent declarations whose lines the commit's
diff touches), and the calls made by the deftests the commit adds or changes. Never checks out over the working tree.
Usage: python3 build.py SCRATCH OUTDIR  (reads SCRATCH/cand.jsonl, SCRATCH/deleted.jsonl)"""
import json, os, re, sys, subprocess, hashlib, random, pickle, tempfile, shutil
from multiprocessing import Pool
REPO = '/Users/sean/src/seon'
SCR, OUT = sys.argv[1], sys.argv[2]
TEXTS = os.path.join(SCR, 'texts')
KCFG = '{:analysis {:keywords false :locals false} :output {:format :json} :linters {:unresolved-symbol {:level :off}}}'

def git(*a, text=True):
    return subprocess.run(['git', '-C', REPO, *a], capture_output=True, text=text).stdout

def kondo(path):
    r = subprocess.run(['clj-kondo', '--lint', path, '--config', KCFG], capture_output=True, text=True)
    try: return json.loads(r.stdout)['analysis']
    except Exception: return {}

def put_text(t):
    d = hashlib.sha256(t.encode()).hexdigest()[:24]
    p = os.path.join(TEXTS, d[:2], d)
    if not os.path.exists(p):
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p + '.tmp%d' % os.getpid(), 'w') as f: f.write(t)
        os.replace(p + '.tmp%d' % os.getpid(), p)
    return d

def schema_kws(text):
    i = text.find(':malli/schema')
    if i < 0: return []
    j = i + len(':malli/schema'); depth = 0; k = j
    while k < len(text):
        c = text[k]
        if c in '[({': depth += 1
        elif c in '])}':
            depth -= 1
            if depth <= 0: k += 1; break
        k += 1
    return sorted(set(re.findall(r':([a-z][\w.\-]*/[\w.\-?!*<>=]+)', text[j:k])))

def hunks(diff):
    """{path: [(old_start, old_count, new_start, new_count)]}"""
    res = {}; cur = None
    for l in diff.splitlines():
        if l.startswith('diff --git'):
            cur = None
        elif l.startswith('--- '):
            oldp = l[4:]
        elif l.startswith('+++ '):
            newp = l[4:]
            cur = (oldp[2:] if oldp != '/dev/null' else None, newp[2:] if newp != '/dev/null' else None)
            res.setdefault(cur, [])
        elif l.startswith('@@') and cur:
            m = re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', l)
            a, b, c, d = m.groups()
            res[cur].append((int(a), int(b or 1), int(c), int(d or 1)))
    return res

def touched(defs, hs):
    out = set()
    for (os_, oc, ns_, nc) in hs:
        if oc > 0:
            lo, hi = os_, os_ + oc - 1
            for d in defs:
                if d['row'] <= hi and d['end'] >= lo: out.add(d['fq'])
        else:  # pure insertion after line os_
            for d in defs:
                if d['row'] <= os_ and d['end'] > os_: out.add(d['fq'])
    return out

def process(inst):
    parent, commit = inst['parent'], inst['commit']
    tmp = tempfile.mkdtemp(dir=SCR)
    try:
        arch = subprocess.run(['git', '-C', REPO, 'archive', parent, 'src'], capture_output=True).stdout
        subprocess.run(['tar', '-x', '-C', tmp], input=arch)
        an = kondo(os.path.join(tmp, 'src'))
        defs = []; lines_cache = {}
        for v in an.get('var-definitions', []):
            if v.get('col') != 1: continue
            fn = v['filename']; rel = os.path.relpath(fn, tmp)
            if not rel.endswith('.clj'): continue
            if fn not in lines_cache: lines_cache[fn] = open(fn, errors='replace').read().split('\n')
            L = lines_cache[fn]; row, end = v['row'], v.get('end-row', v['row'])
            text = '\n'.join(L[row - 1:end])
            defs.append({'fq': v['ns'] + '/' + v['name'], 'ns': v['ns'], 'file': rel, 'row': row, 'end': end,
                         'doc': v.get('doc') or '', 'private': bool(v.get('private')), 'by': v.get('defined-by', ''),
                         'digest': put_text(text), 'tokens': max(1, len(text) // 4), 'schema': schema_kws(text)})
        fqs = {d['fq'] for d in defs}
        edges = set()
        for u in an.get('var-usages', []):
            if u.get('from-var') is None: continue
            a = u['from'] + '/' + u['from-var']; b = u.get('to', '') + '/' + u['name']
            if a in fqs and b in fqs and a != b: edges.add((a, b))
        # ground truth
        diff = git('diff', '-U0', '--no-renames', parent, commit, '--', 'src')
        H = hunks(diff); gt = set(); added = 0
        byfile = {}
        for d in defs: byfile.setdefault(d['file'], []).append(d)
        for (oldp, newp), hs in H.items():
            if not ((oldp or newp) or '').endswith('.clj'): continue
            if oldp: gt |= touched(byfile.get(oldp, []), hs)
        added = len(re.findall(r'^\+\(def', diff, re.M)) - len(re.findall(r'^-\(def', diff, re.M))
        # new or changed deftests and what they call
        TH = hunks(git('diff', '-U0', '--no-renames', parent, commit, '--', 'test'))
        tdir = os.path.join(tmp, 'tst'); os.makedirs(tdir)
        tfiles = {}
        for (oldp, newp), hs in TH.items():
            if newp and newp.endswith('.clj'):
                txt = git('show', f'{commit}:{newp}')
                p = os.path.join(tdir, str(len(tfiles)) + '_' + os.path.basename(newp)); open(p, 'w').write(txt)
                tfiles[p] = hs
        test_names, test_calls = [], set()
        if tfiles:
            ta = kondo(tdir)
            tdefs = [v for v in ta.get('var-definitions', []) if v.get('col') == 1]
            changed = set()
            for v in tdefs:
                for (_, _, ns_, nc) in tfiles.get(v['filename'], []):
                    if nc > 0 and v['row'] <= ns_ + nc - 1 and v.get('end-row', v['row']) >= ns_:
                        changed.add((v['ns'], v['name']))
            test_names = sorted(n + '/' + m for n, m in changed)
            for u in ta.get('var-usages', []):
                if (u['from'], u.get('from-var')) in changed:
                    t = u.get('to', '') + '/' + u['name']
                    if t in fqs: test_calls.add(t)
        msg = git('log', '-1', '--format=%B', commit)
        res = dict(inst, defs=defs, edges=sorted(edges), gt=sorted(gt), added_defs=added, test_names=test_names,
                   test_calls=sorted(test_calls), message=msg)
        with open(os.path.join(OUT, commit[:12] + '.pkl'), 'wb') as f: pickle.dump(res, f)
        return commit[:12], len(defs), len(gt), len(test_calls)
    except Exception as e:
        return commit[:12], 'ERR', repr(e), 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True); os.makedirs(TEXTS, exist_ok=True)
    cand = [json.loads(l) for l in open(os.path.join(SCR, 'cand.jsonl'))]
    dele = {json.loads(l)['commit']: json.loads(l)['deleted'] for l in open(os.path.join(SCR, 'deleted.jsonl'))}
    A = [dict(c, set='task', query_tasks=dele[c['commit']]) for c in cand if c['commit'] in dele]
    rest = [c for c in cand if c['commit'] not in dele]
    random.Random(7).shuffle(rest)
    B = [dict(c, set='message') for c in rest[:int(sys.argv[3]) if len(sys.argv) > 3 else 300]]
    todo = [i for i in A + B if not os.path.exists(os.path.join(OUT, i['commit'][:12] + '.pkl'))]
    print('todo', len(todo), flush=True)
    with Pool(8) as p:
        for r in p.imap_unordered(process, todo): print(*r, flush=True)
