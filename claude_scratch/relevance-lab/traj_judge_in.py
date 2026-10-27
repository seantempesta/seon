# Judge input under the trajectory focus: at each replayed turn with evals, each kind's first five by
# the shipped chance against the plan-decaying-first focus, with the plan, the agent's last three evals
# and what it named next (a hint, not the truth)
import pickle, json, re, random, numpy as np
exec(open('replay4.py').read().split("CONFIGS =")[0].replace("MODE['v'] = sys.argv[1]", ""))
MODE['v'] = 'data'
def first_sentence(s): s = re.sub(r'\s+', ' ', s or ''); m = re.match(r'(.+?[.!?])(\s|$)', s); return m.group(1) if m else s[:200]
out = []
for r in runs:
    cited = named(r['plan']); seen = set(cited)
    for t in range(len(r['turns'])):
        seen |= named(r['turns'][t]['code'])
        qv, text = query(r, t, 'q5', 1.0)
        ch = chances(qv, text, cited, set())
        nxt = set().union(*[named(x['code']) for x in r['turns'][t + 1:t + 4]]) if t + 1 < len(r['turns']) else set()
        shown = {'sections': [], 'functions': [], 'schemas': []}
        for kd, key in (('form', 'functions'), ('section', 'sections'), ('schema', 'schemas')):
            top = [i for i, _ in sorted(ch.items(), key=lambda kv: -kv[1]) if kind[i] == kd and i not in seen][:5]
            shown[key] = [f"{items[i]['name']} {round(ch[i] * 100)}%: {first_sentence(items[i].get('doc') or items[i].get('lead') or items[i].get('text'))}" for i in top]
        out.append({'task': f"{r['id']} turn {t}", 'plan': r['plan'][:1500], 'recent-evals': [x['code'][:500] for x in r['turns'][max(0, t - 2):t + 1]],
                    'read-next': sorted(items[i]['name'] for i in nxt - seen), 'shown': shown})
random.Random(3).shuffle(out); out = out[:48]
for j in range(2): json.dump(out[j * 24:(j + 1) * 24], open(f'traj-judge-in-{j}.json', 'w'), indent=1)
print(len(out), sum(len(v) for o in out for v in o['shown'].values()))
