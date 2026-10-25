# The before/after table from run-stats outputs (notes/step0/stats.clj): per task, outcome and
# turns, the cache hit rate over turns 3 on, mean tokens per turn, cost.
import re, sys, json
def load(path):
    s = open(path).read()
    if s.startswith('['):
        t = s
    else:
        t = None
    m = None if t else re.search(r':text "(.*)"\}\}', s, re.S) or re.search(r':printed "(.*)", :handle', s, re.S)
    t = t or m.group(1).encode().decode('unicode_escape')
    runs = []
    for blk in [b for b in re.split(r'\{(?=:(?:status|outcome|task|run|usd|tokens|hit|miss|turns|per-turn) )', t) if ':per-turn' in b]:
        g = lambda k: (re.search(k + r'\s+([^,\n}]+)', blk) or [None, None])[1]
        pt = re.findall(r'\[(\d+) (\d+) (\d+) (\d+) "([a-z_]+)" (\d+)\]', blk)
        runs.append(dict(task=g(':task').strip('"').strip(), outcome=g(':outcome'), usd=float(g(':usd')),
                         turns=[tuple(int(x) if x.isdigit() else x for x in p) for p in pt]))
    return runs
def row(r):
    ts = r['turns']; late = [t for t in ts if t[0] >= 3]
    hit = sum(t[2] for t in late) / max(1, sum(t[2] + t[3] for t in late))
    tok = sum(t[1] for t in ts) / max(1, len(ts))
    return r['outcome'], len(ts), hit, tok, r['usd']
for path in sys.argv[1:]:
    rs = load(path); print('==', path)
    tot = [0, 0, 0, 0.0]; green = []
    for r in sorted(rs, key=lambda r: r['task']):
        o, n, h, tok, usd = row(r)
        print(f"  {r['task'].split('/')[1][:34]:34} {o:13} {n:3} turns  hit(t>=3) {h:5.1%}  {tok:7.0f} tok/turn  ${usd:.4f}")
        tot[0] += n; tot[3] += usd
        if o == ':green': green.append(n)
    allt = [t for r in rs for t in r['turns']]; late = [t for t in allt if t[0] >= 3]
    print(f"  TOTAL green {len(green)}/5 turns-to-green {green}  turns {tot[0]}  hit(t>=3) {sum(t[2] for t in late)/max(1,sum(t[2]+t[3] for t in late)):.1%}  tok/turn {sum(t[1] for t in allt)/max(1,len(allt)):.0f}  ${tot[3]:.4f}")
