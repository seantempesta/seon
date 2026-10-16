import re, json, time, os, sys
import numpy as np
EDN='/Users/sean/src/seon/docs/research/agent-platform/2026-10-16/context-patterns.edn'
TASK=("Task seon.ref/wildcard-citations-are-prose. A citation whose name part ends in * (such as :seon.render.unknown/*) names a family, "
"not a name, so it must be read as prose and not as a reference. Owner: seon.ref/span-candidate. "
"Done when seon.ref-test/a-citation-ending-in-a-wildcard-is-prose is green. The agent works at a REPL: it reads source, defines a fix with defn, and writes the deftest proof.")
def parse():
    t=open(EDN).read()
    t=t[t.index(':forms'):]
    out=[]
    for m in re.finditer(r'\{:attempt "([^"]+)",\s*:turn (\d+),\s*:ordinal (\d+),\s*:class (:[a-z-]+),\s*:reply-tokens (\d+),\s*:source\s*("(?:[^"\\]|\\.)*"),\s*:elided-in-history (true|false)',t):
        out.append(dict(attempt=m[1],turn=int(m[2]),ordinal=int(m[3]),cls=m[4][1:].replace('other-error','other'),rt=int(m[5]),src=json.loads(m[6]),elided=m[7]=='true'))
    return out
def history(forms,i,k):
    a=forms[i]['attempt']; prev=[f for f in forms[:i] if f['attempt']==a][-k:] if k else []
    lines=[]
    for f in prev:
        s=f['src'] if len(f['src'])<400 else f['src'][:400]+' ...'
        lines.append(f"> {s}\n  reply: {f['rt']} tokens"+(" (later elided from history)" if f['elided'] else ""))
    return "\n".join(lines)
def state(forms,i,k):
    h=history(forms,i,k); f=forms[i]; s=f['src'] if len(f['src'])<600 else f['src'][:600]+' ...'
    return f"{TASK}\n\nForms the agent already ran (oldest first):\n{h or '(none yet)'}\n\nThe agent's latest form:\n> {s}"
