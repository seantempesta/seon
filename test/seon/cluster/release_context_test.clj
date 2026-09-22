(ns seon.cluster.release-context-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.test-support :as support]))

(defn- isolated-handle
  "One isolated execution handle off the executing test's captured commit."
  {:malli/schema [:=> [:cat] :seon.agent/execution-handle]}
  []
  (let [parent (support/execution-handle db/*conn*)]
    (agent/acquire-context!
     parent nil {:seon.agent/isolate? true
                 :seon.cluster.registry/from (:seon.source/commit-id parent)})))

(defn- release-failure
  "The Throwable `release-context!` raised for `handle`, or nothing."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle]
                  [:or :nil [:fn #(instance? Throwable %)]]]}
  [handle]
  (try (agent/release-context! handle) nil
       (catch Throwable failure failure)))

(defn- retains-branch?
  {:malli/schema [:=> [:cat :seon.agent/execution-handle] :boolean]}
  [handle]
  (boolean (some (fn [[[branch _] _]] (= branch (:seon.agent/branch handle)))
                 @(:seon.agent/context-state handle))))

(deftest a-failing-connection-release-still-unlinks-and-forgets-the-context
  ;; The injected release releases the connection and then fails, so the
  ;; unlink that follows is reachable; before the fix the first throw
  ;; skipped both later steps.
  (let [handle (isolated-handle)
        held (:seon.store/store handle)
        branch (:seon.agent/branch handle)
        release store/release-branch!
        _ (is (contains? (registry/roster held) branch))
        _ (is (retains-branch? handle))
        failure (with-redefs [store/release-branch!
                              (fn [connection]
                                (release connection)
                                (throw (ex-info "forced connection release failure"
                                                {:seon.proof/forced :connection})))]
                  (release-failure handle))]
    (is (= :connection (:seon.proof/forced (ex-data failure))))
    (is (empty? (.getSuppressed ^Throwable failure)))
    (is (not (contains? (registry/roster held) branch)) "the branch is still unlinked")
    (is (not (retains-branch? handle)) "the context state no longer retains the branch")))

(deftest every-release-failure-surfaces-with-the-later-ones-suppressed
  (let [handle (isolated-handle)
        held (:seon.store/store handle)
        branch (:seon.agent/branch handle)
        release store/release-branch!]
    (try
      (let [failure (with-redefs [store/release-branch!
                                  (fn [connection]
                                    (release connection)
                                    (throw (ex-info "forced connection release failure"
                                                    {:seon.proof/forced :connection})))
                                  registry/retire-branch!
                                  (fn [_]
                                    (throw (ex-info "forced unlink failure"
                                                    {:seon.proof/forced :unlink})))]
                      (release-failure handle))]
        (is (= :connection (:seon.proof/forced (ex-data failure))))
        (is (= [:unlink] (mapv #(:seon.proof/forced (ex-data %)) (.getSuppressed ^Throwable failure)))
            "the unlink failure rides the first cause as suppressed")
        (is (not (retains-branch? handle)) "the context state is removed after both failures"))
      (finally
        (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))
    (is (not (contains? (registry/roster held) branch)))))
