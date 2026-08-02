---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, eval, agent, database]
---

# Prove SCI built-ins are deterministic before replaying session forms

## Problem

Session restore equates "no workload leaf and no observed host interop" with a
pure, replayable form. SCI built-ins are excluded from the fail-closed missing
call check, however, and built-in does not imply deterministic or effect-free.
An unstorable definition can therefore be persisted as source and produce a
different value when a cold cluster re-evaluates it.

## Evidence

- `src/seon/sci/eval.clj:517-535` excludes every Var carrying
  `:sci/built-in` from `unproven-called-vars`.
- `src/seon/cluster/loop.cljc:296-322` fails closed only for Vars left in that
  set, and treats a missing row reached through `referenced-vars` as having no
  workload.
- `src/seon/cluster/loop.cljc:339-393` therefore retains the defining source
  whenever the form has no observed host interop and reaches no recorded
  workload leaf.
- `src/seon/sci/eval.clj:1091-1098` re-evaluates every retained source on cold
  install. Its docstring at `:1045-1051` calls the predicate a "purity proof",
  which is stronger than the implemented evidence.
- The independent disposable-database probe
  `tmp/adversarial-wave2-probe.clj` evaluated
  `(def replay-symbol (let [x (gensym "x")] (fn [] x)))`. The unstorable
  closure was recorded with its source and an empty unproven-call set. The
  live function returned `x238416`; after committing that session row and
  constructing a fresh cluster context, the restored function returned
  `x238423`. `:replay-equal?` was false.
- The same probe admitted
  `(def replay-print (do (println "SIDE-EFFECT") (fn [] 1)))` for source
  replay with an empty unproven-call set. This is a second effectful SCI
  built-in that the predicate calls pure.

## Owner

The session-source replay predicate shared by `seon.sci.eval` and
`seon.cluster.loop`.

## Acceptance

- Source replay requires a computed proof that every reachable SCI built-in
  and program-graph function is deterministic and effect-free; absence of a
  row never proves purity.
- The `gensym` closure and output-producing closure are durably marked
  `:seon.code.def/unrestorable`, or restored through a faithful stored value;
  neither defining form is re-executed on cold install.
- A recurring fresh-context regression compares the live and restored
  function result and observes any defining-form output.

## Resolved 2026-08-01

Resolved by `8e1ea52c2` and maintained SCI commit `a27e2c0`. SCI now reports
the built-in calls that actually executed during an armed evaluation; the
session-image owner classifies the source-grounded closed nondeterministic and
effectful sets allowed by ruling #32 and refuses source replay for either.
`test/seon/sci/session_image_test.clj` proves that the `gensym` closure and the
printing closure are stated unrestorable after a fresh context, while a
faithfully storable random value restores as data and is never re-executed.
