(ns seon.program.plan
  "Acquire one fenced planning projection, then derive execution placement."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require [clojure.string :as str]
            [seon.capability]
            [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.program.edge :as edge]
            [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(schema/register!
 :seon.execution/placement
 [:enum :anywhere :constrained :unplannable])
(schema/register! :seon.execution/tier :keyword)
(schema/register!
 :seon.execution/root
 [:or :string :seon.db.protocol/ordinary-wire-value])
(schema/register! :seon.execution/roots [:vector :seon.execution/root])
(schema/register! :seon.execution/root-resolution ::edge/resolution)
(schema/register! :seon.execution/invocation :seon.db.protocol/ordinary-wire-value)
(schema/register! :seon.execution/basis-t :int)
(schema/register! :seon.execution/commit-id [:or :string :uuid])
(schema/register! :seon.execution/graph-digest :string)
(schema/register! :seon.execution/schema-fingerprint :int)
(schema/register! :seon.execution/edge-bundles [:map-of :string ::edge/bundle])
(schema/register!
 :seon.execution/tier-inventories
 [:map-of :seon.execution/tier :seon.execution.inventory/tier])
(schema/register!
 :seon.execution/selection-policy
 [:map {:closed true}
  [:seon.execution.selection/invoking-tier
   {:optional true} :seon.execution/tier]
  [:seon.execution.selection/handoff-tier
   {:optional true} :seon.execution/tier]])
(schema/register!
 :seon.execution/artifact-inventories
 [:or
  [:map {:closed true}
   [:seon.execution.inventory/availability [:= :unavailable]]
   [:seon.execution.inventory/unavailable-reason :keyword]]
  [:map {:closed true}
   [:seon.execution.inventory/availability [:= :available]]
   [:seon.execution.inventory/exports-by-tier
    [:map-of :seon.execution/tier [:set :string]]]
   [:seon.execution.inventory/digest :string]]])
(schema/register! :seon.execution/schema-projection :map)
(schema/register!
 :seon.execution/planning-projection
 [:map {:closed true}
  [:seon.execution/basis-t :seon.execution/basis-t]
  [:seon.execution/commit-id :seon.execution/commit-id]
  [:seon.execution/edge-bundles :seon.execution/edge-bundles]
  [:seon.execution/graph-digest :seon.execution/graph-digest]
  [:seon.execution/schema-projection :seon.execution/schema-projection]
  [:seon.execution/schema-fingerprint :seon.execution/schema-fingerprint]
  [:seon.execution/artifact-inventories
   :seon.execution/artifact-inventories]])
(schema/register!
 :seon.execution/plan-request
 [:map {:closed true}
  [:seon.execution/db-value :seon.db/db]
  [:seon.execution/roots :seon.execution/roots]
  [:seon.execution/root-resolution
   {:optional true} :seon.execution/root-resolution]
  [:seon.execution/invocation {:optional true} :seon.execution/invocation]
  [:seon.execution/tier-inventories :seon.execution/tier-inventories]
  [:seon.execution/selection-policy :seon.execution/selection-policy]
  [:seon.execution/planning-projection
   :seon.execution/planning-projection]])
(schema/register!
 :seon.execution/unresolved-edge
 [:map {:closed true}
  [:seon.execution/reason :keyword]
  [:seon.execution/from {:optional true} :string]
  [:seon.execution/target {:optional true} :string]
  [:seon.execution/steering :string]])
(schema/register!
 :seon.execution/schema-manifest
 [:map {:closed true}
  [:seon.execution/schema-keys [:set :qualified-keyword]]
  [:seon.execution/predicate-functions [:set :qualified-symbol]]
  [:seon.execution/attributes
   [:or [:= :all-at-basis] [:set :qualified-keyword]]]])
(schema/register!
 :seon.execution/capability-manifest
 [:map {:closed true}
  [:seon.execution/required-bindings [:set :string]]
  [:seon.execution/remote-bindings [:set :string]]
  [:seon.execution/effects [:map-of :string ::edge/effect]]
  [:seon.execution/native-leaves [:set :string]]
  [:seon.execution/artifact-exports [:set :string]]])
(schema/register! :seon.execution/cache-key [:vector :seon.db.protocol/ordinary-wire-value])
(schema/register!
 :seon.execution/plan
 [:map {:closed true}
  [:seon.execution/placement :seon.execution/placement]
  [:seon.execution/eligible-tiers [:set :seon.execution/tier]]
  [:seon.execution/selected-tier
   {:optional true} :seon.execution/tier]
  [:seon.execution/schema-manifest :seon.execution/schema-manifest]
  [:seon.execution/capability-manifest :seon.execution/capability-manifest]
  [:seon.execution/unresolved [:vector :seon.execution/unresolved-edge]]
  [:seon.execution/cache-key :seon.execution/cache-key]])
(schema/register!
 :seon.execution/core-error
 [:map {:closed true}
  [:seon.error/message :string]
  [:seon.error/kind [:= :core-bug]]
  [:seon.error/data :map]])

(def ^:private function-edge-query
  '[:find [(pull ?function
           [:seon.fn/sym
            :seon.program.edge/generation
            :seon.program.edge/calls
            :seon.program.edge/read-attributes
            :seon.program.edge/written-attributes
            :seon.program.edge/all-at-basis?
            :seon.program.edge/uncertainties
            {:seon.program.edge/terminal-refs
             [:seon.program.edge/terminal-symbol
              :seon.program.edge/effect
              :seon.program.edge/required-bindings
              :seon.program.edge/terminal-generation]}]) ...]
    :where [?function :seon.program.edge/generation]])

(def ^:private schema-query
  '[:find ?key ?form (pull ?tx ?provenance-pattern)
    :in $ ?provenance-pattern
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form ?tx]])

(def ^:private function-contract-query
  '[:find ?sym ?form (pull ?tx ?provenance-pattern)
    :in $ ?provenance-pattern
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form ?tx]])

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- core-error [message data]
  {:seon.error/message message
   :seon.error/kind :core-bug
   :seon.error/data data})

(defn- canonical [value]
  (cond
    (map? value) (mapv (fn [[k v]] [(canonical k) (canonical v)])
                       (sort-by (comp str key) value))
    (set? value) (mapv canonical (sort-by str value))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- digest [value]
  (content-hash/sha-256 (pr-str (canonical value))))

(defn- predicate-symbols-in [value]
  (cond
    (and (vector? value) (= :fn (first value)))
    (let [body (if (map? (second value)) (drop 2 value) (rest value))
          predicate (first body)]
      (if (qualified-symbol? predicate) #{predicate} #{}))
    (map? value)
    (into #{} (mapcat predicate-symbols-in) (concat (keys value) (vals value)))
    (coll? value) (into #{} (mapcat predicate-symbols-in) value)
    :else #{}))

(defn- schema-closure [projection roots]
  (loop [pending (seq roots) seen #{}]
    (if-let [schema-key (first pending)]
      (if (contains? seen schema-key)
        (recur (next pending) seen)
        (recur (concat (get-in projection
                               [:seon.schema.projection/schema-dependencies
                                schema-key])
                       (next pending))
               (conj seen schema-key)))
      seen)))

(defn- tier-serves? [inventory binding]
  (or (contains? (:seon.execution.inventory/bindings inventory) binding)
      (contains? (:seon.execution.inventory/remote-bindings inventory)
                 binding)))

(defn- terminal-tiers [tier-inventories terminal artifacts]
  (let [target (::edge/terminal-symbol terminal)
        effect (::edge/effect terminal)
        bindings (::edge/required-bindings terminal)
        package-tier (cond
                       (str/starts-with? target "seon.packages.js.") :bun
                       (str/starts-with? target "seon.packages.jvm.") :jvm
                       :else nil)
        serving
        (into #{}
              (keep (fn [[tier inventory]]
                      (when (and
                             (or (nil? package-tier)
                                 (= tier package-tier)
                                 (every?
                                  #(contains?
                                    (:seon.execution.inventory/remote-bindings
                                     inventory) %)
                                  bindings))
                             (every? #(tier-serves? inventory %) bindings))
                        tier)))
              tier-inventories)
        trusted-pure
        (into #{}
              (keep (fn [[tier inventory]]
                      (when (every?
                             #(contains?
                               (:seon.execution.inventory/pure-bindings
                                inventory) %)
                             bindings)
                        tier)))
              tier-inventories)]
    (cond
      package-tier
      {:tiers serving
       :restriction? true
       :unresolved
       (when (empty? serving)
         {:seon.execution/reason :missing-capability-binding
          :seon.execution/target target
          :seon.execution/steering
          "Provision this native package binding locally or through its remote seam."})}
      (not= :pure effect)
      {:tiers serving
       :restriction? true
       :unresolved
       (when (empty? serving)
         {:seon.execution/reason :missing-capability-binding
          :seon.execution/target target
          :seon.execution/steering
          "Provision every required capability binding on an eligible tier."})}
      (seq trusted-pure) {:tiers trusted-pure
                          :restriction?
                          (not= trusted-pure (set (keys tier-inventories)))}
      (= :unavailable
         (:seon.execution.inventory/availability artifacts))
      {:tiers #{}
       :restriction? true
       :unresolved
       {:seon.execution/reason :missing-artifact-export-inventory
        :seon.execution/target target
        :seon.execution/steering
        "Acquire the artifact export inventory for this compiled-only terminal."}}
      :else
      (let [exports (:seon.execution.inventory/exports-by-tier artifacts)
            tiers (into #{} (keep (fn [[tier exported]]
                                    (when (contains? exported target) tier)))
                        exports)]
        {:tiers tiers
         :restriction? true
         :artifact-export target
         :unresolved
         (when (empty? tiers)
           {:seon.execution/reason :missing-artifact-export
            :seon.execution/target target
            :seon.execution/steering
            "Compile and publish this terminal in a claimant artifact."})}))))

(declare unresolved)

(defn- definition-function-symbol [form resolution]
  (when (and (seq? form)
             (symbol? (first form))
             (contains? #{"defn" "defn-"} (name (first form)))
             (symbol? (second form)))
    (let [declared (second form)]
      (str
       (if (qualified-symbol? declared)
         declared
         (symbol (str (::edge/namespace resolution)) (name declared)))))))

(defn- specialize-roots [roots resolution]
  (reduce-kv
   (fn [{::keys [pending bundles unresolved resolution] :as state}
        index root]
     (cond
       (string? root)
       (update state ::pending conj root)

       (nil? resolution)
       (update state ::unresolved conj
               (unresolved
                :missing-root-resolution nil nil
                "Supply the retained P1 namespace resolution for in-memory form roots."))

       :else
       (let [defined-symbol (definition-function-symbol root resolution)
             function-symbol
             (or defined-symbol
                 (str "seon.execution.invocation/root-"
                      index "-" (digest root)))
             accepted-form
             (if defined-symbol
               root
               (list 'defn
                     (symbol (str "root-" index))
                     []
                     root))
             bundle
             (edge/analyze-function
              {::edge/function-symbol function-symbol
               ::edge/form accepted-form
               ::edge/resolution resolution})]
         (cond-> (-> state
                     (update ::pending conj function-symbol)
                     (assoc-in [::bundles function-symbol] bundle))
           defined-symbol
           (update-in [::resolution ::edge/current-vars]
                      conj (symbol (name (second root))))))))
   {::pending []
    ::bundles {}
    ::unresolved
    (cond-> []
      (empty? roots)
      (conj
       (unresolved
        :no-roots nil nil
        "Provide at least one persisted function or in-memory form root.")))
    ::resolution resolution}
   roots))

(defn- cache-key [{:seon.execution/keys
                    [db-value roots root-resolution invocation tier-inventories
                     selection-policy planning-projection]
                   :as request}]
  [[(:db-name db-value) (:store-id db-value)]
   (:t db-value)
   (:datahike/commit-id db-value)
   (:seon.execution/graph-digest planning-projection)
   (:seon.execution/schema-fingerprint planning-projection)
   (digest tier-inventories)
   (digest (:seon.execution/artifact-inventories planning-projection))
   (digest [roots
            root-resolution
            selection-policy
            (if (contains? request :seon.execution/invocation)
              [:present invocation]
              :absent)])])

(defn- unresolved [reason from target steering]
  (cond-> {:seon.execution/reason reason
           :seon.execution/steering steering}
    from (assoc :seon.execution/from from)
    target (assoc :seon.execution/target target)))

(defn plan-execution
  "Derive one exact execution placement and both manifests from fenced data."
  {:malli/schema
   [:=> [:cat :seon.execution/plan-request]
    [:or :seon.execution/plan :seon.execution/core-error]]}
  [{:seon.execution/keys
    [db-value roots root-resolution tier-inventories selection-policy
     planning-projection] :as request}]
  (let [basis (:seon.execution/basis-t planning-projection)
        commit (:seon.execution/commit-id planning-projection)
        schema-projection
        (:seon.execution/schema-projection planning-projection)
        fingerprint
        (:seon.execution/schema-fingerprint planning-projection)]
    (if (or (not= basis (:t db-value))
            (not= commit (:datahike/commit-id db-value))
            (not= fingerprint
                  (:seon.schema.projection/fingerprint schema-projection)))
      (core-error
       "Planning projection does not match the requested database value."
       {:seon.execution/request-basis-t (:t db-value)
        :seon.execution/projection-basis-t basis
        :seon.execution/request-commit-id (:datahike/commit-id db-value)
        :seon.execution/projection-commit-id commit})
      (let [specialized (specialize-roots roots root-resolution)
            bundles (merge (:seon.execution/edge-bundles planning-projection)
                           (::bundles specialized))
            artifacts
            (:seon.execution/artifact-inventories planning-projection)
            all-tiers (set (keys tier-inventories))
            initial
            {:pending (seq (::pending specialized))
             :seen #{}
             :eligible (if (seq roots) all-tiers #{})
             :restriction? false
             :schema-roots #{}
             :read-attrs #{}
             :write-attrs #{}
             :all-at-basis? false
             :predicates #{}
             :required-bindings #{}
             :remote-bindings #{}
             :effects {}
             :native-leaves #{}
             :artifact-exports #{}
             :unresolved (vec (::unresolved specialized))}
            folded
            (loop [state initial]
              (if-let [function-symbol (first (:pending state))]
                (if (contains? (:seen state) function-symbol)
                  (recur (update state :pending next))
                  (if-let [bundle (get bundles function-symbol)]
                    (let [terminal-by-symbol
                          (into {} (map (juxt ::edge/terminal-symbol identity))
                                (::edge/terminals bundle))
                          calls (::edge/calls bundle)
                          bundle-schema-roots
                          (into
                           (get-in schema-projection
                                   [:seon.schema.projection/function-dependencies
                                    (symbol function-symbol)])
                           (concat (::edge/read-attributes bundle)
                                   (::edge/written-attributes bundle)))
                          bundle-schema-keys
                          (schema-closure schema-projection bundle-schema-roots)
                          bundle-predicates
                          (into #{}
                                (mapcat
                                 #(predicate-symbols-in
                                   (get-in schema-projection
                                           [:seon.schema.projection/forms %])))
                                bundle-schema-keys)
                          terminals
                          (concat
                           (keep (fn [target]
                                   (when-not (contains? bundles target)
                                     (get terminal-by-symbol target)))
                                 calls)
                           (when-let [self-terminal
                                      (get terminal-by-symbol function-symbol)]
                             [self-terminal]))
                          missing
                          (remove #(or (contains? bundles %)
                                       (contains? terminal-by-symbol %))
                                  calls)
                          state
                          (-> state
                              (update :pending #(concat
                                                (filter (partial contains?
                                                                 bundles)
                                                        calls)
                                                (keep (fn [predicate]
                                                        (let [target
                                                              (str predicate)]
                                                          (when
                                                           (contains? bundles
                                                                      target)
                                                            target)))
                                                      bundle-predicates)
                                                (next %)))
                              (update :seen conj function-symbol)
                              (update :schema-roots into bundle-schema-roots)
                              (update :predicates into bundle-predicates)
                              (update :read-attrs into
                                      (::edge/read-attributes bundle))
                              (update :write-attrs into
                                      (::edge/written-attributes bundle))
                              (update :all-at-basis? #(or %
                                                        (::edge/all-at-basis?
                                                         bundle)))
                              (update :unresolved into
                                      (map #(unresolved
                                             %
                                             function-symbol nil
                                             "Specialize this invocation or close the dynamic program edge.")
                                           (::edge/uncertainties bundle)))
                              (update :unresolved into
                                      (map #(unresolved
                                             :missing-terminal-descriptor
                                             function-symbol %
                                             "Persist a declared terminal descriptor for this call.")
                                           missing)))
                          state
                          (reduce
                           (fn [result terminal]
                             (let [{:keys [tiers restriction? unresolved
                                          artifact-export]}
                                   (terminal-tiers tier-inventories terminal
                                                   artifacts)
                                   bindings (::edge/required-bindings terminal)
                                   remote
                                   (into #{}
                                         (filter
                                          (fn [binding]
                                            (some
                                             #(contains?
                                               (:seon.execution.inventory/remote-bindings
                                                %)
                                               binding)
                                             (vals tier-inventories))))
                                         bindings)]
                               (cond-> result
                                 true (update :eligible #(set
                                                         (filter tiers %)))
                                 restriction? (assoc :restriction? true)
                                 true (update :required-bindings into bindings)
                                 true (update :remote-bindings into remote)
                                 true (assoc-in [:effects
                                                 (::edge/terminal-symbol terminal)]
                                                (::edge/effect terminal))
                                 (str/starts-with?
                                  (::edge/terminal-symbol terminal)
                                  "seon.packages.")
                                 (update :native-leaves conj
                                         (::edge/terminal-symbol terminal))
                                 artifact-export
                                 (update :artifact-exports conj artifact-export)
                                 unresolved
                                 (update :unresolved conj
                                         (assoc unresolved
                                                :seon.execution/from
                                                function-symbol)))))
                           state terminals)]
                      (recur state))
                    (recur
                     (-> state
                         (update :pending next)
                         (update :seen conj function-symbol)
                         (update :unresolved conj
                                 (unresolved
                                  :missing-root-bundle nil function-symbol
                                  "Persist the root function edge bundle before planning."))))))
                state))
            schema-keys (schema-closure schema-projection (:schema-roots folded))
            forms (:seon.schema.projection/forms schema-projection)
            predicates (:predicates folded)
            predicate-missing
            (remove #(or (contains? bundles (str %))
                         (contains?
                          (:seon.schema.projection/pure-predicate-symbols
                           schema-projection)
                          %))
                    predicates)
            folded
            (update folded :unresolved into
                    (map #(unresolved
                           :predicate-function-not-in-graph nil (str %)
                           "Persist the predicate function graph before planning.")
                         predicate-missing))
            eligible (:eligible folded)
            unresolved-values
            (cond-> (vec (:unresolved folded))
              (and (empty? eligible)
                   (empty? (:unresolved folded)))
              (conj
               (unresolved
                :no-eligible-tier nil nil
                "Publish a tier inventory that can execute every reachable terminal.")))
            placement
            (cond
              (or (seq unresolved-values) (empty? eligible)) :unplannable
              (:restriction? folded) :constrained
              :else :anywhere)
            invoking-tier
            (:seon.execution.selection/invoking-tier selection-policy)
            handoff-tier
            (:seon.execution.selection/handoff-tier selection-policy)
            selected-tier
            (when-not (= :unplannable placement)
              (cond
                (contains? eligible invoking-tier) invoking-tier
                (contains? eligible handoff-tier) handoff-tier
                :else nil))]
        (cond->
         {:seon.execution/placement placement
          :seon.execution/eligible-tiers eligible
         :seon.execution/schema-manifest
         {:seon.execution/schema-keys schema-keys
          :seon.execution/predicate-functions predicates
          :seon.execution/attributes
          (if (:all-at-basis? folded)
            :all-at-basis
            (into (:read-attrs folded) (:write-attrs folded)))}
         :seon.execution/capability-manifest
         {:seon.execution/required-bindings (:required-bindings folded)
          :seon.execution/remote-bindings (:remote-bindings folded)
          :seon.execution/effects (:effects folded)
          :seon.execution/native-leaves (:native-leaves folded)
          :seon.execution/artifact-exports (:artifact-exports folded)}
         :seon.execution/unresolved unresolved-values
          :seon.execution/cache-key (cache-key request)}
          selected-tier
          (assoc :seon.execution/selected-tier selected-tier))))))

(defn manifest-covered-by-projection?
  "True when a plan manifest is covered by the acquired full projection."
  {:malli/schema
   [:=> [:cat :seon.execution/schema-manifest
         :seon.execution/schema-projection
         :seon.execution/schema-fingerprint]
    :boolean]}
  [manifest projection expected-fingerprint]
  (let [forms (:seon.schema.projection/forms projection)
        schema-keys (:seon.execution/schema-keys manifest)
        attributes (:seon.execution/attributes manifest)
        predicates (:seon.execution/predicate-functions manifest)
        projected-predicates
        (into (:seon.schema.projection/pure-predicate-symbols projection)
              (mapcat predicate-symbols-in)
              (vals forms))]
    (and (= expected-fingerprint
            (:seon.schema.projection/fingerprint projection))
         (every? #(contains? forms %) schema-keys)
         (or (= :all-at-basis attributes)
             (every? #(contains? forms %) attributes))
         (every? #(contains? projected-predicates %) predicates))))

(defn ^{:async #?(:cljs true :clj false)
        :seon.capability/effect :read}
  acquire-planning-projection
  "Acquire the complete planning projection at one immutable database value."
  {:malli/schema
   [:=> [:cat :seon.db/db]
    [:or :seon.execution/planning-projection
     :seon.execution/core-error]]}
  [database]
  (try
    (let [function-rows
          (await
           (db/query {::db/db database ::db/query function-edge-query}))
          schema-rows
          (await
           (db/query
            {::db/db database
             ::db/query schema-query
             ::db/args [schema/asserting-transaction-provenance-pattern]}))
          contract-rows
          (await
           (db/query
            {::db/db database
             ::db/query function-contract-query
             ::db/args [schema/asserting-transaction-provenance-pattern]}))]
      (if-let [failure (first (filter error-value?
                                     [function-rows schema-rows contract-rows]))]
        (core-error "Planning projection acquisition failed."
                    {:seon.execution/cause failure})
        (let [bundles (edge/reconstruct-bundles function-rows)
              projection
              (schema/projection-from-rows
               {:seon.schema/schema-rows schema-rows
                :seon.schema/function-contract-rows contract-rows})]
          {:seon.execution/basis-t (:t database)
           :seon.execution/commit-id (:datahike/commit-id database)
           :seon.execution/edge-bundles
           (into {} (map (juxt ::edge/function-symbol identity)) bundles)
           :seon.execution/graph-digest
           (edge/program-graph-digest bundles)
           :seon.execution/schema-projection projection
           :seon.execution/schema-fingerprint
           (:seon.schema.projection/fingerprint projection)
           :seon.execution/artifact-inventories
           {:seon.execution.inventory/availability :unavailable
            :seon.execution.inventory/unavailable-reason
            :missing-artifact-export-inventory}})))
    (catch #?(:clj Throwable :cljs :default) throwable
      (core-error
       "Planning projection acquisition failed."
       {:seon.execution/cause
        (or #?(:clj (ex-message throwable)
               :cljs (.-message throwable))
            (str throwable))}))))
