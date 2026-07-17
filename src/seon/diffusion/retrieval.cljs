(ns seon.diffusion.retrieval
  "RETRIEVAL leg of the diffusion buzzsaw — the third control signal beside
   parse (`seon.worker-validator`) and eval (`seon.worker-eval`).

   ## What it answers

   Parse asks \"is the code-buffer well-formed?\"; eval asks \"does it RUN?\".
   Retrieval asks the question only the PROGRAM GRAPH can answer: \"does this
   committed symbol NAME a real fn — and if not, what real fn did the model
   MEAN?\". This is the AUROC-0.471 class (see
   `docs/prds/diffusion-dynamic-context/research/retrieval-denoising-experiment-plan-2026-06-28.md`):
   a confidently-wrong name (`transct!`, `db/store!`) that parses and may even
   run, but does not exist. Commit-entropy is blind to it; the graph is not.

   ## The three steps (this ns is the SEON-side oracle, NO GPU)

   1. DETECT — `unresolved-references`: extract the code-buffer's free symbol
      references (call-position + every used var, minus locals/specials/core),
      char-span each, and keep the ones that do NOT resolve in the program
      graph. This is the offline, graph-membership equivalent of the analyzer's
      `:undeclared-var` warning that `seon.worker-eval` collects on the GPU.
   2. RETRIEVE — `retrieve-candidates`: for an unresolved name, return the
      nearest REAL `:seon.fn` candidates. The GRAPH path (always on): exact
      name match + near-name (Levenshtein) over `:seon.fn/sym`. The SEMANTIC
      enhancement (`retrieve-for-code-buffer+semantic`, gated by `SEON_EMBED`):
      `seon.embed/search-pull` KNN over the Proximum/Vertex fn index, fail-soft.
   3. EMIT — `build-injection` / `to-wire`: the INJECTION descriptor the GPU
      worker's clamp/infill surface consumes — the real symbol + its
      signature/spec + the char-span to steer, in the worker's `{op,…}` shape.
      The output is a CONTROL-SIGNAL payload, not a code edit.

   ## Worker integration (mid-denoise, once on GPU)

   The descriptor is char-span based; the worker maps `::span` → code-buffer token
   positions via its `offset_map` (the L linchpin), then (op `:clamp`) forces
   those positions to `::replacement` while appending `::spec-text` to the
   encoder KV so the decoder cross-attends the real signature for the next
   denoise steps — re-committing the symbol toward a name that EXISTS. The
   round-trip (Seon drives, worker stateless) keeps the pod loopback-only.

   Readers capture one ordinary database value and acquire the compact function
   corpus once. No writes."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.diffusion.grammar :as grammar]
    [seon.embed :as embed]
    [seon.repl.internal :as internal]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas
;; ============================================================

(schema/register! ::code-buffer-text :string)
;; alias-string → ns-name-string, e.g. {"db" "seon.db"} — from the code-buffer's
;; own `(ns … (:require [seon.db :as db]))` and/or a caller-supplied override.
(schema/register! ::aliases [:map-of :string :string])
;; absolute char offsets [start end) into the code-buffer string.
(schema/register! ::span [:tuple :int :int])
(schema/register! ::match-kind [:enum :exact :near-name :semantic])
(schema/register! ::k [:int {:min 1}])

;; A free symbol reference detected in the code-buffer.
(schema/register!
  ::symbol-ref
  [:map
   [::symbol :string]                              ; the token as written
   [::name :string]                                ; the name part (after `/`)
   [::qualifier {:optional true} :string]          ; the ns/alias part (before `/`)
   [::span ::span]
   [::call-position? :boolean]])

;; A retrieved real-fn candidate for an unresolved name.
(schema/register!
  ::candidate
  [:map
   [::sym :string]                                 ; FQ "<ns>/<name>" — :seon.fn/sym
   [::name :string]                                ; the name part
   [::match-kind ::match-kind]
   [::distance :int]                               ; edit distance to the bad name (0 = exact/semantic)
   [::spec-text :string]                           ; compact injectable signature+doc+spec
   [::arglists {:optional true} :string]
   [::doc {:optional true} :string]
   [::spec {:optional true} :string]])

(schema/register! ::op [:enum :clamp :infill])

;; The injection descriptor — the worker's control-signal payload.
(schema/register!
  ::injection
  [:map
   [::op ::op]
   [::unresolved :string]                          ; the hallucinated symbol
   [::span ::span]                                 ; where to steer (char offsets)
   [::replacement :string]                         ; the corrected symbol for the span
   [::spec-text :string]                           ; encoder-KV content (real signature)
   [::candidates [:vector ::candidate]]])

(schema/register!
  ::retrieval-result
  [:map
   [::unresolved [:vector ::symbol-ref]]
   [::injections [:vector ::injection]]])

(schema/register!
  ::detect-request
  [:map
   [::code-buffer-text ::code-buffer-text]
   [::aliases {:optional true} ::aliases]
   [::db {:optional true} :seon.db/db]])

(schema/register!
  ::retrieve-candidates-request
  [:map
   [::name :string]
   [::qualifier {:optional true} :string]
   [::aliases {:optional true} ::aliases]
   [::k {:optional true} ::k]
   [::db {:optional true} :seon.db/db]])

(schema/register!
  ::retrieve-request
  [:map
   [::code-buffer-text ::code-buffer-text]
   [::aliases {:optional true} ::aliases]
   [::k {:optional true} ::k]
   [::db {:optional true} :seon.db/db]])

;; ============================================================
;; Known-symbol surfaces (the offline approximation of the analyzer's
;; "is this declared?" — special forms + a common core allow-list).
;; A symbol that is special/core/local can never be an unresolved-name
;; FLAG, so it is excluded BEFORE the graph membership check.
;; ============================================================

(def ^:private special-forms
  "Structural heads the walker dispatches on AND treats as resolved."
  '#{def defn defn- defmacro defmethod defmulti defprotocol deftype defrecord
     defonce fn fn* let let* letfn loop loop* recur do if when when-not
     when-let when-some if-let if-some if-not cond condp case and or
     -> ->> as-> some-> some->> cond-> cond->> doto for doseq dotimes while
     quote var set! new throw try catch finally
     binding with-redefs with-open with-local-vars locking when-first
     ns declare comment lazy-seq delay reify proxy
     extend-type extend-protocol extend
     deftest is testing async are use-fixtures})

(def ^:private core-symbols
  "Common clojure/cljs.core fns + macros used in agent code-buffers. Not
   exhaustive — a miss here only triggers a retrieval that finds no near
   candidate (fail-soft, no injection), never a wrong correction."
  '#{+ - * / inc dec quot rem mod min max abs = == not= < > <= >= not
     count nth get get-in assoc assoc-in update update-in dissoc conj cons
     first second rest next last butlast take drop take-while drop-while
     take-nth nthrest nthnext take-last drop-last
     map mapv filter filterv remove reduce reduce-kv reductions keep
     keep-indexed map-indexed mapcat into vec set list list* hash-map hash-set
     sorted-map sorted-set zipmap range repeat repeatedly iterate cycle
     interleave interpose partition partition-all partition-by split-at
     split-with sort sort-by group-by frequencies distinct dedupe shuffle
     reverse concat flatten run! doall dorun
     str name namespace keyword symbol gensym subs subvec format
     key val keys vals find merge merge-with select-keys rename-keys
     update-vals update-keys
     apply comp partial juxt complement constantly identity fnil memoize
     trampoline nil? some? true? false? boolean? int? integer? number?
     string? keyword? symbol? map? vector? set? list? seq? coll? sequential?
     fn? ifn? empty? not-empty seq contains? every? not-every? some not-any?
     pos? neg? zero? even? odd? pos-int? nat-int? max-key min-key
     println print prn pr pr-str print-str
     atom deref reset! swap! reset-vals! swap-vals! compare-and-set!
     volatile! vreset! vswap! add-watch remove-watch
     ex-info ex-data ex-message ex-cause assert
     re-find re-matches re-seq re-pattern re-matcher
     boolean long double int char bit-and bit-or bit-xor
     type instance? satisfies? meta with-meta vary-meta})

(def ^:private resolved-names
  "Union of special forms + core — the bare-name allow-list."
  (into special-forms core-symbols))

(def ^:private core-namespaces
  "Qualifier strings whose membership we do NOT verify (treat any name under
   them as resolved): clojure/cljs core, the std libs, and JS interop roots."
  #{"clojure.core" "cljs.core" "clojure.string" "clojure.set" "clojure.walk"
    "clojure.edn" "clojure.zip" "clojure.data" "goog" "goog.object"
    "goog.string" "js" "Math" "JSON" "Object" "Array"})

;; ============================================================
;; Symbol-reference extraction (PURE — structural)
;; ============================================================

(defn- collect-all-syms
  "Every symbol anywhere in `form` (used to harvest destructuring targets)."
  [form]
  (cond
    (symbol? form) #{form}
    (map? form)    (reduce into #{} (map collect-all-syms (concat (keys form) (vals form))))
    (coll? form)   (reduce into #{} (map collect-all-syms form))
    :else          #{}))

(declare walk!)

(defn- walk-children!
  "Walk every child of a non-head collection (none in call position)."
  [acc form]
  (cond
    (map? form)  (doseq [[k v] form] (walk! acc k false) (walk! acc v false))
    (coll? form) (doseq [x form] (walk! acc x false))
    :else        nil))

(defn- bind-targets!
  "Add the symbols of a binding TARGET form (handles destructuring) to :bound."
  [acc target]
  (swap! acc update :bound into (collect-all-syms target)))

(defn- walk-pair-binding!
  "let / loop / binding / when-let / … : a `[t init t init …]` vector then a
   body. Targets bind; inits + body are walked normally."
  [acc form]
  (let [bvec (second form)
        body (drop 2 form)]
    (when (vector? bvec)
      (doseq [[t init] (partition 2 bvec)]
        (bind-targets! acc t)
        (walk! acc init false)))
    (doseq [b body] (walk! acc b false))))

(defn- walk-arities!
  "Walk a fn/defn tail that is EITHER a single `[args] body…` or a set of
   `([args] body…)` arities. Arg-vector symbols bind."
  [acc tail]
  (if (vector? (first tail))
    (do (bind-targets! acc (first tail))
        (doseq [b (rest tail)] (walk! acc b false)))
    (doseq [arity tail :when (seq? arity)]
      (bind-targets! acc (first arity))
      (doseq [b (rest arity)] (walk! acc b false)))))

(defn- walk-fn!
  "fn / fn* — optional name, then arities."
  [acc form]
  (let [tail (rest form)
        tail (if (symbol? (first tail))
               (do (swap! acc update :bound conj (first tail)) (rest tail))
               tail)]
    (walk-arities! acc tail)))

(defn- walk-defn!
  "defn / defn- / defmacro — name, optional docstring/attr-map, then arities."
  [acc form]
  (let [tail (rest form)]
    (when (symbol? (first tail)) (swap! acc update :bound conj (first tail)))
    (walk-arities! acc (drop-while #(or (string? %) (map? %)) (rest tail)))))

(defn- walk-def!
  "def / defonce — name binds; the rest are walked."
  [acc form]
  (let [tail (rest form)]
    (when (symbol? (first tail)) (swap! acc update :bound conj (first tail)))
    (doseq [b (rest tail)] (walk! acc b false))))

(defn- walk-seq-binding!
  "for / doseq — a binding vector with `:let`/`:when`/`:while` modifiers."
  [acc form]
  (let [bvec (second form)
        body (drop 2 form)]
    (when (vector? bvec)
      (doseq [[t v] (partition 2 bvec)]
        (cond
          (= :let t)   (doseq [[tt ii] (partition 2 v)]
                         (bind-targets! acc tt) (walk! acc ii false))
          (keyword? t) (walk! acc v false)
          :else        (do (bind-targets! acc t) (walk! acc v false)))))
    (doseq [b body] (walk! acc b false))))

(defn- walk-seq!
  [acc form]
  (let [h (first form)]
    (cond
      (empty? form)                          nil
      (= 'quote h)                           nil                       ; quoted data
      (= 'ns h)                              nil                       ; ns decl — no refs
      ('#{require import use refer-clojure} h) nil                     ; load decls
      ('#{let let* loop loop* binding when-let when-some if-let if-some
          when-first with-open with-local-vars letfn} h)
      (walk-pair-binding! acc form)
      ('#{fn fn*} h)                         (walk-fn! acc form)
      ('#{defn defn- defmacro} h)            (walk-defn! acc form)
      ('#{def defonce} h)                    (walk-def! acc form)
      ('#{for doseq} h)                      (walk-seq-binding! acc form)
      :else
      (do
        ;; head is a candidate in CALL position (filtered later if core/special)
        (when (symbol? h)
          (swap! acc update :candidates conj {:sym h :head? true}))
        (doseq [x (rest form)] (walk! acc x false))))))

(defn- walk!
  "Accumulate {:candidates [{:sym :head?}] :bound #{}} into `acc` (an atom)."
  [acc form head?]
  (cond
    (symbol? form) (when-not (= '& form)
                     (swap! acc update :candidates conj {:sym form :head? head?}))
    (seq? form)    (walk-seq! acc form)
    (coll? form)   (walk-children! acc form)
    :else          nil))

(defn- code-buffer-aliases
  "Extract `{alias-string → ns-string}` from the code-buffer's `(ns …)` form's
   `:require` specs (`[ns :as alias]`)."
  [forms]
  (reduce
    (fn [m form]
      (if (and (seq? form) (= 'ns (first form)))
        (reduce
          (fn [m clause]
            (if (and (seq? clause) (= :require (first clause)))
              (reduce
                (fn [m spec]
                  (if (and (vector? spec) (symbol? (first spec)))
                    ;; CLJS-safe `:as` lookup — `(.indexOf (to-array …) :as)`
                    ;; uses JS `===`, which never matches a CLJS keyword VALUE.
                    (let [as-i (->> (map-indexed vector spec)
                                    (some (fn [[i x]] (when (= :as x) i))))]
                      (if (and as-i (< (inc as-i) (count spec)))
                        (assoc m (str (nth spec (inc as-i))) (str (first spec)))
                        m))
                    m))
                m (rest clause))
              m))
          m (rest form))
        m))
    {} forms))

(defn- boundary?
  "True iff `c` (a 1-char string or nil) is a token boundary."
  [c]
  (or (nil? c) (boolean (re-find #"[\s(){}\[\]\"';@^~`,]" c))))

(defn- find-symbol-span
  "First absolute `[start end)` span of token `needle` in `code-buffer` bounded by
   non-constituent chars (so `db` does not match inside `db/x`, and `:db/x`
   keyword does not match `db/x`). nil if absent."
  [code-buffer needle]
  (let [n (count needle) clen (count code-buffer)]
    (loop [from 0]
      (let [i (.indexOf code-buffer needle from)]
        (when (>= i 0)
          (let [before (when (pos? i) (subs code-buffer (dec i) i))
                after-i (+ i n)
                after   (when (< after-i clen) (subs code-buffer after-i (inc after-i)))]
            (if (and (boundary? before) (boundary? after))
              [i after-i]
              (recur (inc i)))))))))

(defn- ref-resolved-by-name?
  "A bare/qualified candidate symbol is structurally resolved (NOT a flag)
   when it is a special form / core symbol / interop, OR (qualified) sits
   under a core namespace after alias expansion."
  [sym aliases bound]
  (let [s  (str sym)
        nm (symbol (if-let [i (str/index-of s "/")] (subs s (inc i)) s))
        q  (when-let [i (str/index-of s "/")] (subs s 0 i))]
    (boolean
      (or (contains? bound sym)
          (contains? bound nm)
          (contains? resolved-names sym)
          (contains? resolved-names nm)
          (str/starts-with? s ".")                    ; (.method o) interop
          (and (> (count s) 1) (str/ends-with? s ".")) ; (Ctor.) interop
          (when q
            (let [expanded (get aliases q q)]
              (contains? core-namespaces expanded)))))))

(defn free-references
  "Every FREE symbol reference in the code-buffer — a PURE structural pass.

   Call
   position and value position — minus locals, special forms, core, and
   interop. These are the symbols that MUST resolve to an external var; the
   ones that do not exist in the program graph are the retrieval targets
   (`unresolved-references`). Each carries its first char `::span`."
  {:malli/schema [:=> [:cat [:map [::code-buffer-text ::code-buffer-text]
                                  [::aliases {:optional true} ::aliases]]]
                  [:vector ::symbol-ref]]}
  [{::keys [code-buffer-text aliases]}]
  (let [entries (internal/parse-forms code-buffer-text {:strip-fences? false})
        forms   (->> entries (filter #(= :form (:seon.repl/kind %))) (map :seon.repl/form))
        aliases (merge (code-buffer-aliases forms) (or aliases {}))
        acc     (atom {:candidates [] :bound #{}})]
    (doseq [f forms] (walk! acc f false))
    (let [{:keys [candidates bound]} @acc
          ;; dedupe by symbol, OR-ing call-position?
          by-sym (reduce (fn [m {:keys [sym head?]}]
                           (assoc m sym (or (get m sym) head?)))
                         {} candidates)]
      (->> by-sym
           (keep (fn [[sym head?]]
                   (when-not (ref-resolved-by-name? sym aliases bound)
                     (when-let [span (find-symbol-span code-buffer-text (str sym))]
                       (let [s  (str sym)
                             qi (str/index-of s "/")]
                         (cond-> {::symbol s
                                  ::name (if qi (subs s (inc qi)) s)
                                  ::span span
                                  ::call-position? (boolean head?)}
                           qi (assoc ::qualifier (subs s 0 qi))))))))
           (sort-by (comp first ::span))
           vec))))

;; ============================================================
;; Program-graph membership + near-name retrieval (GRAPH path — always on)
;; ============================================================

(def ^:private function-corpus-query
  '[:find [(pull ?function
                 [:seon.fn/sym :seon.fn/arglists :seon.fn/doc :seon.fn/spec]) ...]
    :where [?function :seon.fn/sym]])

(defn- ^:async function-corpus
  "Acquire compact function facts once at one immutable database value."
  [database]
  (await (db/query {::db/db database ::db/query function-corpus-query})))

(defn- ^:async selected-db [database]
  (or database (await (db/db))))

(defn- name-of [fq]
  (let [i (.lastIndexOf fq "/")] (if (>= i 0) (subs fq (inc i)) fq)))

(defn- ns-of [fq]
  (let [i (.lastIndexOf fq "/")] (when (>= i 0) (subs fq 0 i))))

;; The near-name distance fn is the SHARED `seon.diffusion.grammar/levenshtein`
;; (one mechanism — the worker's `op:"repair"` candidate sweep uses the same fn).

(defn- symbol-resolves-in?
  [functions name qualifier aliases]
  (let [expanded (when qualifier (get aliases qualifier qualifier))]
    (boolean
     (some (fn [{:seon.fn/keys [sym]}]
             (and (= name (name-of sym))
                  (or (nil? expanded) (= expanded (ns-of sym)))))
           functions))))

(defn ^:async symbol-resolves?
  "True iff `::name` names a real program-graph fn.

   Accepts an optional alias-expanded `::qualifier`. A FALSE on a committed symbol is the retrieval signal —
   the confidently-wrong-name class entropy cannot see."
  {:malli/schema [:=> [:cat [:map [::name :string]
                                  [::qualifier {:optional true} :string]
                                  [::aliases {:optional true} ::aliases]
                                  [::db {:optional true} :seon.db/db]]]
                  :boolean]}
  [{::keys [name qualifier aliases db]}]
  (let [database (await (selected-db db))
        functions (await (function-corpus database))]
    (symbol-resolves-in? functions name qualifier aliases)))

(defn- candidate
  "Build one candidate from an already acquired function entity."
  [{:seon.fn/keys [sym arglists doc spec]} match-kind distance]
  (let [
        doc1 (when (seq doc) (first (str/split-lines doc)))
        spec-text (str sym
                       (when (seq arglists) (str " " arglists))
                       (when (seq doc1) (str "\n; " doc1))
                       (when (seq spec) (str "\n" spec)))]
    (cond-> {::sym sym
             ::name (name-of sym)
             ::match-kind match-kind
             ::distance distance
             ::spec-text spec-text}
      (seq arglists) (assoc ::arglists arglists)
      (seq doc)      (assoc ::doc doc)
      (seq spec)     (assoc ::spec spec))))

(defn- retrieve-candidates-in
  [functions name qualifier aliases k]
  (let [k        (or k 5)
        expanded (when qualifier (get aliases qualifier qualifier))
        thresh   (max 2 (js/Math.ceil (/ (count name) 2)))
        scored   (->> functions
                      (keep (fn [{:seon.fn/keys [sym] :as function}]
                              (let [nm (name-of sym)
                                    d  (grammar/levenshtein name nm)]
                                (cond
                                  (= name nm)
                                  {::function function ::match-kind :exact
                                   ::distance 0}
                                  (or (<= d thresh)
                                      (str/includes? nm name)
                                      (str/includes? name nm))
                                  {::function function ::match-kind :near-name
                                   ::distance (min d (inc thresh))}
                                  :else nil))))
                      (sort-by (juxt #(if (= :exact (::match-kind %)) 0 1)
                                     #(if (and expanded
                                               (= expanded
                                                  (ns-of (get-in % [::function
                                                                    :seon.fn/sym]))))
                                        0 1)
                                     ::distance
                                     #(count (name-of (get-in % [::function
                                                                  :seon.fn/sym])))))
                      (take k))]
    (mapv #(candidate (::function %) (::match-kind %) (::distance %)) scored)))

(defn ^:async retrieve-candidates
  "GRAPH-path retrieval of candidate fns for one unresolved name.

   Exact name match + near-name
   (Levenshtein ≤ threshold) over `:seon.fn/sym`, ranked. When `::qualifier`
   resolves (via `::aliases`) to a real ns, same-ns candidates sort first.
   Returns up to `::k` (default 5) candidate maps."
  {:malli/schema [:=> [:cat ::retrieve-candidates-request] [:vector ::candidate]]}
  [{::keys [name qualifier aliases k db]}]
  (let [database (await (selected-db db))
        functions (await (function-corpus database))]
    (retrieve-candidates-in functions name qualifier aliases k)))

;; ============================================================
;; Detection + injection descriptor
;; ============================================================

(defn- unresolved-references-in
  [functions code-buffer-text aliases]
  (let [entries (internal/parse-forms code-buffer-text {:strip-fences? false})
        forms   (->> entries (filter #(= :form (:seon.repl/kind %)))
                     (map :seon.repl/form))
        aliases (merge (code-buffer-aliases forms) (or aliases {}))
        refs    (free-references {::code-buffer-text code-buffer-text
                                  ::aliases aliases})]
    (vec (remove (fn [{::keys [name qualifier]}]
                   (symbol-resolves-in? functions name qualifier aliases))
                 refs))))

(defn ^:async unresolved-references
  "DETECT step: the `free-references` that do NOT resolve in the graph.

   The program-graph misses — the hallucinated / dead-name symbols. Each is a retrieval target."
  {:malli/schema [:=> [:cat ::detect-request] [:vector ::symbol-ref]]}
  [{::keys [code-buffer-text aliases db]}]
  (let [database (await (selected-db db))
        functions (await (function-corpus database))]
    (unresolved-references-in functions code-buffer-text aliases)))

(defn build-injection
  "EMIT step: turn one unresolved `::ref` into an injection descriptor.

   Combines `::ref` with its ranked `::candidates` into the
   worker's `{op,…}` injection descriptor. The best candidate drives
   `::replacement` (qualifier preserved when present) and `::spec-text` (the
   encoder-KV content). Returns nil when there is no candidate to steer toward."
  {:malli/schema [:=> [:cat [:map [::ref ::symbol-ref]
                                  [::candidates [:vector ::candidate]]]]
                  [:maybe ::injection]]}
  [{::keys [ref candidates]}]
  (when-let [best (first candidates)]
    (let [q (::qualifier ref)]
      {::op          :clamp
       ::unresolved  (::symbol ref)
       ::span        (::span ref)
       ::replacement (if q (str q "/" (::name best)) (::name best))
       ::spec-text   (::spec-text best)
       ::candidates  candidates})))

(defn- retrieve-for-code-buffer-in
  [functions code-buffer-text aliases k]
  (let [entries (internal/parse-forms code-buffer-text {:strip-fences? false})
        forms   (->> entries (filter #(= :form (:seon.repl/kind %)))
                     (map :seon.repl/form))
        aliases (merge (code-buffer-aliases forms) (or aliases {}))
        unres   (unresolved-references-in functions code-buffer-text aliases)]
    {::unresolved unres
     ::injections
     (vec (keep (fn [ref]
                  (let [cands (retrieve-candidates-in
                               functions (::name ref) (::qualifier ref) aliases
                               (or k 5))]
                    (build-injection {::ref ref ::candidates cands})))
                unres))}))

(defn ^:async retrieve-for-code-buffer
  "THE graph-path entry: detect, retrieve, and emit injections for a code-buffer.

   Detects unresolved symbols in `::code-buffer-text`, retrieves
   real candidates for each, and emits injection descriptors. PURE reader over
   the db — no GPU, no embeddings. Returns `{::unresolved [...] ::injections
   [...]}`."
  {:malli/schema [:=> [:cat ::retrieve-request] ::retrieval-result]}
  [{::keys [code-buffer-text aliases k db]}]
  (let [database (await (selected-db db))
        functions (await (function-corpus database))]
    (retrieve-for-code-buffer-in functions code-buffer-text aliases k)))

;; ============================================================
;; Semantic enhancement (SEON_EMBED) + wire boundary
;; ============================================================

(def ^:private fn-scope
  "Type-scope KNN to program-graph FUNCTIONS only (attribute presence — the
   attribute IS the kind; no :seon/kind)."
  '[[?e :seon.fn/source]])

(defn- span-context
  "A window of code-buffer text around `[s e)` — the embedder reads the surrounding
   FORM's intent, not just the wrong token."
  [code-buffer [s e]]
  (let [pad 120]
    (subs code-buffer (max 0 (- s pad)) (min (count code-buffer) (+ e pad)))))

(defn- semantic-candidate
  "Build a `:semantic` candidate map from a search-pull hit entity."
  [e]
  (let [fq   (:seon.fn/sym e)
        doc1 (when (seq (:seon.fn/doc e)) (first (str/split-lines (:seon.fn/doc e))))]
    (cond-> {::sym fq ::name (name-of fq) ::match-kind :semantic ::distance 0
             ::spec-text (str fq
                              (when (seq (:seon.fn/arglists e)) (str " " (:seon.fn/arglists e)))
                              (when (seq doc1) (str "\n; " doc1)))}
      (seq (:seon.fn/arglists e)) (assoc ::arglists (:seon.fn/arglists e))
      (seq (:seon.fn/doc e))      (assoc ::doc (:seon.fn/doc e))
      (seq (:seon.fn/spec e))     (assoc ::spec (:seon.fn/spec e)))))

(defn ^:async retrieve-for-code-buffer+semantic
  "ENHANCEMENT over [[retrieve-for-code-buffer]] with semantic neighbours.

   When `SEON_EMBED` is on, augments
   each injection's `::candidates` with semantic neighbours from the Proximum
   fn index (`seon.embed/search-pull`, span-context query, fn-scope). Fail-soft
   — embed off or any wire error returns the graph-only result unchanged."
  {:malli/schema [:=> [:cat ::retrieve-request] :any]}
  [{::keys [code-buffer-text] :as req}]
  (let [base (await (retrieve-for-code-buffer req))]
    (if-not (embed/enabled?)
      base
      (try
        (let [augmented
              (loop [out [] injs (::injections base)]
                (if-let [inj (first injs)]
                  (let [{:seon.embed/keys [hits]}
                        (await (embed/search-pull
                                 {:seon.embed/query (span-context code-buffer-text (::span inj))
                                  :seon.embed/k 5
                                  :seon.embed/where fn-scope
                                  :seon.embed/pull-pattern
                                  '[:seon.fn/sym :seon.fn/arglists :seon.fn/doc :seon.fn/spec]}))
                        have (set (map ::sym (::candidates inj)))
                        sem  (->> hits
                                  (keep (fn [{e :seon.embed/entity}]
                                          (let [fq (:seon.fn/sym e)]
                                            (when (and fq (not (have fq)))
                                              (semantic-candidate e)))))
                                  vec)]
                    (recur (conj out (update inj ::candidates into sem)) (rest injs)))
                  out))]
          (assoc base ::injections augmented))
        (catch :default _ base)))))

(defn to-wire
  "Flatten an `::injection` to the worker's JSON-ready `{op,…}` object.

   Fields: `op`,
   `span` (a JS array of char offsets the worker maps to token positions via
   its offset_map), `replacement`, and `spec_text` (encoder-KV content)."
  {:malli/schema [:=> [:cat [:map [::injection ::injection]]] :any]}
  [{::keys [injection]}]
  #js {:op          (name (::op injection))
       :span        (clj->js (::span injection))
       :unresolved  (::unresolved injection)
       :replacement (::replacement injection)
       :spec_text   (::spec-text injection)})
