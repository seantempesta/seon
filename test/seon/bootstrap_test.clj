(ns seon.bootstrap-test
  "The fact-authored bootstrap plan, seeding seam, and REPL help."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private agent-id "bootstrap-agent")
(def ^:private namespace-name 'my.agents.bootstrap-agent)

(defn- seed-plan-cluster!
  [connection cluster-name]
  (support/seed-cluster! connection cluster-name)
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity))

(defn- run-row
  [db run-id]
  (db/pull db
          '[* {:seon.cluster.run/forms [*]}]
          [:seon.cluster.run/id run-id]))

(defn- ordered-run-forms
  [db run-id]
  (db/q
   {:query
    '[:find ?ordinal ?source ?namespace-name
      :in $ ?run-id
      :where
      [?run :seon.cluster.run/id ?run-id]
      [?form :seon.cluster.run.form/run ?run]
      [?form :seon.cluster.run.form/ordinal ?ordinal]
      [?form :seon.cluster.run.form/source ?source]
      [?form :seon.cluster.run.form/ns ?namespace]
      [?namespace :seon.ns/name ?namespace-name]]
    :args [db run-id]
    :order-by '[?ordinal :asc]}))

(defn- ordered-plan-forms
  [db cluster-name]
  (db/q
   {:query
    '[:find ?form ?ordinal ?source
      :in $ ?cluster-name
      :where
      [?cluster :seon.cluster/name ?cluster-name]
      [?cluster :seon.cluster/bootstrap-plan ?plan]
      [?plan :seon.bootstrap.plan/forms ?form]
      [?form :seon.cluster.run.form/ordinal ?ordinal]
      [?form :seon.cluster.run.form/source ?source]]
    :args [db cluster-name]
    :order-by '[?ordinal :asc]}))

(deftest shipped-default-is-one-complete-half-episode
  (let [forms (bootstrap/packaged-forms)
        texts (mapv :seon.cluster.run.form/source forms)]
    (is (= 12 (count forms)))
    (is (= "(help)" (first texts)))
    (is (= "(in-ns '{{seon.ns/name}})" (second texts)))
    (is (str/starts-with? (nth texts 2) "(require "))
    (is (str/includes? (nth texts 3) ":seon.ns/name"))
    (is (= ["(dir my.run)" "(dir my.message)" "(dir seon.db)"
            "(dir seon.bootstrap)"]
           (subvec texts 4 8)))
    (is (= [:agent :user]
           (mapv :seon.bootstrap.plan.form/namespace-source (take 2 forms))))
    (is (every? #(= :agent (:seon.bootstrap.plan.form/namespace-source %))
                (cons (first forms) (drop 2 forms))))
    (is (= (bootstrap/help-text)
           (:seon.bootstrap.plan.form/help-text (first forms))))
    (testing "the worked demonstration succeeds and closes its own arc"
      (is (str/includes? (nth texts 8) "(defn largest"))
      (is (str/includes? (nth texts 8) ":example/amount"))
      (is (str/includes? (nth texts 9) "(largest ["))
      (is (str/includes? (nth texts 10) ":seon.fn/spec"))
      (is (str/starts-with? (nth texts 11) "(run/complete "))
      (is (not-any? #(str/includes? % "[:sequential :any]") texts))
      (is (not-any? #{"(largest)" "(largest [])"} texts))
      (is (not-any? #(str/includes? % "{:closed true}")
                    (cons (bootstrap/help-text) texts))))
    (is (str/includes? (bootstrap/help-text) "extra keys ignored")
        "the help teaches that open maps admit undeclared keys")
    (is (str/includes? (nth texts 10) "{{seon.ns/name}}/largest"))
    (is (not-any? #(str/includes? % "my.repl/prompt!") texts))
    (is (nil? (ns-resolve 'seon.bootstrap 'sources))
        "the former code-authored vector is gone")))

(deftest creation-reads-one-held-plan-from-cluster-facts
  (support/with-database
    (fn [connection]
      (seed-plan-cluster! connection "bootstrap")
      (let [request {:seon.cluster.agent/id agent-id
                     :seon.cluster/name "bootstrap"
                     :seon.ns/name namespace-name}
            process cluster/boot-process-identity
            expected-sources
            (bootstrap/ordered-sources
             @connection "bootstrap" namespace-name)
            first-result (cluster/ensure-entity! connection process request)
            run-id (bootstrap/run-id agent-id)
            creation-result
            {:seon.cluster.agent/id agent-id
             :seon.ns/name namespace-name
             :seon.cluster/name "bootstrap"
             :seon.cluster.run/id run-id}
            before (run-row @connection run-id)]
        (is (empty? (bootstrap/population-tx @connection))
            "the source population is digest-guarded and idempotent")
        (is (= {:seon.bootstrap.plan/id bootstrap/plan-id}
               (:seon.cluster/bootstrap-plan
                (db/pull @connection
                        '[{:seon.cluster/bootstrap-plan
                           [:seon.bootstrap.plan/id]}]
                        [:seon.cluster/name "bootstrap"]))))
        (is (= creation-result first-result))
        (is (= (mapv (fn [ordinal source]
                       [ordinal
                        (:seon.cluster.run.form/source source)
                        (:seon.ns/name source)])
                     (range)
                     expected-sources)
               (ordered-run-forms @connection run-id)))
        (is (= (bootstrap/plan-digest @connection "bootstrap")
               (:seon.cluster.run/plan-digest before)))
        (is (= process (:seon.cluster.run/process before)))
        (is (= (bootstrap/task-message-id agent-id)
               (:seon.cluster.message/id
                (db/pull @connection [:seon.cluster.message/id]
                         (get-in before [:seon.cluster.run/trigger :db/id])))))
        (is (= (bootstrap/task-message)
               (:seon.cluster.message/content
                (db/pull @connection
                         [:seon.cluster.message/content]
                         [:seon.cluster.message/id
                          (bootstrap/task-message-id agent-id)]))))
        (is (= #{['help 'seon.bootstrap 'help]
                 ['dir 'seon.bootstrap 'dir]
                 ['doc 'seon.bootstrap 'doc]}
               (db/q '[:find ?local ?target-ns ?target-name
                      :in $ ?namespace-name
                      :where
                      [?namespace :seon.ns/name ?namespace-name]
                      [?namespace :seon.ns/refers ?refer]
                      [?refer :seon.ns.refer/local ?local]
                      [?refer :seon.ns.refer/target-ns ?target-ns]
                      [?refer :seon.ns.refer/target-name ?target-name]]
                    @connection namespace-name)))
        (is (= {:seon.cluster.work/situation :resume
                :seon.cluster.run/id run-id
                :seon.cluster.agent/id agent-id
                :seon.cluster.run.form/ordinal 0}
               (work/next-agent-work
                @connection
                {:seon.cluster.agent/id agent-id
                 :seon.cluster.run/process process})))
        (is (= creation-result
               (cluster/ensure-entity!
                connection process
                (assoc request :seon.ns/name 'my.agents.replacement))))
        (is (= before (run-row @connection run-id)))))))

(deftest plan-edits-affect-only-agents-created-after-the-transaction
  (support/with-database
    (fn [connection]
      (seed-plan-cluster! connection "bootstrap-edit")
      (let [process cluster/boot-process-identity
            prior-id "bootstrap-prior"
            later-id "bootstrap-later"
            prior-request
            {:seon.cluster.agent/id prior-id
             :seon.cluster/name "bootstrap-edit"
             :seon.ns/name 'my.agents.bootstrap-prior}
            later-request
            {:seon.cluster.agent/id later-id
             :seon.cluster/name "bootstrap-edit"
             :seon.ns/name 'my.agents.bootstrap-later}
            _ (cluster/ensure-entity! connection process prior-request)
            prior-run-id (bootstrap/run-id prior-id)
            prior-before (run-row @connection prior-run-id)
            prior-digest (:seon.cluster.run/plan-digest prior-before)
            pre-edit-plan-digest
            (bootstrap/plan-digest @connection "bootstrap-edit")
            plan-forms (ordered-plan-forms @connection "bootstrap-edit")
            [edited-eid _ prior-edited-source] (nth plan-forms 2)
            inserted-source "(identity :inserted-bootstrap-form)"
            edited-source "(identity :edited-bootstrap-form)"
            renumber-tx
            (into
             []
             (mapcat
              (fn [[form-eid ordinal _]]
                (when (<= 1 ordinal)
                  [[:db/retract form-eid
                    :seon.cluster.run.form/ordinal ordinal]
                   [:db/add form-eid
                    :seon.cluster.run.form/ordinal (inc ordinal)]])))
             plan-forms)
            edit-tx
            [[:db/retract edited-eid
              :seon.cluster.run.form/source prior-edited-source]
             [:db/add edited-eid
              :seon.cluster.run.form/source edited-source]
             {:db/id "inserted-bootstrap-form"
              :seon.cluster.run.form/ordinal 1
              :seon.cluster.run.form/source inserted-source
              :seon.bootstrap.plan.form/namespace-source :agent}
             [:db/add
              [:seon.bootstrap.plan/id bootstrap/plan-id]
              :seon.bootstrap.plan/forms
              "inserted-bootstrap-form"]]]
        (db/transact! connection {:tx-data (into renumber-tx edit-tx)})
        (is (nil? (:seon.error/kind
                   (cluster/ensure-entity!
                    connection process later-request))))
        (let [later-run-id (bootstrap/run-id later-id)
              later (run-row @connection later-run-id)
              later-sources
              (mapv second (ordered-run-forms @connection later-run-id))
              prior-sources
              (mapv second (ordered-run-forms @connection prior-run-id))]
          (is (= prior-before (run-row @connection prior-run-id))
              "the prior agent's frozen run is untouched")
          (is (= pre-edit-plan-digest prior-digest))
          (is (not= pre-edit-plan-digest
                    (:seon.cluster.run/plan-digest later)))
          (is (= (inc (count (bootstrap/packaged-forms)))
                 (count later-sources)))
          (is (= inserted-source (nth later-sources 1)))
          (is (= edited-source (nth later-sources 3)))
          (is (not-any? #{inserted-source edited-source} prior-sources))
          (is (= (:seon.cluster.run/plan-digest later)
                 (bootstrap/plan-digest
                  @connection
                  "bootstrap-edit"))))))))

(deftest bare-help-prints-the-edn-authored-prose-through-sci
  (let [evaluation
        (sci.eval/evaluate
         {:seon.sci.eval/ctx (sci.eval/build-base-ctx)
          :seon.cluster.run.form/source "(help)"
          :seon.sci.admit/caps (config/result-caps (config/defaults))
          :seon.sci.eval/time-limit-ms 2000
          :seon.config/on-core-error :panic})]
    (is (nil? (:seon.cluster.eval/error evaluation)))
    (is (nil? (:seon.sci.admit/value evaluation)))
    (is (= (bootstrap/help-text)
           (:seon.cluster.eval/output evaluation)))))

(deftest first-agent-supervision-is-one-self-erasing-system-run
  (support/with-database
    (fn [connection]
      (seed-plan-cluster! connection "supervision")
      (db/transact!
       connection
       (into (cluster.agent/creation-tx
              {:seon.cluster.agent/id "root"
               :seon.cluster/name "supervision"
               :seon.ns/name 'my.agents.root})
             (cluster.agent/creation-tx
              {:seon.cluster.agent/id "worker"
               :seon.cluster/name "supervision"
               :seon.ns/name 'my.agents.worker})))
      (let [tx (bootstrap/supervision-tx
                @connection cluster/boot-process-identity
                (java.util.Date.) "worker")]
        (is (seq tx))
        (db/transact! connection tx)
        (let [rows
              (db/q
               {:query
                '[:find ?ordinal ?author ?source
                  :in $ ?run-id
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?form :seon.cluster.run.form/run ?run]
                  [?form :seon.cluster.run.form/ordinal ?ordinal]
                  [?form :seon.cluster.run.form/author ?author]
                  [?form :seon.cluster.run.form/source ?source]]
                :args [@connection (bootstrap/supervision-run-id)]
                :order-by '[?ordinal :asc]})]
          (is (= 2 (count rows)))
          (is (every? #(= :system (second %)) rows))
          (is (str/includes? (nth (first rows) 2)
                             ":seon.cluster.eval/run"))
          (is (str/includes? (nth (second rows) 2)
                             "(my.message/send \"worker\""))
          (is (str/includes? (nth (second rows) 2) "(run/complete"))
          (is (empty?
               (bootstrap/supervision-tx
                @connection cluster/boot-process-identity
                (java.util.Date.) "worker"))))))))
