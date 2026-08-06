---
type: research
status: complete
tags: [research, rendering, schema, plan]
---

# Plan-value UI crossing ruling (2026-07-20)

## Decision

The plan extensibility proof uses one new closed ordinary value without
changing the public planning document API. `my.plan/tree`, `my.plan/document`,
and `my.plan/reconcile!` retain their current map/vector shapes and byte-level
round-trip behavior. A context-only acquisition/composition function acquires
the existing plan rows once, constructs the property-bearing value, and calls
the pure HTML renderer directly. The generic render engine independently
dispatches the identical value when an eval or another ordinary producer
returns it.

No user decision is required. The gap is an implementation-boundary question,
not a product choice.

## Grounded constraints

| Boundary | Maintained source | Constraint |
|---|---|---|
| Plan facts and structural read | `src/my/plan.cljc`; `src/my/plan/internal.cljc` | The ordinary read already acquires a bounded row set and derives a cycle-safe forest. Public callers and reconciliation consume its existing raw shape. |
| Schema registration | `src/seon/schema.cljc`; `src/seon/render.cljs` | Custom property dispatch applies to a validated map value under an explicit activated projection. It intentionally does not dispatch on a bare vector. |
| Context invocation | `src/seon/execution/runtime.cljs` | A configured HTML symbol is selected and called directly with a section request. That invocation is acquisition/composition, not schema-property selection. |
| One renderer | `src/seon/render.cljs`; `src/seon/render/AGENTS.md` | Generic custom selection, late symbol resolution, guarding, and response unwrapping remain in the existing engine. No `my.plan` dispatch branch is added. |
| Data-oriented model | `src/my/AGENTS.md`; `docs/seon/architecture/data-model.md` | Source facts travel in the value. Roll-up, readiness, blockage, focus, waiters, and presentation flags are derived and never stored. |
| Prior rulings | [[plan-value-ui-migration-readiness-2026-07-20]]; [[universal-data-browser-ui-crossing-ruling-2026-07-20]] | The HTML-only query is deleted, the bounded AI context stays specialized, and an existing data projection remains the non-recursive "as data" escape hatch. |

No dependency version changes. This strengthens the existing Malli, Datahike,
and render mechanisms in place.

## Exact ordinary value

Register one closed need reference, one closed recursive node, one roots
collection, and one closed property-bearing wrapper. Names may use local alias
notation in source, but these public keys and meanings are fixed:

```clojure
(schema/register! ::plan-value-need
  [:map {:closed true}
   [::id ::id]])

(schema/register! ::plan-value-node
  [:schema
   {:registry
    {::value-node
     [:map {:closed true}
      [::id ::id]
      [::title ::title]
      [::status ::status]
      [::created-at ::created-at]
      [::goal {:optional true} ::goal]
      [::expect {:optional true} ::expect]
      [::pace {:optional true} ::pace]
      [::description {:optional true} ::description]
      [::completed-at {:optional true} ::completed-at]
      [::message {:optional true} ::message]
      [::needs {:optional true} [:vector ::plan-value-need]]
      [::_parent {:optional true} [:vector [:ref ::value-node]]]]}}
   [:ref ::value-node]])

(schema/register! ::plan-value-roots [:vector ::plan-value-node])

(schema/register! ::plan-value
  [:map {:closed true
         :seon.render/html 'my.plan.internal/plan-html
         :seon.render/ai   'my.plan.internal/plan-ai}
   [::roots ::plan-value-roots]])
```

`::created-at` is required because every stored plan step already requires that
source fact and the retained human surface displays it. `::completed-at` and
`::message` remain optional because they are ordinary conditional source
facts. Message origin is represented by the existing ref, not a derived
`message?` boolean. A need carries only its target identity; title, status, and
done-ness resolve from the same forest.

Children remain under `:my.plan/_parent`, the established structural/document
key. The HTML-only `:my.plan/children` key does not survive. Empty plan is
`{:my.plan/roots []}`; a selected root is a one-element roots vector. Render
properties belong only on the wrapper. They do not belong on the old
map/vector union, and `seon.render` does not gain plan-specific vector
dispatch.

## One walk plus a compatibility projection

The plan owner adds one canonical pure row-to-enriched-node walk. It carries
the source facts required by both views and produces `::plan-value`. The
context acquisition/composition owner uses the existing agent-row query once
and feeds its result to that walk.

The existing `tree` contract must not silently accrete timestamps or message
refs. Its raw node/forest result is a compatibility projection of the same
canonical enriched walk: recursively remove `::created-at`, `::completed-at`,
and `::message`, then return the same root map or forest vector it returns
today. This keeps the structural traversal single while preserving exact
agent-facing examples, tests, `document`, and `reconcile!` behavior.

The configured composition function lives in `my.plan`, where the existing
row acquisition is already owned, and is public only so a stored symbol can
resolve it. It is not marked agent-facing. A discoverable name such as
`my.plan/plan-surface` distinguishes acquisition/composition from the pure
property renderer.

## Pure derived human signals

`my.plan.internal/plan-html` accepts one ordinary
`:seon.render/section-request`, reads the wrapper from `:seon.render/node`, and
performs no database operation. It may flatten the wrapper to ordinary rows in
memory, deriving parent refs from nesting, so the existing pure plan
derivations remain reusable.

The migrated surface preserves:

- nested roots and oldest-first stable ordering;
- subtree done/total roll-up and progress percentage;
- ready, blocked, active, focused-root, and next-step signals;
- inverse waiter titles derived from needs;
- created and completed timestamps;
- message-origin icon and detail from `::message` presence;
- completed-step visibility and the bounded recent-completion tail; and
- the existing Datastar signal names, click expressions, classes, and
  whole-element morph behavior.

The value does not carry stored `message?`, waiters, roll-up, ready, blocked,
focus, next, counts, percentages, or presentation state. Those remain pure
functions of the source facts.

## Direct configured composition, generic property dispatch

The configured `my.plan/plan-surface` calls `plan-html` directly after one
acquisition and passes the normal composed request, including configuration
and the new `:seon.render/node`. It does not call `seon.render/block`.

That direct call is required for three concrete reasons:

1. The configured context path already selected its stored HTML symbol; it is
   an acquisition/composition boundary, not a raw-value dispatch boundary.
2. Its current request does not guarantee the explicit activated schema
   projection required for basis-honest generic property selection.
3. Requiring the complete render engine from the editable plan owner would
   add an unnecessary dependency edge and possible load cycle merely to
   rediscover a renderer the context block already selected.

This does not duplicate schema selection. Generic `seon.render/block` must
still select `plan-html` and `plan-ai` from the activated properties when the
identical wrapper arrives as an ordinary eval-returned value. The generic
proof uses an explicit projection. No plan name or shape branch enters the
render engine.

Both property renderers take one section request and are pure. `plan-ai` uses
the acquired configuration and the existing bounded value writer/sampler (or
its already-owned bounded text seam); it never materializes the forest with
raw `pr-str`. It is the ordinary-value extensibility twin, not a replacement
for the specialized bounded context producer.

## Configuration cut

Change exactly the two normal HTML block symbols:

- `config/system.edn` changes the plan block's `:seon.render/html` from
  `my.plan.internal/plan-block-html` to `my.plan/plan-surface`.
- `config/minimal-plan.edn` makes the same HTML-only change.

Keep unchanged:

- the normal AI symbol `my.plan.internal/plan-block`;
- the autocomplete plan AI symbol and its token cap;
- `my.plan.internal/generate-code-plan-block`; and
- reconciliation prefill through `my.plan/document`.

The specialized AI context remains intentionally windowed. Replacing it with
the complete structural forest would change prompt size, cache identity, and
the measured planning contract.

## Same-unit deletions

Delete after the replacement is exercised:

- `html-plan-selector`;
- `acquire-html-plan-rows`;
- HTML-only `row->node` and `build-forest`;
- database-acquiring `plan-block-html`;
- the `:my.plan/children`, `:my.plan/message?`, and stored-waiter compatibility
  structures from the HTML path; and
- the test that replaces `db/query` while invoking `plan-block-html`.

Presentation helpers may remain only after conversion to the wrapper's
`::_parent` structure and pure wrapper-derived rows. There is no compatibility
query or second forest constructor.

## Focused proof

Schema tests prove:

- empty, one-root, and recursively nested wrappers validate;
- an extra wrapper or node key is rejected;
- a missing title or created-at is rejected; and
- a malformed need reference is rejected.

Compatibility and acquisition tests prove:

- default `tree` remains the same forest vector, root `tree` remains the same
  subtree map, and empty behavior is unchanged;
- `document` to `reconcile!` remains a no-op with byte/identity-compatible
  input;
- the configured surface performs exactly one existing plan-row acquisition;
  and
- no HTML-only query executes.

Pure renderer tests prove nesting, ordering, progress, readiness, blockage,
focus, next step, inverse waiters, message origin, timestamps, completed
visibility, recent completion, ordinary-wire hiccup, and retained Datastar
interactions.

Generic render tests prove:

- explicit-projection dispatch selects both `plan-html` and `plan-ai`;
- removing a required nested title produces an invalid diagnostic candidate
  and never calls custom code;
- the pre-produced generic data projection does not recurse; and
- AI bytes are deterministic and both work and output are bounded.

Configuration and deletion tests prove that only the two HTML symbols changed,
all specialized AI symbols remain, the retired HTML acquisition names are
absent, and `src/seon/render.cljs` contains no `my.plan` property-dispatch
branch. Its existing generic identity extraction is not plan dispatch and is
outside this cut.

## Exit

The boundary closes when the one wrapper is registered, the one acquisition
feeds the configured surface, the old public planning shapes are unchanged,
both properties dispatch generically, the HTML-only query and forest are gone,
focused and complete CLJS gates pass, and the real eval/browser proof is green
against one frozen revision.
