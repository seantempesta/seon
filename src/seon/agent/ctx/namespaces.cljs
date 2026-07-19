(ns seon.agent.ctx.namespaces
  "Render relevant code namespaces into agent context.

   The current namespace may appear in full, while its required namespaces
   appear as compact cards containing indexed schemas and public signatures.
   Selection is database-derived from real require edges; source indexing and
   function execution remain outside this namespace."
  (:require
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.agent.home :as home]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.error.instrument :as einstrument]
    [seon.schema :as schema]))

;; The compact card renderer is defined at the BOTTOM of this file (its
;; helpers cluster there); [[namespaces-block]] above it dispatches the long
;; tail to it, so forward-declare it here.
(declare format-namespaces-block render-one-ns-compact-row resolve-cfg)

;; ============================================================
;; Config interface — the namespaces-block render dials, as reactive
;; datoms on the namespaces block entity (config-driven-agent-init lane's
;; two-level model: a block ref off the agent record). The config loader
;; transacts these onto the block; `db/transact!` refuses unregistered
;; attrs, so they live HERE, colocated with the render fn that reads them.
;;
;; Attribute-PRESENCE is the config (decision 22/23): a ns present in a set
;; IS its config; compact is the ABSENCE. The element type is
;; `:seon.ns/name` symbol, matching the identity attr's shape. The bridge
;; derives a cardinality-many symbol column. A value (not a
;; `:db.type/ref`) tolerates configuring a not-yet-indexed ns (a fresh
;; `my.agent.*` home ns): an unmatched name simply no-ops in the render.
;; Defaults ride each spec (malli-native, decision 4) — a fresh block is
;; compact-everywhere + full-current-ns purely from these defaults.
;; ============================================================

(schema/register! ::full-source   [:vector {:default []} :seon.ns/name]) ; ns present → render FULL (absent → compact)
(schema/register! ::with-tests    [:vector {:default []} :seon.ns/name]) ; ns present → also show its tests
(schema/register! ::current-full?  [:boolean {:default true}])           ; the agent's current ns renders full
(schema/register! ::current-tests? [:boolean {:default true}])           ; …and its current ns shows its tests
(schema/register! ::home-requires :seon.agent.home/require-specs)

;; A namespaces block is stored as one component entity in
;; `:seon.agent/ctx`. The generic `:seon.agent.ctx/block` schema validates the
;; shared block surface; this stored specialization declares the additional
;; attributes this block family persists and queries. The writer derives their
;; Datahike declarations from this entity form during cold initialization and
;; reopen publication. Keep these fields here—not on `:seon.agent`—because
;; configuration belongs exclusively to the component block.
(schema/register! ::block
  [:map {:seon.db/entity true}
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [::full-source {:optional true} ::full-source]
   [::with-tests {:optional true} ::with-tests]
   [::current-full? {:optional true} ::current-full?]
   [::current-tests? {:optional true} ::current-tests?]])

;; ============================================================
;; The namespace-display rules. Two SEPARATE concerns, both pure
;; string/symbol boundary fns (no dependency on anything in `seon.agent.ctx`):
;;
;;   - WHICH rows are indexable/renderable at all — [[included-ns?]]
;;     (`*.internal` / `*-test` excluded). Shared by the boot indexer and
;;     [[namespaces-block]].
;;   - The BOOT-STORAGE rule — [[full-source-ns?]] — which rows the boot
;;     indexer (`seon.client/ns-row`, the one file reader) stores REAL FULL
;;     FILE TEXT for, so they CAN be rendered full later. A SUPERSET of what
;;     any one agent renders full; it does NOT drive per-agent SELECTION
;;     (that is [[namespaces-block]]'s three-rule model: current ∪ requires
;;     ∪ ::full-source).
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace is indexed but ordinarily hidden.

   Applies to the ns or any of its children — the naming convention IS
   the default filter. An exact per-block `::full-source` pin may reveal it
   for a generated-development assignment; incidental prefix matches never
   do. Source strings convert only at the database boundary."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (let [s (str ns-name)]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn my-ns-name?
  "Rule 2: `my.*` is human-authored code and data — always shown.

   Provenance is not consulted (one name rule, no special cases)."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (let [s (str ns-name)]
    (boolean (or (= s "my") (str/starts-with? s "my.")))))

(defn test-ns-name?
  "Rule 1b: a `*-test` namespace is indexed but never rendered.

   Never enters the agent prompt — its `deftest`s are noise to the working
   agent, and the per-fn `:test` usage example already rides the fn's attr-map in
   the compact head. Full tests stay searchable as indexed database data.
   STRUCTURAL, like [[hidden-ns-name?]]: the
   suffix IS the filter. Source strings and namespace symbols are accepted."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (let [s (str ns-name)]
    (str/ends-with? s "-test")))

(defn included-ns?
  "The ordinary selection rule for namespace sections.

   EVERY indexed :seon.ns row renders EXCEPT *.internal (hidden-ns-name?) and *-test
   (test-ns-name?) ones — both STRUCTURAL naming conventions that apply
   to seon, my.*, and downstream code alike. No prefix allow-list: the
   library gate lives on the INDEX side (only first-party + SEON_EXTRA_SRC
   code ever gets a :seon.ns row — seon.indexing/first-party-file?)."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (let [s (str ns-name)]
    (boolean (and (not (hidden-ns-name? s))
                  (not (test-ns-name? s))))))

(defn- selected-ns?
  "True when `ns-name` may enter this block's exact selection.

   Ordinary namespaces follow [[included-ns?]]. A namespace hidden only by
   the `.internal` convention may enter when its exact symbol is present in
   `full-source`; test namespaces remain excluded even when explicitly pinned.
   This is the narrow generated-development override, not prefix expansion."
  [full-source ns-name]
  (and (not (test-ns-name? ns-name))
       (or (included-ns? ns-name)
           (contains? full-source ns-name))))

(defn- base-ns-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(defn- always-full?
  "True when `ns-name` (source string or symbol) is in the resolved config
   policy's `:seon.config/always` set. BOOT-STORAGE only ([[full-source-ns?]]):
   the boot indexer stores these nses' real file source so a per-agent
   `::full-source` pin CAN promote them to full. It does NOT itself render
   anything full — per-agent SELECTION lives in [[namespaces-block]]. The
   policy is ordinary config data supplied by the boot indexer."
  [configuration ns-name]
  (contains? (:seon.config/always
               (config/namespaces-policy configuration))
             (if (symbol? ns-name) ns-name (symbol (str ns-name)))))

(defn full-source-ns?
  "True when `ns-name` carries its REAL FULL FILE TEXT as `:seon.ns/source`.

   Accepts a source string or namespace symbol. Every `my.*` ns (the
   human-authored code and data — always inlined), INCLUDING `.internal` siblings and
   `-test` siblings (the `-test` suffix is stripped to the subject ns
   first), AND every non-hidden seon.* ns the config policy lists in
   `:seon.config/always` ([[always-full?]] — e.g. `:seon.agent.message`, so
   its REAL body is stored; for seon.* the `.internal` suffix beats the
   config policy). Used by the boot indexer (`seon.client/ns-row`) to
   decide which rows get the file read: it stores source for a SUPERSET of
   what any one agent renders full, so a per-agent `::full-source` pin (or
   the current ns) has real source to show. It does NOT drive per-agent
   SELECTION — that is [[namespaces-block]]'s three-rule model
   ([[included-ns?]] keeps `.internal` out of the prompt regardless of what
   is stored). `my.*.internal` MUST store real source even though it never
   renders: its fns are agent-editable render fns
   (`seon.error/agent-authored-sym?` routes every `my.*` fn through
   the SCI cage) and the cage rebuilds a fn's lexical environment — its
   `:require` `:as` aliases — from the stored `:seon.ns/source`; a
   `(ns x)` stub loses the aliases and the fn cannot run BOUNDED.
   Third-party (`acme`) roots are full-source too, gated separately by the
   extra source map acquired by `seon.client/extra-src-ns->source` (the same
   file read). Every other ns gets the minimal `(ns x)` stub at boot (still
   indexed + searchable)."
  {:malli/schema [:=> [:cat :seon.config/singleton
                       [:or :string :symbol]] :boolean]}
  [configuration ns-name]
  (let [s    (str ns-name)
        base (base-ns-name s)]
    (boolean (or (my-ns-name? base)
                 (and (not (hidden-ns-name? s))
                      (always-full? configuration base))))))

(defn- seon-framework-ns?
  "True when `ns-name` (source string or symbol) is a `seon.*` framework ns —
   used to route a STABLE seon.* required ns into the name-sorted cache PREFIX
   vs the recency BODY (the agent's churning my.* / current ns)."
  [ns-name]
  (let [s (str ns-name)]
    (str/starts-with? s "seon.")))

(defn- required-ns-selections
  "The current ns's callable-card selection, keyed by required namespace.

   Persisted require edges are the one dependency and presentation authority:
   a real `:refer` selects exactly those symbols; `:as`, bare, and
   `:refer :all` edges select the namespace's whole public callable surface.
   Multiple refer edges union. Any whole-surface edge wins. `:as-alias` is a
   keyword-resolution edge only and contributes no callable card. The absence
   of `:seon.ns.require/refers` in a value means whole surface."
  [edges cur-ns]
  (if-not cur-ns
    {}
    (reduce
      (fn [selections
           {:seon.ns.require/keys
            [target alias refers refer-all? as-alias?]}]
        (if (or as-alias? (not (included-ns? target)))
          selections
          (let [whole? (or alias refer-all? (not (seq refers)))
                present? (contains? selections target)
                current (get selections target)]
            (assoc selections target
                   (cond
                     whole? {}
                     (and present?
                          (not (contains? current :seon.ns.require/refers)))
                     current
                     :else
                     {:seon.ns.require/refers
                      (into (or (:seon.ns.require/refers current) #{})
                            refers)})))))
      {}
      edges)))

(def ^:private require-edge-selector
  '[:seon.ns.require/target
    :seon.ns.require/alias
    :seon.ns.require/refers
    :seon.ns.require/refer-all?
    :seon.ns.require/as-alias?])

(def ^:private namespace-selector
  `[:seon.ns/name
    :seon.ns/source
    {:seon.ns/require-edges ~require-edge-selector}
    {:seon.fn/_ns [:seon.fn/sym :seon.fn/arglists :seon.fn/doc
                   :seon.fn/source :seon.fn/spec :seon.fn/private?
                   :seon.fn/fn-var? :seon.fn/schema-error]}
    {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}
    {:seon.test/_ns [:seon.test/sym :seon.test/source
                     :seon.test/last-passed-at :seon.test/last-failed-at
                     :seon.test/last-failure-summary]}])

(def ^:private agent-selector
  `[:seon.agent/id
    {:seon.agent/namespace [:seon.ns/name]}
    {:seon.agent/ctx [:db/id :seon.agent.ctx/name
                      ::full-source ::with-tests
                      ::current-full? ::current-tests?]}])

(def ^:private generated-assignment-query
  {:find [(list 'pull '?step
                '[:my.plan/id :my.plan/title :my.plan/status :my.plan/goal
                  :my.plan/description :my.plan/expect
                  {:my.plan/namespace [:seon.ns/name]}
                  {:my.plan/parent
                   [:my.plan/id :my.plan/title :my.plan/status :my.plan/goal
                    :my.plan/description :my.plan/expect
                    {:my.plan/_parent
                     [{:my.plan/namespace [:seon.ns/name]}]}]}]) '.]
   :in ['$ '?agent-id]
   :where [['?agent :seon.agent/id '?agent-id]
           ['?run :seon.agent.run/agent '?agent]
           ['?run :seon.agent.run/status :open]
           ['?run :seon.agent.run/cause '?message]
           ['?step :my.plan/message '?message]
           ['?step :my.plan/namespace '?namespace]]})

(def ^:private namespace-catalog-query
  '{:find [?name ?summary]
    :where [[?namespace :seon.ns/name ?name]
            [(get-else $ ?namespace :seon.ns/summary "") ?summary]]
    :order-by [?name :asc]})

(defn- initial-acquisition-members
  [id]
  [{::protocol/operation protocol/pull-operation
    ::protocol/selector agent-selector
    ::protocol/entity-id [:seon.agent/id id]
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 2048
    :datahike.resource/max-result-weight 262144}
   {::protocol/operation protocol/query-operation
    ::protocol/query-form home/latest-successful-ns-query
    ::protocol/arguments [id]
    :datahike.resource/max-work 1000000
    ;; Datahike counts intermediate relation rows before order/limit. This
    ;; admits roughly 8,000 successful evals and fails explicitly beyond it.
    :datahike.resource/max-results 32768
    :datahike.resource/max-result-weight 262144}
   {::protocol/operation protocol/query-operation
    ::protocol/query-form home/namespace-assignment-query
    ::protocol/arguments [id]
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 64
    :datahike.resource/max-result-weight 4096}
   {::protocol/operation protocol/query-operation
    ::protocol/query-form generated-assignment-query
    ::protocol/arguments [id]
    :datahike.resource/max-work 1000000
    :datahike.resource/max-results 64
    :datahike.resource/max-result-weight 65536}])

(defn- selected-acquisition-members
  ([names] (selected-acquisition-members names false))
  ([names include-catalog?]
   (cond->
    [{::protocol/operation protocol/pull-many-operation
      ::protocol/selector namespace-selector
      ::protocol/entity-ids
      (mapv (fn [nm] [:seon.ns/name nm]) names)
      :datahike.resource/max-work 2000000
      :datahike.resource/max-results 50000
      :datahike.resource/max-result-weight 3145728}
     {::protocol/operation protocol/query-operation
      ::protocol/query-form
      '[:find ?name ?tx
        :in $ [?name ...]
        :where
        [?namespace :seon.ns/name ?name ?tx]]
      ::protocol/arguments [names]
      :datahike.resource/max-work 500000
      :datahike.resource/max-results 256
      :datahike.resource/max-result-weight 8192}]
     include-catalog?
     (conj
      {::protocol/operation protocol/query-operation
       ::protocol/query-form namespace-catalog-query
       ::protocol/arguments []
       :datahike.resource/max-work 2000000
       :datahike.resource/max-results 8192
       :datahike.resource/max-result-weight 524288}))))

(defn- member-result
  [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- acquisition-error
  [stage value]
  {:seon.error/message (str "Namespace " stage " failed.")
   :seon.error/data value
   :seon.error/kind :core-bug})

(defn- effective-full-source
  "Stored namespace policy plus this active generated assignment's siblings."
  [block generated-namespaces]
  (into (set (resolve-cfg block ::full-source #{}))
        generated-namespaces))

(defn- assignment-namespaces
  "Namespace symbols of every namespace-bearing sibling in an assignment."
  [assignment]
  (into #{}
        (keep #(get-in % [:my.plan/namespace :seon.ns/name]))
        (get-in assignment [:my.plan/parent :my.plan/_parent])))

(defn ^:async ^:private acquire-namespace-rows!
  "Acquire namespace rows at one database value."
  [{id :seon.agent/id :as input}]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        initial (if (:seon.error/message database)
                  database
                  (await (db/execute-many
                           {::db/db database
                            ::db/members (initial-acquisition-members id)
                            ::db/max-result-weight 786432})))]
    (if (:seon.error/message initial)
      (acquisition-error "initial acquisition" initial)
      (let [[agent-member eval-ns-member assignment-member
             generated-assignment-member]
            (::db/results initial)
            agent (member-result agent-member)
            latest-successful-ns (some-> (member-result eval-ns-member) first)
            namespace-assignment (some-> (member-result assignment-member) first)]
        (if-not (and (true? (::protocol/success? agent-member))
                     (true? (::protocol/success? eval-ns-member))
                     (true? (::protocol/success? assignment-member))
                     (true? (::protocol/success? generated-assignment-member)))
          (acquisition-error "initial member" (::db/results initial))
          (let [cur-ns (home/current-ns id agent latest-successful-ns
                                        namespace-assignment)
                current-row
                (await (db/pull {::db/db database
                                 ::db/pull-pattern
                                 [:seon.ns/name
                                  {:seon.ns/require-edges require-edge-selector}]
                                 ::db/ref [:seon.ns/name cur-ns]
                                 ::db/max-work 100000
                                 ::db/max-results 512
                                 ::db/max-result-weight 65536}))]
            (if (and (map? current-row) (:seon.error/message current-row))
              (acquisition-error "require-edge acquisition" current-row)
              (let [block (some (fn [candidate]
                                  (when (= :namespaces
                                           (:seon.agent.ctx/name candidate))
                                    candidate))
                                (:seon.agent/ctx agent))
                    generated-assignment
                    (member-result generated-assignment-member)
                    generated-full-source
                    (assignment-namespaces generated-assignment)
                    full-source-cfg
                    (effective-full-source block generated-full-source)
                    with-tests-cfg
                    (set (resolve-cfg block ::with-tests #{}))
                    current-full?
                    (resolve-cfg block ::current-full? true)
                    current-tests?
                    (resolve-cfg block ::current-tests? true)
                    required
                    (required-ns-selections
                      (:seon.ns/require-edges current-row) cur-ns)
                    names (->> (cond-> (into (set (keys required))
                                               full-source-cfg)
                                 cur-ns (conj cur-ns))
                               (filter #(selected-ns? full-source-cfg %))
                               (sort-by name)
                               vec)
                    selected
                    (await (db/execute-many
                             {::db/db database
                              ::db/members
                              (selected-acquisition-members
                               names (some? generated-assignment))
                              ;; Leave 448 KiB beneath the 4 MiB frame ceiling
                              ;; for the protocol response.
                              ::db/max-result-weight 3735552}))
                    [rows-member tx-member catalog-member]
                    (::db/results selected)]
                (if-not (and (true? (::protocol/success? rows-member))
                             (true? (::protocol/success? tx-member))
                             (or (nil? generated-assignment)
                                 (true? (::protocol/success? catalog-member))))
                  (acquisition-error "selected member" (::db/results selected))
                  (let [rows (member-result rows-member)
                        txs (into {} (member-result tx-member))]
                    {::db/db database
                     :seon.agent/id id
                     :seon.agent.ctx.render-fns/current-ns cur-ns
                     ::full-source full-source-cfg
                     ::with-tests with-tests-cfg
                     ::current-full? current-full?
                     ::current-tests? current-tests?
                     ::generated-assignment generated-assignment
                     ::catalog-rows (vec (member-result catalog-member))
                     ::namespace-rows
                     (mapv (fn [nm row]
                             (cond-> (or row {:seon.ns/name nm})
                               (get txs nm) (assoc :seon.db/tx (get txs nm))))
                           names rows)}))))))))))

(def ^:private schema-frontier-query
  '[:find ?requested ?form
    :in $ [?requested ...]
    :where
    [?schema :seon.schema/key ?requested]
    [?schema :seon.schema/form ?form]])

(def ^:private schema-row-aggregate-cap 2048)

(defn- schema-frontier-request
  [database frontier]
  {::db/db database
   ::db/query schema-frontier-query
   ::db/args [frontier]
   ::db/max-work 500000
   ::db/max-results 256
   ::db/max-result-weight 262144})

(defn- database-error
  [value]
  (when (and (map? value) (string? (:seon.error/message value))) value))

(defn ^:async ^:private acquire-one-schema-closure!
  [database row state]
  (let [own-keys (into #{} (keep :seon.schema/key) (:seon.schema/_ns row))
        sources (into []
                      (remove str/blank?)
                      (concat (keep :seon.fn/spec (:seon.fn/_ns row))
                              (keep :seon.schema/form (:seon.schema/_ns row))))
        initial (into (sorted-set)
                      (remove own-keys)
                      (ctx/schema-refs sources))]
    (loop [pending initial seen own-keys state state]
      (if (or (empty? pending)
              (::error state))
        state
        (let [frontier (vec (take ctx/referenced-schema-cap pending))
              rows-by-key (::schema-rows-by-key state)
              missing (::missing-schema-keys state)
              unknown (filterv #(and (not (contains? rows-by-key %))
                                     (not (contains? missing %)))
                               frontier)
              response (if (seq unknown)
                         (await (db/query (schema-frontier-request
                                           database unknown)))
                         [])]
          (if-let [error (database-error response)]
            (assoc state ::error
                   (acquisition-error "schema frontier acquisition" error))
            (let [returned (into {}
                                 (map (fn [[k form]]
                                        [k {:seon.schema/key k
                                            :seon.schema/form form}]))
                                 response)
                  rows-by-key' (merge rows-by-key returned)
                  missing' (into missing
                                 (remove #(contains? returned %))
                                 unknown)]
              (if (> (+ (count rows-by-key') (count missing'))
                     schema-row-aggregate-cap)
                (assoc state ::error
                       (acquisition-error
                         "schema aggregate bound"
                         {:seon.agent.ctx/schema-key-count
                          (+ (count rows-by-key') (count missing'))
                          :seon.agent.ctx/schema-key-cap
                          schema-row-aggregate-cap}))
                (let [definitions (keep rows-by-key' frontier)
                      children (ctx/schema-refs
                                 (mapv :seon.schema/form definitions))
                      seen' (into seen frontier)
                      pending' (into (sorted-set)
                                     (remove seen')
                                     (concat (remove (set frontier) pending)
                                             (remove own-keys children)))]
                  (recur pending' seen'
                         (assoc state
                                ::schema-rows-by-key rows-by-key'
                                ::missing-schema-keys missing')))))))))))

(defn ^:async ^:private acquire-schema-rows!
  "Acquire each namespace's bounded referenced-schema closure."
  [input]
  (if-let [error (database-error input)]
    (assoc input ::error error)
    (let [database (::db/db input)
          namespace-rows (::namespace-rows input)]
      (loop [rows namespace-rows
             state {::schema-rows-by-key {}
                    ::missing-schema-keys #{}}]
        (if (or (empty? rows) (::error state))
          (cond-> (assoc input :seon.agent.ctx/schema-rows
                         (->> (::schema-rows-by-key state)
                              vals
                              (sort-by :seon.schema/key)
                              vec))
            (::error state) (assoc ::error (::error state)))
          (recur (subvec rows 1)
                 (await (acquire-one-schema-closure!
                          database (first rows) state))))))))

(defn- full?
  "True when an included ns `nm` renders FULL (its whole real source); false
   means it renders as a COMPACT CARD ([[render-one-ns-compact]]). ONE rule, no
   second full control:

     full? ⇔ (nm = current-ns ∧ ::current-full?) ∨ (nm ∈ ::full-source)

   The current ns honors the namespaces block's `::current-full?` flag
   (default true). Every other included namespace — the current namespace's
   requirements — renders compact unless selected by `::full-source`."
  [nm cur-ns full-source current-full?]
  (boolean
    (or (contains? full-source nm)
        (and (= nm cur-ns)
             current-full?))))

(defn- resolve-cfg
  "Resolve render-dial attr `k` from the one `:namespaces` block.

   `some?` (not truthiness) draws the present/absent line so a legitimate
   false or empty presence-set overrides the default. Namespace render config
   never falls back to duplicate attributes on the agent entity."
  [block k default]
  (let [bv (get block k)]
    (if (some? bv) bv default)))

(defn- ns-tests-block
  "The indexed test SOURCE for ns `nm`, as a `; tests:`-headed block appended
   after the ns's full/compact render — or nil when the ns owns no
   `:seon.test` rows. Code-as-data: reads the stored `:seon.test/source`
   (keyed off the subject ns via `:seon.test/_ns`), never a file read. Drives
   the `::with-tests` presence-set (an ns in the set → its tests ride along)
   and the current ns's `::current-tests?` flag. nil when the ns has no
   `:seon.ns/name` entity (a brand-new current ns not yet indexed — `db/pull`
   would throw on the missing lookup ref)."
  [row]
  (let [tests (->> (:seon.test/_ns row)
                   (sort-by :seon.test/sym))
        srcs  (keep (fn [{:seon.test/keys [source]}]
                      (when-not (str/blank? source) (str/trim source)))
                    tests)]
    (when (seq srcs)
      (str "\n\n; tests:\n" (str/join "\n\n" srcs)))))

(def ^:private namespaces-header
  ;; Block-specific teaching, COLOCATED (the namespaces surface teaches its
  ;; own functions AND its own render policy — owner rulings 2026-07-10; runtime =
  ;; seon.eval/dispatch-repl-form!): movement/update functions + the full-vs-cards
  ;; distinction + "more namespaces exist in the db". Under minimal context
  ;; the system-text §"THE NAMESPACES BELOW" never renders, so this header is
  ;; the ONE place the policy is taught. Keep it tight.
  (str "; The loaded namespaces below, ordered by recency"
       " (most-recently-modified last).\n"
       "; Namespaces are PLACES — (in-ns 'the.ns) moves you there (your state\n"
       "; is preserved; a NEW name is created with your toolkit requires).\n"
       "; (ns the.ns (:require [dependency.ns :as dep])) declares/UPDATES requires.\n"
       "; A bare (require '[x :as y]) adds a dependency now AND records it in\n"
       "; the declaration. Redefining a fn/schema/test IS how you update it;\n"
       "; (ns-unmap 'name) removes one.\n"
       "; Your CURRENT namespace renders in FULL; its required namespaces\n"
       "; render as INERT COMPACT CARDS (fn signatures + docstring line 1 +\n"
       "; :malli/schema; no bodies or pseudo-definitions). More namespaces exist\n"
       "; here — query rather than guess."))

(defn- cur-ns-workspace-stub
  "The never-omit block for the agent's CURRENT ns when it has no members
   defined yet (GI-2). A fresh home ns (`my.agent.<id>`) carries a
   `:seon.ns/name` row but no stored `:seon.ns/source` and no fns/schemas, so
   [[seon.agent.ctx/render-namespace-ai]] yields an empty body that would be omitted
   — breaking the system prompt's promise that YOUR OWN namespace renders in
   full. This stub keeps that promise: it shows the REAL `(ns … (:require …))`
   form [[seon.eval/setup-agent-ns!]] actually installed — `[seon.agent.message
   :as message]` / `[seon.agent.lifecycle :refer [wait complete …]]` / … WITH
   the aliases + refers — straight from the ONE canonical
   [[seon.agent.home/home-ns-form]], NOT a bare-name reconstruction from the
   stored edges. No hidden aliasing: the agent reads the form and knows
   `message/user`, `db/transact!`, `schema/register!`, `wait`, `complete`
   exist and how to call them. `nm` is a namespace symbol whose `:seon.ns/name`
   row the caller already matched (an included, current-ns row). `id` is the
   agent id, threaded so the stub prose shows THIS agent's actual configured
   requires ([[seon.agent.home/home-requires-for]]) — not the const default."
  [nm require-specs]
  (ctx/ns-demarc
    nm
    (str (home/home-ns-form nm require-specs) "\n"
         "; (your workspace — nothing defined here yet; define schemas + fns and they appear here.\n"
         ";  a defn whose :malli/schema output declares :seon.render/ai (and/or :seon.render/hiccup)\n"
         ";  auto-runs every turn: its output becomes a live section of your context + a surface on your page)")))

(defn- render-one
  "Render ONE included ns FULL through the pure ordinary-data renderer
   ([[seon.agent.ctx/render-namespace-ai]]), flat (no require-recursion;
   the section renders each ns once): the whole-ns view, real file source +
   members, unclipped.

   The agent's CURRENT ns (`cur-ns`) ALWAYS renders, even when empty: an
   empty current ns becomes a [[cur-ns-workspace-stub]] (GI-2) so the prompt's
   'YOUR OWN namespace renders in full' promise holds — this covers BOTH a home
   ns with a `:seon.ns/name` row but no source AND one with NO row at all (a
   brand-new agent whose home ns was never indexed;
   the missing row short-circuits to the stub). Every OTHER ns with nothing
   real to show is
   omitted (nil). `id` threads through to the workspace stub so its prose
   reflects THIS agent's configured requires."
  [nm row schema-rows cur-ns require-specs]
  (if-not row
    ;; No `:seon.ns/name` entity — the current ns still keeps its promise via
    ;; the workspace stub; any other row with no entity is omitted.
    (when (= nm cur-ns) (cur-ns-workspace-stub nm require-specs))
    (let [txt    (-> (ctx/render-namespace-ai
                       {:seon.ns/name nm
                        :seon.agent.ctx/namespace-rows [row]
                        :seon.agent.ctx/schema-rows schema-rows})
                     str/trim)
          ;; render-namespace brackets even an empty ns, whose body is then
          ;; `; (no recorded source/fns/schemas)` (entity present, no
          ;; source/members) or `; requires: x (not in db)` (the home ns —
          ;; a :seon.ns/name row whose sparse pull returns nil). Both mean
          ;; "nothing real to show."
          empty? (or (str/blank? txt)
                     (str/includes? txt "(no recorded source/fns/schemas)")
                     (str/includes? txt "(not in db)"))]
      (cond
        (= nm cur-ns) (if empty?
                        (cur-ns-workspace-stub nm require-specs)
                        txt)
        empty?        nil
        :else         txt))))

(defn- compact-block
  "Render ns `nm` as a COMPACT CARD, or nil when the card would carry no real
   content (a `; (nothing indexed)` / `; (not in db …)` stub) — a required ns
   with nothing indexed adds noise, not signal, so it stays dropped rather than
   emit an empty card. The compact SIBLING of [[render-one]] (the full
   wrapper); delegates to [[render-one-ns-compact]]."
  [nm row schema-rows selection]
  (let [card (render-one-ns-compact-row
               nm row schema-rows (:seon.ns.require/refers selection))]
    (when-not (or (str/includes? card "(nothing indexed)")
                  (str/includes? card "(not in db"))
      card)))

(defn- namespace-catalog-text
  "Generate-code-only namespace symbols with their indexed one-line summaries."
  [catalog-rows]
  (when-let [lines
             (seq
              (into []
                    (comp
                     (remove (fn [[name _summary]] (test-ns-name? name)))
                     (map (fn [[name summary]]
                            (str "; " name
                                 (when-not (str/blank? summary)
                                   (str " — " summary))))))
                    catalog-rows))]
    (str ";;; AVAILABLE PRODUCTION NAMESPACES\n"
         "; These are names, not pseudo-definitions. Inspect compact public\n"
         "; contracts with (my.ns/functions {:my.ns/ns 'the.namespace}).\n"
         (str/join "\n" lines))))

(defn ^:async namespaces-block
  "Acquire and render namespaces at the active database value."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :map]}
  [input _invoke-selected!]
  (let [database-acquired
        (await (acquire-schema-rows! (await (acquire-namespace-rows! input))))
        home-requires
        (when-not (database-error database-acquired)
          (await (home/home-requires-for
                   (::db/db database-acquired)
                   (:seon.agent/id database-acquired))))
        acquired
        (cond
          (database-error database-acquired) database-acquired
          (database-error home-requires)
          (assoc database-acquired ::error
                 (acquisition-error "home require acquisition" home-requires))
          :else (assoc database-acquired ::home-requires home-requires))
        current-ns (:seon.agent.ctx.render-fns/current-ns acquired)
        current-row (some #(when (= current-ns (:seon.ns/name %)) %)
                          (::namespace-rows acquired))]
    {:seon.render/ai (format-namespaces-block acquired)
     :seon.agent.ctx.render-fns/current-ns current-ns
     :seon.agent.ctx.render-fns/fn-rows
     (vec (:seon.fn/_ns current-row))}))

(defn- format-namespaces-block
  "The namespaces body — the CURRENT ns full, its requires as cards.

   COMPACT EVERYTHING EXCEPT THE CURRENT NS — no hardcoded
   list, no `my.*` pinning, no `:always` allow-list. THREE rules:

     - FULL — the agent's CURRENT ns + any ns in the per-agent `::full-source`
       presence-set. A `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─`
       bracketed block carrying its REAL FULL FILE SOURCE, unclipped.
     - COMPACT CARD — every ns the CURRENT ns `:require`s
       ([[required-ns-selections]]) that isn't already full
       ([[render-one-ns-compact]]): inert selected schema records + every
       selected public, schema-complete fn's one-line signature, ~3–5× smaller
       than full.
       The whole card is reader-commented, so echoing it cannot enqueue evals.
       Self-healing on the `:seon.ns/require-edges` rows.
     - DROPPED — everything else remains reachable via indexed search.
       `*.internal` is hidden unless its exact symbol is explicitly pinned in
       `::full-source`; `*-test` is always excluded. Empty cards are dropped.

   DRIVEN BY THE PER-AGENT CONFIG DIALS, read reactively from the agent's one
   `:namespaces` BLOCK entity, then the Malli default ([[resolve-cfg]] — a
   `db/transact!` re-derives next render, no apply step):

     - `::full-source` — a presence-set of namespace symbols to force FULL;
     - `::current-full?` (default true) — whether the agent's CURRENT ns
       renders full (false → its compact card);
     - `::with-tests` — a presence-set of namespace symbols whose indexed test SOURCE
       rides along under the ns's block; the current ns joins this set when
       `::current-tests?` (default true) is on.

   ORDER: the STABLE `seon.*` required nses render FIRST, name-sorted, as a
   cache PREFIX; then the agent's churning BODY (my.* / current ns) ordered by
   RECENCY (tx of the `:seon.ns/name` datom, name tie-break) so the stable core
   forms a stable prefix and the current ns sits nearest the tail.

   NEVER a render-time file read — the boot indexer is the one reader; both the
   full renderer and the compact card read only indexed rows. During an active
   generated-code assignment the same block prepends the complete indexed
   production namespace catalog (symbol + `:seon.ns/summary`); ordinary agents
   never pay that catalog cost."
  [input]
  (if-let [error (or (::error input) (database-error input))]
      (str "[namespaces] render failed: " (pr-str error))
      (let [cur-ns          (:seon.agent.ctx.render-fns/current-ns input)
            full-source-cfg (set (::full-source input))
            with-tests-cfg  (set (::with-tests input))
            current-full?   (if (boolean? (::current-full? input))
                              (::current-full? input) true)
            current-tests?  (if (boolean? (::current-tests? input))
                              (::current-tests? input) true)
            namespace-rows  (::namespace-rows input)
            schema-rows     (:seon.agent.ctx/schema-rows input)
            home-requires   (::home-requires input)
            row-by-name     (into {} (map (juxt :seon.ns/name identity))
                                  namespace-rows)
            current-row     (get row-by-name cur-ns)
            required        (required-ns-selections
                              (:seon.ns/require-edges current-row) cur-ns)
            include-set     (cond-> (into (set (keys required)) full-source-cfg)
                              cur-ns (conj cur-ns))
            tests-set       (cond-> with-tests-cfg
                              (and cur-ns current-tests?) (conj cur-ns))
            scanned         (->> namespace-rows
                     (filter (fn [row]
                               (let [nm (:seon.ns/name row)]
                                 (and (selected-ns? full-source-cfg nm)
                                      (contains? include-set nm)))))
                     (sort-by (fn [row]
                                [(or (:seon.db/tx row)
                                     js/Number.MAX_SAFE_INTEGER)
                                 (name (:seon.ns/name row))])))
            rows (if (and cur-ns
                      (not (some #(= cur-ns (:seon.ns/name %)) scanned)))
               (conj (vec scanned) {:seon.ns/name cur-ns})
               scanned)
        ;; Each row + its full? flag + PHASE: :prefix for a STABLE seon.*
        ;; required ns (name-sorted cache prefix); :body for the agent's
        ;; churning nses (my.* / current ns), recency-ordered nearest the tail.
            selected (mapv (fn [row]
                         (let [nm (:seon.ns/name row)
                               prefix? (and (not= nm cur-ns)
                                            (seon-framework-ns? nm))]
                           [nm row
                            (full? nm cur-ns full-source-cfg current-full?)
                            (if prefix? :prefix :body)
                            (if (= nm cur-ns) {} (get required nm {}))]))
                       rows)
        ;; Render ONE row: full → render-one (omitted when empty); else a
        ;; COMPACT card (it is a required ns). A card is nil when nothing is
        ;; indexed. Append the ns's indexed test source when it is in tests-set.
            render-row (fn [[nm row full? _phase selection]]
                     (when-let [block-txt (if full?
                                            (render-one nm row schema-rows cur-ns
                                                        home-requires)
                                            (compact-block nm row schema-rows selection))]
                       (str block-txt
                            (when (contains? tests-set nm)
                              (ns-tests-block row)))))
            prefix-rows (->> selected
                         (filter (fn [[_ _ _ phase _]] (= phase :prefix)))
                         (sort-by (fn [[nm _ _ _ _]] (name nm))))
            body-rows   (filterv (fn [[_ _ _ phase _]] (= phase :body)) selected)
            prefix-blocks (keep render-row prefix-rows)
            body-blocks   (keep render-row body-rows)
            blocks        (concat prefix-blocks body-blocks)
            catalog       (namespace-catalog-text (::catalog-rows input))]
    (if (seq blocks)
      (str namespaces-header
           (when catalog (str "\n\n" catalog))
           "\n\n" (str/join "\n\n" blocks))
      ""))))

;; ============================================================
;; The COMPACT card renderer — a SIBLING detail-level to
;; [[seon.agent.ctx/render-one-ns-ai]]'s full-source block, NOT a
;; replacement. A card is 3–5× smaller than full source: it keeps the
;; whole schema data model + every public fn's `:malli/schema` I/O contract +
;; its arglist, as INERT comment records. It omits the fn BODY and the deep
;; multiline prose (all but docstring line 1). This is the
;; coverage lever — for the budget of ~11 full nses the agent instead
;; sees its ENTIRE function surface as cards.
;;
;; It reads INDEXED ROWS ONLY (`:seon.fn/_ns`, `:seon.schema/_ns`),
;; never a file read — code-as-data, the boot indexer is the one reader.
;; Every helper is errors-as-values: a bad row degrades one line, never
;; throws into the render.
;;
;; WIRED into [[namespaces-block]]: the current ns + `::full-source` pins
;; render full, the current ns's `:require`s render here as compact cards.
;; ============================================================

(defn- soft-clip
  "Return `s` unchanged when it fits `n`, else append an explicit clip marker.

   Callable records never use an ellipsis as a pretend body or omission token:
   models copied that glyph into executable forms. This remains an interim
   guard until the corpus's docstring line 1 reliably complies with the
   compact-summary convention."
  [s n]
  (let [marker " [clipped]"]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n (count marker)))) marker)
      s)))

(def ^:private runtime-object-tag-re
  "A legacy/runtime `pr-str` object tag, which `cljs.reader` cannot read.

   Function-valued Malli predicates are the common source. New live forms are
   projected through [[einstrument/pr-str-readable]] before this backstop; the
   regex also heals already-indexed `:seon.schema/form` / `:seon.fn/spec`
   strings without a database migration. Bounded to one line so a malformed
   tag can never consume a following compact record."
  #"#object\[[^\]\n]*\]")

(defn- omit-runtime-object-tags
  "Replace unreadable runtime object tags in display string `s`.

   Compact namespace records are documentation, not a replay source. An opaque
   predicate therefore becomes an explicit display marker instead of leaking
   `#object[...]` into the agent's Clojure-shaped prompt."
  [s]
  (let [clean (str/replace (str s) runtime-object-tag-re "<runtime value omitted>")]
    (if (str/includes? clean "#object")
      "<unreadable runtime value omitted>"
      clean)))

(defn- schema-form-text
  "Reader-safe display text derived from one persisted schema source.

   The database is the rendering authority. Both boot-indexed raw forms and
   runtime-indexed `(seon.schema/register! ...)` calls normalize through
   [[ctx/normalize-schema-form]]; unreadable rows are scrubbed rather
   than replaced from Malli's mutable process registry."
  [source]
  (if (str/blank? source)
    "<not indexed>"
    (if-some [form (ctx/normalize-schema-form source)]
      (omit-runtime-object-tags (einstrument/pr-str-readable form))
      (omit-runtime-object-tags (str/trim source)))))

(defn- compact-schema-line
  "One inert `schema <key> = <form>` record for a schema the ns owns.

   `<form>` comes only from persisted `:seon.schema/form`. Every keyword
   stays fully qualified because a compact card is read from the caller's
   CURRENT ns, not necessarily the namespace it describes. It is deliberately
   NOT a synthetic `register!` form: compact cards describe callable code but
   never present partial/reprojected data as executable source."
  [{:seon.schema/keys [key form]}]
  (str "schema " (pr-str key) " = " (schema-form-text form)))

(defn- parsed-arglists
  "Stored arglists as a nonempty seq of vectors, or nil when unreadable."
  [arglists]
  (let [parsed (try (edn/read-string arglists) (catch :default _ nil))]
    (when (and (seq? parsed) (seq parsed) (every? vector? parsed)) parsed)))

(defn- schema-children
  "A vector Malli form's children, excluding its optional properties map."
  [form]
  (when (vector? form)
    (let [xs (subvec form 1)]
      (if (map? (first xs)) (subvec xs 1) xs))))

(defn- readable-schema
  "Reader-safe schema text. Inline maps lose only Malli's `:map` wrapper."
  [form]
  (if (and (vector? form) (= :map (first form)))
    (str "{" (str/join ", "
                       (map (fn [[k & xs]]
                              (let [props (when (map? (first xs)) (first xs))
                                    value (if props (second xs) (first xs))]
                                (str (pr-str k) (when (:optional props) "?")
                                     " " (pr-str value))))
                            (schema-children form))) "}")
    (omit-runtime-object-tags (einstrument/pr-str-readable form))))

(defn- arity-specs
  "The `:=>` forms represented by one persisted function spec string."
  [spec]
  (let [form (when-not (str/blank? spec)
               (try (edn/read-string spec) (catch :default _ nil)))]
    (when (vector? form)
      (case (first form)
        :=> [form]
        :function (filterv #(and (vector? %) (= :=> (first %)))
                           (schema-children form))
        nil))))

(defn- binding-label
  "A stable label for one source arg binding."
  [binding i]
  (cond
    (symbol? binding) (name binding)
    (map? binding) "request"
    (vector? binding) "value"
    :else (str "arg-" (inc i))))

(defn- input-pairs
  "Argument label/schema pairs from `:cat` or `:catn`."
  [input arglist]
  (if-not (vector? input)
    []
    (let [children (vec (or (schema-children input) []))
          ;; `&` is Clojure binding grammar, not the name of the following
          ;; logical Malli input. Preserve the actual rest binding instead.
          bindings (vec (remove #{'&} arglist))]
      (if (= :catn (first input))
        (mapv (fn [entry]
                [(if (keyword? (first entry))
                   (pr-str (first entry))
                   (str (first entry)))
                 (last entry)])
              children)
        (mapv (fn [i schema]
                [(binding-label (get bindings i) i) schema])
              (range)
              children)))))

(defn- arity-contract
  "One function arity without Malli's callable grammar."
  [arglist spec]
  (if-not spec
    (str "positional " (pr-str (or arglist [])) " -> <return unspecified>")
    (let [[input output] (schema-children spec)
          pairs          (input-pairs input arglist)
          map-in?        (and (= 1 (count pairs))
                              (or (map? (first arglist))
                                  (and (vector? (second (first pairs)))
                                       (= :map (first (second (first pairs)))))))]
      (str (if map-in? "map-in " "positional ")
           (if map-in?
             (readable-schema (second (first pairs)))
             (if (seq pairs)
               (str "[" (str/join ", "
                                  (map (fn [[label schema]]
                                         (str label " " (readable-schema schema)))
                                       pairs)) "]")
               (pr-str (or arglist []))))
           " -> " (readable-schema output)))))

(defn- callable-contract
  "All persisted arities as one inert callable contract."
  [arglists spec]
  (let [physical-args (vec (or (parsed-arglists arglists) []))
        specs         (vec (or (arity-specs spec) []))
        ;; A CLJS implementation can use one physical variadic body so Malli
        ;; can describe several logical call shapes. In that exact case the
        ;; physical `[& xs]` is implementation data, not an argument list for
        ;; the first logical arity. The named Malli inputs own every label.
        args          (if (and (= 1 (count physical-args))
                               (< 1 (count specs))
                               (some #{'&} (first physical-args)))
                        []
                        physical-args)
        ;; A valid persisted Malli function schema is the callable authority:
        ;; render exactly its logical arities. Source indexing may recover a
        ;; vector-valued implementation body as another physical arglist (for
        ;; example returned Hiccup immediately following a single arg vector).
        ;; Such implementation data must never become an unspecced alternative.
        ;; Physical arglists remain the fallback only when no callable schema
        ;; was indexed at all.
        n             (if (seq specs) (count specs) (count args))]
    (if (zero? n)
      "positional [] -> <return unspecified>"
      (str/join " OR "
                (map #(arity-contract (get args %) (get specs %))
                     (range n))))))

;; Concrete value shapes, NOT references to the :seon.fn/* attr schemas —
;; those register in `seon.agent`, which loads AFTER this ns at boot, and
;; register! validates compilability eagerly (forward refs throw here).
(schema/register! ::fn-row
  [:map
   [:seon.fn/sym      :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/doc      {:optional true} :string]
   [:seon.fn/spec     {:optional true} :string]
   [:seon.fn/schema-error {:optional true} :string]
   [:seon.fn/fn-var?  {:optional true} :boolean]
   [:seon.fn/private? {:optional true} :boolean]])

(defn callable-fn-row?
  "True for a public function row with a usable complete schema."
  {:malli/schema [:=> [:cat ::fn-row] :boolean]}
  [{:seon.fn/keys [spec schema-error fn-var? private?]}]
  (boolean (and fn-var?
                (not private?)
                (string? spec)
                (not (str/blank? spec))
                (str/blank? schema-error)
                (seq (arity-specs spec)))))

(defn compact-fn-head
  "One fn condensed to an inert, readable callable-contract record.

   The record names map-in versus positional invocation explicitly, pairs each
   argument with its type, and names the return type. It never exposes Malli's
   function grammar (`:=>` / `:cat` / `:catn`), never synthesizes a `defn`, and
   never emits an ellipsis/fake body token. PUBLIC: the ONE per-fn renderer —
   compact namespace cards, function menus/offers, `my.ns/functions`, and
   autocomplete export all consume this exact record."
  {:malli/schema [:=> [:catn [::fn-row ::fn-row]] :string]}
  [{:seon.fn/keys [sym arglists doc spec]}]
  (let [doc-1   (when (and doc (not (str/blank? doc)))
                  (-> (str/trim (first (str/split-lines doc)))
                      (str/replace "…" "...")
                      (soft-clip 78)))
        docpart (if doc-1 (str " — " (pr-str doc-1)) "")
        contract (callable-contract arglists spec)
        head    (str "fn " sym " — " contract docpart)]
    head))

(defn- render-one-ns-compact-row
  "Render one eager namespace row as a compact card."
  [ns-kw row schema-rows refers]
  (let [ns-str (name ns-kw)]
    (if-not row
      (ctx/ns-demarc ns-kw "; (not in db — not indexed)")
      (let [all-schemas (->> (:seon.schema/_ns row)
                             (filter (fn [{:seon.schema/keys [key]}]
                                       (= (namespace key) ns-str)))
                             (sort-by (comp str :seon.schema/key)))
            fns     (->> (:seon.fn/_ns row)
                         (filter callable-fn-row?)
                         (filter (fn [{:seon.fn/keys [sym]}]
                                   (or (nil? refers)
                                       (contains?
                                         refers
                                         (symbol (name (symbol sym)))))))
                         (sort-by :seon.fn/sym))
            schemas (if (nil? refers) all-schemas [])
            reg-lines (map compact-schema-line schemas)
            fn-lines  (map compact-fn-head fns)
            ref-blk   (some-> (ctx/referenced-schema-rows-block
                                {:seon.agent.ctx/seed-specs
                                 (cond-> (into [] (keep :seon.fn/spec) fns)
                                   (nil? refers)
                                   (into (keep :seon.schema/form) all-schemas))
                                 :seon.agent.ctx/own-keys
                                 (if (nil? refers)
                                   (into #{} (map :seon.schema/key) all-schemas)
                                   #{})
                                 :seon.agent.ctx/schema-rows
                                 (into schema-rows all-schemas)})
                              omit-runtime-object-tags)
            parts (cond-> []
                    (seq reg-lines)                 (into reg-lines)
                    ref-blk                         (conj ref-blk)
                    (and (or (seq reg-lines) ref-blk) (seq fn-lines)) (conj "")
                    (seq fn-lines)                  (into fn-lines))
            body  (if (seq parts)
                    (ctx/quote-lines (str/join "\n" parts)
                                     {:seon.agent.ctx/strip-markers? true})
                    "; (nothing indexed)")]
        (ctx/ns-demarc ns-kw body)))))
