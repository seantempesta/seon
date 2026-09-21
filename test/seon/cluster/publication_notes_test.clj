(ns seon.cluster.publication-notes-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.issue :as issue]
            [seon.test-support :as support]))

(deftest source-only-publication-does-not-read-issue-notes
  (support/with-database
   (fn [connection]
     (is (nil? (#'source/index-issues! connection (apply str (repeat 64 "a"))
                                      "tmp/absent-publication-notes" []))))))

(deftest changed-note-replaces-only-itself-and-affected-class-membership
  (support/with-database
   (fn [connection]
     (let [note (fn [slug tags title]
                  {:seon.issue/path (str "docs/seon/issues/" slug ".md")
                   :seon.issue/text
                   (str "---\ntype: issue\nstatus: open\nseverity: cleanup\n"
                        "created: 2026-09-23\ntags: [" tags "]\n---\n# " title
                        "\n## Problem\nAn authored note.")})
           first-note (note "publication-note-first" "class/publication-note" "First")
           other-note (note "publication-note-other" "class/publication-note" "Other")
           class-note (note "publication-note-class" "class/publication-note, class-kill" "Class")
           notes [first-note other-note class-note]
           paths (mapv :seon.issue/path notes)
           initial (issue/index! {:seon.db/connection connection :seon.issue/notes notes
                                  :seon.source/changed-paths paths})
           _ (is (empty? (:seon.issue/refusals initial)) (pr-str initial))
           before (db/db connection)
           other-before (db/pull before '[*] [:seon.issue/id "publication-note-other"])
           changed [(note "publication-note-first" "issue" "First changed")
                    (note "publication-note-other" "class/publication-note" "Unselected edit")
                    class-note]
           result (issue/index! {:seon.db/connection connection :seon.issue/notes changed
                                 :seon.source/changed-paths [(:seon.issue/path first-note)]})
           after (db/db connection)
           report (:seon.db/transaction-report result)
           identities (into #{} (keep (fn [datom]
                                        (:seon.issue/id (db/pull after [:seon.issue/id] (:e datom)))))
                            (:tx-data report))]
       (is report (pr-str result))
       (is (= #{"publication-note-first" "publication-note-class"} identities))
       (is (= other-before (db/pull after '[*] [:seon.issue/id "publication-note-other"])))
       (is (= #{"publication-note-other"}
              (into #{} (map :seon.issue/id)
                    (:seon.issue/members
                     (db/pull after '[{:seon.issue/members [:seon.issue/id]}]
                              [:seon.issue/id "publication-note-class"])))))
       (let [unchanged (issue/index! {:seon.db/connection connection :seon.issue/notes changed
                                      :seon.source/changed-paths [(:seon.issue/path first-note)]})]
         (is (nil? (:seon.db/transaction-report unchanged)))
         (is (= (db/basis-t after) (db/basis-t (db/db connection)))))
       (issue/index! {:seon.db/connection connection :seon.issue/notes [other-note class-note]
                       :seon.source/changed-paths [(:seon.issue/path first-note)]})
       (is (nil? (db/pull (db/db connection) [:seon.issue/id]
                         [:seon.issue/id "publication-note-first"])))))))
