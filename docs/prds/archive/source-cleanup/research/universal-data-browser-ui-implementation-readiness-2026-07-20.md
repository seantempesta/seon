---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Universal data browser UI implementation readiness (2026-07-20)

## Decision

The final Stage 1.5 consumer cut is source-ready after two crossing contracts
are ruled explicitly. The existing owners are otherwise clear: `seon.render`
owns schema-property dispatch and the generic tree, `seon.web.debug` owns the
`/data` projection, `seon.handlers.eval` owns technical eval disclosure, and
`my.plan` owns the plan extensibility proof.

The cut adds no renderer registry, browser EDN parser, value feed, validation
pass, database acquisition, or child lookup. It consumes the already-bounded
projection and the read-only value route. The two unsettled crossings are:

- the closed drill projection carries no trusted selector, route base, or DOM
  target, while the current renderer receives no request context from which to
  derive them; and
- deleting the current `/data` static routes during Stage 1.5 conflicts with
  the Stage 4 route-authority cut, because no database route rows replace them
  in the current route unit.

The recommended rulings are a closed UI-only render request around the frozen
projection, and deferral of `/data` route deletion to the atomic Stage 4 route
cut. Neither ruling changes the projection, drill, authorization, or transport
contracts.

## Dependency and reference ledger

| Dependency or mechanism | Selected source | Contract consumed here |
|---|---|---|
| Guarded render dispatch | `src/seon/render.cljs` | `block` is the one tagged-value dispatcher; `unwrap-response` is the one response-envelope extraction; caught failures use the existing strict/graceful guard. |
| Activated schema matching | `src/seon/schema.cljc` | Projection `shape-rows` already carry `:seon.render/html` and `:seon.render/ai` for any map shape. `matching-shapes` returns deterministic validated rows; `candidate-shapes` is diagnostic only. |
| Bounded value projection | `src/seon/render/value.cljc` | `render-html-data` and `drill-value` own sampling, status, explanation, original paths, omission honesty, and work bounds. The UI renders their data without sampling or validating again. |
| Value route crossing | [[value-route-crossing-rulings-2026-07-20]] | A successful or honestly unavailable read is status 200 `text/html`, bounded failures remain EDN, and the route performs authorization and producer selection before rendering. |
| Datastar non-SSE fetch | `reference-code/datastar/library/src/plugins/actions/fetch.ts:530-570` | A status-200 `text/html` response becomes `datastar-patch-elements`; optional selector and mode response headers control the target. Non-200 responses do not morph. |
| Whole-view Datastar feed | `src/seon/web/datastar.cljs`; `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` | The page remains one stable `#app-view` morphed from one database-derived feed. Drill fetches do not open another feed. Long-lived feed proof is server-side. |
| Database browser | `src/seon/web/debug.cljs` | `render-data!` already receives the feed's immutable database value and uses it for the bounded index page and fleet summary. Only the raw datom presentation changes. |
| Eval disclosure | `src/seon/handlers/eval.cljs` | The cheap activity row, source, ordinary eval failure, and stored one-line result fallback remain. Live successful detail comes from the authorized value route. |
| Plan value | `src/my/plan.cljc`; `src/my/plan/internal.cljc` | `my.plan/tree` is the ordinary structural read. The HTML-only query/render path is superseded by pure renderers registered as schema properties on a concrete nested plan value. |

## Existing render owner and exact extension seam

`src/seon/render.cljs` already owns the complete generic value presentation:

- `value-details` owns local `<details>` disclosure;
- `map-node`, `seqish-node`, and `value-node` render the sampled tree;
- `data-panel` renders the projection header and tree; and
- `block` owns guarded tagged dispatch and the raw-value fallback.

The current tagged precedence is code, markdown, source, an existing
`:seon.render.value/tree` projection, error, literal hiccup, then raw generic
data. An existing projection must remain ahead of schema-property dispatch so
the explicit "as data" path cannot recurse. The other tagged cases remain
unambiguous and unchanged.

For an ordinary raw value, dispatch first preserves the existing explicit
per-entity override, then selects the first deterministically ordered valid
row from `schema/matching-shapes` carrying the requested render property. An
invalid structural candidate never invokes custom code. A missing or throwing
registered renderer remains a visible guarded failure; it must not silently
fall through and conceal a broken registration. A no-schema value uses the
generic tree normally.

The generic projection header adds the primary schema, ordered additional
badges, green valid, red invalid, hollow shape-only status, the already-bounded
invalid explanation on hover, and honest partial state. Hover is HTML/CSS and
performs no request. Remote paging uses only paths retained by the projection;
an output-local non-drillable key index never becomes a URL path.

## Duplicate consumers to migrate and delete

### Database browser

`src/seon/web/debug.cljs:data-element` currently emits one raw
`[:pre (pr-str (:datahike.index-page/datoms page))]`. Replace that presentation
with the one bounded `render/block` projection. Preserve `render-data!`, its
database-value pin, `data-feed-definition`, and the existing Datastar feed.
Entity drill remains parent-owned and never sends a database entity or handle
to an execution child.

### Eval technical disclosure

`src/seon/handlers/eval.cljs:render-html` currently displays a successful
`:seon.eval/result-edn` as a highlighted source block. Preserve the fixed-size
activity row, source, ordinary eval-error presentation, and short stored
summary. Replace only the successful technical result body with an explicit
fetch of the authorized value route. The parent never treats stored EDN as the
live value and never embeds a large result in each transcript morph.

### Plan extensibility proof

`src/my/plan/internal.cljc:acquire-html-plan-rows` and `plan-block-html` form a
second HTML-only database acquisition and tree construction path. They cannot
serve as the custom renderer proof unchanged.

`src/my/plan.cljc` replaces the loose `::tree-result` boundary with a concrete
nested plan-value schema carrying `:seon.render/html` and `:seon.render/ai`
properties. The existing `my.plan/tree` read produces that ordinary value
once. `src/my/plan/internal.cljc` retains pure value-to-hiccup and
value-to-text presentation, then deletes `acquire-html-plan-rows` and the
database-acquiring `plan-block-html`. `config/system.edn` and
`config/minimal-plan.edn` point at the ordinary acquisition/composition owner.
No branch in `seon.render` names `my.plan`.

At this audit, `src/my/plan.cljc` and `test/my/plan_test.cljs` contain unrelated
uncommitted work owned by another lane. The plan UI cut must wait for an
explicit handoff or coherent commit and preserve that work.

## Crossing gap 1: trusted UI address and stable morph target

The frozen `:seon.render.value/drill-result` and drilled projection are closed,
producer-neutral values. They carry path, offset, page size, summary, tree,
statuses, and explanation. They deliberately do not carry:

- whether the producer selector is an eval or entity;
- the trusted route agent id;
- an authorized route base or selector query;
- a deterministic DOM subtree id; or
- a browser patch selector.

`render/block` currently accepts only `(view configuration x)`. Consequently,
neither `data-panel` nor a custom renderer has the trusted request context
needed to build a value-route URL, and the route cannot identify the precise
subtree its successful HTML should outer-morph. Adding those fields to the
closed drill projection would mix UI addressing into the producer contract and
break its frozen schema.

Recommended ruling: register one closed UI-only render request owned by
`seon.render`. It wraps the frozen projection and carries the trusted value
route address plus a deterministic DOM id. The eval, database-browser, and
value-route consumers construct it only after their existing selection and
authorization work. The renderer derives click URLs from this request and
returns the same root id at every page. The HTTP response may use Datastar's
maintained selector response header or an exact matching root id, but the one
chosen form must be frozen in route and render tests.

The same ruling must freeze the custom renderer invocation shape. The prior UI
boundary says the property renderer receives the ordinary render request plus
`:seon.render/schema-key`, but the current `block` call has no section request,
agent id, or selector context. The new request must identify the exact ordinary
map passed to late-resolved custom functions without introducing a second
envelope or ambient context.

## Crossing gap 2: `/data` route deletion is sequenced in Stage 4

`src/seon/web/router.cljs:static-supplement` currently owns `/data` and
`/data/feed`. `src/seon/route.cljs` has no database route rows for either path.
The current Stage 1.5 route unit adds only `GET /agent/{id}/value`; the later
Stage 4 route-authority unit owns the product-route collapse.

Deleting the two static entries in Stage 1.5 would therefore break the
database browser unless this consumer cut widened into Stage 4 and introduced
new route rows. That would violate the ordered roadmap and overlap the route
authority owner.

Recommended ruling: Stage 1.5 migrates `/data` markup and drill behavior while
leaving its two static routes intact. Stage 4 adds the database route rows and
deletes the static copies atomically. The earlier UI migration boundary's
route-deletion language is superseded by this sequencing correction; Stage
1.5 still creates no duplicate route.

## Exact source and test ownership

### Render lane

Owned after the UI-address ruling:

- `src/seon/render.cljs`;
- `test/seon/render/block_test.cljs`; and
- `test/seon/render_test.cljs`.

Protected:

- `src/seon/render/value.cljc` and its tests;
- `src/seon/schema.cljc`, `src/seon/config.cljs`, and their tests;
- `src/seon/web/datastar.cljs`; and
- route, execution-host, operator, retained-branch, and B2 artifact owners.

The focused proof covers tagged precedence, activated valid-property dispatch,
invalid-candidate non-dispatch, no-schema fallback, existing-projection
non-recursion, late symbol redefinition, guarded custom-render failure,
deterministic badges, explanation hover markup, non-drillable nodes, and stable
UI address/root identity.

### Web consumer lane

Owned only after the route worker commits and hands off its crossing:

- `src/seon/web/debug.cljs`;
- `src/seon/handlers/eval.cljs`;
- `test/seon/web/serve_test.cljs`; and
- `test/seon/handlers/eval_test.cljs`.

The route worker's `src/seon/route.cljs`, `src/seon/web/serve.cljs`,
`src/seon/web/router.cljs`, and focused route tests remain protected until that
handoff. `src/seon/web/datastar.cljs` remains unchanged.

The server proof asserts `/data` uses one identical immutable database value
through index selection and projection, output contains the bounded value tree
instead of raw EDN `<pre>`, and entity drill performs no execution send. Eval
tests distinguish available success, ordinary eval failure, retired or evicted
status-200 unavailability, uniform missing or unauthorized 404, and transport
503.

### Plan lane

Owned only after the current unrelated plan diff is handed off:

- `src/my/plan.cljc`;
- `src/my/plan/internal.cljc`;
- `test/my/plan_test.cljs`;
- `config/system.edn`; and
- `config/minimal-plan.edn`.

The focused proof replaces the test that stubs the HTML-only database query
with structural-read and pure property-dispatch tests. It proves a plan value
selects its renderers by schema properties, renders through generic "as data",
becomes invalid generic data when a required key is removed, and leaves no
`my.plan` dispatch in `seon.render`.

## Integrated live and browser falsifiers

Run all evidence against one frozen source revision after the route and UI
owners are integrated.

### Server and work bounds

- Canonical eval and entity requests return deterministic page zero and
  nonzero HTML roots with matching stable ids.
- Instrumented admitted pages touch at most `offset + page-size + 1` source
  items. Small response bytes alone are not proof.
- Cross-agent and missing eval selectors return byte-identical 404 responses
  and perform zero execution sends.
- Retiring the real owning child returns one status-200 unavailable projection
  without retry, spawn, fresh-tier selection, or stored-result parse.
- A transport or core failure remains 503 and does not morph the existing
  subtree.
- `/data` contains no raw datom `<pre>`, pages entity values through the same
  renderer and route, and performs zero execution sends.

### Real browser

Use an agent-owned browser tab. On `/agent/{id}`, verify that valid, invalid,
and shape-only dots and badges are legible in the Phosphor palette; invalid
hover reveals the shipped explanation with zero network request; custom and
"as data" views show the same value; nested paging morphs the same subtree;
and projected keys expose no drill control.

On `/data`, verify the same tree, statuses, hover, and paging behavior and the
absence of raw EDN. The browser console remains free of Datastar or JavaScript
errors, and the page's stable `#app-view` identity survives the drill and feed
morphs.

The browser bridge does not prove long-lived event streams. Separately use a
server-side SSE client to observe `datastar-patch-elements` frames and verify
the pod log records feed open and broadcast against the same frozen revision.

## Exit

After the two recommendations are ruled, the render lane is dependency-ready.
The web consumer lane follows the route worker's commit and explicit handoff.
The plan lane follows the unrelated dirty plan owner's coherent commit or path
handoff. Stage 1.5 graduates only after the focused suites, full CLJS suite,
server work-bound and retirement matrix, server-side SSE observation, and real
browser interaction pass against one frozen revision.
