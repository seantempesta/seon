(ns seon.main
  "The production entry points of the binary: a root serving its checkout (bin/root's `root`
  mode) and the checkout's ports (`ports`). bin/build compiles the binary with the check
  driver (seon.check, in test/, outside src: task seon.main/drivers-leave-src), whose -main
  dispatches these two modes here and every check mode to itself."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as c] [seon.lib :as lib] [seon.print :as print]
            [seon.config] [seon.server] [my.root] [clojure.edn] [jolt.deps]))

(defn out!
  "xs printed as one line, flushed: a mode's console line (ROOT LISTENING, a verdict)."
  {:malli/schema [:=> [:cat [:* :any]] :nil]}
  [& xs] (println (apply str xs)) (flush))

(defn shown
  "A value a person reads on the console, whole, by the one printer (its layout, no budget)."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (print/text (vary-meta v assoc :seon.print/full true)))

(defn root-serve
  "A root with its server on 127.0.0.1:(:seon.config.root/server-port) (seon.server),
  until stdin closes (bin/root stop) or SIGTERM, SIGINT or SIGHUP (Jolt's shutdown hooks):
  what an outside agent's MCP relay (bin/mcp) talks to. It watches its checkout
  (my.root/watch!: every writer's changes loaded as they happen). Its end is a fact on main
  naming that cause (seon.root/stop!). A checkout whose config names no server port (0)
  serves none, and says so; -1 asks the kernel for a free port. ROOT LISTENING names the port
  it bound (what bin/root and seon.test-store/start-root! read), never one picked beforehand."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [port (seon.config/value :seon.config.root/server-port)
        _ (when (zero? port)
            (out! "ROOT REFUSED: :seon.config.root/server-port is 0 (no server); bin/worktree --ports writes config/local.edn with this checkout's ports")
            (lib/c-exit 1))
        sys (c/start! {})]
    ;; the first cause stops root, once; a signal's hook waits for that stop, then Jolt exits
    (let [cause (promise) stopped (promise)
          end! (fn [why] (deliver cause why)
                 (when (= why @cause) (out! "ROOT STOPPED " (shown (select-keys (c/stop! sys why) [:cause :ms :stop-fact-refused]))) (deliver stopped true))
                 @stopped)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (end! :signal))))
      ;; the key's three values, read here only: -1 the kernel assigns a port (0 to bind), >0 that
      ;; port (0, none, refused above); the port bound is the fact, printed for bin/root to record
      (let [bound (.getLocalPort ^java.net.ServerSocket (seon.server/serve! sys (if (= -1 port) 0 port)))]
        ;; the watcher ends with this process (target/watch exits on its parent's exit)
        (let [w (my.root/watch! sys)]
          (out! "ROOT WATCHING " (if (seon.contract/error? w) (str "nothing: " (:seon.error/message w)) "the checkout and its refs")))
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
  now from the checkout's source: its deps.edn roots added to the loader (jolt.deps/add-deps,
  as seon.fork-server's dev-classpath! does) and its test/ and src/ to the source roots, so the release binary holds
  only the program and a check run compiles the driver it runs."
  {:malli/schema [:=> [:cat :string] :any]}
  [root]
  (jolt.deps/add-deps (update (clojure.edn/read-string (slurp (str root "/deps.edn"))) :deps update-vals
                              #(cond-> % (:local/root %) (update :local/root (partial str root "/")))))
  (jolt.host/set-source-roots! (into (vec (jolt.host/source-roots)) [(str root "/test") (str root "/src")]))
  (requiring-resolve 'seon.check/-main))

(defn -main
  "The binary's modes: `root` (root-serve) and `ports` here, every other one the check
  driver's (driver!), with the checkout's root the parent of the working directory (bin/check
  and bin/root run the binary in target/)."
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& args]
  (case (first (seon.config/without-overrides args))
    "root" (root-serve)
    "ports" (ports)
    (apply (driver! (.getParent (java.io.File. (System/getProperty "user.dir")))) args)))
