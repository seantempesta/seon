(ns seon.render.walk
  "The one bounded neighbourhood traversal.

  It follows explicit forward and reverse refs from one immutable database
  value and emits flat render-call units. Renderer selection belongs only to
  `seon.render`: an explicit producer, a unique contract fit in an explicitly
  owning namespace, a schema property, then the structural floor. This
  namespace derives ownership only from real data or traversal refs; it never
  reads keyword text or the viewing agent's namespace.

  There is no public recursive node envelope and no Hiccup marker walker.

  DISTANCE IS SPENT ON CONNECTIONS, one hop each: the root is rendered at
  the requested distance, a neighbour
  at distance-1, and a walk with no hops left follows nothing. Distance
  is an ARGUMENT to the renderer and never a property of it — this
  namespace puts it on the unit under `:seon.render/distance` and the
  renderer MAY read it. \"Distance 0 renders the name only\" is therefore
  a fact about the good default renderers, not a rule enforced here: a
  renderer decides what it does with the budget it was handed, which is
  what makes the convention compositional rather than imposed.

  NEIGHBOURS RUN BOTH WAYS, and that is not a special case. An agent
  holds one forward ref (its open run) and is POINTED AT by everything
  that matters — its runs, the messages sent to it, the errors recorded
  against it. A traversal that followed only forward refs would render an
  agent as an almost empty entity, so the root selector pulls both directions
  from the same database value. Reverse neighbours retain the newest values
  and bounded by the SAME `:seon.sci.admit/caps` collection dial the eval
  door and the generic panel already use; a second width dial here would
  be a magic number and would drift from the first.

  TOTAL, because the prompt path is an error path. Every selected renderer
  failure is a flat value and an undeclared value reaches the floor. Node and
  distance budgets elide explicitly.

  Crash walk: pure over a database value. Nothing here opens, commits or
  holds anything."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.ai.tokens :as tokens]
            [seon.effect :as effect]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The connections
;;; ---------------------------------------------------------------------------

(defn- installed-attributes
  "Concrete installed attributes and their Datahike properties, ordered."
  [database]
  (into (sorted-map-by #(compare (str %1) (str %2)))
        (filter (comp keyword? key))
        (:schema database)))

(defn- reverse-attribute
  [attribute]
  (keyword (namespace attribute) (str "_" (name attribute))))

(defn- selector-key
  [attribute width]
  [attribute :limit (inc (long width))])

(defn root-selector
  "A concrete bidirectional pull selector for an agent-root distance.

  Every installed scalar attribute is enumerated. Every installed ref is an
  explicit forward and reverse subpattern, so Datahike records the canonical
  stored ref in the dependency plan and never widens component expansion to
  `:all`. The pull asks for one value beyond the collection cap so the walk can
  emit an exact elision observation without a second read."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.render/distance
         :seon.sci.admit/caps]
    :seon.db/pull-selector]}
  [database distance caps]
  (let [installed (installed-attributes database)
        ref-attributes (into []
                             (keep (fn [[attribute properties]]
                                     (when (= :db.type/ref
                                              (:db/valueType properties))
                                       attribute)))
                             installed)
        identity-attributes (into []
                                  (keep (fn [[attribute properties]]
                                          (when (= :db.unique/identity
                                                   (:db/unique properties))
                                            attribute)))
                                  installed)
        scalar-attributes (into []
                                (keep (fn [[attribute properties]]
                                        (when (and (not= :db/id attribute)
                                                   (not= :db.type/ref
                                                         (:db/valueType
                                                          properties)))
                                          attribute)))
                                installed)
        width (:seon.config.eval.result/max-collection caps)
        leaf (into [:db/id] identity-attributes)]
    (letfn [(selector-at [remaining]
              (let [nested (if (pos? remaining)
                             (selector-at (dec remaining))
                             leaf)
                    asked-for-nested
                    (if (and (= 1 remaining)
                             (contains? installed
                                        :seon.cluster.message/from)
                             (contains? installed
                                        :seon.cluster.run/trigger))
                      (conj nested
                            {(selector-key :seon.cluster.run/_trigger width)
                             leaf})
                      nested)]
                (into (into [:db/id] scalar-attributes)
                      (concat
                       (map (fn [attribute]
                              {(selector-key attribute width) nested})
                            ref-attributes)
                       (map (fn [attribute]
                              {(selector-key (reverse-attribute attribute)
                                             width)
                               (if (= :seon.cluster.message/from attribute)
                                 asked-for-nested
                                 nested)})
                            ref-attributes)))))]
      (selector-at (long distance)))))

(defn- installed-identity-attributes
  [database]
  (let [installed (installed-attributes database)
        declared (->> (vals (:seon.schema.projection/shape-rows
                             (schema/current-projection)))
                      (keep :seon.entity/id-attr)
                      distinct (filter #(contains? installed %))
                      (sort-by str) vec)
        declared-set (set declared)
        database-identities
        (->> installed
             (keep (fn [[attribute properties]]
                     (when (= :db.unique/identity (:db/unique properties))
                       attribute)))
             (remove declared-set)
             (sort-by str))]
    (into declared database-identities)))

(defn- stable-lookup
  [id-attributes entity]
  (or (some (fn [attribute]
              (when (contains? entity attribute)
                [attribute (get entity attribute)]))
            id-attributes)
      (:db/id entity)))

(defn- pulled-values
  [value]
  (cond
    (map? value) [value]
    (sequential? value) value
    :else []))

(defn- ref-identity
  [id-attributes entity]
  (select-keys entity (into [:db/id] id-attributes)))

(defn- shallow-entity
  [entity ref-attributes id-attributes]
  (reduce
   (fn [result attribute]
     (if-let [value (get result attribute)]
       (assoc result attribute
              (cond
                (map? value) (ref-identity id-attributes value)
                (sequential? value)
                (mapv #(ref-identity id-attributes %) value)
                :else value))
       result))
   (apply dissoc entity (map reverse-attribute ref-attributes))
   ref-attributes))

(defn- acquisition-members
  [database root distance caps]
  (let [installed (installed-attributes database)
        refs (into []
                   (keep (fn [[attribute properties]]
                           (when (= :db.type/ref (:db/valueType properties))
                             attribute)))
                   installed)
        identities (installed-identity-attributes database)
        width (long (:seon.config.eval.result/max-collection caps))]
    (letfn [(connection-values [entity attribute reverse?]
              (let [display (if reverse?
                              (reverse-attribute attribute)
                              attribute)]
                (pulled-values (get entity display))))
            (visit [state entity remaining path reached-by]
              (if-not (:db/id entity)
                state
                (let [lookup (stable-lookup identities entity)]
                  (if (contains? (:seon.render.walk/members state) lookup)
                    state
                    (let [state
                          (-> state
                              (assoc-in [:seon.render.walk/members lookup]
                                        (cond->
                                         {:seon.render.walk/lookup lookup
                                          :seon.render.walk/eid (:db/id entity)
                                          :seon.render.walk/path path
                                          :seon.render.walk/found-depth
                                          (- (long distance) remaining)
                                          :seon.render/value
                                          (shallow-entity entity refs identities)}
                                          reached-by
                                          (assoc :seon.render.walk/attribute
                                                 reached-by)))
                              (update :seon.render.walk/order conj lookup))
                          connection-attributes
                          (if (:seon.ns/name entity)
                            [[:seon.ns/requires false]
                             [:seon.ns/requires true]]
                            (concat (map vector refs (repeat false))
                                    (map vector refs (repeat true))))
                          connections
                          (into []
                                (mapcat
                                 (fn [[attribute reverse?]]
                                   (let [values (connection-values
                                                 entity attribute reverse?)
                                         values (if reverse?
                                                  (sort-by :db/id > values)
                                                  values)
                                         kept (take width values)
                                         kept (if reverse?
                                                (sort-by :db/id kept)
                                                kept)
                                         elided? (> (count values) width)]
                                     (cond->
                                      (mapv (fn [child]
                                              {:seon.render.walk/attribute
                                               attribute
                                               :seon.render.walk/lookup
                                               (stable-lookup identities child)
                                               :seon.render.walk/pulled child})
                                            kept)
                                       elided?
                                       (conj
                                        {:seon.render.walk/attribute attribute
                                         :seon.error/value
                                         {:seon.error/kind ::elided
                                          :seon.error/message
                                          (str "elided additional "
                                               (when reverse? "reverse ")
                                               attribute " connection"
                                               "s"
                                               " at the configured collection cap")
                                          :seon.error/data
                                          {:seon.render.walk/attribute
                                           attribute}}}))))
                                 connection-attributes))]
                      (if (or (pos? remaining)
                              (and (zero? remaining)
                                   (= :seon.cluster.message/from reached-by)))
                        (reduce-kv
                         (fn [result index connection]
                           (if-let [child
                                    (when (or (pos? remaining)
                                              (= :seon.cluster.run/trigger
                                                 (:seon.render.walk/attribute
                                                  connection)))
                                      (:seon.render.walk/pulled connection))]
                             (visit result child (max 0 (dec remaining))
                                    (conj path :seon.render.walk/neighbours
                                          index)
                                    (:seon.render.walk/attribute connection))
                             result))
                         (assoc-in state
                                   [:seon.render.walk/members lookup
                                    :seon.render.walk/connections]
                                   (mapv #(dissoc % :seon.render.walk/pulled)
                                         connections))
                         connections)
                        (assoc-in state
                                  [:seon.render.walk/members lookup
                                   :seon.render.walk/connections]
                                  (mapv #(dissoc % :seon.render.walk/pulled)
                                        connections))))))))]
      (if (map? root)
        (visit {:seon.render.walk/members {}
                :seon.render.walk/order []}
               root (long distance) [] nil)
        {:seon.render.walk/members {}
         :seon.render.walk/order []}))))

(defn root-acquisition
  "Pull and index one agent-root neighbourhood by stable entity identity.

  This performs exactly one database read. Its member values contain only
  each entity's own attributes and direct ref identities; reverse/nested
  structure supplies membership and paths without making an ancestor appear
  changed when only a descendant changed."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :map]}
  [{database :seon.db/db
    lookup :seon.render.walk/lookup
    caps :seon.sci.admit/caps
    :as request}]
  (let [distance (long (get request :seon.render/distance 1))
        selector (root-selector database distance caps)
        root (db/pull database selector lookup)]
    (merge {:seon.render.walk/selector selector
            :seon.render.walk/root root}
           (acquisition-members database root distance caps))))

(defn membership-diff
  "Changed, added, and removed members between two root acquisitions."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [before after]
  (let [before-members (:seon.render.walk/members before)
        after-members (:seon.render.walk/members after)
        before-order (:seon.render.walk/order before)
        after-order (:seon.render.walk/order after)
        added? #(not (contains? before-members %))
        removed? #(not (contains? after-members %))
        changed? #(and (contains? before-members %)
                       (not= (:seon.render/value (get before-members %))
                             (:seon.render/value (get after-members %))))]
    {:seon.render.walk/changed
     (into [] (comp (filter changed?) (map after-members)) after-order)
     :seon.render.walk/added
     (into [] (comp (filter added?) (map after-members)) after-order)
     :seon.render.walk/removed
     (into [] (comp (filter removed?) (map before-members)) before-order)}))

(defn- forward-refs
  "`[attribute eid]` for every ref value the entity itself carries."
  [entity]
  (into []
        (mapcat (fn [[attribute value]]
                  (cond
                    (and (map? value) (contains? value :db/id))
                    [[attribute (:db/id value)]]

                    (sequential? value)
                    (keep (fn [element]
                            (when (and (map? element) (contains? element :db/id))
                              [attribute (:db/id element)]))
                          value)

                    :else nil)))
        (sort-by (comp str key) (dissoc entity :db/id :seon.db/db))))

(defn- namespace-render-distance
  [root-namespace-eid eid entity traversal-hops]
  (if (contains? entity :seon.ns/name)
    (if (= root-namespace-eid eid) 1 2)
    traversal-hops))

(defn owning-namespace
  "The one namespace explicitly named by the value or one of its direct refs.

  This deliberately does not inspect keyword text. An entity with no explicit
  namespace edge has no owning namespace at this boundary and therefore falls
  through to its matching schema property and the structural floor."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :map]
    [:or :seon.render/namespace :nil]]}
  [_database entity]
  (let [names (cond-> (into #{}
                            (keep (fn [[_attribute value]]
                                    (cond
                                      (map? value) (:seon.ns/name value)
                                      (sequential? value)
                                      (some :seon.ns/name value))))
                            entity)
                (:seon.ns/name entity) (conj (:seon.ns/name entity)))]
    (when (= 1 (count names))
      (first names))))

(defn- acquired-namespace-name
  [acquisition member]
  (let [eid->member (into {}
                         (map (juxt :seon.render.walk/eid identity))
                         (vals (:seon.render.walk/members acquisition)))
        entity (:seon.render/value member)
        names (cond->
               (into #{}
                     (keep (fn [[_attribute target-eid]]
                             (get-in eid->member
                                     [target-eid :seon.render/value
                                      :seon.ns/name])))
                     (forward-refs entity))
                (:seon.ns/name entity) (conj (:seon.ns/name entity)))]
    (when (= 1 (count names)) (first names))))

(defn- acquired-root-namespace-eid
  [acquisition]
  (let [root (get-in acquisition
                     [:seon.render.walk/members
                      (first (:seon.render.walk/order acquisition))])
        namespace-ref (get-in root [:seon.render/value
                                    :seon.cluster.agent/namespace])]
    (when (map? namespace-ref) (:db/id namespace-ref))))

(defn neighborhood
  "Render the root acquisition's stable members without further discovery."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :seon.render.walk/units]}
  [{database :seon.db/db
    caps :seon.sci.admit/caps
    ctx :seon.sci.eval/ctx
    output :seon.render/output
    lookup :seon.render.walk/lookup
    :as request}]
  (let [reusable-projection (or (:seon.schema/projection ctx) {})
        projection (schema/call-with-projection
                    reusable-projection
                    #(schema/projection-from-database database
                                                      reusable-projection))]
    (schema/call-with-projection
     projection
     (fn []
       (let [distance (long (get request :seon.render/distance 1))
             acquisition (or (:seon.render.walk/root-acquisition request)
                             (root-acquisition request))
             members (:seon.render.walk/members acquisition)
             order (:seon.render.walk/order acquisition)
             root-namespace-eid (acquired-root-namespace-eid acquisition)
             node-limit (long (:seon.config.eval.result/max-nodes caps))]
         (if (empty? order)
           [{:seon.render.walk/lookup lookup
             :seon.render/distance distance
             :seon.render.walk/path []
             :seon.render.walk/found-depth 0
             :seon.error/value
             {:seon.error/kind ::no-such-entity
              :seon.error/message
              (str "Nothing in the database answers to " (pr-str lookup) ".")}}]
           (into []
                 (comp
                  (take node-limit)
                  (map
                   (fn [member-lookup]
                     (let [member (get members member-lookup)
                           entity (:seon.render/value member)
                           depth (:seon.render.walk/found-depth member)
                           remaining (max 0 (- distance depth))
                           render-distance
                           (namespace-render-distance
                            root-namespace-eid (:seon.render.walk/eid member)
                            entity remaining)
                           owner (acquired-namespace-name acquisition member)
                           render-request
                           (cond-> (assoc request
                                          :seon.render/value entity
                                          :seon.render/distance render-distance
                                          :seon.render.call/id
                                          [output member-lookup render-distance])
                             (:seon.render.walk/attribute member)
                             (assoc :seon.render.walk/attribute
                                    (:seon.render.walk/attribute member))
                             owner (assoc :seon.render/namespace owner))
                           rendered (render/render-call render-request)
                           failure (when (:seon.error/kind rendered) rendered)
                           failure-outcome
                           (when (and failure owner)
                             (render/renderer-failure
                              {:seon.db/db database
                               :seon.render/namespace owner
                               :seon.error/value failure}))]
                       (cond->
                        (-> member
                            (dissoc :seon.render.walk/eid
                                    :seon.render.walk/connections
                                    :seon.render/value)
                            (assoc :seon.render/distance render-distance))
                         failure
                         (assoc :seon.error/value failure
                                :seon.render/output
                                (or (get failure-outcome output)
                                    (if (= output :seon.render/html)
                                      [:div {:class "seon-render-unavailable"}
                                       "renderer unavailable"]
                                      "Renderer unavailable.")))
                         (seq (:seon.db/tx-data failure-outcome))
                         (assoc :seon.db/tx-data
                                (:seon.db/tx-data failure-outcome))
                         (not failure)
                         (assoc :seon.render/output rendered))))))
                 order)))))))

;;; ---------------------------------------------------------------------------
;;; Assembly — the ai kind
;;; ---------------------------------------------------------------------------

(defn prose
  "A rendered neighbourhood as text. THE `:seon.render/ai` ASSEMBLY.

  Each unit gets one compact comment carrying its depth and output. A
  branch root also retains the literal `:branch` path accepted by
  `seon.render/walk`, so shortening presentation never removes the drill
  handle. A node that rendered nothing contributes nothing — omission is
  nil-punning here exactly as it is in a block, so a family with nothing to
  say costs no tokens.

  A node carrying a flat error contributes ITS MESSAGE, because a reader
  told nothing about a neighbour that exists would reason from a gap it
  cannot see. That is the same rule `seon.cluster.prompt` applies to a
  failed block."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.render.walk/units]
     [:maybe :string]]
    [:=>
     [:cat
      :seon.db/database-value
      :seon.render.walk/units
      [:map
       [:seon.render.walk/branch
        {:optional true}
        [:vector [:or :keyword :int]]]]]
     [:maybe :string]]]}
  ([db rendered-units]
   (prose db rendered-units {}))
  ([db rendered-units {requested-branch :seon.render.walk/branch}]
   (letfn [(provenance [unit]
            (or (get-in unit [:seon.error/value :seon.error/kind])
                (:seon.render.walk/lookup unit)))
          (within-branch? [path]
            (or (nil? requested-branch)
                (and (<= (count requested-branch) (count path))
                     (= requested-branch
                        (subvec path 0 (count requested-branch))))))
          (elision-unit? [unit]
            (= ::elided
               (get-in unit [:seon.error/value :seon.error/kind])))
          (unit-lines [unit]
            (let [path (:seon.render.walk/path unit)
                  depth (:seon.render.walk/found-depth unit)
                  unit-provenance (provenance unit)
                  failure (:seon.error/value unit)
                  output (:seon.render/output unit)
                  text (cond
                         (string? output) output
                         (some? output) (pr-str output)
                         failure (:seon.error/message failure))]
              {:seon.render.walk/lines
               [(str ";; d" depth " · " (pr-str unit-provenance)) text]
               :seon.render.walk/branch-metadata
               (when (= path (:seon.render.walk/branch unit))
                 (str ";; unit=" (pr-str unit-provenance)
                      " branch=" (pr-str path)))}))]
     (let [root-unit (first (sort-by #(count (:seon.render.walk/path %))
                                     rendered-units))
           root (:seon.render.walk/lookup root-unit)
           requested-depth (:seon.render/distance root-unit)
           options (cond-> {:root root :depth requested-depth}
                     (some? requested-branch)
                     (assoc :branch requested-branch))
           header (str ";; (seon.render/walk " (pr-str options) ")"
                       " => root=" (pr-str root)
                       " depth=" requested-depth
                       (when (some? requested-branch)
                         (str " branch=" (pr-str requested-branch))))
           ordered (->> rendered-units
                        (filter (comp within-branch?
                                      :seon.render.walk/path)))
           elisions (filter elision-unit? ordered)
           visible (remove elision-unit? ordered)
           rendered-visible (map unit-lines visible)
           agent-id
           (when (= :seon.cluster.agent/id (first root))
             (second root))
           elision-summary
           (when (seq elisions)
             (let [former-noise (str/join "\n" (keep :seon.render/output
                                                       elisions))]
               {:seon.render.walk/guidance
                (str ";; Some branches are elided · inspect with "
                     "(seon.render/walk "
                     (pr-str {:root root :depth (inc requested-depth)}) ")")
                :seon.render.walk/metadata
                (str ";; branches-elided=" (count elisions)
                     " elided-tokens=" (tokens/estimate former-noise))}))
           lines (concat [header]
                         (some-> elision-summary
                                 :seon.render.walk/guidance
                                 vector)
                         (mapcat :seon.render.walk/lines rendered-visible)
                         [";; Volatile context metadata"]
                         (some-> elision-summary
                                 :seon.render.walk/metadata
                                 vector)
                         (when agent-id
                           [(effect/context-suffix db agent-id)])
                         (keep :seon.render.walk/branch-metadata
                               rendered-visible))
           text (str/join "\n" lines)]
       (when-not (str/blank? text) text)))))

;;; ---------------------------------------------------------------------------
;;; The agent's history
;;; ---------------------------------------------------------------------------

(def ^:private initially-introduced-symbols
  #{'db/pull 'db/q 'my.message/read 'my.message/inbox})

(defn- unquoted
  [form]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(defn form-introductions
  "Symbols introduced by one parsed history form."
  {:malli/schema [:=> [:cat [:or :seon.render/form :nil]] [:set :symbol]]}
  [form]
  (let [operation (when (seq? form) (first form))
        target (some-> (second form) unquoted)]
    (case operation
      require
      (into #{}
            (mapcat (fn [spec]
                      (let [spec (unquoted spec)
                            namespace-name (if (vector? spec) (first spec) spec)
                            alias-name (when (vector? spec)
                                    (some (fn [[option-key value]]
                                            (when (= :as option-key) value))
                                          (partition 2 (rest spec))))]
                        (cond-> [namespace-name]
                          alias-name (conj alias-name)))))
            (rest form))

      dir (if (symbol? target) #{target} #{})
      doc (if (symbol? target) #{target} #{})
      def (if (symbol? target) #{target} #{})
      defn (if (symbol? target) #{target} #{})
      #{})))

(defn form-references
  "Qualified symbols one parsed history form requires beforehand."
  {:malli/schema [:=> [:cat [:or :seon.render/form :nil]] [:set :symbol]]}
  [form]
  (let [operation (when (seq? form) (first form))
        target (some-> (second form) unquoted)]
    (cond
      (contains? #{'require 'dir 'def 'defn} operation) #{}
      (= 'doc operation)
      (if (and (symbol? target) (namespace target))
        #{(symbol (namespace target))}
        #{})
      (and (symbol? operation) (namespace operation)) #{operation}
      :else #{})))

(defn- entry-form
  [entry]
  (let [form (:seon.render.history/form entry)]
    (if (string? form)
      (try (read-string form) (catch Throwable _ nil))
      form)))

(defn order-history
  "Topologically order parsed forms with alphabetical ties.

   Each selected entry extends the introduced-symbol set before the next
   selection. Entries whose references cannot be introduced remain in stable
   alphabetical order at the tail, where the class regression exposes them."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [entries]
  (loop [remaining (mapv (fn [entry]
                           (let [form (entry-form entry)]
                             (assoc entry
                                    :seon.render.history/introduces
                                    (form-introductions form)
                                    :seon.render.history/references
                                    (form-references form))))
                         entries)
         introduced initially-introduced-symbols
         ordered []]
    (if (empty? remaining)
      ordered
      (let [eligible (->> remaining
                          (filter #(every? introduced
                                           (:seon.render.history/references %)))
                          (sort-by #(pr-str (:seon.render.history/form %))))
            selected (or (first eligible)
                         (first (sort-by #(pr-str (:seon.render.history/form %))
                                         remaining)))]
        (recur (into [] (remove #(identical? selected %) remaining))
               (into introduced (:seon.render.history/introduces selected))
               (conj ordered selected))))))

(defn- observation-basis
  [captured call-id fallback]
  (long (or (get-in @captured
                    [call-id :seon.render.call/basis-transaction])
            fallback)))

(defn- generic-history-entries
  [request form-units value-units captured]
  (let [values (into {}
                     (map (fn [unit]
                            [[(:seon.render.walk/lookup unit)
                              (:seon.render.walk/path unit)] unit]))
                     value-units)
        database (:seon.db/db request)
        acquisition (:seon.render.walk/root-acquisition request)
        root-member (get-in acquisition
                            [:seon.render.walk/members
                             (first (:seon.render.walk/order acquisition))])
        namespace-name (get-in root-member
                               [:seon.render/value
                                :seon.cluster.agent/namespace
                                :seon.ns/name])
        basis (db/basis-t database)]
    (into []
          (keep
           (fn [form-unit]
             (let [entry-key [(:seon.render.walk/lookup form-unit)
                              (:seon.render.walk/path form-unit)]
                   value-unit (get values entry-key)
                   form (:seon.render/output form-unit)
                   printed-value (or (:seon.render/output value-unit)
                                     (get-in value-unit
                                             [:seon.error/value
                                              :seon.error/message]))
                   distance (:seon.render/distance form-unit)
                   form-call [:seon.render/form
                              (:seon.render.walk/lookup form-unit) distance]
                   value-call [:seon.render/ai
                               (:seon.render.walk/lookup form-unit) distance]
                   observed-basis
                   (max (observation-basis captured form-call basis)
                        (observation-basis captured value-call basis))]
               (when (and (seq? form) (string? printed-value))
                 {:seon.render.history/call-id entry-key
                  :seon.render.history/basis-transaction observed-basis
                  :seon.render.history/form form
                  :seon.render.history/printed-value printed-value
                  :seon.render.history/bytes
                  (str (or namespace-name 'user) "=> " (pr-str form)
                       "\n" printed-value)}))))
          form-units)))

(defn history
  "Derive ordered form/printed-value entries for one agent from one walk."
  {:malli/schema [:=> [:cat :seon.render.walk/history-request] [:vector :map]]}
  [{captured :seon.render/captured-calls
    :as request}]
  (let [captured (or captured (atom {}))
        acquisition (or (:seon.render.walk/root-acquisition request)
                        (root-acquisition request))
        request (assoc request
                       :seon.render/captured-calls captured
                       :seon.render.walk/root-acquisition acquisition)
        form-units (neighborhood (assoc request :seon.render/output
                                        :seon.render/form))
        value-units (neighborhood (assoc request :seon.render/output
                                         :seon.render/ai))
        root-lookup (:seon.render.walk/lookup request)
        generic (->> (generic-history-entries request form-units value-units
                                               captured)
                     (remove #(= root-lookup
                                 (first (:seon.render.history/call-id %))))
                     vec)]
    (order-history generic)))
