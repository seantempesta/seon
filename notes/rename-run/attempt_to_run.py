#!/usr/bin/env python3
"""attempt -> run, over the tracked files of the cwd repo (not reference-code/, notes/).
Usage: attempt_to_run.py [--dry]   Protected, left as written: owner quotes, a quoted Jolt message, and image file names."""
import os, re, subprocess, sys

dry = '--dry' in sys.argv
files = subprocess.run(['git', 'ls-files'], capture_output=True, text=True).stdout.split('\n')

# (task ids containing attempt are renamed too: the owner said rename everywhere; names below is no longer used)
names = set()
for f in files:
    if f.startswith('resources/seon/tasks/') and f.endswith('.edn'):
        t = open(f).read()
        for m in re.finditer(r'(?::seon\.task/name|:name) "([^"]*attempt[^"]*)"', t):
            names.add(m.group(1))
names = sorted(names, key=len, reverse=True)
ID = re.compile(r'(?<![\w.-])((?:seon|my|clojure|jolt)[\w.]*(?<!-test)/(?:' + '|'.join(map(re.escape, names)) + r'))(?![\w-])') if names else None
NAMEQ = re.compile(r'((?::seon\.task/name|:name) )"(' + '|'.join(map(re.escape, names)) + r')"') if names else None
OWNER = re.compile(r'([Oo]wner,? [^"]{0,40}?)(\\"(?:[^"\\]|\\[^"])*\\"|"[^"\\]*")')
KEEP = [re.compile(r'Attempting to call unbound fn'), re.compile(r'zoom-a8-attempt-calc-neg\.jpg')]

RULES = [
    (r'Reattempt', 'Rerun'), (r'reattempt', 'rerun'),
    (r'\bAn attempt', 'A run'), (r'\ban attempt', 'a run'),
    (r'\bAn-attempt', 'A-run'), (r'(?<![\w])an-attempt', 'a-run'),
    (r'Attempts', 'Runs'), (r'attempts', 'runs'), (r'Attempt', 'Run'), (r'attempt', 'run'),
]
RULES = [(re.compile(a), b) for a, b in RULES]

def convert(text, path):
    masks = []
    def mask(s):
        masks.append(s); return '\x00%d\x00' % (len(masks) - 1)
    def line_fn(line):
        if 'ttempt' not in line: return line
        if path == 'AGENTS.md' and line.startswith('| '): return line
        for k in KEEP: line = k.sub(lambda m: mask(m.group(0)), line)
        if path.endswith(('.md', '.edn')): line = OWNER.sub(lambda m: m.group(1) + mask(m.group(2)), line)
        for r, b in RULES: line = r.sub(b, line)
        for i, t in enumerate(masks): line = line.replace('\x00%d\x00' % i, t)
        return line
    return '\n'.join(line_fn(l) for l in text.split('\n'))

changed = {}
for f in files:
    if not f or f.startswith(('reference-code/', 'notes/')) or os.path.islink(f): continue
    if f == 'test/seon/docs_test.clj': continue  # names the old word on purpose (no-text-uses-an-old-word)
    if f.endswith(('.zst', '.jpg', '.png', '.ico', '.woff2', '.diff')): continue
    try: s = open(f).read()
    except Exception: continue
    t = convert(s, f)
    if t != s:
        changed[f] = sum(1 for a, b in zip(s.split('\n'), t.split('\n')) if a != b)
        if not dry: open(f, 'w').write(t)
for f, n in sorted(changed.items(), key=lambda x: -x[1])[:60]: print(f'{n:5} {f}')
print('files', len(changed), 'lines', sum(changed.values()), '| protected task ids:', len(names))
