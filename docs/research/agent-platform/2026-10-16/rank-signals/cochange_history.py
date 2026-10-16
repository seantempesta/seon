"""One pass over git history: for every non-merge commit, the top-level src declarations its hunks fall in, using the
hunk header's enclosing definition (git xfuncname set to a column-0 `(def...` form). Writes cochange.jsonl
{commit, time, decls}. Signal 5 reads only commits strictly before an instance's parent (by first-parent ancestry time)."""
import subprocess, re, json, sys, os, tempfile
REPO = '/Users/sean/src/seon'
attr = tempfile.NamedTemporaryFile('w', suffix='.attr', delete=False); attr.write('*.clj diff=clj\n'); attr.close()
cmd = ['git', '-C', REPO, '-c', f'core.attributesFile={attr.name}', '-c', r'diff.clj.xfuncname=^(\(def.*)$',
       'log', '--no-merges', '-p', '-U0', '--format=@@C %H %ct', '--', 'src']
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, errors='replace')
out = open(sys.argv[1], 'w'); cur = None; path = None
def ns_of(path):
    return path[len('src/'):-len('.clj')].replace('/', '.').replace('_', '-')
def flush():
    if cur and cur['decls']: out.write(json.dumps({'commit': cur['h'], 'time': cur['t'], 'decls': sorted(cur['decls'])}) + '\n')
for l in p.stdout:
    if l.startswith('@@C '):
        flush(); _, h, t = l.split(); cur = {'h': h, 't': int(t), 'decls': set()}
    elif l.startswith('+++ '):
        path = l[6:].strip() if l[4:].startswith('b/') else None
    elif l.startswith('@@ ') and path and path.endswith('.clj'):
        m = re.search(r'@@ \((def[^ ]*) +(?:\^[^ ]+ +)*([^ ()\n]+)', l)
        if m: cur['decls'].add(ns_of(path) + '/' + m.group(2))
flush()
