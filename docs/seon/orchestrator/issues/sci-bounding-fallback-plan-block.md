---
type: issue
status: open
tags: [issue, agent]
---

# SCI-bounding fallback — `my.plan.internal/plan-block` renders on the UNBOUNDED path

Severity: friction (latent blocking — the warning itself says an eventual hang
would freeze the pod). Lane: tooling (render mechanism). Found by the
post-merge acme smoke, 2026-07-02.

## Symptom

On every fresh acme boot (`logs/acme/pod.log:38`):

```
tile fn my.plan.internal/plan-block could not run under SCI bounding
(Unable to resolve symbol: db/*conn*) — rendering it on the UNBOUNDED compiled path
```

The tile still renders, but via the unbounded compiled path — a hang in that
fn would wedge the pod with no bound to cut it.

## Candidate root

`my.plan.internal`'s `:seon.ns/source` require aliases appear not to be
stored/replayed, so SCI can't resolve the `db/*conn*` alias when bounding the
tile fn. Likely a seed/scribe defect, reproducible on any fresh cluster.

## Design note (owner discussion, 2026-07-02)

The name-based routing (`agent-authored-sym?`, `src/seon/render/sci.cljs:94`)
is CORRECT and settled: `my.*` = agent-editable territory, bounded uniformly —
no provenance special-casing, since agents can redefine any `my.*` fn. The
defect is that SCI *can't* bound the seeded fn (alias resolution) and the
fallback silently downgrades to the UNBOUNDED path. Consider whether
fail-open is right at all — a `:seon/error` block on resolution failure
(fail-loud) may be the safer default than an unbounded render.

## Acceptance criteria

- Fresh `cluster reset` boots with ZERO "could not run under SCI bounding"
  warnings in the pod log.
- `my.plan.internal/plan-block` demonstrably runs under SCI bounding (live
  proof: the bounded path taken, observed in the log or an inspect eval).

Related: [[tx-feed-pump-timeouts]] (same smoke). Channel entry:
`docs/prds/agent-ctx/coordination.md` 2026-07-02.
