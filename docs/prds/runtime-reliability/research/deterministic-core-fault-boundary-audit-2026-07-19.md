---
type: research
status: completed
tags: [research, cljs, health]
---

# Deterministic core-fault boundary audit

## Question and dependency ledger

This audit reconciles the six boundaries still listed as in progress in
[[overnight-integrated-graduation-plan-2026-07-18]] with the later source and
evidence on commit `a231c0b16291dd696bdd6fe56884ddb66ec94617`.

The relevant maintained owners are `src/seon/error.cljs`,
`src/seon/agent/loop.cljs`, `src/seon/client.cljs`,
`src/seon/runtime/admission.cljs`, `src/seon/execution.cljs`,
`src/seon/execution/runtime.cljs`, and `src/seon/render.cljs`. The process-local
configuration scope is Bun's `AsyncLocalStorage`; the execution boundary is
the digest-verified Bun child artifact; watched reload enters through Shadow's
build notification hook. No new error, retry, or supervision mechanism is
needed.

## Verdict

The checklist is stale, not the implementation. Ticker, watched reload,
publication, selected calls, and top-level compiled child calls already enter
the database-selected policy and have focused or exact-package evidence.
Startup publication also fails before the child advertises ready, reports the
startup error to its parent, and exits. The first remaining graduation gap is
the **generic core render guard**: its classification and graceful/strict
behavior are tested, but there is no deterministic `:crash` proof showing its
fault datom committed before the execution child exits.

| Boundary | Current owner and contract | Existing evidence | Remaining falsifier |
|---|---|---|---|
| ticker | `seon.agent.loop/run-tick!` records an unexpected rejection inside the configuration acquired when the ticker was installed | 17 tests/71 assertions plus exact persisted fault and pod-only recovery in the overnight plan | None unless this owner changes |
| reload | `seon.client/shadow-build-notify!` acquires database configuration before build or publication failure recording; failure makes admission unavailable | Watched fault `5923` committed before the configured pod crash; normal `up` restored only the pod | None unless the Shadow notification seam changes |
| publication/startup | Pod admission records once through `seon.runtime.admission/mark-unavailable!`; a child prepares with recording disabled to avoid a duplicate, refuses ready on failure, sends a startup error, and exits | Atomic publication and recovery proof in the overnight plan; focused admission and child-start tests | Retain one assertion that a failed child never emits ready; do not add child-side duplicate persistence |
| selected call | `seon.execution/call-selected!` classifies from the selected function symbol and records only core failure inside the caller's configuration scope | 28 tests/108 assertions; exact package `e131a442…` committed transaction `536871421` before child exit while pod stayed ready | None unless selection is bypassed by a new caller |
| top-level child call | `seon.execution/record-top-level-call-error!` reads configuration only after a compiled `seon.*` failure; authored `my.*` failures remain agent errors; parent treats exit as supervised evidence | 29 tests/114 assertions; successful calls add no database read | None unless a new top-level compiled entry bypasses `begin-invocation!` |
| generic render | `seon.render/render`, `block`, and entity converters classify and call `seon.error/record!`; prompt and agent-view composition run under `with-configuration` | `render-failure-is-guarded-or-thrown-by-the-single-strict-dial` proves classification/visible fallback/strict throw, but uses `expecting-core-fault!`, not the real database policy | Inject a core converter throw through `seon.execution.runtime/render-prompt!` or `render-agent-view!` with `:seon.config/on-core-error :crash`; assert the core fault transaction is queryable before the child exit and the pod remains ready |

## Earliest unsettled contract

The generic render guard must not silently keep an execution child alive after
core render machinery fails under `:crash`. The shortest useful falsifier is a
single exact-artifact invocation whose core converter throws below
`seon.render/render` or `seon.render/block`. Success is ordered evidence:

1. the invocation reaches the ordinary render guard inside the acquired
   configuration scope;
2. exactly one `:seon.error/fault :core` transaction is committed;
3. only after that commit does the execution child exit nonzero;
4. the pod and writer remain ready; and
5. restoring the converter lets the next fresh child render successfully.

This is a proof gap first. Source inspection did not find a second render
mechanism or an obvious missing configuration wrapper in the maintained prompt
and agent-view entry points. Do not add another render policy layer unless the
falsifier demonstrates that async context is lost at a specific call edge.

## Focused test package

The smallest regression package is:

- `test/seon/render_test.cljs` for generic guard classification, strict mode,
  and visible graceful output;
- `test/seon/execution/runtime_test.cljs` for prompt and agent-view
  configuration scope;
- `test/seon/execution_test.cljs` for selected-call, top-level failure, child
  startup, IPC error, and supervised exit semantics;
- `test/seon/agent_loop_test.cljs` for ticker scope;
- `test/seon/client_initialization_test.cljs` and
  `test/seon/instrument_delta_test.cljs` for watched reload; and
- `test/seon/runtime/admission_test.cljs` for one recorded publication failure
  and no alive-but-unready admission.

After the generic-render exact-artifact falsifier passes, section 1 can be
rewritten as complete rather than retaining six already-closed items as one
open checkbox. The final graduation gate remains the complete current-source
CLJS/writer/operator checkpoint plus browser and exact-package journeys in the
active roadmap.
