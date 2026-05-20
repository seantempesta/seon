(ns seon.db.routing-test
  "Phase 2 of datahike-migration — dispatch layer tests.

   Covers `seon.db`'s routing logic: when the running datahike flow owns
   a conn-process for a db-name, public ops route through the flow; when
   it doesn't, `datahike-owned?` reports false.

   The datahike flow is built manually in a fixture (via
   `build-datahike-flow!`) and bound to `seon.db/*datahike-flow*`.
   No Integrant boot — keeps the test hermetic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [seon.db :as db]
            [seon.db.datahike.flow :as dh-flow]
            [seon.phase2.demo :as demo]
            [seon.schema :as schema])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;; Ensure the demo ns is loaded so its schemas are registered (it is
;; required above — the `demo` alias keeps the require from being elided
;; by cleanup passes).
(comment demo/entity-schema)

;;; ---------------------------------------------------------------------------
;;; Fixture: build a :memory datahike flow with the demo namespace
;;; ---------------------------------------------------------------------------

(def ^:dynamic *flow* nil)

(defn- stable-memory-id
  "Mirror of flow.clj's private `stable-id` so we can address the konserve
   :memory store used by a db-name and delete it between runs. This keeps
   tests hermetic: two tests building the same flow both see a clean store."
  [db-name]
  (let [slug (if-let [ns (namespace db-name)]
               (str ns "__" (name db-name))
               (name db-name))]
    (UUID/nameUUIDFromBytes (.getBytes slug StandardCharsets/UTF_8))))

(defn- delete-memory-db! [db-name]
  (try
    (dh/delete-database {:store {:backend :memory :id (stable-memory-id db-name)}})
    (catch Exception _
      nil)))

(defn- with-datahike-flow [f]
  ;; Konserve :memory stores are JVM-global and addressed by :id; stable-id
  ;; in flow.clj derives :id from the db-name, so a prior test's data would
  ;; leak in. Delete before AND after to guarantee a fresh store.
  (delete-memory-db! :seon.phase2.demo)
  (let [fs (dh-flow/build-datahike-flow!
             {::dh-flow/namespaces [:seon.phase2.demo]
              ::dh-flow/backend :memory
              ::dh-flow/namespace-schemas {:seon.phase2.demo demo/entity-schema}})]
    (try
      (binding [db/*datahike-flow* fs
                *flow* fs]
        (f))
      (finally
        (dh-flow/stop-datahike-flow! fs)
        (delete-memory-db! :seon.phase2.demo)))))

(use-fixtures :each with-datahike-flow)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest routes-transact-and-query-through-datahike
  (testing "db/transact! + db/query on a datahike-owned db-name round-trip
            through the flow"
    (let [uid (random-uuid)]
      ;; transact! through db dispatch
      (db/transact! :seon.phase2.demo
                    [{:seon.phase2.demo/id uid
                      :seon.phase2.demo/name "routed-alice"}])
      ;; query through db dispatch
      (let [q-result (db/query :seon.phase2.demo
                               '[:find ?n
                                 :in $ ?id
                                 :where
                                 [?e :seon.phase2.demo/id ?id]
                                 [?e :seon.phase2.demo/name ?n]]
                               uid)]
        (is (= #{["routed-alice"]} q-result))))))

(deftest auto-stamp-lands-on-entity-maps
  (testing ":seon.db/namespace stamp is added to plain entity maps and
            is queryable after transact"
    (let [uid (random-uuid)]
      (db/transact! :seon.phase2.demo
                    [{:seon.phase2.demo/id uid
                      :seon.phase2.demo/name "stamped-bob"}])
      (let [pulled (db/pull-by-name :seon.phase2.demo
                                    '[*]
                                    [:seon.phase2.demo/id uid])]
        (is (= :seon.phase2.demo (:seon.db/namespace pulled))
            "every entity map gets its db-name stamped")
        (is (= "stamped-bob" (:seon.phase2.demo/name pulled)))))))

(deftest vector-tuples-not-stamped
  (testing "vector tuples (`[:db/add ...]`, `[:db/retract ...]`) pass through
            unchanged — stamp is entity-map-only"
    (let [uid (random-uuid)]
      ;; First get an entity id we can target with vector ops
      (db/transact! :seon.phase2.demo
                    [{:seon.phase2.demo/id uid
                      :seon.phase2.demo/name "initial"}])
      ;; Add more datoms via vector-tuple form + retract one.
      ;; If stamp-namespace touched vector tuples, this would blow up.
      (let [eid-result (db/query :seon.phase2.demo
                                 '[:find ?e .
                                   :in $ ?id
                                   :where [?e :seon.phase2.demo/id ?id]]
                                 uid)
            eid eid-result]
        (is (some? eid) "must find eid")
        (db/transact! :seon.phase2.demo
                      [[:db/add eid :seon.phase2.demo/name "renamed"]])
        (let [pulled (db/pull-by-name :seon.phase2.demo
                                      '[*]
                                      [:seon.phase2.demo/id uid])]
          (is (= "renamed" (:seon.phase2.demo/name pulled))
              "vector tuple applied cleanly")
          (is (= :seon.phase2.demo (:seon.db/namespace pulled))
              "stamp from earlier tx still present"))))))

(deftest datahike-owned-predicate-is-accurate
  (testing "routing predicate reports true for registered db-names and
            false for unknown ones"
    ;; We only export datahike-owned? as a private fn; use requiring-resolve
    ;; so the test doesn't force it public.
    (let [pred (requiring-resolve 'seon.db/datahike-owned?)]
      (is (pred :seon.phase2.demo)
          "db-name registered with the flow is owned")
      (is (not (pred :seon.unknown))
          "unknown db-name is not owned")
      (is (not (pred :seon))
          ":seon is not owned by this flow"))))

(deftest stamp-does-not-override-explicit-namespace
  (testing "if the caller already set :seon.db/namespace, stamp leaves it alone"
    (let [uid (random-uuid)
          custom-ns :seon.phase2.demo/override-marker]
      ;; Need to register the marker so the validation gate doesn't reject it
      ;; — but :seon.db/namespace is already a registered :keyword, and
      ;; custom-ns is just a keyword value.
      (db/transact! :seon.phase2.demo
                    [{:seon.phase2.demo/id uid
                      :seon.phase2.demo/name "override"
                      :seon.db/namespace custom-ns}])
      (let [pulled (db/pull-by-name :seon.phase2.demo
                                    '[*]
                                    [:seon.phase2.demo/id uid])]
        (is (= custom-ns (:seon.db/namespace pulled))
            "explicit :seon.db/namespace is preserved")))))
