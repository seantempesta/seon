(ns seon.agent
  "An agent: a process forked from the fork server, holding its branch's db value
  (inherited from main at the branch point, then advanced by its own tx-data with
  d/db-with) and running its REPL over its socket to root (seon.repl). The live value of
  each form stays here under its handle.

  The agent is single-threaded and reads and writes its socket itself (seon.sock frames,
  the protocol of root's jolt.async.remote end), never through remote-channel pumps: one
  fiber of its own reads root's frames onto its intake (`intake`), which the loop is the one
  taker of, and the loop is the one sender (a test child's watcher puts its result there too,
  so the loop answers root: run-tests-forked). That
  is physics, not style: an agent forks (its test children, clone!), and a process that
  forks must never have started Chez's parallel-collector sweeper threads, which start at
  the first collection that finds a second thread; a child of such a process hangs in its
  first multi-threaded collection (session RC1). Adding pump or worker threads to an agent
  breaks its forks; docs/failure-points.md, "Physics". Agent code calls the functions of my.db and my.agent,
  referred into its namespace. From proto/flow f2-final proto.agent (session F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.error :as err] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.merge :as pm] [seon.config :as cfg] [seon.reconcile :as r] [seon.vector :as vector] [seon.lib :as lib] [seon.print :as print] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [seon.program :as program] [seon.proofs :as proofs] [seon.fx :as fx] [seon.task :as task] [seon.intr :as intr] [clojure.repl]
            [malli.registry :as mr] [clojure.core.async :as a]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :task :run (the task and run root forked it for, or nil) :fd :conn :db :base (main at the branch's merge base: as forked, then each merge-main!) :main (main's head as last read: as forked, then heard-main) :results :n :pending :tx-seq :mode :ns :home :mismatch
;; :embedded-from (the db embed-written! last read the branch's writes up to)
;; :unembeddable (the entities whose text llama.cpp could not encode, as vectors-proc keeps them)
;; :base-graph (test-plan's reach graph of its base, keyed by the base's point (seon.db/point: commit and t, never the db), from the fork server's :graph of main: base-graph)
;; :branch-graph (the reach graph of the branch's db, keyed by its point, updated from the base's: branch-graph)
;; :branch-changes (what the branch changed since its base, seon.merge/delta-changes, keyed by the two points: branch-changes)
;; :main-changes (what main changed since the base, seon.merge/delta-changes, keyed by the two commits: main-changes)
;; :index (seon.agent/reconcile-index: the reconciler's index and the commit it was observed for (the fork hands the db once), advanced each turn by what differs; at fork, main's from the fork server)
;; :schema-registry (seon.repl: the branch's schema registry, by its schema changes)
;; :tx (the last transaction transact! applied, {:db-after :tx-data}: seon.ctx patches @ctx from it: the db :db is, dropped by commit! when it moves :db)
;; :eval (the id of the eval running, "e5", while seon.repl/eval-string runs it: what transact! names in its tx-meta)
(defonce state (atom nil))


;; ---- the socket to root: values in frames (root's end is a jolt.async.remote end) ------
;; This side reads and writes its socket itself, on its one thread, with no pumps: an agent
;; forks (its tests, a clone), and a process that forks must never have started Chez's
;; parallel-collector sweeper threads (session RC1: a child of such a process hangs in its
;; first multi-threaded collection).

(defn send!
  "Send a message {:op k ...} to root, one frame, whole: true. Root gone (:epipe) ends this
  process, as reading its EOF does, so no caller drops it; a write the kernel refused throws
  (seon.sock/send!, docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data)."
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] [:= true]]}
  [m] (if (= :epipe (sock/send! (:fd @state) m)) (lib/c-exit 0) true))

(defn- intake
  "This process's one intake: a channel of root's messages ({:op k ...}, then :dead at EOF or
  root's end frame), read off root's socket by one fiber of this process (sock/recv! parks it
  on the socket's readiness), and of what this process's own fibers hand the loop (a test
  child's result: run-tests-forked). Made when first asked on this connection (a clone's is
  its own: its connection is new). The loop takes from it (recv-raw!, next-msg!, inbox,
  await-op!), and nothing else reads the socket. Where there is no main carrier (Linux), there
  are no fibers: nil, and the socket is read directly."
  {:malli/schema [:=> [:cat] [:maybe :seon.host/chan]]}
  []
  (when (jolt.host/main-carrier?)
    (let [c (:conn @state) in (:intake @state)]
      (if (and in (identical? c (:conn in)))
        (:ch in)
        ;; unbuffered: the reader parks with each message until the loop takes it, so root's
        ;; further frames wait in the socket's own buffer, as when the loop read it itself
        (let [ch (a/chan)]
          (a/io-thread (loop [] (let [m (sock/recv! c)]
                                  (a/>!! ch (if (= :eof m) :dead m))
                                  (when-not (= :eof m) (recur)))))
          (swap! state assoc :intake {:conn c :ch ch})
          ch)))))

(defn- recv-raw!
  "The next message for this process: its intake's next, or root's next frame read directly
  where there is no intake; :dead at EOF or root's end frame."
  {:malli/schema [:=> [:cat] [:or [:= :dead] :seon.wire/seon-msg]]}
  []
  (if-let [ch (intake)]
    (a/<!! ch)
    (let [m (sock/recv! (:conn @state))] (if (= m :eof) :dead m))))

(defn- poll!
  "The next message already there, without waiting: :none when none is, :dead at EOF. The intake's
  reader runs only while the loop parks, so a frame already on root's socket (or in its conn's
  buffer) is taken by parking until the reader hands it over (sock/readable?)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if-let [ch (intake)]
    (or (a/poll! ch) (if (sock/readable? (:conn @state)) (a/<!! ch) :none))
    (let [m (sock/next-frame! (:conn @state))] (if (= m :eof) :dead m))))

(declare commit!)

(defn next-msg!
  "The next message for the loop. Idle with applied writes (no message waiting): they are
  committed first, so a branch is committed whenever its agent waits."
  {:malli/schema [:=> [:cat] [:or [:= :dead] :seon.wire/seon-msg]]}
  []
  (if-let [m (peek (:pending @state))]
    (do (swap! state update :pending pop) m)
    (if (:dirty? @state)
      (let [m (poll!)]
        (if (= :none m) (do (commit!) (recv-raw!)) m))
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
  turn takes (seon.turn/take!), never the inbox. What the intake holds now is taken
  first (without waiting); any other request taken waits in :pending for the loop."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (loop []
    (let [m (poll!)]
      (when-not (#{:none :dead} m)
        (when-not (arrived! m) (swap! state update :pending conj m))
        (recur))))
  (:inbox @state))

(defn- session-branch
  "The branch main names this agent's session by: its run's :seon.run/branch, which the store
  qualifies on a main that is not :db (seon.db/on-main), so (:branch @state) is not it. nil
  with no run: then no entry is to it."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.run/branch]]}
  [main] (some->> (:run @state) (vector :seon.run/id) (d/entity main) :seon.run/branch))

(defn heard-main
  "Main's head as the store holds it now, held on the state as what this agent last heard of main
  (:main; the record's :main-commit and :main-t, seon.ctx). Every read of main's head is this one."
  {:malli/schema [:=> [:cat] :seon.host/db]}
  [] (let [m (pdb/head (:store-conn @state) (pdb/main-branch (:cfg @state)))] (swap! state assoc :main m) m))

(declare branch-changes)

(defn main-changes
  "What main changed since base, reduced to what main-line reads and held keyed by the two points
  (commit, t) it is a function of: {:after {program-key (hash of its content on main)}}, from
  seon.merge/delta-changes (declared data left out). The diff itself, whose values carry the forms
  and so pin the dbs they were read from, is let go at once (AGENTS section 4, a kept db value is a
  reference); the newest pair is held on the state (:main-changes), evicted by the next and
  regenerated from the two dbs. {} when main's point is base's.
  See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map-of :any :int]]}
  [base main]
  (let [at (juxt d/commit-id :max-tx)
        k [(at base) (at main)]
        {held :key after :after} (:main-changes @state)]
    (cond (= (k 0) (k 1)) {}
          (= k held) after
          :else (let [after (into {} (map (fn [[pk [_ b]]] [pk (hash b)])) (pm/delta-changes base main #{}))]
                  (swap! state assoc :main-changes {:key k :after after})
                  after))))

(defn main-line
  "How main differs from branch b, both cut from base (dbs), as one line, or nil when main changed
  nothing of the program since base: how many names main changed (main-changes, held per pair of
  points), those of them b changed too (branch-changes), those b's changed functions call, and
  those both changed to different content (what a merge of main would leave unmerged), each list
  cut by the one printer; and the form that merges main
  (docs/merging.md#main-moved-is-said-at-every-eval). Cost: O(1) while main is the base; the first
  call at a main costs the diff of main since base, and a later one only the diff of b (held per
  turn) and one query over b's changed vars; measured in docs/merging.md."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] [:maybe :string]]}
  [base b main]
  (let [theirs (main-changes base main)]
    (when (seq theirs)
      (let [ours-ch (branch-changes base b)
            moved (into #{} (keep (fn [[kind x]] (when (#{:var :schema} kind) x))) (keys theirs))
            changed (pm/changed-vars ours-ch)
            ours (filter moved changed)
            conflicts (sort-by str (for [[k [_ after]] ours-ch :let [t (theirs k)] :when (and t (not= (hash after) t))] (second k)))
            reached (remove (set ours) (filter moved (d/q '[:find [?r ...] :in $ [?v ...] :where [?e :var/name ?v] [?e :var/form ?f] [?f :form/refs ?r]] b (vec changed))))
            names #(print/text (vec (sort-by str %)) (:part print/budgets))]
        (str "main moved since your base: " (count moved) " names changed"
             (when (seq ours) (str "; ones you changed too: " (names ours)))
             (when (seq reached) (str "; ones your changes call: " (names reached)))
             (when (seq conflicts) (str "; a merge of main would conflict on: " (names conflicts)))
             ". Merge it when you choose: (my.branch/merge-main!)")))))

(defn main-difference
  "main-line for this agent now: its merge base, its branch and main as last heard (:main, held by
  heard-main, which each turn's readers call); which an outside agent's seon_eval answers carry
  (seon.repl/eval-string, seon.server op-answer :eval; a model agent's turn reads the recorded eval,
  which holds no :main); nil with no base, or when main as last heard is still the base. Computed at
  t, never stored, and it reads no store: a read of main's head (heard-main, seon.db/head,
  datahike.writing/read-head) on each eval held the agent's heap past its ceiling in
  seon.agent-test/an-agent-finds-no-repository (OutOfMemoryError, exit 70). So an agent that never
  hears main (no turn) is told what it last heard; telling it main moved needs root to push the commit
  (task seon.writer/main-notices-carry-a-commit-not-a-db)."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (when-let [base (:base @state)]
    (when-let [main (:main @state)]
      (when-not (= (d/commit-id base) (d/commit-id main))
        (main-line base (db) main)))))

(defn untaken-entries
  "The entries main holds to the runs of this agent's session (the runs on its branch, as main
  names them) that its branch does not hold yet, in main's order (by transaction, then id): each {:id :kind :from :to :body}.
  Delivery is the fact, read here, not the frame root pushes to a live process: a session whose
  process is gone (parked in an earlier boot, a lost wake, a red verdict on a run whose process
  ended) is spawned again, and its run takes them from main (design one-derivation 4.1 section 5;
  the frame alone left such a run with nothing, parked short of main's count, and spawned again).
  O(the entries to the session's runs)."
  {:malli/schema [:=> [:cat] [:vector [:map [:id :string] [:kind :seon.message/kind] [:from :string] [:to :seon.run/id] [:body :string]]]]}
  []
  (let [main (heard-main)
        b (db)
        br (session-branch main)]
    (->> (when br (d/q '[:find ?tx ?id ?kind ?from ?rid ?body :in $ ?br
                          :where [?r :seon.run/branch ?br] [?r :seon.run/id ?rid]
                          [?m :seon.message/to ?r ?tx] [?m :seon.message/id ?id] [?m :seon.message/from ?from] [?m :seon.message/body ?body]
                          [(get-else $ ?m :seon.message/kind :message) ?kind]]
                        main br))
         (remove (fn [[_ id]] (d/entity b [:seon.message/id id])))
         (sort-by (juxt first second))
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
  (let [main (heard-main)
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
  [entries] (into [] (mapcat (fn [{:keys [id kind from to body]}]
                               ;; the agent that takes it, beside its run (:seon.message/agent: its record's messages)
                               (update (task/message-tx id kind from to body) 0 assoc :seon.message/agent {:seon.agent/key (:key @state)})))
                  entries))

(defn taken!
  "The first n messages of the inbox leave it: their reader recorded them (seon.turn/basis!
  takes them once their facts are committed on this branch). nil."
  {:malli/schema [:=> [:cat nat-int?] :nil]}
  [n] (swap! state update :inbox #(vec (drop n %))) nil)
(defn send-message
  "Send body to the agents tos (ids); root delivers it by id. nil."
  {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (send! {:op :send :to (vec tos) :body body}) nil)

(def ^:dynamic *waiting*
  "True while the loop waits for an answer of its own (await-op!): a :tests request taken there is
  read inline, as the wait already holds the loop."
  false)

(defn- await-op!
  "Wait for {:op op :k k} from root; a delivery lands in the inbox (arrived!), a request of op
  in-place (e.g. :tests, whose service may need this agent to run them) is taken meanwhile, and
  anything else waits in :pending for the loop. The process ends when root is gone."
  {:malli/schema [:function [:=> [:cat :keyword :int] :seon.wire/seon-msg]
                  [:=> [:cat :keyword :int [:maybe :keyword]] :seon.wire/seon-msg]]}
  ([op k] (await-op! op k nil))
  ([op k in-place]
   (loop []
     (let [m (recv-raw!)]
       (cond
         (= m :dead) (lib/c-exit 0)
         (and (= op (:op m)) (= k (:k m))) m
         (arrived! m) (recur)
         ;; a request taken in place, and what this process's own fibers hand the loop (a test
         ;; child's lines and its finished run: a wait on the test service's answer is answered by them)
         (or (and in-place (= in-place (:op m))) (#{::say ::ran} (:op m)))
         ;; taken inside a wait for its own answer (prove!): the wait is the loop's already, so a :tests
         ;; read here reads its child inline (*waiting*), never handed to a fiber
         (do (doseq [r (binding [*waiting* true] (handle! m))] (send! r)) (recur))
         :else (do (swap! state update :pending conj m) (recur)))))))


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

(defn take-main!
  "Take what main holds for this agent onto its branch, committed: its entries
  (untaken-entries) and the facts of its own entities (untaken-facts). The one take, a turn's
  (seon.turn/take!) and its code's (messages). Answers the entries taken."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [es (untaken-entries)
        tx (into (untaken-facts) (entries-tx es))]
    (when (seq tx) (transact! tx))
    (commit!)
    es))

(defn messages
  "The words to this agent not yet taken, taken (take-main!, as a turn takes them), then the
  inbox's sends (inbox). Its reader is the code that read them."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [es (take-main!)]
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
      (send! (merge opts {:op :fx :k k :kind kind :req req}))
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
        main (heard-main)
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
  rows and verdict (seon.cost/proof's :actual, a generated case's arguments left to the message
  that prints them) kept as data under :cost (session W2's agent
  page renders them; the judge persists them on the run); a typed error's :seon.error/type,
  :ticks and :budget (a test past its tick budget)."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [m var]
  ;; a generated case's arguments (a row's :args) stay out: they may be any value, which prints
  ;; as no EDN (#error {\n, #'v) and splits the line the test child streams (run-tests-forked);
  ;; the proof's message names each case over its bound by its arguments, printed
  (let [cost (when (map? (:actual m)) (update (select-keys (:actual m) [:rows :verdict]) :rows #(some->> % (mapv (fn [r] (dissoc r :args))))))]
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
  graph-index), reused while base is the point (seon.db/point) it was made for: a value never changes,
  so neither does its graph (AGENTS §5, results reused by input), and the db itself is not held. A new base (merge-main!, a
  resumed branch's older one) is the graph held updated to it (seon.program/reach-update, in
  either direction), never built again: the graph held is the one this process was forked
  with, main's, kept by the fork server (:graph), and built here only when there is none or
  its :data-refs are not inputs'. Measured on Seon's program (2,616 forms): the
  graph 640 ms, 816 MB; a one-form change's update and index 20-45 ms."
  {:malli/schema [:=> [:cat :seon.host/db :seon.program/inputs] :map]}
  [base inputs]
  (let [{w-at :point g0 :graph :as w} (:base-graph @state)
        at (pdb/point base)]
    (if (and w (= at w-at) (= inputs (:inputs w)))
      (:value w)
      (let [g (cond (not (and g0 (= (:data-refs inputs) (:data-refs g0)))) (program/reach-graph base (:data-refs inputs))
                    (= at w-at) g0
                    :else (program/reach-update g0 base))
            v {:graph g :index (program/graph-index g inputs nil)}]
        (swap! state assoc :base-graph {:point at :graph g :inputs inputs :value v})
        v))))

(defn judges?
  "Whether test var t judges a branch, as the test plan selects (test-plan): a deftest (:test) of a
  namespace that is not scratch (seon.program/scratch-ns?: user, or ^:scratch), which never merges."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [t] (let [m (some-> (find-ns (symbol (namespace t))) (ns-resolve (symbol (name t))) meta)]
        (boolean (and (:test m) (not (program/scratch-ns? (:ns m)))))))

(defn test-inputs
  "The reach inputs of db's tests: [shared in], shared the :inputs root's binary was built with
  (:seon.root/source, its boot's), or nil; in those, else none of the tree's ({:env \"\" :tree \"\" :config {}} and
  system.edn's declared refs)."
  {:malli/schema [:=> [:cat :seon.host/db] [:tuple [:maybe :map] :seon.program/inputs]]}
  [db]
  (let [root (d/entity db [:seon.root/id :root])
        source (:seon.root/source root)
        shared (when (= (:boot source) (:seon.root/boot root)) (:inputs source))]
    [shared (or shared {:env "" :tree "" :config {} :data-refs (fx/declared-refs (cfg/value :seon.config.root/system-path))})]))

(defn branch-graph
  "The reach graph of branch db under in, the one test-plan selects by: base's graph (base-graph)
  updated to db (seon.program/reach-update), or db's own with no base; reused while db is the point
  (seon.db/point) it was made for (a value never changes, AGENTS section 5), so a second ask of one db is a
  key lookup; a new db is base's graph updated again, at the cost of what the branch changed
  from base (20-45 ms for one form). Holds the point of the newest asked, never its db."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :seon.program/inputs] :map]}
  [db base in]
  (let [{w-at :point g :graph w-in :inputs} (:branch-graph @state)
        at (pdb/point db)]
    (if (and g (= at w-at) (= in w-in))
      g
      (let [b (when base (base-graph base in))
            g (if b (program/reach-update (:graph b) db) (program/reach-graph db (:data-refs in)))]
        (swap! state assoc :branch-graph {:point at :graph g :inputs in})
        g))))

(defn reconcile-index
  "The reconciler's index of db (seon.reconcile/index), advanced from the one this process held for
  the db it last asked about by what differs, at first from main's as the fork server handed it
  (seon.fork-server/index-at-fork); listed whole only when it holds none. What seon.context/status
  asks for main's claims. It keeps the commit it is of, never the db. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [{from :db c :commit idx :index} (:index @state)
        ;; the db it is of, made again from its commit: a db held past its moment pins its index (AGENTS 4)
        from (or from (some->> c (d/commit-as-db (:store db))))
        v (if idx (r/index idx from db) (r/index db))]
    (swap! state assoc :index {:commit (d/commit-id db) :index v})
    v))

(defn branch-changes
  "What branch db changed since base, its merge base (seon.merge/delta-changes, declared data
  left out): read once per (base, db) pair and held while db is the newest asked, as
  branch-graph is, so a turn's readers (its contract gaps, what it will merge, the problems it
  caused, its test plan, my.branch/changes) take one diff, and a test child forked after it
  inherits it. {} with no base."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :seon.host/db] :seon.merge/changes]}
  [base db]
  ;; the two points are seon.db/point inlined: the lookup is declared one call (seon.program-cost-test/a-turns-held-diff-is-a-lookup)
  (let [{w-base :base w-at :point ch :changes} (:branch-changes @state)]
    (cond (nil? base) {}
          (and ch (= [(d/commit-id base) (:max-tx base)] w-base) (= [(d/commit-id db) (:max-tx db)] w-at)) ch
          :else (let [ch (pm/delta-changes base db #{})]
                  (swap! state assoc :branch-changes {:base (pdb/point base) :point (pdb/point db) :changes ch})
                  ch))))

(defn test-plan
  "What judges branch db, as this process holds it: seon.proofs/plan of its tests' reach digests
  (seon.program/graph-index) against base's (main at its merge base), the greens stored at
  those digests (the verdict cache, greens and reds, whoever ran them: seon.fx/stored-results) and proofs. The digests' inputs with no program facts are this boot's
  :inputs on root's :seon.root/source fact (seon.root/import-source!: present only when root's
  binary is built from its source tree), so an agent's results are kept by digest in the one
  verdict cache (seon.fx/write-verdicts!;
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
  :digests {test digest}, :graph (the reach graph it
  walked) and :process, the tests that reach a process of their own (seon.program/graph-index, the
  same walk as the digests): never run automatically, only when named (DECISIONS, Process tests
  are not automatic). The tests base holds that the branch removed (seon.merge/divergence's
  :removed, each a test var on base) are selected too, with their source as base holds it
  (:removed {test src}): main's tests judge a branch whether or not it still holds them (owner:
  \"deleting the old test doesn't stop the old test from running\"). Given main (its db), the
  results recorded there are found too: results land on main."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:set :qualified-symbol]]
                   [:map [:selected [:vector :qualified-symbol]] [:run [:vector :qualified-symbol]] [:reused [:map-of :qualified-symbol :map]]
                   [:digests [:map-of :qualified-symbol :string]] [:graph :map] [:process [:set :qualified-symbol]]
                   [:removed [:map-of :qualified-symbol :string]]]]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:set :qualified-symbol] [:maybe :seon.host/db]]
                   [:map [:selected [:vector :qualified-symbol]] [:run [:vector :qualified-symbol]] [:reused [:map-of :qualified-symbol :map]]
                   [:digests [:map-of :qualified-symbol :string]] [:graph :map] [:process [:set :qualified-symbol]]
                   [:removed [:map-of :qualified-symbol :string]]]]]}
  ([db base proofs] (test-plan db base proofs nil))
  ([db base proofs main]
  (let [[_ in] (test-inputs db)
        b (when base (base-graph base in))
        g (branch-graph db base in)
        idx (into {} (filter (comp judges? key)) (program/graph-index g in nil))
        cd (update-vals idx :digest)
        unrecorded (sort-by str (filter #(and (judges? %) (not (cd %))) proofs))
        ;; main's tests the branch removed: each judges from base's definition, loaded in the test
        ;; child, its namespace's ns form first when the branch removed the namespace too; one
        ;; that reaches a process of its own (base's index says) stays a process test
        removed (into (sorted-map-by #(compare (str %1) (str %2)))
                      (when base
                        (for [v (:removed (pm/divergence (branch-changes base db)))
                              :let [e (some->> (pdb/eid base :var/name v) (d/entity base))
                                    nsym (symbol (namespace v))
                                    head (when-not (find-ns nsym)
                                           (some->> (pdb/eid base :ns/name nsym) (d/entity base) :ns/form :form/src))]
                              :when (= :test (:var/kind e))]
                          [v (str (when head (str head "\n")) (:form/src (:var/form e)))])))
        removed-process (program/process-tests (select-keys (:index b) (keys removed)))]
    ;; the verdicts cached at the digests (every run's, whoever ran it: seon.fx/stored-results); a
    ;; cache that could not be read is the plan's :unread, which a run answers (never zero greens)
    (let [found (fx/stored-results cd) unread (when (contract/error? found) found)]
      (cond-> (-> (proofs/plan (some-> b :index (update-vals :digest)) cd (if unread {} found) proofs)
                  (update :selected into (concat unrecorded (keys removed)))
                  (update :run into (concat unrecorded (keys removed)))
                  (assoc :digests cd :graph g :removed removed
                         :process (into (program/process-tests idx) removed-process)))
        unread (assoc :unread unread))))))

(defn stored-results
  "The result of each of tests at its reach digest on this branch now, as stored ({test result}, each
  result as its run cached it, its :failures among it), for each with a verdict at that digest in
  the one cache (seon.fx/stored-results: test-plan's :reused), whichever run cached it (a named prove, a bare one, a turn's,
  root's judge); a test with none is left out (unknown). Cost: the test plan's walk (its digests),
  once per call."
  {:malli/schema [:=> [:cat [:maybe [:set :qualified-symbol]]] [:map-of :qualified-symbol :map]]}
  [tests]
  (if (empty? tests)
    {}
    (select-keys (:reused (test-plan (db) (:base @state) (set tests) (heard-main))) tests)))

(declare handle!)

(defn prove!
  "Ask the test service (seon.prove) to prove this branch at its head, and wait for its answer:
  the run's (run-tests-forked's answer, with :key). The service runs one run per key whoever
  asks, the key the digest of what the run reads (seon.program/program-digest of this branch's
  head, as seon.root/prove! takes it): a session's request and this ask are one run; when it is
  this agent's to run, root's :tests request arrives while it waits and is taken here. Other
  messages wait for the loop. :dead when root is gone (the process then ends). mode :turn (a
  turn's observation, the default) or :judge (root's judge at the run's end: its :judge request);
  names, the tests of those namespaces only (none: all it selects)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:enum :turn :judge]] :map]
                  [:=> [:cat [:enum :turn :judge] [:set :symbol]] :map]]}
  ([] (prove! :turn))
  ([mode] (prove! mode #{}))
  ([mode names]
  (commit!)
  ;; the turn's one diff, read here at the committed db, so the test child forked for this run
  ;; inherits it and the turn's other readers find it held (branch-changes)
  (branch-changes (:base @state) (db))
  (let [k (:tx-seq (swap! state update :tx-seq inc))]
    (send! (cond-> {:op :prove :k k :t (:max-tx (db)) :digest (program/program-digest (db)) :mode mode}
             (seq names) (assoc :names names)))
    (:result (await-op! :reply k :tests)))))

(defn- load-removed!
  "In a test child, define each of main's tests the branch removed (test-plan's :removed, {test
  src}) from base's source, in its own namespace (its ns form first when the branch removed
  that too), so it runs as main holds it. nil."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol :string]] :nil]}
  [removed]
  (doseq [[t src] removed]
    (binding [*ns* (create-ns (symbol (namespace t)))] (load-string src)))
  nil)

(defn- run-tests-here
  "The tests a test-plan runs (var symbols), by the one runner (seon.proofs/run-tests!, nothing
  stored: the parent holds the greens), with the branch's schemas in effect (malli's dynamic
  registry bound to seon.program/schema-registry, as the agent's REPL binds it), each test
  within ticks engine ticks of work of its own (the runner's bound): answers report data. Each
  test var is written to w as it starts ({:seon.test/started v}), so the agent can name the test
  a death interrupted, and its reports as it ends ({:seon.test/ended v :reports [...]}), so a
  death loses the test it interrupted and no other (run-tests-forked runs the rest in a new
  child); a test past its ticks is an :error report of the typed
  :seon.test/ticks-spent naming it; a test its namespace's throw kept from reporting is an
  :error report carrying that throw (the runner reports it)."
  {:malli/schema [:=> [:cat :int nat-int? [:sequential :qualified-symbol]] :seon.test/result]}
  [w ticks tests]
  (let [reports (atom []) current (atom [])
        started (atom nil)
        note! (fn [x]
                (cond (:seon.test/started x) (do (reset! started (str "#'" (:seon.test/started x)))
                                                 (sock/write-line! w (sedn/encode {:seon.test/started @started})))
                      (:seon.test/report x) (swap! current conj (test-report (:seon.test/report x) (str "#'" (:test x))))
                      ;; a test's end: its result, after its reports, and the live heap it left (a
                      ;; collection, then the heap's bytes: what it holds for the tests after it, docs/cost.md#what-a-test-run-measures)
                      (:test x) (do (sock/write-line! w (sedn/encode {:seon.test/ended (str "#'" (:test x)) :reports @current :fns (:fns x)
                                                                      :ms (:ms x) :ticks (:ticks x) :bytes (:bytes x)
                                                                      :heap (do (System/gc) (lib/bytes-in-heap))}))
                                    (swap! reports into @current)
                                    (reset! current []))
                      :else nil))
        _ (binding [mr/*registry* (program/schema-registry (program/schema-changes (db)))]
            (proofs/run-tests! tests (zipmap tests (repeat nil)) note! ticks))
        reports @reports
        counts (frequencies (map :type reports))]
    {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :test (count (distinct (keep :var reports)))
     :reports reports}))

(defn child-line
  "One line a test child streamed (run-tests-forked), decoded, or :seon.test/unreadable-line
  naming the line's head (the printer's cut) when it does not read: a value that prints as no
  EDN (#error {\\n splits the line, #'v is no EDN) in what the child sent. The failure point's
  edge (docs/failure-points.md): the test it was about ends :error and the agent goes on."
  {:malli/schema [:=> [:cat :string] :any]}
  [line]
  (try (sedn/decode line)
       (catch Throwable e
         {:seon.error/type :seon.test/unreadable-line
          :seon.error/message (str "a line the test process sent does not read (" (ex-message e) "): " (print/text line (:part print/budgets)))})))

(defn take-line
  "acc ({:streamed :ended :fns :heap}, what a test child's lines said so far) after one more line:
  a test started is the one running (:start, for its caller to tell root); one ended adds its
  reports, its var, its functions' costs and the live heap it left; a line that does not read (child-line) ends the
  test running :error with the line's head, once, and the reading goes on."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [acc line]
  (let [x (child-line line) running (:running acc) acc (dissoc acc :start)]
    (cond (contract/error? x)
          (if (contains? (:told acc #{}) running)
            acc
            (-> acc (update :told (fnil conj #{}) running)
                (update :streamed conj (cond-> {:type :error :seon.error/type (:seon.error/type x) :message (:seon.error/message x)}
                                         running (assoc :var running)))))
          (:seon.test/started x) (assoc acc :running (:seon.test/started x) :start (:seon.test/started x))
          (:seon.test/ended x) (let [t (:seon.test/ended x)]
                                 (cond-> (-> acc (update :streamed into (:reports x)) (update :ended conj t))
                                   (seq (:fns x)) (assoc-in [:fns (proofs/test-symbol t)] (:fns x))
                                   (:heap x) (assoc-in [:heap (proofs/test-symbol t)] (:heap x))
                                   (:ms x) (assoc-in [:measured (proofs/test-symbol t)] (select-keys x [:ms :ticks :bytes]))))
          :else acc)))

(defn test-run-answer
  "The run, from the last line the test child wrote on its pipe before EOF or its exit (nil:
  none), its exit status, and held? (the child exited and its pipe was still open: a process it
  forked holds the write end): that line when it is the run's result; else the child died while
  running the test the line's {:seon.test/started var} names, and the run is one :error report on
  that test var with the status as :exit (none started: a run error naming the status). A held
  pipe adds an :error report of its own (:seon.test/pipe-held)."
  {:malli/schema [:=> [:cat [:maybe :string] :seon.lib/exit-status :boolean] :seon.test/result]}
  [line status held?]
  (let [v (some-> line child-line) var (:seon.test/started v)
        res (cond (contains? v :pass) v
                  (contract/error? v) {:pass 0 :fail 0 :error 1 :reports [(assoc v :type :error :message (:seon.error/message v) :exit status)]}
                  :else {:pass 0 :fail 0 :error 1
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
  means it is held, and a read that ended on part of a line goes on. Each whole line is given to
  seen as it is read (the tests as they start, run-tests-forked). Answers {:line :held?}, or
  :seon.lib/unwatched."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd pos-int?] [:or [:map [:line [:maybe :string]] [:held? :boolean]] :seon.lib/unwatched]]
                  [:=> [:cat :seon.lib/fd pos-int? :seon.host/fn] [:or [:map [:line [:maybe :string]] [:held? :boolean]] :seon.lib/unwatched]]]}
  ([r pid] (read-to-exit r pid (fn [_] nil)))
  ([r pid seen]
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
                    (seq ls) (do (run! seen ls) (recur (peek ls)))
                    ;; nothing there yet, or part of a line: read again
                    (or (not @exited) (not @waited)) (recur line)
                    ;; exited, and a read found nothing there: the pipe is still open, held
                    :else {:line line :held? true})))
          (finally (close))))))))

(defn run-selection
  "What a test-plan p runs when a request names namespaces (names; none: every test it selects), as
  p with :selected and :run restricted to them. A test that reaches a process of its own (p's
  :process: a root, an agent, a fork, the binary) never runs here, named or a proof: a child forked
  from an agent has no process store to start one in (bin/test makes it), so it errored. It is left
  out and listed under :skipped, and runs at the judge's merge gate on a checkout (seon.reconcile's
  :gate; a deferred proof is no reason of the done rule's). proofs is kept for the contract's
  callers: bin/test's own plan, on a checkout, still runs a named or proof process test
  (seon.proofs/left-out)."
  {:malli/schema [:=> [:cat :map [:maybe [:set :symbol]] [:set :qualified-symbol]] :map]}
  [p names proofs]
  (let [in? #(contains? names (symbol (namespace %)))
        p (if (seq names) (-> p (update :selected #(filterv in? %)) (update :run #(filterv in? %))) p)
        skip? (proofs/left-out (:selected p) (:process p) false #{})]
    (-> p (assoc :skipped (vec (sort-by str (filter skip? (:selected p)))))
        (update :selected #(filterv (complement skip?) %)) (update :run #(filterv (complement skip?) %)))))

(defn- test-child
  "Fork this agent to run tests in a child (run-tests-here) and read it to its end: {:answer
  (test-run-answer: the child's final line, or the one :error report of the test it died in),
  :streamed (the reports of the tests that ended, in order), :ended (their vars, as strings),
  :pid, :exit}, or the fork's refused answer. forked is told the child's pid; each test as it starts goes
  to root (what this agent waits on, which a stall names) through say, the loop's send! or what
  hands the loop a message to send."
  {:malli/schema [:=> [:cat :map nat-int? [:sequential :qualified-symbol] :seon.host/fn :seon.host/fn] [:or :map :seon.test/fork-refused]]}
  [plan ticks tests forked say]
  (let [[r w] (sock/socketpair)
        lines (atom {:streamed [] :ended #{} :fns {} :heap {}})]
    (lib/fork!
     {:refused (fn [n] (lib/c-close r) (lib/c-close w)
                 (contract/error :seon.test/fork-refused #'seon.agent/test-child
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
               ;; the test child's own ceiling, from its own heap at its fork: the agent's absolute ceiling
               ;; left a child of a grown agent less room than any test needs (docs/machine.md#what-a-process-owns-in-memory)
               (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes)) (cfg/value :seon.config.agent/trip-bytes))
               (swap! state assoc :mode :test :evaluating false)
               ;; the test's own working directory: what a test writes relative to it is this run's, removed
               ;; below when the child has ended (docs/failure-points.md, a directory walk)
               (let [run-dir (fx/run-dir (lib/c-getpid))] (.mkdirs (java.io.File. ^String run-dir)) (lib/c-chdir run-dir))
               (let [res (try (load-removed! (:removed plan)) (run-tests-here w ticks tests)
                              (catch Throwable e {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]}))]
                 ;; the reports went as their tests ended: this line is the run's end and its counts
                 (sock/write-line! w (sedn/encode (assoc res :reports [])))))
      :parent (fn [pid]
                (lib/c-close w)
                (forked pid)
                (let [read (read-to-exit r pid #(when-let [t (:start (swap! lines take-line %))] (say {:op :testing :test t})))
                      st (lib/wait-status pid)]
                  (lib/c-close r)
                  (fx/remove-tree! (fx/run-dir pid))
                  (if (contract/error? read)
                    read
                    (-> (select-keys @lines [:streamed :ended :fns :heap :measured]) (assoc :answer (test-run-answer (:line read) st (:held? read)) :pid pid :exit st)))))})))

(defn- record-results!
  "Record a test run's results on this branch (task seon.proofs/test-results-are-a-few-facts, seon.proofs/result-tx: only what differs from the
  facts the branch holds, so a run that changes nothing writes nothing): ran, failures and the
  cached results reused, as seon.proofs/result-tx takes them, of the tests the branch holds a var
  entity for, deferred (the process tests the run left to root's gate) as :deferred at their digest,
  removed ({test status}, main's tests the branch removed, judged each run from main's definition) as
  this run's verdict on their name, and the facts of each other test whose digest moved (digests, the
  run's plan) retracted. See docs/merging.md#verdicts-are-a-cache.
  Answers the datoms written, or the typed :seon.agent/unrecorded when the branch's schema
  lacks the facts' attributes (a branch cut before they existed), or when the branch refuses the write
  (docs/failure-points.md, a refused write in a test run's results): the run still answers, with it."
  {:malli/schema [:=> [:cat :map :map :map [:map-of :qualified-symbol :string] [:set :qualified-symbol] [:map-of :qualified-symbol :keyword]] [:or nat-int? :seon.error/base]]}
  [ran failures reused digests deferred removed]
  (let [db (db)]
   (if-not (contains? (d/schema db) :seon.test/failures)
    (contract/error :seon.agent/unrecorded #'record-results! "this branch's schema has no :seon.test/* attributes: the run's results stay in the cache only" {})
   (let [pattern [:var/name :seon.test/digest :seon.test/status :seon.test/failures :seon.test/runs :seon.test/ms :seon.test/ticks :seon.test/bytes]
        ;; the tests this run judged, and every test the branch holds a verdict of (its digest may have moved)
        held (d/q '[:find [?e ...] :where [?e :seon.test/digest]] db)
        ran-es (keep #(pdb/eid db :var/name %) (distinct (concat (keys ran) (keys reused) deferred)))
        old (into {} (map (fn [m] [(:var/name m) (dissoc m :var/name)])) (d/pull-many db pattern (vec (distinct (concat held ran-es)))))
        ;; a test with no var entity here (loaded, never recorded) has no fact to hold
        ;; main's tests the branch removed judge from main's definition each run, with no reach digest of the
        ;; branch's (never cached): their verdict is this run's, on their name
        rm (vec (for [[t st] removed :let [o (some->> (pdb/eid db :var/name t) (d/pull db [:seon.test/status :seon.test/failures]))
                                            status (if (= :pass st) :green :red) fs (when (= :red status) (not-empty (vec (failures t))))]
                      :when (or (not= status (:seon.test/status o)) (not= fs (:seon.test/failures o)))]
                  [(cond-> {:var/name t :seon.test/status status} fs (assoc :seon.test/failures fs))
                   (when (and (:seon.test/failures o) (not fs)) [:db/retract [:var/name t] :seon.test/failures (:seon.test/failures o)])]))
        tx (-> (proofs/result-tx old (select-keys ran (keys old)) failures (select-keys reused (keys old)) digests (into #{} (filter old) deferred))
               (into (comp cat (remove nil?)) rm))]
    ;; the write is the edge: a branch that refuses it answers :unrecorded with the writer's message, so
    ;; the run's thunk (finish, called on the loop, not in the asker's eval) still answers the asker
    (if (seq tx)
      (try (:datoms (transact! tx))
           (catch Throwable e (contract/error :seon.agent/unrecorded #'record-results! (str "the branch refused the run's results: " (ex-message e)) {})))
      0)))))

(defn run-tests-forked
  "Fork this agent; the child runs what test-plan runs for proofs (the selected tests with no
  green held at their digest), each test within ticks engine ticks of work of its own, writing
  each test var as it starts and then one EDN line of its result on a pipe. The agent reads the
  pipe to its end: no clock; the child ends by finishing or by dying (then the last test it
  started is named). Each selected test with a result at its digest is reused, marked :reused: a
  green as one :pass report, a red as the failures its run recorded (the test service's facts,
  seon.fx/stored-results: each assertion's type, message, expected and actual); when every
  one is, nothing is forked (no :pid). The counts are its reports' types, as a run's are. One bound in every mode
  (:seon.config.agent/test-ticks, per test), so a test's verdict is the same in a turn, the
  judge and bin/test, and the judge after a turn reruns no green the turn left.
  Each test that ran is cached at its digest, green or red (seon.fx/write-verdicts!), and its result
  recorded as facts on this branch beside it (record-results!, in every mode but :main); a write the
  cache refused is named under :unstored, and those tests run again. A cache that cannot be read
  answers its :seon.fx/io-error (test-plan's :unread): no test is run as if none were cached. :ran is how many tests reported. (forked pid) runs here once the child exists.
  A fork refused (threads active) is :seon.test/fork-refused: no test ran.
  Root's ruling (a): mode :turn (a turn's observation) selects the proofs and the
  tests the branch itself changed or added; mode :judge (root's judge at the run's end, and
  the test service's runs of a branch) selects the plan's tests; mode :main (the test service's
  run of main, whose prove agent is a branch of main with nothing changed) selects them with no
  base: every test with no result at its digest, main's own tests among them. names (the test service's request: a set of
  namespaces) restricts either to the tests of those namespaces. A process test (the plan's
  :process: it reaches an agent, a fork, a root or the binary; a shell call is none) never runs here unless its namespace
  is named; the ones the change reaches run at the judge's merge gate, on a checkout (seon.reconcile's :gate).
  The answer names those left out (:deferred), why each was (:why, {test {:uses :through}}: the
  process var it reaches and the calls to it, seon.program/process-path), and how many the judge
  runs (:at-judge). Given handoff (root's :tests request taken by the idle loop, on a main
  carrier), the loop never waits on the children: (handoff go) is called and this answers
  :seon.agent/running; go, called on a fiber of handoff's with its own say (what hands the loop a
  message to send: the loop is the one sender), runs the children to their end and answers the
  thunk that finishes the answer, which the loop calls (only the loop takes from the intake)."
  {:malli/schema [:function [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn [:maybe [:set :symbol]]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn [:maybe [:set :symbol]] [:maybe :seon.host/fn]]
                   [:or :seon.test/result :seon.test/fork-refused :seon.error/base [:= :seon.agent/running]]]]}
  ([ticks proofs mode] (run-tests-forked ticks proofs mode (fn [_] nil)))
  ([ticks proofs mode forked] (run-tests-forked ticks proofs mode forked nil))
  ([ticks proofs mode forked names] (run-tests-forked ticks proofs mode forked names nil))
  ([ticks proofs mode forked names handoff]
  (let [plan (test-plan (db) (when-not (= :main mode) (:base @state)) proofs (heard-main))]
  (if-let [unread (:unread plan)]
    unread
  (let [{judge :selected deferred :skipped} (run-selection plan names proofs)
        selected (if (and (= :turn mode) (not (seq names)))
                   ;; the proofs as the plan selects them (one loaded but never recorded has
                   ;; no digest and is selected all the same), and the tests the branch changed
                   (vec (sort-by str (filter (set judge) (-> (set (filter proofs (:selected plan)))
                                                             (into (filter (:digests plan) (pm/changed-vars (branch-changes (:base @state) (db)))))
                                                             (into (keys (:removed plan)))))))
                   judge)
        {:keys [reused digests]} plan
        run (filterv #(not (reused %)) selected)
        ;; a red reused reports the failures its run recorded, so it judges as it did
        reused-reports (vec (for [t selected :let [r (reused t)] :when r
                                  f (or (seq (:failures r))
                                        [(cond-> {:type (if (proofs/green? r) :pass (if (pos? (:error r)) :error :fail))}
                                           (not (proofs/green? r)) (assoc :message "red at this digest, with no failure kept"))])]
                              (cond-> (assoc f :var (str "#'" t) :reused true)
                                ;; a cost proof's rows, as its fresh run reported them (seon.proofs/costs)
                                (and (:cost r) (identical? f (first (:failures r)))) (assoc :cost (:cost r)))))
        t0 (profile/now-ns)
        ;; every selected test green at its digest (a turn's run of the same tree, a session's):
        ;; nothing to run, so no child is forked
        run-all (fn [say]
            (if (empty? run)
              {:pass 0 :fail 0 :error 0 :test 0 :reports [] :ms 0}
              ;; a child that dies loses the test it was running (its :error stays) and the rest
              ;; run in a new child: the death of one test never costs the run its other results
              (loop [todo run reports [] fns {} heap {} measured {}]
                (let [c (test-child plan ticks todo forked say)]
                  (if (contract/error? c)
                    c
                    (let [died (filter :exit (:reports (:answer c)))
                          reports (-> reports (into (:streamed c)) (into (:reports (:answer c))))
                          done (into (:ended c) (keep :var) died)
                          rest-todo (filterv #(not (done (str "#'" %))) todo)
                          counts (frequencies (map :type reports))]
                      ;; a death with something to show for it (a test ended, or the one it died in is
                      ;; named) leaves the rest to a new child; none, and the run ends on it
                      (if (and (seq rest-todo) (seq died) (or (seq (:ended c)) (some :var died)))
                        (recur rest-todo reports (merge fns (:fns c)) (merge heap (:heap c)) (merge measured (:measured c)))
                        {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :test (count (distinct (keep :var reports)))
                         :reports reports :fns (merge fns (:fns c)) :heap (merge heap (:heap c)) :measured (merge measured (:measured c)) :ms (profile/ms-since t0) :pid (:pid c) :exit (:exit c)})))))))
        ;; the answer, once the run is read (by the loop: it records and it sends)
        finish (fn [res]
    ;; what ran, cached by digest (seon.fx/write-verdicts!), whoever asked for the run; a file the
    ;; cache would not take is named on the answer (:unstored), and those tests run again
    (if (contract/error? res)
      res
      (-> res
        (as-> r (let [{:keys [ran failures]} (proofs/answer-ran (assoc r :digests digests))
                      ran (into {} (for [[t x] ran] [t (merge x (get-in r [:measured t]))]))
                      w (fx/write-verdicts! {:id (str (name (:id @state)) " " (random-uuid)) :branch (:branch @state) :agent (:id @state)} ran failures)]
                  ;; beside the cache, the same run's results as facts on this branch (the record shows them)
                  ;; not main's run: it judges main's tree (no :base), whose facts are main's prove's to write
                  (let [rec (when-not (= :main mode) (record-results! ran failures (select-keys reused selected) digests (set deferred)
                                                                     (select-keys (proofs/results r) (keys (:removed plan)))))]
                  (-> (cond-> r (contract/error? w) (assoc :unstored [(:seon.error/message w)])
                        (contract/error? rec) (assoc :unrecorded [(:seon.error/message rec)]))
                      (dissoc :measured)))))
        (update :reports into reused-reports)
        (as-> r (merge-with + r (select-keys (frequencies (map :type reused-reports)) [:pass :fail :error])))
        (assoc :digests (select-keys digests (concat (keep (comp proofs/test-symbol :var) (:reports res)) (filter reused selected))))
        (assoc :reused (count (filter reused selected)) :ran (count (distinct (keep :var (:reports res))))
               :selected selected :deferred deferred :at-judge (count judge))
        ;; why each was left out: the process it reaches and the calls to it (seon.program/process-path)
        (cond-> (seq deferred) (assoc :why (into {} (for [t deferred] [t (program/process-path (:graph plan) t)])))))))]
    (cond
      ;; nothing to run, or no fiber to read them on: the answer now
      (or (empty? run) (nil? handoff)) (finish (run-all send!))
      :else (do (handoff (fn [say] (let [res (run-all say)] #(finish res)))) ::running)))))))

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

(defn state-summary
  "The agent's process state m as the printer shows it (seon.render/summarized, the schema
  :seon.agent/state): each key with its value when that is a scalar, else what it holds, a count
  for a collection and its type for a host object (a database, a connection, a descriptor),
  with the form that reads it whole. A bare (swap! seon.agent/state ...) answers the state: it
  costs what the state names, not what it holds."
  {:malli/schema [:=> [:cat :map] :map]}
  [m]
  (into (array-map)
        (for [[k v] m
              :let [read (list 'get (list 'deref 'seon.agent/state) k)]]
          [k (cond (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) v
                   (coll? v) {:count (count v) :read read}
                   :else {:host (str (type v)) :read read})])))

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
    ;; taken by the idle loop (on a main carrier), the loop never waits on the children: a fiber runs
    ;; them and hands the loop what to send root (::say) and then the thunk that finishes the
    ;; answer (::ran); taken inside a wait for its own answer (*waiting*), read inline
    ;; the answer is the run as the done rule reads it, bounded by its reds (seon.proofs/answer): the
    ;; whole run is cached by digest before it, so nothing is lost, and the frame stays small
    :tests (let [ch (when-not *waiting* (intake))
                 say (if ch #(a/>!! ch {:op ::say :msg %}) send!)
                 proofs (or (some->> (:task m) (task/task (db)) :seon.task/accumulated :proofs) #{})
                 answered #(if (and (map? %) (not (contract/error? %))) (proofs/answer % proofs) %)
                 r (run-tests-forked (or (:ticks m) (cfg/value :seon.config.agent/test-ticks))
                                     proofs
                                     (or (:mode m) :judge)
                                     #(say {:op :tests-started :id (:id m) :pid %})
                                     (some-> (:names m) set)
                                     (when ch
                                       ;; a run that throws hands the loop its throwable (go-monitor),
                                       ;; raised where the answer is made, as an inline run raises it
                                       (fn [go]
                                         (let [g (a/io-thread (go say)) died (a/go-monitor g)]
                                           (a/io-thread
                                             (let [done (or (a/<!! g) (let [e (a/<!! died)] #(throw e)))]
                                               (a/>!! ch {:op ::ran :id (:id m) :finish (comp answered done)})))))))]
             (when-not (= ::running r) [{:op :tests :id (:id m) :result (answered r) :mem-mb (mem-mb)}]))
    ::say [(:msg m)]
    ::ran [{:op :tests :id (:id m) :result ((:finish m)) :mem-mb (mem-mb)}]
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

(defn hello! {:malli/schema [:=> [:cat :int :map] [:= true]]}
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
                        ;; the task it was cloned for (a fork's sibling task, seon.server's), else its parent's
                        (swap! state assoc :task (or (:task m) (:task @state)) :id (:agent m) :key (key-of (pdb/head store-conn (pdb/main-branch (:cfg r))) (:agent m)) :branch (:branch m) :fd fd :conn (sock/conn fd) :mode :agent :tx-seq 0
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

(defn begin-run!
  "Run run of task recorded as this process's session's on its branch, as root records it on main:
  the conversation finds the messages its runs took by it (seon.context/records), and @ctx holds
  the run with its task. Written as the process starts with its run (init!: an outside agent's
  branch is cut before its run is written on main), or by the turn that begins a later run of the
  session; once, nothing written when the branch holds it. The task is running, as main holds it
  (its release, written on main after the branch was cut, read :held here: my.task/current and the
  page's task said held); a task's execution is no content a merge carries (seon.merge/carried-platform-attrs).
  A task the branch lacks (a fork's: its branch is cut from another task's) is written as main
  declares it first (seon.task/create-tx)."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :any]}
  [task run]
  (when-not (some-> (d/entity (db) [:seon.run/id run]) :seon.run/agent)
    (when-not (d/entity (db) [:seon.task/id task])
      (let [t (task/task (heard-main) task)]
        (transact! (task/create-tx (db) [(-> (select-keys t [:seon.task/ns :seon.task/name :seon.task/plan :seon.task/instructions :seon.task/proofs
                                                             :seon.task/needs :seon.task/branch :seon.task/model :seon.task/kind])
                                             (update :seon.task/ns symbol))]))))
    (transact! [{:seon.task/id task :seon.task/execution :running}
                ;; the plan the run began with, as main records it at a run's start (seon.task/run-start-tx), when
                ;; the branch was cut before it: what a design step's done rule compares (:no-design)
                (cond-> {:seon.run/id run}
                  (not (:seon.run/plan (d/entity (db) [:seon.run/id run])))
                  (assoc :seon.run/plan (:plan (:seon.task/accumulated (task/task (db) task)))))
                {:seon.run/id run :seon.run/task [:seon.task/id task] :seon.run/agent {:seon.agent/key (:key @state) :seon.agent/id (:id @state)
                                                         ;; the agent's task, which its record pulls (the agent is the unit, docs/ctx.md#the-pattern);
                                                         ;; nested: a top-level agent map takes a content id, one the key's upsert conflicts with
                                                         :seon.agent/task {:seon.task/id task}}}])))

(defn init!
  "Make this process the agent: its socket (fd), its namespace, its state map, its
  interrupt handlers. serve is the loop a clone runs (seon.repl/serve!)."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :seon.host/fn] :any]}
  [fd {id :agent branch :branch task :task run :run cfg :cfg resumed :resumed commit :commit t :t graph :graph index :index session :key
       marks0 :marks} db0 serve]
  ;; the spawn's phases on the one clock, each with what this thread allocated in it: the fork
  ;; server's marks (marks0), then this process's, sent with its hello (spawn-phases)
  (let [marks (volatile! (conj (vec marks0) [:child-init (profile/now-ns) (profile/alloc-bytes)]))
        mark! (fn [k] (vswap! marks conj [k (profile/now-ns) (profile/alloc-bytes)]))]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
  ;; the config this process was forked holding (root read it; an agent reads no environment)
  (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes)) (cfg/value :seon.config.agent/trip-bytes))
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
    ;; read main's later changes as the branch's (branch-changes)
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
     ;; its key as root named it in its boot (seon.root's :spawn), never pulled from main here: that
     ;; pull cost what the inherited node cache lacked, 24-183 KB a spawn
     (reset! state (cond-> {:id id :key session :branch branch :task task :run run :fd fd :conn c :db @store-conn
                   :base base :main db0 :store-conn store-conn :cfg cfg :results {} :n 0
                   :load-errors (vec load-errors) :misaligned misaligned :unwatched (sock/signal-at-input! fd)
                   :pending clojure.lang.PersistentQueue/EMPTY :inbox [] :tx-seq 0 :mode :agent
                   :ns nsx :interrupt-ok intr-ok :serve serve}
                   ;; main's reach graph as the fork server keeps it, for db0: test-plan's base
                   ;; graph starts from it (base-graph), never built in this process
                   graph (assoc :base-graph {:point (pdb/point db0) :graph graph})
                   ;; main's reconciler index as the fork server keeps it, for db0: the first turn
                   ;; observes the branch from it (seon.agent/reconcile-index), never lists it here
                   index (assoc :index index)))
     (swap! state vary-meta assoc :seon.render/schema :seon.agent/state)
     (mark! :state)
     (swap! state assoc :marks @marks)
     ;; a kept branch, resumed (root released its held task: seon.reconcile/hold-tx), takes
     ;; its task from main, with the failure and evidence the last run left there; an
     ;; agent with no task (a fresh one on a kept branch) takes none
     (when-let [tx (when (and resumed task) (not-empty (task/onto-tx db0 @store-conn task)))]
       (transact! tx))
     ;; forked for a run: it is this session's on the branch from the start
     (when (and task run) (begin-run! task run))))))
