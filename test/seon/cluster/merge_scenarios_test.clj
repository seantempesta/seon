(ns seon.cluster.merge-scenarios-test
  "git's merge scenarios end to end through root's db<->flow pass: a declaration main and a chain both changed
  since the chain forked, its final merge, the merge step it holds and root's merge of that step. The chain world
  is `seon.cluster.db-flow-test`'s (its fixtures, by Var)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.cluster.db-flow-test]
            [seon.cluster.registry :as registry]
            [seon.cluster.source]
            [clojure.java.shell]
            [seon.config :as config]
            [seon.fn]
            [seon.program]
            [seon.db :as db]
            [seon.id :as id]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(def ^:private space "tq.mergeconflict")

(def ^:private proofs-ns
  "The namespace holding `shared` and the chain's proofs, a file of the chain world's checkout."
  (symbol (str space "-rows")))

(defn- shared-source
  "The source of `shared` with body `n`."
  {:malli/schema [:=> [:cat [:or :int :string]] :string]}
  [n]
  (str "(defn shared {:malli/schema [:=> [:cat] :int]} [] " n ")"))

(def ^:private rows-path "test/tq/mergeconflict_rows.clj")

(defn- rows-file
  "The checkout file of `proofs-ns` with `shared` at body `n`: `shared` and the chain's two proofs."
  {:malli/schema [:=> [:cat :int] :string]}
  [n]
  (str "(ns " proofs-ns "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       (shared-source n) "\n\n"
       "(deftest proves-u (is (pos? (shared))))\n\n"
       "(deftest proves-two (is (= 2 (shared))))\n"))

(defn- publish!
  "Main moves as a published merge leaves it: the checkout file `text` committed at `root` and its declarations
  reconciled on main from that file, as `seon.cluster.db-flow-test/checkout!` declares them."
  {:malli/schema [:=> [:cat :seon.db/connection :string :string] :seon.schema/value]}
  [connection root text]
  (spit (java.io.File. ^String root ^String rows-path) text)
  (doseq [args [["add" "."] ["commit" "-q" "-m" "main moves"]]]
    (apply clojure.java.shell/sh "git" "-C" root args))
  (let [entities (seon.fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})]
    (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) entities
                                                               (mapv seon.program/row-identity entities)))))

(deftest ^{:seon.test/long "One chain world: a chain and main both change one function, the chain's final merge refuses on the conflict and holds a merge step, root runs it, its scripted turn resolves the conflict and root's pass merges the merge branch; about six db<->flow passes." :seon.test/long-ms 90000}
  a-conflict-merge-step-resolves-the-conflict-and-merges-onto-main
  ;; Owner, 2026-10-16: main has moved and changed the same declaration the chain changed. git refuses the merge with
  ;; a conflict, someone resolves it, and the resolution is what lands. MERGE-STEP: the gate refuses on the conflict,
  ;; keeps its merge branch (main's head with the chain's version forced on top) and holds a merge step; its agent sees
  ;; the conflict as data and resolves it at the REPL; root merges the repaired branch onto main's latest head
  (with-bindings {#'seon.cluster.db-flow-test/*space* space}
    (test-support/with-database
     (fn [connection]
       (let [requests (atom [])
             checkout (atom nil)
             resolution (shared-source "(+ 1 1)")]
         (test-support/with-provider
          requests (test-support/reply-queue [(str "(in-ns '" proofs-ns ")\n" (shared-source 2))
                                                (str "(in-ns '" proofs-ns ")\n" resolution)])
          (fn [server]
            (#'seon.cluster.db-flow-test/chain-world
             connection {"u" [#{} "(clojure.test/is true)"]}
             (fn [handle]
               ;; the checkout the merges are proven against and published into: `shared` and the chain's proofs in one file
               (let [root (#'seon.cluster.db-flow-test/checkout! connection {rows-path (rows-file 1)})]
                 (reset! checkout root))
               (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "tq-chain"
                               :seon.config/manifest (assoc (test-support/provider-config server) :seon.config.ai/no-provider false)})
               (#'seon.cluster.db-flow-test/with-merge-template! connection)
               (let [routing (:seon.agent/routing handle)
                     store (agent/handle-store handle)
                     chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                                        [{:seon.task/ns (symbol space) :seon.task/name "c" :seon.task/plan "Do c."
                                          :seon.task/proofs #{(symbol (str proofs-ns) "proves-u") (symbol (str proofs-ns) "proves-two")}}])
                     branch (:seon.chain/branch chain)
                     [last-task] (:seon.task/handles chain)
                     before (#'seon.cluster.db-flow-test/merge-branches store)
                     shared (symbol (str proofs-ns) "shared")
                     source-of (fn [database] (:seon.fn/source (db/pull database [:seon.fn/source] [:seon.fn/sym shared])))
                     context {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                              :my.program/base-ctx (:seon.sci.eval/ctx handle) :seon.store/store store}
                     end-of (fn [on k] (#'seon.cluster.db-flow-test/await-on!
                                        on #(db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k]
                                                    [?a :seon.task.attempt/end ?e]]
                                                  (db/db on) k)
                                        ::ended))]
                 (try
                   ;; the chain's agent changes `shared` to 2 in its one scripted turn (its proof `proves-two` is red at the fork)
                   (#'seon.cluster.db-flow-test/pass! handle routing)
                   (let [on-branch (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})
                         attempt-key (id/id [last-task 1])]
                     (is (= :done (end-of on-branch attempt-key)) "the chain's last task ends done, its change made")
                     (is (= (shared-source 2) (source-of (db/db on-branch))) "the chain changed `shared`")
                     ;; main has moved since the fork and changed the same function
                     (publish! connection @checkout (rows-file 3))
                     (is (some? (get-in (db/pull (db/db connection) [{:seon.fn/file [:seon.fn.file/relative-path]}] [:seon.fn/sym (symbol (str proofs-ns) "shared")])
                                        [:seon.fn/file :seon.fn.file/relative-path]))
                         "main's change keeps the checkout file its declaration lives in")
                     (#'seon.cluster.db-flow-test/pass! handle routing)
                     (let [chain-db (db/db on-branch)
                           step (get-in (db/pull chain-db [{:seon.task/next [:seon.task/id]}] [:seon.task/id last-task])
                                        [:seon.task/next :seon.task/id])
                           held (db/pull chain-db [:seon.task/execution :seon.task/branch :seon.task/refusal :seon.task/instructions]
                                         [:seon.task/id (or step "")])
                           kept (:seon.task/branch held)
                           step-key (id/id [step 1])]
                       (is (= :seon.source/conflict-error
                              (:seon.task/refusal (db/pull chain-db [:seon.task/refusal] [:seon.task/assignment attempt-key])))
                           "the final merge refuses on the conflict")
                       (is (= (shared-source 3) (source-of (db/db connection)))
                           "and main keeps its own change")
                       (is (= {:seon.task/execution :held :seon.task/refusal :seon.source/conflict-error}
                              (select-keys held [:seon.task/execution :seon.task/refusal]))
                           "a merge step is held with the conflict as its refusal")
                       (is (keyword? kept) "naming the merge branch the gate kept")
                       (is (str/includes? (str (:seon.task/instructions held)) (str shared))
                           "its instructions name the conflicted declaration")
                       ;; git's model: a conflict stays unmerged until someone resolves it; the merge branch the step
                       ;; works on is main's head with the chain's version forced on top, so its comparison with main
                       ;; still has to name the conflict, or the next merge carries the chain's version over main's
                       (let [main (db/db connection)
                             {:keys [three base branch]} (seon.cluster.source/compare-branch store kept main (get-in main [:config :branch]))]
                         (is (contains? (:seon.program/conflict three) [:seon.fn/sym shared])
                             "the merge branch still holds the conflict against main until it is resolved")
                         (seon.cluster.source/release! (filter some? [base branch])))
                       ((requiring-resolve 'my.root/run!) {:my.program/context context :seon.task/assigned step})
                       (#'seon.cluster.db-flow-test/pass! handle routing)
                       (let [there (registry/active-branch-connection {:seon.store/store store :seon.store/branch kept})]
                         (is (= :done (end-of there step-key)) "the merge step ends done on its merge branch")
                         (is (= 2 (count @requests))
                             "its agent took a model turn: a conflict is resolved by someone, never ended done unseen")
                         (is (= resolution (source-of (db/db there))) "the merge branch holds the agent's resolution"))
                       (#'seon.cluster.db-flow-test/pass! handle routing)
                       (is (nil? (:seon.task/reason (db/pull (db/db on-branch) [:seon.task/reason] [:seon.task/assignment step-key])))
                           "root's merge of the merge branch refuses nothing")
                       (let [main (db/db connection)]
                         (is (= :merged (:seon.task/outcome (db/pull main [:seon.task/outcome] [:seon.task/assignment step-key])))
                             "root's pass merges the merge step's branch")
                         (is (= resolution (source-of main))
                             "main's latest head holds the resolution, neither side's version forced over the other"))))
                   (finally
                     (doseq [kept (remove before (#'seon.cluster.db-flow-test/merge-branches store))]
                       (#'seon.cluster.db-flow-test/release-chain! routing store kept))
                     (#'seon.cluster.db-flow-test/release-chain! routing store branch)))))))))))))

