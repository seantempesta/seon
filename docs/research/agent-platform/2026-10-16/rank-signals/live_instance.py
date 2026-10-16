"""The live set: the 4 readable attempts of seon.ref/wildcard-citations-are-prose (context-patterns.edn), at the
commit before the 2026-10-15 run. Two targets: the owner the fix changed (seon.ref/span-candidate), and every src
declaration the agents read or searched for by name. Usage: python3 live_instance.py SCRATCH"""
import sys, os, re, json, pickle, subprocess
S = sys.argv[1]
sys.argv = ['build.py', S, os.path.join(S, 'inst')]
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'build.py')).read().split("if __name__")[0])
os.makedirs(TEXTS, exist_ok=True)
parent = subprocess.run(['git', '-C', REPO, 'rev-list', '-1', '--before=2026-10-15 12:00', 'HEAD'], capture_output=True, text=True).stdout.strip()
QUERY = ("Task seon.ref/wildcard-citations-are-prose. A citation whose name part ends in * (such as :seon.render.unknown/*) names a family, "
         "not a name, so it must be read as prose and not as a reference. Owner: seon.ref/span-candidate. "
         "Done when seon.ref-test/a-citation-ending-in-a-wildcard-is-prose is green.")
edn = open(os.path.join(REPO, 'docs/research/agent-platform/2026-10-16/context-patterns.edn')).read()
srcs = re.findall(r':source\s+"((?:[^"\\]|\\.)*)"', edn)
names = set()
for s in srcs:
    names |= set(re.findall(r"'(seon\.[\w.\-]+/[\w\-?!*]+)", s))
    names |= {'seon.ref/' + n for n in re.findall(r'\((?:seon\.ref/)?(span-candidate|references|document|names|candidate|candidates|parsed)\b', s)}
inst = {'commit': parent, 'parent': parent, 'time': 0, 'src': [], 'test': [], 'taskfiles': [], 'set': 'live'}
r = process(inst); print(r)
base = pickle.load(open(os.path.join(S, 'inst', parent[:12] + '.pkl'), 'rb')); os.remove(os.path.join(S, 'inst', parent[:12] + '.pkl'))
fqs = {d['fq'] for d in base['defs']}
for tag, gt in (('live-owner', ['seon.ref/span-candidate']), ('live-reads', sorted(names & fqs))):
    x = dict(base, id=tag, commit=tag, set='live', query=QUERY, gt=gt,
             test_calls=sorted({'seon.ref/references'} & fqs), test_names=['seon.ref-test/a-citation-ending-in-a-wildcard-is-prose'])
    pickle.dump(x, open(os.path.join(S, 'inst', tag + '.pkl'), 'wb')); print(tag, gt)
