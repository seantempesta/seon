---
type: research
status: blocked
created: 2026-09-21
tags: [error-model, kind-retirement, ops-effects]
---

# Kind sweep — ops/effects landing note

## Result

This lane stopped before a production commit at a held cross-family schema
boundary. The assigned family is not kind-free: the source census at HEAD
`0a63b34594` has 199 matching lines in 19 assigned source files for
`:seon.error/kind|seon.error/class|error/error?`. No claim of completion is
made.

`src/seon/operator/state.clj` was dirty before the lane reached it and remains
held/owed. The lane did not edit the default cluster, create a worktree, run a
cold gate, or edit any foreign dirty path.

## Exact stop evidence

The first coherent conversion targeted `seon.background/poll`, one of the
cold-gate wrapper refusals. Its local invalid-result and missing-result
producers were converted in a temporary draft to the pure
`seon.error.refusal/diagnostic` constructor, with exact output facets and
distinct substantive observations.

The required foreground command was:

```text
bin/test-fast --paths src/seon/background.clj resources/seon/schemas/my.background.edn -- seon.background-test
```

It launched one JVM and stopped during snapshot admission, before executing a
test. Run id `94313115737c` refused
`:my.background/invalid-result-error`: "A boolean marker alone cannot define an
error facet." The same owner resource also declares
`:my.background/invalid-call-error` using only the boolean
`:my.background/invalid-call` marker. Its producer is
`src/my/background.clj:10-25`, a held file belonging to the already-landed
my-protocol sweep; it currently supplies the authored form only inside
`:seon.error/diagnostic-evidence`, not as a required facet member. Converting
the shared resource without that producer therefore makes the held producer's
returned facet invalid. The draft was removed completely after the refusal;
the assigned production and schema files are unchanged by this lane.

This is the binding held-path boundary: the facet and its producer must change
in one publication, but this lane may not edit `src/my/*`. The owner should
return `src/my/background.clj` and `resources/seon/schemas/my.background.edn`
to one lane, adding a substantive required authored-form observation to the
invalid-call facet, before ops/effects resumes the background slice.

## Cold-gate evidence and handoffs

The starting gate evidence was read from
`tmp/orchestrator/gate-step1-2026-09-21.log` and
`tmp/orchestrator/gate-step1-reds.txt`. The named owned wrapper refusals remain
owed for `seon.problems/problems`, `seon.ai/complete`, `seon.plan/plan!`,
`seon.background/poll`, and `seon.ai/request-body`. The schedule regression at
`test/seon/schedule_test.clj:383` still queries the retired stored kind datom;
the maintenance failure at `src/seon/maintenance.clj:419` remains unconverted.

Pass-through producers owned by `seon.db`, `seon.cluster.message`,
`seon.turn`, and `seon.cluster.reply` remain handoffs to their assigned sweeps.
No new facets were landed.

## Verification and proof owed

The temporary background draft passed the direct namespace load probe before
the canonical schema authority refused it. The fast run has no tally because
snapshot admission failed. The family-wide load command, zero-kind census,
and path-limited fast namespaces remain owed after the held schema/producer
pair is repaired.

The orchestrator ultimately owes the cold command over every changed
ops/effects source, schema, and test namespace, followed by
`bin/test --platform`. No cold or platform proof was run here.
