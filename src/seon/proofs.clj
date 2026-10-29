(ns seon.proofs
  "Proofs: what a change must run, and what a run proved. prove's selection (task
  seon.proofs/prove-runs-what-a-change-reaches, docs/merging.md#a-merge-runs-what-the-change-reaches):
  a key (a test var, or a bin/check step of check-steps) runs only when no green verdict is
  cached at its reach digest (seon.program/graph-index), in the one cache every checkout
  shares (seon.fx/write-verdicts!, seon.fx/stored-results; docs/merging.md#verdicts-are-a-cache);
  bin/prove, bin/test, bin/check without a run size and an agent's test run all select with plan. And the results of a clojure.test run's report data (the agent's
  run-tests-forked answer): one status per test var, read the same way by the agent's turn
  loop and root's judge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.config :as cfg] [seon.program :as program] [seon.edn :as sedn] [seon.error :as error] [seon.profile :as profile] [seon.print :as print]
            [clojure.edn :as edn] [clojure.string :as str] [clojure.test :as t] [malli.core :as m] [malli.generator :as mg]))

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
  "The failing assertions of the test run, per test: {test [{:type :message :expected :actual}]}, with the
  value the test got (:got) and a typed error's :seon.error/type, so a red reused at its digest reads as its run did;
  every one of each (expected and actual as the test child printed them)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential {:gen/max 4} :seon.test/report]]]] :seon.run/failures]}
  [test-run]
  (reduce (fn [acc {:keys [type var] :as r}]
            (let [t (test-symbol var)]
              (if (and t (#{:fail :error} type))
                (update acc t (fnil conj []) (select-keys r [:type :message :expected :actual :got :flaky :seon.error/type]))
                acc)))
          {} (:reports test-run)))

(defn costs
  "The cost proofs' reports of the test run, per test ({:rows :verdict}, kept as data)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential {:gen/max 4} :seon.test/report]]]] :seon.run/costs]}
  [test-run]
  (into {} (for [{:keys [var cost]} (:reports test-run) :let [t (test-symbol var)] :when (and t cost)] [t cost])))

;; ---- prove: run what a change reaches, reuse every green by digest ------------------------

(def check-steps
  "bin/check's steps, the one list of them (bin/check reads it from ./seon steps and fails a
  step it does not name), as prove keys them: each step name (a bin/check --steps unit) to the
  seon.check mode functions its body runs, whose reach is the step's (seon.program/
  graph-index with roots), with the checkout's :env (bin/, native/, resources/, the pins:
  the binary's other inputs) in every digest. laws (bin/laws) reads the whole tree and has no
  functions: its key is the tree's digest (step-digests). health (what the checkout caused since its
  base, seon.problems/caused-split over the program and the wiki, booted on the checkout's source)
  is keyed by the tree and its base's tree (:caused): a moved var leaves the citations of docs/
  dead, which no function's reach shows, and the same tree against another base is another answer.
  forks (bin/forks: the forks' own suites on Jolt) and the lmdb steps run no function: their key
  is :pins, :env and every submodule's pin (the :seon.pin classes of seon.fx/inputs' :config), so
  a pin bump selects them and a change to Seon's .clj program does not. mg-check (:mg) has no key of
  its own: its keys are one per pure contracted function (mg-roots) and the schema registry (mg-registry),
  each digested by what it reaches and run in bin/test's shards beside the tests (mg-run); the step runs
  them all, in the binary, for bin/check mg-check and the full run (docs/checks.md#the-generative-check-is-per-function)."
  '{"laws" :tree "health" :caused "lmdb-kill" :pins "lmdb-sem" :pins "forks" :pins "contracts" [seon.check/contracts] "test-inputs" [seon.check/test-inputs] "mg-check" :mg "fx" [seon.check/fx]
    "config" [seon.check/config-check] "system" [seon.check/system-check] "mcp" [seon.check/mcp-proof]
    "threads" [seon.check/threads-check] "instrument" [seon.check/instrument] "record" [seon.check/record]
    "problems" [seon.check/problems-check] "smoke" [seon.check/smoke] "robust" [seon.check/robust]
    "lifecycle" [seon.check/lifecycle] "backpressure" [seon.check/backpressure] "hotreload" [seon.check/hotreload]
    "drain" [seon.check/drain] "idle" [seon.check/idle] "orphan" [seon.check/orphan]
    "durable" [seon.check/durable-write seon.check/durable-read] "window" [seon.check/window-write seon.check/window-read]
    "ids" [seon.check/ids] "vectors" [seon.check/vectors] "e2e-calc" [seon.e2e/run] "e2e-conflict" [seon.e2e/run] "e2e-semantic" [seon.e2e/run]
    "e2e-hairy" [seon.e2e/run] "e2e-newns" [seon.e2e/run] "e2e-sibling" [seon.e2e/run] "swarm" [seon.e2e/run]
    "e2e-loop" [seon.e2e/run] "e2e-held" [seon.e2e/run] "resume" [seon.e2e/run] "scale" [seon.check/scale]})

(def pure-nss
  "The namespaces whose contracted functions are pure functions of plain data: mg/check
  calls them with generated arguments. Every other function touches a host object (a
  socket, a process, the store) and is never called with random arguments: a generated pid
  handed to kill(2) is a real signal. See docs/checks.md#the-generative-check-is-per-function."
  '#{seon.merge seon.reconcile seon.template seon.task seon.step seon.ref})

(def pure-fns
  "Pure functions in namespaces that are otherwise host code (seon.lib binds libc: never
  generate its arguments; seon.proofs runs tests and reads and writes results). See
  docs/checks.md#the-generative-check-is-per-function."
  '#{seon.lib/percentile seon.lib/r2 seon.lib/summ seon.lib/push-window seon.lib/p50
     seon.cost/work seon.cost/exponent seon.cost/per-unit seon.cost/expected seon.cost/judge seon.profile/top
     ;; seon.proofs runs tests (run-tests!): its pure part (the verdict cache is seon.fx's)
     seon.proofs/test-symbol seon.proofs/red? seon.proofs/results seon.proofs/red-reports seon.proofs/test-run-errors
     seon.proofs/failures seon.proofs/costs seon.proofs/green? seon.proofs/per-call
     seon.proofs/plan})

(defn mg-key?
  "Is k a key of the generative check: \"mg/<qualified-fn>\" (mg-roots) or mg-registry? See docs/checks.md#the-generative-check-is-per-function."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [k] (boolean (and (string? k) (str/starts-with? k "mg/"))))

(defn mg-roots
  "The generative check's keys on a reach graph g (seon.program/reach-graph), as graph-index's roots:
  {\"mg/<fn>\" [fn]} for each contracted var that is pure (pure-nss, pure-fns) and whose contract is
  not a proc's step-fn (a proc is tested as a plain function on its own states, seon.procs-test).
  A key's digest is its function's reach (its form, what it calls, the schemas its contract names)
  as a test's is, so a change re-checks only the contracts it reaches. Cost: one pass over the
  graph's contracted vars. See docs/checks.md#the-generative-check-is-per-function."
  {:malli/schema [:=> [:cat [:map [:contract :map]]] [:map-of :string [:vector :qualified-symbol]]]}
  [g]
  (into (sorted-map)
        (for [[v sids] (:contract g)
              :when (and (or (pure-nss (symbol (namespace v))) (pure-fns v))
                         (not= ["schema :seon.step/step-fn"] (vec sids)))]
          [(str "mg/" v) [v]])))

(defn mg-fn
  "The qualified symbol an mg key names (\"mg/seon.merge/f\" -> seon.merge/f). See docs/checks.md#the-generative-check-is-per-function."
  {:malli/schema [:=> [:cat :string] :qualified-symbol]}
  [k] (symbol (subs k 3)))

(def mg-registry
  "The generative check's key of the schema registry: no [:fn pred] schema lacks a :gen
  (mg-registry-gaps). Its digest is the registry (mg-registry-digest), so a schema change selects it and
  nothing else does. See docs/checks.md#the-generative-check-is-per-function."
  "mg/registry")

(defn mg-registry-digest
  "The digest of the schema registry of reach graph g (its schema entries: key and text, in key order) under env. See docs/checks.md#the-generative-check-is-per-function."
  {:malli/schema [:=> [:cat [:map [:schemas :map]] :string] :string]}
  [g env]
  (error/digest (str mg-registry "\n" env "\n"
                     (str/join "\n" (for [[k text] (sort-by (comp str key) (:schemas g))] (str k " " text))))))

(defn- fn-without-gen
  "The [:fn ...] schemas without a :gen property in form f, anywhere in it."
  [f] (filter #(and (vector? %) (= :fn (first %)) (not (:gen (when (map? (second %)) (second %))))) (tree-seq coll? seq f)))

(defn mg-registry-gaps
  "The keys of the platform's schema forms (seon.contract/forms) that hold a [:fn pred] without :gen: none can be generated. See docs/checks.md#the-generative-check-is-per-function."
  {:malli/schema [:=> [:cat] [:vector :any]]}
  [] (vec (for [[k f] contract/forms :when (seq (fn-without-gen f))] k)))

(declare seed)

(defn mg-run
  "Run the generative check's key k (\"mg/<fn>\" or mg-registry) at digest: {:key :digest :status :ms}, :status :ok, :skipped
  (a proc's step-fn, which is tested as a plain function on its own states, or a contract whose arguments do
  not generate: nothing to fail) or :failed, with :failure {:problem ...} and, from malli.generator/check, its
  :smallest, :seed and the :reproduce form. A function is loaded (its namespace required) and checked by
  malli.generator/check, 100 runs, from the seed (seed fn digest): the same digest checks the same cases. The one
  check bin/test's shards and bin/check mg-check run (docs/checks.md#the-generative-check-is-per-function)."
  {:malli/schema [:=> [:cat :string [:maybe :string]] [:map [:key :string] [:status [:enum :ok :skipped :failed]] [:ms :int]]]}
  [k digest]
  (let [t0 (profile/now-ns)
        done (fn [status failure] (cond-> {:key k :digest digest :status status :ms (max 1 (long (profile/ms-since t0)))}
                                    failure (assoc :failure failure)))]
    (if (= k mg-registry)
      (let [gaps (mg-registry-gaps)]
        (done (if (seq gaps) :failed :ok) (when (seq gaps) {:problem (str "[:fn] schemas without :gen: " (sedn/encode gaps))})))
      (let [sym (mg-fn k)
            v (do (require (symbol (namespace sym))) (ns-resolve (symbol (namespace sym)) (symbol (name sym))))
            c (when v (contract/contract v))]
        (cond
          (nil? c) (done :failed {:problem (str sym " is not a contracted function of the program")})
          (seq (fn-without-gen c)) (done :failed {:problem (str sym "'s contract holds a [:fn] without :gen")})
          (= :seon.step/step-fn c) (done :skipped nil)
          :else
          (let [schema (m/deref (contract/schema c))
                generates? (try (every? #(some? (mg/generator (:input (m/-function-info %)) {:registry contract/registry}))
                                        (m/-function-schema-arities schema))
                                (catch Throwable _ false))]
            (if-not generates?
              (done :skipped nil)
              (let [r (try (mg/check schema @v {:registry contract/registry :malli.generator/=>seed (seed sym digest)})
                           (catch Throwable e {:smallest :threw :problem (ex-message e)}))]
                (cond (nil? r) (done :ok nil)
                      ;; malli could not generate an [:and ...] argument (such-that): not generated, not failed
                      (= ":malli.generator/such-that-failure" (:problem r)) (done :skipped nil)
                      (= :threw (:smallest r)) (done :failed r)
                      :else (let [ch (:check (first (:errors r)))]
                              (done :failed {:smallest (:smallest ch) :problem (some-> (:malli.core/result ch) ex-message) :seed (:malli.generator/seed ch)
                                             :reproduce (list 'malli.generator/check (list 'm/deref (list 'seon.contract/schema (list 'seon.contract/contract (list 'var sym))))
                                                              sym {:registry 'seon.contract/registry :malli.generator/=>seed (:malli.generator/seed ch)})}))))))))))) 

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
  seon.program/graph-index over the steps' functions); for a step reading the whole tree, the
  digest of its name and :tree; for one reading what the tree caused, of its name, :tree and
  :base (the base's tree, seon.fx/base-commit; none: the step is missing, never keyed by the tree
  alone); missing, the steps naming a function the program does not define (renamed, not
  imported) or a base there is none of, which a run reports red rather than keying by :env alone."
  {:malli/schema [:=> [:cat :seon.host/fn [:map [:env :string] [:tree :string] [:base {:optional true} [:maybe :string]]]]
                  [:map [:digests [:map-of :string :string]] [:missing [:vector :string]]]]}
  [digests-of {:keys [tree base env config]}]
  (let [fns (into {} (filter (comp vector? val)) check-steps)
        pins (str/join "\n" (for [[c d] (sort-by (comp str key) config) :when (= "seon.pin" (namespace c))] (str c " " d)))
        ds (merge (digests-of fns)
                  (into {} (for [[k v] check-steps :when (= :tree v)] [k (error/digest (str k "\n" tree))]))
                  (into {} (for [[k v] check-steps :when (= :pins v)] [k (error/digest (str k "\n" env "\n" pins))]))
                  (when base (into {} (for [[k v] check-steps :when (= :caused v)] [k (error/digest (str k "\n" tree "\n" base))]))))]
    {:digests ds :missing (vec (sort (remove #(or (ds %) (= :mg (check-steps %))) (keys check-steps))))}))

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
      ;; first, so it leads the test's failures
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
  {:malli/schema [:=> [:cat [:map-of {:gen/max 4} :qualified-symbol [:map-of {:gen/max 4} :qualified-symbol [:tuple :int :int :int :int :int]]]] [:map-of :qualified-symbol :map]]}
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

(defn- test-facts
  "The :seon.test/* attributes a test's result sets (task seon.proofs/test-results-are-a-few-facts), given old (the entity as the branch holds it, or nil)
  and new ({:digest :status :failures :measure {:ms :ticks :bytes}}; no :measure for a reused result, which ran
  nothing): the digest, the status (a different status at the same digest is :flaky), the failing assertions while not
  green (as data, seon.proofs/failures' shape), and the sums, which a new digest resets to this run and the same digest adds to."
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [old {:keys [digest status failures measure]}]
  (let [same? (= digest (:seon.test/digest old))
        status (if (and same? measure (:seon.test/status old) (not= status (:seon.test/status old))) :flaky status)
        sums (cond (and same? measure) (merge-with + (select-keys old [:seon.test/runs :seon.test/ms :seon.test/ticks :seon.test/bytes])
                                                   {:seon.test/runs 1 :seon.test/ms (:ms measure 0) :seon.test/ticks (:ticks measure 0) :seon.test/bytes (:bytes measure 0)})
                   measure {:seon.test/runs 1 :seon.test/ms (:ms measure 0) :seon.test/ticks (:ticks measure 0) :seon.test/bytes (:bytes measure 0)}
                   same? (select-keys old [:seon.test/runs :seon.test/ms :seon.test/ticks :seon.test/bytes]))
        fs (or (not-empty failures) (when same? (:seon.test/failures old)))]
    (cond-> (assoc sums :seon.test/digest digest :seon.test/status status)
      (and (not= :green status) (seq fs)) (assoc :seon.test/failures fs))))

(def ^:private test-attrs
  "The :seon.test/* attributes a test's var entity holds (seon.proofs/result-tx). See docs/merging.md#verdicts-are-a-cache."
  [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/runs :seon.test/ms :seon.test/ticks :seon.test/bytes])

(defn result-tx
  "The transaction that records a test run's results on the branch that ran it (docs/merging.md#verdicts-are-a-cache,
  task seon.proofs/test-results-are-a-few-facts): for each test of ran ({test result}, answer-ran's, with its :ms
  :ticks :bytes) its digest, status, failing assertions (failures, {test [assertion]}, as data) and sums; for each of
  reused ({test result}, a result the cache held at its digest) its digest, status and the failures it kept, so a
  freshly branched agent's proofs show their reused status; for each of deferred (the process tests the
  run left to root's gate) its digest and :deferred. A status means the verdict at the test's reach digest
  now: each other test old holds whose recorded digest is not its digest in digests ({test digest}, the run's plan:
  every judging test's) loses its facts, so a stray red whose callee moved reads unknown, never red. old is {test
  entity} as the branch holds each now (:seon.test/* attributes; absent for a test it holds none of). Only what
  differs is written: [] when nothing does."
  {:malli/schema [:function [:=> [:cat [:map-of :qualified-symbol [:maybe :map]] :map :map :map [:map-of :qualified-symbol :string]] [:vector :any]]
                  [:=> [:cat [:map-of :qualified-symbol [:maybe :map]] :map :map :map [:map-of :qualified-symbol :string] [:set :qualified-symbol]] [:vector :any]]]}
  ([old ran failures reused digests] (result-tx old ran failures reused digests #{}))
  ([old ran failures reused digests deferred]
  (let [news (-> (into {} (for [[t r] ran :when (:digest r)]
                            [t {:digest (:digest r) :status (if (= :pass (status-of r)) :green :red) :failures (failures t)
                                :measure (select-keys r [:ms :ticks :bytes])}]))
                 (into (for [[t r] reused :when (and (:digest r) (not (contains? ran t)))]
                         [t {:digest (:digest r) :status (cond (:flaky r) :flaky (green? r) :green :else :red)
                             :failures (when-not (green? r) (:failures r))}]))
                 ;; a process test the run left to root's gate: that verdict at its digest
                 (into (for [t deferred :let [dg (digests t)] :when (and dg (not (contains? ran t)) (not (contains? reused t)))]
                         [t {:digest dg :status :deferred}])))
        stale (for [[t o] old :when (and (:seon.test/digest o) (not (contains? news t)) (not= (:seon.test/digest o) (digests t)))]
                t)]
    (into (vec (mapcat (fn [[t n]]
                         (let [o (old t) facts (test-facts o n)
                               set-attrs (into {} (remove (fn [[k v]] (= v (k o)))) facts)
                               gone (for [[k v] (select-keys o [:seon.test/runs :seon.test/ms :seon.test/ticks :seon.test/bytes :seon.test/failures])
                                          :when (not (contains? facts k))]
                                      [:db/retract [:var/name t] k v])]
                           (cond-> (vec gone) (seq set-attrs) (conj (assoc set-attrs :var/name t)))))
                       (sort-by (comp str key) news)))
          (for [t (sort-by str stale) [k v] (select-keys (old t) test-attrs)] [:db/retract [:var/name t] k v])))))

(defn answer
  "Test run r as its asker is answered (docs/merging.md#verdicts-are-a-cache): its counts, :deferred
  and :key as they are, and of its :reports only what the done rule reads: one :pass report for each
  of proofs that passed, every report of a died test, and the failing reports of every
  red test, every failing assertion of each. The verdict cache holds the run whole under its
  digests (an agent reads one by seon.agent/stored-results). So the answer costs its reds, never the tests run (an agent's whole run
  was about 3 MB in one frame): docs/merging.md#verdicts-are-a-cache. results, failures, costs and
  died read it as they read the whole run."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol]] :map]}
  [r proofs]
  (let [reports (vec (:reports r))
        st (results {:reports reports})
        dead (died {:reports reports})
        reds (set (keep (fn [[t s]] (when (not= :pass s) t)) st))
        failing (reduce (fn [acc {:keys [type var] :as rep}]
                          (let [t (test-symbol var)]
                            (if (and (reds t) (#{:fail :error} type))
                              (update acc t (fnil conj []) rep)
                              acc)))
                        {} reports)]
    (assoc r :reports (vec (concat (for [t (sort-by str proofs) :when (= :pass (st t))] {:type :pass :var (str "#'" t)})
                                           (filter #(dead (test-symbol (:var %))) reports)
                                           (mapcat val (sort-by (comp str key) failing)))))))

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
  seon.program/graph-index's :process?), but none when the run asks for them (asked?: it names
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
  {:malli/schema [:=> [:cat [:maybe [:map-of {:gen/max 4} :any :string]] [:map-of {:gen/max 4} :any :string] [:map-of {:gen/max 4} :string :map] [:set {:gen/max 4} :any]]
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
        ;; each pure contracted function is a key of its own (mg-roots), indexed beside the steps
        mg (when checks? (mg-roots graph))
        step-idx (when checks? (program/graph-index graph inputs (merge mg (into {} (filter (comp vector? val)) check-steps))))
        {steps :digests missing :missing} (when checks? (step-digests #(update-vals (select-keys step-idx (keys %)) :digest) (assoc inputs :base base)))
        steps (when checks? (assoc (merge steps (update-vals (select-keys step-idx (keys mg)) :digest)) mg-registry (mg-registry-digest graph (:env inputs))))
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
  {:malli/schema [:=> [:cat :symbol [:sequential :any] [:maybe pos-int?] [:maybe [:map-of :qualified-symbol pos-int?]] :seon.host/fn] :nil]}
  [n vars ticks budgets done]
  (let [each (t/join-fixtures (get @t/each-fixtures n []))
        ;; a test's own budget: its history's (budgets, seon.fx/test-budgets), raised to the
        ;; ^{:seon.test/ticks n} on its deftest (in its digest, being its source), never past ticks
        budget (fn [v] (when ticks (min ticks (max (get budgets (test-symbol (str v)) ticks) (or (:seon.test/ticks (meta v)) 0)))))
        one (fn [v]
              (try (jolt.host/run-interruptible (jolt.host/make-interrupt) #(t/test-var v) (budget v))
                   (catch Throwable e
                     (if-let [spent (:jolt/ticks-spent (ex-data e))]
                       (t/do-report {:type :error :var v :seon.error/type :seon.test/ticks-spent :ticks spent :budget (budget v)
                                     :message (str "the test spent its budget of " (budget v) " engine ticks (work done, not time): max(floor, factor x its median green ticks) capped by the config's test-ticks "
                                                   "(docs/merging.md#a-tests-tick-budget). A test that never ends, or more work than one test may do: fix the loop, or, for work it needs, "
                                                   "mark the deftest ^{:seon.test/ticks N}")})
                       (throw e)))))
        ;; a test whose run threw past its budget is not done: it stays unreported (run-tests!)
        ;; read before the next test's measure opens its epoch: the test's own contracted calls
        measured (fn [v] (let [m (volatile! nil)] (profile/measure! #(one v) #(vreset! m %)) (done v (assoc @m :fns (profile/touched) :budget (budget v)))))
        body #((t/join-fixtures (get @t/once-fixtures n []))
               (fn [] (doseq [v vars :when (:test (meta v))] (each (fn [] (measured v))))))]
    (if ticks
      (jolt.host/run-interruptible (jolt.host/make-interrupt) body (+ ticks (reduce + (map budget vars))))
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
  stored) and an :error report carrying that throw's message and data. Each test runs within its budget of engine ticks of work of its own (Jolt's
  run-interruptible): the one from budgets ({test ticks}, seon.fx/test-budgets: from the test's own
  green history, docs/merging.md#a-tests-tick-budget) else ticks, and never more than ticks (nil or 0:
  unbounded). The budget used is on the result (:budget). A test's verdict is a function of the test
  alone, never of what ran before it in the run: one bound wherever it runs (bin/test, an agent's turn,
  the judge). A test past it is an :error report of the typed :seon.test/ticks-spent naming it,
  and the run goes on to the next (test-vars, which bounds a namespace's fixtures too: a throw
  that ends a namespace, its budget's included, makes its unreported tests :error). Each result
  carries the :ticks and :bytes it did and :fns, each contracted function it called with its calls,
  ticks and bytes, in all and its own (seon.profile/touched). The per-namespace counts are volatiles local to its run: clojure.test
  reports through a callback, one thread, no proc."
  {:malli/schema [:function [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn]
                             [:map-of :qualified-symbol :map]]
                  [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn [:maybe nat-int?]]
                   [:map-of :qualified-symbol :map]]
                  [:=> [:cat [:sequential :qualified-symbol] [:map-of :qualified-symbol [:maybe :string]] :seon.host/fn [:maybe nat-int?] [:maybe [:map-of :qualified-symbol pos-int?]]]
                   [:map-of :qualified-symbol :map]]]}
  ([tests digests note!] (run-tests! tests digests note! nil nil))
  ([tests digests note! ticks] (run-tests! tests digests note! ticks nil))
  ([tests digests note! ticks budgets]
  (let [tset (set tests) ticks (when (and ticks (pos? ticks)) ticks)]
    (into {} (for [[n ts] (group-by (comp symbol namespace) tests)
                   :let [counts (volatile! {}) ran (volatile! {}) out (java.io.StringWriter.)
                         done! (fn [tv m] (let [v (test-symbol (str tv))
                                                r (assoc (merge {:pass 0 :fail 0 :error 0} (@counts v)) :test v :digest (digests v)
                                                         :ms (quot (:wall-ns m) 1000000) :ticks (:ticks m) :bytes (:bytes m) :fns (:fns m) :budget (:budget m))]
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
                                  (try (test-vars n (keep #(ns-resolve n (symbol (name %))) ts) ticks budgets done!) nil
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
