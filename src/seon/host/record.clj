(ns seon.host.record
  "Pure builders for the corpus rows host-tier evals persist.

   Host-tier turns record through the ONE program corpus: the same
   `:seon.eval` receipt rows, `:seon.agent.turn/evals` connections,
   `:seon.fn`/`:seon.ns`/`:seon.schema` rows, `:seon.fn/read-attrs`
   ops, and `:seon.ns/require-edges` rows the Bun child's detect-and-tee
   writes today. The child's owners (`seon.eval/internal` start/terminal
   tx data, `seon.eval` build-tee-entities, fn-read-attrs-tx,
   schema-tee-row, omitted-fn-projection-retractions, and the strict
   single-defn persistence gate) remain authoritative for the Bun tier;
   these builders produce the SAME DATA from a sci context's facts (the
   read form plus the returned sci var's metadata) instead of the
   self-host analyzer's snapshot diff.

   Everything here is pure: forms and row maps in, transaction data out.
   `seon.host.context` owns the writer round-trips that commit them."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as tools.reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [malli.core :as m]
            [seon.ai.tokens :as tokens]
            [seon.content-hash :as content-hash]
            [seon.schema :as schema]
            [seon.schema.internal :as schema.internal]))

(set! *warn-on-reflection* true)

(schema/register! ::source :string)
(schema/register! ::ns-sym :symbol)
(schema/register! ::aliases [:map-of :symbol :symbol])
(schema/register! ::forms [:vector :any])

;;; ---------------------------------------------------------------------------
;;; Reading — one whole-source structural read, fail-closed
;;; ---------------------------------------------------------------------------

(defn read-forms
  "All top-level forms of `source`; empty on any read failure.

   `::kw` resolves against `ns-sym` and `::alias/kw` against `aliases`
   (the same resolved-attr rule as the child's C37 read); an
   unresolvable alias fails the read, and an empty read means every
   persistence gate fails closed — no row is created."
  {:malli/schema [:=> [:cat [:map [::source ::source]
                             [::ns-sym {:optional true} ::ns-sym]
                             [::aliases {:optional true} ::aliases]]]
                  ::forms]}
  [{::keys [source ns-sym aliases]}]
  (try
    (binding [*ns* (create-ns (or ns-sym 'user))
              tools.reader/*alias-map* (or aliases {})]
      (let [reader (reader-types/source-logging-push-back-reader source)]
        (loop [forms []]
          (let [form (tools.reader/read {:eof ::eof :read-cond :preserve}
                                        reader)]
            (if (= ::eof form)
              forms
              (recur (conj forms form)))))))
    (catch Throwable _ [])))

(defn read-ns-form
  "The leading namespace declaration in `source`, or nil.

   Reads only the first form, before aliases declared by that form exist. The
   host loader then derives those aliases through [[ns-require-edges]] and
   passes them back to [[read-forms]] for the one whole-source read."
  ;; A tools.reader form is the genuinely polymorphic third-party boundary.
  {:malli/schema [:=> [:cat ::source] :any]}
  [source]
  (try
    (binding [*ns* (create-ns 'user)]
      (let [reader (reader-types/source-logging-push-back-reader source)
            form (tools.reader/read {:eof nil :read-cond :preserve} reader)]
        (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
          form)))
    (catch Throwable _ nil)))

(defn read-host-form
  "Read one recorded form for the JVM feature set, or nil on failure.

   Reader conditionals stay in the persisted source; this projection gives
   SCI exactly the portable `:clj` branch through the same tools.reader
   mechanism that parsed the block."
  {:malli/schema [:=> [:cat ::source] :any]}
  [source]
  (try
    (tools.reader/read-string {:read-cond :allow :features #{:clj}} source)
    (catch Throwable _ nil)))

(defn- single-defn?
  "True iff `forms` is exactly one top-level `defn`/`defn-` form.

   The strict persistence gate: only a literal single defn mints a
   `:seon.fn` row; everything else runs as scratch and is never teed."
  [forms]
  (boolean (and (= 1 (count forms))
                (seq? (first forms))
                (contains? '#{defn defn-} (first (first forms))))))

(defn- ns-declaration
  "The declared ns symbol when `forms` is led by an `(ns NAME …)` form."
  [forms]
  (let [form (first forms)]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form))))

;;; ---------------------------------------------------------------------------
;;; :seon.fn rows — the single-defn projection
;;; ---------------------------------------------------------------------------

(defn- defn-parts
  "Positional pieces of one read defn form: name, doc, attr-map."
  [form]
  (let [[_head fn-name & tail] form
        [doc tail] (if (string? (first tail))
                     [(first tail) (rest tail)]
                     [nil tail])
        attr-map (when (map? (first tail)) (first tail))]
    {::fn-name fn-name ::doc doc ::attr-map attr-map}))

(defn- fn-row
  "The canonical `:seon.fn` row for one successful single-defn eval.

   Field-for-field the child's var-projection row: FQ string sym,
   nested-map ns upsert (never a lookup-ref — a missing `:seon.ns`
   entity would sink the whole transaction), verbatim source, pr-str'd
   arglists, doc/private?, `:spec` only when `:malli/schema` metadata
   parses, `:seon.fn/schema-error` when it does not. `var-meta` is the
   returned sci var's metadata (`:name`/`:arglists`/`:doc` live there
   exactly as on a Clojure var)."
  [{::keys [form var-meta ns-sym source at]}]
  (let [{::keys [fn-name doc attr-map]} (defn-parts form)
        meta-map (merge (or attr-map {}) (meta fn-name))
        arglists (or (:arglists var-meta)
                     (some->> form
                              (drop-while #(not (or (vector? %) (seq? %))))
                              first
                              vector))
        schema-meta (:malli/schema meta-map)
        schema-error (when (some? schema-meta)
                       (try (m/schema schema-meta) nil
                            (catch Throwable throwable
                              (or (.getMessage throwable)
                                  (str throwable)))))
        spec (when (and (some? schema-meta) (nil? schema-error))
               (pr-str (m/form (m/schema schema-meta))))]
    (cond-> {:seon.fn/sym (str ns-sym "/" fn-name)
             :seon.fn/ns {:seon.ns/name ns-sym}
             :seon.fn/source source
             :seon.fn/source-fingerprint (content-hash/sha-256 source)
             :seon.fn/execution-tier :nursery
             :seon.fn/fn-var? true
             :seon.fn/arglists (pr-str arglists)
             :seon.fn/doc (or (:doc var-meta) doc "")
             :seon.fn/private? (= 'defn- (first form))
             :seon.fn/created-at at}
      (true? (:seon.fn/agent-facing? meta-map))
      (assoc :seon.fn/agent-facing? true)
      (some? spec) (assoc :seon.fn/spec spec)
      schema-error (assoc :seon.fn/schema-error schema-error))))

(def ^:private optional-fn-projection-attrs
  #{:seon.fn/spec :seon.fn/schema-error :seon.fn/agent-facing?})

(defn- omitted-fn-projection-retractions
  "Retract optional function facts a redefinition no longer declares.

   Identity upsert replaces asserted values but omission is not a
   retraction; without these ops a removed contract would survive cold
   reconstruction. Retraction is idempotent."
  [fn-rows]
  (into []
        (mapcat (fn [row]
                  (when-let [sym (:seon.fn/sym row)]
                    (for [attr (sort optional-fn-projection-attrs)
                          :when (not (contains? row attr))]
                      [:db.fn/retractAttribute [:seon.fn/sym sym] attr]))))
        fn-rows))

;;; ---------------------------------------------------------------------------
;;; :seon.fn/read-attrs — the declared read-set as data
;;; ---------------------------------------------------------------------------

(defn- defn-read-forms
  "The subforms of `form` whose keyword literals count as reads.

   For a defn that is the params + body: everything between the name and
   the first non-string/non-map element is annotation (docstring,
   attr-map schema refs), never a data read. Structural, not a name
   list. A non-defn form passes through whole."
  [form]
  (if (and (seq? form)
           (symbol? (first form))
           (contains? #{"defn" "defn-"} (name (first form))))
    (drop-while #(or (string? %) (map? %)) (drop 2 form))
    [form]))

(defn- source-qualified-kws
  "Every qualified keyword literal read from `forms`, as a set.

   Placeholder `?`-prefixed namespaces (an alias the read could not
   resolve) are dropped — absent beats storing a garbage watch attr."
  [forms]
  (into #{}
        (comp (mapcat defn-read-forms)
              (mapcat #(tree-seq coll? seq %))
              (filter #(and (keyword? %)
                            (some? (namespace %))
                            (not (str/starts-with? (namespace %) "?")))))
        (or forms [])))

(defn- fn-read-attrs-tx
  "Tx ops making `:seon.fn/read-attrs` exactly `new-kws`.

   Cardinality-many accumulates on plain upsert, so retract the whole
   attribute first, then assert the exact current values as scalar adds
   (an exactly-two-value vector beginning with an identity attr would
   read as ONE lookup ref)."
  [sym-str new-kws]
  (into [[:db.fn/retractAttribute [:seon.fn/sym sym-str]
          :seon.fn/read-attrs]]
        (map (fn [k]
               [:db/add [:seon.fn/sym sym-str] :seon.fn/read-attrs k]))
        (sort-by str new-kws)))

;;; ---------------------------------------------------------------------------
;;; :seon.ns rows + require edges
;;; ---------------------------------------------------------------------------

(defn- require-spec-edge
  "One `:seon.ns.require/*` edge map from one `:require` spec."
  [spec]
  (cond
    (symbol? spec) {:seon.ns.require/target spec}

    (vector? spec)
    (let [[target & {:as opts}] spec]
      (when (symbol? target)
        (cond-> {:seon.ns.require/target target}
          (symbol? (:as opts))
          (assoc :seon.ns.require/alias (:as opts))
          (symbol? (:as-alias opts))
          (assoc :seon.ns.require/alias (:as-alias opts)
                 :seon.ns.require/as-alias? true)
          (and (sequential? (:refer opts)) (seq (:refer opts)))
          (assoc :seon.ns.require/refers (set (:refer opts)))
          (= :all (:refer opts))
          (assoc :seon.ns.require/refer-all? true))))

    :else nil))

(defn ns-require-edges
  "The `:seon.ns.require/*` edge set an `(ns …)` form declares.

   This is the one source-form parser for host recording and host toolkit
   loading. Consumers derive ordering from the returned target facts; they do
   not reparse namespace text with a second regex or dependency model."
  {:malli/schema [:=> [:cat :any] [:set :map]]}
  [ns-form]
  (into #{}
        (comp (filter #(and (seq? %) (= :require (first %))))
              (mapcat rest)
              (keep require-spec-edge))
        (when (seq? ns-form) (drop 2 ns-form))))

(defn- ns-require-edges-tx
  "Tx ops making `:seon.ns/require-edges` for `ns-sym` exactly `edges`.

   Whole-attribute retraction cascades through the component rows, then
   the exact new set asserts through the namespace identity."
  [ns-sym edges]
  (cond-> [[:db.fn/retractAttribute [:seon.ns/name ns-sym]
            :seon.ns/require-edges]]
    (seq edges)
    (conj {:seon.ns/name ns-sym
           :seon.ns/require-edges (vec (sort-by pr-str edges))})))

;;; ---------------------------------------------------------------------------
;;; :seon.schema rows — registrations detected by registry diff
;;; ---------------------------------------------------------------------------

(defn- schema-row
  "The canonical `:seon.schema` row for one newly registered key.

   Identity upsert on `:seon.schema/key`; `:seon.schema/ns` is the
   nested-map upsert and is present only when the key has a keyword
   namespace (an entity-kind key has none — a literal nil would fail
   the whole transaction)."
  [k at]
  (let [form (schema/schema-definition k)
        properties (schema.internal/attr-form-properties form)]
    (cond-> {:seon.schema/key k
             :seon.schema/form (pr-str form)
             :seon.schema/created-at at}
      (contains? properties :seon.db.id/generator)
      (assoc :seon.db.id/generator (:seon.db.id/generator properties))
      (namespace k)
      (assoc :seon.schema/ns {:seon.ns/name (symbol (namespace k))}))))

;;; ---------------------------------------------------------------------------
;;; :seon.eval receipt rows
;;; ---------------------------------------------------------------------------

(defn start-tx-data
  "The component transaction data that starts one eval receipt.

   Shape-for-shape `seon.eval.internal/start-tx-data`: the `:running`
   receipt row rides inside its owning turn's `:seon.agent.turn/evals`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.turn/id :string]
                             [:seon.eval/id :string]
                             [:seon.eval/at :inst]
                             [:seon.eval/source :string]
                             [:seon.eval/narration :string]
                             [:seon.eval/ns :symbol]
                             [:seon.eval/agent {:optional true} :any]]]
                  [:vector :any]]}
  [{turn-id :seon.agent.turn/id
    eval-id :seon.eval/id
    at :seon.eval/at
    source :seon.eval/source
    narration :seon.eval/narration
    eval-ns :seon.eval/ns
    agent :seon.eval/agent}]
  [{:seon.agent.turn/id turn-id
    :seon.agent.turn/evals
    [(cond->
      {:seon.eval/id eval-id
       :seon.eval/status :running
       :seon.eval/at at
       :seon.eval/source source
       :seon.eval/narration narration
       :seon.eval/ns eval-ns}
       agent (assoc :seon.eval/agent agent))]}])

(def ^:private result-edn-cap-chars
  "Character cap on a persisted result projection (~2k tokens)."
  8192)

(defn- cap-edn
  "Truncate a string with the elision marker the child's cap-edn appends."
  [s]
  (let [n (count s)]
    (if (> n result-edn-cap-chars)
      (str (subs s 0 result-edn-cap-chars) " …⟨"
           (tokens/chars->tokens (- n result-edn-cap-chars))
           " tokens elided⟩")
      s)))

(defn terminal-tx-data
  "The CAS-fenced terminal transition plus the frozen eval row.

   Mirrors the child's terminalize path: the `:running` fence, then one
   row carrying outcome, duration, ending ns, bounded result-edn or
   error text, and captured output. `envelope` is the host's per-form
   eval envelope."
  {:malli/schema [:=> [:cat [:map [:seon.eval/id :string]
                             [::envelope :map]
                             [::at :inst]
                             [::duration-ms :int]
                             [::source ::source]
                             [::narration :string]
                             [::ns-sym ::ns-sym]
                             [::agent-ref {:optional true} :any]
                             [::output {:optional true} :string]]]
                  [:vector :any]]}
  [{eval-id :seon.eval/id
    ::keys [envelope at duration-ms source narration ns-sym agent-ref
            output]}]
  (let [ok? (boolean (:seon.eval/ok? envelope))
        value-text (if (contains? envelope :seon.eval/value-display)
                     (str (:seon.eval/value-display envelope))
                     (pr-str (:seon.eval/value envelope)))
        row (cond-> {:seon.eval/id eval-id
                     :seon.eval/status (cond
                                         (:seon.eval/interrupted? envelope)
                                         :interrupted
                                         ok? :done
                                         :else :error)
                     :seon.eval/ok? ok?
                     :seon.eval/at at
                     :seon.eval/duration-ms duration-ms
                     :seon.eval/source source
                     :seon.eval/narration narration
                     :seon.eval/ns ns-sym}
              agent-ref (assoc :seon.eval/agent agent-ref)
              ok? (assoc :seon.eval/result-edn (cap-edn value-text))
              (not ok?)
              (assoc :seon.eval/error
                     (cap-edn (str (get-in envelope
                                           [:seon/error
                                            :seon.error/message]))))
              (and (string? output) (not (str/blank? output)))
              (assoc :seon.eval/output (cap-edn output)))]
    [[:db.fn/cas [:seon.eval/id eval-id] :seon.eval/status
      :running :running]
     row]))

;;; ---------------------------------------------------------------------------
;;; The one tee entry point
;;; ---------------------------------------------------------------------------

(def transient-ns-syms
  "Scratch namespaces whose defs never mint program-graph rows.

   The host's sci default ns plus the child's transient scaffolding —
   the same transient-stays-transient rule as the child tee (C14)."
  #{'user 'cljs.user 'seon.dynamic 'result})

(defn tee-tx-data
  "Every program-graph op one successful executed form tees.

   `:seon.fn` row + optional-attr retractions + read-attrs ops for a
   single defn; `:seon.ns` row + require-edge rows for an explicit ns
   declaration; one `:seon.schema` row per newly registered key. Empty
   for scratch, and a def in a transient ns stays scratch. `forms` is
   the whole-source read ([[read-forms]]; empty fails closed)."
  {:malli/schema [:=> [:cat [:map [::forms ::forms]
                             [::source ::source]
                             [::ns-sym ::ns-sym]
                             [::var-meta {:optional true} :map]
                             [::new-schema-keys
                              [:set :qualified-keyword]]
                             [::at :inst]]]
                  [:vector :any]]}
  [{::keys [forms source ns-sym var-meta new-schema-keys at]}]
  (let [defn? (and (single-defn? forms)
                   (not (contains? transient-ns-syms ns-sym)))
        fn-rows (when defn?
                  [(fn-row {::form (first forms)
                            ::var-meta var-meta
                            ::ns-sym ns-sym
                            ::source source
                            ::at at})])
        read-attr-ops (when defn?
                        (fn-read-attrs-tx
                         (:seon.fn/sym (first fn-rows))
                         (source-qualified-kws forms)))
        declared-ns (ns-declaration forms)
        declared-ns (when-not (contains? transient-ns-syms declared-ns)
                      declared-ns)
        ns-rows (when declared-ns
                  (into [{:seon.ns/name declared-ns
                          :seon.ns/source source}]
                        (ns-require-edges-tx
                         declared-ns
                         (ns-require-edges (first forms)))))
        schema-rows (mapv #(schema-row % at) (sort new-schema-keys))]
    (vec (concat ns-rows
                 fn-rows
                 (omitted-fn-projection-retractions (vec fn-rows))
                 read-attr-ops
                 schema-rows))))
