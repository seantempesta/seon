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
  (:require [seon.contract :refer [defn defn-]] [seon.edn :as sedn] [seon.error :as error] [seon.fx :as fx] [seon.profile :as profile]
            [seon.config :as config] [seon.system :as system] [clojure.java.shell :as sh]
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
  "{test-symbol status} of a run: :error or :fail when any assertion of the test was,
  else :pass. A test with no assertion has no entry (a proof missing here is :none)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] :seon.proof/results]}
  [run]
  (reduce (fn [acc {:keys [type var]}]
            (if-let [t (test-symbol var)]
              (let [rank {:pass 0 :fail 1 :error 2}]
                (update acc t #(if (and % (>= (rank %) (rank type))) % type)))
              acc))
          {} (:reports run)))

(defn red-reports
  "The failing assertions of run for the tests in `tests`, as data for the context."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]] [:set :qualified-symbol]] [:vector :map]]}
  [run tests]
  (vec (for [{:keys [type var] :as r} (:reports run)
             :when (and (#{:fail :error} type) (tests (test-symbol var)))]
         (-> (select-keys r [:type :message :expected :actual :got]) (assoc :test (test-symbol var))))))

(defn run-errors
  "The errors of run that name no test: the run itself failed (its fork died before any
  test started, a namespace did not load), so every test reads as not run. Their messages."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] [:vector :string]]}
  [run]
  (vec (for [{:keys [type var message]} (:reports run)
             :when (and (= :error type) (nil? (test-symbol var)))]
         (or message "an error with no message"))))

(def max-assertions
  "Failing assertions a red test keeps in its attempt's facts (the first ones)."
  8)

(defn failures
  "The failing assertions of run, per test: {test [{:type :message :expected :actual}]},
  the first max-assertions of each (expected and actual as the test child printed them)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :seon.test/report]]]] :seon.attempt/failures]}
  [run]
  (reduce (fn [acc {:keys [type var] :as r}]
            (let [t (test-symbol var)]
              (if (and t (#{:fail :error} type) (< (count (get acc t)) max-assertions))
                (update acc t (fnil conj []) (select-keys r [:type :message :expected :actual]))
                acc)))
          {} (:reports run)))

(defn costs
  "The cost proofs' reports of run, per test ({:rows :verdict}, kept as data)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :seon.test/report]]]] :seon.attempt/costs]}
  [run]
  (into {} (for [{:keys [var cost]} (:reports run) :let [t (test-symbol var)] :when (and t cost)] [t cost])))

;; ---- the inputs with no program facts --------------------------------------------------

(defn- git {:malli/schema [:=> [:cat :string [:* :string]] :string]}
  [root & args] (:out (apply sh/sh "git" "-C" root args)))

(defn- env-input?
  "Is the file at relative path p an input of every test: everything git tracks or would
  track but the source program facts carry (.clj under src and test), task files (data
  root loads, no test's code) and prose (.md)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [p]
  (not (or (str/ends-with? p ".md")
           (str/starts-with? p "resources/seon/tasks/")
           (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))))))

(defn config-input
  "The config in force as a test reads it, cfg (seon.config/config: the shipped file, this
  checkout's config/local.edn, the #env values, the profile), as text: the checkout's own path
  written as <checkout>, and the TCP ports left out (each checkout's own by design, bin/ports;
  a test's root takes its own). What a result depends on that git does not see."
  {:malli/schema [:=> [:cat :map :string] :string]}
  [cfg checkout]
  (str/replace (sedn/encode (into (sorted-map) (remove (fn [[k]] (= "TCP port" (get-in config/schema-forms [k 1 :seon.config/unit])))) cfg))
               checkout "<checkout>"))

(defn inputs
  "The one producer of the checkout at root's inputs that have no program facts, for bin/test
  and for root (which records them at boot for its agents, seon.root/record-proof-inputs!):
  {:env (every test's: env-input? files, every submodule's pin, Jolt's included, and the config
  in force, config-input) :tree (a test reading files: every file and pin) :data-refs
  (seon.system/declared-refs of its system.edn)}, digests of relative paths and content, so the
  same content at another path is the same inputs."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string :map :string] :map]]}
  ([root] (inputs root config/config (config/checkout)))
  ([root cfg checkout]
   (let [pins (filter #(str/starts-with? % "160000 ") (str/split-lines (git root "ls-files" "-s")))
         files (for [p (sort (str/split (git root "ls-files" "--cached" "--others" "--exclude-standard" "-z") #"\u0000"))
                     :let [f (java.io.File. ^String root ^String p)] :when (.isFile f)]
                 [p (str p " " (error/digest (slurp f)))])
         digest #(error/digest (str/join "\n" %))]
     {:env (digest (concat pins (keep (fn [[p line]] (when (env-input? p) line)) files) [(config-input cfg checkout)]))
      :tree (digest (concat pins (map second files)))
      :data-refs (system/declared-refs (str root "/resources/seon/system.edn"))})))

(defn perform-inputs
  "The :proof-inputs effect's performer (resources/seon/system.edn): inputs of the checkout at
  the request's :root, its :env and :tree, read on an effect worker (git and every file read:
  never on root's own thread, which stays fit to fork). Answers {:value {:env :tree}}."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root]} :req}]
  {:value (select-keys (inputs root) [:env :tree])})

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
    "e2e-loop" [seon.e2e/run] "e2e-stuck" [seon.e2e/run] "resume" [seon.e2e/run] "scale" [seon.check/scale]})

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
  "{digest result} of the results stored green at digests in dir (one <digest>.edn each); none
  for no digests (a branch holding no test: (vals {}) is nil)."
  {:malli/schema [:=> [:cat :string [:maybe [:sequential :string]]] [:map-of :string :map]]}
  [dir digests]
  (into {} (for [dg (distinct digests) :let [f (java.io.File. ^String (result-path dir dg))] :when (.isFile f)
                 :let [r (edn/read-string (slurp f))] :when (green? r)]
             [dg r])))

(defn result-file
  "The write that stores result r ({:test or :step, :digest, :pass :fail :error :ms}) in dir:
  seon.fx/write-file's request {:path :text}, the one encoding greens reads back."
  {:malli/schema [:=> [:cat :string [:map [:digest :string]]] [:map [:path :string] [:text :string]]]}
  [dir r] {:path (result-path dir (:digest r)) :text (sedn/encode r)})

(defn store!
  "Result r written at its digest in dir through the effect owner's file write
  (seon.fx/write-file: temp then rename), in a process with no root (bin/test); an agent asks
  root's :file owner for the same request (result-file). Answers the write's outcome:
  {:value ...}, or its declared :seon.fx/io-error, which the caller records."
  {:malli/schema [:=> [:cat :string [:map [:digest :string]]] :map]}
  [dir r]
  (fx/write-file {:req (result-file dir r)}))

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

(defn shards
  "tests (var symbols) dealt into at most jobs shards, a namespace's tests (and so its fixtures)
  in one, the largest namespace first into the smallest shard; no empty shard."
  {:malli/schema [:=> [:cat [:sequential :qualified-symbol] pos-int?] [:vector [:vector :qualified-symbol]]]}
  [tests jobs]
  (let [by-ns (sort-by (juxt (comp - count val) (comp str key)) (group-by (comp symbol namespace) tests))]
    (vec (remove empty? (reduce (fn [sh [_ ts]] (update sh (apply min-key #(count (sh %)) (range (count sh))) into ts))
                                (vec (repeat (max 1 (min jobs (count by-ns))) [])) by-ns)))))

(defn run-tests!
  "The one clojure.test runner (bin/test's shards, an agent's test fork): run tests (var
  symbols, their namespaces loaded) here, each namespace's by clojure.test/test-vars so its
  fixtures run around them, and tell (note! x) as it goes: {:seon.test/started test} as each
  starts, {:seon.test/report m :test test} for each assertion (clojure.test's report map),
  each result {:test :digest :pass :fail :error :ms} as it ends, and {:seon.test/output text
  :ns n} for a red namespace's clojure.test output. With dir, each result with a digest is
  stored there (store!) as it ends, and a result whose write failed is an :error result naming
  the write's error (never read as stored). Answers {test result}; a test that never reported,
  because its namespace's run threw (a fixture), is an :error result carrying that throw's
  message and data. A run's work budget (Jolt's ticks, :jolt/ticks-spent) ends the whole run:
  that throw is the caller's. The per-namespace counts are volatiles local to its run:
  clojure.test reports through a callback, one thread, no proc."
  {:malli/schema [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] [:maybe :string] :seon.host/fn]
                  [:map-of :qualified-symbol :map]]}
  [tests digests dir note!]
  (let [tset (set tests)]
    (into {} (for [[n ts] (group-by (comp symbol namespace) tests)
                   :let [counts (volatile! {}) ran (volatile! {}) out (java.io.StringWriter.) t0 (volatile! nil)
                         done! (fn [v] (let [r (assoc (merge {:pass 0 :fail 0 :error 0} (@counts v)) :test v :digest (digests v)
                                                      :ms (quot (- (profile/now-ns) @t0) 1000000))
                                             w (when (and dir (:digest r)) (store! dir r))
                                             r (if (:seon.error/type w)
                                                 (-> r (update :error inc) (assoc :message (str "result not stored: " (:seon.error/message w))))
                                                 r)]
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
                         _ (when (or thrown (some #(not (green? (@ran %))) ts)) (note! {:seon.test/output (str out) :ns n}))]
                   tst ts]
               [tst (or (@ran tst)
                        (cond-> {:test tst :digest (digests tst) :pass 0 :fail 0 :error 1 :ms 0
                                 :message (if thrown (str n "'s run threw before this test reported: " (ex-message thrown))
                                              (str n "'s run ended before this test reported"))}
                          (ex-data thrown) (assoc :data (sedn/encode (ex-data thrown)))))]))))
