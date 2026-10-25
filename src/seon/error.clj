(ns seon.error
  "Recorded errors (session D2, ported from session R4's seon.error): every error
  an observer sees, folded by its cause in the observer's own state and written once per
  window as one entity per signature on main, with its count, the agents it hit, when, and
  the first occurrence's error map.

  - An observer (root's api proc: Flow's error channel, an agent's death or stall, an
    effect's error outcome, the eval errors an agent reports with its commit) builds an
    `occurrence` and `fold`s it: its cause's entry only, so an occurrence costs a hash.
  - `flush-tx` writes the signatures that changed since the last write; the observer keeps
    one such write in flight, so a storm of identical errors costs one update per window.
  - An agent folds its own eval errors (`note`) and sends what it folded with its next
    commit; root folds those as occurrences of class :eval.
  Nothing here files tasks or resolves errors: they are mined by hand (root, owner)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :refer [defn defn-]] [datahike.api :as d]))

;; ---- the signature ----------------------------------------------------------------------

(defn digits->n
  "s with every run of digits replaced by N (ids, counts and line numbers differ between
  occurrences of one cause): the one normalisation for error signatures (R4)."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (let [sb (StringBuilder.)]
    (loop [i 0 in-digits? false]
      (if (= i (count s)) (str sb)
          (let [c (.charAt ^String s i) d? (Character/isDigit c)]
            (when-not (and d? in-digits?) (.append sb (if d? \N c)))
            (recur (inc i) d?))))))

(defn digest
  "The first 16 hex digits of the SHA-256 of s: a content identity."
  {:malli/schema [:=> [:cat :string] [:string {:min 16 :max 16}]]}
  [s]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String s))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 b)))))

(defn cause
  "The cause of error map e, as a value: its variant, operation, member, the paths and
  expected schemas of its Malli problems, a foreign exception's class, an exit status or
  HTTP status (they name different causes), and its message with digits normalised.
  Values never enter it: one cause, one value."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [e]
  [(:seon.error/type e) (:seon.error/operation e) (:seon.error/member e)
   (mapv (juxt :path :expected) (:seon.error/problems e))
   (:class e) (or (:exit e) (:status e))
   (some-> (:seon.error/message e) digits->n)])

(defn signature
  "The signature of error map e: the digest of its cause."
  {:malli/schema [:=> [:cat :map] :seon.error/signature]}
  [e] (digest (sedn/encode (cause e))))

(defn occurrence
  "n occurrences (1 unless an agent folded them) of error e of class, seen first at
  first-ms and last at ms, with the agent, task and form they happened in when known."
  {:malli/schema [:=> [:cat :map :seon.error/class [:map [:agent {:optional true} [:maybe :keyword]] [:task {:optional true} [:maybe :string]]
                                                    [:form {:optional true} [:maybe :string]] [:n {:optional true} pos-int?]
                                                    [:first-ms {:optional true} :int]] :int]
                  :seon.error/occurrence]}
  [e class {:keys [agent task form n first-ms]} ms]
  {:error e :class class :cause (cause e) :agent agent :task task :form form :n (or n 1) :first-ms (or first-ms ms) :ms ms})

;; ---- the fold in the observer's state ----------------------------------------------------

(defn fold-entry
  "Signature entry r (nil the first time) after occurrence o: count, agents, last time,
  last agent and task; the first example and form kept. A signature already written (it
  has a :base) only counts: :pending? says the count is not yet on main, :dirty? (a fact
  to write) is set only by a signature not yet written."
  {:malli/schema [:=> [:cat [:maybe :seon.error/entry] :seon.error/occurrence] :seon.error/entry]}
  [r {:keys [cause error class agent task form n first-ms ms]}]
  (if r
    (cond-> (-> r (update :count + n) (assoc :last-ms (max ms (:last-ms r))) (assoc (if (:base r) :pending? :dirty?) true))
      agent (-> (update :agents conj agent) (assoc :last-agent agent))
      task (assoc :last-task task))
    {:signature (digest (sedn/encode cause)) :class class :count n :agents (if agent #{agent} #{}) :first-ms first-ms :last-ms ms
     :example error :form form :last-agent agent :last-task task :dirty? true}))

(defn fold
  "Fold occurrence o into f (cause -> entry): its cause's entry only, so an occurrence
  costs O(1) whatever f holds (the contract checks the entry, not the fold)."
  {:malli/schema [:=> [:cat :map :seon.error/occurrence] :map]}
  [f o] (update f (:cause o) fold-entry o))

(defn dirty?
  "Has f a signature to write (one not yet written)?"
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f] (boolean (some :dirty? (vals f))))

(defn record
  "The recorded error with signature s on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :seon.error/signature] [:maybe :map]]}
  [db s]
  (some-> (d/pull db '[*] [:seon.error/signature s]) (dissoc :db/id)))

(defn flush-tx
  "The window's write: one entity per signature not yet written by this observer, its count
  absolute (the count stored when this observer first wrote it, read once, plus everything
  it folded since). A signature already written is not written again for a repeat: its
  count lives in the fold, and is made durable by the next write that has a signature to
  write anyway. A signature's first record carries its type, operation, message, class and
  first example. db nil (no main to read): counts start at 0. Answers [f' tx]."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db]] [:tuple :map [:vector :map]]]}
  [f db]
  (let [write? (dirty? f)
        flushed (for [[c {:keys [signature class agents first-ms last-ms example form base last-agent last-task] folded :count :as r}] f
                      :when (or (:dirty? r) (and write? (:pending? r)))
                      :let [stored (when (and db (not base)) (record db signature))
                            first? (and (nil? base) (nil? stored))
                            base (or base (:seon.error/count stored 0))
                            n-agents (max (count agents) (:seon.error/agents stored 0))]]
                  [[c (assoc r :dirty? false :pending? false :base base)]
                   (cond-> {:seon.error/signature signature :seon.error/class class :seon.error/count (+ base folded)
                            :seon.error/agents n-agents :seon.error/last-ms last-ms
                            :seon.error/first-ms (or (:seon.error/first-ms stored) first-ms)}
                     last-agent (assoc :seon.error/last-agent last-agent)
                     last-task (assoc :seon.error/last-task last-task)
                     ;; on every write, so a signature stored before its producer named a subject gains it
                     (:seon.error/subject example) (assoc :seon.error/subject (:seon.error/subject example))
                     first?
                     (merge (select-keys example [:seon.error/type :seon.error/operation :seon.error/message])
                            {:seon.error/example example}
                            (when form {:seon.error/form form})))])]
    [(into f (map first) flushed) (mapv second flushed)]))

;; ---- an agent's side: its eval errors, folded until its next commit --------------------

(defn note
  "Errors folded in an agent (cause -> sent entry) after one more error e of class (an eval's
  :eval; a model call's outcome, a short cache hit, :effect) in form at ms: what the agent sends
  root with its next commit (then starts empty)."
  {:malli/schema [:=> [:cat [:maybe :map] :map :seon.error/class [:maybe :string] :int] :map]}
  [m e class form ms]
  (update m (cause e) (fn [s] (if s (-> s (update :n inc) (assoc :last-ms ms))
                                  {:error e :class class :form form :n 1 :first-ms ms :last-ms ms}))))

(defn sent->occurrence
  "An agent's sent entry s (its errors since its last commit, each of the class it was noted as) as
  root's occurrence."
  {:malli/schema [:=> [:cat :seon.error/sent :keyword [:maybe :string]] :seon.error/occurrence]}
  [s agent task]
  (occurrence (:error s) (:class s) {:agent agent :task task :form (:form s) :n (:n s) :first-ms (:first-ms s)} (:last-ms s)))
