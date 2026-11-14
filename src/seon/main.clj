(ns seon.main
  "The production entry point of the binary: a root serving its checkout (bin/system's `root`
  mode); every other mode loads the check driver (seon.check,
  in test/) from the checkout's source. The binary holds what this namespace requires, so it
  requires every namespace system.edn names a proc in (seon.graph resolves them by name at boot)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.system :as c] [seon.program] [seon.lib :as lib] [seon.print :as print]
            [seon.config] [seon.server] [seon.fx] [clojure.string] [my.root] [clojure.edn] [jolt.deps]
            ;; the e2e loop's subject: the binary built from a merge answers its new value
            [seon.generation] [seon.vector] [seon.problems :as problems]
            ;; the procs system.edn names, resolved by name at boot (seon.graph/proc-var)
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
  "The checkout this binary runs for: the parent of the working directory (bin/system and
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
  until stdin closes (bin/system stop) or SIGTERM, SIGINT or SIGHUP (Jolt's shutdown hooks):
  what an outside agent's MCP relay (bin/mcp) talks to. It runs off its store and reads no
  file (docs/source.md#a-boot-resumes-from-the-db). Its end is a fact on main
  naming that cause (seon.system/stop!). A checkout whose config names no server port (0)
  serves none, and says so; -1 asks the kernel for a free port. ROOT LISTENING names the port
  it bound (what bin/system and seon.test-store/start-root! read), never one picked beforehand; a ROOT STALE BINARY line
  before it, on one line, when the binary is not current for main (seon.system/stale-binary), which bin/system start ends on.
  args: the mode's own, `--started-by ns/test` naming the test that started it (its boot's
  :seon.system/started-by)."
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
                   (try (out! "ROOT STOPPED " (shown (select-keys (c/stop! sys why) [:cause :ms :drained :graph :stop-fact-refused])))
                        (finally (deliver stopped true))))
                 @stopped)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (end! :signal))))
      ;; the key's three values, read here only: -1 the kernel assigns a port (0 to bind), >0 that
      ;; port (0, none, refused above); the port bound is the fact, printed for bin/system to record
      ;; a test's root ends with its test process (:seon.config.root/end-with), armed now that the boot has forked
      ;; the fork server (a thread before it would refuse the fork); the fork server and agents end with root
      (let [p (seon.config/value :seon.config.root/end-with)] (when (pos? p) (lib/end-with! p false)))
      (let [bound (.getLocalPort ^java.net.ServerSocket (seon.server/serve! sys (if (= -1 port) 0 port)))]
        ;; a binary of another program than main's is never silent: said before the listening line, so bin/system start and
        ;; bin/update end on it (docs/source.md#files-and-the-db-two-directions)
        ;; the boot view's :stale-binary (seon.system/stale-binary's answer), on one line: bin/system start and bin/update keep the
        ;; line that starts with it, so its fields are joined as text, never a value a printer may break across lines
        ;; what this boot's catch-up indexed and left out (seon.system/catch-up), on one line each: bin/system start builds only after a
        ;; catch-up line (docs/source.md#a-boot-catches-up-with-its-checkout)
        (let [{:keys [paths skipped t] :as c} (get-in @(c/call! sys {:op :state}) [:boot :caught-up])]
          (when (seq paths) (out! "ROOT CAUGHT UP " (count paths) " files at t " t ": " (clojure.string/join " " paths)))
          (doseq [{:keys [path reason]} skipped] (out! "ROOT CATCH-UP LEFT OUT " path ": " reason))
          (when (seon.contract/error? c) (out! "ROOT CATCH-UP FAILED " (:seon.error/message c) ": main resumed as it was")))
        (when-let [{:keys [binary tree reaches missing error]} (get-in @(c/call! sys {:op :state}) [:boot :stale-binary])]
          (out! "ROOT STALE BINARY binary " (or binary "unrecorded") ", main's tree " tree
                (cond missing (str ", " (name missing) " missing") error (str ", " error) :else (str ", what differs reaches " (clojure.string/join " " reaches)))
                ": root forks no agent. " problems/stale-binary-acts))
        ;; a binary with no record of its forms holds none of main's: the fork server loads every live form (seon.program/held?),
        ;; a cost never paid silently
        (when-not (:forms (seon.program/binary-program))
          (out! "ROOT NO BINARY FORMS target/seon.program records no forms of the tree target/seon was built from: the fork server"
                " loads every form of main's program over the binary (bin/build records them)"))
        ;; the port, then how long bin/system stop waits for this process's exit after its TERM (:seon.config.root/stop-ms):
        ;; the script reads the one resolver's value from this line, never the config files itself
        (out! "ROOT LISTENING " bound " " (seon.config/value :seon.config.root/stop-ms)))
      (loop [] (when (read-line) (recur)))
      (end! :stdin-eof))
    (lib/c-exit 0)))

(defn- verdict-line
  "One item's verdict as bin/prove --root prints it as it lands (seon.prove's :prove-verdict): PASS with its whole ms, RED with
  each failure, NO-VERDICT with why; a test or step by its name, the
  tree's build or seed by its kind and tree. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [test kind digest status ms failures]}]
  (let [what (if (#{:build :seed} kind) (str (name kind) " of tree " digest) (str test))
        ms (when ms (str " " (long ms) " ms"))]
    (case status
      :pass (str "PASS " what ms)
      :no-verdict (str "NO-VERDICT " what ": " (clojure.string/join "; " (map :seon.error/message failures)))
      (clojure.string/join "\n  " (cons (str "RED " what " (" (name status) ")") (map shown failures))))))

(defn prove-client
  "bin/prove --root PORT's client: asks the root on port to prove checkout's files (the server's :prove with :checkout, its tree
  id and :stream), prints each test's verdict as it lands (verdict-line), and ends on PROVE OK (0) when the answer names no red,
  else PROVE FAILED (1) after the reds or the typed error that ended it (seon.server/ask-streamed). Answers the exit status.
  With --merge first, mode main: every key with no green at its digest, judged against no base, as bin/prove --merge plans a
  tree; so a system whose main is not the repository's (a worktree's own, proving a branch that changes the prover) proves the
  whole tree, not what differs from its own main. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :int :string [:sequential :string]] :int]}
  [port checkout args]
  (let [merge? (= "--merge" (first args))
        names (if merge? (rest args) args)
        tree (seon.fx/tree-id checkout)
        _ (out! "prove: asking root on port " port " for tree " tree (when merge? ", every key with no green at its digest"))
        r (seon.server/ask-streamed port (cond-> {:op :prove :checkout checkout :tree tree :mode (if merge? "main" "judge") :stream true}
                                           (seq names) (assoc :names (vec names)))
                                    #(case (:type %)
                                       :prove-verdict (out! (verdict-line %))
                                       ;; the service holding this run for the machine's load, and resuming it: never a silent wait
                                       :prove-held (out! (if (:held %)
                                                           (str "HELD: load " (:load %) " >= max-load " (:max-load %) ", " (:queued %) " items waiting")
                                                           "RESUMED"))
                                       nil))]
    (cond (seon.contract/error? r) (do (out! "prove: " (:seon.error/message r)) (out! "PROVE FAILED") 1)
          :else (do ;; the plan line every prove prints (bin/prove's merge verdict reads it for the landing's message)
                    (out! "prove: " (count (:selected r)) " keys, " (:reused r 0) " green at their digest (reused), " (:ran r 0)
                          " run (tests, process tests and check steps) through the system's one admission")
                    (out! "prove: " (shown (select-keys r [:key :ran :reused :pass :fail :error :deferred])))
                    (if (seq (:red r))
                      (do (out! "prove: red " (clojure.string/join " " (:red r))) (out! "PROVE FAILED") 1)
                      (do (out! "PROVE OK") 0))))))

(defn- driver!
  "The check driver's -main (seon.check, in test/), loaded
  now from the checkout's source (checkout-roots!), so the release binary holds only the
  program and a check run compiles the driver it runs."
  {:malli/schema [:=> [:cat :string] :any]}
  [root]
  (checkout-roots! root)
  (requiring-resolve 'seon.check/-main))

(defn -main
  "The binary's modes: `root` (root-serve) here, every other one the check
  driver's (driver!), with the checkout's root the parent of the working directory (bin/check
  and bin/system run the binary in target/)."
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& args]
  ;; first of all: Chez starts a GC sweeper per thread it finds waiting in a collection and never stops one,
  ;; so a pool left unfixed is as big as the busiest collection before root fixes it (seon.system/start!,
  ;; after the fork server), a number that follows timing. Fixed at 0 now (the first act of -main; a collection while
  ;; this namespace loads is earlier, and seon.system-test/the-sweeper-pool-is-fixed-before-anything-collects is the proof it starts none),
  ;; nothing starts before then.
  (lib/gc-sweepers! 0)
  ;; a process ends with what started it (a shell, bin/held, a test runner), and so does all it forks
  ;; (seon.lib/end-with!); one started by launchd (ppid 1) is nobody's child
  (let [pp (lib/c-getppid)] (when (> pp 1) (lib/end-with! pp true)))
  (case (first (seon.config/without-overrides args))
    "root" (root-serve (seon.config/without-overrides args))
    "seed-program" (let [r (c/seed-program! (second (seon.config/without-overrides args)))]
             (out! (if (seon.contract/error? r) "SEED FAILED " "SEED OK ") (shown r))
             (lib/c-exit (if (seon.contract/error? r) 1 0)))
    ;; what bin/build records beside the binary it built (target/seon.program): the program of the tree it was built from
    "built-program" (let [r (c/built-program (checkout) (second (seon.config/without-overrides args)))]
                      (if (seon.contract/error? r) (out! "BUILT-PROGRAM FAILED " (shown r)) (out! (seon.edn/encode r)))
                      (lib/c-exit (if (seon.contract/error? r) 1 0)))
    ;; bin/system start's: the inputs of this checkout's files as they are now, which the boot that follows reads them as
    ;; (runs/root-inputs.edn), so its agents key test results by what it read, never by a tree id a build recorded
    "inputs" (do (out! (seon.edn/encode (seon.fx/inputs (or (seon.config/value :seon.config.source/root) (checkout))))) (lib/c-exit 0))
    "embed-worker" (seon.vector/serve!)
    ;; bin/prove --root PORT [NS ...]: prove PORT CHECKOUT [NS ...]
    "prove" (let [[_ port checkout & names] (seon.config/without-overrides args)]
              (lib/c-exit (prove-client (parse-long port) checkout (vec names))))
    (apply (driver! (checkout)) args)))
