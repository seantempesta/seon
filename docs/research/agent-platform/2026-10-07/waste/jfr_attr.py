# Attribute JFR allocation weight / execution samples to Seon frames.
# usage: python3 jfr_attr.py FILE.jfr [alloc|cpu] [first|all]
import json, subprocess, sys, re, collections
f, kind = sys.argv[1], sys.argv[2]
mode = sys.argv[3] if len(sys.argv) > 3 else "first"
ev = "jdk.ObjectAllocationSample" if kind == "alloc" else "jdk.ExecutionSample"
out = subprocess.run(["jfr", "print", "--json", "--stack-depth", "96", "--events", ev, f], capture_output=True, text=True).stdout
data = json.loads(out)
pat = re.compile(r"^(seon|my)[./]")
def fname(fr):
    m = fr["method"]; t = m["type"]["name"]
    return t
tot = 0; first = collections.Counter(); incl = collections.Counter(); top = collections.Counter()
for e in data["recording"]["events"]:
    v = e["values"]
    w = v.get("weight", 1) if kind == "alloc" else 1
    tot += w
    frames = (v.get("stackTrace") or {}).get("frames", [])
    names = [fname(fr) for fr in frames]
    if any("jvm_request" in n for n in names) and "--keep-jvm" not in sys.argv: tot -= w; continue
    if names: top[names[0] + "." + frames[0]["method"]["name"]] += w
    seen = set(); got = False
    for n in names:
        if pat.match(n):
            # clojure fn class: seon.print$foo$fn__123 -> seon.print/foo
            base = n.replace("/", ".").split("$fn__")[0]
            parts = base.split("$")
            sym = parts[0] + "/" + parts[1].replace("_", "-") if len(parts) > 1 else base
            if not got: first[sym] += w; got = True
            if sym not in seen: incl[sym] += w; seen.add(sym)
    if not got: first["<no seon frame>"] += w
unit = 1e6 if kind == "alloc" else 1
print(f"total {tot/unit:.1f} {'MB' if kind=='alloc' else 'samples'}")
print("== innermost seon frame (self) ==")
for k, w in first.most_common(40): print(f"{w/unit:10.1f}  {k}")
print("== inclusive seon frame ==")
for k, w in incl.most_common(40): print(f"{w/unit:10.1f}  {k}")
print("== top frame ==")
for k, w in top.most_common(15): print(f"{w/unit:10.1f}  {k}")
