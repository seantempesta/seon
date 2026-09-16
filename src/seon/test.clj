(ns seon.test
  "Agent-facing test execution over the one JVM test runner."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.await :as await]
            [seon.config :as config]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.error :as error]
            [seon.fn :as functions]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.test.selection :as selection]
            [seon.test.runner :as runner])
  (:import [clojure.lang DynamicClassLoader]
           [java.util.concurrent FutureTask]))

(defn- unknown [input message]
  {:seon.error/kind ::unknown :seon.test/unknown (str input)
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
                  [:or [:vector [:map [:db/id :int] [:seon.fn/sym :seon.fn/sym]]]
                   :seon.error/value]]}
  [database test-symbol]
  (let [row (db/pull database [:db/id :seon.test/reach-unknown {:seon.test/reach [:db/id]}]
                     [:seon.test/sym test-symbol])]
    (cond
      (:seon.error/kind row) row
      (not (:db/id row)) (unknown test-symbol "The test has no recorded identity.")
      (:seon.test/reach-unknown row) (unknown test-symbol (:seon.test/reach-unknown row))
      (not (seq (:seon.test/reach row)))
      (unknown test-symbol "The test has no retained function closure evidence.")
      :else
      (let [history (db/history database)
            events (db/q '[:find ?a ?v ?t ?added
                           :in $ ?e [?a ...]
                           :where [?e ?a ?v ?t ?added]]
                         history (:db/id row)
                         [:seon.test/run :seon.test/pass-count
                          :seon.test/fail-count :seon.test/error-count])]
        (if (:seon.error/kind events) events
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
                (let [changed (db/q '[:find ?f ?sym
                                      :in $ $since [?f ...] [?a ...]
                                      :where [$since ?f ?a]
                                             [?f :seon.fn/sym ?sym]]
                                    database (db/since history green)
                                    (mapv :db/id (:seon.test/reach row))
                                    [:seon.fn/source :seon.fn/spec])]
                  (if (:seon.error/kind changed) changed
                      (mapv (fn [[e s]] {:db/id e :seon.fn/sym s})
                            (sort-by second changed)))))))))))

(defn- test-loader []
  (let [loader (DynamicClassLoader. (clojure.lang.RT/baseLoader))
        paths (get-in (edn/read-string (slurp "deps.edn")) [:aliases :test :extra-paths])]
    (doseq [path paths]
      (.addURL loader (.toURL (.toURI (io/file path)))))
    loader))

(defn- with-test-loader [work]
  (let [loader (test-loader)
        thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (with-bindings {clojure.lang.Compiler/LOADER loader} (work))
      (finally (.setContextClassLoader thread previous)))))

(defn- event-backstop-ms []
  (with-test-loader
    #(long (* 1000 @(requiring-resolve 'seon.test-support/event-backstop-seconds)))))

(defn- bounded-result [test-var timeout-ms custody]
  (let [test-symbol (str (:ns (meta test-var)) "/" (:name (meta test-var)))
        task (FutureTask. (bound-fn [] (with-test-loader #(runner/run-var! test-var custody))))
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
        (if (:seon.error/kind result)
          {:seon.test/sym test-symbol,
           :seon.test/pass-count 0,
           :seon.test/fail-count 0,
           :seon.test/error-count 1,
           :seon.test/failure-message (:seon.error/message result)}
          result))
      (catch
        Exception
        failure
        (when (instance? InterruptedException failure) (throw failure))
        {:seon.test/sym test-symbol,
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

(defn- destructive-reach
  "Every test symbol whose reach includes a destructive owner, mapped to it.

  The owner set is `seon.test.runner/destructive-owners` — the ONE declaration,
  shared with the cold gate's platform-tier checker — and membership is the
  shared `:seon.fn/calls` derivation `seon.fn/tests-reaching`. An owner with no
  program row is a typed unknown: a rename would otherwise leave this walking
  to nothing and admitting every test, which is absence of signal read as
  health."
  [database]
  (reduce
   (fn [reached owner]
     (let [row (db/pull database [:db/id] [:seon.fn/sym owner])]
       (cond
         (:seon.error/kind row) (reduced row)
         (nil? (:db/id row))
         (reduced
          (unknown owner
                   (str "No program row declares the destructive owner " owner
                        ", so an in-process run cannot tell whether a test "
                        "deletes a filesystem path. Republish the program, or "
                        "correct seon.test.runner/destructive-owners.")))
         :else
         (let [tests (functions/tests-reaching database owner)]
           (if (:seon.error/kind tests)
             (reduced tests)
             (reduce #(assoc %1 %2 owner) reached tests))))))
   {}
   (sort runner/destructive-owners)))

(defn- destructive-path
  "The shortest declared call path from one test down to its destructive owner.
  Evidence for the refusal, walked only when one is being constructed."
  [database test-symbol owner-symbol]
  (let [callees (fn [entity]
                  (let [row (db/pull database
                                     [{:seon.fn/calls [:db/id :seon.fn/sym]}
                                      {:seon.test/subject [:db/id :seon.fn/sym]}]
                                     entity)]
                    (when-not (:seon.error/kind row)
                      (let [subject (:seon.test/subject row)]
                        (concat (:seon.fn/calls row)
                                (cond (nil? subject) nil
                                      (sequential? subject) subject
                                      :else [subject]))))))
        start (:db/id (db/pull database [:db/id] [:seon.test/sym test-symbol]))]
    (loop [frontier (if start [[start [test-symbol]]] [])
           seen (if start #{start} #{})]
      (if (empty? frontier)
        [test-symbol owner-symbol]
        (let [edges (for [[entity path] frontier
                          row (callees entity)]
                      [row path])]
          (if-let [found (some (fn [[row path]]
                                 (when (= owner-symbol (:seon.fn/sym row))
                                   (conj path owner-symbol)))
                               edges)]
            found
            (let [next-frontier (reduce (fn [acc [row path]]
                                          (let [entity (:db/id row)]
                                            (if (or (contains? seen entity)
                                                    (some #(= entity (first %)) acc))
                                              acc
                                              (conj acc [entity (conj path (:seon.fn/sym row))]))))
                                        []
                                        edges)]
              (recur next-frontier (into seen (map first) next-frontier)))))))))

(defn- destructive-exclusion
  "One selected test's destructive evidence, or nil when it reaches no owner."
  [database reach test-symbol]
  (when-let [owner (get reach test-symbol)]
    {:seon.test/sym test-symbol
     :seon.fn/sym owner
     :seon.test/destructive-path (destructive-path database test-symbol owner)
     :seon.test/command ["bin/test" "--" (namespace (symbol test-symbol))]}))

(defn- destructive-refusal
  "Refuse one in-process run that would execute a destructive drill, or nil.

  A test reaching a declared destructive owner deletes a filesystem path it did
  not create. Run in the JVM that operates the developer's own checkout, that
  is the 2026-09-17 incident: an in-process run emptied `data/store`
  (`docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md`). The
  rule was prose; this is the check. It fires only for a JVM whose DECLARED
  operator root is that development root — a `bin/test` worker or a lane's
  `--root` scratch JVM runs the same test untouched. The refusal names the
  test, the owner it reaches, the call path between them, and the cold
  invocation that may run it."
  [database declared-root test-symbol]
  (when-let [root (development-root declared-root)]
    (let [reach (destructive-reach database)]
      (if (:seon.error/kind reach)
        (assoc reach :seon.test/next-tier :none)
        (when-let [evidence (destructive-exclusion database reach test-symbol)]
          (let [owner (:seon.fn/sym evidence)]
            (error/diagnostic
             (merge
              evidence
              {:seon.error/kind ::destructive-in-process
               :seon.error/message
               (str test-symbol " reaches " owner
                    ", which deletes a filesystem path it did not create, and "
                    "this JVM was launched to operate the development root "
                    root ". Run it cold — bin/test -- "
                    (namespace (symbol test-symbol))
                    " — or in a JVM under an isolated operator root "
                    "(bin/seon --root tmp/<lane>-root). Call path: "
                    (str/join " -> " (:seon.test/destructive-path evidence)) ".")
               :seon.error/diagnostic-layer :test
               :seon.error/diagnostic-operation ::run
               :seon.error/diagnostic-member test-symbol
               :seon.error/diagnostic-expected :isolated-operator-root
               :seon.error/diagnostic-offending root
               :seon.error/diagnostic-cause owner
               :seon.error/diagnostic-evidence evidence
               :seon.test/next-tier :none}))))))))

(defn run
  "Run one declared test Var, commit its result facts, and return them.\n\n  The connection is ordinarily supplied by call preparation from the calling\n  agent's environment. The returned value is pulled from the transaction's\n  `:db-after`, so it cannot disagree with the facts that were committed.\n\n  A test whose program-graph reach includes a declared destructive owner is\n  REFUSED, without executing, in a JVM whose declared operator root is the\n  development checkout it runs in; the refusal names the test, the owner, the\n  call path, and the cold invocation that may run it. `:seon.test/declared-root`\n  in the options is that declaration when the caller genuinely holds one;\n  absent, this JVM's own is read once here.\n\n  The test BODY runs under exactly the custody the options hand it:\n  `:seon.db/connection` present means the run is that cluster's own work and\n  the body's elided `seon.db` arities reach it; absent means none, which is\n  what a host REPL calling this is. `run-owned` is the agent's entry and\n  supplies its evaluation's connection. The `connection` argument is where\n  the RESULT FACTS are committed and never decides the body's custody."
  {:malli/schema
   [:function
    [:=> [:cat :seon.test/var :seon.db/connection] [:or :seon.test/result :seon.error/value]]
    [:=>
     [:cat :seon.test/var :seon.db/connection :seon.test/run-options]
     [:or :seon.test/result :seon.error/value]]]}
  ([test-var connection]
    (let [database (db/db connection)
          provenance (runner/provenance database)]
      (if (:seon.error/kind provenance)
        provenance
        (run
          test-var
          connection
          {:seon.db/db database,
           :seon.test.run/provenance provenance,
           :seon.test/remaining-ms (event-backstop-ms)}))))
  ([test-var connection options]
    (let [database (or (:seon.db/db options) (db/db connection))]
      (if (:seon.error/kind database)
        database
        (let [provenance (:seon.test.run/provenance options)
              declared (if-let [entry (find options :seon.test/declared-root)]
                         (val entry)
                         (store/declared-operator-root))
              refusal (destructive-refusal
                        database declared
                        (str (:ns (meta test-var)) "/" (:name (meta test-var))))
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
                       (:seon.error/kind provenance) provenance
                       :else
                       (schema/call-with-projection
                         (db/carried-projection database)
                         #(bounded-result test-var (:seon.test/remaining-ms options)
                                          (select-keys options [:seon.db/connection]))))
              restored (runner/restore-live-cluster-schema! registry-before)
              drifted (runner/schema-restore-drift restored)
              ;; A COMMITTED change is the writer doing its job during the
              ;; run, so it is named on the way past and is nobody's failure.
              _ (when (seq (remove (set drifted) restored))
                  (println "Live cluster schema facts changed during the run:"
                           (pr-str (vec (remove (set drifted) restored)))))
              result (if (or (:seon.error/kind result) (empty? drifted))
                       result
                       (-> result
                           (update :seon.test/error-count (fnil inc 0))
                           (update :seon.test/failure-message
                                   #(str (when % (str % "\n"))
                                         "Live cluster schema registry changed and was restored: "
                                         (pr-str drifted)))))]
          (if (:seon.error/kind result)
            result
            (let [committed (runner/commit-results!
                              connection
                              {:seon.db/db database,
                               :seon.test.runner/results [result],
                               :seon.test/run-basis-t (:seon.test.run/basis-t provenance),
                               :seon.test/run-at (:seon.test.run/at provenance),
                               :seon.test.run/provenance provenance})]
              (if (:seon.error/kind committed) committed (first committed)))))))))


(defn run-owned
  "Run one of MY declared tests, under my own cluster's custody.

  `my.test/run` resolves each test symbol my namespace declares and calls
  this with the Var. My connection is supplied by call preparation — the same
  connection my evaluation reads and writes through — and it is handed down
  as a VALUE to the test body, so a `seon.db` call my test elides inside
  reaches my cluster instead of refusing (the elided arity is the documented
  affordance inside an evaluation, AGENTS §3).

  `run` itself hands nothing: a host REPL running the same Var is not any
  cluster's own work, and its body's elided arities refuse and say so."
  {:malli/schema [:=> [:cat :seon.test/run-owned-request]
                  [:or :seon.test/result :seon.error/value]]}
  [{connection :seon.db/connection test-var :seon.test/var}]
  (let [database (db/db connection)]
    (if (:seon.error/kind database)
      database
      (let [provenance (runner/provenance database)]
        (if (:seon.error/kind provenance)
          provenance
          (run test-var connection
               {:seon.db/db database
                :seon.db/connection connection
                :seon.test.run/provenance provenance
                :seon.test/remaining-ms (event-backstop-ms)}))))))

(defn- identity-tests [database changed]
  (let [[attribute value :as program-identity]
        (if (vector? changed) changed
            (let [s (str changed)]
              (if (:db/id (db/pull database [:db/id] [:seon.test/sym s]))
                [:seon.test/sym s] [:seon.fn/sym s])))
        row (db/pull database [:db/id] program-identity)]
    (cond
      (:seon.error/kind row) row
      (not (:db/id row))
      (unknown (pr-str program-identity) (str "Unknown program identity " (pr-str program-identity) "."))
      (= attribute :seon.fn/sym) (functions/tests-reaching database value)
      (= attribute :seon.test/sym) [value]
      (= attribute :seon.ns/name)
      (let [members (db/q '[:find [?symbol ...] :in $ ?ns
                           :where [?n :seon.ns/name ?ns]
                           [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?symbol]] database value)
            tests (db/q '[:find [?symbol ...] :in $ ?ns
                         :where [?n :seon.ns/name ?ns]
                         [?t :seon.test/ns ?n] [?t :seon.test/sym ?symbol]] database value)]
        (if (:seon.error/kind members) members
            (if (:seon.error/kind tests) tests
                (vec (distinct (concat tests (mapcat #(functions/tests-reaching database %) members)))))))
      :else (unknown (pr-str program-identity) "The reaching set cannot bound a schema change."))))

(defn- changed-reach [database changed]
  (reduce (fn [reaches change]
            (let [selected (identity-tests database change)]
              (if (:seon.error/kind selected) (reduced selected)
                  (assoc reaches change (set selected)))))
          {} changed))

(defn reaching
  "Return tests reaching changed function, test, or namespace identities.

  Uses the database program graph. Unknown identities return a typed error;
  an empty vector only describes known identities with no recorded reach."
  {:malli/schema [:=> [:cat :seon.test/reaching-request]
                  [:or [:vector :seon.test/sym] :seon.error/value]]}
  [{database :seon.db/db changed :seon.test/changed}]
  (let [reaches (changed-reach database changed)]
    (if (:seon.error/kind reaches) reaches
        (vec (sort (distinct (mapcat val reaches)))))))

(defn reach-digest
  "Digest this test's source, transitively reached definitions and named schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or :seon.test/reach-digest :seon.error/value]]}
  [database test-symbol]
  (let [result (runner/reach-digests database [test-symbol])]
    (if (:seon.error/kind result) result (get result test-symbol))))

(defn- stale-in [database test-symbols]
  (let [rows (db/pull-many database
               [:seon.test/sym :seon.test/reach-digest :seon.test/run
                :seon.test/fixture-observation]
               (mapv #(vector :seon.test/sym %) test-symbols))
        eligible (when-not (:seon.error/kind rows)
                   (into [] (keep #(when (and (:seon.test/run %)
                                             (:seon.test/reach-digest %)
                                             (not (:seon.test/fixture-observation %)))
                                     (:seon.test/sym %))) rows))
        digests (if (seq eligible) (runner/reach-digests database eligible) {})]
    (cond
      (:seon.error/kind rows) rows
      (:seon.error/kind digests) digests
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
     (if (:seon.error/kind symbols) symbols
         (stale-in database (vec (sort symbols))))))
  ([database test-symbols]
   (if (empty? test-symbols)
     []
     (stale-in database (vec (sort (distinct test-symbols)))))))

(defn- namespace-tests [database namespaces]
  (db/q '[:find [?symbol ...] :in $ [?ns ...]
          :where [?n :seon.ns/name ?ns] [?t :seon.test/ns ?n]
          [?t :seon.test/sym ?symbol] [?t :seon.test/source]] database namespaces))

(defn- commands [paths namespaces]
  [(into (into ["bin/test"] (when (seq paths) (into ["--paths"] paths)))
         (when (seq namespaces) (into ["--"] (map str namespaces))))
   (into (into ["bin/test"] (when (seq paths) (into ["--paths"] paths)))
         ["--platform"])])

(defn- resolve-test [test-symbol]
  (with-test-loader #(requiring-resolve (symbol test-symbol))))

(defn- prepare-tests! [database selected effective]
  ;; Reload source-bearing test namespaces before using their Vars: the live
  ;; development classpath may not have exposed them during adoption.
  (let [namespaces (filter #(:seon.ns/source
                            (db/pull database [:seon.ns/source] [:seon.ns/name %]))
                          (distinct (map #(symbol (namespace (symbol %))) selected)))
        projection (schema/projection-from-database database)]
    (when (seq namespaces)
      (with-test-loader #(doseq [namespace-name namespaces] (require namespace-name :reload)))
      (instrument/apply!
       {:seon.config/on-core-error (:seon.config/on-core-error effective)
        :seon.sci.admit/caps (config/result-caps effective)
        :seon.schema/projection projection}))))

(defn- relative-path [path]
  (let [root (.toPath (.getCanonicalFile (io/file ".")))
        file (.toPath (.getCanonicalFile (io/file path)))]
    (str (.relativize root file))))

(defn- check-in-process
  [{connection :seon.db/connection changed :seon.test/changed
    paths :seon.test/paths namespaces :seon.test/namespaces
    cluster :seon.boot/cluster-name defer? :seon.test/defer-widened?
    include-long? :seon.test/include-long?
    :as request}
   progress]
  (let [started (System/nanoTime)
        database (db/db connection)
        effective (config/effective database (or cluster "default"))
        paths (mapv relative-path paths)
        widened (seq (concat (filter selection/widening-path? paths)
                             (for [change changed
                                   :when (and (vector? change)
                                              (= :seon.schema/key (first change)))]
                               (str "schema " (second change)))))
        reaches (when (and (some? changed) (not widened)) (changed-reach database changed))
        candidates (when (nil? changed)
                     (vec (sort (namespace-tests database
                                  (or (seq namespaces)
                                      (db/q '[:find [?name ...] :where [?n :seon.ns/name ?name]] database))))))
        selected (if (nil? changed)
                   (stale-in database candidates)
                   (if (and widened defer? (not (seq namespaces)))
                   []
                   (if widened
                   (namespace-tests database
                                    (or (seq namespaces)
                                        (db/q '[:find [?name ...]
                                                :where [?t :seon.test/ns ?n]
                                                [?n :seon.ns/name ?name]] database)))
                   (if (:seon.error/kind reaches) reaches
                       (vec (sort (distinct (mapcat val reaches))))))))
        deferred (when-not (:seon.error/kind selected)
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
        ;; A declared-long test is a real boot or a multi-minute fixture: one of
        ;; them spends the whole :seon.test/check-time-limit-ms allowance the
        ;; whole selection shares, so the cold gate excludes it from every tier
        ;; but --full, and so does this. The declaration is a program-row fact
        ;; (`seon.fn/var-row`), queried here rather than read off a Var the
        ;; check has deliberately not loaded yet. :seon.test/include-long? is
        ;; the caller's explicit opt-in.
        long-excluded (when (and (not (:seon.error/kind selected)) (not include-long?))
                        (into []
                              (keep (fn [test-symbol]
                                      (when-let [reason (:seon.test/long
                                                         (db/pull database [:seon.test/long]
                                                                  [:seon.test/sym test-symbol]))]
                                        {:seon.test/sym test-symbol
                                         :seon.test/long reason
                                         :seon.test/command
                                         ["bin/test" "--" (namespace (symbol test-symbol))]})))
                              selected))
        ;; A test reaching a declared destructive owner never runs in the
        ;; development JVM: the same rule seon.test/run enforces per Var, applied
        ;; to the whole selection so the exclusion is reported, never silent.
        ;; The declaration this check genuinely holds travels with every run it
        ;; starts: a seam that re-read the JVM property would decide twice.
        declared (if-let [entry (find request :seon.test/declared-root)]
                   (val entry)
                   (store/declared-operator-root))
        destructive (when-not (:seon.error/kind selected)
                      (let [reach (when (development-root declared)
                                    (destructive-reach database))]
                        (cond
                          (nil? reach) []
                          (:seon.error/kind reach) reach
                          :else (into [] (keep #(destructive-exclusion database reach %))
                                      selected))))
        excluded (if (vector? destructive)
                   (set (map :seon.test/sym destructive))
                   #{})
        runnable (if (or (:seon.error/kind selected) (:seon.error/kind destructive)
                         (and widened defer?)) []
                     (filterv (complement (-> excluded
                                              (into (map :seon.test/sym) deferred)
                                              (into (map :seon.test/sym) long-excluded)))
                              selected))
        provenance (when (and (not (:seon.error/kind selected)) (seq runnable))
                     (runner/provenance database))]
    (cond
      (:seon.error/kind effective) effective
      (:seon.error/kind provenance) provenance
      (:seon.error/kind selected) selected
      (:seon.error/kind destructive) (assoc destructive :seon.test/next-tier :none)
      :else
      (let [selected (vec (sort selected))
            namespaces (vec (sort (distinct (or (seq namespaces)
                                                (map #(symbol (namespace (symbol %))) selected)))))
            deadline (+ started (* 1000000 (:seon.test/check-time-limit-ms effective)))
            initial (cond-> {:seon.test/tests [] :seon.test/passed [] :seon.test/failed []
                             :seon.test/results []
                             :seon.test.run/basis-t (db/basis-t database)
                             :seon.test/next-tier (commands paths namespaces)}
                      (nil? changed) (assoc :seon.test/skipped-count (- (count candidates) (count selected))
                                           :seon.test/skip-reason "recorded result has an unchanged reach digest")
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
                         (some (set (functions/tests-reaching database "seon.test-support/with-database")) runnable))
                (swap! progress assoc :seon.test/progress "canonical fixture preparation")
                ;; Realize the fixture owner's one base before starting a Var's
                ;; event backstop. The total check deadline still applies.
                (with-test-loader
                  #(deref @(requiring-resolve 'seon.test-support/database-base))))
            prepared (when (seq runnable)
                       (swap! progress assoc :seon.test/progress "test namespace loading and contract arming")
                       (prepare-tests! database runnable effective))
            result
            (if (:seon.error/kind prepared)
              (assoc prepared :seon.test/next-tier :none)
            (loop [remaining runnable result initial]
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
                                    (if-let [test-var (resolve-test test-symbol)]
                                      (run test-var connection
                                           (cond-> {:seon.db/db database
                                                    :seon.test.run/provenance provenance
                                                    :seon.test/remaining-ms remaining-ms}
                                             declared
                                             (assoc :seon.test/declared-root declared)))
                                      (unknown test-symbol "The indexed test Var is unavailable."))
                                    (catch Exception failure
                                      (when (instance? InterruptedException failure)
                                        (throw failure))
                                      (unknown test-symbol (ex-message failure))))
                          green? (and (not (:seon.error/kind outcome))
                                      (pos? (:seon.test/pass-count outcome))
                                      (zero? (:seon.test/fail-count outcome))
                                      (zero? (:seon.test/error-count outcome)))
                          next-result
                          (cond-> (update result :seon.test/tests conj test-symbol)
                            (not (:seon.error/kind outcome)) (update :seon.test/results conj outcome)
                            green? (update :seon.test/passed conj test-symbol)
                            (not green?)
                            (update :seon.test/failed conj
                                    {:seon.test/sym test-symbol
                                     :seon.test/changed (vec (filter #(get (get reaches %) test-symbol) changed))
                                     :seon.test/failure-message
                                     (if (:seon.error/kind outcome) (:seon.error/message outcome)
                                         (failure-message outcome))
                                     :seon.test/failures (vec (:seon.test/failures outcome))})
                            (not green?) (assoc :seon.test/next-tier :none))]
                      ;; The verdicts recorded so far travel with the check, so
                      ;; the total bound firing reports them instead of
                      ;; discarding them for one bare unknown.
                      (swap! progress assoc :seon.test/recorded next-result
                             :seon.test/pending (vec (next remaining)))
                      (recur (next remaining) next-result))))
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
  A selected test reaching a declared destructive owner is EXCLUDED, never run,
  when this JVM operates the development checkout; the exclusions are reported
  with their owner, call path, and cold command in :seon.test/destructive-excluded.
  A selected test declared :seon.test/long is EXCLUDED the same way and reported
  by name with its declared reason and cold command in :seon.test/long-excluded;
  :seon.test/include-long? true runs them, spending the same one allowance.
  When that allowance fires, the completed runs are returned WITH their verdicts
  and the typed expiry naming what was pending in :seon.test/expired.
  Red results return :seon.test/next-tier :none. Every failure names its test
  and the changed identities it reaches. Empty or deferred checks do not mint
  run provenance or compute a program digest; no program was tested.
  Selection, loading, and execution
  share the total :seon.test/check-time-limit-ms fact; each test receives
  the remaining allowance. Timeout reports expiry without interrupting resource acquisition."
  {:malli/schema [:=> [:cat :seon.test/check-request]
                  [:or :seon.test/check-result :seon.error/value]]}
  [{connection :seon.db/connection cluster :seon.boot/cluster-name :as request}]
  (let [effective (config/effective (db/db connection) (or cluster "default"))]
    (cond
      (:seon.error/kind effective) (assoc effective :seon.test/next-tier :none)
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
              (if (:seon.error/kind result)
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
                  [:or :seon.test/check-result :seon.error/value]]}
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
      (:seon.error/kind transaction) transaction
      (:seon.error/kind adoption) adoption
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
    (if (:seon.error/kind result)
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
                             "; never in process on a development root — run "
                             (str/join " " (map quote-arg (:seon.test/command excluded))))))
           (apply str (for [excluded (:seon.test/long-excluded result)]
                        (str "\nlong " (:seon.test/sym excluded) ": "
                             (:seon.test/long excluded)
                             "; one declared-long test spends the whole check allowance — run "
                             (str/join " " (map quote-arg (:seon.test/command excluded))))))
           (if (seq invocations) (str "\nrun " (str/join " then " invocations))
               "\nnext-tier: none; fix failures first")))))

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
       (:seon.error/kind row) row
       (:seon.error/kind digest) digest
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
    (if (:seon.error/kind result) result (boolean result)))))
