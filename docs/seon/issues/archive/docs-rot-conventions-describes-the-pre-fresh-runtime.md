---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, agent, schema, database]
---

# Reconcile the conventions document with the fresh runtime

## Problem

`docs/conventions.md` declares itself the agent-facing standards document and
audit rubric, but several sections still state that already-landed fresh
owners do not exist and then teach the deleted pod-era contracts as their
future target. An agent following the document can add schemas, database calls,
instrumentation, reload hooks, or SSE behavior to owners the fresh system has
already replaced.

## Evidence

- `docs/conventions.md:11-20` says `seon.instrument` and `seon.db` do not exist.
  They are live at `src/seon/instrument.clj` and `src/seon/db.clj`;
  `src/seon/cluster.clj:1575-1576` exposes instrumentation readiness and the
  current tests call `instrument/apply!`.
- `docs/conventions.md:135-177` teaches load-time
  `schema/register!` as the single shipped attribute-schema authority. Shipped
  schemas now live under `resources/seon/schema/` and are admitted by
  `src/seon/schema/edn.clj`; runtime `register!` remains a distinct
  agent-publication surface.
- `docs/conventions.md:382-444` describes instrumentation as unbuilt and
  `seon.db` as absent, then specifies nonexistent `db/query`, `db/transact!`,
  and `db/listen!` calls. The fresh facade exports `q`, `pull`, and `pull-many`
  at `src/seon/db.clj:118,185,221`, while durable writes currently enter
  `src/seon/cluster/store.clj:440`.
- `docs/conventions.md:81-99` says public namespaces are whitelisted and
  `.internal` namespaces are un-whitelisted. Ruling #20 says every function in
  the cluster program graph is callable; rendering into context is not an
  execution allowlist.
- `docs/conventions.md:896-951` calls context and SSE unbuilt and teaches the
  old section-bracket grammar. The fresh owners are `src/seon/context.clj`,
  `src/seon/render/web.clj`, and the sealed REPL/print path in
  `src/seon/print.cljc`.
- `docs/conventions.md:955-992` preserves an Integrant-based
  `after-ns-reload` recipe even though the same document says the fresh tree
  has no Integrant dependency and repository search finds no live reload-hook
  consumer.

The contradiction has transitive readers. Root `AGENTS.md:422,1170` names the
file as the code/schema authority; the `data-oriented-clojure` skill points to
it for docstrings and full conventions; the `data-modeling` skill points to it
for Malli/request patterns; and `datastar-quick-reference.md` calls its SSE
section ground truth. One stale paragraph therefore reaches every Clojure,
schema, and web lane that follows the required workflow.

## Owner

`docs/conventions.md` owns current coding conventions. Fresh source plus the
active architecture and runtime roadmap own behavior; historical recipes
belong in archived research, not in current-state sections.

## Acceptance

- Every `Current state`, `Unbuilt`, and target statement in
  `docs/conventions.md` is re-derived from fresh `src/`, `test/`,
  `resources/seon/schema/`, and `bin/`.
- Shipped schema EDN, current `seon.db` operations, instrumentation, context,
  print, and web Flow have one accurately named owner each.
- No current convention teaches whitelist-based callability, pod/CLJS
  ceremony, Integrant reconstruction, or nonexistent database function names.
- The skills and reference pages that cite conventions are checked in the same
  wave so they cannot preserve a stale paraphrase.

## Resolution

Resolved by the conventions rewrite on 2026-08-01. `docs/conventions.md` now
names the process-root JVM/branch-per-cluster topology from
`src/seon/cluster.clj` and `src/seon/cluster/store.clj`; shipped schema EDN from
`src/seon/schema/edn.clj`; runtime registration from `src/seon/sci/eval.clj`;
host and interpreted instrumentation from `src/seon/instrument.clj`; the
`seon.db` `q`/`pull`/`pull-many` read facade; the co-located
`seon.cluster.store/transact!` write owner; the one-walk prompt/debug boundary;
the `seon.print` sinks; and the live `seon.render.web` Flow and virtual-thread
feed. Deleted allowlists, pod/CLJS APIs, remote database calls, section-bracket
grammar, provider multimethods, and Integrant/clj-reload recipes were removed.

Proof: `seon.dev.markdown/validate-file` reports the revised document valid;
`git diff --check` passes; and stale-owner searches find no deleted database,
context, instrumentation, or reload API in current guidance.
