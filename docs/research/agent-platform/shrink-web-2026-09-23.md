---
type: reference
status: research; read-only, no source edits; two read-only JVM probes on default (no page GET)
created: 2026-09-23
tags: [agent-platform, shrink, web, render, datastar, hyperlith, chassis, reitit, ring]
---

# Shrinking the web and render stack — library research (2026-09-23)

Lane `shrink-web` (Opus 5.5, read-only research with web search). Branch `refactor/agent-platform`,
HEAD `0d1bbfc5a`. Only this document changed. The owner's instruction (2026-09-23): "I actually want this down to 10k or below …
research agents tasked with shrinking code should do web research for libraries that do what we are doing".

## 0. Findings

1. **The render stack alone is 12,314 src lines, more than the owner's 10,000-line target for the whole codebase.**
   The web tier is `web.clj` (3,807 lines), `transcript.clj` (2,449), `render.clj` (1,897),
   `walk.clj` (1,018), `ns.clj` (936), `value.clj` (616), `hiccup.clj` (533),
   `lint.clj` (529), `data.clj` (236), `test.clj` (115), `block.clj` (96), `route.clj` (58)
   and `agent.clj` (24). There are also 1,075 lines of `resources/seon/schemas/seon.render*.edn` and 11,627
   lines of render tests.
2. **About 1,280 lines of `web.clj` (`:577-1856`) plus about 1,350 lines of `transcript.clj` (`:1096-2449`) are the
   debug surface.** That surface is an entity inspector, a cytoscape graph, renderer experiments, the ledger,
   the outline and problems. No library replaces it; only a product decision can shrink it (§3, S7).
3. **Hyperlith's whole delivery model fits in about 50 lines, and Seon's is about 900.** Hyperlith renders the whole view,
   pushes it and lets streaming compression remove the redundancy (`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:137-189`).
   Seon keeps its own delivery machinery: revisioned packages (delta plus keyframe), per-fragment evidence, basis filtering,
   `join-package` (an identity function), revision-gap repair and a sliding tap per tab (`web.clj:1857-2960`).
   Measured here (§4 P2): a 34 KB page re-sent 20 times through one gzip stream with a sync flush costs 3,110 B
   the first time and then about 525 B per frame (65:1). Hyperlith reports 100–230:1 with brotli's larger window.
4. **Every HTTP request compiles the Reitit router again.** `start!` binds `(fn [request] ((handler service) request))`
   (`web.clj:3765`), and `handler` postwalks the route table and calls `ring/router` each time (`:3652-3683`).
   Measured: **1.07 ms per request** (§4 P1). This is Reitit's own `reloading-ring-handler` written by hand
   (reitit-ring 0.10.1 `reitit/ring.cljc:406`).
5. **Static files are served by a hand handler that uses a production regex.** `web.clj:2963-2981` `resource` calls `re-matches`,
   and AGENTS.md requires the owner's permission for a regex in production code. Reitit already ships this handler:
   `reitit.ring/create-resource-handler` (`ring.cljc:289`) uses ring's `resource-response`, mime types and a path-traversal
   guard. ring-core 1.15.3 and ring-codec 1.3.0 are already on the classpath (`clojure -Spath`, verified).
6. **Seon's HTML serializer duplicates chassis.** `hiccup.clj:213-533` (about 320 lines) re-implements escaping, void elements,
   `:class` collections, `:style` maps, raw strings and fragments. Chassis 1.0.365 has all of these, writes to an `Appendable`,
   and hyperlith already uses it (`hyperlith/impl/html.clj`).
7. **The transcript clips HTML in two places.** "One clipping spot" and "HTML never clips" are laws.
   - `transcript.clj:1759` `reply-intent` does `(subs line 0 90)` + "…" in HTML. CSS `text-overflow: ellipsis` does this without a server-side clip.
   - `transcript.clj:386` falls back to an unbounded `pr-str` when no SCI context is present. That is a second value printer beside `value/render-ai`.

   The ledger's `take 6` (`:1950`) is a `<details>` disclosure that also carries the full text, so it is not a clip.
8. **`seon.render.lint` (529 lines plus a 135-line schema) has no production caller.** Only tests use it:
   `rg -lw seon.render.lint src` finds nothing, and `render.clj:973` names it only in a comment.
   jsoup 1.22.2 is already a dependency and answers the structural questions with CSS selectors.

## 1. Region table

"Lines" are clj-kondo var-definition spans (`clj-kondo --lint src test` with analysis; the vars dump was kept in the lane's
scratchpad). "Callers" lists the source namespaces outside the file; test counts are kondo usages from `test/`.

### `src/seon/render/web.clj` (3,807)

| Region (lines) | What it does | Library that already does it | Replacement | Deletable | Callers | Risk |
|---|---|---|---|---|---|---|
| `:1-146` ns doc, `server?`, `mult?`, generators | registers schema predicates for the http-kit server and the core.async mult | — (schema-gate plumbing) | `mult?`, its generator and `byte-array-generator` go once packages go (S3) | ~40 | schema EDN | low |
| `:151-186` `query-params`, `read-browser-edn`, `read-query-value`, `positive-query-long` | hand query-string decoding | `ring.util.codec/form-decode` (ring-codec 1.3.0; §4 P1 verified `{"b" "x y" "c" "✓"}`); `ring.middleware.params/wrap-params` | `form-decode` | ~12 | 18 internal | low |
| `:189-267` `debug-query`, `debug-query-strings` | converts debug query strings ↔ a request map | reitit coercion (`reitit.coercion.malli`, not on the classpath) | keep; this shrinks with S7 | 0–70 | web-debug-test | medium |
| `:268-366` `message-bar-html`, `shell` | document shell, message form, feed opener | hyperlith `build-shim-page-resp` (`impl/datastar.clj:83-115`) is the same shape | keep and re-express in chassis (S2) | ~10 | 4 | low |
| `:367-576` `surface-html`, stream strip, `path-compare`, `page-result` | per-unit fragments with retained evidence and a sorted-map order by rank and path | hyperlith render fn: one view → one hiccup tree (`datastar.clj:159-176`) | page-result returns one ordered hiccup vector; `path-compare`, fragment evidence and the sorted-map go | ~110 | web-test | medium |
| `:577-1856` debug value, graph, renderer experiments, found values, `acquire-debug-data`, `debug-page-result` | the debug inspector | none; the value renderer's default attribute-map printer already renders entities (AGENTS.md rendering law) | product decision, S7 | 700–1,100 | web-debug-test (1,071 test lines) | high: owner |
| `:1857-1927` `join-package` (identity), `package-bytes`, `build-event`, `frame-bytes`, `next-package`, `package-patches` | revisioned delta/keyframe packages | Datastar `patch-elements!` (`datastar-clojure/libraries/sdk/.../api.clj:267`) plus streaming compression (`adapter/common.clj:244` `gzip-profile`; `sdk-brotli/.../brotli.clj:100` `->brotli-profile`) | send the latest whole view; the compressor removes redundancy (S3) | ~65 | internal | medium |
| `:1929-2081` interest (`read-attributes` … `publish-interest!`), `candidate-call-ids`, `program-key`, root acquire/refresh | routes a commit to the pages whose reads it touched; re-acquires the root | Datahike `:cache-context` per-attribute revisions (`reference-code/datahike/src/datahike/query.cljc:2582` `advance-query-cache-context`, `:3015` `:datahike.cache/attribute-revisions`); `clojure.core.cache.wrapped` | memo keyed on `[cache-context revisions of read attributes]` (dependency audit rows 1 and 15) (S5) | ~120 | root-pull-test, web-test | medium, after A1 |
| `:2082-2501` `derive-page`, `newer-page?`, `derive-page!`, `current-page`, `invalidate-runtime-derived-state`, `failed-page-result`, `unreportable-page-result`, `render-pass` | one pass over the watched pages: retained-only shortcut, shared-cache swap, fault-signature dedupe, string floor | hyperlith `render-handler` loop (`datastar.clj:152-176`): `(render-fn req)` → write | a pass maps `render-view` over the watched keys; `render-call`'s own cache (`render.clj:1412`) skips unchanged blocks; failures go through the one error route (S3, S4) | ~250 | web-test, render-cache-test | medium |
| `:2502-2617` `history-segments`, `change-context`, `derive-context!` | context actions and prompt assembly | — (not web) | move to `seon.context`/`seon.turn`; this is not a deletion | 0 | turn-test | low |
| `:2618-2760` `render-step` | Flow proc; ~35 lines of hand port-presence checks | Flow's var-process validation (flow audit, step 2) | keep the proc and delete the port check once step 2 lands | ~40 | cluster | low |
| `:2762-2960` tab registration, `write-package!`, `await-feed-package!`, `feed` | per-tab virtual thread: first paint, revision loop, drain wait, typed feed error | hyperlith `render-handler` (`datastar.clj:152-189`); Datastar `->sse-response` (`sdk-http-kit/.../http_kit.clj:23`); the drain wait needs the fork's `write-state` (`reference-code/http-kit/src/org/httpkit/server.clj:321`) | tap → `(loop [] (when-some [bytes (<!! tap)] (send! …) (await-drain) (recur)))`; register the thread for `stop!` (flow audit rows 19/20) | ~120 | oversight-test | medium |
| `:2963-2981` `content-types`, `resource`; `:3625-3628` `static-response` | classpath static files, **production regex** | `reitit.ring/create-resource-handler` (`ring.cljc:289`) | one route entry (S1) | ~25 | — | low |
| `:2983-3006` `same-origin?`, `:3643` middleware | Origin-vs-Host CSRF check | Fetch-Metadata check: allow a POST whose `Sec-Fetch-Site` is `same-origin` or `none`, with an Origin fallback (Go 1.25 `CrossOriginProtection`; OWASP) | 3-line check (S1) | ~15 | — | low |
| `:3008-3079` `decode-form`, `agent-exists?`, `inbound-tx-meta`, `inbound` | form decoding + inbound message commit | `ring.util.codec/form-decode` for the body | decode via ring; keep `inbound` | ~12 | web-test | low |
| `:3080-3500` route helpers and responses | namespace/agent aliasing, owner creation, page and debug responses | — | keep; the debug responses shrink with S7 | 0–250 | web-debug-test | medium |
| `:3508-3624` `data-response` | data browser | value renderer | folds into S7 | 0–100 | value-options-test | medium |
| `:3630-3683` `bind-handlers`, `handler` | postwalks the route table to bind handlers; builds the router **per request** | `reitit.ring/reloading-ring-handler` (`ring.cljc:406`) is the same thing; compile once instead | build the router once at `start!`; each route holds `#'fn` so a reload still takes effect (S1) | ~12 | web-adoption-test | low |
| `:3685-3807` port derivation, `start!`, `stop!` | FNV-1a port, loopback bind with fallback | hyperlith `start-app` (`core.clj:118-154`) is equivalent | keep; `stop!` shuts the worker executor (flow audit row 20) | 0 | cluster | low |

### Other render owners

| File (lines) | Region | Library that already does it | Replacement | Deletable | Risk |
|---|---|---|---|---|---|
| `hiccup.clj` (533) | `:213-533` StringBuilder serializer (escape, void, attributes, class, style, fragments, raw) | **chassis** `dev.onionpancakes.chassis.core/html` and `write-html` (README: escapes text and attributes by default, `raw`, void elements, class collections, style maps, fragments, compile macro, 2× hiccup speed) | `->string` = `(c/html (normalize node))`; keep `raw` → `c/raw` (S2) | ~280 | medium: sorted attribute order, symbol/string tags, camelCase style keys |
| `hiccup.clj` | `:94-152` `hiccup?` grammar, `:154-190` generator | Malli ships a Hiccup schema (`reference-code/malli/README.md:2530-2545`) with a free generator | **keep** `hiccup?`: its docstring records a measured 8.07 ms → 0.45 ms hot-path fix, and Malli's parser is a regex-schema walk. The generator could become `mg/generator` over the Malli schema (−30) | 0–30 | low |
| `lint.clj` (529) | the whole file | jsoup (`org.jsoup/jsoup 1.22.2`, already a dependency): `Jsoup/parse` + `.select` answers "empty region", "duplicate subtree", "placeholder count" over the served string | delete from src; tests that need it use 5-line jsoup selects (S6) | 529 + 135 EDN | low |
| `transcript.clj` (2,449) | `:1-1095` session projection (messages, receipts, history entries, run AI/HTML) | — | keep and trim | ~150 | medium |
| `transcript.clj` | `:1096-2449` debug session, outline, ledger, problems, budget | none | S7 | 600–1,000 | high: owner |
| `transcript.clj` | `:1741` `colour-source` (via `repl/syntax-tokens`, `repl.clj:279`) | highlight.js with its Clojure grammar, client-side | keep the server tokens (shown text stays exact); not worth a JS dependency | 0 | — |
| `transcript.clj` | `:1759` 90-char `subs`, `:386` `pr-str` fallback | CSS `text-overflow`; the value renderer | law fixes (S6 rider) | ~5 | low |
| `render.clj` (1,897) | `:1412-1600` `render-call`, `call-cache-evidence`, `retained-program-current?`; `:1601-1652` `branch-scope`, `shared-cache` | `clojure.core.cache.wrapped` keyed on Datahike's `:cache-context` (`query.cljc:2582`), as ruled for the projection memo (dependency audit row 1) | one memo per `[program-digest call-id cache-context-of-read-attributes]` (S5) | ~200 | medium, after A1 |
| `render.clj` | `:278-611` renderer selection (candidates, ambiguity, producers, stages, selection) | — (Seon's schema → render-pair law) | keep; that law says "one AI/HTML pair belongs to an entity schema" | 0–80 | — |
| `walk.clj` (1,018) | `:252-534` acquisition members, acquire-entity cache, acquired tree | Datahike pull recursion with a depth limit (`reference-code/datahike/src/datahike/pull_api.cljc:22`, `:224` `push-recursion`) for bounded neighborhoods; the entity cache → core.cache (audit row 15, −30) | replace the hand CAS cache first; test whether pull recursion covers the distance-2 walk | 30–300 | medium |
| `value.clj` (616) | the value renderer: `prepare`, `window`, `value-node*` | — (the one clipping spot the law names) | keep | 0 | — |
| `ns.clj` (936) | the namespace lens | — | keep; review duplication with `transcript` source rendering later | 0–200 | low |

## 2. Hyperlith's model versus `web.clj`

Hyperlith's model is `view = f(state)`, from `reference-code/hyperlith` at `b08a8e868`:

1. One mult of payload-free refresh events, filled by the app's own change notification (`core.clj:98-100`, `:128`).
2. For each SSE connection, a tap into a `dropping-buffer 1`, a render on connect, and on each event
   `(render-fn req)` → `patch-elements` of the whole `#morph` → one brotli stream per connection → `send!`
   (`datastar.clj:152-189`).
3. Actions return 204; they never paint (`datastar.clj:117-135`), which is Seon's `inbound` already (`web.clj:3038`).

Seon already has 1 and 3: `wake/route!` offers payload-free wakes and the proc coalesces them. It builds its own version of
2, and that is where the lines are.
- Revisioned packages: `next-package`, `package-patches` and a keyframe for gap repair.
- Delta selection against a keyframe.
- Basis-transaction filtering in `feed` (`:2917-2921`).
- A typed "missing package" path.
- Per-fragment evidence reuse (`page-result :524-548`), which duplicates the `render-call` cache one level down (`render.clj:1412`).
- `join-package` as an identity function (`:1857-1865`).

**What can replace it.** The render proc renders each watched view once per pass. It still derives only the calls the
`render-call` cache reports stale, then publishes `{registration-key html-string}`. Each tab's loop writes
`patch-elements` of that string through its own compressing writer. Datastar's morph (idiomorph) keeps unchanged
DOM, focus and caret. The message bar already keeps its transient state in signals (`web.clj:268-305`).

**Cost.** Serialization is per pass per page, not per tab. Compression costs O(page bytes) per tab per pass. Measured in P2:
a 170-unit 34 KB page takes 3.3 ms to serialize, and one gzip frame on a warm stream is about 525 B. A small open-tab count makes
that negligible. The trade removes every revision and gap case, which is where feed bugs live.

**What hyperlith has that Seon must not copy.**
- Hyperlith renders per connection. Seon should render per page key and share the string.
- Hyperlith swallows render errors in `er/try-on-error` and closes the socket. Seon's error policy requires the one error route.
- Hyperlith uses `defonce` atoms for the router registry (`router/add-route!`). Seon's route table is data.

**Is hyperlith a dependency?** No. It is an experimental mini-framework with global mutable routing and `Executors`
overrides at load time (`core.clj:24-28`). Datastar-clojure is already on the classpath and its SDK carries the same
pieces. Take the model and leave the library.

## 3. First slices

Each slice adds about 100 lines or fewer and deletes far more. `web.clj` is one file, so S1, S3, S4, S5 and S7 run in
one lane one after another (one file, one lane). S2 (`hiccup.clj` + `deps.edn`) and S6 (`lint.clj` + tests) are
file-disjoint and can start now.

| # | Slice | Adds | Deletes (src) | Depends on | Proof |
|---|---|---|---|---|---|
| **S1** | **Ring and Reitit primitives.** `reitit.ring/create-resource-handler` for `/css` and `/js` (removes the production regex); `ring.util.codec/form-decode` for query and form; compile the router once at `start!` with `#'handler-fn` values in route data (no `bind-handlers` postwalk); `Sec-Fetch-Site` same-origin check | ~20 | ~75 (`query-params`, `decode-form`, `content-types`, `resource`, `static-response`, `bind-handlers`, most of `same-origin?`) | none | REPL: router compile leaves the request path (P1 names 1.07 ms/request as the before value). curl on an **isolated scratch cluster** (never default namespace pages): `/js/datastar.js` 200 `text/javascript`; `/js/..%2fx` 404; message POST 204; cross-site POST 403. Hot reload of one handler takes effect without rebinding |
| **S2** | **Chassis serializer.** Add `dev.onionpancakes/chassis {:mvn/version "1.0.365"}`. `->string` = `(c/html (normalize x))`, where `normalize` sorts attribute maps (byte stability), maps camelCase style keys, turns symbol/string tags into keywords and `Raw` into `c/raw`. Keep `hiccup?` | ~45 | ~290 (`hiccup.clj:213-533`) | none | Property over `hiccup-generator`: new `->string` equals old `->string` at HEAD. Run it before deleting, then keep it as the escaping regression. Measure the 170-unit page (P2: 3.3 ms before). Browser paint of one agent page on a scratch cluster, compared by screenshot |
| **S3** | **Whole-view broadcast (hyperlith model).** The render pass publishes `{key html}`; the tab loop writes the latest; per-tab gzip writer via Datastar's write-profile (`adapter/common.clj:244`), with the fork's `write-state` drain kept. Delete the package, revision, delta, keyframe, basis-filter and fragment-evidence code, `join-package`, `seon.render.package.edn`, and `mult?`/byte generators. Register feed threads and shut the worker executor in `stop!` (flow audit step 14, rows 19/20) | ~90 | ~450 | none (ideally after S1 in the same lane) | Browser: send a message and **observe the reply paint**; typed text in the bar survives a morph during streaming; two tabs both update; kill and reopen a tab and it repaints from facts. Network panel: bytes per frame after the first under 2 KB for an unchanged-heavy page. REPL: pass time with the timing number, and zero stale-page reports across 50 commits |
| **S4** | **Page failure through the one error route.** Replace `failed-page-result`'s fault-signature dedupe, the `offer!`+`log/warn` pair and `unreportable-page-result` with the error route's `fault!` at the var-process boundary (one-error-route design). The page shows the stored error fact's `error/render-html` | ~15 | ~80 | flow step 2 (var-process wrapper) | REPL: a renderer that throws produces one stored error fact per distinct failure, in panic mode and record mode. Browser shows the failed section. Nothing is swallowed |
| **S5** | **Interest and page cache on Datahike's cache-context.** One `clojure.core.cache.wrapped` memo keyed `[program-digest call-id (select-keys attribute-revisions read-attributes)]`. Delete `read-attributes` … `publish-interest!`, `candidate-call-ids`, `program-key`, `newer-page?`, the `derive-page!` swap choreography and `shared-cache`'s branch-scope guard | ~60 | ~250 (web.clj + render.clj) | A1 memo (audit row 1); render walk (row 15) | REPL: an unrelated write (an attribute nothing on the page reads) derives zero calls and costs under 1 ms. A related write derives exactly the blocks that read it. Before/after GET timing (lane-page-key recorded 4–29 ms warm) |
| **S6** | **Delete `seon.render.lint`, plus the law riders.** Remove `lint.clj` and `seon.render.lint.edn`. Convert the lint assertions that are still wanted to jsoup selects over served HTML. Fix `transcript.clj:1759` (CSS ellipsis) and `:386` (value renderer, or an explicit refusal, never `pr-str`) | ~40 (tests) | ~670 (src + EDN) | none | Converted tests go red with the defect reintroduced (duplicate subtree, placeholder flood). HTML shows the full reply intent; CSS truncates it visually. Browser screenshot |
| **S7** | **Debug surface, owner decision; three options.** (a) *Simplest:* debug = value renderer over the subject entity, plus the transcript ledger. Delete renderer experiments, the cytoscape graph (373 KB JS + `seon-graph.js`), `generic-entity`, found values and `data-response`, about −1,500 src. (b) **Recommended:** (a) but keep the session, outline and ledger views, about −1,000 src. (c) Keep every view and route them through shared helpers, about −250. Each option keeps "debug page uses the turn ledger and the acquired session component" (datastar skill) | ≤100 | 250–1,500 | owner ruling | Browser paint of the debug page for one agent on a scratch cluster. The ledger shows every turn, oldest first. An entity drill-down renders through the default attribute-map printer |

The requested 5–8 first slices are S1 to S6 with S7 behind the owner's ruling. **Why S5 comes after S3:** once S3 has removed
the package layer, the only remaining cache is the `render-call` one, so S5 replaces one mechanism instead of two.

**Totals, S1–S6:** about −1,815 src lines deleted against about +270 added, so about −1,545 net. With S7(b) the net is about −2,550.

## 4. Probes (JVM mode, `default`, read-only, no page GET)

- **P1** router compile (226 ms form total, which is 200 compiles plus warmup):
  `(dotimes [_ 200] (ring/ring-handler (ring/router routes opts)))` → **1.071 ms per compile**.
  Separately, `(ring.util.codec/form-decode "a=1&b=x%20y&c=%E2%9C%93&debug=true")` →
  `{"a" "1", "b" "x y", "c" "✓", "debug" "true"}`.
- **P2** serialization and compression (197 ms form total). A synthetic 150–169-unit page through
  `seon.render.hiccup/->string` gives 34,470 B per frame. Twenty frames through one `GZIPOutputStream` with sync flush: the
  first is 3,110 B, then 511, 527, 525, 532 and 535 B. The totals are 649,880 raw → 37,510 B.
  Serialization is **3.29 ms per 170-unit page**. Gzip's 32 KB window is smaller than the frame; brotli's window
  (hyperlith default 2^18) should do better, as the cited 100–230:1 reports.

## 5. Target size for the render stack

| Owner | Now | Target | How |
|---|---|---|---|
| `web.clj` | 3,807 | ≤ 900 | S1, S3, S4, S5, S7(b) |
| `transcript.clj` | 2,449 | ≤ 1,100 | S7(b), trim the session projection |
| `render.clj` | 1,897 | ≤ 1,100 | S5, and cut the selection stages later |
| `walk.clj` | 1,018 | ≤ 600 | audit row 15, pull recursion |
| `ns.clj` | 936 | ≤ 600 | shared source rendering with the transcript |
| `value.clj` | 616 | ~ 600 | keep (the one clipping spot) |
| `hiccup.clj` | 533 | ≤ 200 | S2 |
| `lint.clj` | 529 | 0 | S6 |
| rest (`data`, `test`, `block`, `route`, `agent`) | 529 | ~ 450 | — |
| **Total** | **12,314** | **≤ 6,150 interim; ≤ 3,500 once the debug surface takes option (a) and the transcript and namespace lenses share one source renderer** | |

Even the ≤ 3,500 figure uses 35% of a 10,000-line codebase. If the owner's 10,000 total holds, the debug surface
question (S7) is the deciding one.

## 6. Timings

| Operation | Time | Justification |
|---|---|---|
| `clj-kondo --lint src/seon/render*` with analysis | 1.24 s | over 1 s: proportional to 12.3k lines of analysed render source; a one-off research read |
| `clj-kondo --lint src test` with analysis (52 MB EDN) | **11.83 s** | **over 10 s, which is a defect.** It is proportional to all of src and test (~177k lines), and it was needed for callers outside the render files. Linting only the render files plus a targeted `rg -lw` for callers (as §1 then did) would have been sub-second. This is a lane-method defect, not a system one; the orchestrator should require the targeted form in shrink-lane specs |
| bb var-span extraction ×2 | < 1 s each | — |
| `clojure -Spath` | 0.04 s | — |
| P1 REPL probe | 0.226 s | — |
| P2 REPL probe | 0.197 s | — |
| web fetch/search (5 requests) | not clocked | network |

## 7. Web sources

- Chassis README (features, `write-html`, `raw`, compile macro, benchmarks): https://github.com/onionpancakes/chassis
- Huff (alternative hiccup; `raw-string`, fragments; 22–48% faster than hiccup): https://github.com/escherize/huff
- Hiccup 2 (`html` returns RawString, escaping by default): https://github.com/weavejester/hiccup
- Hyperlith README (view = f(state), CQRS, compression): https://github.com/andersmurphy/hyperlith
- Anders Murphy, "Clojure: Realtime collaborative web apps without ClojureScript": https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html
- Anders Murphy, "Why you should consider using brotli compression with SSE" (100–230:1 over re-renders): https://andersmurphy.com/2025/04/15/why-you-should-use-brotli-sse.html
- OWASP CSRF Prevention Cheat Sheet (Fetch Metadata): https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
- Miguel Grinberg, "CSRF Protection without Tokens or Hidden Form Fields" (Sec-Fetch-Site, Go 1.25 CrossOriginProtection): https://blog.miguelgrinberg.com/post/csrf-protection-without-tokens-or-hidden-form-fields
- Datasette PR 2689, token CSRF replaced by Sec-Fetch-Site: https://github.com/simonw/datasette/pull/2689

## 8. Out of scope, noted

- The 11.8 s whole-tree kondo analysis is the lane-method item in §6; no issue note was filed because this lane is read-only.
- `web.clj:3765`'s per-request router rebuild is a live 1 ms tax on every request, including SSE opens. S1 removes it;
  it needs no separate issue unless S1 is deferred.
