(ns seon.test.arm
  "The program-loading and contract-arming owner shared by test launchers."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.lang ProcessHandle]))

(defn- load-declared-predicate-owners!
  "Load the namespace every packaged predicate symbol names.

  `seon.schema/register-core-predicate!` makes a predicate's owner loaded
  before anything can declare against it, and schema compilation therefore
  refuses to LOAD code while examining an authored form. A JVM that loaded
  only part of the tree breaks that invariant from the other side:
  `seon.schema/malli-form?` answers FALSE for four ordinary shipped
  contracts (`my.fs/write`, `my.shell/run`, and their two JVM owners)
  because `my.fs/content?`, `my.shell/stdin?` and `my.shell/output?` have
  no loaded Var — and under instrumentation that is a contract violation
  out of `seon.schema/canonical-definition` on any static analysis of the
  tree. The set is DERIVED from the population itself: every qualified
  symbol a Malli form carries is a predicate or generator owner. A symbol
  that cannot be loaded is left alone; the compile it feeds still refuses
  loudly and names it."
  [forms]
  (doseq [candidate (tree-seq coll? seq (seq forms))
          :when (qualified-symbol? candidate)]
    (try
      (requiring-resolve candidate)
      (catch Throwable _ nil))))

(defn- packaged-test-projection
  "Acquire the packaged projection once for one test-runner JVM."
  [role]
  (let [forms (schema.edn/packaged-forms)
        _ (load-declared-predicate-owners! forms)
        projection (schema/declaration-projection forms)]
    (binding [*out* *err*]
      (println "bin/test: PACKAGED TEST PROJECTION ACQUIRED"
               "at=" (str (java.time.Instant/now))
               "pid=" (.pid (ProcessHandle/current))
               "role=" role))
    projection))

(def ^:private program-source-root
  ;; The one root a live cluster's boot loads. `test` is deliberately not
  ;; here: a worker loads exactly the test namespaces its selection names.
  "src")

(defn- program-source-files
  [^java.io.File root]
  (->> (file-seq root)
       (filter (fn [^java.io.File file]
                 (and (.isFile file)
                      (or (.endsWith (.getName file) ".clj")
                          (.endsWith (.getName file) ".cljc")))))
       (sort-by (fn [^java.io.File file] (.getPath file)))))

(defn- declared-namespace
  "The namespace one first-party source file declares, or a typed refusal.

  A file whose first form is not an `ns` form used to drop out of the derived
  set with no report, so an unusual or malformed source file silently shrank
  the world the worker armed."
  [^java.io.File file]
  (let [form (try
               (with-open [source (java.io.PushbackReader. (io/reader file))]
                 (read {:read-cond :allow :eof :seon.test.runner/eof} source))
               (catch Throwable failure
                 {:seon.test.runner/unreadable (or (ex-message failure)
                                   (.getName (class failure)))}))]
    (cond
      (and (map? form) (contains? form :seon.test.runner/unreadable))
      {:seon.error/kind :seon.test.runner/unreadable-program-source
       :seon.error/message
       (str "A first-party source file could not be read: " (.getPath file)
            " — " (:seon.test.runner/unreadable form) ".")
       :seon.test.runner/source-file (.getPath file)}

      (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)

      :else
      {:seon.error/kind :seon.test.runner/program-source-declares-no-namespace
       :seon.error/message
       (str "A first-party source file declares no namespace: "
            (.getPath file)
            " — its first form must be an `ns` form, or the worker arms a"
            " smaller world than the cluster it claims to reproduce.")
       :seon.test.runner/source-file (.getPath file)})))

(defn- declared-program-namespaces
  "Every namespace the first-party program declares, read from its own form.

  DERIVED, NOT LISTED. The reader answers what a file's namespace is; a
  path-to-symbol convention would be a naming rule, and a roster would be
  stale within a day. This is what makes the worker's armed set the set a
  cluster arms: a contract in a namespace no test happens to require —
  `seon.artifact/-main`, `seon.artifact/install-initialization-pages!`,
  `seon.test/run` were the three — is enforced on every live cluster and
  was checked by nothing here.

  TOTAL, AND LOUD ABOUT ABSENCE. This function's answer is the INPUT to the
  worker's arming check, and it used to answer `[]` in silence whenever the
  relative root did not resolve — a worker would then require nothing, its own
  test vars would keep the instrumented count positive, and the gate would be
  green about a question it never asked (the same disease one level up,
  `docs/seon/issues/declared-program-namespaces-returns-empty-in-silence.md`).
  An unresolvable root, an unreadable file, a file declaring no namespace, and
  an empty derivation are each a typed refusal naming what was missing."
  []
  (let [root (io/file program-source-root)]
    (when-not (.isDirectory root)
      (throw
       (ex-info
        (str "The first-party program source root does not resolve: "
             (.getPath root) " from working directory "
             (.getCanonicalPath (io/file ".")) ".")
        {:seon.error/kind :seon.test.runner/program-source-root-unresolved
         :seon.test.runner/program-source-root (.getPath root)
         :seon.test.runner/working-directory (.getCanonicalPath (io/file "."))
         :seon.test.runner/instrumentation-unavailable true})))
    (let [declared (mapv declared-namespace (program-source-files root))]
      (when-let [refusal (first (filter map? declared))]
        (throw
         (ex-info (:seon.error/message refusal)
                  (assoc refusal :seon.test.runner/instrumentation-unavailable true))))
      (when (empty? declared)
        (throw
         (ex-info
          (str "The first-party program source root declares no namespaces: "
               (.getCanonicalPath root) ".")
          {:seon.error/kind :seon.test.runner/program-declares-no-namespaces
           :seon.test.runner/program-source-root (.getCanonicalPath root)
           :seon.test.runner/instrumentation-unavailable true})))
      declared)))

(defn- arming-decision
  "The shipped decisions, admission caps and program namespaces one arm needs.

  DECIDED ONCE, BEFORE THE FIRST ARM, AND CARRIED. Deriving it again for a
  mid-run re-arm asks `seon.config/result-caps` its own question under the
  contracts the first arm installed, and the compiled shipped effective config
  does not satisfy `:seon.config/effective` — eighteen optional dials it
  legitimately leaves absent. On the first arm nothing is instrumented so the
  call answers; on a re-arm the worker died, and the namespaces it held were
  reported red as `confirmation parallel-only`, which mis-attributed a day of
  someone else's diagnosis (`docs/seon/issues/the-test-runners-re-arm-kills-
  the-worker-under-its-own-contract.md`).

  That the effective config fails its own key's schema is a separate defect,
  filed there; the arming path must not be the thing that discovers it."
  []
  (let [decisions (config/defaults)
        caps (config/result-caps decisions)]
    (when (:seon.error/kind caps)
      (throw
       (ex-info (:seon.error/message caps)
                (assoc caps :seon.test.runner/instrumentation-unavailable true))))
    {:seon.test.runner/decisions decisions
     :seon.test.runner/caps caps
     :seon.test.runner/program (declared-program-namespaces)}))

(defn arm-contracts!
  "Instrument this worker JVM's loaded contracts exactly as boot does.

  The gate must ask the question a live cluster asks. A contract
  violation that makes an agent's prompt unavailable on a running
  cluster was invisible here for as long as the worker never armed
  instrumentation — a check that reports health because its subject was
  never asked. The dial and the admission caps are the SHIPPED decisions
  the operator compiles (`seon.config/defaults`), so the gate
  cannot drift from boot by carrying constants of its own, and an absent
  cap refuses NAMING the key rather than instrumenting under a partial
  world."
  [decision projection worker-id namespaces]
  (let [{:seon.test.runner/keys [decisions caps program]} decision]
    ;; THE PROGRAM IS LOADED BEFORE IT IS ARMED. Instrumentation selects
    ;; loaded vars carrying `:malli/schema`, so a namespace nothing required
    ;; contributes nothing and the gate silently arms a smaller world than
    ;; the cluster it claims to reproduce.
    (doseq [namespace-name program]
      (require namespace-name))
    (let [applied (instrument/apply!
                   {:seon.config/on-core-error
                    (:seon.config/on-core-error decisions)
                    :seon.sci.admit/caps caps
                    :seon.schema/projection projection})]
      (when (:seon.error/kind applied)
        (throw
         (ex-info (:seon.error/message applied)
                  (assoc applied :seon.test.runner/instrumentation-unavailable true))))
      (when (zero? (:seon.instrument/instrumented applied))
        (throw (ex-info "No test contracts were armed."
                        {:seon.error/kind :seon.test.runner/instrumentation-unavailable})))
      ;; ABSENCE IS NEVER HEALTH, AND A COUNT IS NOT THE QUESTION. A floor of
      ;; zero was satisfied by the worker's OWN test vars, so a worker that
      ;; armed none of the program still passed. The question is SET COVERAGE
      ;; against the set a booted cluster arms, and both sides are derived the
      ;; same way: `seon.instrument/armable` asks malli's own two questions
      ;; (a declared function schema, a non-primitive value) over the program
      ;; namespaces this worker just loaded, and `seon.instrument/instrumented`
      ;; reads the wrappers actually installed. `seon.artifact/-main`,
      ;; `seon.artifact/install-initialization-pages!` and `seon.test/run`
      ;; were live on every cluster and armed by nothing here.
      (let [armable (instrument/armable program)
            installed (instrument/instrumented)
            unarmed (into (sorted-set)
                          (map #(str (symbol %)))
                          (set/difference armable installed))]
        (when (seq unarmed)
          (throw
           (ex-info
            (str "bin/test armed a smaller world than a cluster in worker "
                 worker-id ": " (count unarmed) " of " (count armable)
                 " declared program contracts across " (count program)
                 " program namespaces carry no wrapper — "
                 (str/join ", " (take 10 unarmed))
                 (when (> (count unarmed) 10) ", ...") ".")
            (assoc applied
                   :seon.test.runner/instrumentation-unavailable true
                   :seon.test.runner/program-namespace-count (count program)
                   :seon.test.runner/armable-count (count armable)
                   :seon.test.runner/unarmed-program-contracts (vec unarmed)))))
        (binding [*out* *err*]
          (println "bin/test: CONTRACTS ARMED"
                   "at=" (str (java.time.Instant/now))
                   "worker=" worker-id
                   "mode=" (:seon.config/on-core-error decisions)
                   "namespaces=" (count namespaces)
                   "program-namespaces=" (count program)
                   "registered=" (:seon.instrument/registered applied)
                   "instrumented=" (:seon.instrument/instrumented applied)
                   "program-armable=" (count armable))))
      applied)))

(defn initialize-contracts!
  "Load selected tests and acquire the one arming value for workers and test-fast."
  ([role namespaces]
   (initialize-contracts! role namespaces (packaged-test-projection role)))
  ([role namespaces projection]
   ;; Packaged acquisition loads the declared predicate owners before compiling.
   ;; Carry that same value through fixture preparation, loading and arming.
   (let [_ (schema/call-with-projection
            projection
            #(doseq [namespace-name namespaces] (require namespace-name)))
         decision (arming-decision)
         applied (arm-contracts! decision projection role namespaces)]
     {:seon.test.runner/projection projection
      :seon.test.runner/namespaces namespaces
      :seon.test.runner/decision decision
      :seon.test.runner/instrumented (:seon.instrument/instrumented applied)})))
