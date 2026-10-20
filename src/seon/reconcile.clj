(ns seon.reconcile
  "The one reconciler (lanes R1 and M1's proto.agents.reconcile): a pure function of main's
  db value (desired state: the tasks; observed state: attempts, their ends and proof
  results, root's boot) to the minimal actions that close the gap, and the done rule
  `done-refusals` (one function, used by root to judge and by the agent to know when to
  stop). `reconciler-proc` is the root proc that runs it on main's commits and carries the
  actions out as requests to the api, each action's facts written by the writer."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.edn :as edn] [seon.sock :as sock] [seon.step :as step]
            [seon.task :as task] [seon.template :as tpl] [seon.proofs :as proofs] [seon.merge :as pm]
            [datahike.api :as d] [clojure.string :as str] [clojure.core.async.flow :as flow] [seon.reader :as reader]))

(def default-max-turns
  "Turns an attempt may take when its task sets no :seon.config.agent/max-turns."
  8)

(def default-max-running
  "Parallel attempts when root's boot set no :seon.config.agent/max-running."
  4)

(def default-max-merging
  "Merge attempts at once when root's boot set no :seon.config.merge/max-running. They run
  together only when the definitions they change are disjoint (a merge train); 1 is git's
  one-at-a-time merge queue."
  8)

(def merge-dials
  "The merge dials' defaults, when the task sets none (:seon.config.merge/*)."
  {:max-conflicts 3 :max-merge-turns 2 :max-attach 2})

(defn dials
  "Task t's merge dials: its own :seon.config.merge/* over the defaults."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.reconcile/merge-dials]}
  [t]
  (into {} (for [[k v] merge-dials] [k (get t (keyword "seon.config.merge" (name k)) v)])))

;; ---- the view: one read of main ---------------------------------------------------

(defn desired-env
  "The environment task t's attempts must run in."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (or (:seon.config.agent/max-turns t) default-max-turns)})

;; ---- the done rule -----------------------------------------------------------------

(defn done-refusals
  "Why an attempt's measured facts do not make its task done; empty means done. Refused:
  no proofs (never proven); a proof not passing; a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a contract gap (a function the attempt defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved). A :design task readies
  the interface for its implement step, so its own proofs may stay red."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/refusal]]}
  [{:keys [proofs results main-green allowed-red uncontracted unmerged kind]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           (when (and (empty? proofs) (not design?)) [{:refusal :no-proofs}])
           (when-not design?
             (for [p (sort-by str proofs) :when (not= :pass (get results p :none))] {:refusal :proof-red :symbol p}))
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t))] {:refusal :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)))]
             {:refusal :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:refusal :contract-gap :symbol s})
           (for [k (sort unmerged)] {:refusal :unmerged :key k})))))

(defn- path
  "span as a path reference, or nil, by the rule that tells a path from a symbol: no
  whitespace, and an anchor (#heading), more than one /, or an extension after its last
  / (`docs/DRAGONS.md#physics`, `src/seon/repl.clj`; `app.calc/add` is a symbol)."
  {:malli/schema [:=> [:cat :string] [:maybe [:map [:kind [:= :path]] [:path :string] [:anchor {:optional true} :string]]]]}
  [span]
  (let [[p a] (str/split span #"#" 2)
        last-part (peek (str/split (or p "") #"/" -1))]
    (when (and (seq p) (not (re-find #"\s" span)) (not (#{\: \( \[ \{ \' \@ \^} (first span)))
               (or (some? a) (< 1 (count (re-seq #"/" p))) (and (str/includes? p "/") (str/includes? last-part "."))))
      (cond-> {:kind :path :path p} (seq a) (assoc :anchor a)))))

(defn reference
  "What one backticked span names, by one rule: a path (see path), else what edamame reads
  from it (seon.reader, nothing evaluated): one keyword is {:kind :keyword}; one qualified
  symbol {:kind :symbol}; one form {:kind :form} with the qualified symbols it names; else
  (prose, an unqualified name, a literal, text that does not read) nil."
  {:malli/schema [:=> [:cat :string]
                  [:maybe [:map [:kind [:enum :symbol :keyword :path :form]] [:symbol {:optional true} :qualified-symbol]
                           [:keyword {:optional true} :keyword] [:path {:optional true} :string] [:anchor {:optional true} :string]
                           [:form {:optional true} :any] [:symbols {:optional true} [:vector :qualified-symbol]]]]]}
  [span]
  (or (path span)
      (let [forms (reader/read-text span 'user)
            [x & more] forms]
        (when (and (seq forms) (empty? more))
          (cond (keyword? x) {:kind :keyword :keyword x}
                (qualified-symbol? x) {:kind :symbol :symbol x}
                (coll? x) {:kind :form :form x
                           :symbols (vec (distinct (filter qualified-symbol? (tree-seq coll? seq x))))})))))

(defn spans
  "The backticked spans of Markdown text outside ``` fenced blocks, in order."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  [text]
  (let [prose (str/join "\n" (:out (reduce (fn [{:keys [in?] :as st} l]
                                              (if (str/starts-with? (str/triml l) "```")
                                                (update st :in? not)
                                                (cond-> st (not in?) (update :out conj l))))
                                            {:in? false :out []} (str/split-lines (or text "")))))]
    (mapv second (re-seq #"`([^`\n]+)`" prose))))

(defn references
  "The vars text references: each qualified symbol a backticked span names, alone or in a
  form (reference), in order, once each. Keywords, paths and prose are not vars."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :qualified-symbol]]}
  [text]
  (vec (distinct (for [s (spans text)
                       :let [r (reference s)]
                       sym (case (:kind r) :symbol [(:symbol r)] :form (:symbols r) [])]
                   sym))))

(defn hairy
  "Is a merge too hairy to keep resolving, from measured facts: more conflicts than the
  dial; main deleted a var the task's plan or proofs reference; more model turns without
  green than the dial. Answers {:rule :reason} or nil. The merge agent stops early on it;
  the reconciler decides from the attempt's facts alone."
  {:malli/schema [:=> [:cat [:map [:conflicts [:sequential [:map [:key :any] [:kind :keyword]]]]
                             [:task [:map [:seon.task/accumulated [:map [:plan :string] [:proofs :seon.task/proofs]]]]]
                             [:merge-turns :int] [:max-conflicts :int] [:max-merge-turns :int]]]
                  [:maybe [:map [:rule [:enum :too-many-conflicts :deleted-by-them-referenced :merge-turns]] [:reason :string]]]]}
  [{:keys [conflicts task merge-turns max-conflicts max-merge-turns]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated task)
        refs (into (set proofs) (references plan))
        deleted (for [{:keys [key kind]} conflicts
                      :when (and (= kind :deleted-by-them) (= :var (first key)) (refs (second key)))]
                  (second key))]
    (cond
      (> (count conflicts) max-conflicts)
      {:rule :too-many-conflicts :reason (str (count conflicts) " conflicts > :seon.config.merge/max-conflicts " max-conflicts)}
      (seq deleted)
      {:rule :deleted-by-them-referenced :reason (str "main deleted " (str/join ", " deleted) ", which the task references")}
      (> merge-turns max-merge-turns)
      {:rule :merge-turns :reason (str merge-turns " turns without green > :seon.config.merge/max-merge-turns " max-merge-turns)})))

(defn refusals-text
  "One line naming each refusal."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/refusal]] :string]}
  [rs] (str/join "; " (map #(str (name (:refusal %)) (some->> (or (:symbol %) (:key %)) (str " "))) rs)))

;; ---- the incremental reconciler: an index watched through main's tx reports --------------
;; k8s's informer + W1's engine: list once (index), then each commit's resolved datoms name the
;; task, attempt and root entities it touched (observe); only those are re-read (one pull
;; each) and only the tasks they reach are re-derived: the task itself, the tasks of its
;; attempts, and the tasks that need it (the reverse :needs index), transitively for a needs
;; change. `actions` derives the same actions as the full view from the index alone; it walks
;; only the active attempts (running, ended, dead) and the head of the ready set, so a pass
;; costs what changed plus what is in flight, never the number of tasks.

(def ^:private watched-ns
  #{"seon.task" "seon.attempt" "seon.root" "seon.config.agent" "seon.config.merge"})

(def empty-index
  "The index of a db with no tasks."
  {:root nil :tasks {} :keys {} :needed-by {} :acc {} :depth {} :claims {}
   :attempts {} :by-task {} :done {} :ready (sorted-set) :ready-key {}
   :live #{} :reap #{} :kill {} :judge #{} :merging #{}})

(defn- live-in?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/attempt] :boolean]}
  [idx a] (and (= :running (:seon.attempt/status a)) (= (:boot (:root idx)) (:seon.attempt/boot a))))

(defn- done-info
  "{:any? (merged or accepted) :merged-t (the earliest merge, or nil)} of task id."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:map [:any? :boolean] [:merged-t [:maybe :int]]]]}
  [idx id]
  (let [as (map (:attempts idx) (get-in idx [:by-task id]))
        ms (keep #(when (= :merged (:seon.attempt/status %)) (:seon.attempt/merged-t % 0)) as)]
    {:any? (boolean (some #(#{:merged :done} (:seon.attempt/status %)) as))
     :merged-t (when (seq ms) (apply min ms))}))

(defn- idx-done? {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (boolean (get-in idx [:done id :any?])))

(defn- idx-ready?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id]
  (let [t (get-in idx [:tasks id])]
    (boolean (and t (= :running (:seon.task/execution t)) (not (idx-done? idx id))
                  (every? #(idx-done? idx %) (:seon.task/needs t))
                  (not-any? #(let [a (get-in idx [:attempts %])] (or (live-in? idx a) (#{:ended :dead} (:seon.attempt/status a))))
                            (get-in idx [:by-task id]))))))

(defn- refresh-done
  "Re-derive task id's done state from its attempts; answers [idx flipped?]."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:tuple :seon.reconcile/index :boolean]]}
  [idx id]
  (let [old (get-in idx [:done id :any?]) d (done-info idx id)]
    [(assoc-in idx [:done id] d) (not= old (:any? d))]))

(defn- refresh-ready
  "Move task id's key in the ready set to what its values say now."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.reconcile/index]}
  [idx id]
  (let [idx (-> idx (update :ready disj (get-in idx [:ready-key id])) (update :ready-key dissoc id))
        ;; deepest first: finish the chains already under way before starting new ones
        k [(- (get-in idx [:depth id] 0)) id]]
    (if (idx-ready? idx id) (-> idx (update :ready conj k) (assoc-in [:ready-key id] k)) idx)))

(defn- derive-needs
  "Task id's depth and accumulated proofs from its needs' (already derived), its claims moved."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.reconcile/index]}
  [idx id]
  (let [t (get-in idx [:tasks id])
        ns (filter (:tasks idx) (:seon.task/needs t))
        acc (into (set (:seon.task/proofs t)) (mapcat #(get-in idx [:acc %])) ns)
        old (get-in idx [:acc id] #{})
        claims (as-> (:claims idx) c
                 (reduce (fn [c p] (let [s (disj (get c p) id)] (if (seq s) (assoc c p s) (dissoc c p)))) c (remove acc old))
                 (reduce (fn [c p] (update c p (fnil conj #{}) id)) c (remove old acc)))]
    (assoc idx :acc (assoc (:acc idx) id acc) :claims claims
           :depth (assoc (:depth idx) id (if (seq ns) (inc (apply max (map #(get-in idx [:depth %] 0) ns))) 0)))))

(defn- put-task
  "Task value t (nil: gone) under id, with the reverse needs index moved."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id [:maybe :seon.task/own]] :seon.reconcile/index]}
  [idx id t]
  (let [old (get-in idx [:tasks id :seon.task/needs] #{})
        new (:seon.task/needs t #{})
        nb (as-> (:needed-by idx) nb
             (reduce (fn [nb n] (update nb n disj id)) nb (remove new old))
             (reduce (fn [nb n] (update nb n (fnil conj #{}) id)) nb (remove old new)))]
    (assoc idx :needed-by nb :tasks (if t (assoc (:tasks idx) id t) (dissoc (:tasks idx) id)))))

(defn- put-attempt
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/id [:maybe :seon.attempt/attempt]] :seon.reconcile/index]}
  [idx aid a]
  (let [old (get-in idx [:attempts aid])
        tid (or (:seon.attempt/task a) (:seon.attempt/task old))]
    (-> idx
        (assoc :attempts (if a (assoc (:attempts idx) aid a) (dissoc (:attempts idx) aid)))
        (update-in [:by-task tid] (fnil (if a conj disj) #{}) aid))))

(defn- topo
  "ids and every task that needs one of them, transitively, each after its needs among them."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id]] [:vector :seon.task/id]]}
  [idx ids]
  (let [all (loop [front (vec ids) seen (set ids)]
              (if-let [[x & more] (seq front)]
                (let [ns (remove seen (get-in idx [:needed-by x]))] (recur (into (vec more) ns) (into seen ns)))
                seen))
        deg (into {} (for [x all] [x (count (filter all (get-in idx [:tasks x :seon.task/needs])))]))]
    (loop [q (vec (sort (filter #(zero? (deg %)) all))) deg deg out []]
      (if-let [[x & more] (seq q)]
        (let [nb (filter all (get-in idx [:needed-by x]))
              deg (reduce #(update %1 %2 dec) deg nb)]
          (recur (into (vec more) (sort (filter #(zero? (deg %)) nb))) deg (conj out x)))
        out))))

(defn- classify
  "Attempt aid's place in the sets actions reads: live, reap, kill (with the reason), judge
  (ended or dead), merging (a merge task's attempt live or ended)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/id] :seon.reconcile/index]}
  [idx aid]
  (let [a (get-in idx [:attempts aid])
        t (get-in idx [:tasks (:seon.attempt/task a)])
        st (:seon.attempt/status a)
        live? (and a (live-in? idx a))
        kill (when live?
               (cond (= :cancelled (:seon.task/execution t)) :cancelled
                     (= :held (:seon.task/execution t)) :held
                     (not= (desired-env t) (:seon.attempt/env a)) :env-changed))
        mark (fn [idx k on?] (update idx k (if on? conj disj) aid))]
    (-> idx
        (mark :live live?)
        (mark :reap (and a (= :running st) (not live?)))
        (update :kill #(if kill (assoc % aid kill) (dissoc % aid)))
        (mark :judge (boolean (#{:ended :dead} st)))
        (mark :merging (and a (= :merge (:seon.task/kind t)) (or live? (= :ended st)))))))

(defn- settle
  "Re-derive after entities changed: tasks whose needs or proofs changed (and the tasks that
  need them, transitively) get depth and accumulated proofs again in dependency order; each
  dirty task's done state is re-derived (it depends on its own attempts only); then readiness
  of the dirty tasks and of the tasks needing one whose done state flipped."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id] [:set :seon.task/id] [:set :seon.attempt/id]] :seon.reconcile/index]}
  [idx needs-changed dirty touched]
  (let [order (topo idx needs-changed)
        idx (reduce derive-needs idx order)
        dirty (into dirty order)
        [idx flipped] (reduce (fn [[idx fl] id] (let [[idx f?] (refresh-done idx id)] [idx (cond-> fl f? (conj id))])) [idx []] dirty)]
    (reduce classify
            (reduce refresh-ready idx (into dirty (mapcat #(get-in idx [:needed-by %])) flipped))
            (into touched (mapcat #(get-in idx [:by-task %])) dirty))))

(defn observe
  "The index after main's commit: ops are the commit's resolved datoms ([op e a v]), db the
  db after it. Re-reads only the entities the commit touched."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.host/db [:sequential :any]] :seon.reconcile/index]}
  [idx db ops]
  (let [es (into #{} (keep (fn [[_ e a]] (when (and (keyword? a) (watched-ns (namespace a))) e))) ops)]
    (if (empty? es)
      idx
      (let [step (fn [[idx nc dirty all? touched] e]
                   (let [[kind v :as ev] (task/entity-value db e)
                         [okind oid] (get-in idx [:keys e])]
                     (case (or kind okind)
                       :task (let [id (or (:seon.task/id v) oid)
                                   needs? (not= (get-in idx [:tasks id :seon.task/needs]) (:seon.task/needs v))]
                               [(-> idx (put-task id v) (update :keys #(if v (assoc % e [:task id]) (dissoc % e))))
                                (cond-> nc (or needs? (nil? (get-in idx [:acc id])) (not= (get-in idx [:tasks id :seon.task/proofs]) (:seon.task/proofs v))) (conj id))
                                (conj dirty id) all? touched])
                       :attempt (let [aid (or (:seon.attempt/id v) oid)
                                      tid (or (:seon.attempt/task v) (get-in idx [:attempts aid :seon.attempt/task]))]
                                  [(-> idx (put-attempt aid v) (update :keys #(if v (assoc % e [:attempt aid]) (dissoc % e))))
                                   nc (conj dirty tid) all? (conj touched aid)])
                       :root [(-> idx (assoc :root v) (assoc-in [:keys e] [:root :root]))
                              nc dirty (or all? (not= (:boot (:root idx)) (:boot v))) touched]
                       [idx nc dirty all? touched])))
            [idx nc dirty all? touched] (reduce step [idx #{} #{} false #{}] (sort es))]
        ;; a new boot changes which attempts are live: every task is re-derived (once per boot)
        (settle idx nc (if all? (set (keys (:tasks idx))) dirty) touched)))))

(defn index
  "The index of db, listed: observe of every task, attempt and root entity at once."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (observe empty-index db (for [e (task/entity-ids db)] [:db/add e :seon.task/id nil])))

(defn done-input
  "The done rule's input for attempt results of task id, observed against main at t basis
  (nil: main as idx holds it), over the index: the regression and allowed-red sets restricted to the tests
  that ran (the only ones done-refusals asks about), each test looked up by its claimants."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id :seon.proof/results [:set :qualified-symbol] [:sequential :string] [:maybe :int]] :seon.reconcile/done-input]}
  [idx id results uncontracted unmerged basis]
  (let [by? (fn [x] (when-let [mt (get-in idx [:done x :merged-t])] (or (nil? basis) (<= mt basis))))
        claim (fn [t] (disj (get-in idx [:claims t] #{}) id))]
    {:proofs (get-in idx [:acc id] #{})
     :kind (get-in idx [:tasks id :seon.task/kind] :work)
     :results results
     :main-green (into #{} (filter #(some by? (claim %))) (keys results))
     :allowed-red (into #{} (filter #(some (complement by?) (claim %))) (keys results))
     :uncontracted uncontracted
     :unmerged (vec unmerged)}))

(defn- merge-keys
  "The program keys merge task id lands: what the attempts on its branch measured as changed
  (:seon.attempt/changed of its own attempts and of the tasks it needs on the same branch),
  or nil when no attempt there measured it (unknown: it overlaps every merge)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe [:set :string]]]}
  [idx id]
  (let [br (get-in idx [:tasks id :seon.task/branch])
        ts (cons id (filter #(= br (get-in idx [:tasks % :seon.task/branch])) (get-in idx [:tasks id :seon.task/needs])))
        cs (keep #(:seon.attempt/changed (get-in idx [:attempts %])) (mapcat #(get-in idx [:by-task %]) ts))]
    (when (seq cs) (into #{} cat cs))))

(defn actions
  "The actions for index idx, in the order root executes them (the full view's rule): reap,
  kill, judge, then start ready tasks up to max-running, merges up to max-merging. Reads the
  index's sets: its cost is the attempts to reap, kill and judge plus the starts."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :seon.reconcile/actions]}
  [idx]
  (let [root (or (:root idx) {:boot "" :max-running default-max-running :max-merging default-max-merging})
        att (:attempts idx)
        reaps (for [aid (sort (:reap idx))] {:op :reap :attempt aid})
        kills (for [[aid r] (sort-by key (:kill idx))] {:op :kill :attempt aid :reason r})
        judged (for [aid (sort (:judge idx))
                     :let [a (att aid) id (:seon.attempt/task a) t (get-in idx [:tasks id])
                           merge? (= :merge (:seon.task/kind t))]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :attempt aid}
                   (= :dead (:seon.attempt/status a))
                   {:op :bail :attempt aid :task id :problem (str "its agent died" (some->> (:seon.attempt/reason a) (str ": ")))}
                   :else
                   (let [rs (done-refusals (done-input idx id (:seon.attempt/results a {}) (:seon.attempt/uncontracted a #{})
                                                           (:seon.attempt/unmerged a []) (:seon.attempt/basis-t a)))]
                     (cond
                       (and (empty? rs) merge?) {:op :land :attempt aid :task id}
                       (empty? rs) {:op :accept :attempt aid :task id}
                       (and merge? (< (:seon.task/generation t 0) (:max-attach (dials t)))) {:op :attach :attempt aid :task id}
                       :else
                       {:op :bail :attempt aid :task id
                        :problem (str "attempt " aid " ended " (name (:seon.attempt/outcome a :error))
                                      (some->> (:seon.attempt/reason a) (str " (") (#(str % ")")))
                                      ": " (refusals-text rs))}))))
        ;; the merge train (bors, GitHub's merge queue): green merge attempts land together in
        ;; one commit once no merge attempt is still running, so every member merged the same
        ;; main; a train closes when its first member ends (no new member joins until it
        ;; lands, so landing cannot starve)
        lands (filter #(= :land (:op %)) judged)
        judged (remove #(= :land (:op %)) judged)
        live-merge? (some (:live idx) (:merging idx))
        train (when (and (seq lands) (not live-merge?))
                [{:op :merge-train :attempt (:attempt (first lands)) :members (mapv #(select-keys % [:attempt :task]) lands)}])
        closed? (some #(= :ended (get-in idx [:attempts % :seon.attempt/status])) (:merging idx))
        room (max 0 (- (:max-running root default-max-running) (- (count (:live idx)) (count (:kill idx)))))
        merge? (fn [id] (= :merge (get-in idx [:tasks id :seon.task/kind])))
        merge-room (max 0 (- (:max-merging root default-max-merging) (count (:merging idx))))
        ;; a merge joins only when what it changes is disjoint from every merge in flight;
        ;; one whose changes are unknown runs alone; conflicting merges serialize
        flying (map #(merge-keys idx (get-in idx [:attempts % :seon.attempt/task])) (:merging idx))
        taken0 (when-not (some nil? flying) (into #{} cat flying))
        starts (loop [[[_ id] & more] (seq (:ready idx)) n 0 m 0 taken taken0 acc []]
                 (cond (or (nil? id) (>= n room)) acc
                       (merge? id) (let [ks (merge-keys idx id)
                                         ok (and (< m merge-room) (not closed?) taken
                                                 (if ks (not-any? taken ks) (and (zero? m) (empty? (:merging idx)))))]
                                     (if ok
                                       (recur more (inc n) (inc m) (when ks (into taken ks)) (conj acc id))
                                       (recur more n m taken acc)))
                       :else (recur more (inc n) m taken (conj acc id))))
        spawns (for [id starts :let [t (get-in idx [:tasks id])]]
                 {:op :spawn :task id :env (desired-env t)
                  :attempt (str id "#" (inc (count (get-in idx [:by-task id]))))})]
    (vec (concat reaps kills judged train spawns))))

;; ---- queries, over one index (list it once: (index db)) -----------------------------

(defn reconcile
  "The actions that move db's observed state toward its desired state (index, then actions)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.reconcile/actions]}
  [db] (actions (index db)))

(defn running
  "The attempts running now (in this boot), by id."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:set :seon.attempt/id]]}
  [idx] (:live idx))

(defn done?
  "Is task id done (merged into its target, or accepted on its branch)?"
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (idx-done? idx id))

(defn blocked-by
  "The needs of task id that are not done."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:set :seon.task/id]]}
  [idx id] (into #{} (remove #(idx-done? idx %)) (get-in idx [:tasks id :seon.task/needs])))

(defn should-run
  "Tasks that should have an attempt running and have none, in start order (dependency
  depth, then id); max-running is not applied here."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:vector :seon.task/id]]}
  [idx] (mapv second (:ready idx)))

(defn to-kill
  "Live attempts to stop, with why: [[attempt-id reason]]."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:vector [:tuple :seon.attempt/id :keyword]]]}
  [idx] (vec (sort-by first (:kill idx))))

;; ---- what an attempt's agent wrote on its branch ----

(defn pull-end
  "The end attempt aid's turn loop wrote on branch db ({:outcome :reason :turns :tokens},
  and the error that ended it and its waste as data), or {}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.attempt/id] :map]}
  [db aid]
  (let [e (d/q '[:find ?e . :in $ ?a :where [?e :seon.end/attempt ?a]] db aid)]
    (if e
      (let [m (d/pull db [:seon.end/outcome :seon.end/reason :seon.end/turns :seon.end/tokens :seon.end/error :seon.end/waste] e)]
        (into {} (for [[k v] m] [(keyword (name k)) (if (#{:seon.end/error :seon.end/waste} k) (edn/read-string v) v)])))
      {})))

(defn- edn-text
  "x as stored EDN (no print limits: its producer bounded it)."
  {:malli/schema [:=> [:cat :any] :string]}
  [x] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str x)))

(defn- error-text
  "Error map e as an attempt's stored error (:seon.attempt/error): the one error map, its
  protocol members (a test count, :ok, :merged?) left out."
  {:malli/schema [:=> [:cat :map] :string]}
  [e] (edn-text (dissoc e :pass :fail :error :reports :merged? :ok :tx :applied :conflicts)))

;; ---- the reconciler proc: actions carried out as requests, every answer as data ----------
;; Root's executor (R1's proto.agents.root, a thread per attempt) as one step-fn: main's
;; commits arrive through a sliding buffer of one (a reconciler reads main's latest db, so a
;; commit it missed is covered by the next); each action is a flight, a short chain of
;; requests to the api (spawn, eval, tests, the writer's reads and writes, kill), each answer
;; arriving on :answers keyed by its request id. An action whose attempt is in flight is not
;; started twice.

(defn- cas-tx
  "Move attempt aid's status from -> to (Datahike's compare-and-swap, by lookup ref: a racing
  observer that already moved it leaves it), with the observed members m."
  {:malli/schema [:=> [:cat :seon.attempt/id :seon.attempt/status :seon.attempt/status :map] [:vector :any]]}
  [aid from to m]
  (cond-> [[:db/cas [:seon.attempt/id aid] :seon.attempt/status from to]]
    (seq m) (conj (assoc m :seon.attempt/id aid))))

(defn- request
  "Send req to the api as this proc's request: the answer comes back on :answers tagged by a
  new request id, which then names step of attempt aid's flight. Answers [st output]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step :map] [:tuple :seon.reconcile/state :map]]}
  [st aid step req]
  (let [rid (inc (:rid st))]
    [(-> st (assoc :rid rid) (assoc-in [:pending rid] [aid step]))
     {:op :request :req req :route [:reconciler :answers] :tag rid}]))

(defn- write
  "A write on main through the writer, as step of aid's flight."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step [:sequential :any]] [:tuple :seon.reconcile/state :map]]}
  [st aid step tx] (request st aid step {:op :write :req {:op :tx :branch :db :tx-data (vec tx)}}))

(defn- start
  "Begin action act's flight. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.reconcile/action] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st {:keys [op attempt] :as act}]
  (let [;; a kill reaches a live attempt whose observer chain is in flight: that flight stays
        ;; and sees its agent die
        killing? (and (= :kill op) (get-in st [:flights attempt]))
        st (if killing? (assoc-in st [:flights attempt :killing] true) (assoc-in st [:flights attempt] {:act act}))
        a (task/attempt (:db st) attempt)
        agent (:seon.attempt/agent a)
        one (fn [[st o]] [st [o]])]
    (case op
      :spawn (one (request st attempt :spawned {:op :spawn :opts {:branch (get-in act [:env :branch]) :task (:task act)}}))
      :reap (one (write st attempt :done (cas-tx attempt :running :lost {:seon.attempt/end-ms (System/currentTimeMillis) :seon.attempt/reason "root restarted"})))
      :close (one (write st attempt :done (cas-tx attempt :ended :closed {})))
      :accept (one (write st attempt :done (cas-tx attempt :ended :done {})))
      :kill (let [[st o1] (request st attempt :ignored {:op :kill :id agent})
                  [st o2] (write st attempt (if killing? :ignored :done) (cas-tx attempt :running :killed {:seon.attempt/end-ms (System/currentTimeMillis)
                                                                                    :seon.attempt/reason (name (:reason act))}))]
              [st [o1 o2]])
      :bail (one (write st attempt :done (conj (cas-tx attempt (:seon.attempt/status a) :bailed {})
                                               {:seon.task/id (:task act) :seon.task/execution :held :seon.task/problem (:problem act)})))
      ;; a merge task's branch into main: clean by construction when the branch holds main's
      ;; program head; behind: its task runs again, a new agent
      ;; a merge train: every member's branch into main in one commit (each holds main's
      ;; program head, the members' changes are disjoint); a member found behind or refused
      ;; is answered alone
      :merge-train (let [bs (mapv #(get-in (task/attempt (:db st) (:attempt %)) [:seon.attempt/env :branch]) (:members act))]
                     (one (request (assoc-in st [:flights attempt :branches] bs) attempt :trained
                                   {:op :write :req {:op :merge-train :branches bs :data-attrs #{}}})))
      :attach (one (write st attempt :done (tpl/attach-tx (:db st) act))))))

(defn- judge-tx
  "Attempt aid's end on main from MEASURED facts: the end its agent wrote on the branch,
  root's own proof results, the branch's merge entries and unmerged keys, the contract gaps
  against main, its improved plan carried to the task."
  {:malli/schema [:=> [:cat :seon.reconcile/state :map :seon.proof/results [:map [:db :seon.host/db]] [:set :string]] [:vector :any]]}
  [st {:keys [act run-problems]} results {:keys [db base-t]} changed]
  (let [main (:db st)
        aid (:attempt act) id (:task act)
        end (pull-end db aid)
        mp (pm/program main #{})
        unc (into #{} (for [[[k sym] e] (pm/program db #{})
                            :when (and (= :var k) (= :fn (:kind e)) (nil? (:contract e)) (not (:flagged e)) (not= e (get mp [k sym])))]
                        sym))
        plan (:seon.task/plan (task/task db id))]
    (into (cas-tx aid :running :ended
                    (merge (task/end-members (cond-> {:outcome (:outcome end :error) :turns (:turns end 0) :model-calls (:turns end 0)
                                               :tokens (:tokens end 0) :ms 0.0 :results results :uncontracted unc
                                               :conflicts (mapv #(dissoc % :main-t :resolved?) (pm/entries db))
                                               :unmerged (mapv #(pr-str (:key %)) (pm/unmerged db (pm/program db #{})))
                                               :changed changed}
                                        (:reason end) (assoc :reason (:reason end))
                                        (not (:outcome end)) (assoc :reason "the agent wrote no end"))
                                      (System/currentTimeMillis) (or base-t (:max-tx main)))
                    ;; the problems measured at its end (lane D2): red assertions, cost verdicts,
                    ;; the error that ended it, its waste
                    (cond-> {}
                      (seq (:failures run-problems)) (assoc :seon.attempt/failures (edn-text (:failures run-problems)))
                      (seq (:costs run-problems)) (assoc :seon.attempt/costs (edn-text (:costs run-problems)))
                      (:error end) (assoc :seon.attempt/error (error-text (:error end)))
                      (seq (:waste end)) (assoc :seon.attempt/waste (edn-text (:waste end))))))
            (when (and plan (not= plan (:seon.task/plan (task/task main id)))) (task/plan-tx id plan)))))

(defn- advance
  "Attempt aid's flight takes answer r of its step. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step :any] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st aid step r]
  (let [{:keys [act agent] :as f} (get-in st [:flights aid])
        done (fn [st] [(update st :flights dissoc aid) []])
        one (fn [[st o]] [st [o]])
        ;; its agent died: the one error map root answered with (:seon.agent/died: last op,
        ;; stderr tail; or :seon.root/no-agent) is the attempt's error
        dead (fn [st e] (one (write st aid :done (cas-tx aid :running :dead {:seon.attempt/end-ms (System/currentTimeMillis)
                                                                             :seon.attempt/reason (:seon.error/message e)
                                                                             :seon.attempt/error (error-text e)}))))]
    (case step
      :ignored [st []]
      :done (done st)
      :spawned (if-let [ag (:id r)]
                 (one (write (assoc-in st [:flights aid :agent] ag) aid :started
                             (task/attempt-start-tx aid (:task act) (:boot st) ag (:env act) (System/currentTimeMillis))))
                 (done st))
      ;; an outside agent works this attempt (seon.entrance): no turn loop; it hands it back
      :started (if (= "outside" (get-in act [:env :model]))
                 (done st)
                 (one (request st aid :worked
                               {:op :eval :id agent
                                :forms (pr-str (list 'seon.turn/work! (list 'quote (cond-> {:task (:task act) :attempt aid :max-turns (get-in act [:env :max-turns])}
                                                                                       (get-in act [:env :model]) (assoc :model (get-in act [:env :model]))))))})))
      ;; the turn loop answered: died (no live agent), or judge it from measured facts
      :worked (if (= -1 (:id (first r)))
                (dead st (:err (first r)))
                (one (request st aid :tested {:op :tests :id agent :timeout-ms 30000})))
      :tested (if (contract/error? r)
                (dead st r)
                (one (request (-> st (assoc-in [:flights aid :results] (proofs/results r))
                                  (assoc-in [:flights aid :run-problems] {:failures (proofs/failures r) :costs (proofs/costs r)}))
                              aid :read {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}})))
      ;; what the branch changed since its merge base (three-way's :ours), measured for the train
      :read (one (request (assoc-in st [:flights aid :branch-db] r) aid :diffed
                          {:op :write :req {:op :branch-diff :branch (get-in act [:env :branch])}}))
      ;; every attempt is a new agent: none is reused
      :diffed (let [[st o1] (request st aid :ignored {:op :kill :id agent})
                    [st o2] (write st aid :done (judge-tx st f (:results f) (:branch-db f) (into #{} (map pr-str) (:ours r))))]
                [st [o1 o2]])
      ;; each member's answer, as one write: merged at the train's t, behind (its merge task
      ;; runs again, a new agent), or refused (held for root)
      :trained (one (write st aid :done
                           (vec (mapcat (fn [{:keys [attempt task]} r]
                                          (cond
                                            (:merged? r) (cas-tx attempt :ended :merged {:seon.attempt/merged-t (long (:tx r))})
                                            (= :seon.merge/behind (:seon.error/type r)) (cas-tx attempt :ended :behind {:seon.attempt/reason (:seon.error/message r)
                                                                                                                        :seon.attempt/error (error-text r)})
                                            :else (conj (cas-tx attempt :ended :bailed {:seon.attempt/error (error-text r)})
                                                        {:seon.task/id task :seon.task/execution :held
                                                         :seon.task/problem (str "merge refused: " (:seon.error/message r))})))
                                        (:members act) (:results r))))))))

(defn- pass
  "Reconcile main's latest db and start each action whose attempt is not in flight; spawns
  wait while earlier spawns have not written their attempt yet (max-running counts facts).
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st]
  (let [early (count (filter #(and (= :spawn (get-in % [:act :op])) (not (:agent %))) (vals (:flights st))))
        acts (remove #(let [f (get-in st [:flights (:attempt %)])] (and f (or (not= :kill (:op %)) (:killing f)))) (actions (:idx st)))
        acts (concat (remove #(= :spawn (:op %)) acts) (drop early (filter #(= :spawn (:op %)) acts)))]
    (reduce (fn [[st outs] act] (let [[st o] (start st act)] [st (into outs o)])) [st []] acts)))

(defn reconciler-proc
  "The reconciler. In :main (main's commits {:t :ops :db}, a sliding buffer of one) and :answers (the
  api's answers to its requests). Out :to-api (its requests, as {:op :request ...}).
  Args: :boot (this root's boot id); in :judge (outside agents' attempts handed back).
  Starts deepest-first (finish chains under way)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:boot "root's boot id" :judge "outside agents' attempts to judge (external)"}
       :ins {:main "main's commits" :answers "the api's answers"} :outs {:to-api "requests"}
       :workload :io
       :ping-map-fn (fn [st] {:seen (:seen st 0) :lists (:lists st 0) :t (:t st) :flights (count (:flights st)) :pending (count (:pending st))})})
  ([args] (assoc (dissoc args :judge) :seen 0 :rid 0 :flights {} :pending {} ::flow/in-ports {:judge (:judge args)}))
  ([st _] st)
  ([st in m]
   (case in
     ;; each commit's datoms move the index (observe); a commit the sliding buffer dropped
     ;; (main's t is not the next one) lists main again (index), k8s's relist after a gap
     :main (let [db (:db m) t (:max-tx db)
                 idx (if (and (:idx st) (= t (inc (:t st))))
                       (observe (:idx st) db (:ops m))
                       (index db))
                 st (assoc st :db db :t t :idx idx :seen (inc (:seen st)) :lists (cond-> (:lists st 0) (not= t (inc (:t st -2))) inc))
                 [st outs] (pass st)]
             [st {:to-api (into [{:op :stat :k :main-seen-us :v (/ (- (sock/now-ns) (:ns m)) 1000.0)}] outs)}])
     ;; an outside agent handed its attempt back: judged as a turn loop's end is
     :judge (let [{:keys [attempt task env agent]} m
                  st (assoc-in st [:flights attempt] {:act {:op :spawn :attempt attempt :task task :env env} :agent agent})
                  [st o] (request st attempt :tested {:op :tests :id agent :timeout-ms 30000})]
              [st {:to-api [o]}])
     :answers (let [[aid step] (get-in st [:pending (:tag m)])
                    st (update st :pending dissoc (:tag m))
                    [st outs] (if aid (advance st aid step (:r m)) [st []])
                    ;; a flight that ended may free its attempt for the next action
                    [st more] (if (and aid (not (get-in st [:flights aid])) (:db st)) (pass st) [st []])]
                [st {:to-api (into outs more)}]))))
