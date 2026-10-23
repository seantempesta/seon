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
            [jolt.deps] [clojure.edn :as edn]))

(defn- reap
  "Every child that exited, as :exit events (non-blocking waitpid), and st without them in
  its :children. Answers [st events]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :seon.fork-server/event]]]}
  [st]
  (loop [st st evs []]
    (let [pid (lib/c-waitpid-nb -1 (:status-buf st) 1)]
      (if (pos? pid)
        (recur (update st :children disj pid) (conj evs {:ev :exit :pid pid :status (lib/decode-status (ffi/read (:status-buf st) :int))}))
        [st evs]))))

(declare main)

(defn- fork-carrying!
  "lib/fork! of a request whose socket came with it (SCM_RIGHTS): the child closes this
  server's descriptors and runs (child fd)."
  {:malli/schema [:=> [:cat :map :seon.host/fn :seon.host/fn :seon.host/fn] :any]}
  [st child refused parent]
  (let [{:keys [conn]} st fd (sock/take-fd! conn)]
    (lib/fork! {:fd fd :refused refused :parent parent
                :child #(do (sock/close! (:fd conn)) (sock/close-pending-fds! conn) (child fd))})))

(defn server-step
  "The fork server's step-fn. In :cmd ({:op :tx|:fork|:stats|:static}, and :tick when the
  pipe is idle, to reap); out :ev (events for root). The fork is this process's own
  effect: the child never returns from the step (it becomes the agent)."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:cmd "main's commits and fork requests, FIFO; :tick when idle"} :outs {:ev "events for root"}
       :workload :io
       :ping-map-fn (fn [st] {:t (:max-tx (:db st)) :mismatch (:mismatch st)})})
  ([{:keys [db conn]}] {:db db :mismatch 0 :conn conn :status-buf (ffi/alloc 4) :children #{}})
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
     :tick (let [[st evs] (reap st)] [st {:ev evs}])
     :tx (if (<= (:t m) (:max-tx (:db st)))
           [st nil] ; already in the db this server was forked with
           (let [t0 (profile/now-ns)
                 ;; main's commit as main's own transaction t (seon.db/replay)
                 db' (pdb/replay (:db st) (:t m) (:tx-data m) (:tx-meta m {}))
                 st (cond-> (assoc st :db db') (not= (:max-tx db') (:t m)) (update :mismatch inc))
                 ;; main's program changed: load it here, so agents forked from now on inherit it compiled
                 errs (seq (program/load-changes! db' (:tx-data m)))]
             [st {:ev (cond-> [] errs (conj {:ev :load-errors :t (:t m) :errors (vec errs)})
                        true (conj {:ev :applied :t (:max-tx db') :writer-t (:t m) :apply-ms (profile/ms-since t0)
                                    :lag-ms (/ (- (profile/now-ns) (:ns m)) 1e6) :mismatch (:mismatch st)}))}]))
     :fork (let [t0 (profile/now-ns)]
             (fork-carrying! st #(repl/run! % m (:db st) (:ns m))
                             (fn [n] [st {:ev [{:ev :fork-failed :agent (:agent m) :active-threads n}]}])
                             (fn [pid] (let [[st evs] (reap (update st :children conj pid))]
                                         [st {:ev (into [{:ev :forked :agent (:agent m) :slot (:slot m) :pid pid
                                                          :fork-ms (profile/ms-since t0) :t (:max-tx (:db st))}]
                                                        evs)}]))))
     :stats [st {:ev [{:ev :stats :t (:max-tx (:db st)) :datoms (count (d/datoms (:db st) :eavt))
                       :mismatch (:mismatch st) :mem-mb (lib/r2 (lib/real-mb)) :os-threads (lib/nthreads)
                       :active-threads (lib/active-threads)}]}]
     :static (let [t0 (profile/now-ns)] (lib/static!) [st {:ev [{:ev :static :ms (profile/ms-since t0)}]}])
     ;; a standby (session F2): a fork of this server, idle on its own named pipes until root
     ;; promotes it, so that no fork after boot ever comes from root (multithreaded).
     ;; The previous standby is killed: one standby at a time.
     :standby (fork-carrying! st #(main (:db st) % false)
                              (fn [n] [st {:ev [{:ev :standby-failed :gen (:gen m) :active-threads n}]}])
                              (fn [pid] (when-let [old (:standby-pid st)] (lib/c-kill old 9))
                                (let [[st evs] (reap (-> st (assoc :standby-pid pid) (update :children conj pid)))]
                                  [st {:ev (into [{:ev :standby :gen (:gen m) :pid pid :t (:max-tx (:db st))}] evs)}])))
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
  {:malli/schema [:=> [:cat :seon.host/db :seon.lib/fd :boolean] :any]}
  [db0 fd boot?]
  ;; every agent forked from here works in the scratch dir, with no repository above it: an
  ;; agent changes code through its REPL and git finds nothing (owner, 2026-10-22)
  (when boot? (lib/c-setpgid 0 0) (dev-classpath!)
    (let [dir (get cfg/config :seon.config.agent/cwd)] (.mkdirs (java.io.File. dir)) (lib/c-chdir dir)))
  (let [conn (sock/conn fd)
        recv! (fn [] (let [m (sock/recv-within! conn 100)]
                       (case m :eof nil :timeout [:cmd {:op :tick}] [:cmd m])))]
    (step/run-loop #'server-step {:db db0 :conn conn}
                   recv! (fn [_ m] (sock/send! fd m)))
    (lib/c-kill 0 9)
    (lib/c-exit 0)))

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
  "Fork the boot fork server holding db (lib/fork!: boot has one active thread; another is
  a defect, thrown). In the child it never returns; here answers {:pid :fd}."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.fork-server/forked]}
  [db]
  (let [[r s] (sock/socketpair)]
    (lib/fork! {:fd s :child #(do (sock/close! r) (main db s true))
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
  (lib/c-shutdown fd 1) ; SHUT_WR
  (some-> pid (lib/c-waitpid (ffi/alloc 4) 0))
  (some-> ^Thread reader (.join 5000))
  nil)

(defn- promote
  "The current server died: its standby becomes the server. It is sent main's commits
  since its fork and the requests the dead server never answered (an agent socket the
  dead server held reads EOF at root: that spawn fails by itself). Then it forks the next
  standby. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map :seon.step/outputs]]}
  [st]
  (let [sb (:standby st)
        alive? (and sb (zero? (lib/c-kill (:pid sb) 0)))
        st (do (run! sock/close! (vals (:in-flight st))) (dissoc st :in-flight))]
    (sock/close! (:fd st))
    (some-> (:standby-pending st) drop-standby)
    (if-not alive?
      [(assoc st :pid nil :lost true :standby-pending nil) {:to-api [{:ev :fork-server-down :old (:pid st)} {:ev :fork-server-lost}]}]
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
     :cmd (do (when (:pid st) (if (:fd m) (sock/send! (:fd st) (dissoc m :fd) (:fd m)) (sock/send! (:fd st) m)))
              (let [st (cond-> st
                         ;; root keeps its copy of the agent's socket end until the server
                         ;; says it forked (a descriptor only in flight may be lost)
                         (:fd m) (assoc-in [:in-flight (:agent m)] (:fd m))
                         (= :tx (:op m)) (update :log conj m))]
                [(if (>= (count (:log st)) (cfg/value :seon.config.root/standby-every)) (request-standby st) st) nil]))
     :ext (if (= :shutdown (:op m))
            (do (release! st)
                (sock/close! (:fd st))
                [(assoc st :stopping true :standby nil :standby-pending nil) {:to-api [{:op :fs-down :pid (:pid st)}]}])
            (let [req (dissoc m :rid)]
              (when (:pid st) (sock/send! (:fd st) req))
              [(update st :waiters conj {:rid (:rid m) :req req}) nil]))
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
