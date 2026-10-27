#!/usr/bin/env python3
"""The remaining AGENTS section 7a words, after step1.py and attempt_to_run.py. Run from the repository root.
Usage: vocab_pass.py [--dry].  Idempotent: text already renamed is untouched.
Scope: tracked files outside reference-code/ and notes/.  Left as written: owner quotes, AGENTS.md and
docs/vocabulary.md (their tables name the old words), the two rename plans (seon.docs.edn, seon.db.edn) and the
decisions page's quoted titles.  What it changes, by kind:
  - task files and ids: seon.entrance -> seon.server, seon.fences -> seon.audit, and the ids named with an old word
  - FENCES (the 2026-10-20 audit's name) -> GUARDS
  - stray prose and comments (the REPLACE table)"""
import os, re, subprocess, sys

dry = '--dry' in sys.argv
files = [f for f in subprocess.run(['git', 'ls-files'], capture_output=True, text=True).stdout.split('\n')
         if f and not f.startswith(('reference-code/', 'notes/')) and not os.path.islink(f)
         and not f.endswith(('.zst', '.jpg', '.png', '.ico', '.woff2', '.diff'))]

# files where the old words are the subject: never rewritten
HOLD = {'AGENTS.md', 'docs/vocabulary.md', 'resources/seon/tasks/seon.docs.edn', 'resources/seon/tasks/seon.db.edn',
        'test/seon/docs_test.clj'}

FILE_MOVES = {'resources/seon/tasks/seon.entrance.edn': 'resources/seon/tasks/seon.server.edn'}

# seon.fences.edn (seon.audit.edn on this branch before the ruling) is dissolved: a task lives in its owning
# namespace's file (AGENTS section 3).  Entry name -> owner; an entry not listed stops the pass.
DISSOLVE = ['resources/seon/tasks/seon.fences.edn', 'resources/seon/tasks/seon.audit.edn']
TASKS = 'resources/seon/tasks/'
OWNER_OF = {
    'write-back-answers-what-happened': 'seon.fx', 'a-transient-model-failure-parks': 'seon.fx',
    'octopus-merges-off-as-decided': 'seon.reconcile', 'merge-trains-off-as-decided': 'seon.reconcile',
    'one-test-bound-derived-and-named': 'seon.agent', 'fork-error-names-the-threads': 'seon.agent',
    'records-keep-the-exact-value': 'seon.repl',
    'root-boots-without-a-synthetic-program': 'seon.root', 'the-compute-executor-is-declared': 'seon.root',
    'health-is-answered-and-failover-recorded': 'seon.root', 'a-stall-is-past-the-requests-own-bound': 'seon.root',
    'event-loss-is-counted': 'seon.root',
    'leases-and-store-size-are-declared-bounds': 'seon.db', 'a-run-ends-on-a-defect-too': 'seon.e2e',
    'checks-await-facts-and-assert-exact-numbers': 'seon.check',
    'the-failure-point-gate-sees-guards': 'seon.check', 'the-dragon-gate-sees-fences': 'seon.check',
    'cost-tolerances-are-derived': 'seon.cost', 'no-clip-in-renders': 'seon.render'}

def dissolve():
    src = next((f for f in DISSOLVE if os.path.exists(f)), None)
    if not src: return {}
    text = open(src).read()
    entries = re.split(r'\n (?=\{:seon\.task/name )', text.strip()[1:-1].strip())
    moved = {}
    for e in entries:
        name = re.match(r'\{:seon\.task/name "([^"]*)"', e).group(1)
        owner = OWNER_OF.get(name)
        if not owner: sys.exit('vocab_pass: no owner for task ' + name + ' of ' + src + ' (add it to OWNER_OF)')
        moved[name] = (owner, e)
    if not dry:
        for owner in sorted({o for o, _ in moved.values()}):
            f = TASKS + owner + '.edn'
            t = open(f).read().rstrip()
            assert t.endswith(']'), f
            add = ''.join('\n ' + e for o, e in moved.values() if o == owner and e not in t)
            open(f, 'w').write(t[:-1].rstrip() + add + ']\n')
        subprocess.run(['git', 'rm', '-q', '-f', src], check=True)
    return {n: o for n, (o, _) in moved.items()}

# (regex, replacement) over every file but HOLD; a line of an owner quote is left as written
GLOBAL = [
    (r'\bseon\.entrance(?=[/.])', 'seon.server'),
    (r'a-door-defect-answers-that-request-alone', 'a-server-defect-answers-that-request-alone'),
    (r'the-dragon-gate-sees-fences', 'the-failure-point-gate-sees-guards'),
    (r'merge-trains-off-as-decided', 'octopus-merges-off-as-decided'),
    (r'a-train-is-proven-by-one-combined-run', 'an-octopus-merge-is-proven-by-one-combined-run'),
    (r'with-merge-census-cost', 'with-merge-mg-check-cost'),
    (r'a-refusal-is-the-one-error-map', 'an-error-is-the-one-error-map'),
    (r'an-observers-defect-is-a-problem-not-a-refusal', 'an-observers-defect-is-a-problem-not-invalid-input'),
    (r'an-invalid-is-the-one-error-map', 'an-error-is-the-one-error-map'),  # earlier target of the id above
    (r'an-observers-defect-is-a-problem-not-an-invalid', 'an-observers-defect-is-a-problem-not-invalid-input'),
    (r'\bFENCES\b', 'GUARDS'),
]

# (file, old, new): exact strings, once each
REPLACE = [
    ('BAGGAGE.md', '``bin/lane`` claims', '``bin/worktree`` claims'),
    ('test/seon/context_test.clj', 'a contract refusal is data', 'a contract invalid is data'),
    ('test/seon/context_test.clj', 'the refusal names the deftest', 'the reason names the deftest'),
    ('test/seon/config_test.clj', "the scripted stand-in's script", "the scripted model's script"),
    ('test/seon/config_test.clj', 'never the stand-in', 'never the scripted model'),
    ('test/seon/fixtures.clj', 'The stand-in for a model in a loop', 'The scripted model in a loop'),
    ('test/seon/check.clj', 'no counted stand-in for kernel time', 'no counted measure of kernel time'),
    ('test/seon/reconcile_test.clj', ':seon.config.contract/guarantees', ':seon.config.contract/instrument'),
    ('test/seon/reconcile_test.clj', 'PROTO_GUARANTEES', 'PROTO_INSTRUMENT'),
    ('docs/source.md', "for an agent's harvest to read", "for an agent's stage to read"),
    ('resources/seon/tasks/seon.stage.edn', '(which harvests first)', '(which stages first)'),
    ('resources/seon/tasks/seon.e2e.edn', 'reads a refusal as a reuse', 'reads a refused spawn as a reuse'),
    ('resources/seon/tasks/seon.e2e.edn', 'two agentless refusals', 'two agentless refused spawns'),
    ('resources/seon/tasks/seon.merge.edn', '(`seon.writer` merge-refusal)', '(`seon.writer` merge-refused)'),
    ('docs/health.md', 'attention, never a refusal (', 'attention, never a reason to refuse done ('),
    ('resources/seon/schemas/seon.problems.edn', 'never a reason for a refusal', 'never a reason to refuse done'),
    ('src/seon/problems.clj', 'attention, never a refusal.', 'attention, never a reason to refuse done.'),
    ('test/seon/problems_test.clj', 'attention, never a refusal:', 'attention, never a reason to refuse done:'),
    ('docs/decisions.md', '- `seon.audit.edn`: nine entries read "Done:" yet load as tasks; move the open ones to their owning files and delete the file?\n', ''),  # settled (root, 2026-10-23): the file is dissolved into its owners
    ('docs/plan.md', '`seon.audit.edn` dissolved', 'the guards audit\'s task file dissolved'),
    ('docs/plan.md', '`seon.fences.edn` dissolved', 'the guards audit\'s task file dissolved'),
    ('resources/seon/tasks/seon.db.edn', '- task ids stay: an id is a record', '- task ids stayed at pass 2 (the store erase renamed them, task seon.docs/names-are-clojures-words): an id is a record'),
    ('test/seon/source_test.clj', "the next agent's harvest read", "the next agent's stage read"),
]

# (file, regex, replacement) over the whole file: a local named with an old word
FILE_REGEX = [('test/datahike/query_test.clj', r'(?<![\w-])refusal(?![\w-])', 'refused')]

moved = dissolve()
changed = {f: 0 for f in {TASKS + o + '.edn' for o in moved.values()}}
def put(path, text):
    changed[path] = changed.get(path, 0) + 1
    if not dry:
        open(path, 'w').write(text)

OWNER = re.compile(r'[Oo]wner,? [^"]{0,40}?"')
for f in files:
    if f in HOLD or not os.path.exists(f): continue
    try: s = open(f).read()
    except Exception: continue
    t = s
    lines = []
    for line in t.split('\n'):
        if not OWNER.search(line):
            for a, b in GLOBAL: line = re.sub(a, b, line)
            line = re.sub(r'\bseon\.(?:fences|audit)/([\w-]+)', lambda m: (OWNER_OF.get(m.group(1)) or 'seon.audit') + '/' + m.group(1), line)
        lines.append(line)
    t = '\n'.join(lines)
    for g, a, b in FILE_REGEX:
        if g == f: t = re.sub(a, b, t)
    for g, a, b in REPLACE:
        if g == f: t = t.replace(a, b, 1) if a in t else t
    if t != s: put(f, t)

for a, b in FILE_MOVES.items():
    if os.path.exists(a):
        changed[b] = changed.get(b, 0)
        if not dry: subprocess.run(['git', 'mv', a, b], check=True)
# the live checkouts' local config layers (gitignored): the key bin/ports no longer reads under its old name
wt = [l.split(' ', 1)[1] for l in subprocess.run(['git', 'worktree', 'list', '--porcelain'], capture_output=True, text=True).stdout.split('\n') if l.startswith('worktree ')]
for w in wt:
    f = w + '/config/local.edn'
    if os.path.exists(f):
        t = open(f).read()
        u = t.replace(':seon.config.root/entrance-port', ':seon.config.root/server-port').replace('SEON_ENTRANCE_PORT', 'SEON_SERVER_PORT').replace("root's entrance", "root's server")
        if u != t:
            print('local.edn', f)
            if not dry: open(f, 'w').write(u)
for f, n in sorted(changed.items()): print(f'{n:3} {f}')
print('files', len(changed))
