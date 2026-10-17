(ns seon.cluster.source-test
  (:require [seon.batch]
            [seon.config]
            [seon.turn.world :as world]
            [seon.cache]
            [seon.test]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.context :as context]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.task :as task]
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
             (doseq [branch (remove #{:db source/current-branch} (registry/branches opened))]
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
          (registry/branches opened)))

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
               (:seon.cluster.source/rule result)))
        (is (= "injected refusal"
               (get-in result
                       [:seon.source/transaction-result :seon.error/message])))
        (is (= digest-a
               (get-in result [:seon.source/transaction-result :seon.error/offending
                               :tx-data 0 :seon.source/digest]))
            "the refused write is the seal of this publication")
        (is (= #{:db :current-src} (set (registry/branches opened))))
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
  ;; the gate records its members.
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
                      (is (:db-after written))
                      #{[:seon.fn/sym 'scratch.gate-class/f]}))
                  nil)]
        (is (true? (:seon.test/passed? gate)))
        (is (str/includes? (:seon.source/tally gate) "executed 1 ")))
      (finally (registry/retire-branch! {:seon.store/store held-store :seon.store/branch s})))))

(deftest an-undefined-slot-is-not-a-complete-contract
  ;; a merge's changed functions are judged by the one function-contract rule: no contract and an unjustified
  ;; :any or :some slot are both incomplete, each position named; a justified boundary and a full contract pass
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.any
      (str "(ns tq.any)\n"
           "(defn whole {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
           "(defn bare [x] x)\n"
           "(defn loose {:malli/schema [:=> [:cat :any :int] [:sequential :some]]} [x y] [x y])\n"
           "(defn boundary {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary"
           " :seon.schema.admission/reason \"A total predicate.\" :gen/elements [nil 0]}]] :boolean]} [x] (some? x))\n"))
     (let [database (db/db connection)
           identities (into #{} (map #(vector :seon.fn/sym (symbol "tq.any" %))) ["whole" "bare" "loose" "boundary"])]
       (is (= '[tq.any/bare tq.any/loose] (source/uncontracted database identities))
           "the function with no contract and the one with undefined slots are uncontracted")
       (is (= #{['tq.any/bare :seon.fn.contract.position/contract :seon.fn.contract.finding/missing-spec]
                ['tq.any/loose [:seon.fn.contract.position/input 0] :seon.fn.contract.finding/any]
                ['tq.any/loose :seon.fn.contract.position/output :seon.fn.contract.finding/some]}
              (into #{} (map (juxt :seon.fn/sym :seon.fn.contract/position :seon.fn.contract/finding))
                    (source/contract-gaps database identities)))
           "each gap names its function, position and finding")))))

(defn- recorded
  "A recorded result of `test-symbol`, green or red."
  [test-symbol green?]
  {:seon.test/sym test-symbol :seon.test/pass-count (if green? 1 0) :seon.test/fail-count (if green? 0 1)
   :seon.test/error-count 0 :seon.test/run-basis-t 1 :seon.test/run-at #inst "2026-10-14"})

(deftest done-refusals-judges-every-task-alike
  ;; the done rule, for every task: a function of the judged results, the next task's proofs and what main holds
  ;; green; it runs no test, and the branch is read only for the changed functions' contracts
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.done
      (str "(ns tq.done)\n"
           "(defn stub {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
           "(defn loose {:malli/schema [:=> [:cat :any] :int]} [x] 1)\n"))
     (let [branch (db/db connection)
           base {:seon.source/members #{'tq.done-test/proof} :seon.task/proofs #{'tq.done-test/proof}
                 :seon.source/results [(recorded 'tq.done-test/proof true)]
                 :seon.program/identities #{} :seon.test.run/id "run-1" :seon.test.run/branch :tq-done}
           red (fn [judged test-symbol & more]
                 (-> judged (update :seon.source/members conj test-symbol)
                     (update :seon.source/results conj (recorded test-symbol false))
                     (as-> j (reduce (fn [j [k v]] (assoc j k v)) j (partition 2 more)))))
           new-red (fn [judged] (-> (red judged 'tq.done-test/new)
                                    (update :seon.program/identities conj [:seon.test/sym 'tq.done-test/new])))
           lint-refused (source/lint-refusal [{:seon.source/path "test/tq/done_test.clj" :seon.lint/row 3 :seon.lint/col 9
                                               :seon.lint/type :seon/unbounded-assertion-message
                                               :seon.lint/message "An is message is prose."}])]
       (doseq [[row judged expected]
               [["own proof green: done" base []]
                ["own proof red: refused"
                 (assoc base :seon.source/results [(recorded 'tq.done-test/proof false)]) [:seon.source/red-test-error]]
                ["own proof with no result: refused" (assoc base :seon.source/results []) [:seon.source/test-evidence-error]]
                ["own proof that never started: unknown evidence, not red"
                 (assoc base :seon.source/results
                        [(seon.error.refusal/error :seon.test/result-unknown
                          {:seon.error/layer :seon.test/execution :seon.error/operation 'seon.test.runner/recorded-member-results
                           :seon.error/message "Never started." :seon.error/expected :completed-member
                           :seon.error/offending 'tq.done-test/proof :seon.test/execution-refusal :seon.test/population-unknown
                           :seon.test/error-test-symbol 'tq.done-test/proof :seon.test/run [:seon.test.run/id "run-1"]
                           :seon.test/run-at #inst "2026-10-14"})])
                 [:seon.source/test-evidence-error]]
                ["an empty proof set never finishes" (assoc base :seon.task/proofs #{}) [:seon.source/test-evidence-error]]
                ["a Prove step with no proof of its own, its product the next task's red proof: done"
                 (red (assoc base :seon.task/proofs #{} :seon.source/members #{} :seon.source/results [])
                      'tq.done-test/next :seon.source/next-proofs #{'tq.done-test/next})
                 []]
                ["a Prove step with no proof of its own, one product test not yet written: refused"
                 (red (assoc base :seon.task/proofs #{} :seon.source/members #{} :seon.source/results [])
                      'tq.done-test/next :seon.source/next-proofs #{'tq.done-test/next 'tq.done-test/unwritten})
                 [:seon.source/test-evidence-error]]
                ["a branch-new red outside the next proofs: refused"
                 (assoc (new-red base) :seon.source/next-proofs #{'tq.done-test/other}) [:seon.source/red-test-error]]
                ["the same red among the next proofs: done"
                 (assoc (new-red base) :seon.source/next-proofs #{'tq.done-test/new}) []]
                ["the terminal task with a branch-new red: refused" (new-red base) [:seon.source/red-test-error]]
                ["a reaching test red here: refused, whatever main or the merge base holds"
                 (red base 'tq.done-test/existing) [:seon.source/red-test-error]]
                ["the same red as a next proof, a row added to an existing table: done"
                 (red base 'tq.done-test/existing :seon.source/next-proofs #{'tq.done-test/existing})
                 []]
                ["a new function with an :any slot: refused"
                 (update base :seon.program/identities conj [:seon.fn/sym 'tq.done/loose]) [:seon.source/uncontracted-error]]
                ["a contracted stub no test reaches: done"
                 (update base :seon.program/identities conj [:seon.fn/sym 'tq.done/stub]) []]
                ["green proofs, a clj-kondo error in a file the merge would publish: refused"
                 (assoc base :seon.source/lint-refusal lint-refused) [:seon.source/lint-error]]
                ["the last task: a function its merge carries that no green test reaches: refused"
                 (assoc base :seon.source/merge-identities #{[:seon.fn/sym 'tq.done/stub]}) [:seon.source/test-evidence-error]]
                ["a middle task: the same function waits for the next task's proofs: done"
                 (assoc base :seon.source/merge-identities #{[:seon.fn/sym 'tq.done/stub]}
                        :seon.source/next-proofs #{'tq.done-test/other})
                 []]]]
         (is (= expected (mapv :seon.error/type (source/done-refusals branch judged))) row))
       (is (= [:working [:seon.source/lint-error]]
              ((juxt :seon.task/status #(mapv :seon.error/type (:seon.source/refusals %)))
               (context/step-status {:seon.task/proofs #{'tq.done-test/proof} :seon.task/product #{} :seon.context/head {}
                                     :seon.context/candidate branch :seon.context/main branch
                                     :seon.source/judged (assoc base :seon.source/lint-refusal lint-refused)})))
           "an own-branch assignment's step, in no chain, is judged by the same done rule")
       (is (= [['tq.done-test/new]]
              (mapv #(mapv :seon.test/sym (:seon.source/red-tests %)) (source/done-refusals branch (new-red base))))
           "the red-test refusal names the red test")))))

(deftest done-and-merge-judge-a-red-reaching-test-the-same-way
  ;; live run 2, chain ce9d: a reaching test red after the change with no result on main; the done rule counted it
  ;; no regression and the merge counted it the change's. Owner 2026-10-17 (merge-rulings.md): red is red, whether
  ;; or not main holds it red, and the merge judges with the done rule, so neither reads main for it
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.same-test
      (str "(ns tq.same-test (:require [clojure.test :refer [deftest is]]))\n"
           "(deftest reaching (is (= 1 1)))\n")
      "test")
     (let [branch (db/db connection)
           results [(recorded 'tq.same-test/proof true) (recorded 'tq.same-test/reaching false)]
           judged {:seon.source/members #{'tq.same-test/proof 'tq.same-test/reaching}
                   :seon.task/proofs #{'tq.same-test/proof} :seon.source/results results
                   :seon.program/identities #{} :seon.test.run/id "run-1" :seon.test.run/branch :tq-same}
           shape (fn [refusals] (mapv (juxt :seon.error/type #(mapv (juxt :seon.test/sym :seon.source/verdict) (:seon.source/red-tests %)))
                                      refusals))]
       (is (= [[:seon.source/red-test-error [['tq.same-test/reaching :red]]]]
              (shape (source/done-refusals branch judged)))
           "the done rule, the merge's one judge too, refuses it, naming the test")))))

(deftest done-and-merge-judge-one-member-list
  ;; live run 4, chain 42e0: done judged its proof and one changed test while the merge's run selected 64 tests
  ;; reaching its change (a changed namespace row seeds every member of the namespace), three of them red; four other
  ;; chains capped on reaching tests no run of theirs selected. One function gives the list the test step runs, the
  ;; done rule judges and the merge gate runs
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.one-test
      (str "(ns tq.one-test (:require [clojure.test :refer [deftest is]]))\n"
           "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
           "(defn g {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
           "(deftest proof (is (= 1 (f 1))))\n"
           "(deftest reaches-f (is (= 2 (f 2))))\n"
           "(deftest reaches-g (is (= 3 (g 3))))\n"
           "(deftest ^{:seon.test/long \"declared long\"} long-reaches-f (is (= 4 (f 4))))\n")
      "test")
     (let [database (db/db connection)
           listed (fn [changed] (:seon.source/members (source/merge-members database changed #{'tq.one-test/proof})))]
       (is (= #{'tq.one-test/proof 'tq.one-test/reaches-f} (listed #{[:seon.fn/sym 'tq.one-test/f]}))
           "a changed function: the proof and the tests reaching it, a declared-long one aside")
       (is (= #{'tq.one-test/proof 'tq.one-test/reaches-f 'tq.one-test/reaches-g} (listed #{[:seon.ns/name 'tq.one-test]}))
           "a changed namespace row: the tests reaching every member of it, as the merge gate selects them")
       (is (= #{'tq.one-test/proof 'tq.one-test/long-reaches-f} (listed #{[:seon.test/sym 'tq.one-test/long-reaches-f]}))
           "a changed test is a member even when declared long")
       (is (= #{} (:seon.source/deferred (source/merge-members database #{[:seon.fn/sym 'tq.one-test/f]} #{'tq.one-test/proof})))
           "no member here runs only on a platform host")
       (let [members (listed #{[:seon.fn/sym 'tq.one-test/f]})
             judged {:seon.source/members members :seon.task/proofs #{'tq.one-test/proof}
                     :seon.source/results [(recorded 'tq.one-test/proof true) (recorded 'tq.one-test/reaches-f false)]
                     :seon.program/identities #{} :seon.test.run/id "run-1" :seon.test.run/branch :tq-one}
             shape (fn [refusals] (mapv (juxt :seon.error/type #(mapv :seon.test/sym (:seon.source/red-tests %))) refusals))]
         (is (= [[:seon.source/red-test-error ['tq.one-test/reaches-f]]] (shape (source/done-refusals database judged)))
             "done, the merge's one judge too, refuses the reaching member turned red")
         (let [red-proof (assoc judged :seon.source/results [(recorded 'tq.one-test/proof false) (recorded 'tq.one-test/reaches-f true)])]
           (is (= [[:seon.source/red-test-error ['tq.one-test/proof]]] (shape (source/done-refusals database red-proof)))
               "a red proof refuses"))
         (let [deferred (assoc judged :seon.source/results [(recorded 'tq.one-test/proof true)]
                               :seon.source/deferred #{'tq.one-test/reaches-f})]
           (is (= [] (source/done-refusals database deferred))
               "a deferred member is the merge's platform run's to answer, so done waits for none")))))))

(deftest unchanged-call-graph-reuses-the-merges-member-list
  (test-support/with-database
   (fn [connection]
     (let [declare! #(test-support/declare-source!
                     connection 'tq.cached-test
                     (str "(ns tq.cached-test (:require [clojure.test :refer [deftest is]]))\n"
                          "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                          "(deftest proof (is (= 1 " % ")))\n") "test")
           changed #{[:seon.fn/sym 'tq.cached-test/f]}]
       (declare! "(f 1)")
       (let [database (db/db connection)
             first-list (source/merge-members database changed #{})
             repeated (profile/measure #(source/merge-members database changed #{}))]
         (is (= first-list (:seon.profile/value repeated)))
         (is (zero? (profile/calls-of repeated 'seon.test/reaching))))
       (declare! "1")
       (is (= #{} (:seon.source/members (source/merge-members (db/db connection) changed #{}))))))))

(deftest done-and-merge-judge-a-reaching-test-with-no-current-result-the-same-way
  ;; live run 3, chain fddd: the agent restored main's text of an existing test its change turned red, and the
  ;; attempt ended before the reaching run on that text finished; the done rule skipped the member with no current
  ;; result while the merge ran it red. A member with no executed result is unknown evidence for both judges.
  (test-support/with-database
   (fn [connection]
     (let [branch (db/db connection)
           judged {:seon.source/members #{'tq.unknown-test/proof 'tq.unknown-test/reaching}
                   :seon.task/proofs #{'tq.unknown-test/proof}
                   :seon.source/results [(recorded 'tq.unknown-test/proof true)]
                   :seon.program/identities #{} :seon.test.run/id "run-1" :seon.test.run/branch :tq-unknown}
           shape (fn [refusals] (mapv (juxt :seon.error/type :seon.error/offending) refusals))]
       (is (= [[:seon.source/test-evidence-error ['tq.unknown-test/reaching]]]
              (shape (source/done-refusals branch judged)))
           "the done rule, the merge's one judge too, refuses the reaching member with no current result, naming it")))))

(deftest an-uncovered-refusal-names-the-red-tests-reaching-it
  ;; live run 2, chain ce9d: its proof was green, but the one test the call graph walks to seon.effect/render-ai
  ;; was red, so the done rule refused render-ai as reached by no green test, naming no test; the agent spent its
  ;; last four calls querying runs by hand for what would satisfy the refusal
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.reach
      "(ns tq.reach)\n(defn stub {:malli/schema [:=> [:cat :int] :int]} [x] x)\n")
     (test-support/declare-source!
      connection 'tq.reach-test
      (str "(ns tq.reach-test (:require [clojure.test :refer [deftest is]]))\n"
           "(deftest ^{:seon.test/subject \"tq.reach/stub\"} reaches (is (= 1 1)))\n")
      "test")
     (let [judged {:seon.source/members #{'tq.reach-test/proof 'tq.reach-test/reaches}
                   :seon.task/proofs #{'tq.reach-test/proof}
                   :seon.source/results [(recorded 'tq.reach-test/proof true) (recorded 'tq.reach-test/reaches false)]
                   :seon.program/identities #{[:seon.fn/sym 'tq.reach/stub]}
                   :seon.source/merge-identities #{[:seon.fn/sym 'tq.reach/stub]}
                   :seon.test.run/id "run-1" :seon.test.run/branch :tq-reach}
           measured (profile/measure #(source/done-refusals (db/db connection) judged))
           uncovered (filterv #(= ['tq.reach/stub] (:seon.error/offending %)) (:seon.profile/value measured))]
       (is (= [['tq.reach-test/reaches]] (mapv #(mapv :seon.test/sym (:seon.source/red-tests %)) uncovered))
           "the refusal names the red test that would cover the function once green")
       (is (zero? (profile/calls-of measured 'seon.fn/gate-sets))
           "coverage reads the member fold's seed to tests, not a reach walk per function")))))

(deftest done-refusals-judges-a-never-started-member-as-evidence
  ;; a member whose recorded result never started is unknown evidence; only an executed failure is a red test
  (test-support/with-database
   (fn [connection]
     (let [branch (db/db connection)
           never-started (seon.error.refusal/error
                          :seon.test/result-unknown
                          {:seon.error/layer :seon.test/execution :seon.error/operation 'seon.test.runner/recorded-member-results
                           :seon.error/message "Never started." :seon.error/expected :completed-member
                           :seon.error/offending 'tq.rv-test/proof :seon.test/execution-refusal :seon.test/population-unknown
                           :seon.test/error-test-symbol 'tq.rv-test/proof :seon.test/run [:seon.test.run/id "run-1"]
                           :seon.test/run-at #inst "2026-10-14"})
           judged {:seon.source/members #{'tq.rv-test/proof} :seon.task/proofs #{'tq.rv-test/proof}
                   :seon.source/results [never-started] :seon.program/identities #{}
                   :seon.test.run/id "run-1" :seon.test.run/branch :tq-rv}]
       (doseq [[row results expected]
               [["never started: test evidence" [never-started] [:seon.source/test-evidence-error]]
                ["executed red: a red test" [(recorded 'tq.rv-test/proof false)] [:seon.source/red-test-error]]]]
         (is (= expected (mapv :seon.error/type (source/done-refusals branch (assoc judged :seon.source/results results)))) row))))))

(deftest member-fold-equals-the-full-merge-list
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "default")
     (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) 'cost.fold)])
     (let [context (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection)) (db/db connection) connection)
           evaluation (world/world {:seon.db/connection connection :seon.boot/cluster-name "default"
                                    :seon.sci.eval/ctx context :seon.batch/sink (seon.batch/sink)
                                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms seon.config/defaults)
                                    :seon.agent/id "member-fold"})
           changed #{[:seon.fn/sym 'cost.fold/a] [:seon.fn/sym 'cost.fold/c]}
           define! #(test-support/define! evaluation 'cost.fold %)]
       (define! (str "(defn a {:malli/schema [:=> [:cat :int] :int]} [x] x) "
                     "(defn b {:malli/schema [:=> [:cat :int] :int]} [x] x) "
                     "(defn c {:malli/schema [:=> [:cat :int] :int]} [x] x) "
                     "(defn middle {:malli/schema [:=> [:cat :int] :int]} [x] (a x)) "
                     "(clojure.test/deftest left (clojure.test/is (= 1 (middle 1)))) "
                     "(clojure.test/deftest shared (clojure.test/is (= (a 1) (c 1)))) "
                     "(clojure.test/deftest right (clojure.test/is (= 1 (c 1))))"))
       (source/merge-members (db/db connection) changed #{})
       (define! "(defn middle {:malli/schema [:=> [:cat :int] :int]} [x] (a (+ x 0)))")
       (let [measured (profile/measure #(source/merge-members (db/db connection) changed #{}))]
         (is (= #{'cost.fold/left 'cost.fold/shared 'cost.fold/right}
                (:seon.source/members (:seon.profile/value measured))))
         (is (zero? (profile/calls-of measured 'seon.fn/nearest-tests))
             "an edge outside every selection walk leaves the accumulated contributions held"))
       (doseq [[callee identities expected] [["b" changed #{'cost.fold/shared 'cost.fold/right}]
                                             ["a" changed #{'cost.fold/left 'cost.fold/shared 'cost.fold/right}]
                                             ["b" #{[:seon.fn/sym 'cost.fold/c]} #{'cost.fold/shared 'cost.fold/right}]
                                             ["a" #{} #{}]]]
         (define! (str "(defn middle {:malli/schema [:=> [:cat :int] :int]} [x] (" callee " x))"))
         (let [database (db/db connection)
               folded (source/merge-members database identities #{})
               revisions (seon.test/selection-revisions database)]
           (is (= expected (:seon.source/members folded)))
           (seon.cache/evict! :seon.cluster.source/members [revisions identities #{}])
           (seon.cache/evict! :seon.cluster.source/member-contributions [revisions identities])
           (is (= folded (source/merge-members database identities #{}))
               "the fold and a full derivation after eviction agree")))))))
