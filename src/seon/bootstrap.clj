(ns seon.bootstrap
  "The fact-authored bootstrap run shared by every new agent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.cluster.run :as run]
            [seon.db :as db]
            [seon.schema :as schema]))

(def plan-id
  "The inherited bootstrap-plan identity on every cluster branch."
  :default)

(defn- plan-summary
  [unit]
  (let [database (:seon.db/db unit)
        eid (:db/id unit)
        namespace-sources
        (if (and database eid)
          (db/q '[:find [?namespace-source ...]
                  :in $ ?plan
                  :where
                  [?plan :seon.bootstrap.plan/forms ?form]
                  [?form :seon.bootstrap.plan.form/namespace-source
                   ?namespace-source]]
                database eid)
          (keep :seon.bootstrap.plan.form/namespace-source
                (:seon.bootstrap.plan/forms unit)))
        help-texts
        (if (and database eid)
          (db/q '[:find [?help-text ...]
                  :in $ ?plan
                  :where
                  [?plan :seon.bootstrap.plan/forms ?form]
                  [?form :seon.bootstrap.plan.form/help-text ?help-text]]
                database eid)
          (keep :seon.bootstrap.plan.form/help-text
                (:seon.bootstrap.plan/forms unit)))
        counts (frequencies namespace-sources)]
    {:forms (count namespace-sources)
     :agent (get counts :agent 0)
     :user (get counts :user 0)
     :help-texts (count help-texts)
     :help-text-tokens (reduce + 0 (map tokens/estimate help-texts))}))

(defn render-ai
  "`:seon.render/ai` — one bootstrap plan without its source payloads."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (:seon.bootstrap.plan/id unit)]
    (let [{:keys [forms agent user help-texts help-text-tokens]}
          (plan-summary unit)]
      (str "Bootstrap plan " id " · digest "
           (:seon.bootstrap.plan/digest unit) ".\n"
           forms " ordered evaluation forms: " agent " agent, " user
           " user; " help-texts " help-text form"
           (when-not (= 1 help-texts) "s") " · approximately "
           help-text-tokens " tokens."))))

(defn render-html
  "`:seon.render/html` — one readable bootstrap-plan card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [id (:seon.bootstrap.plan/id unit)]
    (let [{:keys [forms agent user help-texts help-text-tokens]}
          (plan-summary unit)]
      [:article {:class "seon-family-entry seon-bootstrap-plan-entry"}
       [:h3 (str "Bootstrap plan " id)]
       [:dl
        [:div [:dt "Digest"]
         [:dd [:code (:seon.bootstrap.plan/digest unit)]]]
        [:div [:dt "Ordered forms"] [:dd (str forms)]]
        [:div [:dt "Namespace source"]
         [:dd (str agent " agent / " user " user")]]
        [:div [:dt "Help text"]
         [:dd (str help-texts " form"
                   (when-not (= 1 help-texts) "s") " · approximately "
                   help-text-tokens " tokens")]]]])))

(def ^:private resource-path
  "seon/bootstrap.edn")

(def ^:private namespace-token
  "{{seon.ns/name}}")

(defn packaged-forms
  "The shipped bootstrap form maps read from the classpath EDN resource."
  {:malli/schema [:=> [:cat] :seon.bootstrap/default-forms]}
  []
  (let [resource (io/resource resource-path)
        forms (when resource (edn/read-string (slurp resource)))]
    (when-not resource
      (throw
       (ex-info "The shipped bootstrap EDN resource is absent."
                {:seon.error/kind :seon.bootstrap/resource-absent
                 :seon.bootstrap/resource resource-path})))
    (when-not (schema/valid-candidate-value?
               :seon.bootstrap/default-forms forms)
      (throw
       (ex-info "The shipped bootstrap EDN resource is invalid."
                {:seon.error/kind :seon.bootstrap/resource-invalid
                 :seon.bootstrap/resource resource-path
                 :seon.bootstrap/explanation
                 (schema/explain-candidate-value
                  :seon.bootstrap/default-forms forms)})))
    forms))

(defn help-text
  "The legacy authored help payload, retained only until plan deletion."
  {:malli/schema [:=> [:cat] :string]}
  []
  (:seon.bootstrap.plan.form/help-text (first (packaged-forms))))

(defmacro help
  "Read the calling agent's live situation.

  The returned situation is the generated opening's control surface. Its
  schema members are the seeds: adding a derived member to that shape is how
  the opening grows. The value is pulled live from current facts; no member is
  copied onto the agent as stored presentation state."
  []
  (list 'seon.bootstrap/situation))

(defn situation
  "Derive one agent's live opening seeds from current database facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.cluster.agent/id]
    [:or :seon.cluster.agent/situation :seon.error/value]]}
  [database agent-id]
  (let [agent
        (db/pull database
                 '[:seon.cluster.agent/id
                   {:seon.cluster.agent/namespace
                    [:db/id :seon.ns/name
                     {:seon.ns/requires [:seon.ns/name]}]}
                   {:seon.cluster.agent/run [:seon.cluster.run/id]}]
                 [:seon.cluster.agent/id agent-id])]
    (if-not (:seon.cluster.agent/id agent)
      {:seon.error/kind :seon.cluster.agent/no-such-agent
       :seon.error/message (str "No agent has id " (pr-str agent-id) ".")
       :seon.error/data {:seon.cluster.agent/id agent-id}}
      (let [namespace (:seon.cluster.agent/namespace agent)
            run (:seon.cluster.agent/run agent)
            unread
            (or (db/q '[:find (count ?message) .
                        :in $ ?agent-id
                        :where
                        [?agent :seon.cluster.agent/id ?agent-id]
                        [?message :seon.cluster.message/to ?agent]
                        (not-join [?message]
                          [?run :seon.cluster.run/trigger ?message])]
                      database agent-id)
                0)]
        (cond->
         {:seon.cluster.agent/id agent-id
          :seon.cluster.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace)]
          :seon.cluster.agent/unread-message-count (long unread)
          :seon.cluster.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace)
               (map :seon.ns/name)
               sort
               vec)}
          run
          (assoc :seon.cluster.agent/open-run-ref
                 [:seon.cluster.run/id
                  (:seon.cluster.run/id run)]))))))

(defmacro dir
  "List the public names in namespace-name through Clojure's REPL macro."
  [namespace-name]
  (list 'clojure.repl/dir namespace-name))

(defmacro doc
  "Print documentation for symbol through Clojure's REPL macro."
  [documented-symbol]
  (list 'clojure.repl/doc documented-symbol))

(defn run-id
  "The deterministic id of an agent's system-authored bootstrap run."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.run/id]}
  [agent-id]
  (str "bootstrap:" agent-id))

(defn task-message-id
  "The deterministic identity of one agent's real bootstrap task message."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.message/id]}
  [agent-id]
  (str "bootstrap-task:" agent-id))

(defn task-message
  "The small real assignment that the shipped bootstrap episode completes."
  {:malli/schema [:=> [:cat] :seon.cluster.message/content]}
  []
  (str "Define a durable contracted function named largest that returns the "
       "row with the greatest :example/amount, or {} for empty input. Call "
       "it once, query its stored :seon.fn/spec, then complete with a short "
       "reply naming what you built and its contract."))

(defn- digest-value
  [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

(defn supervision-run-id
  "The deterministic identity of root's first-agent supervision run."
  {:malli/schema [:=> [:cat] :seon.cluster.run/id]}
  []
  "bootstrap-supervision:root")

(defn- settled-form-sources
  [database agent-id]
  (db/q '[:find [?source ...]
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          [?form :seon.cluster.run.form/run ?run]
          [?form :seon.cluster.run.form/ordinal ?ordinal]
          [?form :seon.cluster.run.form/source ?source]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]]
        database agent-id))

(defn- calls-symbol?
  [source called]
  (try
    (boolean
     (some #{called}
           (tree-seq coll? seq (edn/read-string source))))
    (catch Throwable _
      false)))

(defn- contains-history-query?
  [source]
  (try
    (let [elements (set (tree-seq coll? seq (edn/read-string source)))]
      (and (contains? elements :seon.cluster.eval/run)
           (contains? elements :seon.cluster.run.form/run)))
    (catch Throwable _
      false)))

(defn- root-read-agent-history?
  [database]
  (boolean
   (some #(or (calls-symbol? % 'seon.render.transcript/history-entries)
              (contains-history-query? %))
         (settled-form-sources database "root"))))

(defn- root-messaged-agent?
  [database]
  (some?
   (db/q '[:find ?message .
           :where
           [?root :seon.cluster.agent/id "root"]
           [?message :seon.cluster.message/from ?root]]
         database)))

(defn supervision-tx
  "Open root's self-erasing two-form lesson when its first agent arrives.

  Each action is omitted when root's durable history already proves it. The
  returned forms use the ordinary system-run transaction path and therefore
  acquire ordinary execution receipts."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/process
                       :seon.cluster.run/opened-at
                       :seon.cluster.agent/id]
                  :seon.store/transaction-data]}
  [database process opened-at agent-id]
  (let [run-id (supervision-run-id)
        already-open? (some? (db/pull database [:db/id]
                                      [:seon.cluster.run/id run-id]))
        read? (not (root-read-agent-history? database))
        send? (not (root-messaged-agent? database))
        read-expression
        (str "(db/q {:query '[:find ?at ?source ?result "
             ":in $ ?agent-id :where "
             "[?agent :seon.cluster.agent/id ?agent-id] "
             "[?run :seon.cluster.run/agent ?agent] "
             "[?form :seon.cluster.run.form/run ?run] "
             "[?form :seon.cluster.run.form/ordinal ?ordinal] "
             "[?form :seon.cluster.run.form/source ?source] "
             "[?receipt :seon.cluster.eval/run ?run] "
             "[?receipt :seon.cluster.eval/ordinal ?ordinal] "
             "[?receipt :seon.cluster.eval/at ?at] "
             "[?receipt :seon.cluster.eval/result-edn ?result]] "
             ":args [(db/db) " (pr-str agent-id) "] "
             ":order-by '[?at :desc] :limit 2})")
        read-source
        (if send?
          read-expression
          (str "(let [history " read-expression "] "
               "(assoc (run/complete \"Read " agent-id
               "'s recent history.\") :my.run/supervision history))"))
        send-expression
        (str "(my.message/send " (pr-str agent-id)
             " \"What are you doing?\")")
        send-source
        (str "(merge " send-expression
             " (run/complete \"Read " agent-id
             "'s recent history and asked what it is doing.\"))")
        sources
        (cond-> []
          read?
          (conj {:seon.cluster.run.form/source read-source
                 :seon.ns/name 'my.agents.root})
          send?
          (conj {:seon.cluster.run.form/source send-source
                 :seon.ns/name 'my.agents.root}))]
    (if (or already-open? (empty? sources))
      []
      (run/system-run-tx
       database
       {:seon.cluster.agent/id "root"
        :seon.cluster.run/id run-id
        :seon.cluster.run/process process
        :seon.cluster.run/opened-at opened-at
        :seon.cluster.run/starting-ns [:seon.ns/name 'my.agents.root]
        :seon.cluster.run/plan-digest (digest-value sources)
        :seon.cluster.run/sources sources}))))

(defn population-tx
  "Install the shipped bootstrap plan once on a source database value."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.store/transaction-data]}
  [db]
  (let [forms (packaged-forms)
        digest (digest-value forms)
        current (db/pull db
                        [:seon.bootstrap.plan/id
                         :seon.bootstrap.plan/digest]
                        [:seon.bootstrap.plan/id plan-id])]
    (cond
      (nil? current)
      [{:seon.bootstrap.plan/id plan-id
        :seon.bootstrap.plan/digest digest
        :seon.bootstrap.plan/forms
        (mapv (fn [ordinal form]
                (assoc form
                       :seon.cluster.run.form/ordinal (long ordinal)
                       :seon.cluster.run.form/author :system))
              (range)
              forms)}]

      (= digest (:seon.bootstrap.plan/digest current))
      []

      :else
      (throw
       (ex-info
        "The database already carries a different bootstrap plan."
        {:seon.error/kind :seon.bootstrap/population-conflict
         :seon.bootstrap.plan/id plan-id
         :seon.bootstrap.plan/digest digest
         :seon.bootstrap.plan/current-digest
         (:seon.bootstrap.plan/digest current)})))))

(defn- ordered-plan-rows
  [db cluster-name]
  (let [rows
        (db/q
         {:query
          '[:find ?ordinal ?source ?designation
            :in $ ?cluster-name
            :where
            [?cluster :seon.cluster/name ?cluster-name]
            [?cluster :seon.cluster/bootstrap-plan ?plan]
            [?plan :seon.bootstrap.plan/forms ?form]
            [?form :seon.cluster.run.form/ordinal ?ordinal]
            [?form :seon.cluster.run.form/source ?source]
            [?form :seon.bootstrap.plan.form/namespace-source ?designation]]
          :args [db cluster-name]
          :order-by '[?ordinal :asc]})
        actual-ordinals (mapv first rows)
        expected-ordinals (mapv long (range (count rows)))]
    (when (empty? rows)
      (throw
       (ex-info "The cluster has no bootstrap-plan forms."
                {:seon.error/kind :seon.bootstrap/plan-absent
                 :seon.cluster/name cluster-name})))
    (when-not (= expected-ordinals actual-ordinals)
      (throw
       (ex-info "The cluster bootstrap-plan ordinals are not contiguous."
                {:seon.error/kind :seon.bootstrap/invalid-ordinals
                 :seon.cluster/name cluster-name
                 :seon.bootstrap.plan/expected-ordinals expected-ordinals
                 :seon.bootstrap.plan/actual-ordinals actual-ordinals})))
    rows))

(defn ordered-sources
  "The cluster's bootstrap-plan facts resolved for one agent namespace."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster/name
                       :seon.ns/name]
                  :seon.cluster.reply/sources]}
  [db cluster-name namespace-name]
  (mapv
   (fn [[_ source designation]]
     {:seon.cluster.run.form/source
      (str/replace source namespace-token (str namespace-name))
      :seon.ns/name
      (case designation
        :agent namespace-name
        :user 'user)})
   (ordered-plan-rows db cluster-name)))

(defn agent-sources
  "The owning cluster's ordered bootstrap sources for an existing agent."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  :seon.cluster.reply/sources]}
  [db agent-id]
  (let [[cluster-name namespace-name]
        (db/q '[:find [?cluster-name ?namespace-name]
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?agent :seon.cluster.agent/cluster ?cluster]
               [?cluster :seon.cluster/name ?cluster-name]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db agent-id)]
    (when-not cluster-name
      (throw
       (ex-info "The agent has no cluster-backed bootstrap plan."
                {:seon.error/kind :seon.bootstrap/agent-plan-absent
                 :seon.cluster.agent/id agent-id})))
    (ordered-sources db cluster-name namespace-name)))

(defn plan-digest
  "The stable digest of a cluster's ordered bootstrap-plan facts."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster/name]
                  :seon.cluster.run/plan-digest]}
  [db cluster-name]
  (digest-value (ordered-plan-rows db cluster-name)))

(defn seed-tx
  "Transaction data opening, claiming, and freezing one bootstrap run."
  {:malli/schema
   [:=>
    [:cat
     :seon.db/database-value
     [:map
      [:seon.cluster.agent/id :seon.cluster.agent/id]
      [:seon.cluster/name :seon.cluster/name]
      [:seon.ns/name :seon.ns/name]
      [:seon.cluster.run/process :seon.cluster.run/process]
      [:seon.cluster.run/opened-at :seon.cluster.run/opened-at]]]
    :seon.store/transaction-data]}
  [db
   {agent-id :seon.cluster.agent/id
    cluster-name :seon.cluster/name
    namespace-name :seon.ns/name
    process :seon.cluster.run/process
    opened-at :seon.cluster.run/opened-at}]
  (let [id (run-id agent-id)
        message-id (task-message-id agent-id)
        sources (ordered-sources db cluster-name namespace-name)
        namespace-row
        {:seon.ns/name namespace-name
         :seon.ns/requires
         [[:seon.ns/name 'my.run]
          [:seon.ns/name 'my.message]
          [:seon.ns/name 'seon.bootstrap]]
         :seon.ns/refers
         [{:seon.ns.refer/local 'help
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'help}
          {:seon.ns.refer/local 'dir
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'dir}
          {:seon.ns.refer/local 'doc
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'doc}]}
        message-row
        {:seon.cluster.message/id message-id
         :seon.cluster.message/ordinal 0
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content (task-message)
         :seon.cluster.message/at opened-at}]
    (into [namespace-row message-row]
          (run/system-run-tx
           db
           {:seon.cluster.agent/id agent-id
            :seon.cluster.run/id id
            :seon.cluster.run/process process
            :seon.cluster.run/opened-at opened-at
            :seon.cluster.run/trigger
            [:seon.cluster.message/id message-id]
            :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
            :seon.cluster.run/plan-digest (plan-digest db cluster-name)
            :seon.cluster.run/sources sources}))))
