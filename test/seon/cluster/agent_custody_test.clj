(ns seon.cluster.agent-custody-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
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
      (is (not (identical? (:seon.agent/context-state parent) (:seon.agent/context-state child)))
          "the isolated world holds its own context state")
      (is (not (identical? parent-connection (held-connection child "custody-parent")))
          "an inherited row never reaches the parent's held connection")
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
