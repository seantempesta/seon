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

FILE_MOVES = {'resources/seon/tasks/seon.entrance.edn': 'resources/seon/tasks/seon.server.edn',
              'resources/seon/tasks/seon.fences.edn': 'resources/seon/tasks/seon.audit.edn'}

# (regex, replacement) over every file but HOLD; a line of an owner quote is left as written
GLOBAL = [
    (r'\bseon\.entrance(?=[/.])', 'seon.server'),
    (r'\bseon\.fences(?=[/.])', 'seon.audit'),
    (r'a-door-defect-answers-that-request-alone', 'a-server-defect-answers-that-request-alone'),
    (r'the-dragon-gate-sees-fences', 'the-failure-point-gate-sees-guards'),
    (r'merge-trains-off-as-decided', 'octopus-merges-off-as-decided'),
    (r'a-train-is-proven-by-one-combined-run', 'an-octopus-merge-is-proven-by-one-combined-run'),
    (r'with-merge-census-cost', 'with-merge-mg-check-cost'),
    (r'a-refusal-is-the-one-error-map', 'an-invalid-is-the-one-error-map'),
    (r'an-observers-defect-is-a-problem-not-a-refusal', 'an-observers-defect-is-a-problem-not-an-invalid'),
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
    ('test/seon/source_test.clj', "the next agent's harvest read", "the next agent's stage read"),
]

changed = {}
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
        lines.append(line)
    t = '\n'.join(lines)
    for g, a, b in REPLACE:
        if g == f: t = t.replace(a, b, 1) if a in t else t
    if t != s: put(f, t)

for a, b in FILE_MOVES.items():
    if os.path.exists(a):
        changed[b] = changed.get(b, 0)
        if not dry: subprocess.run(['git', 'mv', a, b], check=True)
for f, n in sorted(changed.items()): print(f'{n:3} {f}')
print('files', len(changed))
