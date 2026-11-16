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
            [jolt.deps] [clojure.edn :as edn] [seon.fx :as fx] [seon.graph :as graph] [seon.reconcile :as r] [seon.agent :as agent] [seon.proofs :as proofs] [jolt.io-poller :as poller] [seon.task :as task] [seon.ctx :as ctx] [seon.doc :as doc] [datahike.db.interface :as dbi]))

(defn- reaped
  "Child pid, whose exit arrived (jolt.io-poller/exit-chan): waited for exactly, so the wait is the
  reap, and its :exit event. Answers [st events]."
  {:malli/schema [:=> [:cat :map :seon.lib/pid] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st pid]
  (when-not (= pid (lib/waitpid! pid (:status-buf st)))
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
  so a step that throws before it forks can close it: server-step): the child holds fd alone of this server's descriptors
  (lib/fork!) and runs (child fd). open, a volatile holding fd, is cleared once fork! has
  closed it here (before refused or parent runs)."
  {:malli/schema [:=> [:cat :map :seon.lib/fd :seon.host/volatile :seon.host/fn :seon.host/fn :seon.host/fn] :any]}
  [st fd open child refused parent]
  (let [{:keys [conn]} st done (fn [f] (fn [x] (vreset! open nil) (f x)))]
    (lib/fork! {:fd fd :refused (done refused) :parent (done parent)
                :child #(child fd)})))

(defn graph-at-fork
  "Server state st with main's reach graph (built when the server starts) brought to :db, only
  when a commit since moved the program (:graph-moved, the writer's :program-t, seon.writer/program-at): a spawn's own commit (its
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

(defn warm-start
  "Server state st with, once per move of main (:warm-t), main's search text index (seon.doc/text-index) and main's
  schema registry (seon.program/schema-changes), which each agent forked holds as its branch's until it writes a schema
  entry (:schema-registry): built once here, inherited by every agent. Nothing per fork: a per-fork build of the agent's
  record, made only to warm this process's node cache and dropped, is gone (the owner: no baggage). Nothing is
  written. See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st _]
  (let [db (:db st) t (:max-tx db)]
    (if (= t (:warm-t st))
      st
      ;; main's search text index (seon.doc/text-index), built here once and carried by each commit
      ;; (the :tx step), so every agent forked inherits it and its first search builds nothing (an in-memory FTS5 table survives fork() as plain memory)
      (do (doc/text-index db)
      ;; main's schema registry, which each agent forked holds until it writes a schema entry
      (let [k (program/schema-changes db)]
        (let [reg (program/schema-registry k)]
          ;; main's registry is this process's (it holds main), so every agent forked from it starts holding main's
          (program/hold-registry! reg)
          (assoc st :warm-t t :schema-registry {:key k :registry reg :index (pdb/index-of {:forms k})})))))))

(defn index-for-agent
  "What a forked agent is handed of st's index: {:db :index}, the db it is of being main's current
  (index-at-fork brought it there), which the agent holds as its :main and :base."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] {:db (:db st) :index (get-in st [:index :index])})

(defn- stood
  "Server state st with its program's standing load errors (:standing, by namespace) brought to a load: the namespaces it
  loaded again (touched, or every one for a whole load: :all) lose what they held, then errs (seon.program/error maps) are
  held by their :ns, until a later load of that namespace succeeds. Every agent forked from here is given them
  (repl/run!'s :program-errors), so one forked from a program that did not load says so at its start, never silently: they
  were reported once, as an event, and forgotten. See docs/processes.md#an-agent-that-starts-without-its-program."
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :seon.program/error]] [:or [:= :all] [:set :symbol]]] :map]}
  [st errs touched]
  (let [held (if (= :all touched) {} (apply dissoc (:standing st {}) touched))]
    (assoc st :standing (merge-with into held (group-by :ns errs)))))

(defn fork-args
  "What an agent forked from server state st for request m is given (repl/run!): m with the server's reach graph, index and
  schema registry, the spawn's marks, and the standing load errors of the program it is forked with (`stood`), so it starts
  saying so. See docs/processes.md#an-agent-that-starts-without-its-program."
  {:malli/schema [:=> [:cat :map :map [:sequential :any]] :map]}
  [st m marks]
  (assoc m :graph (:graph st) :index (index-for-agent st) :schema-registry (:schema-registry st) :marks marks
           :program-errors (vec (mapcat val (:standing st)))))

(defn- asserted-nss
  "The namespaces of the forms tx-data (resolved ops) asserts on db: what a :tx's load loaded again."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:set :symbol]]}
  [db ops]
  (into #{} (keep (fn [[op _ a v]] (when (and (= op :db/add) (= a :form/ns)) (:ns/name (d/entity db v))))) ops))

(defn- preload
  "Server state st with what a first fork would otherwise wait for done now, while no one waits: the reach graph and index brought to
  :db, main's registry and text index made, the test fixtures made when main's program moved, and the heap made static when due. Main's
  program, tests included, is loaded only by the :tx arm (load-diff!), never here. Answers [st' load errors]. A fork after it costs a fork. See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:maybe [:sequential :any]]]]}
  [st]
  (let [errs (seq (:load-errors st))
        st (-> st (dissoc :preload-t :load-errors) graph-at-fork index-at-fork (warm-start {}))]
    ;; the test fixtures made here once a program, before the heap is made static, so every agent inherits them made
    ;; the fixtures made once a program, then a full collection, which frees what the program move replaced: a forked agent never
    ;; collects what it inherits (Jolt's collection ceiling, jolt.host/a-forked-child-never-collects-what-it-inherited), so it is freed here
    (when (:fixtures-due st) (proofs/warm-fixtures!) (System/gc))
    [(dissoc st :fixtures-due) errs]))

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
     ;; the first notice of a server forked bare (holding no db: nothing root built is inherited): main at that commit, its
     ;; config held and its program loaded (load-all!: the forms the binary lacks), then one collection, so every agent forked
     ;; after shares what was built here once (task seon.step/roots-procs-are-the-first-forks-graph)
     :tx (if (nil? (:db st))
           (let [t0 (profile/now-ns)
                 store (or (:store st) (pdb/connect (assoc (:cfg st) :branch (pdb/main-branch (:cfg st)))))
                 db' (d/commit-as-db store (:commit m))
                 _ (program/hold-config! db')
                 loaded (program/load-all! db')
                 _ (System/gc)
                 st (assoc st :db db' :store store :program-t (:program-t m) :graph-moved true :static-due true :fixtures-due true
                           :load-errors (seq (:errors loaded)) :standing (group-by :ns (:errors loaded)))]
             [st {:ev (cond-> [] (seq (:errors loaded)) (conj {:ev :load-errors :t (:t m) :errors (vec (:errors loaded))})
                        true (conj {:ev :applied :t (:max-tx db') :writer-t (:t m) :apply-ms (profile/ms-since t0)
                                    :lag-ms (/ (- (profile/now-ns) (:ns m)) 1e6) :mismatch 0}))}])
         (if (<= (:t m) (:max-tx (:db st)))
           [st nil] ; already in the db this server was forked with
           (let [t0 (profile/now-ns)
                 ;; main at t is the commit the writer made, read from the store (lazy over its
                 ;; persistent indexes), never its datoms replayed here: replaying a 42,942-datom
                 ;; import took 3.5 s, and the first fork waited behind it
                 db' (d/commit-as-db (:store st) (:commit m))
                 ;; the search's text index held for main carried to it, the texts the commit wrote replaced: only
                 ;; from its parent (a commit between them, unseen here, could have written one; the next read
                 ;; then builds it)
                 _ (when (contains? (set (get-in db' [:meta :datahike/parents])) (d/commit-id (:db st)))
                     (doc/carry-index! (:db st) db' (map (fn [op] [(nth op 1) (nth op 2)]) (:tx-data m))))
                 ;; the program moved: the writer's :program-t changed (seon.writer/program-at, the one computation of it)
                 moved? (not= (:program-t m) (:program-t st))
                 ;; the one load path: this process runs the commit's program where it held its own db's, by the diff of the
                 ;; two (seon.program/load-diff!: what was removed unmapped, what is new loaded with what it reaches, tests
                 ;; included, and the commit's config held), so agents forked from now on inherit it compiled. Never from
                 ;; :tx-data: a notice missed is covered by the next diff, and from an empty db the first is the whole
                 ;; program. A commit that moves no program loads nothing (its diff measured 0 ms, result/c1786805)
                 ;; main's schema held first (registry, Datahike schema, entity index; nothing when no schema entry moved), so every
                 ;; fork inherits it and the contracts the load re-arms compile against it (seon.program/hold-main-schema!)
                 errs (when moved? (seq (concat (program/hold-main-schema! db') (program/load-diff! (:db st) db'))))
                 ;; one collection once the program moved, never before a fork: what the diff replaced is freed here, once a
                 ;; merge, so every agent forked after inherits no garbage (a collection ~50-100 ms; a fork's bound ~5 ms,
                 ;; docs/cost.md#the-test-systems-lower-bound)
                 _ (when moved? (System/gc))
                 st (cond-> (assoc st :db db')
                      ;; a commit not the one the writer said
                      (not= (:max-tx db') (:t m)) (update :mismatch inc)
                      ;; the reach graph is brought to it at the next fork, and the fixtures made again
                      moved? (assoc :graph-moved true :fixtures-due true :program-t (:program-t m)))
                 st (stood st errs (asserted-nss db' (:tx-data m)))
                 ;; the commit a :preload was waiting to see
                 [st load-errs] (if (and (:preload-t st) (>= (:max-tx db') (:preload-t st))) (preload st) [st nil])
                 errs (seq (concat errs load-errs))]
             [st {:ev (cond-> [] errs (conj {:ev :load-errors :t (:t m) :errors (vec errs)})
                        true (conj {:ev :applied :t (:max-tx db') :writer-t (:t m) :apply-ms (profile/ms-since t0)
                                    :lag-ms (/ (- (profile/now-ns) (:ns m)) 1e6) :mismatch (:mismatch st)}))}])))
     ;; draining (server-step's ::flow/drain): no new fork; its socket end is closed and root told, the agent forks at the next root
     :fork (if (:draining st)
             (do (sock/close! (sock/take-fd! (:conn st)))
                 [st {:ev [{:ev :fork-failed :agent (:agent m) :reason "the fork server is draining: the agent forks at the next root"}]}])
           (let [fd (sock/take-fd! (:conn st)) _ (vreset! open fd) ; the request's socket, closed if this step throws before fork!
                 t0 (profile/now-ns)
                 ;; main's program, tests included, is this process's already (the :tx arm's load-diff!), so every agent and test
                 ;; child forked from here inherits it compiled (docs/source.md#the-binary-holds-the-tests-compiled)
                 load-errs (seq (:load-errors st))
                 st (dissoc st :load-errors)
                 st (graph-at-fork st)
                 tg (profile/now-ns)
                 st (-> st index-at-fork (warm-start m))
                 t1 (profile/now-ns)
                 ;; the ^:seon.test/fixture delays (the worlds, the test store) made here once a program, before the freeze, so
                 ;; every agent forked inherits them made, shared: each agent made them at its first prove (195M ticks, 1.1 GB,
                 ;; tests2 result/ba7c27c8). A program commit (:fixtures-due) may redefine one, so they are forced again then, and the
                 ;; heap collected in full, freeing what the move replaced: an agent never collects what it inherits
                 ;; (docs/machine.md#what-a-process-owns-in-memory)
                 _ (when (:fixtures-due st) (proofs/warm-fixtures!) (System/gc))
                 st (dissoc st :fixtures-due)
                 static-ms (profile/ms-since t1)
                 ;; the spawn's phases here, on the one clock (seon.agent/spawn-phases)
                 marks [[:fork-server-in t0 nil] [:reach-graph tg nil] [:index t1 nil] [:static (profile/now-ns) nil]]]
             (fork-carrying! st fd open #(repl/run! % (fork-args st m marks) (:db st) (:ns m))
                             (fn [{n :active-threads hs :holders}] [st {:ev (cond-> [{:ev :fork-failed :agent (:agent m) :active-threads n :holders hs}]
                                            load-errs (conj {:ev :load-errors :t (:max-tx (:db st)) :errors (vec load-errs)}))}])
                             (fn [pid] (let [[st evs] (adopt st pid)]
                                         [st {:ev (cond-> (into [{:ev :forked :agent (:agent m) :slot (:slot m) :pid pid
                                                                  :fork-ms (- (profile/ms-since t0) static-ms) :static-ms static-ms
                                                                  :t (:max-tx (:db st))}]
                                                                evs)
                                                    load-errs (conj {:ev :load-errors :t (:max-tx (:db st)) :errors (vec load-errs)}))}])))))
     ;; root's boot asks once its import is written (:t): if that commit has not reached here, it waits for the :tx that brings it, so an imported program
     ;; loads whole, never form by form as changes
     :preload (if (< (:max-tx (:db st)) (:t m))
                [(assoc st :preload-t (:t m)) {:ev [{:ev :preload}]}]
                (let [[st errs] (preload st)]
                  [st {:ev (cond-> [{:ev :preload}] errs (conj {:ev :load-errors :t (:max-tx (:db st)) :errors (vec errs)}))}]))
     ;; no count of main's datoms: a scan of the whole store inside the process a reading measures, read by no one
     :stats [st {:ev [(cond-> {:ev :stats :t (:max-tx (:db st))
                       :mismatch (:mismatch st) :mem-mb (lib/r2 (lib/real-mb)) :os-threads (lib/nthreads)
                       :active-threads (lib/active-threads)}
                      ;; asked with :heap: what this process holds, for a trace of its contents against its row
                      ;; (docs/cost.md#the-test-systems-lower-bound): Chez's live and held bytes and the heap's types by generation
                      (:heap m) (assoc :heap (assoc (jolt.host/heap-histogram 20)
                                                    :bytes-allocated (jolt.scheme/eval-string "(bytes-allocated)")
                                                    :current-memory-bytes (jolt.scheme/eval-string "(current-memory-bytes)"))))]}]
     :static (let [t0 (profile/now-ns)] (System/gc) [st {:ev [{:ev :static :ms (profile/ms-since t0)}]}])
     [st nil]))

(defn server-step
  "The fork server's step-fn. In :cmd ({:op :tx|:fork|:preload|:stats|:static|:exit}: :exit is a child
  that exited, by its pid); out :ev (events for root). The fork is this process's own
  effect: the child never returns from the step (it becomes the agent)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:cmd "main's commits and fork requests, FIFO; a child's :exit"} :outs {:ev "events for root"}
       :workload :io
       :ping-map-fn (fn [st] {:t (:max-tx (:db st)) :mismatch (:mismatch st)})})
  ;; :graph, main's reach graph (seon.program/reach-graph), brought to :db at a fork after a program commit (graph-at-fork), handed
  ;; to every agent forked (seon.agent/base-graph). :index, main's reconciler index and the commit it is of,
  ;; observed forward the same way (index-at-fork) and handed to every agent forked (seon.agent/reconcile-index starts from it)
  ;; the reach graph is built here when the server starts, so no
  ;; spawn waits for it (the first fork built it: 0.8-3.8 s measured)
  ([{:keys [db cfg store conn exits load-errors]}]
   {:db db :cfg cfg :store store :mismatch 0 :conn conn :exits exits :status-buf (ffi/alloc 4) :children #{}
    ;; the errors of the boot server's load of main's tests at its start (main), sent with the first :preload or fork
    :load-errors load-errors
    ;; the first fork forces the fixtures and collects in full, as a program move does
    :fixtures-due true
    :graph (program/reach-indexed (program/reach-graph db (fx/declared-refs graph/built-text)) (second (agent/test-inputs db)))
    ;; listed here when the server boots
    :index {:commit (d/commit-id db) :index (r/index db)}})
  ;; stop (root closed its end, or died): every child (agents and their test children) killed and reaped
  ;; before this process exits, so root's waitpid of this server is the end of every one of
  ;; them. An agent that had closed its socket but not yet exited was handed to launchd when
  ;; root SIGKILLed the server first, and outlived stop! (bin/check lifecycle's :left,
  ;; docs/processes.md#a-stop-reaps-every-child)
  ;; drain: no new fork (serve refuses one, typed); its children go on, each agent draining at its own turn boundary, and the
  ;; stop that follows reaps them (docs/health.md#a-proc-that-does-not-drain)
  ([st tr]
   (when (= tr ::flow/stop)
     (doseq [pid (:children st)] (lib/kill! pid 9) (lib/waitpid! pid (:status-buf st))))
   (cond-> st (= tr ::flow/drain) (assoc :draining true)))
  ([st _ m]
   ;; a step that throws changes nothing (st as it was) and answers what it took with the error: the asker's
   ;; reply or the event root records, never a server that goes on silent or one that dies for a request
   ;; (docs/processes.md#a-step-that-throws)
   (let [open (volatile! nil)]
     (try (serve st m open)
          (catch Throwable e
            (some-> @open lib/c-close)
            [st {:ev [(step-failed m e)]}])))))

(defmacro ^:private baked-deps-text
  "The checkout's deps.edn as this namespace compiles, as seon.config/built-text reads the shipped config. See
  docs/source.md#a-boot-resumes-from-the-db."
  [] (slurp (str (cfg/checkout) "/deps.edn")))

(def ^:private built-deps
  "The deps.edn this binary was built with: the dependency roots its agents resolve a library's files from, whatever
  the file on disk says now. See docs/source.md#a-boot-resumes-from-the-db."
  (baked-deps-text))

(defn- dev-classpath!
  "The binary's deps.edn roots (built-deps) added to the loader (jolt.deps/add-deps, the twin of
  clojure.repl.deps/add-libs), so every agent forked from here resolves a library's files as a
  REPL started in that project does: io/resource, clojure.repl/source, requiring an uncompiled
  namespace. A built binary carries no dependency roots of its own (Jolt #1232). Its
  :local/root paths are made absolute against the checkout (add-deps resolves them against the cwd, and an agent
  works elsewhere). Once, before the first fork; it reads no file."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [root (cfg/checkout)]
    (jolt.deps/add-deps (update (edn/read-string built-deps) :deps update-vals
                                #(cond-> % (:local/root %) (update :local/root (partial str root "/")))))))

(defn main
  "The fork server's loop, on its socket to root. The boot server makes a process group
  that every agent, test child and clone inherits.
  EOF on the socket is root's death: the server kills the group, itself included. Never
  returns."
  {:malli/schema [:=> [:cat :seon.host/db :seon.db/cfg :seon.lib/fd] :any]}
  [db0 cfg fd]
  ;; every agent forked from here works in the scratch dir, with no repository above it: an
  ;; agent changes code through its REPL and git finds nothing (DECISIONS, Agents work at
  ;; the REPL through tool calls: no git, no source-file editing)
  ;; the server watches root, its parent, and leads the group of every agent, clone and test child,
  ;; which watch root too (fork! arms them): root's death ends the group even where no Scheme
  ;; runs to read the EOF (seon.lib/end-with!)
  (lib/c-setpgid 0 0) (lib/end-with! (lib/c-getppid) true) (dev-classpath!)
  (let [dir (get cfg/config :seon.config.agent/cwd)] (.mkdirs (java.io.File. dir)) (lib/c-chdir dir))
  ;; it reads main's commits from the store itself, opened as an agent opens it
  ;; (seon.db/after-fork!): the parent's handles are not used across fork
  (pdb/after-fork! cfg)
  (let [conn (sock/conn fd)
        store (pdb/connect (assoc cfg :branch (pdb/main-branch cfg)))
        ;; db0 at its commit, read through this process's own store; nothing of it is read ahead: an agent reads what it
        ;; needs through the node cache it inherits (docs/machine.md#what-a-process-owns-in-memory)
        db0 (d/commit-as-db store (d/commit-id db0))
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
    ;; it inherits main's program and tests from root: the binary holds test/ compiled, and root's boot load-all! loaded
    ;; whatever main changed since the build, so this process loads nothing at its start
    (step/run-loop #'server-step {:db db0 :cfg cfg :store store :conn conn :exits exits}
                   recv! (fn [_ m] (sock/send! fd m)))
    (lib/kill! 0 9)
    (lib/c-exit 0)))

;; ---- root's side: the :fork-server proc (proto/flow f2-final proto.control) ----------

(defn- start-event-reader!
  "The fork server's socket, read by one thread (a blocking read) into the :fork-server
  proc's :ev port. EOF is {:ev :eof :pid}. Ends at EOF."
  {:seon.flow/edge "an edge reader for a blocking read: the fork server's event pipe, read by one thread that puts each event on its proc's in-port" :malli/schema [:=> [:cat :seon.host/chan :seon.lib/fd :seon.lib/pid] :seon.host/thread]}
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
    (lib/fork! {:fd s :child #(main db cfg s)
                :refused #(throw (ex-info "the fork server cannot fork: root boots with more than one thread"
                                          {:seon.error/type ::not-single-threaded :active-threads (:active-threads %) :holders (:holders %)}))
                :parent (fn [pid] {:pid pid :fd r})})))

(defn- release!
  "Release what init opened: the server's socket closed for writing, and the server's
  process group (it and every child it forked) ended at once, so no stop waits for a server still warming to read its
  EOF; its exit
  awaited when it is root's child; every process it forked and has not reaped killed and its exit awaited (`:forked`, kept from
  its :forked and :exit events); then the event reader joined, whose EOF follows. Answers how the server ended:
  :group-ended (its group, ended here) or :eof (a server already gone, which kill no longer reaches)."
  {:malli/schema [:=> [:cat :map] [:enum :group-ended :eof]]}
  [{:keys [pid fd reader] :as st}]
  (some-> fd (lib/c-shutdown 1)) ; SHUT_WR; a lost server's socket is closed already
  ;; root's boot server leads its own group (main's setpgid): the group ended at once, so a stop never waits for the server
  ;; to read its EOF, which a server still warming (its tests' load: ~35 s) did not do until it was done (every
  ;; stop of a root under ~35 s old took 26-32 s). It writes nothing to the store, and at a stop its agents have ended.
  (let [ended (if (and pid (lib/kill! (- pid) 9)) :group-ended :eof)]
    (some-> pid (lib/waitpid! (ffi/alloc 4)))
    ;; every process it forked that has not been reaped (:forked, emptied by each :exit) is killed and its exit awaited, so a stop
    ;; answers only once every process it started has exited: killed with the group, an agent whose parent died was reaped later by
    ;; launchd, and outlived the stop under load (batch 21's lifecycle step, :left). The wait ends by the kernel: a SIGKILLed
    ;; process exits, and exit-chan answers at once for a pid already gone. Only a pid the kill reached is awaited: one gone, or
    ;; never ours to signal (lib/kill! answers false for ESRCH and EPERM), is not waited on, so no wait can be a hang
    (doseq [p (filterv #(lib/kill! % 9) (:forked st))] (a/<!! (poller/exit-chan p)))
    (some-> ^Thread reader (.join 5000))
    ended))

(defn lost
  "The answer to every request a lost fork server is asked (one that died: root, multithreaded, forks no other): the
  request refused, naming why. Root's api refuses a spawn with it
  once it has heard of the loss, before any agent is made."
  {:malli/schema [:=> [:cat] :seon.system/refused]}
  [] (seon.contract/error :seon.system/refused #'seon.fork-server/lost
                          "the fork server is lost: it died, and root forks no other, so no agent is forked and no fork server request answered until a restart"))

(defn- lose
  "The server died (EOF of its events): it is lost, since root is multithreaded and forks no other server (lesson 1:
  every fork comes from a single-threaded process). Each request it never answered is answered with `lost`, and a fork
  in flight fails (an agent socket the dead server held reads EOF at root). Agents keep running. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map :seon.step/outputs]]}
  [st]
  (run! sock/close! (vals (:in-flight st)))
  (sock/close! (:fd st))
  [(-> st (dissoc :fd :in-flight) (assoc :pid nil :lost true :waiters clojure.lang.PersistentQueue/EMPTY))
   {:to-api (into [{:ev :fork-server-down :old (:pid st)} {:ev :fork-server-lost}]
                  (map (fn [{:keys [rid]}] {:dest {:rid rid} :r (lost)})) (:waiters st))}])

(defn fork-server-proc
  "The fork server's side in root, and the one writer of its command pipe.
  In :cmd (the writer's commits and forks), :ext (the api's {:op :stats|:static :rid},
  {:op :shutdown}), :ev (its events, via the reader thread). Out :to-api.
  Root never forks after boot (lesson 1: every fork comes from a single-threaded process): when the server dies it is
  lost (`lose`)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:pid "the server" :fd "its socket" :fork-server-ev "its events, read by the reader thread"}
       :ins {:cmd "commits and forks, FIFO" :ext "the api's requests"} :outs {:to-api "events and answers"}
       ;; pipe writes: short, blocking only on the pipe
       :workload :io
       :ping-map-fn (fn [st] {:pid (:pid st) :waiting (count (:waiters st)) :stopping (boolean (:stopping st))})})
  ([{:keys [fork-server-ev fd pid] :as args}]
   (assoc args :waiters clojure.lang.PersistentQueue/EMPTY :reader (start-event-reader! fork-server-ev fd pid)
          ::flow/in-ports {:ev fork-server-ev}))
  ([st tr]
   ;; the stop transition releases what init opened: the server and the reader
   (when (and (= tr ::flow/stop) (not (:stopping st)))
     (release! st))
   ;; a drain is passed on to the server, whose run-loop runs its own (server-step: no new fork)
   (when (and (= tr ::flow/drain) (not (:lost st)) (:fd st))
     (sock/send! (:fd st) {:op :drain}))
   st)
  ([st in m]
   (case in
     :cmd (if (:lost st)
            ;; no server to send it to: a fork fails now, its socket end closed
            [st (when (:fd m)
                  (sock/close! (:fd m))
                  {:to-api [{:ev :fork-failed :agent (:agent m) :reason (:seon.error/message (lost))}]})]
            (do (if (:fd m) (sock/send! (:fd st) (dissoc m :fd) (:fd m)) (sock/send! (:fd st) m))
              ;; root keeps its copy of the agent's socket end until the server
              ;; says it forked (a descriptor only in flight may be lost)
              [(cond-> st (:fd m) (assoc-in [:in-flight (:agent m)] (:fd m))) nil]))
     :ext (if (= :shutdown (:op m))
            (let [ended (release! st)]
                (some-> (:fd st) sock/close!)
                [(assoc st :stopping true) {:to-api [{:op :fs-down :pid (:pid st) :ended ended}]}])
            (if (:lost st)
              [st {:to-api [{:dest {:rid (:rid m)} :r (lost)}]}]
              (let [req (dissoc m :rid)]
                (sock/send! (:fd st) req)
                [(update st :waiters conj {:rid (:rid m) :req req}) nil])))
     :ev (case (:ev m)
           (:stats :static :preload) (if-let [{:keys [rid]} (peek (:waiters st))]
                              [(update st :waiters pop) {:to-api [{:dest {:rid rid} :r m}]}]
                              [st nil])
           ;; EOF of the server's events: it is lost, unless root is stopping
           :eof (if (or (:stopping st) (not= (:pid m) (:pid st)))
                  [st nil]
                  (lose st))
           ;; the server's step threw on a request (seon.fork-server/server-step): its state is as it was, and the
           ;; request's asker is answered the error: a stats or static waiter by its rid, a fork as it
           ;; fails; the error goes on to root's problems
           :step-failed (case (:op m)
                          (:stats :static :preload) (if-let [{:keys [rid]} (peek (:waiters st))]
                                             [(update st :waiters pop) {:to-api [{:dest {:rid rid} :r (:error m)} m]}]
                                             [st {:to-api [m]}])
                          :fork (do (some-> (get-in st [:in-flight (:agent m)]) sock/close!)
                                    [(update st :in-flight dissoc (:agent m))
                                     {:to-api [{:ev :fork-failed :agent (:agent m) :reason (:seon.error/message (:error m))} m]}])
                          [st {:to-api [m]}])
           (:forked :fork-failed) (do (some-> (get-in st [:in-flight (:agent m)]) sock/close!)
                                      ;; a forked process is root's to see end at its stop (release!): its pid kept until its :exit
                                      [(cond-> (update st :in-flight dissoc (:agent m))
                                         (and (= :forked (:ev m)) (:pid m)) (update :forked (fnil conj #{}) (:pid m)))
                                       {:to-api [m]}])
           ;; the server reaped a child: no longer one release! awaits
           :exit [(update st :forked (fnil disj #{}) (:pid m)) {:to-api [m]}]
           [st {:to-api [m]}]))))
