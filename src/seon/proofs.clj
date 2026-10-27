(ns seon.proofs
  "Proofs: what a change must run, and what a run proved. prove's selection (task
  seon.proofs/prove-runs-what-a-change-reaches, docs/merging.md#a-merge-runs-what-the-change-reaches):
  a key (a test var, or a bin/check step of check-steps) runs only when no green result is
  recorded at its reach digest (seon.program/reach-digests) on main, where every
  checkout's results land, the standing root's main (record!, stored-results); bin/prove, bin/test, bin/check without a run size and an agent's test run all
  select with plan. And the results of a clojure.test run's report data (the agent's
  run-tests-forked answer): one status per test var, read the same way by the agent's turn
  loop and root's judge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.config :as cfg] [seon.program :as program] [seon.edn :as sedn] [seon.error :as error] [seon.profile :as profile]
            [clojure.edn :as edn] [datahike.api :as d] [clojure.string :as str] [clojure.test :as t]))

(defn test-symbol
  "The qualified symbol of a report's :var (\"#'ns/name\" or \"ns/name\"), or nil."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :qualified-symbol]]}
  [v]
  (when v
    (let [s (str/replace v #"^#'" "")]
      (when (str/includes? s "/") (symbol s)))))

(defn latest-status
  "Test t's last result on main as db holds it (its :seon.proof/latest's status, which only main's
  runs write, so a branch holds main's as of its base), or :none when main holds none."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :keyword]}
  [db t] (or (:seon.proof/status (d/pull db [:seon.proof/status] [:seon.proof/latest (str t)])) :none))

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
  and lmdb-sem (each a binary of its own), forks (bin/forks, a Jolt process a unit)."
  #{"laws" "lmdb-kill" "lmdb-sem" "forks"})

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

(defn stored-results
  "{digest result} of the results db holds at digests (:seon.proof/ facts, results-tx's): one AVET
  seek and one EAVT seek per digest. A green is {:digest :pass 1 :fail 0 :error 0}; a red is its status
  counted once, with the failures it recorded (:failures [{:type :message :expected :actual}]) and, for
  a cost proof, the verdict it was judged by (:cost {:rows :verdict}); a
  digest recorded both green (:seon.proof/green) and red (:seon.proof/red) is flaky, whichever came
  last. A test's verdict is a function of its digest (one per-test tick bound everywhere), so a red
  is reused as a green is."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:map-of :string :map]]}
  [db digests]
  (into {} (for [dg (distinct digests) :let [e (:e (first (d/datoms db :avet :seon.proof/digest dg)))]
                 :when e
                 ;; the entity's datoms, one EAVT seek (a pull costs several times it)
                 :let [{g :seon.proof/green red-s :seon.proof/red fs :seon.proof/failures cost :seon.proof/cost}
                       (into {} (comp (filter #(#{:seon.proof/green :seon.proof/red :seon.proof/failures :seon.proof/cost} (:a %))) (map (juxt :a :v))) (d/datoms db :eavt e))
                       green (when g {:digest dg :pass 1 :fail 0 :error 0})
                       red (when red-s (cond-> {:digest dg :pass 0 :fail (if (= :fail red-s) 1 0) :error (if (= :error red-s) 1 0)
                                                :failures (vec (remove :flaky fs))}
                                         cost (assoc :cost cost)))
                       r (verdict dg green red)]
                 :when r]
             [dg r])))

(defn last-ms
  "{test ms} of tests: each one's newest recorded :seon.proof/ms, over every digest it ran at (one
  AVET seek a test, :seon.proof/test indexed): what orders a run's deal of tests into shards, never
  what a test proves. A test with no result is left out."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :qualified-symbol]] [:map-of :qualified-symbol :int]]}
  [db tests]
  (into {} (for [t (distinct tests)
                 :let [ms (->> (d/datoms db :avet :seon.proof/test t)
                               (keep #(first (d/datoms db :eavt (:e %) :seon.proof/ms)))
                               (sort-by :tx) last :v)]
                 :when ms]
             [t ms])))

(defn status-of
  "The status a result ({:pass :fail :error}) records: :error when an assertion errored, :fail when one
  failed, else :pass."
  {:malli/schema [:=> [:cat :map] [:enum :pass :fail :error]]}
  [r] (cond (pos? (:error r 0)) :error (pos? (:fail r 0)) :fail :else :pass))

(defn detail
  "What each contracted function cost each test of ran ({key result}, a result's :fns:
  seon.profile/touched's rows), as one value: {test {var [calls ticks self-ticks bytes self-bytes]}}.
  A run's one out-of-line value (:seon.proof.run/detail), never a datom a row."
  {:malli/schema [:=> [:cat [:map-of :any :map]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]]]}
  [ran]
  (into {} (for [[_ r] ran :when (and (:test r) (seq (:fns r)))]
             [(:test r) (into {} (for [{:keys [var calls ticks self-ticks bytes self-bytes]} (:fns r)] [var [calls ticks self-ticks bytes self-bytes]]))])))

(defn per-call
  "Each function's cost per call over the tests of a detail (detail's shape): {var {:calls n :ticks
  :self-ticks :bytes :self-bytes}}, the measures summed over the tests and divided by the calls. A
  recursive function's inclusive ticks and bytes count its inner calls again; its self ones do not."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]]] [:map-of :qualified-symbol :map]]}
  [detail]
  (let [sums (reduce (fn [m [v row]] (update m v #(if % (mapv + % row) row))) {} (mapcat val detail))]
    (into {} (for [[v [calls ticks self-ticks bytes self-bytes]] sums :when (pos? calls)]
               [v {:calls calls :ticks (quot ticks calls) :self-ticks (quot self-ticks calls) :bytes (quot bytes calls) :self-bytes (quot self-bytes calls)}]))))

(defn- moved?
  "Has measure x moved from y by more than the noise ratio (:seon.config.proofs/fn-noise)? A per-call
  measure: how many calls it was over is not compared."
  {:malli/schema [:=> [:cat :int [:maybe :int] number?] :boolean]}
  [x y noise] (or (nil? y) (> (Math/abs (double (- x y))) (* noise (max 1 (Math/abs (double y)))))))

(defn fn-tx
  "The function facts a run of main's tree leaves (run id, its detail): one :seon.proof.fn/ entity a
  function, its cost per call (per-call) and the run that measured it, written only for a function
  db holds none of, or whose self ticks or bytes per call moved past :seon.config.proofs/fn-noise:
  facts change only when a value changes."
  {:malli/schema [:=> [:cat :seon.host/db :string [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]]] [:vector :map]]}
  [db run detail]
  (let [noise (cfg/value :seon.config.proofs/fn-noise)]
    (vec (for [[v {:keys [calls ticks self-ticks bytes self-bytes]}] (per-call detail)
               :let [e (:e (first (d/datoms db :avet :seon.proof.fn/var v)))
                     old (when e (into {} (map (juxt :a :v)) (d/datoms db :eavt e)))]
               :when (or (nil? e) (moved? self-ticks (:seon.proof.fn/self-ticks old) noise) (moved? self-bytes (:seon.proof.fn/self-bytes old) noise))]
           {:seon.proof.fn/var v :seon.proof.fn/calls calls :seon.proof.fn/ticks ticks :seon.proof.fn/self-ticks self-ticks
            :seon.proof.fn/bytes bytes :seon.proof.fn/self-bytes self-bytes :seon.proof.fn/run run}))))

(defn results-tx
  "The facts that record ran ({key result}, a test's {:test :digest :pass :fail :error :ms :ticks :bytes
  :fns} or a step's {:step :digest ...}) of run ({:id, and :tree, or :branch :t :change}): one
  :seon.proof/ result per digest that ran, with failures ({key [assertion]}, seon.proofs/failures),
  and the run's entity, holding its detail (what each test's functions cost: detail, one value).
  A flaky result (judged, not run) and an :unreported one (its namespace threw before it reported:
  no verdict of its own) record nothing, as no result with no :digest does; a reused one is never
  in ran. When nothing ran, nothing at all."
  {:malli/schema [:=> [:cat [:map [:id :string] [:tree {:optional true} :string] [:branch {:optional true} [:or :keyword :string]]
                              [:t {:optional true} :int] [:change {:optional true} :map]]
                       [:map-of :any [:map [:digest {:optional true} :string] [:test {:optional true} :qualified-symbol] [:step {:optional true} :string]
                                      [:pass {:optional true} nat-int?] [:fail {:optional true} nat-int?] [:error {:optional true} nat-int?]
                                      [:ms {:optional true} number?] [:ticks {:optional true} :int] [:bytes {:optional true} :int]
                                      [:fns {:optional true} [:sequential :seon.profile/fn-cost]] [:cost {:optional true} :seon.cost/report]]]
                       [:map-of :any [:sequential :map]]]
                  [:vector :map]]}
  [run ran failures]
  (let [where (cond-> {:seon.proof/run (:id run)}
                (:branch run) (assoc :seon.proof/branch (name (:branch run)))
                (:t run) (assoc :seon.proof/t (:t run))
                (:change run) (assoc :seon.proof/change (:change run)))
        ran (into {} (filter (fn [[_ r]] (and (:digest r) (not (:flaky r)) (not (:unreported r))))) ran)
        dt (detail ran)]
    ;; nothing ran (every key reused): nothing to write
    (into (if (empty? ran) [] [(cond-> {:seon.proof.run/id (:id run)}
             (:tree run) (assoc :seon.proof.run/tree (:tree run))
             (:branch run) (assoc :seon.proof.run/branch (name (:branch run)))
             (:t run) (assoc :seon.proof.run/t (:t run))
             (seq dt) (assoc :seon.proof.run/detail dt))])
          (for [[k r] ran :let [dg (:digest r) t (:test r)]]
            (cond-> (assoc where :seon.proof/digest dg :seon.proof/status (status-of r)
                           ;; what this digest was ever judged: green and red both is flaky (stored-results)
                           (if (green? r) :seon.proof/green :seon.proof/red) (if (green? r) true (status-of r)))
              t (assoc :seon.proof/test t)
              (:step r) (assoc :seon.proof/step (:step r))
              (:ms r) (assoc :seon.proof/ms (long (:ms r)))
              (:ticks r) (assoc :seon.proof/ticks (:ticks r))
              (:bytes r) (assoc :seon.proof/bytes (:bytes r))
              (seq (failures k)) (assoc :seon.proof/failures (vec (failures k)))
              (:cost r) (assoc :seon.proof/cost (:cost r)))))))

(defn answer-ran
  "What an agent's test run answer ({:reports :digests :fns}, seon.agent/run-tests-forked's) ran:
  {:ran {test result} :failures {test [assertion]}}, results-tx's arguments. A test reused at its
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

(defn record-tx
  "The facts req's results leave on main ({:run :ran :failures :main?}, results-tx's arguments; :main?
  a run of main's tree, whose functions' costs are kept, fn-tx), db being main now: one transaction."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:map [:run :map] [:ran :map] [:failures {:optional true} :map] [:main? {:optional true} :boolean]]] [:vector :map]]}
  [db {:keys [run ran failures main?]}]
  (let [tx (results-tx run ran (or failures {}))
        dt (:seon.proof.run/detail (first tx))]
    (cond-> tx (and main? dt) (into (fn-tx db (:id run) dt)))))

(defn record!
  "The one writer of results: req's facts (record-tx) committed on main by (write! tx-data), root's
  writer, db being main now. Whoever ran them asks root: the test service through root's api (its
  proc writes record-tx's facts), bin/test and bin/prove through the server's :record op. Answers
  write!'s answer, or the declared :seon.proofs/unrecorded with its message: those results are not
  kept, so they run again."
  {:malli/schema [:=> [:cat :seon.host/fn [:maybe :seon.host/db] :map] :any]}
  [write! db req]
  (let [r (write! (record-tx db req))]
    (if (contract/error? r)
      (contract/error :seon.proofs/unrecorded #'record! (str "results not recorded: " (:seon.error/message r)) {})
      r)))

;; ---- what the results answer ------------------------------------------------------------

(defn fns
  "Every contracted function main's runs measured, its cost per call as the newest run that moved it
  recorded ({:var :calls :ticks :self-ticks :bytes :self-bytes :run}): one AEVT scan of the functions."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (vec (for [dt (d/datoms db :aevt :seon.proof.fn/var)]
         (into {} (map (fn [x] [(keyword (name (:a x))) (:v x)])) (d/datoms db :eavt (:e dt))))))

(defn slowest
  "The k contracted functions that cost most per call, by the measure by (:self-ticks unless given:
  the work done in the function itself; :self-bytes the memory), costliest first (fns)."
  {:malli/schema [:function [:=> [:cat :seon.host/db nat-int?] [:vector :map]] [:=> [:cat :seon.host/db nat-int? :keyword] [:vector :map]]]}
  ([db k] (slowest db k :self-ticks))
  ([db k by] (vec (take k (sort-by (juxt (comp - by) (comp str :var)) (fns db))))))

(defn details
  "What each test at digests spent in each function, as the run that recorded its result at that
  digest measured it: {test {var [calls ticks self-ticks bytes self-bytes]}}, read from those runs'
  details (one value each, a few runs for a tree)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]]]}
  [db digests]
  (let [res (for [dg (distinct digests) :let [e (:e (first (d/datoms db :avet :seon.proof/digest dg)))] :when e]
              (into {} (map (juxt :a :v)) (d/datoms db :eavt e)))
        runs (into {} (for [id (distinct (keep :seon.proof/run res)) :let [e (:e (first (d/datoms db :avet :seon.proof.run/id id)))] :when e]
                        [id (:v (first (d/datoms db :eavt e :seon.proof.run/detail)))]))]
    (into {} (for [r res :let [t (:seon.proof/test r) d (get-in runs [(:seon.proof/run r) t])] :when (and t d)] [t d]))))

(defn exercising
  "The tests at digests (a tree's) that called contracted function v, and how much ({:test :calls
  :ticks :self-ticks :bytes :self-bytes}), most ticks first (details)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol [:sequential :string]] [:vector :map]]}
  [db v digests]
  (vec (sort-by (juxt (comp - :ticks) (comp str :test))
                (for [[t fs] (details db digests) :let [row (fs v)] :when row
                      :let [[calls ticks self-ticks bytes self-bytes] row]]
                  {:test t :calls calls :ticks ticks :self-ticks self-ticks :bytes bytes :self-bytes self-bytes}))))

(defn slower
  "What costs more per call in detail (a run's, or a tree's: details) than main's runs last recorded
  (fns): each function whose self ticks or self bytes per call grew past :seon.config.proofs/fn-noise,
  {:var :by :before :after :ratio}, the largest ratio first. A function main never measured is new,
  not slower."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :qualified-symbol [:map-of :qualified-symbol [:vector :int]]]] [:vector :map]]}
  [db detail]
  (let [main (into {} (map (juxt :var identity)) (fns db))
        noise (cfg/value :seon.config.proofs/fn-noise)]
    (vec (sort-by (juxt (comp - :ratio) (comp str :var))
                  (for [[v now] (per-call detail) by [:self-ticks :self-bytes]
                        :let [x (by now) y (get-in main [v by])]
                        :when (and y (pos? y) (> x (* (+ 1 noise) y)))]
                    {:var v :by by :before y :after x :ratio (double (/ x y))})))))

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
  nil: none), found ({digest result}, the results recorded at cand's digests: stored-results) and proofs
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
  candidates' digests answering the results held at them ({digest result}: the caller's read, from
  the results root's main); base the tree this checkout's caused problems are read against (nil: none,
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
        found (into {} (filter (fn [[_ r]] (or (green? r) (:flaky r)))) (results-at (vec (distinct (vals cand)))))
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
  :ns n} for a red namespace's clojure.test output. It only runs: its caller records the results
  (results-tx, record!). Answers {test result}; a test that never reported, because its namespace's
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
