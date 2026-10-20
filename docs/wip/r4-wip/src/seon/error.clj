(ns seon.error
  "Recorded errors (lane R4): Seon's own failures, one entity per signature on main with
  a count, the task that answers it and its resolution.

  - The entrant boundary (root's reader of Flow's error channel; a contract refusal raised
    inside platform code) builds an occurrence; the api proc folds occurrences in its
    state (`fold`) and writes only the signatures that changed, once per flush window
    (`flush-tx`): a storm of identical errors costs one datom update per window.
  - The reconciler (`actions`) files a task for an open error that recurred enough
    (`:file`) and resolves an error whose producer was deleted, or changed with its task's
    proofs green (`:resolve`). A recurrence after resolution reopens it (the fold retracts
    the resolution and counts a generation); the next `:file` files generation g.
  - The filed task carries the evidence as data: signature, count, the printed error, the
    failing form, the producer's definition and its callers (K1's reference facts).
  An agent's own mistakes are delivered to it, never recorded here; the same agent-error
  signature across many agents is a template's defect (seon.learn)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str]
            [seon.learn :as learn] [seon.task :as task]))

;; ---- the signature ----------------------------------------------------------------------

(defn cause
  "The cause of error map e, as a value: its variant, operation, member, the paths and
  expected schemas of its Malli problems, the class of a foreign exception and its message
  with digits normalised. Values never enter it (one cause, one value); the fold keys by
  it, so an occurrence costs a hash, not a digest."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [e]
  [(:seon.error/type e) (:seon.error/operation e) (:seon.error/member e)
   (mapv (juxt :path :expected) (:seon.error/problems e))
   (:class e) (some-> (or (:seon.error/message e) (:message e)) learn/digits->n)])

(defn signature
  "The signature of error map e: the digest of its cause, computed once per cause when it
  is written."
  {:malli/schema [:=> [:cat :map] :seon.error/signature]}
  [e] (learn/digest (pr-str (cause e))))

(defn occurrence
  "One occurrence of error e, seen at the entrant boundary: its cause, its producer (the
  operation that raised it), the agent and form it happened in, when."
  {:malli/schema [:=> [:cat :map [:map [:agent {:optional true} [:maybe :keyword]] [:form {:optional true} [:maybe :string]]] :int]
                  :seon.error/occurrence]}
  [e {:keys [agent form]} ms]
  {:error e :cause (cause e)
   :producer (let [op (:seon.error/operation e)] (when (qualified-symbol? op) op))
   :agent agent :form form :ms ms})

;; ---- the fold in the recording proc's state ------------------------------------------------

(defn fold-entry
  "Signature entry r (nil the first time) after occurrence o: count, agents, last time;
  the first example, producer and form kept."
  {:malli/schema [:=> [:cat [:maybe :map] :seon.error/occurrence]
                  [:map [:signature :seon.error/signature] [:count pos-int?] [:agents [:set :keyword]] [:first-ms :int]
                   [:last-ms :int] [:example :map] [:producer [:maybe :qualified-symbol]] [:form [:maybe :string]] [:dirty? :boolean]]]}
  [r {:keys [cause error producer agent form ms]}]
  (if r
    (-> r (update :count inc) (assoc :last-ms ms :dirty? true) (cond-> agent (update :agents conj agent)))
    {:signature (learn/digest (pr-str cause)) :count 1 :agents (if agent #{agent} #{}) :first-ms ms :last-ms ms
     :example error :producer producer :form form :dirty? true}))

(defn fold
  "Fold occurrence o into f (the recording proc's :seon.error/fold, cause -> entry): its
  cause's entry only, so an occurrence costs O(1) whatever f holds; the contract checks
  the entry it changes (seon.error/fold-entry), not the whole fold."
  {:malli/schema [:=> [:cat :map :seon.error/occurrence] :map]}
  [f o] (update f (:cause o) fold-entry o))

(defn- printed
  "An error map as stored text: printed within a budget (a stored copy is the printed
  value, never the live one)."
  {:malli/schema [:=> [:cat :any] :string]}
  [x] (binding [*print-length* 16 *print-level* 4] (pr-str x)))

(defn record
  "The recorded error with signature s on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :seon.error/signature] [:maybe :seon.error/record]]}
  [db s]
  (when-let [e (task/eid db :seon.error/signature s)]
    (-> (d/pull db '[* {:seon.error/task [:seon.task/id]}] e)
        (dissoc :db/id)
        (update :seon.error/task #(some-> % :seon.task/id)))))

(defn producer-src
  "The recorded source of var sym on db (main's program facts), or nil."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :qualified-symbol]] [:maybe :string]]}
  [db sym]
  (when sym (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/src ?s]] db sym)))

(defn flush-tx
  "The window's write: one entity per signature that changed, its count absolute: the
  count stored when this boot first flushed it (its :base, read once, so a db value that
  lags this proc's own writes never undercounts) plus everything folded since. A
  signature's first record carries the first occurrence's example, form and producer,
  with the producer's source on main. A signature recorded as resolved that occurs again
  is reopened: its resolution retracted, its generation counted, its producer's source
  re-read. Answers [f' tx]."
  {:malli/schema [:=> [:cat :seon.error/fold :seon.host/db] [:tuple :seon.error/fold [:vector :any]]]}
  [f db]
  (let [dirty (filter (comp :dirty? val) f)
        flushed (for [[c {:keys [signature agents first-ms last-ms example producer form base] folded :count :as r}] dirty
                      :let [s signature]
                      :let [stored (record db s)
                            base (or base (:seon.error/count stored 0))
                            reopened? (and stored (:seon.error/resolved-ms stored))]]
                  [[c (assoc r :dirty? false :base base)]
                   (concat
                     (when reopened? [[:db/retract [:seon.error/signature s] :seon.error/resolved-ms (:seon.error/resolved-ms stored)]])
                     [(cond-> {:seon.error/signature s :seon.error/count (+ base folded) :seon.error/last-ms last-ms
                               :seon.error/agents (max (count agents) (:seon.error/agents stored 0))}
                        (nil? stored)
                        (merge (select-keys example [:seon.error/type :seon.error/operation :seon.error/message])
                               {:seon.error/first-ms first-ms :seon.error/example (printed example) :seon.error/generation 0}
                               (when producer {:seon.error/producer producer})
                               (when-let [src (producer-src db producer)] {:seon.error/producer-src src})
                               (when form {:seon.error/form form}))
                        reopened?
                        (merge {:seon.error/generation (inc (:seon.error/generation stored 0))}
                               (when-let [src (producer-src db producer)] {:seon.error/producer-src src})))])])]
    [(into f (map first) flushed) (vec (mapcat second flushed))]))

;; ---- the reconciler's part: file, resolve -----------------------------------------------------

(def dials
  "Recorded-error dials when the config sets none: a task is filed once a signature
  occurred 3 times or in 2 agents; a signature is filed at most 3 generations."
  {:min-count 3 :min-agents 2 :max-generation 3})

(defn- open-records
  "Every recorded error on db that is not resolved, with what the reconciler decides on:
  one query for all of them (not a pull per record), so a pass costs O(open) rows."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (let [rows (d/q '[:find ?s ?c ?g ?a ?p ?psrc
                    :keys :seon.error/signature :seon.error/count :seon.error/generation :seon.error/agents
                          :seon.error/producer :seon.error/producer-src
                    :where [?e :seon.error/signature ?s] (not [?e :seon.error/resolved-ms _])
                    [?e :seon.error/count ?c]
                    [(get-else $ ?e :seon.error/generation 0) ?g]
                    [(get-else $ ?e :seon.error/agents 0) ?a]
                    [(get-else $ ?e :seon.error/producer :none) ?p]
                    [(get-else $ ?e :seon.error/producer-src :none) ?psrc]]
                  db)
        tasks (into {} (d/q '[:find ?s ?tid :where [?e :seon.error/signature ?s] [?e :seon.error/task ?t] [?t :seon.task/id ?tid]] db))]
    (vec (for [r rows]
           (cond-> (into {} (remove (comp #{:none} val)) r)
             (tasks (:seon.error/signature r)) (assoc :seon.error/task (tasks (:seon.error/signature r))))))))

(defn- program-srcs
  "var -> recorded source on db, for the producers ps (one query)."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-symbol]] [:map-of :qualified-symbol :string]]}
  [db ps]
  (if (empty? ps) {}
      (into {} (d/q '[:find ?v ?s :in $ [?v ...] :where [?e :var/name ?v] [?e :var/src ?s]] db (vec ps)))))

(defn task-id
  "The id of the task answering signature s at generation g."
  {:malli/schema [:=> [:cat :seon.error/signature nat-int?] :seon.task/id]}
  [s g] (task/task-id 'seon.defect (str s (when (pos? g) (str "-r" g)))))

(defn- task-green?
  "Is task id done with at least one proof, and every proof passing in the attempt that
  made it done?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id]
  (let [t (get-in v [:tasks id])
        proofs (get-in t [:seon.task/accumulated :proofs])
        done (filter #(#{:merged :done} (:seon.attempt/status %)) (get-in v [:attempts id]))]
    (boolean (and (seq proofs) (seq done)
                  (some (fn [a] (every? #(= :pass (get (:seon.attempt/results a) %)) proofs)) done)))))

(defn resolution
  "Why recorded error r is resolved (its producer's source on main now: src, nil when
  the producer has no definition), or nil: its producer was deleted; or its producer's
  source differs from the one recorded and the task answering it is done with its proofs
  green (view v)."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] [:maybe [:enum :producer-deleted :producer-changed-green]]]}
  [v r src]
  (when (:seon.error/producer r)
    (cond (nil? src) :producer-deleted
          (and (:seon.error/producer-src r) (not= src (:seon.error/producer-src r))
               (:seon.error/task r) (task-green? v (:seon.error/task r)))
          :producer-changed-green)))

(defn actions
  "The reconciler's actions over recorded errors on main db (its view v): resolve what is
  resolved; file a task for an open error that occurred min-count times or in min-agents
  agents and has no task of its generation. Two queries, then O(open) in memory."
  {:malli/schema [:=> [:cat :seon.host/db :map :seon.error/dials] [:vector :seon.error/action]]}
  [db v {:keys [min-count min-agents max-generation]}]
  (let [open (open-records db)
        srcs (program-srcs db (into #{} (keep :seon.error/producer) open))]
    (vec (for [r open
               :let [s (:seon.error/signature r) g (:seon.error/generation r 0)
                     why (resolution v r (get srcs (:seon.error/producer r)))
                     tid (task-id s g)]
               a [(cond
                    why {:op :resolve :signature s :why why}
                    (and (nil? (get-in v [:tasks tid]))
                         (<= g max-generation)
                         (or (>= (:seon.error/count r) min-count) (>= (:seon.error/agents r 0) min-agents)))
                    {:op :file :signature s})]
               :when a]
           a))))

(defn callers
  "The program definitions on db that reference var sym (K1's reference facts on main)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :qualified-symbol]] [:vector :qualified-symbol]]}
  [db sym]
  (if sym (vec (sort-by str (d/q '[:find [?c ...] :in $ ?p :where [?e :var/refs ?p] [?e :var/name ?c]] db sym))) []))

(defn evidence
  "The evidence a filed task starts from: the signature, its count, the printed error and
  failing form, the producer, its definition on main and its callers."
  {:malli/schema [:=> [:cat :seon.host/db :seon.error/record] :map]}
  [db r]
  (let [p (:seon.error/producer r)]
    {:kind :error :signature (:seon.error/signature r) :count (:seon.error/count r)
     :example (:seon.error/example r) :form (:seon.error/form r)
     :producer p :producer-src (producer-src db p) :callers (callers db p)}))

(defn file-decls
  "The task declarations filed for recorded error r (generation g), on one branch: a
  prove step (kind :design: it writes a deftest reproducing the signature and adds it to
  the proofs, which may stay red), the fix (it needs the prove step, so it inherits the
  plan and the proof) and its merge, each from its template."
  {:malli/schema [:=> [:cat :seon.host/db :seon.error/record [:map-of :keyword :seon.template/template]] [:vector :map]]}
  [db r templates]
  (let [s (:seon.error/signature r) g (:seon.error/generation r 0)
        id (task-id s g) nm (subs id (inc (str/index-of id "/")))
        branch (task/default-branch id)
        ev (evidence db r)
        about (str "error " s)
        plan (str "Seon's own error " s " occurred " (:seon.error/count r) " times"
                  (when (pos? (:seon.error/agents r 0)) (str " in " (:seon.error/agents r) " agents"))
                  ": " (or (:seon.error/message r) (:seon.error/example r))
                  (when-let [p (:seon.error/producer r)]
                    (str ".\nIts producer is `" p "`"
                         (when (seq (:callers ev)) (str "; its callers are " (str/join ", " (map #(str "`" % "`") (:callers ev)))))))
                  ".\nThe printed error, the failing form and the producer's definition are in the evidence.")
        decl (fn [suffix plan needs t]
               (learn/stamp (cond-> {:seon.task/ns 'seon.defect :seon.task/name (str nm suffix) :seon.task/plan plan
                                     :seon.task/branch branch :seon.task/about about}
                              needs (assoc :seon.task/needs #{(str id needs)})
                              (nil? needs) (assoc :seon.task/evidence ev))
                            t))]
    [(decl "-prove" plan nil (:defect-prove templates))
     (decl "" "" "-prove" (:defect-fix templates))
     (decl "-merge" "" "" (:merge templates))]))

;; ---- the view ---------------------------------------------------------------------------------

(defn records-html
  "The errors view: one row per recorded error, open first, by count."
  {:malli/schema [:=> [:cat [:vector :seon.error/record] [:map-of :seon.task/id :keyword]] [:map [:seon.render/html vector?]]]}
  [rs task-state]
  {:seon.render/html
   [:table.errors
    [:tr [:th "signature"] [:th "type"] [:th "producer"] [:th "count"] [:th "agents"] [:th "task"] [:th "state"]]
    (for [r (sort-by (juxt #(some? (:seon.error/resolved-ms %)) (comp - :seon.error/count)) rs)
          :let [t (:seon.error/task r)]]
      [:tr {:id (str "err-" (:seon.error/signature r)) :class (if (:seon.error/resolved-ms r) "resolved" "open")}
       [:td [:code (:seon.error/signature r)]] [:td (str (:seon.error/type r ""))] [:td (str (:seon.error/producer r ""))]
       [:td (:seon.error/count r)] [:td (:seon.error/agents r 0)]
       [:td (or t "-")] [:td (if (:seon.error/resolved-ms r) "resolved" (some-> (get task-state t) name))]])]})

(defn file-tx
  "The transaction filing the tasks for recorded error s on db: the defect tasks (prove,
  fix, merge), released; the error linked to its fix task. Answers [] when s is not recorded."
  {:malli/schema [:=> [:cat :seon.host/db :seon.error/signature [:map-of :keyword :seon.template/template]]
                  [:vector :any]]}
  [db s templates]
  (if-let [r (record db s)]
    (let [decls (file-decls db r templates)
          ids (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) decls)
          created (task/create-tx db decls)]
      (if (:seon.error/type created)
        created
        (vec (concat created (task/execution-tx ids :running)
                     [{:seon.error/signature s :seon.error/task [:seon.task/id (task-id s (:seon.error/generation r 0))]}]))))
    []))

(defn resolve-tx
  "The transaction resolving recorded error s at ms."
  {:malli/schema [:=> [:cat :seon.error/signature :int] [:vector :map]]}
  [s ms] [{:seon.error/signature s :seon.error/resolved-ms ms}])
