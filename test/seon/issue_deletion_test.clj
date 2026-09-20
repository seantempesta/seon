(ns seon.issue-deletion-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.issue :as issue]
            [seon.test-support :as support]))

(deftest removed-notes-retract-entities-and-components-through-both-writers
  (doseq [writer [:index :adopt]]
    (testing (name writer)
      (support/with-database
       (fn [connection]
         (let [note (fn [slug]
                      {:seon.issue/path (str "docs/seon/issues/" slug ".md")
                       :seon.issue/text
                       (str "---\ntype: issue\nstatus: open\nseverity: cleanup\n"
                            "tags: [issue]\n---\n# " slug
                            "\nThe cited file remains: src/seon/issue.clj:265.")})
               kept (note "deletion-kept")
               gone (note "deletion-gone")
               lookup [:seon.issue/id "deletion-gone"]
               _ (support/transacted! connection
                                     [[:db.fn/call issue/index-tx [kept gone]]])
               before (db/db connection)
               row (db/pull before '[*] lookup)
               components (mapv :db/id (:seon.issue/files row))
               file-ids (mapv #(get-in % [:seon.issue.citation/file :db/id])
                              (:seon.issue/files row))
               rows (filterv #(= "deletion-kept" (:seon.issue/id %))
                             (#'issue/identity-rows before))
               operation (case writer
                           :index [:db.fn/call issue/index-tx [kept]]
                           :adopt [:db.fn/call issue/adopt-tx rows])
               report (support/transacted! connection [operation])
               after (:db-after report)]
           (is (integer? (:db/id row)) "The deleted subject must have existed.")
           (is (seq components) "The component cascade must have a subject.")
           (is (every? integer? file-ids) "The cited file must resolve before deletion.")
           (is (nil? (db/pull after '[*] lookup)))
           (is (empty? (db/datoms after :eavt (:db/id row))))
           (doseq [component components]
             (is (empty? (db/datoms after :eavt component))))
           (doseq [file-id file-ids]
             (is (seq (db/datoms after :eavt file-id))
                 "A citation's noncomponent file ref does not own the file."))
           (is (= row (db/pull (db/as-of after (db/basis-t before)) '[*] lookup)))
           (is (= "deletion-kept"
                  (:seon.issue/id (db/pull after [:seon.issue/id]
                                          [:seon.issue/id "deletion-kept"]))))
           (is (empty? (remove #(= :db/txInstant (:a %))
                              (:tx-data (support/transacted! connection [operation]))))
               "Repeating deletion is a no-op, without a retained identity.")))))))

(deftest removed-started-note-refuses-atomically-through-both-writers
  (doseq [writer [issue/index-tx issue/adopt-tx]]
    (support/with-database
     (fn [connection]
       (support/seed-cluster! connection "issue-deletion")
       (support/transacted!
        connection
        (agent/creation-tx {:seon.agent/id "deletion-creator"
                            :seon.ns/name 'my.agents.deletion-creator
                            :seon.cluster/name "issue-deletion"}))
       (support/transacted!
        connection
        [[:db.fn/call issue/index-tx
          [{:seon.issue/path "docs/seon/issues/deletion-protected.md"
            :seon.issue/text
            (str "---\ntype: issue\nstatus: open\nseverity: cleanup\n"
                 "tags: [issue]\n---\n# Retain assigned tests\n"
                 "seon.issue-test/issue-worker-creation-is-atomic src/seon/issue.clj:265")}
           {:seon.issue/path "docs/seon/issues/deletion-sibling.md"
            :seon.issue/text
            "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue]\n---\n# Sibling\nKeep on refused deletion."}]]])
       (let [lookup [:seon.issue/id "deletion-protected"]
             creator [:seon.agent/id "deletion-creator"]]
         (support/transacted! connection
                              [[:db/add lookup :seon.issue/created-by creator]
                               [:db/add lookup :seon.issue/agent creator]])
         (let [before (db/db connection)
               row (db/pull before '[*] lookup)
               sibling (db/pull before '[*] [:seon.issue/id "deletion-sibling"])
               result (db/transact! connection
                                    {:tx-data [[:db.fn/call writer []]]
                                     :tx-meta {:seon.db/user creator}})
               after (db/db connection)]
           (is (seq (:seon.issue/tests row)) "Retention must protect a real test ref.")
           (is (seq (:seon.issue/files row)) "Refusal must retain citation components.")
           (is (string? (:seon.db.write.attempt/request-id result)) (pr-str result))
           (is (= (db/basis-t before) (db/basis-t after)))
           (is (= row (db/pull after '[*] lookup)))
           (is (= sibling (db/pull after '[*] [:seon.issue/id "deletion-sibling"])))))))))
