---
type: issue
status: resolved
tags: [agent, runtime, issue]
severity: blocker
---

# Guarded eval door lacks a Bun installation and config owner

## Problem

U1 requires one portable SCI guarded-eval entry, a hostile-loop fuel proof on
both the JVM and single-threaded Bun tiers, and new manifest-backed config
facts. The current source and the lane ownership do not expose both required
owners.

The production Bun evaluator is `cljs.js`, not SCI. Its only wall-clock guard
is an async Promise race, which explicitly cannot preempt a synchronous loop.
There is no production `sci/init` or `:interrupt-fn` installation site in
`src/seon/eval.cljs`.

The config manifest's leaf schemas, closed section shapes, singleton entity,
manifest shape, and flattening resolver live in
`src/seon/config/resolve.cljc`. U1 grants `src/seon/config.cljs` and
`config/system.edn`, but not that actual schema owner.

## Evidence

- `src/seon/eval.cljs:1185-1292` evaluates through `cljs.js/eval-str`.
- `src/seon/eval.cljs:1294-1355` races only asynchronous settlement and says a
  synchronous loop has no hard cancellation.
- The only Bun SCI engine is the retired experimental source at
  `tmp/sci-probe/exec-src/seon/execution/sci_runtime.cljs`; the accepted C2
  audit rules that tier out.
- `src/seon/host/context.clj:875-883` is the sole production SCI
  `:interrupt-fn` installation.
- `src/seon/config.cljs:32-36` names `seon.config.resolve` as the owner of new
  section and manifest schemas.
- `src/seon/config/resolve.cljc:54-63,278-302,675-764` owns the leaf, section,
  singleton, and manifest registrations that a new fact must extend.

Retained context lifetime also needs an explicit implementation ruling.
`src/seon/host.clj:100-122` reuses an agent SCI context across new wire
sessions, while SCI functions capture their context's `:interrupt-fn` when
defined. A closure directly capturing one wire session's fuel array can
therefore outlive that session. A portable dynamically bound pointer to the
session-owned cell may solve this without changing the pre-ruled cell
placement, but that seam is not stated or proved.

## Owner decision

Before U1 resumes:

1. Define Bun acceptance as either a direct SCI CLJS conformance test (not a
   claim about production `seon.eval`) or grant and sequence the production
   engine/cutover owner. Do not wrap `cljs.js` in a timer and call it fuel.
2. Grant `src/seon/config/resolve.cljc` to U1, or give the config owner the
   complete leaf/section/singleton/manifest/resolver change.
3. Confirm how the base guard resolves the current session-owned fuel cell
   across retained-context reuse. A portable dynamic binding around the one
   entry is the narrow candidate; a captured session closure is invalid.

## Acceptance

- The named Bun tier has a real SCI `:interrupt-fn` installation, or the gate
  explicitly says it is a portable direct-SCI conformance test only.
- New budgets validate through the closed manifest schema, flatten into the
  singleton, transact as facts, and are acquired by both guarded entrypoints.
- A context reused by a second wire session consumes only the second session's
  reset fuel cell.
- Both hostile-loop proofs finish without relying on an outer process kill.

## Resolution

The 2026-07-23 orchestrator rulings removed all three ambiguities. U9 owns
deletion of the production Bun `cljs.js` engine, so U1 proves the portable
CLJS guard directly without inventing a second production evaluator. The
config owner committed first and then granted U1's exact guard section.
Finally, the stable mutable holder is retained-context-owned and reset by the
door for every invocation; no dynamic binding sits in the hot path. U1's
second-session regression proves that a function defined before the second
session consumes the second invocation's freshly reset budget.

## Closure evidence — 2026-07-23

Resolved by `8000f5327` and the config-owner handoff in `3d8c9a9a6`.
`src/seon/host/guard.cljc:47-72` owns the retained-context holder and resets it
at every invocation; `src/seon/host/guard.cljc:210-220` is the one guarded
entry. The direct portable hostile-loop proof is
`test/seon/host/guard_test.cljc:51-67`, the second-session reuse regression is
`test/seon/host/guard_context_test.clj:17-75`, and the closed config projection
is exercised by `test/seon/host/guard_config_test.cljs:7-43`.
