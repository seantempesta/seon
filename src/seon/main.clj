(ns seon.main
  "The production entry points of the binary: a root serving its checkout (bin/root's `root`
  mode) and the checkout's ports (`ports`); every other mode loads the check driver (seon.check,
  in test/) from the checkout's source. The binary holds what this namespace requires, so it
  requires every namespace system.edn names a proc in (seon.system resolves them by name at boot)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as c] [seon.lib :as lib] [seon.print :as print]
            [seon.config] [seon.server] [my.root] [clojure.edn] [jolt.deps]
            ;; the e2e loop's subject: the binary built from a merge answers its new value
            [seon.generation] [seon.vector]
            ;; the procs system.edn names, resolved by name at boot (seon.system/proc-var)
            [seon.fork-server] [seon.prove] [seon.reconcile] [seon.render.proc] [seon.web] [seon.writer]))

(defn out!
  "xs printed as one line, flushed: a mode's console line (ROOT LISTENING, a verdict)."
  {:malli/schema [:=> [:cat [:* :any]] :nil]}
  [& xs] (println (apply str xs)) (flush))

(defn shown
  "A value a person reads on the console, whole, by the one printer (its layout, no budget)."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (print/text (vary-meta v assoc :seon.print/full true)))

(defn- checkout
  "The checkout this binary runs for: the parent of the working directory (bin/root and
  bin/check run the binary in target/)."
  {:malli/schema [:=> [:cat] :string]}
  [] (.getParent (java.io.File. (System/getProperty "user.dir"))))

(defn checkout-roots!
  "The checkout's source added to this process's loader: its deps.edn roots (jolt.deps/add-deps,
  as seon.fork-server's dev-classpath! does) and its test/ and src/ as source roots, so a name
  outside the release binary (the check driver, a scripted provider's fixture, both in test/)
  loads from the checkout by its namespace."
  {:malli/schema [:=> [:cat :string] :any]}
  [root]
  (jolt.deps/add-deps (update (clojure.edn/read-string (slurp (str root "/deps.edn"))) :deps update-vals
                              #(cond-> % (:local/root %) (update :local/root (partial str root "/")))))
  (jolt.host/set-source-roots! (into (vec (jolt.host/source-roots)) [(str root "/test") (str root "/src")])))

(defn root-serve
  "A root with its server on 127.0.0.1:(:seon.config.root/server-port) (seon.server),
  until stdin closes (bin/root stop) or SIGTERM, SIGINT or SIGHUP (Jolt's shutdown hooks):
  what an outside agent's MCP relay (bin/mcp) talks to. It watches what its loaders read
  (my.root/watch!: every writer's changes loaded as they happen). Its end is a fact on main
  naming that cause (seon.root/stop!). A checkout whose config names no server port (0)
  serves none, and says so; -1 asks the kernel for a free port. ROOT LISTENING names the port
  it bound (what bin/root and seon.test-store/start-root! read), never one picked beforehand.
  args: the mode's own, `--started-by ns/test` naming the test that started it (its boot's
  :seon.root/started-by)."
  {:malli/schema [:=> [:cat [:sequential :string]] :any]}
  [args]
  (let [port (seon.config/value :seon.config.root/server-port)
        _ (when (zero? port)
            (out! "ROOT REFUSED: :seon.config.root/server-port is 0 (no server); bin/worktree --ports writes config/local.edn with this checkout's ports")
            (lib/c-exit 1))
        ;; a scripted provider's script is a test fixture (seon.fixtures, in test/): the
        ;; provider's config says so, and the checkout's source is added before root starts
        _ (when (= :scripted (:kind (seon.config/provider seon.config/config))) (checkout-roots! (checkout)))
        sys (c/start! {:started-by (some->> args (partition 2 1) (filter #(= "--started-by" (first %))) last second)})]
    ;; the first cause stops root, once; a signal's hook waits for that stop, then Jolt exits
    (let [cause (promise) stopped (promise)
          ;; stopped is delivered however the stop ends, so a signal's hook waiting on it ends with
          ;; it (a stop that throws goes on to its caller, its cause first)
          end! (fn [why] (deliver cause why)
                 (when (= why @cause)
                   (try (out! "ROOT STOPPED " (shown (select-keys (c/stop! sys why) [:cause :ms :stop-fact-refused])))
                        (finally (deliver stopped true))))
                 @stopped)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (end! :signal))))
      ;; the key's three values, read here only: -1 the kernel assigns a port (0 to bind), >0 that
      ;; port (0, none, refused above); the port bound is the fact, printed for bin/root to record
      (let [bound (.getLocalPort ^java.net.ServerSocket (seon.server/serve! sys (if (= -1 port) 0 port)))]
        ;; the watcher ends with this process (target/watch exits on its parent's exit)
        (let [w (my.root/watch! sys)]
          (out! "ROOT WATCHING " (if (seon.contract/error? w) (str "nothing: " (:seon.error/message w)) "what its loaders read and its refs")))
        (out! "ROOT LISTENING " bound))
      (loop [] (when (read-line) (recur)))
      (end! :stdin-eof))
    (lib/c-exit 0)))

(defn ports
  "The checkout's ports, server then dashboard, on one line (bin/ports and bin/root read it)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (println (seon.config/value :seon.config.root/server-port) (seon.config/value :seon.config.web/port))
  (lib/c-exit 0))

(defn- driver!
  "The check driver's -main (seon.check, in test/: task seon.main/drivers-leave-src), loaded
  now from the checkout's source (checkout-roots!), so the release binary holds only the
  program and a check run compiles the driver it runs."
  {:malli/schema [:=> [:cat :string] :any]}
  [root]
  (checkout-roots! root)
  (requiring-resolve 'seon.check/-main))

(defn -main
  "The binary's modes: `root` (root-serve) and `ports` here, every other one the check
  driver's (driver!), with the checkout's root the parent of the working directory (bin/check
  and bin/root run the binary in target/)."
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& args]
  ;; first of all: Chez starts a GC sweeper per thread it finds waiting in a collection and never stops one,
  ;; so a pool left unfixed is as big as the busiest collection before root fixes it (seon.root/start!,
  ;; after the fork server), a number that follows timing. Fixed at 0 now (the first act of -main; a collection while
  ;; this namespace loads is earlier, and seon.root-test/the-sweeper-pool-is-fixed-before-anything-collects is the proof it starts none),
  ;; nothing starts before then.
  (lib/gc-sweepers! 0)
  ;; a process ends with what started it (a shell, bin/held, a test runner), and so does all it forks
  ;; (seon.lib/end-with!); one started by launchd (ppid 1) is nobody's child
  (let [pp (lib/c-getppid)] (when (> pp 1) (lib/end-with! pp true)))
  (case (first (seon.config/without-overrides args))
    "root" (root-serve (seon.config/without-overrides args))
    "ports" (ports)
    "seed-program" (let [r (c/seed-program! (second (seon.config/without-overrides args)))]
             (out! (if (seon.contract/error? r) "SEED FAILED " "SEED OK ") (shown r))
             (lib/c-exit (if (seon.contract/error? r) 1 0)))
    "embed-worker" (seon.vector/serve!)
    (apply (driver! (checkout)) args)))
