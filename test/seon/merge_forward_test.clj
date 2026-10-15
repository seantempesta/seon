(ns seon.merge-forward-test
  (:require [clojure.test :refer [deftest is]]
            [seon.merge :as merge]
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
                       :seon.task/plan "Merge main's new file forward into `seon.merge/merge-main!`'s chain."}])
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

(deftest a-forward-merge-moves-the-branch-onto-mains-adoption-record
  ;; git merge main moves what the branch is built on: after main adopts a newer program, the chain branch's
  ;; adoption record names main's, so its test world's overlay (`seon.test/program-overlay-tx`) compares the branch
  ;; with the program it now holds, not its fork point's (a function main deleted since would be retracted from a
  ;; world that never held it)
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main-conn (support/agent-tx (db/db main-conn) "root")))
        {chain :seon.chain/branch}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "record-" suffix)
                       :seon.task/plan "Move the chain onto main's adoption record in `seon.merge/merge-main!`."}])
        record (fn [database] (into {} (db/q '[:find ?n ?c :where [?e :seon.cluster/name ?n] [?e :seon.source/commit-id ?c]]
                                            database)))
        [cluster-name] (first (record (db/db main-conn)))
        on-chain (fn [f] (registry/on-branch store chain f))]
    (try
      (support/transacted! main-conn [[:db/add [:seon.cluster/name cluster-name] :seon.source/commit-id
                                       (db/commit-id (db/db main-conn))]])
      (let [adopted (record (db/db main-conn))
            merged (merge/merge-main! target chain)]
        (is (nil? (:seon.error/type merged)) "the forward merge is accepted")
        (is (= adopted (record (on-chain db/db))) "the branch is built on main's adoption record"))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))
