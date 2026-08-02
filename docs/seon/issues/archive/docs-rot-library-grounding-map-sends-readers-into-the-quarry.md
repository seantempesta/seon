---
type: issue
status: resolved
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

## Resolution

Resolved in the path-limited library-grounding commit containing this note.

- `docs/seon/architecture/library-grounding.md:14-27` records the verified
  submodule revisions for Datahike, Konserve, SCI, core.async, Malli,
  clj-kondo, Reitit, and Datastar Clojure.
- `docs/seon/architecture/library-grounding.md:29-42` now begins every concept
  row at live `src/`, `resources/`, `script/`, or `bin/` owners and continues
  to exact existing files under `reference-code/`. The map contains no
  `src-old/`, deleted CLJS gate, Bun/pod owner, remote database protocol, or
  missing test command.
- `src/seon/cluster.clj:1-17` and
  `src/seon/cluster/store.clj:288-398` verify the process-root store and
  branch-per-cluster seam; `src/seon/fn/analyzer.clj:1-28,107-139` verifies
  the clj-kondo JVM analysis seam; and `src/seon/sci/eval.clj:1205-1270`
  verifies the live cluster `ctx` and supplied-context evaluation seam.
- `src/seon/blob.clj:19-54` verifies content-addressed Konserve blob writes and
  digest-checked reads. `src/seon/render/route.clj:1-55` and
  `src/seon/render/web.clj:692-804` verify the live Reitit and Datastar/http-kit
  seams.
- Every listed path was existence-checked at the pinned checkout. Inbound
  architecture links in `architecture.md`, `data-model.md`, the decisions,
  and this map's Related section now resolve to a map whose first-party owners
  are current; none embeds a quarry owner as current guidance.
