(ns seon.prove
  "The test service (docs/merging.md#the-test-service): one `:prove` proc admits every request to
  run tests, whoever asks (a Claude session through the `prove` server op / seon_prove, an agent's
  turn), so a change is proved once.

  - A request names a branch, its tx (t) and the digest of what its run reads (seon.system/prove!,
    seon.agent/prove!); its key (run-key) is \"branch@digest\", so a write that changes nothing
    the run reads (its own facts, the eval that asked) never makes a second run. A run is shared by its key, and the key
    holds the mode and the names asked: a session's tree run (mode judge, with a checkout, on its tree's branch) never joins
    a turn's run (mode turn, no checkout, keyed with !turn), whose plan holds no process item, check step or build.
  - Runs of different keys are in flight at once. A request whose key is in flight joins its waiters; it never starts a
    second run. Each run is planned by the agent holding its branch (what it would run, at their digests), and its
    items, (test, digest) pairs, join one queue: an item many runs need is queued once and run once, granted to a free
    worker (seon.prove/admission: :seon.config.proofs/max-workers, max-cores and max-load) one at a time, an agent's turn
    first, then by waiters and measured time. A run is answered when every proof of its asker has its verdict: an agent's turn its own proofs (its first red when it named
    none), any other run every item.
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
  digest, the plan taking no base). No checkout: two checkouts of one tree asking at once share a run, whose process items
  and steps run on the first asker's checkout; a checkout removed while that run reads it answers its waiters :tree-moved."
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

(defn- remember
  "st with run's green answer r remembered at its slot, and every slot of a branch that can no longer be asked dropped:
  the run's branch's slots at another digest (a key holds its digest, so an ask of the branch's code as it now is never
  matches them), and, for an answer :seon.system/ended, every slot of the branch (its agent ended). :done so holds the slots
  at each live branch's latest digest, a few each."
  {:malli/schema [:=> [:cat :map :map :any] :map]}
  [st run r]
  (let [ended? (= :seon.system/ended (:seon.error/type r))
        keep? (fn [[[b] v]] (not (and (= b (:branch run)) (or ended? (not= (:digest v) (:digest run))))))
        st (update st :done #(into {} (filter keep?) %))]
    (if (or (contract/error? r) (some :exit (:reports r)) (seq (:red r)))
      st
      (assoc-in st [:done (slot run)] {:key (:key r) :digest (:digest run)
                                       :r (-> r (dissoc :measured :verdicts) (assoc :ran 0)
                                              (update :reports (partial mapv #(assoc % :reused true)))
                                              (as-> x (assoc x :reused (count (distinct (keep :var (:reports x)))))))}))))

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
  cores rounded down, at least 1), the cores when neither is set; with :max-load when it is set; and :max-processes, the
  processes its running items may hold, derived from the cores (the cores and two: AGENTS.md §3a.7, never a key)."
  {:malli/schema [:=> [:cat [:map [:cores pos-int?] [:max-workers {:optional true} [:maybe pos-int?]]
                             [:max-cores {:optional true} [:maybe double?]] [:max-load {:optional true} [:maybe number?]]]]
                  [:map [:workers pos-int?] [:max-processes pos-int?] [:max-load {:optional true} number?]]]}
  [{:keys [max-workers max-cores max-load cores]}]
  (let [by-cores (when max-cores (max 1 (long (Math/floor (* max-cores cores)))))
        counts (filterv some? [max-workers by-cores])]
    (cond-> {:workers (if (seq counts) (reduce min counts) cores) :max-processes (+ cores 2)}
      max-load (assoc :max-load max-load))))

(defn config-admission
  "admission of the config this process holds: its optional keys as its branch states them, absent when unset. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat] [:map [:workers pos-int?] [:max-processes pos-int?] [:max-load {:optional true} number?]]]}
  []
  (let [c (:config (cfg/in-force))]
    (admission {:max-workers (:seon.config.proofs/max-workers c) :max-cores (:seon.config.proofs/max-cores c)
                :max-load (:seon.config.proofs/max-load c) :cores (cfg/value :seon.config.machine/cores)})))

(defn machine-load
  "The machine's 1-minute load average, as grant reads it: the one reading of the load the service's step makes (a test
  passes its own through this var). A reading of the machine, never a clock: docs/clocks.md#the-machines-load-admits-work."
  {:malli/schema [:=> [:cat] number?]}
  [] (lib/load-average))

(defn batch-tag
  "The value of SEON_PROVE_BATCH in every process batch bid starts: this service's process and the batch, so a reading of the
  machine's processes (held-processes) names the batch each belongs to, a root a process test detaches among them. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :int] :string]}
  [bid] (str (lib/c-getpid) "-" bid))

(defn held-processes
  "The processes on the machine by the SEON_PROVE_BATCH each was started with ({tag n}): one ps read of every process's
  environment (~70 ms, a process), the one reading of them grant makes a pass, and only while items run; nil when ps answers none
  (grant then grants nothing more while an item runs). A reading of the machine, never a clock:
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat] [:maybe [:map-of :string nat-int?]]]}
  []
  ;; awk keeps each process's tag, so only the tags cross (the whole listing is ~0.9 MB); pipefail answers ps's failure
  (let [r (lib/sh "zsh" "-c" (str "setopt pipefail; ps -axwwE -o command | awk 'match($0, /SEON_PROVE_BATCH=[^ ]+/) "
                                  "{ print substr($0, RSTART + 17, RLENGTH - 17) }'"))]
    (when (zero? (:exit r)) (frequencies (str/split-lines (:out r))))))

(defn- item-class
  "What an item's processes are measured by: its kind, a process test or not, on the binary or not."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [it] [(:kind it :test) (boolean (:process? it)) (boolean (:binary? it))])

(def ^:private plain
  "The class of a test that reaches no process (seon.program/process-path): its batch is its test child alone. See
  docs/merging.md#the-test-service."
  [:test false false])

(defn- expected
  "The processes an item of it's class holds at its peak, as far as st has read: a plain test's batch, its child; else the most
  a reading saw a batch of its class hold; a class never read, all of :max-processes, so it runs alone until its peak is read.
  Never another class's peak: a process test boots a whole root (its bin/held, its embed-worker, its children), and twenty of
  them granted at a step's three a batch put ~74 roots on 18 cores (runs/evidence/load70-1026)."
  {:malli/schema [:=> [:cat :map [:maybe :map]] pos-int?]}
  [st it]
  (let [c (item-class it)]
    (if (= plain c)
      1
      (or (get-in st [:peaks c]) (:max-processes st)))))

(defn- held-now
  "The processes batch bid holds by reading held (held-processes'): those started with its tag, and a test batch's own child
  (a fork, whose environment ps reads as its agent's)."
  {:malli/schema [:=> [:cat :map :int [:map-of :string nat-int?]] nat-int?]}
  [st bid held]
  (+ (get held (batch-tag bid) 0) (if (= :test (get-in st [:items (first (get-in st [:batches bid])) :kind] :test)) 1 0)))

(defn- in-flight
  "The processes st's running batches hold by reading held: each the more of what it holds now and its class's peak (a batch
  only just granted holds nothing yet; one past its peak holds less than it may again)."
  {:malli/schema [:=> [:cat :map [:map-of :string nat-int?]] nat-int?]}
  [st held]
  (reduce-kv (fn [n bid b] (+ n (max (held-now st bid held) (expected st (get-in st [:items (first b)]))))) 0 (:batches st)))

(defn- measured
  "st with each running batch's reading in held kept as its class's peak where it is the most read yet; batches in fresh, granted
  in this pass, are not read (their processes have not started)."
  {:malli/schema [:=> [:cat :map [:map-of :string nat-int?] [:set :int]] :map]}
  [st held fresh]
  (reduce-kv (fn [st bid b] (if (fresh bid) st
                               (let [c (item-class (get-in st [:items (first b)])) h (held-now st bid held)]
                                 (cond-> st (and (not= plain c) (> h (get-in st [:peaks c] 0))) (assoc-in [:peaks c] h)))))
             st (:batches st)))

(defn- free-workers
  "The workers st may still start items on under its count bound: its :workers less its running batches. See
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

(defn- drop-waits
  "Asker rid no longer waits on items ks: each loses it as a waiter, a queued one no one waits on leaves the queue, and they
  leave its :left. What a red's asker does with the items that are no proofs of its own (item-ended)."
  {:malli/schema [:=> [:cat :map :any [:sequential :any]] :map]}
  [st rid ks]
  (reduce (fn [st k]
            (let [st (-> st (update-in [:items k :waiters] disj rid) (update-in [:askers rid :left] disj k))
                  it (get-in st [:items k])]
              (cond-> st (and it (empty? (:waiters it)) (= :queued (:status it))) (update :items dissoc k))))
          st ks))

(defn- answer-asker
  "Asker rid answered with its verdict (:green true, or the reds and errors it met first) and :verdicts, every verdict it
  was told as it landed (item-ended), so a client streaming them can write the ones it has not yet: the answer, not the
  events' order, says what it is owed (docs/server.md#ops); and gone from every item's waiters; a queued item no one
  waits on any more leaves the queue (no work for an answer already given)."
  {:malli/schema [:=> [:cat :map :any :map] [:tuple :map [:vector :map]]]}
  [st rid r]
  (let [left (get-in st [:askers rid :left])
        r (assoc r :verdicts (get-in st [:askers rid :verdicts] []))
        st (reduce (fn [st k]
                     (let [st (update-in st [:items k :waiters] disj rid)
                           it (get-in st [:items k])]
                       (cond-> st (and it (empty? (:waiters it)) (= :queued (:status it))) (update :items dissoc k))))
                   (update st :askers dissoc rid) left)]
    [st [{:op :asker-answered :asker rid :r r}]]))

;; a fork's start (65 ms) and what its first test makes (a process test's root: ~20 s, then ~0.5 s a test) is paid once a batch,
;; so the queue goes to the workers in shares, never one test a fork (measured 2026-11-12, docs/merging.md#the-test-service)
(defn- batch-of
  "The batch granted with queued item k (it, its state): k and, for a test item of no turn, other queued test items of the same
  agent, mode and checkout that one of its askers waits on and whose needs are met, up to its share of them over the free workers. One pass that stops when full. A set of items. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :any :map] [:set :any]]}
  [st k it]
  ;; a turn's item alone: a turn is answered at its first red, so nothing of it runs past that in a batch
  (if (or (not= :test (:kind it :test)) (= :turn (:mode it)))
    #{k}
    (let [{:keys [agent mode checkout]} it
          fits? (fn [o] (and (= :queued (:status o)) (= :test (:kind o :test))
                             (= agent (:agent o)) (= mode (:mode o)) (= checkout (:checkout o)) (some (:waiters it) (:waiters o))
                             (not-any? (:items st) (:needs o))))
          queued (count (filter fits? (vals (:items st))))
          share (long (Math/ceil (/ queued (double (max 1 (free-workers st))))))]
      (loop [b #{k} [[k2 o] & more :as left] (seq (:items st))]
        (cond (empty? left) b
              (or (= k k2) (not (fits? o))) (recur b more)
              (< (count b) share) (recur (conj b k2) more)
              :else b)))))

(defn- grant
  "While admission allows (a worker free under :workers; with :max-load, the load under it, read once a pass through
  machine-load and kept as :load, or nothing running: the first item is always granted, so the queue never stalls with
  nothing to wake it) and an item is queued: the most valuable queued item (a :turn asker's first, then
  the one whose nearest-done asker has the least measured work left, item-value breaking a tie), with its batch (batch-of: a share of the queue for one fork), asked of the agent
  holding it as {:op :tests :id agent :mode m :names #{test} :items {test digest}} (each at the digest the agent's plan gave
  it: seon.agent/item-selection), or, an item of another :kind (a build, a check
  step, a tree's build or seed), of root's effect owner as {:op :fx :kind :item :req {:kind :tree :checkout :name :digest
  :binary? :bound}}; an item is not granted while an item it :needs (a set: its tree's build, seed) is queued or running;
  tagged
  [:batch bid]; each queued item passed over ages a skip.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (loop [st st outs [] ld nil held nil read? false fresh #{}]
    ;; the most valuable queued item, by one reduce over the items (no seq of them: a lazy filter and apply max-key
    ;; allocated 1.5 KB an item queued, each grant)
    (let [bid0 (:bid st 0)
          ;; the askers of a turn, once a grant: most grants have none, and then no item's waiters are read for them
          turns (into #{} (keep (fn [[r a]] (when (= :turn (:prio a)) r))) (:askers st))
          running (count (:batches st))
          free? (pos? (free-workers st))
          ;; the processes the running batches hold, read once a pass and only while one runs (held-processes): what decides
          ;; whether one more fits under :max-processes; each reading is kept as its classes' peaks (measured)
          ;; plain tests alone (each batch its child) need no reading: they hold what they count
          ;; (held: nil unread, or ps answered none, and then nothing more is granted while one runs)
          reading? (and free? (pos? running) (not read?) (some #(= :queued (:status %)) (vals (:items st))))
          held (cond (not reading?) held
                     (every? #(= plain (item-class %)) (vals (:items st))) {}
                     :else (held-processes))
          read? (or read? reading?)
          st (if (and (map? held) (empty? fresh)) (measured st held fresh) st)
          flight (when (map? held) (in-flight st held))
          ;; each asker's work left: the measured ms of its queued items, one pass (an item many wait on counts for each)
          ;; with one asker every item ties, so the pass is made only when two or more wait
          left (when (and free? (next (:askers st))) (reduce-kv (fn [m _ it] (if (= :queued (:status it)) (reduce #(update %1 %2 (fnil + 0) (:ms it 1)) m (:waiters it)) m))
                                      {} (:items st)))
          [k it] (when free?
                   (reduce-kv (fn [best k it]
                                ;; an item that needs its tree's build or seed waits while that is queued or running (a red one ends it)
                                (if (and (= :queued (:status it)) (not-any? (:items st) (:needs it))
                                         ;; with nothing running, any (never stalled); else where its class's peak fits under
                                         ;; :max-processes beside what the running batches hold (none while unread)
                                         (or (zero? running) (and flight (<= (+ flight (expected st it)) (:max-processes st)))))
                                  ;; a turn's first; then the item of the asker nearest done (shortest remaining work first: the least
                                  ;; mean time to an answer over askers); its waiters and age only break a tie
                                  (let [v (+ (if (and (seq turns) (some turns (:waiters it))) 1e12 0)
                                             (if left (/ 1e6 (inc (double (reduce min Double/MAX_VALUE (keep left (:waiters it)))))) 0)
                                             (* 1e-6 (item-value it bid0)))]
                                    (if (or (nil? best) (> v (nth best 2))) [k it v] best))
                                  best))
                              nil (:items st)))
          ;; the load is read only when it decides: an item to grant, one running, and a max-load set
          ld (if (and k (nil? ld) (:max-load st) (pos? running)) (machine-load) ld)
          st (cond-> st ld (assoc :load ld))]
      (cond
        (nil? k) [st outs]
        ;; held for the load: each asker with an item queued is told once as the hold begins (:prove-held, which its
        ;; client prints, so a hold never reads as a hang), and once as it ends, at the next grant (:resumed)
        (and ld (pos? running) (>= ld (:max-load st)))
        (if (:held st)
          [st outs]
          (let [askers (into (sorted-set) (comp (filter #(= :queued (:status %))) (mapcat :waiters)) (vals (:items st)))
                queued (count (filter #(= :queued (:status %)) (vals (:items st))))]
            [(assoc st :held askers)
             (into outs (for [a askers] {:op :prove-held :key a :held true :load ld :max-load (:max-load st) :queued queued}))]))
        :else
        (let [outs (into outs (for [a (:held st)] {:op :prove-held :key a :held false}))
              st (dissoc st :held)
              batch (batch-of st k it)
              bid (inc (:bid st 0))
              st (reduce #(assoc-in %1 [:items %2 :status] :running) (-> st (assoc :bid bid) (assoc-in [:batches bid] batch)) batch)
              [st m] (step/ask st [:prove :answers] [:batch (str bid)]
                               (if (= :test (:kind it :test))
                                 (cond-> {:op :tests :id (:agent it) :mode (:mode it :judge) :names (into #{} (map first) batch) :items (into {} batch)
                                          :seon.prove/batch (batch-tag bid)}
                                   (:checkout it) (assoc :checkout (:checkout it)))
                                 ;; a build, a seed or a check step: root's effect owner runs it on the run's checkout
                                 {:op :fx :kind :item :req {:kind (:kind it) :tree (:tree it) :checkout (:checkout it)
                                                            :name (if (#{:build :seed} (:kind it)) (:kind it) (first k)) :digest (second k)
                                                            :binary? (boolean (:binary? it)) :bound (:bound it) :seon.prove/batch (batch-tag bid)}}))]
          (recur st (conj outs m) ld held read? (conj fresh bid)))))))

(defn- item-ended
  "Item k ended with result r ({:pass :fail :error} counts, its :failures; an error: its batch died): each asker waiting on
  it loses it; one that met a red or an error is answered so at once; one with nothing left is answered green. The item leaves the state: its verdict is the cache's now (its runner wrote it as it ended).
  A build or seed that is not green is its typed error, and so is every item that needs it (none is granted). Each asker is
  first told the verdict as it lands, {:op :prove-verdict :key asker :test :digest :kind :status (:pass :fail :error,
  or :no-verdict for an error) :ms :failures}, which root puts on its events for a streaming :prove to write
  (docs/merging.md#the-test-service). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :any :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters kind checkout]} (get-in st [:items k])
        st (update st :items dissoc k)
        [t d] k
        r (if (and (#{:build :seed} kind) (not (contract/error? r)) (not (proofs/green? r)))
            (contract/error :seon.prove/build-red #'seon.prove/item-ended (str "the " (name kind) " of tree " d " is red") {:tree d :kind kind :answer r})
            r)
        red? (or (contract/error? r) (not (proofs/green? r)))
        ;; a green build or seed is the checkout's for its tree: a later run of that tree reuses it (ask-items), so no second
        ;; one relinks the binary or remakes the store while the first run's items read them. One tree a checkout: a new
        ;; tree's replaces it
        st (if (and (#{:build :seed} kind) (not red?))
             (update-in st [:made checkout] #(if (= d (:tree %)) (update % :items conj k) {:tree d :items #{k}}))
             st)
        ms (or (get-in r [:answer :measured t :ms]) (when (map? r) (:ms r)))
        verdict (cond-> (cond (contract/error? r) {:test t :digest d :status :no-verdict :failures [(select-keys r [:seon.error/type :seon.error/message])]}
                              :else (cond-> {:test t :digest d :status (proofs/status-of r)} (seq (:failures r)) (assoc :failures (:failures r))))
                  kind (assoc :kind kind)
                  ms (assoc :ms ms))
        [st outs] (reduce (fn [[st outs] rid]
                            (let [st (-> st (update-in [:askers rid :left] disj k) (update-in [:askers rid :verdicts] (fnil conj []) verdict)
                                         (assoc-in [:askers rid :results t] r))
                                  {:keys [left results]} (get-in st [:askers rid])]
                              (cond
                                    ;; a red, and a proof of the asker's still to hear: it keeps waiting for its proofs alone (Sean,
                                    ;; 2026-11-11: an agent sees every proof's verdict, never :none because another went red); the
                                    ;; other items it waited on it no longer needs (drop-waits)
                                    (and (or red? (get-in st [:askers rid :red?])) (some (get-in st [:askers rid :proofs] #{}) left))
                                    [(drop-waits (assoc-in st [:askers rid :red?] true) rid (remove (get-in st [:askers rid :proofs]) left)) outs]
                                    (or red? (get-in st [:askers rid :red?])) (let [[st o] (answer-asker st rid {:green false :results results
                                                                                    :red (vec (sort-by str (keep (fn [[t r]] (when (or (contract/error? r) (not (proofs/green? r))) t)) results)))})]
                                           [st (into outs o)])
                                    (empty? left) (let [[st o] (answer-asker st rid {:green true :results results})] [st (into outs o)])
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
  are queued once; then slots are granted (grant). A build or seed its checkout already made green for its tree (:made,
  item-ended) is not queued again, and nothing waits on it.
  With no item it is answered green at once. Answers [st outputs]. See docs/merging.md#the-test-service."
  {:malli/schema [:function [:=> [:cat :map :any [:enum :turn :batch] [:map-of :any :map]] [:tuple :map [:vector :map]]]
                  [:=> [:cat :map :any [:enum :turn :batch] [:map-of :any :map] [:set :any]] [:tuple :map [:vector :map]]]]}
  ([st rid prio items] (ask-items st rid prio items #{}))
  ([st rid prio items proofs]
  (let [made? (fn [[k m]] (contains? (get-in st [:made (:checkout m) :items]) k))
        done (into #{} (comp (filter made?) (map key)) items)
        items (into {} (comp (remove made?) (map (fn [[k m]] [k (cond-> m (:needs m) (update :needs #(not-empty (reduce disj % done))))]))) items)]
  (if (empty? items)
    [st [{:op :asker-answered :asker rid :r {:green true :results {}}}]]
    (let [st (reduce (fn [st [k m]]
                       (if (get-in st [:items k])
                         (update-in st [:items k :waiters] conj rid)
                         (assoc-in st [:items k] (assoc (select-keys m [:agent :ms :mode :kind :needs :tree :checkout :binary? :bound :process?]) :status :queued :waiters #{rid} :at (:bid st 0)))))
                     (assoc-in st [:askers rid] {:left (set (keys items)) :prio prio :results {}
                                                 ;; the items that are its proofs: past a red it still waits for these (item-ended)
                                                 :proofs (into #{} (filter #(contains? proofs (first %))) (keys items))}) items)]
      (grant st))))))

(defn batch-ended
  "Batch bid's answer r (the agent's :tests answer: its :reports by test var, its :failures; or the error its run answered,
  a died child among them): each of its items ended with its own result, one the answer does not name with the typed
  :seon.prove/no-verdict (never silence), every item of a batch that errored with that error; or, for an item of root's
  effect owner (a build, a process test, a step), its {:value {:pass :fail :error :failures :ms}}, or its typed
  error; its slot is free again and the next is granted. A no-verdict is the core failing, so it is also a :platform
  problem, carrying its evidence: the test, its digest and the names the answer did report. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :int :any] [:tuple :map [:vector :map]]]}
  [st bid r]
  (let [batch (get-in st [:batches bid])
        fx? (not= :test (get-in st [:items (first batch) :kind] :test))
        st (update st :batches dissoc bid)
        fails (when-not (or fx? (contract/error? r)) (proofs/failures r))
        reported (when-not (or fx? (contract/error? r)) (into #{} (keep #(some-> (:var %) (subs 2) symbol)) (:reports r)))
        ;; ended at its wall bound: wall time reads the machine, so no verdict, a :platform problem (seon.proofs/answer-ran)
        wall (when-not (or fx? (contract/error? r)) (into #{} (keep #(when (= :seon.test/wall-spent (:seon.error/type %)) (some-> (:var %) (subs 2) symbol))) (:reports r)))
        v (when fx? (:value r))
        result (fn [[t d]] (cond (contract/error? r) r
                                 ;; root's effect owner ran it: its verdict reads as a test's
                                 (and fx? (map? v)) (assoc v :answer {:pass (:pass v 0) :fail (:fail v 0) :error (:error v 0) :digests {t d}
                                                                      :reports (if (seq (:failures v)) (mapv #(assoc % :var (str "#'" t)) (:failures v)) [{:type :pass :var (str "#'" t)}])})
                                 (and (not fx?) (wall t)) (contract/error :seon.prove/no-verdict #'seon.prove/batch-ended
                                                                          (str t " ended at its wall bound (:seon.test/wall-spent): wall time reads the machine, so it is no verdict and is never cached; its fiber dump is on its test child's stderr")
                                                                          {:test t :digest d :reported (vec (sort-by str reported))})
                                 (and (not fx?) (fails t)) {:pass 0 :fail 1 :error 0 :failures (fails t) :answer r}
                                 (and (not fx?) (reported t)) {:pass 1 :fail 0 :error 0 :answer r}
                                 :else (contract/error :seon.prove/no-verdict #'seon.prove/batch-ended
                                                       (str t "'s batch answered no verdict for it")
                                                       {:test t :digest d :reported (vec (sort-by str reported))})))
        results (map (juxt identity result) (sort-by str batch))
        [st outs] (reduce (fn [[st outs] [k r]] (let [[st o] (item-ended st k r)] [st (into outs o)])) [st []] results)
        no-verdicts (for [[_ r] results :when (= :seon.prove/no-verdict (:seon.error/type r))] {:op :problem :class :platform :error r})
        [st more] (grant st)]
    [st (-> outs (into no-verdicts) (into more))]))

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

(defn- batch-errors
  "The reports of a run's batches that name no test (a child that died before a test began), once a batch: its items share its
  answer, and run-answer counts its whole answer (a count with no report said nothing of why). See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [results]
  (into [] (comp (keep (fn [[_ r]] (when (map? (:answer r)) (:answer r)))) (distinct)
                 (mapcat (fn [a] (remove #(proofs/test-symbol (:var %)) (:reports a)))))
        (sort-by (comp str key) results)))

(defn- run-answer
  "The answer run (its plan :base) is given, its items' results (per test: the item's counts, :failures and the :answer of
  the grant that ran it; or the error its batch answered): the run-tests-forked shape the done rule and an agent's prove
  read: the plan's reused reports, each item's own reports, every digest, function cost and measure (:measured, which the
  asker's agent writes as its facts: seon.agent/record-answer!), the counts, what was selected, deferred and why, and
  any verdict the cache would not take (:unstored). A process test's and a check step's results count as a test's; a
  build's is no result of the run (an item that needed it answers its error). :red names every red of the run by its item's name, a check step's (a string) among the tests (finish keeps it), as
  its failing reports could not: a step's name is no test var. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [base results]
  (let [results (into {} (remove (comp vector? key)) results)
        own (fn [t r] (if (contract/error? r)
                        [{:type :error :var (str "#'" t) :message (:seon.error/message r) :seon.error/type (:seon.error/type r)}]
                        (filterv #(= (str "#'" t) (:var %)) (:reports (:answer r)))))
        reports (-> (vec (:reused-reports base)) (into (mapcat (fn [[t r]] (own t r))) (sort-by (comp str key) results))
                    (into (batch-errors results)))
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
      ;; each deferred test's digest at the base, as the agent's plan gave it: what the merge check reads main's verdict at
      (seq (:deferred-digests base)) (assoc :deferred-digests (:deferred-digests base))
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
  again. An error is a :platform problem too, but :seon.system/ended (the agent it asked ended: a cancel, its run's end).
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :string :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters] :as run} (get-in st [:runs k])
        err? (contract/error? r)
        ended? (#{:seon.system/ended :seon.prove/superseded} (:seon.error/type r))
        ;; a run's items name their own reds (run-answer: a step's among them); any other answer, by its failing reports
        r (if err? r (assoc r :key k :red (vec (sort-by str (distinct (concat (:red r) (keys (proofs/failures r))))))))
        ;; asked again, it ran nothing for that asker: each verdict reads as reused at its digest, with no measure to add
        st (remember (-> st (update :runs dissoc k) (assoc :last (:branch run))) run r)
        [st kill] (runner-done st run)]
    [st (cond-> (into (reply [] waiters r) kill) (and err? (not ended?)) (conj {:op :problem :class :platform :error r}))]))

(defn- settle
  "Outputs of the queue (ask-items, batch-ended) carried out: an :asker-answered of run k finishes it with its answer
  (run-answer of its plan and its items' results, each item with no verdict its own error; or, when no item landed a verdict, the
  typed error); every other output passes through. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:tuple :map [:vector :map]]]}
  [st outs]
  (reduce (fn [[st acc] o]
            (if (= :asker-answered (:op o))
              (let [k (:asker o) run (get-in st [:runs k])]
                (if run
                  ;; a run none of whose items landed a verdict (its forks refused, its batches errored) answers the typed error, as a
                  ;; run that could not run; else its verdicts, each item with no verdict its own :error report (run-answer), so one
                  ;; test ended at its wall bound voids only its own verdict, never the run's (a task closed on it)
                  (let [rs (:results (:r o))
                        ;; a build or seed is no result of the run (run-answer): only its tests and steps decide
                        own (sort-by (comp str key) (remove (comp vector? key) rs))
                        [st more] (finish st k (or (when (and (seq own) (every? (comp contract/error? val) own)) (val (first own)))
                                                   (assoc (run-answer (:base run) rs) :verdicts (:verdicts (:r o) []))))]
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
  :rid (main's commit notice) is a run nobody waits on. A run in flight for the same slot at another digest is superseded first:
  its waiters answered :seon.prove/superseded, its items no one else waits on dropped. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st {:keys [rid branch] :as m}]
  (let [rids (if rid #{rid} #{})
        t (or (:t m) (:main-t st))
        names (not-empty (:names m))
        mode (or (:mode m) :judge)
        key (run-key branch (:digest m) names mode)
        req (-> m (select-keys [:branch :digest :agent :proofs :checkout :tree]) (assoc :key key :t t :names names :mode mode))
        done (get-in st [:done (slot req)])]
    (cond
      ;; the daily full run, a run of its own
      (= :full mode) (full-request st req)
      ;; a run of this key in flight: joined
      (get-in st [:runs key]) [(update-in st [:runs key :waiters] into rids) []]
      ;; asked again after its run, nothing it reads changed
      (and done (= key (:key done))) [st (reply [] rids (:r done))]
      ;; a run of this slot at another digest is superseded: its branch moved on, so what only it wanted leaves the queue
      (some (fn [[k r]] (and (not= k key) (= (slot r) (slot req)))) (:runs st))
      (let [[st outs] (reduce (fn [[st outs] [k r]]
                                (if (and (not= k key) (= (slot r) (slot req)))
                                  (let [st (-> st (drop-waits k (vec (get-in st [:askers k :left]))) (update :askers dissoc k))
                                        [st o] (finish st k (contract/error :seon.prove/superseded #'seon.prove/request
                                                                            (str "run " k " was superseded by " key ": its branch moved on") {:by key}))]
                                    [st (into outs o)])
                                  [st outs]))
                              [st []] (sort-by first (:runs st)))
            [st o2] (request st m)]
        [st (into outs o2)])
      (:agent req) (plan! (assoc-in st [:runs key] (assoc req :waiters rids)) key)
      ;; main: a prove agent on the tests branch, forked from the fork server's main, plans it
      :else (let [st (assoc-in st [:runs key] (assoc req :waiters rids :stage :spawn))
                  [st m] (step/ask st [:prove :answers] [:spawn key] {:op :spawn :opts {:branch :tests}})]
              [st [m]]))))

(defn- plan-items
  "The items of plan r ({:process {test digest} :steps {step {:digest :binary? :bound}} :build {:tree :needed?}}, seon.agent's
  answer to a :process? plan) that root's effect owner runs on run's checkout (main's, the checkout this root runs from, for
  a run that names none) at its tree (the run's, else the plan's): the build, [[:build tree] tree], once a tree when it is
  needed; the seed, [[:seed tree] tree], needing the build when there is one, which brings the checkout's program store to the
  tree (and, :binary?, seeds the check store and the process tests' store); each process test and each step, needing the seed. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :map] [:map-of :any :map]]}
  [run r]
  (let [tree (or (:tree run) (get-in r [:build :tree]))
        on {:tree tree :checkout (or (:checkout run) (cfg/checkout))}
        bk [[:build tree] tree]
        build? (get-in r [:build :needed?])
        ;; the seed brings the checkout's plan program store to the tree first, which every process and step item clones
        ;; (seon.fx/run-item), so each of them needs it; when a process test or a binary step runs, it also seeds the check
        ;; store and makes the process tests' store (its :binary?)
        seed? (boolean (or (seq (:process r)) (seq (:steps r))))
        stores? (boolean (or (seq (:process r)) (some :binary? (vals (:steps r)))))
        ;; a seed that seeds the stores is another item than one that only keeps the tree: a checkout's green one of the
        ;; second (:made) never stands for the first
        sk [[:seed tree (if stores? :stores :tree)] tree]]
    (cond-> (into {}
                  ;; a process test runs as any test does, in a fork of the agent holding the run's branch (a batch of them shares
                  ;; the one test root the first starts, ~20 s, then ~0.5 s a test), never a cold bin/test process (~10 s more each);
                  ;; it needs the tree's seed: the binary and the process tests' store
                  (concat (for [[t d] (:process r)]
                            [[t d] (cond-> (assoc on :kind :test :process? true :agent (:agent run) :mode (:mode run) :ms (get-in r [:ms t])) seed? (assoc :needs #{sk}))])
                          (for [[s {:keys [digest binary? bound]}] (:steps r)]
                            [[s digest] (cond-> (assoc on :kind :step :binary? (boolean binary?) :bound bound)
                                          seed? (assoc :needs #{sk}))])))
      build? (assoc bk (assoc on :kind :build))
      seed? (assoc sk (cond-> (assoc on :kind :seed :binary? stores?) build? (assoc :needs #{bk}))))))

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
                             items (into (plan-items run r) (for [[t d] (:items r)] [[t d] (cond-> {:agent (:agent run) :mode (:mode run) :kind :test} (get-in r [:ms t]) (assoc :ms (get-in r [:ms t]))
                                                                                     (:checkout run) (assoc :checkout (:checkout run)))]))
                             ;; past a red, a turn waits for its own proofs; any other run (a tree's :judge, main's) is a verdict of the
                             ;; whole and waits for every item, so a session and a landing hear every red, never only the first
                             proofs (if (= :turn (:mode run)) (set (:proofs run)) (into #{} (map first) (keys items)))
                             [st outs] (ask-items (assoc-in st [:runs key :stage] :items) key prio items proofs)]
                         (settle st outs))
        :else [(update st :stale (fnil inc 0)) []]))))

(defn- state-view
  "What the service holds, as data: the runs in flight (each key's stage and waiters), the queue (items queued and running),
  the free workers, the load it last read (when a max-load is set), and the api's asks it has open. A hang in the chain is read from it (seon.system's stall task records it)."
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
