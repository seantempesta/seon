# Printing, rendering and web: Seon vs the reference libraries

Research only. Nothing was run (no JVM, no tests, no `bin/seon`); every claim is from reading source at the working
tree of 2026-10-07 (branch `refactor/agent-platform`, HEAD `a8e3bde5b`). Line counts are `wc -l` today. Estimates of
lines removed are hypotheses until a branch has done the cut (CLAUDE.md "Be patient at the REPL"). Anything I did
not read is marked UNVERIFIED. The existing dedup spec `docs/seon/specs/render-dedup.md` (D1-D10, ~3.9k lines, root
ruling R1 = keep every feature) is the baseline; where my items overlap it I say so, and where they go further I say
what feature or exactness they trade.

## 0. The area in numbers

| file | lines | defns | role |
|---|---|---|---|
| `src/seon/print.cljc` | 810 | 44 | print nodes -> fipp ops -> text / hiccup; elision text; test.check generators |
| `src/seon/print/value.clj` | 133 | 5 | projection-free floor: `bounded-text`, `throwable-text` |
| `src/seon/render/value.clj` | 1479 | 69 | budgeted selection of a value into a print node tree; `prepare`; `render-ai/html` |
| `src/seon/render.clj` | 1963 | 94 | render-function selection (4 stages), retained-call cache, `render-call`, `walk` entry |
| `src/seon/render/walk.clj` | 1317 | 47 | bounded neighbourhood / history / graph traversal over Datahike |
| `src/seon/render/web.clj` | 4156 | 163 | page shell, debug pages, render Flow proc, SSE feed, routes, lifecycle |
| `src/seon/render/transcript.clj` | 2333 | 114 | agent history units, debug session/outline/ledger/problems |
| `src/seon/render/hiccup.clj` | 525 | ~25 | hiccup grammar + HTML serializer |
| `render/{data,ns,var,schema,test,block,route}.clj` | 224+337+159+187+166+96+60 = 1229 | | entity observations and per-schema render pairs |
| `src/seon/web/{jvm,extract,search}.clj`, `my/web.clj` | 468+14+32+41 = 555 | | outbound HTTP fetch/search tool (not a render surface) |
| **total** | **14,500** | | tests for this area: ~12,280 lines (`web_test` 2419, `transcript_test` 1502, `value_test` 1288, `web_debug_test` 1169, `render_simplification_test` 955, ...) |

Reference sizes: fipp 1,061 (src; engine 335, edn 125, visit 61, clojure 196), puget 1,211 (printer 809, dispatch 111,
color 253), kindly 864 (api 137, kind 488), clerk 17,418 total of which `viewer.cljc` 2,050 and the whole JVM
notebook server (`webserver` 420, `builder` 446, `eval` 333) is ~7.9k, hyperlith 3,455 of which `src` is 1,384
(`core` 154, `impl/datastar` 189), datastar-clojure `sdk` 1,656 and `sdk-http-kit` 227, http-kit 1,293 (server+client
core, fork), portal 23,394 (inspector UI `inspector.cljs` 1,267; ALL viewers under `ui/viewer/` 4,363 lines).

Where the lines are not: docstrings are about 10% (web 347, render 249, value 195 of their files, my estimate by
script), comments 3-6%, blank 5-7%. The bulk is code. `web.clj` is 163 mostly short functions (largest 140 lines):
the size is breadth, not a few giant functions. Section split of `web.clj` by its own banners: shell 153-398 (245),
painting 398-559 (160), **per-agent debug value 559-1833 (1,274)**, render proc 1833-2669 (836), feed 2669-2894 (225),
routes/handlers 2894-3986 (1,092), lifecycle 3986-4156 (170). Debug UI in total (web 1,274 + part of routes ~600 +
transcript ~2,300) is about 4k lines: the same size as every portal viewer put together.

---

## 1. Pairings: Seon namespace -> most similar library code

### 1.1 `seon.print` (810) + `seon.render.value` (1479) + `seon.print.value` (133) <-> fipp + puget

Comparable library parts and sizes:

- fipp `edn.cljc` 125 + `visit.cljc` 61 = 186 lines: a complete pretty printer for EDN with `print-length`,
  `print-level`, `print-meta`, symbol hooks. The layout engine (`engine.cljc` 335) Seon already requires
  (`print.cljc:5` `[fipp.engine :as fipp]`, used at `print.cljc:593`).
- puget `printer.clj` 809 (with colour, sorted keys, `:coll-limit`, namespace-map lifting, record/tagged dispatch);
  `dispatch.clj` 111 (class lookup chains).
- Seon: print.cljc minus generators ~650 + render/value.clj 1479 + print/value.clj 133 = ~2,260 for "value in,
  budgeted text + hiccup out".

Seon is 12x the fipp visitor and ~2x puget's whole printer. WHY, concretely (section 2.1): Seon is not "a printer",
it is a budget allocator + layout predictor + elision-handle generator + two-face emitter. Parts a library never does:
a token budget spread breadth-first across the shape; prediction of fipp's own line breaks to charge widths;
requery handles; stable node ids; reference/identity resolution through a database; a hiccup face. Several of those
are legitimate (see 2.1 "what Seon does that libraries do not"), two are duplications (layout prediction, generators).

### 1.2 `seon.render` selection stages (render.clj 286-440, 577-787, ~330 lines) <-> clerk `viewer-for`, portal `get-compatible-viewers`, reveal `stream-dispatch`, kindly `consider`

- clerk `viewer.cljc:1481-1497` `viewer-for` (17 lines) + `find-viewer` 1470-1471 (2) + `->viewers` (160-170).
- portal `inspector.cljs:115-126` `get-compatible-viewers-1` (12 lines) + `get-viewer` 150-155.
- reveal `stream.clj:90-91` one multimethod: `(or (::type (meta x)) (class x))`.
- kindly `api.cljc:64-72` `consider` (9 lines): a kind is metadata, no renderer inside kindly at all.

Seon's selection = 4 stages (`selection-stage-order [:explicit-value :namespace :declared :floor]`, render.clj:577),
each building an evidence map, plus an inspection function that re-runs all stages (759-787).

### 1.3 `seon.render/render-call` + retained calls (render.clj 800-1010, 1496-1665, ~450 lines) <-> clerk presentation cache, nothing in portal

Clerk caches by a hash of the form + dependency analysis (`:presentation-cache`, `store!-wrapped-value`,
`viewer.cljc:944-952`) and, for the live notebook, re-presents whole docs. Portal has no server-side render cache
(the client renders). Seon is more elaborate than either: it keeps per-call read evidence and revalidates
(`call-cache-evidence` 902, `refresh-read-evidence` 1008, `same-invocation-evidence?` 994). This is the one place
where Seon does something none of the reference viewers needs, so no comparable library code exists to shrink to;
the reduction is in making the cache one, not in replacing it (spec D1).

### 1.4 `seon.render.walk` (1317) + `seon.render.data` (224) <-> datahike pull API; portal has no equivalent

No viewer library walks a database. Closest: Datahike's `pull` (recursion `...`, `:limit`, reverse attrs) which
`walk.clj` already uses (`root-selector` 129-159 builds a pull pattern; `walk.clj:19-20` requires
`datahike.pull-api`). walk.clj then adds distance accounting, node caps, continuation, history, graph, related
(`neighborhood` 114 lines, `acquisition-members` 94, `acquired-tree` 61, `acquire-entity` 54, `graph` 58,
`history` 59, `related` 60). See item 10.

### 1.5 `seon.render.hiccup` (525) <-> hyperlith `impl/html.clj` (32) over chassis, datastar-clojure

hyperlith's whole HTML layer is 32 lines because it delegates to `dev.onionpancakes/chassis`
(`html.clj:2-3`, `html->str h/html`, `html->stream h/write-html`). chassis is not in `deps.edn` (grep of
`hiccup|chassis|replicant` finds nothing), but `~/.m2/repository/dev/onionpancakes/chassis` exists on this machine.
Seon's file adds: a strict grammar predicate `hiccup?` (94), a test.check `hiccup-generator` (154-212, ~60 lines in
src), React-camelCase style normalisation (338-365), sorted-attribute determinism (382-410), tag shorthand (258).

### 1.6 `seon.render.web` page + feed + routes (4156) <-> hyperlith (1384 src) + datastar-clojure sdk-http-kit (227) + http-kit

- hyperlith `impl/datastar.clj:60-189`: shim page (22 lines), POST action handler (22), SSE `render-handler` (62
  lines: tap a mult, brotli stream, on-open thread, render, write, flush, close).
- hyperlith `core.clj` 154: start, middleware, `defview`/`defaction` macros (route registration by path digest).
- datastar-clojure: `->sse-response` (Seon calls it, `web.clj:2800` region), `patch-elements!`/`patch-signals!`.
- Seon: `shell` 341-397 (56), `render-step` 2536-2672 (137), `render-pass` 2315-2419 (105), `next-package` 1793-1815 +
  `package-patches` 1817-1831, `write-package!` 2704-2751 (48), `await-feed-package!` 2757-2784, `feed` 2785-2893
  (113), `handler` 3942-3984 (48), routes via reitit `route.clj` 60 + `bind-handlers` 3903.
  Subtotal of the delivery machinery (everything between "a page value exists" and "bytes on the socket"): ~620.

### 1.7 `seon.render.transcript` (2333) + web.clj debug section (1274) <-> portal inspector (1267) + table/diff/exception viewers

Nothing comparable in clerk/hyperlith: they have no agent-session debugger. The honest comparison is portal:
portal's entire generic value inspector with navigation, filter, selection and viewer switching is 1,267 lines of
`inspector.cljs`; its table viewer 329 (`table.cljc`), exception viewer ~320. Seon's debug UI is ~4k lines of bespoke
hiccup per finding (see `ledger-turn-body` `transcript.clj:1757-1855`, `ledger-strip` 2160-2210) with Datalog
queries embedded in renderers (`transcript.clj:1775-1777` `db/q ... :seon.context.capture/prompt`).

---

## 2. Why the libraries are shorter: concrete mechanisms, both sides cited

### 2.1 Printing: fipp/puget vs Seon

**(a) One visitor with one dispatch cond vs a 114-line classifier.**

fipp `visit.cljc:32-52` is the entire type dispatch:

```clojure
(defn visit* [visitor x]
  (cond
    (nil? x) (visit-nil visitor)
    (util/boolean? x) (visit-boolean visitor x)
    (string? x) (visit-string visitor x)
    (symbol? x) (visit-symbol visitor x)
    (keyword? x) (visit-keyword visitor x)
    (number? x) (visit-number visitor x)
    (seq? x) (visit-seq visitor x)
    (vector? x) (visit-vector visitor x)
    (map? x) (visit-map visitor x)
```

and every scalar is one line, `fipp/edn.cljc:30-50`: `(visit-number [this x] ... [:text (pr-str x)])`. fipp lets
`pr-str` produce `##NaN`, `1N`, `1/3`, `#inst "..."`, `#uuid "..."`, `\space`.

Seon `render/value.clj:428-541` `classify` re-derives each of those strings by hand:

```clojure
(#{Double Float} c)
(scalar ::print/number x (let [d (double x)]
                           (cond (Double/isNaN d) "##NaN"
                                 (= d Double/POSITIVE_INFINITY) "##Inf"
...
(identical? c java.util.Date) (scalar ::print/inst x (str "#inst \"" (.toInstant ^java.util.Date x) "\""))
```

Clojure 1.12's `pr-str` already prints those (`print-method` for `Double` NaN/Inf, `Date`, `UUID`, `BigInt`,
`Ratio`). What Seon needs on top is only the length cap (`scalar-bound 32`, value.clj:311) and the no-host-object rule.
Cost of the idiom difference: ~114 lines vs ~35 (a `case` on class family calling `pr-str`, then the cap).

**(b) Bound by two numbers, in one pass, vs a budget solver.**

fipp `edn.cljc:9-21`, the whole of elision:

```clojure
(defn pretty-coll [{:keys [print-level print-length] :as printer} open xs sep close f]
  (let [printer (cond-> printer print-level (update :print-level dec))
        xform (comp (if print-length (take print-length) identity) (map #(f printer %)) (interpose sep))
        ys (if (pos? (or print-level 1)) (sequence xform xs) "#")
        ellipsis (when (and print-length (seq (drop print-length xs))) [:span sep "..."])]
    [:group open [:align ys ellipsis] close]))
```

12 lines: depth and length cut, lazily (`take`, `drop` on the seq, never counting beyond the cut), no allocation
beyond what is shown. puget adds the same two knobs: `trim-coll?` `printer.clj:178-183` (`:coll-limit`), and
`order-collection` 185-195 (sort only when `count <= sort-keys`, so unbounded colls are never sorted).

Seon `render/value.clj:1045-1097` `select-nodes` + `push-work`/`schedule`/`admit-member`/`admit-chunk`/`complete-tail`
(835-1030, ~200 lines) is a priority queue keyed `[level rank ordinal seq]` in a `sorted-map`
(`push-work`, value.clj:844-851) so that "every member at one level is admitted before any at the next", under ONE
global token budget (`charge`, 839-842). That is a genuinely different algorithm (fair breadth-first allocation), and it
IS what gives the AI reader "the essence of a big value" rather than "the first 20 of the first key". The libraries
do not do this; but see 2.1(c): the *cost accounting* inside it is what makes it 700 lines instead of ~150.

**(c) fipp's layout is computed once, by fipp; Seon computes it twice.**

`print.cljc:593-597` lays out with fipp:

```clojure
(transduce (comp fipp/annotate-rights (fipp/annotate-begins layout) (fipp/format-nodes layout))
           append out ops)
```

But before that, `render/value.clj:311-345` and `569-727` re-implement fipp's fits test so the budget can be charged
in laid-out characters: `flat-width` (627-639), `extras` (653-666: "Width fipp's line breaks add to a broken node"),
`start-column` (668-675), `breaks?` (677-686, docstring: "fipp's fits test: a group breaks when its parent broke ...
and its flat width exceeds the width left at its start column"), `chain`, `settle` (694-727, incremental re-derivation
of flat widths along the ancestor chain), `nest`, `tuple?`, `nest-cap`/`chunk-width`/`sorted-bound`/`tuple-bound`
constants. About 200 lines whose only job is to predict `fipp.engine`'s output size while the tree is still being
grown. Neither fipp nor puget needs this because neither has a token budget: they take `print-length`/`print-level`,
cut first, lay out once. The structural fix: charge the budget by flat width (or by `count` of the `pr-str` of a
scalar leaf plus a constant per delimiter), choose cut parameters, lay out once with fipp, and if the text exceeds
the budget by more than a small factor, lower the level and re-run (bounded retry, each run O(shown)).

**(d) Dispatch by data/hook vs protocol vs per-kind functions.**

fipp uses a protocol (`IVisitor`, 17 methods, `visit.cljc:8-27`) so a second visitor (clojure code printer,
`fipp.clojure` 196 lines) costs one record. puget uses data: `chained-lookup`/`caching-lookup`/`inheritance-lookup`
(`dispatch.clj:13-111`) over a map class -> handler. Seon has neither: the node kinds are keywords on maps
(`::print/face`) and each consumer (`node-work` print.cljc:558-571, `leaf-ops` 434-475, `summary` 497-502, value.clj
`classify`, `tuple-node`, `node-continuation`) re-switches on the face. That is data dispatch in the style the
project wants; its cost is that adding a face touches four functions. One table face -> {:text :open :close ...}
(a map literal) would let `collection-work`'s `case` forms (print.cljc:521-527 open/close) disappear.

**(e) Generators in the library vs in the test.**

`print.cljc:13-172` (about 160 lines: `number-generator`, `char-generator`, `projected-node-generator`,
`elision-node-generator`, `generated-item-identity`, `distinct-generated-nodes`, `node-generator`) is test.check
code in a production namespace; its only consumer is `test/seon/print_test.clj:198` (`(gen/sample print/node-generator
300)`), found by grep of src and test. fipp and puget ship no generators.

**(f) What Seon does that fipp/puget do not (keep).**
- Handles: a cut is a value with a requery form (`print.cljc:305-326` `continuation`, e.g. `(drop 32 result/e7b3)`).
- Two faces from one op stream (`emit-both` 646-654; `ops-hiccup` 607-629 folds the same ops).
- A depth-safe explicit stack (`node-ops` 585-... ; fipp recurses on the JVM stack).
- Token estimates (`render-elision-ai` 352-392, docstring ~25 of its 40 lines).

### 2.2 Viewer selection: clerk/portal/reveal/kindly vs `seon.render`

**Clerk**: a viewer is a map `{:name :pred :render-fn :transform-fn ...}` (`viewer.cljc:882-940`):

```clojure
(def number-viewer {:name `number-viewer :pred number? :render-fn 'nextjournal.clerk.render/render-number ...})
(def map-viewer    {:name `map-viewer    :pred map?    :render-fn '... :opening-paren "{" :closing-paren "}" :page-size 10})
```

The registry is an ordered vector; selection is first match (`viewer.cljc:1481-1497`):

```clojure
(defn viewer-for [viewers x]
  (or (when-let [selected-viewer (->viewer x)] ...)          ; explicit: the value names its viewer
      (find-viewer viewers (fn [{:keys [pred]}] ... (pred (->value x))))
      (throw (ex-info "cannot find matching viewer for value" ...))))
```

Specificity is by position: `add-viewers` prepends, so later/more specific wins; ambiguity cannot exist because
order is total. `transform-fn` + `render-fn` split: the JVM side only produces data (`apply-viewers*` 1520-1545 loops
until a viewer has a `render-fn`, max 10 times); the render-fn is a quoted symbol evaluated in the browser (SCI,
`render.cljs`), so there is no hiccup code on the server at all for stock viewers.

**Portal**: `{:predicate fn :component fn :name kw}` (`ui/viewer/pprint.clj:25-29`), first compatible in a list with a
per-location user override (`inspector.cljs:115-126, 150-155`):

```clojure
(filter #(when-let [pred (:predicate %)] (pred value)) viewers)
```

12 lines. The user switches viewers from the UI (`set-viewer!` 157-166), i.e. selection state is a map
`location -> viewer-name`, not a protocol.

**Reveal**: one multimethod `(defmulti stream-dispatch (fn [x _] (or (::type (meta x)) (class x))))`
(`stream.clj:90-91`); metadata is the explicit-viewer override.

**Kindly**: no renderer; `consider` (`api.cljc:64-72`) attaches `{:kindly/kind :kind/hiccup}` metadata. The consumer
(Clay, clerk) owns rendering. 137 + 488 lines because it is a vocabulary of kinds with docs (`kind.cljc` is mostly
docstrings).

**Seon** (render.clj): four stages `[:explicit-value :namespace :declared :floor]` (577), where:
- `:explicit-value` (618-642) = clerk/reveal's "value names its viewer", via `:seon.render/ai` key on the map. Same idea,
  ~25 lines; fine.
- `:namespace` (682-710) = per-viewing-agent functions whose Malli contract accepts the value and returns the face
  (`schema/function-accepts-and-returns-in?`); refused when more than one (`ambiguity` 286-302, 17 lines of error
  building).
- `:declared` (536-575 `declared-producers`, 40 lines) = the value's schema (declared by metadata
  `:seon.error/declared-schema`, else the contract's return alternative it satisfies, else the attribute, else owner)
  chosen "most specific" along `:and` ancestry (`most-specific` 406-412, `refined-names` 389-404, `canonical-name`
  380-387, `named-alternatives` 364-378, `output-alternatives` 414-427, `satisfied-alternatives` 429-436).
- `:floor` constant.
- Each stage returns a stage-evidence record (`selection-stage` 585-594, `selected-stage`, `finish-selection`,
  `selection-candidate`), re-derived by `selection-inspection` (759-787) for the debug page.

Why it is ~5x longer than clerk's: (1) specificity is COMPUTED per call from schema ancestry, instead of fixed once by
order; (2) ambiguity is a runtime error value, instead of impossible; (3) every decision carries evidence data for
the inspection page, which clerk/portal do not have (they have a viewer-switcher UI instead); (4) candidates are
discovered by running Malli validators against the value at call time (portal does the same with `pred`, but over a
flat list). The semantic content Seon wants (render by declared schema, most specific, ambiguity refused) is
achievable with a registry vector built once per projection:

```
[{:schema k :validator v :render sym :face :ai/:html :rank depth-in-:and-DAG}] ; sorted by rank desc at projection build
(some #(when ((:validator %) value) %) registry)
```

with ambiguity (two renderers for one schema+face) refused when the registry is built, which is a registration-time
error with the schema key in hand, not a per-call one. `schema/projection-cache-value` (used at render.clj:395,
421) shows the per-projection once-cache already exists.

### 2.3 Elision handles and budgets: clerk and portal

**Clerk** (`viewer.cljc`):
- A global node budget, default 200: `->budget` 1718 `(:nextjournal/budget opts 200)`; `make-!budget-opts` 1721-1724
  creates an ATOM `!budget`; `present*` (1736-1760) decrements it once per presented node:
  `(when (and !budget (not presented?)) (swap! !budget #(max (dec %) 0)))`. Traversal is depth-first in
  the order of the value; when the budget reaches 0, `present+paginate-children` (1681-1705) clamps the page size
  `(update :n min @!budget)` (1687). So whatever comes first in DFS order gets the budget; later siblings get none.
  (Seon's breadth-first allocation is fairer; clerk's is 40 lines and mutable.)
- A page size per viewer type: `:page-size 20` vectors/sets/seqs, `10` maps, `80` strings, `2` map entries
  (`viewer.cljc:932-940`, string-viewer 882-888). The value is cut to `n` children by `drop+take-xf`; no per-node
  width accounting.
- The "more" handle is DATA: `make-elision` (1656-1662) wraps `fetch-opts` `{:n :offset :total :path}` in a
  `elision-viewer` (1043-1044, `:render-fn render-elision`); `get-elision` (1672-1678) computes `:total` with
  `bounded-count-opts` so an infinite seq never hangs; strings use `subs` offsets (1707-1717).
- Fetch: the whole presented tree is stored by path (`store!-wrapped-value`, `present*` 1739-1741) and the root
  carries `:present-elision-fn` (`viewer.cljc:1856`, `(partial present-elision* !path->wrapped-value)`); the
  webserver exposes it (`webserver.clj:103-110` `serve-blob`: `desc (present-elision-fn fetch-opts)`). The browser
  asks `?n&offset&path`. This is "dig on result handle" with the handle being a blob id + path, requested by the
  UI, not typed by an agent.

**Portal**: no server-side budget. The runtime serialises the whole value with CSON (`runtime/cson/writer.cljc`, only
JS-integer limits `57-78`) and the UI renders lazily: `ui/lazy.cljs:70-79`

```clojure
(defn lazy-seq [_coll opts]
  (let [{:keys [default-take step] :or {default-take 0 step 10}} opts n (r/atom default-take)] ...
    (let [[head tail] (split-at (or @n default-take) coll)] [:<> head (when (seq tail) [visible-sensor #(swap! n + step)])]
```

An `IntersectionObserver` sentinel (`lazy.cljs:7-20`) increases the take when scrolled into view. The "more" handle is a
DOM node; the cut value never needs a name because nothing is cut on the server. (Portal pays in wire size and relies
on a browser; an LLM reader has neither, which is exactly why Seon needs its own cut.)

**Seon**: the cut is a node `{::elision-unit ::omitted ::prefix ::requery-form ...}` rendered as text
`…N (drop 32 result/e7b3)` (`print.cljc:328-351` `form-text`, `elided-text`), or as a data map
(`render-elision-ai` 352-392), derived from `Cursor` + `requery-id` (`render/data.clj` 28-97, `node-id`
`value.clj:233-262`). The budget is a token estimate allocated across levels, measured in laid-out characters
(2.1c). Differences from clerk worth noting:
1. Clerk's cut is one map; Seon has three functions for one cut text (`form-text`, `elided-text`,
   `render-elision-ai`) plus `cut-text`/`node-continuation`/`cut-node`/`fold-collapsed-tail` in value.clj
   (586-616, 1098-1155): ~190 lines for "show `…N` and how to get the rest".
2. Clerk's handle is addressed by `path` only (no per-value identity); Seon's agent-typed form needs a result handle
   (`result/e7b3`) because an LLM must be able to type it; that identity machinery (`value-identity` 187, `node-id`
   233, `identity-address` 206) is required and not duplicable by clerk.
3. Clerk's budget is nodes, Seon's is tokens-of-laid-out-text. The second is required by CLAUDE.md "state the size
   of anything an agent sees in estimated tokens". It need not be exact to the layout character.

### 2.4 Web and SSE: hyperlith/datastar-clojure/http-kit vs web.clj

**Hyperlith (1,384 src lines) gets a live multi-user page with:**
- One full re-render per tick, no server-side diff: `render-handler` `datastar.clj:136-165` renders `(render-fn req)`
  to a hiccup tree, writes it as ONE `datastar-patch-elements` event, and relies on (a) the browser's DOM morph
  (Datastar uses idiomorph) and (b) a shared-window brotli stream (`br/compress-out-stream out {:window-size
  br-window-size}`, 18 = 262 KB window) so repeated page bytes compress to near zero. The diff is delegated to the
  browser and to the compressor; none is in application code.
- A `core.async` mult as the only fan-out: `(a/tap (:hyperlith.core/refresh-mult req) (a/chan (a/dropping-buffer 1)))`
  (`datastar.clj:104-108`); dropping buffer 1 means a slow tab simply skips ticks.
- Registration of a view is one macro (`core.clj:defview`), router a map.
- A shim page that never changes (ETag, 304), so the view stream carries all content (`datastar.clj:60-92`).

**Seon (render proc + feed + package machinery, ~620 lines)** instead:
- Computes a per-page serialized KEYFRAME and DELTA with revision numbers on the server (`next-package`
  web.clj:1793-1815): `delta (into (sorted-map) (filter (fn [[id html]] (not= html (get keyframe id)))) page)` plus
  both byte arrays and sizes, `package-patches` picks delta only if `base-revision` matches the tab's delivered
  revision and the delta is smaller (1817-1831), `feed` loops on `delivered-revision` and drops stale basis
  (2820-2862). So there are four concepts (revision, base-revision, keyframe, delta) to ship what hyperlith ships
  by "send the view, the browser morphs it".
- Honest tradeoff: Seon's pages are large (debug/session pages), the per-surface map `id -> html` already makes the
  delta trivial, and the user-facing constraint is "sub-second, don't cause storage/CPU growth", so a server-side
  surface diff is defensible. But the machinery for retention across passes (`::packages` in a shared atom
  `render/shared-cache`, render.clj:1695; `swap! cache assoc ::packages`, web.clj:2415) is what costs lines: the
  delta could be computed per connection against the last bytes THAT connection wrote (a loop variable in `feed`),
  deleting revisions and the retained package map (item 5).
- Backpressure: `write-package!` (2704-2751) parks the virtual thread on http-kit's drain future using a fork-only
  atomic write-state (`::write-state http/write-state`, 2693) and the generic `await/await!` with a diagnostic map
  (24 lines of error-shaped config per await). hyperlith's `dropping-buffer 1` plus `send!` returns the same
  protection with 0 lines. The drain-park exists because a slow socket otherwise queues unbounded bytes in http-kit;
  hyperlith avoids the queue by dropping ticks before rendering, Seon renders per page not per tab and writes per tab.
- Error values at every await: `await-feed-package!` 2757-2784 builds a 10-key diagnostic around `await/await!`,
  and `render-step` builds a 12-key `ex-info` for a missing port (2573-2585). This is CLAUDE.md "Errors are values of
  one shape" and it shows in line counts: a 3-line hyperlith `(a/<!! <ch)` is 25 lines here. A constructor
  `(error/await-diagnostic operation layer member expected)` would remove ~15 lines per site; there are at least 6
  sites in web.clj (grep `:seon.await/diagnostic`).

**datastar-clojure `sdk-http-kit` (227 lines)** is already used (`web.clj` requires `datastar.http-kit`); Seon adds
`socket-leaf` (2688-2702) to inject `send!`/`write-state`/`close-sse!` for tests, which is dependency-injection by
map; this is not duplicated library code, it is the test seam.

**Routes**: reitit table in `route.clj` (60 lines) plus `bind-handlers` (web.clj:3903-3914) that postwalks the
route table to substitute handler fns by name (12 lines): hyperlith uses a path digest and a macro (`core.clj`
`defview`), 8 lines, but Seon's table is data and reverse-routable (`route/path`), the right call for this project.

### 2.5 Hiccup: chassis (hyperlith) vs `seon.render.hiccup`

hyperlith `html.clj:1-32`: `(def html->str h/html)` etc. All of escaping, void elements, attribute booleans, class
vectors, raw strings, streaming writes are chassis. Seon's 525-line file re-implements these with one `StringBuilder`
(486-523) and ADDS: strictness (`hiccup?` 94-153: invalid -> refusal -> error card), determinism (sorted attributes,
382-410), style maps (338-365), camelCase tolerance. Two of those (determinism, strictness) are Seon requirements
(`equality suppression, the SSE diff, and byte identity` rest on determinism, hiccup.clj docstring). chassis writes
attributes in map iteration order, which for map literals built in code is stable but not for maps assembled by
`merge`/`assoc`; so a chassis swap would need a `sort`-before-render step or a test that proves determinism for the
call sites. R4 of the dedup spec forbids a new dependency in a dedup slice, so the realistic cut here is the
generator (60 lines in src) and the unused tolerance paths, not the file.

### 2.6 Debug UI: portal's inspector vs Seon's bespoke pages

Portal's inspector shows ANY value by recursion over viewers (the `inspect` fn dispatches `get-viewer` per node,
`inspector.cljs:~560-700` UNVERIFIED line detail), with a viewer picker per location, table/diff/exception
as ordinary viewers (`ui/viewer/table.cljc` 329: `{:predicate table-view? :component ...}` at 329). So adding
"show a ledger" is "register a viewer for the ledger-shaped value". Seon's debug pages are functions from a request
to hiccup with the finding logic, Datalog and layout fused in one function
(`ledger-turn-body` 98 lines: pulls, filters, partitions, groups and emits `[:section ...]` in the same `let`).
That is why `transcript.clj` cannot get smaller by local edits: separating "compute data" from "render data" (the
project's own "Take things apart" and "Return data, not comments; renders return data" laws) is the cut, and the
data half would be rendered by the generic value renderer that already exists (`emit-hiccup` print.cljc:639), as
portal does with its default `pprint`/tree viewers (`ui/viewer/tree.cljc`).

---

## 3. How clerk and portal choose a viewer and cut large values; vs Seon

| question | clerk | portal | Seon today |
|---|---|---|---|
| what is a viewer | map `{:name :pred :render-fn :transform-fn :page-size :opening-paren ...}` | map `{:predicate :component :name :doc}` | a Var (function) with Malli contract; face attached as schema property `:seon.render/ai|html` or by namespace contract fit |
| registry | ordered vector `:nextjournal/viewers`, `add-viewers` prepends, per-ns (`get-viewers`, viewer.cljc:520) | atom `api/viewers` list, user-registered | Malli registry projection; function symbols stored as schema properties; viewer functions of the viewing namespace discovered by `sci.kernel/public-functions-in` |
| selection | explicit key, else FIRST `pred` match in order (viewer.cljc:1481-1497) | selected-by-user, else first `predicate` match (inspector.cljs:115-155) | 4 stages; per-schema most-specific by `:and` ancestry; >1 candidate refused at call time |
| specificity | position in the vector | position + `:portal.viewer/default` hint | computed (`most-specific`) |
| where it runs | server: `transform-fn` only; client: `render-fn` | client | JVM: render fn invoked inside SCI/branch with db value, results captured with read evidence |
| size cut | per-viewer `:page-size` + global `!budget` atom (default 200 nodes), DFS order | none on wire; lazy render by IntersectionObserver in UI | token budget `B` spread breadth-first by level/rank/ordinal; width/depth/string bounds from render profile |
| cut handle | an `elision-viewer` node with `{:n :offset :total :path}`; fetch by blob id + path via `present-elision-fn` | none (DOM sentinel) | a node with `::requery-form` text `(drop N result/handle)` (an agent types it); stable `result/e7b3` ids |
| laziness | `bounded-count-opts` for infinite seqs, `drop+take-xf` | DOM-level | `take`/pending seq stepped to member limit (`more?` 616-625: "A counted seq is stepped only to its member limit") |
| re-render caching | presentation cache by form hash + deps | none | per-call retained output, read evidence and program identity (`render-call`) |

What this table says: the three differences that cost Seon lines are (1) specificity and ambiguity computed per call
(selection), (2) layout prediction inside the budget (value.clj 569-727), (3) retained-call evidence (render.clj
800-1010). The difference that is NOT a cost but a requirement is the typed requery handle; clerk's data-only handle
(`{:n :offset :total :path}`) shows the shape: one map; Seon's text is derived from the same map at the edge.

---

## 4. Top 10 reductions

Ordered by (lines removed x confidence). "Overlap" = already in render-dedup.md, so root's R1 ruling covers it. Line
numbers are today's.

### 1. Remove test.check generators from the production printer namespaces
- Seon: `src/seon/print.cljc:13-172` (`literal` 13-18 stays; `print-number?`, `number-generator`, `print-char?`,
  `char-generator`, `projected-node-generator`, `elision-node-generator`, `generated-item-options`,
  `generated-item-identity`, `distinct-generated-nodes`, `node-generator`) and `src/seon/render/hiccup.clj:154-212`
  `hiccup-generator`, plus `:gen/...` exemptions that exist for them. Only consumer found:
  `test/seon/print_test.clj:198`. (Also removes `clojure.test.check.generators` from `print.cljc:6` ns require, so a
  `.cljc` production ns no longer loads test.check.)
- Replacement: delete from src; the one property test uses `malli.generator` on `:seon.print/node` (the schema is
  already declared, `print.cljc:727-760` `node?`) or ~20 hand-written nodes in the test.
- Lines: about -160 (print) -60 (hiccup) = **-220 src**, +30 test.
- Risk: low. UNVERIFIED that no `resources/seon/*.edn` schema references the generators by symbol (a grep of `src
  test resources` for `print/node-generator` found only the test; the grep over resources hit a 620 KB parity file
  and was not conclusive for symbol references with other prefixes). Check `:gen/` keys that name them before cutting.

### 2. Delete the layout predictor; budget by flat width, lay out once with fipp
- Seon: `src/seon/render/value.clj:311-345` (constants and `escaped-width`, `integer-digits`), `569-727`
  (`leaf-width`, `flat-width`, `nest`, `tuple?`, `extras`, `start-column`, `breaks?`, `chain`, `settle`), their callers
  in `make-node` (776-837), `charge` (839), `grown-to`/`admit-*` (867-1020), and `:flat/:sum/:broken?/:indent` fields on
  nodes.
- Replacement: per node charge = flat width (already `:width` of leaf text, `(count open+close)`, separators 1-2) with
  no break/indent term; accept overshoot up to the number of lines x indent (bounded, <= ~15% in practice, UNMEASURED);
  `emit-text` then lays out with fipp once. If measured size exceeds the budget by > 10%, re-run `select-nodes`
  with budget scaled down (max 3 passes; most values need one). The token estimate is already approximate
  (`tokens/estimate-chars`), so character-exact accounting buys false precision.
- Lines: about -200 (value.clj 1479 -> ~1280). Also removes the incremental `settle` cascade, which is the
  super-linear risk named in CLAUDE.md "What the work is proportional to".
- Risk: medium. Behaviour change = budgets become approximate; `value_test.clj` (1288 lines) pins exact cut points
  (UNVERIFIED how many assert exact widths). Needs owner agreement that "budget is a bound within 10%", and an Opus
  lane (it changes the one REPL path's print). Measure first: time and bytes of the current `select-nodes` on a
  1 MB map vs a flat-width version (not done here).

### 3. `classify` scalar branch -> `pr-str` with a cap
- Seon: `src/seon/render/value.clj:428-541` (114 lines; scalars 430-475 are ~45 lines, bigint/ratio/bigdec/ratio
  text 448-466).
- Replacement: `(scalar-node face x (let [s (pr-str x)] (if (> (count s) scalar-bound) (summary-for x s) s)))` with one
  class-family `cond` (nil/boolean/number/char/string/keyword/symbol/uuid/inst). `(pr-str ##NaN)` is `##NaN`,
  `(pr-str 1N)` `1N`, `(pr-str 1/3)` `1/3`, `(pr-str #inst ...)` `#inst "..."`, `(pr-str \a)` `\a`. Keep the
  bigint/ratio digit summaries (the `≈ N digits` case) as one helper.
- Lines: about -60.
- Risk: low-medium (exact text of edge scalars pinned by tests, e.g. characters with names; `char-name-string` helper
  stays or goes with `pr-str`). Needs `value_test` scalar cases to pass unchanged; unverified here.

### 4. Selection: one registry vector built per projection, first match wins
- Seon: `src/seon/render.clj:286-302` (`ambiguity`), `364-436` (`named-alternatives`, `canonical-name`,
  `refined-names`, `most-specific`, `output-alternatives`, `satisfied-alternatives`), `577-787` (stage records,
  `selection`, `selection-inspection`), and the `:declared` branch of `declared-producers` 536-575.
- Replacement (clerk `find-viewer` + `viewer-for`, viewer.cljc:1470/1481): at `schema/projection-cache-value` time build
  `[{:schema :validator :ai-sym :html-sym}]` sorted by specificity (topological order of the `:and` parents Seon
  already derives in `refined-names`); selection = explicit key, else namespace-contract fit, else
  `(some #(when ((:validator %) value) %) registry)`, else floor. Ambiguity (two same-face renderers on one schema)
  is refused ONCE when the projection is built. `selection-inspection` becomes `(filter ... registry)` returning the
  matching entries in order, which is all the debug page lists.
- Lines: about -200 (330 -> ~130).
- Risk: medium-high. This is "semantics of the one REPL path" (CLAUDE.md model-by-risk: Opus). Behaviour that must be
  kept: attribute-scoped requests (489-503), Var identity (`:seon.render.var/var`, 557-560), record `ILookup` rule
  (561-563), `entity-owner` fallback (565). Needs a branch-level proof that no existing declared pair changes selection
  (diff the selected producer for every registered schema x face before/after; cheap).

### 5. Per-connection deltas instead of retained revision packages
- Seon: `web.clj:1782-1831` (`frame-bytes`, `package-bytes`, `next-package`, `package-patches`), the `::packages`
  parts of `render-pass` (2315-2419: `next-package` reduce 2391-2412, `swap! cache assoc ::packages`, `latest-packages`
  reset), `feed`'s revision bookkeeping (2820-2862), `render-step`'s `latest-packages` join branch (2661-2667), and the
  `::packages` entry in `render/shared-cache` (render.clj:1695).
- Replacement (hyperlith `render-handler` datastar.clj:136-165 idea): the render proc publishes only
  `{registration-key {surface-id html}}` (the page map it already computes, `:seon.render.web/page`); each tab's
  `feed` keeps `last-sent` as a loop variable, writes `(into {} (remove (fn [[id h]] (= h (get last-sent id)))) page)`
  as one patch (the exact filter `next-package` applies today at 1801-1805), and a first/after-gap write is the full
  page. A reconnect is a new connection = a full page by construction, so base-revision matching disappears. Optionally
  brotli the stream with a shared window (hyperlith 152-162), which would make deltas unnecessary for bandwidth.
- Lines: about -130 (and the revision fields from schemas).
- Risk: medium. It moves delta computation from per-page to per-tab (N tabs on one page = N string-equality passes over
  the changed map; trivial) and removes the retained map that currently survives the proc. Reconnect tests 23-42 s
  (render-dedup.md section 5 item 5) may be tied to this path; measure first (UNVERIFIED). Overlaps D1's cache
  merging; do after D1.

### 6. Debug "experiments" and "applicable renderers" rendered by the generic value HTML face
- Seon: `web.clj:1140-1250` (`applicable-renderers-html` 1140-1177, `debug-selected-renderer-details`,
  `debug-renderer-contract`, `selected-unit-experiment` 1117-1139, `experiment-preview-html` 1024-1048,
  `debug-render-experiment` 1441-1513) ~ 250 lines of hiccup assembled by hand over data that
  `render/selection-inspection` already returns as a map.
- Replacement: `(render/render-call {... :seon.render/value (selection-inspection request) :seon.render/output
  :seon.render/html})` (the floor face `render.value/render-html` renders any map as a collapsible tree,
  `print.cljc:607-629`), and one `<form>` to choose a producer. This is exactly what portal does with its default
  viewer for unknown shapes. Overlap with spec D5 (-250) but my cut is deeper: not "merge two experiment renderers"
  but "no bespoke renderer".
- Lines: about -200.
- Risk: low-medium. Debug-page look changes; `web_debug_test.clj` (1169 lines) asserts on hiccup (UNVERIFIED which
  assertions pin structure); test assertions on data (the inspection map) survive.

### 7. One graph page: data to JSON, legend and grouping client-side
- Seon: `web.clj:857-905 graph-model` (45), `906-936 debug-graph-html` (31), `3227-3288 graph-node-name,
  inclusion-paths, edge-kind, node-attributes-html` (62), `3290-3349 context-graph-model` (60), `3350-3388 graph-legend,
  graph-debug-href, graph-node-label` (39), `3405-3543 graph-debug-html` (139) = ~375 lines; plus `seon-graph.js` 392
  and the vendored cytoscape (resources/public/js).
- Replacement: both models emit `{:nodes :edges}` through `render.walk/graph` (already `walk.clj:1118`
  "graph", 58 lines); the data route (`data-response`, 3811-3897) serves it as JSON; the legend and grouping live in
  `seon-graph.js`. One Clojure function of ~80 lines.
- Lines: about -250 Clojure (spec D4 estimated -350 combined with its own cut; I stay lower because I did not read
  `graph-debug-html`'s AI/HTML pairing).
- Risk: low (debug only). Not verified that nothing but the debug page uses `graph-model`.

### 8. Separate data from hiccup in transcript ledger/outline/problems, render data with generic viewers
- Seon: `transcript.clj:1532-1614 render-outline` + `1396-1530` outline helpers (~230), `1757-2231` ledger
  (`ledger-turn-body` 98, `ledger-strip` 50, `render-ledger` 52, `ledger-emissions`, ledger heading helpers) (~470),
  `session-problems`/`prefix-problem` 1989-2060ff (~200). ~900 lines of the 2,333.
- Replacement: each of these functions becomes `(defn turn-ledger [db turn-id] -> map)`: bytes, effects, outcomes,
  calibration, findings as plain data, with a contract; the AI face prints the map through the value printer
  (budgeted, requery handles for free); the HTML face uses `emit-hiccup` plus a small table renderer for the per-turn
  rows. The colour strip stays as ~30 lines of hiccup over the same data. Pattern: portal's table viewer
  (`ui/viewer/table.cljc:329` registration) over rows.
- Lines: about -450 if features are kept as data (R1: "keep every feature"; this keeps every FACT, loses bespoke
  layout, so it needs owner sign-off). -900 only if the ledger+outline views are cut (render-dedup.md section 6 Q1).
- Risk: medium-high: it is where the spec's own "honest limit" sits, and `transcript_test.clj` (1502 lines) asserts on
  rendered text/hiccup. Needs a spec first (feedback: SPEC BEFORE REWRITE).

### 9. Fold the three bounded-text/`datum` printers into `pr-str` with built-in bounds
- Seon: `src/seon/print/value.clj:34-72` (`datum` + `bounded-text`, 39 lines) and the delegates `render/value.clj:76-90`
  (`bounded-text`, `floor`, 15 lines), `error.clj:356 bounded-text`, `error.clj:853 bound-text` (UNVERIFIED sizes;
  render-dedup.md D6).
- Replacement: `(binding [*print-length* 8 *print-level* 3 *print-meta* false *print-namespace-maps* false] (pr-str x))`
  then cap at `limit` chars with an ellipsis. `*print-length*` and `*print-level*` are what fipp (`edn.cljc:9-21`) and
  Clojure core already honour; `datum` exists to (a) render host objects as `#object[Class 0xhash]` without
  `print-method` and (b) mark cut members with `...` as DATA. Keep (a) as `print-method` guard:
  `(binding [*print-dup* false]...)` plus a pre-walk is what `datum` does today, so the saving is only in not
  duplicating at the other two sites.
- Lines: -50 to -80 across the three sites (`datum` itself stays, the delegates and `error.clj` copies go).
- Risk: low (D6 already ruled). Caller conversion needs a grep that the shell glob failed on in the spec; do it with
  `rg 'bounded-text|value/floor|bound-text' src test`.

### 10. Walk acquisition: derive one bounded pull from the schema and walk the result
- Seon: `render/walk.clj:129-175 root-selector/entity-lookup`, `251-345 acquisition-members`,
  `345-541 root-pull-plan/namespace-connections/acquire-entity/acquired-tree/root-acquisition` (~200),
  `541-790 forward-refs/.../declared-acquisition/neighborhood` (~250): ~700 lines that acquire the entities within a
  distance with node caps.
- Replacement: Datahike `pull` with a pattern generated once per (schema, distance) from the declared render concerns
  (`root-selector` already builds this pattern at 129-159) using nested map-specs `{:attr [...]}` to depth N or
  the `...` recursion form, `:limit` per attribute, reverse attributes `_attr`; then `tree-seq` over the pulled map
  to emit render-call units with the distance each node was reached at. Datahike owns the traversal (CLAUDE.md "Read
  the dependency first. Datahike owns ... pull").
- Lines: -300 to -400 IF the pull handles per-node caps and continuation; UNVERIFIED whether `datahike.pull-api`
  supports a global node cap (I did not read `reference-code/datahike/src/datahike/pull_api.cljc` for this). Treat as
  a hypothesis to measure: time `(walk ...)` on the real store vs a single `d/pull` with the pattern.
- Risk: high. `walk` is on the agent's prompt path (CLAUDE.md "the path is total"; D1) and its continuation/read
  evidence feed the retained-call cache. Opus or Astra lane, spec first.

### Totals and where they sit against the target

| item | est. lines | overlaps spec | confidence |
|---|---|---|---|
| 1 generators out of src | -220 | D9 (-150) | high |
| 2 layout predictor | -200 | none | medium |
| 3 classify via pr-str | -60 | none | medium |
| 4 selection registry | -200 | none | medium |
| 5 per-connection deltas | -130 | D1 (cache) | medium |
| 6 experiments as generic | -200 | D5 | medium |
| 7 one graph page | -250 | D4 | high |
| 8 ledger/outline as data | -450 | D2/D3/D7 | low-medium |
| 9 one bounded printer | -60 | D6 | high |
| 10 pull-based walk | -300 | none | low |
| **sum** | **~ -2,070** | spec items overlap ~1,000 | |

The spec's D1-D8 already plan ~3.9k; my items add roughly 1.1k not in it (2, 3, 4, 10 and the deeper parts of 6, 8)
for a combined area of about 14.5k - 3.9k - 1.1k = ~9.5k, i.e. under the 10k aim for the area alone, but the owner's
~10k is for ALL of src (91k today), so this area at ~9.5k still consumes the whole budget. Reaching a share that
fits (the spec's own estimate: ~1.5k for this area) is not a refactor: it is the redesign in section 5.

---

## 5. What a 1.5-3k render/web area would look like (the libraries' shape)

Not a recommendation to start; the spec rules R1 against feature cuts. This is the shape the libraries suggest.

1. **One printer, ~400 lines**, in the fipp style: a `visit`-like `cond` over value kind -> `[:group open [:align ys
   ellipsis] close]` fipp document, with `print-length` and `print-level` derived from the token budget by one
   `budget->bounds` function; text via `fipp.engine/pprint-document`; hiccup via the same document (Seon already
   folds fipp ops into hiccup, `ops-hiccup` print.cljc:607-629, ~25 lines). Elision = one map
   `{:omitted n :requery form}` as clerk's `elision-viewer` value, rendered at the edge. Fair breadth-first allocation
   kept as a small "choose bounds so that the printed size fits" loop, not an incremental solver.
2. **One viewer registry, ~150 lines** (clerk's `find-viewer`): ordered vector of `{:schema :validator :ai :html}` per
   projection; explicit key and viewing-namespace functions as the first two lookups.
3. **One retained-call check, ~150 lines**: clerk's cache keys by dependency hash; Seon's equivalent is the call's
   read evidence; one function `(current? call db)`.
4. **Web, ~600 lines**: hyperlith's shape: reitit table, shell page, one SSE handler per page that renders and writes
   changed surfaces, `http-kit` + `datastar-clojure` as today; pages are functions of `(db, request)` returning
   `{surface-id hiccup}`. All state per connection is loop variables.
5. **Debug UI, ~600 lines**: every debug page is `(render-call {:value (a plain data map from one query function)})`
   through the generic tree/table viewers, with ~10 small domain viewers (colour strip, byte bar) registered like
   portal's.

The cost is the debug UI's bespoke layout and the exactness of the budget. Both are product decisions, listed in
section 6.

---

## 6. Questions for root (each needs a ruling before a lane starts)

1. Is a 10% overshoot of the token budget acceptable if it deletes the layout predictor (item 2)? Measure first:
   time and size of current vs flat-width selection on three real values (a 100 KB pulled entity, a 1 MB result map,
   a long string). Proposed owner: Opus lane on a Seon branch, not Sonnet (it changes the print).
2. May item 8 turn per-turn ledger/outline into data rendered by the generic value renderer plus a small table/strip
   view, losing bespoke layout but keeping every datum? That is inside R1 only if "feature" means data, not layout.
3. Item 5 and the render-dedup D1 cache merge both touch `render/shared-cache` (render.clj:1695) and
   `render-pass`: one owner, D1 first.
4. Item 10 (walk via one pull): is a probe comparing `(walk ...)` against a generated `d/pull` pattern on the live store
   worth an Astra slice (CLAUDE.md "Astra takes the hard performance work")? The proportionality question is
   "work per walk is proportional to nodes shown", and I have not measured it.
5. chassis for `hiccup.clj`: if the owner lifts R4 for a dependency that is already in `~/.m2`, the file shrinks from
   525 to ~120 (keep `hiccup?` as the strict gate, drop the serializer), at the cost of dropping sorted-attribute
   byte determinism unless chassis call sites are proven stable.

Not read (UNVERIFIED): datahike pull_api limits, bodies of graph-debug-html/acquire-debug-data, render/ns|var|schema|test renderers, seon.sci.admit (868 lines, adjacent budgeted print stage), error.clj bounded-text copies, clerk render.cljs.
