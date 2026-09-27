(ns seon.cluster.source-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.issue :as issue]
            [seon.schema.datahike]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.test.cache :as cache]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

(def ^:private probe-schema
  [{:db/ident :seon.source.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.source.test/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))

(defonce ^:private blocked-entered (atom nil))
(defonce ^:private blocked-release (atom nil))


(defn populate-program!
  "Publish only the fixture files against their canonical published ancestor."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.fn/manifest :seon.fn.manifest/manifest]]]
                  [:or :seon.reconcile/result :seon.error/value]]}
  [{:keys [:seon.db/connection :seon.fn/manifest] :as request}]
  (let [database (or (:seon.source/previous-database request) (db/db connection))
        projection (or (db/carried-projection database) (schema/handed-projection))]
    (fn/index! (assoc request
                     :seon.schema/projection projection
                     :seon.schema.projection/forms (:seon.schema.projection/forms projection)
                     :seon.source/previous-database database
                     :seon.fn/previous-manifest (assoc manifest :seon.fn.manifest/artifacts [])
                     :seon.fn/changed-paths (set (map :seon.fn.file/relative-path
                                                     (:seon.fn.manifest/artifacts manifest)))))))

(defn populate!
  [{:keys [:seon.db/connection :seon.source/digest]}]
  (test-support/transacted! connection
    (conj (mapv (fn [entity] [:db/retractEntity entity])
                (db/q '[:find [?entity ...] :where [?entity :seon.source.test/marker]] @connection))
          {:seon.source.test/marker digest})))

(defn populate-fails!
  [_]
  (throw (ex-info "population failed" {::injected true})))

(defn populate-from-data!
  [{:keys [:seon.db/connection :seon.source.test/marker]}]
  (test-support/transacted! connection [{:seon.source.test/marker marker}]))

(defonce ^:private handed-database (atom nil))

(defn populate-recording!
  "Record the previous database publication hands its population, then populate."
  [request]
  (reset! handed-database (:seon.source/previous-database request))
  (populate! request))

(defn populate-blocked!
  [request]
  (.countDown ^CountDownLatch @blocked-entered)
  (test-support/await-event! @blocked-release "release blocked publication")
  (populate! request)
  nil)



(defn- fixture-program!
  "Analyze the small publication program in its own source directory."
  {:malli/schema [:=> [:cat :string] :seon.fn.manifest/manifest]}
  [root]
  (let [resources (io/file "test/resources/publication-program")]
    (doseq [file (file-seq resources)
            :when (and (.isFile file) (.endsWith (.getName file) ".txt"))]
      (let [relative (str (.relativize (.toPath resources) (.toPath file)))
            target (io/file root (subs relative 0 (- (count relative) 4)))]
        (.mkdirs (.getParentFile target))
        (io/copy file target)))
    (fn/build-manifest {:seon.fn/roots [root]})))

(defn- with-store
  [body]
  (let [projection (schema/handed-projection)]
  (test-support/with-database
   (fn [custody]
     (let [before (db/basis-t (db/db custody))
           root (str "tmp/source-test/" (random-uuid))
           dir (str root "/data/store")]
       (.mkdirs (io/file root))
       (try
         (test-support/populate-published-operator-root!
          root {:seon.test/fixture-observation
                "Publication and branch-head races require a private physical store."})
         (let [opened (store/open-store! {:seon.store/dir dir})]
           (try
             (doseq [branch (remove #{:db source/current-branch} (registry/roster opened))]
               (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch}))
             (let [connection (store/open-branch! opened source/current-branch)]
               (try
                 (db/carry-connection-projection-state!
                  connection (sci.eval/projection-state @connection projection))
                 (test-support/transacted!
                  connection
                  (into probe-schema
                        (map #(vector :db/retractEntity %))
                        (db/q '[:find [?source ...] :where [?source :seon.source/digest]]
                              (db/db connection))))
                 (finally (d/release connection))))
             (schema/call-with-projection
              projection
              #(let [result (body (assoc opened
                                         :seon.schema/projection projection
                                         :seon.fn/manifest (fixture-program! (str root "/program"))))]
                 ;; Publication under this outer custody writes its own scratch and head.
                 (is (= before (db/basis-t (db/db custody))) "the enclosing custody's branch is unchanged")
                 result))
             (finally (store/release-store! opened))))
         (finally (test-support/delete-recursively! root))))))))

(defn- publish
  ([opened digest]
   (publish opened digest 'seon.cluster.source-test/populate!))
  ([opened digest populate]
   (publish opened digest populate {}))
  ([opened digest populate populate-request]
   (let [database (d/branch-as-db (:seon.store/connection-object opened) source/current-branch)
         previous (db/carry-projection-state
                   database (sci.eval/projection-state database (:seon.schema/projection opened)))]
    (try
     (source/publish! (merge (select-keys populate-request [:seon.source/expected-commit-id
                                                            :seon.db/writer])
                     {:seon.store/store opened
                     :seon.source/digest digest
                     :seon.source/populate populate
                     :seon.source/changed-paths
                     (mapv :seon.fn.file/relative-path
                           (:seon.fn.manifest/artifacts (:seon.fn/manifest opened)))
                     :seon.source/populate-request
                     (merge {:seon.fn/manifest (:seon.fn/manifest opened)
                             :seon.source/previous-database previous
                             :seon.source/change-classes #{:program}}
                            (dissoc populate-request :seon.db/writer))
                     :seon.source/progress! @#'cluster/*source-progress!*}))
     (finally (d/release-materialized-db database))))))

(defn- markers
  [connection]
  (set (db/q '[:find [?marker ...]
              :where [_ :seon.source.test/marker ?marker]]
            @connection)))

(defn- source-digests
  [connection]
  (set (db/q '[:find [?digest ...]
              :where [_ :seon.source/digest ?digest]]
            @connection)))

(defn- scratch-branches
  [opened]
  (filter #(str/starts-with? (name %) "building-source-")
          (registry/roster opened)))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch Exception failure
      (loop [throwable failure, found nil]
        (if throwable
          (recur (ex-cause throwable)
                 (or (not-empty (ex-data throwable)) found))
          found)))))

(deftest digest-is-stable-and-refuses-an-absent-root
  (let [root (str "tmp/source-test/roots/" (random-uuid))]
    (.mkdirs (io/file root))
    (try
      (spit (io/file root "a.clj") "(def a 1)")
      (let [first-digest (source/digest {:seon.source/roots [root]})]
        (is (= first-digest
               (source/digest {:seon.source/roots [(str "./" root)]})))
        (spit (io/file root "notes.txt") "ignored")
        (is (= first-digest (source/digest {:seon.source/roots [root]})))
        (spit (io/file root "a.clj") "(def a 2)")
        (is (not= first-digest (source/digest {:seon.source/roots [root]}))))
      (finally
        (test-support/delete-recursively! root))))
  (is (= :seon.cluster.source/root-absent
         (:seon.cluster.source/rule
          (refusal #(source/digest
                     {:seon.source/roots
                      [(str "tmp/source-test/absent-" (random-uuid))]}))))))

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate the physical-store scratch branch whose refused publication must be retired."}
  flat-scratch-write-refusal-retires-the-candidate
  (with-store
    (fn [opened]
      ;; Publication's writer is an explicit request value: this one refuses
      ;; flatly, echoing the transaction it was handed. The fixture store
      ;; already holds the source attributes, so the first scratch write it
      ;; reaches is the seal.
      (let [refusing-writer
            (fn [_connection transaction]
              {:seon.db.write.attempt/request-id "source-refusal" :seon.error/at (java.util.Date.)
               :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
               :seon.error/message "injected refusal" :seon.error/offending transaction})
            result (refusal #(publish opened digest-a 'seon.cluster.source-test/populate!
                                      {:seon.db/writer refusing-writer}))]
        (is (= :seon.cluster.source/source-seal-refused
               (:seon.cluster.source/rule result))
            (pr-str result))
        (is (= "injected refusal"
               (get-in result
                       [:seon.source/transaction-result :seon.error/message])))
        (is (= digest-a
               (get-in result [:seon.source/transaction-result :seon.error/offending
                               :tx-data 0 :seon.source/digest]))
            "the refused write is the seal of this publication")
        (is (= #{:db :current-src} (set (registry/roster opened))))
        (is (empty? (scratch-branches opened)))))))

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate cluster branch heads retained across a second physical-store publication."}
  incremental-publication-does-not-change-an-existing-cluster
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)]
        (registry/ensure-cluster!
         {:seon.store/store opened
          :seon.boot/cluster-name "incremental-a"
          :seon.source/commit-id (:seon.source/commit-id a)})
        (let [b (publish opened digest-b 'seon.cluster.source-test/populate-from-data!
                         {:seon.source/expected-commit-id (:seon.source/commit-id a)
                          :seon.source.test/marker "upserted"})]
          (registry/ensure-cluster!
           {:seon.store/store opened
            :seon.boot/cluster-name "incremental-b"
            :seon.source/commit-id (:seon.source/commit-id b)})
          (doseq [[cluster expected-digest expected-markers]
                  [["incremental-a" #{digest-a} #{digest-a}]
                   ["incremental-b" #{digest-b} #{digest-a "upserted"}]]]
            (let [connection
                  (store/open-branch! opened (registry/cluster-branch cluster))]
              (try
                (is (= expected-digest (source-digests connection)))
                (is (= expected-markers (markers connection)))
                (finally
                  (d/release connection))))))))))

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate the physical-store scratch branch publication opens at its published commit."}
  publication-hands-its-commit-projection-unstamped
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)]
        (publish opened digest-b 'seon.cluster.source-test/populate-recording!
                 {:seon.source/expected-commit-id (:seon.source/commit-id a)
                  :seon.source/previous-database nil})
        (let [handed @handed-database
              committed (source/commit-database opened (:seon.source/commit-id a))]
          (is (nil? (:seon.schema/projection (meta handed))))
          (is (identical? (db/carried-projection committed)
                          (db/carried-projection handed))))))))

(deftest source-tombstone-provenance-does-not-prevent-live-removal
  (test-support/with-database
    (fn [connection]
      (let [ctx (sci.eval/build-base-ctx (schema/handed-projection))
            function-symbol 'source-deletion-probe/value
            fn-identity [:seon.fn/sym function-symbol]]
        (test-support/transacted! connection
                                  [(test-support/namespace-entity @connection 'source-deletion-probe)
                                   (test-support/program-fn-entity
                                    (db/db connection) function-symbol
                                    "(ns source-deletion-probe) (defn value [] 1)")])
        (sci/eval-string* ctx "(ns source-deletion-probe) (defn value [] 1)")
        (is (= 1 (sci/eval-string* ctx "(source-deletion-probe/value)")))
        (test-support/transacted!
         connection
         [[:db/retractEntity
           [:seon.test/sym
            'seon.cluster.source-test/source-tombstone-provenance-does-not-prevent-live-removal]]
          [:db/retractEntity fn-identity]])
        (is (= 1 (:seon.sci.eval/installed
                  (sci.eval/install-row!
                   {:seon.sci.eval/ctx ctx :seon.db/db @connection
                    :seon.program/row
                    {:seon.program/delete-identities [fn-identity]
                     :seon.program/ns [:seon.ns/name 'source-deletion-probe]
                     :seon.program/source "(ns-unmap 'source-deletion-probe 'value)"}}))))
        (is (nil? (sci/eval-string* ctx "(resolve 'source-deletion-probe/value)")))
        (is (nil? (db/pull @connection '[*] fn-identity)))))))



(deftest publication-input-digests-include-unreported-edits-and-deletions
  (let [before {"src/a.clj" "a1" "src/b.clj" "b1"}
        after {"src/a.clj" "a2" "src/new.clj" "n1"}]
    (is (= {:seon.test.cache/changed ["src/a.clj" "src/new.clj"]
            :seon.test.cache/removed ["src/b.clj"]}
           (seon.test.cache/changed-inputs before after)))))

(deftest ^{:seon.test/long "Measured 5,590 ms at the REPL (2026-09-25): one nested seon.test/run of one member on the candidate branch (4,788 ms), the candidate write and acquisition."
           :seon.test/long-ms 15000}
  work-on-an-acquired-branch-runs-as-that-branchs-own-work-under-an-agents-custody
  ;; Class regression (custody class, 2026-09-25): the candidate's write, its
  ;; nested test run and the member acquisitions ran under the caller's custody,
  ;; which the writer refuses for another branch. Under the body's own custody
  ;; the gate records its members; a run whose recording write is refused
  ;; returns the writer's declared refusal, never a contract finding.
  (let [target (test-support/execution-handle nil)
        held-store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        entity #(test-support/program-entity (db/db %1) %2 %3)
        s (keyword (str "gate-class-" (id/id)))]
    (test-support/transacted! h-conn [(test-support/namespace-entity (db/db h-conn) 'scratch.gate-class)
                                      (entity h-conn '[:seon.fn/sym scratch.gate-class/f] "(defn f {:malli/schema [:=> [:cat] :int]} [] 1)")])
    (test-support/transacted! h-conn [(entity h-conn '[:seon.test/sym scratch.gate-class/f-check]
                                           "(clojure.test/deftest f-check (clojure.test/is (pos? (scratch.gate-class/f))))")])
    (try
      (let [gate (cluster/candidate-gate!
                  target held-store s
                  (fn [execution]
                    ;; The real writer, never the fixture writer that binds its own custody.
                    (let [c (:seon.db/connection execution)
                          written (db/transact! c [(entity c '[:seon.fn/sym scratch.gate-class/f] "(defn f {:malli/schema [:=> [:cat] :int]} [] 2)")])]
                      (is (:db-after written) (pr-str (select-keys written [:seon.error/message])))
                      #{[:seon.fn/sym 'scratch.gate-class/f]}))
                  (constantly #{}))]
        (is (true? (:seon.test/passed? gate)) (:seon.source/tally gate))
        (is (str/includes? (:seon.source/tally gate) "executed 1 ")))
      (let [execution (cluster.agent/acquire-context! target nil {:seon.agent/branch s})]
        (try
          (let [refused ((requiring-resolve 'seon.test/run)
                         {:seon.test/policy :named :seon.test/execution execution
                          :seon.test/recording-connection (:seon.db/connection execution)
                          :seon.test/changed ['scratch.gate-class/f]})]
            (is (:seon.db/transaction-refused refused) (pr-str (select-keys refused [:seon.error/message])))
            ;; A recording connection outside the caller's custody is the caller's
            ;; mistake: returned, never a core fault (which would throw under :panic).
            (is (get-in refused [:seon.error/data :seon.db/ambient-branch])))
          (finally (cluster.agent/release-context! execution))))
      (finally (registry/retire-branch! {:seon.store/store held-store :seon.store/branch s})))))

(deftest ^{:seon.test/long "Two prepares, each one nested seon.test/run on a new candidate commit (the save gate's cost, lane-nsa-slice4-2026-09-23.md)."
           :seon.test/long-ms 120000}
  a-merge-naming-a-task-the-destination-lacks-refuses-and-an-admitted-task-survives-a-refused-merge
  ;; P1-3 (review 5fb38b91f, spec 7.13): task entities never ride the merge, so the
  ;; destination's admitted tasks decide the merged program's task edges.
  (let [target (test-support/execution-handle nil)
        held-store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        ;; A namespace this test writes itself: test-support/program-entity digests with the
        ;; fixture namespace entity, which must be the one stored (67580898d).
        sym #(symbol "scratch.merge-tasks" %)
        entity #(test-support/program-entity (db/db %1) [%2 (sym %3)] %4)
        write! #(test-support/transacted! %1 (vec %2))
        handle (issue/task-handle 'scratch.merge-tasks "merge-task" :function)
        issue-id (str "merge-task-test-" (id/id))
        c (keyword (str "merge-task-test-c-" (id/id)))
        _ (write! h-conn [(test-support/namespace-entity (db/db h-conn) 'scratch.merge-tasks)
                          (entity h-conn :seon.fn/sym "merge-tasked" "(defn merge-tasked {:malli/schema [:=> [:cat] :int]} [] 1)")])
        _ (write! h-conn [(entity h-conn :seon.test/sym "merge-tasked-check" "(clojure.test/deftest merge-tasked-check (clojure.test/is (pos? (scratch.merge-tasks/merge-tasked))))")
                          {:seon.issue/id issue-id :seon.issue/title "Tasked" :seon.issue/status :open
                           :seon.issue/severity :cleanup :seon.issue/problem "Replace the tasked function."
                           :seon.issue/tests #{[:seon.test/sym (sym "merge-tasked-check")]}}])
        _ (registry/branch! {:seon.store/store held-store :seon.store/branch c
                             :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)
        made (volatile! [])
        prepare #(let [p (source/prepare-merge! target held-store c issue-id)]
                   (some->> (:seon.source/candidate p) (vswap! made conj)) p)
        intent #(:seon.task/intent (db/pull (db/db h-conn) [:seon.task/intent] [:seon.task/id handle]))]
    (try
      (write! c-conn [(entity c-conn :seon.fn/sym "merge-tasked" (str "(defn merge-tasked {:seon/tasks #{\"" handle "\"}} [] 2)"))])
      (let [proposal (prepare)
            before (db/commit-id (db/db h-conn))
            refused (source/accept-merge! target held-store proposal)]
        (is (true? (:seon.test/passed? proposal)) (:seon.source/tally proposal))
        (is (= {handle #{(sym "merge-tasked")}} (:seon.error/offending refused)) (pr-str refused))
        (is (= before (db/commit-id (db/db h-conn))) "the refused merge writes nothing")
        (testing "a task admitted on the destination survives a refused merge, then the merge lands"
          (is (:seon.task/changed? (issue/index-tasks! {:seon.db/connection h-conn :seon.task/basis {}
                                                         :seon.task/files {"resources/seon/tasks/scratch.merge-tasks.edn"
                                                                           "[#:seon.task{:name \"merge-task\" :kind :function :intent \"Merge.\"}]"}})))
          ;; The task write touches no merged entity, so the same proposal now merges.
          (is (:seon.source/commit-id (source/accept-merge! target held-store proposal)))
          (is (= #{handle} (issue/open-tasks (db/db h-conn) [:seon.fn/sym (sym "merge-tasked")])))
          (is (= "Merge." (intent)))))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store held-store :seon.store/branch b}))))))
