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

  Neighbours are the schema's declared render concerns and owned components.
  Reverse refs participate only when a schema declares the concern. Namespace
  pages follow requires in both directions. Operational refs pointing at an
  agent do not become page concerns merely because they exist.

  TOTAL, because the prompt path is an error path. Every selected renderer
  failure is a flat value and an undeclared value reaches the floor. Node and
  distance budgets elide explicitly.

  Crash walk: pure over a database value. Nothing here opens, commits or
  holds anything."
  (:require [seon.error.refusal]
            [clojure.string :as str]
            [datahike.pull-api :as pull-api]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.env :as env]
            [seon.cluster.wake :as wake]
            [seon.eval :as evaluation]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.data :as data]
            [seon.print.value :as print.value]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            [malli.core :as m]
            [seon.sci.admit :as admit]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.kernel :as sci.kernel]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(deftype ^:private DatabaseSchemaIdentity [schema]
  Object
  (equals [_ other]
    (and (instance? DatabaseSchemaIdentity other)
         (identical? schema (.-schema ^DatabaseSchemaIdentity other))))
  (hashCode [_]
    (System/identityHashCode schema)))

;;; ---------------------------------------------------------------------------
;;; The connections
;;; ---------------------------------------------------------------------------

(defn- installed-attributes
  "Concrete installed attributes and their Datahike properties, ordered."
  [database]
  (into (sorted-map-by #(compare (str %1) (str %2)))
        (filter (comp keyword? key))
        (:schema (db/schema-database database))))

(defn- reverse-attribute
  [attribute]
  (keyword (namespace attribute) (str "_" (name attribute))))

(defn- selector-key
  [attribute width]
  [attribute :limit (inc (long width))])

(defn- pull-width
  "The pull's own query-work limit on one attribute's connections."
  ^long [caps]
  (admit/required-cap caps :seon.config.eval.result/max-collection))

(defn- bounded-acquisition-distance
  [distance caps]
  (min (long distance)
       (dec (admit/required-cap caps :seon.config.eval.result/max-nodes))))

(defn- declared-concerns
  [projection entity]
  (into []
        (distinct)
        (mapcat #(-> (mr/schema (:seon.schema.projection/registry projection)
                               (:seon.schema/key %))
                    internal/entity-properties :seon.render/units)
                (schema/matching-shapes-in projection (render/transacted entity)))))

(defn- connection-attributes
  [projection installed entity]
  (if (:seon.ns/name entity)
    [[:seon.ns/requires false] [:seon.ns/requires true]]
    (into []
          (comp
           (distinct)
           (keep (fn [display]
                   (let [reverse? (str/starts-with? (name display) "_")
                         attribute (if reverse?
                                     (keyword (namespace display) (subs (name display) 1))
                                     display)]
                     (when (= :db.type/ref (get-in installed [attribute :db/valueType]))
                       [attribute reverse?])))))
          (concat (declared-concerns projection entity)
                  (keep (fn [[attribute properties]]
                          (when (and (:db/isComponent properties)
                                     (get entity attribute))
                            attribute))
                        installed)))))

(defn root-selector
  "Acquire own attributes and forward reference identities.

  Forward refs have an explicit identity subpattern, including components:
  Datahike never implicitly expands them. Once this value identifies its
  matching schemas, acquisition reads only their declared reverse concerns.
  The pull's existing query-work limit asks one past its bound."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.render/distance
         :seon.sci.admit/caps]
    :seon.db/pull-selector]}
  [database _distance caps]
  (let [installed (installed-attributes database)
        inert (wake/inert-attributes database)
        leaf (into [:db/id]
                   (keep (fn [[attribute properties]]
                           (when (and (= :db.unique/identity (:db/unique properties))
                                      (not (inert attribute)))
                             attribute)))
                   installed)
        width (pull-width caps)]
    (into [:db/id]
                (keep (fn [[attribute properties]]
                        (when (not= :db/id attribute)
                          (if (= :db.type/ref (:db/valueType properties))
                            {(selector-key attribute width) leaf}
                            (if (= :db.cardinality/many (:db/cardinality properties))
                              (selector-key attribute width)
                              attribute)))))
                installed)))

(defn- stable-lookup
  [id-attributes entity]
  (or (some (fn [attribute]
              (when (contains? entity attribute)
                [attribute (get entity attribute)]))
            id-attributes)
      (:db/id entity)))

(defn entity-lookup
  "Resolve one entity id to its stable declared lookup identity."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.db/ref]
    [:or :seon.render.walk/lookup :seon.db/error-result]]}
  [database entity]
  (let [pulled (db/pull database '[*] entity)]
    (if (or (:seon.db/invalid-read pulled) (:seon.schema/expected-value pulled))
      pulled
      (stable-lookup (db/populated-identity-attributes database) pulled))))

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
  "Restore stored values after traversal temporarily resolves connections.

  Namespace requirements are symbol observations, not refs. Traversal resolves
  them to namespace entities, but a render value must carry the original
  symbols or it stops matching the namespace schema and recursively renders
  the resolved program graph through the structural floor."
  [entity ref-attributes id-attributes]
  (let [connected-attributes
        (cond-> ref-attributes
          (:seon.ns/name entity) (conj :seon.ns/requires))]
    (reduce
     (fn [result attribute]
       (if-let [value (get result attribute)]
         (assoc result attribute
                (cond
                  (= :seon.ns/requires attribute)
                  (mapv :seon.ns/name value)

                  (map? value) (ref-identity id-attributes value)
                  (sequential? value)
                  (mapv #(ref-identity id-attributes %) value)
                  :else value))
         result))
     (apply dissoc entity (map reverse-attribute connected-attributes))
     connected-attributes)))

(defn- connection-observation
  "One elision value naming the bound that cut an attribute's connections:
  the pull's own query-work limit. The pull asks for one value beyond it, so
  a returned count past it is the pull's truncation, reported always; reading
  absence of signal as \"nothing was cut\" was this project's named failure
  class (research/verify-storage-bound-2026-09-07.md B5). Presentation width
  belongs to the value renderer, never the walk."
  {:malli/schema
   [:=> [:cat :qualified-keyword :boolean :int]
    [:map [:seon.render.walk/attribute :qualified-keyword]
     [:seon.error/value :seon.render.walk/elided-error]]]}
  [attribute reverse? shown]
  (let [bound-by :seon.config.eval.result/max-collection]
    {:seon.render.walk/attribute attribute
     :seon.error/value
     (let [observation
           {:seon.error/fix "Inspect the supplied value or continue from the reported traversal subject."
            :seon.error/at (java.util.Date.)
            :seon.error/layer :seon.render.walk/connections
            :seon.error/operation 'seon.render.walk/connection-observation
            :seon.print/bound-by bound-by
            :seon.render.walk/limit shown
            :seon.render.walk/continuation-subject [attribute reverse?]
            :seon.error/message (str "elided additional " (when reverse? "reverse ") attribute
                                   " connections past " shown
                                   ", bounded by " bound-by)
            :seon.error/data {:seon.render.walk/attribute attribute
                               :seon.print/bound-by bound-by
                               :seon.render.walk/shown (long shown)}
            :seon.error/member :seon.render.walk/lookup
            :seon.error/expected "a traversal within the requested query-work bound"
            :seon.error/offending {:seon.render.walk/attribute attribute :seon.render.walk/shown shown}}]
       observation)}))

(defn- acquisition-members
  [projection database root distance query-width]
  (let [installed (installed-attributes database)
        refs (into []
                   (keep (fn [[attribute properties]]
                           (when (= :db.type/ref
                                    (:db/valueType properties))
                             attribute)))
                   installed)
        identities (db/populated-identity-attributes database)
        query-width (long query-width)]
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
                          declared-connections
                          (connection-attributes projection installed entity)
                          connections
                          (into []
                                (mapcat
                                 (fn [[attribute reverse?]]
                                   (let [values (connection-values
                                                 entity attribute reverse?)
                                         values (if reverse?
                                                  (sort-by :db/id > values)
                                                  values)
                                         shown query-width
                                         kept (take shown values)
                                         kept (if reverse?
                                                (sort-by :db/id kept)
                                                kept)
                                         elided? (> (count values) shown)]
                                     (cond->
                                      (mapv (fn [child]
                                              {:seon.render.walk/attribute
                                               attribute
                                               :seon.render.walk/lookup
                                               (stable-lookup identities child)
                                               :seon.render.walk/pulled child})
                                            kept)
                                       elided?
                                       (conj (connection-observation
                                              attribute reverse? shown)))))
                                 declared-connections))]
                      (if (pos? remaining)
                        (reduce-kv
                         (fn [result index connection]
                           (if-let [child
                                    (:seon.render.walk/pulled connection)]
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

(defn root-pull-plan
  "Acquire a compiled pull plan by its schema and selector; retain caller bounds."
  {:malli/schema [:=> [:cat :seon.render.walk/acquisition-request] :map]}
  [{database :seon.db/db, caps :seon.sci.admit/caps, ctx :seon.sci.eval/ctx, :as request}]
  (let [projection (or
                     (:seon.schema/projection request)
                     (db/carried-projection database)
                     (sci.kernel/context-projection ctx)
                     (schema/current-projection))
        distance (long (get request :seon.render/distance 1))
        selector (root-selector database distance caps)
        plan (schema/projection-cache-value
              projection
              [:seon.render.walk/root-pull-plan
               (:seon.schema.projection/fingerprint projection)
               (DatabaseSchemaIdentity. (:schema (db/schema-database database)))
               selector]
              #(hash-map :seon.schema.projection/fingerprint (:seon.schema.projection/fingerprint projection)
                         :seon.render.walk/selector selector
                         :datahike.pull/plan (pull-api/compile-pull-plan database selector)))]
    (assoc plan :seon.render/distance distance :seon.sci.admit/caps caps)))

(defn- namespace-connections
  "Resolve observed namespace names for this walk without changing stored facts."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :int]
                  [:or :map :seon.db/error-result]]}
  [database entity width]
  (if-let [namespace-name (:seon.ns/name entity)]
    (let [names (:seon.ns/requires entity)
          requirers (db/q '[:find [?entity ...] :in $ ?name
                             :where [?entity :seon.ns/requires ?name]]
                           database namespace-name)]
      (if (or (:seon.db/invalid-read requirers) (:seon.schema/expected-value requirers))
        requirers
        (assoc entity
               :seon.ns/requires
               (db/pull-many database [:db/id :seon.ns/name]
                             (mapv #(vector :seon.ns/name %) (take (inc width) names)))
               :seon.ns/_requires
               (db/pull-many database [:db/id :seon.ns/name]
                             (vec (take (inc width) (sort > requirers)))))))
    entity))

(defn- acquire-entity
  "Reuse one entity pull only while its recorded read evidence is current."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value :map :map :seon.render.walk/lookup :seon.render/cache] [:map [:seon.render.call/output :seon.schema/value] [:seon.render.call/basis-transaction :seon.db/basis-t] [:seon.render.call/read-evidence [:vector :seon.db/read-evidence]]]]}
  [projection database installed plan lookup cache]
  (let [cache-key [::entity-pull lookup]
        previous (get @cache cache-key)]
    (if (and previous
             (identical? (:datahike.pull/plan plan) (:datahike.pull/plan previous))
             (not (let [output (:seon.render.call/output previous)]
               (or (:seon.db/invalid-read output) (:seon.schema/expected-value output))))
             (db/read-evidence-current? database (:seon.render.call/read-evidence previous)))
      (let [refreshed (render/refresh-read-evidence database previous)]
        (swap! cache
               (fn [current]
                 (if (identical? previous (get current cache-key))
                   (assoc current cache-key refreshed)
                   current)))
        refreshed)
      (let [captured (atom [])
            value
            (binding [db/*read-evidence-sink* captured]
              (let [entity (db/pull database {:selector (:seon.render.walk/selector plan)
                                              :datahike.pull/plan (:datahike.pull/plan plan)
                                              :eid lookup})
                    reverse-selector
                    (when (:db/id entity)
                      (into []
                            (keep (fn [[attribute reverse?]]
                                    (when (and reverse? (not= :seon.ns/requires attribute))
                                      {(selector-key (reverse-attribute attribute)
                                                     (pull-width (:seon.sci.admit/caps plan)))
                                       [:db/id]})))
                            (connection-attributes projection installed entity)))]
                (if (:seon.ns/name entity)
                  (namespace-connections database entity (pull-width (:seon.sci.admit/caps plan)))
                  (if (seq reverse-selector)
                  (let [reverse-values (db/pull database reverse-selector lookup)]
                    (if (or (:seon.db/invalid-read reverse-values) (:seon.schema/expected-value reverse-values))
                      reverse-values
                      (merge entity reverse-values)))
                  entity))))
            entry {:datahike.pull/plan (:datahike.pull/plan plan)
                   :seon.render.call/output value
                   :seon.render.call/basis-transaction (db/basis-t database)
                   :seon.render.call/read-evidence
                   (db/read-evidence @captured)}]
        (swap! cache
               (fn [current]
                 (if (> (long (get-in current [cache-key :seon.render.call/basis-transaction] -1))
                        (:seon.render.call/basis-transaction entry))
                   current
                   (assoc current cache-key entry))))
        entry))))

(defn- acquired-tree
  "Expand each distinct entity once, in the walk's declared traversal order."
  [request distance caps]
  (let [database (:seon.db/db request)
        plan (root-pull-plan (assoc request :seon.render/distance 0))
        cache (render/shared-cache (:seon.sci.eval/ctx request))
        projection (or (:seon.schema/projection request)
                       (db/carried-projection database)
                       (sci.kernel/context-projection (:seon.sci.eval/ctx request)))
        installed (installed-attributes database)
        width (pull-width caps)]
    ;; The walk's state is a value threaded through `visit`, [state value]:
    ;; entities pulled by lookup and eid, eids expanded, and read evidence.
    (letfn [(visit [state lookup remaining]
              (let [[state value]
                    (if-some [pulled (get-in state [::pulled lookup])]
                      [state pulled]
                      (let [entry (acquire-entity projection database installed plan lookup cache)
                            value (:seon.render.call/output entry)]
                        [(-> state
                             (update ::evidence into (:seon.render.call/read-evidence entry))
                             (update ::pulled assoc lookup value (:db/id value) value))
                         value]))
                    eid (:db/id value)]
                (if (or (not eid) (contains? (::visited state) eid))
                  [state value]
                  (reduce
                   (fn [[state result] [display _attribute reverse?]]
                     (if-let [children (get value display)]
                       (let [expand (fn [state child] (visit state (:db/id child) (max 0 (dec remaining))))]
                         (if (map? children)
                           (let [[state expanded] (expand state children)]
                             [state (assoc result display expanded)])
                           (let [selected (into #{} (map :db/id)
                                                (take width (if reverse?
                                                              (sort-by :db/id > children)
                                                              children)))
                                 [state expanded]
                                 (reduce (fn [[state out] child]
                                           (if (selected (:db/id child))
                                             (let [[state child] (expand state child)]
                                               [state (conj out child)])
                                             [state (conj out child)]))
                                         [state []] children)]
                             [state (assoc result display expanded)])))
                       [state result]))
                   [(update state ::visited conj eid) value]
                   (when (pos? remaining)
                     (map (fn [[attribute reverse?]]
                            [(if reverse? (reverse-attribute attribute) attribute)
                             attribute reverse?])
                          (connection-attributes projection installed value)))))))]
      (let [[state root] (visit {::pulled {} ::visited #{} ::evidence []}
                                (:seon.render.walk/lookup request) distance)
            evidence (::evidence state)]
        (when db/*read-evidence-sink*
          (swap! db/*read-evidence-sink* into
                 (map #(assoc % :seon.db/db database) evidence)))
        {:seon.render.walk/root root
         :seon.render.call/read-evidence evidence}))))

(defn root-acquisition
  "Pull and index one agent-root neighbourhood by stable entity identity.

  Each distinct entity is acquired once through shared read-evidence caches.
  Cycles and unrelated namespace refs never trigger recursive repeated pulls.
  Its member values contain only
  each entity's own attributes and direct ref identities; reverse/nested
  structure supplies membership and paths without making an ancestor appear
  changed when only a descendant changed."
  {:malli/schema [:=> [:cat :seon.render.walk/acquisition-request] :map]}
  [{database :seon.db/db
    ctx :seon.sci.eval/ctx
    :as request}]
  (let [projection (or (:seon.schema/projection request)
                       (db/carried-projection database)
                       (sci.kernel/context-projection ctx)
                       (schema/current-projection))]
    (schema/call-with-projection
     projection
     (fn []
       (let [{distance :seon.render/distance
              caps :seon.sci.admit/caps
              :as pull-plan}
             (or (:seon.render.walk/root-pull-plan request)
                 (root-pull-plan request))
             tree (acquired-tree request (bounded-acquisition-distance distance caps) caps)
             root (:seon.render.walk/root tree)]
         (merge pull-plan tree
                ;; THE WIDTH IS THE CALLER'S, NEVER THE CACHED PLAN'S. A
                ;; compiled pull plan is shared across every caller of one
                ;; schema generation; reading a presentation decision off it
                ;; would hand the second caller the first caller's profile.
                (acquisition-members projection database root
                                     (bounded-acquisition-distance distance caps)
                                     ;; The pull's query-work bound is the
                                     ;; only cut; the value renderer elides.
                                     (pull-width caps))))))))

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
    (if (= root-namespace-eid eid) 1 0)
    traversal-hops))

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
        entity (:seon.render/value root)
        namespace-ref (:seon.agent/namespace entity)]
    (if (:seon.ns/name entity)
      (:seon.render.walk/eid root)
      (when (map? namespace-ref) (:db/id namespace-ref)))))

(defn- distance-cap-unit
  {:malli/schema
   [:=> [:cat :map :int]
    [:or :nil
     [:map [:seon.render.walk/lookup :seon.render.walk/lookup]
      [:seon.error/value :seon.render.walk/elided-error]]]]}
  [member remaining]
  (when (and (zero? remaining)
             (seq (:seon.render.walk/connections member)))
    {:seon.render.walk/lookup (:seon.render.walk/lookup member)
     :seon.render/distance remaining
     :seon.render.walk/path
     (conj (:seon.render.walk/path member)
           :seon.render.walk/neighbours 0)
     :seon.render.walk/found-depth
     (inc (:seon.render.walk/found-depth member))
     :seon.error/value
     (let [observation
           {:seon.error/fix "Inspect the supplied value or continue from the reported traversal subject."
            :seon.error/at (java.util.Date.)
            :seon.error/layer :seon.render.walk/distance
            :seon.error/operation 'seon.render.walk/distance-cap-unit
            :seon.print/bound-by :seon.render/distance
            :seon.render.walk/limit remaining
            :seon.render.walk/continuation-subject (:seon.render.walk/lookup member)
            :seon.error/message "elided connections at the requested distance cap"
            :seon.error/member :seon.render.walk/lookup
            :seon.error/expected "a traversal within the requested query-work bound"
            :seon.error/offending member}]
       observation)}))

(defn- declared-acquisition
  "Order declared concerns; an owned component has a block even before it exists."
  [projection _database acquisition output]
  (let [root-lookup (first (:seon.render.walk/order acquisition))
        root (get-in acquisition [:seon.render.walk/members root-lookup])
        entity (:seon.render/value root)
        concerns (when (map? entity) (declared-concerns projection entity))]
    (if (empty? concerns)
      acquisition
      (reduce
       (fn [result display]
         (let [reverse? (str/starts-with? (name display) "_")
               attribute (if reverse? (keyword (namespace display) (subs (name display) 1)) display)
               properties (some-> (mr/schema (:seon.schema.projection/registry projection) attribute) m/properties)
               producer (when (or (:seon.render/derived properties)
                                  (and reverse? (:seon.render/form properties))
                                  (and (not reverse?) (:seon.db/component properties)))
                          (get properties output))
               connected (filter #(= attribute (:seon.render.walk/attribute %))
                                 (map (:seon.render.walk/members acquisition)
                                      (rest (:seon.render.walk/order acquisition))))]
           (if producer
             (let [lookup [root-lookup display]
                   member {:seon.render.walk/lookup lookup
                           :seon.render.walk/path [display]
                           :seon.render.walk/found-depth 1
                           :seon.render.walk/attribute attribute
                           :seon.render/value
                           (assoc {attribute root-lookup}
                                  (first root-lookup) (second root-lookup))}]
               (-> result
                   (assoc-in [:seon.render.walk/members lookup] member)
                   (update :seon.render.walk/order conj lookup)))
             (update result :seon.render.walk/order into
                     (map :seon.render.walk/lookup connected)))))
       (assoc acquisition :seon.render.walk/order [root-lookup]) concerns))))

(defn- scoped-attribute
  "The attribute this member's render request is ABOUT, if any.

  The walk stamps `:seon.render.walk/attribute` on members with two different
  meanings. `declared-acquisition` synthesizes a member FOR an attribute — a
  declared concern block — and that member stands for the attribute itself, so
  it carries no entity of its own. `visit` stamps the attribute it REACHED an
  entity THROUGH, and that member carries the entity's own
  `:seon.render.walk/eid`; its value is the neighbour, never the attribute's
  value, so its render request is about the neighbour and carries no
  attribute. Render selection reads only the request, so the walk decides
  here rather than letting the seam guess from the value's keys."
  [member]
  (when-not (:seon.render.walk/eid member)
    (:seon.render.walk/attribute member)))

(defn neighborhood
  "Render the root acquisition's stable members without further discovery."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :seon.render.walk/units]}
  [{database :seon.db/db
    caps :seon.sci.admit/caps
    ctx :seon.sci.eval/ctx
    output :seon.render/output
    lookup :seon.render.walk/lookup
    :as request}]
  (let [projection (or (:seon.schema/projection request)
                       (db/carried-projection database)
                       (sci.kernel/context-projection ctx)
                       (schema/current-projection))]
    (schema/call-with-projection
     projection
     (fn []
       (let [distance (long (get request :seon.render/distance 1))
             acquisition (or (:seon.render.walk/root-acquisition request)
                             (root-acquisition request))
             acquisition (declared-acquisition projection database acquisition output)
             members (:seon.render.walk/members acquisition)
             order (:seon.render.walk/order acquisition)
             root-namespace-eid (acquired-root-namespace-eid acquisition)
             node-limit (admit/required-cap
                         caps :seon.config.eval.result/max-nodes)]
         (if (empty? order)
           [{:seon.render.walk/lookup lookup
             :seon.render/distance distance
             :seon.render.walk/path []
             :seon.render.walk/found-depth 0
             :seon.error/value
             (let [observation
                   (assoc {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.render.walk/render
                    :seon.error/operation 'seon.render.walk/neighborhood
                    :seon.error/message "The requested lookup has no entity in this database."
                    :seon.error/fix "Supply the expected member and repeat the requested operation."
                    :seon.render.walk/missing-lookup lookup
                    :seon.error/member :seon.render.walk/lookup
                    :seon.error/expected "an existing entity"} :seon.error/type :seon.render.walk/no-such-entity-error)]
               observation)}]
           (into []
                 (comp
                  (take node-limit)
                  (mapcat
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
                             (scoped-attribute member)
                             (assoc :seon.render.walk/attribute
                                    (scoped-attribute member)))
                           rendered (render/render-call render-request)
                           ;; A failure is what render-call declares as its
                           ;; error alternative (a refusal, a bounded read's
                           ;; `:seon.db/invalid-read`): an AI face is data
                           ;; (renders return data, owner 313e81181), so a map
                           ;; is output unless it is that error.
                           failure (when (and (map? rendered)
                                              ((schema/projection-validator
                                                (db/carried-projection database)
                                                :seon.render/error-result)
                                               rendered))
                                     rendered)
                           failure-outcome
                           (when (and failure owner)
                             (render/renderer-failure
                              {:seon.db/db database
                               :seon.render/namespace owner
                               :seon.error/value failure}))
                           member-unit
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
                                          [:div
                                           {:class "seon-render-unavailable"}
                                           "renderer unavailable"]
                                          "Renderer unavailable.")))
                             (seq (:seon.db/tx-data failure-outcome))
                             (assoc :seon.db/tx-data
                                    (:seon.db/tx-data failure-outcome))
                             (not failure)
                             (assoc :seon.render/output rendered))
                           marker (when (not= output :seon.render/html)
                                    (distance-cap-unit member remaining))]
                       (cond-> [member-unit]
                         marker (conj marker))))))
                 order)))))))


;;; ---------------------------------------------------------------------------
;;; The agent's history
;;; ---------------------------------------------------------------------------

(defn continue
  "One cut's continuation: the walk node it names, navigated by the cut's
  path and selected again from its absolute offset (one-render-path §3g).

  A result handle names an evaluation's live object in the caller's SCI
  context (`:gone` once evicted or after a restart); `[:seon.blob/digest d]`
  names an MCP value's stored artifact, read through the caller's connection,
  the same backing its first page was selected from; a lookup ref names an
  entity at the caller's database value. The same floor selects the value,
  so the answer is an ordinary prepared projection."
  {:malli/schema [:=> [:cat :seon.render.walk/continue-request]
                  [:or :seon.render.value/projection :seon.render.value/missing-root-identity-error
                   :seon.render.walk/continuation-error :seon.render.data/no-such-path-error :seon.db/error-result]]}
  [{node :seon.render.walk/continuation-node cursor :seon.render.data/cursor database :seon.db/db ctx :seon.sci.eval/ctx
    connection :seon.db/connection :as request}]
  (let [refusal (fn [reason message]
                  (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.walk/continue 'seon.render.walk/continue
                          {:seon.error/message message :seon.error/member :seon.render.walk/continuation-node
                           :seon.error/expected "a retained walk node" :seon.error/offending node})
                         :seon.render.walk/continuation-reason reason :seon.render.walk/continuation-node node))
        resolved
        (cond
          (qualified-symbol? node)
          (if-some [entry (some-> (:seon.sci.eval/result-objects ctx) deref (find (subs (name node) 1)))]
            {:value (val entry)}
            (refusal :gone "The result is no longer retained by this context."))
          (and (vector? node) (= :seon.blob/digest (first node)))
          (if-some [content (when connection (blob/get connection (second node)))]
            (let [stored (render.value/read-artifact content)]
              (if (:seon.render.value/unreadable-artifact stored)
                (refusal :malformed (:seon.render.value/unreadable-artifact stored))
                {:value (render.value/artifact-value stored)}))
            (refusal (if connection :gone :unavailable)
                     (if connection "No blob has this digest: a GC sweep collected it, or it was never stored."
                         "A stored value's continuation needs the connection that holds it.")))
          database
          (let [pulled (db/pull database '[*] node)]
            (cond (:seon.db/invalid-read pulled) pulled
                  (:db/id pulled) {:value pulled}
                  :else (refusal :gone "No entity has this identity in this database.")))
          :else (refusal :unavailable "An entity continuation needs the database value it reads."))]
    (if-not (contains? resolved :value)
      resolved
      (let [found (data/at (:value resolved) cursor)]
        (if-not (contains? found :seon.render.data/value)
          found
          (render.value/prepare
           (assoc (dissoc request :seon.render.walk/continuation-node)
                  :seon.render/value (:seon.render.data/value found)
                  :seon.render.value/root node)))))))

(defn history
  "Render saved evaluations in chronological order through their schema pair.

  A named reply turn excludes its own evaluations, including source rows
  admitted with its opening transaction. Generated system evaluations remain
  visible. Without a turn id, every stored evaluation participates."
  {:malli/schema [:=> [:cat :seon.render.walk/history-request]
                  [:or [:vector :map] :seon.render/request-error]]}
  [{database :seon.db/db lookup :seon.render.walk/lookup :as request}]
  (let [agent (db/pull database [:seon.agent/id] lookup)
        agent-id (:seon.agent/id agent)
        evaluations (cond
                      (:seon.db/invalid-read agent) agent
                      (nil? agent-id)
                      (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.walk/history 'seon.render.walk/history
                       {:seon.error/message "The history lookup does not name an agent."
                       :seon.render/refused-member :seon.render.walk/lookup}) :seon.error/type :seon.render/request-error)
                      :else
                      (evaluation/of-agent database agent-id
                                           (or (:seon.db/pull-selector request)
                                               evaluation/history-selector)))
        selected (when-let [id (:seon.turn/id request)]
                   (db/pull database [:db/id :seon.turn.work/situation]
                            [:seon.turn/id id]))]
    (if-let [refusal (or (when (:seon.db/invalid-read selected) selected)
                        (when (or (:seon.db/invalid-read evaluations)
                                  (:seon.agent/no-such-agent evaluations)
                                  (:seon.render/refused-member evaluations))
                          evaluations))]
      (assoc (assoc refusal :seon.render/refused-member :seon.render.history/entries) :seon.error/type :seon.render/request-error)
      (reduce
       (fn [entries saved]
         (let [lookup [:seon.cluster.eval/id (:seon.cluster.eval/id saved)]
               ;; `:t` is the read's basis (kept below), not an attribute of
               ;; the receipt entity; without it the entity's owner and pair
               ;; apply, for this AI render and for every later face of the
               ;; entry (the outline's HTML fell to the floor with it).
               receipt (dissoc saved :t)
               rendered (render/render-call
                         (assoc request :seon.render/value receipt
                                        :seon.render/output :seon.render/ai
                                        :seon.render.call/id [lookup]))]
           (if (:seon.render/refused-member rendered)
             (reduced (assoc (assoc rendered :seon.render/refused-member :seon.render.history/entries) :seon.error/type :seon.render/request-error))
             (conj entries
                   {:seon.render.history/call-id [lookup]
                    :seon.render/value receipt
                    :seon.render.history/subject lookup
                    :seon.render.history/basis-transaction (:t saved)
                    :seon.render.history/bytes (or rendered "")}))))
       [] (if (and selected (not= :generate (:seon.turn.work/situation selected)))
            (remove #(= (:db/id selected)
                        (get-in % [:seon.cluster.eval/run :db/id])) evaluations)
            evaluations)))))

;;; ---------------------------------------------------------------------------
;;; The linked graph: levels of set-at-a-time joins, definitions first
;;; ---------------------------------------------------------------------------

(defn- schema-properties
  "The properties `k` declares: an entity schema's, else its schema's own."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword] [:maybe :map]]}
  [projection k]
  (some-> (mr/schema (:seon.schema.projection/registry projection) k)
          (as-> s (or (internal/entity-properties s) (m/properties s)))))

(defn- named-leaf
  "The named schema `attribute`'s value schema bottoms out in, through
  `:set`, `:vector`, `:sequential`, `:and` and `:maybe`; else nil."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword] [:maybe :qualified-keyword]]}
  [projection attribute]
  (loop [form (some-> (mr/schema (:seon.schema.projection/registry projection) attribute) m/form) seen #{}]
    (cond
      (and (qualified-keyword? form) (not= form attribute)) form
      (and (vector? form) (#{:set :vector :sequential :and :maybe} (first form)) (not (seen form)))
      (recur (some #(when (or (qualified-keyword? %) (vector? %)) %) (drop (if (map? (second form)) 2 1) form))
             (conj seen form))
      :else nil)))

(defn- value-references
  "`[holder identity]` for each stored value attribute naming another entity
  by its identity value: the holder's value schema bottoms out in the same
  named schema as the identity attribute's (`:seon.task/tasks` and
  `:seon.task/id` both end in `:seon.task/handle`), or the holder or that
  named schema declares `:seon.db/reference-to` (one identity or a set)."
  {:malli/schema [:=> [:cat :seon.schema/projection [:sequential :map]] :seon.render.walk/value-attributes]}
  [projection declared]
  (let [ref? #(= :db.type/ref (:db/valueType %))
        leaf #(named-leaf projection %)
        by-leaf (group-by leaf (into [] (comp (filter :db/unique) (remove ref?) (map :db/ident)) declared))
        declared-to #(let [to (when % (:seon.db/reference-to (schema-properties projection %)))]
                       (if (keyword? to) #{to} (set to)))]
    (vec (sort (for [{a :db/ident :as attribute} declared
                     :when (not (or (:db/unique attribute) (ref? attribute)))
                     :let [l (leaf a)]
                     i (disj (into (set (when l (get by-leaf l))) (concat (declared-to a) (declared-to l))) a)]
                 [a i])))))

(defn graph-structure
  "Ref, value, identity and component attributes, their owning schemas, the
  owning schemas declaring an AI pair, and per owning schema its required
  entries and the reverse attributes its `:seon.render/units` declares as
  attachments; derived once per projection."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.render.walk/structure]}
  [projection]
  (schema/projection-cache-value
   projection ::graph-structure
   (fn []
     (let [declared (schema.datahike/malli->datahike-schema-in projection (schema.datahike/database-attributes-in projection))
           properties #(some-> (mr/schema (:seon.schema.projection/registry projection) %)
                               (as-> s (or (internal/entity-properties s) (m/properties s))))
           owners (fn [pick owner] (into {} (comp (filter pick) (map :db/ident) (map (juxt identity owner))) declared))
           identities (owners :db/unique #(:seon.program/row-schema (properties %) %))
           components (owners :db/isComponent #(:seon.db/component-schema (properties %) %))
           entities (keep #(when-let [s (some->> % (mr/schema (:seon.schema.projection/registry projection)))]
                             (when (internal/entity-schema? s) [% s]))
                          (distinct (concat (vals identities) (vals components))))]
       {::ref-attributes (into [] (comp (filter (comp #{:db.type/ref} :db/valueType)) (map :db/ident)) declared)
        ::value-attributes (value-references projection declared)
        ::identity-owners identities ::component-owners components
        ::rendered-schemas (into #{} (filter #(qualified-symbol? (:seon.render/ai (properties %))))
                                 (concat (vals identities) (vals components)))
        ::required-attributes (into {} (map (fn [[k s]] [k (into #{} (keep (fn [[a p]] (when-not (:optional p) a))) (internal/entity-entries s))])) entities)
        ::attachments (into {} (map (fn [[k s]] [k (into #{} (filter #(str/starts-with? (name %) "_")) (:seon.render/units (internal/entity-properties s)))])) entities)}))))

(defn- frontier-datoms
  "`[e a v]` for every datom of each frontier entity, read by EAVT; else the
  first refusal. Datalog binding only the entity (`[?e ?a]`) scanned past the
  query-work bound (65,536) on a task at eid 34746 (default, 2026-09-28)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :int]]
                  [:or [:vector [:tuple :int :keyword :seon.schema/value]] :seon.db/invalid-read-error]]}
  [db frontier]
  (reduce (fn [found e]
            (let [datoms (db/datoms db :eavt e)]
              (if (:seon.db/invalid-read datoms) (reduced datoms) (into found (map (juxt :e :a :v)) datoms))))
          [] frontier))

(defn- frontier-edges
  "The edges `family` reads from the frontier's own `datoms`: its refs
  (`:forward`), the entities its value references name (`:value-forward`,
  AVET on the identity) and the holders naming its identity values
  (`:value-reverse`, attribute and value bound as scalars). Else the first
  refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.render.walk/structure
                       [:vector [:tuple :int :keyword :seon.schema/value]] :keyword]
                  [:or :seon.render.walk/edges :seon.db/invalid-read-error]]}
  [db bounds structure datoms family]
  (let [pairs (::value-attributes structure)
        reverse? (= family :value-reverse)]
    (case family
      :forward (let [refs (set (::ref-attributes structure))] (into [] (filter (comp refs second)) datoms))
      (reduce (fn [edges [e a i v]]
                (let [found (if reverse?
                              (db/q (assoc bounds :query '[:find [?t ...] :in $ ?a ?v :where [?t ?a ?v]] :args [db a v]))
                              (db/datoms db :avet i v))]
                  (if (:seon.db/invalid-read found)
                    (reduced found)
                    (into edges (map (fn [t] [e (cond-> a reverse? reverse-attribute) (if reverse? t (:e t))])) found))))
              []
              (let [partners (group-by (if reverse? second first) pairs)]
                (for [[e held v] datoms [a i] (partners held)] [e a i v]))))))

(defn- joined
  "The `[source attribute target]` edges each family joins from the whole
  frontier, reverse attributes spelled `:ns/_name`; else the first refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.render.walk/structure [:sequential :int] [:vector :keyword]]
                  [:or :seon.render.walk/edges :seon.db/invalid-read-error]]}
  [db bounds structure frontier families]
  (let [datoms (when (some #{:forward :value-forward :value-reverse} families) (frontier-datoms db frontier))]
    (reduce
     (fn [edges family]
       (let [found (cond
                     (:seon.db/invalid-read datoms) datoms
                     (#{:reverse :attachment :identity} family)
                     (let [[clauses attributes]
                           (case family
                             :reverse ['[[?t ?a ?e]] (::ref-attributes structure)]
                             ;; Every schema's declared attachments; `level-step` keeps those its source's schema declares.
                             :attachment ['[[?t ?a ?e]] (into [] (comp (mapcat val) (distinct) (map #(keyword (namespace %) (subs (name %) 1))))
                                                              (::attachments structure))]
                             ;; The identity VALUE is not a target: bind ?t to the holder, so every family yields `[entity attribute entity]`.
                             :identity ['[[?e ?a _] [(identity ?e) ?t]] (vec (keys (::identity-owners structure)))])
                           found (db/q (assoc bounds :query (into '[:find ?e ?a ?t :in $ [?e ...] [?a ...] :where] clauses)
                                              :args [db (vec frontier) attributes]))]
                       (if (or (:seon.db/invalid-read found) (= family :identity))
                         found
                         (mapv (fn [[e a t]] [e (reverse-attribute a) t]) found)))
                     :else (frontier-edges db bounds structure datoms family))]
         (if (:seon.db/invalid-read found) (reduced found) (into edges (sort found)))))
     [] families)))

(defn- owning-schemas
  "Each edge target's owning schema: its identity's row schema, else the
  component schema of the attribute reaching it; else the refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.render.walk/structure [:sequential [:tuple :int [:maybe :qualified-keyword] :int]]]
                  [:or :seon.render.walk/owning-schemas :seon.db/invalid-read-error]]}
  [db bounds structure edges]
  (let [found (joined db bounds structure (distinct (map peek edges)) [:identity])]
    (if (:seon.db/invalid-read found)
      found
      (merge (into {} (keep (fn [[_ a t]] (some->> (get (::component-owners structure) a) (vector t)))) edges)
             (into {} (map (fn [[t a _]] [t (get (::identity-owners structure) a)])) found)))))

(defn- level-step
  "One level's edges and owning schemas; an owned child without a pair (an
  arity, and the vector cell holding it) is joined through within its
  owner's level, however deep the chain. Else the refusal.
  `frontier-schemas` (each frontier node's owning schema) selects the forward
  story: forward refs plus the reverse attachments the source's schema
  declares; without it every ref and value edge is followed both ways."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.render.walk/structure [:sequential :int] [:maybe :seon.render.walk/owning-schemas]]
                  [:or :seon.render.walk/level-result :seon.db/invalid-read-error]]}
  [db bounds structure frontier frontier-schemas]
  (let [refused #(when (:seon.db/invalid-read %) %)
        declared? (fn [[e a _]] (or (not frontier-schemas) (not (str/starts-with? (name a) "_"))
                                    (contains? (get (::attachments structure) (frontier-schemas e)) a)))
        ;; child -> the frontier node it belongs to, for each unrendered owned child
        owned (fn [owner schemas edges]
                (into {} (keep (fn [[e a t]] (when (and ((::component-owners structure) a)
                                                        (not ((::rendered-schemas structure) (schemas t))))
                                               [t (get owner e e)])))
                      edges))
        found (joined db bounds structure frontier (if frontier-schemas [:forward :attachment] [:forward :reverse :value-forward :value-reverse]))
        found (if (refused found) found (filterv declared? found))
        schemas (or (refused found) (owning-schemas db bounds structure found))]
    (or (some refused [found schemas])
        (loop [owner (owned {} schemas found)
               edges found
               schemas schemas
               pending (vec (keys owner))]
          (if (empty? pending)
            {::edges (filterv #(not (owner (peek %))) edges) ::owning-schemas schemas}
            (let [inner (joined db bounds structure pending (if frontier-schemas [:forward] [:forward :value-forward]))
                  inner-schemas (cond (refused inner) inner (empty? inner) {} :else (owning-schemas db bounds structure inner))]
              (or (some refused [inner inner-schemas])
                  (let [schemas (merge schemas inner-schemas)
                        deeper (apply dissoc (owned owner schemas inner) (keys owner))]
                    (recur (merge owner deeper)
                           (into edges (map (fn [[e a t]] [(owner e) a t])) inner)
                           schemas
                           (vec (keys deeper)))))))))))

(defn- definitions-first
  "Postorder over definition edges: each node after the definitions it names,
  siblings by `chronology`. An edge onto the current path is a back-reference,
  so a cycle renders once."
  {:malli/schema [:=> [:cat [:sequential :int] [:map-of :int [:set :int]] [:map-of :int [:vector :int]]]
                  [:tuple :seon.render.walk/graph-order :seon.render.walk/back-references]]}
  [ids depends chronology]
  (letfn [(visit [[done order backs] path node]
            (if (done node)
              [done order backs]
              (let [path (conj path node)
                    [done order backs] (reduce #(if (path %2) (update %1 2 conj [node %2]) (visit %1 path %2))
                                               [(conj done node) order backs] (sort-by chronology (depends node)))]
                [done (conj order node) backs])))]
    (vec (rest (reduce #(visit %1 #{} %2) [#{} [] []] (sort-by chronology ids))))))

(defn- finished-graph
  "The walked graph with pulled node values, definitions-first order (turns by
  `:seon.turn/opened-tx`, then entity id) and back-references."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.render.walk/graph] :seon.render.walk/graph]}
  [db {nodes ::nodes edges ::edges :as graph}]
  (let [ids (vec (keys nodes))
        values (zipmap ids (db/pull-many db '[*] ids))
        projection (db/carried-projection db)
        definition? #(:seon.program/definition-edge (schema-properties projection %))
        depends (reduce (fn [m [e a t]]
                          (cond (= e t) m
                                (definition? a) (update m e (fnil conj #{}) t)
                                (definition? (keyword (namespace a) (subs (name a) 1))) (update m t (fnil conj #{}) e)
                                :else m))
                        {} edges)
        [order backs] (definitions-first ids depends (into {} (map #(vector % [(:db/id (get-in values [% :seon.turn/opened-tx]) %) %])) ids))]
    (assoc graph ::nodes (into {} (map (fn [[e node]] [e (assoc node :seon.render/value (values e))])) nodes)
           ::graph-order order ::back-references backs ::skipped (update-vals (::skipped graph) count))))

(defn graph
  "The linked graph from `lookup`, `:seon.render/distance` levels deep (default 3).

  With `::forward-only?` it follows forward refs and only the reverse refs an
  owning schema declares as attachments in `:seon.render/units`: the story
  from the root outward. Otherwise refs and value references (derived from
  the schemas, `value-references`) are followed forward and reverse.

  A target is a node when its owning schema declares an AI pair; the others are
  counted by schema under `::skipped`. Each level's joins bind the whole
  frontier through `seon.db/q` under the caps' query-work bounds. A refusal,
  `max-nodes`, or `max-collection` per source and attribute records a cut."
  {:malli/schema [:=> [:cat :seon.render.walk/graph-request]
                  [:or :seon.render.walk/graph :seon.render.walk/no-such-entity-error]]}
  [{db :seon.db/db lookup :seon.render.walk/lookup caps :seon.sci.admit/caps :as request}]
  (let [structure (graph-structure (db/carried-projection db))
        rendered (::rendered-schemas structure)
        max-nodes (admit/required-cap caps :seon.config.eval.result/max-nodes)
        per-attribute (admit/required-cap caps :seon.config.eval.result/max-collection)
        bounds {:max-work max-nodes :max-results max-nodes :max-result-weight max-nodes}
        root (:db/id (db/pull db [:db/id] lookup))]
    (if-not root
      (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.walk/render 'seon.render.walk/graph
       {:seon.error/message "The requested lookup has no entity in this database."
        :seon.render.walk/missing-lookup lookup :seon.error/member :seon.render.walk/lookup}) :seon.error/type :seon.render.walk/no-such-entity-error)
      (loop [level 0 frontier [root] edges [] skipped {} cuts []
             nodes {root {::node-level 0 ::node-schema (get (owning-schemas db bounds structure [[root nil root]]) root)}}]
        (let [step (when (and (< level (get request :seon.render/distance 3)) (seq frontier))
                     (level-step db bounds structure frontier
                                 (when (::forward-only? request) (into {} (map (juxt identity (comp ::node-schema nodes))) frontier))))
              cut (fn [bound level n & more] (apply assoc {::cut-bound bound ::node-level level ::cut-count n} more))]
          (if (or (nil? step) (:seon.db/invalid-read step))
            (finished-graph db (cond-> {::graph-root root ::nodes nodes ::edges edges ::skipped skipped}
                                 step (assoc ::cuts (conj cuts (cut :seon.db/invalid-read level (count frontier) :seon.error/value step)))
                                 (and (nil? step) (seq cuts)) (assoc ::cuts cuts)))
            (let [groups (group-by (juxt first second) (::edges step))
                  kept (into [] (mapcat #(take-last per-attribute (sort-by peek %))) (vals groups))
                  schemas (::owning-schemas step)
                  targets (remove nodes (distinct (map peek kept)))
                  fresh (vec (sort (filter (comp rendered schemas) targets)))
                  admitted (vec (take (max 0 (- max-nodes (count nodes))) fresh))
                  ;; An identity-less owned entity is known by the component attribute reaching it.
                  reached (into {} (keep (fn [[_ a t]] (when ((::component-owners structure) a) [t a]))) kept)
                  nodes (into nodes (map (fn [e] [e (cond-> {::node-level (inc level) ::node-schema (schemas e)}
                                                      (reached e) (assoc ::attribute (reached e)))])) admitted)]
              (recur (inc level) admitted (into edges (filter (comp nodes peek)) kept)
                     (reduce #(update %1 (get schemas %2 ::unowned) (fnil conj #{}) %2) skipped (remove (comp rendered schemas) targets))
                     (cond-> (into cuts (keep (fn [[[e a] group]] (when (> (count group) per-attribute)
                                                                  (cut :seon.config.eval.result/max-collection level (count group) ::cut-source e ::attribute a))))
                                   groups)
                       (< (count admitted) (count fresh))
                       (conj (cut :seon.config.eval.result/max-nodes (inc level) (- (count fresh) (count admitted)))))
                     nodes))))))))

;;; ---------------------------------------------------------------------------
;;; Related data: an entity's one hop, beside its render
;;; ---------------------------------------------------------------------------

(def ^:private item-unit-keys
  "The root's own subject keys, which never reach a neighbour's render."
  [:seon.repl/handle :seon.render.value/root :seon.render.data/cursor :seon.render.data/path
   :seon.render.walk/attribute :seon.render.walk/lookup :seon.render.call/selected-producer
   :seon.related/attribute :seon.related/offset :seon.render/output])

(defn- related-item
  "One neighbour rendered at distance 0 by its own schema's declared render
  for `output`, called explicitly (AGENTS: a render that wants a nested thing
  rendered calls that render); its refusal is the item. Without a declared
  render for `output` the value printer shows it."
  {:malli/schema [:=> [:cat :map :seon.render/output :map] :seon.render/value]}
  [unit output {schema ::node-schema value :seon.render/value}]
  (let [producer (get (schema-properties (db/carried-projection (:seon.db/db unit)) schema) output)
        unit (assoc (apply dissoc unit item-unit-keys) :seon.render/distance 0)
        answer (when producer
                 (render/declared-projection (assoc unit :seon.render.call/selected-producer producer) value output))]
    (cond
      (:seon.print/node answer) (get-in answer [:seon.print/node :seon.print/value])
      (:seon.error/value answer) (:seon.error/value answer)
      (= output :seon.render/html) (render.value/render-html (assoc unit :seon.render/value value))
      :else value)))

(defn- shown-counts
  "Allocate `width` items breadth first from each group's target count."
  {:malli/schema [:=> [:cat [:sequential nat-int?] :int] [:map-of :int :int]]}
  [counts width]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                    (keep-indexed (fn [g n] (when (pos? n) [g n]))) counts)
         left width shown {}]
    (if (and (pos? left) (seq queue))
      (let [[g n] (peek queue)]
        (recur (cond-> (pop queue) (> n 1) (conj [g (dec n)]))
               (dec left) (update shown g (fnil inc 0))))
      shown)))

(defn related
  "`:seon.related/data`: the entity `lookup` names, one hop both ways, forward
  and reverse refs and value references (`graph` at distance 1), grouped by
  the attribute reaching each neighbour, newest first. Each item is rendered
  at distance 0 by its own schema's declared render for `output` (default
  AI); a neighbour whose schema declares none is counted by schema under
  `:seon.related/skipped`. The profile's `max-children` bounds the items
  rendered across all groups, breadth first; a group showing fewer than it
  reaches carries `:seon.related/count` and the call that shows the next
  ones, `attribute` selecting that group from `offset`.

  ## Examples

  (seon.render.walk/related {:seon.render.walk/lookup [:seon.fn/sym 'seon.test/run]
                             :seon.related/attribute :seon.fn/_calls :seon.related/offset 4})

  ## Costs

  One `graph` level plus one explicit render per shown item, unarmed on
  default 2026-09-28: the level 4-7 ms for `seon.db/write-owned-values-error`
  and 19 ms for `seon.test/run` (117 neighbours), an item 0.3-0.6 ms (a test's
  recorded result 3 ms)."
  {:malli/schema [:=> [:cat :seon.related/request]
                  [:or :seon.related/data :seon.render.walk/no-such-entity-error :seon.db/invalid-read-error]]}
  [{db :seon.db/db ctx :seon.sci.eval/ctx lookup :seon.render.walk/lookup profile :seon.render/profile
    selected :seon.related/attribute :as request}]
  (let [offset (long (get request :seon.related/offset 0))
        width (:seon.render.profile/max-children profile)
        output (get request :seon.render/output :seon.render/ai)
        caps (or (:seon.sci.admit/caps request) (:seon.sci.admit/caps (env/of ctx)))
        found (graph {:seon.db/db db :seon.render.walk/lookup lookup :seon.render/distance 1
                      :seon.sci.admit/caps (assoc caps :seon.config.eval.result/max-collection (+ offset width))})
        refused (some #(when (= :seon.db/invalid-read (::cut-bound %)) (:seon.error/value %)) (::cuts found))]
    (cond
      (not (::graph-root found)) found
      refused refused
      :else
      (let [root (::graph-root found)
            counts (into {} (keep #(when (= root (::cut-source %)) [(::attribute %) (::cut-count %)])) (::cuts found))
            groups (cond->> (sort-by (comp str key) (group-by second (filter #(= root (first %)) (::edges found))))
                     selected (filter #(= selected (key %))))
            targets (mapv (fn [[_ edges]] (drop offset (sort > (distinct (map peek edges))))) groups)
            shown (shown-counts (mapv count targets) width)]
        (cond-> (into (sorted-map-by #(compare (str %1) (str %2)))
                      (map-indexed
                       (fn [g [attribute edges]]
                         (let [total (get counts attribute (count (distinct (map peek edges))))
                               items (take (get shown g 0) (nth targets g))]
                           [attribute
                            (cond-> {:seon.related/items (mapv #(related-item request output (get (::nodes found) %)) items)}
                              (< (+ offset (count items)) total)
                              (assoc :seon.related/count total
                                     :seon.render/operations
                                     [(list 'seon.render.walk/related
                                            {:seon.render.walk/lookup lookup :seon.related/attribute attribute
                                             :seon.related/offset (+ offset (count items))})]))])))
                      groups)
          (and (not selected) (seq (::skipped found)))
          (assoc :seon.related/skipped (::skipped found)))))))

(defn- related-html
  "The related data as Hiccup: one group per attribute, its items as their
  HTML renders gave them, its count and next call when it shows fewer."
  {:malli/schema [:=> [:cat [:or :seon.related/data :seon.error/base]] :seon.render/hiccup]}
  [data]
  (if (:seon.error/message data)
    [:section {:class "seon-related"} [:p (:seon.error/message data)]]
    (into [:section {:class "seon-related"}]
          (map (fn [[attribute group]]
                 (if (= :seon.related/skipped attribute)
                   (into [:p {:class "seon-related-skipped"} "Not rendered: "]
                         (map (fn [[schema n]] [:code (str schema " " n) " "])) group)
                   [:details {:open true}
                    [:summary [:code (str attribute)]
                     (when-let [total (:seon.related/count group)] (str " " (count (:seon.related/items group)) " of " total))]
                    (into [:ul] (map #(vector :li %)) (:seon.related/items group))
                    (when-let [[operation] (:seon.render/operations group)] [:code (print.value/bounded-text operation)])])))
          data)))

(defn with-related
  "The root value's declared render `answer` with its entity's
  `:seon.related/data` beside the render, never inside it: assoc'd onto the AI
  face's data, a section after the HTML face. Unchanged when the value names
  no entity or one the database does not hold yet, the render is at
  distance 0, or the face is neither (REPL entries text)."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render/value :seon.render/output :map] :map]}
  [unit value output answer]
  (let [face (:seon.print/node answer)
        shown (:seon.print/value face)
        html? (= output :seon.render/html)
        lookup (render.value/value-identity (:seon.db/db unit) value)]
    (if-not (and lookup (:seon.sci.eval/ctx unit) (:seon.render/profile unit)
                 (= :seon.print/projected (:seon.print/face face))
                 (pos? (long (get unit :seon.render/distance 1)))
                 (if html? (vector? shown) (map? shown)))
      answer
      (let [data (related (assoc (apply dissoc unit item-unit-keys) :seon.render.walk/lookup lookup :seon.render/output output))]
        ;; a value whose identity no stored entity holds yet (a schema a run
        ;; registered before it settled) has no one hop: nothing exists to show
        (if (:seon.render.walk/missing-lookup data)
          answer
          (assoc-in answer [:seon.print/node :seon.print/value]
                    (if html?
                      [:div {:class "seon-related-entity"} shown (related-html data)]
                      (assoc shown :seon.related/data data))))))))

