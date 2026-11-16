(ns seon.writer
  "The writer: the only d/transact, d/branch! and merge caller, with group commit (drain
  the in-port, one Datahike commit per branch, one flush). From proto/flow f2-final
  proto.control's writer (session F2; writer design A, the session design papers in git history)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.lib :as lib]
            [seon.program :as program] [seon.merge :as pm] [seon.problems :as problems] [seon.source :as source] [seon.doc :as doc] [seon.contract :as contract] [seon.sock :as sock] [seon.step :as step] [seon.config :as cfg] [seon.task :as task]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.string :as str]))

(defn- main-commit
  "Main moved from prev to t, by one stored commit: its readers (the reconciler, the test
  service, the vectors and render procs) get the notice {:t :prev :ops :commit :ns}, the commit
  to make the db from when they take it (d/commit-as-db), never a db value that a queue or a
  slow reader would keep alive (docs/machine.md#what-a-process-owns-in-memory); root loads the program change (a
  restarted fork server forks from root). The fork server hears of it per stored commit
  (stored!). Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/t :seon.db/t :seon.db/resolved-tx :seon.host/db] :seon.writer/state]}
  [st o prev t ops db]
  (step/out! o :main {:t t :prev prev :ops ops :commit (d/commit-id @(:conn st)) :ns (profile/now-ns) :program-t (:program-t st)})
  ;; root holds main: a moved schema entry (a retraction included: a deleted key leaves the registry) is its registry, Datahike schema
  ;; and entity index now, held BEFORE the load, so the contracts the load re-arms compile against it (seon.program/hold-main-schema!)
  ;; then the program by the one load path (seon.program/load-diff!), from the db this process last loaded its program from to this
  ;; commit's: never from the commit's ops, so a commit whose ops don't show a form as a reader of ops expects is covered by the next
  ;; diff, and nothing is missed for good (runtime, 2026-11-16: seon.vector/round's new body never loaded in the writer process)
  ;; :program-db has no default: the writer's start sets it (boot loaded main's), and a state without it fails load-diff!'s contract, typed, never a
  ;; diff of db against itself that loads nothing (deps, 2026-11-16: a proc started before :program-db existed loaded nothing at t 537044348)
  (let [moved? (program/moves-program? ops)
        errs (when moved? (seq (concat (program/hold-main-schema! db) (program/load-diff! (:program-db st) db {:tests? false}))))
        st (cond-> st moved? (assoc :program-db db))]
    (when errs (step/out! o :events (step/event {:type :load-errors :errors (vec errs)})))
  ;; a config entry moved: main's root entity says so with the next commit (docs/config.md#facts-on-main)
  (if-let [{c :config srcs :sources} (cfg/moved db ops)] (update st :pend (fnil conj []) {:tx-data (task/config-tx c srcs)}) st)))

(defn- mark-tx
  "The tx-data that moves main's content mark (:seon.system/content-t) past every t db, main before the commit, holds, so a
  commit that moves the content never shares a mark with one before it (docs/storage.md#mains-content-mark)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.db/tx-data]}
  [db] [{:seon.system/id :root :seon.system/content-t (inc (:max-tx db))}])

(defn- mark!
  "The report of main's content mark moved, applied on the connection after ops (resolved datoms applied and not yet
  committed, from db, main before them) when they move main's content (seon.merge/moves-content?), else nil: the commit
  that stores them carries it (docs/storage.md#mains-content-mark). A mark the connection refuses throws, so no commit is
  made without it."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/resolved-tx :seon.host/db] [:maybe :map]]}
  [st ops db]
  (when (pm/moves-content? ops)
    (let [r (dw/apply! (:conn st) {:tx-data (mark-tx db) :tx-meta (pdb/tx-meta (pdb/conn-main (:conn st)))})]
      (if (map? r) r (throw r)))))

(defn- stored!
  "Main's stored commit, made of ops (its transactions' resolved datoms, in order): the fork
  server's replica reads that commit from the store (d/commit-as-db; FIFO with fork
  requests) and loads the program they change, never replaying the datoms. A group commit
  is one stored commit, so one notice covers its batch. The env is MDB_NOSYNC: a committed
  write is readable by another process before the flusher's sync."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/resolved-tx] :nil]}
  [st o ops]
  (let [db @(:conn st)]
    (step/out! o :to-fork-server {:op :tx :t (:max-tx db) :commit (d/commit-id db) :ns (profile/now-ns) :tx-data ops :program-t (:program-t st)})
    nil))

(defn- program-at
  "Writer state st with :program-t, the t of main's last commit whose ops move the program (seon.program/moves-program?), brought to
  commit t of ops: the one call of seon.program/moves-program?, here once a commit, where every commit is seen (O(ops), 77-424
  ticks a commit of 1-11 ops on main [M]), carried on each notice. Its reader compares it and calls nothing: the fork server's :graph-moved. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map :seon.db/t :seon.db/resolved-tx] :map]}
  [st t ops] (cond-> st (program/moves-program? ops) (assoc :program-t t)))

(defn against-main
  "What branch head's change since base (ours, seon.merge/delta-changes' {key [before after]}) adds against main: :uncontracted, the
  functions it defines without a contract, and :caused, the derived problems it causes, of the keys where main now holds another value
  than the branch: one the branch holds because main holds it (a session's import of a worktree that merged main carries main's change)
  is main's (plan item 8; root's pilot). Main is read at those keys alone, O(what the branch changed), never its whole change
  since base. The done rule's measures. See docs/tasks.md#the-done-rule."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/changes :seon.merge/data-attrs] [:map [:uncontracted [:vector :qualified-symbol]] [:caused [:vector :string]]]]}
  [base head main ours data-attrs]
  (let [at-main (pm/program-of main (vec (keys ours)) data-attrs)
        mine (into {} (remove (fn [[k [_ now]]] (= now (get at-main k)))) ours)]
    {:uncontracted (vec (pm/uncontracted mine))
     :caused (vec (problems/caused base head mine))}))

(defn- written
  "What a merge of changes ours writes back, read on db (main with them merged): :files, the source
  files (namespaces' and schema resources') they touch, and :unfiled, the changed forms of
  namespaces with no file, which no write-back can write: a merge's write-back
  (docs/merging.md#a-merge-runs-what-the-change-reaches)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:map [:files [:vector :string]] [:unfiled :map]]]}
  [db ours] {:files (source/changed-files db ours) :unfiled (source/unfiled-forms db ours)})

(defn write-out-req
  "The :git write-out request (seon.fx/write-out) for branch b, read on main and head (b's committed head): the files b's change touches,
  drawn as head holds them (changed-files of its delta-changes against main, seon.doc/files, with their modes), and its pins that differ
  from main's as links, on base (main's :seon.system/source :commit), at dir, on refs/seon/candidate/<b> (never a branch; seon.fx/drop-write-out deletes it with its candidate). O(what b changed): the text
  of its changed files and its pins. See task seon.writer/a-branchs-files-are-written-out-to-a-tree."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.db/branch :string :string] :map]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.db/branch :string :string :seon.merge/changes] :map]]}
  ([main head b dir base] (write-out-req main head b dir base (pm/delta-changes main head :all)))
  ;; ours, the branch's own change since main (its merge base's diff, seon.agent/branch-changes, which the agent holds): O(what it
  ;; changed). Drawn against main's head instead it read everything main moved since the branch was cut: 8.9B ticks / 33.8 GB / 36.7 s
  ;; for a branch of 13 changed files (22 drawn, 9 of them main's own moves written back at the branch's older text), run inside the
  ;; prove proc's step, which held a switch-over's stop 3 s past its bound
  ([main head b dir base ours]
   (let [paths (source/changed-files head ours)
         mains (source/pins-of main)]
     {:dir dir :ref (str "refs/seon/candidate/" (name b)) :base base
      :files (doc/files head paths) :modes (source/modes head paths)
      :links (into (sorted-map) (remove (fn [[p c]] (= c (mains p)))) (source/pins-of head))
      :message (str "Seon candidate: " (name b) "\n")})))

(defn- refused
  "The writer refusing a request, as the one error map (:seon.writer/refused); members
  describe the refused answer."
  {:malli/schema [:=> [:cat :string :map] :seon.writer/refused]}
  [why members] (contract/error :seon.writer/refused #'seon.writer/refused why members))

(defn- merge-refused
  "Why branch head (r: {:ours its program change against main, seon.merge/delta-changes}) cannot merge into main now (session M1), or
  nil: the branch has unmerged entries
  (:seon.merge/unmerged); its program calls intern, alter-var-root, eval or load-string,
  or defines inside a function body (:seon.merge/mutation, seon.merge/mutations); it
  carries an uncontracted function (:seon.merge/uncontracted: contracts decide what is
  admitted). A changed doc merges as code does: its page is written back with the merge (seon.doc/files)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:map [:ours :seon.merge/changes]]] [:maybe :map]]}
  [main head r]
  (let [unmerged (pm/unmerged head)
        uncontracted (pm/uncontracted (:ours r))]
    (cond
      (seq unmerged)
      (contract/error :seon.merge/unmerged #'seon.writer/merge-refused
                      (str "the branch has " (count unmerged) " unmerged entr(ies)")
                      {:tx (:max-tx main) :unmerged (mapv :key unmerged)})
      (seq (pm/mutations main (:ours r)))
      (let [ms (pm/mutations main (:ours r))]
        (contract/error :seon.merge/mutation #'seon.writer/merge-refused
                        (str "the branch's program " (str/join "; " (for [m ms] (str (sedn/encode (:defines m))
                                                                                    (if (:call m) (str " calls " (:call m)) (str " defines " (:inline-def m) " inside a function body"))))))
                        {:tx (:max-tx main) :mutations ms
                         :seon.error/fix "Every definition happens once, where it is defined: vary behaviour through arguments or the environment, and define at the top level, not inside a function."}))
      (seq uncontracted)
      (contract/error :seon.merge/uncontracted #'seon.writer/merge-refused
                      (str "the branch carries " (count uncontracted) " function(s) without a contract")
                      {:tx (:max-tx main) :uncontracted uncontracted
                       :seon.error/fix "Declare each function's contract ({:malli/schema [:=> [:cat ...] ...]}) and merge again."}))))

(defn- dangling-refused
  "The refusal of a merge whose program, merged into main, leaves a reference naming nothing (seon.merge/dangling: a removed
  definition another form calls), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:maybe :map]]}
  [main merged]
  (when-let [ds (seq (pm/dangling main merged))]
    (contract/error :seon.merge/dangling #'seon.writer/dangling-refused
                    (str/join "; " (for [{:keys [ref form]} ds] (str form " calls " ref ", which the merge removes")))
                    {:tx (:max-tx main) :dangling (vec ds)})))

(defn- branch-tx!
  "The report of transacting arg-map on branch b of the store cfg names, through the one write path (seon.db/connect's, stamped with b):
  a connection opened for it and released after. Every write the writer makes on a branch it does not hold takes this. See
  docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :map :keyword [:or :map :seon.db/tx-data]] :map]}
  [cfg b arg-map]
  (let [bc (pdb/connect (assoc cfg :branch b))]
    (try (d/transact bc arg-map) (finally (d/release bc)))))

(defn- refused-entries
  "The unmerged entries for the ops of a replayed transaction the write check refused with data: one a key the ops write ([:datum e a],
  [:datum e a v] for a cardinality-many attribute, e as the branch names it), only the attributes the refusal names when it names some
  (:missing, :attribute), each with what base, the branch (ours) and main (theirs) hold there (seon.merge/held-at) and :why, the
  refusal's words. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db [:set [:tuple :int :int]] [:sequential :any] :any] [:vector :seon.merge/conflict]]}
  [main base branch remapped ops data]
  (let [named (set (concat (:missing data) (some-> (:attribute data) vector)))
        ;; an entity the merge re-identified is the branch's own id in its entry, as decide names a key
        back (into {} (map (fn [[e0 e]] [e e0])) remapped)
        many? (fn [a] (= :db.cardinality/many (get-in (:schema branch) [a :db/cardinality])))
        ks (distinct (for [[op e a v] ops :when (#{:db/add :db/retract} op) :when (or (empty? named) (named a))
                           :let [e0 (get back e e)]]
                       (if (many? a) [:datum e0 a v] [:datum e0 a])))]
    (vec (for [k ks :let [b (pm/held-at base k) o (pm/held-at branch k) t (pm/held-at main k)]]
           (cond-> {:key k :entity (pm/named branch (second k)) :why (or (:seon.error/message data) (str (:seon.error/type data)))}
             (some? b) (assoc :base b) (some? o) (assoc :ours o) (some? t) (assoc :theirs t))))))

(defn replay
  "main with the branch's source transactions (seon.merge/carry's :txs) replayed in order: each a new transaction through the write
  check (seon.db/with), carrying meta and naming its source (:seon.tx/source) and the source's instant (:seon.tx/at-ms). A transaction
  the check refuses (a fact a schema main has since tightened forbids) is left out and becomes unmerged entries (`refused-entries`),
  so one merge names every such fact and the branch settles them as it settles a conflict. {:db the value after
  the rest, :entries}. Each attribute the branch declared since base (a registered key, an imported schema entry: carry's
  :declarations, the branch's own declaration) that the value lacks is declared at the head of the first transaction, before any
  fact uses it (Datahike's own facts are no facts of the merge, seon.merge/facts). See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db [:map [:txs [:vector :map]] [:remapped [:set [:tuple :int :int]]]] :map]
                  [:map [:db :seon.host/db] [:entries [:vector :seon.merge/conflict]]]]}
  [main base branch {:keys [txs remapped declarations]} meta]
  (let [;; the branch's declarations db lacks: O(attributes the branch declared), a map lookup each
        declared (fn [db] (filterv #(not (contains? (:schema db) (:db/ident %))) declarations))]
  (reduce (fn [acc {:keys [source ops]}]
            ;; the named edge (docs/failure-points.md): the write check refuses a replayed transaction with its typed error, which
            ;; the merge answers as the branch's entries to settle
            (try (assoc acc :db (:db-after (pdb/with (:db acc) (into (declared (:db acc)) ops) nil (assoc meta :seon.tx/source source :seon.tx/at-ms (some-> ^java.util.Date (:db/txInstant (d/entity branch source)) .getTime)) true)))
                 (catch clojure.lang.ExceptionInfo e
                   (update acc :entries into (refused-entries main base branch remapped ops (ex-data e))))))
          {:db main :entries []} txs)))

(defn candidate
  "Main merged into branch head as a value, nothing written: the one merge's decision (seon.merge/decide: the branch's facts since base
  against main's at the same keys), and when clean its change carried onto main's head (seon.merge/carry, replayed through the write
  check, `replay`), its decided entries settled and its moved tasks' cites made again. {:conflicts [entry]} (a conflict, or a fact the
  replay's check refused), or {:db merged :keys ks :cite-keys :whole :settle-ops}; what merge-main-into writes, and what every reader of
  a merge takes (the judge's diff), so a merge is computed one way. O(what the branch changed). See
  docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map] :map]}
  [main base head meta]
  (let [m (pm/mapped base head main)
        r (pm/decide base head main m)
        c (when-not (:conflicts r) (pm/carry base head main m))
        {replayed :db refused :entries} (when c (replay main base head c meta))
        conflicts (or (:conflicts r) (not-empty refused))]
    (if conflicts
      {:conflicts conflicts}
      (let [;; the keys the branch touched (its carried facts): only these can differ from main's change
            ks (vec (distinct (for [{:keys [ops]} (:txs c) [o e a] ops :when (not= :db/retractEntity o)] [e a])))
            ;; a merged task's cites are derived from its text: made again here, never carried (seon.task/merged-cites-tx)
            settled (pdb/with replayed (:settle-ops r) nil meta true)
            cites (task/merged-cites-tx main (:db-after settled) (mapv (fn [[e a]] [:datum e a]) ks))
            cited (when (seq cites) (pdb/with (:db-after settled) cites nil meta true))]
        {:db (if cited (:db-after cited) (:db-after settled))
         :keys ks
         ;; the keys the cites wrote, read from their datoms (cites-tx gives entity maps), the transaction's own facts left out
         :cite-keys (vec (for [dt (:tx-data cited) :when (not= (:e dt) (:tx dt))] [(:e dt) (:a dt)]))
         ;; the entities touched whole: each a merge re-identifies (both ids), each it retracts whole, each entry it settles
         :whole (vec (distinct (concat (mapcat identity (:remapped c))
                                       (for [{:keys [ops]} (:txs c) [o e] ops :when (= :db/retractEntity o)] e)
                                       (map second (:settle-ops r)))))}))))

(defn merge-main-into
  "Main merged into branch b on main's program. Decided by seon.merge/decide (the branch's facts since the base against main's at the
  same keys, O(what the branch changed)): a conflict writes only its unmerged entries. Clean: the branch's own change since the base carried onto main's head
  (seon.merge/carry, replayed through the write check, `replay`: O(what the branch changed), never main's churn replayed), its decided
  entries settled and its moved tasks' cites made again, and one force-branch! onto b with both parents, stating its change against the
  branch head it replaces (seon.merge/change-record: what main moved for this branch, at the keys either side touched), so a reader on
  the branch wakes on what moved, never on its own facts made again. Answers {:ok :conflicts :commit :keys :t :main}, or the typed
  error of a swept merge base. A fact the write check refuses in the replay is an unmerged entry, as a conflict is. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :any :map :seon.db/branch] :map]}
  [conn cfg b]
  (let [main-b (pdb/conn-main conn) main @conn head (pdb/head conn b)
        base (pdb/branch-base conn main-b b)
        ;; nothing to merge when the branch's base is main's head (a resume with main unmoved): nothing decided
        same? (and (not (contract/error? base)) (= (d/commit-id base) (d/commit-id main)))
        cand (when-not (or same? (contract/error? base)) (candidate main base head (pdb/tx-meta b {:seon.tx/merged main-b})))]
    (cond
      (contract/error? base) base
      same? {:ok true :keys 0 :main (d/commit-id main)}
      (:conflicts cand)
      (do (branch-tx! cfg b {:tx-data (pm/unmerged-tx head (mapv #(dissoc % :entity) (:conflicts cand)) (:max-tx main)) :tx-meta (pdb/tx-meta b)})
        {:ok false :conflicts (:conflicts cand) :main (d/commit-id main)})
      :else
      (let [merged (:db cand)
            ds (pm/merge-record head merged (pm/changed-datoms base main) (into (:keys cand) (:cite-keys cand)) (:whole cand))
            cid (d/force-branch! merged b #{(d/commit-id head) (d/commit-id main)}
                                 {:expected-current-commit (d/commit-id head) :changes {:base (d/commit-id head) :datoms ds}})]
        {:ok true :commit cid :keys (count (:keys cand)) :t (:max-tx merged) :main (d/commit-id main)}))))

(defn- reprove-tests
  "R, the tests a branch b must prove again before it merges: those whose results on head (b with main merged in) are its own (their
  :seon.proof/ran-on names b) and read a key main changed since proved-on, the main b's proof read (its merge base before the merge);
  :unknown when b holds no result of its own, so its proof's reads are unknown and it proves again whole (never merged unproved).
  Main's change is read from its commits (seon.merge/changed-datoms), each datom's keys sought among the results' read keys
  (seon.db/results-moved): O(what main moved). See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any] [:or [:= :unknown] [:set :qualified-symbol]]]}
  [main head b proved-on]
  (let [own (into #{} (map :e) (d/datoms head :avet :seon.proof/ran-on b))]
    (if (or (empty? own) (contract/error? proved-on) (nil? proved-on))
      :unknown
      (let [[gone added] (pm/changed-datoms proved-on main)]
        (into #{} (comp (filter own) (keep #(:v (first (d/datoms head :eavt % :seon.proof/test)))))
              (pdb/results-moved head (concat gone added)))))))

(defn- merge-into-main
  "Branch b merged into main in ONE writer step (plan item 10): main merged into b first (merge-main-into: the candidate, built on
  main's head; a conflict or a refused fact answers its entries and nothing moves), b's candidate refused as merge-refused and
  dangling-refused judge it, else main takes the candidate's change in one commit with b's head a parent (d/merge-db), so b's merge
  base is its head. Main can't move inside the step, so a merge is never behind. proved-t is main's t the branch's proof ran on: when
  main's program moved since (:program-t, program-at) and a test whose reads main moved since b's base proved it (R, reprove-tests:
  :unknown when b holds no result of its own), b still takes main and the answer is {:reprove true :tests R}, nothing of main moved,
  which the writer asks of the test service itself (reprove!) and finishes in the step that takes its answer (r-answered), never
  answering its caller :reprove: each pass costs what moved, with no count. Answers
  [st {:merged? :tx ...}], {:merged? false :reprove true :tests R}, or a refusal; R empty merges in the step. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/branch :int] [:tuple :seon.writer/state :map]]}
  [st o b proved-t]
  (let [conn (:conn st)
        ;; the main the branch's proof read: its merge base before this merge takes main in
        proved-on (when (pdb/branch? conn b) (pdb/branch-base conn (pdb/conn-main conn) b))
        r (if (pdb/branch? conn b) (merge-main-into conn (:cfg st) b) (refused (str "no branch " b) {}))]
    (if (or (contract/error? r) (not (:ok r)))
      [st (if (contract/error? r) r {:merged? false :conflicts (:conflicts r)})]
      (let [main @conn head (pdb/head conn b)
            ;; the candidate's change against main, read once from its commit (changed-datoms): the ops main takes, and the program
            ;; view merge-refused's checks and the write-back read (delta-changes over those datoms, never a second diff)
            [gone added] (pm/changed-datoms main head)
            tw {:ours (pm/delta-changes main head :all (pm/content-datoms main head :all (into gone added)))}
            why-not (or (merge-refused main head tw) (dangling-refused main head))
            ;; R, only when main's program moved since the proof (a data move re-proves nothing, as before)
            rt (when (> (:program-t st 0) proved-t) (reprove-tests main head b proved-on))
            txe (into #{} (map :tx) (concat gone added))
            ops (-> [] (into (for [dt gone :when (not (txe (:e dt)))] [:db/retract (:e dt) (:a dt) (:v dt)]))
                    (into (for [dt added :when (not (txe (:e dt)))] [:db/add (:e dt) (:a dt) (:v dt)])))]
        (cond
          why-not [st why-not]
          ;; main's program moved since the proof: b holds main now; the tests whose reads main moved prove again on it (R), and
          ;; with none the branch merges in this step
          (or (= :unknown rt) (seq rt))
          [st {:merged? false :reprove true :tests rt :tx (:max-tx main)}]
          (empty? ops) [st {:merged? true :tx (:max-tx main) :datoms 0}]
          :else
          (let [rep (d/merge-db conn #{(d/commit-id head)} (into ops (mark-tx main)) (pdb/tx-meta (pdb/conn-main conn)))
                t (:max-tx (:db-after rep))
                tx-ops (pdb/report->tx rep)
                ;; one commit of main's, as a batch's: the flush that follows covers it (told-flusher)
                st (update (program-at st t tx-ops) :commits inc)
                _ (stored! st o tx-ops)]
            [(main-commit st o (:max-tx (:db-before rep)) t tx-ops (:db-after rep))
             (merge {:merged? true :tx t :datoms (count tx-ops)} (written (:db-after rep) (:ours tw)))]))))))

(defn- answer
  "The answer r for dest, as data on :to-api."
  {:malli/schema [:=> [:cat :seon.host/volatile [:maybe :map] :any] :nil]}
  [o dest r] (when dest (step/out! o :to-api {:dest dest :r r})))

(defn- told-flusher
  "st with its commits since c0 marked dirty and, while dirty and durable with no notice out, the flusher told what is committed
  ({:op :dirty :commits n}, once until it answers): what every step that commits does, so each commit is covered by a flush."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :int] :seon.writer/state]}
  [st o c0]
  (let [st (cond-> st (> (:commits st) c0) (assoc :dirty true))]
    (if (and (:durable? st) (:dirty st) (not (:notified st)))
      (do (step/out! o :to-flusher {:op :dirty :commits (:commits st)})
          (assoc st :notified true))
      st)))

(defn- reprove!
  "st with branch b's R (merge-into-main's {:reprove true :tests R :tx main-t}) asked of the test service through the api, as
  every asker asks (seon.step/ask: {:op :prove :branch :t :digest :names :mode :judge}, answered on the writer's :answers): R
  by name, :unknown the judge's whole plan (:names nil), at b's head, which holds main now. dest waits for the merge, never for
  :reprove. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile [:maybe :map] :seon.db/branch :map] :seon.writer/state]}
  [st o dest b r]
  (let [head (pdb/head (:conn st) b)
        [st req] (step/ask st [:writer :answers] {:dest dest :branch b :proved-t (:tx r)}
                           {:op :prove :branch b :t (:max-tx head) :digest (program/run-digest head) :mode :judge
                            :names (when (set? (:tests r)) (:tests r))})]
    (step/out! o :to-api req)
    st))

(defn- r-answered
  "st after the test service answered a merge's R (reprove!, m {:tag rid :r}): green (no :red, no :no-verdict) merges b in this
  step (merge-into-main, proved at the main R was read against, so main moved again meanwhile asks only R' of what moved since);
  a red or a no verdict (the platform's error) answers the merge's caller a refusal naming them. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :map] :seon.writer/state]}
  [st o {:keys [tag r]}]
  (let [[st v] (step/answered st tag)]
    (if-not v
      st
      (let [{:keys [dest branch proved-t]} v]
        (if (and (map? r) (not (contract/error? r)) (empty? (:red r)) (empty? (:no-verdict r)))
          (let [c0 (:commits st)
                [st r2] (merge-into-main st o branch proved-t)]
            (if (:reprove r2)
              (reprove! st o dest branch r2)
              (do (answer o dest r2) (told-flusher st o c0))))
          (do (answer o dest (if (contract/error? r)
                               r
                               (refused (str "R did not prove green on the merged branch: " (count (:red r)) " red, "
                                             (count (:no-verdict r)) " with no verdict")
                                        {:red (vec (:red r)) :no-verdict (:no-verdict r)})))
              st))))))

(defn- commit-batch
  "Group commit of root's writes to main: every queued transaction applied in arrival order (datahike.writer/apply!), main's
  content mark after them when they moved it (`mark!`), then one Datahike commit (datahike.writer/commit-pending!). A
  transaction that fails its apply is answered refused and the others commit. A commit the store refuses (its codec has no
  encoding for a value; main stays as it was, Datahike moves it only after the write) is abandoned and retried one
  transaction at a time, so only the one that carried the value is answered refused. Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile] :seon.writer/state]}
  [st o]
  (if-let [ms (seq (:pend st))]
    (let [conn (:conn st)
          before @conn
          reps (mapv (fn [m] (dw/apply! conn {:tx-data (:tx-data m) :tx-meta (pdb/tx-meta (pdb/conn-main conn))})) ms)
          ok (filter (comp map? second) (map vector ms reps))
          ops (vec (mapcat (comp pdb/report->tx second) ok))
          c (when (seq ok)
              (try (let [mk (mark! st ops before)] (assoc (dw/commit-pending! conn) :mark mk))
                   (catch Throwable e e)))
          refuse (fn [m rep] (answer o (:dest m) (or (when (:seon.error/type (ex-data rep)) (ex-data rep)) (refused (step/error-msg rep) {}))))]
      (cond
        (empty? ok)
        (do (doseq [[m rep] (map vector ms reps)] (refuse m rep)) (dissoc st :pend))
        (map? c)
        ;; one stored commit, one notice: the last report's t, every report's ops in order, the mark's after them
        (let [ops (into ops (some-> (:mark c) pdb/report->tx))
              t (:max-tx (:db c))
              st (program-at st t ops)]
          (stored! st o ops)
          (doseq [[m rep] (map vector ms reps)]
            (if (map? rep) (answer o (:dest m) {:ok true :t (:max-tx (:db-after rep)) :datoms (count (pdb/report->tx rep))}) (refuse m rep)))
          ;; what main-commit queued (a moved config's facts) is this batch's next commit
          (commit-batch (main-commit (-> st (dissoc :pend) (update :commits inc)) o (:max-tx before) t ops (:db c)) o))
        :else
        (do (dw/abandon-pending! conn)
            (if (next ms)
              (reduce (fn [st m] (commit-batch (assoc st :pend [m]) o)) (dissoc st :pend) ms)
              (do (refuse (first ms) c) (dissoc st :pend))))))
    st))

(defn- branch-held
  "A refusal naming the live agent that holds branch b on main's db (its :seon.agent/branch, its status live), or nil: a live agent
  writes its own branch, so a repair waits for it to stop. See docs/merging.md#root-repairs-a-failed-merge-from-its-repl."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :keyword] [:maybe :map]]}
  [conn main b]
  (let [db @conn
        live task/live-statuses]
    (when-let [k (first (for [d (d/datoms db :aevt :seon.agent/branch)
                              :when (= b (pdb/on-main main (:v d)))
                              :let [e (d/entity db (:e d))]
                              :when (contains? live (:seon.agent/status e))]
                          (:seon.agent/key e)))]
      (refused (str "agent " k " is live on " b ": its branch is its own to write") {:agent k :branch b}))))

(defn- other-op
  "Every request but :tx. Answers the new state."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op dest] :as m}]
  (let [conn (:conn st)
        main (pdb/conn-main conn)
        ;; every branch named in a request is root's name for it, under root's main
        m (cond-> m
            (:branch m) (update :branch #(pdb/on-main main %))
            ;; :from a branch's name, or a commit's id (a branch from a point: seon.system/back!), which no main qualifies
            (keyword? (:from m)) (update :from #(pdb/on-main main %))
            (:branches m) (update :branches #(mapv (fn [b] (pdb/on-main main b)) %)))
        exists? (fn [b] (pdb/branch? conn b))
        reply (fn [st r] (answer o dest r) st)]
    (case op
      ;; answered once the store is clean (at the end of this step, or when the flush that covers
      ;; its commits comes back): the event that ends the last pending sync (seon.check/idle)
      :clean (update st :clean-waiters (fnil conj []) dest)
      ;; an agent process committed its branch: a commit the next flush covers
      :agent-commit (-> st (assoc :dirty true) (update :commits inc))
      ;; the agent's branch (new: a pointer to main's head), then its fork: the agent opens
      ;; the branch itself and owns its writes (run-ahead, committed by the agent), and holds
      ;; the task and run it works from its first form
      ;; with :commit C (a session restored at that point of its branch, docs/storage.md#compiled-code), the branch is made
      ;; from commit C and not from main's head, and is resumed from its first form: the agent loads its program at C over main's image
      :spawn (let [{:keys [agent branch commit]} m
                   resumed? (exists? branch)
                   ;; a fork is an outside effect: what it reads from the store (its session's entries,
                   ;; seon.agent/undelivered) is synced first (docs/storage.md#durability-is-continuous-group-commit), the
                   ;; batch this op just committed included (:dirty is settled only after the drain)
                   _ (when (:durable? st) (pdb/sync! (:cfg st)))
                   gone (when (and commit (not resumed?))
                          ;; the named edge (docs/failure-points.md): datahike's branch! throws :commit-not-found before it writes anything
                          (try (d/branch! conn commit branch) nil
                               (catch clojure.lang.ExceptionInfo e
                                 (if (= :commit-not-found (:type (ex-data e))) e (throw e)))))]
               (if (or gone (and commit resumed?))
                 (reply st (refused (if gone
                                      (str "commit " commit " is gone from the store (collected): no session can be restored at it")
                                      (str "branch " branch " exists: a restore at commit " commit " makes a new branch"))
                                    {:branch branch :commit commit}))
                 (let [;; a resumed branch takes main first, on main's program (merge-main-into), so its process starts on main's
                       ;; runtime and program, as every later turn would merge it (seon.fx/runtime-for reads the branch's own source
                       ;; tree at init!); a conflict leaves its unmerged entries for the agent, a swept base answers its typed error
                       ;; and the agent runs main's program (seon.agent/init!), either way on the reply (docs/merging.md#a-branch-merges-main-in)
                       merged (when (and resumed? (not commit)) (merge-main-into conn (:cfg st) branch))
                       resumed? (boolean (or resumed? commit))]
                   (when-not resumed? (d/branch! conn main branch))
                   ;; main's commit and t, which this branch starts at and the image it forks from holds
                   (step/out! o :to-fork-server {:op :fork :agent agent :branch branch :task (:task m) :ns (:ns m) :fd (:fd m) :key (:key m)
                                                  :cfg (:cfg st) :resumed resumed?
                                                  :commit (d/commit-id @conn) :t (:max-tx @conn)})
                   (reply st (cond-> {:ok true :t (:max-tx @conn) :resumed resumed?}
                               merged (assoc :merged (select-keys merged [:ok :conflicts :commit :keys :main :seon.error/type :seon.error/message])))))))
      ;; a session's files as a branch of main, keyed by their tree (seon.system/prove-tree!): made once, from main's head, with
      ;; the catch-up of the checkout written on it; a second asker of one tree finds it made (:exists), never makes it again
      :branch-for-tree (reply st (if (exists? (:branch m))
                                   {:ok true :exists true}
                                   (do (d/branch! conn main (:branch m))
                                       (when (seq (:tx m)) (branch-tx! (:cfg st) (:branch m) (vec (:tx m))))
                                       {:ok true :exists false})))
      ;; a tree's branch dropped once no proof of it runs (seon.system/prove-tree!; the owner's ruling, 2026-11-11): only a tree-<id>
      ;; branch, never another; one already gone answers so
      ;; a branch's repair (docs/merging.md#root-repairs-a-failed-merge-from-its-repl): a write on branch b through the one path, stamped with
      ;; b, or b's head moved to commit :to (its db, :to its one parent) while b is still at :expected; refused while a live agent
      ;; holds b, since that agent is its branch's one writer
      :tx (reply st (or (branch-held conn main (:branch m))
                        (let [rep (branch-tx! (:cfg st) (:branch m) {:tx-data (vec (:tx-data m))})]
                          {:ok true :t (:max-tx (:db-after rep)) :datoms (count (:tx-data rep)) :commit (d/commit-id (:db-after rep))})))
      :reset-branch (reply st (or (when-not (exists? (:branch m)) (refused (str "no branch " (:branch m)) {}))
                                  (branch-held conn main (:branch m))
                                  (if-let [to (d/commit-as-db conn (:to m))]
                                    {:ok true :commit (d/force-branch! to (:branch m) #{(:to m)} {:expected-current-commit (:expected m)}) :from (:expected m)}
                                    (refused (str "no commit " (:to m)) {:to (:to m)}))))
      ;; a branch dropped, any but main (a tree's once no proof of it runs, seon.system/tree-leave!; one a merge left inconsistent, by root's
      ;; go): its head goes, and the next collection (seon.db/collect!) sweeps every commit only it reached, so a drop is never undone;
      ;; refused while a live agent holds it; one already gone answers so
      :drop-branch (reply st (cond (= main (:branch m)) (refused "main is never dropped" {:branch (:branch m)})
                                   (not (exists? (:branch m))) {:ok true :dropped false}
                                   :else (or (branch-held conn main (:branch m))
                                             (do (d/delete-branch! conn (:branch m)) {:ok true :dropped true}))))
      :clone-branch (do (d/branch! conn (:from m) (:branch m))
                        (reply st {:ok true :t (:max-tx (pdb/head conn (:branch m))) :cfg (:cfg st)}))
      ;; R non-empty is proved by the test service and the merge finishes in the step that takes its answer (r-answered): the
      ;; caller is answered the merge, never :reprove
      :merge (let [[st r] (merge-into-main st o (:branch m) (:proved-t m))]
               (if (:reprove r) (reprove! st o dest (:branch m) r) (reply st r)))
      ;; main merged into branch b on main's program (merge-main-into); its agent waits meanwhile
      :merge-main (reply st (if-not (exists? (:branch m))
                              (refused (str "no branch " (:branch m)) {})
                              (merge-main-into conn (:cfg st) (:branch m))))
      ;; a branch read for the dashboard and the done rule: its own change and the one merge's decision against main
      :branch-diff (reply st (if (exists? (:branch m))
                               (let [b (:branch m) head (pdb/head conn b) base (pdb/branch-base conn main b)
                                     _ (when (contract/error? base) (throw (ex-info (:seon.error/message base) base)))
                                     data-attrs (:data-attrs m #{})
                                     ;; the branch's own change since its base (seon.merge/delta-changes) and the one merge's decision against
                                     ;; main (seon.merge/decide): both O(what the branch changed), never main's churn since the base
                                     ours (pm/delta-changes base head data-attrs)
                                     adds (against-main base head @conn ours data-attrs)]
                                 {:ok true :db head :base base :base-t (:max-tx base)
                                  :ours (vec (sort-by str (keys ours)))
                                  :conflicts (count (:conflicts (pm/decide base head @conn)))
                                  :unmerged (mapv :key (pm/unmerged head))
                                  :uncontracted (:uncontracted adds) :caused (:caused adds)
                                  :vars (into (sorted-set) (keep (fn [[k s]] (when (= :var k) s))) (keys ours))})
                               (refused (str "no branch " (:branch m)) {})))
      ;; root's read of a branch (or main): its committed value from the store; :base is the
      ;; db at the branch's merge base, :base-t main's t there (what of main it holds). A
      ;; branch named as a task or run names it (:task-calc-add) is read as the store
      ;; names it under this main (seon.db/on-main)
      :branch-db (let [b (pdb/on-main main (:branch m))]
                   (reply st (cond
                               (= main b) {:ok true :db @conn}
                               (exists? b) (let [base (pdb/branch-base conn main b)]
                                             (if (contract/error? base) base {:ok true :db (pdb/head conn b) :base base :base-t (:max-tx base)}))
                               :else (refused (str "no branch " b) {}))))
      ;; root's collection of the store (seon.db/collect!), asked at quiescence: swept when a
      ;; branch the last one saw is gone, and recorded on main through the batch; then root's
      ;; heap collected in full, which returns its free pages past the reserve to the OS
      ;; (docs/machine.md#what-a-process-owns-in-memory)
      :collect (let [r (pdb/collect! conn (cfg/value :seon.config.store/sole-writer?))]
                 (reply (if (:skipped r) st (commit-batch (update st :pend (fnil conj []) {:tx-data [{:seon.system/id :root :seon.system/collected r}]}) o))
                        (do (lib/non-static-live!) {:ok true :collected r})))
      (reply st (refused (str "unknown op " op) {})))))

(defn- writer-one
  "One request into the batch: root's :tx on main joins the batch; any other op first
  commits the batch (it reads committed heads), then runs. Only the op itself is caught (an
  interop edge, Datahike, refused: its answer is the error, as data): the batch's commit is
  outside the catch, so a refused op never hands back a state from before a commit Datahike made."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op] :as m}]
  ;; a :tx on main joins the batch; one naming another branch is a branch repair, run alone (other-op's :tx)
  (if (and (= op :tx) (contains? #{nil (pdb/conn-main (:conn st))} (some->> (:branch m) (pdb/on-main (pdb/conn-main (:conn st))))))
    ;; the batch's transact checks it (seon.db/transact-checked): a refusal is answered to its requester alone
    (update st :pend (fnil conj []) m)
    (let [st (commit-batch st o)]
      (try (other-op st o m)
           (catch Throwable e
             ;; with the store's map use: a full map (MDB_MAP_FULL) shows itself against its size
             (answer o (:dest m) (refused (step/error-msg e) (cond-> {} (pdb/map-use (:cfg st)) (assoc :store (pdb/map-use (:cfg st))))))
             (dissoc st :pend))))))

(defn- drain {:malli/schema [:=> [:cat :seon.host/chan :seon.writer/request] [:vector :seon.writer/request]]}
  [ch m]
  (loop [acc [m] k 1]
    (if (< k (cfg/value :seon.config.root/writer-batch))
      (if-let [y (a/poll! ch)] (recur (conj acc y) (inc k)) acc)
      acc)))

(defn writer-proc
  "The writer: root's own writes to main (group commit), d/branch!, and every merge into
  main. Each agent process writes its own branch (run-ahead, seon.agent) and tells it
  :agent-commit, so the one flush covers it. In :in (every write request, from the :api
  and the agent procs). Out :to-fork-server (main's
  commits and forks, FIFO), :to-api (answers {:dest :r}, after the batch's flush, and
  {:op :writer :tx-count :p50-ms :commits}), :main (main's commits), :events.
  A closed :in is the end of the flow: everything taken is committed and flushed, then
  {:op :drained} goes out. Continuous group commit (DECISIONS, Durability is continuous group
  commit): answers
  leave when the store has committed their batch (LMDB's commit, unsynced: every process reads it); a dirty store with no notice out tells the
  flusher {:op :dirty :commits n}, and its {:op :flushed :commits n} makes the store clean
  when n is still the commit count, else the flusher is told again. A :flush? request (an
  outside effect follows) is synced before its answer; a :clean request is answered once the
  store is clean, its flushes' notices before it."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "main's connection" :cfg "the store config" :to-writer "its in-port channel"}
       ;; :to-writer is bound, not a conn: the writer drains it whole for one group commit (drain), which a Flow channel never allows
       :in-params #{:to-writer}
       ;; the api's answers to the writer's own requests (a merge's R, proved by the test service: reprove!), {:tag rid :r}
       :ins {:answers "the api's answers to the writer's requests"}
       :outs {:to-fork-server "commits and forks, FIFO" :to-api "answers {:dest :r}" :main "main's commits"
              :to-flusher "the store became dirty"}
       ;; its own thread: a transaction is CPU and store work the fiber scheduler cannot see
       :workload :mixed
       :ping-map-fn (fn [st] {:tx-count (:tx-count st) :commits (:commits st) :flushes (:flushes st 0) :dirty (boolean (:dirty st)) :main-t (:max-tx @(:conn st))})})
  ([args]
   ;; main's content mark moves at the writer's start: what a boot wrote before it ran (a seed, a catch-up) bears no mark of
   ;; its own, so no branch's base shares the mark main carries from here (docs/storage.md#mains-content-mark)
   (d/transact (:conn args) {:tx-data (mark-tx @(:conn args)) :tx-meta (pdb/tx-meta (pdb/conn-main (:conn args)))})
   ;; :program-t seeded at main's t: a root booted onto main proves it once (seon.prove's :main), never waits for the next program move
   ;; :program-db, the db this process's program was loaded from (boot loaded main's): each later load is the diff from it (main-commit)
   (assoc args :tx-count 0 :commits 0 :batches 0 :rid 0 :pending {} :program-t (:max-tx @(:conn args)) :program-db @(:conn args)
          ::flow/in-ports {:in (:to-writer args)} ::flow/out-ports {:events (:events args)}))
  ([st tr]
   st)
  ([st in m]
   (cond
     ;; the test service's answer to a merge's R (reprove!): merged on green, refused on a red or no verdict
     (= :answers in) (let [o (volatile! {}) st (r-answered st o m)] [st @o])
     (nil? m)
     ;; the in-port closed: what was taken is committed; flush it, then done flows on
     (do (when (and (:durable? st) (:dirty st)) (pdb/sync! (:cfg st)))
         [(assoc st :dirty false) {:to-api [{:op :drained :tx-count (:tx-count st)}]}])
     :else
     (let [ms (drain (:to-writer st) m)
           o (volatile! {})
           c0 (:commits st)
           t0 (profile/now-ns)
           ;; the flusher's answers: the commit count its sync covered
           covered (some->> (seq (keep #(when (= :flushed (:op %)) (:commits %)) ms)) (apply max))
           reqs (remove #(= :flushed (:op %)) ms)
           st (commit-batch (reduce #(writer-one %1 o %2) st reqs) o)
           commits (- (:commits st) c0)
           ;; root's commits, an agent's (:agent-commit) or a merge's: dirty before :flush? reads it
           st (cond-> st (pos? commits) (assoc :dirty true))
           f0 (:flushes st 0)
           ;; now for a write an outside effect follows (:flush?), inline before its answer
           flush-now? (and (:durable? st) (:dirty st) (some :flush? reqs))
           _ (when flush-now? (pdb/sync! (:cfg st)))
           st (cond-> st
                flush-now? (-> (assoc :dirty false) (update :flushes (fnil inc 0)))
                covered (assoc :notified false)
                (and covered (:dirty st) (= covered (:commits st))) (-> (assoc :dirty false) (update :flushes (fnil inc 0))))
           ;; still dirty and no notice out: the flusher is told what is committed so far
           st (told-flusher st o (:commits st))
           n-tx (count (filter #(= :tx (:op %)) reqs))
           st (-> st (update :tx-count + n-tx) (update :batches inc))]
       ;; what changed, to root's view: a batch, or a flush (its count is shown, never pinged)
       (when (or (seq reqs) (not= f0 (:flushes st 0)))
         (step/out! o :to-api {:op :writer :tx-count (:tx-count st) :batch-ms (profile/ms-since t0) :n-tx n-tx :commits commits
                               :flushes (:flushes st 0)}))
       ;; a clean store answers who asked (after the flush's notice above, so it reaches the api
       ;; first); a store with no durability has nothing to sync
       (if (and (seq (:clean-waiters st)) (not (and (:durable? st) (:dirty st))))
         (do (doseq [d (:clean-waiters st)] (answer o d {:ok true :commits (:commits st) :flushes (:flushes st 0) :durable? (boolean (:durable? st))}))
             [(dissoc st :clean-waiters) @o])
         [st @o])))))

(defn flusher-proc
  "The group flush: parked until the writer says the store is dirty ({:op :dirty :commits
  n}), it syncs at once (seon.db/sync! on the store config; commits during the sync share
  the next one) and answers the writer {:op :flushed :commits n}, the commits it covered.
  An idle system never syncs."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:to-writer "the writer's in-port" :cfg "the store config"}
       :ins {:in "dirty notices from the writer"} :outs {}
       ;; its own thread: the env sync is a blocking foreign call
       :workload :mixed
       :ping-map-fn (fn [st] {:flushes (:flushes st 0)})})
  ([args] (assoc args :flushes 0 ::flow/out-ports {:to-writer (:to-writer args)}))
  ([st _] st)
  ([st _ m]
   (if (nil? m)
     [st nil]
     (do (pdb/sync! (:cfg st))
         [(update st :flushes inc) {:to-writer [{:op :flushed :commits (:commits m)}]}]))))
