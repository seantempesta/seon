(ns seon.issue-test
  (:require [clojure.test]
            [clojure.core.async]
            [clojure.java.io]
            [clojure.string]
            [seon.cluster]
            [seon.cluster.agent]
            [seon.db]
            [seon.eval]
            [seon.flow]
            [seon.id]
            [seon.issue]
            [seon.turn]
            [seon.test-support]))

(clojure.test/deftest indexed-issues-replace-facts-and-retain-identities
 (seon.test-support/with-database
  (fn [connection]
   (let [wanted #{"class-classification-is-inferred-from-hand-lists.md"
                   "agent-form-calls-to-core-namespaces-are-not-indexed.md"
                   "a-search-contract-predicate-cannot-be-made-durable.md"}
         selected-real (filterv #(contains? wanted (.getName (clojure.java.io/file (:seon.issue/path %))))
                                (seon.issue/notes "."))
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected-real})
         d (seon.db/db connection)
         member (seon.db/pull d '[:seon.issue/commits {:seon.issue/tests [:seon.test/sym]}]
                             [:seon.issue/id "agent-form-calls-to-core-namespaces-are-not-indexed"])
         search-value (seon.db/pull d '[{:seon.issue/tests [:seon.test/sym]}] [:seon.issue/id "a-search-contract-predicate-cannot-be-made-durable"])
         class-value (seon.db/pull d '[{:seon.issue/members [:seon.issue/id]}]
                                  [:seon.issue/id "class-classification-is-inferred-from-hand-lists"])]
     (clojure.test/is (= 3 (count selected-real)))
     (clojure.test/is (= 3 (:seon.issue/count report)) (pr-str report))
     (clojure.test/is (some #(= "seon.search-test/index-step-contract-has-durable-generative-host-predicates"
                               (:seon.test/sym %)) (:seon.issue/tests search-value)))
     (clojure.test/is (some #{"5deb40e4e"} (:seon.issue/commits member)))
     (clojure.test/is (some #(= "agent-form-calls-to-core-namespaces-are-not-indexed" (:seon.issue/id %))
                           (:seon.issue/members class-value)))
     (clojure.test/is (some #(= 'my.run/complete (:seon.issue/value %)) (:seon.issue/refusals report))))
   (let [real (first (filter #(clojure.string/includes? (:seon.issue/text %) "seon.fn")
                             (seon.issue/notes ".")))
         selected [{:seon.issue/path "docs/seon/issues/probe-class.md"
                    :seon.issue/text "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue, class/n7, class-kill]\n---\n# Class\n## Problem\nFind facts."}
                   {:seon.issue/path "docs/seon/issues/probe-member.md"
                    :seon.issue/text "---\ntype: issue\nstatus: open\nseverity: friction\ntags: [issue, class/n7]\n---\n# Member\n## Problem\nseon.db/pull seon.issue-missing/absent abcdef123"}
                   real]
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})]
     (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))
     (clojure.test/is (= 3 (:seon.issue/count report)))
     (clojure.test/is (some #(= 'seon.issue-missing/absent (:seon.issue/value %)) (:seon.issue/refusals report)))
     (let [member (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"])
           class-row (seon.db/pull (seon.db/db connection) '[{:seon.issue/members [:seon.issue/id]}] [:seon.issue/id "probe-class"])]
       (clojure.test/is (= #{"abcdef123"} (set (:seon.issue/commits member))))
       (clojure.test/is (seq (:seon.issue/functions member)))
       (clojure.test/is (some #(= "probe-member" (:seon.issue/id %)) (:seon.issue/members class-row)))
       (clojure.test/is (clojure.string/includes? (seon.issue/render-ai member) "(my.issue/status"))
       (clojure.test/is (= :section (first (seon.issue/render-html member))))
       (clojure.test/is (= "probe-member" (:seon.issue/id (seon.issue/status {:seon.db/db (seon.db/db connection) :seon.issue/id "probe-member"}))))
       (seon.issue/index! {:seon.db/connection connection
                          :seon.issue/notes (mapv #(update % :seon.issue/text clojure.string/replace "status: open" "status: resolved") selected)})
       (clojure.test/is (= :resolved (:seon.issue/status (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"]))))
       (seon.issue/index! {:seon.db/connection connection :seon.issue/notes []})
       (clojure.test/is (= {:db/id (:db/id member) :seon.issue/id "probe-member"}
                           (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"])))
       (clojure.test/is (empty? (seon.issue/issues {:seon.db/db (seon.db/db connection)}))))))))

