(ns seon.bootstrap-test
  "The system-authored bootstrap plan, seeding seam, and REPL help."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private agent-id "bootstrap-agent")
(def ^:private namespace-name 'my.agents.bootstrap-agent)

(deftest candidate-vector-is-thirteen-ordered-forms
  (let [sources (bootstrap/sources namespace-name)
        texts (mapv :seon.cluster.run.form/source sources)]
    (is (= 13 (count sources)))
    (is (= ["(help)"
            "(in-ns 'my.agents.bootstrap-agent)"
            "(dir my.run)"
            "(doc my.run/complete)"
            "(dir my.message)"]
           (subvec texts 0 5)))
    (is (= ['user 'user]
           (mapv :seon.ns/name (take 2 sources))))
    (is (every? #(= namespace-name (:seon.ns/name %))
                (drop 2 sources)))
    (testing "the refusal is followed immediately by its closed-map repair"
      (is (str/includes? (nth texts 7) "[:map [:label :string]"))
      (is (not (str/includes? (nth texts 7) "{:closed true}")))
      (is (str/includes? (nth texts 8) "{:closed true}")))
    (is (= "(largest)" (nth texts 10)))
    (is (= "(largest [])" (nth texts 11)))
    (is (str/includes? (nth texts 12)
                       "my.agents.bootstrap-agent/largest"))
    (is (not-any? #(str/includes? % "my.repl/prompt!") texts))))

(deftest creation-seeds-one-held-plan-and-resumes-at-form-zero
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "bootstrap")
      (let [request {:seon.cluster.agent/id agent-id
                     :seon.cluster/name "bootstrap"
                     :seon.ns/name namespace-name}
            process cluster/boot-process-identity
            first-result (cluster/ensure-entity! connection process request)
            run-id (bootstrap/run-id agent-id)
            forms
            (d/q {:query
                  '[:find ?ordinal ?source ?namespace-name
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?form :seon.cluster.run.form/run ?run]
                    [?form :seon.cluster.run.form/ordinal ?ordinal]
                    [?form :seon.cluster.run.form/source ?source]
                    [?form :seon.cluster.run.form/ns ?namespace]
                    [?namespace :seon.ns/name ?namespace-name]]
                  :args [@connection run-id]
                  :order-by '[?ordinal :asc]})
            before (d/pull @connection
                           '[* {:seon.cluster.run/forms [*]}]
                           [:seon.cluster.run/id run-id])]
        (is (nil? (:seon.error/kind first-result)))
        (is (= (mapv (fn [ordinal source]
                       [ordinal
                        (:seon.cluster.run.form/source source)
                        (:seon.ns/name source)])
                     (range)
                     (bootstrap/sources namespace-name))
               forms))
        (is (= (bootstrap/plan-digest (bootstrap/sources namespace-name))
               (:seon.cluster.run/plan-digest before)))
        (is (= process (:seon.cluster.run/process before)))
        (is (not (contains? before :seon.bootstrap/pinned?)))
        (is (= {:seon.cluster.work/situation :resume
                :seon.cluster.run/id run-id
                :seon.cluster.agent/id agent-id
                :seon.cluster.run.form/ordinal 0}
               (work/next-agent-work
                @connection
                {:seon.cluster.agent/id agent-id
                 :seon.cluster.run/process process})))
        (is (nil? (:seon.error/kind
                   (cluster/ensure-entity!
                    connection process
                    (assoc request :seon.ns/name 'my.agents.replacement)))))
        (is (= before
               (d/pull @connection
                       '[* {:seon.cluster.run/forms [*]}]
                       [:seon.cluster.run/id run-id])))))))

(deftest bare-help-prints-the-one-prose-block-through-sci
  (let [evaluation
        (sci.eval/evaluate
         {:seon.sci.eval/ctx (sci.eval/build-base-ctx)
          :seon.cluster.run.form/source "(help)"
          :seon.sci.admit/caps (config/result-caps (config/defaults))
          :seon.sci.eval/time-limit-ms 2000
          :seon.config/on-core-error :panic})]
    (is (nil? (:seon.cluster.eval/error evaluation)))
    (is (nil? (:seon.sci.admit/value evaluation)))
    (is (= bootstrap/help-text (:seon.cluster.eval/output evaluation)))))
