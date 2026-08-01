---
type: issue
status: open
tags: [issue, sci, eval, agent]
---

# `sci/fork` does not isolate redefinition of an ALREADY-INTERNED var

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

## Orchestrator escalation, 2026-08-01: this is CROSS-AGENT CONTAMINATION today

Falsified live against the running `default` cluster by the
orchestrator (not a fixture):

```clojure
(let [base (se/base)                      ; the process-shared base ctx
      fork-a (se/fork)]
  (sci.core/eval-string* fork-a "(in-ns 'my.message) (def send \"CLOBBERED-BY-A\")")
  {:base-after      @(sci.core/resolve base 'my.message/send)   ; => "CLOBBERED-BY-A"
   :fresh-fork-sees @(sci.core/resolve (se/fork) 'my.message/send)}) ; => "CLOBBERED-BY-A"
```

The base ctx every agent forks from is MUTATED, so one agent redefining
any corpus name silently changes that name for every other agent in the
process and for every agent created afterwards, across clusters. Today
`acquire!` reinstalls program rows per run, which MASKS this for corpus
functions (the reinstall overwrites the poisoned Var) — that mask is
accidental, and it disappears exactly when we park a hot ctx per agent
(the session-persistence slice 1) or skip a redundant acquire.

Severity: this is the one thing in the fresh tree that lets an agent
mistake escape its own session. It blocks parked-ctx work and must be
fixed at the sci fork seam (we own the fork:
`reference-code/sci`) — the candidate is a per-fork Var copy-on-write
for names inherited from the parent, so a fork's `def` interns a NEW
Var in the fork's env rather than `bindRoot`ing the shared one.

Acceptance: the probe above shows the base unchanged and a fresh fork
seeing the original definition; a regression covers the class; parked
hot ctxs are safe to enable afterwards.
