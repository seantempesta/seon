(ns seon.bootstrap
  "The fact-authored bootstrap run shared by every new agent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.cluster.run :as run]
            [seon.db :as db]
            [seon.schema :as schema]))

(def plan-id
  "The inherited bootstrap-plan identity on every cluster branch."
  :default)

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
  "The prose context authored on the shipped help form map."
  {:malli/schema [:=> [:cat] :string]}
  []
  (:seon.bootstrap.plan.form/context (first (packaged-forms))))

(defmacro help
  "Print the one prose guide to the agent REPL."
  []
  (list 'clojure.core/print (help-text)))

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

(defn- digest-value
  [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

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
                (assoc form :seon.cluster.run.form/ordinal (long ordinal)))
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
            [?form :seon.ns/name-designation ?designation]]
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
     [:map {:closed true}
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
           :seon.ns.refer/target-name 'doc}]}]
    (into
     []
     cat
     [[namespace-row]
      (run/open-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at opened-at})
      (run/claim-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/process process
        :seon.cluster.run/live-processes #{process}
        :seon.cluster.run/now opened-at})
      (run/plan-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/process process
        :seon.cluster.run/plan-digest (plan-digest db cluster-name)
        :seon.cluster.run/sources sources})])))
