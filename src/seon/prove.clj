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
  "The key of the run of branch at tx t."
  {:malli/schema [:=> [:cat :keyword :int] :string]}
  [branch t] (str (name branch) "@" t))

;; ---- the facts of a run ------------------------------------------------------------------

(defn result-tx
  "The facts a finished run leaves on main: one :seon.proof/ result per test that ran (not a
  reused one: its result is already a fact) at its digest, greens and reds, and the run's fact.
  run: the run request {:key :branch :t :agent}; answer: the agent's tests answer ({:reports
  :digests :pass :fail :error :ms}); ms: the run's wall time. The change names who asked."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [run answer]
  (let [statuses (proofs/results {:reports (remove :reused (:reports answer))})
        fails (proofs/failures {:reports (:reports answer)})
        change (cond-> (select-keys run [:branch :t]) (:agent run) (assoc :agent (:agent run)))]
    (into [{:seon.proof.run/id (:key run) :seon.proof.run/branch (name (:branch run)) :seon.proof.run/t (:t run)
            :seon.proof.run/pass (:pass answer 0) :seon.proof.run/fail (:fail answer 0) :seon.proof.run/error (:error answer 0)
            :seon.proof.run/ms (long (:ms answer 0))}]
          (for [[t s] statuses :let [dg (get-in answer [:digests t])] :when dg]
            (cond-> {:seon.proof/digest dg :seon.proof/test t :seon.proof/status s :seon.proof/run (:key run)
                     :seon.proof/branch (name (:branch run)) :seon.proof/t (:t run) :seon.proof/change change}
              (fails t) (assoc :seon.proof/failures (fails t)))))))

;; ---- admission ---------------------------------------------------------------------------

(defn- reply [outs rids r] (into outs (for [rid rids] {:op :prove-answer :rid rid :r r})))

(defn- go
  "Start run req (its waiters set): the first request of its chain. Answers [st outputs]."
  [st req waiters]
  (let [run (assoc req :waiters waiters)
        st (assoc st :running run)
        [st m] (if (:agent req)
                 (step/ask st [:prove :answers] [:tests (:key req)] {:op :tests :id (:agent req)})
                 (step/ask st [:prove :answers] [:spawn (:key req)] {:op :spawn :opts {:branch :tests}}))]
    [(assoc-in st [:running :stage] (if (:agent req) :tests :spawn)) [m]]))

(defn- take-next
  "After a run of branch b: the pending run of the next branch in name order after b (then the
  first), started; nothing pending: idle. Answers [st outputs]."
  [st b]
  (let [bs (sort (keys (:next st)))
        nb (or (first (filter #(pos? (compare (name %) (name b))) bs)) (first bs))]
    (if nb
      (let [{:keys [waiters] :as req} (get-in st [:next nb])]
        (go (update st :next dissoc nb) (dissoc req :waiters) waiters))
      [st []])))

(defn- finish
  "The running run ended with answer r (recorded or not): every waiter answered once, its key
  remembered for its branch, the next run started. Answers [st outputs]."
  [st r]
  (let [{:keys [key branch waiters]} (:running st)
        st (-> st (dissoc :running) (update :done (fn [d] (assoc d branch {:key key :r r}))) (assoc :last branch))
        [st more] (take-next st branch)]
    [st (into (reply [] waiters r) more)]))

(defn- request
  "Admit request m ({:rid :branch :t :agent :proofs}) of the api. Answers [st outputs]."
  [st {:keys [rid branch] :as m}]
  (let [t (or (:t m) (:main-t st))
        key (run-key branch t)
        req (-> m (select-keys [:branch :agent :proofs]) (assoc :key key :t t))]
    (cond
      (= key (get-in st [:running :key])) [(update-in st [:running :waiters] conj rid) []]
      (= key (get-in st [:next branch :key])) [(update-in st [:next branch :waiters] conj rid) []]
      (= key (get-in st [:done branch :key])) [st (reply [] [rid] (get-in st [:done branch :r]))]
      (:running st) [(update-in st [:next branch] (fn [old] (assoc req :waiters (conj (:waiters old #{}) rid)))) []]
      :else (go st req #{rid}))))

(defn- answered
  "The api's answer r to the request of chain step; the next step, or the end. Answers [st outputs]."
  [st [step key] r]
  (let [run (:running st)]
    (if-not (= key (:key run))
      [st []]
      (case step
        :spawn (if (contract/error? r)
                 (finish st r)
                 (let [[st m] (step/ask (assoc-in st [:running :runner] (:id r)) [:prove :answers] [:tests key] {:op :tests :id (:id r)})]
                   [(assoc-in st [:running :stage] :tests) [m]]))
        :tests (let [st (assoc-in st [:running :answer] r)]
                 (if (contract/error? r)
                   (let [[st fin] (finish st r)
                         [st kill] (if-let [a (:runner run)] (step/ask st [:prove :answers] [:killed key] {:op :kill :id a}) [st nil])]
                     [st (cond-> fin kill (conj kill))])
                   (let [tx (result-tx run r)
                         [st w] (step/ask st [:prove :answers] [:recorded key] {:op :write :req {:op :tx :branch :db :tx-data tx}})
                         [st kill] (if-let [a (:runner run)] (step/ask st [:prove :answers] [:killed key] {:op :kill :id a}) [st nil])]
                     [(assoc-in st [:running :stage] :recording) (cond-> [w] kill (conj kill))])))
        ;; the run's answer, whole (its reports are what the done rule reads), once its facts are written
        :recorded (finish st (if (contract/error? r)
                               r
                               (assoc (:answer run) :key key :ran true :red (vec (sort-by str (keys (proofs/failures (:answer run))))))))
        :killed [st []]))))

(defn prove-proc
  "The test service's admission (see the namespace doc). In :req (requests the api forwards,
  {:rid :branch :t :agent :proofs}), :answers (the api's answers to this proc's requests) and
  :main (the writer's commit notice); out :to-api (its requests, and {:op :prove-answer :rid :r}
  for each waiter)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {} :ins {:req "requests to prove a branch at a tx" :answers "the api's answers" :main "main's commit notice, a sliding buffer of one"}
       :outs {:to-api "requests, and the answers to the waiters"}
       :workload :compute
       :ping-map-fn (fn [st] {:running (some-> st :running :key) :waiting (count (:waiters (:running st))) :next (count (:next st)) :main-t (:main-t st)})})
  ([_args] {:rid 0 :pending {} :next {} :done {}})
  ([st _tr] st)
  ([st in m]
   (let [[st outs] (case in
                     :req (request st m)
                     :answers (let [[st v] (step/answered st (:tag m))] (if v (answered st v (:r m)) [st []]))
                     :main [(assoc st :main-t (:t m)) []])]
     [st (if (seq outs) {:to-api (vec outs)} {})])))
