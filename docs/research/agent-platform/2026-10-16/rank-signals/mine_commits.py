"""Pick candidate commits: no merges, change src/**/*.clj and test/**/*.clj. Writes candidates.jsonl."""
import subprocess, json, sys
REPO='/Users/sean/src/seon'
out=subprocess.run(['git','-C',REPO,'log','--no-merges','--format=@@%H %P %ct','--name-status','--','src','test','resources/seon/tasks'],capture_output=True,text=True).stdout
cs=[];cur=None
for l in out.splitlines():
    if l.startswith('@@'):
        parts=l[2:].split(); cur={'h':parts[0],'parents':parts[1:-1],'t':int(parts[-1]),'f':[]}; cs.append(cur)
    elif l and cur: cur['f'].append(l.split('\t'))
with open(sys.argv[1],'w') as w:
    for c in cs:
        if len(c['parents'])!=1: continue
        src=[f[-1] for f in c['f'] if f[-1].startswith('src/') and f[-1].endswith('.clj')]
        tst=[f[-1] for f in c['f'] if f[-1].startswith('test/') and f[-1].endswith('.clj')]
        tk=[f[-1] for f in c['f'] if f[-1].startswith('resources/seon/tasks/') and f[-1].endswith('.edn')]
        if src and tst:
            w.write(json.dumps({'commit':c['h'],'parent':c['parents'][0],'time':c['t'],'src':src,'test':tst,'taskfiles':tk})+'\n')
