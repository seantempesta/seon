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
  first multi-threaded collection (lane RC1). Adding pump or worker threads to an agent
  breaks its forks; docs/DRAGONS.md, "Physics". Agent code calls the verbs of my.db and my.agent,
  referred into its namespace. From proto/flow f2-final proto.agent (lane F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.error :as err] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.merge :as pm] [seon.config :as cfg] [seon.lib :as lib] [seon.print :as print] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [jolt.ffi :as ffi] [seon.program :as program] [seon.intr :as intr] [clojure.repl]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :fd :conn :db :results :n :pending :tx-seq :mode :ns :home :nss :mismatch
(defonce state (atom nil))

;; collect-trip-bytes in an agent: Chez's default scales with the parent heap (E10); a
;; small trip keeps an agent's garbage, and so its private pages, small
(def trip-bytes (* 1048576 (Long/parseLong (or (System/getenv "SEON_TRIP_MB") "4"))))

;; An agent's heap may grow this far beyond what it inherited; past it an allocation
;; throws OutOfMemoryError (an :err item), so a runaway agent cannot take the machine.
(def heap-extra-bytes (* 1048576 (Long/parseLong (or (System/getenv "SEON_AGENT_HEAP_MB") "1024"))))

;; ---- the socket to root: values in frames (root's end is a jolt.async.remote end) ------
;; This side reads and writes its socket itself, on its one thread, with no pumps: an agent
;; forks (its tests, a clone), and a process that forks must never have started Chez's
;; parallel-collector sweeper threads (lane RC1: a child of such a process hangs in its
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
    (if (:dirty-at @state)
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
    (swap! state #(-> % (assoc :db (:db c)) (dissoc :dirty-at)))
    ;; the eval errors folded since the last commit ride it (seon.error/note): root counts
    ;; them across agents
    (let [errs (:errors @state)]
      (when errs (swap! state dissoc :errors))
      (send! (cond-> {:op :committed :t (:max-tx (:db c)) :txs (:tx-count c)} errs (assoc :errors (vec (vals errs))))))))

(defn transact!
  "Write tx-data to this agent's branch: applied at once to the value ahead of the
  committed one (its answer does not wait for a commit), committed when the agent is idle,
  at a turn's end or within the flush window (owner, 2026-10-20: the agent-owned run-ahead
  writer). Answers {:t :datoms :tempids}. Inside a test (a fork of the agent) it applies to
  the fork's db only. A refused transaction throws its message into the caller's eval."
  {:malli/schema [:=> [:cat :seon.db/tx-data] :seon.agent/tx-result]}
  [tx-data]
  (if (= :test (:mode @state))
    (let [r (d/with (db) tx-data)]
      (swap! state assoc :db (:db-after r))
      {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r)) :local true})
    (let [r (dw/apply! (:store-conn @state) {:tx-data tx-data})]
      (when-not (map? r) (throw (ex-info (str "transact refused: " (ex-message r)) {:writer-error (ex-message r)})))
      (let [now (lib/now-ns)
            since (:dirty-at (swap! state #(-> % (assoc :db (:db-after r)) (update :dirty-at (fn [t] (or t now))))))]
        (when (>= (- now since) (* 1000000 cfg/FLUSH-MS)) (commit!))
        (cond-> {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r))}
          (seq (:tempids r)) (assoc :tempids (into {} (filter (comp string? key)) (:tempids r))))))))

;; ---- effects (seon.fx, lane FX1): root's owner procs, through the stand-in -------------

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
  completion, reasoning, cache hits) when the provider reported them."
  {:malli/schema [:=> [:cat :int :keyword :map] :any]}
  [k kind r]
  (let [c (or (:value r) r)]
    (swap! state assoc-in [:defs [:fx k]]
           [(cond-> (into {:seon.fx/id (str (name (:id @state)) "-" (lib/c-getpid) "-fx" k) :seon.fx/kind kind
                           :seon.fx/status (if (:seon.error/type r) :error :ok)
                           :seon.fx/us (long (:seon.fx/us r 0)) :seon.fx/queue-us (long (:seon.fx/queue-us r 0))}
                          (for [[f a] model-cost-attrs :let [v (when (map? c) (get c f))] :when (int? v)] [a (long v)]))
              (:cost r) (assoc :seon.fx/cost (long (:cost r)))
              (:seon.error/type r) (assoc :seon.fx/error (str (:seon.error/type r) ": " (:seon.error/message r))
                                          :seon.fx/error-type (:seon.error/type r)))])))

(defn- fx-test
  "An effect asked inside a test fork: the stub's outcome, or the declared refusal."
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [kind req]
  (if *fx-stub*
    (*fx-stub* kind req)
    (contract/error :seon.fx/in-test 'seon.fx/call "an effect inside a test does not leave the test's process: bind seon.agent/*fx-stub*"
                    {:seon.fx/kind kind})))

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
          _ (when (= :dead (send! (merge opts {:op :fx :k k :kind kind :req req}))) (lib/c-exit 0))
          m (await-op! :fx-reply k)
          r (dissoc m :op :k)]
      (swap! state update :fx-pending dissoc k)
      (fx-record! k kind r)
      r)))

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

;; ---- merging main into this branch (seon.merge; lane M1) -------------------------------

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
        main (pdb/head conn :db)
        head @conn
        r (pm/three-way (pm/program (pdb/branch-base conn (:branch @state)) data-attrs) (pm/program head data-attrs) (pm/program main data-attrs))]
    (if (empty? (:theirs r))
      {:ok true :applied [] :conflicts [] :t (:max-tx head)}
      (let [theirs (pm/program main data-attrs)
            rep (d/merge-db conn #{:db} (into (pm/apply-tx head (into {} (for [k (:apply r)] [k (get theirs k)])))
                                              (pm/unmerged-tx head (:conflicts r) (:max-tx main))))
            ops (pdb/report->tx rep)
            errs (program/load-changes! (:db-after rep) ops)]
        (swap! state assoc :db @conn)
        (send! {:op :committed :t (:max-tx @conn) :txs 1})
        (cond-> {:ok true :applied (:apply r) :conflicts (:conflicts r) :t (:max-tx @conn) :datoms (count ops)}
          (seq errs) (assoc :load-errors errs))))))

;; ---- evaluation -----------------------------------------------------------------

(def ^:private message-chars
  "An exception's message is kept to this many characters in its error map."
  2000)

(defn error-map
  "A form's exception as the one error map: its ex-data when that already is one (a
  contract refusal names its operation, member and problems), else the
  :seon.agent/eval-error variant raised at the agent's REPL boundary, with the exception's
  class and its ex-data printed by the one printer."
  {:malli/schema [:=> [:cat :seon.host/throwable] :seon.error/base]}
  [^Throwable e]
  (if (contract/error? (ex-data e))
    (ex-data e)
    (let [m (or (ex-message e) (str (type e)))]
      (contract/error :seon.agent/eval-error 'seon.repl/eval-code (subs m 0 (min message-chars (count m)))
                      (cond-> {:class (str (type e))}
                        (ex-data e) (assoc :data (print/text (ex-data e) (:part print/budgets))))))))

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
  rows and verdict (seon.cost/proof's :actual) kept as data under :cost (lane W2's agent
  page renders them; the judge persists them on the attempt)."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [m var]
  (let [cost (when (map? (:actual m)) (select-keys (:actual m) [:rows :verdict]))]
    (cond-> {:type (:type m) :var var :message (:message m)}
      (not= :pass (:type m)) (assoc :expected (print/text (:expected m) (:part print/budgets))
                                    :actual (print/text (:actual m) (:part print/budgets)))
      (contract/valid? :seon.cost/report cost) (assoc :cost cost))))

(defn test-namespaces
  "The namespaces whose tests judge a branch: every program namespace of db holding tests
  (main's, merged proofs among them, and the ones this agent created). Scratch namespaces
  (user, ^:scratch) never merge, so their tests never judge."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential :seon.host/namespace]]}
  [db]
  (filter (fn [nsx] (and (not (program/scratch-ns? nsx)) (some (comp :test meta) (vals (ns-interns nsx)))))
          (keep find-ns (sort (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db)))))

(defn- run-tests-here
  "clojure.test over test-namespaces of the branch; answers report data."
  {:malli/schema [:=> [:cat] :seon.test/result]}
  []
  (let [reports (atom [])
        orig t/report
        nss (test-namespaces (db))
        counts (binding [t/report (fn [m]
                                    (when (#{:pass :fail :error} (:type m))
                                      (swap! reports conj (test-report m (some-> (first t/*testing-vars*) str))))
                                    (orig m))
                         t/*test-out* (java.io.StringWriter.)]
                 ;; test-ns tests the vars interned now (Jolt's run-tests also runs its
                 ;; registry, which keeps a test after ns-unmap)
                 (apply merge-with + {:test 0 :pass 0 :fail 0 :error 0}
                        (map #(select-keys (t/test-ns %) [:test :pass :fail :error]) nss)))]
    {:pass (:pass counts 0) :fail (:fail counts 0) :error (:error counts 0) :test (:test counts 0)
     :reports @reports}))

(defn- ffi-status
  "Reap the test child (killing it first when kill?); answers its decoded exit status."
  {:malli/schema [:=> [:cat :seon.lib/pid :boolean] :seon.lib/exit-status]}
  [pid kill?]
  (when kill? (lib/c-kill pid 9))
  (let [b (ffi/alloc 4)]
    (lib/c-waitpid pid b 0)
    (lib/decode-status (ffi/read b :int))))

(defn run-tests-forked
  "Fork this agent; the child runs the tests and writes one EDN line on a pipe. A child
  that does not answer in timeout-ms is killed."
  {:malli/schema [:=> [:cat :int] :seon.test/result]}
  [timeout-ms]
  (let [[r w] (lib/pipe) t0 (lib/now-ns) pid (lib/safe-fork 1000)]
    (cond
      (= pid -2)
      (do (lib/c-close r) (lib/c-close w)
          {:pass 0 :fail 0 :error 1 :ms (lib/ms-since t0)
           :reports [{:type :error :message "cannot fork the tests: another thread of this agent stayed busy for 1 s (a running future?)"}]})
      (zero? pid)
      (do (lib/c-close r)
          ;; die with the agent: it kills this child at the bound; if the agent dies itself,
          ;; the engine timer sees this process re-parented and exits (no watcher thread).
          ;; The agent's socket is FD_CLOFORK: this child does not hold it.
          (lib/set-trip! (* 16 1048576))
          (swap! state assoc :mode :test :evaluating false)
          (let [agent-pid (lib/c-getppid)
                res (try (lib/run-bounded (fn [] (not= agent-pid (lib/c-getppid))) (fn [] (lib/c-exit 0)) run-tests-here)
                         (catch Throwable e {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]}))]
            (lib/write-line! w (pr-str res)))
          (lib/c-exit 0))
      :else
      (do (lib/c-close w)
          (let [rd (lib/line-reader r)
                deadline (+ t0 (* timeout-ms 1000000))
                line (loop []
                       (let [left (quot (- deadline (lib/now-ns)) 1000000)]
                         (if (<= left 0)
                           :timeout
                           (let [ls (rd (min left 1000))]
                             (cond (= ls :eof) :eof (seq ls) (first ls) :else (recur))))))
                st (ffi-status pid (keyword? line))]
            (lib/c-close r)
            (assoc (cond
                     (string? line) (read-string line)
                     (= line :timeout) {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (str "tests timed out after " timeout-ms " ms; killed")}]}
                     :else {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (str "test process died: " st)}]})
                   :ms (lib/ms-since t0) :pid pid :exit st))))))

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
      (refer 'my.agent)
      (refer 'clojure.repl :only '[doc])
      (alias 'd 'datahike.api)
      (alias 'str 'clojure.string))
    nsx))

(defn mem-mb
  "Real MB of this process (lib/real-mb walks the VM regions, ~1.5 ms), refreshed at most
  once a second, or now when fresh?."
  {:malli/schema [:function [:=> [:cat] :seon.lib/mb] [:=> [:cat :boolean] :seon.lib/mb]]}
  ([] (mem-mb false))
  ([fresh?]
   (let [{:keys [mem-at mem]} @state now (lib/now-ns)]
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
    :tests [{:op :tests :id (:id m) :result (run-tests-forked (or (:timeout-ms m) 30000)) :mem-mb (mem-mb)}]
    ;; an async effect's outcome (fx-send!): a message to this agent, recorded
    :fx-reply (let [kind (get-in @state [:fx-pending (:k m)])
                    r (dissoc m :op :k)]
                (swap! state (fn [s] (-> s (update :fx-pending dissoc (:k m))
                                         (update :inbox (fnil conj []) {:from :fx :k (:k m) :kind kind :body r}))))
                (fx-record! (:k m) kind r)
                nil)
    ;; root asks this agent to merge main into its branch (it owns the branch's writes)
    :merge-main [{:op :merged-main :id (:id m) :result (merge-main! (:data-attrs m #{}))}]
    :clone (do (clone! m) nil)
    :stats [{:op :stats :id (:id m) :result {:mem-mb (mem-mb true) :t (:max-tx (db)) :pid (lib/c-getpid)
                                              :results (count (:results @state)) :evals (:n @state)
                                              :heap-mb (lib/r2 (/ (lib/bytes-in-heap) 1048576.0))}}]
    ;; a message (lane F2): it waits in :inbox until the agent's code takes it ((messages));
    ;; root hears that it arrived
    :message (do (swap! state update :inbox (fnil conj []) (select-keys m [:from :body]))
                 [{:op :received :sent-ns (:ns m) :recv-ns (sock/now-ns)}])
    nil))

(defn hello! {:malli/schema [:=> [:cat :int :map] [:or [:= true] [:= :dead]]]}
  [t-req-ns extra]
  (send! (merge {:op :hello :pid (lib/c-getpid) :start-ms (lib/ms-since t-req-ns) :mem-mb (mem-mb) :t (:max-tx (db))} extra)))

;; ---- stderr: a file per agent, its tail read by root when the agent dies -------------

(defn stderr-path
  "Where agent id's stderr goes: <store path>-agents/<id>.err (root makes the directory at
  boot and removes it with a store it owns), or nil for a store with no path (in memory)."
  {:malli/schema [:=> [:cat [:maybe :string] :keyword] [:maybe :string]]}
  [store-path id] (when store-path (str store-path "-agents/" (name id) ".err")))

(defn- stderr-to-file!
  "This process's stderr (fd 2: what Chez, the runtime and native code print) into the
  file at path, truncated. The edge of a dragon: a file that cannot be made leaves stderr
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
  (let [t0 (lib/now-ns)
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
        (let [pid1 (lib/safe-fork 1000)]
          (cond
            (= pid1 -2) (do (sock/close! fd) (send! {:op :clone-failed :k k :error "cannot fork: another thread of this agent stayed busy for 1 s"}))
            (zero? pid1)
            (if (zero? (lib/c-fork))
              (let [parent (:id @state)]
                ;; the parent's socket closed on fork (FD_CLOFORK); this clone's came with the request
                (sock/clofork! fd)
                (let [c (sock/conn fd)]
                  ;; the clone opens the store itself, on its own branch, with its own lease
                  (pdb/after-fork! (:cfg r) (:lease r))
                  (stderr-to-file! (stderr-path (get-in r [:cfg :store :path]) (:agent m)))
                  (swap! state assoc :id (:agent m) :branch (:branch m) :fd fd :conn c :mode :agent :tx-seq 0
                         :pending clojure.lang.PersistentQueue/EMPTY :mem-at nil :fx-pending {}
                         :store-conn (d/connect (assoc (:cfg r) :branch (:branch m))))
                  (swap! state assoc :db @(:store-conn @state)))
                (hello! t0 {:cloned-from parent})
                ((:serve @state)))
              (lib/c-exit 0))
            :else
            (do (ffi-status pid1 false)
                (sock/close! fd)
                (send! {:op :cloned :k k :clone (:agent m)}))))))))

(defn init!
  "Make this process the agent: its socket (fd), its namespace, its state map, its
  interrupt handlers. serve is the loop a clone runs (seon.repl/serve!)."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :seon.host/fn] :any]}
  [fd {id :agent branch :branch cfg :cfg lease :lease resumed :resumed} db0 serve]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
  (lib/set-trip! trip-bytes)
  (lib/heap-cap! (+ (lib/bytes-in-heap) heap-extra-bytes))
  (stderr-to-file! (stderr-path (get-in cfg [:store :path]) id))
  ;; this agent owns its branch's writes: it opens the store itself (seon.db/after-fork!)
  (pdb/after-fork! cfg lease)
  ;; the model's API key is root's alone (its :model owner calls the model; lane FX1)
  (doseq [v (distinct (remove nil? [(System/getenv "SEON_AI_API_KEY_ENV") "DEEPSEEK_API_KEY"]))] (lib/c-unsetenv v))
  (let [nsx (make-ns! id)
        intr-ok (interrupt-self-test)
        c (sock/conn fd)
        store-conn (d/connect (assoc cfg :branch branch))]
    (intr/install!)
    ;; a kept branch, resumed: its program is what this process runs (it was forked holding main's)
    (let [load-errors (when resumed (program/load-diff! db0 @store-conn))]
     ;; a resumed branch whose program does not load runs main's for what failed: its errors
     ;; go to root with its hello (recorded problems), never dropped (T5b: a stub ran silently)
     (reset! state {:id id :branch branch :fd fd :conn c :db @store-conn :store-conn store-conn :cfg cfg :results {} :n 0
                   :load-errors (vec load-errors)
                   :pending clojure.lang.PersistentQueue/EMPTY :tx-seq 0 :mode :agent
                   :ns nsx :nss #{(ns-name nsx)} :interrupt-ok intr-ok :serve serve}))))
