(ns seon.merge-forward-test
  (:require [clojure.test :refer [deftest is]]
            [seon.merge :as merge]
            [seon.step :as step]
            [seon.cluster.source :as source]
            [seon.program :as program]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.id :as id]
            [seon.task :as task]
            [seon.test-support :as support]))

(deftest a-forward-merge-brings-the-files-main-added
  ;; git merge main brings a file main added after the fork: main writes a new file and a function declared in it,
  ;; and the chain branch, which never had that file, receives both in one transaction
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        sym (symbol "seon.merge-forward-test" (str "added-" suffix))
        path (str "test/fixture/forward_added_" suffix ".clj")
        write! (fn [connection entities] (support/transacted! connection (vec entities)))
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (write! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "file-" suffix)
                       :seon.task/plan "Merge main's new file forward into `seon.merge/merge-main!`'s chain."
                       :seon.task/proofs '#{fixture.forward/proof}}])
        on-chain (fn [f] (registry/on-branch store chain f))]
    (try
      (write! main-conn [{:seon.fn.file/relative-path path :seon.fn.file/digest (id/digest 64 [path])}])
      (write! main-conn [(support/program-entity (db/db main-conn) [:seon.fn/sym sym]
                                                 (str "(defn " (name sym) " {:malli/schema [:=> [:cat] :int]} [] 1)")
                                                 {:seon.fn/file [:seon.fn.file/relative-path path]})])
      (let [merged (merge/merge-main! target chain)
            after (on-chain db/db)]
        (is (nil? (:seon.error/type merged)) "the forward merge carries the file its new declaration names")
        (is (contains? (:seon.program/identities merged) [:seon.fn/sym sym]) "main's new function moved")
        (is (= path (get-in (db/pull after [{:seon.fn/file [:seon.fn.file/relative-path]}] [:seon.fn/sym sym])
                            [:seon.fn/file :seon.fn.file/relative-path]))
            "the branch holds the function in main's new file"))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))

(deftest a-forward-merge-is-a-merge-commit-on-mains-head
  ;; git merge main: the branch's new head has main's head as a parent, so Datahike's commit graph makes main's head
  ;; the merge base (`seon.cluster.source/merge-base`) and nothing stores it; the branch is built on main's adoption
  ;; record at that base; a relaunch forked from the branch's end has the same base
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        relaunch (keyword (str "relaunch-" suffix))
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "record-" suffix)
                       :seon.task/plan "Merge main forward as a merge commit in `seon.merge/merge-main!`."
                       :seon.task/proofs '#{fixture.forward/proof}}])
        record (fn [database] (into {} (db/q '[:find ?n ?c :where [?e :seon.cluster/name ?n] [?e :seon.source/commit-id ?c]]
                                            database)))
        [cluster-name] (first (record (db/db main-conn)))
        on-chain (fn [f] (registry/on-branch store chain f))]
    (try
      (support/transacted! main-conn [[:db/add [:seon.cluster/name cluster-name] :seon.source/commit-id
                                       (db/commit-id (db/db main-conn))]])
      (let [adopted (record (db/db main-conn))
            main-commit (db/commit-id (db/db main-conn))
            merged (merge/merge-main! target chain)
            head (on-chain db/db)
            base (source/merge-base head (db/commit-id head) main-commit)]
        (is (= main-commit (:seon.source/commit-id merged)) "the forward merge is accepted onto main's head")
        (is (contains? (set (d/parent-commit-ids head)) main-commit) "the branch's head is a merge commit naming main's")
        (is (= main-commit base) "the merge base is main's head, from the commit graph")
        (is (= adopted (record (db/commit-database head base))) "the branch is built on main's record at its merge base")
        (is (= #{} (:seon.program/identities (merge/merge-main! target chain))) "an up-to-date branch merges nothing")
        (is (= (db/commit-id head) (db/commit-id (on-chain db/db))) "and writes no commit")
        (registry/fork! {:seon.store/store store :seon.store/branch relaunch :seon.branch/target (get-in (db/db main-conn) [:config :branch])
                         :seon.cluster.registry/from chain :seon.db/db head})
        (let [forked (registry/on-branch store relaunch db/db)]
          (is (= main-commit (source/branch-merge-base forked)) "a relaunch forked from the branch's end has the same base")))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch relaunch})
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))

(deftest after-a-forward-merge-the-branch-changed-only-its-own-declarations
  ;; git diff merge-base HEAD: after `git merge main`, what main moved in sits at the merge base's digest, so the
  ;; branch's change (`seon.step/changed`, what the done rule judges) is the one function it defined, not main's
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        own (symbol "seon.merge-forward-test" (str "own-" suffix))
        theirs (mapv #(symbol "seon.merge-forward-test" (str "theirs-" % "-" suffix)) (range 8))
        defined (fn [database sym] (support/program-entity database [:seon.fn/sym sym]
                                                           (str "(defn " (name sym) " {:malli/schema [:=> [:cat] :int]} [] 1)")))
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "own-" suffix)
                       :seon.task/plan "Judge only the branch's own change after `seon.merge/merge-main!`."
                       :seon.task/proofs '#{fixture.forward/proof}}])
        on-chain (fn [f] (registry/on-branch store chain f))
        functions (fn [identities] (into #{} (keep (fn [[a v]] (when (= :seon.fn/sym a) v))) identities))]
    (try
      (on-chain #(support/transacted! % [(defined (db/db %) own)]))
      ;; main moves on further than the branch did: its t passes every t the branch wrote
      (doseq [sym theirs] (support/transacted! main-conn [(defined (db/db main-conn) sym)]))
      (let [merged (merge/merge-main! target chain)]
        (is (= (set theirs) (functions (:seon.program/identities merged))) "main's functions moved forward")
        (is (= #{own} (on-chain #(step/changed (db/db %) :seon.fn/sym)))
            "the step's change is the branch's own function")
        (on-chain
         (fn [connection]
           (let [after (db/db connection)
                 base (source/merge-base-database after (db/db main-conn))]
             (is (= #{own} (functions (program/own-changed-identities base after source/merge-bound)))
                 "the done rule judges the branch's own function")
             (source/release! [base])))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))

(deftest a-change-past-its-read-bound-answers-the-refusal
  ;; a branch that changed more declarations than the bound reads gets `changed-identities`' refusal back from
  ;; `seon.program/own-changed-identities`, never a contract error from comparing digests over the refusal
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        syms (mapv #(symbol "seon.merge-forward-test" (str "bound-" % "-" suffix)) (range 2))
        defined (fn [database sym] (support/program-entity database [:seon.fn/sym sym]
                                                           (str "(defn " (name sym) " {:malli/schema [:=> [:cat] :int]} [] 1)")))
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "bound-" suffix)
                       :seon.task/plan "Refuse a change past its bound in `seon.program/own-changed-identities`."
                       :seon.task/proofs '#{fixture.forward/proof}}])]
    (try
      (registry/on-branch store chain
                          (fn [connection]
                            (doseq [sym syms] (support/transacted! connection [(defined (db/db connection) sym)]))
                            (let [after (db/db connection)
                                  base (source/merge-base-database after (db/db main-conn))
                                  changed (program/own-changed-identities base after {:seon.program/max-datoms 1})]
                              (is (= :seon.program/read-bound (:seon.program/missing-evidence changed))
                                  "the bound's refusal is the answer")
                              (source/release! [base]))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))

(deftest ^{:seon/tasks #{'seon.program/a-digest-change-converts-every-stored-declaration}}
  a-forward-merge-converts-the-branchs-own-declarations-to-the-current-digest
  ;; a declaration the branch wrote under a previous `seon.program/definition-digest` formula (as a store written by
  ;; an older JVM holds it) is converted by the forward merge, so the writer admits a copy of it (a test world's
  ;; program overlay, `seon.test/clean-request-handle`) instead of refusing its stored digest
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        own (symbol "seon.merge-forward-test" (str "formula-" suffix))
        theirs (symbol "seon.merge-forward-test" (str "formula-main-" suffix))
        defined (fn [database sym] (support/program-entity database [:seon.fn/sym sym]
                                                           (str "(defn " (name sym) " {:malli/schema [:=> [:cat] :int]} [] 1)")))
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "formula-" suffix)
                       :seon.task/plan "Convert the branch's digests in `seon.merge/merge-main!`."
                       :seon.task/proofs '#{fixture.forward/proof}}])
        on-chain (fn [f] (registry/on-branch store chain f))
        stored #(:seon.program/definition-digest (db/pull % [:seon.program/definition-digest] [:seon.fn/sym own]))
        previous (id/digest 64 [:previous-formula own])]
    (try
      (on-chain (fn [connection]
                  (support/transacted! connection [(defined (db/db connection) own)])
                  ;; the previous formula's digest, as the older JVM's checked writer stored it
                  (d/transact connection {:tx-data [[:db/add [:seon.fn/sym own] :seon.program/definition-digest previous]]})))
      (is (= previous (on-chain #(stored (db/db %)))) "the branch holds the previous formula's digest")
      (support/transacted! main-conn [(defined (db/db main-conn) theirs)])
      (let [merged (merge/merge-main! target chain)]
        (is (nil? (:seon.error/type merged)) "the forward merge is written")
        (on-chain
         (fn [connection]
           (let [after (db/db connection)
                 current (:seon.program/definition-digest (defined after own))]
             (is (= current (stored after)) "the branch's own declaration holds the current formula's digest")
             (is (not (:seon.error/at (db/with after [(defined after own)])))
                 "the writer admits a copy of the converted declaration")))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))
