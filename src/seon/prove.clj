(ns seon.prove
  "The test service (docs/merging.md#the-test-service): one `:prove` proc admits every request to
  run tests, whoever asks (a Claude session through the `prove` server op / seon_prove, an agent's
  turn), so a change is proved once.

  - A request names a branch, its tx (t) and the digest of what its run reads (seon.root/prove!,
    seon.agent/prove!); its key (run-key) is \"branch@digest\", so a write that changes nothing
    the run reads (its own facts, the eval that asked) never makes a second run. A run is shared by its key, and the key
    holds the mode and the names asked: a session's tree run (mode judge, with a checkout, on its tree's branch) never joins
    a turn's run (mode turn, no checkout, keyed with !turn), whose plan holds no process item, check step or build.
  - Runs of different keys are in flight at once. A request whose key is in flight joins its waiters; it never starts a
    second run. Each run is planned by the agent holding its branch (what it would run, at their digests), and its
    items, (test, digest) pairs, join one queue: an item many runs need is queued once and run once, granted to a free
    worker (seon.prove/admission: :seon.config.proofs/max-workers, max-cores and max-load) one at a time, an agent's turn
    first, then by waiters and measured time. A run is answered at its first red item or its last green one.
  - Every waiter is answered exactly once, with the run's answer.
  A run is a chain of requests to the api (this proc's `:to-api`, answers on `:answers`): the
  agent that holds the branch runs its tests (`:tests`); main, which no agent holds, is run by a
  prove agent forked from the fork server's main (its program, the tests among them:
  :seon.config.source/dirs) and killed after. The agent that ran them caches each verdict by its
  digest (seon.fx/write-verdicts!) before it answers, so every run after finds them
  (seon.fx/stored-results) and runs none of them again; the asker's agent writes the run's facts on its own branch once
  it is answered (seon.agent/record-answer!), and nothing else is written to the store.
  `:main` is the writer's commit notice (a sliding buffer of one: a notice is replaced by the next,
  a reader catches up from main's head): it keeps the latest main t for a request that names
  none, and with :seon.config.proofs/prove-main-on-commit a commit that moves main's program
  (seon.program/moves-program?) is a request to prove main at it, with no waiter: main is proved
  at each program commit, whoever asks."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.proofs :as proofs] [seon.program :as program] [seon.config :as cfg] [seon.lib :as lib]
            [clojure.core.async.flow :as flow] [clojure.string :as str] [seon.db :as pdb]))

(defn run-key
  "The key of the run of branch whose program has digest dg (seon.program/run-digest of its
  head: what a run reads, so a write that changes no form never makes a second run), of the tests of
  namespaces names (nil: all it selects), in mode (:judge: the plan's tests; :turn: the proofs and
  what the branch changed, an agent's turn; :main: main's run, every test with no result at its
  digest, the plan taking no base)."
  {:malli/schema [:=> [:cat :keyword :string [:maybe [:set :symbol]] [:enum :turn :judge :main :full]] :string]}
  [branch dg names mode] (str (name branch) "@" dg (when (seq names) (str ":" (str/join "," (sort names)))) (when (= :turn mode) "!turn") (when (= :full mode) "!full")))

;; ---- admission ---------------------------------------------------------------------------

(defn- reply
  "outs with an answer r for each of rids."
  {:malli/schema [:=> [:cat [:vector :map] [:or [:set :int] [:sequential :int]] :any] [:vector :map]]}
  [outs rids r] (into outs (for [rid rids] {:op :prove-answer :rid rid :r r})))

(defn- slot
  "The identity of what a request asks for apart from its digest: the branch, the namespaces named and the mode, under
  which a run answered green is remembered (:done), so asking again at the same key is answered from it."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword [:maybe [:set :symbol]] [:enum :turn :judge :main :full]]]}
  [{:keys [branch names mode]}] [branch names mode])

;; ---- items: a (test, digest) pair, run once whoever asks ------------------------------------
;; The test service's unit of work (notes test-system-as-a-flow-2026-11-10.md, step 2): an asker names the items its plan
;; has no verdict for; each item is queued once with every asker waiting on it, granted in batches to the agent holding
;; its branch one at a time while a worker is free (the machine's test children: the one bound), and each asker is answered at its first
;; red or its last green. State: :items {[test digest] {:status :waiters :agent :ms :mode :at}}, :askers {rid {:left
;; :prio :results}}, :batches {bid #{item}} (the items running), :workers n, :max-load x. No clock: an item passed over is
;; aged in skips.

(defn admission
  "The test service's admission from the config's three optional bounds (docs/merging.md#the-test-service), any combination
  (each nil when unset): {:workers n}, n the least of the count bounds set (max-workers; max-cores, a share in (0,1] of the
  cores rounded down or a count, at least 1), the cores when neither is set; with :max-load when it is set."
  {:malli/schema [:=> [:cat [:map [:cores pos-int?] [:max-workers {:optional true} [:maybe pos-int?]]
                             [:max-cores {:optional true} [:maybe number?]] [:max-load {:optional true} [:maybe number?]]]]
                  [:map [:workers pos-int?] [:max-load {:optional true} number?]]]}
  [{:keys [max-workers max-cores max-load cores]}]
  (let [by-cores (when max-cores (max 1 (if (<= max-cores 1) (long (Math/floor (* max-cores cores))) (long max-cores))))
        counts (filterv some? [max-workers by-cores])]
    (cond-> {:workers (if (seq counts) (reduce min counts) cores)}
      max-load (assoc :max-load max-load))))

(defn config-admission
  "admission of the config this process holds: its optional keys as its branch states them, absent when unset. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat] [:map [:workers pos-int?] [:max-load {:optional true} number?]]]}
  []
  (let [c (:config (cfg/in-force))]
    (admission {:max-workers (:seon.config.proofs/max-workers c) :max-cores (:seon.config.proofs/max-cores c)
                :max-load (:seon.config.proofs/max-load c) :cores (cfg/value :seon.config.machine/cores)})))

(defn machine-load
  "The machine's 1-minute load average, as grant reads it: the one reading of the load the service's step makes (a test
  passes its own through this var). A reading of the machine, never a clock: docs/clocks.md#the-machines-load-admits-work."
  {:malli/schema [:=> [:cat] number?]}
  [] (lib/load-average))

(defn- free-workers
  "The items st may still start under its count bound: its :workers less the items running (its batches). See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] :int]}
  [st] (- (:workers st 0) (count (:batches st))))

(defn item-value
  "What running queued item it next is worth, bid grants made so far: the askers waiting on it, plus the times it was
  passed over (an age counted in grants since it was queued at grant :at, never a clock, and never written: a grant
  that aged every item it passed over copied each, 3.6 KB an item queued), over its measured ms (a cheap item many need
  first: weighted shortest job first, the least mean time to an answer). See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map nat-int?] :double]}
  [{:keys [waiters at ms]} bid]
  (/ (double (+ (count waiters) (- bid (or at bid)))) (double (max 1 (or ms 1)))))

(defn- answer-asker
  "Asker rid answered with its verdict (:green true, or the reds and errors it met first), and gone from every item's
  waiters; a queued item no one waits on any more leaves the queue (no work for an answer already given)."
  {:malli/schema [:=> [:cat :map :any :map] [:tuple :map [:vector :map]]]}
  [st rid r]
  (let [left (get-in st [:askers rid :left])
        st (reduce (fn [st k]
                     (let [st (update-in st [:items k :waiters] disj rid)
                           it (get-in st [:items k])]
                       (cond-> st (and it (empty? (:waiters it)) (= :queued (:status it))) (update :items dissoc k))))
                   (update st :askers dissoc rid) left)]
    [st [{:op :asker-answered :asker rid :r r}]]))

(defn- grant
  "While admission allows (a worker free under :workers; with :max-load, the load under it, read once a pass through
  machine-load and kept as :load, or nothing running: the first item is always granted, so the queue never stalls with
  nothing to wake it) and an item is queued: the most valuable queued item (a :turn asker's first, then
  item-value), alone (no shards: a worker pulls one item, so none idles while another holds a list), asked of the agent
  holding it as {:op :tests :id agent :mode m :names #{test} :items {test digest}} (a named test runs alone, at the digest
  the agent's plan gave it: seon.agent/item-selection), or, an item of another :kind (a build, a process test, a check
  step, a tree's build or seed), of root's effect owner as {:op :fx :kind :item :req {:kind :tree :checkout :name :digest
  :binary? :bound}}; an item is not granted while an item it :needs (a set: its tree's build, seed) is queued or running;
  tagged
  [:batch bid]; each queued item passed over ages a skip.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (loop [st st outs [] ld nil]
    ;; the most valuable queued item, by one reduce over the items (no seq of them: a lazy filter and apply max-key
    ;; allocated 1.5 KB an item queued, each grant)
    (let [bid0 (:bid st 0)
          ;; the askers of a turn, once a grant: most grants have none, and then no item's waiters are read for them
          turns (into #{} (keep (fn [[r a]] (when (= :turn (:prio a)) r))) (:askers st))
          running (count (:batches st))
          [k it] (when (pos? (free-workers st))
                   (reduce-kv (fn [best k it]
                                ;; an item that needs its tree's build or seed waits while that is queued or running (a red one ends it)
                                (if (and (= :queued (:status it)) (not-any? (:items st) (:needs it)))
                                  (let [v (+ (if (and (seq turns) (some turns (:waiters it))) 1e12 0) (item-value it bid0))]
                                    (if (or (nil? best) (> v (nth best 2))) [k it v] best))
                                  best))
                              nil (:items st)))
          ;; the load is read only when it decides: an item to grant, one running, and a max-load set
          ld (if (and k (nil? ld) (:max-load st) (pos? running)) (machine-load) ld)
          st (cond-> st ld (assoc :load ld))]
      (if (or (nil? k) (and ld (pos? running) (>= ld (:max-load st))))
        [st outs]
        (let [batch #{k}
              bid (inc (:bid st 0))
              st (-> st (assoc :bid bid) (assoc-in [:batches bid] batch) (assoc-in [:items k :status] :running))
              [st m] (step/ask st [:prove :answers] [:batch (str bid)]
                               (if (= :test (:kind it :test))
                                 {:op :tests :id (:agent it) :mode (:mode it :judge) :names (into #{} (map first) batch) :items (into {} batch)}
                                 ;; a build, a process test or a check step: root's effect owner runs it on the run's checkout
                                 {:op :fx :kind :item :req {:kind (:kind it) :tree (:tree it) :checkout (:checkout it)
                                                            :name (if (#{:build :seed} (:kind it)) (:kind it) (first k)) :digest (second k)
                                                            :binary? (boolean (:binary? it)) :bound (:bound it)}}))]
          (recur st (conj outs m) ld))))))

(defn- item-ended
  "Item k ended with result r ({:pass :fail :error} counts, its :failures; an error: its batch died; a signal, {:signal
  :overran :ms}: a step that ran past its bound, no verdict): each asker waiting on it loses it; one that met a red or an
  error is answered so at once; one with nothing left is answered green, its signals listed (:signals). A signal is
  neither red nor green. The item leaves the state: its verdict is the cache's now (its runner wrote it as it ended).
  A build or seed that is not green is its typed error, and so is every item that needs it (none is granted). Each asker is
  first told the verdict as it lands, {:op :prove-verdict :key asker :test :digest :kind :status (:pass :fail :error,
  :signal, or :no-verdict for an error) :ms :failures}, which root puts on its events for a streaming :prove to write
  (docs/merging.md#the-test-service). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :any :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters kind]} (get-in st [:items k])
        st (update st :items dissoc k)
        [t d] k
        signal? (and (map? r) (some? (:signal r)))
        r (if (and (#{:build :seed} kind) (not signal?) (not (contract/error? r)) (not (proofs/green? r)))
            (contract/error :seon.prove/build-red #'seon.prove/item-ended (str "the " (name kind) " of tree " d " is red") {:tree d :kind kind :answer r})
            r)
        red? (and (not signal?) (or (contract/error? r) (not (proofs/green? r))))
        ms (or (get-in r [:answer :measured t :ms]) (when (map? r) (:ms r)))
        verdict (cond-> (cond (contract/error? r) {:test t :digest d :status :no-verdict :failures [(select-keys r [:seon.error/type :seon.error/message])]}
                              signal? {:test t :digest d :status :signal :signal (:signal r)}
                              :else (cond-> {:test t :digest d :status (proofs/status-of r)} (seq (:failures r)) (assoc :failures (:failures r))))
                  kind (assoc :kind kind)
                  ms (assoc :ms ms))
        [st outs] (reduce (fn [[st outs] rid]
                            (let [st (cond-> (update-in st [:askers rid :left] disj k)
                                       signal? (update-in [:askers rid :signals] (fnil conj []) (select-keys verdict [:test :digest :kind :signal :ms]))
                                       (not signal?) (assoc-in [:askers rid :results t] r))
                                  {:keys [left results signals]} (get-in st [:askers rid])]
                              (cond red? (let [[st o] (answer-asker st rid (cond-> {:green false :results results
                                                                                    :red (vec (sort-by str (keep (fn [[t r]] (when (or (contract/error? r) (not (proofs/green? r))) t)) results)))}
                                                                             (seq signals) (assoc :signals signals)))]
                                           [st (into outs o)])
                                    (empty? left) (let [[st o] (answer-asker st rid (cond-> {:green true :results results} (seq signals) (assoc :signals signals)))] [st (into outs o)])
                                    :else [st outs])))
                          [st (mapv #(assoc verdict :op :prove-verdict :key %) (sort-by str waiters))] (sort-by str waiters))]
    (if (and (#{:build :seed} kind) red?)
      ;; every item still queued that needs this build or seed ends with its error (a seed that needs a red build passes it on)
      (reduce (fn [[st outs] k2] (if (get-in st [:items k2]) (let [[st o] (item-ended st k2 r)] [st (into outs o)]) [st outs]))
              [st outs] (sort-by str (keep (fn [[k2 it]] (when (contains? (:needs it) k) k2)) (:items st))))
      [st outs])))

(defn ask-items
  "Asker rid (prio :turn or :batch) waits on items ({[name digest] {:agent :ms :mode :kind ...}}, what its plan holds no
  verdict for; :kind :test (the default), :process, :step, :build or :seed, the others with the :tree and :checkout they
  run on, :binary? and :bound, and :needs, the set of items they wait for): an item queued or running gains it as a waiter, the rest
  are queued once; then slots are granted (grant).
  With no item it is answered green at once. Answers [st outputs]. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :any [:enum :turn :batch] [:map-of :any :map]] [:tuple :map [:vector :map]]]}
  [st rid prio items]
  (if (empty? items)
    [st [{:op :asker-answered :asker rid :r {:green true :results {}}}]]
    (let [st (reduce (fn [st [k m]]
                       (if (get-in st [:items k])
                         (update-in st [:items k :waiters] conj rid)
                         (assoc-in st [:items k] (assoc (select-keys m [:agent :ms :mode :kind :needs :tree :checkout :binary? :bound]) :status :queued :waiters #{rid} :at (:bid st 0)))))
                     (assoc-in st [:askers rid] {:left (set (keys items)) :prio prio :results {}}) items)]
      (grant st))))

(defn batch-ended
  "Batch bid's answer r (the agent's :tests answer: its :reports by test var, its :failures; or the error its run answered,
  a died child among them): each of its items ended with its own result, one the answer does not name with the typed
  :seon.prove/no-verdict (never silence), every item of a batch that errored with that error; or, for an item of root's
  effect owner (a build, a process test, a step), its {:value {:pass :fail :error :failures :ms}}, a step's {:value
  {:signal :overran :ms}}, or its typed error; its slot is free again and the next is granted. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :int :any] [:tuple :map [:vector :map]]]}
  [st bid r]
  (let [batch (get-in st [:batches bid])
        fx? (not= :test (get-in st [:items (first batch) :kind] :test))
        st (update st :batches dissoc bid)
        fails (when-not (or fx? (contract/error? r)) (proofs/failures r))
        reported (when-not (or fx? (contract/error? r)) (into #{} (keep #(some-> (:var %) (subs 2) symbol)) (:reports r)))
        v (when fx? (:value r))
        result (fn [[t d]] (cond (contract/error? r) r
                                 ;; root's effect owner ran it: a step's signal is no verdict; a verdict reads as a test's
                                 (and fx? (:signal v)) v
                                 (and fx? (map? v)) (assoc v :answer {:pass (:pass v 0) :fail (:fail v 0) :error (:error v 0) :digests {t d}
                                                                      :reports (if (seq (:failures v)) (mapv #(assoc % :var (str "#'" t)) (:failures v)) [{:type :pass :var (str "#'" t)}])})
                                 (and (not fx?) (fails t)) {:pass 0 :fail 1 :error 0 :failures (fails t) :answer r}
                                 (and (not fx?) (reported t)) {:pass 1 :fail 0 :error 0 :answer r}
                                 :else (contract/error :seon.prove/no-verdict #'seon.prove/batch-ended
                                                       (str t "'s batch answered no verdict for it") {:test t})))
        [st outs] (reduce (fn [[st outs] k] (let [[st o] (item-ended st k (result k))] [st (into outs o)])) [st []] (sort-by str batch))
        [st more] (grant st)]
    [st (into outs more)]))

;; ---- the daily full run: a run of its own ------------------------------------------------

(defn- full-request
  "The daily full run (the cron proc's request, :full mode): bin/test --all in a worktree of git's
  main, an OS child (the :tests effect), as a run of its own, so it never holds the run slot an
  agent's turn waits on (a full run takes most of an hour, and can stall: measured, its
  first one stalled in a process test). The same day's request again joins it or is answered by
  it; another day's while one runs is a :platform problem naming the one that has not ended.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st req]
  (let [cur (:full st)]
    (cond
      (and cur (= (:key cur) (:key req))) [st []]
      cur [st [{:op :problem :class :platform
                :error (contract/error :seon.prove/full-run-running #'seon.prove/full-request
                                       (str "the full run " (:key req) " was asked while " (:key cur) " has not ended") {:running (:key cur)})}]]
      (= (:key req) (:full-done st)) [st []]
      :else (let [[st m] (step/ask st [:prove :answers] [:full (:key req)] {:op :fx :kind :tests :req {:all? true}})]
              [(assoc st :full req) [m]]))))

(defn- full-answered
  "The full run's chain: the effect's answer (its shards cached their verdicts), its slot free. A failed effect is a
  :platform problem, and so is a red: this run is what catches what a digest can't see (docs/merging.md#verdicts-are-a-cache), so
  a red it finds is told, naming each test."
  {:malli/schema [:=> [:cat :map :keyword :any] [:tuple :map [:vector :map]]]}
  [st step r]
  (let [run (:full st)]
    (cond
      (contract/error? r) [(dissoc st :full) [{:op :problem :class :platform :error r}]]
      :else (let [reds (vec (sort-by str (keep (fn [[t st]] (when-not (= :pass st) t)) (get-in r [:value :results]))))]
              [(-> st (dissoc :full) (assoc :full-done (:key run)))
               (if (empty? reds)
                 []
                 [{:op :problem :class :platform
                   :error (contract/error :seon.prove/full-run-red #'seon.prove/full-answered
                                          (str "the full run " (:key run) " found " (count reds) " red on main: " (str/join " " reds)) {:tests reds})}])]))))

;; ---- runs: a request planned by its agent, its items on the one queue --------------------------------
;; A run (one key: branch, digest, names, mode) is planned by the agent holding its branch (its :tests with :plan?: what
;; it would run, at their digests; what it reuses and defers), main's by a prove agent forked for it. Its items join the
;; queue (ask-items, the run's key its asker); it is answered when its last item is green or its first is red, from its
;; plan and its items' answers as they landed. Runs of different keys are in flight at once: the workers bound the work.

(defn- run-answer
  "The answer run (its plan :base) is given, its items' results (per test: the item's counts, :failures and the :answer of
  the grant that ran it; or the error its batch answered): the run-tests-forked shape the done rule and an agent's prove
  read: the plan's reused reports, each item's own reports, every digest, function cost and measure (:measured, which the
  asker's agent writes as its facts: seon.agent/record-answer!), the counts, what was selected, deferred and why, and
  any verdict the cache would not take (:unstored). A process test's and a check step's results count as a test's; a
  build's is no result of the run (an item that needed it answers its error); a step's signals are listed (:signals), no
  verdict. :red names every red of the run by its item's name, a check step's (a string) among the tests (finish keeps it), as
  its failing reports could not: a step's name is no test var. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :map [:maybe [:vector :map]]] :map]}
  [base results signals]
  (let [results (into {} (remove (comp vector? key)) results)
        own (fn [t r] (if (contract/error? r)
                        [{:type :error :var (str "#'" t) :message (:seon.error/message r) :seon.error/type (:seon.error/type r)}]
                        (filterv #(= (str "#'" t) (:var %)) (:reports (:answer r)))))
        reports (into (vec (:reused-reports base)) (mapcat (fn [[t r]] (own t r))) (sort-by (comp str key) results))
        ;; the reused reports' types, and each item's own counts (its answer keeps one pass report a test, its counts every assertion)
        counts (merge-with + (frequencies (map :type (:reused-reports base)))
                           (apply merge-with + {} (for [[_ r] results] (if (contract/error? r) {:error 1} (select-keys (:answer r) [:pass :fail :error])))))]
    (cond-> {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :reports reports
             :digests (into (or (:digests base) {}) (for [[_ r] results :when (map? (:answer r)) [t d] (:digests (:answer r))] [t d]))
             :fns (into {} (for [[_ r] results :when (map? (:answer r)) [t f] (:fns (:answer r))] [t f]))
             :measured (into {} (for [[_ r] results :when (map? (:answer r)) [t m] (:measured (:answer r))] [t m]))
             :selected (vec (:selected base)) :deferred (vec (:deferred base)) :at-judge (:at-judge base 0)
             :reused (count (filter :reused (:reused-reports base))) :ran (count results)
             :red (vec (sort-by str (distinct (concat (keys (proofs/failures {:reports (vec (:reused-reports base))}))
                                                      (keep (fn [[t r]] (when (or (contract/error? r) (not (proofs/green? r))) t)) results)))))}
      (seq (:why base)) (assoc :why (:why base))
      (seq signals) (assoc :signals signals)
      (seq (:missing base)) (assoc :missing (:missing base))
      ;; a verdict the cache would not take, named as its run named it
      (some (comp :unstored :answer) (vals results)) (assoc :unstored (vec (mapcat (comp :unstored :answer) (vals results)))))))

(defn- runner-done
  "outputs with the request that ends run's prove agent (main's run), once no item of the queue is that agent's."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st run]
  (let [a (:runner run)]
    (if (and a (not-any? #(= a (:agent %)) (vals (:items st))))
      (let [[st m] (step/ask st [:prove :answers] [:killed (:key run)] {:op :kill :id a})] [st [m]])
      [st []])))

(defn- finish
  "Run k ended with answer r: every waiter answered once. A run answered green with nothing died is remembered under its
  slot (a key asked again gets that answer, nothing runs); an error, a death or a red is not, so asking again runs it
  again. An error is a :platform problem too, but :seon.root/ended (the agent it asked ended: a cancel, its run's end).
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :string :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters] :as run} (get-in st [:runs k])
        err? (contract/error? r)
        ended? (= :seon.root/ended (:seon.error/type r))
        died? (and (not err?) (some :exit (:reports r)))
        ;; a run's items name their own reds (run-answer: a step's among them); any other answer, by its failing reports
        r (if err? r (assoc r :key k :red (vec (sort-by str (distinct (concat (:red r) (keys (proofs/failures r))))))))
        st (cond-> (-> st (update :runs dissoc k) (assoc :last (:branch run)))
             ;; asked again, it ran nothing for that asker: each verdict reads as reused at its digest, with no measure to add
             (not (or err? died? (seq (:red r)))) (assoc-in [:done (slot run)] {:key k :r (-> r (dissoc :measured) (assoc :ran 0)
                                                                                         (update :reports (partial mapv #(assoc % :reused true)))
                                                                                         (as-> x (assoc x :reused (count (distinct (keep :var (:reports x)))))))}))
        [st kill] (runner-done st run)]
    [st (cond-> (into (reply [] waiters r) kill) (and err? (not ended?)) (conj {:op :problem :class :platform :error r}))]))

(defn- settle
  "Outputs of the queue (ask-items, batch-ended) carried out: an :asker-answered of run k finishes it with its answer
  (run-answer of its plan and its items' results, or the typed error of an item with no verdict); every other output passes through. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:tuple :map [:vector :map]]]}
  [st outs]
  (reduce (fn [[st acc] o]
            (if (= :asker-answered (:op o))
              (let [k (:asker o) run (get-in st [:runs k])]
                (if run
                  ;; an item with no verdict (its fork refused, its batch errored) is the run's answer: the typed error, as a run
                  ;; that could not run answers; the verdicts that did land are the cache's
                  (let [rs (:results (:r o))
                        [st more] (finish st k (or (some (fn [[_ r]] (when (contract/error? r) r)) (sort-by (comp str key) rs))
                                                   (run-answer (:base run) rs (:signals (:r o)))))]
                    [st (into acc more)])
                  [st acc]))
              [st (conj acc o)]))
          [st []] outs))

(defn- plan!
  "Ask the agent holding run k's branch for its plan; a run of a session's checkout (:checkout) asks for its process tests,
  check steps and build as items too (:process? true), which run on that checkout (docs/merging.md#the-test-service).
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :string] [:tuple :map [:vector :map]]]}
  [st k]
  (let [{:keys [agent mode names checkout]} (get-in st [:runs k])
        [st m] (step/ask st [:prove :answers] [:plan k] (cond-> {:op :tests :id agent :mode mode :plan? true} names (assoc :names names)
                                                          (and checkout (not= :turn mode)) (assoc :process? true)))]
    [(assoc-in st [:runs k :stage] :plan) [m]]))

(defn- request
  "Admit request m ({:rid :branch :t :digest :agent :names :mode}) of the api, keyed by its :digest (run-key); one with no
  :rid (main's commit notice) is a run nobody waits on. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st {:keys [rid branch] :as m}]
  (let [rids (if rid #{rid} #{})
        t (or (:t m) (:main-t st))
        names (not-empty (:names m))
        mode (or (:mode m) :judge)
        key (run-key branch (:digest m) names mode)
        req (-> m (select-keys [:branch :agent :proofs :checkout :tree]) (assoc :key key :t t :names names :mode mode))
        done (get-in st [:done (slot req)])]
    (cond
      ;; the daily full run, a run of its own
      (= :full mode) (full-request st req)
      ;; a run of this key in flight: joined
      (get-in st [:runs key]) [(update-in st [:runs key :waiters] into rids) []]
      ;; asked again after its run, nothing it reads changed
      (and done (= key (:key done))) [st (reply [] rids (:r done))]
      (:agent req) (plan! (assoc-in st [:runs key] (assoc req :waiters rids)) key)
      ;; main: a prove agent on the tests branch, forked from the fork server's main, plans it
      :else (let [st (assoc-in st [:runs key] (assoc req :waiters rids :stage :spawn))
                  [st m] (step/ask st [:prove :answers] [:spawn key] {:op :spawn :opts {:branch :tests}})]
              [st [m]]))))

(defn- plan-items
  "The items of plan r ({:process {test digest} :steps {step {:digest :binary? :bound}} :build {:tree :needed?}}, seon.agent's
  answer to a :process? plan) that root's effect owner runs on run's checkout (main's, the checkout this root runs from, for
  a run that names none) at its tree (the run's, else the plan's): the build, [[:build tree] tree], once a tree when it is
  needed; the seed, [[:seed tree] tree], which needs the build, when a step needs the binary; each process test, needing
  the seed (which also makes the process tests' store); and each step, a :binary? one needing the seed. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :map] [:map-of :any :map]]}
  [run r]
  (let [tree (or (:tree run) (get-in r [:build :tree]))
        on {:tree tree :checkout (or (:checkout run) (cfg/checkout))}
        bk [[:build tree] tree] sk [[:seed tree] tree]
        build? (get-in r [:build :needed?])
        ;; the seed also makes the process tests' store (seon.fx/run-item: seon.test-store/make-process-store!), so a process item
        ;; needs it, as a binary step does
        seed? (and build? (or (seq (:process r)) (some :binary? (vals (:steps r)))))]
    (cond-> (into {}
                  (concat (for [[t d] (:process r)] [[t d] (cond-> (assoc on :kind :process :binary? true) build? (assoc :needs #{sk}))])
                          (for [[s {:keys [digest binary? bound]}] (:steps r)]
                            [[s digest] (cond-> (assoc on :kind :step :binary? (boolean binary?)) bound (assoc :bound bound) (and seed? binary?) (assoc :needs #{sk}))])))
      build? (assoc bk (assoc on :kind :build))
      seed? (assoc sk (assoc on :kind :seed :needs #{bk})))))

(defn- answered
  "The api's answer r to the request of chain step: a prove agent spawned (main's run: it plans), a run's plan (its items
  join the queue), a batch's answer (batch-ended), a runner killed. An answer to a run no longer in flight is counted
  (:stale). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map [:tuple :keyword :string] :any] [:tuple :map [:vector :map]]]}
  [st [step key] r]
  (case step
    :killed [st []]
    :full (if (= key (:key (:full st))) (full-answered st step r) [(update st :stale (fnil inc 0)) []])
    :batch (let [[st outs] (batch-ended st (parse-long key) r)] (settle st outs))
    (let [run (get-in st [:runs key])]
      (cond
        (nil? run) [(update st :stale (fnil inc 0)) []]
        (contract/error? r) (finish st key r)
        (= :spawn step) (plan! (update-in st [:runs key] assoc :runner (:id r) :agent (:id r)) key)
        (= :plan step) (let [st (assoc-in st [:runs key :base] (dissoc r :items :ms :process :steps :build))
                             prio (if (= :turn (:mode run)) :turn :batch)
                             items (into (plan-items run r) (for [[t d] (:items r)] [[t d] (cond-> {:agent (:agent run) :mode (:mode run) :kind :test} (get-in r [:ms t]) (assoc :ms (get-in r [:ms t])))]))
                             [st outs] (ask-items (assoc-in st [:runs key :stage] :items) key prio items)]
                         (settle st outs))
        :else [(update st :stale (fnil inc 0)) []]))))

(defn- state-view
  "What the service holds, as data: the runs in flight (each key's stage and waiters), the queue (items queued and running),
  the free workers, the load it last read (when a max-load is set), and the api's asks it has open. A hang in the chain is read from it (seon.root's stall task records it)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (cond-> {:runs (into (sorted-map) (for [[k r] (:runs st)] [k {:stage (:stage r) :waiting (count (:waiters r))}]))
           :queued (count (filter #(= :queued (:status %)) (vals (:items st)))) :running (count (filter #(= :running (:status %)) (vals (:items st))))
           :slots (free-workers st) :open (vec (sort-by str (vals (:pending st))))}
    (some? (:load st)) (assoc :load (:load st))))

(defn prove-proc
  "The test service (see the namespace doc). In :req (requests the api forwards, {:rid :branch :t :digest :agent :names
  :mode}), :answers (the api's answers to this proc's requests) and :main (the writer's commit notice); out :to-api (its
  requests; {:op :prove-answer :rid :r} for each waiter, one message each; {:op :problem} for a failed run; {:op
  :prove-state :r view} when what it holds changed, `state-view`, which root keeps for a stall's evidence). Its workers,
  the items running at once on the machine, are bounded by admission (:seon.config.proofs/max-workers, max-cores, max-load). Buffers (system.edn): :req and
  :answers are lossless and bounded (a full one parks the sender: the api); :main is a sliding buffer of one."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "the store main's commit notices are read from (seon.db/at-commit)"} :ins {:req "requests to prove a branch at a tx" :answers "the api's answers" :main "main's commit notice, a sliding buffer of one"}
       :outs {:to-api "requests, and the answers to the waiters"}
       :workload :io
       :ping-map-fn (fn [st] {:runs (count (:runs st)) :full (some-> st :full :key) :queued (count (:items st)) :slots (free-workers st)
                              :main-t (:main-t st) :stale-answers (:stale st 0)})})
  ([args] (merge {:rid 0 :pending {} :runs {} :done {} :items {} :askers {} :batches {} :conn (:conn args)} (config-admission)))
  ([st _tr] st)
  ([st0 in m]
   (let [st st0
         [st outs] (case in
                     :req (request st m)
                     :answers (let [[st v] (step/answered st (:tag m))] (if v (answered st v (:r m)) [(update st :stale (fnil inc 0)) []]))
                     :main (let [st (assoc st :main-t (:t m))]
                             (if (and (cfg/value :seon.config.proofs/prove-main-on-commit) (program/moves-program? (:ops m)))
                               (request st {:branch :main :t (:t m) :digest (program/run-digest (pdb/at-commit (:conn st) (:commit m))) :mode :main})
                               [st []])))]
     (let [outs (cond-> (vec outs) (not= (state-view st) (state-view st0)) (conj {:op :prove-state :r (state-view st)}))]
       [st (if (seq outs) {:to-api outs} {})]))))
