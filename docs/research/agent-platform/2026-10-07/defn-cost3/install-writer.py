import json,sys,subprocess

def form(source,name):
    start=min(i for i in (source.find('(defn '+name+'\n'),source.find('(defn- '+name+'\n')) if i>=0)
    depth=0; string=False; escape=False; comment=False
    for i in range(start,len(source)):
        c=source[i]
        if comment:
            if c=='\n': comment=False
            continue
        if string:
            if escape: escape=False
            elif c=='\\': escape=True
            elif c=='"': string=False
            continue
        if c==';': comment=True
        elif c=='"': string=True
        elif c=='(': depth+=1
        elif c==')':
            depth-=1
            if depth==0: return source[start:i+1]
    raise ValueError(name)

version=sys.argv[1]; output=sys.argv[2]
files={'src/seon/db.clj':['write-entity-schemas','write-map-error','write-entity-value','write-entity-error','write-owned-values-error','write-attribute-plans','comparable-component','write-report-error','stored-value-check']}
code=[]; arming=[]
for path,names in files.items():
    source=open(path).read() if version=='change' else subprocess.check_output(['git','show',version+':'+path],text=True)
    ns='seon.db'
    code.append("(in-ns '"+ns+")")
    definitions=[]
    for name in names:
        definition=form(source,name)
        definitions.append(definition)
        arming.append("(in-ns '"+ns+")")
        arming.append("""(let [contract (:malli/schema (first (filter map? (drop 2 'DEFINITION))))
 database (seon.db/db (seon.cluster.boot/connection "default"))]
 (when contract
  (seon.instrument/apply! (assoc (seon.config/effective database "default")
   :seon.schema/projection
   (seon.schema/projection-with-function-contract (seon.db/carried-projection database)
    'SYMBOL contract {:seon.schema.admission/source :platform})
   :seon.instrument/changed-identities #{[:seon.fn/sym 'SYMBOL]}))))""".replace('DEFINITION',definition).replace('SYMBOL',ns+'/'+name))
    code.append("(do " + "\n".join(definitions) + ")")
code.extend(arming)
# The JVM reader accepts the cljc conditional through read-cond allow.
request=json.dumps({'name':'eval_clj','arguments':{'cluster':'default','agent':'defn-cost3-host','mode':'jvm','code':'\n'.join(code)}})
open(output,'w').write('(seon.mcp/call (str '+' '.join(json.dumps(request[i:i+12000]) for i in range(0,len(request),12000))+'))')
