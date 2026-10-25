# Field research: render systems for data (Seon render design)

Verified = read in source/docs this session. "Memory" = not re-verified.

## 1. Nextjournal Clerk viewers (closest prior art)
Source read: https://github.com/nextjournal/clerk/blob/main/src/nextjournal/clerk/viewer.cljc (verified, ~2050 lines)
- A viewer is a plain map: `{:name sym :pred fn :transform-fn fn :render-fn quoted-cljs-form :page-size n ...}`.
- A value is wrapped `{:nextjournal/value x :nextjournal/viewer v :nextjournal/viewers [...]}` (`ensure-wrapped`).
- `with-viewer` just attaches a viewer to a value, overriding selection:
  `(clerk/with-viewer '#(vector :h3 "Hello " % "!") "x")`  (notebooks/viewer_api.clj)
- Selection (`viewer-for`): if the value carries a viewer (symbol/keyword = look up by :name), use it; else FIRST viewer in the ordered `default-viewers` vector whose `:pred` is true of the value; none -> throw. Order is priority; `fallback-viewer` has `:pred (constantly true)`.
  `(clerk/with-viewers (clerk/add-viewers [{:pred number? :render-fn '(fn [n] [:div ...])}]) 42)` prepends custom viewers.
- `apply-viewers*` loop: pick viewer, run `:transform-fn` on the wrapped value, and if the viewer has no `:render-fn` re-select on the result (max 10 iterations). So a transform-fn can rewrite data into another typed value and let selection continue. Nesting: `:transform-fn` of collections wraps children with the same viewers (`:nextjournal/viewers` is inherited by children); `render-fn` of a coll renders children via `inspect-children`.
- Two phases: JVM presents (select + transform -> pure data tree carrying viewer + render-fn symbol), browser renders (`render-fn` is a CLJS form evaluated client-side). Presentation is data you can print/inspect.
- Large data: each coll viewer has `:page-size` (map 10, vector 20, string 80). Present takes a budget (`:nextjournal/budget`); items beyond it are replaced by an `elision-viewer` value `{:count :offset :remaining :path}`; the browser later asks the server to `present-elision` that path/offset (lazy pagination). Lazy seqs like `(range)` are paged by `bounded-count`.
- Take: viewers-as-data + first-match pred list + transform-fn that can return a different typed value + elision node as DATA ("N more, here is how to fetch") is the model to copy. Skip: client/server split, cache, budgets by blob.

## 2. Portal and datafy/nav (verified from docs/portal repo)
- clojure.datafy (https://clojure.org/reference/datafy): `(datafy x)` -> data (via `Datafiable`, or `::datafy` meta); `(nav coll k v)` -> next value for following a key/value (via `Navigable`, or `:clojure.datafy/nav` meta). Lazy graph walking: each node is plain data + metadata saying how to go further. REBL / Portal use exactly this: click a value -> `nav` -> new node.
  ```clojure
  (extend-protocol Datafiable java.io.File (datafy [f] {:name (.getName f) :files (seq (.listFiles f)) :parent (.getParentFile f)}))
  ```
- Portal viewers (doc/ui/viewers.md): each viewer has a predicate; the UI filters viewers by value and offers the applicable ones. Default is chosen by priority, overridable by METADATA on the value:
  `(tap> ^{:portal.viewer/default :portal.viewer/hiccup} [:h1 "hi"])`; and the hiccup viewer allows `[:portal.viewer/markdown s]` as a "component" tag, i.e. viewers addressable as hiccup tags.
- Take: for Datahike, `datafy`/`nav` over an entity (`{:db/id 5 :agent/task {:db/id 9}}` where refs are nav-able) is the standard lazy graph protocol. Selection by pred + metadata/schema override.

## 3. Reagent / hiccup / Replicant (verified: reagent doc, replicant readme)
- Reagent: a component is a plain fn; `[my-fn {props} child ...]` is data, called by the renderer with `(apply my-fn props children)`; "data in, HTML out". Form-2/3 add state/lifecycle (not needed for text).
- Replicant (cjohansen): deliberately NO components-with-state, no atoms/subscriptions: "State (data) in, hiccup (data) out"; the whole UI is one pure fn from data to hiccup. Context is passed by the caller as ordinary args. (Replicant does allow fn tags? Its readme says components are not a primitive; unverified detail.)
- Context injection precedents: React context / re-frame `subscribe` are both "implicit lookup of a shared value inside render". Simplest server-side analogue: a dynamic var or, better, an explicit first-arg/ctx map `{:db db :medium :text}` threaded by the one `render` walker (no globals). Hiccup2/server hiccup have no injection; they just call fns.

## 4. Malli-driven rendering (verified partly)
- No mature library dispatching UI from Malli (search: only prototypes/blog posts, e.g. Flexiana "Malli and HTML forms as data", `malli.util/path-schemas`, ui-kit by RutledgePaulV). People walk the schema (`m/walk`, `m/children`, `malli.util/subschemas`) and map subschema -> component themselves. So: build it, it's small.
- Render hints as schema properties: `[:map {:render/text 'seon.render/task-line :render/html 'seon.render/task-card} [:task/id :uuid] ...]`; read with `(m/properties s)`; works on registry-named schemas `(m/deref s)`. Properties are arbitrary keyword-keyed data, qualified keys recommended.
- Registry lookup: schema by keyword name via the registry `(m/schema :seon/task {:registry r})`; validating arbitrary data against candidates: `(m/validate s x)` / `m/parse` with `:orn` gives which branch matched.
- Function schemas (verified, docs/function-schemas.md): `(m/=> f [:=> [:cat :int] :int])`, `:malli/schema` var metadata + `(mi/collect!)`, `mx/defn` inline; retrieve at runtime `(get-in (m/function-schemas) '[ns f :schema])`; multi-arity via `[:function ...]`. So (c) "fn needs db" = inspect first `:cat` entry of its function schema for a `[:= db-schema]`/ `:seon/db` marker (or a property `{:inject :db}` on the arg schema) and prepend the db.

## 5. How harnesses present REPL/tool output to the model
- Anthropic "Effective context engineering" (https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents, verified): goal "the smallest possible set of high-signal tokens"; context rot; tool-result clearing as "safest lightest touch" compaction; just-in-time retrieval: keep lightweight references (paths, queries, links) and load on demand (Claude Code does); structured note-taking outside the window; tools should return token-efficient info.
- Anthropic "Writing tools for agents" (https://www.anthropic.com/engineering/writing-tools-for-agents, verified): `response_format` concise|detailed (concise ~1/3 tokens in their example); pagination/range/filter/truncation with sensible defaults (Claude Code caps tool responses at 25,000 tokens); when truncating, say how to narrow ("steer agents"); actionable error messages not tracebacks; natural-language names over UUIDs (fewer hallucinations) but keep ids where needed to chain calls; format (XML/JSON/Markdown) matters and should be evaluated per model.
- SWE-agent ACI (https://arxiv.org/abs/2405.15793, abstract verified; details MEMORY, unverified): simple few-option commands; windowed file viewer (~100 lines) with position header; search results capped (suppresses >50 hits and tells the agent to refine); explicit message for empty output; linter feedback on edit; old observations collapsed in history.
- OpenHands (docs.all-hands.dev condensers, verified via search snippets): truncates long observations from the MIDDLE (keeps head and tail); observation-masking condenser replaces values of observations outside a recent window; browser-output condenser drops huge accessibility trees.
- Aider (https://aider.chat/docs/repomap.html, verified): repo map = files + key symbol signatures, graph-ranked, fitted to a token budget (`--map-tokens` default 1k). Lesson: a rendered SUMMARY fitted to a budget by importance, not by position.
- Jupyter AI: nothing found; skip.
- Common findings: concise by default; every cut states what was left and how to get it (matches AGENTS.md "no clipping"); stable names; summary of state at the end (recency) ; old results masked, not deleted.

## 6. Synthesis: simplest design for (a)-(e)
Principle: ONE walker `render`, turning data into a tree of hiccup, then ONE per-medium emitter. Selection is Clerk's (pred/schema), composition is Reagent's, graph walk is datafy/nav, hints are Malli properties.
```clojure
;; ctx = {:db db :medium :text|:html :budget n}      ; ctx is an explicit arg (Replicant style), no globals
(defn render-fn-for [ctx x]                          ; (b) Clerk viewer-for, by schema instead of pred
  (let [s (schema-of ctx x)]                         ; match registry schemas: :db/ident type attr, else m/validate over candidates
    (get (m/properties s) (medium-key ctx))))        ; {:render/text f :render/html g} on the schema (Malli props)
(defn call [ctx f props children]                    ; (c) inject db via the fn's function schema
  (let [in (first-arg-schema f)]                     ; (get-in (m/function-schemas) [ns name :schema]) -> [:=> [:cat in ...] out]
    (apply f (cond-> [] (db-arg? in) (conj (:db ctx)))  ;   [:cat [:= :seon/db] ...] or {:inject :db}
             props children)))
(defn render [ctx x]                                 ; the walker
  (cond
    (and (vector? x) (ifn? (first x)))               ; (a) [f {props} & kids] = Reagent component call
      (let [[f & r] x, [props kids] (if (map? (first r)) [(first r) (rest r)] [{} r])]
        (render ctx (call ctx f props (map #(render ctx %) kids))))
    (and (vector? x) (keyword? (first x))) (into [(first x)] (map #(render ctx %) (rest x))) ; plain hiccup element
    (map? x) (if-let [f (render-fn-for ctx x)] (render ctx (call ctx f {} [x]))   ; entity -> schema's render (transform-fn loop)
                (render ctx [:map-fallback x]))
    :else x))
(defn nav-kids [ctx e] ; (e) graph walk: datafy/nav over Datahike; refs become [:ref eid] nodes
  (d/pull (:db ctx) '[*] e))                         ; depth/budget in ctx; beyond budget emit elision DATA: [:more {:eid e :n 12}] (Clerk elision)
(defn emit [medium tree] ...)                        ; (d) :text -> indented lines, names not uuids, [:more ..] -> "(12 more: (render eid))"; :html -> hiccup2/html
```
Mapping to prior art: (a) Reagent/Replicant fn-in-vector; (b) Clerk `viewer-for` + `apply-viewers` loop, with the "pred" being a Malli schema; (c) new but small: Malli `m/function-schemas` as the injection declaration (nearest analogues: re-frame subscribe / React context, made explicit); (d) Anthropic concise/detailed `response_format` + Portal per-viewer-per-value choice, here as `:render/text` vs `:render/html` schema properties; (e) datafy/nav + Clerk elision-as-data; elision nodes tell the agent the exact call to see more (Anthropic "steer agents").
Cautions: validation per node is costly, so match by schema identity (attribute namespace / `:db/ident` type tag) first and `m/validate` only for untyped data; fall back to a typed `[:unrendered x]` fact, not silent `pr-str`.
