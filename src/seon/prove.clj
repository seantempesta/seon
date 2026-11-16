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
    asked of root's :process effect owner, which admits it by the processes its class holds (seon.fx/admits?), an agent's turn
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
  A request that names no t is of main's head, read from the store's connection when it arrives; main is proved when someone
  asks, never by a commit (on a clean store a run per program move started 223 tests at boot)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.proofs :as proofs] [seon.program :as program] [seon.config :as cfg] [seon.fx :as fx]
            [clojure.core.async.flow :as flow] [clojure.string :as str]))

(defn run-key
  "The key of the run of branch whose program has digest dg (seon.program/run-digest of its
  head: what a run reads, so a write that changes no form never makes a second run), of the tests of
  namespaces names (nil: all it selects), in mode (:judge: the plan's tests; :turn: the proofs and
  what the branch changed, an agent's turn; :main: main's run, every test with no result at its
  digest, the plan taking no base). No checkout: two checkouts of one tree asking at once share a run, whose process items
  and steps run on the first asker's checkout; a checkout removed while that run reads it answers its waiters :tree-moved."
  {:malli/schema [:function [:=> [:cat :keyword :string [:maybe [:set :symbol]] [:enum :turn :judge :main]] :string]
                  [:=> [:cat :keyword :string [:maybe [:set :symbol]] [:enum :turn :judge :main] :boolean] :string]]}
  ([branch dg names mode] (run-key branch dg names mode false))
  ;; process?: the judge of a merge, whose process tests run on the candidate: another run than an eval's prove of the same digest
  ([branch dg names mode process?] (str (name branch) "@" dg (when (seq names) (str ":" (str/join "," (sort names)))) (when (= :turn mode) "!turn") (when process? "!process"))))

;; ---- answers ---------------------------------------------------------------------------

(defn- reply
  "outs with an answer r for each of rids."
  {:malli/schema [:=> [:cat [:vector :map] [:or [:set :int] [:sequential :int]] :any] [:vector :map]]}
  [outs rids r] (into outs (for [rid rids] {:op :prove-answer :rid rid :r r})))

(defn- slot
  "The identity of what a request asks for apart from its digest: the branch, the namespaces named and the mode, under
  which a run answered green is remembered (:done), so asking again at the same key is answered from it."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword [:maybe [:set :symbol]] [:enum :turn :judge :main]]]}
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
    (if (or (contract/error? r) (some :exit (:reports r)) (seq (:red r)) (seq (:no-verdict r)))
      st
      (assoc-in st [:done (slot run)] {:key (:key r) :digest (:digest run)
                                       :r (-> r (dissoc :measured :verdicts) (assoc :ran 0)
                                              (update :reports (partial mapv #(assoc % :reused true)))
                                              (as-> x (assoc x :reused (count (distinct (keep :var (:reports x)))))))}))))

;; ---- items: a (test, digest) pair, run once whoever asks ------------------------------------
;; The test service's unit of work (notes test-system-as-a-flow-2026-11-10.md, step 2): an asker names the items its plan
;; has no verdict for; each item is queued once with every asker waiting on it, granted in batches to the agent holding
;; its branch as requests to root's :process effect owner, which admits them by the processes each class holds (seon.fx/admits?),
;; and each asker is answered at its first red or its last green. State: :items {[test digest] {:status :waiters :agent :ms :mode
;; :at}}, :askers {rid {:left :prio :results}}, :batches {bid #{item}} (the items asked), :budget n (the owner's max-workers, seon.fx/limits: the share
;; batch-of deals a queue over). No clock: an item passed over is aged in skips.

(defn- item-class
  "An item's :seon.fx/class at the :process owner, what its processes are measured by: its kind, a process test or not, on the
  binary or not. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [it] [(:kind it :test) (boolean (:process? it)) (boolean (:binary? it))])

(def ^:private plain
  "The class of a test that reaches no process (seon.program/process-path): its batch is its test child alone. See
  docs/merging.md#the-test-service."
  [:test false false])

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

(defn- shared-free
  "How many more batches of the process tests that share the candidate's one root (an item's :shared?) st may run now: the
  :seon.config.proofs/shared-root-batches dial less those running (each a batch of :batches whose items are running and shared), at
  least 0. One pass over the batches. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] nat-int?]}
  [st]
  (max 0 (- (cfg/value :seon.config.proofs/shared-root-batches)
            (count (filter (fn [[_ b]] (some #(let [it (get-in st [:items %])] (and (:shared? it) (= :running (:status it)))) b)) (:batches st))))))

;; a fork's start (65 ms) and what its first test makes (a process test's root: ~20 s, then ~0.5 s a test) is paid once a batch,
;; so the queue goes to the workers in shares, never one test a fork (measured 2026-11-12, docs/merging.md#the-test-service)
(defn- batch-of
  "The batch granted with queued item k (it, its state): k and, for a test item of no turn, other queued test items of the same
  agent, mode and checkout that one of its askers waits on and whose needs are met, up to its share of them over free, the batches this grant may still deal (the :process owner's max-workers, seon.fx/limits, less
  those it dealt). One pass that stops when full. A set of items. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :any :map pos-int?] [:set :any]]}
  [st k it free]
  ;; a turn's item alone: a turn is answered at its first red, so nothing of it runs past that in a batch
  (if (or (not= :test (:kind it :test)) (= :turn (:mode it)))
    #{k}
    (let [{:keys [agent mode checkout]} it
          fits? (fn [o] (and (= :queued (:status o)) (= :test (:kind o :test)) (= (boolean (:process? it)) (boolean (:process? o)))
                             (= (boolean (:shared? it)) (boolean (:shared? o)))
                             (= agent (:agent o)) (= mode (:mode o)) (= checkout (:checkout o)) (some (:waiters it) (:waiters o))
                             (not-any? (:items st) (:needs o))))
          queued (count (filter fits? (vals (:items st))))
          ;; process items too go to the free workers in shares: they share the tree's one booted candidate (:candidates), so no
          ;; child boots a root of its own (they once shared one child for that root, 166 tests in one sequence, 732 s)
          ;; those sharing the candidate's one root: over the batches of them the dial still allows (shared-free), never the free workers
          share (long (Math/ceil (/ queued (double (if (:shared? it) (shared-free st) free)))))]
      (loop [b #{k} [[k2 o] & more :as left] (seq (:items st))]
        (cond (empty? left) b
              (or (= k k2) (not (fits? o))) (recur b more)
              (< (count b) share) (recur (conj b k2) more)
              :else b)))))

(defn- grant
  "Every queued item whose needs are met (an item it :needs, its tree's build or seed, neither queued nor running) asked of
  root's :process effect owner, the most valuable first (a :turn asker's, then the one whose nearest-done asker has the least
  measured work left, item-value breaking a tie), each with its batch (batch-of: a share of the queue for one fork) as
  {:op :fx :kind :process :prio v :seon.fx/class c :req r}: the owner admits it by the processes its class holds
  (seon.fx/admits?), so this service holds no admission of its own. r is a test batch {:kind :test :seon.fx/ask {:op :tests :id agent
  :mode m :names #{test} :items {test digest}}} (each at the digest the agent's plan gave it: seon.agent/item-selection), or
  {:kind :tree :checkout :name :digest :binary? :bound} for a build, a check step, a tree's build or seed. Tagged [:batch bid].
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  ;; the owner's max-workers as the config states it now, derived once a config change (seon.fx/limits-held: one compare a grant),
  ;; so a dial turned deals the next grant by it; a :fixed-budget in st (a test's) holds it instead
  (loop [st (let [[st lim] (fx/limits-held st)] (assoc st :budget (or (:fixed-budget st) (:max-workers lim)))) outs [] sent 0]
    (let [bid0 (:bid st 0)
          turns (into #{} (keep (fn [[r a]] (when (= :turn (:prio a)) r))) (:askers st))
          ;; each asker's work left: the measured ms of its queued items, one pass, only when two or more wait
          left (when (next (:askers st)) (reduce-kv (fn [m _ it] (if (= :queued (:status it)) (reduce #(update %1 %2 (fnil + 0) (:ms it 1)) m (:waiters it)) m))
                                                    {} (:items st)))
          value (fn [it] (+ (if (and (seq turns) (some turns (:waiters it))) 1e12 0)
                            (if left (/ 1e6 (inc (double (reduce min Double/MAX_VALUE (keep left (:waiters it)))))) 0)
                            (* 1e-6 (item-value it bid0))))
          ;; the batches of shared-root items the dial still allows: once a pass
          sf (shared-free st)
          [k it v] (reduce-kv (fn [best k it]
                                (if (and (= :queued (:status it)) (not-any? (:items st) (:needs it))
                                         ;; one that shares the candidate's root waits while the dial's batches of them run
                                         (or (not (:shared? it)) (pos? sf)))
                                  (let [v (value it)] (if (or (nil? best) (> v (nth best 2))) [k it v] best))
                                  best))
                              nil (:items st))]
      ;; credit at the ask site: no batch is asked once the open asks fill the :answers port (flow's review: one grant could open
      ;; a budget's worth past it; the input filter is only the backstop)
      (if (or (nil? k) (>= (count (:pending st)) (cfg/value :seon.config.root/reconciler-buf)))
        [st outs]
        (let [batch (batch-of st k it (max 1 (- (:budget st) sent)))
              bid (inc (:bid st 0))
              st (reduce #(assoc-in %1 [:items %2 :status] :running) (-> st (assoc :bid bid) (assoc-in [:batches bid] batch)) batch)
              c (item-class it)
              [st m] (step/ask st [:prove :answers] [:batch (str bid)]
                               (cond-> {:op :fx :kind :process :prio v :seon.fx/class c
                                        ;; who waits on it: told when the owner holds it for the machine's load
                                        :seon.prove/askers (into (sorted-set) (mapcat #(get-in st [:items % :waiters])) batch)
                                        :req (if (= :test (:kind it :test))
                                               {:kind :test :seon.prove/batch (fx/batch-tag bid)
                                                ;; the agent runs it: the owner asks the api for it and takes its one answer
                                                :seon.fx/ask (cond-> {:op :tests :id (:agent it) :mode (:mode it :judge) :names (into #{} (map first) batch) :items (into {} batch)
                                                              :seon.prove/batch (fx/batch-tag bid)}
                                                       (:checkout it) (assoc :checkout (:checkout it))
                                                       ;; process tests run on the tree's one booted candidate, on a branch of this batch's own
                                                       (when-let [cd (get-in st [:candidates (or (:checkout it) (:branch it))])]
                                                         (and (:process? it) (contains? (:needs it) [[:candidate (:tree cd)] (:tree cd)])))
                                                       (assoc :seon.test/candidate (assoc (select-keys (get-in st [:candidates (or (:checkout it) (:branch it)) :candidate]) [:port :store :main])
                                                                                          :branch (keyword (str "candidate-batch-" bid)))))}
                                               ;; a build, a seed or a check step: run on the run's checkout
                                               (cond-> {:kind (:kind it) :tree (:tree it) :checkout (:checkout it)
                                                        :name (if (#{:build :seed} (:kind it)) (:kind it) (first k)) :digest (second k)
                                                        :binary? (boolean (:binary? it)) :bound (:bound it) :seon.prove/batch (fx/batch-tag bid)}
                                                 ;; an agent branch's candidate names its branch (it has no checkout)
                                                 (:branch it) (assoc :branch (:branch it))))}
                                 (= plain c) (assoc :seon.fx/processes 1)))]
          (recur st (conj outs m) (inc sent)))))))

(defn- stop-candidate
  "st asking the :process effect to stop candidate c (seon.test-store/stop-candidate!, its store removed, its runs and logs too unless
  c is :keep-logs?, a candidate one of whose items went red), its checkout's tree
  having moved on; its answer is only counted (answered's :stop-candidate). Answers [st output]. See task
  seon.prove/tests-fork-from-the-branchs-warm-agent."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map :map]]}
  [st c] (step/ask st [:prove :answers] [:stop-candidate (str (:port c))]
                   {:op :fx :kind :process :prio 0 :seon.fx/class [:candidate false true] :seon.fx/processes 1 :req {:kind :stop-candidate :candidate c}}))

(defn- item-ended
  "Item k ended with result r ({:pass :fail :error} counts, its :failures; an error: its batch died): each asker waiting on
  it loses it; one that met a red or an error is answered so at once; one with nothing left is answered green. The item leaves the state: its verdict is the cache's now (its runner wrote it as it ended).
  A build or seed that is not green is its typed error, and so is every item that needs it (none is granted). Each asker is
  first told the verdict as it lands, {:op :prove-verdict :key asker :test :digest :kind :status (:pass :fail :error,
  or :no-verdict for an error) :ms :failures}, which root puts on its events for a streaming :prove to write
  (docs/merging.md#the-test-service). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :any :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters kind checkout branch]} (get-in st [:items k])
        ;; where a build, seed or candidate is made and held: its checkout, else (an agent's branch's candidate) its branch
        checkout (or checkout branch)
        st (update st :items dissoc k)
        [t d] k
        r (if (and (#{:build :seed :candidate} kind) (not (contract/error? r)) (not (proofs/green? r)))
            (contract/error :seon.prove/build-red #'seon.prove/item-ended (str "the " (name kind) " of tree " d " is red") {:tree d :kind kind :answer r})
            r)
        red? (or (contract/error? r) (not (proofs/green? r)))
        ;; a green build or seed is the checkout's for its tree: a later run of that tree reuses it (ask-items), so no second
        ;; one relinks the binary or remakes the store while the first run's items read them. One tree a checkout: a new
        ;; tree's replaces it
        st (if (and (#{:build :seed :candidate} kind) (not red?))
             (update-in st [:made checkout] #(if (= d (:tree %)) (update % :items conj k) {:tree d :items #{k}}))
             st)
        ;; a booted candidate is held for its checkout's tree; one of an older tree is stopped (stop-candidate, an effect)
        old (when (and (= :candidate kind) (not red?)) (get-in st [:candidates checkout]))
        st (if (and (= :candidate kind) (not red?)) (assoc-in st [:candidates checkout] {:tree d :candidate (:candidate r)}) st)
        ;; a red on the candidate its checkout holds marks it: when it is stopped its logs are kept, the evidence of that red or hang
        st (if (and red? (not (#{:build :seed :candidate} kind)) (get-in st [:candidates checkout]))
             (assoc-in st [:candidates checkout :red?] true)
             st)
        logs (fn [held] (cond-> (:candidate held) (:red? held) (assoc :keep-logs? true)))
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
    (if (and (#{:build :seed :candidate} kind) red?)
      ;; every item still queued that needs this build or seed ends with its error (a seed that needs a red build passes it on)
      (reduce (fn [[st outs] k2] (if (get-in st [:items k2]) (let [[st o] (item-ended st k2 r)] [st (into outs o)]) [st outs]))
              [st outs] (sort-by str (keep (fn [[k2 it]] (when (contains? (:needs it) k) k2)) (:items st))))
      (let [;; the candidate this ended item's tree holds: stopped once no item queued or running needs it (a run's last process
            ;; test ended), and made no more, so the tree's next run boots it again; one of an older tree is stopped as a new one is held
            held (get-in st [:candidates checkout])
            ck (when held [[:candidate (:tree held)] (:tree held)])
            idle? (and ck (not= :candidate kind) (not-any? #(contains? (:needs %) ck) (vals (:items st))))
            [st outs] (if (and old (not= (:tree old) d)) (let [[st m] (stop-candidate st (logs old))] [st (conj outs m)]) [st outs])]
        (if idle?
          (let [[st m] (stop-candidate (-> st (update :candidates dissoc checkout) (update-in [:made checkout :items] disj ck)) (logs held))]
            [st (conj outs m)])
          [st outs])))))

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
                         (assoc-in st [:items k] (assoc (select-keys m [:agent :ms :mode :kind :needs :tree :checkout :branch :binary? :bound :process? :shared?]) :status :queued :waiters #{rid} :at (:bid st 0)))))
                     (assoc-in st [:askers rid] {:left (set (keys items)) :prio prio :results {}
                                                 ;; the items that are its proofs: past a red it still waits for these (item-ended)
                                                 :proofs (into #{} (filter #(contains? proofs (first %))) (keys items))}) items)]
      (grant st))))))

(defn- verdicts-write
  "st and the output asking the api to write tx (a batch's :seon.proof tx-data) on the results branch
  (:seon.config.proofs/results-branch), the tx held under k until its answer: nothing when tx is empty. See
  docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :map :string [:maybe [:vector :map]]] [:tuple :map [:vector :map]]]}
  [st k tx]
  (if (seq tx)
    (let [[st m] (step/ask (assoc-in st [:verdict-writes k] tx) [:prove :answers] [:verdicts k]
                           {:op :write :req {:op :tx :branch (cfg/value :seon.config.proofs/results-branch) :tx-data tx}})]
      [st [m]])
    [st []]))

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
        ;; every item that ended with an error, a no-verdict's or the batch's whole answer's (its child died, its agent's answer
        ;; failed), is a :platform problem carrying it, so it is read where it is filed, never only in a run's answer, which the
        ;; judge reads as unrun; an agent that ended or a run superseded is no fault of the core
        no-verdicts (for [[_ r] results :when (and (contract/error? r) (not (#{:seon.system/ended :seon.prove/superseded} (:seon.error/type r))))]
                      {:op :problem :class :platform :error r})
        ;; the batch's verdicts (:seon.proof tx-data its agent made, seon.proofs/verdict-tx) written to the results branch by this
        ;; proc, one write a batch: asked of the api, answered on :answers as :verdicts (never a wait in this step)
        [st vw] (verdicts-write st (str bid) (when (map? r) (:verdict-tx r)))
        [st more] (grant st)]
    [st (-> outs (into no-verdicts) (into vw) (into more))]))

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
  ;; a report an item owns (a check step's, named "#'step", no test symbol) is that item's, never its batch's: kept as both, a red
  ;; step was reported twice and counted twice (seon.prove-test/a-red-step-is-named-red-and-never-remembered)
  (let [owned (into #{} (map #(str "#'" (key %))) results)]
    (into [] (comp (keep (fn [[_ r]] (when (map? (:answer r)) (:answer r)))) (distinct)
                   (mapcat (fn [a] (remove #(or (proofs/test-symbol (:var %)) (owned (:var %))) (:reports a)))))
          (sort-by (comp str key) results))))

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
                        ;; :no-verdict: what seon.proofs/results and failures read as no verdict, as a death's :exit
                        [{:type :error :var (str "#'" t) :message (:seon.error/message r) :seon.error/type (:seon.error/type r) :no-verdict true}]
                        (filterv #(= (str "#'" t) (:var %)) (:reports (:answer r)))))
        reports (-> (vec (:reused-reports base)) (into (mapcat (fn [[t r]] (own t r))) (sort-by (comp str key) results))
                    (into (batch-errors results)))
        ;; the reused reports' types, and each item's own counts, never its :answer's: an answer is its batch's, shared by every item
        ;; the batch ran and holding the other tests its child ran, so summed an item it counted a batch's failures n times and named
        ;; none of them (web: :fail 2 for 2 green named tests, result/c529a48e; seon.proofs-test/a-runs-counts-are-its-items-own-never-their-batchs)
        ;; and a batch's reports that name no test (a child that died: batch-errors, once a batch) counted once each, being no item's
        counts (merge-with + (frequencies (map :type (:reused-reports base)))
                           (frequencies (map :type (batch-errors results)))
                           (apply merge-with + {} (for [[_ r] results :when (not (contract/error? r))] (select-keys r [:pass :fail :error]))))]
    (cond-> {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :reports reports
             ;; an item answered an error has no verdict (its batch's error, a wall-time end): the platform failing, held typed for
             ;; its asker, never a red (docs/principles.md#6-nothing-changed-nothing-runs-a-change-costs-what-it-touched)
             ;; a plain map: an answer crosses the wire, which encodes no sorted collection of a comparator of its own
             :no-verdict (into {} (for [[t r] results :when (contract/error? r)] [t (select-keys r [:seon.error/type :seon.error/message])]))
             :digests (into (or (:digests base) {}) (for [[_ r] results :when (map? (:answer r)) [t d] (:digests (:answer r))] [t d]))
             :fns (into {} (for [[_ r] results :when (map? (:answer r)) [t f] (:fns (:answer r))] [t f]))
             :measured (into {} (for [[_ r] results :when (map? (:answer r)) [t m] (:measured (:answer r))] [t m]))
             :selected (vec (:selected base)) :deferred (vec (:deferred base)) :at-judge (:at-judge base 0)
             :reused (count (filter :reused (:reused-reports base))) :ran (count results)
             :red (vec (sort-by str (distinct (concat (keys (proofs/failures {:reports (vec (:reused-reports base))}))
                                                      (keep (fn [[t r]] (when (and (not (contract/error? r)) (not (proofs/green? r))) t)) results)))))}
      (seq (:why base)) (assoc :why (:why base))
      ;; each deferred test's digest at the base, as the agent's plan gave it: what the merge check reads main's verdict at
      (seq (:deferred-digests base)) (assoc :deferred-digests (:deferred-digests base))
      (seq (:missing base)) (assoc :missing (:missing base))
      ;; a verdict the cache would not take, named as its run named it
      (some (comp :unstored :answer) (vals results)) (assoc :unstored (vec (mapcat (comp :unstored :answer) (vals results)))))))

(defn- runner-done
  "outputs with the requests that end the prove agents of the tests branch (main's runs' runners, :runners {run-key agent}) whose
  run has ended and that no item of the queue is still the agent of, each asked once (:killing): a runner that still held an
  item at its own run's end is ended at a later finish, never left holding the branch. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (reduce (fn [[st outs] [k a]]
            (if (and (nil? (get-in st [:runs k])) (not (contains? (:killing st) k)) (not-any? #(= a (:agent %)) (vals (:items st))))
              (let [[st m] (step/ask (update st :killing (fnil conj #{}) k) [:prove :answers] [:killed k] {:op :kill :id a})] [st (conj outs m)])
              [st outs]))
          [st []] (sort-by key (:runners st))))

(defn- tests-branch-busy?
  "Whether a prove agent of the tests branch is in flight, whatever its run's slot: a main run spawning one, or a runner not yet
  ended (:runners). A second spawn on the branch then is refused (seon.system/spawn!: branch held), so an agentless main ask waits
  (:held-main) instead. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [st]
  (boolean (or (seq (:runners st)) (some #(and (nil? (:agent %)) (= :spawn (:stage %))) (vals (:runs st))))))

(defn- finish
  "Run k ended with answer r: every waiter answered once. A run answered green with nothing died is remembered under its
  slot (a key asked again gets that answer, nothing runs); an error, a death or a red is not, so asking again runs it
  again. An error is a :platform problem too, but :seon.system/ended (the agent it asked ended: a cancel, its run's end), the owner
  of its error's operation named on it. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :string :any] [:tuple :map [:vector :map]]]}
  [st k r]
  (let [{:keys [waiters] :as run} (get-in st [:runs k])
        err? (contract/error? r)
        ended? (#{:seon.system/ended :seon.prove/superseded} (:seon.error/type r))
        ;; a run's items name their own reds (run-answer: a step's among them); any other answer, by its failing reports
        r (if err? r (assoc r :key k :red (vec (sort-by str (distinct (concat (:red r) (keys (proofs/failures r))))))))
        ;; asked again, it ran nothing for that asker: each verdict reads as reused at its digest, with no measure to add
        st (remember (-> st (update :runs dissoc k) (assoc :last (:branch run))) run r)
        [st kill] (runner-done st)
        owner (some-> (:seon.error/operation r) namespace symbol)]
    [st (cond-> (into (reply [] waiters r) kill)
          (and err? (not ended?))
          (conj {:op :problem :class :platform
                 :error (cond-> r owner (assoc :seon.problems/owner owner))}))]))

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
  (let [{:keys [agent mode names checkout digest process?]} (get-in st [:runs k])
        ;; its key and digest: the agent holds this plan under its key, beside another run's (seon.agent/hold-plan)
        [st m] (step/ask st [:prove :answers] [:plan k] (cond-> {:op :tests :id agent :mode mode :plan? true :key k :digest digest} names (assoc :names names)
                                                          ;; process tests are items of a session's checkout run and of a merge's judge (process?): an
                                                          ;; eval's prove names them unrun (the owner's one prove of a change)
                                                          (and (not= :turn mode) (or checkout process?)) (assoc :process? true)))]
    [(assoc-in st [:runs k :stage] :plan) [m]]))

(defn- verdicts-request
  "Request m of the api carrying a run's verdicts as data ({:rid :verdicts}: the :seon.proof entities seon.proofs/verdict-tx
  makes): written by this proc's one per-batch write (verdicts-write), the asker answered {:written n} once they are
  (answered :verdicts). Only :seon.proof attributes: this proc is the one writer of the verdict shape, never a generic write
  of a client's tx; each entity is checked against its schema by the write. Answers [st outputs]. See
  docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st {:keys [rid verdicts]}]
  (let [vs (vec verdicts) rids (if rid #{rid} #{})]
    (if (every? (fn [v] (and (map? v) (every? #(= "seon.proof" (namespace %)) (keys v)))) vs)
      (let [k (str "v" rid) [st o] (verdicts-write (cond-> st rid (assoc-in [:verdict-askers k] rid)) k vs)]
        (if (seq o) [st o] [(update st :verdict-askers dissoc k) (reply [] rids {:written 0})]))
      [st (reply [] rids (contract/error :seon.prove/not-verdicts #'seon.prove/verdicts-request "a :verdicts request carries only :seon.proof entities"
                                         {:keys (into #{} (comp (filter map?) (mapcat keys) (remove #(= "seon.proof" (namespace %)))) vs)}))])))

(defn- request
  "Admit request m ({:rid :branch :t :digest :agent :names :mode}) of the api, keyed by its :digest (run-key); one with no
  :rid (main's commit notice) is a run nobody waits on. A run in flight for the same slot at another digest is superseded first:
  its waiters answered :seon.prove/superseded, its items no one else waits on dropped. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st {:keys [rid branch] :as m}]
  (if (contains? m :verdicts)
    (verdicts-request st m)
  (let [rids (if rid #{rid} #{})
        ;; a request naming no t is of main's head now, one read of the connection the proc holds
        t (or (:t m) (some-> (:conn st) deref :max-tx))
        names (not-empty (:names m))
        mode (or (:mode m) :judge)
        key (run-key branch (:digest m) names mode (boolean (:process? m)))
        req (-> m (select-keys [:branch :digest :agent :proofs :checkout :tree :process?]) (assoc :key key :t t :names names :mode mode))
        done (get-in st [:done (slot req)])]
    (cond
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
      ;; the tests branch's runner is in flight (another slot's main run): this ask waits for it, held in order and asked again
      ;; when the branch is free (re-enter-held), never a second spawn the branch refuses; a held notice (no :rid) keeps only the newest
      (tests-branch-busy? st) [(update st :held-main (fn [h] (conj (if rid (vec h) (filterv :rid h)) m))) []]
      ;; main: a prove agent on the tests branch, forked from the fork server's main, plans it
      :else (let [st (assoc-in st [:runs key] (assoc req :waiters rids :stage :spawn))
                  [st m] (step/ask st [:prove :answers] [:spawn key] {:op :spawn :opts {:branch :tests}})]
              [st [m]])))))

(defn- re-enter-held
  "The held main asks (:held-main, held while the tests branch was busy) asked again, in order, through request's own rule, once
  the branch is free: the first spawns its runner and the rest are held again behind it. Answers [st outputs]. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (if (or (empty? (:held-main st)) (tests-branch-busy? st))
    [st []]
    (reduce (fn [[st outs] m] (let [[st o] (request st m)] [st (into outs o)]))
            [(dissoc st :held-main) []] (:held-main st))))

(defn- branch-items
  "The items of plan r (seon.agent/process-plan's) for a run of an agent's branch, which names no checkout: its process tests, each
  needing the branch's one candidate, [[:candidate commit] commit], the standing binary booted on a clone of the standing store with
  the branch as its main (it loads the branch's forms; no tree, build or seed). A branch that moved a build input (:input-moved?)
  needs a build of its own, which waits on its files written to a tree (task seon.writer/a-branchs-files-are-written-out-to-a-tree):
  none, its process tests left named and unrun. See task seon.prove/tests-fork-from-the-branchs-warm-agent."
  {:malli/schema [:=> [:cat :map :map] [:map-of :any :map]]}
  [run r]
  (if (or (empty? (:process r)) (get-in r [:build :input-moved?]) (not (:branch r)))
    {}
    (let [c (:commit r)
          ck [[:candidate c] c]
          on {:branch (:branch r)}]
      (into {ck (assoc on :kind :candidate)}
            (for [[t d] (:process r)]
              [[t d] (assoc on :kind :test :process? true :shared? (contains? (:shared r) t) :agent (:agent run) :mode (:mode run) :ms (get-in r [:ms t]) :needs #{ck})])))))

(defn- plan-items
  "The items of plan r ({:process {test digest} :steps {step {:digest :binary? :bound}} :build {:tree :needed?}}, seon.agent's
  answer to a :process? plan) that root's effect owner runs on run's checkout (main's, the checkout this root runs from, for
  a run that names none) at its tree (the run's, else the plan's): the build, [[:build tree] tree], once a tree when it is
  needed; the seed, [[:seed tree] tree], needing the build when there is one, which brings the checkout's program store to the
  tree (and, :binary?, seeds the check store and the process tests' store); each process test and each step, needing the seed. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :map] [:map-of :any :map]]}
  [run r]
  (if-not (:checkout run)
    (branch-items run r)
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
        sk [[:seed tree (if stores? :stores :tree)] tree]
        ;; the one booted candidate of the tree (seon.test-store/boot-candidate!: the tree's build on a clone of the process tests'
        ;; store), booted once and held in this proc's state (:candidates), which every process test of the tree runs on, never a
        ;; root of its own (task seon.prove/tests-fork-from-the-branchs-warm-agent)
        ck (when (seq (:process r)) [[:candidate tree] tree])]
    (cond-> (into {}
                  ;; a process test runs as any test does, in a fork of the agent holding the run's branch (a batch of them shares
                  ;; the one test root the first starts, ~20 s, then ~0.5 s a test), never a cold bin/test process (~10 s more each);
                  ;; it needs the tree's seed: the binary and the process tests' store
                  (concat (for [[t d] (:process r)]
                            [[t d] (cond-> (assoc on :kind :test :process? true :shared? (contains? (:shared r) t) :agent (:agent run) :mode (:mode run) :ms (get-in r [:ms t])) seed? (assoc :needs (cond-> #{sk} ck (conj ck))))])
                          (for [[s {:keys [digest binary? bound]}] (:steps r)]
                            [[s digest] (cond-> (assoc on :kind :step :binary? (boolean binary?) :bound bound)
                                          seed? (assoc :needs #{sk}))])))
      build? (assoc bk (assoc on :kind :build))
      ck (assoc ck (assoc on :kind :candidate :needs #{sk}))
      seed? (assoc sk (cond-> (assoc on :kind :seed :binary? stores?) build? (assoc :needs #{bk})))))))

(defn flaky-problems
  "[st problems]: a :platform problem for each flaky reused report of plan r (seon.proofs/flaky: green and red at one digest),
  once a digest (st's :flaky): its test, its owner (the namespace whose task file holds its work), digest, the red run's failures
  and both runs' ticks, saying when they are over ten times
  apart (the runs did different work for one input). A flaky digest is no verdict, so it holds no landing; this is where it is
  seen. See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st r]
  (reduce (fn [[st ps] {:keys [var] :as f}]
            (let [{:keys [green red] :as ticks} (:seon.proofs/ticks f)
                  t (some-> var (subs 2) symbol)
                  dg (or (:digest f) (get (:digests r) t))
                  ;; its owner: the namespace whose task file holds the test's work (app.x-test's is app.x), as an :unsynced row names it
                  owner (some-> t namespace (str/replace #"-test$" "") symbol)]
              (if (and (:flaky f) t dg (not (contains? (:flaky st) dg)))
                (let [apart? (and green red (pos? (min green red)) (> (max green red) (* 10 (min green red))))]
                  [(update st :flaky (fnil conj #{}) dg)
                   (conj ps {:op :problem :class :platform
                             :error (contract/error :seon.proofs/flaky #'seon.prove/flaky-problems
                                                    (str t " was green and red at digest " dg ", for " owner " to fix at its cause"
                                                         (if apart? (str ", with ticks " green " and " red " over ten times apart: its runs did different work for one input")
                                                             ": its result reads something its digest misses")
                                                         " (load, a cold fixture, a shared file); no verdict, never a red that holds a landing")
                                                    (cond-> {:test t :digest dg :owner owner :red-failures (vec (:seon.proofs/red-failures f))}
                                                      ticks (assoc :ticks ticks)))})])
                [st ps])))
          [st []] (:reused-reports r)))

(defn- answered
  "The api's answer r to the request of chain step: a prove agent spawned (main's run: it plans), a run's plan (its items
  join the queue), a batch's answer (batch-ended), a runner killed. An answer to a run no longer in flight is counted
  (:stale). Answers [st outputs]."
  {:malli/schema [:=> [:cat :map [:tuple :keyword :string] :any] [:tuple :map [:vector :map]]]}
  [st [step key] r]
  (case step
    ;; a candidate stopped: nobody waits on it
    :stop-candidate [st []]
    :killed (re-enter-held (-> st (update :killing disj key) (update :runners dissoc key)))
    ;; a batch's verdicts written (verdicts-write): done; the results branch missing, the writer makes it from main
    ;; (:clone-branch) and the same write is asked again; any other refusal, a :platform problem (the verdicts run again)
    :verdicts (let [tx (get-in st [:verdict-writes key]) st (update st :verdict-writes dissoc key)
                    rb (cfg/value :seon.config.proofs/results-branch)
                    asker (get-in st [:verdict-askers key])
                    st (cond-> st asker (update :verdict-askers dissoc key))]
                (cond (not (contract/error? r)) [st (if asker (reply [] [asker] {:written (count tx)}) [])]
                      ;; the writer's refusal of a :tx on a branch the store lacks [M]: "Database does not exist."
                      (and tx (= :seon.writer/refused (:seon.error/type r)) (str/includes? (str (:seon.error/message r)) "does not exist"))
                      (let [[st m] (step/ask st [:prove :answers] [:results-branch key] {:op :write :req {:op :clone-branch :from :db :branch rb}})]
                        [(cond-> (assoc-in st [:verdict-writes key] tx) asker (assoc-in [:verdict-askers key] asker)) [m]])
                      :else [st (cond-> [{:op :problem :class :platform :error r}] asker (reply [asker] r))]))
    :results-branch (let [[st o] (verdicts-write st key (get-in st [:verdict-writes key]))] [st o])
    ;; a batch's end can be a runner's last item, its run finished before it: the runner ends then (runner-done), and the held
    ;; asks re-enter once the branch is free, never left waiting on a runner no finish will sweep
    :batch (let [[st outs] (batch-ended st (parse-long key) r) [st o] (settle st outs) [st k] (runner-done st) [st h] (re-enter-held st)]
             [st (-> o (into k) (into h))])
    (let [run (get-in st [:runs key])]
      (cond
        (nil? run) [(update st :stale (fnil inc 0)) []]
        ;; a run that died at its spawn holds no runner: the held asks are asked again
        (contract/error? r) (let [[st o] (finish st key r) [st o2] (re-enter-held st)] [st (into o o2)])
        (= :spawn step) (plan! (-> st (update-in [:runs key] assoc :runner (:id r) :agent (:id r)) (assoc-in [:runners key] (:id r))) key)
        (= :plan step) (let [st (assoc-in st [:runs key :base] (dissoc r :items :ms :process :steps :build))
                             prio (if (= :turn (:mode run)) :turn :batch)
                             items (into (plan-items run r) (for [[t d] (:items r)] [[t d] (cond-> {:agent (:agent run) :mode (:mode run) :kind :test} (get-in r [:ms t]) (assoc :ms (get-in r [:ms t]))
                                                                                     (:checkout run) (assoc :checkout (:checkout run)))]))
                             ;; past a red, a turn waits for its own proofs; any other run (a tree's :judge, main's) is a verdict of the
                             ;; whole and waits for every item, so a session and a landing hear every red, never only the first
                             proofs (if (= :turn (:mode run)) (set (:proofs run)) (into #{} (map first) (keys items)))
                             [st apart] (flaky-problems st r)
                             [st outs] (ask-items (assoc-in st [:runs key :stage] :items) key prio items proofs)]
                         (settle st (into outs apart)))
        :else [(update st :stale (fnil inc 0)) []]))))

(defn- state-view
  "What the service holds, as data: the runs in flight (each key's stage and waiters), the queue (items queued and running),
  and the api's asks it has open. A hang in the chain is read from it (seon.system's stall task records it)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  {:runs (into (sorted-map) (for [[k r] (:runs st)] [k {:stage (:stage r) :waiting (count (:waiters r))}]))
   :queued (count (filter #(= :queued (:status %)) (vals (:items st)))) :running (count (filter #(= :running (:status %)) (vals (:items st))))
   :open (vec (sort-by str (vals (:pending st))))})

(defn prove-proc
  "The test service (see the namespace doc). In :req (requests the api forwards, {:rid :branch :t :digest :agent :names
  :mode}) and :answers (the api's answers to this proc's requests); out :to-api (its
  requests; {:op :prove-answer :rid :r} for each waiter, one message each; {:op :problem} for a failed run). Its workers,
  the items running at once on the machine, are admitted by root's :process effect owner (seon.fx/admits?). Buffers (system.edn): :req and
  :answers are lossless and bounded (a full one parks the sender: the api)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "main's connection: the head a request naming no t is of"} :ins {:req "requests to prove a branch at a tx" :answers "the api's answers"}
       :outs {:to-api "requests, and the answers to the waiters"}
       :workload :io
       ;; the queue's counts and each run's stage are state-view's, the one reading of them: a slow run shows its items running
       :ping-map-fn (fn [st] {:runs (count (:runs st))
                              :queued (count (filter #(= :queued (:status %)) (vals (:items st))))
                              :running (count (filter #(= :running (:status %)) (vals (:items st))))
                              :run-stages (:runs (state-view st))
                              :stale-answers (:stale st 0)
                              ;; what the tests branch is held by and what waits on it
                              :runs-in-flight (vec (sort (keys (:runs st)))) :runners (:runners st) :held-main (count (:held-main st))})})
  ([args] {:rid 0 :pending {} :runs {} :done {} :items {} :askers {} :batches {} :conn (:conn args)
           ;; the running binary's built identity (seon.program/binary-program, what seon.fx/runtime-for compares), held for the proc's life
           :binary (some-> (program/binary-program) :digest)
           })
  ([st _tr] st)
  ([st0 in m]
   (let [st st0
         [st outs] (case in
                     :req (request st m)
                     :answers (let [[st v] (step/answered st (:tag m))] (if v (answered st v (:r m)) [(update st :stale (fnil inc 0)) []])))]
     (let [outs (vec outs)
           ;; credit (docs/flow.md, request and answer): its asks are answered on :answers, which holds reconciler-buf; with that many
           ;; open it reads only :answers, so the api never parks putting an answer while this parks putting an ask
           st (assoc st ::flow/input-filter (if (>= (count (:pending st)) (cfg/value :seon.config.root/reconciler-buf)) #{:answers} identity))]
       [st (if (seq outs) {:to-api outs} {})]))))
