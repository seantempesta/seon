(ns seon.schema-test
  "CLJS-side tests for seon.schema's register! gates.

   Pins the single-segment keyword-namespace gate (gym S-21 paid-run
   finding, 2026-06-10): `:workout/date` landed in a paid run beside
   the established `:seon.workout/date` despite the teaching banning
   it — register! now refuses single-segment namespaces with a guiding
   error (the established register! failure mode: a thrown
   `:user-input` ex-info, surfaced to agents as an error envelope).

   Run via bin/test-cljs."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [seon.schema :as schema]))

(defn- unregister! [& ks]
  (swap! @#'schema/*schemas #(apply dissoc % ks)))

(deftest single-segment-keyword-namespace-is-refused-with-guidance
  (testing "the S-21 defect shape — :workout/date"
    (let [e (try (schema/register! :workout/date :string)
                 nil
                 (catch :default e e))]
      (is (some? e) "register! must throw, not register")
      (is (not (schema/registered? :workout/date))
          "nothing landed in the registry")
      (is (= :seon.schema/single-segment-namespace
             (:seon.schema/error (ex-data e))))
      (is (= :user-input (:seon.error/kind (ex-data e)))
          "agent-input error kind — the established register! failure mode")
      (is (str/includes? (ex-message e) ":kb.workout/date")
          "the error GUIDES: names a corrected multi-segment example")
      (is (str/includes? (ex-message e) "domain-attrs")
          "the error teaches reuse-first: check the catalog before registering"))))

(deftest multi-segment-and-bare-keys-still-register
  (testing "multi-segment data domain"
    (is (= :schematest.workout/date
           (schema/register! :schematest.workout/date :string))))
  (testing "seon.* substrate-style two-segment namespace"
    (is (= :schematest.gate/ok?
           (schema/register! :schematest.gate/ok? :boolean))))
  (testing "un-namespaced entity-kind keys (the :seon.message shape)"
    (is (= :schematest.kind
           (schema/register! :schematest.kind
                             [:map [:schematest.workout/date
                                    :schematest.workout/date]]))))
  (unregister! :schematest.workout/date :schematest.gate/ok?
               :schematest.kind))
