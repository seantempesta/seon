---
type: issue
status: closed
tags: [issue, sci, eval, agent]
---

# CLOSED not-a-bug: shared Vars across forks are the INTENDED model

Measured 2026-08-01 (`tmp/sci-session/probe5_isolation.clj`, and
`probe4_surface.clj` reproducing it through the real door):

```
parent ctx:  (ns agent1) (def x :parent)
fork of it:  (ns agent1) (def x :fork)
parent x  => :fork          ; the parent's value changed
same Var object in both ctxs => true
```

`sci/fork` copies the env MAP into a new atom
(`reference-code/sci/src/sci/core.cljc:318-323`) but the `sci.lang.Var`
objects are shared. A `def` of a name the parent already interned
`bindRoot`s that shared Var (`sci/impl/evaluator.cljc:25-47`), so the
write is visible in the parent and in every sibling fork. Only a name
the fork interns FIRST is isolated (confirmed in the same probe).

This is the same class as the leak recorded in
`research/sci-precomputed-analysis-2026-07-31.md` §1.3(b), but from the
other direction: that one leaked at ANALYSIS time from the base; this one
leaks at EVAL time from any fork whose name already exists upstream.

Why it matters now: it is not a live defect today (each run forks the
base, which contains no agent-authored vars, and the fork is discarded at
run end), but it silently invalidates three designs currently on the
table:

- **parking a per-agent ctx** and forking it per run — the run's
  redefinitions would write through into the parked session, and two
  concurrent runs of one agent would share vars;
- **snapshotting an agent's ending ctx** for a grader by forking it — the
  grader's `def`s would corrupt the graded agent's session;
- any "fork the base after installing the interpreted corpus" scheme in
  which an agent redefines a corpus function (the grader precedence
  question, `plan/grader-in-fact-space-2026-08-01.md` §1) — that write
  would hit every other agent in the process.

A deep snapshot that re-interns fresh Vars with the parent's current
values is cheap (~6 us for a small namespace) but is NOT equivalent:
interpreted fns resolve their free names through the Var objects captured
at creation, so functions created before the snapshot keep reading the
ORIGINAL vars (measured in the same probe). Var identity is the
redefinition channel, exactly as in Clojure.

Acceptance: either (a) the design never forks a ctx containing
agent-authored vars — the branch point is replay into a fresh ctx, and
that constraint is written where the fork happens; or (b) sci gains a
genuine deep fork in our vendored fork with a test proving parent
isolation for pre-existing vars AND correct free-name resolution for
pre-existing interpreted fns. Whichever is chosen, a regression pins the
parent-isolation behavior so the next design does not rediscover it.


## Owner ruling, 2026-08-01: this is the design, not a defect

The orchestrator escalated the shared-Var behavior as cross-agent
contamination. **The owner overruled it:** an agent making a GLOBAL
change is intended, and the benefit agent A creates must be
IMMEDIATELY AVAILABLE to agent B. One live program graph, shared by
every agent in the cluster — the same reason the corpus is database
facts rather than per-agent copies.

The mechanics recorded above are still accurate and worth keeping:
`sci/fork` copies the env map but shares `sci.lang.Var` objects, so a
`def` of an existing name `bindRoot`s through to the parent and every
sibling. That IS the propagation mechanism for shared improvement.

What remains open is not isolation but OWNERSHIP: agents stay in their
own namespace lanes, and the owner is open to ENFORCING that (a write
to a namespace you do not own is refused). `:seon.cluster.agent/namespace`
is already unique per agent, so the ownership fact exists; the
enforcement seam does not. Tracked as its own question in the grader /
program-graph design, not as a sci defect.

Consequence for parked hot ctxs: no blocker. Shared Vars mean a parked
ctx sees other agents' improvements without any refresh, which is the
behavior we want.
