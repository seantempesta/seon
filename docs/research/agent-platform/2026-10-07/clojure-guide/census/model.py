"""Build the function table and reference index for src (+test, script, resources). Pickled to model.pkl."""
import sys, os, re, pickle, subprocess; sys.path.insert(0,'.')
import clj
root='/Users/sean/src/seon'
SRC=clj.src_files(root,'src')
TEST=clj.src_files(root,'test')
SCRIPT=clj.src_files(root,'script')
RESCLJ=clj.src_files(root,'resources')

def rel(p): return os.path.relpath(p,root)

QSYM=re.compile(r'([A-Za-z][\w.\-]*)/([A-Za-z*+!?<>=_\-][\w\-*?!<>=+\']*)')

def raw_files():
    out=[]
    for d in ['resources','bin','script','docs','.agents','dev-resources','config','env','deps.edn','bb.edn','tests.edn','AGENTS.md']:
        p=os.path.join(root,d)
        if os.path.isfile(p): out.append(p)
        else:
            for dd,_,fs in os.walk(p):
                for f in fs:
                    if f.endswith(('.edn','.clj','.cljc','.md','.sh','.json','.js','.css','.html')) or d=='bin':
                        out.append(os.path.join(dd,f))
    return out

class Fn: pass

def build():
    parsed={}
    fns=[]
    for p in SRC+TEST+SCRIPT+RESCLJ:
        parsed[p]=clj.load(p)
    for p in SRC:
        P=parsed[p]; ns=clj.ns_of(P)
        for kind,name,node in clj.top_defs(P):
            f=Fn(); f.kind=kind; f.name=name; f.ns=ns; f.file=rel(p); f.l0=node.l0; f.l1=node.l1; f.node=node
            fns.append(f)
    return parsed,fns

if __name__=='__main__':
    parsed,fns=build()
    print(len(fns))
