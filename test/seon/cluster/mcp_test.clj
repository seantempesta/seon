(ns seon.cluster.mcp-test
  "The runtime observation (`runtime_status`, `bin/seon status`) and the value
  artifact marker."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.operator.runtime :refer [running-instances]]
            [seon.oversight :as oversight]
            [seon.problems :as problems]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(deftest missing-artifacts-have-a-real-marker-under-armed-admission
  (let [missing {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 123}
        artifact (render.value/artifact missing)]
    (is (= missing (render.value/artifact-value artifact)))
    (is (some? (:seon.sci.admit/print-node artifact)))
    (let [refusal (support/refusal-data
                   #(admit/admit-value
                     {:seon.sci.admit/value missing
                      :seon.sci.admit/caps {}
                      :seon.sci.admit/interrupt-fn (fn [])}))]
      (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.instrument/contract-error) refusal))
      (is (= 'seon.sci.admit/admit-value
             (:seon.instrument/fn refusal))
          "bounded admission still requires its declared limits"))))

(defn- listening-instance
  "A declared `:seon.boot/instance` for a fixture cluster, built by boot's own
  constructor over this JVM's live listener and bootstrap config (renamed), with
  the fixture's connection as its database layer."
  [connection cluster-name]
  (let [live (some #(when (:seon.boot/prepl-server %) %) (vals @running-instances))]
    (assert live "a fixture instance stands beside this JVM's live instance")
    (assoc (boot/listening-instance (assoc (:seon.boot/config live) :seon.boot/cluster-name cluster-name)
                                    (:seon.boot/prepl-server live))
           :seon.boot/cluster-connection connection)))

(defn- running-instance
  "The fixture cluster's instance: boot's listening instance over the fixture
  connection, holding a real SCI context forked onto that connection through
  the production fork (an instance's context is an SCI context, never a map)."
  [connection cluster-name]
  (assoc (listening-instance connection cluster-name)
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection cluster-name)))

(deftest runtime-observation-is-whole-and-its-ai-face-renders-less
  ;; The producer returns every open fault whole, newest first; the value
  ;; printer is the one clipper. Its declared AI face, which `bin/seon status`
  ;; prints, names counts and the newest five by operation, never a message.
  ;; Problem families are counts; the readiness value omits their facts.
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "mcp-observation-bound-" (random-uuid))
           _ (support/apply-config! connection cluster-name {})
           environment (env/environment {:seon.boot/cluster-name cluster-name :seon.db/connection connection})
           record! (fn [i message]
                     (fault/fault! environment (IllegalStateException. ^String message)
                                   {:seon.error/layer ::observation-bound
                                    :seon.error/operation (symbol "seon.cluster.mcp-test" (str "fault-" i))
                                    :seon.db.process/id "mcp-test"}))
           receipts (mapv #(record! % "bounded fault") (range 50))
           long-receipt (record! 50 (apply str (repeat 1000 \x)))
           _ (problems/resolve! connection (:seon.error/signature (first receipts)))]
       (swap! running-instances assoc cluster-name (listening-instance connection cluster-name))
       (try
         (let [result (cluster/mcp-runtime-observation cluster-name)
               shown (:seon.dev.mcp/open-faults result)
               status (cluster/runtime-observation-ai {:seon.render/value result})
               status-faults (:seon.dev.mcp/open-faults status)]
           ;; the fixture branch also carries its parent's open and resolved faults
           (is (<= 50 (:seon.dev.mcp/open-fault-count result)))
           (is (= (:seon.dev.mcp/open-fault-count result) (count shown)) "every open fault, whole")
           (is (<= 1 (:seon.dev.mcp/resolved-fault-count result)))
           (is (not-any? #{(:seon.error/signature (first receipts))} (map :seon.error/signature shown)))
           (is (= (apply str (repeat 1000 \x))
                  (some #(when (= (:seon.error/signature long-receipt) (:seon.error/signature %))
                           (:seon.error/message %))
                        shown))
               "the producer keeps the whole message")
           (is (<= 50 (get-in result [:seon.dev.mcp/problem-counts :seon.problems/error-signatures]))
               "each problem family is a count, never its facts")
           (is (not (contains? (:seon.dev.mcp/readiness result) :seon.problems/problems)))
           (testing "the declared AI face renders less"
             (is (= :seon.dev.mcp/runtime-observation
                    (last (:malli/schema (meta #'cluster/mcp-runtime-observation)))))
             (is (= (count shown) (:seon.dev.mcp/count status-faults)))
             (is (= 3 (count (:seon.dev.mcp/top status-faults))))
             (is (= (- (count shown) 3) (:seon.dev.mcp/omitted status-faults)))
             (is (not-any? :seon.error/message (:seon.dev.mcp/top status-faults))
                 "a fault is named by operation, never by its unbounded message")
             ;; It fits the 1,000-token agent budget (~3,200 characters) whole,
             ;; so the MCP reply is the face, never a blob handle.
             (is (< (count (pr-str status)) 3200))))
         (finally (swap! running-instances dissoc cluster-name)))))))

(deftest ^{:seon.test/fixture-observation
           "Observes that flow health uses an ordinary canonical branch; the fresh-store owner is referenced only to assert zero acquisitions."}
  live-runtime-observation-hands-its-projection-to-flow-health
  (let [cluster-name "mcp-runtime-projection-test"
        fresh-stores (atom 0)
        acquire @#'support/with-fresh-database]
    (with-redefs-fn
      {#'support/with-fresh-database
       (fn [& args] (swap! fresh-stores inc) (apply acquire args))}
      (fn []
        (support/with-database
         (fn [connection]
           (config/apply! {:seon.db/connection connection
                          :seon.boot/cluster-name cluster-name})
           (support/seed-cluster! connection cluster-name)
           (swap! running-instances assoc cluster-name
                  (assoc (running-instance connection cluster-name)
                         :seon.flow/graph ::graph))
           (try
             (with-redefs
              [boot/readiness
               (fn [_] {:seon.boot/cluster-name cluster-name})
               oversight/flow-status
               (fn [database _]
                 (let [effective (config/effective database cluster-name)]
                   (if (or (:seon.config/missing-effective effective) (:seon.config/error-key effective) (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                     effective
                     {:seon.oversight/agents []
                      :seon.oversight/plumbing []})))]
               (let [result (cluster/mcp-runtime-observation cluster-name)]
                 (is (= :observed (:seon.dev.mcp/health result)))
                 (is (= {:seon.oversight/agents []
                         :seon.oversight/plumbing []}
                        (:seon.dev.mcp/flow result)))
                 (is (= :observed (:seon.dev.mcp/health result)))))
             (finally
               (swap! running-instances dissoc cluster-name)))))))
    (is (zero? @fresh-stores)
        "Flow-health projection needs only an ordinary canonical branch.")))
