---
type: issue
status: open
severity: blocker
tags: [issue, agent, sci, runtime, class/p1, wave/evolving-session-phases]
---

# Generated turn fork omits the agent-scoped environment

## Problem

A fresh generated opening evaluates `(help)` in a turn fork whose environment
has no `:seon.cluster.agent/id`. Call preparation therefore returns
`:seon.call-preparation/unavailable` instead of the live situation, and the
opening cannot advance beyond form zero.

## Evidence

Live isolated-root proof on 2026-08-12, commit `16f022fc9`: both root and a
fresh `explorer` agent settled this result for `(help)`:

```clojure
{:seon.error/kind :seon.call-preparation/unavailable
 :seon.error/message
 "Cannot call seon.bootstrap/situation: :seon.cluster.agent/id is unavailable. This call's environment carries no agent id; pass one explicitly."}
```

`src/seon/cluster/loop.clj:1321-1335` passes the agent id to
`seon.sci.eval/fork-for-turn`, but `src/seon/sci/eval.clj:1441-1464` forks the
base and restores definitions without scoping the carried environment onto
the returned fork.

A process-local REPL wrapper that replaced the fork's environment state with
`(env/scope (env/of ctx) {:seon.cluster.agent/id agent-id})` made the next
fresh agent's `(help)` return the correct live situation. No source was edited.

After this proof, a foreign lane added an uncommitted working-tree change that
scopes the environment at `seon.sci.eval/evaluate` and a focused assertion in
`test/seon/sci/eval_test.clj`. This issue remains open until that lane lands
and the reset-boundary opening proof succeeds; this research lane did not edit,
stage, or verify the foreign change.

## Owner

`seon.sci.eval/evaluate` owns the form-scoped environment on the supplied turn
fork. The environment scope must ride the evaluation context; the loop must
not create a parallel carrier.

## Acceptance

- A fresh generated opening's `(help)` returns the selected agent's situation.
- Call preparation reads the agent id from the fork's one carried environment.
- A second agent in the same cluster receives its own id, proving the scope is
  per turn rather than a mutation of the cluster base.
- Restart preserves the same behavior without a warm-process patch.
