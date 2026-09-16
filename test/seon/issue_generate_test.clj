(ns seon.issue-generate-test
  (:require [clojure.test]
            [seon.db]
            [seon.id]
            [seon.issue]
            [seon.issue.detect]
            [seon.test-support]))

(def ^:private detector "seon.issue.detect/public-without-doc")

(defn bare-entity-subject
  "Probe detector naming its subject by an entity id, which a refork changes."
  [_database]
  [{:db/id 12345}])

(defn- seed!
  "Program rows the detectors read: the detector itself and two probe functions."
  [connection]
  (seon.db/transact!
   connection
   [{:seon.ns/name 'example.probe}
    {:seon.fn/sym detector}
    {:seon.fn/sym "seon.issue-generate-test/bare-entity-subject"}
    {:seon.fn/sym "example.probe/alpha" :seon.fn/private? false
     :seon.fn/source "(defn alpha [] 1)" :seon.fn/ns [:seon.ns/name 'example.probe]}
    {:seon.fn/sym "example.probe/beta" :seon.fn/private? false
     :seon.fn/source "(defn beta [] 2)" :seon.fn/ns [:seon.ns/name 'example.probe]
     :seon.fn/doc "Beta answers two."}]))

(defn- subject-issue-id [sym]
  (seon.id/id (into (sorted-map)
                    {:seon.issue/detector (symbol detector) :seon.fn/sym sym})))

(defn- generate! [connection]
  (seon.issue/generate! {:seon.db/connection connection
                         :seon.issue/detector detector
                         :seon.issue/severity :cleanup}))

(defn- issue-row [connection issue-id]
  (seon.db/pull (seon.db/db connection)
                '[:db/id :seon.issue/id :seon.issue/status :seon.issue/problem :seon.issue/title
                  :seon.issue/severity :seon.issue/resolved-tx
                  {:seon.issue/detector [:seon.fn/sym]}
                  {:seon.issue/functions [:seon.fn/sym]}
                  {:seon.issue/namespaces [:seon.ns/name]}]
                [:seon.issue/id issue-id]))

(clojure.test/deftest generate-is-idempotent-per-detector-and-subject
  (seon.test-support/with-database
   (fn [connection]
     (seed! connection)
     (let [first-run (generate! connection)
           alpha-id (subject-issue-id "example.probe/alpha")
           alpha (issue-row connection alpha-id)]
       (clojure.test/is (nil? (:seon.error/kind first-run)) (pr-str first-run))
       (clojure.test/is (= :open (:seon.issue/status alpha)) (pr-str alpha))
       (clojure.test/is (= detector (get-in alpha [:seon.issue/detector :seon.fn/sym])))
       (clojure.test/is (= #{"example.probe/alpha"}
                           (set (map :seon.fn/sym (:seon.issue/functions alpha))))
                        "the subject is stored as a ref, not as text")
       (clojure.test/is (= #{'example.probe}
                           (set (map :seon.ns/name (:seon.issue/namespaces alpha))))
                        "the responsible namespace is a ref the steward can query")
       (clojure.test/is (nil? (issue-row connection (subject-issue-id "example.probe/beta")))
                        "a function that already carries a docstring is no subject")
       ;; A human's edit of the prose survives every later run.
       (seon.db/transact! connection [{:db/id (:db/id alpha)
                                       :seon.issue/problem "Edited by hand."}])
       (let [before (:max-tx (seon.db/db connection))
             second-run (generate! connection)
             again (issue-row connection alpha-id)]
         (clojure.test/is (= 0 (:seon.issue/forms second-run))
                          (str "a second run of the same subjects writes nothing: "
                               (pr-str second-run)))
         (clojure.test/is (= before (:max-tx (seon.db/db connection))))
         (clojure.test/is (= (:db/id alpha) (:db/id again))
                          "the identity is the detector plus the subject, so one entity")
         (clojure.test/is (= "Edited by hand." (:seon.issue/problem again)))
         (clojure.test/is (= (:seon.issue/count first-run) (:seon.issue/count second-run))))))))

(clojure.test/deftest generate-resolves-and-reopens-without-losing-identity
  (seon.test-support/with-database
   (fn [connection]
     (seed! connection)
     (generate! connection)
     (let [alpha-id (subject-issue-id "example.probe/alpha")
           opened (issue-row connection alpha-id)]
       (clojure.test/is (nil? (:seon.issue/resolved-tx opened)) (pr-str opened))
       ;; The standard now holds: the subject leaves the detector's result.
       (seon.db/transact! connection [{:seon.fn/sym "example.probe/alpha"
                                       :seon.fn/doc "Alpha answers one."}])
       (generate! connection)
       (let [resolved (issue-row connection alpha-id)]
         (clojure.test/is (= (:db/id opened) (:db/id resolved))
                          "resolution keeps the entity; nothing is deleted")
         (clojure.test/is (= :resolved (:seon.issue/status resolved)))
         (clojure.test/is (some? (:seon.issue/resolved-tx resolved)) (pr-str resolved))
         (clojure.test/is (= (:seon.issue/problem opened) (:seon.issue/problem resolved))
                          "the prose is not rewritten by resolution"))
       (let [steady (generate! connection)]
         (clojure.test/is (= 0 (:seon.issue/forms steady))
                          (str "an already resolved subject re-resolves nothing: " (pr-str steady))))
       ;; The finding comes back.
       (seon.db/transact! connection [[:db/retract
                                       (:db/id (seon.db/pull (seon.db/db connection) [:db/id]
                                                             [:seon.fn/sym "example.probe/alpha"]))
                                       :seon.fn/doc]])
       (generate! connection)
       (let [reopened (issue-row connection alpha-id)]
         (clojure.test/is (= (:db/id opened) (:db/id reopened)))
         (clojure.test/is (= :open (:seon.issue/status reopened)))
         (clojure.test/is (nil? (:seon.issue/resolved-tx reopened)) (pr-str reopened)))))))

(clojure.test/deftest generate-refuses-a-bare-entity-id-subject
  (seon.test-support/with-database
   (fn [connection]
     (seed! connection)
     (let [before (:max-tx (seon.db/db connection))
           result (seon.issue/generate!
                   {:seon.db/connection connection
                    :seon.issue/detector "seon.issue-generate-test/bare-entity-subject"
                    :seon.issue/severity :cleanup})]
       (clojure.test/is (= :seon.issue/subject-without-identity (:seon.error/kind result))
                        (pr-str result))
       (clojure.test/is (string? (:seon.error/message result)))
       (clojure.test/is (= before (:max-tx (seon.db/db connection)))
                        "a refused detector writes nothing at all")
       (let [unknown (seon.issue/generate!
                      {:seon.db/connection connection
                       :seon.issue/detector "seon.issue.detect/absent-detector"
                       :seon.issue/severity :cleanup})]
         (clojure.test/is (= :seon.issue/detector-unknown (:seon.error/kind unknown))
                          (pr-str unknown)))))))
