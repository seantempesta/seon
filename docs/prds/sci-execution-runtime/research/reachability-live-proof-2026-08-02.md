---
type: research
status: complete
tags: [sci, runtime, proof]
---

# Public-Var reachability live proof — 2026-08-02

## Boundary

This proves the live-boot compatibility of commit `0c3a3d535`, which changes
compiled first-party SCI acquisition from `ns-interns` to `ns-publics`. The
proof is intentionally a real operator boot and agent turn, not the fixture
population used by `seon.custody-stability-test`.

Ruling #20 is unchanged: every public function in the cluster program graph
remains callable. The graph already computes `:seon.fn/private?`, and its
documentation, namespace render, and bootstrap projections already exclude
private implementation Vars. This change makes the installation seam agree
with those projections.

## Recurring proof

The focused command initially waited for the protected full-suite owner to
release `tmp/bin-test.lock`; no second runner or bypass was used. It then
completed with:

```text
Ran 42 tests containing 173 assertions.
0 failures, 0 errors.
```

The new census derives core namespace membership from database transaction
provenance, compares every loaded namespace's installed SCI intern names with
its host `ns-publics`, and separately proves that private host Vars were present
in the census input.

## Fresh operator boot and bootstrap

The isolated root was
`tmp/reachability-phase2-live-root-0c3a3d535`. A brand-new root correctly
refused its first `start` because no `current-src` branch existed. Running
`bin/seon --root tmp/reachability-phase2-live-root-0c3a3d535 init` published:

```text
commit 6a6f9e4b-b6be-50a6-80e4-b851b8b8797c
digest e06fb003ea2e5ec7eaa2510d3ee3a77455207b3a93f578783641f9f3b8a34d8d
```

The subsequent `start reachability-phase2` reached readiness in 1,449 ms at
`http://127.0.0.1:7846`. The fresh branch contained root agent `root`, assigned
namespace `my.agents.root`, and a closed `bootstrap:root` run with no run error.
The ordinary root-agent turn below is the independent behavioral proof that
bootstrap left a usable agent and context.

## Real agent turn and render surfaces

An HTTP form POST to `/agent/root/message` returned 204. The message asked the
agent to evaluate `(+ 40 2)` and complete with `"42"`. The per-agent graph made
one real `deepseek-v4-flash` request; its durable attempt finished with `stop`.
Run `1722ed09-49a0-4dde-9630-c3a84c8b2210` settled four ordered receipts:

```clojure
(comment "I'll evaluate (+ 40 2) and then complete with \"42\".")
(+ 40 2)                       ; => 42
(comment "Now I'll complete with the result.")
(my.run/complete "42")        ; => completed, result "42"
```

The run closed with no `:seon.cluster.run/error`. Before the provider call, the
runtime committed one context capture at database basis 536870955. Its one
render-walk contribution measured 5,529 estimated tokens, and the stored prompt
contained the inbound request. That proves the live context assembly path ran,
not merely SCI evaluation.

All four web compatibility surfaces returned HTTP 200:

- `/`
- `/agent/root`
- `/ns/my.agents.root`
- `/ns/my.agents.root/debug`

The debug namespace page contained `:seon.render/ai`, `:seon.render/html`, the
`my.agents.root` namespace, and the settled transcript value `42`. No ordinary
bootstrap, prompt, evaluation, namespace-page, or transcript path depended on
a private first-party Var being installed.

## Teardown

`bin/seon --root tmp/reachability-phase2-live-root-0c3a3d535 down` stopped PID
52757 through prepl plus SIGTERM and released the flock. Final status reported
both scratch clusters stopped, `0/0 clusters alive`, no orphan Seon JVMs, and a
readable offline roster.
