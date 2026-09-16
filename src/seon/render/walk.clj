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
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.cluster.wake :as wake]
            [seon.eval :as evaluation]
            [seon.print :as print]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
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

(defn- declared-concerns
  [projection entity]
  (into []
        (distinct)
        (mapcat #(-> (get-in projection [:seon.schema.projection/forms
                                         (:seon.schema/key %)])
                    schema.form/schema-properties :seon.render/units)
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
                            attribute))))
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
  [projection database root distance width query-width]
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
        cache (:seon.schema.projection/compiled projection)
        cache-key [:seon.render.walk/root-pull-plan
                   (:seon.schema.projection/fingerprint projection)
                   (DatabaseSchemaIdentity. (:schema (db/schema-database database)))
                   selector]
        candidate (delay
                    {:seon.schema.projection/fingerprint
                     (:seon.schema.projection/fingerprint projection),
                     :seon.render.walk/selector selector,
                     :datahike.pull/plan
                     ((requiring-resolve 'datahike.pull-api/compile-pull-plan)
                       database
                       selector)})
        acquired (if cache
                   (get
                     (swap!
                       cache
                       #(if (contains? % cache-key) % (assoc % cache-key candidate)))
                     cache-key)
                   candidate)]
    (assoc @acquired :seon.render/distance distance :seon.sci.admit/caps caps)))

(defn- acquire-entity
  "Reuse one entity pull only while its recorded read evidence is current."
  [projection database installed plan lookup cache]
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
            value
            (binding [db/*read-evidence-sink* captured]
              (let [entity (db/pull database {:selector (:seon.render.walk/selector plan)
                                              :datahike.pull/plan (:datahike.pull/plan plan)
                                              :eid lookup})
                    reverse-selector
                    (when (:db/id entity)
                      (into []
                            (keep (fn [[attribute reverse?]]
                                    (when reverse?
                                      {(selector-key (reverse-attribute attribute)
                                                     (pull-width (:seon.sci.admit/caps plan)))
                                       [:db/id]})))
                            (connection-attributes projection installed entity)))]
                (if (seq reverse-selector)
                  (let [reverse-values (db/pull database reverse-selector lookup)]
                    (if (:seon.error/kind reverse-values)
                      reverse-values
                      (merge entity reverse-values)))
                  entity)))
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
        projection (or (:seon.schema/projection request)
                       (db/carried-projection database)
                       (sci.kernel/context-projection (:seon.sci.eval/ctx request)))
        installed (installed-attributes database)
        pulled (atom {})
        visited (atom #{})
        evidence (atom [])
        width (pull-width caps)]
    (letfn [(visit [lookup remaining]
              (let [value (or (get @pulled lookup)
                              (let [entry (acquire-entity projection database installed plan lookup cache)
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
                     (fn [result [display _attribute reverse?]]
                       (if-let [children (get value display)]
                         (let [expand #(visit (:db/id %) (max 0 (dec remaining)))]
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
                     (when (pos? remaining)
                       (map (fn [[attribute reverse?]]
                              [(if reverse? (reverse-attribute attribute) attribute)
                               attribute reverse?])
                            (connection-attributes projection installed value))))))))]
      (let [root (visit (:seon.render.walk/lookup request) distance)]
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
               properties (schema.form/attr-form-properties
                           (get-in projection [:seon.schema.projection/forms attribute]))
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
                             (scoped-attribute member)
                             (assoc :seon.render.walk/attribute
                                    (scoped-attribute member))
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
  "Render saved evaluations in chronological order through their schema pair.

  A named reply turn excludes its own evaluations, including source rows
  admitted with its opening transaction. Generated system evaluations remain
  visible. Without a turn id, every stored evaluation participates."
  {:malli/schema [:=> [:cat :seon.render.walk/history-request]
                  [:or [:vector :map] :seon.error/value]]}
  [{database :seon.db/db lookup :seon.render.walk/lookup :as request}]
  (let [agent-id (:seon.agent/id
                  (db/pull database [:seon.agent/id] lookup))
        evaluations (if-let [selector (:seon.db/pull-selector request)]
                      (evaluation/of-agent database agent-id selector)
                      (evaluation/of-agent database agent-id))
        selected (when-let [id (:seon.turn/id request)]
                   (db/pull database [:db/id :seon.turn.work/situation]
                            [:seon.turn/id id]))]
    (if (:seon.error/kind evaluations)
      evaluations
      (reduce
       (fn [entries saved]
         (let [lookup [:seon.cluster.eval/id (:seon.cluster.eval/id saved)]
               rendered (render/render-call
                         (assoc request :seon.render/value saved
                                        :seon.render/output :seon.render/ai
                                        :seon.render.call/id [lookup]))]
           (if (:seon.error/kind rendered)
             (reduced rendered)
             (conj entries
                   {:seon.render.history/call-id [lookup]
                    :seon.render/value saved
                    :seon.render.history/subject lookup
                    :seon.render.history/basis-transaction (:t saved)
                    :seon.render.history/bytes (or rendered "")}))))
       [] (if (and selected (not= :generate (:seon.turn.work/situation selected)))
            (remove #(= (:db/id selected)
                        (get-in % [:seon.cluster.eval/run :db/id])) evaluations)
            evaluations)))))
