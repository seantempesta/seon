(ns seon.proofs
  "Proofs: what a change must run, and what a run proved. prove's selection (task
  seon.proofs/prove-runs-what-a-change-reaches, docs/merging.md#a-merge-runs-what-the-change-reaches):
  a key (a test var, or a bin/check step of check-steps) runs only when no green result is
  stored at its reach digest (seon.program/reach-digests), in the one directory every checkout
  shares; bin/prove, bin/test, bin/check without a run size and an agent's test run all
  select with plan. And the results of a clojure.test run's report data (the agent's
  run-tests-forked answer): one status per test var, read the same way by the agent's turn
  loop and root's judge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.edn :as sedn] [seon.error :as error] [seon.profile :as profile]
            [clojure.edn :as edn] [clojure.string :as str] [clojure.test :as t]))

(defn test-symbol
  "The qualified symbol of a report's :var (\"#'ns/name\" or \"ns/name\"), or nil."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :qualified-symbol]]}
  [v]
  (when v
    (let [s (str/replace v #"^#'" "")]
      (when (str/includes? s "/") (symbol s)))))

(defn red?
  "Is test run t red: any failing or erroring assertion (a run's or a tests event's counts)?"
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [t] (pos? (+ (:fail t 0) (:error t 0))))

(defn results
  "{test-symbol status} of a test run: :error or :fail when any assertion of the test was,
  else :pass. A test with no assertion has no entry (a proof missing here is :none)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] :seon.proof/results]}
  [test-run]
  (reduce (fn [acc {:keys [type var]}]
            (if-let [t (test-symbol var)]
              (let [rank {:pass 0 :fail 1 :error 2}]
                (update acc t #(if (and % (>= (rank %) (rank type))) % type)))
              acc))
          {} (:reports test-run)))

(defn red-reports
  "The failing assertions of the test run for the tests in `tests`, as data for the context."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]] [:set :qualified-symbol]] [:vector :map]]}
  [test-run tests]
  (vec (for [{:keys [type var] :as r} (:reports test-run)
             :when (and (#{:fail :error} type) (tests (test-symbol var)))]
         (-> (select-keys r [:type :message :expected :actual :got]) (assoc :test (test-symbol var))))))

(defn test-run-errors
  "The errors of the test run that name no test: the test run itself failed (its fork died before any
  test started, a namespace did not load), so every test reads as not run. Their messages."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] [:vector :string]]}
  [test-run]
  (vec (for [{:keys [type var message]} (:reports test-run)
             :when (and (= :error type) (nil? (test-symbol var)))]
         (or message "an error with no message"))))

(def max-assertions
  "Failing assertions a red test keeps in its run's facts (the first ones)."
  8)

(defn failures
  "The failing assertions of the test run, per test: {test [{:type :message :expected :actual}]},
  the first max-assertions of each (expected and actual as the test child printed them)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :seon.test/report]]]] :seon.run/failures]}
  [test-run]
  (reduce (fn [acc {:keys [type var] :as r}]
            (let [t (test-symbol var)]
              (if (and t (#{:fail :error} type) (< (count (get acc t)) max-assertions))
                (update acc t (fnil conj []) (select-keys r [:type :message :expected :actual]))
                acc)))
          {} (:reports test-run)))

(defn costs
  "The cost proofs' reports of the test run, per test ({:rows :verdict}, kept as data)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :seon.test/report]]]] :seon.run/costs]}
  [test-run]
  (into {} (for [{:keys [var cost]} (:reports test-run) :let [t (test-symbol var)] :when (and t cost)] [t cost])))

;; ---- prove: run what a change reaches, reuse every green by digest ------------------------

(def check-steps
  "bin/check's steps, the one list of them (bin/check reads it from ./seon steps and fails a
  step it does not name), as prove keys them: each step name (a bin/check --steps unit) to the
  seon.check mode functions its body runs, whose reach is the step's (seon.program/
  reach-digests with roots), with the checkout's :env (bin/, native/, resources/, the pins:
  the binary's other inputs) in every digest. laws (bin/laws) reads the whole tree and has no
  functions: its key is the tree's digest (step-digests)."
  '{"laws" :tree "lmdb-kill" [] "contracts" [seon.check/contracts] "mg-check" [seon.check/mg-check] "fx" [seon.check/fx]
    "config" [seon.check/config-check] "system" [seon.check/system-check] "mcp" [seon.check/mcp-proof]
    "threads" [seon.check/threads-check] "instrument" [seon.check/instrument] "record" [seon.check/record]
    "problems" [seon.check/problems-check] "smoke" [seon.check/smoke] "robust" [seon.check/robust]
    "lifecycle" [seon.check/lifecycle] "backpressure" [seon.check/backpressure] "hotreload" [seon.check/hotreload]
    "drain" [seon.check/drain] "idle" [seon.check/idle] "orphan" [seon.check/orphan]
    "durable" [seon.check/durable-write seon.check/durable-read] "window" [seon.check/window-write seon.check/window-read]
    "lease" [seon.check/lease] "e2e-calc" [seon.e2e/run] "e2e-conflict" [seon.e2e/run] "e2e-semantic" [seon.e2e/run]
    "e2e-hairy" [seon.e2e/run] "e2e-newns" [seon.e2e/run] "e2e-sibling" [seon.e2e/run] "swarm" [seon.e2e/run]
    "e2e-loop" [seon.e2e/run] "e2e-held" [seon.e2e/run] "resume" [seon.e2e/run] "scale" [seon.check/scale]})

(defn step-digests
  "Of check-steps, {:digests {step digest} :missing [step ...]}: the reach digests (digests-of,
  seon.program/reach-digests over the steps' functions) and, for a step reading the whole tree, the
  digest of its name and :tree; missing, the steps naming a function the program does not
  define (renamed, not imported), which a run reports red rather than keying by :env alone."
  {:malli/schema [:=> [:cat :seon.host/fn [:map [:env :string] [:tree :string]]]
                  [:map [:digests [:map-of :string :string]] [:missing [:vector :string]]]]}
  [digests-of {:keys [tree]}]
  (let [fns (into {} (filter (comp vector? val)) check-steps)
        ds (merge (digests-of fns)
                  (into {} (for [[k v] check-steps :when (= :tree v)] [k (error/digest (str k "\n" tree))])))]
    {:digests ds :missing (vec (sort (remove ds (keys check-steps))))}))

(defn green? "Is result r green: no failing or erroring assertion?" {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [r] (and (some? r) (not (red? r))))

(defn result-path "Where the result at digest lives in dir." {:malli/schema [:=> [:cat :string :string] :string]}
  [dir digest] (str dir "/" digest ".edn"))

(defn greens
  "{digest result} of the results stored green at digests in dir (one <digest>.edn each)."
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:map-of :string :map]]}
  [dir digests]
  (into {} (for [dg (distinct digests) :let [f (java.io.File. ^String (result-path dir dg))] :when (.isFile f)
                 :let [r (edn/read-string (slurp f))] :when (green? r)]
             [dg r])))

(defn result-file
  "The write that stores result r ({:test or :step, :digest, :pass :fail :error :ms}) in dir:
  seon.fx/write-file's request {:path :text}, the one encoding greens reads back (store-greens!)."
  {:malli/schema [:=> [:cat :string [:map [:digest :string]]] [:map [:path :string] [:text :string]]]}
  [dir r] {:path (result-path dir (:digest r)) :text (sedn/encode r)})

(defn store-greens!
  "Each green result of ran ({key result}) that has a :digest written at it in dir by (write!
  request), request being seon.fx/write-file's (result-file): bin/test's shards and steps
  write through seon.fx/write-file itself, an agent through root's :file owner. Answers the
  writes refused, [{:key :message}], each read: that key's green is not stored and it runs again."
  {:malli/schema [:=> [:cat :seon.host/fn :string [:map-of :any :map]] [:vector [:map [:key :any] [:message [:maybe :string]]]]]}
  [write! dir ran]
  (vec (for [[k r] ran :when (and (green? r) (:digest r))
             :let [w (write! (result-file dir r))] :when (:seon.error/type w)]
         {:key k :message (:seon.error/message w)})))

(defn plan
  "What a change runs: of cand ({key digest}, the candidate's), base (the base's {key digest},
  nil: none), found ({digest result}, the greens stored at cand's digests: greens) and proofs
  (keys that always judge), {:selected :run :reused}. With a base (an agent's branch against the
  main it forked from), selected is the keys whose digest moved or is new, and the proofs cand
  holds: what judges the change. With none (a worktree session's or root's tree), the proofs and every key
  with no green at its digest. run is the selected with no green at their digest (sorted), what
  runs; reused {key result} every key green at its digest. So an unchanged tree runs nothing.
  Cost: one lookup per key, then a sort of what is selected."
  {:malli/schema [:=> [:cat [:maybe [:map-of :any :string]] [:map-of :any :string] [:map-of :string :map] [:set :any]]
                  [:map [:selected [:vector :any]] [:run [:vector :any]] [:reused [:map-of :any :map]]]]}
  [base cand found proofs]
  (let [selected (vec (sort-by str (distinct (concat (filter cand proofs)
                                                    (if base
                                                      (remove #(= (base %) (cand %)) (keys cand))
                                                      (remove #(found (cand %)) (keys cand)))))))]
    {:selected selected
     :run (filterv #(not (found (cand %))) selected)
     :reused (into {} (for [[k dg] cand :let [r (found dg)] :when r] [k r]))}))

(def ^:private report
  "clojure.test's own report as this namespace loads (no binding in force), so a run nested in
  a test's run reports through it, never through the enclosing run's binding."
  t/report)

(defn run-tests!
  "The one clojure.test runner (bin/test's shards, an agent's test fork): run tests (var
  symbols, their namespaces loaded) here, each namespace's by clojure.test/test-vars so its
  fixtures run around them, and tell (note! x) as it goes: {:seon.test/started test} as each
  starts, {:seon.test/report m :test test} for each assertion (clojure.test's report map),
  each result {:test :digest :pass :fail :error :ms} as it ends, and {:seon.test/output text
  :ns n} for a red namespace's clojure.test output. It only runs: its caller stores the greens
  (store-greens!). Answers {test result}; a test that never reported, because its namespace's
  run threw (a fixture), is an :error result and an :error report carrying that throw's
  message and data. A run's work budget (Jolt's ticks, :jolt/ticks-spent) ends the whole run:
  that throw is the caller's. The per-namespace counts are volatiles local to its run:
  clojure.test reports through a callback, one thread, no proc."
  {:malli/schema [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn]
                  [:map-of :qualified-symbol :map]]}
  [tests digests note!]
  (let [tset (set tests)]
    (into {} (for [[n ts] (group-by (comp symbol namespace) tests)
                   :let [counts (volatile! {}) ran (volatile! {}) out (java.io.StringWriter.) t0 (volatile! nil)
                         done! (fn [v] (let [r (assoc (merge {:pass 0 :fail 0 :error 0} (@counts v)) :test v :digest (digests v)
                                                      :ms (quot (- (profile/now-ns) @t0) 1000000))]
                                         (vswap! ran assoc v r)
                                         (note! r)))
                         thrown (binding [t/*test-out* out
                                          t/report (fn [m]
                                                     ;; a test running tests of its own (test-ns inside a deftest) reports
                                                     ;; them too: only this run's tests are counted
                                                     (let [v (some-> (or (:var m) (first t/*testing-vars*)) str test-symbol)
                                                           v (when (tset v) v)]
                                                       (case (when v (:type m))
                                                         :begin-test-var (do (vreset! t0 (profile/now-ns)) (note! {:seon.test/started v}))
                                                         :end-test-var (done! v)
                                                         (:pass :fail :error) (do (vswap! counts update-in [v (:type m)] (fnil inc 0))
                                                                                  (note! {:seon.test/report m :test v}))
                                                         nil))
                                                     (binding [t/*test-out* out] (report m)))]
                                  (try (t/test-vars (keep #(ns-resolve n (symbol (name %))) ts)) nil
                                       (catch Throwable e (if (:jolt/ticks-spent (ex-data e)) (throw e) e))))
                         unreported (for [tst ts :when (not (@ran tst))]
                                      (let [msg (if thrown (str n "'s run threw before this test reported: " (ex-message thrown))
                                                    (str n "'s run ended before this test reported"))]
                                        (note! {:seon.test/report {:type :error :message msg} :test tst})
                                        [tst (cond-> {:test tst :digest (digests tst) :pass 0 :fail 0 :error 1 :ms 0 :message msg}
                                               (ex-data thrown) (assoc :data (sedn/encode (ex-data thrown))))]))
                         result (into @ran unreported)
                         _ (when (or thrown (some #(not (green? (result %))) ts)) (note! {:seon.test/output (str out) :ns n}))]
                   tst ts]
               [tst (result tst)]))))
