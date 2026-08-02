---
type: research
status: complete
tags: [sci, runtime, database, proof]
---

# Context-derived custody live proof — 2026-08-02

## Boundary

This is the reset-boundary proof for ruling #43 phase 3. The evaluation
request no longer carries `:seon.store/branch-connection`; `cluster-ctx`
captures its connection and `evaluate` binds the compiled `seon.db/*conn*`
from that context. The process-root registries also live in
`seon.operator.runtime`, a classpath namespace outside the indexed `src` and
`test` program roots.

The pre-change falsifier built cluster contexts A and B, ambiently bound the
compiled `seon.db/*conn*` to B, then evaluated an A query without the optional
request connection. It returned B's row. The same form after the change
returned A's row. An acquired `build-base-ctx` with no custody now returns
`:seon.db/missing-connection-binding` even under an ambient B binding.

## Recurring proof

The focused custody and evaluation gate passed 43 tests and 179 assertions.
The loop, store, boot, and bootstrap-drive gate passed 62 tests and 272
assertions. The late-resolving oversight consumer passed 3 tests and 33
assertions. All three gates reported zero failures and zero errors.

The custody stability suite derives the public functions whose declared output
references a custody schema. It also proves that the operator namespace has no
database program-graph row and no SCI namespace, while the registry Vars
referred by their compiled consumers are owned by `seon.operator.runtime`.
Its fixed-seed FOREIGN-CONTEXT INTEGRITY property builds independent A and B
contexts and asserts B's complete namespace/Var-root snapshot remains equal
after every generated A form, including definitions, namespace mutation,
database access, and attempts to resolve the relocated roots.

## Fresh operator boot

The isolated root was `tmp/custody-phase3-live-root`. Explicit initialization
published current source commit
`6a6fb4ea-1e5d-573a-9815-6978a12dabfb` with digest
`11084c8d0ff99db32e4d4bd591d0e25dfb487ef6e50928c22c904fe0856498ac`.
Cluster `custody-phase3` reached readiness at `http://127.0.0.1:7775`; its
`bootstrap:root` run closed.

## Real agent turn and render surfaces

An HTTP form POST to `/agent/root/message` returned 204. The message asked the
root agent to evaluate `(+ 40 2)` and complete with `"42"`. A real
`deepseek-v4-flash` attempt finished with reason `stop`. Run
`36e65185-a215-4d23-8bd3-6b9ab1c06893` closed without a run error, and its
ordered receipt stored source `(+ 40 2)` and result `42` at database basis
536870961. The pre-call context capture was committed at basis 536870955 and
measured approximately 5,518 estimated tokens.

All compatibility surfaces returned HTTP 200:

- `/`
- `/agent/root`
- `/ns/my.agents.root`
- `/ns/my.agents.root/debug`

The debug namespace page contained both `:seon.render/ai` and
`:seon.render/html`, the closed run, the inbound message, and `Form 0 returned
42`. This proves evaluation, durable settlement, context assembly, and both
render projections on the fresh branch.

## Teardown

`bin/seon --root tmp/custody-phase3-live-root down` stopped PID 81338 through
prepl plus SIGTERM and released the flock. Final status reported zero live
clusters, a readable offline roster, and no orphan Seon JVMs.
