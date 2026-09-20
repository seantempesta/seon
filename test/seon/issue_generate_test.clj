(ns seon.issue-generate-test
  (:require [clojure.test]
            [clojure.string]
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

(defn- written!
  "Transact and assert the report. A fixture that ignores its own transaction
  report reads a refusal as behaviour: the first version of these regressions
  seeded synthetic `{:seon.fn/sym …}` rows, and `seon.db/write-error`
  (`src/seon/db.clj:2777`) validates every map keyed by an identity attribute
  against that attribute's entity schema, so the whole seed was refused with
  `seon.db/transact! refused transaction data at [1 :seon.schema.admission/source]`
  — `resources/seon/schemas/seon.fn.edn:92` makes that key required. These
  regressions now use the canonical population's own declarations and change
  them with `:db/add`/`:db/retract` forms, which carry no entity map."
  [connection tx]
  (let [report (seon.db/transact! connection tx)]
    (clojure.test/is (let [observed report] (or (some? (:db-after observed)) (nat-int? (:seon.issue/count observed)) (string? (:seon.issue/id observed)))) (pr-str report))
    report))

(defn- some-subject
  "One real docstring-less public function from the canonical population."
  [connection]
  (let [subjects (seon.issue.detect/public-without-doc (seon.db/db connection))
        sym (first (sort (map :seon.fn/sym subjects)))]
    (clojure.test/is (string? sym)
                     "the canonical fixture holds at least one public function with no docstring")
    sym))

(defn- function-entity [connection sym]
  (:db/id (seon.db/pull (seon.db/db connection) [:db/id] [:seon.fn/sym sym])))

(clojure.test/deftest generate-is-idempotent-per-detector-and-subject
  (seon.test-support/with-database
   (fn [connection]
     (let [subject (some-subject connection)
           documented (first (sort (seon.db/q '[:find [?s ...] :where
                                                [?f :seon.fn/sym ?s] [?f :seon.fn/private? false]
                                                [?f :seon.fn/doc _]]
                                              (seon.db/db connection))))
           first-run (generate! connection)
           issue-id (subject-issue-id subject)
           row (issue-row connection issue-id)]
       (clojure.test/is (let [observed first-run] (or (some? (:db-after observed)) (nat-int? (:seon.issue/count observed)) (string? (:seon.issue/id observed)))) (pr-str first-run))
       (clojure.test/is (pos? (:seon.issue/count first-run)) (pr-str first-run))
       (clojure.test/is (= :open (:seon.issue/status row)) (pr-str row))
       (clojure.test/is (= detector (get-in row [:seon.issue/detector :seon.fn/sym])))
       (clojure.test/is (= #{subject} (set (map :seon.fn/sym (:seon.issue/functions row))))
                        "the subject is stored as a ref, not as text")
       (clojure.test/is (= #{(symbol (namespace (symbol subject)))}
                           (set (map :seon.ns/name (:seon.issue/namespaces row))))
                        "the responsible namespace is a ref the steward can query")
       (clojure.test/is (nil? (:seon.issue/status (issue-row connection (subject-issue-id documented))))
                        "a function that already carries a docstring is no subject")
       ;; A human's edit of the prose survives every later run.
       (written! connection [[:db/add (:db/id row) :seon.issue/problem "Edited by hand."]])
       (let [before (:max-tx (seon.db/db connection))
             second-run (generate! connection)
             again (issue-row connection issue-id)]
         (clojure.test/is (= 0 (:seon.issue/forms second-run))
                          (str "a second run of the same subjects writes nothing: " (pr-str second-run)))
         (clojure.test/is (= before (:max-tx (seon.db/db connection))))
         (clojure.test/is (= (:db/id row) (:db/id again))
                          "the identity is the detector plus the subject, so one entity")
         (clojure.test/is (= "Edited by hand." (:seon.issue/problem again)))
         (clojure.test/is (= (:seon.issue/count first-run) (:seon.issue/count second-run))))))))

(clojure.test/deftest generate-resolves-and-reopens-without-losing-identity
  (seon.test-support/with-database
   (fn [connection]
     (let [subject (some-subject connection)
           entity (function-entity connection subject)
           issue-id (subject-issue-id subject)]
       (generate! connection)
       (let [opened (issue-row connection issue-id)]
         (clojure.test/is (= :open (:seon.issue/status opened)) (pr-str opened))
         (clojure.test/is (nil? (:seon.issue/resolved-tx opened)) (pr-str opened))
         ;; The standard now holds: the subject leaves the detector's result.
         (written! connection [[:db/add entity :seon.fn/doc "It answers its own question."]])
         (generate! connection)
         (let [resolved (issue-row connection issue-id)]
           (clojure.test/is (= (:db/id opened) (:db/id resolved))
                            "resolution keeps the entity; nothing is deleted")
           (clojure.test/is (= :resolved (:seon.issue/status resolved)) (pr-str resolved))
           (clojure.test/is (some? (:seon.issue/resolved-tx resolved)) (pr-str resolved))
           (clojure.test/is (= (:seon.issue/problem opened) (:seon.issue/problem resolved))
                            "the prose is not rewritten by resolution"))
         (let [steady (generate! connection)]
           (clojure.test/is (= 0 (:seon.issue/forms steady))
                            (str "an already resolved subject re-resolves nothing: " (pr-str steady))))
         ;; The finding comes back.
         (written! connection [[:db/retract entity :seon.fn/doc]])
         (generate! connection)
         (let [reopened (issue-row connection issue-id)]
           (clojure.test/is (= (:db/id opened) (:db/id reopened)))
           (clojure.test/is (= :open (:seon.issue/status reopened)) (pr-str reopened))
           (clojure.test/is (nil? (:seon.issue/resolved-tx reopened)) (pr-str reopened))))))))

(clojure.test/deftest generate-refuses-a-bare-entity-id-subject
  (seon.test-support/with-database
   (fn [connection]
     (let [before (:max-tx (seon.db/db connection))
           result (seon.issue/generate!
                   {:seon.db/connection connection
                    :seon.issue/detector 'seon.issue-generate-test/bare-entity-subject
                    :seon.issue/severity :cleanup})]
       (clojure.test/is (= 0 (:seon.issue/subject-identity-count result))
                        (pr-str result))
       (clojure.test/is (string? (:seon.error/message result)))
       (clojure.test/is (= before (:max-tx (seon.db/db connection)))
                        "a refused detector writes nothing at all")
       (let [unknown (seon.issue/generate!
                      {:seon.db/connection connection
                       :seon.issue/detector 'seon.issue.detect/absent-detector
                       :seon.issue/severity :cleanup})]
         (clojure.test/is (= 'seon.issue.detect/absent-detector (:seon.issue/missing-detector unknown))
                          (pr-str unknown)))))))

(clojure.test/deftest a-generated-issue-names-its-detector-and-promises-no-tests
  (seon.test-support/with-database
   (fn [connection]
     (let [subject (some-subject connection)]
       (generate! connection)
       (let [database (seon.db/db connection)
             view (seon.issue/status {:seon.db/db database
                                      :seon.issue/id (subject-issue-id subject)})
             unit {:seon.db/db database
                   :seon.render/value (issue-row connection (subject-issue-id subject))}
             text (seon.issue/render-ai unit)]
         (clojure.test/is (empty? (:seon.issue/tests view)) (pr-str view))
         (clojure.test/is (= (list (symbol detector) '(seon.db/db))
                             (:seon.issue/check-form view))
                          "the form that decides done re-evaluates the detector, never an empty check")
         (clojure.test/is (not (clojure.string/includes? text "my.test/check"))
                          (str "a detector issue must not promise tests it does not have: " text))
         (clojure.test/is (clojure.string/includes? text detector) text)
         (clojure.test/is (clojure.string/includes?
                           (pr-str (seon.issue/render-html unit))
                           detector)
                          "the block names the detector too"))))))

(clojure.test/deftest the-docstring-standard-scopes-on-the-source-root-fact
  ;; The unscoped detector stays the honest over-report. Given a root it joins
  ;; positively on the fact the indexer wrote, so a test helper and a
  ;; production function are told apart by the query, never by their names.
  (seon.test-support/with-database
    (fn [connection]
      (let [database (seon.db/db connection)
            syms (fn [request] (into #{} (map :seon.fn/sym)
                                     (seon.issue.detect/public-without-doc database request)))
            unscoped (into #{} (map :seon.fn/sym)
                           (seon.issue.detect/public-without-doc database))
            production (syms {:seon.fn.file/relative-root "src"})
            helpers (syms {:seon.fn.file/relative-root "test"})
            root-of (fn [sym]
                      (seon.db/q '[:find [?root ...] :in $ ?sym :where
                                   [?f :seon.fn/sym ?sym] [?f :seon.fn/file ?file]
                                   [?file :seon.fn.file/relative-root ?root]]
                                 database sym))]
        (clojure.test/is (seq production) "the fixture holds an undocumented production function")
        (clojure.test/is (seq helpers) "the fixture holds an undocumented test helper")
        (clojure.test/is (empty? (filter helpers production)))
        (clojure.test/is (every? unscoped (concat production helpers))
                         "a scoped subject is always an unscoped subject")
        (clojure.test/is (every? #(= ["src"] (root-of %)) production))
        (clojure.test/is (every? #(= ["test"] (root-of %)) helpers))))))
