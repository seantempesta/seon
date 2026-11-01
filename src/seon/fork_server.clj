(ns seon.fork-server
  "The fork server: ONE thread (E13). It holds main's db value, applies every main
  commit's tx-data with d/db-with in commit order, and forks agents on request. Commits
  and fork requests arrive on one FIFO pipe, so an agent forked after commit k sees k:
  the agent's branch was created by the writer at exactly that point. Its one socket to
  root (session F2, T1's transport) carries commands down and events up, and each agent's
  socket arrives with its fork request (SCM_RIGHTS). It reaps its
  children and reports their exits. It never starts a thread, a future or core.async.

  It is a step-fn (`server-step`), run on its one thread by `seon.step/run-loop` (session
  F2): the same process contract as root's Flow procs, with pause, resume, stop and ping
  as messages on its pipe."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.lib :as lib] [seon.repl :as repl] [seon.program :as program] [seon.db :as pdb]
            [seon.step :as step] [seon.sock :as sock] [seon.config :as cfg] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [jolt.ffi :as ffi]
            [jolt.deps] [clojure.edn :as edn] [seon.fx :as fx] [seon.reconcile :as r] [seon.agent :as agent] [jolt.io-poller :as poller] [seon.task :as task] [seon.ctx :as ctx] [seon.doc :as doc] [datahike.db.interface :as dbi]))

(defn- reaped
  "Child pid, whose exit arrived (jolt.io-poller/exit-chan): waited for exactly, so the wait is the
  reap, and its :exit event. Answers [st events]."
  {:malli/schema [:=> [:cat :map :seon.lib/pid] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st pid]
  (when-not (= pid (lib/c-waitpid pid (:status-buf st) 0))
    (throw (ex-info "the fork server cannot wait for a child it watched" {:pid pid})))
  [(update st :children disj pid)
   [{:ev :exit :pid pid :status (lib/decode-status (ffi/read (:status-buf st) :int))}]])

(defn- adopt
  "Child pid, now this server's to reap: its exit is put on the server's :exits channel when it comes
  (jolt.io-poller/exit-chan: at once for one already gone), where the loop takes it as a command.
  Answers [st events]."
  {:malli/schema [:=> [:cat :map :seon.lib/pid] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st pid]
  (poller/exit-chan pid (:exits st))
  [(update st :children conj pid) []])

(declare main)

(defn- fork-carrying!
  "lib/fork! of a request whose socket came with it (SCM_RIGHTS), fd (taken from the request's frame when the step began,
  so a step that throws before it forks can close it: server-step): the child closes this
  server's descriptors and runs (child fd). open, a volatile holding fd, is cleared once fork! has
  closed it here (before refused or parent runs)."
  {:malli/schema [:=> [:cat :map :seon.lib/fd :seon.host/volatile :seon.host/fn :seon.host/fn :seon.host/fn] :any]}
  [st fd open child refused parent]
  (let [{:keys [conn]} st done (fn [f] (fn [x] (vreset! open nil) (f x)))]
    (lib/fork! {:fd fd :refused (done refused) :parent (done parent)
                :child #(do (sock/close! (:fd conn)) (sock/close-pending-fds! conn) (child fd))})))

(defn graph-at-fork
  "Server state st with main's reach graph (built when the server starts) brought to :db, only
  when a commit since moved the program (:graph-moved, seon.program/moves-program?): a spawn's own commit (its
  run) moves none, and reach-update reads every live form (15-21 ms a fork, measured). The graph keeps its
  index (seon.program/reach-indexed), moved with it and held under the inputs an agent forked from :db takes
  (seon.agent/test-inputs: made whole here when they are not the held ones, never in every agent), so an agent's
  base-graph starts from main's answers and recomputes only the tests its base differs in
  (docs/source.md#tests-run-by-reach). An unmoved program is a key lookup."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (cond-> st (:graph-moved st)
          (-> (update :graph #(-> % (program/reach-update (:db st)) (program/reach-indexed (second (agent/test-inputs (:db st))))))
              (dissoc :graph-moved))))

(defn index-at-fork
  "Server state st with main's reconciler index (:index {:commit :index}, listed when the server
  starts: the index and the commit it is of, never the db, which would pin its own version of the
  index between forks) observed forward to :db (seon.reconcile/index over what differs, the commit
  made again for it), when main moved since: a forked agent's first turn observes its branch from
  it, never lists the index itself (a listing of 903 tasks: 33M ticks, 160 MB, in every agent). See
  docs/machine.md#roots-heap."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [{from :commit idx :index} (:index st)]
    (cond-> st (not= from (d/commit-id (:db st)))
            (assoc :index {:commit (d/commit-id (:db st)) :index (r/index idx (d/commit-as-db (:store st) from) (:db st))}))))

(defn evals-max-after
  "Main's highest eval number (seon.repl/evals-max) after a commit's ops (resolved [op e a v]), from
  n, its highest before: what each agent forked from here counts its evals on from, kept at the
  cost of the commit, never by reading main's evals; a commit the writer's notice does not match
  reads them again (the :tx step). See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat nat-int? [:sequential :any]] nat-int?]}
  [n ops] (reduce (fn [n [_ _ a v]] (if (= :seon.eval/id a) (max n (or (repl/eval-number v) 0)) n)) n ops))

(defn warm-start
  "Server state st with the index nodes the start of the agent m forks reads read into this process's
  node cache: its first write (seon.agent/begin-run-tx on its own task, run and key, then the cites
  every agent is made with) applied with d/with to :db and dropped, and its record built on it
  (seon.ctx/build: its task, run and the sections it cites); and, once per move of main (:warm-t), main's
  schema registry (seon.program/schema-changes), which each agent forked holds as its branch's until it writes a schema entry (:schema-registry). An agent's reads stay
  in its own process, so without these each agent decoded them cold, and more of them as main's commits
  rewrote leaves and its cache evicted the rest; read here, each agent inherits them decoded, and this
  process's reads keep them in its cache. Only what this agent reads is read, so what the server holds
  grows by that, not by what main writes (its static: seon.fork-server/static-due?). Nothing is written.
  See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [task run key agent branch]}]
  (let [db (:db st) t (:max-tx db)]
    (when (and task run key (d/entity db [:seon.task/id task]))
      (let [db1 (:db-after (d/with db (agent/begin-run-tx db task run key agent)))
            db2 (:db-after (d/with db1 (task/agent-cites-tx db1 [:seon.agent/key key] key)))]
        (ctx/build db2 (pdb/eid db2 :seon.agent/key key) branch)))
    (if (= t (:warm-t st))
      st
      ;; main's search text indexes (seon.doc/text-index), built here once and carried by each commit
      ;; that writes no text (the :tx step), so every agent forked inherits them and its first search
      ;; reads none of them cold (a forked agent's first grep built them: 514 MB, ~1 s)
      (do (doseq [a (distinct (vals doc/text-attrs))] (doc/text-index db a))
      ;; main's schema registry, which each agent forked holds until it writes a schema entry
      (let [k (program/schema-changes db)]
        (assoc st :warm-t t :schema-registry {:key k :registry (program/schema-registry k)}))))))

(defn index-for-agent
  "What a forked agent is handed of st's index: {:db :index}, the db it is of being main's current
  (index-at-fork brought it there), which the agent holds as its :main and :base."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] {:db (:db st) :index (get-in st [:index :index])})

(defn static-due?
  "Whether the fork server freezes its heap into static before this fork: main's program moved
  since the last freeze (its compiled code is what agents must share), or, after a collection of
  everything outside static (what forks replaced is freed there, never frozen), what an agent
  would inherit outside the static generation has passed :seon.config.agent/inherited-bytes. A
  freeze keeps whatever is live then for the server's life, so it is done only when due. See
  docs/machine.md#what-a-process-owns-in-memory."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [st]
  (boolean (or (:static-due st) (> (lib/non-static-live!) (cfg/value :seon.config.agent/inherited-bytes)))))

(defn- step-failed
  "The event for a step that threw on message m: the error, naming the op and the message's agent or
  generation, so root answers the asker it belongs to (seon.fork-server/fork-server-proc)."
  {:malli/schema [:=> [:cat :map :seon.host/throwable] :map]}
  [m ^Throwable e]
  {:ev :step-failed :op (:op m) :agent (:agent m) :gen (:gen m)
   :error (seon.contract/error :seon.fork-server/step-failed #'step-failed
                               (str "the fork server's step threw on " (:op m) ": " (step/error-msg e))
                               {:op (:op m)})})

(defn- serve
  "The fork server's answer to one command m (server-step's :cmd): [st' outputs]. open is a volatile
  the fork steps hold their request's descriptor in until fork! has closed it."
  {:malli/schema [:=> [:cat :map :map :seon.host/volatile] [:tuple :map [:maybe :map]]]}
  [st m open]
   (case (:op m)
     :exit (let [[st evs] (reaped st (:pid m))] [st {:ev evs}])
     :tx (if (<= (:t m) (:max-tx (:db st)))
           [st nil] ; already in the db this server was forked with
           (let [t0 (profile/now-ns)
                 ;; main at t is the commit the writer made, read from the store (lazy over its
                 ;; persistent indexes), never its datoms replayed here: replaying a 42,942-datom
                 ;; import took 3.5 s, and the first fork waited behind it
                 db' (d/commit-as-db (:store st) (:commit m))
                 ;; the search's text indexes held for main carried to it when the commit wrote no text: only
                 ;; from its parent (a commit between them, unseen here, could have written one; the next read
                 ;; then checks the texts' fingerprint)
                 _ (when (contains? (set (get-in db' [:meta :datahike/parents])) (d/commit-id (:db st)))
                     (doc/carry-index! (:db st) db' (into #{} (map #(nth % 2 nil)) (:tx-data m))))
                 st (cond-> (assoc st :db db' :evals-max (evals-max-after (:evals-max st) (:tx-data m)))
                      ;; a commit not the one the writer said: main is read again, its eval count included, so
                      ;; no agent forked after counts from a number a commit it never saw raised
                      (not= (:max-tx db') (:t m)) (-> (update :mismatch inc) (assoc :evals-max (repl/evals-max db')))
                      ;; the program moved: the reach graph is brought to it at the next fork
                      (program/moves-program? (:tx-data m)) (assoc :graph-moved true :static-due true))
                 ;; main's program changed: load it here, so agents forked from now on inherit it compiled; while
                 ;; the tests' load is still due it loads them all at the first fork, as main has them then
                 errs (seq (program/load-changes! db' (:tx-data m) {:tests? (not (:tests-due st))}))]
             [st {:ev (cond-> [] errs (conj {:ev :load-errors :t (:t m) :errors (vec errs)})
                        true (conj {:ev :applied :t (:max-tx db') :writer-t (:t m) :apply-ms (profile/ms-since t0)
                                    :lag-ms (/ (- (profile/now-ns) (:ns m)) 1e6) :mismatch (:mismatch st)}))}]))
     :fork (let [fd (sock/take-fd! (:conn st)) _ (vreset! open fd) ; the request's socket, closed if this step throws before fork!
                 t0 (profile/now-ns)
                 ;; main's tests, loaded once, before the first fork, so every agent and test child forked from here
                 ;; inherits them compiled, as read-only pages (docs/source.md#a-process-holds-the-tests-only-when-it-runs-them);
                 ;; their errors go to root as a tx's load errors do
                 load-errs (when (:tests-due st) (seq (:errors (program/load-all! (:db st)))))
                 st (dissoc st :tests-due)
                 st (graph-at-fork st)
                 tg (profile/now-ns)
                 st (-> st index-at-fork (warm-start m))
                 ;; the heap made static when it is due (static-due?), so the agent's collections never
                 ;; trace (and un-share) much of what it inherits (design agent-loop section 2: 163 MB
                 ;; of this heap sat in generations 3-4 at fork; static, an agent's work left 106 MB
                 ;; where it left 333). Not every fork: what a fork replaced (main's db, the graph, the
                 ;; index) stays in static for this server's life (docs/machine.md#what-a-process-owns-in-memory)
                 t1 (profile/now-ns)
                 due? (static-due? st)
                 _ (when due? (lib/static!))
                 st (dissoc st :static-due)
                 static-ms (profile/ms-since t1)
                 ;; the spawn's phases here, on the one clock (seon.agent/spawn-phases)
                 marks [[:fork-server-in t0 nil] [:reach-graph tg nil] [:index t1 nil] [:static (profile/now-ns) nil]]]
             (fork-carrying! st fd open #(repl/run! % (assoc m :graph (:graph st) :index (index-for-agent st) :evals-max (:evals-max st) :schema-registry (:schema-registry st) :marks marks) (:db st) (:ns m))
                             (fn [n] [st {:ev (cond-> [{:ev :fork-failed :agent (:agent m) :active-threads n}]
                                            load-errs (conj {:ev :load-errors :t (:max-tx (:db st)) :errors (vec load-errs)}))}])
                             (fn [pid] (let [[st evs] (adopt st pid)]
                                         [st {:ev (cond-> (into [{:ev :forked :agent (:agent m) :slot (:slot m) :pid pid
                                                                  :fork-ms (- (profile/ms-since t0) static-ms) :static-ms static-ms
                                                                  :t (:max-tx (:db st))}]
                                                                evs)
                                                    load-errs (conj {:ev :load-errors :t (:max-tx (:db st)) :errors (vec load-errs)}))}]))))
     :stats [st {:ev [{:ev :stats :t (:max-tx (:db st)) :datoms (count (d/datoms (:db st) :eavt))
                       :mismatch (:mismatch st) :mem-mb (lib/r2 (lib/real-mb)) :os-threads (lib/nthreads)
                       :active-threads (lib/active-threads)}]}]
     :static (let [t0 (profile/now-ns)] (lib/static!) [st {:ev [{:ev :static :ms (profile/ms-since t0)}]}])
     ;; a standby (session F2): a fork of this server, idle on its own named pipes until root
     ;; promotes it, so that no fork after boot ever comes from root (multithreaded).
     ;; The previous standby is killed: one standby at a time.
     ;; its graph and index brought to :db first, so a standby never holds one a commit has passed
     :standby (let [fd (sock/take-fd! (:conn st)) _ (vreset! open fd)
                    st (-> st graph-at-fork index-at-fork)]
               (fork-carrying! st fd open #(main (:db st) (:cfg st) % false (select-keys st [:graph :index :evals-max :tests-due]))
                              (fn [n] [st {:ev [{:ev :standby-failed :gen (:gen m) :active-threads n}]}])
                              (fn [pid] (when-let [old (:standby-pid st)] (lib/kill! old 9))
                                (let [[st evs] (adopt (assoc st :standby-pid pid) pid)]
                                  [st {:ev (into [{:ev :standby :gen (:gen m) :pid pid :t (:max-tx (:db st))}] evs)}]))))
     [st nil]))

(defn server-step
  "The fork server's step-fn. In :cmd ({:op :tx|:fork|:stats|:static|:exit}: :exit is a child
  that exited, by its pid); out :ev (events for root). The fork is this process's own
  effect: the child never returns from the step (it becomes the agent)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:cmd "main's commits and fork requests, FIFO; a child's :exit"} :outs {:ev "events for root"}
       :workload :io
       :ping-map-fn (fn [st] {:t (:max-tx (:db st)) :mismatch (:mismatch st)})})
  ;; :graph, main's reach graph (seon.program/reach-graph), brought to :db at a fork or a standby after a program commit (graph-at-fork), handed
  ;; to every agent forked (seon.agent/base-graph); a standby inherits it. :index, main's reconciler index and the commit it is of,
  ;; observed forward the same way (index-at-fork) and handed to every agent forked (seon.agent/reconcile-index starts from it)
  ;; the reach graph is built here when the server starts (a standby inherits its server's), so no
  ;; spawn waits for it (the first fork built it: 0.8-3.8 s measured)
  ([{:keys [db cfg store conn graph index evals-max exits tests-due]}]
   {:db db :cfg cfg :store store :mismatch 0 :conn conn :exits exits :status-buf (ffi/alloc 4) :children #{} :tests-due (boolean tests-due)
    ;; the first fork freezes the heap: nothing is static yet (seon.fork-server/static-due?)
    :static-due true
    :graph (or graph (program/reach-indexed (program/reach-graph db (fx/declared-refs (cfg/value :seon.config.root/system-path)))
                                            (second (agent/test-inputs db))))
    ;; listed here when the server boots; a standby inherits its server's
    :index (or index {:commit (d/commit-id db) :index (r/index db)})
    ;; main's highest eval number, read once here and kept from each commit (evals-max-after)
    :evals-max (or evals-max (repl/evals-max db))})
  ;; stop (root closed its end, or died): every child (agents, the standby) killed and reaped
  ;; before this process exits, so root's waitpid of this server is the end of every one of
  ;; them. An agent that had closed its socket but not yet exited was handed to launchd when
  ;; root SIGKILLed the server first, and outlived stop! (bin/check lifecycle's :left,
  ;; docs/processes.md#a-stop-reaps-every-child)
  ([st tr]
   (when (= tr ::flow/stop)
     (doseq [pid (:children st)] (lib/kill! pid 9) (lib/c-waitpid pid (:status-buf st) 0)))
   st)
  ([st _ m]
   ;; a step that throws changes nothing (st as it was) and answers what it took with the error: the asker's
   ;; reply or the event root records, never a server that goes on silent or one that dies for a request
   ;; (docs/processes.md#a-step-that-throws)
   (let [open (volatile! nil)]
     (try (serve st m open)
          (catch Throwable e
            (some-> @open lib/c-close)
            [st {:ev [(step-failed m e)]}])))))

(defn- dev-classpath!
  "The source root's deps.edn roots added to the loader (jolt.deps/add-deps, the twin of
  clojure.repl.deps/add-libs), so every agent forked from here resolves a library's files as a
  REPL started in that project does: io/resource, clojure.repl/source, requiring an uncompiled
  namespace. A built binary carries no dependency roots of its own (Jolt #1232). Its
  :local/root paths are made absolute (add-deps resolves them against the cwd, and an agent
  works elsewhere). Once, before the first fork."
  {:malli/schema [:=> [:cat] :any]}
  []
  (when-let [root (get cfg/config :seon.config.source/root)]
    (jolt.deps/add-deps (update (edn/read-string (slurp (str root "/deps.edn"))) :deps update-vals
                                #(cond-> % (:local/root %) (update :local/root (partial str root "/")))))))

(defn main
  "The fork server's loop, on its socket to root. The boot server makes a process group
  that every agent, test child and clone inherits; a standby (boot? false) stays in it.
  EOF on the socket is root's death: the server kills the group, itself included. Never
  returns."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.db/cfg :seon.lib/fd :boolean] :any] [:=> [:cat :seon.host/db :seon.db/cfg :seon.lib/fd :boolean [:maybe :map]] :any]]}
  ([db0 cfg fd boot?] (main db0 cfg fd boot? nil))
  ([db0 cfg fd boot? {:keys [graph index evals-max tests-due]}]
  ;; every agent forked from here works in the scratch dir, with no repository above it: an
  ;; agent changes code through its REPL and git finds nothing (DECISIONS, Agents work at
  ;; the REPL through tool calls: no git, no source-file editing)
  ;; the boot server watches root, its parent, and leads the group of every agent, clone, test child and
  ;; standby, which watch root too (fork! arms them): root's death ends the group even where no Scheme
  ;; runs to read the EOF (seon.lib/end-with!)
  (when boot? (lib/c-setpgid 0 0) (lib/end-with! (lib/c-getppid) true) (dev-classpath!)
    (let [dir (get cfg/config :seon.config.agent/cwd)] (.mkdirs (java.io.File. dir)) (lib/c-chdir dir)))
  ;; it reads main's commits from the store itself, opened as an agent opens it
  ;; (seon.db/after-fork!): the parent's handles are not used across fork
  (pdb/after-fork! cfg)
  (let [conn (sock/conn fd)
        store (d/connect (assoc cfg :branch (pdb/main-branch cfg)))
        ;; db0 at its commit, read through this process's own store; the boot server reads all
        ;; of it once, so agents forked from here inherit it decoded (root does not hold it:
        ;; docs/machine.md#what-a-process-owns-in-memory)
        db0 (d/commit-as-db store (d/commit-id db0))
        _ (when boot? (pdb/preload! db0))
        ;; one wait on its two dependencies, root's socket and a child's exit (no timeout): alts! over
        ;; the exits channel and the socket's readiness, which stays pending until the socket is read
        exits (a/chan 64)
        ready (volatile! nil)
        recv! (fn [] (loop []
                       (let [m (sock/next-frame! conn)]
                         (if (= :none m)
                           (let [c (or @ready (vreset! ready (poller/ready-chan fd :read)))
                                 [v port] (a/alts!! [exits c])]
                             (if (= port c)
                               (do (vreset! ready nil) (recur))
                               [:cmd {:op :exit :pid v}]))
                           (case m :eof nil [:cmd m])))))]
    ;; root evaluates no test namespace (program/load-all! {:tests? false}); the boot server does, once, at
    ;; its first fork (:tests-due), so a root that forks no agent never loads them, and its socket is read
    ;; from boot: a stop that came while 2,020 forms were evaluated one by one waited 12-21 s for them
    (step/run-loop #'server-step {:db db0 :cfg cfg :store store :conn conn :exits exits :graph graph :index index :evals-max evals-max :tests-due (or boot? tests-due)}
                   recv! (fn [_ m] (sock/send! fd m)))
    (lib/kill! 0 9)
    (lib/c-exit 0))))

;; ---- root's side: the :fork-server proc (proto/flow f2-final proto.control) ----------

(defn- start-event-reader!
  "The fork server's socket, read by one thread (a blocking read) into the :fork-server
  proc's :ev port. EOF is {:ev :eof :pid}. Ends at EOF."
  {:malli/schema [:=> [:cat :seon.host/chan :seon.lib/fd :seon.lib/pid] :seon.host/thread]}
  [ev-ch fd pid]
  (doto (Thread.
          (fn []
            (let [c (sock/conn fd)]
              (loop []
                (let [m (sock/recv! c)]
                  (if (= m :eof)
                    (a/>!! ev-ch {:ev :eof :pid pid})
                    (do (a/>!! ev-ch m) (recur)))))))
          "seon-fs-events")
    (.setDaemon true) (.start)))

(defn fork-server
  "Fork the boot fork server holding db, on the store cfg names (lib/fork!: boot has one
  active thread; another is a defect, thrown). In the child it never returns; here answers
  {:pid :fd}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.db/cfg] :seon.fork-server/forked]}
  [db cfg]
  (let [[r s] (sock/socketpair)]
    (lib/fork! {:fd s :child #(do (sock/close! r) (main db cfg s true))
                :refused #(throw (ex-info "the fork server cannot fork: root boots with more than one thread"
                                          {:seon.error/type ::not-single-threaded :active-threads %}))
                :parent (fn [pid] {:pid pid :fd r})})))

(defn- request-standby
  "Ask the current server to fork a standby; its socket to root goes with the request
  (SCM_RIGHTS), root keeps the other end."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (if (or (:stopping st) (:standby-pending st) (nil? (:pid st)))
    st
    (let [gen (inc (:gen st 0))
          [r s] (sock/socketpair)]
      (sock/send! (:fd st) {:op :standby :gen gen} s)
      ;; root keeps s until the server answers (a descriptor only in flight may be lost)
      (assoc st :gen gen :standby-pending {:gen gen :fd r :sent s}))))

(defn- drop-standby {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [sb]
  (when sb
    (some-> (:pid sb) (lib/kill! 9))
    (some-> (:sent sb) sock/close!)
    (sock/close! (:fd sb))))

(defn- release!
  "Release what init opened: its standbys killed; the server's socket closed for writing,
  so the server reads EOF, kills and reaps every child (server-step's stop) and exits; its
  exit awaited when it is root's child (the boot server; a promoted one is not); then the
  event reader joined, whose EOF follows. nil."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [pid fd reader] :as st}]
  (drop-standby (:standby st)) (drop-standby (:standby-pending st))
  (some-> fd (lib/c-shutdown 1)) ; SHUT_WR; a lost server's socket is closed already
  (some-> pid (lib/c-waitpid (ffi/alloc 4) 0))
  (some-> ^Thread reader (.join 5000))
  nil)

(defn lost
  "The answer to every request a lost fork server is asked (one that died with no standby
  to take its place): the request refused, naming why. Root's api refuses a spawn with it
  once it has heard of the loss, before any agent is made."
  {:malli/schema [:=> [:cat] :seon.root/refused]}
  [] (seon.contract/error :seon.root/refused #'seon.fork-server/lost
                          "the fork server is lost: it died with no standby to take its place, so root forks no agent and answers no fork server request"))

(defn- promote
  "The current server died: its standby becomes the server. It is sent main's commits
  since its fork and the requests the dead server never answered (an agent socket the
  dead server held reads EOF at root: that spawn fails by itself). Then it forks the next
  standby. With no standby the server is lost: each request it never answered is answered
  with that error. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map :seon.step/outputs]]}
  [st]
  (let [sb (:standby st)
        alive? (and sb (lib/kill! (:pid sb) 0))
        st (do (run! sock/close! (vals (:in-flight st))) (dissoc st :in-flight))]
    (sock/close! (:fd st))
    (some-> (:standby-pending st) drop-standby)
    (if-not alive?
      [(-> st (dissoc :fd) (assoc :pid nil :lost true :standby-pending nil :waiters clojure.lang.PersistentQueue/EMPTY))
       {:to-api (into [{:ev :fork-server-down :old (:pid st)} {:ev :fork-server-lost}]
                      (map (fn [{:keys [rid]}] {:dest {:rid rid} :r (lost)})) (:waiters st))}]
      (let [_ (doseq [c (:log st) :when (> (:t c) (:t sb))] (sock/send! (:fd sb) c))
            _ (doseq [{:keys [req]} (:waiters st)] (sock/send! (:fd sb) req))
            st (assoc st :standby-pending nil :pid (:pid sb) :fd (:fd sb) :standby nil :restarts (inc (:restarts st 0))
                      :reader (start-event-reader! (:fork-server-ev st) (:fd sb) (:pid sb)))]
        [(request-standby st)
         {:to-api [{:ev :fork-server-down :old (:pid st)} {:ev :fork-server-up :pid (:pid sb)}]}]))))

(defn fork-server-proc
  "The fork server's side in root, and the one writer of its command pipe.
  In :cmd (the writer's commits and forks), :ext (the api's {:op :stats|:static :rid},
  {:op :shutdown}), :ev (its events, via the reader thread). Out :to-api.
  Root never forks after boot: the server keeps a standby (a fork of itself, with its own
  socket to root); when the server dies the standby is promoted (lesson 1: every fork comes from a
  single-threaded process)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:pid "the server" :fd "its socket" :fork-server-ev "its events, read by the reader thread"}
       :ins {:cmd "commits and forks, FIFO" :ext "the api's requests"} :outs {:to-api "events and answers"}
       ;; pipe writes: short, blocking only on the pipe
       :workload :io
       :ping-map-fn (fn [st] {:pid (:pid st) :standby (:pid (:standby st)) :restarts (:restarts st 0)
                              :waiting (count (:waiters st)) :log (count (:log st)) :stopping (boolean (:stopping st))})})
  ([{:keys [fork-server-ev fd pid] :as args}]
   (request-standby
     (assoc args :waiters clojure.lang.PersistentQueue/EMPTY :log [] :reader (start-event-reader! fork-server-ev fd pid)
            ::flow/in-ports {:ev fork-server-ev})))
  ([st tr]
   ;; the stop transition releases what init opened: the server, its standby, the reader
   (when (and (= tr ::flow/stop) (not (:stopping st)))
     (release! st))
   st)
  ([st in m]
   (case in
     :cmd (if (:lost st)
            ;; no server to send it to: a fork fails now, its socket end closed
            [st (when (:fd m)
                  (sock/close! (:fd m))
                  {:to-api [{:ev :fork-failed :agent (:agent m) :reason (:seon.error/message (lost))}]})]
            (do (if (:fd m) (sock/send! (:fd st) (dissoc m :fd) (:fd m)) (sock/send! (:fd st) m))
              (let [st (cond-> st
                         ;; root keeps its copy of the agent's socket end until the server
                         ;; says it forked (a descriptor only in flight may be lost)
                         (:fd m) (assoc-in [:in-flight (:agent m)] (:fd m))
                         (= :tx (:op m)) (update :log conj m))]
                [(if (>= (count (:log st)) (cfg/value :seon.config.root/standby-every)) (request-standby st) st) nil])))
     :ext (if (= :shutdown (:op m))
            (do (release! st)
                (some-> (:fd st) sock/close!)
                [(assoc st :stopping true :standby nil :standby-pending nil) {:to-api [{:op :fs-down :pid (:pid st)}]}])
            (if (:lost st)
              [st {:to-api [{:dest {:rid (:rid m)} :r (lost)}]}]
              (let [req (dissoc m :rid)]
                (sock/send! (:fd st) req)
                [(update st :waiters conj {:rid (:rid m) :req req}) nil])))
     :ev (case (:ev m)
           (:stats :static) (if-let [{:keys [rid]} (peek (:waiters st))]
                              [(update st :waiters pop) {:to-api [{:dest {:rid rid} :r m}]}]
                              [st nil])
           :standby (let [pend (:standby-pending st)]
                      (if (= (:gen m) (:gen pend))
                        (do (some-> (:standby st) (assoc :pid nil) drop-standby) ; the server killed the old one
                            (some-> (:sent pend) sock/close!)
                            [(assoc st :standby (-> pend (dissoc :sent) (assoc :pid (:pid m) :t (:t m))) :standby-pending nil
                                    :log (filterv #(> (:t %) (:t m)) (:log st)))
                             nil])
                        [st nil]))
           :standby-failed (do (drop-standby (:standby-pending st)) [(assoc st :standby-pending nil) {:to-api [m]}])
           ;; EOF of the current server's events: promote its standby, unless root is stopping
           :eof (if (or (:stopping st) (not= (:pid m) (:pid st)))
                  [st nil]
                  (promote st))
           ;; the server's step threw on a request (seon.fork-server/server-step): its state is as it was, and the
           ;; request's asker is answered the error: a stats or static waiter by its rid, a fork or a standby as it
           ;; fails; the error goes on to root's problems
           :step-failed (case (:op m)
                          (:stats :static) (if-let [{:keys [rid]} (peek (:waiters st))]
                                             [(update st :waiters pop) {:to-api [{:dest {:rid rid} :r (:error m)} m]}]
                                             [st {:to-api [m]}])
                          :fork (do (some-> (get-in st [:in-flight (:agent m)]) sock/close!)
                                    [(update st :in-flight dissoc (:agent m))
                                     {:to-api [{:ev :fork-failed :agent (:agent m) :reason (:seon.error/message (:error m))} m]}])
                          :standby (do (drop-standby (:standby-pending st)) [(assoc st :standby-pending nil) {:to-api [m]}])
                          [st {:to-api [m]}])
           (:forked :fork-failed) (do (some-> (get-in st [:in-flight (:agent m)]) sock/close!)
                                      [(update st :in-flight dissoc (:agent m)) {:to-api [m]}])
           [st {:to-api [m]}]))))
