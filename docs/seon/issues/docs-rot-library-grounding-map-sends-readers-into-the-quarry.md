---
type: issue
status: open
severity: blocker
tags: [issue, architecture, agent, database, flow]
---

# Rebuild the current library grounding map from fresh owners

## Problem

`docs/seon/architecture/library-grounding.md` calls itself the current
concept-to-source read map, but 14 of its 20 concept rows point to `src-old/`
owners. Several rows also name deleted CLJS gates and absent runtime
mechanisms. This inverts the repository's archaeology rule: the document an
agent is told to trust sends it to the quarry before the surviving owner.

## Evidence

- Rows 16-35 direct database, schema, instrumentation, planning, Bun, route,
  Datastar, and Inspect work through `src-old/` paths. Fresh owners now include
  `src/seon/cluster/store.clj`, `src/seon/db.clj`,
  `src/seon/schema/{edn,datahike}.clj[c]`, `src/seon/instrument.clj`,
  `src/seon/render/{route,web}.clj`, and `src/seon/sci/eval.clj`.
- `library-grounding.md:33-34` tells readers to use the changed-CLJS owner and
  `bin/test-cljs`/`bin/test-writer`; both scripts are absent and `bin/test` is
  the fresh gate.
- `library-grounding.md:30` points route derivation to
  `src-old/seon/route.cljs` and `src-old/seon/web/router.cljs`. The current
  route table and reverse router are `src/seon/render/route.clj:4-44`.
- `library-grounding.md:13` names the pinned Datahike transaction source
  correctly, but then pairs it with the old database server instead of the
  live `cluster.run`/`cluster.store` seam. The vendored revisions verified for
  this audit are Datahike `256b714d97a0`, SCI `a27e2c0e0794`, core.async
  `dc35f3e0d7bc`, and Konserve `737697d9205e`.

The reader chain is direct and broad. Root `AGENTS.md:419` designates
`library-grounding.md` as the measured source map; the architecture map links
it as the current map; data-model links it from refs, schema, route, and error
sections; ADR-007 sends instrumentation readers there; and archived PRD
runbooks still cite it as current grounding. An agent obeying the mandatory
dependency-ledger rule is therefore confidently grounded in deleted code.

## Owner

`docs/seon/architecture/library-grounding.md` owns the always-current
dependency-to-first-party seam map. Each fresh mechanism owner must supply its
current first-party source and the exact pinned dependency source on the other
side.

## Acceptance

- Every row begins at a fresh `src/`, `script/`, `bin/`, or schema-resource
  owner; `src-old/` appears only in an explicitly labeled lesson/quarry column.
- Deleted CLJS gates, Bun/pod owners, remote database protocol, and missing
  commands are absent from current invariants.
- Every dependency row names a real path at the pinned revision and a live
  first-party consumer.
- All inbound readers are checked so none continues to call the old map's
  quarry owner current.
