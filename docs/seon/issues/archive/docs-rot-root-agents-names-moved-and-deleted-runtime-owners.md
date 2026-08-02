---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, architecture, runtime]
---

# Re-ground root AGENTS vocabulary in fresh owners

## Problem

Root `AGENTS.md` correctly declares the fresh CLJ-only system and great
deletion, but its operational and vocabulary sections still cite moved or
deleted owners and restate pod-era behavior. Since every repository task loads
this file, these contradictions have the widest possible blast radius.

## Evidence

- `AGENTS.md:37-40` uses the banned `driver` name twice for the surviving
  effect shapes; the live run loop is `src/seon/cluster/loop.cljc`.
- Vocabulary rows `AGENTS.md:625,628-631` cite missing
  `script/seon/dev/process.clj`, quarry-only
  `src-old/seon/db/protocol.cljc`, and quarry-only
  `src-old/seon/agent/driver.clj` as defining current process, initialization,
  and run-loop terms. Current process identity is split between
  `src/seon/cluster/process.clj` and `script/seon/fresh_operator.clj`; current
  run transitions are in `src/seon/cluster/{run,loop}.cljc`.
- `AGENTS.md:691-692` calls `src-old/seon/route.cljs` current route truth and
  lists `/`, `POST /agents`, and `/agent/{id}`. Fresh route truth is
  `src/seon/render/route.clj:4-31`, including namespace/debug/feed/data/assets
  and `POST /agent/{id}/message`; it has no `POST /agents` route.
- `AGENTS.md:719-722` says the pod forwards writes through
  `seon.db.replica` and only the JVM server owns Datahike. The pod and replica
  path are deleted; fresh branch-local writes use
  `src/seon/cluster/store.clj` in the process-root JVM.
- `AGENTS.md:804-807` says agent eval awaits Promises and the self-host engine
  is interim, while the same authority says the CLJS build is off and the
  self-host engine is deleted.
- `AGENTS.md:815,1157,1171-1173` direct detailed current ownership/provider
  readers into `src-old/**/AGENTS.md`; those files themselves describe dying
  or condemned pod namespaces. Quarry lessons may be referenced, but they
  cannot own fresh behavior.
- `AGENTS.md:1139-1143` calls root `:writer` and `:cljs` aliases current shared
  fork authority. `unlogged-findings-2026-08-01.md` item 3 already records the
  alias contradiction, so this note does not duplicate that underlying alias
  defect; it records root authority's larger moved-owner chain.

There is no outer reader to repair first: root `AGENTS.md` is automatically
loaded for every task and explicitly designates the architecture map,
conventions, active runtime runbook, and quarry authorities. Its stale pointers
then feed those readers into the architecture, conventions, and localized
runbook rot recorded in sibling issues.

## Owner

Root `AGENTS.md` owns repository-wide runtime law and vocabulary. Fresh source
owners on both sides of each interface, plus the maintained dependency source,
must ground each current row.

## Acceptance

- Every current vocabulary row names existing fresh first-party source and the
  exact dependency source where the boundary crosses a dependency.
- Current route, process, run-loop, database, eval, and provider guidance no
  longer delegates to deleted pod/quarry owners.
- Historical/quarry pointers are explicitly lessons only and cannot be read as
  current operational authority.
- A repository-wide reader chase confirms no localized authority or skill
  preserves the removed root claim after reconciliation.

## Resolution

Resolved by `b37d54b64` on 2026-08-01. The named lines now
point process identity to `src/seon/cluster/process.clj`,
`script/seon/fresh_operator.clj`, and `script/seon/dev/state.clj`; run custody
and the run loop to `resources/seon/schema/run.edn` plus
`src/seon/cluster/{run,loop}.cljc`; initialization to
`src/seon/cluster.clj` and `src/seon/fn.clj`; routes to
`src/seon/render/route.clj`; reads to `seon.db`; and writes to
`seon.cluster.store/transact!`. The CLJS self-host, replica, quarry-owner,
obsolete ACME, and provider-adapter claims were removed from current guidance.

The provider paragraph now matches `src/seon/ai.cljc`, the
`:seon.config.ai/*` declarations in `resources/seon/schema/config.edn`, and the
attempt facts committed by `src/seon/cluster/loop.cljc`. Searches over
`AGENTS.md` find none of the moved process/route/run/database owners named by
this issue. The remaining `src-old` occurrences are explicit quarry law, not
current ownership delegation.
