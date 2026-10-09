"""Reproduce the honest audit's tracked-line and commit-path census."""
import collections, json, subprocess, sys

def git(*args):
    return subprocess.check_output(['git', *args]).decode()

end = sys.argv[1] if len(sys.argv) > 1 else 'HEAD'
start = '9e0f1cba8'
counts = {}
for rev in (start, end):
    counts[rev] = {}
    for root in ('src', 'test', 'script'):
        files = git('ls-tree', '-r', '--name-only', rev, '--', root).splitlines()
        blobs = [subprocess.check_output(['git', 'show', f'{rev}:{p}']) for p in files]
        counts[rev][root] = {'files': len(files), 'lines': sum(b.count(b'\n') for b in blobs),
                             'nonblank': sum(sum(bool(x.strip()) for x in b.splitlines()) for b in blobs)}
commits = git('rev-list', '--reverse', f'{start}^..{end}').splitlines()
rows = []
for c in commits:
    paths = git('diff-tree', '--no-commit-id', '--name-only', '-r', c).splitlines()
    taskdoc = lambda p: p.startswith(('docs/', 'resources/seon/tasks/', '.agents/skills/', '.claude/')) or p in ('AGENTS.md','CLAUDE.md')
    code = any(p.startswith(('src/', 'test/', 'script/', 'bin/', 'resources/seon/schemas/', 'reference-code/')) or p == 'deps.edn' for p in paths)
    category = 'code-or-tests' if code else 'tasks-docs-only' if paths and all(map(taskdoc, paths)) else 'other'
    rows.append({'sha': c, 'subject': git('show','-s','--format=%s',c).strip(), 'category': category, 'paths': paths,
                 'branch_evidence': 'Branch-evidence:' in git('show','-s','--format=%B',c)})
print(json.dumps({'start_inclusive':start,'end':end,'line_counts':counts,'commit_count':len(rows),
                  'categories':dict(collections.Counter(r['category'] for r in rows)), 'commits':rows},indent=2))
