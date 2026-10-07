# For stacks whose innermost seon frame is TARGET, attribute weight to the first outward
# seon frame outside seon.db / seon.schema / seon.instrument / seon.call_preparation.
import json, subprocess, sys, collections
f, kind, target = sys.argv[1], sys.argv[2], sys.argv[3]
ev = "jdk.ObjectAllocationSample" if kind == "alloc" else "jdk.ExecutionSample"
data = json.loads(subprocess.run(["jfr","print","--json","--stack-depth","160","--events",ev,f],capture_output=True,text=True).stdout)
skip = ("seon.db/","seon.schema","seon.instrument/","seon.call_preparation/","seon.cache/")
c = collections.Counter(); tot=0
for e in data["recording"]["events"]:
    v=e["values"]; w=v.get("weight",1) if kind=="alloc" else 1
    syms=[]
    for fr in (v.get("stackTrace") or {}).get("frames",[]):
        n=fr["method"]["type"]["name"].replace("/",".")
        if n.startswith(("seon.","my.")):
            parts=n.split("$fn__")[0].split("$")
            if len(parts)>1: syms.append(parts[0].replace(".","/",0)+"/"+parts[1].replace("_","-"))
    syms=[s if "/" in s.split(".")[-1] else s for s in syms]
    syms=[s.replace("seon/","seon.") for s in syms]
    if not syms or not syms[0].endswith(target): continue
    tot+=w
    caller=next((s for s in syms[1:] if not s.startswith(skip)),"<none>")
    c[caller]+=w
u=1e6 if kind=="alloc" else 1
print(f"{target}: {tot/u:.1f}")
for k,w in c.most_common(20): print(f"{w/u:10.1f} {k}")
