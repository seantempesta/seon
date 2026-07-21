---
type: research
status: active
tags: [research, cljs, agent, database]
---

# Phase 0 live verification — the DB-is-the-running-system spine is PROVEN

Live REPL verification (against the running pod, port 7890, `:client` build,
fresh `:core-seed` store) of the riskiest unknowns in
[[docs/prds/agent-runtime/db-is-the-running-system-2026-06-17]] BEFORE writing
implementation code. "Slow is fast." Every claim below was OBSERVED, not inferred.

## TL;DR — the gate is green

The DB-style `*load-fn*` model works exactly as the spine research predicted.
A prototype load-fn over an in-memory `{ns-sym → source}` map drove a FULL
transitive dependency load (`entry → a → b`) sequenced entirely by `cljs.js`,
on the EXISTING `analyze-deps false` path (no flag flip), with same-ns forward
refs resolving in one bulk ns-eval. Reconstitution from real DB rows produces
well-formed loadable source. The requires-gap is confirmed and explains why
indexing `:seon.ns/requires` is the one unblocking fix.

## Environment facts (live)

- Pod runtime is the `:client` shadow build; `@seon.repl/!compile-state` is an
  **atom-of-atom** — the INNER atom (`@@seon.repl/!compile-state` → env map with
  `:cljs.analyzer/namespaces`) is the real `cljs.js` compiler-state atom that
  `cljs.js/eval-str` + `analyzer_info/defs-since` expect. Pass `@!compile-state`.
- `seon.db/*conn*` is bound; `@seon.db/*conn*` is a datahike db value queryable
  via `seon.db/query`. Store baseline matches the PRD: ns 74 / fn 192 /
  schema 408 / test 221.
- `cljs.js/*loaded*` already contains **79 nses** including `cljs.core` — so
  loading agent nses does NOT trigger a load-fn call for already-compiled deps
  (load-once memoization, `js.cljs:291`). `seon.eval/guarded-load` is reachable
  as the JS fn `seon.eval.guarded_load` for delegation from a prototype load-fn.

## The mechanism, grounded in reference-code/clojurescript (v1.12.41)

`ns-side-effects` (`js.cljs:566-640`) calls `load-deps` (which invokes
`*load-fn*`) iff its `load` arg is true (line 629); `:analyze-deps` only gates
the `(not load)` branch (634) and `check-uses` (620). `eval*` calls
`(ns-side-effects true …)` with **`load` hardcoded true** for any `:ns`/`:ns*`
form (`js.cljs:825-826`). Therefore the load-fn ALWAYS fires for the requires of
a `(ns … (:require …))` form — including under `analyze-deps false`. This is why
the current replay already hits `boot/load` for requires (the B4 incident), and
why the DB branch slots into the existing eval path with no analyze-deps change.

## Proof 1 — load-fn drives a transitive chain + same-ns forward refs

Prototype: a load-fn over `{probe.b "(ns probe.b)\n(defn b-val [] 42)",
probe.a "(ns probe.a (:require [probe.b :as b]))\n(defn fwd-user [] (helper))\n
(defn helper [] 7)\n(defn a-val [] (+ (b/b-val) (fwd-user)))"}`, delegating
non-probe names to `guarded-load`. `cljs.js/eval-str` of
`"(ns probe.entry (:require [probe.a :as a]))\n(defn entry-val [] (a/a-val))"`
with `:analyze-deps false`.

Result (OBSERVED):

```clojure
{:load-fn-call-order ["probe.a" "probe.b"]   ;; cljs.js sequenced entry→a→b
 :eval-status "ok ns=probe.entry"
 :entry-val 49   ;; probe.entry/entry-val → a/a-val (cross-ns, 2 hops)
 :a-val 49       ;; b/b-val (42, cross-ns) + fwd-user→helper (7, same-ns FORWARD ref)
 :b-val 42}
```

Confirms: (1) the load-fn drives a multi-ns dependency-ordered load via cljs.js's
own `load-deps` + `*loaded*`; (2) same-ns forward refs (`fwd-user` calls `helper`
defined LATER in the same source) resolve in one `eval-str` pass — bulk-per-ns
eval is correct where per-def eval needed the retry hack; (3) it all works on
`analyze-deps false`. THIS IS THE SPINE.

## Proof 2 — analyzer `:requires` shape (capture input for Phase 1)

For freshly-eval'd `probe.a`: `:requires` = `{b probe.b, probe.b probe.b}`
(`{alias→ns, ns→ns}`), `:uses` nil, `:require-macros` `{}`. `(vals :requires)`
gives the ns syms. `analyzer_info/ns-deps` already composes
`(set (concat (vals requires) (vals uses) (vals require-macros)))` minus self ∩
known-set, returning `#{probe.b}` for probe.a and `#{probe.a}` for probe.entry —
correct. Phase-1 capture = store the FULL required-ns set (unfiltered) as
keywords; the load-time topo intersects with the DB-layer ns set.

## Proof 3 — reconstitution + the requires-gap (why :seon.ns/requires is THE fix)

Live read-only over the store: **9 of 62** stored `:seon.ns/source` rows contain
`(:require`; the rest are bare stubs (e.g. `:seon.ctx` source = `"(ns seon.ctx)"`).
Reconstitution of `:seon.ctx` (ns form + its 20 `:seon.fn` sources) concatenates
into well-formed loadable source. Since `cljs.js` reads requires from the ns FORM
at load time, and the stored ns form is usually a bare stub, reconstitution MUST
REBUILD `(ns name (:require <:seon.ns/requires>))` from the stored requires —
not from `:seon.ns/source`'s ns form. That is the one unblocking fix. (Note: core
ns sources are stubs from `index-core!`; under the new model core is COMPILED, its
rows are display-only, so this matters for AGENT/third-party DB-layer nses.)

## Phase 2 spine — COMPLETE + live-proven (2026-06-17)

The spine is implemented and independently re-verified by the orchestrator on a CLEAN
reset store: a 2-ns agent chain (`my.rt.a` requires `[my.rt.b :as b]`) survived a real
`bin/seon restart pod` and `my.rt.a/av` returned 77 (loaded from the DB by the new
`replay-program-graph!`, alias resolved, `my.rt.a` present in the analyzer). Clean-store
boot replay = 0 nses, no errors. Net client.cljs −70 LOC (deletion dominates).

### The over-inclusion + the origin-based load filter (Phase 3 ENABLER)

The new load set is NS-based: `agent-ns-set = (all :seon.ns) − (core-ns-set)`. On a
RESTART boot this over-includes ~12 rows — the `fn-less-compiled-roots` (`my.kb`,
`my.soul`) + schema-only `seon.*` data-nses — which are not in the var-meta-driven
`core-ns-set`. Their reconstituted re-eval is idempotent (register! calls; replay-n-fail
was 0), so resume is CORRECT, but it is wasteful and a smell.

POLICY CORRECTION (Sean, 2026-06-17): AGENTS must NOT override core/third-party (the
compiled package). Agent evals define only in their OWN nses; redefine-as-upsert applies
WITHIN agent nses only. So the load set staying agent-only is CORRECT — we do NOT want to
load agent overrides of core. Override of core is THIRD-PARTY and BUILD-TIME (two source
paths), not an agent DB upsert. (Supersedes the earlier "origin-based filter to load agent
overrides of core" idea — that was the wrong direction.)

Two consequences:
- Fix the over-inclusion so the load set is genuinely agent-only (exclude `my.kb`/`my.soul`
  fn-less roots + schema-only `seon.*` data-nses). `:seon.db/origin` IS queryable as
  tx-meta — `[?e :seon.fn/source ?src ?tx] [?tx :seon.db/origin ?o]` → `{:core-seed 197}`
  on a fresh store (creation-evals! forge `:core-seed`); distinct origins `{:system,
  :agent, :core-seed}`. So "load rows whose current source-datom origin = `:agent`" cleanly
  EXCLUDES core-seed (the over-inclusion fix) — same mechanism, opposite intent.
- Add a TEE GUARD so an agent eval can't PERSIST (or take live effect on) an override of a
  compiled core/third-party fn — and warn the agent. (Replaces the "redefinition fixture
  for agent override-of-core"; the fixture should test THIRD-PARTY build-time override.)

## Still TODO (post-implementation, by design)

- **Resume end-to-end** (write agent ns with requires → restart pod → it returns):
  needs Phase 1 (capture requires) + Phase 2 (DB load-fn + topo) in place; will be
  proven via a real `bin/seon restart pod`, not a scratch harness. The async-
  transact friction in the synchronous MCP eval means a true resume proof belongs
  to the real boot path, not a hand-rolled rehearsal.
- **Generic compiled-ns enumeration** (drop `fn-less-compiled-roots` /
  `curated-core-vars`): candidate sources observed live — `@cljs.js/*loaded*` (79
  nses, but includes cljs.*/clojure.*) or the goog dep registry. Decide in Phase 4
  with a live check; flagged OPEN, not pretended.
