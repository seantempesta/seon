(ns seon.prove
  "The test service (docs/proofs.md#the-test-service): one `:prove` proc admits every request to
  run tests, whoever asks (a Claude session through the `prove` server op / seon_prove, an agent's
  turn), so a change is proved once.

  - A request names a branch, its tx (t) and the digest of what its run reads (seon.root/prove!,
    seon.agent/prove!); its key (run-key) is \"branch@digest\", so a write that changes nothing
    the run reads (its own facts, the eval that asked) never makes a second run.
  - One run at a time per checkout. A request whose key is running, or is the next to run,
    joins that run's waiters; it never starts a second run.
  - A request for another key while a run is in flight replaces the next run of its branch (the
    latest wins; the replaced request's waiters follow the newer one). Branches take turns: after
    a run, the next branch in name order after the one that ran goes first.
  - A key whose run ended is answered at once from that answer, until its key moves.
  - Every waiter is answered exactly once, with the run's answer.
  A run is a chain of requests to the api (this proc's `:to-api`, answers on `:answers`): the
  agent that holds the branch runs its tests (`:tests`); main, which no agent holds, is run by a
  prove agent forked from the fork server's main (its program, the tests among them:
  :seon.config.source/dirs) and killed after. The agent that ran them caches each verdict by its
  digest (seon.fx/write-verdicts!) before it answers, so every run after finds them
  (seon.fx/stored-results) and runs none of them again; nothing is written to the store.
  `:main` is the writer's commit notice (a sliding buffer of one: a notice is replaced by the next,
  a reader catches up from main's head): it keeps the latest main t for a request that names
  none, and with :seon.config.proofs/prove-main-on-commit a commit that moves main's program
  (seon.program/moves-program?) is a request to prove main at it, with no waiter: main is proved
  at each program commit, whoever asks."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.proofs :as proofs] [seon.program :as program] [seon.config :as cfg]
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
  "The identity of what a request asks for apart from its digest: the branch, the namespaces named and the mode.
  A newer request of one slot replaces an older pending one; requests of different slots never do."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword [:maybe [:set :symbol]] [:enum :turn :judge :main :full]]]}
  [{:keys [branch names mode]}] [branch names mode])

(defn- same?
  "Is request r the one req asks: the same key (branch, digest, names) and the same agent?"
  {:malli/schema [:=> [:cat [:maybe :map] :map] :boolean]}
  [r req] (boolean (and r (= (:key r) (:key req)) (= (:agent r) (:agent req)))))

(defn- go
  "Start run req (its waiters set): the first request of its chain. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map [:set :int]] [:tuple :map [:vector :map]]]}
  [st req waiters]
  (let [run (assoc req :waiters waiters)
        st (assoc st :running run)
        [st m] (if (:agent req)
                 (step/ask st [:prove :answers] [:tests (:key req)] (cond-> {:op :tests :id (:agent req) :mode (:mode req)} (:names req) (assoc :names (:names req))))
                 (step/ask st [:prove :answers] [:spawn (:key req)] {:op :spawn :opts {:branch :tests}}))]
    [(assoc-in st [:running :stage] (if (:agent req) :tests :spawn)) [m]]))

;; ---- items: a (test, digest) pair, run once whoever asks ------------------------------------
;; The test service's unit of work (notes test-system-as-a-flow-2026-11-10.md, step 2): an asker names the items its plan
;; has no verdict for; each item is queued once with every asker waiting on it, granted in batches to the agent holding
;; its branch one at a time while a worker is free (the machine's test children: the one bound), and each asker is answered at its first
;; red or its last green. State: :items {[test digest] {:status :waiters :agent :ms :risk :skips}}, :askers {rid {:left
;; :prio :results}}, :batches {bid #{item}}, :slots n. No clock: an item passed over is aged in skips.

(defn item-value
  "What running queued item it next is worth: the askers waiting on it, plus the times it was passed over (:skips, an age
  counted in grants, never a clock), times one plus its chance of being red (:risk, 0-1: a test its branch changed, one red
  lately), over its measured ms (a cheap item many need first: weighted shortest job first, the least mean time to an
  answer). See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] :double]}
  [{:keys [waiters skips risk ms]}]
  (double (/ (* (+ (count waiters) (or skips 0)) (+ 1.0 (or risk 0.0))) (max 1 (or ms 1)))))

(defn- turn?
  "Does an asker of a :turn (an agent's turn: someone is waiting to act) wait on item it? Such items go ahead of a batch's."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [st it] (boolean (some #(= :turn (get-in st [:askers % :prio])) (:waiters it))))

(defn- answer-asker
  "Asker rid answered with its verdict (:green true, or the reds and errors it met first), and gone from every item's
  waiters; a queued item no one waits on any more leaves the queue (no work for an answer already given)."
  {:malli/schema [:=> [:cat :map :int :map] [:tuple :map [:vector :map]]]}
  [st rid r]
  (let [left (get-in st [:askers rid :left])
        st (reduce (fn [st k]
                     (let [st (update-in st [:items k :waiters] disj rid)
                           it (get-in st [:items k])]
                       (cond-> st (and it (empty? (:waiters it)) (= :queued (:status it))) (update :items dissoc k))))
                   (update st :askers dissoc rid) left)]
    [st (reply [] [rid] r)]))

(defn- grant
  "While a worker is free (a slot) and an item is queued: the most valuable queued item (a :turn asker's first, then
  item-value), alone (no shards: a worker pulls one item, so none idles while another holds a list), asked of the agent
  holding it as {:op :tests :id agent :items [test]} tagged [:batch bid]; each queued item passed over ages a skip.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (loop [st st outs []]
    (let [queued (filter #(= :queued (:status (val %))) (:items st))]
      (if (or (empty? queued) (< (:slots st 0) 1))
        [st outs]
        (let [[k it] (apply max-key (fn [[_ it]] (+ (if (turn? st it) 1e12 0) (item-value it))) queued)
              batch #{k}
              bid (inc (:bid st 0))
              st (reduce (fn [st k2] (if (batch k2) (assoc-in st [:items k2 :status] :running) (update-in st [:items k2 :skips] (fnil inc 0))))
                         (-> st (assoc :bid bid) (update :slots dec) (assoc-in [:batches bid] batch))
                         (map key queued))
              [st m] (step/ask st [:prove :answers] [:batch (str bid)] {:op :tests :id (:agent it) :items (vec (sort (map first batch)))})]
          (recur st (conj outs m)))))))

(defn- item-ended
  "Item k ended with result r ({:pass :fail :error} counts, its :failures; an error: its batch died): each asker waiting on
  it loses it; one that met a red or an error is answered so at once; one with nothing left is answered green. The item
  leaves the state: its verdict is the cache's now (its runner wrote it as it ended). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :any :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [waiters (get-in st [:items k :waiters])
        st (update st :items dissoc k)
        red? (or (contract/error? r) (not (proofs/green? r)))]
    (reduce (fn [[st outs] rid]
              (let [st (-> st (update-in [:askers rid :left] disj k) (assoc-in [:askers rid :results (first k)] r))
                    {:keys [left results]} (get-in st [:askers rid])]
                (cond red? (let [[st o] (answer-asker st rid {:green false :results results
                                                               :red (vec (sort-by str (keep (fn [[t r]] (when (or (contract/error? r) (not (proofs/green? r))) t)) results)))})]
                             [st (into outs o)])
                      (empty? left) (let [[st o] (answer-asker st rid {:green true :results results})] [st (into outs o)])
                      :else [st outs])))
            [st []] waiters)))

(defn ask-items
  "Asker rid (prio :turn or :batch) waits on items ({[test digest] {:agent :ms :risk}}, what its plan holds no verdict
  for): an item queued or running gains it as a waiter, the rest are queued once; then slots are granted (grant).
  With no item it is answered green at once. Answers [st outputs]. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :int [:enum :turn :batch] [:map-of :any :map]] [:tuple :map [:vector :map]]]}
  [st rid prio items]
  (if (empty? items)
    [st (reply [] [rid] {:green true :results {}})]
    (let [st (reduce (fn [st [k m]]
                       (if (get-in st [:items k])
                         (update-in st [:items k :waiters] conj rid)
                         (assoc-in st [:items k] (assoc (select-keys m [:agent :ms :risk]) :status :queued :waiters #{rid} :skips 0))))
                     (assoc-in st [:askers rid] {:left (set (keys items)) :prio prio :results {}}) items)]
      (grant st))))

(defn batch-ended
  "Batch bid's answer r (the agent's :tests answer: its :reports by test var, its :failures; or the error its run answered,
  a died child among them): each of its items ended with its own result, one the answer does not name with the typed
  :seon.prove/no-verdict (never silence), every item of a batch that errored with that error; its slot is free again and
  the next is granted. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :int :any] [:tuple :map [:vector :map]]]}
  [st bid r]
  (let [batch (get-in st [:batches bid])
        st (-> st (update :batches dissoc bid) (update :slots inc))
        fails (when-not (contract/error? r) (proofs/failures r))
        reported (when-not (contract/error? r) (into #{} (keep #(some-> (:var %) (subs 2) symbol)) (:reports r)))
        result (fn [[t _]] (cond (contract/error? r) r
                                 (fails t) {:pass 0 :fail 1 :error 0 :failures (fails t)}
                                 (reported t) {:pass 1 :fail 0 :error 0}
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

(defn- take-next
  "After a run of branch b: the pending run whose branch is the next in name order after b (then
  the first), started; nothing pending: idle. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :keyword] [:tuple :map [:vector :map]]]}
  [st b]
  (let [ks (sort-by (juxt (comp name first) (comp str second)) (keys (:next st)))
        k (or (first (filter #(pos? (compare (name (first %)) (name b))) ks)) (first ks))]
    (if k
      (let [{:keys [waiters] :as req} (get-in st [:next k])]
        (go (update st :next dissoc k) (dissoc req :waiters) waiters))
      [st []])))

(defn- finish
  "The running run ended with answer r: every waiter answered once, the next run started. A run
  that answered is remembered under its slot (a key asked again gets that answer, nothing runs);
  an error is never remembered, nor an answer whose test child died (a report with its :exit: a
  death is no verdict, so asking again runs it again rather than answering the death), so asking
  again retries, and it is recorded as a :platform problem
  (the api's :problem) as well as answered, but for :seon.root/ended: the agent it asked ended (a
  cancel, its run's end, its run's record), and the run ends with it, no fault of the
  platform (bin/check e2e-calc: a cancel killed an agent whose turn had asked for its tests); a
  prove agent root stopped (a cancel) ends its run the same way.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :any] [:tuple :map [:vector :map]]]}
  [st r]
  (let [{:keys [key branch waiters] :as run} (:running st)
        err? (contract/error? r)
        ended? (= :seon.root/ended (:seon.error/type r))
        died? (and (not err?) (some :exit (:reports r)))
        st (cond-> (-> st (dissoc :running) (assoc :last branch))
             (not (or err? died? (seq (proofs/failures r)))) (assoc-in [:done (slot run)] {:key key :r r}))
        [st more] (take-next st branch)]
    [st (cond-> (into (reply [] waiters r) more) (and err? (not ended?)) (conj {:op :problem :class :platform :error r}))]))

(defn- request
  "Admit request m ({:rid :branch :t :digest :agent :names}) of the api, keyed by its :digest
  (run-key); one with no :rid (main's commit notice) is a run nobody waits on. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st {:keys [rid branch] :as m}]
  (let [rids (if rid #{rid} #{})
        t (or (:t m) (:main-t st))
        names (not-empty (:names m))
        mode (or (:mode m) :judge)
        key (run-key branch (:digest m) names mode)
        req (-> m (select-keys [:branch :agent :proofs]) (assoc :key key :t t :names names :mode mode))
        k (slot req)
        done (get-in st [:done k])]
    (cond
      ;; the daily full run, a run of its own
      (= :full mode) (full-request st req)
      (same? (:running st) req) [(update-in st [:running :waiters] into rids) []]
      (same? (get-in st [:next k]) req) [(update-in st [:next k :waiters] into rids) []]
      ;; asked again after its run, nothing it reads changed
      (and done (= key (:key done))) [st (reply [] rids (:r done))]
      ;; another tx of the same slot while a run is in flight: the latest wins, the asker it replaces follows it
      (:running st) [(update-in st [:next k] (fn [old] (assoc req :waiters (into (:waiters old #{}) rids)))) []]
      :else (go st req rids))))

(defn- kill-runner
  "The request that ends the run's prove agent (none: the run had none)."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:maybe :map]]]}
  [st run]
  (if-let [a (:runner run)] (step/ask st [:prove :answers] [:killed (:key run)] {:op :kill :id a}) [st nil]))

(defn- step-done
  "The run's answer, whole (its reports are what the done rule reads), or the error its run answered.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map :string :any] [:tuple :map [:vector :map]]]}
  [st run key r]
  (finish st (if (contract/error? r)
               r
               (assoc (:answer run) :key key :red (vec (sort-by str (keys (proofs/failures (:answer run)))))))))

(defn- answered
  "The api's answer r to the request of chain step; the next step, or the end. An answer to a run
  that is no longer the running one is counted (:stale). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map [:tuple :keyword :string] :any] [:tuple :map [:vector :map]]]}
  [st [step key] r]
  (let [run (:running st)]
    (cond
      (= :killed step) [st []]
      (= :full step) (if (= key (:key (:full st))) (full-answered st step r) [(update st :stale (fnil inc 0)) []])
      (not= key (:key run)) [(update st :stale (fnil inc 0)) []]
      :else
      (case step
        ;; a prove agent on the `tests` branch, forked from the fork server's main: main's program, its tests among it
        :spawn (if (contract/error? r)
                 (finish st r)
                 (let [st (assoc-in st [:running :runner] (:id r))
                       [st m] (step/ask st [:prove :answers] [:tests key] (cond-> {:op :tests :id (:id r) :mode (:mode run)} (:names run) (assoc :names (:names run))))]
                   [(assoc-in st [:running :stage] :tests) [m]]))
        :tests (let [st (assoc-in st [:running :answer] r)
                     [st kill] (kill-runner st run)]
                 ;; its verdicts are cached by the agent that ran them (seon.agent/run-tests-forked):
                 ;; nothing is written to the store, so the waiters are answered now
                 (let [[st fin] (step-done st (assoc-in run [:answer] r) key r)] [st (cond-> fin kill (conj kill))]))))))

(defn- state-view
  "What the service holds, as data: the running run's key, stage and runner, how many wait on it,
  how many runs wait to start, and the api's asks it has open ([step key]: a :spawn, :tests, :write
  or :kill answer owed to it). A hang in the chain is read from it: a run whose ask is open and
  whose target is idle (seon.root's stall task records it)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [run (:running st)]
    {:running (:key run) :stage (:stage run) :runner (:runner run) :waiting (count (:waiters run)) :next (count (:next st))
     :open (vec (sort-by str (vals (:pending st))))}))

(defn prove-proc
  "The test service's admission (see the namespace doc). In :req (requests the api forwards,
  {:rid :branch :t :digest :agent :names}), :answers (the api's answers to this proc's requests) and
  :main (the writer's commit notice); out :to-api (its requests; {:op :prove-answer :rid :r} for
  each waiter, one message each; {:op :problem} for a failed run; {:op :prove-state :r view} when
  what it holds changed (`state-view`), which root keeps for a stall's evidence). Buffers (system.edn): :req and
  :answers are lossless and bounded (a full one parks the sender: the api); :main is a sliding
  buffer of one, a notice replaced by the next."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "the store main's commit notices are read from (seon.db/at-commit)"} :ins {:req "requests to prove a branch at a tx" :answers "the api's answers" :main "main's commit notice, a sliding buffer of one"}
       :outs {:to-api "requests, and the answers to the waiters"}
       :workload :io
       :ping-map-fn (fn [st] {:running (some-> st :running :key) :full (some-> st :full :key) :waiting (count (:waiters (:running st))) :next (count (:next st))
                              :main-t (:main-t st) :stale-answers (:stale st 0)})})
  ([args] {:rid 0 :pending {} :next {} :done {} :conn (:conn args)})
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
