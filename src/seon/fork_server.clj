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
            [jolt.deps] [clojure.edn :as edn] [seon.fx :as fx] [seon.reconcile :as r]))

(defn- reaped
  "Child pid, which exited (kqueue's NOTE_EXIT, seon.lib/kq-wait) or was already gone when it was
  watched: waited for exactly, so the wait is the reap, and its :exit event. Answers [st events]."
  {:malli/schema [:=> [:cat :map :seon.lib/pid] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st pid]
  (when-not (= pid (lib/c-waitpid pid (:status-buf st) 0))
    (throw (ex-info "the fork server cannot wait for a child it watched" {:pid pid})))
  [(update st :children disj pid)
   [{:ev :exit :pid pid :status (lib/decode-status (ffi/read (:status-buf st) :int))}]])

(defn- adopt
  "Child pid, now this server's to reap: watched for its exit, so its :exit is a command when it
  comes. One already gone cannot be watched and is reaped now. Answers [st events]."
  {:malli/schema [:=> [:cat :map :seon.lib/pid] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st pid]
  (let [st (update st :children conj pid) r (lib/kq-watch (:kq st) pid)]
    (case (int r)
      0 [st []]
      -1 (reaped st pid)
      (throw (ex-info "the fork server cannot watch a child" {:pid pid})))))

(declare main)

(defn- fork-carrying!
  "lib/fork! of a request whose socket came with it (SCM_RIGHTS): the child closes this
  server's descriptors and runs (child fd)."
  {:malli/schema [:=> [:cat :map :seon.host/fn :seon.host/fn :seon.host/fn] :any]}
  [st child refused parent]
  (let [{:keys [conn]} st fd (sock/take-fd! conn)]
    (lib/fork! {:fd fd :refused refused :parent parent
                :child #(do (sock/close! (:fd conn)) (sock/close-pending-fds! conn) (child fd))})))

(defn moves-program?
  "Whether a commit's resolved ops change the program (an attribute of seon.program/attr-namespaces):
  what moves the reach graph a fork hands its agent."
  {:malli/schema [:=> [:cat [:sequential :any]] :boolean]}
  [ops] (boolean (some (fn [[_ _ a]] (and (keyword? a) (program/attr-namespaces (namespace a)))) ops)))

(defn graph-at-fork
  "Server state st with main's reach graph (built when the server starts) brought to :db, only
  when a commit since moved the program (:graph-moved, moves-program?): a spawn's own commit (its
  run) moves none, and reach-update reads every live form (15-21 ms a fork, measured)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (cond-> st (:graph-moved st) (-> (update :graph program/reach-update (:db st)) (dissoc :graph-moved))))

(defn index-at-fork
  "Server state st with main's reconciler index (:index {:db :index}, listed when the server starts)
  observed forward to :db (seon.reconcile/index over what differs), when main moved since: a forked
  agent's first turn observes its branch from it, never lists the index itself (a listing of 903
  tasks: 33M ticks, 160 MB, in every agent)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [{from :db idx :index} (:index st)]
    (cond-> st (not (identical? from (:db st))) (assoc :index {:db (:db st) :index (r/index idx from (:db st))}))))

(defn server-step
  "The fork server's step-fn. In :cmd ({:op :tx|:fork|:stats|:static|:exit}: :exit is a child
  that exited, by its pid); out :ev (events for root). The fork is this process's own
  effect: the child never returns from the step (it becomes the agent)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:cmd "main's commits and fork requests, FIFO; a child's :exit"} :outs {:ev "events for root"}
       :workload :io
       :ping-map-fn (fn [st] {:t (:max-tx (:db st)) :mismatch (:mismatch st)})})
  ;; :graph, main's reach graph (seon.program/reach-graph), brought to :db at a fork or a standby after a program commit (graph-at-fork), handed
  ;; to every agent forked (seon.agent/base-graph); a standby inherits it. :index, main's reconciler index and the db it is of,
  ;; observed forward the same way (index-at-fork) and handed to every agent forked (seon.turn's index! starts from it)
  ;; the reach graph is built here when the server starts (a standby inherits its server's), so no
  ;; spawn waits for it (the first fork built it: 0.8-3.8 s measured)
  ([{:keys [db cfg store conn graph index kq]}]
   {:db db :cfg cfg :store store :mismatch 0 :conn conn :kq kq :status-buf (ffi/alloc 4) :children #{}
    :graph (or graph (program/reach-graph db (fx/declared-refs (cfg/value :seon.config.root/system-path))))
    :index (or index {:db db :index (r/index db)})})
  ;; stop (root closed its end, or died): every child (agents, the standby) killed and reaped
  ;; before this process exits, so root's waitpid of this server is the end of every one of
  ;; them. An agent that had closed its socket but not yet exited was handed to launchd when
  ;; root SIGKILLed the server first, and outlived stop! (bin/check lifecycle's :left,
  ;; notes/pure-tests/lifecycle.md)
  ([st tr]
   (when (= tr ::flow/stop)
     (doseq [pid (:children st)] (lib/c-kill pid 9) (lib/c-waitpid pid (:status-buf st) 0)))
   st)
  ([st _ m]
   (case (:op m)
     :exit (let [[st evs] (reaped st (:pid m))] [st {:ev evs}])
     :tx (if (<= (:t m) (:max-tx (:db st)))
           [st nil] ; already in the db this server was forked with
           (let [t0 (profile/now-ns)
                 ;; main at t is the commit the writer made, read from the store (lazy over its
                 ;; persistent indexes), never its datoms replayed here: replaying a 42,942-datom
                 ;; import took 3.5 s, and the first fork waited behind it
                 db' (d/commit-as-db (:store st) (:commit m))
                 st (cond-> (assoc st :db db') (not= (:max-tx db') (:t m)) (update :mismatch inc)
                      ;; the program moved: the reach graph is brought to it at the next fork
                      (moves-program? (:tx-data m)) (assoc :graph-moved true))
                 ;; main's program changed: load it here, so agents forked from now on inherit it compiled
                 errs (seq (program/load-changes! db' (:tx-data m)))]
             [st {:ev (cond-> [] errs (conj {:ev :load-errors :t (:t m) :errors (vec errs)})
                        true (conj {:ev :applied :t (:max-tx db') :writer-t (:t m) :apply-ms (profile/ms-since t0)
                                    :lag-ms (/ (- (profile/now-ns) (:ns m)) 1e6) :mismatch (:mismatch st)}))}]))
     :fork (let [t0 (profile/now-ns)
                 st (-> st graph-at-fork index-at-fork)
                 ;; the heap made static first, so the agent's collections never trace (and un-share)
                 ;; what it inherits (design agent-loop section 2: 163 MB of this heap sat in
                 ;; generations 3-4 at fork; static, an agent's work left 106 MB where it left 333).
                 ;; Every fork: a spawn writes its run on main, so main has moved by the next one
                 ;; anyway, and a static of an unmoved heap costs what is new (6 ms measured)
                 t1 (profile/now-ns)
                 _ (lib/static!)
                 static-ms (profile/ms-since t1)
                 ;; the spawn's phases here, on the one clock (seon.agent/spawn-phases)
                 marks [[:fork-server-in t0 nil] [:reach-graph t1 nil] [:static (profile/now-ns) nil]]]
             (fork-carrying! st #(repl/run! % (assoc m :graph (:graph st) :index (:index st) :marks marks) (:db st) (:ns m))
                             (fn [n] [st {:ev [{:ev :fork-failed :agent (:agent m) :active-threads n}]}])
                             (fn [pid] (let [[st evs] (adopt st pid)]
                                         [st {:ev (into [{:ev :forked :agent (:agent m) :slot (:slot m) :pid pid
                                                          :fork-ms (- (profile/ms-since t0) static-ms) :static-ms static-ms
                                                          :t (:max-tx (:db st))}]
                                                        evs)}]))))
     :stats [st {:ev [{:ev :stats :t (:max-tx (:db st)) :datoms (count (d/datoms (:db st) :eavt))
                       :mismatch (:mismatch st) :mem-mb (lib/r2 (lib/real-mb)) :os-threads (lib/nthreads)
                       :active-threads (lib/active-threads)}]}]
     :static (let [t0 (profile/now-ns)] (lib/static!) [st {:ev [{:ev :static :ms (profile/ms-since t0)}]}])
     ;; a standby (session F2): a fork of this server, idle on its own named pipes until root
     ;; promotes it, so that no fork after boot ever comes from root (multithreaded).
     ;; The previous standby is killed: one standby at a time.
     ;; its graph and index brought to :db first, so a standby never holds one a commit has passed
     :standby (let [st (-> st graph-at-fork index-at-fork)]
               (fork-carrying! st #(main (:db st) (:cfg st) % false (select-keys st [:graph :index]))
                              (fn [n] [st {:ev [{:ev :standby-failed :gen (:gen m) :active-threads n}]}])
                              (fn [pid] (when-let [old (:standby-pid st)] (lib/c-kill old 9))
                                (let [[st evs] (adopt (assoc st :standby-pid pid) pid)]
                                  [st {:ev (into [{:ev :standby :gen (:gen m) :pid pid :t (:max-tx (:db st))}] evs)}]))))
     [st nil])))

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
  ([db0 cfg fd boot? {:keys [graph index]}]
  ;; every agent forked from here works in the scratch dir, with no repository above it: an
  ;; agent changes code through its REPL and git finds nothing (DECISIONS, Agents work at
  ;; the REPL through tool calls: no git, no source-file editing)
  (when boot? (lib/c-setpgid 0 0) (dev-classpath!)
    (let [dir (get cfg/config :seon.config.agent/cwd)] (.mkdirs (java.io.File. dir)) (lib/c-chdir dir)))
  ;; it reads main's commits from the store itself, opened as an agent opens it
  ;; (seon.db/after-fork!): the parent's handles are not used across fork
  (pdb/after-fork! cfg)
  (let [conn (sock/conn fd)
        store (d/connect (assoc cfg :branch (pdb/main-branch cfg)))
        ;; one wait on its two dependencies, root's socket and a child's exit (no timeout)
        kq (lib/kq-new)
        recv! (fn [] (loop []
                       (let [m (sock/next-frame! conn)]
                         (if (= :none m)
                           (let [r (lib/kq-wait kq fd)]
                             (cond (neg? r) (throw (ex-info "the fork server's wait failed" {:fd fd}))
                                   (pos? r) [:cmd {:op :exit :pid r}]
                                   :else (recur)))
                           (case m :eof nil [:cmd m])))))]
    (step/run-loop #'server-step {:db db0 :cfg cfg :store store :conn conn :kq kq :graph graph :index index}
                   recv! (fn [_ m] (sock/send! fd m)))
    (lib/c-kill 0 9)
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
    (some-> (:pid sb) (lib/c-kill 9))
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
        alive? (and sb (zero? (lib/c-kill (:pid sb) 0)))
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
           (:forked :fork-failed) (do (some-> (get-in st [:in-flight (:agent m)]) sock/close!)
                                      [(update st :in-flight dissoc (:agent m)) {:to-api [m]}])
           [st {:to-api [m]}]))))
