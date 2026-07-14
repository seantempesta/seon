---
type: research
status: completed
tags: [research, web, agent, database]
---

# Canvas source audit — 2026-07-14

## Question and scope

What already implements the one `my.canvas` path, which dependency semantics
does it rely on, and what must change before schemas and function definitions
alone let a simple model drive every control correctly?

This was a read-only source, test, and live-default audit. It did not change
runtime code, write database facts, use the active ACME worktree, or inspect the
protected shared-schema file.

## Dependency ledger

| Boundary | Selected source | Grounded behavior |
|---|---|---|
| Malli | `0.20.0`; exact tag commit `4c054bd7…` is present in `reference-code/malli` | `:map-of` validates every key/value and `:=>` supplies the discoverable function contract. `:any` inside `::data` and `::values` is genuinely dynamic today, but the surrounding key, request, and transaction boundaries must carry the useful constraint. |
| Datahike | maintained `6f90b339…`, exactly the selected CLJ/CLJS override | The agent identity lookup-ref and one transaction path own canvas pin and agent-local values. Reads use one frozen database value; writes return the standard envelope. |
| Datastar | shipped `resources/public/js/datastar.js` is byte-identical to `reference-code/datastar/bundles/datastar.js` at `bb9ed6fb…` (SHA-256 `c9c8b997…`) | `data-bind` creates/updates signals and distinguishes checkbox booleans. Default `@post` sends filtered signals as JSON and emits `started`, `error`, and `finished` browser events. It performs native form validation only in `contentType: 'form'` mode. |
| Datastar Clojure | `reference-code/datastar-clojure` `1cef624e…`, tagged `v1.0.0-RC7` | Confirms the separate gzip SSE and signal-reading idioms; Seon intentionally ships its own CLJS/Node adapter. |
| Reitit | selected `0.10.1`; exact tag/checkout `106fc4c7…` | The database route resolves one `/agent/{id}/call` handler; controls do not need or own routes. |
| Transit CLJS | selected `0.8.280`; exact tag is present and checkout is `3d8a2c49…` | Render-time button data remains data through the query-string codec; symbols, lists, and tagged values are refused before invoke. |
| ClojureScript / Shadow | selected `1.12.145` / `3.4.10` | Exact Shadow release commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` is present; its parent `2911c908…` is still `3.4.9`. The exact selected ClojureScript source is still missing, so analyzer-sensitive changes remain blocked on that grounding gap; ordinary canvas data/control work does not need such a change. |

## Current mechanism

The path is already singular:

1. `my.canvas/view` returns the final AI/human twin response.
2. `show!`, `pinned`, and `clear!` write/read/retract the one
   `:seon.render.canvas/content` pin on the current agent.
3. `state` and `save!` read/write qualified domain attributes on that same
   agent through `seon.db`.
4. `button`, `input`, `select`, `toggle`, and `form` return ordinary hiccup.
5. `seon.web.reactive.transform` qualifies agent handler symbols, serializes
   captured button data with Transit, and rewrites handler slots to Datastar
   `@post` expressions.
6. `seon.web.reactive.call` derives the owning agent from the handler namespace,
   checks the program graph grant, resolves the compiled function, applies data
   values without recompiling them, awaits it, and returns one HTTP result.
7. A successful handler transaction reaches the existing database listener and
   render-unit/feed path. There is no action-specific refresh.

The live default REPL listed eleven indexed `my.canvas/*` functions, each with a
registered program-graph schema. A pure `view` probe returned the expected twin.
An explicit frozen-db probe read root's `:seon.agent/id`; root currently had no
explicit canvas pin, so `pinned` correctly returned an empty map. A button probe
produced one data call form. An attempted unsupported Datalog string predicate
failed as a value and the corrected database-read-plus-Clojure-filter probe
succeeded, which also confirmed the practiced REPL workflow.

The focused baseline passes 20 tests and 61 assertions across
`my.canvas-test`, `seon.web.reactive.transform-test`, and
`seon.web.reactive.call-test`.

## Gaps

### Controls do not expose a complete human transition

The control constructors have no pending/disabled/error contract. Datastar
emits request lifecycle events, but generated hiccup does not consume them.
`handle!` turns an invoked-handler failure into a 422 JSON response and a log;
the database feed has no fact to morph, and the control has no inline error
surface. The human can see an unchanged button/form and can submit again without
knowing whether the prior action is running or failed. This is tracked by
[[../../../seon/issues/canvas-controls-hide-pending-and-failure]].

### Tests prove pieces, not the canvas behavior

The current tests prove control hiccup, transform encoding/data refusal,
capability ownership, and one invoked transaction. They do not cover:

- `show! → pinned → clear!` against one fresh database;
- `save! → state` with registered values and a failed transaction envelope;
- JSON signal decoding for input/select/toggle while excluding page signals;
- visible pending, disabled, validation, and failure behavior;
- duplicate-submit/retry semantics;
- a transaction-driven canvas morph, final DOM, or focus preservation;
- narrow/wide browser layout; or
- generated skill/tool-card success with a simple model.

### Polymorphic maps need an explicit justification boundary

`::data` and `::values` are `[:map-of :qualified-keyword :any]`. They span
arbitrary domain handler values and arbitrary registered database attributes,
so one closed value schema cannot honestly describe them. The code compensates
at the real owners: Transit admits pure data only, handler instrumentation
validates its concrete request, and `seon.db/transact!` validates each attribute
against the registry. Keep that dependent validation explicit and tested; do
not imply that `:any` itself teaches a small model what a particular handler or
attribute accepts.

### The public API and generated teaching must converge

The canvas skill is a useful worked example, but graduation requires the
program-graph function card and referenced schemas to be sufficient before the
skill supplies examples. Missing schema affordances must be repaired in the
real `my.canvas`/handler contracts, not compensated with longer prompt prose.

## Ordered implementation slices

1. **Transition data.** Define one namespaced control-result/lifecycle value
   covering idle, pending, committed, validation failure, and handler failure.
   Settle whether transient pending is browser-local while durable failure is a
   normal returned/recorded error; do not store duplicate presentation state.
2. **Helper round trips.** Add fresh-database tests for pin/read/clear and
   save/read, including absent optional data and failed envelopes.
3. **Signal contract.** Make signal parsing a testable pure owner and prove
   exact input/select/toggle types, page-signal exclusion, malformed input, and
   native/schema validation interaction from exact Datastar behavior.
4. **Visible action state.** Use the existing Datastar lifecycle/call/feed
   boundary to prevent accidental duplicate submission and show structured
   failure without adding an action-specific refresh or second state registry.
5. **One canvas transition.** Reconcile explicit pin, derived focus, session
   selection, clear, and transaction invalidation through the settled render-
   unit engine; delete any superseded canvas-specific routing.
6. **Simple-model and browser proof.** Run schema-only discovery first, then the
   generated canvas skill, across the complete control matrix and real narrow/
   wide browser layouts. Refine names/schema shapes from failures.

## Regression and live matrix

| Case | Durable assertion | Live falsification |
|---|---|---|
| show/pin/clear | one agent fact, explicit retract, derived default resumes | REPL read after each committed envelope |
| save/state | registered values round-trip; missing attrs omitted | frozen-db read equals post-commit fact |
| button captured data | pure data reaches one concrete handler schema | one write and one feed morph |
| input/select/toggle form | qualified keys and string/string/boolean values | browser handler receives exact map only |
| validation failure | no invocation or partial transaction | visible field/control error, feed remains live |
| handler failure | structured error, no wedge or fabricated success | visible error, retry succeeds, next action works |
| duplicate click | one declared idempotency/disable behavior | rapid click cannot silently double effect |
| reactive update | only affected active canvas unit renders | server-side gzip frame plus final DOM |
| focus/session | pin/clear affects intended agent/tab only | two tabs do not steal selection |
| layout | stable semantic DOM and usable controls | narrow and wide browser screenshots/interactions |
| simple model | schema/function discovery selects correct call | reproducible Inspect task and provenance |

## Deletion and non-goals

- Delete no security or data-only checks from the existing call gate.
- Add no canvas route, client writer, action refresh, form runtime, tool
  registry, stored render, or second error shape.
- Remove duplicated canvas-specific transition logic only after the shared
  render-unit/session contracts prove parity.
- Do not grow the standing prompt to hide an unclear schema or invisible
  control result.
