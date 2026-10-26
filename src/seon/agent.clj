(ns seon.agent
  "An agent: a process forked from the fork server, holding its branch's db value
  (inherited from main at the branch point, then advanced by its own tx-data with
  d/db-with) and running its REPL over its socket to root (seon.repl). The live value of
  each form stays here under its handle.

  The agent is single-threaded and reads and writes its socket itself (seon.sock frames,
  the protocol of root's jolt.async.remote end), never through remote-channel pumps. That
  is physics, not style: an agent forks (its test children, clone!), and a process that
  forks must never have started Chez's parallel-collector sweeper threads, which start at
  the first collection that finds a second thread; a child of such a process hangs in its
  first multi-threaded collection (session RC1). Adding pump or worker threads to an agent
  breaks its forks; docs/failure-points.md, "Physics". Agent code calls the functions of my.db and my.agent,
  referred into its namespace. From proto/flow f2-final proto.agent (session F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.error :as err] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.merge :as pm] [seon.config :as cfg] [seon.vector :as vector] [seon.lib :as lib] [seon.print :as print] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [seon.program :as program] [seon.proofs :as proofs] [seon.prove] [seon.fx :as fx] [seon.source :as source] [seon.task :as task] [seon.intr :as intr] [clojure.repl]
            [malli.registry :as mr]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :task :run (the task and run root forked it for, or nil) :fd :conn :db :base (main at the branch's merge base: as forked, then each merge-main!) :results :n :pending :tx-seq :mode :ns :home :mismatch
;; :embedded-from (the db embed-written! last read the branch's writes up to)
;; :unembeddable (the entities whose text llama.cpp could not encode, as vectors-proc keeps them)
;; :base-graph (test-plan's reach graph of its base, keyed by the base value, from the fork server's :graph of main: base-graph)
;; :index (seon.turn/index!: the reconciler's index of :db, the db it was observed for, advanced each turn by what differs)
;; :schema-registry (seon.repl: the branch's schema registry, by its schema changes)
;; :tx (the last transaction transact! applied, {:db-after :tx-data}: seon.ctx patches @ctx from it)
;; :eval (the id of the eval running, "e5", while seon.repl/eval-string runs it: what transact! names in its tx-meta)
(defonce state (atom nil))


;; ---- the socket to root: values in frames (root's end is a jolt.async.remote end) ------
;; This side reads and writes its socket itself, on its one thread, with no pumps: an agent
;; forks (its tests, a clone), and a process that forks must never have started Chez's
;; parallel-collector sweeper threads (session RC1: a child of such a process hangs in its
;; first multi-threaded collection).

(defn send!
  "Send a message {:op k ...} to root, one frame; :dead when root is gone."
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] [:or [:= true] [:= :dead]]]}
  [m] (if (= :epipe (sock/send! (:fd @state) m)) :dead true))

(defn- recv-raw!
  "The next message from root ({:op k ...}), or :dead at EOF or root's end frame."
  {:malli/schema [:=> [:cat] [:or [:= :dead] :seon.wire/seon-msg]]}
  []
  (let [m (sock/recv! (:conn @state))]
    (if (= m :eof) :dead m)))

(declare commit!)

(defn next-msg!
  "The next message for the loop. Idle with applied writes (no message waiting): they are
  committed first, so a branch is committed whenever its agent waits."
  {:malli/schema [:=> [:cat] [:or [:= :dead] :seon.wire/seon-msg]]}
  []
  (if-let [m (peek (:pending @state))]
    (do (swap! state update :pending pop) m)
    (if (:dirty? @state)
      (let [m (sock/next-frame! (:conn @state))]
        (case m
          :none (do (commit!) (recv-raw!))
          :eof :dead
          m))
      (recv-raw!))))

;; ---- the agent's own API ------------------------------------------------------

(defn db {:malli/schema [:=> [:cat] :seon.host/db]}
  [] (:db @state))

(declare handle!)

(defn- arrived!
  "Take the message m from root if it is a delivery ({:op :message}): into the inbox, and root
  hears it arrived; answers whether it was one. Wherever the agent reads its socket (the loop,
  a wait for an answer, a read of its messages), a delivery lands in the inbox at once."
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] :boolean]}
  [m]
  (if (= :message (:op m))
    (do (doseq [r (handle! m)] (send! r)) true)
    false))

(defn inbox
  "The messages delivered to this agent and not yet taken ({:from :body}), left in the inbox:
  root's requests (e.g. :catch-up, \"main moved, merge main\") and other agents' sends. Words to
  its session's runs (the owner's, root's at a stage, a verdict) are entries on main, which its
  turn takes (seon.turn/take!), never the inbox. What root sent
  before this call is read off the socket first (without waiting); any other request read
  waits in :pending for the loop."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (loop []
    (let [m (sock/next-frame! (:conn @state))]
      (when-not (#{:none :eof} m)
        (when-not (arrived! m) (swap! state update :pending conj m))
        (recur))))
  (:inbox @state))

(defn- session-branch
  "The branch main names this agent's session by: its run's :seon.run/branch, which the store
  qualifies on a main that is not :db (seon.db/on-main), so (:branch @state) is not it. nil
  with no run: then no entry is to it."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.run/branch]]}
  [main] (some->> (:run @state) (vector :seon.run/id) (d/entity main) :seon.run/branch))

(defn untaken-entries
  "The entries main holds to the runs of this agent's session (the runs on its branch, as main
  names them) that its branch does not hold yet, in main's order: each {:id :kind :from :to :body}.
  Delivery is the fact, read here, not the frame root pushes to a live process: a session whose
  process is gone (parked in an earlier boot, a lost wake, a red verdict on a run whose process
  ended) is spawned again, and its run takes them from main (design one-derivation 4.1 section 5;
  the frame alone left such a run with nothing, parked short of main's count, and spawned again).
  O(the entries to the session's runs)."
  {:malli/schema [:=> [:cat] [:vector [:map [:id :string] [:kind :seon.message/kind] [:from :string] [:to :seon.run/id] [:body :string]]]]}
  []
  (let [main (pdb/head (:store-conn @state) (pdb/main-branch (:cfg @state)))
        b (db)
        br (session-branch main)]
    (->> (when br (d/q '[:find ?tx ?id ?kind ?from ?rid ?body :in $ ?br
                          :where [?r :seon.run/branch ?br] [?r :seon.run/id ?rid]
                          [?m :seon.message/to ?r ?tx] [?m :seon.message/id ?id] [?m :seon.message/from ?from] [?m :seon.message/body ?body]
                          [(get-else $ ?m :seon.message/kind :message) ?kind]]
                        main br))
         (remove (fn [[_ id]] (d/entity b [:seon.message/id id])))
         (sort-by first)
         (mapv (fn [[_ id kind from rid body]] {:id id :kind kind :from from :to rid :body body})))))

(def taken-attributes
  "The attributes of the runs of an agent's session that root writes on main and its turn copies
  onto its branch (seon.turn/take!), so its ctx holds them: those whose schema says
  {:seon.db/taken true}, read here once. Its outcome and reason, never its status: a branch
  holds its run by reference (the agent's own record), and a status there says the branch holds
  the whole run (seon.task/run, seon.task/running read it so)."
  (into #{} (filter #(:seon.db/taken (contract/properties %))) (keys contract/forms)))

(defn untaken-facts
  "The transaction that copies onto this branch what main holds and its branch does not, of its
  own entities: the taken attributes (taken-attributes) of the runs on its branch that it
  holds, and each eval of its own its agent's :seon.agent/opened names, as that eval's
  :seon.eval/opened. take! is the one way outside facts reach the branch, and so its ctx (a
  change the agent's context and page show goes through the data its context reads). Root is
  the one writer of these: the agent writes its run's end as :seon.end/*, so main's value is
  never older than the branch's; a value main retracts stays on the branch. O(the session's
  runs and the opened evals on main)."
  {:malli/schema [:=> [:cat] :seon.db/tx-data]}
  []
  (let [main (pdb/head (:store-conn @state) (pdb/main-branch (:cfg @state)))
        b (db)
        pattern (into [:seon.run/id] taken-attributes)
        br (session-branch main)]
    (vec (concat
           (for [m (when br (d/q '[:find [(pull ?r pattern) ...] :in $ pattern ?br :where [?r :seon.run/branch ?br]] main pattern br))
                 :let [held (d/entity b [:seon.run/id (:seon.run/id m)])
                       new (into {} (remove (fn [[k v]] (= v (get held k)))) m)]
                 :when (and held (seq (dissoc new :seon.run/id)))]
             (assoc new :seon.run/id (:seon.run/id m)))
           (for [id (:seon.agent/opened (d/entity main [:seon.agent/key (:key @state)]))
                 :let [e (d/entity b [:seon.eval/id id])]
                 :when (and e (not (:seon.eval/opened e)))]
             {:seon.eval/id id :seon.eval/opened true})))))

(defn entries-tx
  "The transaction that writes entries (untaken-entries) on this branch: the same facts root
  wrote on main, which their reader takes by writing them."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.db/tx-data]}
  [entries] (into [] (mapcat (fn [{:keys [id kind from to body]}] (task/message-tx id kind from to body))) entries))

(defn taken!
  "The first n messages of the inbox leave it: their reader recorded them (seon.turn/basis!
  takes them once their facts are committed on this branch). nil."
  {:malli/schema [:=> [:cat nat-int?] :nil]}
  [n] (swap! state update :inbox #(vec (drop n %))) nil)
(defn send-message
  "Send body to the agents tos (ids); root delivers it by id. nil."
  {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (when (= :dead (send! {:op :send :to (vec tos) :body body})) (lib/c-exit 0)) nil)

(defn- await-op!
  "Wait for {:op op :k k} from root; anything else waits in :pending for the loop."
  {:malli/schema [:=> [:cat :keyword :int] :seon.wire/seon-msg]}
  [op k]
  (loop []
    (let [m (recv-raw!)]
      (cond
        (= m :dead) (lib/c-exit 0)
        (and (= op (:op m)) (= k (:k m))) m
        (arrived! m) (recur)
        :else (do (swap! state update :pending conj m) (recur))))))


(defn mem-mb
  "This process's memory as macOS charges it, read now (lib/real-mb: one task_info call): on the
  events that report the agent's state (its hello, each commit, a :tests reply, :stats),
  never per evaluation and never kept for a time."
  {:malli/schema [:=> [:cat] :seon.lib/mb]}
  [] (lib/r2 (lib/real-mb)))

(defn commit!
  "Commit the writes this agent applied (run-ahead) to its branch, once, through its own
  store handle, and tell root (its flush makes the commit durable). nil when nothing waits."
  {:malli/schema [:=> [:cat] :any]}
  []
  (when-let [c (dw/commit-pending! (:store-conn @state))]
    (swap! state #(-> % (assoc :db (:db c)) (dissoc :dirty? :tx)))
    ;; the eval errors folded since the last commit ride it (seon.error/note): root counts
    ;; them across agents
    (let [errs (:errors @state)]
      (when errs (swap! state dissoc :errors))
      (send! (cond-> {:op :committed :t (:max-tx (:db c)) :txs (:tx-count c) :mem-mb (mem-mb)} errs (assoc :errors (vec (vals errs))))))))

(defn transact!
  "Write tx-data to this agent's branch: applied at once to the value ahead of the
  committed one (its answer does not wait for a commit), committed when the agent is idle
  or at a turn's end, never because time passed (owner: the agent-owned run-ahead
  writer). Answers {:t :datoms :tempids}. Inside a test (a fork of the agent) it applies to
  the fork's db only. A refused transaction throws its message into the caller's eval."
  {:malli/schema [:=> [:cat :seon.db/tx-data] :seon.agent/tx-result]}
  [tx-data]
  (if (= :test (:mode @state))
    (let [r (d/with (db) tx-data)]
      (swap! state assoc :db (:db-after r) :tx (select-keys r [:db-after :tx-data]))
      {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r)) :local true})
    (let [r (dw/apply! (:store-conn @state) {:tx-data tx-data :tx-meta (pdb/tx-meta (:branch @state) (cond-> {} (:eval @state) (assoc :seon.tx/eval (:eval @state))))})]
      (when-not (map? r) (throw (ex-info (str "transact refused: " (ex-message r)) {:writer-error (ex-message r)})))
      ;; :tx, the transaction that moved :db: what seon.ctx patches @ctx from
      (swap! state assoc :db (:db-after r) :tx (select-keys r [:db-after :tx-data]) :dirty? true)
      (cond-> {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r))}
        (seq (:tempids r)) (assoc :tempids (into {} (filter (comp string? key)) (:tempids r)))))))

;; ---- effects (seon.fx, worktree session FX1): root's owner procs, through the agent's proc ---

(defn messages
  "The words to this agent not yet taken, taken: the entries main holds to its session's runs
  (untaken-entries), written on its branch as a turn takes them (seon.turn/take!), then the
  inbox's sends (inbox). Its reader is the code that read them."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [es (untaken-entries)]
    (when (seq es) (transact! (entries-tx es)) (commit!))
    (inbox)
    (let [[old _] (swap-vals! state assoc :inbox [])] (into es (:inbox old)))))

(def ^:dynamic *fx-stub*
  "Inside a test (a fork of this agent) no effect leaves the process: a test that needs
  one binds this to a fn of [kind req] answering the outcome, an injected value."
  nil)

(def ^:private model-cost-attrs
  "A model call's cost members (seon.model's completion, or its failure) and their facts."
  {:prompt-tokens :seon.fx/prompt-tokens :completion-tokens :seon.fx/completion-tokens
   :reasoning-tokens :seon.fx/reasoning-tokens :cache-hit-tokens :seon.fx/cache-hit-tokens})

(defn- fx-record!
  "The outcome joins this eval request's recording transaction (the asker records): its
  status, latency and queue wait, its error's type, and a model call's tokens (prompt,
  completion, reasoning, cache hits) when the provider reported them. The effect k is no
  longer pending (:fx-pending, its kind by key). Answers its kind."
  {:malli/schema [:=> [:cat :int :map] [:maybe :keyword]]}
  [k r]
  (let [c (or (:value r) r)
        kind (get-in @state [:fx-pending k])]
    (swap! state update :fx-pending dissoc k)
    (swap! state assoc-in [:defs [:fx k]]
           [(cond-> (into {:seon.fx/id (str (name (:id @state)) "-" (lib/c-getpid) "-fx" k) :seon.fx/kind kind
                           :seon.fx/status (if (contract/error? r) :error :ok)
                           :seon.fx/us (long (:seon.fx/us r 0)) :seon.fx/queue-us (long (:seon.fx/queue-us r 0))}
                          (for [[f a] model-cost-attrs :let [v (when (map? c) (get c f))] :when (int? v)] [a (long v)]))
              (:cost r) (assoc :seon.fx/cost (long (:cost r)))
              (contract/error? r) (assoc :seon.fx/error (str (:seon.error/type r) ": " (:seon.error/message r))
                                          :seon.fx/error-type (:seon.error/type r)))])
    kind))

(defn- fx-test
  "An effect asked inside a test fork: the stub's outcome, or the declared error."
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [kind req]
  (if *fx-stub*
    (*fx-stub* kind req)
    (contract/error :seon.fx/in-test #'seon.agent/fx-test "an effect inside a test does not leave the test's process: bind seon.agent/*fx-stub*"
                    {:seon.fx/kind kind})))

(defn fx-send!
  "The effect kind with req, started: answers its key k at once. The outcome arrives as a
  message {:from :fx :k k :kind kind :body outcome} ((messages)), recorded then."
  {:malli/schema [:=> [:cat :keyword :map :map] [:or :int :map]]}
  [kind req opts]
  (if (= :test (:mode @state))
    (fx-test kind req)
    (let [k (:tx-seq (swap! state update :tx-seq inc))]
      (when (= :dead (send! (merge opts {:op :fx :k k :kind kind :req req}))) (lib/c-exit 0))
      (swap! state update :fx-pending (fnil assoc {}) k kind)
      k)))

(defn fx!
  "The effect kind with req, through root's owner proc, answered here: its outcome
  {:value ...} or its declared error. Recorded with this eval request. No lock: an
  interrupt may end the wait; the outcome then arrives later as an async one does."
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [kind req opts]
  (if (= :test (:mode @state))
    (fx-test kind req)
    (let [k (fx-send! kind req opts)
          r (dissoc (await-op! :fx-reply k) :op :k)]
      (fx-record! k r)
      r)))

;; ---- the branch's own vectors (docs/vector.md#what-is-embedded) ----------------------------

(defn embed-written!
  "The texts this branch wrote since the last call (since its fork, at the first: after
  my.branch/merge-main!, main's texts merged in too, which carry no vector) and its task's plan,
  those whose vector is due for model (seon.vector/due over seon.vector/written), embedded by
  root's :embed effect in one request (the first n of them; 0 asks nothing) and their vectors
  written on the branch: what seon.vector/near reads here follows the agent's own writes, and
  the model stays in root. n is :seon.config.vector/texts and model the one it names unless
  given. Nothing due asks nothing. A failed embedding is the effect's recorded error and the
  texts stay due, read again from the same db next call, as while more are due than one request
  takes; texts llama.cpp could not encode are kept out of later requests (:unembeddable, as
  seon.vector/vectors-proc keeps them), so they never hold back the rest. Answers the texts
  embedded, or the error."
  {:malli/schema [:function [:=> [:cat] [:or nat-int? :map]] [:=> [:cat nat-int? :string] [:or nat-int? :map]]]}
  ([] (let [n (cfg/value :seon.config.vector/texts)]
        (if (pos? n) (embed-written! n (vector/model-id (cfg/value :seon.config.vector/model))) 0)))
  ([n model]
   (let [head (db)
         plan (some->> (:task @state) (pdb/eid head :seon.task/id) (#(d/datoms head :eavt % :seon.task/plan)))
         due (remove (comp (get @state :unembeddable #{}) first)
                     (vector/due head model (distinct (into (vector/written (get @state :embedded-from (:base @state)) head) plan))))
         ask (take n due)]
     (if (empty? ask)
       (do (when (empty? due) (swap! state assoc :embedded-from head)) 0)
       (let [r (fx! :embed {:texts (mapv second ask)} {})]
         (cond
           (= :seon.vector/encode-failed (:seon.error/type r)) (do (swap! state update :unembeddable (fnil into #{}) (map first ask)) r)
           (contract/error? r) r
           :else (let [{:keys [model vectors tokens]} (:value r)
                       _ (transact! (vector/tx-data (map first ask) (map peek ask) vectors tokens model))]
                   (when (= (count ask) (count due)) (swap! state assoc :embedded-from (db)))
                   (count ask))))))))

;; ---- merging main into this branch (seon.merge; session M1) -------------------------------

(defn merge-main!
  "git merge main into this agent's branch, in this process (it owns the branch's writes):
  main's changes since the merge base that do not conflict apply in one merge commit with
  main as parent, each conflict stays on the branch as an unmerged entry, and the changed
  definitions load here. The task content main carried since the base (seon.task/carry-tx,
  the other way: another session's design, root's edit) comes in the same commit, since the
  base moves to main's head and a member main changed would otherwise read, at the run's end,
  as this branch reverting it (live finding F3); one both changed keeps the branch's and is
  answered in :task-conflicts. data-attrs: the data that merges beside the program. Answers
  {:ok :applied :conflicts :task-conflicts :t}."
  {:malli/schema [:=> [:cat :seon.merge/data-attrs] [:map [:ok :boolean]]]}
  [data-attrs]
  (commit!)
  (let [conn (:store-conn @state)
        main-b (pdb/main-branch (:cfg @state))
        main (pdb/head conn main-b)
        head @conn
        r (pm/delta-three-way (:base @state) head main data-attrs)]
    (if (empty? (:theirs r))
      {:ok true :applied [] :conflicts [] :t (:max-tx head)}
      (let [carried (task/carry-tx (:base @state) main head)
            rep (d/merge-db conn #{main-b} (-> (pm/merge-tx head (:base @state) main (:apply r) data-attrs)
                                               (into (pm/unmerged-tx head (:conflicts r) (:max-tx main)))
                                               (into (:tx carried))
                                               ;; the catch-up itself, a fact of the agent's record (its :catch-ups)
                                               (conj {:seon.catch-up/id (str (name (:branch @state)) "@" (:max-tx main))
                                                      :seon.catch-up/agent {:seon.agent/key (:key @state)}
                                                      :seon.catch-up/from main-b :seon.catch-up/from-t (:max-tx main)
                                                      :seon.catch-up/applied (vec (:apply r)) :seon.catch-up/conflicts (vec (:conflicts r))}))
                             (pdb/tx-meta (:branch @state) {:seon.tx/merged main-b}))
            ops (pdb/report->tx rep)
            errs (program/load-changes! (:db-after rep) ops)]
        ;; the merge base is now main's head: what main changed is not this branch's change
        (swap! state assoc :db @conn :base (pdb/branch-base conn main-b (:branch @state)))
        (send! {:op :committed :t (:max-tx @conn) :txs 1})
        (cond-> {:ok true :applied (:apply r) :conflicts (:conflicts r) :t (:max-tx @conn) :datoms (count ops)}
          (seq (:conflicts carried)) (assoc :task-conflicts (:conflicts carried))
          (seq errs) (assoc :load-errors errs))))))

;; ---- evaluation -----------------------------------------------------------------


(defn- unresolved-message
  "An unresolved symbol's message without the \"(did you mean ...)\" suffix (the suggestions are
  the error's fix). Its words are copied from `jolt.nrepl/err->msg`'s cause (jolt-core/jolt/nrepl.clj
  at the Jolt pin, reference-code/jolt e0351af8), which builds it inline from the same ex-data and
  cannot be called alone; a pin bump that rewords it is a wording change here."
  {:malli/schema [:=> [:cat :string] :string]}
  [sym] (str "Unable to resolve symbol: " sym " in this context"))

(defn error-map
  "An exception caught at one of the agent's entrant boundaries (operation: the REPL's
  seon.repl/eval-code by default) as the one error map: its ex-data when that already is
  one (a contract's error names its operation, member and problems), else the
  :seon.agent/eval-error variant, with the exception's class, its ex-data's diagnostic keys
  (Jolt's :jolt.error/* : line, column, symbol, ns, kind, suggestions; and
  :clojure.error/*) as keys, the suggestions as the fix, any other ex-data as :data, and its cause chain (each cause's class
  and message)."
  {:malli/schema [:function [:=> [:cat :seon.host/throwable] :seon.error/base]
                  [:=> [:cat :seon.host/throwable :qualified-symbol] :seon.error/base]]}
  ([e] (error-map e 'seon.repl/eval-code))
  ([^Throwable e op]
   (if (contract/error? (ex-data e))
     (ex-data e)
     (let [d (ex-data e)
           diag? (comp #{"jolt.error" "clojure.error"} namespace key)
           jd (into {} (filter (every-pred (comp qualified-keyword? key) diag?)) d)
           other (not-empty (into {} (remove (every-pred (comp qualified-keyword? key) diag?)) d))
           sym (:jolt.error/symbol jd)
           unresolved? (and sym (= :analyze/unresolved-symbol (:jolt.error/kind jd)))
           m (if unresolved? (unresolved-message sym) (step/error-msg e))
           causes (mapv #(str (type %) ": " (ex-message %)) (take-while some? (rest (iterate ex-cause e))))]
       (contract/error :seon.agent/eval-error op m
                       (cond-> (assoc jd :class (str (type e)))
                         (seq (:jolt.error/suggestions jd)) (assoc :seon.error/fix (str "did you mean " (str/join ", " (:jolt.error/suggestions jd))))
                         other (assoc :data other)
                         (seq causes) (assoc :causes causes)))))))

(defn write-end!
  "Run's end on this branch (:seon.end/*, which root's judge reads), committed: its
  outcome, and when known its reason, the error that ended it (as data), its turns, tokens
  and waste. The turn loop's end and the process's death write it the same way."
  {:malli/schema [:=> [:cat :seon.run/id [:map [:outcome :seon.run/outcome]]] :any]}
  [run {:keys [outcome reason error turns tokens waste]}]
  (transact! [(cond-> {:seon.end/run run :seon.end/outcome outcome}
                turns (assoc :seon.end/turns turns)
                tokens (assoc :seon.end/tokens tokens)
                reason (assoc :seon.end/reason reason)
                error (assoc :seon.end/error error)
                (seq waste) (assoc :seon.end/waste waste))])
  (commit!))

(defn died!
  "The process's death by defect e, caught at its entrant boundary (seon.repl/serve!): the
  cause, printed by the one printer, to stderr (root records its tail with the death, even
  when the end cannot be written), then the end of the run this process works, written
  on its branch. Root names the death by the exit status that follows."
  {:malli/schema [:=> [:cat :seon.host/throwable] :any]}
  [e]
  (let [em (error-map e 'seon.repl/serve!)]
    (sock/write-line! 2 (print/text em (:part print/budgets)))
    (when-let [a (get-in @state [:turn :run])]
      (write-end! a {:outcome :error :reason (str "the agent's process died: " (:seon.error/message em)) :error em}))))

(defn- interrupt-self-test
  "T1's boot check: SIGUSR1 reaches a Chez handler only in a process whose ancestors were
  all forked from a main thread. Answers whether this agent's handler ran."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [got (volatile! false)]
    (lib/on-signal! lib/SIGUSR1 (fn [] (vreset! got true)))
    (lib/c-kill (lib/c-getpid) lib/SIGUSR1)
    (loop [k 0] (when (and (not @got) (< k 100000)) (eval 1) (recur (inc k))))
    @got))

;; ---- tests, in a fork of this agent ------------------------------------------------

(defn test-report
  "One clojure.test report as the run keeps it: its type, test var and message; a failing
  assertion's expected and actual printed by the one printer under :part; a cost proof's
  rows and verdict (seon.cost/proof's :actual) kept as data under :cost (session W2's agent
  page renders them; the judge persists them on the run); a typed error's :seon.error/type,
  :ticks and :budget (a test past its tick budget)."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [m var]
  (let [cost (when (map? (:actual m)) (select-keys (:actual m) [:rows :verdict]))]
    ;; clojure.test's is takes any message and prints it; the run keeps it as that text
    (cond-> {:type (:type m) :var var :message (let [msg (:message m)] (cond-> msg (not (or (nil? msg) (string? msg))) (print/text (:part print/budgets))))}
      (not= :pass (:type m)) (assoc :expected (print/text (:expected m) (:part print/budgets))
                                    :actual (print/text (:actual m) (:part print/budgets)))
      ;; what the assertion evaluated to: clojure.test reports a failed (is (pred a b)) as
      ;; (not (pred a' b')), so its second element; the agent reads it beside the assertion
      (and (= :fail (:type m)) (seq? (:actual m)) (= 'not (first (:actual m))))
      (assoc :got (print/text (second (:actual m)) (:part print/budgets)))
      (contract/valid? :seon.cost/report cost) (assoc :cost cost)
      ;; a test past its tick budget (seon.proofs/run-tests!): the typed error and its counts
      (:seon.error/type m) (merge (select-keys m [:seon.error/type :ticks :budget])))))

(defn- base-graph
  "The reach graph of base under inputs, with its index ({:graph :index}: seon.program/
  graph-index), reused while base is the very value it was made for: a value never changes,
  so neither does its graph (AGENTS §5, results reused by input). A new base (merge-main!, a
  resumed branch's older one) is the graph held updated to it (seon.program/reach-update, in
  either direction), never built again: the graph held is the one this process was forked
  with, main's, kept by the fork server (:graph), and built here only when there is none or
  its :data-refs are not inputs'. Measured on Seon's program (2,616 forms): the
  graph 640 ms, 816 MB; a one-form change's update and index 20-45 ms."
  {:malli/schema [:=> [:cat :seon.host/db :seon.program/inputs] :map]}
  [base inputs]
  (let [{w-db :db g0 :graph :as w} (:base-graph @state)]
    (if (and w (identical? base w-db) (= inputs (:inputs w)))
      (:value w)
      (let [g (cond (not (and g0 (= (:data-refs inputs) (:data-refs g0)))) (program/reach-graph base (:data-refs inputs))
                    (identical? base w-db) g0
                    :else (program/reach-update g0 base))
            v {:graph g :index (program/graph-index g inputs nil)}]
        (swap! state assoc :base-graph {:db base :graph g :inputs inputs :value v})
        v))))

(defn test-plan
  "What judges branch db, as this process holds it: seon.proofs/plan of its tests' reach digests
  (seon.program/reach-digests) against base's (main at its merge base), the greens stored at
  those digests (files in results-dir, and the test service's result facts in db, greens and reds: seon.proofs/stored-results) and proofs. The digests' inputs with no program facts are this boot's
  :inputs on root's :seon.root/source fact (seon.root/import-source!: present only when root's
  binary is built from its source tree), so an agent's results are kept by digest in the one
  results directory every checkout shares (:seon.config.source/test-results-dir;
  DECISIONS, Agents share greens: a test green at its digest never runs again, \"especially
  when freshly branched\"; every eval is a recorded form, so the digest covers what ran); a
  root with no such fact this boot shares nothing and reuses nothing (the
  inputs then are blank, which keys nothing stored: it only selects). A test is selected when
  it is a proof or reaches what the branch changed since base (a var, method, fixture, ns form,
  macro or schema entry), and runs when no result is stored at its digest (a red is reused as recorded)
  (seon.reconcile/done-explain reads the selected). Scratch namespaces' tests (user,
  ^:scratch) never merge, so they never judge; a proof with no test var here does not run, and
  one with a var but no program facts (loaded, never recorded) has no digest: it always runs.
  With no base (a tree with nothing to diff against: root's on-change runs), the proofs and
  every test with no green at its digest, of which those with no result run (seon.proofs/plan's own rule). Answers the plan with
  :digests {test digest}, :dir (the results directory when shared), :graph (the reach graph it
  walked) and :process, the tests that reach a process of their own (seon.program/reach-index, the
  same walk as the digests): never run automatically, only when named (DECISIONS, Process tests
  are not automatic)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:set :qualified-symbol]]
                  [:map [:selected [:vector :qualified-symbol]] [:run [:vector :qualified-symbol]] [:reused [:map-of :qualified-symbol :map]]
                   [:digests [:map-of :qualified-symbol :string]] [:dir [:maybe :string]] [:graph :map] [:process [:set :qualified-symbol]]]]}
  [db base proofs]
  (let [root (d/entity db [:seon.root/id :root])
        source (:seon.root/source root)
        shared (when (= (:boot source) (:seon.root/boot root)) (:inputs source))
        in (or shared {:env "" :tree "" :data-refs (fx/declared-refs (cfg/value :seon.config.root/system-path))})
        dir (when shared (cfg/value :seon.config.source/test-results-dir))
        judges? (fn [t] (let [m (some-> (find-ns (symbol (namespace t))) (ns-resolve (symbol (name t))) meta)]
                          (and (:test m) (not (program/scratch-ns? (:ns m))))))
        b (when base (base-graph base in))
        g (if b (program/reach-update (:graph b) db) (program/reach-graph db (:data-refs in)))
        idx (into {} (filter (comp judges? key)) (program/graph-index g in nil))
        cd (update-vals idx :digest)
        unrecorded (sort-by str (filter #(and (judges? %) (not (cd %))) proofs))]
    (-> (proofs/plan (some-> b :index (update-vals :digest)) cd (merge (if dir (proofs/greens dir (vec (vals cd))) {}) (proofs/stored-results db (vec (vals cd)))) proofs)
        (update :selected into unrecorded)
        (update :run into unrecorded)
        (assoc :digests cd :dir dir :graph g
               :process (program/process-tests idx)))))

(declare handle!)

(defn load-tests!
  "Make the checkout's tests (the files under dirs, read here by the :source performer: the tree
  is megabytes, more than a frame to root carries) part of this
  agent's program: their namespaces loaded here and their forms recorded on its branch
  (seon.source/import-tx), so test-plan finds them and their reach digests. What the test
  service's prove agent does before it runs (seon.prove). Answers the files loaded, or the
  :source effect's error."
  {:malli/schema [:=> [:cat [:sequential :string]] [:or :int :map]]}
  [dirs]
  (let [r (fx/read-source {:req {:root (cfg/checkout) :dirs (vec dirs)}})]
    (if (:value r)
      (let [files (:value r)]
        (doseq [[_ _ text] (source/load-order files)] (load-string text))
        (transact! (source/import-tx (db) files))
        (count files))
      r)))

(defn- await-reply!
  "Wait for root's answer k to this agent's ask (a :reply on the api's route back to its proc):
  root's :tests request arriving meanwhile is taken here (its test service may need this agent to
  run them); other messages wait for the loop. The process ends when root is gone."
  {:malli/schema [:=> [:cat :int] :any]}
  [k]
  (loop []
    (let [m (recv-raw!)]
      (cond (= m :dead) (lib/c-exit 0)
            (and (= :reply (:op m)) (= k (:k m))) (:result m)
            (= :tests (:op m)) (do (doseq [r (handle! m)] (send! r)) (recur))
            :else (do (swap! state update :pending conj m) (recur))))))

(defn prove!
  "Ask the test service (seon.prove) to prove this branch at its head, and wait for its answer:
  the run's (run-tests-forked's answer, with :key). The service runs one run per key whoever
  asks, the key the digest of what the run reads (seon.prove/read-digest of this branch's
  program, as seon.root/prove! takes it of the same head): a session's request and this ask are one run; when it is
  this agent's to run, root's :tests request arrives while it waits and is taken here. Other
  messages wait for the loop. :dead when root is gone (the process then ends). mode :turn (a
  turn's observation, the default) or :judge (root's judge at the run's end: its :judge request)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:enum :turn :judge]] :map]]}
  ([] (prove! :turn))
  ([mode]
  (commit!)
  (let [k (:tx-seq (swap! state update :tx-seq inc))]
    (when (= :dead (send! {:op :prove :k k :t (:max-tx (db)) :digest (seon.prove/read-digest nil (program/program-digest (db))) :mode mode})) (lib/c-exit 0))
    (await-reply! k))))

(defn- run-tests-here
  "The tests a test-plan runs (var symbols), by the one runner (seon.proofs/run-tests!, nothing
  stored: the parent holds the greens), with the branch's schemas in effect (malli's dynamic
  registry bound to seon.program/schema-registry, as the agent's REPL binds it), each test
  within ticks engine ticks of work of its own (the runner's bound): answers report data. Each
  test var is written to w as it starts ({:seon.test/started v}), so the agent can name the test
  a death interrupted; a test past its ticks is an :error report of the typed
  :seon.test/ticks-spent naming it; a test its namespace's throw kept from reporting is an
  :error report carrying that throw (the runner reports it)."
  {:malli/schema [:=> [:cat :int nat-int? [:sequential :qualified-symbol]] :seon.test/result]}
  [w ticks tests]
  (let [reports (atom [])
        started (atom nil)
        note! (fn [x]
                (cond (:seon.test/started x) (do (reset! started (str "#'" (:seon.test/started x)))
                                                 (sock/write-line! w (sedn/encode {:seon.test/started @started})))
                      (:seon.test/report x) (swap! reports conj (test-report (:seon.test/report x) (str "#'" (:test x))))
                      :else nil))
        _ (binding [mr/*registry* (program/schema-registry (program/schema-changes (db)))]
            (proofs/run-tests! tests (zipmap tests (repeat nil)) note! ticks))
        reports @reports
        counts (frequencies (map :type reports))]
    {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :test (count (distinct (keep :var reports)))
     :reports reports}))

(defn test-run-answer
  "The run, from the last line the test child wrote on its pipe before EOF or its exit (nil:
  none), its exit status, and held? (the child exited and its pipe was still open: a process it
  forked holds the write end): that line when it is the run's result; else the child died while
  running the test the line's {:seon.test/started var} names, and the run is one :error report on
  that test var with the status as :exit (none started: a run error naming the status). A held
  pipe adds an :error report of its own (:seon.test/pipe-held)."
  {:malli/schema [:=> [:cat [:maybe :string] :seon.lib/exit-status :boolean] :seon.test/result]}
  [line status held?]
  (let [v (some-> line read-string) var (:seon.test/started v)
        res (if (contains? v :pass)
              v
              {:pass 0 :fail 0 :error 1
               :reports [(cond-> {:type :error :exit status
                                  :message (str "test process died: " status (when var (str " while " var " ran")))}
                           var (assoc :var var))]})]
    (cond-> res
      held? (-> (update :error inc)
                (update :reports conj {:type :error :seon.error/type :seon.test/pipe-held
                                       :message "the test process exited and a process it forked still holds its pipe (its end of file never came): what it wrote is the run"})))))

(defn read-to-exit
  "The last line test child pid writes on fd r, read to the pipe's end of file or the child's
  exit, whichever comes first: the exit is kqueue's NOTE_EXIT, the fact the fork server reaps
  by, so a process the child forked that holds the write end cannot keep the run waiting.
  Parks on seon.lib/wait-fd-or-exit (r's input or the exit). After the exit, what is left in
  the pipe is read: a read that found nothing there (its wait called) with the pipe still open
  means it is held, and a read that ended on part of a line goes on. Answers {:line :held?}, or
  :seon.lib/unwatched."
  {:malli/schema [:=> [:cat :seon.lib/fd pos-int?] [:or [:map [:line [:maybe :string]] [:held? :boolean]] :seon.lib/unwatched]]}
  [r pid]
  (let [w (lib/wait-fd-or-exit r pid)]
    (if (contract/error? w)
      w
      (let [{:keys [wait close]} w
            exited (volatile! false) failed (volatile! nil) waited (volatile! false)
            rd (sock/line-reader r #(let [x (wait)]
                                      (vreset! waited true)
                                      (cond (= :exited x) (vreset! exited true)
                                            (contract/error? x) (vreset! failed x))))]
        (try
          (loop [line nil]
            (vreset! waited false)
            (let [ls (rd)]
              (cond (= ls :eof) {:line line :held? false}
                    @failed @failed
                    (seq ls) (recur (peek ls))
                    ;; nothing there yet, or part of a line: read again
                    (or (not @exited) (not @waited)) (recur line)
                    ;; exited, and a read found nothing there: the pipe is still open, held
                    :else {:line line :held? true})))
          (finally (close)))))))

(defn run-selection
  "What a test-plan p runs when a request names namespaces (names; none: every test it selects), as
  p with :selected and :run restricted to them. A test that reaches a process of its own (p's
  :process) runs only when asked for: its namespace is named, or it is one of the proofs;
  otherwise it is left out and listed under :skipped (DECISIONS, Process tests are not automatic)."
  {:malli/schema [:=> [:cat :map [:maybe [:set :symbol]] [:set :qualified-symbol]] :map]}
  [p names proofs]
  (let [in? #(contains? names (symbol (namespace %)))
        p (if (seq names) (-> p (update :selected #(filterv in? %)) (update :run #(filterv in? %))) p)
        skip? (fn [t] (and (contains? (:process p) t) (not (seq names)) (not (contains? proofs t))))]
    (-> p (assoc :skipped (vec (sort-by str (filter skip? (:selected p)))))
        (update :selected #(filterv (complement skip?) %)) (update :run #(filterv (complement skip?) %)))))

(defn run-tests-forked
  "Fork this agent; the child runs what test-plan runs for proofs (the selected tests with no
  green held at their digest), each test within ticks engine ticks of work of its own, writing
  each test var as it starts and then one EDN line of its result on a pipe. The agent reads the
  pipe to its end: no clock; the child ends by finishing or by dying (then the last test it
  started is named). Each selected test with a result at its digest is reused, marked :reused: a
  green as one :pass report, a red as the failures its run recorded (the test service's facts,
  seon.proofs/stored-results: each assertion's type, message, expected and actual); when every
  one is, nothing is forked (no :pid). The counts are its reports' types, as a run's are. One bound in every mode
  (:seon.config.agent/test-ticks, per test), so a test's verdict is the same in a turn, the
  judge and bin/test, and the judge after a turn reruns no green the turn left.
  Each test that ran green is stored at its digest in test-plan's :dir through root's :file
  owner (seon.proofs/store-greens!); a write refused is named under :unstored, and that test
  runs again. :ran is how many tests reported. (forked pid) runs here once the child exists.
  A fork refused (threads active) is :seon.test/fork-refused: no test ran.
  Root's ruling (a): mode :turn (a turn's observation) selects the proofs and the
  tests the branch itself changed or added; mode :judge (root's judge at the run's end, and
  the test service's runs) selects the plan's tests. names (the test service's request: a set of
  namespaces) restricts either to the tests of those namespaces. A process test (the plan's
  :process: it reaches a shell, a fork, a root or the binary) never runs here unless its namespace
  is named (DECISIONS, Process tests are not automatic; root's gate, my.root/gate!, asks).
  The answer names those left out (:deferred), why each was (:why, {test {:uses :through}}: the
  process var it reaches and the calls to it, seon.program/process-path), and how many the judge
  runs (:at-judge)."
  {:malli/schema [:function [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge]] [:or :seon.test/result :seon.test/fork-refused]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge] :seon.host/fn] [:or :seon.test/result :seon.test/fork-refused]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge] :seon.host/fn [:maybe [:set :symbol]]] [:or :seon.test/result :seon.test/fork-refused]]]}
  ([ticks proofs mode] (run-tests-forked ticks proofs mode (fn [_] nil)))
  ([ticks proofs mode forked] (run-tests-forked ticks proofs mode forked nil))
  ([ticks proofs mode forked names]
  (let [plan (test-plan (db) (:base @state) proofs)
        {judge :selected deferred :skipped} (run-selection plan names proofs)
        selected (if (and (= :turn mode) (not (seq names)))
                   ;; the proofs as the plan selects them (one loaded but never recorded has
                   ;; no digest and is selected all the same), and the tests the branch changed
                   (vec (sort-by str (filter (set judge) (into (set (filter proofs (:selected plan)))
                                                               (filter (:digests plan) (program/changed-vars (:base @state) (db)))))))
                   judge)
        {:keys [reused digests dir]} plan
        run (filterv #(not (reused %)) selected)
        ;; a red reused reports the failures its run recorded, so it judges as it did
        reused-reports (vec (for [t selected :let [r (reused t)] :when r
                                  f (or (seq (:failures r))
                                        [(cond-> {:type (if (proofs/green? r) :pass (if (pos? (:error r)) :error :fail))}
                                           (not (proofs/green? r)) (assoc :message "red at this digest, with no failure kept"))])]
                              (assoc f :var (str "#'" t) :reused true)))
        t0 (profile/now-ns)
        ;; every selected test green at its digest (a turn's run of the same tree, a session's):
        ;; nothing to run, so no child is forked
        res (if (empty? run)
              {:pass 0 :fail 0 :error 0 :test 0 :reports [] :ms 0}
              (let [[r w] (sock/socketpair)]
                (lib/fork!
                {:refused (fn [n] (lib/c-close r) (lib/c-close w)
                            (contract/error :seon.test/fork-refused #'seon.agent/run-tests-forked
                                            (str "cannot fork the tests: " n " Chez threads active (a running future?)") {:active-threads n}))
                 :child (fn []
                          (lib/c-close r)
                          ;; nothing this child forks (a test's own child, a root it starts) holds the
                          ;; write end, so its exit is the pipe's end of file
                          (sock/clofork! w)
                          ;; the run ends with its agent (a test ends by its own budget): the agent's end of this socket
                          ;; pair closing raises SIGIO here and ends the child, whatever the test is
                          ;; doing (task seon.agent/a-proof-run-ends-with-its-agent; the agent's own
                          ;; socket to root is FD_CLOFORK: not held here, so its handler is replaced)
                          (when-let [unwatched (sock/signal-at-input! w)]
                            (sock/write-line! w (sedn/encode {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (:seon.error/message unwatched)}]}))
                            (lib/c-exit 0))
                          (intr/exit-at-eof! #(sock/peer-gone? w))
                          (lib/set-trip! (* 16 1048576))
                          (swap! state assoc :mode :test :evaluating false)
                          (let [res (try (run-tests-here w ticks run)
                                         (catch Throwable e {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]}))]
                            (sock/write-line! w (sedn/encode res))))
                 :parent (fn [pid]
                           (lib/c-close w)
                           (forked pid)
                           (let [read (read-to-exit r pid)
                                 st (lib/wait-status pid)]
                             (lib/c-close r)
                             (if (contract/error? read)
                               read
                               (assoc (test-run-answer (:line read) st (:held? read)) :ms (profile/ms-since t0) :pid pid :exit st))))})))
        unstored (when (and dir (not (contract/error? res)))
                   (proofs/store-greens! #(fx! :file % {}) dir
                                         (into {} (for [[t status] (proofs/results res) :when (and (= :pass status) (digests t))]
                                                    [t {:test t :digest (digests t) :pass 1 :fail 0 :error 0}]))))]
    (if (contract/error? res)
      res
      (-> res
        (cond-> (seq unstored) (assoc :unstored unstored))
        (update :reports into reused-reports)
        (as-> r (merge-with + r (select-keys (frequencies (map :type reused-reports)) [:pass :fail :error])))
        (assoc :digests (select-keys digests (concat (keep (comp proofs/test-symbol :var) (:reports res)) (filter reused selected))))
        (assoc :reused (count (filter reused selected)) :ran (count (distinct (keep :var (:reports res))))
               :selected selected :deferred deferred :at-judge (count judge))
        ;; why each was left out: the process it reaches and the calls to it (seon.program/process-path)
        (cond-> (seq deferred) (assoc :why (into {} (for [t deferred] [t (program/process-path (:graph plan) t)])))))))))

;; ---- the loop ----------------------------------------------------------------------

(defn- make-ns! {:malli/schema [:=> [:cat :seon.agent/id] :seon.host/namespace]}
  [id]
  (let [sym (symbol (str "agent." (name id)))
        nsx (create-ns sym)]
    ;; the agent's own namespace is its scratch pad (seon.program/scratch-ns?): what it
    ;; defines here never becomes program
    (alter-meta! nsx assoc :scratch true)
    (binding [*ns* nsx]
      (refer-clojure)
      (refer 'clojure.test :only '[deftest is are testing use-fixtures])
      (refer 'my.db)
      (refer 'my.agent :exclude '[source source-fn pst])
      ;; the agent's record as a map, @ctx (seon.ctx)
      (refer 'seon.ctx :only '[ctx])
      ;; clojure.repl as clojure.main's REPL has it (clojure.main/repl-requires). Jolt's lacks
      ;; source, source-fn and pst: interned from my.agent, whose source also reads the agent's
      ;; own redefinitions from its branch db (task seon.context/the-standard-repl-tools moves the
      ;; file-reading part into Jolt's own clojure.repl)
      (doseq [s '[source-fn source pst] :let [v (ns-resolve 'my.agent s)]]
        (intern (find-ns 'clojure.repl) (with-meta s (select-keys (meta v) [:macro :arglists :doc])) @v))
      (refer 'clojure.repl :only '[source apropos dir pst doc find-doc])
      (require 'clojure.pprint)
      (refer 'clojure.pprint :only '[pp pprint])
      (alias 'd 'datahike.api)
      (alias 'str 'clojure.string))
    nsx))

(defn- key-of
  "Agent id's key (seon.task/agent-key) in the boot of the root that forked it: main's
  :seon.root/boot, read from main (db: main as the agent forked, or main's head), never from the
  agent's branch, whose root fact is as old as the branch (a resumed branch's names an earlier
  boot, and its agent's records went to that boot's agent)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] :string]}
  [db id] (task/agent-key (:seon.root/boot (d/pull db [:seon.root/boot] [:seon.root/id :root])) id))

(declare clone!)

(defn handle!
  "One Seon platform request; answers the messages to send back (a vector). (REPL
  requests are nREPL's: seon.repl.)"
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] [:maybe [:vector :seon.wire/seon-msg]]]}
  [m]
  (case (:op m)
    ;; the proofs of the task root names (:task), as this branch holds them
    ;; root hears the test child's pid once it is forked (its agent proc's :tests-started event)
    ;; root's judge at a run's end: the test service proves this branch (prove! :judge), so the
    ;; run is recorded on main and joins any other asker's of its key; answered as :tests is
    :judge [{:op :tests :id (:id m) :result (prove! :judge) :mem-mb (mem-mb)}]
    :tests [{:op :tests :id (:id m) :result (run-tests-forked (or (:ticks m) (cfg/value :seon.config.agent/test-ticks))
                                                              (or (some->> (:task m) (task/task (db)) :seon.task/accumulated :proofs) #{})
                                                              (or (:mode m) :judge)
                                                              #(send! {:op :tests-started :id (:id m) :pid %})
                                                              (some-> (:names m) set))
             :mem-mb (mem-mb)}]
    ;; an async effect's outcome (fx-send!): a message to this agent, recorded
    :fx-reply (let [r (dissoc m :op :k)]
                (swap! state update :inbox (fnil conj []) {:from :fx :k (:k m) :kind (fx-record! (:k m) r) :body r})
                nil)
    ;; root asks this agent to merge main into its branch (it owns the branch's writes)
    :merge-main [{:op :merged-main :id (:id m) :result (merge-main! (:data-attrs m #{}))}]
    :clone (do (clone! m) nil)
    :stats [{:op :stats :id (:id m) :result {:mem-mb (mem-mb) :t (:max-tx (db)) :pid (lib/c-getpid)
                                              :results (count (:results @state)) :evals (:n @state)
                                              :heap-mb (lib/r2 (/ (lib/bytes-in-heap) 1048576.0))}}]
    ;; a message (session F2): it waits in :inbox until the agent's code takes it ((messages));
    ;; root hears that it arrived
    :message (do (swap! state update :inbox (fnil conj []) (select-keys m [:from :body]))
                 [{:op :received :sent-ns (:ns m) :recv-ns (profile/now-ns)}])
    nil))

(defn spawn-phases
  "The spawn's phases from root's request at t-req-ns, read off marks ([phase ns bytes], bytes
  nil where the mark is another process's): each phase's wall ms since the mark before it (the
  first since the request) and the bytes this thread allocated in it."
  {:malli/schema [:=> [:cat :int [:sequential [:tuple :keyword :int [:maybe :int]]]] [:vector :map]]}
  [t-req-ns marks]
  (vec (for [[[_ n0 b0] [k n1 b1]] (partition 2 1 (cons [:request t-req-ns nil] marks))]
         (cond-> {:phase k :ms (lib/r2 (/ (- n1 n0) 1e6))} (and b0 b1) (assoc :bytes (- b1 b0))))))

(defn hello! {:malli/schema [:=> [:cat :int :map] [:or [:= true] [:= :dead]]]}
  [t-req-ns extra]
  (send! (merge {:op :hello :pid (lib/c-getpid) :start-ms (profile/ms-since t-req-ns) :mem-mb (mem-mb) :t (:max-tx (db))
                 :phases (spawn-phases t-req-ns (conj (:marks @state []) [:hello (profile/now-ns) (profile/alloc-bytes)]))}
                extra)))

;; ---- stderr: a file per agent, its tail read by root when the agent dies -------------

(defn stderr-path
  "Where agent id's stderr goes: <dir>/<id>.err, dir its root's (seon.db/agents-dir: root
  makes it at boot), or nil for a store with no path (in memory)."
  {:malli/schema [:=> [:cat [:maybe :string] :keyword] [:maybe :string]]}
  [dir id] (when dir (str dir "/" (name id) ".err")))

(defn- stderr-to-file!
  "This process's stderr (fd 2: what Chez, the runtime and native code print) into the
  file at path, truncated. The edge of a failure point: a file that cannot be made leaves stderr
  where it was (the death then records no tail)."
  {:malli/schema [:=> [:cat [:maybe :string]] :any]}
  [path]
  (when path
    (let [fd (lib/c-creat path 420)]
      (when-not (neg? fd) (lib/c-dup2 fd 2) (lib/c-close fd)))))

(defn- clone!
  "Fork this agent, live values and all, into a new agent on a branch of this branch.
  Root branches this branch first (no transaction is in flight here, so the new branch's
  head is this db); the clone is double-forked so it is not this agent's child."
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] :any]}
  [{:keys [k] :as m}]
  (let [t0 (profile/now-ns)
        ;; the clone's socket: root sends it in a frame of its own while its writer pump is
        ;; idle (SCM_RIGHTS beside another writer's frames would not be atomic): this agent
        ;; says it waits, root holds its other messages until the descriptor is sent
        _ (send! {:op :fd-ready :k k})
        _ (await-op! :fd k)
        fd (sock/take-fd! (:conn @state))]
    ;; the new branch starts at this branch's committed head: what was applied commits first
    (commit!)
    (send! {:op :branch-me :k k})
    (let [r (await-op! :branched k)]
      (if (:error r)
        (do (sock/close! fd) (send! {:op :clone-failed :k k :error (:error r)}))
        (lib/fork!
          {:fd fd :double? true
           :refused (fn [n] (send! {:op :clone-failed :k k :error (contract/error :seon.agent/failed #'seon.agent/clone! (str "cannot fork: " n " Chez threads active (a running future?)") {:agent (:agent m)})}))
           :parent (fn [_] (send! {:op :cloned :k k :clone (:agent m)}))
           :child (fn []
                    (let [parent (:id @state)]
                      ;; the parent's socket closed on fork (FD_CLOFORK); this clone's came with the request
                      (sock/clofork! fd)
                      ;; the clone opens the store itself, on its own branch
                      (pdb/after-fork! (:cfg r))
                      (stderr-to-file! (stderr-path (pdb/agents-dir (:cfg r)) (:agent m)))
                      (let [store-conn (d/connect (assoc (:cfg r) :branch (:branch m)))]
                        (swap! state assoc :id (:agent m) :key (key-of (pdb/head store-conn (pdb/main-branch (:cfg r))) (:agent m)) :branch (:branch m) :fd fd :conn (sock/conn fd) :mode :agent :tx-seq 0
                               :pending clojure.lang.PersistentQueue/EMPTY :fx-pending {}
                               :store-conn store-conn :db @store-conn))
                      (let [unwatched (sock/signal-at-input! fd)]
                        (hello! t0 (cond-> {:cloned-from parent} unwatched (assoc :unwatched unwatched))))
                      ((:serve @state))))})))))

(defn misaligned
  "Where a new agent's branch or forked image is not at the commit :spawn branched it from
  (commit and t: main's head then, seon.writer :spawn): the members of its
  :seon.agent/fork-misaligned problem, or nil when both are there."
  {:malli/schema [:=> [:cat :uuid :int :seon.host/db :seon.host/db] [:maybe :map]]}
  [commit t branch image]
  (when (or (not= commit (d/commit-id branch)) (not= t (:max-tx image)))
    {:commit commit :t t :branch-commit (d/commit-id branch) :image-t (:max-tx image)}))

(defn init!
  "Make this process the agent: its socket (fd), its namespace, its state map, its
  interrupt handlers. serve is the loop a clone runs (seon.repl/serve!)."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :seon.host/fn] :any]}
  [fd {id :agent branch :branch task :task run :run cfg :cfg resumed :resumed commit :commit t :t graph :graph session :key
       marks0 :marks} db0 serve]
  ;; the spawn's phases on the one clock, each with what this thread allocated in it: the fork
  ;; server's marks (marks0), then this process's, sent with its hello (spawn-phases)
  (let [marks (volatile! (conj (vec marks0) [:child-init (profile/now-ns) (profile/alloc-bytes)]))
        mark! (fn [k] (vswap! marks conj [k (profile/now-ns) (profile/alloc-bytes)]))]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
  ;; the config this process was forked holding (root read it; an agent reads no environment)
  (lib/set-trip! (cfg/value :seon.config.agent/trip-bytes))
  (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes)))
  (stderr-to-file! (stderr-path (pdb/agents-dir cfg) id))
  (mark! :setup)
  ;; this agent owns its branch's writes: it opens the store itself (seon.db/after-fork!)
  (pdb/after-fork! cfg)
  (mark! :store-reopened)
  ;; the model's API key is root's alone (its :model owner calls the model; session FX1)
  (lib/c-unsetenv (cfg/value :seon.config.ai/api-key-variable))
  (let [nsx (make-ns! id)
        _ (mark! :ns)
        intr-ok (interrupt-self-test)
        _ (mark! :interrupt-test)
        c (sock/conn fd)
        store-conn (d/connect (assoc cfg :branch branch))
        _ (mark! :connected)]
    (intr/install!)
    ;; root gone, this process ends, busy or not (the fork server's group kill may be gone too)
    (intr/exit-at-eof! #(sock/peer-gone? (:fd @state)))
    (mark! :handlers)
    ;; :base is the branch's merge base with main: a new branch's is the image (main as it
    ;; forked); a resumed one's is older than main, and comparing against main's head would
    ;; read main's later changes as the branch's (seon.program/divergence, changed-vars)
    (let [base (if resumed (pdb/branch-base store-conn (pdb/main-branch cfg) branch) db0)
          ;; a kept branch, resumed: this process (forked holding main's program) runs the
          ;; branch's own changes since its base on top, as merging main would; from main's
          ;; head it would unmap all main gained since (step E run #11: test/'s namespaces)
          ;; a branch keeps the schema it was cut with: resumed, it gains the attributes this
          ;; program has since (seon.db/install-missing!), and its forms the :form/keywords
          ;; facts they were recorded without, before the program it holds is loaded
          _ (when resumed
              (let [_ (pdb/install-missing! store-conn) tx (program/gained-tx @store-conn)]
                (when (seq tx) (d/transact store-conn {:tx-data tx :tx-meta (pdb/tx-meta branch)}))))
          load-errors (when resumed (program/load-diff! base @store-conn))
          _ (mark! :base)
          misaligned (when-not resumed (misaligned commit t @store-conn db0))
          _ (mark! :misaligned)]
     ;; a resumed branch whose program does not load runs main's for what failed: its errors
     ;; go to root with its hello (recorded problems), never dropped (T5b: a stub ran silently)
     (reset! state (cond-> {:id id :key (or session (key-of db0 id)) :branch branch :task task :run run :fd fd :conn c :db @store-conn
                   :base base :store-conn store-conn :cfg cfg :results {} :n 0
                   :load-errors (vec load-errors) :misaligned misaligned :unwatched (sock/signal-at-input! fd)
                   :pending clojure.lang.PersistentQueue/EMPTY :inbox [] :tx-seq 0 :mode :agent
                   :ns nsx :interrupt-ok intr-ok :serve serve}
                   ;; main's reach graph as the fork server keeps it, for db0: test-plan's base
                   ;; graph starts from it (base-graph), never built in this process
                   graph (assoc :base-graph {:db db0 :graph graph})))
     (mark! :state)
     (swap! state assoc :marks @marks)
     ;; a kept branch, resumed (root released its held task: seon.reconcile/hold-tx), takes
     ;; its task from main, with the failure and evidence the last run left there; an
     ;; agent with no task (a fresh one on a kept branch) takes none
     (when-let [tx (when (and resumed task) (not-empty (task/onto-tx db0 @store-conn task)))]
       (transact! tx))))))
