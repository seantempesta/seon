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
            [seon.eval :as evaluation]
            [seon.print :as print]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
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

(defn root-selector
  "A concrete bidirectional pull selector for an agent-root distance.

  Every installed scalar attribute is enumerated. Every installed ref is an
  explicit forward and reverse subpattern, so Datahike records the canonical
  stored ref in the dependency plan and never widens component expansion to
  `:all`. The pull asks for one value beyond the query-work limit so the walk
  can emit an exact elision observation without a second read. Acquisition
  depth
  also stops at one less than the node cap: a deeper member's path alone would
  already consume more nodes than the result can retain."
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
        ;; THE PULL'S OWN LIMIT IS QUERY WORK, not the AI boundary's elision:
        ;; it decides how many stored refs Datahike is asked for, and the
        ;; walk's declared connection width decides how many the agent sees.
        width (pull-width caps)
        distance (bounded-acquisition-distance distance caps)
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
                                        :seon.turn/trigger))
                      (conj nested
                            {(selector-key :seon.turn/_trigger width)
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
      (selector-at distance))))

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
    [:or :seon.render.walk/lookup :seon.error/value]]}
  [database entity]
  (let [pulled (db/pull database '[*] entity)]
    (if (:seon.error/kind pulled)
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

(defn- connection-observation
  "One elision value naming WHICH bound cut an attribute's connections.

  Two bounds cut here and both must be reported. The pull asks for one value
  beyond its query-work limit, so a returned count past that limit is the
  pull's own truncation — and it is reported whether or not the request
  carried a presentation profile. Reading `Integer/MAX_VALUE` as \"nothing was
  cut\" was this project's named failure class, a check reporting health from
  absence of signal: with no profile the observation could never fire even
  though the pull had already stopped at 8,193 (measured 2026-09-07,
  research/verify-storage-bound-2026-09-07.md B5)."
  [attribute reverse? shown query-limit presentation-limit]
  (let [bound-by (if (<= (long query-limit) (long presentation-limit))
                   :seon.config.eval.result/max-collection
                   :seon.render.profile/max-children)]
    {:seon.render.walk/attribute attribute
     :seon.error/value
     {::elided true
      :seon.error/kind ::elided
      :seon.error/message
      (str "elided additional " (when reverse? "reverse ") attribute
           " connections past " shown
           ", bounded by " bound-by)
      :seon.error/data
      {:seon.render.walk/attribute attribute
       :seon.print/bound-by bound-by
       :seon.render.walk/shown (long shown)}}}))

(defn- acquisition-members
  [database root distance width query-width]
  (let [installed (installed-attributes database)
        refs (into []
                   (keep (fn [[attribute properties]]
                           (when (= :db.type/ref
                                    (:db/valueType properties))
                             attribute)))
                   installed)
        identities (db/populated-identity-attributes database)
        width (long width)
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
                          connection-attributes
                          (if (:seon.ns/name entity)
                            [[:seon.ns/requires false]
                             [:seon.ns/requires true]]
                            (concat
                             (map vector refs (repeat false))
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
                                         ;; THE TIGHTER OF THE TWO BOUNDS
                                         ;; decides what is shown, and the
                                         ;; observation names which one it
                                         ;; was.
                                         shown (min width query-width)
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
                                              attribute reverse? shown
                                              query-width width)))))
                                 connection-attributes))]
                      (if (or (pos? remaining)
                              (and (zero? remaining)
                                   (= :seon.cluster.message/from reached-by)))
                        (reduce-kv
                         (fn [result index connection]
                           (if-let [child
                                    (when (or (pos? remaining)
                                              (= :seon.turn/trigger
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

(defn join-membership
  "Join additional walk acquisitions into one stable membership.

  The original root and member order stay byte-identical. Each new member is
  appended at its first occurrence; repeated subjects collapse to the member
  already acquired from the same immutable database value."
  {:malli/schema [:=> [:cat :map [:vector :map]] :map]}
  [acquisition additions]
  (reduce
   (fn [joined addition]
     (reduce
      (fn [result lookup]
        (if (contains? (:seon.render.walk/members result) lookup)
          result
          (-> result
              (assoc-in [:seon.render.walk/members lookup]
                        (get-in addition [:seon.render.walk/members lookup]))
              (update :seon.render.walk/order conj lookup))))
      joined
      (:seon.render.walk/order addition)))
   acquisition
   additions))

(defn root-pull-plan
  "Acquire one immutable compiled pull plan for a schema generation and fit."
  {:malli/schema [:=> [:cat :seon.render.walk/acquisition-request] :map]}
  [{database :seon.db/db
    caps :seon.sci.admit/caps
    ctx :seon.sci.eval/ctx
    :as request}]
  (let [projection (or (sci.kernel/context-projection ctx)
                       (schema/current-projection)
                       {})
        distance (long (get request :seon.render/distance 1))
        selector (root-selector database distance caps)
        cache (:seon.schema.projection/compiled projection)
        cache-key [::root-pull-plan
                   (:seon.schema.projection/fingerprint projection)
                   (DatabaseSchemaIdentity.
                    (:schema (db/schema-database database)))
                   distance
                   caps]
        candidate
        (delay
          {:seon.schema.projection/fingerprint
           (:seon.schema.projection/fingerprint projection)
           :seon.render/distance distance
           :seon.sci.admit/caps caps
           :seon.render.walk/selector selector
           :datahike.pull/plan
           ((requiring-resolve 'datahike.pull-api/compile-pull-plan)
            database selector)})
        acquired
        (if cache
          (get (swap! cache
                      (fn [compiled]
                        (if (contains? compiled cache-key)
                          compiled
                          (assoc compiled cache-key candidate))))
               cache-key)
          candidate)]
    @acquired))

(defn- acquire-entity
  "Reuse one entity pull only while its recorded read evidence is current."
  [database plan lookup cache]
  (let [cache-key [::entity-pull lookup]
        previous (get @cache cache-key)]
    (if (and previous
             (identical? (:datahike.pull/plan plan) (:datahike.pull/plan previous))
             (not (:seon.error/kind (:seon.render.call/output previous)))
             (db/read-evidence-current? database (:seon.render.call/read-evidence previous)))
      (let [refreshed (render/refresh-read-evidence database previous)]
        (swap! cache
               (fn [current]
                 (if (identical? previous (get current cache-key))
                   (assoc current cache-key refreshed)
                   current)))
        refreshed)
      (let [captured (atom [])
            value (binding [db/*read-evidence-sink* captured]
                    (db/pull database {:selector (:seon.render.walk/selector plan)
                                       :datahike.pull/plan (:datahike.pull/plan plan)
                                       :eid lookup}))
            entry {:datahike.pull/plan (:datahike.pull/plan plan)
                   :seon.render.call/output value
                   :seon.render.call/basis-transaction (db/basis-t database)
                   :seon.render.call/read-evidence
                   (db/read-evidence @captured {:seon.db/retain-read-results? true})}]
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
        refs (into [] (keep (fn [[a properties]]
                             (when (= :db.type/ref (:db/valueType properties)) a)))
                   (installed-attributes database))
        connections (concat (map (fn [a] [a a false]) refs)
                            (map (fn [a] [(reverse-attribute a) a true]) refs))
        pulled (atom {})
        visited (atom #{})
        evidence (atom [])
        width (pull-width caps)]
    (letfn [(visit [lookup remaining reached-by]
              (let [value (or (get @pulled lookup)
                              (let [entry (acquire-entity database plan lookup cache)
                                    value (:seon.render.call/output entry)]
                                (swap! evidence into (:seon.render.call/read-evidence entry))
                                (swap! pulled assoc lookup value (:db/id value) value)
                                value))
                    eid (:db/id value)]
                (if (or (not eid) (@visited eid))
                  value
                  (do
                    (swap! visited conj eid)
                    (reduce
                     (fn [result [display attribute reverse?]]
                       (if-let [children (get value display)]
                         (let [expand #(visit (:db/id %) (max 0 (dec remaining)) attribute)]
                           (assoc result display
                                  (if (map? children)
                                    (expand children)
                                    (let [selected (into #{} (map :db/id)
                                                         (take width (if reverse?
                                                                       (sort-by :db/id > children)
                                                                       children)))]
                                      (mapv #(if (selected (:db/id %)) (expand %) %) children)))))
                         result))
                     value
                     (cond
                       (pos? remaining)
                       (if (:seon.ns/name value)
                         [[:seon.ns/requires :seon.ns/requires false]
                          [:seon.ns/_requires :seon.ns/requires true]]
                         connections)
                       (= :seon.cluster.message/from reached-by)
                       [[:seon.turn/_trigger :seon.turn/trigger true]]
                       :else []))))))]
      (let [root (visit (:seon.render.walk/lookup request) distance nil)]
        (when db/*read-evidence-sink*
          (swap! db/*read-evidence-sink* into
                 (map #(assoc % :seon.db/db database) @evidence)))
        {:seon.render.walk/root root
         :seon.render.call/read-evidence @evidence}))))

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
  (let [projection (or (sci.kernel/context-projection ctx)
                       (schema/current-projection)
                       {})]
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
                (acquisition-members database root
                                     (bounded-acquisition-distance distance caps)
                                     ;; No presentation width here: the
                                     ;; value renderer elides, the pull's
                                     ;; query-work bound is the only cut.
                                     (pull-width caps)
                                     (pull-width caps))))))))

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
                                    :seon.agent/namespace])]
    (when (map? namespace-ref) (:db/id namespace-ref))))

(defn- distance-cap-unit
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
     {::elided true
      :seon.error/kind ::elided
      :seon.error/message
      "elided connections at the requested distance cap"}}))

(defn neighborhood
  "Render the root acquisition's stable members without further discovery."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :seon.render.walk/units]}
  [{database :seon.db/db
    caps :seon.sci.admit/caps
    ctx :seon.sci.eval/ctx
    output :seon.render/output
    lookup :seon.render.walk/lookup
    :as request}]
  (let [projection (or (sci.kernel/context-projection ctx)
                       (schema/current-projection)
                       {})]
    (schema/call-with-projection
     projection
     (fn []
       (let [distance (long (get request :seon.render/distance 1))
             acquisition (or (:seon.render.walk/root-acquisition request)
                             (root-acquisition request))
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
             {::no-such-entity true
              :seon.error/kind ::no-such-entity
              :seon.error/message
              (str "Nothing in the database answers to " (pr-str lookup) ".")}}]
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
;;; Assembly — the ai kind
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The generated opening episode
;;; ---------------------------------------------------------------------------

(defn- reference-keys
  "Comparable spellings of one structural reference.

  Program identities retain their declared database representation (for
  example a function identity is stored as a string), while values introduce
  real Clojure symbols. This generic normalization relates those two spellings
  without knowing which domain shape supplied either one."
  [reference]
  (cond
    (and (vector? reference) (= 2 (count reference)))
    (into #{reference} (reference-keys (second reference)))

    (symbol? reference)
    #{reference (str reference)}

    :else
    #{reference}))

(defn- introduced-subject?
  [frontier subject]
  (boolean (some frontier (reference-keys subject))))

(defn form-symbols
  "Qualified and namespace symbols structurally present in one form."
  {:malli/schema [:=> [:cat :seon.repl/form] [:set :symbol]]}
  [form]
  (into #{}
        (filter (fn [value]
                  (and (symbol? value)
                       (or (namespace value)
                           (str/includes? (str value) ".")))))
        (tree-seq coll? seq form)))

(defn- explained-symbol?
  [explained subject candidate-symbol]
  (or (some explained (reference-keys candidate-symbol))
      (some (reference-keys subject) (reference-keys candidate-symbol))))

(defn ordered-episode
  "Derive the deterministic executable prefix from one bounded pull result.

  Generation is macroexpansion to a teaching fixed point: before emitting a
  form, recursively select explanations for every qualified or namespace
  symbol it contains. A candidate is ready only when its subject appeared in
  an earlier settled value and every other symbol in its form has already been
  explained by a settled candidate. The explanation form may name its own
  subject. Resolve means teach; a subject or symbol with no candidate in the
  bounded pull fails closed by leaving the dependent form ungenerated.

  The pull decides membership and carries fact order; introductions decide
  readiness without re-sorting that order. A reference outside the pulled
  neighborhood grows no context.

  The returned vector contains the settled prefix plus at most one next entry
  awaiting execution. Calling this pure function again with that entry's
  settled evaluation extends the same byte-stable prefix. Saved shown text
  is an observation, never decoded to recover references. Only an actual
  print node supplied by an older caller can introduce structural references."
  {:malli/schema [:=> [:cat :seon.repl/pull-result] :seon.repl/episode]}
  [{root-key :seon.repl/root-key
    candidates :seon.repl/candidates
    settled :seon.repl/settled
    identity-attributes :seon.print/identity-attributes
    intent-subjects :my.plan/intent-subjects}]
  (let [settled-by-key
        (into {} (map (juxt :seon.repl/key identity)) settled)
        ordered-candidates (vec candidates)]
    (loop [remaining ordered-candidates
           frontier (into #{} (mapcat reference-keys) intent-subjects)
           explained #{}
           episode []]
      (let [selected
            (if (empty? episode)
              (some #(when (= root-key (:seon.repl/key %)) %) remaining)
              (first
               (filter
                (fn [{subject :seon.repl/subject entry :seon.repl/entry
                      previous-key :seon.repl/previous-key}]
                  (and (introduced-subject? frontier subject)
                       (or (nil? previous-key)
                           (some #(= previous-key (:seon.repl/key %)) episode))
                       (every? #(explained-symbol? explained subject %)
                               (form-symbols (:seon.repl/form entry)))))
                remaining)))]
        (if-not selected
          episode
          (let [entry (assoc (:seon.repl/entry selected)
                             :seon.repl/key (:seon.repl/key selected)
                             :seon.repl/subject (:seon.repl/subject selected))
                episode (conj episode entry)
                settlement (get settled-by-key (:seon.repl/key selected))
                settled-node (:seon.sci.admit/print-node settlement)]
            (if-not settlement
              episode
              (recur (into [] (remove #(= (:seon.repl/key selected)
                                           (:seon.repl/key %))
                                      remaining))
                     (into frontier
                           (mapcat reference-keys)
                           (when (print/node? settled-node)
                             (print/references identity-attributes settled-node)))
                     (into explained
                           (reference-keys (:seon.repl/subject selected)))
                     episode))))))))

;;; ---------------------------------------------------------------------------
;;; The agent's history
;;; ---------------------------------------------------------------------------

(defn history
  "Render saved evaluations in chronological order through their schema pair."
  {:malli/schema [:=> [:cat :seon.render.walk/history-request]
                  [:or [:vector :map] :seon.error/value]]}
  [{database :seon.db/db lookup :seon.render.walk/lookup :as request}]
  (let [agent-id (:seon.agent/id
                  (db/pull database [:seon.agent/id] lookup))
        evaluations (evaluation/of-agent database agent-id)]
    (if (:seon.error/kind evaluations)
      evaluations
      (reduce
       (fn [entries saved]
         (let [lookup [:seon.cluster.eval/id (:seon.cluster.eval/id saved)]
               rendered (render/render-ai
                         (assoc request :seon.render/value saved))]
           (if (:seon.error/kind rendered)
             (reduced rendered)
             (conj entries
                   {:seon.render.history/call-id [lookup]
                    :seon.render.history/subject lookup
                    :seon.render.history/basis-transaction (:t saved)
                    :seon.render.history/bytes (or rendered "")}))))
       [] evaluations))))
