(ns seon.test-failure-facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.blob :as blob]
            [seon.id :as id]
            [seon.problems :as problems]
            [seon.render.test :as render]
            [seon.db :as db]
            [seon.test :as sut]
            [seon.program :as program]
            [seon.cluster.source]
            [seon.schema.datahike]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn- with-probe [connection check]
  (let [namespace-name (symbol (str "failure.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (str namespace-name "/probe")
        mode (atom :red)
        test-var (intern namespace-object 'probe)
        path (get-in (db/pull (db/db connection)
                       '[{:seon.fn/file [:seon.fn.file/path]}]
                       [:seon.test/sym "seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced"])
                     [:seon.fn/file :seon.fn.file/path])]
    (alter-meta! test-var assoc :test
      (fn []
        (case @mode
          :green (is true)
          :blob (is (= :small (apply str (repeat 5000 "é"))) "large actual")
          :red (testing "outer" (testing "inner"
                 (is (= 1 2) "first claim")
                 (is (= :a :b) "second claim"))))))
    (try
      (let [tx (db/transact! connection
                 [{:seon.ns/name namespace-name}
                  {:seon.test/sym test-symbol :seon.test/ns [:seon.ns/name namespace-name]
                   :seon.schema.admission/source :core
                   :seon.fn/file [:seon.fn.file/path path]
                   :seon.test/source "(deftest probe (is (= 1 2)) (is (= :a :b)))"}])]
        (is (:db-after tx) (pr-str tx))
        (when (:seon.error/kind tx)
          (throw (ex-info "The failure fixture transaction was refused." tx))))
      (check test-symbol test-var mode)
      (finally (remove-ns namespace-name)))))

(defn- run-probe [connection test-var]
  (let [database (db/db connection)]
    (sut/run test-var connection {:seon.db/db database
                                 :seon.test.run/provenance (runner/provenance database)
                                 :seon.test/remaining-ms 100000})))

(deftest one-failing-is-becomes-one-failure-entity
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v _]
          (let [result (run-probe connection v)
                failures (:seon.test/failures result)]
            (is (= 2 (:seon.test/fail-count result)) (pr-str result))
            (is (= 2 (count failures)))
            (is (= #{"first claim" "second claim"} (set (map :seon.test.failure/message failures))))
            (doseq [failure failures]
              (is (= :fail (:seon.test.failure/type failure)))
              (is (string? (:seon.test.failure/expected failure)))
              (is (string? (:seon.test.failure/actual failure)))
              (is (pos-int? (:seon.test.failure/line failure)))
              (is (int? (get-in failure [:seon.test.failure/file :db/id])))
              (is (= [[0 "outer"] [1 "inner"]] (sort-by first (:seon.test.failure/contexts failure)))))
            (is (= 2 (count (db/q '[:find ?f :in $ ?s
                                    :where [?t :seon.test/sym ?s]
                                           [?t :seon.test/failures ?f]
                                           [?f :seon.test.failure/file ?file]
                                           [?file :seon.fn.file/path]] (db/db connection) s))))))))))

(deftest a-repeated-failure-upserts-its-entity
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [_ v _]
          (let [before (:seon.test/failures (run-probe connection v))
                after (:seon.test/failures (run-probe connection v))]
            (is (= 2 (count before) (count after)))
            (is (= (set (map :seon.test.failure/id before)) (set (map :seon.test.failure/id after))))
            (is (= #{2} (set (map :seon.test.failure/seen-count after)))))
          (let [again (:seon.test/failures (run-probe connection v))]
            (is (= #{3} (set (map :seon.test.failure/seen-count again))))
            (is (every? #(not= (:seon.test.failure/first-run %) (:seon.test.failure/last-run %)) again))))))))

(deftest a-green-run-retracts-its-failures
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v mode]
          (let [red (run-probe connection v) ids (mapv :seon.test.failure/id (:seon.test/failures red))]
            (is (= 2 (count ids)))
            (reset! mode :green)
            (let [green (run-probe connection v)]
              (is (= 1 (:seon.test/pass-count green)))
              (is (nil? (:seon.test/failures green)))
              (is (nil? (:seon.test/failing-assertions green)))
              (is (nil? (:seon.test/failure-message green)))
              (is (empty? (db/q '[:find ?e :in $ [?id ...]
                                  :where [?e :seon.test.failure/id ?id]] (db/db connection) ids)))
              (is (nil? (:seon.test/failures (db/pull (db/db connection)
                                             [:seon.test/failures] [:seon.test/sym s])))))))))))

(deftest an-oversized-actual-settles-into-a-blob
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (with-probe connection
        (fn [_ v mode]
          (reset! mode :blob)
          (let [failure (first (:seon.test/failures (run-probe connection v)))
                digest (:seon.test.failure/actual-blob failure)
                content (when digest (blob/get connection digest))]
            (is (string? digest))
            (is (nil? (:seon.test.failure/actual failure)))
            (is (> (:seon.test.failure/actual-size failure 0) 10000))
            (is (str/includes? (or content "") (apply str (repeat 5000 "é"))))
            (when digest
              (is (= content (sci/eval-string* (support/fork-cluster-ctx connection)
                                (pr-str (list 'seon.blob/get digest))))
                  "the rendered blob read uses real SCI connection preparation"))))))))

(deftest an-interpreted-test-failure-records-no-false-site
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s _ _]
          (let [ctx (support/fork-cluster-ctx connection)
                ns-name (symbol (namespace (symbol s)))
                source (str "(ns " ns-name " (:require [clojure.test :refer [deftest is]])) "
                            "(deftest probe (is (= 1 2))) #'probe")
                v (sci/eval-string* ctx source)
                result (run-probe connection v)
                failure (first (:seon.test/failures result))]
            (is (= 1 (:seon.test/fail-count result)) (pr-str result))
            (is (nil? (:seon.test.failure/file failure)))
            (is (nil? (:seon.test.failure/line failure)))
            (is (= (id/id [s [:fail nil (:seon.test.failure/expected failure)
                              (:seon.test.failure/actual failure) nil] 0])
                   (:seon.test.failure/id failure)))))))))

(deftest failures-render-their-site-and-claim
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [_ v _]
          (let [result (run-probe connection v)
                unit {:seon.db/db (db/db connection) :seon.render/value result}
                ai (render/render-ai unit)
                html (render/render-html unit)
                nodes (tree-seq coll? seq html)
                links (filter #(and (vector? %) (= :a (first %))) nodes)]
            (is (str/includes? ai "expected:"))
            (is (str/includes? ai "actual:"))
            (is (str/includes? ai "test/seon/test_failure_facts_test.clj:"))
            (is (every? #(not (str/includes? ai %)) (:seon.test/failing-assertions result)))
            (is (some #(str/ends-with? (get (second %) :data-file "")
                                       "test/seon/test_failure_facts_test.clj") links))))))))

(deftest a-recorded-test-renders-its-sites-and-changed-dependencies
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v mode]
          (reset! mode :green)
          (is (= 1 (:seon.test/pass-count (run-probe connection v))))
          (reset! mode :red)
          (let [red (run-probe connection v)
                namespace-name (symbol (namespace (symbol s)))
                reached (str namespace-name "/reached")
                tx (db/transact! connection
                     [{:seon.fn/sym reached :seon.schema.admission/source :core
                       :seon.fn/ns [:seon.ns/name namespace-name]
                       :seon.fn/source "(defn reached [] :changed)"}
                      {:seon.test/sym s :seon.schema.admission/source :core
                       :seon.test/reach [[:seon.fn/sym reached]]}])
                database (db/db connection)
                changed (sut/changed-since-green database s)
                unit {:seon.db/db database
                      :seon.render/value (db/pull database '[*] [:seon.test/sym s])}
                [summary _evidence changed-form]
                (rest (read-string (str "(do\n" (render/render-ai unit) "\n)")))
                sites (set (map (juxt #(get-in % [:seon.test.failure/file :seon.fn.file/path])
                                      :seon.test.failure/line)
                                (:seon.test/failures red)))
                links (filter #(and (vector? %) (= :a (first %)) (map? (second %)))
                              (tree-seq coll? seq (render/render-html unit)))]
            (is (:db-after tx) (pr-str tx))
            (is (= 2 (:seon.test/fail-count red)) (pr-str red))
            (is (= 2 (count sites)) (pr-str sites))
            (is (= [reached] (mapv :seon.fn/sym changed)) (pr-str changed))
            (is (= (str "Test " s ": fail") (first (str/split-lines (second summary))))
                (pr-str summary))
            (is (every? (fn [[path line]] (str/includes? (second summary) (str path ":" line))) sites)
                (pr-str summary))
            (is (= (list 'seon.test/changed-since-green (list 'seon.db/db) s) changed-form)
                (pr-str changed-form))
            (is (= sites (set (keep (fn [[_ attributes]]
                                      (when-let [path (:data-file attributes)]
                                        [path (:data-line attributes)]))
                                    links)))
                (pr-str links))
            (is (contains? (set (mapcat #(drop 2 %) links)) reached) (pr-str links))))))))

(deftest failure-readers-use-the-structured-claims
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v _]
          (let [result (run-probe connection v)
                entry (first (filter #(= s (:seon.test/sym %))
                                     (:seon.problems/failed-tests (problems/problems (db/db connection) {}))))
                found {:seon.problems/failed-tests [entry]}]
            (is (= (:seon.test/failures result) (:seon.test/failures entry)))
            (is (str/includes? (problems/ai-prose found) "first claim"))
            (is (str/includes? (problems/log-report found) "expected:"))
            (is (str/includes? (sut/failure-message (dissoc result :seon.test/failure-message)) "second claim"))
            (is (str/includes? (pr-str (problems/html-report found)) "data-file"))))))))

(defn- completion [database test-symbol failures]
  (let [run (runner/provenance database)]
    {:seon.db/db database
     :seon.test.run/provenance run
     :seon.test/run-basis-t (:seon.test.run/basis-t run)
     :seon.test/run-at (:seon.test.run/at run)
     :seon.test.runner/results
     [{:seon.test/sym test-symbol :seon.test/pass-count (if (zero? failures) 1 0)
       :seon.test/fail-count failures :seon.test/error-count 0}]}))

(defn- transact! [connection rows]
  (db/transact! connection
    (mapv (fn [row]
            (cond-> row
              (and (map? row) (:seon.fn/sym row))
              (assoc :seon.fn/ns [:seon.ns/name 'seon.id] :seon.schema.admission/source :core)
              (and (map? row) (:seon.test/sym row))
              (assoc :seon.schema.admission/source :core))) rows)))

(deftest publication-replaces-cardinality-many-tuples
  (support/with-database
    (fn [connection]
      (let [s "reach.facts/tuple-owner"]
        (is (:db-after (transact! connection
                        [{:seon.fn/sym s :seon.fn/call-arities
                          #{["clojure.core/inc" 1] ["clojure.core/+" 2] ["clojure.core/str" 1]}}])))
        (let [current (db/pull (db/db connection) '[*] [:seon.fn/sym s])]
          (is (:db-after (db/transact! connection
                          (program/exact-replacement-tx current
                            (assoc (dissoc current :db/id) :seon.fn/call-arities #{["clojure.core/dec" 1]})))))
          (is (= #{["clojure.core/dec" 1]}
                 (set (:seon.fn/call-arities
                        (db/pull (db/db connection) [:seon.fn/call-arities] [:seon.fn/sym s]))))))))))

(deftest recorded-reach-belongs-to-the-tested-value-and-is-replaced
  (support/with-database
    (fn [connection]
      (let [s "reach.facts/check"
            a "reach.facts/a" b "reach.facts/b"]
        (is (:db-after (transact! connection
                        [{:db/id "a" :seon.fn/sym a}
                         {:db/id "b" :seon.fn/sym b}
                         {:seon.test/sym s :seon.fn/calls ["a"]}])))
        (let [tested (db/db connection)
              captured (completion tested s 1)]
          (is (:db-after (db/transact! connection
                          [[:db.fn/retractAttribute [:seon.test/sym s] :seon.fn/calls]
                           [:db/add [:seon.test/sym s] :seon.fn/calls [:seon.fn/sym b]]])))
          (is (vector? (runner/commit-results! connection captured)))
          (is (= #{a} (set (db/q '[:find [?s ...] :in $ ?test
                                  :where [?t :seon.test/sym ?test]
                                         [?t :seon.test/reach ?f]
                                         [?f :seon.fn/sym ?s]] (db/db connection) s))))
          (is (= (get (runner/reach-digests tested [s]) s)
                 (:seon.test/reach-digest (db/pull (db/db connection)
                                           [:seon.test/reach-digest] [:seon.test/sym s]))))
          (is (vector? (runner/commit-results! connection (completion (db/db connection) s 0))))
          (is (= #{b} (set (db/q '[:find [?s ...] :in $ ?test
                                  :where [?t :seon.test/sym ?test]
                                         [?t :seon.test/reach ?f]
                                         [?f :seon.fn/sym ?s]] (db/db connection) s)))))))))

(deftest what-made-it-red-names-changed-functions
  (support/with-database
    (fn [connection]
      (let [s "changed.facts/check" a "changed.facts/a" b "changed.facts/b"]
        (is (:db-after (transact! connection
                        [{:db/id "a" :seon.fn/sym a :seon.fn/source "old"
                          :seon.fn/spec "[:=> [:cat] :int]"}
                         {:db/id "b" :seon.fn/sym b :seon.fn/source "old"}
                         {:seon.test/sym s :seon.fn/calls ["a"]}])))
        (runner/commit-results! connection (completion (db/db connection) s 1))
        (is (= :seon.test/unknown (:seon.error/kind (sut/changed-since-green (db/db connection) s))))
        (runner/commit-results! connection (completion (db/db connection) s 0))
        (db/transact! connection [[:db/add [:seon.fn/sym a] :seon.fn/source "intermediate"]])
        (runner/commit-results! connection (completion (db/db connection) s 0))
        (is (= [] (sut/changed-since-green (db/db connection) s))
            "the later green is recognized even when its zero counts are unchanged")
        (db/transact! connection [[:db.fn/retractAttribute [:seon.fn/sym a] :seon.fn/spec]
                                 [:db/add [:seon.fn/sym b] :seon.fn/source "unrelated"]])
        (runner/commit-results! connection (completion (db/db connection) s 1))
        (is (= [a] (mapv :seon.fn/sym (sut/changed-since-green (db/db connection) s)))
            "spec retraction counts; a changed function outside the tested closure does not")))))

(deftest recorded-run-names-its-addressable-destination
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            captured (assoc-in (completion database "branch.facts/check" 0)
                              [:seon.test.run/provenance :seon.test.run/branch]
                              :building-source-retired)
            _ (is (vector? (runner/commit-results! connection captured)))
            row (db/pull (db/db connection)
                        '[{:seon.test/run [*]}] [:seon.test/sym "branch.facts/check"])
            run (:seon.test/run row)]
        (is (= (get-in database [:config :branch]) (:seon.test.run/branch run)))
        (is (= :building-source-retired (:seon.test.run/tested-branch run)))
        (is (:db/id (db/pull (db/db connection) [:db/id]
                            [:seon.test.run/id (:seon.test.run/id run)])))
        (is (vector? (runner/commit-results! connection captured))
            "retrying the same completion preserves immutable normalized provenance")))))

(deftest explicit-namespace-completion-commits-with-membership-unknown
  (support/with-database
    (fn [connection]
      (let [s "namespace.completion/check"
            _ (is (:db-after (transact! connection [{:seon.test/sym s}])))
            database (db/db connection)
            digest (get (runner/reach-digests database [s]) s)
            captured (-> (completion database s 0)
                         (dissoc :seon.db/db)
                         (assoc :seon.test.runner/selection-mode :namespaces
                                :seon.test/reach-digests {s digest})
                         (assoc-in [:seon.test.run/provenance :seon.test.run/branch]
                                   :building-source-no-longer-addressable))
            result (runner/commit-results! connection captured)]
        (is (vector? result) (pr-str result))
        (is (= digest (:seon.test/reach-digest (first result))))
        (is (string? (:seon.test/reach-unknown (first result))))
        (is (= 1 (:seon.test/pass-count (first result))))
        (is (= :seon.test/unknown (:seon.error/kind (sut/changed-since-green (db/db connection) s))))
        (let [known (runner/commit-results! connection (completion (db/db connection) s 0))]
          (is (vector? known))
          (is (nil? (:seon.test/reach-unknown (first known)))))))))

(def ^:private reported-path-schema
  (seon.schema.datahike/malli->datahike-schema [:seon.test.failure/reported-file]))

(deftest recording-mints-an-absent-identity-instead-of-rejecting-the-completion
  (testing "reach evidence that outlived a declaration is recorded, never refused"
    (support/with-database
      {:seon.test-support/extra-schema reported-path-schema}
      (fn [connection]
        (let [s "absent.facts/check"
              present "absent.facts/present"
              deleted "absent.facts/deleted"]
          (is (:db-after (transact! connection [{:seon.fn/sym present}
                                                {:seon.test/sym s}])))
          (let [database (db/db connection)
                _ (is (nil? (db/pull database [:db/id] [:seon.fn/sym deleted]))
                      "the recording database genuinely has no row for the named symbol")
                captured
                (-> (completion database s 1)
                    (dissoc :seon.db/db)
                    (assoc :seon.test/reach-digests
                           {s (get (runner/reach-digests database [s]) s)}
                           :seon.test/reaches
                           {s [[:seon.fn/sym present] [:seon.fn/sym deleted]]}))
                result (runner/commit-results! connection captured)
                recorded (db/db connection)]
            (is (vector? result) (pr-str result))
            (is (= 1 (:seon.test/fail-count (first result)))
                "the rest of the completion commits")
            (is (= #{present deleted}
                   (set (db/q '[:find [?sym ...] :in $ ?test
                                :where [?t :seon.test/sym ?test]
                                       [?t :seon.test/reach ?f]
                                       [?f :seon.fn/sym ?sym]] recorded s)))
                "both members are recorded, the absent one through a minted identity")
            (is (= {:seon.fn/sym deleted}
                   (dissoc (db/pull recorded '[*] [:seon.fn/sym deleted])
                           :db/id :seon.fn/ns :seon.schema.admission/source))
                "the minted row is a tombstone: the identity and nothing else")
            (is (= 'absent.facts
                   (get-in (db/pull recorded '[{:seon.fn/ns [:seon.ns/name]}]
                                    [:seon.fn/sym deleted])
                           [:seon.fn/ns :seon.ns/name])))
            (is (nil? (:seon.fn/source (db/pull recorded '[:seon.fn/source]
                                                [:seon.fn/sym deleted])))
                "minting never fabricates a definition")))))))

(deftest preserved-evidence-survives-a-rebuild-that-deleted-a-declaration
  (support/with-database
    {:seon.test-support/extra-schema reported-path-schema}
    (fn [connection]
      (let [deleted "rebuilt.facts/deleted"
            path "/rebuilt/facts/no-such-file.clj"
            evidence [{:seon.test/sym "rebuilt.facts/check"
                       :seon.schema.admission/source :core
                       :seon.test/pass-count 0 :seon.test/fail-count 1
                       :seon.test/error-count 0
                       :seon.test/reach [[:seon.fn/sym deleted]]
                       :seon.test/failures
                       [{:seon.test.failure/id (id/id ["rebuilt" 0])
                         :seon.test.failure/type :fail
                         :seon.test.failure/ordinal 0
                         :seon.test.failure/file [:seon.fn.file/path path]
                         :seon.test.failure/line 7}]}]
            database (db/db connection)]
        (is (nil? (db/pull database [:db/id] [:seon.fn/sym deleted]))
            "the rebuilt source never minted the deleted declaration")
        (let [rewritten (#'seon.cluster.source/preserved-evidence-tx database evidence)
              report (db/transact! connection [[:db.fn/call (fn [_] rewritten)]])
              committed (db/db connection)]
          (is (:db-after report) (pr-str report))
          (is (= [deleted]
                 (mapv :seon.fn/sym
                       (:seon.test/reach
                        (db/pull committed '[{:seon.test/reach [:seon.fn/sym]}]
                                 [:seon.test/sym "rebuilt.facts/check"]))))
              "the carried member still resolves, through a minted tombstone")
          (let [failure (first (:seon.test/failures
                                (db/pull committed
                                         '[{:seon.test/failures [*]}]
                                         [:seon.test/sym "rebuilt.facts/check"])))]
            (is (= path (:seon.test.failure/reported-file failure)))
            (is (= 7 (:seon.test.failure/line failure))
                "the site keeps its line without a dangling file ref")))))))
