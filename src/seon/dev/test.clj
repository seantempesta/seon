(ns seon.dev.test
  "REPL-first test system that returns structured data.
   Refers to clojure.core/test excluded to avoid collision.

   Core functions:
   - test           - Run tests, return structured results
   - test-affected  - Dependency-aware testing via code graph
   - test-gen       - Generative tests on schema-annotated fns
   - last-results   - Most recent test run
   - results-history - Past N runs

   All functions return data maps, not text. Results are stored
   in an atom for later inspection.

   Example:
     (require '[seon.dev.test :as t])

     (t/test 'seon.graph.query-test)
     ;; => {::success true ::test-count 7 ::pass-count 7 ...}

     (:failures (t/last-results))
     ;; => []"
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as ct]
            [seon.dev.verify :as verify]
            [seon.dev.test-select :as ts]))

;;; ---------------------------------------------------------------------------
;;; Results Store
;;; ---------------------------------------------------------------------------

(defonce ^:private *results-store
  (atom {:latest nil :history []}))

(defn- store-result! [result]
  (swap! *results-store
         (fn [s]
           {:latest result
            :history (vec (take-last 50 (conj (:history s) result)))})))

(defn last-results
  "Most recent test run result."
  []
  (:latest @*results-store))

(defn results-history
  "Last n test run results (default 10)."
  ([] (results-history 10))
  ([n] (vec (take-last n (:history @*results-store)))))

;;; ---------------------------------------------------------------------------
;;; Structured Test Runner
;;; ---------------------------------------------------------------------------

(defn- run-ns-tests
  "Run tests for a single namespace symbol, capturing structured failures."
  [test-ns-sym]
  (let [;; Require/reload the test ns
        load-error (try
                     (when (find-ns test-ns-sym)
                       (remove-ns test-ns-sym))
                     (require test-ns-sym :reload)
                     nil
                     (catch Exception e
                       (str "Failed to load " test-ns-sym ": " (.getMessage e))))
        _ (when load-error
            (throw (ex-info load-error {:ns test-ns-sym})))
        failures (atom [])
        counters (atom {:test 0 :pass 0 :fail 0 :error 0})
        start (System/currentTimeMillis)]
    (binding [ct/report
                (fn [m]
                  (case (:type m)
                    :begin-test-var
                    (swap! counters update :test inc)

                    :pass
                    (swap! counters update :pass inc)

                    :fail
                    (do (swap! counters update :fail inc)
                        (swap! failures conj
                               {::test-var (when-let [v (first ct/*testing-vars*)]
                                             (symbol (str (.-ns v)) (str (.-sym v))))
                                ::type :fail
                                ::message (:message m)
                                ::expected (:expected m)
                                ::actual (:actual m)
                                ::file (:file m)
                                ::line (:line m)}))

                    :error
                    (do (swap! counters update :error inc)
                        (swap! failures conj
                               {::test-var (when-let [v (first ct/*testing-vars*)]
                                             (symbol (str (.-ns v)) (str (.-sym v))))
                                ::type :error
                                ::message (:message m)
                                ::expected (:expected m)
                                ::actual (:actual m)
                                ::file (:file m)
                                ::line (:line m)}))

                    ;; ignore :begin-test-ns, :end-test-ns, :end-test-var, :summary
                    nil))]
      (ct/run-tests test-ns-sym)
      (let [c @counters
            elapsed (- (System/currentTimeMillis) start)]
        {::success (and (zero? (:fail c)) (zero? (:error c)))
         ::test-count (:test c)
         ::pass-count (:pass c)
         ::fail-count (:fail c)
         ::error-count (:error c)
         ::failures @failures
         ::duration-ms elapsed
         ::target test-ns-sym
         ::timestamp (java.util.Date.)}))))

(defn- resolve-var-target
  "If target is a symbol like 'ns/var, return [ns-sym var-sym]. Else nil."
  [target]
  (when (symbol? target)
    (when-let [ns-part (namespace target)]
      [(symbol ns-part) (symbol (name target))])))

(defn- run-var-test
  "Run a single test var."
  [ns-sym var-sym]
  (let [test-ns (if (.endsWith (str ns-sym) "-test")
                  ns-sym
                  (symbol (str ns-sym "-test")))
        load-error (try
                     (when (find-ns test-ns)
                       (remove-ns test-ns))
                     (require test-ns :reload)
                     nil
                     (catch Exception e
                       (str "Failed to load " test-ns ": " (.getMessage e))))
        _ (when load-error
            (throw (ex-info load-error {:ns test-ns})))]
    (if-let [v (ns-resolve test-ns var-sym)]
      (let [failures (atom [])
            counters (atom {:test 0 :pass 0 :fail 0 :error 0})
            start (System/currentTimeMillis)]
        (binding [ct/report
                  (fn [m]
                    (case (:type m)
                      :begin-test-var (swap! counters update :test inc)
                      :pass (swap! counters update :pass inc)
                      :fail (do (swap! counters update :fail inc)
                                (swap! failures conj
                                       {::test-var (symbol (str test-ns) (str var-sym))
                                        ::type :fail
                                        ::message (:message m)
                                        ::expected (:expected m)
                                        ::actual (:actual m)
                                        ::file (:file m)
                                        ::line (:line m)}))
                      :error (do (swap! counters update :error inc)
                                 (swap! failures conj
                                        {::test-var (symbol (str test-ns) (str var-sym))
                                         ::type :error
                                         ::message (:message m)
                                         ::expected (:expected m)
                                         ::actual (:actual m)
                                         ::file (:file m)
                                         ::line (:line m)}))
                      nil))]
          (ct/test-var v)
          (let [c @counters
                elapsed (- (System/currentTimeMillis) start)]
            {::success (and (zero? (:fail c)) (zero? (:error c)))
             ::test-count (:test c)
             ::pass-count (:pass c)
             ::fail-count (:fail c)
             ::error-count (:error c)
             ::failures @failures
             ::duration-ms elapsed
             ::target (symbol (str test-ns) (str var-sym))
             ::timestamp (java.util.Date.)})))
      (throw (ex-info (str "Var not found: " test-ns "/" var-sym)
                      {:ns test-ns :var var-sym})))))

(defn- aggregate-results
  "Merge multiple ns results into one."
  [results target]
  {::success (every? ::success results)
   ::test-count (reduce + 0 (map ::test-count results))
   ::pass-count (reduce + 0 (map ::pass-count results))
   ::fail-count (reduce + 0 (map ::fail-count results))
   ::error-count (reduce + 0 (map ::error-count results))
   ::failures (vec (mapcat ::failures results))
   ::duration-ms (reduce + 0 (map ::duration-ms results))
   ::target target
   ::timestamp (java.util.Date.)})

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn test
  "Run tests, return structured data.

   Accepts:
   - A namespace symbol:     (test 'seon.graph.query-test)
   - A qualified var symbol: (test 'seon.graph.query-test/dependents-of-test)
   - A vector of ns symbols: (test ['seon.db-test 'seon.graph.query-test])

   Returns map with ::success, ::test-count, ::pass-count, ::fail-count,
   ::error-count, ::failures, ::duration-ms, ::target, ::timestamp."
  [target]
  (let [result
        (cond
          ;; Qualified symbol — single var
          (and (symbol? target) (namespace target))
          (let [[ns-sym var-sym] (resolve-var-target target)]
            (run-var-test ns-sym var-sym))

          ;; Vector — multiple namespaces
          (vector? target)
          (aggregate-results (mapv run-ns-tests target) target)

          ;; Plain symbol — single namespace
          (symbol? target)
          (run-ns-tests target)

          :else
          (throw (ex-info "Invalid target. Use symbol, qualified symbol, or vector of symbols."
                          {:target target})))]
    (store-result! result)
    result))

(defn- find-test-namespaces
  "Find all *-test namespace symbols by scanning the test directory."
  []
  (let [test-dir (io/file "test")]
    (when (.isDirectory test-dir)
      (->> (file-seq test-dir)
           (filter #(and (.isFile ^java.io.File %)
                         (str/ends-with? (.getName ^java.io.File %) "_test.clj")))
           (map (fn [^java.io.File f]
                  (let [path (.getPath f)
                        ;; Remove test/ prefix and .clj suffix, convert to ns symbol
                        rel (subs path (inc (count (.getPath test-dir)))
                                  (- (count path) 4))]
                    (-> rel
                        (str/replace "/" ".")
                        (str/replace "_" "-")
                        symbol))))
           sort
           vec))))

(defn- integration-ns?
  "Check if a test namespace has ^:integration metadata."
  [ns-sym]
  (try
    (require ns-sym)
    (when-let [ns-obj (find-ns ns-sym)]
      (:integration (meta ns-obj)))
    (catch Exception _ false)))

(defn- deferred-ns?
  "Check if a test namespace has ^:deferred metadata. Deferred
   namespaces are placeholders for tests whose subject is parked
   pending some architectural trigger — see
   `seon/docs/prds/datahike-migration/deferred.md` for the index +
   each entry's revisit condition. Default test runs skip them."
  [ns-sym]
  (try
    (require ns-sym)
    (when-let [ns-obj (find-ns ns-sym)]
      (:deferred (meta ns-obj)))
    (catch Exception _ false)))

(defn- excluded-ns?
  "Predicate combining ^:integration and ^:deferred — both skip
   default `test-all` runs."
  [ns-sym]
  (or (integration-ns? ns-sym)
      (deferred-ns? ns-sym)))

(defn- safe-run-ns-tests
  "Run tests for a single namespace, catching Throwable to prevent
   LMDB native crashes from killing the REPL session."
  [test-ns-sym]
  (try
    (run-ns-tests test-ns-sym)
    (catch Throwable t
      {::success false
       ::test-count 0
       ::pass-count 0
       ::fail-count 0
       ::error-count 1
       ::failures [{::test-var test-ns-sym
                    ::type :error
                    ::message (str "Namespace crashed: " (.getMessage t))
                    ::expected nil
                    ::actual (str (class t) ": " (.getMessage t))}]
       ::duration-ms 0
       ::target test-ns-sym
       ::timestamp (java.util.Date.)})))

(defn test-all
  "Run all unit test namespaces. Excludes ^:integration and ^:deferred
   tagged namespaces. Each namespace runs in isolation — a crash in
   one does not kill the rest. Returns aggregated structured results."
  []
  (let [all-nses (find-test-namespaces)
        unit-nses (remove excluded-ns? all-nses)]
    (if (empty? unit-nses)
      (let [r {::success true ::test-count 0 ::pass-count 0 ::fail-count 0
               ::error-count 0 ::failures [] ::duration-ms 0
               ::target :all ::timestamp (java.util.Date.)}]
        (store-result! r)
        r)
      (let [results (mapv safe-run-ns-tests unit-nses)
            result (aggregate-results results :all)]
        (store-result! result)
        result))))

(defn test-affected
  "Run tests for ns and all its dependents. Returns aggregated results.

   Options:
     :depth - :direct (default) or :transitive

   Example:
     (test-affected 'seon.graph.query)
     (test-affected 'seon.graph.query :depth :transitive)"
  [ns-sym & {:keys [depth] :or {depth :direct}}]
  (let [ns-str (str ns-sym)
        ;; M-1 (2026-05-15): the previous probe looked at
        ;; :seon.db.datalevin/connections (removed from system.edn). The
        ;; graph backing affected-test-namespaces queries `:seon.runtime`,
        ;; which is not yet in :seon.db/flow (Cluster 4 — :seon.runtime
        ;; migration). Until that lands, this stays `false` and
        ;; `test-affected` falls back to running only the ns's own test —
        ;; identical behaviour to the live system before M-1.
        has-db? false
        test-nses (if has-db?
                    (ts/affected-test-namespaces
                     {::ts/db-name :seon.runtime ::ts/ns-name ns-str ::ts/depth depth})
                    ;; Fallback: just the ns's own test
                    (let [test-sym (symbol (str ns-str "-test"))]
                      (try
                        (require test-sym)
                        [test-sym]
                        (catch Exception _ []))))]
    (if (empty? test-nses)
      (let [r {::success true ::test-count 0 ::pass-count 0 ::fail-count 0
               ::error-count 0 ::failures [] ::duration-ms 0
               ::target ns-sym ::timestamp (java.util.Date.)}]
        (store-result! r)
        r)
      (let [results (mapv run-ns-tests test-nses)
            result (aggregate-results results (symbol (str ns-sym "+dependents")))]
        (store-result! result)
        result))))

(defn test-gen
  "Run generative tests on schema-annotated functions.

   Accepts:
   - Namespace symbol:  (test-gen 'seon.graph.query)
   - Qualified symbol:  (test-gen 'seon.graph.query/dependents-of)

   Options:
     :num-tests - tests per function (default 10)

   Returns structured result from verify/run-gen-tests."
  [target & {:keys [num-tests] :or {num-tests 10}}]
  (let [ns-sym (if (namespace target)
                 (symbol (namespace target))
                 target)
        result (verify/run-gen-tests
                {::verify/namespace ns-sym
                 ::verify/num-tests num-tests})
        ;; If targeting a specific fn, filter failures
        result (if (namespace target)
                 (update result ::verify/failures
                         (fn [fs] (filterv #(= (::verify/fn-symbol %) (symbol (name target))) fs)))
                 result)
        stored {::success (::verify/success result)
                ::failures (::verify/failures result)
                ::target target
                ::timestamp (java.util.Date.)
                ::type :generative}]
    (store-result! stored)
    stored))
