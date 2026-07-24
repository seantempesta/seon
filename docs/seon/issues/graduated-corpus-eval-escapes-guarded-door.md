---
type: issue
status: active
severity: blocker
tags: [issue, runtime, agent]
---

# Graduated corpus source compiles with host eval, escaping the guarded door

## Evidence (containment audit 2026-07-23, verified)

Graduated corpus source is compiled with host `clojure.core/eval` and
installed behind an SCI var — its body has native JVM reach and is
invisible to interpreter-step accounting. Stored agent definitions and
graduation tests likewise execute outside the door/eval pool. Full
citations: research/sci-containment-surface-audit-2026-07-23.md
(Critical #1, High #3).

## Law violated

"Every sci invocation passes the one guarded door" (agent-runtime.md
:179-211); every JVM eval enters the bounded pool (:110-113). Sci
containment is mistake-catching, not security (processes are the
boundary) — but graduation currently removes even mistake-catching.

## Owner decision needed (MORNING ITEM, recommendation)

Graduation semantics: (a) graduated fns stay SCI-interpreted (uniform
door, slower), (b) graduated fns compile natively BUT only after the
P4/R33 pure-call-graph admission proves them door-equivalent
(recommended: graduation = proven-pure compilation, the door's exit
exam), or (c) status quo with an explicit documented trust ruling.
