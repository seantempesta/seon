(ns seon.agent-render-namespace-test
  "Pure ordinary-data proofs for the canonical namespace AI renderer."
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [seon.agent.ctx :as ctx]
   [seon.db :as db]))

(def ^:private namespace-row
  {:seon.ns/name :pure.root
   :seon.fn/_ns
   [{:seon.fn/sym "pure.root/run"
     :seon.fn/arglists "([request])"
     :seon.fn/source "(defn run [request] request)"
     :seon.fn/spec
     "[:=> [:cat :pure.root/own :pure.cross/a :pure.missing/value] :pure.cross/output]"}]
   :seon.schema/_ns
   [{:seon.schema/key :pure.root/own
     :seon.schema/form "[:map [:pure.root/value :pure.cross/b]]"}]})

(def ^:private schema-rows
  [{:seon.schema/key :pure.root/own
    :seon.schema/form "[:map [:pure.root/value :pure.cross/b]]"}
   {:seon.schema/key :pure.cross/a
    :seon.schema/form "[:tuple :pure.cross/b :string]"}
   {:seon.schema/key :pure.cross/b
    :seon.schema/form "[:or :pure.cross/a :int]"}
   {:seon.schema/key :pure.cross/output
    :seon.schema/form ":string"}])

(defn- fail-on-database-io [& _]
  (throw (js/Error. "pure namespace formatting attempted database I/O")))

(deftest eager-namespace-formatting-is-pure-and-closes-schema-refs
  (let [render #(ctx/render-namespace-ai
                 {:seon.ns/name :pure.root
                  :seon.agent.ctx/namespace-rows [namespace-row]
                  :seon.agent.ctx/schema-rows schema-rows})
        text (with-redefs [db/query fail-on-database-io
                           db/pull fail-on-database-io
                           db/entity fail-on-database-io]
               (render))]
    (testing "ordinary eager rows reach no database API"
      (is (str/includes? text "[fn pure.root/run]")))
    (testing "cross-namespace references close cycles once"
      (is (str/includes? text "(register! :pure.cross/a"))
      (is (str/includes? text "(register! :pure.cross/b"))
      (is (str/includes? text "(register! :pure.cross/output"))
      (is (= 1 (count (re-seq #"register! :pure.cross/a" text))))
      (is (= 1 (count (re-seq #"register! :pure.cross/b" text)))))
    (testing "owned and missing definitions are not duplicated or invented"
      (is (not (str/includes? text "(register! :pure.root/own")))
      (is (not (str/includes? text "(register! :pure.missing/value"))))
    (testing "the frontier uses the shared Malli reference parser"
      (is (= #{:pure.root/own :pure.cross/a :pure.missing/value
               :pure.cross/output}
             (ctx/schema-refs
              ["[:=> [:cat :pure.root/own :pure.cross/a :pure.missing/value] :pure.cross/output]"]))))))

(deftest eager-namespace-formatting-preserves-missing-row-and-cap
  (let [keys (mapv #(keyword "pure.cap" (str "k" %)) (range 41))
        rows (mapv (fn [i k]
                     {:seon.schema/key k
                      :seon.schema/form
                      (if (= i 40) ":string" (pr-str (keys (inc i))))})
                   (range 41) keys)
        row {:seon.ns/name :pure.cap.root
             :seon.fn/_ns
             [{:seon.fn/sym "pure.cap.root/run"
               :seon.fn/spec
               (str "[:=> [:cat " (pr-str (first keys)) "] :string]")}]}
        capped (ctx/render-namespace-ai
                {:seon.ns/name :pure.cap.root
                 :seon.agent.ctx/namespace-rows [row]
                 :seon.agent.ctx/schema-rows rows})
        missing (ctx/render-namespace-ai
                 {:seon.ns/name :pure.absent
                  :seon.agent.ctx/namespace-rows [row]
                  :seon.agent.ctx/schema-rows rows})]
    (is (= 40 (count (re-seq #"\(register! :pure.cap/k" capped)))
        "the closure cap emits exactly forty definitions")
    (is (str/includes? capped "40+ referenced schemas — capped"))
    (is (= "; requires: pure.absent (not in db)" missing))))
