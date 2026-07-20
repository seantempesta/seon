---
type: research
status: complete
tags: [research, architecture, rendering, agent]
---

# Unresolved render-symbol boundary (2026-07-20)

## Decision

One rule governs every stored function symbol: resolve it through
`seon.eval/lookup-value` at use time and require the result to be callable.
An absent or non-function value is ordinary failure data, never an omitted
render and never a fall-through to another renderer.

For rendering, the failure value is one closed `:seon/error` projection naming
the symbol and its owning location. The AI view emits its legible, copyable
self-healing message; the HTML view sends the same value through the existing
`seon.render.canvas/error-card` seam. Neither path invokes `strict-fail!`,
records a fault datom, or substitutes the generic tree. The next render
resolves again, so defining the function or correcting the stored symbol heals
both views without acknowledgement state.

One derived warning family reports every currently uncallable stored render or
route symbol. It replaces the canvas-only warning, is pure over data acquired
at one immutable database value, and vanishes when the symbol becomes callable
or the slot is removed. A request-driven route miss additionally records one
`:core` fault in the route owner because an actual request failed; a render-time
miss does not write on every page or prompt derivation. Execution-call misses
retain their existing one-load-attempt and structured per-call error behavior;
this unit does not add a second execution resolution path.

## Shortest falsifier

The issue remains live in the current source. `render-entity-html` and
`render-entity-ai` call `lookup-value`, conditionally invoke only a truthy
result, then unwrap `nil` (`src/seon/render.cljs:350-358,632-639`). Thus a
registered converter symbol that resolves to nil contributes nothing. A
truthy non-function reaches invocation and is reported only as a thrown
renderer, even though the defect is the same invalid stored target.

The nearby explicit-slot path is only half-correct: `missing-render` produces
a self-healing AI line but deliberately returns nil for HTML
(`src/seon/render.cljs:719-728`). `check-canvas-unresolved` derives a useful
self-healing warning, but its acquisition and explanation cover only
`:seon.render.canvas/content` (`src/seon/warn.cljs:787-832` and
`src/seon/agent/ctx/warnings.cljs:112-117`). Context-block slots, activated
schema renderer properties, and route handlers therefore have no equivalent
derived diagnosis.

The running default cluster was rebuild-pending while independent source lanes
owned active edits, so no live checkpoint is claimed here. The source branch is
decisive and the acceptance probes below are reserved for a frozen revision.

## Dependency ledger

| Mechanism | Selected source | Contract this unit consumes |
|---|---|---|
| Runtime symbol lookup | `src/seon/eval.cljs:490-535`; `reference-code/clojurescript/src/main/cljs/cljs/core.cljs:11994-12010,12129-12158` | `cljs.core/find-ns-obj` finds compiled Node namespaces under both uncompiled and simple output, and `cljs.core/munge` supplies the member name. `lookup-value` is the one qualified-symbol adapter and returns nil on absence. |
| Activated renderer properties | `src/seon/schema.cljc:340-375`; [[activated-schema-projection-boundary-2026-07-20]] | Renderer symbols are properties in the immutable activated catalog, not a warning registry or copied database attribute. Stage 1.5 widens this catalog to every eligible map schema and freezes ordered valid matching. |
| Universal value dispatch | `src/seon/render.cljs`; [[universal-data-browser-ui-migration-boundary-2026-07-20]] | Explicit per-value override, then the first valid matching schema property, then bounded generic data. A missing custom renderer is visible and must not fall through. `unwrap-response`, `block`, and the existing guard remain the only dispatch seams. |
| Malli property preservation | `reference-code/malli/src/malli/core.cljc:367-425`; `src/seon/schema.cljc:342-359` | Malli forms preserve schema properties; Seon derives renderer symbols from those properties when it builds the active catalog. No second property parser belongs in the renderer or warning check. |
| Render error values | `src/seon/render.cljs:243-278,330-370,620-652`; `src/seon/render/canvas.cljs:552-577` | Render results use the one unwrap contract. Failures shown to humans use the one overridable error-card seam over `:seon/error` data. |
| Derived warnings | `src/seon/warn.cljs:787-857`; `src/seon/agent/ctx/warnings.cljs:15-176` | Checks are pure over one bounded acquisition. Current function rows plus compiled lookup distinguish current authored functions from core compiled functions. Warnings are never stored. |
| Database routes | `src/seon/route.cljs`; `src/seon/web/router.cljs:169-181`; [[route-authority-collapse-2026-07-20]] | A route exists independently of whether its handler resolves. No match remains 404/redirect policy; a matched route with an uncallable handler is a 500 core misconfiguration and the Stage 4 request boundary owns its single forensic fault. |
| Existing issue | [[../../../seon/issues/render-entity-converters-silently-vanish-on-unresolved-symbol]]; [[envelope-symbol-conformance-2026-07-20]] | Close only after both renderer views are visible, the generalized warning self-heals, and the focused plus live proofs pass. |

## One callable-symbol predicate

The implementation must centralize the semantic question rather than repeat
truthiness checks:

- a stored target must be a qualified symbol;
- a compiled target is available only when `lookup-value` returns a function;
- an authored target is available when the acquired program row names that
  symbol and has `:seon.fn/fn-var? true`; and
- a non-function runtime value with the right name is uncallable, not a
  throwing renderer and not a successful resolution.

The render boundary can test the actual resolved value directly. The warning
boundary cannot invent a parent-side child lookup: it combines the acquired
current function rows with compiled `lookup-value`, following the existing
canvas check. A published authored function is database program truth; replay
or child-admission failure is a separate execution fault. Schema-error and
missing-schema warnings remain owned by their existing checks and are not
folded into “unresolved.”

Every affected entry is ordinary namespaced data identifying both the stored
symbol and location. Deterministic ordering is by symbol string, then location
string, with duplicates collapsed only when both are equal. The warning kind
becomes `:unresolved-symbol`; there is no parallel
`:canvas-unresolved` kind after migration.

## Error-as-value contract

The render owner constructs one schema-valid standard failure map before
choosing a view:

```clojure
{:seon.error/message "fn my.example/missing does not resolve to a function"
 :seon.error/kind    :agent
 :seon.error/data
 {:seon.render.canvas/error-symbol 'my.example/missing
  :seon.render.canvas/error-where  "schema :my.example/value, html"
  :seon.render.canvas/error-hint   "Define that qualified function or correct the stored symbol; the render self-heals on the next derivation."}}
```

`:seon.db/error`, the schema behind `:seon.render/error`, requires
`:seon.error/message` and `:seon.error/kind` and admits optional structured
`:seon.error/data` (`src/seon/db.cljs:24-30`). The current render calls instead
pass unregistered `:seon.error/symbol`, `:seon.error/where`, and
`:seon.error/hint` keys and omit the required kind
(`src/seon/render.cljs:368-370,615-618` and
`src/seon/render/canvas.cljs:552-577`). That is the schema sibling of the
nil-vanish defect and must be folded into this owner cut, not preserved as the
new error contract.

Register the three presentation fields in `seon.render.canvas`, place them
inside `:seon.error/data`, and classify `:seon.error/kind` from the selected
symbol using the existing agent/core boundary. The exact location string may
name a stable schema key, block name, canvas owner, or route name. No new
top-level error family is created. The AI projection is derived from this map
and the HTML projection is `canvas/error-card` over the same map. The function
schemas remain concrete: AI returns a string when a symbol was selected, HTML
returns hiccup when a symbol was selected, and absence of any
configured/matching renderer remains the only nil that permits generic
fallback.

This distinction is load-bearing:

- **no renderer selected** is normal and proceeds to the bounded generic tree;
- **renderer selected but uncallable** is a visible error value and stops
  dispatch; and
- **callable renderer throws** follows the existing guarded/strict failure
  path and fault classification.

## Exact owner cut

### Render owner

Owned source and tests:

- `src/seon/render.cljs`
- `src/seon/render/canvas.cljs`
- `test/seon/render_test.cljs`
- `test/seon/render/block_test.cljs`
- `test/seon/render/canvas_test.cljs`

After the Stage 1.5 UI migration has established final property dispatch:

1. replace the private AI-only `missing-render` with one helper that builds the
   closed failure map and projects it by view;
2. use `fn?`, not truthiness, at explicit slot and custom property resolution;
3. make selected-but-uncallable property dispatch return the visible failure
   immediately, never nil and never the generic value tree;
4. keep truly absent schema properties on the normal generic path; and
5. make every `render.cljs` call to `canvas/error-card` pass the valid
   message/kind/data shape, deleting its unregistered top-level
   `:seon.error/symbol`/`where`/`hint` convention; and
6. preserve `unwrap-response`, `strict-fail!`, and `canvas/error-card` as the
   only response, throw, and HTML-error seams.

The pre-Stage-1.5 `entity-render`, `entity-primary-schema`, and duplicate
`render-entity-html`/`render-entity-ai` selection mechanics are not new APIs to
preserve. The data-browser cut may delete or reshape them. This unit edits the
resulting one dispatch owner rather than restoring those functions to close a
line-number-specific bug.

### Warning acquisition and derivation

Owned source and tests:

- `src/seon/warn.cljs`
- `src/seon/agent/ctx/warnings.cljs`
- `test/seon/warn_test.cljs`
- `test/seon/agent/ctx/warnings_test.cljs`

Replace, do not retain beside, the canvas-specific mechanism:

- delete `check-canvas-unresolved` and its `:canvas-unresolved` result;
- add one `check-unresolved-symbols` entry to `checks`;
- rename the acquired canvas-only relation to a bounded collection of stored
  symbol slots carrying location, channel, and symbol;
- include context-block `:seon.render/ai`/`:seon.render/html`, canvas content,
  activated schema catalog renderer properties, and database route handlers;
- reuse the already acquired program function rows and final activated catalog
  projection rather than parse Malli properties a second way; and
- keep literals and strings out at acquisition/projection, since only symbols
  are callable slots.

Stage 1.5 must expose the activated catalog as ordinary projection data to this
acquisition boundary, or supply an equivalent database-derived catalog member.
Do not rebuild a candidate catalog inside `seon.warn`, and do not read the
process-local candidate collector: the warning must describe the same frozen
program projection the renderer uses.

The acquisition stays in the existing `db/execute-many` batches with explicit
work/result/weight bounds. Adding one unbounded query per slot family or a
renderer-local database read would violate both the warnings and data-browser
contracts.

### Route owner

Stage 4 owns `src/seon/web/router.cljs` and its tests. It must land first:

- the route-authority collapse leaves one request-time `lookup-value` call;
- matched-but-uncallable handler returns 500 and records one `:core` fault for
  that request;
- the generalized warning consumes route rows but performs no request and
  writes no fault; and
- correcting the route fact or defining the function removes the warning.

Stage 5 must not reopen route dispatch or add another record site. It only adds
the settled route rows to the generalized derived warning after Stage 4 freezes
their final shape.

### Explicit non-owners

- `src/seon/eval.cljs`: keep `lookup-value` nil-on-miss; no new resolver.
- `src/seon/execution.cljs`: keep its load attempt and per-call error value.
- `src/seon/schema.cljc`: Stage 1.5 owns catalog widening; this unit consumes it.
- `src/seon/render/canvas.cljs`: remains the single error-card seam and owns
  the registered presentation keys inside `:seon.error/data`; no second card.
- `docs/seon/architecture/ui.md`: update the target wording in the integration
  commit so every selected-but-uncallable renderer is visibly self-healing.

## Dependency order

1. Complete and freeze Stage 1.5 through the universal UI migration. This
   settles matching, property precedence, generic fallback, and the catalog
   data consumed by the warning. Close the nil-vanish issue in that cut only if
   it also implements the complete semantics and proofs here; otherwise leave
   it open for this unit.
2. Complete and freeze Stage 4 route authority, including its single
   request-fault behavior and final route-row projection.
3. In Stage 5, implement the render helper and generalized warning as one
   coherent unit, deleting the canvas-only derivation in the same commit.
4. Run focused tests, then the frozen live self-heal probe. Archive the issue
   only with the implementation commit and recorded evidence.

Implementing the generalized warning before Stage 1.5 would bind it to the
retiring entity-only catalog. Implementing route coverage before Stage 4 would
bind it to temporary static and database route authorities. Both would create
cleanup work rather than convergence.

## Focused acceptance

### Render behavior

- An explicit AI slot naming an absent symbol emits the deterministic
  self-healing line; an explicit HTML slot naming the same symbol emits the
  error-card seam.
- A schema property selected from a complete valid match behaves identically
  in both views. Neither view invokes the bounded generic renderer.
- A symbol resolving to a non-function produces the same visible error class,
  not a TypeError-derived “renderer threw” report.
- No selected renderer remains ordinary: a value with no property match uses
  the bounded generic projection and emits no unresolved warning.
- Defining or redefining the exact function changes the next render without a
  schema or block rewrite.
- A callable renderer that throws still exercises `strict-fail!`, records the
  existing correctly classified fault, and uses the established graceful
  projection when strict mode is off.

### Warning behavior

- One fixture each for a context AI slot, context HTML slot, canvas, schema AI
  property, schema HTML property, and route handler appears in one sorted
  `:unresolved-symbol` cluster.
- Literal AI text and literal Hiccup never appear; a current authored function
  row and a compiled core function are both clean.
- A database function row whose `:seon.fn/fn-var?` is false does not falsely
  satisfy a callable slot.
- Removing the slot or adding the function makes its affected entry vanish on
  the next pure check; no acknowledgement or warning datom exists.
- Acquisition tests prove every query uses the identical invocation database
  value and retains explicit work/result/weight limits.
- `rg "canvas-unresolved|check-canvas-unresolved" src test` returns no active
  implementation or test path after the migration.

Focused gate:

```bash
bin/test-cljs seon.render-test seon.render.block-test seon.render.canvas-test seon.warn-test seon.agent.ctx.warnings-test seon.web.router-test
```

The exact selector syntax should follow the runner's current accepted form at
execution time; the six namespaces above are the required coverage, not a
claim that a broad suite substitutes for them.

## Frozen live acceptance

Use a branch-scoped test cluster after all source owners release and record its
exact commit.

1. Install one block whose AI and HTML slots name a unique absent qualified
   symbol. Render the prompt and page: the prompt contains the self-healing
   message, the page contains the error card, and the warnings block contains
   one affected entry.
2. Register a unique ordinary map schema whose two render properties name the
   same absent symbol, then render a valid matching value through the universal
   browser. Both custom views are visibly failed and “as data” still shows the
   bounded generic projection only when explicitly selected.
3. Define the exact schema'd function in the owning execution child. Without
   rewriting the block, schema, or warning state, observe the next prompt/page
   render use the function and the derived warning disappear.
4. Against the Stage 4 route proof fixture, request one matched route with an
   absent handler: status is 500 and exactly one request fault is recorded.
   Correct the symbol or define the handler, then observe success and warning
   disappearance. A no-match request remains the established no-match policy.
5. Verify the Datastar page morph in a real browser and the long-lived SSE frame
   server-side. Confirm no browser console error and no repeated error datoms
   from render/feed recomputation.

The issue closes only when the same frozen revision passes these probes plus
the full CLJS gate. The program's final twice-frozen suites and live-cluster
graduation remain broader gates and are not implied by this unit.
