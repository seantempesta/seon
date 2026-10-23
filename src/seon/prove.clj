(ns seon.prove
  "The test service (docs/proofs.md#the-test-service): one `:prove` proc admits every request to
  run tests, whoever asks (a Claude session through the `prove` server op / seon_prove, an agent's
  turn), so a change is proved once.

  - A request names a branch and its tx (t); its key is \"branch@t\", the digest of what would run.
  - One run at a time per checkout. A request whose key is running, or is the next to run,
    joins that run's waiters; it never starts a second run.
  - A request for another key while a run is in flight replaces the next run of its branch (the
    latest wins; the replaced request's waiters follow the newer one). Branches take turns: after
    a run, the next branch in name order after the one that ran goes first.
  - A key whose run ended is answered at once from that answer, until its branch moves.
  - Every waiter is answered exactly once, with the run's answer.
  A run is a chain of requests to the api (this proc's `:to-api`, answers on `:answers`): the
  agent that holds the branch runs its tests (`:tests`); a branch no agent holds (main, `tests`)
  is run by a prove agent spawned for it and killed after. Its results are recorded on main as
  facts, one per test digest (`result-tx`), before its waiters are answered.
  `:main` is the writer's commit notice (a sliding buffer of one: a notice is replaced by the next,
  a reader catches up from main's head); it keeps the latest main t for a request that names none."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.proofs :as proofs]
            [clojure.core.async.flow :as flow] [clojure.string :as str]))

(defn run-key
  "The key of the run of branch at tx t, of the tests of namespaces names (nil: all it selects), in
  mode (:judge: the plan's tests; :turn: the proofs and what the branch changed, an agent's turn)."
  {:malli/schema [:=> [:cat :keyword :int [:maybe [:set :symbol]] [:enum :turn :judge]] :string]}
  [branch t names mode] (str (name branch) "@" t (when (seq names) (str ":" (str/join "," (sort names)))) (when (= :turn mode) "!turn")))

;; ---- the facts of a run ------------------------------------------------------------------

(defn result-tx
  "The facts a finished run leaves on main: one :seon.proof/ result per test that ran or was
  reused (a green the results directory held becomes a fact too) at its digest, greens and reds, and the run's fact.
  run: the run request {:key :branch :t :agent}; answer: the agent's tests answer ({:reports
  :digests :pass :fail :error :ms}); ms: the run's wall time. The change names who asked."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [run answer]
  (let [statuses (proofs/results {:reports (:reports answer)})
        fails (proofs/failures {:reports (:reports answer)})
        change (cond-> (select-keys run [:branch :t]) (:agent run) (assoc :agent (:agent run)))]
    (into [(cond-> {:seon.proof.run/id (:key run) :seon.proof.run/branch (name (:branch run)) :seon.proof.run/t (:t run)
            :seon.proof.run/pass (:pass answer 0) :seon.proof.run/fail (:fail answer 0) :seon.proof.run/error (:error answer 0)
            :seon.proof.run/ms (long (:ms answer 0))}
             (seq (:deferred answer)) (assoc :seon.proof.run/skipped-process (vec (:deferred answer))))]
          (mapcat (fn [[t s]]
                    (when-let [dg (get-in answer [:digests t])]
                      (let [fact (cond-> {:seon.proof/status s :seon.proof/run (:key run) :seon.proof/branch (name (:branch run))
                                          :seon.proof/t (:t run) :seon.proof/change change}
                                   (fails t) (assoc :seon.proof/failures (fails t)))]
                        (cond-> [(assoc fact :seon.proof/digest dg :seon.proof/test t)]
                          ;; main's newest result of the test, read by :test-red
                          (= :main (:branch run)) (conj (assoc fact :seon.proof/latest (str t)))))))
                  statuses))))

;; ---- admission ---------------------------------------------------------------------------

(defn- reply [outs rids r] (into outs (for [rid rids] {:op :prove-answer :rid rid :r r})))

(defn- slot
  "The identity of what a request asks for apart from its tx: the branch and the namespaces named.
  A newer request of one slot replaces an older pending one; requests of different slots never do."
  [{:keys [branch names mode]}] [branch names mode])

(defn- same?
  "Is request r the one req asks: the same key (branch, tx, names) and the same agent?"
  [r req] (and r (= (:key r) (:key req)) (= (:agent r) (:agent req))))

(defn- go
  "Start run req (its waiters set): the first request of its chain. Answers [st outputs]."
  [st req waiters]
  (let [run (assoc req :waiters waiters)
        st (assoc st :running run)
        [st m] (if (:agent req)
                 (step/ask st [:prove :answers] [:tests (:key req)] (cond-> {:op :tests :id (:agent req) :mode (:mode req)} (:names req) (assoc :names (:names req))))
                 (step/ask st [:prove :answers] [:spawn (:key req)] {:op :spawn :opts {:branch :tests}}))]
    [(assoc-in st [:running :stage] (if (:agent req) :tests :spawn)) [m]]))

(defn- take-next
  "After a run of branch b: the pending run whose branch is the next in name order after b (then
  the first), started; nothing pending: idle. Answers [st outputs]."
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
  an error is never remembered, so asking again retries, and it is recorded as a :platform problem
  (the api's :problem) as well as answered, but for :seon.root/ended: the agent it asked ended (a
  cancel, its run's end, its run's record), and the run ends with it, no fault of the
  platform (bin/check e2e-calc: a cancel killed an agent whose turn had asked for its tests); a
  prove agent root stopped (a cancel) ends its run the same way.
  Answers [st outputs]."
  [st r]
  (let [{:keys [key branch waiters recorded-t] :as run} (:running st)
        err? (contract/error? r)
        ended? (= :seon.root/ended (:seon.error/type r))
        st (cond-> (-> st (dissoc :running) (assoc :last branch))
             (not err?) (assoc-in [:done (slot run)] {:key key :r r :after recorded-t}))
        [st more] (take-next st branch)]
    [st (cond-> (into (reply [] waiters r) more) (and err? (not ended?)) (conj {:op :problem :class :platform :error r}))]))

(defn- request
  "Admit request m ({:rid :branch :t :agent :names}) of the api. Answers [st outputs]."
  [st {:keys [rid branch] :as m}]
  (let [t (or (:t m) (:main-t st))
        names (not-empty (:names m))
        mode (or (:mode m) :judge)
        key (run-key branch t names mode)
        req (-> m (select-keys [:branch :agent :proofs]) (assoc :key key :t t :names names :mode mode))
        k (slot req)
        done (get-in st [:done k])]
    (cond
      (same? (:running st) req) [(update-in st [:running :waiters] conj rid) []]
      (same? (get-in st [:next k]) req) [(update-in st [:next k :waiters] conj rid) []]
      ;; asked again after its run; or at the tx the run's own results were written at (main)
      (and done (or (= key (:key done)) (= t (:after done)))) [st (reply [] [rid] (:r done))]
      ;; another tx of the same slot while a run is in flight: the latest wins, the asker it replaces follows it
      (:running st) [(update-in st [:next k] (fn [old] (assoc req :waiters (conj (:waiters old #{}) rid)))) []]
      :else (go st req #{rid}))))

(defn- kill-runner
  "The request that ends the run's prove agent (none: the run had none)."
  [st run]
  (if-let [a (:runner run)] (step/ask st [:prove :answers] [:killed (:key run)] {:op :kill :id a}) [st nil]))

(defn- answered
  "The api's answer r to the request of chain step; the next step, or the end. An answer to a run
  that is no longer the running one is counted (:stale). Answers [st outputs]."
  [st [step key] r]
  (let [run (:running st)]
    (cond
      (= :killed step) [st []]
      (not= key (:key run)) [(update st :stale (fnil inc 0)) []]
      :else
      (case step
        ;; a prove agent on the `tests` branch: main's program, the checkout's tests loaded and recorded
        :spawn (if (contract/error? r)
                 (finish st r)
                 (let [[st m] (step/ask (assoc-in st [:running :runner] (:id r)) [:prove :answers] [:prepared key]
                                        {:op :eval :id (:id r) :forms "(seon.agent/load-tests! [\"test\"])"})]
                   [(assoc-in st [:running :stage] :prepare) [m]]))
        :prepared (let [e (cond (contract/error? r) r
                                (= :err (:tag (last r))) (contract/error :seon.test/prepare 'seon.prove/prove-proc
                                                                         (str "the prove agent could not load the tests: " (:printed (last r))) {}))]
                    (if e
                      (let [[st fin] (finish st e) [st kill] (kill-runner st run)] [st (conj fin kill)])
                      (let [[st m] (step/ask st [:prove :answers] [:tests key] (cond-> {:op :tests :id (:runner run) :mode (:mode run)} (:names run) (assoc :names (:names run))))]
                        [(assoc-in st [:running :stage] :tests) [m]])))
        :tests (let [st (assoc-in st [:running :answer] r)
                     [st kill] (kill-runner st run)]
                 (if (contract/error? r)
                   (let [[st fin] (finish st r)] [st (cond-> fin kill (conj kill))])
                   (let [[st w] (step/ask st [:prove :answers] [:recorded key] {:op :write :req {:op :tx :branch :db :tx-data (result-tx run r)}})]
                     [(assoc-in st [:running :stage] :recording) (cond-> [w] kill (conj kill))])))
        ;; the run's answer, whole (its reports are what the done rule reads), once its facts are written
        :recorded (finish (assoc-in st [:running :recorded-t] (when-not (contract/error? r) (:t r)))
                          (if (contract/error? r)
                            r
                            (assoc (:answer run) :key key :ran true :red (vec (sort-by str (keys (proofs/failures (:answer run)))))))))))) 

(defn prove-proc
  "The test service's admission (see the namespace doc). In :req (requests the api forwards,
  {:rid :branch :t :agent :names}), :answers (the api's answers to this proc's requests) and
  :main (the writer's commit notice); out :to-api (its requests; {:op :prove-answer :rid :r} for
  each waiter, one message each; {:op :problem} for a failed run). Buffers (system.edn): :req and
  :answers are lossless and bounded (a full one parks the sender: the api); :main is a sliding
  buffer of one, a notice replaced by the next."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {} :ins {:req "requests to prove a branch at a tx" :answers "the api's answers" :main "main's commit notice, a sliding buffer of one"}
       :outs {:to-api "requests, and the answers to the waiters"}
       :workload :io
       :ping-map-fn (fn [st] {:running (some-> st :running :key) :waiting (count (:waiters (:running st))) :next (count (:next st))
                              :main-t (:main-t st) :stale-answers (:stale st 0)})})
  ([_args] {:rid 0 :pending {} :next {} :done {}})
  ([st _tr] st)
  ([st in m]
   (let [[st outs] (case in
                     :req (request st m)
                     :answers (let [[st v] (step/answered st (:tag m))] (if v (answered st v (:r m)) [(update st :stale (fnil inc 0)) []]))
                     :main [(assoc st :main-t (:t m)) []])]
     [st (if (seq outs) {:to-api (vec outs)} {})])))
