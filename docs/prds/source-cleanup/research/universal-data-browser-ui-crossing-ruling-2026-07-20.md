---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Universal data browser UI crossing ruling (2026-07-20)

## Decision

The render owner lands one small prerequisite before the value route commits
its HTML response. The current route-local `#seon-value-panel` wrapper cannot
provide a stable per-subtree identity, trusted paging address, or the ordinary
context needed by schema-property renderers. Admission, authorization, and
sampling may continue in the route lane, but the route must not freeze that
temporary wrapper as the UI contract.

The prerequisite extends `seon.render` in place. It adds one closed UI-only
request around the already-frozen drilled projection and gives `block` the
ordinary render request that every late-resolved custom renderer receives. It
does not add UI address fields to `:seon.render.value/drill-result`, introduce
an ambient projection, create a route-local renderer, or open another feed.

No user decision is required. The contradiction is dependency ordering, not a
product choice.

## Grounding

| Boundary | Maintained source | Constraint |
|---|---|---|
| One renderer | `src/seon/render.cljs`; `src/seon/render/AGENTS.md` | `block`, `data-panel`, late symbol resolution, guarding, and `unwrap-response` remain the only rendering path. |
| Frozen drill data | `src/seon/render/value.cljc` | The drilled projection owns bounded sampled data, paths, statuses, and explanations. It remains producer-neutral and carries no browser address. |
| Explicit schema projection | `src/seon/schema.cljc` | Database-pinned consumers use `matching-shapes-in` with their explicit projection. They never use an ambient activated projection to reinterpret a value from another database basis. |
| Presentation identity | `src/seon/web/view_unit.cljs` | `identity-token` is the existing deterministic, type-sensitive opaque identity mechanic. It renders, queries, caches, and publishes nothing. |
| Non-SSE HTML patch | `reference-code/datastar/library/src/plugins/actions/fetch.ts:530-570`; `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:120-153` | A status-200 `text/html` response becomes `datastar-patch-elements`. Without a selector and in outer mode, each returned root is matched to the existing element by its `id`. |
| Route crossing | [[value-route-crossing-rulings-2026-07-20]] | The route performs policy acquisition, authorization, producer selection, and bounded sampling before rendering one successful HTML subtree. |
| UI consumer cut | [[universal-data-browser-ui-implementation-readiness-2026-07-20]] | The database browser, eval disclosure, and plan proof consume the render and route contracts only after their owners commit and hand off. |

## Closed UI request

`seon.render` registers the following concrete shapes once. The names may use
the owning namespace's alias notation in source, but the public keys and
semantics are fixed here:

```clojure
(schema/register! :seon.render/schema-key :keyword)

(schema/register! :seon.render/value-selector
  [:or
   [:map {:closed true}
    [:seon.render/eval-id :string]]
   [:map {:closed true}
    [:seon.render/entity-id :int]]])

(schema/register! :seon.render/value-route-base :string)

(schema/register! :seon.render/value-request
  [:map {:closed true}
   [:seon.render/value-route-base :seon.render/value-route-base]
   [:seon.render/value-selector :seon.render/value-selector]
   [:seon.render/value-projection
    :seon.render.value/drilled-projection]])
```

The route has already admitted a positive safe entity id before constructing
the entity selector. The schema is structural; admission remains the owner of
numeric range and authorization.

The wrapper contains no database value, execution handle, schema projection,
configuration, status duplicate, DOM id, or preassembled query string. In
particular, it does not become a channel for smuggling the schema projection
through the frozen drill value. The selector stays structured so the renderer,
not the caller, owns canonical URL construction.

## One block call and one custom-render request

The render prerequisite atomically changes the typed-block call to:

```clojure
(block view configuration render-request x)
```

The four positional arguments are fully named by the function schema:

- `view` is `:html` or `:ai`;
- `configuration` is the acquired configuration singleton;
- `render-request` is the existing open
  `:seon.render/section-request`; and
- `x` is the tagged or ordinary value.

Every existing caller migrates in the same commit. There is no compatibility
arity, ambient fallback, or second block function.

The ordinary render request is also the exact custom-render invocation value.
For a raw value whose explicit schema projection selects a registered render
property, `block` late-resolves the property symbol and invokes it with:

```clojure
(assoc render-request
       :seon.config/configuration configuration
       :seon.render/node x
       :seon.render/schema-key matched-schema-key)
```

The renderer may return bare content or the existing response envelope;
`unwrap-response` remains the sole extraction seam. Tagged code, markdown,
source, an existing value projection, errors, and literal hiccup stay ahead of
raw-value schema dispatch. Therefore the explicit "as data" projection cannot
re-enter its custom renderer.

The owning operation passes its explicit committed schema projection as named
ordinary render context. If the load order permits the existing schema shape
to be referenced directly, add an optional `:seon.schema/projection` entry to
`:seon.render/section-request`. If that reference would create a schema-load
cycle, register the optional projection context in the dependency-neutral leaf
that already owns the render schemas and reference it from both sides. Do not
replace it with `schema/current-projection`, an untyped map, a UI-wrapper key,
or a second projection cache. Database-pinned dispatch calls
`schema/matching-shapes-in`; an ordinary process-local caller may explicitly
supply the activated projection it already owns.

## Stable subtree identity law

The root DOM id is:

```text
seon-value-<identity-token>
```

`identity-token` consumes exactly these trusted identity facts:

```clojure
{:seon.agent/id                 agent-id
 ;; exactly one of:
 :seon.render/eval-id          eval-id
 :seon.render/entity-id        entity-id
 :seon.render/path-text        (pr-str canonical-path)}
```

The map contains exactly one selector key. `path-text` is safe identity input
because the route has already accepted the strict canonical path codec and the
render projection retains only original drill paths. Encoding the path as its
canonical scalar text also fits `view-unit`'s identity-value contract without
widening it to arbitrary collections.

The id excludes offset, page size, route base, availability, summary, schema
status, projection bytes, and render time. Consequently:

- the same authorized producer and logical path has the same id at page zero,
  later offsets, availability transitions, and repeated renders;
- a different agent, producer selector, or logical path has a different id;
  and
- a nested control can predict the exact id of the subtree its response will
  replace.

No caller supplies the DOM id. `seon.render` derives it from the trusted
request. A route-local constant, hash of response bytes, random id, offset-keyed
id, or browser-generated id violates this law.

## URL and Datastar law

`data-panel` derives every drill URL inside `seon.render` from:

- the trusted route base;
- exactly one structured selector;
- an original retained path from the projection; and
- the next admitted offset.

Every query value is percent-encoded independently. The renderer never
concatenates a caller-provided query fragment and never serializes a projected
display key as a path. A node without a retained original drill path emits no
remote drill control.

The successful route response contains exactly one root element with the
derived id. It sends no `Datastar-Selector` response header. Datastar's
maintained default outer-patch branch parses each response root, finds the
existing element by `child.id`, and morphs that element. This keeps target
derivation in one place and avoids a second route-owned CSS selector contract.
Non-200 EDN responses remain non-morphing.

Available and honestly unavailable status-200 projections use the same wrapper
and root identity. Retirement or eviction therefore updates the existing
subtree rather than appending a second panel.

## Dependency-cycle disposition

`seon.web.view-unit` currently depends only on `seon.schema`, so using its pure
`identity-token` from the renderer is acceptable only if the resulting
namespace graph remains acyclic. The implementation begins with the shortest
falsifier: load the complete render namespace graph after adding that require.

If it creates or inverts a maintained dependency boundary, move the existing
pure identity implementation and its test once into a dependency-neutral
render leaf, then update the web consumer to use that owner. Do not copy its
encoding into `seon.render`, duplicate it in the route, or introduce a second
token format. This is a relocation of the one presentation-identity mechanic,
not a new mechanism.

## Dependency-ordered implementation

### 1. Render prerequisite

Owned paths:

- `src/seon/render.cljs`;
- `test/seon/render/block_test.cljs`; and
- `test/seon/render_test.cljs`.

If and only if the dependency-cycle falsifier requires relocation, the same
atomic unit also owns `src/seon/web/view_unit.cljs`, its one destination leaf,
and `test/seon/web/view_unit_test.cljs`.

The commit registers the closed request, derives stable ids and URLs, renders
the one root, adds explicit schema-property dispatch, and migrates every block
caller. It does not edit the value sampler, route, database browser, eval
handler, plan owner, operator, retained branch, or B2 artifacts.

Focused proof establishes:

- the UI wrapper rejects extra or malformed keys;
- id equality is independent of map insertion order, offset, availability,
  and projection bytes;
- id inequality follows agent, selector, and canonical-path differences;
- every URL component is encoded once from structured trusted input;
- projected and otherwise non-drillable keys emit no control;
- tagged precedence and existing-projection non-recursion remain unchanged;
- explicit-projection custom dispatch selects only a validated property row;
- the custom fn receives the exact ordinary request plus configuration, node,
  and schema key;
- late redefinition is observed;
- missing and throwing custom renderers remain visible guarded failures; and
- no old block arity remains in source or tests.

### 2. Value route

After the render handoff, the route removes its temporary hard-coded wrapper.
Only after authorization and producer selection does it construct the closed
value request and call `block` with the ordinary request context. It continues
to own HTTP status, content type, `no-store`, absence uniformity, and transport
failure classification.

Route proof establishes one exact root id for available and unavailable
responses, page zero and nonzero offsets keep the same id, each body has one
root, no selector header is present, and no schema projection or UI id appears
inside the frozen drill projection.

### 3. Consumers

The database-browser and eval consumer lane starts only after the route commits
and explicitly hands off its crossing. The plan lane starts only after its
current unrelated owner commits or hands off the dirty paths. Their ownership
and live proof remain as specified in
[[universal-data-browser-ui-implementation-readiness-2026-07-20]].

## `/data` route disposition

Stage 1.5 preserves the existing `/data` and `/data/feed` entries in
`seon.web.router/static-supplement`. It changes their projection and drill UI,
not their route authority.

Stage 4 atomically adds the corresponding database route rows and deletes the
two static entries. Deleting them during Stage 1.5 would make the database
browser unreachable; adding duplicate database rows early would create two
route authorities. Neither is permitted.

## Exit

The render prerequisite closes when its focused suites pass and the route lane
has an explicit callable handoff. The route closes when its HTML responses use
the render-owned wrapper and stable id without a route-local selector or
projection copy. Integrated Stage 1.5 proof then runs against one frozen source
revision through focused suites, the full CLJS gate, work-bound server probes,
retirement behavior, server-side SSE observation, and a real browser.
