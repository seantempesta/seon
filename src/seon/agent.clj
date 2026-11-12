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
  breaks its forks; docs/failure-points.md, \"Physics\". Agent code calls the functions of my.db and my.agent,
  referred into its namespace. From proto/flow f2-final proto.agent (session F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.error :as err] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.doc :as doc] [seon.ref :as ref] [seon.merge :as pm] [seon.config :as cfg] [seon.reconcile :as r] [seon.vector :as vector] [seon.lib :as lib] [seon.print :as print] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [seon.program :as program] [seon.proofs :as proofs] [seon.fx :as fx] [seon.task :as task] [seon.intr :as intr] [seon.render :as render] [seon.template :as template] [seon.graph :as graph] [clojure.repl]
            [malli.registry :as mr] [malli.core] [jolt.analyzer] [clojure.core.async :as a] [jolt.io-poller :as poller]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :task (the task root forked it for, or nil) :db :base (main at the branch's merge base: as forked, then each merge-main!) :main (main's head as last read: as forked, then heard-main) :results :n :waits (seon.agent/await-reply's promises, by key) :busy (the :repl proc runs a request) :tx-seq :mode :ns :home :mismatch
;; :embedded-from (the db embed-written! last read the branch's writes up to)
;; :unembeddable (the entities whose text llama.cpp could not encode, as vectors-proc keeps them)
;; :base-graph (test-plan's reach graph of its base, keyed by the base's point (seon.db/point: commit and t, never the db), from the fork server's :graph of main: base-graph)
;; :branch-graph (the reach graph of the branch's db, keyed by its point, updated from the base's: branch-graph)
;; :branch-changes (what the branch changed since its base, seon.merge/delta-changes, keyed by the two points: branch-changes)
;; :main-changes (what main changed since the base, seon.merge/delta-changes, keyed by the two commits: main-changes)
;; :index (seon.agent/reconcile-index: the reconciler's index and the commit it was observed for (the fork hands the db once), advanced each turn by what differs; at fork, main's from the fork server)
;; :schema-registry (seon.repl: the branch's schema registry, by its schema changes)
;; :tx (the last transaction transact! applied, {:db-after :tx-data}: seon.ctx patches @ctx from it: the db :db is, dropped by commit! when it moves :db)
;; :refused (the :seon.agent/commit-refused errors since the agent last answered: commit!, take-refused!)
;; :eval (the id of the eval running, "e<its eid>", while seon.repl/eval-string runs it: what transact! names in its tx-meta)
(defonce state (atom nil))

(defonce ^{:doc "This process's socket to root, {:fd :conn :ch}, its one reader's channel among it (intake): the
  process's own, apart from state, so forms that replace state (a test's helper resetting it at the REPL)
  leave the process able to answer root and reading with one reader (a test that runs a measure in the agent must not leave it dead on its next reply). See docs/processes.md#the-socket-and-the-agent-proc."}
  link (atom nil))


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
  [m] (if (= :epipe (sock/send! (:fd @link) m)) (lib/c-exit 0) true))

(defn intake
  "This process's one intake: a channel of root's messages ({:op k ...}), closed at EOF or root's
  end frame, read off root's socket by one fiber (sock/recv! parks it on the socket's readiness,
  and keeps a descriptor sent with a frame for its handler: the clone's socket, take-fd!). The
  :transport proc's in-port (seon.repl/transport-step), the socket's one reader. Made when first
  asked on this connection (a clone's is its own: its connection is new).
  See docs/processes.md#the-socket-and-the-agent-proc."
  {:malli/schema [:=> [:cat] :seon.host/chan]}
  []
  (let [{c :conn ch :ch} @link]
    (or ch
        ;; unbuffered: the reader parks with each message until the transport takes it, so root's
        ;; further frames wait in the socket's own buffer
        (let [ch (a/chan)]
          (a/io-thread (loop [] (let [m (sock/recv! c)]
                                  (if (= :eof m) (a/close! ch) (when (a/>!! ch m) (recur))))))
          (swap! link assoc :ch ch)
          ch))))

(declare commit!)

;; ---- the agent's own API ------------------------------------------------------

(defn- no-branch!
  "Throws the typed :seon.agent/no-branch: this process is no agent (root's REPL), and what was called is an agent's API,
  which reads and writes its branch. See docs/server.md#ops."
  {:malli/schema [:=> [:cat] :any]}
  []
  (throw (ex-info "no branch" (contract/error :seon.agent/no-branch #'no-branch!
                                              "this is root's REPL, which has no branch; my.agent, my.db, my.task and my.branch are an agent's API"
                                              {:seon.error/fix "Read main at root: (d/q query @(:conn sys)), (d/pull @(:conn sys) pattern eid), my.root/*. Or run the form in an attached agent: seon_eval with its id."}))))

(defn db
  "This agent's branch as a db value; the typed :seon.agent/no-branch in a process that has none (`no-branch!`). See docs/server.md#ops."
  {:malli/schema [:=> [:cat] :seon.host/db]}
  [] (or (:db @state) (no-branch!)))

(declare handle!)

(defn inbox
  "The effect outcomes delivered to this agent and not yet taken ({:from :fx :k :kind :body}), left in the inbox as each
  arrives (handle!'s :fx-reply). Every message to the agent, a person's or another agent's, is an entity on main, which its turn
  delivers (seon.turn/deliver!), never the inbox. See docs/users.md#messages."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (:inbox @state))

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
  and so pin the dbs they were read from, is let go at once (a kept db value is a
  reference: docs/storage.md#a-kept-db-value-is-a-reference); the newest pair is held on the state (:main-changes), evicted by the next and
  regenerated from the two dbs. {} when main's point is base's.
  See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map-of :any :int]]}
  [base main]
  (let [at (juxt d/commit-id :max-tx)
        k [(at base) (at main)]
        {held :key after :after} (:main-changes @state)]
    (cond (= (k 0) (k 1)) {}
          ;; main's content mark is base's: only records moved main (docs/storage.md#mains-content-mark)
          (pm/same-content? base main) {}
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
  hears main (no turn) is told what it last heard; telling it main moved needs root to push the commit."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (when-let [base (:base @state)]
    (when-let [main (:main @state)]
      (when-not (= (d/commit-id base) (d/commit-id main))
        (main-line base (db) main)))))

(defn main-moved?
  "Has main (main, as last heard) moved past this branch's merge base (base)? The one comparison main-line's
  report starts from, by commit. See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]}
  [base main]
  (boolean (and base main (not= (d/commit-id base) (d/commit-id main)))))

(defn undelivered
  "The messages main holds to this agent that its branch does not hold yet (seon.task/undelivered, which says how). Main is read only
  here, through `heard-main`: where messages live is this one read. Delivery is the fact, read here, not a frame root pushes to a
  live process: a process spawned again reads what came while none lived. See docs/users.md#messages."
  {:malli/schema [:=> [:cat] [:vector [:map [:id :string] [:from :map] [:body :string] [:sent-ms :int]]]]}
  [] (task/undelivered (heard-main) (db) (:key @state)))

(defn untaken-facts
  "The transaction that copies onto this branch what main holds and its branch does not, of its
  own entities: each eval of its own its agent's :seon.agent/opened names, as that eval's
  :seon.eval/opened. take! is the one way outside facts reach the branch, and so its ctx (a
  change the agent's context and page show goes through the data its context reads). O(the opened evals on main)."
  {:malli/schema [:=> [:cat] :seon.db/tx-data]}
  []
  (let [main (heard-main)
        b (db)]
    (vec (for [id (:seon.agent/opened (d/entity main [:seon.agent/key (:key @state)]))
               :let [e (d/entity b [:seon.eval/id id])]
               :when (and e (not (:seon.eval/opened e)))]
           {:seon.eval/id id :seon.eval/opened true}))))

(defn turn-now
  "The agent's turn number in force: its :seon.agent/turn on this branch, 0 before its first model turn. Every record
  the agent writes names that turn's model call (:seon.eval/call: seon.task/call-ref). See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat] nat-int?]}
  [] (or (:seon.agent/turn (d/entity (db) [:seon.agent/key (:key @state)])) 0))

(defn own-message-tx
  "Message mid from from (its sender's lookup, seon.task/sender), sent at sent-ms: the one message entity (seon.task/message-tx) as this agent's branch holds it, `to` its own ref only. Its being on the branch is its delivery, and the turn that
  took it is derived from the transaction that wrote it (seon.ctx/placed-messages). Its sender is written by its identity (seon.task/sender),
  an upsert, so a sender made after this branch forked resolves here too. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :string :map :any :int] :seon.db/tx-data]}
  [mid from body sent-ms]
  (task/message-tx mid from [(:key @state)] body sent-ms))

(defn entries-tx
  "The transaction that writes entries (undelivered) on this branch: the entity root wrote on main, with this agent's ref only (own-message-tx)."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.db/tx-data]}
  [entries] (into [] (mapcat (fn [{:keys [id from body sent-ms]}] (own-message-tx id from body sent-ms))) entries))

(defn taken!
  "The first n entries of the inbox (effect outcomes, which no model turn reads) leave it at a turn's take (seon.turn/take!). nil."
  {:malli/schema [:=> [:cat nat-int?] :nil]}
  [n] (swap! state update :inbox #(vec (drop n %))) nil)
(defn send-message
  "Send body to the agents tos (ids): root writes it as a message fact on main, to each one's branch, whether or not its
  process lives (seon.system/deliver-message). A body that is not text is its text by the one printer, whole, as the message
  is. nil. See docs/users.md#messages."
  {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body]
  (send! {:op :send :to (vec tos)
          :body (if (string? body) body (print/text (cond-> body (instance? clojure.lang.IObj body) (vary-meta assoc :seon.print/full true))))})
  nil)

(defn await-reply
  "Send root m, a request of this process's own key k ({:op .. :k k}), and wait for root's
  answer to it, the message carrying k: the :transport proc delivers it to the promise held
  under k in :waits (seon.repl/transport-step), put there before m is sent, so no answer
  arrives unawaited, and every other message goes where it is owed meanwhile. A wait an
  interrupt stops lets its promise go; the answer that comes later goes where an unawaited one
  does (handle!). Root gone ends the process (the transport's end). See
  docs/processes.md#the-socket-and-the-agent-proc."
  {:malli/schema [:=> [:cat :int :seon.wire/seon-msg] :seon.wire/seon-msg]}
  [k m]
  (let [p (promise)]
    (swap! state assoc-in [:waits k] p)
    (try (send! m) @p
         (finally (swap! state update :waits dissoc k)))))

(defn mem-mb
  "This process's memory as the platform charges it, read now (lib/real-mb: one call): on the
  events that report the agent's state (its hello, each commit, a :tests reply, :stats),
  never per evaluation and never kept for a time."
  {:malli/schema [:=> [:cat] :seon.lib/mb]}
  [] (lib/r2 (lib/real-mb)))

(defn- refused!
  "The agent after a commit its branch refused (e, what the writer threw): the run-ahead writes since the last good
  commit dropped (datahike.writer/abandon-pending!), :db the committed value again, :dirty? and :tx cleared, the schema
  registry read again, and the error kept in :refused for the next answer (`take-refused!`). Answers it as the
  typed :seon.agent/commit-refused. See docs/storage.md#a-commit-the-store-refuses."
  {:malli/schema [:=> [:cat :any :any] :seon.agent/commit-refused]}
  [conn e]
  (let [{:keys [tx-count reports]} (dw/abandon-pending! conn)
        evals (into [] (comp (keep #(get-in % [:tx-meta :seon.tx/eval])) (distinct)) reports)
        err (contract/error :seon.agent/commit-refused #'commit!
                            (str "the branch refused these writes: " (ex-message e) ". The " tx-count " transactions since the last commit that held were dropped"
                                 (when (seq evals) (str " (those of " (str/join ", " evals) ")"))
                                 ", so what your code wrote to the branch did not stay, and its record is not on the branch. Definitions it evaluated are still in this process, not on the branch.")
                            {:transactions (or tx-count 0) :evals evals})]
    (swap! state #(-> % (assoc :db (d/db conn)) (dissoc :dirty? :tx :schema-registry) (update :refused (fnil conj []) err)))
    err))

(defn take-refused!
  "The commits this agent's branch refused since it last answered (`refused!`), taken: one :seon.agent/commit-refused, the
  last's message over the sum of their dropped transactions and the eval ids among them, or nil when none was.
  See docs/failure-points.md."
  {:malli/schema [:=> [:cat] [:maybe :seon.agent/commit-refused]]}
  []
  (when-let [es (seq (:refused @state))]
    (swap! state dissoc :refused)
    (assoc (last es) :transactions (reduce + (map :transactions es)) :evals (into [] (comp (mapcat :evals) (distinct)) es))))

(defn launch!
  "Ask root to make a released task of text, from template (a name in seon.template/templates), which the reconciler gives an agent of its own: root's answer, {:task id} or its refusal. See docs/agent.md#working-for-a-person."
  {:malli/schema [:=> [:cat :string :keyword] :map]}
  [text template]
  (let [k (:tx-seq (swap! state update :tx-seq inc))]
    (:result (await-reply k {:op :launch :k k :text text :template template}))))

(defn focus!
  "Ask root to move the signed-in user's view to the page of the agent with key k: root's answer, or its refusal (no user signed in, no such agent). See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :string] :any]}
  [k]
  (let [n (:tx-seq (swap! state update :tx-seq inc))]
    (:result (await-reply n {:op :focus :k n :key k}))))

(defn note-costs!
  "fns (seon.profile/touched of an eval) added to what this agent's process has measured since it started, (:fn-costs @state):
  an observation of this process on this machine, never written to the store; nothing in a process that is no agent's. See docs/sharing.md#a-functions-cost-is-a-fact."
  {:malli/schema [:=> [:cat [:vector :seon.profile/fn-cost]] :any]}
  [fns]
  (when (:store-conn @state)
    (swap! state #(assoc % :fn-costs (profile/accrue (:fn-costs % {}) fns (:eval %))))))

(declare transact!)

(defn commit!
  "Commit the writes this agent applied (run-ahead) to its branch, once, through its own
  store handle, and tell root (its flush makes the commit durable). nil when nothing waits. A
  commit the store refuses (a value it cannot hash) answers the typed :seon.agent/commit-refused and drops those
  writes (`refused!`), never throws: no caller's loop ends on it. See docs/failure-points.md."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [conn (or (:store-conn @state) (no-branch!))
        c (try (dw/commit-pending! conn) (catch Throwable e e))]
    (cond
      (instance? Throwable c) (refused! conn c)
      c (do
          (swap! state #(-> % (assoc :db (:db c)) (dissoc :dirty? :tx)))
          ;; the eval errors folded since the last commit ride it (seon.error/note): root counts
          ;; them across agents
          (let [errs (:errors @state)]
            (when errs (swap! state dissoc :errors))
            (send! (cond-> {:op :committed :t (:max-tx (:db c)) :txs (:tx-count c) :mem-mb (mem-mb)} errs (assoc :errors (vec (vals errs))))))))))

(defn- with-citations
  "tx-data, which writes a task's plan or instructions, with what that text cites derived in it
  (seon.task/rederive-tx over the db it makes), so no db holds the new text with the old citations,
  whoever wrote it (a swap! of ctx, my.task/update!, a transact! of the agent's own).
  See docs/references.md#task-references."
  {:malli/schema [:=> [:cat :seon.host/db :seon.db/tx-data] :seon.db/tx-data]}
  [db tx-data]
  (let [r (pdb/speculate db tx-data) db' (:db-after r)
        ids (into [] (comp (filter #(#{:seon.task/plan :seon.task/instructions} (:a %))) (keep #(:seon.task/id (d/entity db' (:e %)))) (distinct)) (:tx-data r))]
    (into (vec tx-data) (mapcat #(task/rederive-tx db' (task/task db' %))) ids)))

(defn- with-slot
  "State st holding its branch's schema slot {:key changes :registry r :index ix}, built from its :db (seon.program/schema-changes,
  seon.db/index-of), and :index-builds, how many times this agent built one. See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [k (program/schema-changes (:db st))]
    (-> st
        (assoc :schema-registry {:key k :registry (program/schema-registry k) :index (pdb/index-of {:forms k})})
        (update :index-builds (fnil inc 0)))))

(defn schema-moved
  "State st with its schema slot built again (`with-slot`) when attrs, the attributes a write to :db wrote, hold a schema entry's
  (:schema/*), else as it is: the one build of the entity index that write costs, which the slot's readers (the registry, the ctx,
  the page walk) are handed, never building their own. See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map [:set :any]] :map]}
  [st attrs] (cond-> st (some #(and (keyword? %) (= "schema" (namespace %))) attrs) with-slot))

(defn branch-schemas
  "This agent's branch's schema changes, their registry and the entity index they make, {:key changes :registry r :index ix}: the one slot
  of its state, built by the write that moves a schema entry (`schema-moved`), by a fork carrying main's (seon.fork-server/warm-start) or, when a
  refused commit dropped it, here at the next use. Reading the changes is two passes over every schema entry, so no eval or write reads them while none moved. See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat] :map]}
  []
  (or (:schema-registry @state) (:schema-registry (swap! state with-slot))))

(defn branch-index
  "This agent's branch's entity index (`branch-schemas`): what its renders and its recognition of a value take as ix (seon.db/index-of);
  the binary's when no branch is held (root, a scratch session: it has no schema entries of its own). See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat] :map]}
  [] (if (:db @state) (:index (branch-schemas)) (pdb/index)))

(defn- config-moved!
  "This process's config held again when a transaction of its own branch wrote attrs naming an entry or a schema entry (a schema its config is read against): one set lookup otherwise. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :seon.host/db [:set :any]] :any]}
  [db attrs]
  (when (or (attrs :config/form) (attrs :schema/form)) (program/hold-config! db)))

(defn transact!
  "Write tx-data to this agent's branch: applied at once to the value ahead of the
  committed one (its answer does not wait for a commit), committed when the agent is idle
  or at a turn's end, never because time passed (owner: the agent-owned run-ahead
  writer). A write of a task's plan or instructions carries what the text cites (`with-citations`).
  Answers {:t :datoms :tempids}. Inside a test (a fork of the agent) it applies to
  the fork's db only. A refused transaction throws its message into the caller's eval."
  {:malli/schema [:=> [:cat :seon.db/tx-data] :seon.agent/tx-result]}
  [tx-data]
  (let [text? #{:seon.task/plan :seon.task/instructions}
        tx-data (if (some #(if (map? %) (some text? (keys %)) (and (vector? %) (text? (nth % 2 nil)))) tx-data)
                  (with-citations (db) tx-data)
                  tx-data)]
    ;; the branch's own schema changes go with the write: the one transaction path checks it (seon.db/with, the writer's 'transact!)
    (let [schemas (let [{:keys [key registry]} (branch-schemas)] (when (seq key) {:forms key :registry registry}))]
    (when-let [e (program/config-refused (db) tx-data)] (throw (ex-info (:seon.error/message e) e)))
    (if (= :test (:mode @state))
      (let [r (pdb/with (db) tx-data schemas) attrs (into #{} (map :a) (:tx-data r))]
        (doc/carry-index! (:db-before r) (:db-after r) (map (juxt :e :a) (:tx-data r)))
        (swap! state #(-> % (assoc :db (:db-after r) :tx (select-keys r [:db-after :tx-data])) (schema-moved attrs)))
        (config-moved! (:db-after r) attrs)
        {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r)) :local true})
      (let [r (dw/apply! (:store-conn @state) {:tx-data tx-data :seon.db/schemas schemas :tx-meta (pdb/tx-meta (:branch @state) (cond-> {} (:eval @state) (assoc :seon.tx/eval (:eval @state))))})]
        ;; a typed refusal (seon.db/invalid-entity) reaches the eval as itself; any other carries the writer's words
        (when-not (map? r) (throw (if (:seon.error/type (ex-data r)) r (ex-info (str "transact refused: " (ex-message r)) {:writer-error (ex-message r)}))))
        ;; :tx, the transaction that moved :db: what seon.ctx patches @ctx from; the search's text
        ;; indexes and the schema registry carried past it when it wrote none of their attributes
        (let [attrs (into #{} (map :a) (:tx-data r))]
          ;; from the db the writer applied it to (its report's), whatever the state held
          (doc/carry-index! (:db-before r) (:db-after r) (map (juxt :e :a) (:tx-data r)))
          (swap! state #(-> % (assoc :db (:db-after r) :tx (select-keys r [:db-after :tx-data]) :dirty? true) (schema-moved attrs)))
          (config-moved! (:db-after r) attrs))
        (cond-> {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r))}
          (seq (:tempids r)) (assoc :tempids (into {} (filter (comp string? key)) (:tempids r)))))))))

;; ---- effects (seon.fx, worktree session FX1): root's owner procs, through the agent's proc ---

(defn deliver!
  "Bring what main holds for this agent onto its branch, committed: its messages (undelivered) and the facts of
  its own entities (untaken-facts). The one delivery, a turn's (seon.turn/deliver!) and its code's (messages). A message
  on the branch is delivered; the turn that read it is derived from its transaction (seon.ctx/placed-messages). Answers the
  messages delivered. See docs/users.md#messages."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [es (undelivered)
        tx (into (untaken-facts) (entries-tx es))]
    (when (seq tx) (transact! tx))
    (commit!)
    es))

(defn messages
  "The messages to this agent not yet delivered, delivered (deliver!, as a turn delivers them: the entities on
  main to it, another agent's sends included), then the effect outcomes of the inbox. Delivered, a message stays in `@ctx` until the agent
  removes it (`(swap! ctx update :seon.message/_to dissoc e)`), and is not delivered again. Each delivered one's :from is its sender's name
  (seon.task/sender-name: an agent's key, \"seon\" for the system), what the agent reads of who sent it; the entity is the ref. See docs/users.md#messages."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [es (mapv #(update % :from task/sender-name) (deliver!))]
    (let [[old _] (swap-vals! state assoc :inbox [])] (into es (:inbox old)))))

(def ^:dynamic *fx-stub*
  "Inside a test (a fork of this agent) no effect leaves the process: a test that needs
  one binds this to a fn of [kind req] answering the outcome, an injected value."
  nil)

(defn- fx-done!
  "The effect k is no longer pending (:fx-pending, its kind by key). Answers its kind."
  {:malli/schema [:=> [:cat :int] [:maybe :keyword]]}
  [k]
  (let [kind (get-in @state [:fx-pending k])]
    (swap! state update :fx-pending dissoc k)
    kind))

(defn- fx-test
  "An effect asked inside a test fork: the stub's outcome, or the declared error."
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [kind req]
  (if *fx-stub*
    (*fx-stub* kind req)
    (contract/error :seon.fx/in-test #'seon.agent/fx-test "an effect inside a test does not leave the test's process: bind seon.agent/*fx-stub*"
                    {})))

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
    (let [k (:tx-seq (swap! state update :tx-seq inc))
          _ (swap! state update :fx-pending (fnil assoc {}) k kind)
          r (dissoc (await-reply k (merge opts {:op :fx :k k :kind kind :req req})) :op :k)]
      (fx-done! k)
      r)))

;; ---- the branch's own vectors (docs/vector.md#what-is-embedded) ----------------------------

(defn embed-written!
  "The texts this branch wrote since the last call (since its fork, at the first: after
  my.branch/merge-main!, main's texts merged in too, which carry no vector), its task's plan and the queries of its task's searches,
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
         ;; the task's plan and the queries of the searches it holds (its request's among them), written on
         ;; main before the fork: what the first page searches by meaning (docs/vector.md#search)
         plan (when-let [te (some->> (:task @state) (pdb/eid head :seon.task/id))]
                (into (vec (d/datoms head :eavt te :seon.task/plan))
                      (mapcat #(d/datoms head :eavt (:db/id %) :seon.search/query))
                      (:seon.search/_of (d/entity head te))))
         due (remove (comp (get @state :unembeddable #{}) first)
                     (vector/due head model (distinct (into (vector/written (get @state :embedded-from (:base @state)) head) plan))))
         ask (take n due)]
     (if (empty? ask)
       (do (when (empty? due) (swap! state assoc :embedded-from head)) 0)
       (let [r (fx! :embed {:texts (mapv second ask)} {})]
         (cond
           (= :seon.vector/encode-failed (:seon.error/type r)) (do (swap! state update :unembeddable (fnil into #{}) (map first ask)) r)
           (contract/error? r) r
           :else (let [{:keys [model vectors]} (:value r)
                       _ (transact! (vector/tx-data (map first ask) (map peek ask) vectors model))]
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
  answered in :task-conflicts. data-attrs: the data that merges beside the program. When main's
  content mark is the base's (seon.merge/same-content?), only records moved main: nothing is
  diffed and nothing merges. Answers {:ok :applied :conflicts :task-conflicts :t}."
  {:malli/schema [:=> [:cat :seon.merge/data-attrs] [:map [:ok :boolean]]]}
  [data-attrs]
  (commit!)
  (let [conn (:store-conn @state)
        main-b (pdb/main-branch (:cfg @state))
        main (heard-main)
        head @conn
        ;; main's content mark is the base's: only records moved main, so nothing merges and nothing is diffed; declared data
        ;; moves no mark, so a merge that declares some reads the diff (docs/storage.md#mains-content-mark)
        same? (and (= #{} data-attrs) (pm/same-content? (:base @state) main))
        ;; git's merge base: the main last merged, unless main has since merged a commit of this branch (its own merge up), which
        ;; is then the base; read from the commit graph only when main moved, over the commits since (docs/sharing.md#a-merge)
        base (if (or same? (= (d/commit-id main) (d/commit-id (:base @state)))) (:base @state) (pdb/branch-base conn main-b (:branch @state)))
        r (if same? {:theirs {}} (pm/delta-three-way base head main data-attrs))]
    (if (empty? (:theirs r))
      {:ok true :applied [] :conflicts [] :t (:max-tx head)}
      (let [carried (task/carry-tx base main head)
            ;; the parent is the commit merged: a branch name would name main's head when this commits, which can be newer than what was merged
            rep (d/merge-db conn #{(d/commit-id main)} (-> (pm/merge-tx head base main (:apply r) data-attrs)
                                               (into (pm/unmerged-tx head (:conflicts r) (:max-tx main)))
                                               (into (:tx carried)))
                             (pdb/tx-meta (:branch @state) {:seon.tx/merged main-b}))
            ops (pdb/report->tx rep)
            errs (program/load-changes! (:db-after rep) ops)]
        ;; the merge base is now main's head, the value just merged, so no walk of the commit graph finds it: what main changed is not this branch's change
        (let [attrs (into #{} (map (fn [op] (nth op 2 nil))) ops)]
          (doc/carry-index! head @conn (map (fn [op] [(nth op 1) (nth op 2)]) ops))
          (swap! state #(-> % (assoc :db @conn :base main) (schema-moved attrs)))
          ;; the same on the branch's fact, or the status, which reads the fact, takes what main changed for this branch's work until a next turn writes it
          (let [merged-t (:max-tx @conn)]
            ;; carried like any write: skipped, the next search rebuilt the index after every merge
            (let [r (d/transact conn [{:seon.agent/key (:key @state) :seon.agent/base-commit (d/commit-id (:base @state))}])]
              (doc/carry-index! (:db-before r) (:db-after r) (map (juxt :e :a) (:tx-data r))))
            (swap! state assoc :db @conn :merged-t merged-t)))
        (send! {:op :committed :t (:max-tx @conn) :txs 1})
        (cond-> {:ok true :applied (:apply r) :conflicts (:conflicts r) :t (:merged-t @state) :datoms (count ops)}
          (seq (:conflicts carried)) (assoc :task-conflicts (:conflicts carried))
          (seq errs) (assoc :load-errors errs))))))

;; ---- evaluation -----------------------------------------------------------------


(def repl-names
  "The names an agent's REPL resolves in any namespace where nothing else does (`repl-name`), by the namespace
  holding each, :all its publics; a name two hold is the first's (clojure.repl's source, source-fn and pst are
  Seon's, interned there under their own names by my.agent's last form, and my.agent's are the same functions). See
  docs/agent.md#the-repl."
  '[[clojure.repl [source source-fn apropos dir pst doc find-doc]] [clojure.pprint [pp pprint]] [my.agent :all] [my.db :all] [seon.ctx [ctx]]
    [clojure.test [deftest is are testing use-fixtures]]])

(def ^:private repl-aliases
  "The aliases an agent's REPL resolves in any namespace that has none of the name (`repl-name`). See docs/agent.md#the-repl."
  '{"d" datahike.api "str" clojure.string})

(defn repl-name
  "The qualified symbol an agent's REPL reads sym as when nothing in its namespace resolves it (Jolt's
  jolt.host/*resolve-unresolved*, bound by seon.repl): a REPL helper (`repl-names`), or a name under one of
  `repl-aliases` (d/q); nil for any other. Nothing is written into a namespace. See docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :symbol] [:maybe :qualified-symbol]]}
  [sym]
  (if-let [a (namespace sym)]
    (when-let [t (get repl-aliases a)] (when (find-ns t) (symbol (str t) (name sym))))
    (some (fn [[from names]]
            (when-let [held (some-> (find-ns from) ns-publics)]
              (when (and (contains? held sym) (or (= :all names) (some #{sym} names)))
                (symbol (str from) (str sym)))))
          repl-names)))

(defn- unresolved-message
  "An unresolved symbol's message without the \"(did you mean ...)\" suffix (the suggestions are
  the error's fix). Its words are copied from `jolt.nrepl/err->msg`'s cause (jolt-core/jolt/nrepl.clj
  at the Jolt pin, reference-code/jolt e0351af8), which builds it inline from the same ex-data and
  cannot be called alone; a pin bump that rewords it is a wording change here."
  {:malli/schema [:=> [:cat :string] :string]}
  [sym] (str "Unable to resolve symbol: " sym " in this context"))

(defn on-the-wire
  "Response r as its reply carries it: its value only when it is plain data (seon.contract/plain?: what the socket and the
  wire carry), never the printed text back, which is no EDN once the eval printed. See
  docs/storage.md#plain-data."
  {:malli/schema [:=> [:cat :map] :map]}
  [r] (cond-> (dissoc r :value) (and (contains? r :value) (contract/plain? (:value r))) (assoc :value (:value r))))

(defn- no-such-schema
  "What ex-data d says when malli has no schema of that name, {:message :fix} with the nearest names it knows, else nil.
  See docs/failure-points.md."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:message :string] [:fix {:optional true} :string]]]]}
  [d]
  (when (= :malli.core/invalid-schema (:type d))
    (let [schema (:schema (:data d))
          near (jolt.analyzer/suggestions-for (str schema) (map str (keys (malli.core/default-schemas))))]
      (cond-> {:message (str "malli has no schema " schema)} (seq near) (assoc :fix (str "did you mean " (str/join ", " near)))))))

(defn error-map
  "An exception caught at one of the agent's entrant boundaries (operation: the REPL's
  seon.repl/eval-code by default) as the one error map: its ex-data when that already is
  one (a contract's error names its operation, member and problems), else the
  :seon.agent/eval-error variant, with the exception's class, its ex-data's diagnostic keys
  (Jolt's :jolt.error/* : line, column, symbol, ns, kind, suggestions; and
  :clojure.error/*) as keys, the suggestions as the fix, any other ex-data as :data, and its cause chain (each cause's class
  and message), and an OutOfMemoryError's heap by type as :jolt/heap (jolt.host/heap-histogram's shape:
  what holds the memory, docs/machine.md#what-a-process-owns-in-memory)."
  {:malli/schema [:function [:=> [:cat :seon.host/throwable] :seon.error/base]
                  [:=> [:cat :seon.host/throwable :qualified-symbol] :seon.error/base]]}
  ([e] (error-map e 'seon.repl/eval-code))
  ([^Throwable e op]
   (if (contract/error? (ex-data e))
     (ex-data e)
     (let [d (ex-data e)
           diag? (comp #{"jolt.error" "clojure.error"} namespace key)
           jd (into {} (filter (every-pred (comp qualified-keyword? key) diag?)) d)
           ;; an OutOfMemoryError's heap by type (Jolt: :jolt/heap): data on the map, never the Throwable
           heap (:jolt/heap d)
           other (not-empty (into {} (remove (some-fn (every-pred (comp qualified-keyword? key) diag?) (comp #{:jolt/heap} key))) d))
           sym (:jolt.error/symbol jd)
           unresolved? (and sym (= :analyze/unresolved-symbol (:jolt.error/kind jd)))
           no-schema (no-such-schema d)
           m (cond no-schema (:message no-schema) unresolved? (unresolved-message sym) :else (step/error-msg e))
           ;; a REPL name in a definition: the program resolves as written, so the definition calls it qualified
           repl (when unresolved? (repl-name (symbol sym)))
           causes (mapv #(str (type %) ": " (ex-message %)) (take-while some? (rest (iterate ex-cause e))))]
       (contract/error :seon.agent/eval-error op m
                       (cond-> (assoc jd :class (str (type e)))
                         (seq (:jolt.error/suggestions jd)) (assoc :seon.error/fix (str "did you mean " (str/join ", " (:jolt.error/suggestions jd))))
                         (:fix no-schema) (assoc :seon.error/fix (:fix no-schema))
                         ;; Clojure's own forms reach a private var
                         (= :analyze/private-var (:jolt.error/kind jd)) (assoc :seon.error/fix (str sym " is private: call it (#'" sym " ...) or read it @#'" sym))
                         repl (assoc :seon.error/fix (str sym " is the REPL's: a definition is the program, which resolves as written, so it calls " repl))
                         other (assoc :data other)
                         heap (assoc :jolt/heap heap)
                         (seq causes) (assoc :causes causes)))))))

(defn clear-end!
  "This wake begins: the end the wake before wrote is taken off this agent's branch, committed, so an end root reads there is this
  wake's own, whether it ran a turn or threw before its first (seon.reconcile/pull-end). See docs/turn.md#the-end."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [k (:key @state) e (d/entity (db) [:seon.agent/key k])
        gone (for [a [:seon.agent/outcome :seon.agent/ended-turn :seon.agent/reason :seon.agent/error :seon.agent/waste] :when (get e a)]
               [:db.fn/retractAttribute [:seon.agent/key k] a])]
    (when (seq gone) (transact! (vec gone)) (commit!))))

(defn write-end!
  "This agent's end on its branch (:seon.agent/outcome and the rest, which root's judge reads), committed: its
  outcome, and when known its reason, the error that ended it (as data) and its waste, naming the turn it came on
  (:seon.agent/ended-turn), and retracting what an earlier end set that this one does not. The turn loop's end and the process's death write it the same way.
  See docs/turn.md#the-end."
  {:malli/schema [:=> [:cat [:map [:outcome :seon.agent/outcome]]] :any]}
  [{:keys [outcome reason error waste]}]
  (let [k (:key @state)
        e (d/entity (db) [:seon.agent/key k])
        now (cond-> {:seon.agent/outcome outcome :seon.agent/ended-turn (turn-now)}
              reason (assoc :seon.agent/reason reason)
              error (assoc :seon.agent/error error)
              (seq waste) (assoc :seon.agent/waste waste))]
    ;; what its functions cost is not written: an observation of this process, kept live in (:fn-costs @state) (docs/sharing.md#a-functions-cost-is-a-fact)
    (transact! (into [(assoc now :seon.agent/key k)]
                     (for [a [:seon.agent/reason :seon.agent/error :seon.agent/waste] :when (and (not (contains? now a)) (get e a))]
                       [:db.fn/retractAttribute [:seon.agent/key k] a])))
    (commit!)))

(defn- interrupt-self-test
  "T1's boot check: SIGUSR1 reaches a Chez handler only in a process whose ancestors were
  all forked from a main thread. Answers whether this agent's handler ran."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [got (volatile! false)]
    (lib/on-signal! lib/SIGUSR1 (fn [] (vreset! got true)))
    (lib/kill! (lib/c-getpid) lib/SIGUSR1)
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
      ;; an error's place: the first frames of where it was thrown, which #error's print leaves out (a spawn's ENOENT named
      ;; no caller, 2026-11-12)
      (instance? Throwable (:actual m)) (assoc :at (proofs/error-at (:actual m)))
      ;; a test past its tick budget (seon.proofs/run-tests!): the typed error and its counts
      (:seon.error/type m) (merge (select-keys m [:seon.error/type :ticks :budget])))))

(defn- base-graph
  "The reach graph of base under inputs, with its index ({:graph :index}: seon.program/
  graph-index), reused while base is the point (seon.db/point) it was made for: a value never changes,
  so neither does its graph (results reused by input: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on), and the db itself is not held. A new base (merge-main!, a
  resumed branch's older one) is the graph held updated to it (seon.program/reach-update, in
  either direction), never built again: the graph held is the one this process was forked
  with, main's, kept by the fork server (:graph) with its index (seon.program/reach-indexed), and built
  here only when there is none or its :data-refs are not inputs'. The graph held keeps its index
  across the update, so the base's is the answers of the tests the move touched, never of every test
  (docs/source.md#tests-run-by-reach)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.program/inputs] :map]}
  [base inputs]
  (let [{w-at :point g0 :graph :as w} (:base-graph @state)
        at (pdb/point base)]
    ;; a point names a value only with its commit (seon.db/point)
    (if (and w (first at) (= at w-at) (= inputs (:inputs w)))
      (:value w)
      (let [g (cond (not (and g0 (= (:data-refs inputs) (:data-refs g0)))) (program/reach-graph base (:data-refs inputs))
                    (and (first at) (= at w-at)) g0
                    :else (program/reach-update g0 base))
            g (program/reach-indexed g inputs)
            v {:graph g :index (program/index-of g)}]
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
  (:seon.system/source, its boot's: seon.system/booted-source, present only when the binary is main's program), or nil; in
  those, else none of the tree's ({:env \"\" :tree \"\" :config {}} and the binary's system.edn's declared refs).
  See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :seon.host/db] [:tuple [:maybe :map] :seon.program/inputs]]}
  [db]
  (let [root (d/entity db [:seon.system/id :root])
        source (:seon.system/source root)
        shared (when (= (:boot source) (:seon.system/boot root)) (:inputs source))]
    [shared (or shared {:env "" :tree "" :config {} :data-refs (fx/declared-refs graph/built-text)})]))

(defn branch-graph
  "The reach graph of branch db under in, the one test-plan selects by: base's graph (base-graph)
  updated to db (seon.program/reach-update), or db's own with no base; reused while db is the point
  (seon.db/point) it was made for (a value never changes: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on), so a second ask of one db is a
  key lookup; a new db is base's graph updated again, at the cost of what the branch changed
  from base (20-45 ms for one form). Holds the point of the newest asked, never its db."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :seon.program/inputs] :map]}
  [db base in]
  (let [{w-at :point g :graph w-in :inputs} (:branch-graph @state)
        at (pdb/point db)]
    (if (and g (first at) (= at w-at) (= in w-in))
      g
      (let [b (when base (base-graph base in))
            g (if b (program/reach-update (:graph b) db) (program/reach-graph db (:data-refs in)))]
        (swap! state assoc :branch-graph {:point at :graph g :inputs in})
        g))))

(defn reconcile-index
  "The reconciler's index of db (seon.reconcile/index), advanced from the one this process held for
  the db it last asked about by what differs, at first from main's as the fork server handed it
  (seon.fork-server/index-at-fork); listed whole only when it holds none. What seon.ctx.status/status
  asks for main's claims. It keeps the commit it is of, never the db. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [{from :db c :commit idx :index} (:index @state)
        ;; the db it is of, made again from its commit: a db held past its moment pins its index (docs/storage.md#a-kept-db-value-is-a-reference)
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
          (and ch (d/commit-id db) (d/commit-id base) (= [(d/commit-id base) (:max-tx base)] w-base) (= [(d/commit-id db) (:max-tx db)] w-at)) ch
          :else (let [ch (pm/delta-changes base db #{})]
                  (swap! state assoc :branch-changes {:base (pdb/point base) :point (pdb/point db) :changes ch})
                  ch))))

(defn- branch-inputs
  "Inputs in, a root's boot's, as branch db's tests read them: the schema class moved by the entries db holds beyond the binary's, which a
  branch can register (program/schema-changes), and, for the files a branch changed since base (:file/text, a script), each one's class and the tree a
  test reading files takes; and held, the config this process's branch states, key by key, when it differs from what the process loaded with, so a branch's edit of a key moves the tests reading that key and no other. A digest no gate's checkout shares only runs more. See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :seon.program/inputs :seon.host/db [:maybe :seon.host/db] :map] :seon.program/inputs]}
  [in db base held]
  (let [changes (program/schema-changes db)
        moved (when base (mapcat identity (d/datoms-diff base db :aevt [:file/text])))
        files (into (sorted-map) (for [dt moved :let [p (:file/path (d/entity db (:e dt)))] :when p] [p (:file/text (d/entity db (:e dt)))]))
        mix (fn [old x] (lib/digest (str old (sedn/encode x))))]
    (cond-> in
      (not= held cfg/config) (update :config merge (fx/config-input held (cfg/checkout)))
      (seq changes) (update-in [:config :seon.env/schemas] mix (vec (sort-by key changes)))
      (seq files) (update :tree mix (vec files))
      (seq files) (update :config (fn [c] (reduce-kv (fn [c p text] (if-let [k (fx/env-class p)] (update c k mix [p text]) c)) c files))))))

(defn test-plan
  "What judges branch db, as this process holds it: seon.proofs/plan of its tests' reach digests
  (seon.program/graph-index) against base's (main at its merge base), the greens stored at
  those digests (the verdict cache, greens and reds, whoever ran them: seon.fx/stored-results) and proofs. The digests' inputs with no program facts are this boot's
  :inputs on root's :seon.system/source fact (seon.system/booted-source: present only when root's
  binary was built from main's program), so an agent's results are kept by digest in the one
  verdict cache (seon.fx/write-verdicts!;
  DECISIONS, Agents share greens: a test green at its digest never runs again, \"especially
  when freshly branched\"; every eval is a recorded form, so the digest covers what ran); a
  root with no such fact this boot shares nothing and reuses nothing (the
  inputs then are blank, which keys nothing stored: it only selects). A test is selected when
  it is a proof or reaches what the branch changed since base (a var, method, fixture, ns form,
  macro or schema entry), and runs when no result is stored at its digest (a red is reused as recorded)
  (seon.reconcile/done-explain reads the selected). Scratch namespaces' tests (user,
  ^:scratch) never merge, so they never judge; seon.template/stall-proofs judge only a task whose proofs name them; a proof with no form on the branch does not run, whatever
  this process holds, so it has no result (:proof-unknown): nothing of it merges.
  With no base (a tree with nothing to diff against: root's on-change runs), the proofs and
  every test with no green at its digest, of which those with no result run (seon.proofs/plan's own rule). Answers the plan with
  :digests {test digest}, :graph (the reach graph it
  walked), :inputs (the branch's inputs it digested by: its root's boot's, with what the branch changed) and :process, the tests that reach a process of their own (seon.program/graph-index, the
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
        ;; one digest whichever runner plans a test (seon.prove/one-digest-whichever-runner-plans-it), and the SAME inputs for
        ;; base and branch, or every test reads as changed by the branch: private contracts as the test child runs them,
        ;; checked (check-private! at its fork), and a process test's config values its root's (index-answer reads them)
        in (-> in (assoc-in [:config :seon.config.contract/private-checked?] "true")
               (assoc :test-system-config (fx/config-input cfg/test-system-config (cfg/checkout))))
        ;; the reach graph is always the agent's base's updated to db, whether or not the selection has a base:
        ;; built from nothing it cost 853 MB of a 1 GiB eval budget where an update costs 290 (a plan of main's
        ;; run, mode :main, selects with no base; measured 2026-10-30, 1,169 tests)
        g (branch-graph db (or base (:base @state)) in)
        ;; the index class the shell's runner takes (seon.program/with-index-code), from this graph, before base is indexed
        in (cond-> in (nil? (get-in in [:config :seon.env/index])) (as-> x (program/with-index-code g x)))
        b (when base (base-graph base in))
        g (branch-graph db (or base (:base @state)) in)
        in (branch-inputs in db (or base (:base @state)) (:config (cfg/in-force)))
        g (program/reach-indexed g in)
        ;; a stall task's proofs read that task's cause, so they judge only a task that names them
        idx (into {} (filter (fn [[t _]] (and (judges? t) (or (contains? proofs t) (not (template/stall-proofs t)))))) (program/index-of g))
        ;; a proof over the branch's data (:data?: its own call of a branch reader, seon.program/test-class-of) reads db's
        ;; point, which no reach holds: it is one more input of its digest (docs/merging.md#verdicts-are-a-cache)
        point (let [[c t] (pdb/point db)] (str c " " t))
        mix #(-> (str % "\ndata " point) lib/digest)
        cd (update-vals idx #(cond-> (:digest %) (:data? %) mix))
        ;; recorded reads (seon.proofs/read-digests): a test at its newest green's digest while all it read is unchanged; the
        ;; reads index made once a commit of this branch and kept in the agent's state, never per ask
        held (let [h (fx/held-reads (vec (keys cd)))] (if (contract/error? h) {} h))
        cd (if (seq held)
             (let [at (pdb/point db)
                   rd (or (when (= at (first (:reads-index @state))) (second (:reads-index @state)))
                          (let [rd (program/reads-index g in {})] (swap! state assoc :reads-index [at rd]) rd))]
               (proofs/read-digests cd rd held))
             cd)
        ;; base's digests take the same point for those tests, so selection still asks what the code changed: a proof over the
        ;; data is selected by its reach, and its verdict is kept and found at the data it read
        bd (some-> b :index (as-> ix (into {} (for [[t {:keys [digest]}] ix] [t (cond-> digest (:data? (idx t)) mix)]))))
        ;; main's tests the branch removed: each judges from base's definition, loaded in the test
        ;; child, its namespace's ns form first when the branch removed the namespace too; one
        ;; that reaches a process of its own (base's index says) stays a process test
        removed (into (sorted-map-by #(compare (str %1) (str %2)))
                      (when base
                        (for [v (filter symbol? (:removed (pm/divergence (branch-changes base db))))
                              :let [e (some->> (pdb/eid base :var/name v) (d/entity base))
                                    nsym (symbol (namespace v))
                                    head (when-not (find-ns nsym)
                                           (some->> (pdb/eid base :ns/name nsym) (d/entity base) :ns/form :form/src))]
                              :when (contains? (set (:var/meta e)) :test)]
                          [v (str (when head (str head "\n")) (:form/src (:var/form e)))])))
        removed-process (program/process-tests (select-keys (:index b) (keys removed)))]
    ;; the verdicts cached at the digests (every run's, whoever ran it: seon.fx/stored-results); a
    ;; cache that could not be read is the plan's :unread, which a run answers (never zero greens)
    (let [stored (fx/stored-results cd) unread (when (contract/error? stored) stored)
          ;; a green is anyone's. A red is this agent's own, so its judge need not run what its turn just recorded, never another's: a digest does not
          ;; see the machine, so another's red, under load, would be told to this agent as its work
          found (when-not unread (into {} (remove (fn [[_ r]] (and (proofs/red? r) (not (:flaky r)) (not= (:key @state) (get-in r [:writer :key]))))) stored))]
      (cond-> (-> (proofs/plan bd cd (if unread {} found) proofs)
                  (update :selected into (keys removed))
                  (update :run into (keys removed))
                  (assoc :digests cd :base-digests bd :graph g :inputs in :removed removed
                         :process (into (program/process-tests idx) removed-process)
                         ;; what a green's run records it read beside the functions it called (seon.proofs/reads-key): each test's
                         ;; reads besides code, and the digest of every read, the agent's one reads index a commit
                         :static-reads (into {} (keep (fn [[t v]] (when (seq (:reads v)) [t (:reads v)]))) idx)
                         :reads-rd (delay (let [at (pdb/point db)]
                                            (or (when (= at (first (:reads-index @state))) (second (:reads-index @state)))
                                                (let [rd (program/reads-index g in {})] (swap! state assoc :reads-index [at rd]) rd))))))
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

(declare record-answer!)

(defn prove!
  "Ask the test service (seon.prove) to prove this branch at its head, and wait for its answer:
  the run's (run-tests-forked's answer, with :key). The service runs one run per key whoever
  asks, the key the digest of what the run reads (seon.program/run-digest of this branch's
  head, as seon.system/prove! takes it): a session's request and this ask are one run; when it is
  this agent's to run, root's :tests request arrives while it waits and the :tests proc serves
  it (seon.repl/tests-step). mode :turn (a
  turn's observation, the default) or :judge (root's judge at the run's end: its :judge request);
  names, those tests and the tests of those namespaces only (none: all it selects)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:enum :turn :judge]] :map]
                  [:=> [:cat [:enum :turn :judge] [:set :symbol]] :map]]}
  ([] (prove! :turn))
  ([mode] (prove! mode #{}))
  ([mode names]
  (commit!)
  ;; the turn's one diff, read here at the committed db, so the test child forked for this run
  ;; inherits it and the turn's other readers find it held (branch-changes)
  (branch-changes (:base @state) (db))
  (let [k (:tx-seq (swap! state update :tx-seq inc))
        answered (volatile! false)]
    (try
      (let [;; its proofs, the task's and those asked for by name: the service answers past a red until each has its verdict
            ;; (seon.prove/item-ended; Sean, 2026-11-11: "have them fix that"), which an ask that named none never got
            proofs (into (or (some->> (:task @state) (task/task (db)) :seon.task/accumulated :proofs) #{}) (filter qualified-symbol?) names)
            r (:result (await-reply k (cond-> {:op :prove :k k :t (:max-tx (db)) :digest (program/run-digest (db)) :mode mode}
                                        (seq names) (assoc :names names)
                                        (seq proofs) (assoc :proofs proofs))))]
        (vreset! answered true)
        ;; its facts on this branch, once an ask, from the answer and this agent's plan of the run
        (if (and (map? r) (not (contract/error? r)))
          (record-answer! r (:held-plan @state) (or (some->> (:task @state) (task/task (db)) :seon.task/accumulated :proofs) #{}))
          r))
      ;; an interrupt ended this wait, the only way one ends before its answer: the test child it was for goes too, so its run answers as a death (no verdict) and nothing is left running
      (finally (when-not @answered (some-> (:test-child @state) (lib/kill! (jolt.host/signal-number "KILL")))))))))

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
  within its budget of engine ticks of work of its own (the runner's: ten times its stated estimate, ticks at most): answers report data. Each
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
                                                                      :ms (:ms x) :ticks (:ticks x) :bytes (:bytes x) :budget (:budget x)
                                                                      :heap (do (System/gc) (lib/bytes-in-heap))}))
                                    (swap! reports into @current)
                                    (reset! current []))
                      :else nil))
        ;; the branch's registry as the REPL binds it: the one held (branch-schemas), never derived again here
        _ (binding [mr/*registry* (:registry (branch-schemas))]
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
                                   (:ms x) (assoc-in [:measured (proofs/test-symbol t)] (select-keys x [:ms :ticks :bytes :budget]))))
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
  exit, whichever comes first: the exit is a value on jolt.io-poller/exit-chan (kqueue's NOTE_EXIT,
  a pidfd on Linux), the fact the fork server reaps by, so a process the child forked that holds
  the write end cannot keep the run waiting. Parks in alts! over the exit and r's readiness (a
  fiber's park). After the exit, what is left in the pipe is read: a read that found nothing there
  (its wait called) reaps the child first (the exit event can come before the kernel has closed the
  dying child's fds: scale read 1-4 of 100 agents' pipes as held), and a read after the reap that
  still finds the pipe open means another process holds it; a read that ended on part of a line goes
  on. Each whole line is given to seen as it is read (the tests as they start, run-tests-forked). The
  child is reaped once, here. Answers {:line :held? :status}, :status its exit status (seon.lib/wait-status)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd pos-int?] [:map [:line [:maybe :string]] [:held? :boolean] [:status :seon.lib/exit-status]]]
                  [:=> [:cat :seon.lib/fd pos-int? :seon.host/fn] [:map [:line [:maybe :string]] [:held? :boolean] [:status :seon.lib/exit-status]]]]}
  ([r pid] (read-to-exit r pid (fn [_] nil)))
  ([r pid seen]
  (let [exit (poller/exit-chan pid)
        ready (volatile! nil)
        exited (volatile! false) waited (volatile! false)
        status (volatile! nil)
        reap! (fn [] (or @status (vreset! status (lib/wait-status pid))))
        ;; the readiness wait stays pending until r is read, so a wait the exit ended leaves none behind
        wait (fn [] (vreset! waited true)
               (when-not @exited
                 (let [c (or @ready (vreset! ready (poller/ready-chan r :read)))
                       [_ port] (a/alts!! [exit c])]
                   (if (= port c) (vreset! ready nil) (vreset! exited true)))))
        rd (sock/line-reader r wait)]
    (loop [line nil]
      (vreset! waited false)
      (let [ls (rd)]
        (cond (= ls :eof) {:line line :held? false :status (reap!)}
              (seq ls) (do (run! seen ls) (recur (peek ls)))
              ;; nothing there yet, or part of a line: read again
              (or (not @exited) (not @waited)) (recur line)
              ;; exited, and a read found nothing there: reaped, every fd the child held is closed, so read again
              (nil? @status) (do (reap!) (recur line))
              ;; and still nothing there after the reap: another process holds the pipe
              :else {:line line :held? true :status @status}))))))

(defn named-tests
  "The test vars names names on db (each a deftest's var, :var/meta holds :test): a qualified name, that test; a
  namespace's, its tests. What a request naming them runs, beside the task's proofs, whether or not the
  branch changed what they reach (a prove naming tests on a branch that changed nothing
  would otherwise answer ran 0, reused 0, each test :none); a named test
  runs alone (not the namespace's other tests, whose reds are not its own). See docs/agent.md#testing.
  Cost: a seek per name, and a namespace's forms' definitions."
  {:malli/schema [:=> [:cat :seon.host/db [:set :symbol]] [:set :qualified-symbol]]}
  [db names]
  (into #{} (for [n names
                  v (if (qualified-symbol? n)
                      (some-> (d/entity db [:var/name n]) vector)
                      (when-let [ne (:db/id (d/entity db [:ns/name n]))]
                        (for [dt (d/datoms db :avet :form/ns ne) v (:form/defines (d/entity db (:e dt)))
                              :when (and (contains? (set (:var/meta v)) :test) (= (str n) (some-> (:var/name v) namespace)))]
                          v)))
                  :when (contains? (set (:var/meta v)) :test)]
              (:var/name v))))

(defn run-selection
  "What a test-plan p runs when a request names tests or namespaces (names; none: every test it selects), as
  p with :selected and :run restricted to them. A test that reaches a process of its own (p's
  :process: a root, an agent, a fork, the binary) never runs here, named or a proof: a child forked
  from an agent has no process store to start one in (bin/test makes it), so it errored. It is left
  out and listed under :skipped, and runs on the merge gate's checkout (judge!, seon.reconcile's
  :gate; a deferred proof is no reason of the done rule's), unless it is green at its digest (p's :reused, whoever ran it), which needs no gate. proofs is kept for the contract's
  callers: bin/test's own plan, on a checkout, still runs a named or proof process test
  (seon.proofs/left-out)."
  {:malli/schema [:=> [:cat :map [:maybe [:set :symbol]] [:set :qualified-symbol]] :map]}
  [p names proofs]
  (let [in? #(or (contains? names %) (contains? names (symbol (namespace %))))
        p (if (seq names) (-> p (update :selected #(filterv in? %)) (update :run #(filterv in? %))) p)
        ;; a slow test the run did not name and no proof holds is left out as bin/prove leaves it (docs/merging.md#a-slow-test-runs-last)
        slow (when-not (seq names) (fx/slow-tests (select-keys (:digests p) (remove proofs (:run p)))))
        skip? (into (set (remove #(proofs/green? (get-in p [:reused %])) (proofs/left-out (:selected p) (:process p) false #{}))) (keys slow))]
    (-> p (assoc :skipped (vec (sort-by str (filter skip? (:selected p)))))
        (update :selected #(filterv (complement skip?) %)) (update :run #(filterv (complement skip?) %)))))

(defn- own-store!
  "Make this freshly forked process (a clone, a test child) read the store through its own env: the env and connections it inherited
  forgotten (seon.db/after-fork!: LMDB forbids using them across fork(), whose reader slot is the parent's), a connection of its own on
  branch, and every db value the state holds of its parent's made again through it (main's, the base, the branch's, the index's), so no
  node of one not yet realised is read through the parent's env. What the parent decoded is reused through the node cache
  after-fork! keeps. Answers the connection. See docs/storage.md#an-agents-reads-and-writes."
  {:malli/schema [:=> [:cat :seon.db/cfg :keyword] :any]}
  [cfg branch]
  (pdb/after-fork! cfg)
  (let [conn (pdb/connect (assoc cfg :branch branch))
        own #(some->> % d/commit-id (pdb/at-commit conn))]
    (swap! state (fn [st] (cond-> (assoc (dissoc st :embedded-from) :store-conn conn)
                            (:db st) (update :db own)
                            (:base st) (update :base own)
                            (:main st) (update :main own)
                            (get-in st [:index :db]) (update-in [:index :db] own))))
    conn))

(defn- warm-fixtures!
  "Force, once in this agent, every loaded var marked ^:seon.test/fixture (a delay a test realizes on first use: the test store),
  so each test child forked after inherits it made: realized in the child, it was made again each fork (65M ticks, 1 to 3 s a
  test item) and dropped with it. Never at load: a binary loads the test namespaces before its main carrier. Answers
  how many it forced. See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent."
  {:malli/schema [:=> [:cat] nat-int?]}
  []
  (if (:fixtures-warm @state)
    0
    (let [vs (for [n (all-ns) [_ v] (ns-interns n) :when (:seon.test/fixture (meta v))] v)]
      (doseq [v vs] (force @v))
      (swap! state assoc :fixtures-warm true)
      (count vs))))

(defn- test-child
  "Fork this agent to run tests in a child (run-tests-here; fds the run's atom of the pipe ends open here) and read it to its end: {:answer
  (test-run-answer: the child's final line, or the one :error report of the test it died in),
  :streamed (the reports of the tests that ended, in order), :ended (their vars, as strings),
  :pid, :exit}, or the fork's refused answer. forked is told the child's pid; each test as it starts goes
  to root (what this agent waits on, which a stall names) through say, the loop's send! or what
  hands the loop a message to send."
  {:malli/schema [:=> [:cat :map nat-int? [:sequential :qualified-symbol] :seon.host/fn :seon.host/fn :any] [:or :map :seon.test/fork-refused]]}
  [plan ticks tests forked say fds]
  (let [[r w] (sock/socketpair)
        lines (atom {:streamed [] :ended #{} :fns {} :heap {}})
        ;; fds: the pipe ends open in this agent for the run's children (an atom, a set), each child closes every
        ;; one but its own write end: a sibling's end held here would keep the agent's death from ending that sibling
        _ (swap! fds conj r w)
        ;; what ran ahead is committed first, so the child's db is a commit it can make again through its own env (own-store!)
        _ (when (:dirty? @state) (commit!))
        ;; the fixtures made here once, so this child and every later one inherits them
        _ (warm-fixtures!)]
    (lib/fork!
     {:refused (fn [n] (lib/c-close r) (lib/c-close w) (swap! fds disj r w)
                 (contract/error :seon.test/fork-refused #'seon.agent/test-child
                                 (str "cannot fork the tests: " n " Chez threads active (a running future?)") {:active-threads n}))
      :child (fn []
               (run! lib/c-close (disj @fds w))
               ;; nothing this child forks (a test's own child, a root it starts) holds the
               ;; write end, so its exit is the pipe's end of file
               (sock/clofork! w)
               ;; the run ends with its agent (a test ends by its own budget): the agent's end of this socket
               ;; pair closing raises SIGIO here and ends the child, whatever the test is
               ;; doing (the agent's own
               ;; socket to root is FD_CLOFORK: not held here, so its handler is replaced)
               (when-let [unwatched (sock/signal-at-input! w)]
                 (sock/write-line! w (sedn/encode {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (:seon.error/message unwatched)}]}))
                 (lib/c-exit 0))
               (intr/exit-at-eof! #(sock/peer-gone? w) false)
               ;; the test child's own ceiling, from its own heap at its fork: the agent's absolute ceiling
               ;; left a child of a grown agent less room than any test needs (docs/machine.md#what-a-process-owns-in-memory)
               (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes)) (cfg/value :seon.config.agent/trip-bytes))
               ;; a run of a session's checkout (seon.system/prove-tree!): its tests read that checkout's files, as their digests
               ;; (the tree's) say, never root's, which this agent was forked from (seon.config/checkout reads SEON_CHECKOUT)
               (when-let [co (:checkout plan)] (lib/c-setenv "SEON_CHECKOUT" co 1))
               ;; its tests run as bin/test's runner runs them: no interrupt or eval of the agent's
               ;; (task seon.agent/a-test-child-starts-as-a-test-runner-does)
               (intr/forked!)
               ;; its reads through its own env, never the agent's (own-store!); its writes stay its own value's (:test mode)
               (own-store! (:cfg @state) (:branch @state))
               ;; and as a test process: a private function's contract is checked on each call here, whatever its agent's
               ;; setting (the owner, 2026-11-10: "Private helpers: tests only"; docs/checks.md#contracts-armed-at-definition)
               (contract/check-private! true)
               ;; nor the agent's walk: a fork holds its reactions on @ctx, which would run at a test's every write of a
               ;; record of its own (docs/merging.md#a-test-child-is-a-fork-of-a-live-agent)
               (some-> (:walk @state) render/dispose-walk!)
               (swap! state #(-> % (dissoc :walk) (assoc :mode :test :evaluating false)))
               ;; the test's own working directory: what a test writes relative to it is this run's, removed
               ;; below when the child has ended (docs/failure-points.md, a directory walk)
               (let [run-dir (fx/run-dir (lib/c-getpid))] (.mkdirs (java.io.File. ^String run-dir)) (lib/c-chdir run-dir))
               (let [[res streamed?] (try [(do (load-removed! (:removed plan)) (run-tests-here w ticks tests)) true]
                                          (catch Throwable e [{:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]} false]))]
                 ;; a run's reports went as its tests ended: this line is its end and its counts; a run that threw
                 ;; streamed none, so its line keeps the throw's report (scale's agents read :error 2 and nothing else)
                 (sock/write-line! w (sedn/encode (cond-> res streamed? (assoc :reports []))))))
      :parent (fn [pid]
                (lib/c-close w) (swap! fds disj w)
                (forked pid)
                (swap! state assoc :test-child pid)
                (let [read (read-to-exit r pid #(when-let [t (:start (swap! lines take-line %))] (say {:op :testing :test t})))
                      st (:status read)]
                  (swap! state dissoc :test-child)
                  (lib/c-close r) (swap! fds disj r)
                  (seon.lib/remove-tree! (fx/run-dir pid))
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
  {:malli/schema [:=> [:cat :map :map :map [:map-of :qualified-symbol :string] [:set :qualified-symbol] [:map-of :qualified-symbol :keyword] [:set :symbol]] [:or nat-int? :seon.error/base]]}
  [ran failures reused digests deferred removed proofs]
  (let [db (db)]
   (if-not (contains? (d/schema db) :seon.test/failures)
    (contract/error :seon.agent/unrecorded #'record-results! "this branch's schema has no :seon.test/* attributes: the run's results stay in the cache only" {})
   (let [pattern [:var/name :seon.test/digest :seon.test/status :seon.test/failures :seon.test/ticks :seon.test/bytes]
        pulled #(into {} (map (fn [m] [(:var/name m) (dissoc m :var/name)])) (d/pull-many db pattern (vec %)))
        ;; every test the branch holds a verdict of (its digest may have moved), then those of this run's worth a fact
        held (pulled (d/q '[:find [?e ...] :where [?e :seon.test/digest]] db))
        ran (proofs/facts-worth-keeping held proofs ran)
        reused (proofs/facts-worth-keeping held proofs reused)
        ran-es (keep #(pdb/eid db :var/name %) (distinct (concat (keys ran) (keys reused) deferred)))
        old (into held (pulled (distinct ran-es)))
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

(defn- record-answer!
  "Record the test service's answer r to this agent's ask (seon.prove's run-answer: each item's reports, digests and
  :measured, and the plan's reused reports and :deferred) on this branch, once an ask: record-results! of what it ran,
  reused and deferred, at the digests of held (held-plan, this agent's plan of the run, every judging test's, so a fact
  whose digest moved is retracted). The answer as it was, with :unrecorded when the branch refused the write. See
  docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:set :symbol]] :map]}
  [r held proofs]
  (let [digests (merge (:digests held) (:digests r))
        {:keys [ran failures]} (proofs/answer-ran (assoc r :digests digests))
        ran (into {} (for [[t x] ran] [t (merge x (get-in r [:measured t]))]))
        reps (filterv :reused (:reports r))
        st (proofs/results {:reports reps})
        fs (proofs/failures {:reports reps})
        reused (into {} (for [[t s] st :let [d (digests t)] :when d]
                          [t {:digest d :pass (if (= :pass s) 1 0) :fail (if (= :fail s) 1 0) :error (if (= :error s) 1 0) :failures (get fs t [])}]))
        rec (record-results! ran failures reused digests (set (:deferred r))
                             (select-keys (proofs/results r) (keys (get-in held [:plan :removed]))) proofs)]
    (cond-> (dissoc r :measured) (contract/error? rec) (update :unrecorded (fnil conj []) (:seon.error/message rec)))))

(defn- run-plan
  "What a run of this branch selects and runs (run-tests-forked's, and the test service's :plan of it, seon.prove): the
  plan (test-plan, with the tests of the namespaces named as proofs), the tests it selects for mode (:turn: the proofs and
  what the branch changed; else the plan's), those deferred to the gate (process tests), those reused at their digest with
  the reports they read as, and run, the rest, with each one's digest; or {:unread error} when the cache could not be read.
  See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat [:set :symbol] [:enum :turn :judge :main] [:maybe [:set :symbol]]] :map]}
  [proofs mode names]
  ;; the tests of the namespaces a request names are selected as proofs are: named, they run whatever the branch changed
  (let [named (when (seq names) (named-tests (db) names))
        proofs (cond-> proofs (seq names) (into named))
        plan (test-plan (db) (when-not (= :main mode) (:base @state)) proofs (heard-main))
        ;; a test named is asked now: a red held at its digest runs again, so a red whose cause no digest sees (a rebuilt binary, a repaired checkout) can be asked
        plan (cond-> plan (seq named) (update :reused (fn [held] (into {} (remove (fn [[t r]] (and (named t) (not (proofs/green? r))))) held))))]
  (if-let [unread (:unread plan)]
    {:unread unread}
  (let [{judge :selected deferred :skipped} (run-selection plan names proofs)
        selected (if (and (= :turn mode) (not (seq names)))
                   ;; the proofs as the plan selects them (one with no form on the branch is not), and the tests the branch changed
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
                                (and (:cost r) (identical? f (first (:failures r)))) (assoc :cost (:cost r)))))]
    {:plan plan :judge judge :deferred deferred :selected selected :run run :reused reused :digests digests :reused-reports reused-reports}))))

(defn- held-plan
  "What this agent keeps of its plan of a run (run-plan's sel) for the items the test service grants it: every digest, and
  of the plan only what running a test reads (:removed, :process, :base-digests, :static-reads and the reads index made, so
  each green is keyed by what it read), not its graph. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map] :map]}
  [sel]
  {:digests (:digests sel)
   :plan (cond-> (select-keys (:plan sel) [:removed :process :base-digests :static-reads])
           (:reads-rd (:plan sel)) (assoc :reads-rd (force (:reads-rd (:plan sel)))))})

(defn- item-selection
  "The selection that runs items ({test digest}, the test service's grant) as held (held-plan, this agent's last plan of a
  run) planned them: each test alone, selected and run, reused and deferred nothing; nil when held gave any of them another
  digest (or there is none), which is a plan of another program than the one the item was queued at. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe [:map-of :qualified-symbol :string]]] [:maybe :map]]}
  [held items]
  (when (and held (seq items) (every? (fn [[t d]] (= d (get-in held [:digests t]))) items))
    (let [ts (vec (sort-by str (keys items)))]
      (assoc held :judge ts :selected ts :run ts :deferred [] :reused {} :reused-reports []))))

(defn- grant-selection
  "What a run selects: items given (the test service's grant, {test digest}) are what it runs, as held planned them
  (item-selection), or, held gone or of another program, as planned again (plan: run-plan of their names); given and empty,
  nothing (a grant of no items ran every test, 1,765 of them, 2026-11-12); items not given (nil), a plan of its own. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe [:map-of :qualified-symbol :string]] :seon.host/fn] :map]}
  [held items plan]
  (cond (nil? items) (plan)
        (empty? items) {:plan (:plan held) :judge [] :selected [] :run [] :deferred [] :reused {} :reused-reports [] :digests {}}
        :else (or (item-selection held items) (plan))))

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
  namespaces) restricts either to the tests of those namespaces, and selects every one of them (named-tests). A process test (the plan's
  :process: it reaches an agent, a fork, a root or the binary; a shell call is none) never runs here unless its namespace
  is named; the ones the change reaches run on the merge gate's checkout, this agent's verdict (judge!) and the judge's (seon.reconcile's :gate).
  The answer names those left out (:deferred), why each was (:why, {test {:uses :through}}: the
  process var it reaches and the calls to it, seon.program/process-path), and how many the judge
  runs (:at-judge)."
  {:malli/schema [:function [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn [:maybe [:set :symbol]]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn [:maybe [:set :symbol]] [:maybe [:map-of :qualified-symbol :string]]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]
                  [:=> [:cat pos-int? [:set :qualified-symbol] [:enum :turn :judge :main] :seon.host/fn [:maybe [:set :symbol]] [:maybe [:map-of :qualified-symbol :string]] [:maybe :string]] [:or :seon.test/result :seon.test/fork-refused :seon.error/base]]]}
  ([ticks proofs mode] (run-tests-forked ticks proofs mode (fn [_] nil)))
  ([ticks proofs mode forked] (run-tests-forked ticks proofs mode forked nil))
  ([ticks proofs mode forked names] (run-tests-forked ticks proofs mode forked names nil))
  ([ticks proofs mode forked names items] (run-tests-forked ticks proofs mode forked names items nil))
  ;; checkout: the session's checkout whose files a run of its tree reads (seon.system/prove-tree!): its test children read it
  ;; (test-child's SEON_CHECKOUT), whichever plan the run takes; it rode on the held plan alone, so a run that planned again read
  ;; the main tree's (its process tests found no process store there, 2026-11-12)
  ([ticks proofs mode forked names items checkout]
  ;; an item the test service granted, at the digest this agent's plan of the run gave it: run as planned, nothing planned again
  ;; the runtime invariant (docs/merging.md#a-session-proves-through-root): this process runs its branch's program before a
  ;; test of it is judged; a definition it holds another form of is named, never judged (seon.program/program-differs)
  (if-let [differs (when-let [base (:base @state)] (not-empty (program/program-differs base (db))))]
    (contract/error :seon.agent/program-differs #'seon.agent/run-tests-forked
                    (str "this agent runs another program than its branch: " (str/join ", " (map :var differs)))
                    {:differs differs})
  (let [{:keys [unread] :as sel} (cond-> (grant-selection (:held-plan @state) items #(run-plan proofs mode names))
                                    checkout (assoc-in [:plan :checkout] checkout))]
  (if unread
    unread
  (let [{:keys [plan judge deferred selected run reused digests reused-reports]} sel
        t0 (profile/now-ns)
        ;; every selected test green at its digest (a turn's run of the same tree, a session's):
        ;; nothing to run, so no child is forked
        ;; one child's run of todo: a child that dies loses the test it was running (its :error stays) and the
        ;; rest of its todo run in a new child: the death of one test never costs the run its other results
        run-child (fn [todo say fds]
              (loop [todo todo reports [] fns {} heap {} measured {}]
                (let [c (test-child plan ticks todo forked say fds)]
                  (if (contract/error? c)
                    c
                    (let [died (filter :exit (:reports (:answer c)))
                          reports (-> reports (into (:streamed c)) (into (:reports (:answer c))))
                          done (into (:ended c) (keep :var) died)
                          rest-todo (filterv #(not (done (str "#'" %))) todo)]
                      ;; a death with something to show for it (a test ended, or the one it died in is
                      ;; named) leaves the rest to a new child; none, and the child's run ends on it
                      (if (and (seq rest-todo) (seq died) (or (seq (:ended c)) (some :var died)))
                        (recur rest-todo reports (merge fns (:fns c)) (merge heap (:heap c)) (merge measured (:measured c)))
                        {:reports reports :fns (merge fns (:fns c)) :heap (merge heap (:heap c)) :measured (merge measured (:measured c))
                         :pid (:pid c) :exit (:exit c)}))))))
        ;; every selected test green at its digest (a turn's run of the same tree, a session's): nothing to run, so no
        ;; child is forked. Else the tests are dealt by the one deal bin/test uses (proofs/shards, by each test's
        ;; median measured ms) into at most :seon.config.proofs/jobs children, each run on a fiber of its own (a
        ;; park on its pipe lets the others run) and every one read to its end; one child is the 1-child case
        run-all (fn [say]
            (if (empty? run)
              {:pass 0 :fail 0 :error 0 :test 0 :reports [] :ms 0}
              (let [;; each test's measured ms, or its class's configured estimate (seon.proofs/estimated-ms)
                    ms (proofs/estimated-ms run (fx/typical-ms run) (:process plan))
                    ;; no more children than the work needs: each is worth at least what a fork and a child's start cost
                    n (-> (quot (reduce + (vals ms)) (cfg/value :seon.config.proofs/child-min-ms)) (max 1) (min (cfg/value :seon.config.proofs/jobs)))
                    fds (atom #{})
                    fibers (mapv #(let [g (a/io-thread (run-child % say fds))] [g (a/go-monitor g)])
                                 (proofs/shards run n ms))
                    ;; all are taken before a throw is raised, so no child outlives the run
                    cs (mapv (fn [[g died]] (or (a/<!! g) {::threw (a/<!! died)})) fibers)]
                (if-let [e (some ::threw cs)]
                  (throw e)
                  (if-let [e (first (filter contract/error? cs))]
                    e
                    (let [reports (into [] (mapcat :reports) cs)
                          counts (frequencies (map :type reports))]
                      {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :test (count (distinct (keep :var reports)))
                       :reports reports :fns (into {} (map :fns) cs) :heap (into {} (map :heap) cs) :measured (into {} (map :measured) cs)
                       :ms (profile/ms-since t0) :pid (:pid (first cs)) :exit (:exit (first cs))})))))) 
        ;; the answer, once the run is read (by the loop: it records and it sends)
        finish (fn [res]
    ;; what ran, cached by digest (seon.fx/write-verdicts!), whoever asked for the run; a file the
    ;; cache would not take is named on the answer (:unstored), and those tests run again
    (if (contract/error? res)
      res
      (-> res
        (as-> r (let [{:keys [ran failures]} (proofs/answer-ran (assoc r :digests digests))
                      ran (into {} (for [[t x] ran] [t (merge x (get-in r [:measured t]))]))
                      ;; each green keyed by what it read: the functions it called, itself, its reads besides code (docs/source.md#tests-run-by-reach)
                      ran (if-let [rd (some-> (:reads-rd plan) force)]
                            (into {} (for [[t x] ran] [t (cond-> x (proofs/green? x)
                                                           (assoc :reads (proofs/reads-key rd {:fns (conj (into #{} (map :var) (get-in r [:fns t])) t)
                                                                                               :keys (get-in plan [:static-reads t])})))]))
                            ran)
                      w (fx/write-verdicts! {:id (str (name (:id @state)) " " (random-uuid)) :branch (:branch @state) :agent (:id @state) :key (:key @state)} ran failures)]
                  ;; beside the cache, the same run's results as facts on this branch (the record shows them); an item the test service
                  ;; granted writes the cache only: its asker's answer writes the facts once (record-answer!)
                  ;; not main's run: it judges main's tree (no :base), whose facts are main's prove's to write
                  (let [rec (when-not (or (= :main mode) items) (record-results! ran failures (select-keys reused selected) digests (set deferred)
                                                                     (select-keys (proofs/results r) (keys (:removed plan))) proofs))]
                  (-> (cond-> r (contract/error? w) (assoc :unstored [(:seon.error/message w)])
                        (contract/error? rec) (assoc :unrecorded [(:seon.error/message rec)]))
                      ;; an item's measure goes with its answer: its asker's answer writes the test's sums (record-answer!)
                      (cond-> (not items) (dissoc :measured))))))
        (update :reports into reused-reports)
        (as-> r (merge-with + r (select-keys (frequencies (map :type reused-reports)) [:pass :fail :error])))
        (assoc :digests (select-keys digests (concat (keep (comp proofs/test-symbol :var) (:reports res)) (filter reused selected))))
        (as-> r (let [reds (proofs/results r) bd (:base-digests plan)
                      stored (when (seq bd) (fx/stored-results (into {} (keep (fn [[t s]] (when (and (#{:fail :error} s) (bd t)) [t (bd t)]))) reds)))]
                  ;; a cache that cannot be read excuses nothing: the red stays the branch's
                  (assoc r :main-red (proofs/main-red reds digests bd (if (or (nil? stored) (contract/error? stored)) {} stored)))))
        (assoc :reused (count (filter reused selected)) :ran (count (distinct (keep :var (:reports res))))
               :selected selected :deferred deferred :at-judge (count judge)
               ;; each deferred test's digest as main held it (the plan's base): what the merge gate reads main's own verdict of it by
               :deferred-digests (into {} (for [t deferred] [t (get (:base-digests plan) t)])))
        ;; why each was left out: the process it reaches and the calls to it (seon.program/process-path)
        (cond-> (seq deferred) (assoc :why (into {} (for [t deferred] [t (program/process-path (:graph plan) t)])))))))]
    (finish (run-all send!))))))))

;; ---- a run's whole verdict ---------------------------------------------------------------

(defn- with-gate-verdicts
  "Test run r with what this branch holds of its :deferred tests (the gate's facts, gate-tx): each with a verdict counted in the
  run as a reused result is, so its reds and greens read as the rest do; those with none stay :deferred."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (let [dbv (db)
        verdict (fn [t] (d/entity dbv [:var/name t]))
        by (group-by (fn [t] (if (contains? #{nil :deferred} (:seon.test/status (verdict t))) :deferred :judged)) (:deferred r))
        reports (vec (for [t (:judged by) :let [v (verdict t)]
                           f (if (= :green (:seon.test/status v)) [{:type :pass}] (:seon.test/failures v))]
                       (assoc f :var (str "#'" t) :reused true)))]
    (-> r (update :reports (fnil into []) reports)
        (assoc :deferred (vec (:deferred by)) :reused (+ (:reused r 0) (count (:judged by))))
        (as-> x (merge-with + x (select-keys (frequencies (map :type reports)) [:pass :fail :error]))))))

(defn gate-plan-failure
  "What a gate's log (text) says stopped its plan before any test ran, and the namespaces whose ns form this branch rewrote (forms), a form's cause
  as often as not: its message, or nil when the log names none, for the failure to say rather than mark every test an error. See
  docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :string [:sequential :symbol]] [:maybe :string]]}
  [text forms]
  (when-let [[_ what at] (re-find #"error\[[^\]]*\]: (.*)\n\s*--> (\S+)" text)]
    (str "the gate could analyze none of your tests: " what ", at " at
         (when (seq forms) (str "; your branch rewrote the ns form of " (str/join ", " forms) ", which may have lost a require")))))

(defn ask-gate
  "Root's :gate request for tests of this branch, at prio, answered here (seon.system/ext-op, seon.fx/run-tests): the seam a test answers at, since a
  real gate is a worktree, a build and a run. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat [:vector :qualified-symbol] :int] :any]}
  [tests prio]
  (let [k (:tx-seq (swap! state update :tx-seq inc))]
    (:result (await-reply k {:op :gate :k k :tests tests :prio prio}))))

(defn judge!
  "A run's whole verdict: prove! :judge (names as it takes them), and once what it ran is green, the process tests it left
  (:deferred, which a fork of this agent cannot run) on the merge gate's checkout through root's one :gate request (seon.fx/run-tests),
  each read from the branch where the gate already judged it at its digest and never asked again. Their verdicts are facts on this
  branch (seon.proofs/gate-tx), so a red reads on its status and page with a turn to fix it, and the answer counts them. The task's own
  proofs and the tests named come first, ahead of other agents' longer runs (:prio); a red among them answers now. The rest, the tests
  its change only reaches, run when reach? is true, which is when its status is clear and it has nothing else to do (seon.turn/judged);
  (my.agent/prove), reach? false, leaves them :deferred and says so, so an eval never sits behind them. What no gate will run
  (seon.task/unjudged) stays :deferred, said under :unjudged and on the status. A failed gate, or a changed namespace with no file on
  the branch, is the answer: no verdict. Committed first: the gate reads the committed head, and the verdicts are held at its digests. See
  docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat [:set :symbol] :boolean] :map]}
  [names reach?]
  (let [r (prove! :judge names)]
    (if (or (contract/error? r) (empty? (:deferred r)) (seq (proofs/failures r)))
      r
      (let [_ (commit!)
            dbv (db)
            tk (some->> (:task @state) (task/task dbv))
            closed (task/unjudged dbv tk (:deferred r))
            named? (fn [t] (or (task/proof? tk t) (contains? names t) (contains? names (symbol (namespace t)))))
            pull-of (fn [t] (d/pull (db) [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/ticks :seon.test/bytes] [:var/name t]))
            open (filterv #(contains? #{nil :deferred} (:seon.test/status (pull-of %))) (remove (set (:tests closed)) (:deferred r)))
            ;; one stage's tests through the gate, written where the status reads them; its error or nil
            gate! (fn [tests prio]
                    (when (seq tests)
                      (let [g (ask-gate tests prio)]
                        (cond
                          (contract/error? g) g
                          (seq (:unread g)) (contract/error :seon.agent/gate-unread #'judge! (str "the gate can't run your process tests: " (str/join "; " (for [u (:unread g)] (str (:ns u) ": " (:reason u))))) {:unread (:unread g)})
                          ;; every test :error, none reached: why is in its log, and the verdict is no verdict
                          (and (seq (get-in g [:value :results])) (every? #{:error} (vals (get-in g [:value :results]))) (:log (:value g))
                               (gate-plan-failure (slurp (:log (:value g))) (sort (for [[k n] (keys (branch-changes (:base @state) (db))) :when (= :ns k)] n))))
                          (contract/error :seon.agent/gate-unread #'judge!
                                          (gate-plan-failure (slurp (:log (:value g))) (sort (for [[k n] (keys (branch-changes (:base @state) (db))) :when (= :ns k)] n))) {:tests tests})
                          :else (do (transact! (proofs/gate-tx (into {} (map (juxt identity pull-of)) tests) (:value g)))
                                    (commit!)
                                    nil)))))
            first-err (gate! (filterv named? open) 1)
            red? (some (fn [t] (= :red (:seon.test/status (pull-of t)))) (filter named? open))
            rest-tests (when-not (or first-err red?) (filterv (complement named?) open))
            err (or first-err (when reach? (gate! rest-tests 0)))
            unjudged (cond closed (:why closed)
                           (and (seq rest-tests) (not reach?)) "the tests your change reaches run when your status is clear")]
        (if err
          err
          (cond-> (with-gate-verdicts r) unjudged (assoc :unjudged unjudged)))))))

;; ---- the loop ----------------------------------------------------------------------

(defn- start-ns
  "The namespace agent's REPL starts in: its task's :seon.task/ns on db (task its :seon.task/id), made as an ns form
  would make it when the program has none (clojure.core referred), else its own scratch namespace nsx (a task that
  names no namespace). Its first eval runs there and its context pulls it (seon.ctx/current-ns); the REPL's
  names resolve there as anywhere (`repl-name`). See docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :string] :seon.host/namespace] :seon.host/namespace]}
  [db task nsx]
  (if-let [sym (some->> task (pdb/eid db :seon.task/id) (d/entity db) :seon.task/ns)]
    (or (find-ns sym) (binding [*ns* (create-ns sym)] (refer-clojure) *ns*))
    nsx))

(defn- make-ns! {:malli/schema [:=> [:cat :seon.agent/id] :seon.host/namespace]}
  [id]
  (let [sym (symbol (str "agent." (name id)))
        nsx (create-ns sym)]
    ;; the agent's own namespace is its scratch pad (seon.program/scratch-ns?): what it
    ;; defines here never becomes program
    (alter-meta! nsx assoc :scratch true)
    ;; a namespace like any: the REPL's names (my.*, clojure.repl's, pprint, d and str) resolve in it as in every
    ;; namespace (`repl-name`), referred nowhere
    (binding [*ns* nsx]
      (refer-clojure)
      ;; clojure.repl as clojure.main's REPL has it (clojure.main/repl-requires); my.agent puts Seon's source, source-fn,
      ;; pst, doc and dir into it in every process
      (require 'clojure.pprint))
    nsx))

(defn state-summary
  "The agent's process state m as the printer shows it (seon.render/model, the model render of the schema
  :seon.agent/state): each key with its value when that is a scalar, else what it holds, a count
  for a collection and its type for a host object (a database, a connection, a descriptor),
  with the form that reads it whole. A bare (swap! seon.agent/state ...) answers the state: it
  costs what the state names, not what it holds."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :map]}
  [_ m]
  (into (array-map)
        (for [[k v] m
              :let [read (list 'get (list 'deref 'seon.agent/state) k)]]
          [k (cond (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) v
                   (coll? v) {:count (count v) :read read}
                   :else {:host (str (type v)) :read read})])))

(declare clone!)

(defn process-plan
  "Of sel (run-plan's), what a run on a checkout runs beside its in-process items, each at the digest this branch gives it
  (docs/merging.md#the-test-service): :process {test digest}, the process tests it left (:deferred) that have one; :steps
  {step {:digest :binary? :bound}}, the bin/check steps with no green at their digest as bin/prove plans them
  (seon.proofs/plan-of over this branch's reach graph and inputs, every step that reaches a process kept, the generative
  check's keys left to the shards), :bound seon.proofs/step-bounds' (every step has one), :binary? seon.proofs/step-needs-binary?; a request naming tests or namespaces (names) runs only the
  steps it names among them (bin/prove --root PORT seon.config-test config), as the plain bin/prove NS runs none; :missing, the steps with no digest here (one naming a
  function the program lacks; health, keyed by a base tree no branch holds); :build {:tree :needed?}, needed when a process
  test or a binary step is among them; :deferred, what it left that is no process item. Or {:unread error}: the cache
  could not be read."
  {:malli/schema [:=> [:cat :map [:maybe [:set :symbol]]] :map]}
  [sel names]
  (let [plan (:plan sel)
        unread (volatile! nil)
        p (proofs/plan-of {:graph (:graph plan) :inputs (:inputs plan)} "checks" :merge nil nil
                          (fn [cand] (let [r (fx/stored-results cand)] (if (contract/error? r) (do (vreset! unread r) {}) r))))
        process (into {} (for [t (:deferred sel) :let [d (get-in sel [:digests t])] :when (and d (contains? (:process plan) t))] [t d]))
        run (vec (cond->> (remove proofs/mg-key? (:run-steps p)) (seq names) (filter #(contains? names (symbol %)))))
        ;; the generative contract keys with no green at their digest: items of the run too, as the shell's shards run them
        ;; (a merge's verification proves them; leaving them out, a tree proved through the system proved less than the shell's)
        mg (into {} (for [k (filter proofs/mg-key? (:run-steps p)) :when (not (seq names))] [k (get (:steps p) k)]))
        ;; every step's bound (seon.proofs/step-bounds: one never measured takes the largest measured median), from every check
        ;; step's median, read once when a step runs
        bounds (if (seq run) (proofs/step-bounds run (fx/typical-ms (vec (keys proofs/check-steps)))) {})
        steps (into {} (for [s run] [s {:digest (get (:steps p) s) :binary? (proofs/step-needs-binary? s) :bound (bounds s)}]))]
    (if-let [u @unread]
      {:unread u}
      {:process process :steps steps :mg mg :missing (vec (:missing p)) :deferred (vec (remove process (:deferred sel)))
       :build {:tree (:tree (:inputs plan)) :needed? (boolean (or (seq process) (some :binary? (vals steps))))}})))

(defn asked
  "The tests a :tests request m's asker reads a verdict of, so its answer keeps a pass report for each (seon.proofs/answer): the
  task's proofs, the tests m names (a namespace's name, its tests among selected) and the items the test service granted (:items):
  an item kept no pass and read as no verdict at the service (seon.prove/batch-ended). See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol] [:maybe [:sequential :qualified-symbol]]] [:set :qualified-symbol]]}
  [m proofs selected]
  (let [names (set (:names m))]
    (-> proofs
        (into (filter #(or (names %) (names (symbol (namespace %))))) selected)
        (into (keys (:items m))))))

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
    ;; served by the :tests proc on its own fiber (seon.repl/tests-step), which waits on the children while
    ;; the :repl proc runs what it runs; the answer is the run as the done rule reads it, bounded by its
    ;; reds (seon.proofs/answer): the whole run is cached by digest before it, so nothing is lost, and
    ;; the frame stays small
    :tests (if (:plan? m)
             ;; the test service's plan of a run (seon.prove): what it would run, each at its digest, and what it reuses and defers;
             ;; nothing forked. The service then asks for each item by name, as its workers free
             (let [proofs (or (some->> (:task m) (task/task (db)) :seon.task/accumulated :proofs) #{})
                   mode (or (:mode m) :judge)
                   sel (run-plan proofs mode (some-> (:names m) set))
                   ;; a run on a checkout (:process?): its process tests, check steps and build are items of the service's too
                   pp (when (and (:process? m) (not (:unread sel))) (process-plan sel (some-> (:names m) set)))
                   sel (cond-> sel pp (assoc :deferred (:deferred pp)))
                   ;; the plan's own facts on this branch, once a run: what it reuses and what it defers to the gate, at their
                   ;; digests (each item's run records what it ran); main's run is main's prove's to write
                   ;; held for the items it grants this agent (item-selection), each run at the digest planned here, and for
                   ;; the facts its answer writes on this branch (record-answer!)
                   _ (when-not (or (:unread sel) (:unread pp)) (swap! state assoc :held-plan (held-plan sel)))]
               [{:op :tests :id (:id m) :mem-mb (mem-mb)
                 :result (or (:unread sel) (:unread pp)
                             (cond-> {:items (select-keys (:digests sel) (:run sel)) :selected (:selected sel) :deferred (:deferred sel)
                                      ;; each item's measured ms, or its class's estimate (seon.proofs/estimated-ms): what the service orders by
                                      :ms (let [ts (into (:run sel) (keys (:process pp)))] (proofs/estimated-ms ts (fx/typical-ms ts) (:process (:plan sel))))
                                      :at-judge (count (:judge sel)) :reused-reports (:reused-reports sel)
                                      :digests (select-keys (:digests sel) (filter (:reused sel) (:selected sel)))}
                               (seq (:deferred sel)) (assoc :why (into {} (for [t (:deferred sel)] [t (program/process-path (:graph (:plan sel)) t)]))
                                                           ;; each deferred test's digest at the base, from the base's program (nil: new on the
                                                           ;; branch), which the merge check reads main's verdict at; the service's answer carries it
                                                           :deferred-digests (into {} (for [t (:deferred sel)] [t (get (:base-digests (:plan sel)) t)])))
                               pp (merge (select-keys pp [:process :steps :mg :build :missing]))))}])
           (let [proofs (or (some->> (:task m) (task/task (db)) :seon.task/accumulated :proofs) #{})
                 r (run-tests-forked (or (:ticks m) (cfg/value :seon.config.agent/test-ticks))
                                     proofs
                                     (or (:mode m) :judge)
                                     #(send! {:op :tests-started :id (:id m) :pid %})
                                     (some-> (:names m) set)
                                     (:items m)
                                     ;; items of a run of a session's checkout: their test children read that checkout's files
                                     (:checkout m))]
             [{:op :tests :id (:id m) :result (if (and (map? r) (not (contract/error? r))) (proofs/answer r (asked m proofs (:selected r))) r) :mem-mb (mem-mb)}]))
    ;; an async effect's outcome (fx-send!): a message to this agent, recorded
    :fx-reply (let [r (dissoc m :op :k)]
                (swap! state update :inbox (fnil conj []) {:from :fx :k (:k m) :kind (fx-done! (:k m)) :body r})
                nil)
    ;; root asks this agent to merge main into its branch (it owns the branch's writes)
    :merge-main [{:op :merged-main :id (:id m) :result (merge-main! (:data-attrs m #{}))}]
    :clone (do (clone! m) nil)
    :stats [{:op :stats :id (:id m) :result {:mem-mb (mem-mb) :t (:max-tx (db)) :pid (lib/c-getpid)
                                              :results (count (:results @state)) :evals (:n @state)
                                              :heap-mb (lib/r2 (/ (lib/bytes-in-heap) 1048576.0))}}]
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
        _ (await-reply k {:op :fd-ready :k k})
        fd (sock/take-fd! (:conn @link))]
    ;; the new branch starts at this branch's committed head: what was applied commits first
    (commit!)
    (let [r (await-reply k {:op :branch-me :k k})]
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
                      ;; the clone opens the store itself, on its own branch, and reads through it alone (own-store!)
                      (stderr-to-file! (stderr-path (pdb/agents-dir (:cfg r)) (:agent m)))
                      (let [store-conn (own-store! (:cfg r) (:branch m))]
                        ;; the task it was cloned for (a fork's sibling task, seon.server's), else its parent's
                        (reset! link {:fd fd :conn (sock/conn fd)})
                        (swap! state assoc :task (or (:task m) (:task @state)) :id (:agent m) :key (name (:agent m)) :branch (:branch m) :mode :agent :tx-seq 0
                               :fx-pending {} :waits {}
                               :store-conn store-conn :db @store-conn)
                        ;; it starts where its task is, as a new agent does
                        (swap! state #(assoc % :ns (start-ns (:db %) (:task %) (:ns %)))))
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

(declare begin-run-tx! begin-run-tx)

(defn begin-run!
  "This agent begins task on its branch: the agent's record names it as its task (:seon.agent/task: the conversation finds the
  messages it took by the agent, seon.ctx/placed-messages, and @ctx holds the task), the task running as main holds it. Written as
  the process starts (init!: an outside agent's branch is cut before root writes its start on main), or by the turn that begins a
  later task of the session; once, nothing written when the branch holds it. The task is running, as main holds it
  (its release, written on main after the branch was cut, read :held here: my.task/current and the
  page's task said held); a task's execution is no content a merge carries (seon.merge/carried-platform-attrs).
  A task the branch lacks (a fork's: its branch is cut from another task's) is written as main
  declares it first (seon.task/create-tx). The agent's first task on its branch (its entity there with no task yet) is
  where the agent is made: it is written with what every agent cites (seon.task/agent-cites-tx), on its branch, so
  its record holds them and its own cite! and uncite! change them, and a merge of main never brings one back."
  {:malli/schema [:=> [:cat :seon.task/id] :any]}
  [task]
  (let [k (:key @state) e (d/entity (db) [:seon.agent/key k])]
    (when-not (= task (:seon.task/id (:seon.agent/task e)))
      (let [made? (nil? (:seon.agent/task e))]
        (begin-run-tx! task)
        (when made? (transact! (task/agent-cites-tx (db) [:seon.agent/key k] k)))))))

(defn begin-run-tx
  "The write that makes task this agent's on db, its branch (begin-run-tx!'s second, and what
  the fork server applies to warm what an agent's start reads: seon.fork-server/warm-start): the task
  running, naming the agent (key k), its task and what it reads as its system message (seon.doc/system-page)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :string] :seon.db/tx-data]}
  [db task k]
  [{:seon.task/id task :seon.task/execution :running}
   ;; the agent's task, which its record pulls (the agent is the unit, docs/ctx.md#the-pattern)
   ;; what it reads as its system message: the system prompt's page, its top section and all below (seon.ctx.wire/agent-system-text)
   (let [sys (ref/page-top db doc/system-page)]
     (cond-> {:db/id k :seon.agent/key k :seon.agent/task {:seon.task/id task}} sys (assoc :seon.agent/system [sys])))])

(defn- begin-run-tx!
  "Task written on this agent's branch (begin-run!'s write): the task as main declares it when the
  branch lacks it, then begin-run-tx."
  {:malli/schema [:=> [:cat :seon.task/id] :any]}
  [task]
  (when-not (d/entity (db) [:seon.task/id task])
    (let [t (task/task (heard-main) task)]
      (transact! (task/create-tx (db) [(-> (select-keys t [:seon.task/ns :seon.task/name :seon.task/plan :seon.task/instructions :seon.task/proofs
                                                           :seon.task/needs :seon.task/branch :seon.task/model :seon.task/kind])
                                           (update :seon.task/ns symbol))]))))
  (transact! (begin-run-tx (db) task (:key @state))))

(defn init!
  "Make this process the agent: its socket (fd), its namespace, its state map, its
  interrupt handlers. serve is the loop a clone runs (seon.repl/serve!)."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :seon.host/fn] :any]}
  [fd {id :agent branch :branch task :task cfg :cfg resumed :resumed commit :commit t :t graph :graph index :index schema-registry :schema-registry session :key
       marks0 :marks program-errors :program-errors} db0 serve]
  ;; the spawn's phases on the one clock, each with what this thread allocated in it: the fork
  ;; server's marks (marks0), then this process's, sent with its hello (spawn-phases)
  (let [inherited (lib/generation-bytes)
        marks (volatile! (conj (vec marks0) [:child-init (profile/now-ns) (profile/alloc-bytes)]))
        mark! (fn [k] (vswap! marks conj [k (profile/now-ns) (profile/alloc-bytes)]))]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
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
        store-conn (pdb/connect (assoc cfg :branch branch))
        ;; main as it forked, made again through this process's own env: db0 is the fork server's value, and a node of it not yet
        ;; realised would be read through the env it inherited, which LMDB forbids across fork() (lmdb.h's caveats: its reader slot
        ;; is the parent's, reclaimed when the parent dies). Nothing after this reads the parent's value; what the parent decoded is
        ;; reused through the node cache after-fork! kept (docs/storage.md#an-agents-reads-and-writes)
        db0 (pdb/at-commit store-conn (d/commit-id db0))
        index (some-> index (assoc :db db0))
        _ (mark! :connected)]
    ;; the config this process was forked holding (root read it; an agent reads no environment), the heap bound as its task
    ;; states it (docs/config.md#the-resolver): a task's own :seon.config.agent/heap-extra-bytes bounds its agent
    (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes {:task (when task (d/pull db0 (cfg/keys-declared) [:seon.task/id task]))}))
                   (cfg/value :seon.config.agent/trip-bytes))
    (intr/install!)
    ;; root gone, this process ends, busy or not (the fork server's group kill may be gone too)
    (intr/exit-at-eof! #(sock/peer-gone? (:fd @link)))
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
          ;; what did not load in the program it was forked with (seon.fork-server/stood: main's, held by the server), then what of its
          ;; resumed branch's does not load here: both go to root with its hello, each a recorded problem of this agent, never a silent start
          load-errors (concat program-errors (when resumed (program/load-diff! base @store-conn)))
          _ (mark! :base)
          misaligned (when-not resumed (misaligned commit t @store-conn db0))
          _ (mark! :misaligned)]
     ;; a resumed branch whose program does not load runs main's for what failed: its errors
     ;; go to root with its hello (recorded problems), never dropped (T5b: a stub ran silently)
     ;; its key as root named it in its boot (seon.system's :spawn), never pulled from main here: that
     ;; pull cost what the inherited node cache lacked, 24-183 KB a spawn
     (reset! link {:fd fd :conn c})
     (reset! state (cond-> {:id id :key session :branch branch :task task :db @store-conn
                   :base base :main db0 :store-conn store-conn :cfg cfg :results {} :n 0
                   :load-errors (vec load-errors) :misaligned misaligned :unwatched (sock/signal-at-input! fd)
                   :waits {} :inbox [] :tx-seq 0 :mode :agent
                   :ns (start-ns @store-conn task nsx) :interrupt-ok intr-ok :serve serve :inherited-bytes inherited}
                   ;; main's reach graph as the fork server keeps it, for db0: test-plan's base
                   ;; graph starts from it (base-graph), never built in this process
                   graph (assoc :base-graph {:point (pdb/point db0) :graph graph})
                   ;; main's reconciler index as the fork server keeps it, for db0: the first turn
                   ;; observes the branch from it (seon.agent/reconcile-index), never lists it here
                   index (assoc :index index)
                   ;; main's schema registry as the fork server read it: the branch's until a write moves a
                   ;; schema entry (schema-moved), so no eval reads the schema entries before then
                   (and schema-registry (not resumed)) (assoc :schema-registry schema-registry)))
     (swap! state vary-meta assoc :seon.render/schema :seon.agent/state)
     (mark! :state)
     (swap! state assoc :marks @marks)
     ;; a kept branch, resumed (root released its held task: seon.reconcile/hold-tx), takes
     ;; its task from main, with the failure and evidence the last run left there; an
     ;; agent with no task (a fresh one on a kept branch) takes none
     (when-let [tx (when (and resumed task) (not-empty (task/onto-tx db0 @store-conn task)))]
       (transact! tx))
     ;; forked for a task: it is this session's on the branch from the start. A label that names no task of main's (a check's
     ;; agent works "lc 0") begins none
     (when (and task (d/entity (heard-main) [:seon.task/id task])) (begin-run! task))))))
