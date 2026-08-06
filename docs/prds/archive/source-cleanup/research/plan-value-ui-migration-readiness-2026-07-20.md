---
type: research
status: complete
tags: [research, rendering, schema, plan]
---

# Plan-value UI migration readiness (2026-07-20)

## Decision

The plan UI lane must wait for the current generated-root repair to commit or
hand off `src/my/plan.cljc` and `test/my/plan_test.cljs`. After that handoff,
the migration is source-ready only if it closes one contract omitted by the
earlier UI ruling: the normal `my.plan/tree` result is a vector, while
`seon.render` intentionally performs schema-property dispatch only for maps.
Adding render properties to today's loose `::tree-result` union therefore does
not make the normal plan forest dispatchable.

Preserve the public `tree`, `document`, and `reconcile!` shapes. Introduce one
ordinary, closed `::plan-value` map containing a vector of concrete recursive
plan nodes. The context acquisition/composition function wraps the existing
structural read in that value; evals may return the same ordinary value. The
pure HTML and AI renderers consume it through schema properties. This is the
smallest compatible implementation of the already-ruled “concrete nested
plan-value schema”; changing `tree` itself would break agent-facing examples,
reconciliation, tests, and recorded model behavior.

The existing HTML surface also uses fields absent from `tree`: creation and
completion times and message origin. The one structural projection must carry
those source facts if the surface retains them. Roll-up, ready, blocked,
focused-root, and next-step state remain pure derivations from status,
children, and needs; they are not stored in the value.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract used here |
|---|---|---|
| Plan graph and structural read | `src/my/plan.cljc`; `src/my/plan/internal.cljc` | `tree` acquires one bounded row set and derives `subtree-from-rows` / `forest-from-rows`; `document` and `reconcile!` consume the existing raw tree/forest shape. |
| Schema registration | `src/seon/schema.cljc`; `src/seon/render.cljs` | Activated schema rows retain `:seon.render/html` / `:seon.render/ai`; custom selection uses `matching-shapes-in` and only considers a map value. |
| Custom invocation | `src/seon/render.cljs` | The pure renderer receives the section request plus configuration, `:seon.render/node`, and matched `:seon.render/schema-key`; `unwrap-response` remains the extraction seam. |
| Context render invocation | `src/seon/execution/runtime.cljs`; `src/seon/execution.cljs` | A configured block symbol is invoked with section input and `invoke-selected!`. It is an acquisition/composition function, not automatically a value-property renderer. |
| Current HTML plan path | `src/my/plan/internal.cljc` | `acquire-html-plan-rows` is a second query; `build-forest` creates a second child key and derived display fields; `plan-block-html` performs acquisition and presentation together. |
| Maintained UI ruling | [[universal-data-browser-ui-crossing-ruling-2026-07-20]] | Property renderers are pure over an ordinary value; no `my.plan` branch enters `seon.render`. |

No new dependency version is selected. This unit strengthens the repository's
existing Malli registry, Datahike read, and render dispatch mechanisms.

## Exact current structural value

`subtree-from-rows` currently emits a node with required
`:my.plan/id`, `:my.plan/title`, and `:my.plan/status`; optional
`:my.plan/goal`, `:my.plan/expect`, `:my.plan/pace`,
`:my.plan/description`, and `:my.plan/needs`; and recursive children under
`:my.plan/_parent`. `tree` returns one such map for `::root`, a vector of them
for the ordinary/default or `::all?` read, and `[]` for no plan. The registered
boundary is only `[:or :map [:vector :map]]`.

The HTML-only path independently queries rows, renames children to
`:my.plan/children`, and adds `:my.plan/created-at`,
`:my.plan/completed-at`, a derived `:my.plan/message?`, and derived waiter
titles. It then recomputes roll-up/readiness/blockage over those rows. This is
the duplicate path to delete, not a function to register unchanged.

## Concrete plan-value contract

Register the recursive node once, then the closed property-bearing wrapper:

```clojure
(schema/register! ::plan-value-node
  [:schema
   {:registry
    {::value-node
     [:map {:closed true}
      [::id ::id]
      [::title ::title]
      [::status ::status]
      [::goal {:optional true} ::goal]
      [::expect {:optional true} ::expect]
      [::pace {:optional true} ::pace]
      [::description {:optional true} ::description]
      [::created-at {:optional true} ::created-at]
      [::completed-at {:optional true} ::completed-at]
      [::message {:optional true} :seon.db/ref]
      [::needs {:optional true} [:vector [:map {:closed true} [::id ::id]]]]
      [::_parent {:optional true} [:vector [:ref ::value-node]]]]}}
   [:ref ::value-node]])

(schema/register! ::roots [:vector ::plan-value-node])

(schema/register! ::plan-value
  [:map {:closed true
         :seon.render/html 'my.plan.internal/plan-html
         :seon.render/ai   'my.plan.internal/plan-ai}
   [::roots ::roots]])

```

The exact symbol names may follow the owner's final naming, but the shape laws
are fixed: one map wrapper, one required roots vector, concrete recursive
nodes, closed maps, and both render properties on the wrapper. Empty plan is
`{::roots []}` and therefore remains a valid renderable value. A root-specific
read becomes `{::roots [root]}`. No property belongs on the old map/vector
union, and `seon.render` must not grow vector dispatch just for plans.

The structural selector and `subtree-from-rows` add the three source fields
used by the current human surface: `::created-at`, `::completed-at`, and
`::message`. Message presence supplies the icon without a derived boolean.
Waiter titles are derived by inverting needs over the same roots. Every other
signal remains a pure walk over the ordinary value.

## Functions and configuration

Retain two pure property renderers:

- `plan-html` accepts the ordinary custom-render request, reads
  `:seon.render/node` as `::plan-value`, and returns hiccup (bare or through
  the existing HTML response envelope); it performs no database operation.
- `plan-ai` accepts the same request and returns bounded text. It must honor
  the acquired render/token configuration rather than materialize an
  unbounded forest into prompt text.

Add one context acquisition/composition function in the existing plan owner.
It calls the existing structural acquisition once, constructs `::plan-value`,
and feeds that value to the pure presentation owner. It must not issue another
query, activate an ambient schema projection, or retain a compatibility HTML
query. Whether it invokes the pure function directly or through `render/block`
must be settled by the implementation's namespace-cycle falsifier; copying
property selection is forbidden. A direct pure call is acceptable for the
configured context block only if the same function is also exercised through
generic schema-property dispatch for eval-returned values.

`config/system.edn` and `config/minimal-plan.edn` change only their HTML plan
symbol from `plan-block-html` to that acquisition/composition owner. The
bounded AI context continues to use `plan-block` (and the autocomplete profile
continues to use it): replacing it with the full structural forest would alter
the prompt window and cache contract. `generate-code-plan-block` also remains.
Thus “AI property renderer” proves ordinary-value extensibility without
silently replacing the specialized bounded context acquisition.

Delete in the same unit:

- `html-plan-selector` and `acquire-html-plan-rows`;
- `row->node` and `build-forest` as a second structural projection (move only
  genuinely reusable pure presentation helpers to consume `::_parent`);
- the database-acquiring `plan-block-html`; and
- the test that stubs `db/query` inside `plan-block-html`.

## Compatibility and interaction with the active repair

Do not change `tree` or `document` return values, `reconcile!`'s
`:my.plan/tree` input, `:seon.render/prefill-fn 'my.plan/document`, or their
agent-facing examples. Existing tests explicitly expect vector and map tree
results, and the function corpus teaches agents to round-trip `document` into
`reconcile!`.

At audit time, the generated-root repair owns uncommitted changes in
`src/my/plan.cljc` and `test/my/plan_test.cljs` (terminal delivery fencing and
bounded eval evidence). The UI lane cannot begin until that owner makes a
coherent commit or explicit path handoff. Its changes are semantically
independent but overlap the exact two files required here; they must be
preserved and tested together, never reverted or reimplemented.

## Ownership and protected paths

After handoff, the plan lane owns:

- `src/my/plan.cljc`;
- `src/my/plan/internal.cljc`;
- `test/my/plan_test.cljs`;
- `config/system.edn`; and
- `config/minimal-plan.edn`.

Protected are `src/seon/render.cljs`, `src/seon/schema.cljc`, value-route and
web-consumer files, operator/lifecycle paths, retained branches, and
`.shadow-cljs-b2/` / `out-b2/`. If the acquisition function cannot call the
generic renderer without a namespace cycle, stop and return that falsifier to
the render owner; do not widen this lane into `seon.render`.

## Focused and live proof

Focused tests must prove:

- the registered wrapper accepts empty, one-root, and nested forests and
  rejects an extra key, a missing required node key, and malformed needs;
- ordinary default `tree` remains a vector, root `tree` remains a map, and
  `document` → `reconcile!` stays byte/identity compatible;
- one structural acquisition supplies the configured HTML surface and no
  HTML-only query executes;
- generic explicit-projection dispatch selects `plan-html` and `plan-ai` for
  `::plan-value`, with no branch in `seon.render` naming `my.plan`;
- deleting a required nested title makes the wrapper an invalid diagnostic
  candidate and never invokes custom code;
- the explicit “as data” projection does not recurse into the custom renderer;
- HTML preserves nesting, progress/readiness/blockage, message origin, and
  timestamps from the ordinary value; and
- AI text is deterministically bounded by the acquired configuration.

Run the focused `my.plan` and render dispatch suites, then the complete CLJS
gate at the integrated Stage 1.5 freeze. Live proof returns a real plan value
from an eval, observes property-selected HTML and AI, switches the identical
value to generic data, and observes an invalid nested value render red with a
bounded explanation. The `/agent/{id}` plan surface must use one structural
read, preserve its Datastar interactions, and emit no browser console errors.

## Exit

The earliest unsettled contract is the concrete map wrapper and its one
acquisition/composition owner. Implementation starts only after the dirty plan
owner commits or hands off. It exits when the HTML-only database query is gone,
the public planning API is unchanged, property dispatch works for the ordinary
wrapper, focused and complete tests pass, and the real browser/eval proof is
green against one frozen revision.
