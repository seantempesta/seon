#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns reach-closure.live-proof
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.operator]
            [seon.render.hiccup :as html]
            [seon.render.test :as render]
            [seon.test :as test]
            [seon.test.runner :as runner]))

(defn value "The function changed by this live proof."
  {:malli/schema [:=> [:cat] :int]} [] 1)
(defn old-dependency "A dependency removed before the final green run."
  {:malli/schema [:=> [:cat] :keyword]} [] :observed)
(defn new-dependency "A dependency added before the final green run."
  {:malli/schema [:=> [:cat] :keyword]} [] :observed)
(deftest assertion
  (old-dependency)
  (is (= 1 (value)) "the observed value is one"))

(def result (promise))
(let [path (.getCanonicalPath (io/file *file*))]
  (doto
    (Thread.
      (bound-fn []
        (try
          (let [connection (seon.operator/connection "default")
                test-ref [:seon.test/sym "reach-closure.live-proof/assertion"]
                value-ref [:seon.fn/sym "reach-closure.live-proof/value"]
                old-ref [:seon.fn/sym "reach-closure.live-proof/old-dependency"]
                new-ref [:seon.fn/sym "reach-closure.live-proof/new-dependency"]
                transact (fn [rows]
                           (let [outcome (db/transact! connection rows)]
                             (when (:seon.error/kind outcome)
                               (throw (ex-info "Live proof transaction refused." outcome)))
                             outcome))
                run (fn []
                      (let [database (db/db connection)]
                        (test/run #'assertion connection
                          {:seon.db/db database
                           :seon.test.run/provenance (runner/provenance database)
                           :seon.test/remaining-ms 100000})))
                _ (transact
                    [{:seon.ns/name 'reach-closure.live-proof}
                     {:seon.fn.file/path path
                      :seon.fn.file/digest (id/sha-256 [(.getBytes (slurp path) "UTF-8")])}
                     {:seon.fn/sym (second value-ref)
                      :seon.fn/ns [:seon.ns/name 'reach-closure.live-proof]
                      :seon.schema.admission/source :core
                      :seon.fn/source "(defn value [] 1)" :seon.fn/spec "[:=> [:cat] :int]"}
                     {:seon.fn/sym (second old-ref)
                      :seon.fn/ns [:seon.ns/name 'reach-closure.live-proof]
                      :seon.schema.admission/source :core
                      :seon.fn/source "(defn old-dependency [] :observed)"}
                     {:seon.fn/sym (second new-ref)
                      :seon.fn/ns [:seon.ns/name 'reach-closure.live-proof]
                      :seon.schema.admission/source :core
                      :seon.fn/source "(defn new-dependency [] :observed)"}
                     {:db/id "proof-test" :seon.test/sym (second test-ref)
                      :seon.test/ns [:seon.ns/name 'reach-closure.live-proof]
                      :seon.schema.admission/source :core
                      :seon.test/source "(deftest assertion (old-dependency) (is (= 1 (value))))"
                      :seon.fn/file [:seon.fn.file/path path]}
                     [:db.fn/retractAttribute test-ref :seon.fn/calls]
                     [:db/add "proof-test" :seon.fn/calls value-ref]
                     [:db/add "proof-test" :seon.fn/calls old-ref]])
                baseline (run)
                _ (alter-var-root #'value (constantly (fn [] 2)))
                _ (transact [[:db/add value-ref :seon.fn/source "(defn value [] 2)"]])
                red (run)
                red-db (db/db connection)
                started (System/nanoTime)
                changed (test/changed-since-green red-db (second test-ref))
                changed-ms (/ (double (- (System/nanoTime) started)) 1000000)
                unit {:seon.db/db red-db :seon.render/value red}
                _ (spit "tmp/reach-closure-red.html"
                        (str "<!doctype html><meta charset=utf-8><title>Recorded test failure</title>"
                             (html/->string (render/render-html unit))))
                _ (alter-var-root #'value (constantly (fn [] 1)))
                _ (alter-meta! #'assertion assoc :test
                               (fn [] (new-dependency) (is (= 1 (value)))))
                _ (transact [[:db/add value-ref :seon.fn/source "(defn value [] 1)"]
                             [:db.fn/retractAttribute test-ref :seon.fn/calls]
                             [:db/add test-ref :seon.fn/calls value-ref]
                             [:db/add test-ref :seon.fn/calls new-ref]
                             [:db/add test-ref :seon.test/source
                              "(deftest assertion (new-dependency) (is (= 1 (value))))"]])
                green (run)
                evidence
                {::pid (.pid (java.lang.ProcessHandle/current))
                 ::baseline baseline ::red red ::changed changed ::changed-ms changed-ms
                 ::red-ai (render/render-ai unit)
                 ::green green
                 ::green-reach (db/pull (db/db connection)
                                 '[{:seon.test/reach [:seon.fn/sym]}] test-ref)
                 ::remaining-failures
                 (db/q '[:find [?id ...] :in $ [?id ...]
                         :where [_ :seon.test.failure/id ?id]]
                       (db/db connection) (mapv :seon.test.failure/id (:seon.test/failures red)))}]
            (spit "docs/prds/steward-platform/research/reach-closure-live-evidence-2026-09-16.edn"
                  (pr-str evidence))
            (deliver result evidence))
          (catch Throwable failure
            (deliver result {:seon.error/message (ex-message failure)
                             :seon.error/data (ex-data failure)}))))
      "reach-closure-live-proof")
    (.setDaemon true)
    (.start)))
