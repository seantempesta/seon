(ns seon.test
  "Agent-facing test execution over the one JVM test runner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.await :as await]
            [seon.config :as config]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.error :as error]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test.runner :as runner])
  (:import [clojure.lang DynamicClassLoader]
           [java.util.concurrent FutureTask]))

(defn- unknown
  {:malli/schema [:=> [:cat :seon.schema/value :string] :seon.test/unknown-error]}
  [input message]
  {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/execution
   :seon.error/operation 'seon.test/unknown :seon.test/unknown (str input)
   :seon.error/message message :seon.test/next-tier :none})

(defn failure-text
  "Show an assertion's exact claim, ordered contexts, and known source site."
  {:malli/schema [:=> [:cat [:or :seon.test.failure/value :seon.test.failure/report]] :string]}
  [failure]
  (let [field (fn [inline blob]
                (or (get failure inline)
                    (when-let [digest (get failure blob)]
                      (pr-str (list 'seon.blob/get digest)))))
        path (get-in failure [:seon.test.failure/file :seon.fn.file/relative-path])]
    (str/join "\n"
      (remove nil?
        [(str (name (:seon.test.failure/type failure))
              (when path (str " " path ":" (:seon.test.failure/line failure))))
         (when (seq (:seon.test.failure/contexts failure))
           (str/join " > " (map second (sort-by first (:seon.test.failure/contexts failure)))))
         (:seon.test.failure/message failure)
         (when-let [expected (field :seon.test.failure/expected :seon.test.failure/expected-blob)]
           (str "expected: " expected))
         (when-let [actual (field :seon.test.failure/actual :seon.test.failure/actual-blob)]
           (str "actual: " actual))]))))

(defn failure-message
  "Read structured assertion evidence, retaining legacy text for old results."
  {:malli/schema [:=> [:cat :seon.test.runner/captured-result] :string]}
  [result]
  (if-let [failures (or (seq (:seon.test/failures result))
                        (seq (:seon.test.failure/reports result)))]
    (str/join "\n\n" (map failure-text failures))
    (or (:seon.test/failure-message result) "No assertion claim was retained.")))

(defn changed-since-green
  "Functions in the recorded tested closure with source/spec datoms after its
  last green result. Includes retractions. Missing history or closure evidence
  is unknown. This names changed dependencies, not proof of causation."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or [:vector :seon.fn/sym]
                   :seon.error/value]]}
  [database test-symbol]
  (let [row (db/pull database [:db/id :seon.test/reach-unknown :seon.test/reach-digest '(limit :seon.test/reach nil)]
                     [:seon.test/sym test-symbol])]
    (cond
      (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
      (not (:db/id row)) (unknown test-symbol "The test has no recorded identity.")
      (:seon.test/reach-unknown row) (unknown test-symbol (:seon.test/reach-unknown row))
      (not (:seon.test/reach-digest row))
      (unknown test-symbol "The test has no retained function closure evidence.")
      :else
      (let [history (db/history database)
            events (db/q '[:find ?a ?v ?t ?added
                           :in $ ?e [?a ...]
                           :where [?e ?a ?v ?t ?added]]
                         history (:db/id row)
                         [:seon.test/run :seon.test/pass-count
                          :seon.test/fail-count :seon.test/error-count])]
        (if (and (map? events) (contains? events :seon.error/at) (contains? events :seon.error/layer) (contains? events :seon.error/operation)) events
            (let [{green :seon.test/run-basis-t}
                  (reduce
                    (fn [state [t datoms]]
                      (let [next-state
                            (reduce (fn [s [a v _ added]]
                                      (if added (assoc s a v) (dissoc s a)))
                                    state (sort-by #(if (nth % 3) 1 0) datoms))]
                        (cond-> next-state
                          (and (some #(and (= :seon.test/run (first %)) (nth % 3)) datoms)
                               (pos? (get next-state :seon.test/pass-count 0))
                               (= 0 (:seon.test/fail-count next-state))
                               (= 0 (:seon.test/error-count next-state)))
                          (assoc :seon.test/run-basis-t t))))
                    {} (sort-by first (group-by #(nth % 2) events)))]
              (if-not green
                (unknown test-symbol "No green result is retained in this test's history.")
                (let [changed (db/q '[:find [?sym ...]
                                      :in $history $since [?sym ...] [?a ...]
                                      :where [$history ?f :seon.fn/sym ?sym]
                                             [$since ?f ?a]]
                                    history (db/since history green)
                                    (vec (:seon.test/reach row))
                                    [:seon.fn/source :seon.fn/spec :seon.fn/sym])]
                  (if (and (map? changed) (contains? changed :seon.error/at) (contains? changed :seon.error/layer) (contains? changed :seon.error/operation)) changed
                      (vec (sort changed)))))))))))

(defn test-loader
  "Build a loader from the tool owner's ordered, resolved test classpath."
  {:malli/schema [:=> [:cat :seon.test/classpath]
                  [:or :seon.test/class-loader :seon.error/value]]}
  [{roots :seon.test/classpath-roots root :seon.test/classpath-root
    digest :seon.dev-cache/digest}]
  (let [loaded-cache (System/getProperty "seon.dependency-cache.path")]
    (if (and digest loaded-cache
             (not= digest (.getName (io/file loaded-cache))))
      (error/diagnostic
       {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/resolution
             :seon.error/operation 'seon.test/test-loader
        :seon.error/message "The JVM loaded a different dependency cache; adding URLs cannot replace its classes."
        :seon.error/diagnostic-layer :test-resolution
        :seon.error/diagnostic-operation 'seon.test/test-loader
        :seon.error/diagnostic-member :seon.dev-cache/digest
        :seon.error/diagnostic-expected digest
        :seon.error/diagnostic-offending (.getName (io/file loaded-cache))
        :seon.error/diagnostic-cause :loaded-dependency-classes
        :seon.error/diagnostic-evidence {:seon.test/classpath-root root}})
      (let [loader (DynamicClassLoader. (clojure.lang.RT/baseLoader))]
    (doseq [path roots]
      (let [file (io/file path)]
        (.addURL loader (.toURL (.toURI (if (.isAbsolute file) file
                                          (io/file root path)))))))
        loader))))

(defn- with-test-loader
  ([work] (with-test-loader (clojure.lang.RT/baseLoader) work))
  ([loader work]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (with-bindings {clojure.lang.Compiler/LOADER loader} (work))
      (finally (.setContextClassLoader thread previous))))))

(defn- event-backstop-ms []
  ;; `seon.test-support` lives under `test/`, off this namespace's classpath
  ;; until `with-test-loader` installs the test loader: a deliberate late
  ;; dependency, never a load-cycle dodge.
  (with-test-loader
    #(long (* 1000 @(requiring-resolve 'seon.test-support/event-backstop-seconds)))))

(defn- bounded-result [test-var timeout-ms custody]
  (let [test-symbol (symbol (str (:ns (meta test-var))) (str (:name (meta test-var))))
        task (FutureTask.
              (bound-fn []
                (with-test-loader
                  #(if (:seon.sci.eval/ctx custody)
                     (sci.eval/run-test
                      (assoc custody :seon.test/var test-var
                                     :seon.sci.eval/time-limit-ms timeout-ms))
                     (runner/run-var! test-var custody)))))
        thread (.unstarted (Thread/ofVirtual) task)]
    (.start thread)
    (try
      (let [result (await/await!
                     {:seon.await/future task,
                      :seon.await/bound
                      {:seon.await/config-attribute :seon.test/remaining-ms,
                       :seon.await/config-value timeout-ms},
                      :seon.await/diagnostic
                      {:seon.error/diagnostic-layer :test,
                       :seon.error/diagnostic-operation :seon.test/run,
                       :seon.error/diagnostic-member test-symbol,
                       :seon.error/diagnostic-expected :test-completion,
                       :seon.error/diagnostic-offending :pending,
                       :seon.error/diagnostic-evidence {:seon.test/sym test-symbol}}})]
        (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation))
          {:seon.test/sym test-symbol,
           :seon.test.member/began? false,
           :seon.test.member/ended? false,
           :seon.test.run/terminated? (.isDone task),
           :seon.test/pass-count 0,
           :seon.test/fail-count 0,
           :seon.test/error-count 1,
           :seon.test/failure-message (:seon.error/message result)}
          (assoc result :seon.test.run/terminated? true)))
      (catch
        Exception
        failure
        (when (instance? InterruptedException failure) (throw failure))
        {:seon.test/sym test-symbol,
         :seon.test.member/began? false,
           :seon.test.member/ended? false,
           :seon.test.run/terminated? (.isDone task),
           :seon.test/pass-count 0,
         :seon.test/fail-count 0,
         :seon.test/error-count 1,
         :seon.test/failure-message
         (str
           "Test execution failed: "
           (or (some-> failure ex-cause ex-message) (ex-message failure)))})
      ;; Expiry bounds observation. Interrupting a fixture during Datahike's
      ;; permit handoff can abandon an already-granted roster permit. The
      ;; daemon virtual thread must finish its resource scopes normally.
      (finally (when-not (.isDone task) (.cancel task false))))))

;;; ---------------------------------------------------------------------------
;;; An in-process run refuses a destructive drill on a development root
;;; ---------------------------------------------------------------------------

(defn- development-root
  "The declared operator root when THIS JVM was launched to operate its own
  working directory — the developer's checkout, with its live `data/store`.

  `bin/seon [--root PATH] start` declares the root it operates on every child
  JVM, so an ordinary development JVM declares the checkout it runs in; a
  `bin/test` worker declares its isolated run root
  (`src/seon/test/runner.clj:2549`) and `bin/test-fast` declares none. Returns
  the canonical development root, or nil when this JVM operates an isolated
  root or declares nothing."
  [declared]
  (when-not (str/blank? declared)
    (let [root (.getCanonicalPath (io/file declared))
          working (.getCanonicalPath (io/file (System/getProperty "user.dir")))]
      (when (= root working) root))))

(defn destroyers
  "What each declared destructive owner destroys: `{owner-symbol text}`.

  THE ONE DERIVATION on this side, over `:seon.fn/destroys` — the declaration
  each owner carries in its own metadata at its definition, indexed as a
  program fact. There is no roster of owners in code: the cold gate's tier
  checker derives the same set from the same attribute over manifest rows
  (`seon.test.runner/destructive-owner-rows`), so the two halves of the rule
  read one declaration and can never disagree.

  A program in which NOTHING declares it is the typed unknown, never an empty
  set: an unpublished or drifted program would otherwise walk to nothing and
  admit every test, which is absence of signal read as health."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:map-of :seon.fn/sym :seon.fn/destroys] :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find ?sym ?destroys
                     :where
                     [?function :seon.fn/destroys ?destroys]
                     [?function :seon.fn/sym ?sym]]
                   database)]
    (cond
      (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation)) rows
      (empty? rows)
      (unknown :seon.fn/destroys
               (str "No function in this program declares :seon.fn/destroys, "
                    "so an in-process run cannot tell whether a test deletes a "
                    "filesystem path it did not create. Republish the program "
                    "(bin/seon init --dev default), or declare the attribute "
                    "in the owner's own metadata at its definition."))
      :else (into {} rows))))

(defn- destructive-reach
  "Every test symbol whose reach includes a destructive owner, mapped to it.

  Membership is the shared `:seon.fn/calls` derivation `seon.fn/tests-reaching`,
  walked from each owner `destroyers` names."
  [database]
  (let [owners (destroyers database)]
    (if (and (map? owners) (contains? owners :seon.error/at) (contains? owners :seon.error/layer) (contains? owners :seon.error/operation))
      owners
      (reduce
       (fn [reached owner]
         (let [tests (functions/tests-reaching database owner)]
           (if (and (map? tests) (contains? tests :seon.error/at) (contains? tests :seon.error/layer) (contains? tests :seon.error/operation))
             (reduced tests)
             (reduce #(assoc %1 %2 owner) reached tests))))
       {}
       (sort (keys owners))))))

(defn- destructive-path
  "Shortest named call path to a destructive declaration."
  [database test-symbol owner-symbol]
  (loop [frontier [[test-symbol]] seen #{}]
    (when (seq frontier)
      (if-let [found (first (filter #(= owner-symbol (peek %)) frontier))]
        found
        (let [paths
              (into []
                    (mapcat (fn [path]
                              (let [name (peek path)
                                    identity-attribute (if (= name test-symbol) :seon.test/sym :seon.fn/sym)
                                    row (db/pull database
                                                 '[(limit :seon.fn/calls nil) :seon.test/subject]
                                                 [identity-attribute name])]
                                (when (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation))
                                  (throw (ex-info "Destructive path unavailable." row)))
                                (for [target (concat (:seon.fn/calls row)
                                                     (when-let [subject (:seon.test/subject row)] [subject]))
                                      :when (not (seen target))]
                                  (conj path target))))) frontier)]
          (recur paths (into seen (map peek) frontier)))))))

(defn- destructive-exclusion
  "One selected test's destructive evidence, or nil when it reaches no owner."
  [database owners reach test-symbol]
  (when-let [owner (get reach test-symbol)]
    (cond-> {:seon.test/sym test-symbol
             :seon.fn/sym owner
             :seon.test/destructive-path (destructive-path database test-symbol owner)
             :seon.test/command ["bin/test" "--" (namespace (symbol test-symbol))]}
      (string? (get owners owner))
      (assoc :seon.fn/destroys (get owners owner)))))

(defn host
  "Where one test runs, and why, as data.

  `:seon.test/host` is `:seon.test.host/in-process` — the cluster's own JVM,
  where an agent's own run and `seon.test/check` execute it — or
  `:seon.test.host/isolated-snapshot`, because the test reaches a function
  declaring `:seon.fn/destroys`; that answer carries the owner, what it
  destroys, the declared call path between them, and the cold invocation that
  may run it.

  Derived, never declared on the test and never stored: the answer is the
  program graph read now, so it moves the moment a declaration or a call edge
  does. A test with NO program row has no known call graph and the answer is
  the typed unknown — absence of edges is never read as safe.

  Example:
  (seon.test/host (seon.db/db) \"seon.cluster.boot-test/a-real-boot\")"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or :seon.test/host-report :seon.error/value]]}
  [database test-symbol]
  (let [owners (destroyers database)]
    (if (and (map? owners) (contains? owners :seon.error/at) (contains? owners :seon.error/layer) (contains? owners :seon.error/operation))
      owners
      (let [row (db/pull database [:db/id] [:seon.test/sym test-symbol])]
        (cond
          (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
          (nil? (:db/id row))
          (unknown test-symbol
                   (str "No program row declares the test " test-symbol
                        ", so its call graph is unknown and where it runs "
                        "cannot be derived. Publish the program "
                        "(bin/seon init --dev default --changed <file>) and "
                        "ask again."))
          :else
          (let [reach (destructive-reach database)]
            (if (and (map? reach) (contains? reach :seon.error/at) (contains? reach :seon.error/layer) (contains? reach :seon.error/operation))
              reach
              (if-let [evidence (destructive-exclusion database owners reach test-symbol)]
                (assoc evidence :seon.test/host :seon.test.host/isolated-snapshot)
                {:seon.test/sym test-symbol
                 :seon.test/host :seon.test.host/in-process}))))))))

(defn host-text
  "One line an agent reads: where this test runs and why.
  The unknown says so; it never reads as in-process."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] :string]}
  [database test-symbol]
  (let [report (host database test-symbol)]
    (cond
      (and (map? report) (contains? report :seon.error/at) (contains? report :seon.error/layer) (contains? report :seon.error/operation))
      (str "runs: unknown — " (:seon.error/message report))
      (= :seon.test.host/isolated-snapshot (:seon.test/host report))
      (str "runs: isolated snapshot, under its own operator root ("
           (str/join " -> " (:seon.test/destructive-path report)) ": "
           (or (:seon.fn/destroys report) "deletes a filesystem path it did not create")
           "). Cold invocation: "
           (str/join " " (:seon.test/command report)))
      :else "runs: in the cluster process")))

(defn- destructive-refusal
  "Refuse one in-process run that would execute a destructive drill, or nil.

  A test reaching a function that declares `:seon.fn/destroys` deletes a
  filesystem path it did not create. Run in the JVM that operates the
  developer's own checkout, that is the 2026-09-17 incident: an in-process run
  emptied `data/store`
  (`docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md`). The
  rule was prose; this is the check, and it reads the ONE derivation `host`
  reads. It fires only for a JVM whose DECLARED operator root is that
  development root — a `bin/test` worker or a lane's `--root` scratch JVM runs
  the same test untouched. The refusal names the test, the owner it reaches,
  what that owner destroys, the call path between them, and the cold
  invocation that may run it. An UNANSWERABLE host is refused too: an unknown
  call graph is never admitted as safe."
  [database declared-root test-symbol]
  (when-let [root (development-root declared-root)]
    (let [report (host database test-symbol)]
      (cond
        (and (map? report) (contains? report :seon.error/at) (contains? report :seon.error/layer) (contains? report :seon.error/operation)) (assoc report :seon.test/next-tier :none)

        (= :seon.test.host/isolated-snapshot (:seon.test/host report))
        (let [evidence (dissoc report :seon.test/host)
              owner (:seon.fn/sym report)]
          (error/diagnostic
           (merge
            evidence
            {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/execution
             :seon.error/operation 'seon.test/run
             :seon.error/message
             (str test-symbol " reaches " owner
                  ", which deletes a filesystem path it did not create ("
                  (or (:seon.fn/destroys report) "declared :seon.fn/destroys")
                  "), and this JVM was launched to operate the development "
                  "root " root ". Run it cold — bin/test -- "
                  (namespace (symbol test-symbol))
                  " — or in a JVM under an isolated operator root "
                  "(bin/seon --root tmp/<lane>-root). Call path: "
                  (str/join " -> " (:seon.test/destructive-path report)) ".")
             :seon.error/diagnostic-layer :test
             :seon.error/diagnostic-operation ::run
             :seon.error/diagnostic-member test-symbol
             :seon.error/diagnostic-expected :isolated-operator-root
             :seon.error/diagnostic-offending root
             :seon.error/diagnostic-cause owner
             :seon.error/diagnostic-evidence evidence
             :seon.test/next-tier :none})))))))

(defn run
  "Run one declared test Var, commit its result facts, and return them.\n\n  The connection is ordinarily supplied by call preparation from the calling\n  agent's environment. The returned value is pulled from the transaction's\n  `:db-after`, so it cannot disagree with the facts that were committed.\n\n  A test whose program-graph reach includes a function declaring\n  `:seon.fn/destroys` is REFUSED, without executing, in a JVM whose declared operator root is the\n  development checkout it runs in; the refusal names the test, the owner, the\n  call path, and the cold invocation that may run it. `:seon.test/declared-root`\n  in the options is that declaration when the caller genuinely holds one;\n  absent, this JVM's own is read once here.\n\n  The test BODY runs under exactly the custody the options hand it:\n  `:seon.db/connection` present means the run is that cluster's own work and\n  the body's elided `seon.db` arities reach it; absent means none, which is\n  what a host REPL calling this is. `run-owned` is the agent's entry and\n  supplies its evaluation's connection. The `connection` argument is where\n  the RESULT FACTS are committed and never decides the body's custody."
  {:malli/schema
   [:function
    [:=> [:cat :seon.test/var :seon.db/connection] [:or :seon.test/result :seon.error/value]]
    [:=>
     [:cat :seon.test/var :seon.db/connection :seon.test/run-options]
     [:or :seon.test/result :seon.error/value]]]}
  ([test-var connection]
    (let [database (db/db connection)
          provenance (runner/provenance database)]
      (if (and (map? provenance) (contains? provenance :seon.error/at) (contains? provenance :seon.error/layer) (contains? provenance :seon.error/operation))
        provenance
        (run
          test-var
          connection
          {:seon.db/db database,
           :seon.test.run/provenance provenance,
           :seon.test/remaining-ms (event-backstop-ms)}))))
  ([test-var connection options]
    (let [database (or (:seon.db/db options) (db/db connection))]
      (if (and (map? database) (contains? database :seon.error/at) (contains? database :seon.error/layer) (contains? database :seon.error/operation))
        database
        (let [provenance (:seon.test.run/provenance options)
              declared (if-let [entry (find options :seon.test/declared-root)]
                         (val entry)
                         (store/declared-operator-root))
              refusal (destructive-refusal
                        database declared
                        (symbol (str (:ns (meta test-var))) (str (:name (meta test-var)))))
              ;; AN IN-PROCESS RUN HAPPENS INSIDE A LIVE CLUSTER'S JVM.
              ;; Whatever a test leaves in that cluster's schema projection
              ;; refuses every later write it attempts, so afterwards the
              ;; projection is advanced to the one the cluster's OWN FACTS
              ;; declare and every disagreeing key is NAMED. The snapshot is
              ;; evidence for that naming, not the thing put back: a run that
              ;; coincides with a committed retraction must not have it
              ;; reasserted (AGENTS §2.1).
              registry-before (runner/live-cluster-schema-states)
              result (cond
                       refusal refusal
                       (and (map? provenance) (contains? provenance :seon.error/at) (contains? provenance :seon.error/layer) (contains? provenance :seon.error/operation)) provenance
                       :else
                       (schema/call-with-projection
                         (db/carried-projection database)
                         #(bounded-result test-var (:seon.test/remaining-ms options)
                                          (select-keys options [:seon.db/connection :seon.sci.eval/ctx]))))
              restored (runner/restore-live-cluster-schema! registry-before)
              drifted (runner/schema-restore-drift restored)
              ;; A COMMITTED change is the writer doing its job during the
              ;; run, so it is named on the way past and is nobody's failure.
              _ (when (seq (remove (set drifted) restored))
                  (println "Live cluster schema facts changed during the run:"
                           (pr-str (vec (remove (set drifted) restored)))))
              result (if (or (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation)) (empty? drifted))
                       result
                       (-> result
                           (update :seon.test/error-count (fnil inc 0))
                           (update :seon.test/failure-message
                                   #(str (when % (str % "\n"))
                                         "Live cluster schema registry changed and was restored: "
                                         (pr-str drifted)))))]
          (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation))
            result
            (let [committed (runner/commit-results!
                              connection
                              {:seon.db/db database,
                               :seon.test.runner/results [result],
                               :seon.test/run-basis-t (:seon.test.run/basis-t provenance),
                               :seon.test/run-at (:seon.test.run/at provenance),
                               :seon.test.run/provenance provenance
                               :seon.test.run/terminated? (true? (:seon.test.run/terminated? result))})]
              (if (and (map? committed) (contains? committed :seon.error/at) (contains? committed :seon.error/layer) (contains? committed :seon.error/operation)) committed (first committed)))))))))


(declare prepare-tests! resolve-test)

(defn run-owned
  "Run one of MY declared tests, under my own cluster's custody.

  `my.test/run` resolves each test symbol my namespace declares and calls
  this with the Var. My connection is supplied by call preparation — the same
  connection my evaluation reads and writes through — and it is handed down
  as a VALUE to the test body, so a `seon.db` call my test elides inside
  reaches my cluster instead of refusing (the elided arity is the documented
  affordance inside an evaluation, AGENTS §3).

  `run` itself hands nothing: a host REPL running the same Var is not any
  cluster's own work, and its body's elided arities refuse and say so.

  An unchanged, previously green bare request returns its recorded result
  without execution. The result names :seon.test/unchanged and the recording
  basis. Supply :seon.test/run-basis-t to request a specific basis; a newer
  current basis deliberately reruns, even when the program is unchanged."
  {:malli/schema [:=> [:cat :seon.test/run-owned-request]
                  [:or :seon.test/result :seon.error/value]]}
  [{connection :seon.db/connection test-var :seon.test/var :as request}]
  (let [database (db/db connection)]
    (if (and (map? database) (contains? database :seon.error/at) (contains? database :seon.error/layer) (contains? database :seon.error/operation))
      database
      (let [test-symbol (symbol (str (:ns (meta test-var))) (str (:name (meta test-var))))
            reused (runner/reusable-result
                    (merge {:seon.db/db database :seon.test/identity test-symbol}
                           (select-keys request [:seon.test/run-basis-t])))]
        (if (not= :seon.test/execution-required reused)
          reused
          (let [provenance (runner/provenance database)
                prepared (prepare-tests! database connection request)
                resolved (if (and (map? prepared) (contains? prepared :seon.error/at) (contains? prepared :seon.error/layer) (contains? prepared :seon.error/operation))
                           prepared
                           (resolve-test
                            (assoc prepared :seon.test/identity test-symbol)))]
            (cond
              (and (map? provenance) (contains? provenance :seon.error/at) (contains? provenance :seon.error/layer) (contains? provenance :seon.error/operation)) provenance
              (and (map? resolved) (contains? resolved :seon.error/at) (contains? resolved :seon.error/layer) (contains? resolved :seon.error/operation)) resolved
              :else
              (run resolved connection
                   {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx prepared)
                    :seon.test.run/provenance provenance
                    :seon.test/remaining-ms (event-backstop-ms)}))))))))

(defn- selection-refusal
  "Name unavailable evidence on the supplied immutable database."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword :string :seon.schema/value]
                  :seon.test/selection-error]}
  [database kind message observed]
  (assoc (error/diagnostic
   {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/selection
    :seon.error/operation 'seon.test/select :seon.error/message message
    :seon.error/diagnostic-layer :test-selection
    :seon.error/diagnostic-operation 'seon.test/select
    :seon.error/diagnostic-member kind
    :seon.error/diagnostic-expected :complete-comparable-program-evidence
    :seon.error/diagnostic-offending observed
    :seon.error/diagnostic-cause kind
    :seon.error/diagnostic-evidence
    {:seon.test.run/basis-t (db/basis-t database)
     :seon.test.run/branch (get-in (db/schema-database database) [:config :branch])}}) :seon.test/selection-refusal kind))

(defn- selection-read!
  "Carry a polymorphic query result unchanged; preserve a refused read exactly."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [result]
  (if (and (map? result)
           (contains? result :seon.error/at)
           (contains? result :seon.error/layer)
           (contains? result :seon.error/operation))
    (throw (ex-info (:seon.error/message result) result))
    result))

(defn- selection-facts
  "Read complete selected attributes through bound entity indexes."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :int] [:vector :qualified-keyword]]
                  [:map-of :int [:map-of :qualified-keyword [:set :seon.schema/value]]]]}
  [database entities attributes]
  (reduce (fn [facts [entity attribute value]]
            (update-in facts [entity attribute] (fnil conj #{}) value))
          {} (selection-read!
              (db/q '[:find ?entity ?attribute ?value
                      :in $ [?entity ...] [?attribute ...] :where [?entity ?attribute ?value]]
                    database entities attributes))))

(defn- definition-digests
  "Identify definition content independently of branch-local entities.
  analyzed-source-digest identifies the analyzed input file or batch, so it
  proves analysis but cannot identify an individual definition's change."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:map-of :qualified-symbol :seon.source/digest]]}
  [database symbols]
  (let [rows (selection-read!
              (db/q '[:find ?symbol ?attribute ?value
                      :in $ [?symbol ...] [?attribute ...]
                      :where (or [?entity :seon.fn/sym ?symbol]
                                 [?entity :seon.test/sym ?symbol])
                             [?entity ?attribute ?value]]
                    database symbols
                    [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references
                     :seon.test/source :seon.test/subject :seon.test/platform
                     :seon.test/fixture :seon.test/fixture-observation :seon.test/long
                     :seon.schema.admission/source]))]
    (into {} (map (fn [[symbol facts]]
                    [symbol (id/digest 64 (vec (sort-by pr-str (map #(subvec % 1) facts))))]))
          (group-by first rows))))

(defn- changed-definition-symbols
  "Read assertion and retraction identities in the same lineage, including namespace bindings."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t]
                  [:set :qualified-symbol]]}
  [database basis]
  (let [history (db/history database)
        changes (db/since history basis)
        direct (selection-read!
                (db/q '[:find [?symbol ...]
                        :in $history $changes [?attribute ...]
                        :where [$changes ?entity ?attribute]
                        (or [$history ?entity :seon.fn/sym ?symbol]
                            [$history ?entity :seon.test/sym ?symbol])]
                      history changes
                      [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references
                       :seon.fn/sym :seon.test/sym :seon.test/source :seon.test/subject
                       :seon.test/platform :seon.test/fixture :seon.test/fixture-observation
                       :seon.test/long :seon.schema.admission/source
                       :seon.program/analyzed-source-digest]))
        before (definition-digests (db/as-of database basis) (vec direct))
        after (definition-digests database (vec direct))
        direct (filter #(not= (get before %) (get after %)) direct)
        namespaces (selection-read!
                    (db/q '[:find [?ns ...] :in $history $changes [?attribute ...]
                            :where [$changes ?entity ?attribute]
                            (or-join [?entity ?ns]
                              (and [$history ?entity :seon.ns/name] [(identity ?entity) ?ns])
                              [$history ?ns :seon.ns/aliases ?entity]
                              [$history ?ns :seon.ns/refers ?entity]
                              [$history ?ns :seon.ns/imports ?entity])]
                          history changes
                          [:seon.ns/source :seon.ns/requires :seon.ns/aliases
                           :seon.ns/refers :seon.ns/imports :seon.ns/name
                           :seon.ns.alias/local :seon.ns.alias/target-ns
                           :seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name
                           :seon.ns.import/local :seon.ns.import/target-class]))
        namespace-symbols (if (seq namespaces)
                            (selection-read!
                             (db/q '[:find [?symbol ...] :in $ [?ns ...]
                                     :where
                                     (or [?entity :seon.fn/ns ?ns] [?entity :seon.test/ns ?ns])
                                     (or [?entity :seon.fn/sym ?symbol] [?entity :seon.test/sym ?symbol])]
                                   history namespaces)) [])]
    (into (set direct) namespace-symbols)))

(defn- selection-seeds
  "Resolve supplied definition and namespace identities in this database."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/changed]
                  [:or [:set :qualified-symbol] :seon.test/selection-error :seon.test/unknown-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database changed]
  (reduce
   (fn [seeds change]
     (let [[attribute value] (if (vector? change) change [:seon.fn/sym change])]
       (if (= attribute :seon.ns/name)
         (let [members (db/q '[:find [?symbol ...] :in $ ?name
                               :where [?n :seon.ns/name ?name]
                               (or-join [?e ?n] [?e :seon.fn/ns ?n] [?e :seon.test/ns ?n])
                               (or [?e :seon.fn/sym ?symbol] [?e :seon.test/sym ?symbol])]
                             database value)]
           (cond (and (map? members)
                      (contains? members :seon.error/at)
                      (contains? members :seon.error/layer)
                      (contains? members :seon.error/operation)) (reduced members)
                 (empty? members) (reduced (unknown change "Namespace has no analyzed definitions."))
                 :else (into seeds members)))
         (if (qualified-symbol? value)
           (let [known (db/q '[:find ?e . :in $ ?symbol
                               :where (or [?e :seon.fn/sym ?symbol]
                                          [?e :seon.test/sym ?symbol]
                                          [?e :seon.fn/calls ?symbol]
                                          [?e :seon.fn/references ?symbol]
                                          [?e :seon.test/subject ?symbol])]
                             (db/history database) value)]
             (cond (and (map? known)
                        (contains? known :seon.error/at)
                        (contains? known :seon.error/layer)
                        (contains? known :seon.error/operation)) (reduced known)
                   known (conj seeds value)
                   :else (reduced (selection-refusal database :seon.test/identity-unresolved
                                   "Changed identity has no definition, history or incoming edge."
                                   [:seon.fn/sym value]))))
           (reduced (selection-refusal database :seon.test/coverage-unknown
                                       "This changed declaration has no bounded graph selection." change))))))
   #{} changed))

(defn select
  "Select complete test memberships for one explicit cluster and immutable database.
  Baselines and outstanding work derive from admitted run/member facts, ordered
  by selection transaction. Calls, references and declared subjects use one
  union gate walk. Named requests retain their declared eligibility scope.
  Green evidence from an earlier program is reusable when its reachable content
  and external inputs still match; its tested provenance remains unchanged.
  Unknown coverage refuses. No filesystem reads occur."
  {:malli/schema [:=> [:cat :seon.test.selection/request]
                  [:or :seon.test.selection/result :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db cluster :seon.test.run/cluster
    requested-namespaces :seon.test/namespaces requested-identities :seon.test/identities
    supplied-basis :seon.test.run/change-basis-t :as request}]
  (try
    (if-not cluster
      (selection-refusal database :seon.test/cluster-required "Selection requires explicit cluster custody." :absent)
      (let [cluster-row (selection-read! (db/pull database [:db/id :seon.cluster/name] cluster))
            cluster-id (:db/id cluster-row)
            refuse! (fn [kind message observed]
                      (let [refusal (selection-refusal database kind message observed)]
                        (throw (ex-info message refusal))))
            _ (when-not (:seon.cluster/name cluster-row)
                (refuse! :seon.test/cluster-unavailable "The requested cluster is absent." cluster))
            branch (get-in (db/schema-database database) [:config :branch])
            basis-t (db/basis-t database)
            _ (when (and supplied-basis (> supplied-basis basis-t))
                (refuse! :seon.test/invalid-basis "A comparison basis cannot be in the future." supplied-basis))
            _ (doseq [attribute [:seon.fn/calls :seon.fn/references :seon.test/reach]]
                (let [installed (get (:schema (db/schema-database database)) attribute)]
                  (when-not (and (= :db.type/symbol (:db/valueType installed))
                                 (= :db.cardinality/many (:db/cardinality installed))
                                 (:db/index installed))
                    (refuse! :seon.test/edge-schema-mismatch "Selection requires indexed symbol edges." attribute))))
            namespaces (set requested-namespaces)
            identities (set requested-identities)
            policy (or (:seon.test.run/policy request)
                       (when (or (seq namespaces) (seq identities)) :named) :incremental)
            include-long? (or (= :full policy) (true? (:seon.test/include-long? request)))
            run-ids (selection-read!
                     (db/q '[:find [?run ...] :in $ ?cluster
                             :where [?run :seon.test.run/cluster ?cluster]] database cluster-id))
            source-ids (selection-read!
                        (db/q '[:find [?source ...] :where [?source :seon.source/digest]] database))
            member-ids (selection-read!
                        (db/q '[:find [?member ...] :in $ [?run ...] [?attribute ...]
                                :where [?run ?attribute ?member]]
                              database run-ids [:seon.test.run/members :seon.test.run/covered-by]))
            facts (selection-facts database (concat run-ids source-ids member-ids)
                    [:seon.source/digest :seon.source/test-input-digest
                     :seon.test.run/id :seon.test.run/at :seon.test.run/program-digest
                     :seon.test.run/cluster :seon.test.run/policy
                     :seon.test.run/branch :seon.test.run/tested-branch
                     :seon.test.run/include-long? :seon.test.run/input-digest
                     :seon.test.run/basis-t :seon.test.run/selection-tx
                     :seon.test.run/namespaces :seon.test.run/identities
                     :seon.test.run/members :seon.test.run/covered-by
                     :seon.test.member/symbol :seon.test.member/reasons
                     :seon.test.member/completed-tx :seon.test.member/terminated-tx
                     :seon.test.member/began? :seon.test.member/ended?
                     :seon.test.member/pass-count :seon.test.member/fail-count
                     :seon.test.member/error-count :seon.test.member/error])
            one (fn [entity attribute] (first (get-in facts [entity attribute])))
            sources source-ids
            input-digest (when (= 1 (count sources))
                           (one (first sources) :seon.source/test-input-digest))
            _ (when-not input-digest
                (refuse! :seon.test/input-evidence-unavailable
                         "The publication has no unique external-input identity." (vec sources)))
            _ (when-not (selection-read!
                          (db/q '[:find ?test . :where [?test :seon.test/sym]] database))
                (refuse! :seon.test/population-unknown "No indexed test population is available." :absent))
            runs (->> run-ids
                      (filter #(and (one % :seon.test.run/id)
                                    (= cluster-id (one % :seon.test.run/cluster))
                                    (= branch (one % :seon.test.run/branch))
                                    (or (nil? (one % :seon.test.run/tested-branch))
                                        (= branch (one % :seon.test.run/tested-branch)))
                                    (or (= :platform policy) (not= :platform (one % :seon.test.run/policy)))
                                    (= include-long? (one % :seon.test.run/include-long?))
                                    (= input-digest (one % :seon.test.run/input-digest))
                                    (= namespaces (get-in facts [% :seon.test.run/namespaces] #{}))
                                    (= identities (get-in facts [% :seon.test.run/identities] #{}))
                                    (one % :seon.test.run/selection-tx)))
                      (sort-by #(one % :seon.test.run/selection-tx)))
            members (fn [run] (into (get-in facts [run :seon.test.run/members] #{})
                                   (get-in facts [run :seon.test.run/covered-by] #{})))
            admitted-members (selection-facts (db/history database) runs
                               [:seon.test.run/members :seon.test.run/covered-by])
            _ (doseq [run runs]
                (let [admitted (into (get-in admitted-members [run :seon.test.run/members] #{})
                                     (get-in admitted-members [run :seon.test.run/covered-by] #{}))]
                  (when-not (= admitted (members run))
                    (refuse! :seon.test/population-unknown
                             "Admitted membership refs were removed; coverage is unavailable."
                             (one run :seon.test.run/id)))))
            _ (doseq [run runs member (members run)]
                (when-not (and (one member :seon.test.member/symbol)
                               (seq (get-in facts [member :seon.test.member/reasons])))
                  (refuse! :seon.test/population-unknown "An admitted membership is unavailable." member)))
            complete? (fn [member]
                        (and (one member :seon.test.member/completed-tx)
                             (one member :seon.test.member/terminated-tx)
                             (number? (one member :seon.test.member/pass-count))
                             (number? (one member :seon.test.member/fail-count))
                             (number? (one member :seon.test.member/error-count))))
            green? (fn [member]
                     (and (complete? member)
                          (true? (one member :seon.test.member/began?))
                          (true? (one member :seon.test.member/ended?))
                          (pos? (one member :seon.test.member/pass-count))
                          (zero? (one member :seon.test.member/fail-count))
                          (zero? (one member :seon.test.member/error-count))
                          (not (one member :seon.test.member/error))))
            completed (filter #(and (seq (members %)) (every? complete? (members %))) runs)
            last-green (last (filter #(every? green? (members %)) completed))
            comparison (or supplied-basis (some-> (last completed) (one :seon.test.run/basis-t)))
            removed-files (when comparison
                            (selection-read!
                             (db/q '[:find [?path ...] :in $ $changes
                                     :where [$changes ?file :seon.fn.file/relative-path ?path _ false]
                                            (not-join [?file] [?file :seon.fn.file/relative-path])]
                                   database (db/since (db/history database) comparison))))
            first-run? (and (= :incremental policy) (or (nil? last-green) (seq removed-files)))
            pending (reduce (fn [result run]
                              (reduce (fn [result member]
                                        (let [symbol (one member :seon.test.member/symbol)]
                                          (if (green? member) (dissoc result symbol)
                                            (update result symbol (fnil into #{})
                                                    (get-in facts [member :seon.test.member/reasons])))))
                                      result (members run)))
                            {} (if last-green (drop-while #(<= (one % :seon.test.run/selection-tx)
                                                                (one last-green :seon.test.run/selection-tx)) runs) runs))
            observed-changed (if comparison (changed-definition-symbols database comparison) #{})
            changed (into observed-changed
                          (selection-read! (selection-seeds database (vec (:seon.test/changed request)))))
            schema-changes (when (and comparison (= :incremental policy) (not first-run?))
                             (selection-read!
                              (db/q '[:find [?e ...] :in $ [?attribute ...] :where [?e ?attribute]]
                                    (db/since (db/history database) comparison)
                                    [:seon.schema/key :seon.schema/edn])))
            _ (when (seq schema-changes)
                (refuse! :seon.test/coverage-unknown "Schema changes require proven bounded dependencies." (vec schema-changes)))
            reached (if (seq changed)
                      (selection-read! (functions/gate-sets {:seon.db/db database :seon.fn/seeds changed})) [])
            work? (or first-run? (seq changed) (seq pending))
            reached (set reached)
            candidates
            (if (or first-run? (#{:all :full} policy))
              (selection-read! (db/q '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]] database))
              (into (into (into identities reached) (keys pending))
                    (concat
                     (when (and (= :incremental policy) (not work?))
                       (map #(one % :seon.test.member/symbol) (mapcat members runs)))
                     (when (seq namespaces)
                       (selection-read!
                        (db/q '[:find [?symbol ...] :in $ [?name ...]
                                :where [?ns :seon.ns/name ?name]
                                       [?test :seon.test/ns ?ns] [?test :seon.test/sym ?symbol]]
                              database namespaces)))
                     (when (or work? (= :platform policy))
                       (selection-read!
                        (db/q '[:find [?symbol ...]
                                :where [?test :seon.test/platform] [?test :seon.test/sym ?symbol]] database))))))
            tests (into {} (selection-read!
                            (db/q '[:find ?symbol ?entity :in $ [?symbol ...]
                                    :where [?entity :seon.test/sym ?symbol]] database candidates)))
            candidate-data (selection-facts database (vec (vals tests))
                             [:seon.test/sym :seon.test/ns :seon.fn/file
                              :seon.schema.admission/source :seon.program/analyzed-source-digest
                              :seon.test/fixture :seon.test/fixture-observation
                              :seon.test/long :seon.test/platform])
            related-ids (into #{} (mapcat (fn [row]
                                           (concat (:seon.test/ns row) (:seon.fn/file row))))
                              (vals candidate-data))
            candidate-data (merge candidate-data
                                  (selection-facts database (vec related-ids)
                                    [:seon.ns/name :seon.fn.file/relative-root]))
            one (fn [entity attribute]
                  (first (get (or (get candidate-data entity) (get facts entity)) attribute)))
            _ (doseq [entity (vals tests)]
                (when-not (one entity :seon.program/analyzed-source-digest)
                  (refuse! :seon.test/analysis-unknown "Program analysis provenance is absent."
                           (or (one entity :seon.fn/sym) (one entity :seon.test/sym)))))
            named? (fn [[symbol entity]]
                     (or (identities symbol)
                         (namespaces (one (one entity :seon.test/ns) :seon.ns/name))))
            excluded? (fn [[_ entity]]
                        (or (one entity :seon.test/fixture)
                            (one entity :seon.test/fixture-observation)))
            _ (doseq [entry tests :when (and (named? entry) (excluded? entry))]
                (refuse! :seon.test/fixture-excluded "Explicit fixture material is not gate membership." (first entry)))
            eligible (into {}
                       (filter (fn [[_ entity :as entry]]
                                 (and (not (excluded? entry))
                                      (or (= :agent (one entity :seon.schema.admission/source))
                                          (= "test" (one (one entity :seon.fn/file) :seon.fn.file/relative-root))
                                          (named? entry))
                                      (or include-long? (identities (first entry))
                                          (not (one entity :seon.test/long)))
                                      (or (not= :named policy) (named? entry))))) tests)
            _ (doseq [symbol identities :when (not (get eligible symbol))]
                (refuse! :seon.test/identity-unresolved "The requested test is not eligible." symbol))
            _ (doseq [ns-symbol namespaces
                      :when (not-any? #(= ns-symbol (one (one % :seon.test/ns) :seon.ns/name)) (vals tests))]
                (refuse! :seon.test/namespace-unresolved "The requested namespace has no eligible tests." ns-symbol))
            long-excluded
            (into [] (keep (fn [[test-symbol entity]]
                             (when (and (not include-long?) (not (identities test-symbol))
                                        (not (excluded? [test-symbol entity])) (one entity :seon.test/long))
                               {:seon.test/sym test-symbol :seon.test/long (one entity :seon.test/long)
                                :seon.test/command ["bin/test-check" (:seon.cluster/name cluster-row)
                                                    "--test" (str test-symbol)]}))) tests)
            reasons (reduce-kv
                     (fn [result symbol entity]
                       (let [entry [symbol entity]
                             reasons (cond-> (if (= :incremental policy) (get pending symbol #{}) #{})
                                       (and first-run? (not= :platform policy)) (conj :first-run)
                                       (and (not= :platform policy) (reached symbol)) (conj :reaches-changed)
                                       (or (named? entry) (#{:all :full} policy)) (conj :named)
                                       (and (or work? (#{:platform :all :full} policy)) (one entity :seon.test/platform)) (conj :platform))]
                         (if (seq reasons) (assoc result symbol reasons) result)))
                     (sorted-map) eligible)
            digest (selection-read! (runner/program-digest database))
            latest (reduce
                    (fn [result run-eid]
                      (reduce (fn [result member]
                                (assoc result (one member :seon.test.member/symbol) [run-eid member]))
                              result (get-in facts [run-eid :seon.test.run/members])))
                    {} (sort-by #(one % :seon.test.run/selection-tx)
                                (filter #(and (= branch (one % :seon.test.run/branch))
                                              (not (one % :seon.test.run/tested-branch))
                                              (= input-digest (one % :seon.test.run/input-digest))
                                              (one % :seon.test.run/selection-tx)) run-ids)))
            reuse-candidates (if (and (= :incremental policy) (not work?)) eligible reasons)
            changed-program-candidates
            (into {} (keep (fn [[test-symbol _]]
                             (when-let [[run-eid member] (get latest test-symbol)]
                               (when (and (green? member)
                                          (not= digest (one run-eid :seon.test.run/program-digest)))
                                 [test-symbol (one run-eid :seon.test.run/basis-t)]))))
                  reuse-candidates)
            current-reach (when (seq changed-program-candidates)
                            (selection-read! (runner/reach-digests database (vec (keys changed-program-candidates)))))
            recorded-reach
            (reduce-kv (fn [result tested-basis entries]
                         (merge result
                                (selection-read!
                                 (runner/reach-digests (db/as-of database tested-basis)
                                                       (mapv first entries)))))
                       {} (group-by val changed-program-candidates))
            reused (into (sorted-map)
                         (keep (fn [[test-symbol _]]
                                 (when-let [[run-eid member] (get latest test-symbol)]
                                   (when (and (green? member)
                                              (or (nil? supplied-basis) (= supplied-basis (one run-eid :seon.test.run/basis-t)))
                                              (not (reached test-symbol))
                                              (or (= digest (one run-eid :seon.test.run/program-digest))
                                                  (and (string? (get current-reach test-symbol))
                                                       (= (get current-reach test-symbol)
                                                          (get recorded-reach test-symbol)))))
                                     [test-symbol
                                      {:seon.test/sym test-symbol :seon.test/unchanged true
                                       :seon.test/run-basis-t (one run-eid :seon.test.run/basis-t)
                                       :seon.test.run/basis-t (one run-eid :seon.test.run/basis-t)
                                       :seon.test.run/program-digest (one run-eid :seon.test.run/program-digest)
                                       :seon.test.run/input-digest input-digest
                                       :seon.test/run-at (one run-eid :seon.test.run/at)
                                       :seon.test/run [:seon.test.run/id (one run-eid :seon.test.run/id)]
                                       :seon.test/recorded-basis-t (one member :seon.test.member/completed-tx)
                                       :seon.test/pass-count (one member :seon.test.member/pass-count)
                                       :seon.test/fail-count 0 :seon.test/error-count 0}]))))
                         reuse-candidates)
            reasons (apply dissoc reasons (keys reused))]
        (cond-> {:seon.test.run/basis-t basis-t
                 :seon.test.run/cluster cluster-id
                 :seon.test.run/policy policy
                 :seon.test.run/include-long? include-long?
                 :seon.test.run/input-digest input-digest
                 :seon.test.run/members (mapv (fn [[symbol reasons]]
                                               {:seon.test/sym symbol :seon.test.member/reasons reasons}) reasons)}
          (seq reused) (assoc :seon.test.selection/unchanged (vec (vals reused)))
          (seq long-excluded) (assoc :seon.test/long-excluded long-excluded)
          first-run? (assoc :seon.test.selection/widenings
                            [(cond (seq removed-files) :removed-file
                                   (some #(and (= cluster-id (one % :seon.test.run/cluster))
                                               (= :incremental (one % :seon.test.run/policy))
                                               (one % :seon.test.run/selection-tx)
                                               (not= input-digest (one % :seon.test.run/input-digest)))
                                         run-ids) :outside-program-graph
                                   :else :missing-basis)])
          (seq removed-files) (assoc :seon.test.selection/removed (vec (sort removed-files)))
          comparison (assoc :seon.test.run/change-basis-t comparison)
          (seq namespaces) (assoc :seon.test.run/namespaces namespaces)
          (seq identities) (assoc :seon.test.run/identities identities))))
    (catch clojure.lang.ExceptionInfo failure
      (let [refusal (ex-data failure)]
        (if (or (:seon.test/selection-refusal refusal)
                (:seon.test/unknown refusal)
                (:seon.test.run/unavailable refusal)
                (:seon.db/invalid-read refusal)
                (:seon.schema/missing-projection refusal))
          refusal
          (throw failure))))))

(defn reaching
  "Return tests reaching explicit changed definitions through the same union graph owner."
  {:malli/schema [:=> [:cat :seon.test/reaching-request]
                  [:or [:vector :seon.test/sym] :seon.test/selection-error :seon.test/unknown-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db changed :seon.test/changed}]
  (let [seeds (selection-seeds database changed)]
    (if (and (map? seeds)
             (contains? seeds :seon.error/at)
             (contains? seeds :seon.error/layer)
             (contains? seeds :seon.error/operation)) seeds
      (functions/gate-sets {:seon.db/db database :seon.fn/seeds seeds}))))

(defn selection-admission
  "Prepare the same complete admission for either execution host, before any body starts."
  {:malli/schema [:=> [:cat :seon.test.selection/request]
                  [:or :seon.test.run/admission :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db :as request}]
  (let [selected (select request)]
    (if (and (map? selected)
             (contains? selected :seon.error/at)
             (contains? selected :seon.error/layer)
             (contains? selected :seon.error/operation)) selected
      (let [provenance (runner/provenance database)]
        (if (and (map? provenance)
                 (contains? provenance :seon.error/at)
                 (contains? provenance :seon.error/layer)
                 (contains? provenance :seon.error/operation)) provenance
          (-> selected
              (dissoc :seon.test.run/basis-t)
              (assoc :seon.test.run/provenance provenance)
              (update :seon.test.run/members
                      (fn [members]
                        (mapv (fn [member]
                                {:seon.test.member/symbol (:seon.test/sym member)
                                 :seon.test.member/reasons (:seon.test.member/reasons member)}) members)))))))))

(defn check-request-admission
  "Admit the in-process host's immutable selection request through the shared owner."
  {:malli/schema [:=> [:cat :seon.test.selection/request]
                  [:or :seon.test.run/admission :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [request]
  (selection-admission request))

(defn- admission-refusal!
  {:malli/schema [:=> [:cat :keyword :seon.test.run/id :seon.schema/value :seon.schema/value] :nil]}
  [kind run-id expected offending]
  (let [failure
        (assoc (error/diagnostic
         {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/admission
          :seon.error/operation 'seon.test/admit-run
          :seon.error/message "Test run admission refused inconsistent evidence."
          :seon.error/diagnostic-layer :test
          :seon.error/diagnostic-operation :seon.test/admit-run
          :seon.error/diagnostic-member run-id
          :seon.error/diagnostic-expected expected
          :seon.error/diagnostic-offending offending
          :seon.error/diagnostic-cause kind
          :seon.error/diagnostic-evidence {:seon.test.run/id run-id}}) :seon.test/admission-refusal kind)]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- admission-members
  {:malli/schema [:=> [:cat :seon.db/database-value :int] [:vector [:map-of :keyword :seon.schema/value]]]}
  [database run-eid]
  ;; Query refs rather than pulling the parent's cardinality-many collection:
  ;; Datahike's default pull limit must not shorten an execution obligation.
  (let [run-id (selection-read!
                (db/q '[:find ?id . :in $ ?run :where [?run :seon.test.run/id ?id]]
                      database run-eid))
        ids (db/q '[:find [?member ...] :in $ ?run [?attribute ...]
                    :where [?run ?attribute ?member]]
                  database run-eid
                  [:seon.test.run/members :seon.test.run/covered-by])]
    (when (and (map? ids)
               (contains? ids :seon.error/at)
               (contains? ids :seon.error/layer)
               (contains? ids :seon.error/operation))
      (throw (ex-info (:seon.error/message ids) ids)))
    (mapv #(let [row (db/pull database
                             [:db/id :seon.test.member/symbol :seon.test.member/reasons] %)]
             (when (or (and (map? row)
                            (contains? row :seon.error/at)
                            (contains? row :seon.error/layer)
                            (contains? row :seon.error/operation))
                       (not (:seon.test.member/symbol row))
                       (not (seq (:seon.test.member/reasons row))))
               (admission-refusal! :seon.test/population-unknown run-id
                                   :admitted-member (or row :seon.error/unknown)))
             row)
          ids)))

(defn- admission-scope
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/value]] [:map-of :keyword :seon.schema/value]]}
  [row]
  (cond-> (-> (select-keys row [:seon.test.run/cluster :seon.test.run/program-digest
                       :seon.test.run/input-digest :seon.test.run/branch
                       :seon.test.run/tested-branch :seon.test.run/change-basis-t
                       :seon.test.run/policy :seon.test.run/include-long?])
      (assoc :seon.test.run/namespaces (set (:seon.test.run/namespaces row))
             :seon.test.run/identities (set (:seon.test.run/identities row))))
    (= :named (:seon.test.run/policy row))
    (assoc :seon.test.run/basis-t (:seon.test.run/basis-t row))))

(defn- admission-member-values
  {:malli/schema [:=> [:cat [:sequential [:map-of :keyword :seon.schema/value]]] [:set [:map-of :keyword :seon.schema/value]]]}
  [members]
  (into #{} (map #(-> (select-keys % [:seon.test.member/symbol
                                     :seon.test.member/reasons])
                      (update :seon.test.member/reasons set))) members))

(defn admit-run
  "Reserve a supplied selection at the writer, before any test executes.

  Invoke with [:db.fn/call seon.test/admit-run request]. The request
  carries the publisher's input digest and immutable tested provenance;
  selection is handed as immutable input. This function executes no test bodies. It checks the current program and custody,
  then reserves only members not already covered by matching admitted runs.
  Concurrent requests see earlier reservations in the transaction database.
  An identical replay emits no evidence datoms; a changed replay refuses.

  This entry admits the primary host's own branch. Isolated snapshot custody
  requires its separate immutable publication handoff before admission."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/admission]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance
             cluster :seon.test.run/cluster
             members :seon.test.run/members :as request}]
  (let [run-id (:seon.test.run/id run)
        cluster-row (db/pull database [:db/id :seon.cluster/name] cluster)
        cluster-id (:db/id cluster-row)
        branch (get-in (db/schema-database database) [:config :branch])
        digest (runner/program-digest database)
        row (-> (merge (select-keys request
                                   [:seon.test.run/cluster :seon.test.run/input-digest
                                    :seon.test.run/policy :seon.test.run/include-long?
                                    :seon.test.run/deadline
                                    :seon.test.run/exclusions
                                    :seon.test.run/change-basis-t :seon.test.run/namespaces
                                    :seon.test.run/identities])
                       (select-keys run
                                    [:seon.test.run/id :seon.test.run/at :seon.test.run/git-sha
                                     :seon.test.run/program-digest :seon.test.run/basis-t
                                     :seon.test.run/callers-at-head
                                     :seon.test.run/branch :seon.test.run/tested-branch]))
                (assoc :seon.test.run/cluster cluster-id))
        selector (into [:db/id :seon.test.run/selection-tx
                        [:seon.test.run/callers-at-head :limit nil]
                        [:seon.test.run/namespaces :limit nil]
                        [:seon.test.run/exclusions :limit nil]
                        [:seon.test.run/identities :limit nil]]
                       (keys (dissoc row :seon.test.run/members
                                     :seon.test.run/namespaces :seon.test.run/identities
                                     :seon.test.run/callers-at-head
                                     :seon.test.run/exclusions)))
        previous (db/pull database selector [:seon.test.run/id run-id])]
    (doseq [read-result [cluster-row previous]]
      (when (and (map? read-result)
                 (contains? read-result :seon.error/at)
                 (contains? read-result :seon.error/layer)
                 (contains? read-result :seon.error/operation))
        (throw (ex-info (:seon.error/message read-result) read-result))))
    (when-not (:seon.cluster/name cluster-row)
      (admission-refusal! :seon.test/cluster-unavailable run-id
                          :explicit-authority-cluster cluster))
    (when (or (not= branch (:seon.test.run/branch run))
              (:seon.test.run/tested-branch run))
      (admission-refusal! :seon.test/cluster-mismatch run-id branch
                          (select-keys run [:seon.test.run/branch
                                            :seon.test.run/tested-branch])))
    (when (or (and (map? digest)
                   (contains? digest :seon.error/at)
                   (contains? digest :seon.error/layer)
                   (contains? digest :seon.error/operation))
              (not= digest (:seon.test.run/program-digest run)))
      (admission-refusal! :seon.test/program-mismatch run-id digest
                          (:seon.test.run/program-digest run)))
    (when (or (> (:seon.test.run/basis-t run) (db/basis-t database))
              (> (get request :seon.test.run/change-basis-t 0)
                 (:seon.test.run/basis-t run)))
      (admission-refusal! :seon.test/invalid-basis run-id
                          (db/basis-t database)
                          (select-keys row [:seon.test.run/basis-t
                                            :seon.test.run/change-basis-t])))
    (when (seq (changed-definition-symbols database (:seon.test.run/basis-t run)))
      (admission-refusal! :seon.test/program-mismatch run-id
                          :unchanged-tested-program :program-changed-after-tested-basis))
    (let [inputs (selection-read!
                  (db/q '[:find [?digest ...] :where [_ :seon.source/test-input-digest ?digest]] database))]
      (when-not (= 1 (count inputs))
        (admission-refusal! :seon.test/input-evidence-unavailable run-id :one-publication-input-digest inputs))
      (when-not (= (first inputs) (:seon.test.run/input-digest request))
        (admission-refusal! :seon.test/program-mismatch run-id (first inputs) (:seon.test.run/input-digest request))))
    (when (not= (count members) (count (set (map :seon.test.member/symbol members))))
      (admission-refusal! :seon.test.run/immutable run-id
                          :one-membership-per-symbol members))
    (if (:db/id previous)
      (let [prior (-> (dissoc previous :db/id :seon.test.run/selection-tx)
                      (assoc :seon.test.run/cluster
                             (get-in previous [:seon.test.run/cluster :db/id])))
            normalize #(-> %
                           (cond-> (:seon.test.run/callers-at-head %)
                             (update :seon.test.run/callers-at-head set))
                           (update :seon.test.run/exclusions
                                   (fn [rows] (set (map (fn [row] (dissoc row :db/id)) rows))))
                           (dissoc :seon.test.run/namespaces :seon.test.run/identities)
                           (merge (select-keys (admission-scope %)
                                              [:seon.test.run/namespaces
                                               :seon.test.run/identities])))]
        (when (or (not (:seon.test.run/selection-tx previous))
                  (not= (normalize row) (normalize prior))
                  (not= (admission-member-values members)
                        (admission-member-values
                         (admission-members database (:db/id previous)))))
          (admission-refusal! :seon.test.run/immutable run-id row prior))
        [])
      (let [runs (db/q '[:find [?run ...] :in $ ?cluster ?digest ?inputs
                         :where [?run :seon.test.run/cluster ?cluster]
                                [?run :seon.test.run/program-digest ?digest]
                                [?run :seon.test.run/input-digest ?inputs]
                                [?run :seon.test.run/selection-tx]]
                       database cluster-id digest (:seon.test.run/input-digest row))
            _ (when (and (map? runs)
                         (contains? runs :seon.error/at)
                         (contains? runs :seon.error/layer)
                         (contains? runs :seon.error/operation))
                (throw (ex-info (:seon.error/message runs) runs)))
            existing
            (into {}
                  (mapcat (fn [run-eid]
                            (let [candidate (db/pull database selector run-eid)
                                  _ (when (and (map? candidate)
                                               (contains? candidate :seon.error/at)
                                               (contains? candidate :seon.error/layer)
                                               (contains? candidate :seon.error/operation))
                                      (throw (ex-info (:seon.error/message candidate) candidate)))
                                  candidate (assoc candidate :seon.test.run/cluster
                                                   (get-in candidate [:seon.test.run/cluster :db/id]))]
                              (when (= (admission-scope row) (admission-scope candidate))
                                (map (juxt :seon.test.member/symbol identity)
                                     (admission-members database run-eid))))))
                  (sort > runs))
            covered (keep #(get existing (:seon.test.member/symbol %)) members)
            reserved (remove #(get existing (:seon.test.member/symbol %)) members)]
        [(cond-> (assoc row :seon.test.run/selection-tx "datomic.tx")
           (seq reserved) (assoc :seon.test.run/members (vec reserved))
           (seq covered) (assoc :seon.test.run/covered-by (set (map :db/id covered))))]))))

(defn reach-digest
  "Digest this test's source, transitively reached definitions and named schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or :seon.test/reach-digest :seon.error/value]]}
  [database test-symbol]
  (let [result (runner/reach-digests database [test-symbol])]
    (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation)) result (get result test-symbol))))

(defn- stale-in [database test-symbols]
  (let [rows (db/pull-many database
               [:seon.test/sym :seon.test/reach-digest :seon.test/run
                :seon.test/fixture-observation]
               (mapv #(vector :seon.test/sym %) test-symbols))
        eligible (when-not (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation))
                   (into [] (keep #(when (and (:seon.test/run %)
                                             (:seon.test/reach-digest %)
                                             (not (:seon.test/fixture-observation %)))
                                     (:seon.test/sym %))) rows))
        digests (if (seq eligible) (runner/reach-digests database eligible) {})]
    (cond
      (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation)) rows
      (and (map? digests) (contains? digests :seon.error/at) (contains? digests :seon.error/layer) (contains? digests :seon.error/operation)) digests
      :else (into [] (keep (fn [row]
                            (let [s (:seon.test/sym row)]
                              (when (or (:seon.test/fixture-observation row)
                                        (not (:seon.test/run row))
                                        (not (:seon.test/reach-digest row))
                                        (not= (get digests s) (:seon.test/reach-digest row)))
                                s)))) rows))))

(defn stale
  "Source-bearing tests with no result or a changed reach digest.
  Declared fixture observations are always stale. The named arity answers
  for exactly the supplied tests, so a caller holding its own set never
  digests the whole population to learn which of them must run again."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value]
     [:or [:vector :seon.test/sym] :seon.error/value]]
    [:=> [:cat :seon.db/database-value [:sequential :seon.test/sym]]
     [:or [:vector :seon.test/sym] :seon.error/value]]]}
  ([database]
   (let [symbols (db/q '[:find [?s ...] :where
                         [?t :seon.test/sym ?s] [?t :seon.test/source]] database)]
     (if (and (map? symbols) (contains? symbols :seon.error/at) (contains? symbols :seon.error/layer) (contains? symbols :seon.error/operation)) symbols
         (stale-in database (vec (sort symbols))))))
  ([database test-symbols]
   (if (empty? test-symbols)
     []
     (stale-in database (vec (sort (distinct test-symbols)))))))

(defn- commands [paths namespaces]
  [(into (into ["bin/test"] (when (seq paths) (into ["--paths"] paths)))
         (when (seq namespaces) (into ["--"] (map str namespaces))))
   (into (into ["bin/test"] (when (seq paths) (into ["--paths"] paths)))
         ["--platform"])])

(defn resolve-test
  "Resolve an admitted test in the host or acquired SCI program by provenance."
  {:malli/schema [:=> [:cat :seon.test/resolution-request]
                  [:or :seon.test/var :seon.test/resolution-error :seon.test/not-runnable-error :seon.test.run/unavailable-error :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db test-symbol :seon.test/identity
    ctx :seon.sci.eval/ctx loader :seon.test/class-loader
    projection :seon.schema/projection}]
  (let [refuse (fn [kind message observed]
                 (assoc (error/diagnostic
                  {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/resolution
                   :seon.error/operation 'seon.test/resolve-test :seon.error/message message
                   :seon.error/diagnostic-layer :test-resolution
                   :seon.error/diagnostic-operation 'seon.test/resolve-test
                   :seon.error/diagnostic-member test-symbol
                   :seon.error/diagnostic-expected :admitted-executable-test
                   :seon.error/diagnostic-offending observed
                   :seon.error/diagnostic-cause kind
                   :seon.error/diagnostic-evidence
                   {:seon.test.run/basis-t (db/basis-t database)}}) :seon.test/resolution-refusal kind))
        row (db/pull database
                     '[:db/id :seon.test/source :seon.schema.admission/source
                       :seon.program/analyzed-source-digest :seon.fn/file
                       {:seon.test/ns [:seon.ns/name]}]
                     [:seon.test/sym test-symbol])
        acquired (sci.eval/acquired-program ctx)
        acquired-db (:seon.db/db acquired)
        wanted (runner/program-digest database)
        actual (when acquired-db (runner/program-digest acquired-db))
        source (:seon.test/source row)
        admission (:seon.schema.admission/source row)]
    (cond
      (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
      (not (:db/id row))
      (refuse :seon.test/identity-unresolved "The admitted test has no current row." test-symbol)
      (or (not (string? source))
          (not= (symbol (namespace test-symbol))
                (get-in row [:seon.test/ns :seon.ns/name]))
          ;; Analysis covers the resolver context, not just declaration text.
          ;; Acquired program equality below verifies the complete admitted facts.
          (nil? (:seon.program/analyzed-source-digest row)))
      (refuse :seon.test/provenance-unknown "The test lacks matching source, namespace or analysis evidence." row)
      (and (map? wanted) (contains? wanted :seon.error/at) (contains? wanted :seon.error/layer) (contains? wanted :seon.error/operation)) wanted
      (and (map? actual) (contains? actual :seon.error/at) (contains? actual :seon.error/layer) (contains? actual :seon.error/operation)) actual
      (not= wanted actual)
      (refuse :seon.test/program-mismatch "The SCI context did not acquire the tested program."
              {:seon.test.run/program-digest wanted :seon.test/acquired-digest actual})
      (seq (:seon.test/acquisition-refusals acquired))
      (first (:seon.test/acquisition-refusals acquired))
      (not (or (= :agent admission) (and (= :core admission) (:seon.fn/file row))))
      (refuse :seon.test/provenance-unknown "The test has no executable admission provenance." row)
      :else
      (try
        (let [test-var (schema/call-with-projection
                        projection
                        #(if (= :agent admission)
                           (sci/resolve ctx test-symbol)
                           (with-test-loader loader (fn [] (requiring-resolve test-symbol)))))]
          (if (and (runner/var-reference? test-var) (ifn? (:test (meta test-var))))
            test-var
            {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/resolution
             :seon.error/operation 'seon.test/resolve-test
             :seon.test/not-runnable (str test-symbol)
             :seon.error/message "The admitted identity has no executable test Var."}))
        (catch LinkageError failure
          (refuse :seon.test/classpath-incompatible
                  (or (ex-message failure) (.getName (class failure))) test-symbol))
        (catch Exception failure
          (refuse :seon.test/classpath-unavailable
                  (or (ex-message failure) (.getName (class failure))) test-symbol))))))

(defn- prepare-tests! [database connection request]
  (let [context (:my.program/context request)
        loader (or (:seon.test/class-loader request)
                   (some-> (:my.program/base-ctx context) sci.eval/acquired-program
                           :seon.test/class-loader)
                   (clojure.lang.RT/baseLoader))
        ctx (or (:my.program/base-ctx context)
                (with-test-loader loader #(sci.eval/cluster-ctx database connection)))]
    (if (and (map? ctx) (contains? ctx :seon.error/at) (contains? ctx :seon.error/layer) (contains? ctx :seon.error/operation))
      ctx
      {:seon.db/db database :seon.db/connection connection
       :seon.schema/projection (schema/projection-from-database database)
       :seon.test/class-loader loader :seon.sci.eval/ctx ctx})))

(defn- relative-path [path]
  (let [root (.toPath (.getCanonicalFile (io/file ".")))
        file (.toPath (.getCanonicalFile (io/file path)))]
    (str (.relativize root file))))

(defn check-admission
  "Prepare check admission through the shared selector, without reading files."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/check-request]
                  [:or :seon.test.run/admission :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database {changed :seon.test/changed paths :seon.test/paths
             cluster :seon.boot/cluster-name :as request}]
  (let [file-symbols (when (seq paths)
                       (db/q '[:find [?symbol ...] :in $ [?path ...]
                               :where [?file :seon.fn.file/relative-path ?path]
                                      [?entity :seon.fn/file ?file]
                                      (or [?entity :seon.fn/sym ?symbol]
                                          [?entity :seon.test/sym ?symbol])]
                             database (mapv relative-path paths)))
        seeds (when-not (and (map? file-symbols)
                             (contains? file-symbols :seon.error/at)
                             (contains? file-symbols :seon.error/layer)
                             (contains? file-symbols :seon.error/operation))
                (vec (concat changed file-symbols)))]
    (if (and (map? file-symbols)
             (contains? file-symbols :seon.error/at)
             (contains? file-symbols :seon.error/layer)
             (contains? file-symbols :seon.error/operation)) file-symbols
      (check-request-admission (cond-> {:seon.db/db database
                       :seon.test/namespaces (set (:seon.test/namespaces request))
                       :seon.test/changed seeds
                       :seon.test/include-long? (true? (:seon.test/include-long? request))}
                cluster (assoc :seon.test.run/cluster [:seon.cluster/name cluster])
                (:seon.test.run/cluster request) (assoc :seon.test.run/cluster (:seon.test.run/cluster request))
                (:seon.test.run/change-basis-t request)
                (assoc :seon.test.run/change-basis-t (:seon.test.run/change-basis-t request)))))))

(defn- check-in-process
  [{connection :seon.db/connection changed :seon.test/changed
    paths :seon.test/paths namespaces :seon.test/namespaces
    cluster :seon.boot/cluster-name defer? :seon.test/defer-widened?
    :as request}
   progress]
  (let [started (System/nanoTime)
        database (db/db connection)
        selection (check-admission database request)
        effective (when-not (and (map? selection)
                                 (contains? selection :seon.error/at)
                                 (contains? selection :seon.error/layer)
                                 (contains? selection :seon.error/operation)) (config/effective database cluster))
        paths (mapv relative-path paths)
        widened (when (some #((:seon.test.member/reasons %) :first-run)
                            (:seon.test.run/members selection)) ["first run for these gate inputs"])
        selected (if (and (map? selection)
                          (contains? selection :seon.error/at)
                          (contains? selection :seon.error/layer)
                          (contains? selection :seon.error/operation)) selection
                   (mapv :seon.test.member/symbol (:seon.test.run/members selection)))
        deferred (when-not (and (map? selected)
                                (contains? selected :seon.error/at)
                                (contains? selected :seon.error/layer)
                                (contains? selected :seon.error/operation))
                   (into []
                         (keep (fn [test-symbol]
                                 (when-let [reason (:seon.test/fixture-observation
                                                   (db/pull database [:seon.test/fixture-observation]
                                                            [:seon.test/sym test-symbol]))]
                                   {:seon.test/sym test-symbol
                                    :seon.test/fixture-observation reason
                                    :seon.test/command ["bin/test-check" (or cluster "default")
                                                        "--test" test-symbol]})))
                         selected))
        long-excluded (:seon.test/long-excluded selection)
        ;; A test reaching a declared destructive owner never runs in the
        ;; development JVM: the same rule seon.test/run enforces per Var, applied
        ;; to the whole selection so the exclusion is reported, never silent.
        ;; The declaration this check genuinely holds travels with every run it
        ;; starts: a seam that re-read the JVM property would decide twice.
        declared (if-let [entry (find request :seon.test/declared-root)]
                   (val entry)
                   (store/declared-operator-root))
        destructive (when-not (and (map? selected)
                                   (contains? selected :seon.error/at)
                                   (contains? selected :seon.error/layer)
                                   (contains? selected :seon.error/operation))
                      (let [owners (when (development-root declared)
                                     (destroyers database))
                            reach (cond
                                    (nil? owners) nil
                                    (and (map? owners) (contains? owners :seon.error/at) (contains? owners :seon.error/layer) (contains? owners :seon.error/operation)) owners
                                    :else (destructive-reach database))]
                        (cond
                          (nil? reach) []
                          (and (map? reach) (contains? reach :seon.error/at) (contains? reach :seon.error/layer) (contains? reach :seon.error/operation)) reach
                          :else (into []
                                      (keep #(destructive-exclusion database owners reach %))
                                      selected))))
        excluded (if (vector? destructive)
                   (set (map :seon.test/sym destructive))
                   #{})
        runnable (if (or (and (map? selected)
                              (contains? selected :seon.error/at)
                              (contains? selected :seon.error/layer)
                              (contains? selected :seon.error/operation)) (and (map? destructive)
                                                      (contains? destructive :seon.error/at)
                                                      (contains? destructive :seon.error/layer)
                                                      (contains? destructive :seon.error/operation))
                         (and widened defer?)) []
                     (filterv (complement (-> excluded
                                              (into (map :seon.test/sym) deferred)
                                              (into (map :seon.test/sym) long-excluded)))
                              selected))
        provenance (:seon.test.run/provenance selection)
        admitted (when (and (not (and (map? selected)
                                      (contains? selected :seon.error/at)
                                      (contains? selected :seon.error/layer)
                                      (contains? selected :seon.error/operation)))
                            (not (and (map? destructive)
                                      (contains? destructive :seon.error/at)
                                      (contains? destructive :seon.error/layer)
                                      (contains? destructive :seon.error/operation)))
                            (not (and (map? effective)
                                      (contains? effective :seon.error/at)
                                      (contains? effective :seon.error/layer)
                                      (contains? effective :seon.error/operation))))
                   (db/transact! connection [[:db.fn/call admit-run
                       (cond-> (assoc selection :seon.test.run/members
                                      (filterv #((set runnable) (:seon.test.member/symbol %))
                                               (:seon.test.run/members selection)))
                         (or (seq long-excluded) (seq excluded) (seq deferred) (and widened defer?))
                         (assoc :seon.test.run/exclusions
                                (vec (concat
                                      (map (fn [entry] {:seon.test.member/symbol (:seon.test/sym entry)
                                                        :seon.test.selection/disposition :long}) long-excluded)
                                      (for [test-symbol selected :when (not ((set runnable) test-symbol))]
                                        {:seon.test.member/symbol test-symbol
                                         :seon.test.selection/disposition
                                         (if (excluded test-symbol) :destructive :deferred)})))))]]))]
    (cond
      (and (map? effective)
           (contains? effective :seon.error/at)
           (contains? effective :seon.error/layer)
           (contains? effective :seon.error/operation)) effective
      (and (map? admitted)
           (contains? admitted :seon.error/at)
           (contains? admitted :seon.error/layer)
           (contains? admitted :seon.error/operation)) admitted
      (and (map? provenance)
           (contains? provenance :seon.error/at)
           (contains? provenance :seon.error/layer)
           (contains? provenance :seon.error/operation)) provenance
      (and (map? selected)
           (contains? selected :seon.error/at)
           (contains? selected :seon.error/layer)
           (contains? selected :seon.error/operation)) selected
      (and (map? destructive)
           (contains? destructive :seon.error/at)
           (contains? destructive :seon.error/layer)
           (contains? destructive :seon.error/operation)) (assoc destructive :seon.test/next-tier :none)
      :else
      (let [selected (vec (sort selected))
            namespaces (vec (sort (distinct (or (seq namespaces)
                                                (map #(symbol (namespace (symbol %))) selected)))))
            deadline (+ started (* 1000000 (:seon.test/check-time-limit-ms effective)))
            initial (cond-> {:seon.test/tests [] :seon.test/passed [] :seon.test/failed []
                             :seon.test/results (vec (:seon.test.selection/unchanged selection))
                             :seon.test.run/basis-t (db/basis-t database)
                             :seon.test/next-tier (commands paths namespaces)}
                      (nil? changed) (assoc :seon.test/skipped-count
                                           (count (:seon.test.selection/unchanged selection))
                                           :seon.test/skip-reason "recorded result covers unchanged program facts")
                      (seq deferred) (assoc :seon.test/deferred deferred)
                      (seq destructive) (assoc :seon.test/destructive-excluded destructive)
                      (seq long-excluded) (assoc :seon.test/long-excluded long-excluded)
                      provenance (assoc :seon.test.run/program-digest
                                        (:seon.test.run/program-digest provenance))
                      widened (assoc :seon.test/widened
                                     (str "the reaching set cannot bound this change: "
                                          (str/join ", " widened))))
            _ (swap! progress assoc :seon.test/recorded initial :seon.test/pending (vec runnable))
            _ (when (and (seq runnable)
                         (some (set (functions/tests-reaching database 'seon.test-support/with-database)) runnable))
                (swap! progress assoc :seon.test/progress "canonical fixture preparation")
                ;; Realize the fixture owner's one base before starting a Var's
                ;; event backstop. The total check deadline still applies.
                ;; `seon.test-support` is reachable only under the test
                ;; loader — the same deliberate late dependency as
                ;; `event-backstop-ms`.
                (with-test-loader
                  #(deref @(requiring-resolve 'seon.test-support/database-base))))
            prepared (when (seq runnable)
                       (swap! progress assoc :seon.test/progress "test namespace loading and contract arming")
                       (prepare-tests! database connection request))
            result
            (if (and (map? prepared) (contains? prepared :seon.error/at) (contains? prepared :seon.error/layer) (contains? prepared :seon.error/operation))
              (assoc prepared :seon.test/next-tier :none)
            (loop [remaining (sort-by (fn [test-symbol]
                                           [(if (some #(and (= test-symbol (:seon.test.member/symbol %))
                                                            ((:seon.test.member/reasons %) :platform))
                                                      (:seon.test.run/members selection)) 0 1)
                                            test-symbol]) runnable) result initial]
              (if-let [test-symbol (first remaining)]
                (let [_ (swap! progress assoc :seon.test/progress test-symbol
                               :seon.test/pending (vec remaining))
                      remaining-ms (quot (- deadline (System/nanoTime)) 1000000)]
                  (if-not (pos? remaining-ms)
                    (-> result
                        (assoc :seon.test/next-tier :none :seon.test/pending (vec remaining))
                        (update :seon.test/failed conj
                                {:seon.test/sym test-symbol :seon.test/changed changed
                                 :seon.test/failure-message
                                 "Total :seon.test/check-time-limit-ms bound fired before this test."}))
                    (let [outcome (try
                                    (let [test-var (resolve-test
                                                    (assoc prepared :seon.test/identity
                                                           (symbol test-symbol)))]
                                      (if (and (map? test-var) (contains? test-var :seon.error/at) (contains? test-var :seon.error/layer) (contains? test-var :seon.error/operation))
                                        test-var
                                      (run test-var connection
                                           (cond-> {:seon.db/db database
                                                    :seon.db/connection connection
                                                    :seon.sci.eval/ctx (:seon.sci.eval/ctx prepared)
                                                    :seon.test.run/provenance provenance
                                                    :seon.test/remaining-ms remaining-ms}
                                             declared
                                             (assoc :seon.test/declared-root declared)))))
                                    (catch Exception failure
                                      (when (instance? InterruptedException failure)
                                        (throw failure))
                                      (unknown test-symbol (ex-message failure))))
                          green? (and (not (and (map? outcome) (contains? outcome :seon.error/at) (contains? outcome :seon.error/layer) (contains? outcome :seon.error/operation)))
                                      (pos? (:seon.test/pass-count outcome))
                                      (zero? (:seon.test/fail-count outcome))
                                      (zero? (:seon.test/error-count outcome)))
                          next-result
                          (cond-> (update result :seon.test/tests conj test-symbol)
                            (not (and (map? outcome) (contains? outcome :seon.error/at) (contains? outcome :seon.error/layer) (contains? outcome :seon.error/operation))) (update :seon.test/results conj outcome)
                            green? (update :seon.test/passed conj test-symbol)
                            (not green?)
                            (update :seon.test/failed conj
                                    {:seon.test/sym test-symbol
                                     :seon.test/changed (vec changed)
                                     :seon.test/failure-message
                                     (if (and (map? outcome) (contains? outcome :seon.error/at) (contains? outcome :seon.error/layer) (contains? outcome :seon.error/operation)) (:seon.error/message outcome)
                                         (failure-message outcome))
                                     :seon.test/failures (vec (:seon.test/failures outcome))})
                            (not green?) (assoc :seon.test/next-tier :none))]
                      ;; The verdicts recorded so far travel with the check, so
                      ;; the total bound firing reports them instead of
                      ;; discarding them for one bare unknown.
                      (swap! progress assoc :seon.test/recorded next-result
                             :seon.test/pending (vec (next remaining)))
                      (if (and (not green?)
                               (some #(and (= test-symbol (:seon.test.member/symbol %))
                                           ((:seon.test.member/reasons %) :platform))
                                     (:seon.test.run/members selection)))
                        (assoc next-result :seon.test/pending (vec (next remaining)))
                        (recur (next remaining) next-result)))))
                result)))]
        (assoc result :seon.test/elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0))))))

(defn- expired-result
  "Report the verdicts a check already recorded when its total bound fired.

  The bound firing is honest about the test that never returned; discarding the
  runs that DID complete is not — a caller reading one bare unknown cannot tell
  a slow selection from a red one. `check-in-process` publishes its accumulating
  result and its remaining selection as it goes, so the expiry is derived from
  what the check genuinely holds: the completed runs with their verdicts, plus
  the typed expiry naming what was pending."
  [snapshot started message]
  (let [phase (:seon.test/progress snapshot)
        recorded (:seon.test/recorded snapshot)
        pending (vec (:seon.test/pending snapshot))
        expiry (unknown phase (str message " Pending: " phase))]
    (if (map? recorded)
      (cond-> (assoc recorded
                     :seon.test/next-tier :none
                     :seon.test/expired expiry
                     :seon.test/elapsed-ms
                     (/ (double (- (System/nanoTime) started)) 1000000.0))
        (seq pending) (assoc :seon.test/pending pending))
      expiry)))

(defn check
  "Run tests observing this change in the calling JVM and record their facts.

  Without :seon.test/changed, select stale results and report unchanged skips.
  Supply :seon.test/changed symbols or adoption identities and optional
  :seon.test/paths for exact escalation commands. Widening inputs select the
  supplied affected namespaces, or all declared test namespaces when unknown.
  Hook callers set :seon.test/defer-widened? to report widening without running.
  A selected test reaching a function that declares `:seon.fn/destroys` is
  EXCLUDED, never run,
  when this JVM operates the development checkout; the exclusions are reported
  with their owner, call path, and cold command in :seon.test/destructive-excluded.
  A selected test declared :seon.test/long is EXCLUDED the same way and reported
  by name with its declared reason and cold command in :seon.test/long-excluded;
  :seon.test/include-long? true runs them, spending the same one allowance.
  When that allowance fires, the completed runs are returned WITH their verdicts
  and the typed expiry naming what was pending in :seon.test/expired.
  Red results return :seon.test/next-tier :none. Every failure names its test
  and the changed identities it reaches. Selection is admitted before execution,
  including a zero-member request. Declared exclusions and deferred requests
  are recorded separately from executable memberships. A bound or red platform
  member leaves the admitted, unexecuted remainder outstanding.
  Selection, loading, and execution
  share the total :seon.test/check-time-limit-ms fact; each test receives
  the remaining allowance. Timeout reports expiry without interrupting resource acquisition."
  {:malli/schema [:=> [:cat :seon.test/check-request]
                  [:or :seon.test/check-result :seon.test/selection-error :seon.test/unknown-error
                   :seon.test/admission-error :seon.test.run/unavailable-error
                   :seon.config/error :seon.db.write/error :seon.db.availability/error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{connection :seon.db/connection cluster :seon.boot/cluster-name :as request}]
  (let [database (db/db connection)
        custody (when cluster (db/pull database [:db/id :seon.cluster/name] [:seon.cluster/name cluster]))
        effective (when (:seon.cluster/name custody) (config/effective database cluster))]
    (cond
      (nil? cluster) (selection-refusal database :seon.test/cluster-required "Check requires explicit cluster custody." :absent)
      (and (map? custody)
           (contains? custody :seon.error/at)
           (contains? custody :seon.error/layer)
           (contains? custody :seon.error/operation)) custody
      (not (:seon.cluster/name custody)) (selection-refusal database :seon.test/cluster-unavailable "The requested cluster is absent." cluster)
      (and (map? effective) (contains? effective :seon.error/at) (contains? effective :seon.error/layer) (contains? effective :seon.error/operation)) (assoc effective :seon.test/next-tier :none)
      (not (:seon.test/check-time-limit-ms effective))
      (unknown :seon.test/check-time-limit-ms "Apply cluster configuration to supply the declared test check time limit.")
      :else
        (let [started (System/nanoTime)
              progress (atom {:seon.test/progress "reaching selection"})
              task (FutureTask. ^java.util.concurrent.Callable
                                (bound-fn [] (check-in-process request progress)))
              thread (.unstarted (Thread/ofVirtual) ^Runnable task)]
          (.start thread)
          (try
            (let [result (await/await!
                          {:seon.await/future task
                           :seon.await/bound
                           {:seon.await/config-attribute :seon.test/check-time-limit-ms
                            :seon.await/config-value (:seon.test/check-time-limit-ms effective)}
                           :seon.await/diagnostic
                           {:seon.error/diagnostic-layer :test
                            :seon.error/diagnostic-operation ::check
                            :seon.error/diagnostic-member :check-completion
                            :seon.error/diagnostic-expected :check-result
                            :seon.error/diagnostic-offending :pending
                            :seon.error/diagnostic-evidence {:seon.test/changed (:seon.test/changed request)}}})]
              (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation))
                (expired-result @progress started (:seon.error/message result))
                (do
                  (when-let [n (:seon.test/skipped-count result)]
                    (println "Skipped" n "tests:" (:seon.test/skip-reason result)))
                  result)))
            (catch Exception failure
              (let [phase (:seon.test/progress @progress)]
                (unknown phase (str "Check failed at " phase ": "
                                    (or (some-> failure ex-cause ex-message)
                                        (ex-message failure))))))
            (finally (when-not (.isDone task) (.cancel task false))))))))

(defn check-adoption
  "Check the last converged development adoption in this calling JVM.

  Changes and paths come from its commit transaction. Missing adoption facts
  are unknown, never green. Widened edits only report the namespace gate."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.boot/cluster-name]
                  [:or :seon.test/check-result :seon.error/value
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [connection cluster-name]
  (let [database (db/db connection)
        transaction (db/q '[:find (max ?t) . :in $ ?name
                            :where [?c :seon.cluster/name ?name]
                            [?t :seon.test/adoption-cluster ?c]] database cluster-name)
        adoption (when (integer? transaction)
                   (db/pull database
                            '[:seon.test/adoption-cluster
                              :seon.test/adoption-inputs
                              {:seon.test/adoption-identities
                               [:seon.ns/name :seon.fn/sym :seon.test/sym :seon.schema/key]}]
                            transaction))]
    (cond
      (and (map? transaction) (contains? transaction :seon.error/at) (contains? transaction :seon.error/layer) (contains? transaction :seon.error/operation)) transaction
      (and (map? adoption) (contains? adoption :seon.error/at) (contains? adoption :seon.error/layer) (contains? adoption :seon.error/operation)) adoption
      (not (:seon.test/adoption-cluster adoption))
      (unknown cluster-name "The latest adoption has no recorded changed identities; adopt with the updated recording owner first.")
      :else
      (check {:seon.db/connection connection
              :seon.boot/cluster-name cluster-name
              :seon.test/changed (mapv program/row-identity (:seon.test/adoption-identities adoption))
              :seon.test/paths (vec (:seon.test/adoption-inputs adoption))
              :seon.test/defer-widened? true}))))

(defn feedback
  "Format complete in-process check feedback, including executable next tiers."
  {:malli/schema [:=> [:cat [:or :seon.test/check-result :seon.error/value]] :string]}
  [result]
  (let [quote-arg (fn [s] (str "'" (str/replace s "'" "'\"'\"'") "'"))
        invocations (when (vector? (:seon.test/next-tier result))
                   (map #(str/join " " (map quote-arg %)) (:seon.test/next-tier result)))]
    (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation))
      (str "check unavailable: " (:seon.error/message result))
      (str (when-let [reason (:seon.test/widened result)] (str "widened: " reason "; "))
           "tests run " (count (:seon.test/tests result))
           " / passed " (count (:seon.test/passed result))
           " / failed " (count (:seon.test/failed result))
           " / elapsed " (:seon.test/elapsed-ms result) " ms"
           (when-let [n (:seon.test/skipped-count result)]
             (str " / skipped " n ": " (:seon.test/skip-reason result)))
           (when-let [excluded (seq (:seon.test/destructive-excluded result))]
             (str " / destructive-excluded " (count excluded)))
           (when-let [excluded (seq (:seon.test/long-excluded result))]
             (str " / long-excluded " (count excluded)))
           (when-let [expiry (:seon.test/expired result)]
             (str " / expired: " (:seon.error/message expiry)))
           (apply str (for [failure (:seon.test/failed result)]
                        (str "\n" (:seon.test/sym failure) " reaches "
                             (pr-str (:seon.test/changed failure)) ": "
                             (:seon.test/failure-message failure))))
           (apply str (for [deferred (:seon.test/deferred result)]
                        (str "\ndeferred " (:seon.test/sym deferred) ": "
                             (:seon.test/fixture-observation deferred)
                             "; run " (str/join " " (map quote-arg (:seon.test/command deferred))))))
           (apply str (for [excluded (:seon.test/destructive-excluded result)]
                        (str "\ndestructive " (:seon.test/sym excluded) " reaches "
                             (:seon.fn/sym excluded)
                             (when-let [what (:seon.fn/destroys excluded)]
                               (str " (" what ")"))
                             "; never in process on a development root — run "
                             (str/join " " (map quote-arg (:seon.test/command excluded))))))
           (apply str (for [excluded (:seon.test/long-excluded result)]
                        (str "\nlong " (:seon.test/sym excluded) ": "
                             (:seon.test/long excluded)
                             "; one declared-long test spends the whole check allowance — run "
                             (str/join " " (map quote-arg (:seon.test/command excluded))))))
           (if (seq invocations) (str "\nrun " (str/join " then " invocations))
               "\nnext-tier: none; fix failures first")))))

(defn check-request
  "Perform one operator test check in the cluster JVM.

  Projection, configuration, preparation and execution belong to this call.
  The supplied bound covers the whole operation; without an override the
  cluster's declared check allowance applies. Missing observations return a
  typed error. Successful observations always carry all three assertion
  counts, including a genuine zero, and the CLI verdict and display text."
  {:malli/schema [:=> [:cat :seon.test.check/request]
                  :seon.test.check/response]}
  [{connection :seon.db/connection cluster :seon.boot/cluster-name
    test-symbol :seon.test/sym override :seon.test/check-time-limit-ms}]
  (let [database (db/db connection)
        projection (db/carried-projection database)]
    (schema/call-with-projection
     projection
     (fn []
       (let [effective (config/effective database cluster)
             bound (or override (:seon.test/check-time-limit-ms effective))]
         (cond
           (and (map? effective) (contains? effective :seon.error/at) (contains? effective :seon.error/layer) (contains? effective :seon.error/operation)) effective
           (not (pos-int? bound))
           (unknown :seon.test/check-time-limit-ms
                    "Apply cluster configuration to supply the test check allowance.")
           :else
           (let [started (System/nanoTime)
                 task
                 (FutureTask.
                  ^java.util.concurrent.Callable
                  (bound-fn []
                    (let [result
                          (if test-symbol
                            (let [prepared (prepare-tests! database [test-symbol] effective)
                                  remaining (- bound (long (/ (- (System/nanoTime) started) 1000000)))]
                              (cond
                                (and (map? prepared) (contains? prepared :seon.error/at) (contains? prepared :seon.error/layer) (contains? prepared :seon.error/operation)) prepared
                                (not (pos? remaining))
                                (unknown test-symbol "Test preparation exhausted the check allowance.")
                                :else
                                (if-let [test-var (resolve-test test-symbol)]
                                  (run test-var connection
                                       {:seon.test.run/provenance (runner/provenance database)
                                        :seon.test/remaining-ms remaining})
                                  (unknown test-symbol "The indexed test Var is unavailable."))))
                            (check-adoption connection cluster))]
                      (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation))
                        result
                        (let [results (if test-symbol [result] (:seon.test/results result))
                              passes (reduce + 0 (map :seon.test/pass-count results))
                              failures (reduce + 0 (map :seon.test/fail-count results))
                              errors (reduce + 0 (map :seon.test/error-count results))]
                          {:seon.test/pass-count passes
                           :seon.test/fail-count failures
                           :seon.test/error-count errors
                           :seon.test.check/text (if test-symbol (pr-str result) (feedback result))
                           :seon.test.check/passed?
                           (boolean (and (zero? failures) (zero? errors)
                                         (not (:seon.test/expired result))
                                         (or (not test-symbol) (pos? passes))))})))))]
             (.start (.unstarted (Thread/ofVirtual) ^Runnable task))
             (try
               (await/await!
                {:seon.await/future task
                 :seon.await/bound {:seon.await/config-attribute :seon.test/check-time-limit-ms
                                    :seon.await/config-value bound}
                 :seon.await/diagnostic
                 {:seon.error/diagnostic-layer :test
                  :seon.error/diagnostic-operation ::check-request
                  :seon.error/diagnostic-member :check-completion
                  :seon.error/diagnostic-expected :seon.test.check/result
                  :seon.error/diagnostic-offending :pending
                  :seon.error/diagnostic-evidence
                  (cond-> {:seon.boot/cluster-name cluster}
                    test-symbol (assoc :seon.test/sym test-symbol))}})
               (catch Exception failure
                 (unknown (or test-symbol cluster) (ex-message failure)))
               (finally (when-not (.isDone task) (.cancel task false)))))))))))

(defn owned-symbols
  "Read the test symbols declared in the calling agent's assigned namespace."
  {:malli/schema [:=> [:cat :my.plan/request] [:vector :seon.test/sym]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (vec (sort (db/q '[:find [?symbol ...] :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id]
                            [?agent :seon.agent/namespace ?namespace]
                            [?test :seon.test/ns ?namespace]
                            [?test :seon.test/sym ?symbol]]
                   database agent-id))))

(defn verified?
  "True when a source-bearing test passed on the specified tested program.
  Missing subjects, results, runs, or assertions return false. A database
  refusal remains an error value; callers must require true, not truthiness."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.test/sym] [:or :boolean :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.test/sym :seon.test.run/program-digest]
     [:or :boolean :seon.error/value]]]}
  ([database test-symbol]
   (let [row (db/pull database
               [:seon.test/source :seon.test/pass-count :seon.test/fail-count
                :seon.test/error-count :seon.test/run :seon.test/reach-digest] [:seon.test/sym test-symbol])
         digest (reach-digest database test-symbol)]
     (cond
       (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
       (and (map? digest) (contains? digest :seon.error/at) (contains? digest :seon.error/layer) (contains? digest :seon.error/operation)) digest
       :else (boolean (and (:seon.test/source row) (:seon.test/run row)
                           (pos? (get row :seon.test/pass-count 0))
                           (= 0 (:seon.test/fail-count row))
                           (= 0 (:seon.test/error-count row))
                           (= digest (:seon.test/reach-digest row)))))))
  ([database test-symbol program-digest]
  (let [result (db/q
                '[:find ?test .
                  :in $ ?symbol ?digest
                  :where
                  [?test :seon.test/sym ?symbol]
                  [?test :seon.test/source]
                  [?test :seon.test/pass-count ?passes]
                  [(pos? ?passes)]
                  [?test :seon.test/fail-count 0]
                  [?test :seon.test/error-count 0]
                  [?test :seon.test/run ?run]
                  [?run :seon.test.run/id]
                  [?run :seon.test.run/program-digest ?digest]]
                database test-symbol program-digest)]
    (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation)) result (boolean result)))))
