(ns seon.proofs
  "Proofs: what a change must run, and what a run proved. prove's selection (task
  seon.proofs/prove-runs-what-a-change-reaches, docs/merging.md#a-merge-runs-what-the-change-reaches):
  a key (a test var, or a bin/check step of check-steps) runs only when no green verdict is
  cached at its reach digest (seon.program/reach-digests), in the one cache every checkout
  shares (seon.fx/write-verdicts!, seon.fx/stored-results; docs/merging.md#verdicts-are-a-cache);
  bin/prove, bin/test, bin/check without a run size and an agent's test run all select with plan. And the results of a clojure.test run's report data (the agent's
  run-tests-forked answer): one status per test var, read the same way by the agent's turn
  loop and root's judge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.config :as cfg] [seon.program :as program] [seon.edn :as sedn] [seon.error :as error] [seon.profile :as profile]
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
  else :pass. A test with no assertion has no entry (a proof missing here is :none), nor has a
  test the run's child died in (a report with its :exit, seon.agent/test-run-answer): a death is
  no verdict, so a proof it took is unknown and a test the change never reached is nothing to
  the judge."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] :seon.proof/results]}
  [test-run]
  (let [died (into #{} (keep #(when (:exit %) (test-symbol (:var %)))) (:reports test-run))]
    (reduce (fn [acc {:keys [type var]}]
              (if-let [t (test-symbol var)]
                (if (died t)
                  acc
                  (let [rank {:pass 0 :fail 1 :error 2}]
                    (update acc t #(if (and % (>= (rank %) (rank type))) % type))))
                acc))
            {} (:reports test-run))))

(defn red-reports
  "The failing assertions of the test run for the tests in `tests`, as data for the context."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]] [:set :qualified-symbol]] [:vector :map]]}
  [test-run tests]
  (vec (for [{:keys [type var] :as r} (:reports test-run)
             :when (and (#{:fail :error} type) (tests (test-symbol var)))]
         (-> (select-keys r [:type :message :expected :actual :got]) (assoc :test (test-symbol var))))))

(defn died
  "The tests of the test run whose child died before it ended (a report with an :exit): no verdict,
  so neither green nor red."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] [:set :qualified-symbol]]}
  [test-run]
  (into #{} (keep #(when (:exit %) (test-symbol (:var %)))) (:reports test-run)))

(defn test-run-errors
  "The errors of the test run that name no test: the test run itself failed (its fork died before any
  test started, a namespace did not load), so every test reads as not run. Their messages."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] [:vector :string]]}
  [test-run]
  (vec (for [{:keys [type var message]} (:reports test-run)
             :when (and (= :error type) (nil? (test-symbol var)))]
         (or message "an error with no message"))))

(defn failures
  "The failing assertions of the test run, per test: {test [{:type :message :expected :actual}]},
  the first :seon.config.proofs/max-assertions of each (expected and actual as the test child printed them)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :seon.test/report]]]] :seon.run/failures]}
  [test-run]
  (let [kept (cfg/value :seon.config.proofs/max-assertions)]
   (reduce (fn [acc {:keys [type var] :as r}]
            (let [t (test-symbol var)]
              (if (and t (#{:fail :error} type) (< (count (get acc t)) kept))
                (update acc t (fnil conj []) (select-keys r [:type :message :expected :actual :flaky]))
                acc)))
          {} (:reports test-run))))

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
  functions: its key is the tree's digest (step-digests). health (what the checkout caused since its
  base, seon.problems/caused-split over the program and the wiki, booted on the checkout's source)
  is keyed by the tree and its base's tree (:caused): a moved var leaves the citations of docs/
  dead, which no function's reach shows, and the same tree against another base is another answer.
  forks (bin/forks: the forks' own suites on Jolt) runs no function: its key is :env alone, so a
  pin bump selects it and a change to Seon's .clj program does not."
  '{"laws" :tree "health" :caused "lmdb-kill" [] "lmdb-sem" [] "forks" [] "contracts" [seon.check/contracts] "test-inputs" [seon.check/test-inputs] "mg-check" [seon.check/mg-check] "fx" [seon.check/fx]
    "config" [seon.check/config-check] "system" [seon.check/system-check] "mcp" [seon.check/mcp-proof]
    "threads" [seon.check/threads-check] "instrument" [seon.check/instrument] "record" [seon.check/record]
    "problems" [seon.check/problems-check] "smoke" [seon.check/smoke] "robust" [seon.check/robust]
    "lifecycle" [seon.check/lifecycle] "backpressure" [seon.check/backpressure] "hotreload" [seon.check/hotreload]
    "drain" [seon.check/drain] "idle" [seon.check/idle] "orphan" [seon.check/orphan]
    "durable" [seon.check/durable-write seon.check/durable-read] "window" [seon.check/window-write seon.check/window-read]
    "ids" [seon.check/ids] "vectors" [seon.check/vectors] "e2e-calc" [seon.e2e/run] "e2e-conflict" [seon.e2e/run] "e2e-semantic" [seon.e2e/run]
    "e2e-hairy" [seon.e2e/run] "e2e-newns" [seon.e2e/run] "e2e-sibling" [seon.e2e/run] "swarm" [seon.e2e/run]
    "e2e-loop" [seon.e2e/run] "e2e-held" [seon.e2e/run] "resume" [seon.e2e/run] "scale" [seon.check/scale]})

(def binary-free
  "The steps that run no seon.check mode on target/seon: laws (bin/laws over the files), lmdb-kill
  and lmdb-sem (each a binary of its own), forks (bin/forks, a Jolt process a unit), health
  (bin/test --health, over the program store's kept trees)."
  #{"laws" "lmdb-kill" "lmdb-sem" "forks" "health"})

(defn step-needs-binary?
  "Does bin/check's step run a seon.check mode on target/seon (and a store branch)? Every step
  does but binary-free's, so a run of only those builds nothing and seeds nothing."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [step] (boolean (and (contains? check-steps step) (not (binary-free step)))))

(defn step-digests
  "Of check-steps, {:digests {step digest} :missing [step ...]}: the reach digests (digests-of,
  seon.program/reach-digests over the steps' functions); for a step reading the whole tree, the
  digest of its name and :tree; for one reading what the tree caused, of its name, :tree and
  :base (the base's tree, seon.fx/base-commit; none: the step is missing, never keyed by the tree
  alone); missing, the steps naming a function the program does not define (renamed, not
  imported) or a base there is none of, which a run reports red rather than keying by :env alone."
  {:malli/schema [:=> [:cat :seon.host/fn [:map [:env :string] [:tree :string] [:base {:optional true} [:maybe :string]]]]
                  [:map [:digests [:map-of :string :string]] [:missing [:vector :string]]]]}
  [digests-of {:keys [tree base]}]
  (let [fns (into {} (filter (comp vector? val)) check-steps)
        ds (merge (digests-of fns)
                  (into {} (for [[k v] check-steps :when (= :tree v)] [k (error/digest (str k "\n" tree))]))
                  (when base (into {} (for [[k v] check-steps :when (= :caused v)] [k (error/digest (str k "\n" tree "\n" base))]))))]
    {:digests ds :missing (vec (sort (remove ds (keys check-steps))))}))

(defn green? "Is result r green: no failing or erroring assertion?" {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [r] (and (some? r) (not (red? r))))

(defn flaky
  "The result of a digest that judged both green and red: red (red's counts and failures), with
  :flaky true and one more failure saying so. A test's verdict is a function of its digest, so
  one that differs is a defect at its cause (a clock, a race, an input the digest misses): it is
  judged red, never reused as green, and never run again until a change moves its digest."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [dg red]
  (-> red
      (assoc :flaky true :digest dg)
      ;; first, so no cut of a test's failures (:seon.config.proofs/max-assertions) leaves it out
      (update :failures #(into [{:type :fail :flaky true :message (str "flaky: green and red at digest " dg " (seon.proofs/flaky)")}]
                               (remove :flaky) %))))

(defn verdict
  "The one result of digest dg from what it judged: green and red, flaky (red); either alone, it."
  {:malli/schema [:=> [:cat :string [:maybe :map] [:maybe :map]] [:maybe :map]]}
  [dg green red]
  (cond (:flaky red) red
        (:flaky green) green
        (and green red) (flaky dg red)
        :else (or green red)))

(defn status-of
  "The status a result ({:pass :fail :error}) records: :error when an assertion errored, :fail when one
  failed, else :pass."
  {:malli/schema [:=> [:cat :map] [:enum :pass :fail :error]]}
  [r] (cond (pos? (:error r 0)) :error (pos? (:fail r 0)) :fail :else :pass))

(defn per-call
  "Each function's cost per call over the tests of details ({test {var row}}, seon.fx/details): {var {:calls n :ticks
  :self-ticks :bytes :self-bytes}}, the measures summed over the tests and divided by the calls. A
  recursive function's inclusive ticks and bytes count its inner calls again; its self ones do not."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol [:map-of :qualified-symbol [:tuple :int :int :int :int :int]]]] [:map-of :qualified-symbol :map]]}
  [detail]
  (let [sums (reduce (fn [m [v row]] (update m v #(if % (mapv + % row) row))) {} (mapcat val detail))]
    (into {} (for [[v [calls ticks self-ticks bytes self-bytes]] sums :when (pos? calls)]
               [v {:calls calls :ticks (quot ticks calls) :self-ticks (quot self-ticks calls) :bytes (quot bytes calls) :self-bytes (quot self-bytes calls)}]))))

(defn answer-ran
  "What an agent's test run answer ({:reports :digests :fns}, seon.agent/run-tests-forked's) ran:
  {:ran {test result} :failures {test [assertion]}}, seon.fx/write-verdicts!'s arguments. A test reused at its
  digest ran nothing, and one whose child died while it ran (a report with its :exit) has no
  verdict: a death is unknown, and a result at a digest is reused, so it would never run again."
  {:malli/schema [:=> [:cat :map] [:map [:ran :map] [:failures :map]]]}
  [answer]
  (let [reports {:reports (vec (:reports answer))}
        died (set (keep #(when (:exit %) (test-symbol (:var %))) (:reports answer)))
        reused (set (keep #(when (:reused %) (test-symbol (:var %))) (:reports answer)))
        cost-of (costs reports)]
    {:ran (into {} (for [[t s] (results reports) :let [dg (get-in answer [:digests t])] :when (and dg (not (died t)) (not (reused t)))]
                     (cond-> [t {:test t :digest dg :pass (if (= :pass s) 1 0) :fail (if (= :fail s) 1 0) :error (if (= :error s) 1 0)
                                 :fns (get-in answer [:fns t] [])}]
                       (cost-of t) (assoc-in [1 :cost] (cost-of t)))))
     :failures (failures reports)}))

(defn answer
  "Test run r as its asker is answered (docs/merging.md#verdicts-are-a-cache): its counts, :deferred
  and :key as they are, and of its :reports only what the done rule reads: one :pass report for each
  of proofs that passed, every report of a died test, and the failing reports of the first
  :seon.config.proofs/answer-reds red tests (those among proofs first), each test's first
  :seon.config.proofs/max-assertions. The rest is counted (:reds-left, the red tests left out) and
  is in the verdict cache under the run's digests (an agent reads one whole by
  seon.agent/stored-results). So the answer costs its reds, never the tests run (an agent's whole run
  was about 3 MB in one frame): docs/merging.md#verdicts-are-a-cache. results, failures, costs and
  died read it as they read the whole run."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol]] :map]}
  [r proofs]
  (let [reports (vec (:reports r))
        st (results {:reports reports})
        dead (died {:reports reports})
        reds (vec (sort-by (juxt (comp not proofs) str) (keep (fn [[t s]] (when (not= :pass s) t)) st)))
        bound (cfg/value :seon.config.proofs/answer-reds)
        kept (set (take bound reds))
        per (cfg/value :seon.config.proofs/max-assertions)
        failing (reduce (fn [acc {:keys [type var] :as rep}]
                          (let [t (test-symbol var)]
                            (if (and (kept t) (#{:fail :error} type) (< (count (get acc t)) per))
                              (update acc t (fnil conj []) rep)
                              acc)))
                        {} reports)]
    (cond-> (assoc r :reports (vec (concat (for [t (sort-by str proofs) :when (= :pass (st t))] {:type :pass :var (str "#'" t)})
                                           (filter #(dead (test-symbol (:var %))) reports)
                                           (mapcat val (sort-by (comp str key) failing)))))
      (> (count reds) bound) (assoc :reds-left (- (count reds) bound)))))

;; ---- what the results answer ------------------------------------------------------------

(defn exercising
  "The tests of details ({test {var [calls ticks self-ticks bytes self-bytes]}}, the per-function rows
  of a tree's verdicts: seon.fx/details) that called contracted function v, and how much ({:test :calls
  :ticks :self-ticks :bytes :self-bytes}), most ticks first."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]] :qualified-symbol] [:vector :map]]}
  [details v]
  (vec (sort-by (juxt (comp - :ticks) (comp str :test))
                (for [[t fs] details :let [row (fs v)] :when row
                      :let [[calls ticks self-ticks bytes self-bytes] row]]
                  {:test t :calls calls :ticks ticks :self-ticks self-ticks :bytes bytes :self-bytes self-bytes}))))

(defn left-out
  "The keys of ks a run leaves out: those in process (tests and bin/check steps that reach a
  process of their own: a root, an agent, a fork, the binary; a shell call is none:
  seon.program/reach-index's :process?), but none when the run asks for them (asked?: it names
  their namespaces, or it is a merge's verification) and never one of proofs (DECISIONS, Process
  tests are not automatic). The one filter bin/test's plan (test-runner/plan-of) and an agent's
  (seon.agent/run-selection) apply."
  {:malli/schema [:=> [:cat [:sequential :any] [:set :any] :boolean [:set :any]] [:set :any]]}
  [ks process asked? proofs]
  (if asked? #{} (into #{} (filter #(and (contains? process %) (not (contains? proofs %)))) ks)))

(defn plan
  "What a change runs: of cand ({key digest}, the candidate's), base (the base's {key digest},
  nil: none), found ({digest result}, the verdicts cached at cand's digests: seon.fx/stored-results) and proofs
  (keys that always judge), {:selected :run :reused}. With a base (an agent's branch against the
  main it forked from), selected is the keys whose digest moved or is new, and the proofs cand
  holds: what judges the change. With none (a worktree session's or root's tree), the proofs and every key
  with no green at its digest. run is the selected with no result at their digest (sorted), what
  runs; reused {key result} every key with a result at its digest, green or red (a red reused
  judges red again, with its failures; a flaky one is red, so it is selected and never run). So an
  unchanged tree runs nothing.
  Cost: one lookup per key, then a sort of what is selected."
  {:malli/schema [:=> [:cat [:maybe [:map-of :any :string]] [:map-of :any :string] [:map-of :string :map] [:set :any]]
                  [:map [:selected [:vector :any]] [:run [:vector :any]] [:reused [:map-of :any :map]]]]}
  [base cand found proofs]
  (let [selected (vec (sort-by str (distinct (concat (filter cand proofs)
                                                    (if base
                                                      (remove #(= (base %) (cand %)) (keys cand))
                                                      (remove #(green? (found (cand %))) (keys cand)))))))]
    {:selected selected
     :run (filterv #(not (found (cand %))) selected)
     :reused (into {} (for [[k dg] cand :let [r (found dg)] :when r] [k r]))}))

(defn plan-of
  "What bin/prove and bin/test run on a program: the tests of names (or all) and the bin/check
  steps with no green at their digest (plan), less, when nothing is named, the ones that reach a
  process (left-out). reach is the program's, {:graph :inputs :digests :process :binary :no-test}
  (seon.program/reach-graph and graph-index over the checkout's program facts: the caller's, a
  root's or the runner's own); what is tests, checks or both; all? true runs every key, :merge
  is a merge's verification (every key with no green at its digest, the ones that reach a
  process too, so nothing is left out and none named by hand); results-at, a function of the
  candidates ({key digest}) answering the verdicts cached at their digests ({digest result}:
  seon.fx/stored-results, the caller's read); base the tree this checkout's caused problems are read against (nil: none,
  the health step is missing). Answers {:run-tests :run-steps :reused :cand :process :binary
  :no-test :flaky :left :digests :steps :missing :inputs}."
  {:malli/schema [:=> [:cat :map [:enum "tests" "checks" "both"] [:or :boolean [:= :merge]] [:maybe [:sequential :any]] [:maybe :string] :seon.host/fn] :map]}
  [{:keys [graph inputs digests process binary no-test]} what all? names base results-at]
  (let [tests? (not= what "checks") checks? (not= what "tests")
        ;; the steps' functions read from the tests' graph: their digests and which boot a root or a process
        step-idx (when checks? (program/graph-index graph inputs (into {} (filter (comp vector? val)) check-steps)))
        {steps :digests missing :missing} (when checks? (step-digests #(update-vals (select-keys step-idx (keys %)) :digest) (assoc inputs :base base)))
        process (into (if tests? process #{}) (keep (fn [[k v]] (when (:process? v) k))) step-idx)
        named? (or (true? all?) (seq names))
        merge? (= :merge all?)
        all-cand (merge (when tests? digests) steps)
        cand (apply dissoc all-cand (left-out (vec (keys all-cand)) process (boolean (or named? merge?)) #{}))
        ;; a green is reused and a flaky judged red; a red alone runs again, so a red is never
        ;; reported as if it passed (a run ends on what it ran)
        found (into {} (filter (fn [[_ r]] (or (green? r) (:flaky r)))) (results-at cand))
        {:keys [run reused]} (plan nil cand (if (true? all?) {} found) #{})
        {run-steps true run-tests false} (group-by string? run)]
    {:run-tests (vec run-tests) :run-steps (vec run-steps) :reused reused :cand cand :process process :binary binary :no-test no-test
     ;; green and red at its digest, whatever the plan runs (the full run reruns every key): red
     :flaky (into (sorted-map-by #(compare (str %1) (str %2))) (for [[k dg] cand :let [r (found dg)] :when (:flaky r)] [k r]))
     :left (when-not (or named? merge?) (vec (sort-by str (filter all-cand process))))
     :digests digests :steps steps :missing missing :inputs inputs}))

(def ^:private report
  "clojure.test's own report as this namespace loads (no binding in force), so a run nested in
  a test's run reports through it, never through the enclosing run's binding."
  t/report)

(def ^:dynamic *digests*
  "{test var -> reach digest} of the run-tests! run in progress (bound around each namespace's
  run), read by test-seed: a generative test draws from its own digest."
  {})

(defn seed
  "A test.check seed (a long) that is a function of parts (a key and its reach digest) and the
  config's salt (:seon.config.proofs/seed-salt): the same digest draws the same cases, so a
  result is a function of its digest; a change to what the key reaches draws new ones; the salt
  re-rolls every seed at once (it is config, so it moves every digest too)."
  {:malli/schema [:=> [:cat [:* :any]] :int]}
  [& parts]
  (Long/parseLong (subs (error/digest (sedn/encode (cons (cfg/value :seon.config.proofs/seed-salt) parts))) 0 15) 16))

(defn test-seed
  "The seed of the clojure.test test running now: (seed test digest), its digest the one
  run-tests! runs it at (*digests*; none for a test run another way, which still gets a seed
  its failure names). A generative test passes it as test.check's :seed (or ::mg/=>seed) and
  prints it on failure, its reproduction."
  {:malli/schema [:=> [:cat] :int]}
  []
  (let [v (test-symbol (str (first t/*testing-vars*)))] (seed v (get *digests* v))))

(defn- test-vars
  "clojure.test's test-vars for vars of namespace n (Jolt's: its :once fixtures around them, each
  var in its :each fixtures), each test with a budget of its own: ticks engine ticks of work (nil:
  unbounded), inside its :each fixtures, so their teardown runs whatever the test did. A test past
  it reports one :error of the typed :seon.test/ticks-spent naming it, and ends (its
  :end-test-var), so the next test runs. The namespace's fixtures are bounded too: the whole
  namespace within ticks per test and one more, so a fixture that never ends stops the namespace,
  its unreported tests :error (run-tests!). Each test, its budget's end included, is measured by
  seon.profile/measure!, the one measurement of a call, and told (done v measured) once it ended,
  measured carrying :fns, what each contracted function it called cost (seon.profile/touched).
  Each measure opens its own epoch of contracted calls (seon.profile/begin!), so a caller's epoch
  open around an in-process run counts from the last test on (bin/test's shards open none)."
  {:malli/schema [:=> [:cat :symbol [:sequential :any] [:maybe pos-int?] :seon.host/fn] :nil]}
  [n vars ticks done]
  (let [each (t/join-fixtures (get @t/each-fixtures n []))
        one (fn [v]
              (try (jolt.host/run-interruptible (jolt.host/make-interrupt) #(t/test-var v) ticks)
                   (catch Throwable e
                     (if-let [spent (:jolt/ticks-spent (ex-data e))]
                       (t/do-report {:type :error :var v :seon.error/type :seon.test/ticks-spent :ticks spent :budget ticks
                                     :message (str "the test spent its budget of " ticks " engine ticks (work done, not time): a test that never ends, or more work than one test may do")})
                       (throw e)))))
        ;; a test whose run threw past its budget is not done: it stays unreported (run-tests!)
        ;; read before the next test's measure opens its epoch: the test's own contracted calls
        measured (fn [v] (let [m (volatile! nil)] (profile/measure! #(one v) #(vreset! m %)) (done v (assoc @m :fns (profile/touched)))))
        body #((t/join-fixtures (get @t/once-fixtures n []))
               (fn [] (doseq [v vars :when (:test (meta v))] (each (fn [] (measured v))))))]
    (if ticks
      (jolt.host/run-interruptible (jolt.host/make-interrupt) body (* ticks (inc (count vars))))
      (body))
    nil))

(defn run-tests!
  "The one clojure.test runner (bin/test's shards, an agent's test fork): run tests (var
  symbols, their namespaces loaded) here, each namespace's by clojure.test/test-vars so its
  fixtures run around them, and tell (note! x) as it goes: {:seon.test/started test} as each
  starts, {:seon.test/report m :test test} for each assertion (clojure.test's report map),
  each result {:test :digest :pass :fail :error :ms} as it ends, and {:seon.test/output text
  :ns n} for a red namespace's clojure.test output. It only runs: its caller caches the verdicts
  (seon.fx/write-verdicts!). Answers {test result}; a test that never reported, because its namespace's
  run threw (a fixture), is an :error result (:unreported true: no verdict of its own, never
  stored) and an :error report carrying that throw's message and data. Each test runs within ticks engine ticks of work of its own (Jolt's
  run-interruptible; nil or 0: unbounded), so a test's verdict is a function of the test alone,
  never of what ran before it in the run: one bound wherever it runs (bin/test, an agent's turn,
  the judge). A test past it is an :error report of the typed :seon.test/ticks-spent naming it,
  and the run goes on to the next (test-vars, which bounds a namespace's fixtures too: a throw
  that ends a namespace, its budget's included, makes its unreported tests :error). Each result
  carries the :ticks and :bytes it did and :fns, each contracted function it called with its calls,
  ticks and bytes, in all and its own (seon.profile/touched). The per-namespace counts are volatiles local to its run: clojure.test
  reports through a callback, one thread, no proc."
  {:malli/schema [:function [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn]
                             [:map-of :qualified-symbol :map]]
                  [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn [:maybe nat-int?]]
                   [:map-of :qualified-symbol :map]]]}
  ([tests digests note!] (run-tests! tests digests note! nil))
  ([tests digests note! ticks]
  (let [tset (set tests) ticks (when (and ticks (pos? ticks)) ticks)]
    (into {} (for [[n ts] (group-by (comp symbol namespace) tests)
                   :let [counts (volatile! {}) ran (volatile! {}) out (java.io.StringWriter.)
                         done! (fn [tv m] (let [v (test-symbol (str tv))
                                                r (assoc (merge {:pass 0 :fail 0 :error 0} (@counts v)) :test v :digest (digests v)
                                                         :ms (quot (:wall-ns m) 1000000) :ticks (:ticks m) :bytes (:bytes m) :fns (:fns m))]
                                            (vswap! ran assoc v r)
                                            (note! r)))
                         thrown (binding [t/*test-out* out
                                          *digests* digests
                                          t/report (fn [m]
                                                     ;; a test running tests of its own (test-ns inside a deftest) reports
                                                     ;; them too: only this run's tests are counted
                                                     (let [v (some-> (or (:var m) (first t/*testing-vars*)) str test-symbol)
                                                           v (when (tset v) v)]
                                                       (case (when v (:type m))
                                                         :begin-test-var (note! {:seon.test/started v})
                                                         (:pass :fail :error) (do (vswap! counts update-in [v (:type m)] (fnil inc 0))
                                                                                  (note! {:seon.test/report m :test v}))
                                                         nil))
                                                     (binding [t/*test-out* out] (report m)))]
                                  (try (test-vars n (keep #(ns-resolve n (symbol (name %))) ts) ticks done!) nil
                                       (catch Throwable e e)))
                         unreported (for [tst ts :when (not (@ran tst))]
                                      (let [msg (if thrown (str n "'s run threw before this test reported: " (ex-message thrown))
                                                    (str n "'s run ended before this test reported"))]
                                        (note! {:seon.test/report {:type :error :message msg} :test tst})
                                        [tst (cond-> {:test tst :digest (digests tst) :pass 0 :fail 0 :error 1 :ms 0 :message msg :unreported true}
                                               (ex-data thrown) (assoc :data (sedn/encode (ex-data thrown))))]))
                         result (into @ran unreported)
                         _ (when (or thrown (some #(not (green? (result %))) ts)) (note! {:seon.test/output (str out) :ns n}))]
                   tst ts]
               [tst (result tst)])))))
