(ns seon.cluster.agent-custody-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- created!
  "Create `agent-id` through the canonical writer on `connection`."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] :seon.db/transaction-report]}
  [connection agent-id]
  (support/transacted! connection
                       (agent/creation-tx {:seon.agent/id agent-id
                                           :seon.ns/name (symbol (str "my.agents." agent-id))
                                           :seon.cluster/name "default"})))

(defn- recorded-branch
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] [:maybe :seon.agent/branch]]}
  [connection agent-id]
  (:seon.agent/branch (db/pull @connection '[:seon.agent/branch] [:seon.agent/id agent-id])))

(defn- held-connection
  "The connection an acquisition of `agent-id` through `handle` holds, or its refusal."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.agent/id]
                  [:or :seon.db/connection :seon.error/throwable]]}
  [handle agent-id]
  (try (:seon.db/connection (agent/acquire-context! handle agent-id))
       (catch clojure.lang.ExceptionInfo refusal refusal)))

(deftest an-agent-lives-on-the-branch-its-creating-connection-holds
  ;; Before the fix creation-tx named `:cluster-default` from the cluster label,
  ;; so the parent acquisition refused, and the isolated world shared the
  ;; parent's context state and so could borrow the parent's held connection.
  (let [parent (support/execution-handle db/*conn*)
        parent-connection (:seon.db/connection parent)
        parent-branch (get-in @parent-connection [:config :branch])
        _ (created! parent-connection "custody-parent")
        child (agent/acquire-context! parent nil {:seon.agent/isolate? true})
        child-connection (:seon.db/connection child)
        child-branch (:seon.agent/branch child)]
    (try
      (is (= parent-branch (recorded-branch parent-connection "custody-parent")))
      (is (identical? parent-connection (held-connection parent "custody-parent"))
          "the parent acquires its own agent live on its own connection")
      (is (not (identical? (:seon.agent/routing parent) (:seon.agent/routing child)))
          "the isolated world holds its own registry")
      (is (identical? (:seon.agent/routing child)
                      (get-in @(:seon.agent/routing parent) [:seon.agent/children child-branch]))
          "the parent registry records the child's by branch")
      (is (not (identical? parent-connection (held-connection child "custody-parent")))
          "an inherited entity never reaches the parent's held connection")
      (created! child-connection "custody-child")
      (is (= child-branch (recorded-branch child-connection "custody-child")))
      (let [held (held-connection child "custody-child")]
        (is (identical? child-connection held) "the child's agent acquires on the child's branch")
        (created! held "custody-sentinel"))
      (is (= child-branch (recorded-branch child-connection "custody-sentinel")))
      (is (nil? (recorded-branch parent-connection "custody-sentinel"))
          "a write by the child's agent is absent from the parent")
      (is (nil? (recorded-branch parent-connection "custody-child")))
      (finally (agent/release-context! child)))))

(defn- poisoned-child!
  "An isolated child of `parent` holding one good row and one row its program
  cannot install (it names an unresolvable dependency)."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :string] :map]}
  [parent tag]
  (let [child (agent/acquire-context! parent nil {:seon.agent/isolate? true})
        connection (:seon.db/connection child)
        namespace-name (symbol (str "custody.poison-" tag))
        member #(symbol (str namespace-name) %)]
    (support/transacted! connection [(support/namespace-entity (db/db connection) namespace-name)])
    (support/transacted! connection (agent/creation-tx {:seon.agent/id (str "poison-" tag)
                                                        :seon.ns/name namespace-name
                                                        :seon.cluster/name (:seon.cluster/name parent)}))
    (support/transacted! connection
                         [(support/program-fn-entity (db/db connection) (member "bad")
                                                  "(defn ^{:malli/schema [:=> [:cat :int] :int]} bad [x] (missing-dependency x))")
                          (support/program-fn-entity (db/db connection) (member "good")
                                                  "(defn ^{:malli/schema [:=> [:cat :int] :int]} good [x] (inc x))")])
    {:child child :bad (member "bad") :good (member "good") :agent-id (str "poison-" tag)}))

(defn- recorded-refusal
  "The stored acquisition refusal of `function` on `connection`'s branch, or nil.
  An agent row that cannot interpret is its agent's mistake, recorded with
  `:seon.sci.eval/refused-function`, not `:seon.sci.eval/row-symbol`."
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-symbol] [:maybe :int]]}
  [connection function]
  (db/q '[:find ?error . :in $ ?function
          :where [?error :seon.error/occurrences ?occurrence]
                 [?occurrence :seon.sci.eval/refused-function ?function]]
        (db/db connection) function))

(deftest ^{:seon.test/long "Measured 6,555 ms (root run 16b466ddab78, 2026-09-25): two isolated child acquisitions with their fixture writes (about 0.8 s and 0.9 s each at the REPL) and two program acquisitions (0.7-1.3 s each)."
           :seon.test/long-ms 10000}
  an-acquisition-records-its-refusal-on-the-acquired-branch-under-another-custody
  ;; Astra de1075aa3 finding 1: recording an un-installable entity is the acquired
  ;; branch's own write, whether acquisition enters through the agent entrance or
  ;; through a lazy first invocation, and whoever's custody the caller holds.
  (let [parent (support/execution-handle db/*conn*)
        parent-connection (:seon.db/connection parent)
        entrance (poisoned-child! parent "entrance")
        lazy (poisoned-child! parent "lazy")]
    (try
      (is (identical? parent-connection db/*conn*) "the body runs under the parent's custody")
      (let [before (db/basis-t (db/db parent-connection))
            entrance-connection (:seon.db/connection (:child entrance))
            lazy-connection (:seon.db/connection (:child lazy))]
        ;; The agent entrance: acquisition throws if it could not record.
        (agent/acquire-context! (:child entrance) (:agent-id entrance))
        (is (some? (recorded-refusal entrance-connection (:bad entrance))))
        ;; Lazy invocation: a fresh context acquires at its first named call.
        (let [ctx (sci.eval/cluster-ctx (db/db lazy-connection) lazy-connection)
              invoked (kernel/invoke {:seon.sci.eval/ctx ctx :seon.db/db (db/db lazy-connection)
                                      :seon.fn/sym (:good lazy) :seon.sci.eval/args [41]
                                      :seon.sci.eval/time-limit-ms 5000
                                      :seon.sci.admit/caps (config/result-caps config/defaults)})]
          (is (= 42 (:seon.sci.admit/value invoked)))
          (is (some? (recorded-refusal lazy-connection (:bad lazy)))))
        (is (= before (db/basis-t (db/db parent-connection))) "the caller's branch is unchanged")
        (is (nil? (recorded-refusal parent-connection (:bad entrance)))))
      (finally
        (agent/release-context! (:child entrance))
        (agent/release-context! (:child lazy))))))
