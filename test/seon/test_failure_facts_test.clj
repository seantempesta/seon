(ns seon.test-failure-facts-test
  (:require [seon.schema] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.blob :as blob]
            [seon.id :as id]
            [seon.problems :as problems]
            [seon.render.test :as render]
            [seon.db :as db]
            [seon.test :as sut]
            [seon.program :as program]
            [seon.schema.datahike]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn- with-probe {:malli/schema [:=> [:cat :seon.db/connection [:=> [:cat :qualified-symbol :seon.schema/value :seon.schema/value] :seon.schema/value]] :seon.schema/value]}
  [connection check]
  (support/seed-cluster! connection "default")
  (support/transacted!
   connection
   [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                               (db/db connection))
     :seon.source/test-input-digest (id/digest 64 [::failure-inputs])}])
  (let [namespace-name (symbol (str "failure.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (symbol (str namespace-name) "probe")
        mode (atom :red)
        test-var (intern namespace-object 'probe)
        path (get-in (db/pull (db/db connection)
                       '[{:seon.fn/file [:seon.fn.file/relative-path]}]
                       [:seon.test/sym 'seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced])
                     [:seon.fn/file :seon.fn.file/relative-path])]
    (alter-meta! test-var assoc :test
      (fn []
        (case @mode
          :green (is true)
          :blob (is (= :small (apply str (repeat 5000 "é"))) "large actual")
          :thrown (throw (ex-info "probe thrown" {::probe :thrown}))
          :red (testing "outer" (testing "inner"
                 (is (= 1 2) "first claim")
                 (is (= :a :b) "second claim"))))))
    (try
      ;; The probe is a HOST test: `resolve-test` runs a `:core` entity's compiled
      ;; Var (the one interned above, whose body follows `mode`), while an
      ;; `:agent` entity runs its interpreted source, which never changes mode.
      (let [database (db/db connection)
            tx (db/transact! connection
                 [(support/namespace-entity database namespace-name)
                  (support/program-entity database [:seon.test/sym test-symbol]
                                       "(deftest probe (is (= 1 2)) (is (= :a :b)))"
                                       {:seon.fn/file [:seon.fn.file/relative-path path]
                                        :seon.schema.admission/source :core})])]
        (is (:db-after tx) (pr-str tx))
        (when (:seon.db.write.attempt/request-id tx)
          (throw (ex-info "The failure fixture transaction was refused." tx))))
      (check test-symbol test-var mode)
      (finally (remove-ns namespace-name)))))

(defn- replace-declaration!
  "Replace one declaration exactly; see `seon.test-support/replacement-tx`."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.program/identity :string :map]
                  :seon.db/transaction-report]}
  [connection declaration source overrides]
  (support/transacted! connection (support/replacement-tx (db/db connection) declaration source overrides)))

(defn- redeclare-probe!
  "Declare `forms` in the probe's namespace and the probe as the host test
  `test-form`, from one analyzed source, replacing each stored row exactly."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test/sym :string [:* :string]]
                  :seon.db/transaction-report]}
  [connection test-symbol test-form & forms]
  (let [database (db/db connection)
        namespace-name (symbol (namespace test-symbol))
        source (str/join "\n" (concat [(pr-str (list 'ns namespace-name))] forms [test-form]))
        path (get-in (db/pull database '[{:seon.fn/file [:seon.fn.file/relative-path]}]
                              [:seon.test/sym test-symbol])
                     [:seon.fn/file :seon.fn.file/relative-path])
        replacement #(support/replacement-tx database %1 source %2)]
    (support/transacted!
     connection
     (into (replacement [:seon.test/sym test-symbol]
                        {:seon.fn/file [:seon.fn.file/relative-path path]
                         :seon.schema.admission/source :core})
           (mapcat #(replacement [:seon.fn/sym (symbol (str namespace-name)
                                                       (str (second (read-string %))))] {}))
           forms))))

(defn- run-probe [connection test-var]
  (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                         :seon.test/recording-connection connection
                         :seon.test/policy :named
                         :seon.test/identities
                         #{(symbol (str (:ns (meta test-var))) (str (:name (meta test-var))))}})]
    (if (:seon.error/at result) result (first (:seon.test/results result)))))

(defn- reports
  "The failure reports a recorded result carries."
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [result]
  ;; a green result carries no reports key
  (:seon.test.failure/reports result []))

(deftest one-failing-is-becomes-one-failure-report
  ;; An admitted run records immutable member reports; the test entity carries no
  ;; latest-result failure family.
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v _]
          (let [result (run-probe connection v)
                failures (reports result)]
            (is (= 2 (:seon.test/fail-count result)) (pr-str result))
            (is (= 2 (count failures)))
            (is (= #{"first claim" "second claim"} (set (map :seon.test.failure/message failures))))
            (doseq [failure failures]
              (is (= :fail (:seon.test.failure/type failure)))
              (is (string? (:seon.test.failure/expected failure)))
              (is (string? (:seon.test.failure/actual failure)))
              (is (pos-int? (:seon.test.failure/line failure)))
              (is (= "test/seon/test_failure_facts_test.clj" (:seon.test.failure/reported-file failure)))
              (is (= ["outer" "inner"] (:seon.test.failure/testing-contexts failure))))
            (is (= 2 (count (db/q '[:find ?report :in $ ?s
                                    :where [?member :seon.test.member/symbol ?s]
                                           [?member :seon.test.member/failures ?report]]
                                  (db/db connection) s))))
            (is (nil? (:seon.test/failures (db/pull (db/db connection) [:seon.test/failures]
                                                    [:seon.test/sym s]))))))))))

(deftest a-repeated-failure-is-the-same-report
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v _]
          (let [ids #(set (map :seon.test.report/id (reports %)))
                before (run-probe connection v)
                after (run-probe connection v)]
            (is (= 2 (count (ids before))))
            (is (= (ids before) (ids after)) "an equal claim at one position is one report")
            (is (= 2 (count (db/q '[:find [?id ...] :in $ ?s
                                    :where [?member :seon.test.member/symbol ?s]
                                           [?member :seon.test.member/failures ?report]
                                           [?report :seon.test.report/id ?id]]
                                  (db/db connection) s))))))))))

(deftest a-rethrown-error-records-each-execution
  ;; A thrown actual is stored whole as an error value carrying its own
  ;; `:seon.error/at`, so each execution stores another blob. The report id
  ;; covered only the captured signature and position, so the second run of
  ;; an unchanged erroring test collided with the first report and the writer
  ;; refused the whole completion as :seon.test/report-conflict (fault
  ;; 93e4a1e2 on default, 2026-09-27: turn-test and agent-test recorded nothing).
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s _ mode]
          (reset! mode :thrown)
          (let [record #(sut/run {:seon.test/execution (support/execution-handle connection)
                                  :seon.test/recording-connection connection
                                  :seon.test/policy :named :seon.test/identities #{s}})
                first-run (record)
                second-run (record)
                report (fn [run] (first (reports (first (:seon.test/results run)))))]
            (doseq [run [first-run second-run]]
              (is (nil? (:seon.test/recording-refusal run)) (pr-str (:seon.test/recording-refusal run)))
              (is (= 1 (:seon.test/executed-count run)))
              (is (= 1 (:seon.test/error-count (first (:seon.test/results run)))))
              (is (= 'clojure.lang.ExceptionInfo (:seon.test.failure/throwable (report run)))))
            (is (= (:seon.test/failure-identity (report first-run))
                   (:seon.test/failure-identity (report second-run)))
                "the claim is unchanged")
            (is (not= (:seon.test.failure/actual-blob (report first-run))
                      (:seon.test.failure/actual-blob (report second-run)))
                "each execution stored its own thrown value")
            (is (not= (:seon.test.report/id (report first-run))
                      (:seon.test.report/id (report second-run)))
                "so each is its own report")))))))

(deftest a-green-run-is-the-latest-result-and-keeps-red-history
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v mode]
          (let [red (run-probe connection v)]
            (is (= 2 (count (reports red))))
            (reset! mode :green)
            (let [green (run-probe connection v)
                  latest (sut/recorded-result (db/db connection) s)]
              (is (= 1 (:seon.test/pass-count green)))
              (is (empty? (reports green)))
              (is (= [1 0 0] ((juxt :seon.test/pass-count :seon.test/fail-count :seon.test/error-count) latest)))
              (is (empty? (reports latest)))
              (is (= (set (map :seon.test.report/id (reports red)))
                     (set (db/q '[:find [?id ...] :in $ ?s
                                  :where [?member :seon.test.member/symbol ?s]
                                         [?member :seon.test.member/failures ?report]
                                         [?report :seon.test.report/id ?id]]
                                (db/db connection) s)))
                  "the red member's reports are immutable history"))))))))

(deftest an-oversized-actual-settles-into-a-blob
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (with-probe connection
        (fn [_ v mode]
          (reset! mode :blob)
          (let [failure (first (reports (run-probe connection v)))
                digest (:seon.test.failure/actual-blob failure)
                content (when digest (blob/get connection digest))]
            (is (string? digest))
            (is (nil? (:seon.test.failure/actual failure)))
            ;; The stored actual is its bounded shown text, never a whole-value
            ;; print (aa00e58fc); text over the blob threshold settles there.
            (is (> (:seon.test.failure/actual-size failure 0)
                   (db/q '[:find ?n . :where [_ :seon.config.eval.result/blob-threshold ?n]]
                         (db/db connection))))
            (is (str/includes? (or content "") (apply str (repeat 100 "é"))))
            (when digest
              (is (= content (sci/eval-string* (support/fork-cluster-ctx connection)
                                (pr-str (list 'seon.blob/get digest))))
                  "the rendered blob read uses real SCI connection preparation"))))))))

(deftest an-interpreted-test-failure-records-no-false-site
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v _]
          ;; The agent's own entity: its member interprets this source in SCI.
          (let [source (str "(ns " (namespace s) " (:require [clojure.test :refer [deftest is]]))\n"
                            "(deftest probe (is (= 1 2)))")
                _ (replace-declaration! connection [:seon.test/sym s] source {})
                result (run-probe connection v)
                failure (first (reports result))]
            (is (= 1 (:seon.test/fail-count result)) (pr-str result))
            (is (= "(= 1 2)" (:seon.test.failure/expected failure)) (pr-str failure))
            (is (nil? (:seon.test.failure/reported-file failure)))
            (is (nil? (:seon.test.failure/line failure)))))))))

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
          ;; The change that makes it red: the test now calls `reached`. Reach
          ;; derives from the entity's analyzed calls, so `reached` is a changed
          ;; dependency, and the changed program is executed, not reused.
          (let [reached (symbol (namespace s) "reached")
                tx (redeclare-probe! connection s
                                     "(deftest probe (reached) (is (= 1 2)) (is (= :a :b)))"
                                     "(defn reached [] :changed)")
                red (run-probe connection v)
                database (db/db connection)
                changed (sut/changed-since-green database s)
                unit {:seon.db/db database
                      :seon.render/value (db/pull database '[*] [:seon.test/sym s])}
                [summary _evidence changed-form]
                (rest (read-string (str "(do\n" (render/render-ai unit) "\n)")))
                sites (set (map (juxt :seon.test.failure/reported-file :seon.test.failure/line)
                                (reports red)))
                links (filter #(and (vector? %) (= :a (first %)) (map? (second %)))
                              (tree-seq coll? seq (render/render-html unit)))]
            (is (:db-after tx) (pr-str tx))
            (is (= 2 (:seon.test/fail-count red)) (pr-str red))
            (is (= 2 (count sites)) (pr-str sites))
            (is (= [reached] changed) (pr-str changed))
            (is (= (str "Test " s ": fail") (first (str/split-lines (second summary))))
                (pr-str summary))
            (is (every? (fn [[path line]] (str/includes? (second summary) (str path ":" line))) sites)
                (pr-str summary))
            (is (= (list 'seon.test/changed-since-green (list 'seon.db/db) (list 'quote s)) changed-form)
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

(deftest a-claim-reported-at-a-moved-line-records-another-report
  ;; A report's id once named only its claim, so an edit that moved a failing
  ;; assertion gave the new report the stored report's id with other facts
  ;; (docs/seon/issues/moving-a-failing-assertion-conflicts-with-its-immutable-report.md):
  ;; the recorder refused the whole request with :seon.test/report-conflict
  ;; and left every later member pending (run df397c67dcc7, 2026-09-23).
  (let [attributes [:seon.test/failure-identity :seon.test.failure/type
                    :seon.test.failure/line :seon.test.failure/reported-file]
        s 'report.facts/moved
        failure (fn [line]
                  {:seon.test/failure-identity (id/digest 64 [::moved-claim])
                   :seon.test.failure/type :fail
                   :seon.test.failure/line line
                   :seon.test.failure/reported-file "test/report/facts.clj"
                   :seon.test.failure/file {:db/id 1}})
        at-10 (runner/report-entity attributes s (failure 10))
        at-12 (runner/report-entity attributes s (failure 12))]
    (is (not= (:seon.test.report/id at-10) (:seon.test.report/id at-12))
        "the moved claim is another report")
    (is (= at-12 (runner/report-entity attributes s (failure 12)))
        "an equal report is the same entity")
    (is (= 12 (:seon.test.failure/line at-12)))
    (let [forged (runner/report-entity attributes s (assoc (failure 12) :seon.test.failure/type :error))]
      (is (= (:seon.test.report/id at-12) (:seon.test.report/id forged))
          "other content under an unchanged signature at one position is that report's identity")
      (is (not= at-12 forged) "so the recorder's comparison refuses it as a report conflict"))
    (is (nil? (find at-12 :seon.test.failure/file))
        "only declared report attributes are stored")))

(defn- stored-reach
  "The reach symbols recorded on `test-symbol`'s row within its own namespace.
  `:seon.test/reach` holds edge symbols (host ones like `clojure.core/defn`
  included), never refs to declaration rows."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] [:set :qualified-symbol]]}
  [database test-symbol]
  (into #{} (filter #(= (namespace test-symbol) (namespace %)))
        (db/q '[:find [?s ...] :in $ ?test
                :where [?t :seon.test/sym ?test] [?t :seon.test/reach ?s]]
              database test-symbol)))

(defn- synthetic-source
  "One synthetic namespace's source: its `ns` form followed by `forms`."
  {:malli/schema [:=> [:cat :seon.ns/name [:sequential :string]] :string]}
  [namespace-name forms]
  (str/join "\n" (cons (pr-str (list 'ns namespace-name '(:require [clojure.test :refer [deftest]])))
                        forms)))

(defn- declare!
  "Declare the named members of one synthetic namespace, and that namespace,
  through the production analyzer, so the writer receives canonical rows."
  {:malli/schema [:=> [:cat :seon.db/connection [:vector [:tuple :keyword :qualified-symbol]]
                       [:* :string]]
                  :seon.db/transaction-report]}
  [connection identities & forms]
  (let [database (db/db connection)
        namespace-name (symbol (namespace (second (first identities))))
        source (synthetic-source namespace-name forms)]
    (support/transacted! connection
      (into [(support/namespace-entity database namespace-name)]
            (map #(support/program-entity database % source))
            identities))))

(defn- redeclare!
  "Replace one declaration exactly with its entity analyzed from `form`."
  {:malli/schema [:=> [:cat :seon.db/connection [:tuple :keyword :qualified-symbol] :string]
                  :seon.db/transaction-report]}
  [connection declaration form]
  (let [database (db/db connection)
        source (synthetic-source (symbol (namespace (second declaration))) [form])]
    (support/transacted! connection (support/replacement-tx database declaration source {}))))

(deftest publication-replaces-cardinality-many-tuples
  (support/with-database
    (fn [connection]
      (let [s 'reach.facts/tuple-owner]
        (is (:db-after (declare! connection [[:seon.fn/sym s]]
                                 "(defn tuple-owner [x] (str (inc x) (+ x x)))")))
        (let [current (db/pull (db/db connection) '[*] [:seon.fn/sym s])]
          (is (:db-after (db/transact! connection
                          (program/exact-replacement-tx current
                            (assoc (dissoc current :db/id) :seon.fn/call-arities #{['clojure.core/dec 1]})))))
          (is (= #{['clojure.core/dec 1]}
                 (set (:seon.fn/call-arities
                        (db/pull (db/db connection) [:seon.fn/call-arities] [:seon.fn/sym s]))))))))))

(deftest recorded-reach-belongs-to-the-tested-value-and-is-replaced
  (support/with-database
    (fn [connection]
      (let [s 'reach.facts/check
            a 'reach.facts/a b 'reach.facts/b]
        (is (:db-after (declare! connection [[:seon.fn/sym a] [:seon.fn/sym b] [:seon.test/sym s]]
                                 "(defn a [] 1)" "(defn b [] 2)" "(deftest check (a))")))
        (let [tested (db/db connection)
              captured (completion tested s 1)]
          (is (:db-after (redeclare! connection [:seon.test/sym s] "(deftest check (b))")))
          (is (vector? (runner/commit-results! connection captured)))
          (is (= #{a} (stored-reach (db/db connection) s)))
          (is (= (get (runner/reach-digests tested [s]) s)
                 (:seon.test/reach-digest (db/pull (db/db connection)
                                           [:seon.test/reach-digest] [:seon.test/sym s]))))
          (is (vector? (runner/commit-results! connection (completion (db/db connection) s 0))))
          (is (= #{b} (stored-reach (db/db connection) s))))))))

(deftest what-made-it-red-names-changed-functions
  ;; `changed-since-green` reads the member records of an admitted run, so each
  ;; green here is a real nested run of the host probe.
  (support/with-database
    (fn [connection]
      (with-probe connection
        (fn [s v mode]
          (let [a (symbol (namespace s) "a")
                calls-a "(deftest probe (a))"]
            (reset! mode :green)
            (redeclare-probe! connection s calls-a
                              "(defn a {:malli/schema [:=> [:cat] :int]} [] 1)" "(defn b [] 2)")
            (is (string? (:seon.test/unknown (sut/changed-since-green (db/db connection) s))))
            (is (= 1 (:seon.test/pass-count (run-probe connection v))))
            (redeclare-probe! connection s calls-a "(defn a {:malli/schema [:=> [:cat] :int]} [] 3)")
            (is (= [a] (sut/changed-since-green (db/db connection) s)))
            (is (= 1 (:seon.test/pass-count (run-probe connection v))))
            (is (= [] (sut/changed-since-green (db/db connection) s))
                "the later green is recognized even when its counts are unchanged")
            (redeclare-probe! connection s calls-a "(defn a [] 3)" "(defn b [] 4)")
            (is (= [a] (sut/changed-since-green (db/db connection) s))
                "spec retraction counts; a changed function outside the tested closure does not")))))))

(deftest recorded-run-names-its-addressable-destination
  (support/with-database
    (fn [connection]
      (let [_ (declare! connection [[:seon.test/sym 'branch.facts/check]] "(deftest check)")
            database (db/db connection)
            captured (assoc-in (completion database 'branch.facts/check 0)
                              [:seon.test.run/provenance :seon.test.run/branch]
                              :building-source-retired)
            _ (is (vector? (runner/commit-results! connection captured)))
            entity (db/pull (db/db connection)
                        '[{:seon.test/run [*]}] [:seon.test/sym 'branch.facts/check])
            run (:seon.test/run entity)]
        (is (= (get-in database [:config :branch]) (:seon.test.run/branch run)))
        (is (= :building-source-retired (:seon.test.run/tested-branch run)))
        (is (:db/id (db/pull (db/db connection) [:db/id]
                            [:seon.test.run/id (:seon.test.run/id run)])))
        (is (vector? (runner/commit-results! connection captured))
            "retrying the same completion preserves immutable normalized provenance")))))

(deftest explicit-namespace-completion-commits-with-membership-unknown
  (support/with-database
    (fn [connection]
      (let [s 'namespace.completion/check
            _ (is (:db-after (declare! connection [[:seon.test/sym s]] "(deftest check)")))
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
        (is (string? (:seon.test/unknown (sut/changed-since-green (db/db connection) s))))
        (let [known (runner/commit-results! connection (completion (db/db connection) s 0))]
          (is (vector? known))
          (is (nil? (:seon.test/reach-unknown (first known)))))))))

(defn- reported-path-schema []
  (seon.schema.datahike/malli->datahike-schema-in (seon.schema/handed-projection) [:seon.test.failure/reported-file]))

(deftest recording-keeps-an-absent-reach-member-as-its-symbol
  (testing "reach evidence that outlived a declaration is recorded, never refused"
    (support/with-database
      {:seon.test-support/extra-schema (reported-path-schema)}
      (fn [connection]
        (let [s 'absent.facts/check
              present 'absent.facts/present
              deleted 'absent.facts/deleted]
          (is (:db-after (declare! connection [[:seon.fn/sym present] [:seon.test/sym s]]
                                   "(defn present [] 1)" "(deftest check)")))
          (let [database (db/db connection)
                _ (is (nil? (db/pull database [:db/id] [:seon.fn/sym deleted]))
                      "the recording database genuinely has no entity for the named symbol")
                captured
                (-> (completion database s 1)
                    (dissoc :seon.db/db)
                    (assoc :seon.test/reach-digests
                           {s (get (runner/reach-digests database [s]) s)}
                           :seon.test/reaches
                           {s #{present deleted}}))
                result (runner/commit-results! connection captured)
                recorded (db/db connection)]
            (is (vector? result) (pr-str result))
            (is (= 1 (:seon.test/fail-count (first result)))
                "the rest of the completion commits")
            (is (= #{present deleted}
                   (stored-reach recorded s))
                "both members are recorded as the edge symbols reach holds")
            (is (nil? (db/pull recorded [:db/id] [:seon.fn/sym deleted]))
                "recording an absent member creates no declaration entity")))))))

(defn- record-tx-data
  "Commit one completion through the writer and return its emitted datoms."
  [connection captured]
  (let [report (db/transact! connection
                             [[:db.fn/call #'runner/record-tx captured]])]
    (is (:db-after report) (pr-str report))
    (remove #(= :db/txInstant (:a %)) (:tx-data report))))

(deftest an-unchanged-re-record-emits-no-datom
  ;; Recording re-asserted every reach member, digest and failure attribute it
  ;; had just retracted: one unchanged 93-result completion cost 157,981
  ;; datoms on `default` (2026-09-17), and each cold batch grew the store by
  ;; hundreds of megabytes. The replacement is a delta now; unchanged
  ;; evidence must write nothing at all.
  (support/with-database
    (fn [connection]
      (let [s 'delta.facts/check t 'delta.facts/other
            a 'delta.facts/a b 'delta.facts/b
            assertion-id (id/id [s "first claim" 0] 64)]
        (is (:db-after (declare! connection [[:seon.fn/sym a] [:seon.fn/sym b]
                                             [:seon.test/sym s] [:seon.test/sym t]]
                                 "(defn a [] 1)" "(defn b [] 2)"
                                 "(deftest check (a) (b))" "(deftest other (a))")))
        (let [tested (db/db connection)
              run (runner/provenance tested)
              ;; No :seon.db/db — `commit-results!` resolves the tested value
              ;; into digests and memberships and dissocs it before the
              ;; transaction function, because an embedded database value is
              ;; not admissible transaction-function data.
              captured {:seon.test.run/provenance run
                        :seon.test/run-basis-t (:seon.test.run/basis-t run)
                        :seon.test/run-at (:seon.test.run/at run)
                        :seon.test/reach-digests (runner/reach-digests tested [s t])
                        :seon.test/reaches (runner/reach-memberships tested [s t])
                        :seon.test.runner/results
                        [{:seon.test/sym s :seon.test/pass-count 0
                          :seon.test/fail-count 1 :seon.test/error-count 0
                          :seon.test/failing-assertions [assertion-id]
                          :seon.test/failure-message "first claim"}
                         {:seon.test/sym t :seon.test/pass-count 1
                          :seon.test/fail-count 0 :seon.test/error-count 0}]}
              first-pass (record-tx-data connection captured)]
          (is (seq first-pass) "the first recording writes the result")
          (is (seq (filter #(= :seon.test/reach (:a %)) first-pass))
              "the first recording asserts the reach membership")
          (is (= [] (vec (record-tx-data connection captured)))
              "an identical re-record emits nothing beyond the run's provenance")
          (testing "one changed result emits only that result's change"
            (let [changed (assoc-in captured
                                    [:seon.test.runner/results 1
                                     :seon.test/pass-count] 2)
                  datoms (record-tx-data connection changed)
                  other (:db/id (db/pull (db/db connection) [:db/id]
                                         [:seon.test/sym t]))]
              (is (= #{other} (set (map :e datoms)))
                  (str "only the changed result's entity is touched: "
                       (pr-str datoms)))
              (is (= #{:seon.test/pass-count} (set (map :a datoms)))
                  (pr-str datoms))
              (is (= [2] (mapv :v (filter :added datoms)))
                  (pr-str datoms)))))))))

(deftest a-changed-reach-member-retracts-only-itself
  ;; The membership is replaced member by member: a dropped member retracts
  ;; itself and every retained member writes nothing. A whole-attribute
  ;; retraction is what made an unchanged 1000-member closure cost 2000
  ;; datoms per run.
  (support/with-database
    (fn [connection]
      (let [s 'member.facts/check a 'member.facts/a b 'member.facts/b]
        (is (:db-after (declare! connection [[:seon.fn/sym a] [:seon.fn/sym b] [:seon.test/sym s]]
                                 "(defn a [] 1)" "(defn b [] 2)" "(deftest check (a) (b))")))
        (let [tested (db/db connection)
              run (runner/provenance tested)
              captured (fn [members]
                         {:seon.test.run/provenance run
                          :seon.test/run-basis-t (:seon.test.run/basis-t run)
                          :seon.test/run-at (:seon.test.run/at run)
                          :seon.test/reach-digests
                          (runner/reach-digests tested [s])
                          :seon.test/reaches
                          {s (set members)}
                          :seon.test.runner/results
                          [{:seon.test/sym s :seon.test/pass-count 1
                            :seon.test/fail-count 0 :seon.test/error-count 0}]})]
          (is (seq (record-tx-data connection (captured [s a b]))))
          (let [datoms (record-tx-data connection (captured [s a]))]
            (is (= [:seon.test/reach] (distinct (map :a datoms))) (pr-str datoms))
            (is (= 1 (count datoms)) (pr-str datoms))
            (is (false? (:added (first datoms))) (pr-str datoms))
            (is (= b (:v (first datoms))) (pr-str datoms)))
          (is (= #{s a} (stored-reach (db/db connection) s))))))))
