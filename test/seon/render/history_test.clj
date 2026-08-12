(ns seon.render.history-test
  "Class regressions for self-generating, append-only REPL history."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.ns :as render.ns]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn- render-request
  [database ctx value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/value value
   :seon.render/output :seon.render/form
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record})

(deftest form-is-the-third-output-of-the-existing-selection-chain
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      (into []
            (filter (comp #{:seon.render/form
                            :seon.render/output
                            :seon.render/rendered
                            :seon.render/call-request
                            :seon.ns/ns
                            :seon.fn/fn
                            :seon.schema/schema
                            :seon.cluster.message/message
                            :seon.cluster.message/to}
                          :seon.schema/key))
            (schema/canonical-schema-rows)))
     (db/transact!
      connection
      [{:seon.ns/name 'fixture.history}
       {:seon.cluster.agent/id "history-agent"
        :seon.cluster.agent/namespace [:seon.ns/name 'fixture.history]}
       {:seon.cluster.message/id "history-message"
        :seon.cluster.message/to [:seon.cluster.agent/id "history-agent"]
        :seon.cluster.message/at (java.util.Date. 1786400000000)
        :seon.cluster.message/content "Read me."}])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           selected
           (fn [value & [attribute]]
             (#'seon.render/producer
              (cond-> (render-request database ctx value)
                attribute
                (assoc :seon.render.walk/attribute attribute))
              :seon.render/form
              :seon.render/form))
           namespace-entity
           (db/pull database '[*] [:seon.ns/name 'fixture.history])
           message
           (db/pull database '[*]
                    [:seon.cluster.message/id "history-message"])]
       (testing "shape owners declare ordinary doc/dir/read forms"
         (is (= 'seon.render.ns/namespace-form
                (selected namespace-entity)))
         (is (= '(dir (quote fixture.history))
                (render.ns/namespace-form namespace-entity)))
         (is (= 'seon.render.transcript/message-form
                (selected message)))
         (is (= '(my.message/read "history-message")
                (transcript/message-form message))))
       (testing "an attribute declaration precedes the landed entity shape"
         (is (= 'seon.render.transcript/inbox-form
                (selected message :seon.cluster.message/to)))
         (is (= '(my.message/inbox) (transcript/inbox-form message))))
       (testing "the entity floor uses the projection's declared identity"
         (let [agent-entity (db/pull database '[*]
                                     [:seon.cluster.agent/id "history-agent"])
               producer (selected agent-entity)
               form (render/render-form
                     (render-request database ctx agent-entity))]
           (is (= 'seon.render/render-form producer))
           (is (= 'db/pull (first form)))
           (is (= [:seon.cluster.agent/id "history-agent"] (last form)))))
       (testing "the attribute floor is a listing query"
         (let [request (assoc (render-request database ctx namespace-entity)
                              :seon.render.walk/attribute :seon.ns/requires)
               producer (selected namespace-entity :seon.ns/requires)
               form (render/render-form request)]
           (is (= 'seon.render/render-form producer))
           (is (= 'db/q (first form)))
           (is (str/includes? (pr-str form) ":seon.ns/requires"))))))))

(defn- scenario-entries
  [names]
  (mapcat
   (fn [suffix]
     (let [namespace-name (symbol (str "fixture." suffix))
           function-name (symbol (str namespace-name) "run")]
       [{:seon.render.history/form (list 'dir (list 'quote namespace-name))}
        {:seon.render.history/form (list 'doc (list 'quote function-name))}
        {:seon.render.history/form (list function-name)}]))
   names))

(defn- define-before-use?
  [entries]
  (loop [introduced #{'db/pull 'db/q 'my.message/read 'my.message/inbox}
         remaining entries]
    (if-let [entry (first remaining)]
      (let [references (:seon.render.history/references entry)
            introductions (:seon.render.history/introduces entry)]
        (and (every? introduced references)
             (recur (into introduced introductions) (next remaining))))
      true)))

(deftest every-generated-history-defines-before-use
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [names (gen/vector-distinct
                  (gen/elements ["alpha" "bravo" "charlie" "delta"])
                  {:min-elements 1 :max-elements 4})
           rotation gen/nat]
          (let [entries (vec (scenario-entries names))
                offset (mod rotation (count entries))
                shuffled (vec (concat (subvec entries offset)
                                      (subvec entries 0 offset)))
                ordered (walk/order-history shuffled)]
            (define-before-use? ordered)))
         :seed 2026081101)]
    (is (true? (:result result)) (pr-str result))))

(deftest advancing-bases-only-append-to-the-prompt-prefix
  (let [first-entry
        {:seon.render.history/call-id [:entity :alpha]
         :seon.render.history/basis-transaction 10
         :seon.render.history/bytes "user=> (alpha)\n:alpha"}
        repeated (assoc first-entry :seon.render.history/bytes "rewritten")
        second-entry
        {:seon.render.history/call-id [:entity :beta]
         :seon.render.history/basis-transaction 11
         :seon.render.history/bytes "user=> (beta)\n:beta"}
        prompt-n (web/append-history [] [first-entry])
        prompt-n+1 (web/append-history prompt-n [repeated second-entry])
        prompt-bytes #(str/join "\n\n" (map :seon.render.history/bytes %))]
    (is (= [first-entry second-entry] prompt-n+1)
        "the old observation is never reserialized or rewritten")
    (is (str/starts-with? (prompt-bytes prompt-n+1)
                          (prompt-bytes prompt-n))
        "prompt N is a byte prefix of prompt N+1")))
