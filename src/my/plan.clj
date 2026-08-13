(ns my.plan
  "The fact-first union of derived obligations and authored work."
  (:require [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.print :as print]
            [seon.render.value :as render.value]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.plan.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Authored graph derivation
;;; ---------------------------------------------------------------------------

(def rules
  "Datalog rules deriving authored plan graph state from current facts."
  '[[(descendant ?ancestor ?node)
     [?node :my.plan.item/parent ?ancestor]]
    [(descendant ?ancestor ?node)
     [?middle :my.plan.item/parent ?ancestor]
     (descendant ?middle ?node)]
    [(leaf ?item)
     (not-join [?item] [?child :my.plan.item/parent ?item])]
    [(open-work ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (leaf ?item)]
    [(open-work ?item)
     (descendant ?item ?leaf)
     [?leaf :my.plan.item/id]
     (not-join [?leaf] [?leaf :my.plan.item/completed-at _])
     (leaf ?leaf)]
    [(blocked ?item)
     [?item :my.plan.item/needs ?dependency]
     (open-work ?dependency)]
    [(ready ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (leaf ?item)
     (not (blocked ?item))]
    [(ready ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (not (leaf ?item))
     (not (open-work ?item))
     (not (blocked ?item))]])

(def ^:private item-selector
  '[:my.plan.item/id
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/completed-at
    :my.plan.item/expected-result
    {:my.plan.item/agent [:db/id]}
    {:my.plan.item/parent [:db/id]}
    {:my.plan.item/needs [:db/id]}
    {:my.plan.item/about [:db/id]}])

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

(defn- refuse!
  [kind message data]
  (throw
   (ex-info message
            {:seon.error/kind kind
             :seon.error/message message
             :seon.error/data data})))

(defn- agent-eid
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.cluster.agent/id ?agent-id]]
        database agent-id))

(defn- item-eid
  [database item-id]
  (db/q '[:find ?item .
          :in $ ?item-id
          :where [?item :my.plan.item/id ?item-id]]
        database item-id))

(defn- ref-eid
  [database reference]
  (some-> (db/entity database reference) :db/id))

(defn- item-owner
  [database item]
  (db/q '[:find ?agent .
          :in $ ?item
          :where [?item :my.plan.item/agent ?agent]]
        database item))

(defn- owned-item-eid
  [database agent-entity item-reference member]
  (let [item (ref-eid database item-reference)]
    (when-not item
      (refuse! ::item-reference-not-found
               (str "Plan item reference " (pr-str item-reference)
                    " does not exist.")
               {member item-reference}))
    (when (not= agent-entity (item-owner database item))
      (refuse! ::item-reference-not-owned
               (str "Plan item reference " (pr-str item-reference)
                    " belongs to another agent.")
               {member item-reference}))
    item))

(defn- resolve-item-refs
  [database agent-entity request]
  (let [parent
        (when-some [reference (:my.plan.item/parent request)]
          (owned-item-eid database agent-entity reference :my.plan.item/parent))
        needs
        (into #{}
              (map #(owned-item-eid database agent-entity % :my.plan.item/needs))
              (:my.plan.item/needs request))
        about
        (into #{}
              (map
               (fn [reference]
                 (or (ref-eid database reference)
                     (refuse! ::subject-not-found
                              (str "Plan subject " (pr-str reference)
                                   " does not exist.")
                              {:my.plan.item/about reference}))))
              (:my.plan.item/about request))]
    (cond-> request
      parent (assoc :my.plan.item/parent parent)
      (seq needs) (assoc :my.plan.item/needs needs)
      (seq about) (assoc :my.plan.item/about about))))

(defn- add-item-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)]
    (when-not agent-entity
      (refuse! ::agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.cluster.agent/id agent-id}))
    (when (item-eid database item-id)
      (refuse! ::identity-exists
               (str "Plan item " (pr-str item-id) " already exists.")
               {:my.plan.item/id item-id}))
    (let [resolved (resolve-item-refs database agent-entity request)
          tempid "new-plan-item"
          item
          (-> resolved
              (dissoc :seon.cluster.agent/id :my.plan/anchor?)
              (assoc :db/id tempid :my.plan.item/agent agent-entity))]
      (cond-> [item]
        (:my.plan/anchor? request)
        (conj [:db/add agent-entity :my.plan/anchor tempid])))))

(defn- complete-item-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)
        item (item-eid database item-id)]
    (when-not item
      (refuse! ::not-found
               (str "There is no plan item named " (pr-str item-id) ".")
               {:my.plan.item/id item-id}))
    (when (not= agent-entity (item-owner database item))
      (refuse! ::not-owned
               (str "Plan item " (pr-str item-id)
                    " belongs to another agent.")
               {:my.plan.item/id item-id
                :seon.cluster.agent/id agent-id}))
    (if (db/q '[:find ?completed-at .
                :in $ ?item
                :where [?item :my.plan.item/completed-at ?completed-at]]
              database item)
      []
      (cond->
       [[:db/add item :my.plan.item/completed-at
         (:my.plan.item/completed-at request)]]
        (= item
               (db/q '[:find ?anchor .
                       :in $ ?agent
                       :where [?agent :my.plan/anchor ?anchor]]
                     database agent-entity))
        (conj [:db/retract agent-entity :my.plan/anchor item])))))

(defn- transact-plan!
  [connection agent-id tx-data]
  (db/transact!
   connection
   {:tx-data tx-data
    :tx-meta {:seon.db/user [:seon.cluster.agent/id agent-id]}}))

(defn- item-value
  [value]
  (cond
    (and (map? value) (map? (:seon.render/value value)))
    (render.value/transacted (:seon.render/value value))

    (map? value)
    (render.value/transacted value)

    :else value))

(defn- item-row
  [database item]
  (let [row (db/pull database item-selector item)]
    (if (error-value? row) row (item-value row))))

(defn add!
  "Add one new authored plan item for the calling agent."
  {:malli/schema
   [:=> [:cat :my.plan.item/add-request
         :seon.db/connection :seon.cluster.agent/id]
    [:or :my.plan.item/item :seon.error/value]]}
  [item connection agent-id]
  (let [request (assoc item :seon.cluster.agent/id agent-id)
        result
        (transact-plan!
         connection agent-id
         [[:db.fn/call #'add-item-call request]])]
    (if (error-value? result)
      result
      (item-row (:db-after result)
                [:my.plan.item/id (:my.plan.item/id item)]))))

(defn complete!
  "Complete one authored item by asserting its completion instant."
  {:malli/schema
   [:=> [:cat :my.plan.item/id :my.plan.item/completed-at
         :seon.db/connection :seon.cluster.agent/id]
    [:or :my.plan.item/item :seon.error/value]]}
  [item-id completed-at connection agent-id]
  (let [result
        (transact-plan!
         connection agent-id
         [[:db.fn/call #'complete-item-call
           {:my.plan.item/id item-id
            :my.plan.item/completed-at completed-at
            :seon.cluster.agent/id agent-id}]])]
    (if (error-value? result)
      result
      (item-row (:db-after result) [:my.plan.item/id item-id]))))

;;; ---------------------------------------------------------------------------
;;; Current reads — authored facts plus structurally separate derived arms
;;; ---------------------------------------------------------------------------

(def ^:private ready-query
  '[:find [?item ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.cluster.agent/id ?agent-id]
    [?item :my.plan.item/agent ?agent]
    (ready ?item)])

(def ^:private blocked-query
  '[:find [?item ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.cluster.agent/id ?agent-id]
    [?item :my.plan.item/agent ?agent]
    [?item :my.plan.item/id]
    (not-join [?item] [?item :my.plan.item/completed-at _])
    (blocked ?item)])

(defn- item-order
  [anchor item]
  [(if (= anchor (:my.plan.item/id item)) 0 1)
   (:my.plan.item/title item)
   (:my.plan.item/id item)])

(defn- items-for-eids
  [database anchor eids]
  (let [rows (mapv #(item-row database %) eids)]
    (if-let [error (some #(when (error-value? %) %) rows)]
      error
      (vec (sort-by #(item-order anchor %) rows)))))

(defn- anchor-id
  [database agent-id]
  (db/q '[:find ?item-id .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?agent :my.plan/anchor ?item]
          [?item :my.plan.item/id ?item-id]]
        database agent-id))

(defn ready
  "List this agent's authored items that are ready now."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
    [:or :my.plan/ready-items :seon.error/value]]}
  [database agent-id]
  (let [eids (db/q ready-query database rules agent-id)]
    (if (error-value? eids)
      eids
      (items-for-eids database (anchor-id database agent-id) eids))))

(defn- message-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?message ?id ?content
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?message :seon.cluster.message/to ?agent]
                [?message :seon.cluster.message/id ?id]
                [?message :seon.cluster.message/content ?content]
                (not-join [?message]
                  [?run :seon.cluster.run/trigger ?message])]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[message id content]]
         {:my.plan/obligation-source :message
          :my.plan/obligation-id id
          :my.plan/obligation-title content
          :my.plan/obligation-ref message})
       (sort-by second rows)))))

(defn- run-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?run ?id
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?run :seon.cluster.run/agent ?agent]
                [?run :seon.cluster.run/id ?id]
                (not-join [?run] [?run :seon.cluster.run/closed-at _])]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[run id]]
         {:my.plan/obligation-source :run
          :my.plan/obligation-id id
          :my.plan/obligation-title (str "Finish open run " (pr-str id) ".")
          :my.plan/obligation-ref run})
       (sort-by second rows)))))

(defn- test-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?test ?symbol ?failures ?errors
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?agent :seon.cluster.agent/namespace ?namespace]
                [?test :seon.test/ns ?namespace]
                [?test :seon.test/sym ?symbol]
                [?test :seon.test/fail-count ?failures]
                [?test :seon.test/error-count ?errors]
                [(+ ?failures ?errors) ?red]
                [(> ?red 0)]]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[test-entity test-symbol failures errors]]
         {:my.plan/obligation-source :test
          :my.plan/obligation-id test-symbol
          :my.plan/obligation-title
          (str "Fix " test-symbol " (" failures " failures, " errors " errors).")
          :my.plan/obligation-ref test-entity})
       (sort-by second rows)))))

(defn- obligations
  [database agent-id]
  (let [arms [(message-obligations database agent-id)
              (run-obligations database agent-id)
              (test-obligations database agent-id)]]
    (if-let [error (some #(when (error-value? %) %) arms)]
      error
      (into [] cat arms))))

(defn- completion-limit
  [database agent-id]
  (let [cluster-name
        (db/q '[:find ?cluster-name .
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?agent :seon.cluster.agent/cluster ?cluster]
                [?cluster :seon.cluster/name ?cluster-name]]
              database agent-id)
        effective (when cluster-name (config/effective database cluster-name))]
    (long
     (:seon.config.render.agent/max-children
      (if (and (map? effective) (not (error-value? effective)))
        effective
        (config/defaults))))))

(defn- completion-view
  [database agent-id]
  (let [rows
        (db/q '[:find ?item ?completed-at ?id
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?item :my.plan.item/agent ?agent]
                [?item :my.plan.item/id ?id]
                [?item :my.plan.item/completed-at ?completed-at]]
              database agent-id)]
    (if (error-value? rows)
      rows
      (let [ordered
            (sort-by
             (fn [[_ completed-at id]]
               [(- (.getTime ^java.util.Date completed-at)) id])
             rows)
            total (count ordered)
            limit (completion-limit database agent-id)
            recent-eids (mapv first (take limit ordered))
            recent (mapv #(item-row database %) recent-eids)
            omitted (- total (count recent))]
        (if-let [error (some #(when (error-value? %) %) recent)]
          error
          (cond-> {:my.plan/recent-completions recent}
            (pos? omitted)
            (assoc
             :my.plan/older-completions
             {:seon.print/face :seon.print/elided
              :seon.print/omitted omitted
              :seon.print/elision-unit :children
              :seon.render.data/total total
              :seon.render.data/path [:my.plan/recent-completions]
              :seon.render.data/next-offset (count recent)
              :seon.render.profile/id :seon.render.profile/agent
              :seon.print/requery-id [:seon.cluster.agent/id agent-id]})))))))

(defn plan
  "Read this agent's derived obligations and authored plan facts."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
    [:or :my.plan/view :seon.error/value]]}
  [database agent-id]
  (let [agent-entity (agent-eid database agent-id)]
    (if (error-value? agent-entity)
      agent-entity
      (if-not agent-entity
        {:seon.error/kind ::agent-not-found
         :seon.error/message
         (str "There is no agent named " (pr-str agent-id) ".")
         :seon.error/data {:seon.cluster.agent/id agent-id}}
        (let [anchor (anchor-id database agent-id)
              ready-eids (db/q ready-query database rules agent-id)
              blocked-eids (db/q blocked-query database rules agent-id)
              ready-items
              (if (error-value? ready-eids)
                ready-eids
                (items-for-eids database anchor ready-eids))
              blocked-items
              (if (error-value? blocked-eids)
                blocked-eids
                (items-for-eids database anchor blocked-eids))
              derived (obligations database agent-id)
              completions (completion-view database agent-id)
              values [ready-items blocked-items derived completions]]
          (if-let [error (some #(when (error-value? %) %) values)]
            error
            (cond->
             {:seon.cluster.agent/id agent-id
              :my.plan/obligations derived
              :my.plan/ready ready-items
              :my.plan/blocked blocked-items
              :my.plan/recent-completions
              (:my.plan/recent-completions completions)}
              anchor (assoc :my.plan/anchor [:my.plan.item/id anchor])
              (:my.plan/older-completions completions)
              (assoc :my.plan/older-completions
                     (:my.plan/older-completions completions)))))))))

;;; ---------------------------------------------------------------------------
;;; Declared AI and HTML projections
;;; ---------------------------------------------------------------------------

(defn- item-line
  [item]
  (str (pr-str (:my.plan.item/id item)) ": " (:my.plan.item/title item)
       (when-let [expected (:my.plan.item/expected-result item)]
         (str " — expect " expected))))

(defn render-item-ai
  "Render one authored plan item as text."
  {:malli/schema [:=> [:cat :my.plan.item/item] :seon.render/ai]}
  [item]
  (str "Plan item " (item-line (item-value item))))

(defn render-item-html
  "Render one authored plan item as Hiccup."
  {:malli/schema [:=> [:cat :my.plan.item/item] :seon.render/hiccup]}
  [item]
  (let [item (item-value item)]
    (cond-> [:article {:class "seon-family-entry my-plan-item"}
             [:h3 (:my.plan.item/title item)]
             [:p {:class "my-plan-id"} (pr-str (:my.plan.item/id item))]]
      (:my.plan.item/description item)
      (conj [:p (:my.plan.item/description item)])

      (:my.plan.item/expected-result item)
      (conj [:p {:class "my-plan-expected"}
             (str "Expected: " (:my.plan.item/expected-result item))]))))

(defn render-ready-items-ai
  "Render the ready authored plan frontier as text."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/ai]}
  [items]
  (if (seq items)
    (str "Ready authored work (" (count items) "):\n"
         (str/join "\n" (map #(str "- " (item-line %)) items)))
    "No authored work is ready."))

(defn render-ready-items-html
  "Render the ready authored plan frontier as Hiccup."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/hiccup]}
  [items]
  (into [:section {:class "seon-family-entry my-plan-ready"}
         [:h3 (str "Ready authored work (" (count items) ")")]]
        (map render-item-html)
        items))

(defn- section-ai
  [title values line]
  (str title " (" (count values) ")"
       (when (seq values)
         (str ":\n" (str/join "\n" (map #(str "- " (line %)) values))))))

(defn render-plan-ai
  "Render the current plan union as bounded text."
  {:malli/schema [:=> [:cat :my.plan/view] :seon.render/ai]}
  [view]
  (let [older (:my.plan/older-completions view)]
    (str/join
     "\n\n"
     (cond->
      [(str "Plan for " (pr-str (:seon.cluster.agent/id view))
            (when-let [anchor (:my.plan/anchor view)]
              (str " — anchor " (pr-str anchor))))
       (section-ai
        "Derived obligations"
        (:my.plan/obligations view)
        (fn [obligation]
          (str (name (:my.plan/obligation-source obligation)) " "
               (pr-str (:my.plan/obligation-id obligation)) ": "
               (:my.plan/obligation-title obligation))))
       (section-ai "Ready authored work" (:my.plan/ready view) item-line)
       (section-ai "Blocked authored work" (:my.plan/blocked view) item-line)
       (section-ai "Recent completions"
                   (:my.plan/recent-completions view) item-line)]
       older (conj (print/render-elision-ai older))))))

(defn- item-list-html
  [title items css-class]
  (into [:section {:class css-class}
         [:h3 (str title " (" (count items) ")")]]
        (map render-item-html)
        items))

(defn render-plan-html
  "Render the current plan union as bounded Hiccup."
  {:malli/schema [:=> [:cat :my.plan/view] :seon.render/hiccup]}
  [view]
  (let [derived-obligations (:my.plan/obligations view)
        older (:my.plan/older-completions view)]
    (cond->
     [:section {:class "seon-family-entry my-plan"}
      [:h2 (str "Plan for " (pr-str (:seon.cluster.agent/id view)))]
      (when-let [anchor (:my.plan/anchor view)]
        [:p {:class "my-plan-anchor"} (str "Anchor " (pr-str anchor))])
      (into [:section {:class "my-plan-obligations"}
             [:h3 (str "Derived obligations (" (count derived-obligations) ")")]]
            (map
             (fn [obligation]
               [:p (str (name (:my.plan/obligation-source obligation)) " "
                        (pr-str (:my.plan/obligation-id obligation)) ": "
                        (:my.plan/obligation-title obligation))]))
            derived-obligations)
      (item-list-html "Ready authored work" (:my.plan/ready view)
                      "my-plan-ready")
      (item-list-html "Blocked authored work" (:my.plan/blocked view)
                      "my-plan-blocked")
      (item-list-html "Recent completions"
                      (:my.plan/recent-completions view)
                      "my-plan-completed")]
      older
      (conj [:p {:class "my-plan-elision"}
             (print/render-elision-ai older)]))))
