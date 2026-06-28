(ns seon.render.value
  "Structural value renderer — the render-twin applied to EVERY eval value.

   Where `seon.render` resolves a fn-per-surface for ENTITIES, this ns
   renders the RAW VALUE an eval returned, on the same two surfaces:

   - `render-ai`        → the agent-facing TEXT: a clipped but
                          structure-revealing skeleton the agent can
                          navigate with ordinary Clojure
                          (`(get-in result/<id> …)`, filter, count)
                          WITHOUT re-querying.
   - `render-html-data` → the DATA CONTRACT the interactive HTML panel
                          (UI session U's lane) consumes to build a
                          collapsible drill-down browser. This ns produces
                          PLAIN DATA only — no hiccup, no web classes.

   ## Why a sampler, not pr-str + char-clip

   The live eval path today (`seon.eval/render-result-edn`) projects opaque
   handles, then `pr-str`s, then CHAR-clips the string mid-token. A mid-token
   clip yields invalid EDN AND destroys the navigation metadata an agent
   needs to form a `get-in` path (research: Clojure REPL Data Sampling,
   §'Technical Constraints of Naive Truncation'). This ns instead builds a
   DEPTH- and BREADTH-bounded SKELETON of the value — every retained node
   keeps its real key/index, so a path read off the skeleton resolves
   against the live `result/<id>` value verbatim.

   ## What the skeleton preserves

   - navigation paths — map keys + vector indices intact (`get-in`/`nth`)
   - per-node TYPE + COUNT — `{…12 keys}`, `[…96 items]`, `#{…5 items}`
   - elision markers — `… +129 more`, plus, for a HOMOGENEOUS collection of
     maps, the shared key-set: `… +129 more each {:a :b :c}` (the column
     set of a 137-row query result, without scrolling 137 rows)
   - lazy-safe HEAD sampling — never realizes more than `max-items`+1 of a
     lazy/infinite seq
   - opaque handles — a datahike DB/Datom/Entity, a record, or a raw JS
     object becomes a compact tagged token, never a multi-KB index blob

   ## Marker vocabulary (the html DATA CONTRACT)

   The skeleton is plain data; non-plain nodes become reserved-namespaced
   marker maps the emitter + the U panel both read:

     {:seon.render.value/kind :vector|:set|:seq
      :seon.render.value/shown   [...]            ; bounded sample
      :seon.render.value/elided  n | :more        ; tail count (or :more)
      :seon.render.value/shape   [:k …]}          ; shared keys, if homogeneous
     {<k> <v> … :seon.render.value/elided-keys n}  ; map w/ elided tail
     {:seon.render.value/pruned :map|:vector|:set|:seq
      :seon.render.value/count  n}                 ; depth-limit prune
     {:seon.render.value/string-len n :seon.render.value/head \"…\"}
     {:seon.eval/opaque \"datahike/DB\" :seon.eval/summary \"max-tx=42\"}
     {:seon.eval/datom [e a v]}

   (The opaque/datom markers use the `:seon.eval/opaque|datom` reserved
   keys so the whole system speaks one vocabulary. The opaque-DETECTION +
   projection logic lives ONLY here; `seon.eval` requires this ns for both
   `render-ai` and `project-plain` — see ns-end note.)"
  (:require
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.platform :as platform]))

(defn- env-int [env-name default]
  (let [v (some-> (platform/env-val env-name) js/parseInt)]
    (if (and (number? v) (not (js/isNaN v)) (pos? v)) v default)))

;; ============================================================
;; Sampling bounds — every one overridable by env for token economy.
;; ============================================================

(def default-opts
  {:max-depth    (env-int "SEON_VALUE_MAX_DEPTH" 3)
   :max-keys     (env-int "SEON_VALUE_MAX_KEYS" 8)
   :max-items    (env-int "SEON_VALUE_MAX_ITEMS" 8)
   :max-string   (env-int "SEON_VALUE_MAX_STRING" 80)
   :shape-sample (env-int "SEON_VALUE_SHAPE_SAMPLE" 8)})

(def ^:private verbatim-cap
  "Char budget under which an eval value prints WHOLE (REPL-style) instead of
   being skeletonized — small enough that the agent sees the REAL nesting of
   its own just-stored data, not `{…2 keys}`/`\"…\"`. Grounded at the same
   1500 as `seon.agent.ctx/eval-render-cap` (which can't be required here —
   that ns sits ABOVE this one): a value this size is genuinely small, well
   under the result-body clip. Override via SEON_VALUE_VERBATIM_CAP."
  (env-int "SEON_VALUE_VERBATIM_CAP" 1500))

(def ^:private verbatim-probe-opts
  "Generous bounds used ONLY to test — LAZY-SAFELY — whether `value` is small
   and fully plain before `pr-str`'ing it whole. `sample` realizes at most
   max-items+1 of any seq, so an untruncated probe PROVES the value is finite,
   opaque-free, and bounded: `pr-str` then cannot hang on a lazy/infinite seq
   nor blow up. Bounds far exceed anything a verbatim-cap-sized value reaches,
   so the char count stays the real gate."
  {:max-depth 64 :max-keys 256 :max-items 256
   :max-string verbatim-cap :shape-sample 8})

;; ============================================================
;; Opaque detection — the ONE home for "this node is not plain data,
;; project it to a compact marker." `seon.eval` requires this ns rather
;; than carrying its own copy. Per-node, only on VISITED nodes, so a giant
;; value is never fully walked.
;; ============================================================

(defn- datahike-handle?
  [x]
  (or (and (record? x) (some? (:max-tx x)))
      (and (not (map? x)) (not (record? x)) (not (seqable? x))
           (some? (:db/id x)))))

(defn- datom-shape? [x]
  (and (not (coll? x)) (not (record? x))
       (number? (:e x)) (keyword? (:a x))))

(defn- opaque-node?
  "A node that is NOT plain data — must be projected to a marker before it
   bloats / breaks the skeleton."
  [x]
  (or (datahike-handle? x)
      (record? x)
      (datom-shape? x)
      (object? x)
      (fn? x)))

(defn- opaque-marker
  "Reader-safe marker for one non-plain node. Never throws."
  [x]
  (try
    (cond
      (datom-shape? x)
      {:seon.eval/datom [(:e x) (:a x) (:v x)]}

      (and (record? x) (some? (:max-tx x)))
      {:seon.eval/opaque "datahike/DB"
       :seon.eval/summary (str "max-tx=" (:max-tx x)
                               (when-some [me (:max-eid x)] (str " max-eid=" me)))}

      (and (not (map? x)) (not (record? x)) (some? (:db/id x)))
      {:seon.eval/opaque "datahike/Entity"
       :seon.eval/summary (str ":db/id=" (:db/id x))}

      (record? x)
      {:seon.eval/opaque (or (some-> (type x) pr-str) "record")
       :seon.eval/summary (let [s (pr-str x)] (subs s 0 (min 80 (count s))))}

      (fn? x)
      {:seon.eval/opaque "fn"}

      (object? x)
      {:seon.eval/opaque "js/Object"
       :seon.eval/summary (let [s (pr-str x)] (subs s 0 (min 80 (count s))))}

      :else
      {:seon.eval/opaque "unknown"
       :seon.eval/summary (let [s (str x)] (subs s 0 (min 80 (count s))))})
    (catch :default _
      {:seon.eval/opaque "unknown" :seon.eval/summary "<unprintable>"})))

(declare project-plain)

(defn- project-plain*
  [value]
  (cond
    ;; opaque handles FIRST — a datahike DB is also map?/coll?.
    (opaque-node? value) (opaque-marker value)

    ;; plain map — recurse over keys AND values.
    (map? value)
    (reduce-kv (fn [m k vv] (assoc m (project-plain k) (project-plain vv))) {} value)

    ;; plain vector/set/list/seq — recurse element-wise (lazy/seq → vector
    ;; for a finite, reader-safe shape).
    (coll? value)
    (if (seq? value)
      (mapv project-plain value)
      (into (empty value) (map project-plain) value))

    :else value))

(defn project-plain
  "Recursively project VALUE into reader-safe PLAIN DATA: every non-plain
   node (datahike DB/Datom/Entity, record, JS object, fn) becomes a compact
   marker map; plain scalars (incl. #inst/#uuid) and collections survive,
   walked element-wise. Unbounded — the full structure is preserved, only
   opaque nodes are summarized; pair it with `pr-str` for a round-trippable
   string. (`sample` is the BOUNDED variant for agent display.) Never
   throws — a walk failure degrades to the opaque marker for that node."
  {:malli/schema [:=> [:catn [:seon.render.value/value :any]] :any]}
  [value]
  (try (project-plain* value)
       (catch :default _ (opaque-marker value))))

;; ============================================================
;; SAMPLE — depth + breadth bounded skeleton of plain data + markers.
;; ============================================================

(defn- counted-count [coll]
  (when (counted? coll) (count coll)))

(defn- shared-keys
  "Sorted UNION of the keys of the first `shape-sample` items WHEN they are
   all maps — the column set a homogeneous collection of rows shares. nil
   otherwise. Bounded: never realizes past `shape-sample` items."
  [items shape-sample]
  (let [sample (take shape-sample items)]
    (when (and (seq sample) (every? map? sample))
      (->> sample (map (comp set keys)) (reduce into #{}) (sort-by str) vec))))

(defn- clip-string [s max-string]
  (if (> (count s) max-string)
    {:seon.render.value/string-len (count s)
     :seon.render.value/head       (subs s 0 (max 0 (dec max-string)))}
    s))

(declare sample*)

(defn- sample-seqish
  "Breadth + lazy-safe element sampling of a vector/set/seq. Realizes at
   most `max-items`+1 elements to detect overflow without forcing a huge or
   infinite seq. Order preserved."
  [coll {:keys [max-items shape-sample] :as opts} depth kind]
  (let [head+1 (vec (take (inc max-items) coll))
        over?  (> (count head+1) max-items)
        shown  (mapv #(sample* % opts (inc depth)) (take max-items head+1))
        total  (counted-count coll)
        elided (cond (not over?) 0
                     total       (- total max-items)
                     :else       :more)
        shape  (when over? (shared-keys coll shape-sample))]
    (cond-> {:seon.render.value/kind  kind
             :seon.render.value/shown shown}
      (not= elided 0) (assoc :seon.render.value/elided elided)
      shape           (assoc :seon.render.value/shape shape))))

(defn- sample*
  [x {:keys [max-depth max-keys max-string] :as opts} depth]
  (cond
    ;; opaque handles FIRST — a datahike DB is also map?/coll?.
    (opaque-node? x) (opaque-marker x)

    (string? x) (clip-string x max-string)

    ;; depth limit — prune NON-EMPTY nested colls to a typed+counted marker
    ;; so the agent still sees "a map of 12 keys lives here" and can drill
    ;; it. Empty colls are tiny, render verbatim (fall through).
    (and (>= depth max-depth) (coll? x) (seq x))
    {:seon.render.value/pruned (cond (map? x) :map (set? x) :set
                                     (vector? x) :vector :else :seq)
     :seon.render.value/count  (counted-count x)}

    (map? x)
    ;; REPL-faithful: keep the map's NATURAL key order (what `pr-str` shows),
    ;; never re-sort — an agent reading a constructed value back (e.g. an
    ;; `{:seon.db/ok? false …}` envelope) sees the keys as it built them.
    (let [shown-ks (take max-keys (keys x))
          elided   (max 0 (- (count x) max-keys))]
      (cond-> (into {} (map (fn [k] [k (sample* (get x k) opts (inc depth))]))
                    shown-ks)
        (pos? elided) (assoc :seon.render.value/elided-keys elided)))

    (vector? x) (sample-seqish x opts depth :vector)
    (set? x)    (sample-seqish x opts depth :set)
    (coll? x)   (sample-seqish x opts depth :seq)

    :else x))

(defn sample
  "Depth + breadth bounded SKELETON of `x` (plain data + marker maps).
   `opts` overrides `default-opts`. Navigation paths (map keys, vector
   indices) are preserved on every retained node. Lazy-safe; never throws."
  {:malli/schema [:function
                  [:=> [:catn [:seon.render.value/x :any]] :any]
                  [:=> [:catn [:seon.render.value/x :any]
                              [:seon.render.value/opts :map]] :any]]}
  ;; The 1-arity delegates with `{}` (NOT nil): the 2-arity's `opts` slot is
  ;; schema'd `:map`, and under always-on instrumentation a nil there throws
  ;; `:malli.core/invalid-input` on this internal self-call (uninstrumented,
  ;; `merge` tolerated nil — the trap only fires once instrumented at boot).
  ([x] (sample x {}))
  ([x opts] (sample* x (merge default-opts opts) 0)))

;; ============================================================
;; EMIT — render the skeleton to structure-revealing comment text.
;; Markers become compact tokens; scalars via pr-str; collections print
;; INLINE when they fit a width budget, else break one child per line.
;; ============================================================

(def ^:private width
  (env-int "SEON_VALUE_WIDTH" 72))

(defn- leaf-marker? [x]
  (and (map? x)
       (or (:seon.eval/opaque x)
           (:seon.eval/datom x)
           (:seon.render.value/string-len x)
           (:seon.render.value/pruned x))))

(defn- truncated?
  "Any marker present in the skeleton = a partial view (something elided,
   pruned, clipped, or opaque). Drives the trailing drill hint."
  [skel]
  (boolean
    (some #(and (map? %)
                (some #{:seon.render.value/elided :seon.render.value/elided-keys
                        :seon.render.value/pruned :seon.eval/opaque
                        :seon.eval/datom :seon.render.value/string-len}
                      (keys %)))
          (tree-seq coll? #(if (map? %) (vals %) (seq %)) skel))))

(defn- ind [depth] (apply str (repeat depth "  ")))

(defn- emit-leaf [x]
  (cond
    (:seon.eval/datom x)
    (let [[e a v] (:seon.eval/datom x)]
      (str "#datom[" e " " (pr-str a) " " (pr-str v) "]"))

    (:seon.eval/opaque x)
    (str "#‹" (:seon.eval/opaque x)
         (when-some [s (:seon.eval/summary x)] (str " " s)) "›")

    (:seon.render.value/pruned x)
    (let [k (:seon.render.value/pruned x) c (:seon.render.value/count x)
          [o cl] (case k :map ["{" "}"] :set ["#{" "}"] :vector ["[" "]"] ["(" ")"])
          unit   (if (= k :map) "keys" "items")]
      (str o "…" (when c (str c " " unit)) cl))

    (:seon.render.value/string-len x)
    (str (pr-str (str (:seon.render.value/head x) "…"))
         "⟨" (tokens/chars->tokens (:seon.render.value/string-len x)) " tokens⟩")))

(defn- map-parts [m]
  (let [elided (:seon.render.value/elided-keys m)
        m      (dissoc m :seon.render.value/elided-keys)]
    [(map (fn [[k v]] [(pr-str k) v]) m)
     (when elided (str "… +" elided " more keys"))]))

(defn- seqish-parts [m]
  (let [{:seon.render.value/keys [shown elided shape]} m]
    [shown
     (str/join " "
       (remove nil?
         [(cond (nil? elided) nil
                (= :more elided) "… +more"
                :else (str "… +" elided " more"))
          (when shape (str "each {" (str/join " " (map pr-str shape)) "}"))]))]))

(declare emit)

(defn- emit-inline [x]
  (cond
    (leaf-marker? x) (emit-leaf x)
    (:seon.render.value/kind x)
    (let [[open close] (case (:seon.render.value/kind x)
                         :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
          [elems tail] (seqish-parts x)]
      (str open (str/join " " (concat (map emit-inline elems)
                                      (when (seq tail) [tail]))) close))
    (map? x)
    (let [[pairs tail] (map-parts x)]
      (str "{" (str/join ", " (concat (map (fn [[k v]] (str k " " (emit-inline v))) pairs)
                                      (when tail [tail]))) "}"))
    :else (pr-str x)))

(defn- fits? [x depth]
  (let [s (emit-inline x)]
    (and (not (str/includes? s "\n"))
         (<= (+ (count s) (* 2 depth)) width))))

(defn- emit
  "Render skeleton node `x` at `depth`; inline when it fits, else break one
   child per line."
  [x depth]
  (cond
    (leaf-marker? x) (emit-leaf x)
    (fits? x depth)  (emit-inline x)

    (:seon.render.value/kind x)
    (let [[open close] (case (:seon.render.value/kind x)
                         :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
          [elems tail] (seqish-parts x)
          sep (str "\n" (ind (inc depth)))]
      (str open
           (str/join sep (map #(emit % (inc depth)) elems))
           (when (seq tail) (str sep tail))
           close))

    (map? x)
    (let [[pairs tail] (map-parts x)
          sep (str "\n" (ind (inc depth)))]
      (str "{"
           (str/join sep (map (fn [[k v]] (str k " " (emit v (inc depth)))) pairs))
           (when tail (str sep tail))
           "}"))

    :else (pr-str x)))

;; ============================================================
;; RENDER-AI — the agent-facing text. The bounded skeleton FIRST (so it
;; composes cleanly behind the transcript's `;=>` prefix — no `;=> ;;`
;; double-comment), then, ONLY when the view is partial, ONE trailing `;`
;; hint folding the top-level type/count + a drill pointer at the live var.
;; ============================================================

(defn- top-type+size [x]
  (when (and (coll? x) (not (record? x)))
    (let [t (cond (map? x) "map" (vector? x) "vector" (set? x) "set"
                  (seq? x) "seq" :else "coll")
          n (if (counted? x) (count x) (str "≥" (count (take 1001 x))))]
      (str t " " n (if (map? x) " keys" " items")))))

(defn- bounded-view
  "The DEPTH/BREADTH-bounded skeleton + ONE trailing drill hint — the view
   for a value too large/deep/opaque to print whole."
  [eval-id value]
  (let [skel  (sample value)
        clip? (truncated? skel)
        body  (emit skel 0)
        tsz   (top-type+size value)
        hint  (when clip?
                (str "\n; ‹partial view"
                     (when tsz (str " of " tsz)) "› — the COMPLETE value is "
                     "result/" eval-id "  (get-in result/" eval-id
                     " […]) · filter · count · take/drop"))]
    (str body hint)))

(defn render-ai
  "Agent-facing TEXT for an eval value. `eval-id` names the live var the
   agent drills. A small value renders VERBATIM (like a REPL — full nesting,
   so the agent navigates its own stored data correctly next turn); a large /
   deep / opaque value renders as a bounded structural skeleton + ONE trailing
   hint line (top-level type/count + a drill pointer at `result/<id>`). The
   whole output is valid Clojure comment prose — no backticks, no fences — so
   it round-trips through the eval-able context.

   The SIZE GATE: a lazy-safe `verbatim-probe-opts` sample first proves the
   value is finite + plain (never hangs on a lazy/infinite seq); only then is
   `pr-str` safe, and a value whose printed form fits `verbatim-cap` prints
   whole. Everything else falls to `bounded-view`."
  {:malli/schema [:=> [:catn [:seon.render.value/eval-id :string]
                             [:seon.render.value/value :any]]
                  :string]}
  [eval-id value]
  (let [probe (sample value verbatim-probe-opts)]
    (if (truncated? probe)
      (bounded-view eval-id value)
      ;; probe untruncated ⇒ value is finite, opaque-free, bounded ⇒ pr-str
      ;; cannot hang. Print it WHOLE when it fits the char budget.
      (let [edn (pr-str value)]
        (if (<= (count edn) verbatim-cap)
          edn
          (bounded-view eval-id value))))))

;; ============================================================
;; RENDER-HTML-DATA — the DATA CONTRACT for the interactive drill-down
;; panel (UI session U's lane). PLAIN DATA only — no hiccup, no web
;; classes. U turns this into a collapsible browser.
;;
;; The tree IS the `sample` skeleton: every node carries its real key /
;; index, so the panel reconstructs a `get-in` PATH from a node's position
;; and EXPANDS a pruned/elided node by re-sampling `(get-in result/<id>
;; path)` one level deeper (the live value is `result/<id>`; expansion is a
;; fresh server `/call` — see the U coordination ask in the PRD note).
;; ============================================================

(defn render-html-data
  "DATA CONTRACT the interactive HTML value-browser consumes. Returns:

     {:seon.render.value/eval-id    <id-string>     ; live-var handle
      :seon.render.value/summary    <\"map 12 keys\">  ; one-line header
      :seon.render.value/truncated? <bool>          ; is this a partial view
      :seon.render.value/tree       <sample skeleton>}

   The `:tree` is the same plain-data skeleton `render-ai` emits — the
   panel renders each marker as a collapsible affordance and requests
   deeper slices by path. No hiccup here: styling + interactivity are U's."
  {:malli/schema [:=> [:catn [:seon.render.value/eval-id :string]
                             [:seon.render.value/value :any]]
                  :map]}
  [eval-id value]
  (let [skel (sample value)]
    {:seon.render.value/eval-id    eval-id
     :seon.render.value/summary    (or (top-type+size value)
                                       (some-> (:seon.eval/opaque skel))
                                       "scalar")
     :seon.render.value/truncated? (truncated? skel)
     :seon.render.value/tree       skel}))

;; ============================================================
;; Live integration:
;;
;; `seon.eval/render-result-edn` (the producer of `:seon.eval/result-edn`,
;; the AI text) is `(render-ai eval-id value)` + a final char-cap backstop.
;; `seon.eval/sanitize-result-edn` (the read-side net for legacy rows)
;; reuses `project-plain`. The opaque-DETECTION + projection logic lives
;; ONLY here; `seon.eval` requires this ns — a one-way edge (eval →
;; render.value), no cycle. The `result/<id>` handle on the `;=>` line is
;; still added downstream by `seon.agent.ctx/format-eval-row` (untouched).
;; ============================================================
