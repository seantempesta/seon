(ns seon.test
  "Agent-facing test execution over the one JVM test runner."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.await :as await]
            [seon.config :as config]
            [seon.db :as db]
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

(defn- bounded-result [test-var timeout-ms]
  (let [test-symbol (str (:ns (meta test-var)) "/" (:name (meta test-var)))
        task (FutureTask. ^java.util.concurrent.Callable
                          (bound-fn [] (with-test-loader #(runner/run-var! test-var))))
        thread (.unstarted (Thread/ofVirtual) ^Runnable task)]
    (.start thread)
    (try
      (let [result
            (await/await!
             {:seon.await/future task
              :seon.await/bound
              {:seon.await/config-attribute :seon.test-support/event-backstop-seconds
               :seon.await/config-value timeout-ms}
              :seon.await/diagnostic
              {:seon.error/diagnostic-layer :test
               :seon.error/diagnostic-operation ::run
               :seon.error/diagnostic-member test-symbol
               :seon.error/diagnostic-expected :test-completion
               :seon.error/diagnostic-offending :pending
               :seon.error/diagnostic-evidence {:seon.test/sym test-symbol}}})]
        (if (:seon.error/kind result)
          {:seon.test/sym test-symbol :seon.test/pass-count 0
           :seon.test/fail-count 0 :seon.test/error-count 1
           :seon.test/failure-message (:seon.error/message result)}
          result))
      (catch Exception failure
        (when (instance? InterruptedException failure) (throw failure))
        {:seon.test/sym test-symbol :seon.test/pass-count 0
         :seon.test/fail-count 0 :seon.test/error-count 1
         :seon.test/failure-message
         (str "Test execution failed: "
              (or (some-> failure ex-cause ex-message) (ex-message failure)))})
      (finally (when-not (.isDone task) (.cancel task true))))))

(defn run
  "Run one declared test Var, commit its result facts, and return them.

  The connection is ordinarily supplied by call preparation from the calling
  agent's environment. The returned value is pulled from the transaction's
  `:db-after`, so it cannot disagree with the facts that were committed."
  {:malli/schema
   [:function
    [:=> [:cat :seon.test/var :seon.db/connection]
     [:or :seon.test/result :seon.error/value]]
    [:=> [:cat :seon.test/var :seon.db/connection :seon.test/run-options]
     [:or :seon.test/result :seon.error/value]]]}
  ([test-var connection]
   (let [provenance (runner/provenance (db/db connection))]
     (if (:seon.error/kind provenance) provenance
         (run test-var connection
              {:seon.test.run/provenance provenance
               :seon.test/remaining-ms (event-backstop-ms)}))))
  ([test-var connection options]
  (let [database (db/db connection)]
    (if (:seon.error/kind database)
      database
      (let [provenance (:seon.test.run/provenance options)
            result (if (:seon.error/kind provenance)
                     provenance
                     (bounded-result test-var (min (event-backstop-ms)
                                                   (:seon.test/remaining-ms options))))]
        (if (:seon.error/kind result)
          result
          (let [committed
                (runner/commit-results!
                 connection
                 {:seon.test.runner/results [result]
                  :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                  :seon.test/run-at (:seon.test.run/at provenance)
                  :seon.test.run/provenance provenance})]
            (if (:seon.error/kind committed)
              committed
              (first committed)))))))))

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
    cluster :seon.boot/cluster-name defer? :seon.test/defer-widened?}
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
        reaches (when-not widened (changed-reach database changed))
        selected (if (and widened defer? (not (seq namespaces)))
                   []
                   (if widened
                   (namespace-tests database
                                    (or (seq namespaces)
                                        (db/q '[:find [?name ...]
                                                :where [?t :seon.test/ns ?n]
                                                [?n :seon.ns/name ?name]] database)))
                   (if (:seon.error/kind reaches) reaches
                       (vec (sort (distinct (mapcat val reaches)))))))
        runnable (if (and widened defer?) [] selected)
        provenance (when (and (not (:seon.error/kind selected)) (seq runnable))
                     (runner/provenance database))]
    (cond
      (:seon.error/kind effective) effective
      (:seon.error/kind provenance) provenance
      (:seon.error/kind selected) selected
      :else
      (let [selected (vec (sort selected))
            namespaces (vec (sort (distinct (or (seq namespaces)
                                                (map #(symbol (namespace (symbol %))) selected)))))
            deadline (+ started (* 1000000 (:seon.test/check-time-limit-ms effective)))
            initial (cond-> {:seon.test/tests [] :seon.test/passed [] :seon.test/failed []
                             :seon.test/results []
                             :seon.test.run/basis-t (db/basis-t database)
                             :seon.test/next-tier (commands paths namespaces)}
                      provenance (assoc :seon.test.run/program-digest
                                        (:seon.test.run/program-digest provenance))
                      widened (assoc :seon.test/widened
                                     (str "the reaching set cannot bound this change: "
                                          (str/join ", " widened))))
            _ (when (and (seq selected) (not (and widened defer?))
                         (some (set (functions/tests-reaching database "seon.test-support/with-database")) selected))
                (reset! progress "canonical fixture preparation")
                ;; Realize the fixture owner's one base before starting a Var's
                ;; event backstop. The total check deadline still applies.
                (with-test-loader
                  #(deref @(requiring-resolve 'seon.test-support/database-base))))
            prepared (when (and (seq selected) (not (and widened defer?)))
                       (reset! progress "test namespace loading and contract arming")
                       (prepare-tests! database selected effective))
            result
            (if (:seon.error/kind prepared)
              (assoc prepared :seon.test/next-tier :none)
            (loop [remaining runnable result initial]
              (if-let [test-symbol (first remaining)]
                (let [_ (reset! progress test-symbol)
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
                                           {:seon.test.run/provenance provenance
                                            :seon.test/remaining-ms remaining-ms})
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
                                     (or (:seon.test/failure-message outcome)
                                         (:seon.error/message outcome) "No passing assertions were observed.")})
                            (not green?) (assoc :seon.test/next-tier :none))]
                      (recur (next remaining) next-result))))
                result)))]
        (assoc result :seon.test/elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0))))))

(defn check
  "Run tests observing this change in the calling JVM and record their facts.

  Supply :seon.test/changed symbols or adoption identities and optional
  :seon.test/paths for exact escalation commands. Widening inputs select the
  supplied affected namespaces, or all declared test namespaces when unknown.
  Hook callers set :seon.test/defer-widened? to report widening without running.
  Red results return :seon.test/next-tier :none. Every failure names its test
  and the changed identities it reaches. Empty or deferred checks do not mint
  run provenance or compute a program digest; no program was tested.
  Selection, loading, and execution
  share the total :seon.test/check-time-limit-ms fact; each test also has
  the declared event backstop. Timeout requests interruption and reports it."
  {:malli/schema [:=> [:cat :seon.test/check-request]
                  [:or :seon.test/check-result :seon.error/value]]}
  [{connection :seon.db/connection cluster :seon.boot/cluster-name :as request}]
  (let [effective (config/effective (db/db connection) (or cluster "default"))]
    (cond
      (:seon.error/kind effective) (assoc effective :seon.test/next-tier :none)
      (not (:seon.test/check-time-limit-ms effective))
      (unknown :seon.test/check-time-limit-ms "Apply cluster configuration to supply the declared test check time limit.")
      :else
        (let [progress (atom "reaching selection")
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
                (unknown @progress (str (:seon.error/message result) " Pending: " @progress))
                result))
            (catch Exception failure
              (unknown @progress (str "Check failed at " @progress ": "
                                      (or (some-> failure ex-cause ex-message)
                                          (ex-message failure)))))
            (finally (when-not (.isDone task) (.cancel task true))))))))

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
           (apply str (for [failure (:seon.test/failed result)]
                        (str "\n" (:seon.test/sym failure) " reaches "
                             (pr-str (:seon.test/changed failure)) ": "
                             (:seon.test/failure-message failure))))
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
   [:=> [:cat :seon.db/database-value :seon.test/sym :seon.test.run/program-digest]
    [:or :boolean :seon.error/value]]}
  [database test-symbol program-digest]
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
    (if (:seon.error/kind result) result (boolean result))))
