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
  (:require [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.error :as err] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.merge :as pm] [seon.config :as cfg] [seon.lib :as lib] [seon.print :as print] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [seon.program :as program] [seon.task :as task] [seon.intr :as intr] [clojure.repl]
            [malli.registry :as mr]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :task :attempt (the task and attempt root forked it for, or nil) :fd :conn :db :base (main at the branch's merge base: as forked, then each merge-main!) :results :n :pending :tx-seq :mode :ns :home :nss :mismatch
;; :schema-registry (seon.repl: the branch's schema registry, by its schema changes)
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

(defn messages
  "The messages delivered to this agent and not yet taken ({:from :body}), taken: root's
  requests (e.g. :catch-up, \"main moved, merge main\") and other agents' sends."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (let [[old _] (swap-vals! state assoc :inbox [])] (:inbox old)))
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
        ;; Flow's ping reaches a waiting agent too: health while an effect is in flight
        (= :ping (:op m)) (do (send! {:op :pong :status :waiting :count (:n @state 0) :held 0
                                      :state {:evals (:n @state) :waiting-for op}})
                              (recur))
        :else (do (swap! state update :pending conj m) (recur))))))


(defn commit!
  "Commit the writes this agent applied (run-ahead) to its branch, once, through its own
  store handle, and tell root (its flush makes the commit durable). nil when nothing waits."
  {:malli/schema [:=> [:cat] :any]}
  []
  (when-let [c (dw/commit-pending! (:store-conn @state))]
    (swap! state #(-> % (assoc :db (:db c)) (dissoc :dirty?)))
    ;; the eval errors folded since the last commit ride it (seon.error/note): root counts
    ;; them across agents
    (let [errs (:errors @state)]
      (when errs (swap! state dissoc :errors))
      (send! (cond-> {:op :committed :t (:max-tx (:db c)) :txs (:tx-count c)} errs (assoc :errors (vec (vals errs))))))))

(defn transact!
  "Write tx-data to this agent's branch: applied at once to the value ahead of the
  committed one (its answer does not wait for a commit), committed when the agent is idle
  or at a turn's end, never because time passed (owner, 2026-10-20: the agent-owned run-ahead
  writer). Answers {:t :datoms :tempids}. Inside a test (a fork of the agent) it applies to
  the fork's db only. A refused transaction throws its message into the caller's eval."
  {:malli/schema [:=> [:cat :seon.db/tx-data] :seon.agent/tx-result]}
  [tx-data]
  (if (= :test (:mode @state))
    (let [r (d/with (db) tx-data)]
      (swap! state assoc :db (:db-after r))
      {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r)) :local true})
    (let [r (dw/apply! (:store-conn @state) {:tx-data tx-data :tx-meta (pdb/tx-meta)})]
      ;; past this process's block of entity ids: the typed error, nothing applied
      (when-let [e (and (not (map? r)) (pdb/lease-spent r (:lease @state)))] (throw (ex-info (:seon.error/message e) e)))
      (when-not (map? r) (throw (ex-info (str "transact refused: " (ex-message r)) {:writer-error (ex-message r)})))
      (swap! state assoc :db (:db-after r) :dirty? true)
      (cond-> {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r))}
        (seq (:tempids r)) (assoc :tempids (into {} (filter (comp string? key)) (:tempids r)))))))

;; ---- effects (seon.fx, worktree session FX1): root's owner procs, through the agent's proc ---

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
                           :seon.fx/status (if (:seon.error/type r) :error :ok)
                           :seon.fx/us (long (:seon.fx/us r 0)) :seon.fx/queue-us (long (:seon.fx/queue-us r 0))}
                          (for [[f a] model-cost-attrs :let [v (when (map? c) (get c f))] :when (int? v)] [a (long v)]))
              (:cost r) (assoc :seon.fx/cost (long (:cost r)))
              (:seon.error/type r) (assoc :seon.fx/error (str (:seon.error/type r) ": " (:seon.error/message r))
                                          :seon.fx/error-type (:seon.error/type r)))])
    kind))

(defn- fx-test
  "An effect asked inside a test fork: the stub's outcome, or the declared error."
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [kind req]
  (if *fx-stub*
    (*fx-stub* kind req)
    (contract/error :seon.fx/in-test 'seon.fx/call "an effect inside a test does not leave the test's process: bind seon.agent/*fx-stub*"
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

;; ---- merging main into this branch (seon.merge; session M1) -------------------------------

(defn merge-main!
  "git merge main into this agent's branch, in this process (it owns the branch's writes):
  main's changes since the merge base that do not conflict apply in one merge commit with
  main as parent, each conflict stays on the branch as an unmerged entry, and the changed
  definitions load here. data-attrs: the data that merges beside the program. Answers
  {:ok :applied :conflicts :t}."
  {:malli/schema [:=> [:cat :seon.merge/data-attrs] [:map [:ok :boolean]]]}
  [data-attrs]
  (commit!)
  (let [conn (:store-conn @state)
        main-b (pdb/main-branch (:cfg @state))
        main (pdb/head conn main-b)
        head @conn
        r (pm/three-way (pm/program (:base @state) data-attrs) (pm/program head data-attrs) (pm/program main data-attrs))]
    (if (empty? (:theirs r))
      {:ok true :applied [] :conflicts [] :t (:max-tx head)}
      (let [theirs (pm/program main data-attrs)
            rep (d/merge-db conn #{main-b} (into (pm/apply-tx head (into {} (for [k (:apply r)] [k (get theirs k)])))
                                              (pm/unmerged-tx head (:conflicts r) (:max-tx main)))
                             (pdb/tx-meta))
            ops (pdb/report->tx rep)
            errs (program/load-changes! (:db-after rep) ops)]
        ;; the merge base is now main's head: what main changed is not this branch's change
        (swap! state assoc :db @conn :base (pdb/branch-base conn main-b (:branch @state)))
        (send! {:op :committed :t (:max-tx @conn) :txs 1})
        (cond-> {:ok true :applied (:apply r) :conflicts (:conflicts r) :t (:max-tx @conn) :datoms (count ops)}
          (seq errs) (assoc :load-errors errs))))))

;; ---- evaluation -----------------------------------------------------------------


(defn error-map
  "An exception caught at one of the agent's entrant boundaries (operation: the REPL's
  seon.repl/eval-code by default) as the one error map: its ex-data when that already is
  one (a contract's error names its operation, member and problems), else the
  :seon.agent/eval-error variant, with the exception's class, its ex-data printed by the one
  printer, and its cause chain (each cause's class and message)."
  {:malli/schema [:function [:=> [:cat :seon.host/throwable] :seon.error/base]
                  [:=> [:cat :seon.host/throwable :qualified-symbol] :seon.error/base]]}
  ([e] (error-map e 'seon.repl/eval-code))
  ([^Throwable e op]
   (if (contract/error? (ex-data e))
     (ex-data e)
     (let [m (or (ex-message e) (str (type e)))
           causes (mapv #(str (type %) ": " (ex-message %)) (take-while some? (rest (iterate ex-cause e))))]
       (contract/error :seon.agent/eval-error op m
                       (cond-> {:class (str (type e))}
                         (ex-data e) (assoc :data (print/text (ex-data e) (:part print/budgets)))
                         (seq causes) (assoc :causes causes)))))))

(defn write-end!
  "Attempt's end on this branch (:seon.end/*, which root's judge reads), committed: its
  outcome, and when known its reason, the error that ended it (as data), its turns, tokens
  and waste. The turn loop's end and the process's death write it the same way."
  {:malli/schema [:=> [:cat :seon.attempt/id [:map [:outcome :seon.attempt/outcome]]] :any]}
  [attempt {:keys [outcome reason error turns tokens waste]}]
  (transact! [(cond-> {:seon.end/attempt attempt :seon.end/outcome outcome}
                turns (assoc :seon.end/turns turns)
                tokens (assoc :seon.end/tokens tokens)
                reason (assoc :seon.end/reason reason)
                error (assoc :seon.end/error error)
                (seq waste) (assoc :seon.end/waste waste))])
  (commit!))

(defn died!
  "The process's death by defect e, caught at its entrant boundary (seon.repl/serve!): the
  cause, printed by the one printer, to stderr (root records its tail with the death, even
  when the end cannot be written), then the end of the attempt this process works, written
  on its branch. Root names the death by the exit status that follows."
  {:malli/schema [:=> [:cat :seon.host/throwable] :any]}
  [e]
  (let [em (error-map e 'seon.repl/serve!)]
    (sock/write-line! 2 (print/text em (:part print/budgets)))
    (when-let [a (get-in @state [:turn :attempt])]
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
  page renders them; the judge persists them on the attempt)."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [m var]
  (let [cost (when (map? (:actual m)) (select-keys (:actual m) [:rows :verdict]))]
    (cond-> {:type (:type m) :var var :message (:message m)}
      (not= :pass (:type m)) (assoc :expected (print/text (:expected m) (:part print/budgets))
                                    :actual (print/text (:actual m) (:part print/budgets)))
      ;; what the assertion evaluated to: clojure.test reports a failed (is (pred a b)) as
      ;; (not (pred a' b')), so its second element; the agent reads it beside the assertion
      (and (= :fail (:type m)) (seq? (:actual m)) (= 'not (first (:actual m))))
      (assoc :got (print/text (second (:actual m)) (:part print/budgets)))
      (contract/valid? :seon.cost/report cost) (assoc :cost cost))))

(defn test-vars
  "The tests that judge branch db, as this process holds them: proofs, and every test that
  reaches a var the branch changed since base (main at its merge base), the changed tests
  among them (seon.reconcile/done-explain reads only these). A proof with no var here does
  not run; scratch namespaces (user, ^:scratch) never merge, so their tests never judge."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:set :qualified-symbol]] [:sequential :seon.host/var]]}
  [db base proofs]
  (let [changed (program/changed-vars base db)]
    (filter #(and (:test (meta %)) (not (program/scratch-ns? (:ns (meta %)))))
            (keep #(some-> (find-ns (symbol (namespace %))) (ns-resolve (symbol (name %))))
                  (sort-by str (into (into proofs changed) (program/reaching-tests db changed)))))))

(defn- run-tests-here
  "clojure.test over the branch's test-vars for the task's proofs, with the branch's schemas in effect (malli's
  dynamic registry bound to seon.program/schema-registry, as the agent's REPL binds it), the
  whole run within ticks engine ticks of work: answers report data. Each test var is written
  to w as it starts ({:seon.test/started v}), so the agent can name the test a death interrupted; a run
  past its ticks stops with the typed :seon.test/ticks-spent naming the test that was running."
  {:malli/schema [:=> [:cat :int pos-int? [:set :qualified-symbol]] :seon.test/result]}
  [w ticks proofs]
  (let [reports (atom [])
        started (atom nil)
        orig t/report
        vars (test-vars (db) (:base @state) proofs)
        r (try (binding [t/report (fn [m]
                                    (when (= :begin-test-var (:type m))
                                      (reset! started (str (:var m)))
                                      (sock/write-line! w (pr-str {:seon.test/started @started})))
                                    (when (#{:pass :fail :error} (:type m))
                                      (swap! reports conj (test-report m (some-> (first t/*testing-vars*) str))))
                                    (orig m))
                         t/*test-out* (java.io.StringWriter.)
                         mr/*registry* (program/schema-registry (program/schema-changes (db)))]
                 ;; exactly the selected vars, each namespace's fixtures around them
                 (jolt.host/run-interruptible (jolt.host/make-interrupt) #(t/test-vars vars) ticks)
                 nil)
               (catch Throwable e
                 (if-let [n (:jolt/ticks-spent (ex-data e))]
                   (contract/error :seon.test/ticks-spent 'seon.agent/run-tests-forked
                                   (str "the test run spent its budget of " ticks " engine ticks (work done, not time)"
                                        (when @started (str " in " @started)) ": a test that never ends, or more work than the run allows")
                                   (cond-> {:ticks n :budget ticks} @started (assoc :test @started)))
                   (throw e))))
        reports (cond-> @reports
                  r (conj (cond-> {:type :error :message (:seon.error/message r) :seon.error/type (:seon.error/type r)
                                   :ticks (:ticks r) :budget ticks}
                            @started (assoc :var @started))))
        counts (frequencies (map :type reports))]
    {:pass (counts :pass 0) :fail (counts :fail 0) :error (counts :error 0) :test (count (distinct (keep :var reports)))
     :reports reports}))

(defn run-answer
  "The run, from the last line the test child wrote on its pipe before EOF (nil: none) and
  its exit status: that line when it is the run's result; else the child died while running
  the test the line's {:seon.test/started var} names, and the run is one :error report on
  that test var with the status as :exit (none started: a run error naming the status)."
  {:malli/schema [:=> [:cat [:maybe :string] :seon.lib/exit-status] :seon.test/result]}
  [line status]
  (let [v (some-> line read-string) var (:seon.test/started v)]
    (if (contains? v :pass)
      v
      {:pass 0 :fail 0 :error 1
       :reports [(cond-> {:type :error :exit status
                          :message (str "test process died: " status (when var (str " while " var " ran")))}
                   var (assoc :var var))]})))

(defn run-tests-forked
  "Fork this agent; the child runs the test-vars of proofs within ticks engine ticks of work, writing
  each test var as it starts and then one EDN line of its result on a pipe. The agent reads
  the pipe to its end: no clock; the child ends by finishing, by its budget, or by dying
  (then the last test it started is named). (forked pid) runs here once the child exists."
  {:malli/schema [:function [:=> [:cat pos-int? [:set :qualified-symbol]] :seon.test/result]
                  [:=> [:cat pos-int? [:set :qualified-symbol] :seon.host/fn] :seon.test/result]]}
  ([ticks proofs] (run-tests-forked ticks proofs (fn [_] nil)))
  ([ticks proofs forked]
  (let [[r w] (lib/pipe) t0 (profile/now-ns)]
    (lib/fork!
      {:refused (fn [n] (lib/c-close r) (lib/c-close w)
                  {:pass 0 :fail 0 :error 1 :ms (profile/ms-since t0)
                   :reports [{:type :error :message (str "cannot fork the tests: " n " Chez threads active (a running future?)")}]})
       :child (fn []
                (lib/c-close r)
                ;; the run ends by its budget; if the agent dies first, the next write to the
                ;; pipe ends this child (EPIPE). The agent's socket is FD_CLOFORK: not held here.
                (lib/set-trip! (* 16 1048576))
                (swap! state assoc :mode :test :evaluating false)
                (let [res (try (run-tests-here w ticks proofs)
                               (catch Throwable e {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]}))]
                  (sock/write-line! w (pr-str res))))
       :parent (fn [pid]
                 (lib/c-close w)
                 (forked pid)
                 (let [rd (lib/line-reader r)
                       line (loop [line nil]
                              (let [ls (rd -1)]
                                (if (= ls :eof) line (recur (or (peek ls) line)))))
                       st (lib/wait-status pid)]
                   (lib/c-close r)
                   (assoc (run-answer line st) :ms (profile/ms-since t0) :pid pid :exit st)))}))))

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

(defn mem-mb
  "Real MB of this process (lib/real-mb walks the VM regions, ~1.5 ms), refreshed at most
  once a second, or now when fresh?."
  {:malli/schema [:function [:=> [:cat] :seon.lib/mb] [:=> [:cat :boolean] :seon.lib/mb]]}
  ([] (mem-mb false))
  ([fresh?]
   (let [{:keys [mem-at mem]} @state now (profile/now-ns)]
     (if (and (not fresh?) mem-at (< (- now mem-at) 1000000000))
       mem
       (let [m (lib/r2 (lib/real-mb))] (swap! state assoc :mem m :mem-at now) m)))))

(declare clone!)

(defn handle!
  "One Seon platform request; answers the messages to send back (a vector). (REPL
  requests are nREPL's: seon.repl.)"
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] [:maybe [:vector :seon.wire/seon-msg]]]}
  [m]
  (case (:op m)
    ;; the proofs of the task root names (:task), as this branch holds them
    ;; root hears the test child's pid once it is forked (its agent proc's :tests-started event)
    :tests [{:op :tests :id (:id m) :result (run-tests-forked (or (:ticks m) (cfg/value :seon.config.agent/test-ticks))
                                                              (or (some->> (:task m) (task/task (db)) :seon.task/accumulated :proofs) #{})
                                                              #(send! {:op :tests-started :id (:id m) :pid %}))
             :mem-mb (mem-mb)}]
    ;; an async effect's outcome (fx-send!): a message to this agent, recorded
    :fx-reply (let [r (dissoc m :op :k)]
                (swap! state update :inbox (fnil conj []) {:from :fx :k (:k m) :kind (fx-record! (:k m) r) :body r})
                nil)
    ;; root asks this agent to merge main into its branch (it owns the branch's writes)
    :merge-main [{:op :merged-main :id (:id m) :result (merge-main! (:data-attrs m #{}))}]
    :clone (do (clone! m) nil)
    :stats [{:op :stats :id (:id m) :result {:mem-mb (mem-mb true) :t (:max-tx (db)) :pid (lib/c-getpid)
                                              :results (count (:results @state)) :evals (:n @state)
                                              :ids (pdb/lease-use (db) (:lease @state))
                                              :heap-mb (lib/r2 (/ (lib/bytes-in-heap) 1048576.0))}}]
    ;; a message (session F2): it waits in :inbox until the agent's code takes it ((messages));
    ;; root hears that it arrived
    :message (do (swap! state update :inbox (fnil conj []) (select-keys m [:from :body]))
                 [{:op :received :sent-ns (:ns m) :recv-ns (profile/now-ns)}])
    nil))

(defn hello! {:malli/schema [:=> [:cat :int :map] [:or [:= true] [:= :dead]]]}
  [t-req-ns extra]
  (send! (merge {:op :hello :pid (lib/c-getpid) :start-ms (profile/ms-since t-req-ns) :mem-mb (mem-mb) :t (:max-tx (db))} extra)))

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
      (if-not (:ok r)
        (do (sock/close! fd) (send! {:op :clone-failed :k k :error (:error r)}))
        (lib/fork!
          {:fd fd :double? true
           :refused (fn [n] (send! {:op :clone-failed :k k :error (str "cannot fork: " n " Chez threads active (a running future?)")}))
           :parent (fn [_] (send! {:op :cloned :k k :clone (:agent m)}))
           :child (fn []
                    (let [parent (:id @state)]
                      ;; the parent's socket closed on fork (FD_CLOFORK); this clone's came with the request
                      (sock/clofork! fd)
                      ;; the clone opens the store itself, on its own branch, with its own lease
                      (pdb/after-fork! (:cfg r) (:lease r))
                      (stderr-to-file! (stderr-path (pdb/agents-dir (:cfg r)) (:agent m)))
                      (let [store-conn (d/connect (assoc (:cfg r) :branch (:branch m)))]
                        (swap! state assoc :id (:agent m) :branch (:branch m) :fd fd :conn (sock/conn fd) :mode :agent :tx-seq 0
                               :lease (:lease r) :pending clojure.lang.PersistentQueue/EMPTY :mem-at nil :fx-pending {}
                               :store-conn store-conn :db @store-conn))
                      (hello! t0 {:cloned-from parent})
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
  [fd {id :agent branch :branch task :task attempt :attempt cfg :cfg lease :lease resumed :resumed commit :commit t :t} db0 serve]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
  ;; the config this process was forked holding (root read it; an agent reads no environment)
  (lib/set-trip! (cfg/value :seon.config.agent/trip-bytes))
  (lib/heap-cap! (+ (lib/bytes-in-heap) (cfg/value :seon.config.agent/heap-extra-bytes)))
  (stderr-to-file! (stderr-path (pdb/agents-dir cfg) id))
  ;; this agent owns its branch's writes: it opens the store itself (seon.db/after-fork!)
  (pdb/after-fork! cfg lease)
  ;; the model's API key is root's alone (its :model owner calls the model; session FX1)
  (lib/c-unsetenv (cfg/value :seon.config.ai/api-key-variable))
  (let [nsx (make-ns! id)
        intr-ok (interrupt-self-test)
        c (sock/conn fd)
        store-conn (d/connect (assoc cfg :branch branch))]
    (intr/install!)
    ;; a kept branch, resumed: its program is what this process runs (it was forked holding main's)
    (let [load-errors (when resumed (program/load-diff! db0 @store-conn))
          misaligned (when-not resumed (misaligned commit t @store-conn db0))]
     ;; a resumed branch whose program does not load runs main's for what failed: its errors
     ;; go to root with its hello (recorded problems), never dropped (T5b: a stub ran silently)
     ;; :base is the branch's merge base with main: a new branch's is the image (main as it
     ;; forked); a resumed one's is older than main, and comparing against main's head would
     ;; read main's later changes as the branch's (seon.program/divergence, changed-vars)
     (reset! state {:id id :branch branch :task task :attempt attempt :fd fd :conn c :db @store-conn
                   :base (if resumed (pdb/branch-base store-conn (pdb/main-branch cfg) branch) db0) :store-conn store-conn :cfg cfg :lease lease :results {} :n 0
                   :load-errors (vec load-errors) :misaligned misaligned
                   :pending clojure.lang.PersistentQueue/EMPTY :tx-seq 0 :mode :agent
                   :ns nsx :nss #{(ns-name nsx)} :interrupt-ok intr-ok :serve serve}))))
