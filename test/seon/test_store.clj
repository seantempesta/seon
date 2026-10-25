(ns seon.test-store
  "The test store: this process's one in-memory store for tests and cost setups, seeded once
  on :db (bin/test makes it on first use), and each test takes a branch of it (one store,
  many branches, as in production; owner, 2026-10-21). Test-only, so it lives in test/, not
  in src (session DUP-HELPERS); it seeds and opens through seon.db's own seed! and open-main!."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.db :as pdb] [clojure.java.shell :as sh] [clojure.string] [clojure.edn] [seon.lib]))

(def ^:private store
  "The test store's config, made and seeded with about 2,000 datoms on first use."
  (delay (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                        :writer pdb/writer-cfg}]
           (d/create-database (pdb/made-cfg cfg))
           (let [conn (d/connect cfg)] (pdb/ids! conn) (pdb/seed! conn 2000) (d/release conn))
           cfg)))

(defn kept-store!
  "The path of the kept store of regenerable output at base for this schema,
  base-<seon.db/schema-digest> (the test runner's program store, the process tests' store); a
  store at base made with another schema (base itself, or base-<8 hex digits> but this one) is
  removed: it is output, made again from its inputs, never a record, so a changed schema makes a
  new store, nothing migrated or refused (task seon.test-store/a-kept-store-is-keyed-by-its-schema).
  Nothing is made here; the caller opens it."
  {:malli/schema [:=> [:cat :string] :string]}
  [base]
  (let [f (java.io.File. ^String base)
        dir (.getParentFile f)
        stem (.getName f)
        keyed (str stem "-" pdb/schema-digest)
        old (re-pattern (str "\\Q" stem "\\E(-[0-9a-f]{8})?"))]
    (doseq [n (some-> dir .list seq) :when (and (re-matches old n) (not= n keyed))]
      (sh/sh "rm" "-rf" (str dir "/" n)))
    (str dir "/" keyed)))

(def ^:private program-store-path
  "This process's store of kept program dbs, one branch per git tree (test-runner/program-db),
  under target/tmp: a test process's own, so shards running at once never share one; removed
  when the process ends (a shutdown hook)."
  (delay (let [path (str (System/getProperty "user.dir") "/target/tmp/program-store-" (random-uuid))]
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (sh/sh "rm" "-rf" path))))
           path)))

(defn program-store
  "The path of this process's store of kept program dbs (program-store-path)."
  {:malli/schema [:=> [:cat] :string]}
  [] @program-store-path)

(defn process-store
  "The path of the one durable store the process tests' roots take a branch of (each its own
  main): <checkout>/target/runs/test-store-<schema digest> (kept-store!), made once by bin/test's
  plan before any shard starts (make-process-store!), so no two shards' roots ever create it at once.
  A root that found it missing created it (seon.db/open!), and another root starting in
  another shard meanwhile saw its data file and opened a database not yet made: \"Database
  does not exist\" (task seon.test-store/the-process-store-is-made-before-any-shard). A missing
  store is refused, naming who makes it, never created here. checkout: default this process's."
  {:malli/schema [:function [:=> [:cat] :string] [:=> [:cat :string] :string]]}
  ([] (process-store (.getCanonicalPath (java.io.File. "."))))
  ([checkout]
   (let [path (str checkout "/target/runs/test-store-" pdb/schema-digest)]
     (when-not (pdb/store? path)
       (throw (ex-info (str "no process store at " path ": bin/test makes it (its plan: make-process-store!) before any shard starts; run the test through bin/test")
                       {:seon.error/type :seon.test-store/no-process-store :path path})))
     path)))

(defn make-process-store!
  "The process tests' store (process-store) of checkout, made when missing (the schema and no
  example program on :db, as a root's own store starts) and every one of another schema removed
  (kept-store!): bin/test's plan calls it before any shard starts, when a selected test runs
  the binary. Answers its path."
  {:malli/schema [:=> [:cat :string] :string]}
  [checkout]
  (let [path (kept-store! (str checkout "/target/runs/test-store"))]
    (.mkdirs (.getParentFile (java.io.File. ^String path)))
    (d/release (:conn (pdb/open! :lmdb path 0 :db)))
    path))

(defn test-db
  "The test store's seed as a db value (the schema and about 2,000 datoms of the example
  program), for a test that only reads or uses d/db-with: no branch is made."
  {:malli/schema [:=> [:cat] :seon.host/db]}
  []
  (let [c (d/connect @store)] (try @c (finally (d/release c)))))

(defn test-branch!
  "(f cfg conn) on a fresh branch of the test store (its seed: the schema and about 2,000
  datoms of the example program), cfg naming the branch; the branch is deleted when f
  returns or throws (a fixture: a test leaves the test store as it found it). Answers f's value."
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/cfg :seon.host/conn] :any]] :any]}
  [f]
  (let [b (keyword (str "test-" (random-uuid)))
        [conn _] (pdb/open-main! @store b)
        seed (d/connect @store)]
    (try (f (assoc @store :branch b) conn)
         (finally (d/release conn) (pdb/delete-main! seed b) (d/release seed)))))

(def ^:private starter
  "This test process's starter fifo: made once, named by its pid, and held open read-write for the
  process's life (its children do not inherit it), so its EOF says the test process is gone however
  it ended; what a test starts runs under bin/held, which ends it then (task
  seon.check/a-test-root-names-its-store-and-main). Removed when the process ends or is
  terminated (a shutdown hook); a SIGKILLed one's is reclaimed by the next process's first use
  (bin/held --reclaim: its pid is no process) - task seon.check/a-killed-starter-leaves-no-fifo.
  [path its-holder]."
  (delay (let [dir (.getCanonicalPath (java.io.File. "target/tmp"))
               path (str dir "/test-starter-" (seon.lib/c-getpid) ".fifo")]
           (.mkdirs (java.io.File. dir))
           (sh/sh "bin/held" "--reclaim" dir)
           (sh/sh "rm" "-f" path)
           (sh/sh "mkfifo" path)
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (.delete (java.io.File. path)))))
           [path (java.io.RandomAccessFile. path "rw")])))

(defn binary-sh
  "clojure.java.shell/sh of a command that runs the checkout's binary (target/seon, bin/root,
  bin/check): the one way a test does, so seon.program/tree-readers names it and such a test's
  reach digest covers the whole tree the binary is built from. It runs under bin/held with this
  process's starter fifo: what it starts ends when the test process ends."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& args] (apply sh/sh "env" (str "SEON_STARTER_FIFO=" (first @starter)) (.getCanonicalPath (java.io.File. "bin/held")) args))

(defn- root-sh
  "bin/root COMMAND ARGS for a test's root (root): its store, main and runs named in the
  environment it starts with, over the caller's, so nothing inherited redirects it (task
  seon.check/a-test-root-names-its-store-and-main); its server port -1, a port the kernel
  assigns, which bin/root next carries to the next generation."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] :map]}
  [{:keys [store main runs env]} command args]
  (apply binary-sh (concat ["env" (str "SEON_RUNS=" runs) (str "SEON_STORE=" store) (str "SEON_MAIN=" main)
                            "SEON_SERVER_PORT=-1" "SEON_WEB_PORT=0"]
                           (for [[k v] env] (str k "=" v))
                           ["bin/root" command] args)))

(defn- bound-port "The port the root in runs bound (bin/root wrote it from ROOT LISTENING), or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [runs]
  (let [f (java.io.File. (str runs "/root.port"))]
    (when (.exists f) (parse-long (clojure.string/trim (slurp f))))))

(defn drop-main!
  "Delete main and every branch under it from the store at store (seon.check/drop-mains, through
  the binary), once the root that made them has stopped: what a test's root made leaves the one
  store with the test."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [store main]
  (binary-sh "./seon" "drop" main "--set" (str ":seon.config.root/store-path=" (pr-str store)) :dir "target"))

(defn start-root!
  "A built root (bin/root start, this checkout's binary) on store and main, both named and never
  inherited (a caller's SEON_STORE or SEON_MAIN, a bin/check run's, opened a store another run
  had written). Its server is on a port the kernel assigns (:seon.config.root/server-port -1),
  read back from its ROOT LISTENING line: bin/root returns at that line or at the root's death,
  never on a clock. runs: its pids, log and port; env: more variables {name value}; args: more
  root arguments. Answers the options with :port, nil when the root died before listening (its
  log says why). runs is one root's, named per main by its caller: a root still running there
  is refused, never answered (bin/root start in it would only report that root, and the test
  would ask another test's root: task seon.test-store/a-test-root-is-its-own). The refused start
  backs that rule, it is no lock: two starts at once in one runs could both pass it, which a
  runs per main makes unreachable."
  {:malli/schema [:=> [:cat [:map [:store :string] [:main :string] [:runs :string]
                             [:env {:optional true} [:map-of :string :string]] [:args {:optional true} [:sequential :string]]]]
                  :map]}
  [{:keys [runs args] :as root}]
  (.mkdirs (java.io.File. ^String runs))
  (when (zero? (:exit (root-sh root "status" [])))
    (throw (ex-info (str "a root is already running in " runs ": a test's root takes a runs directory of its own")
                    {:seon.error/type :seon.test-store/runs-in-use :runs runs})))
  (root-sh root "start" (vec args))
  (assoc root :port (bound-port runs)))

(defn next-root!
  "bin/root next on root: the running root's next generation started on the same store and main.
  Answers root with that one's :port."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] :map]}
  [root]
  (root-sh root "next" [])
  (assoc root :port (bound-port (:runs root))))

(defn kill-root!
  "SIGKILL the root (bin/root kill), answered once the root and every process it started are
  gone (bin/held --drain). Answers {:drained boolean}: false when bin/root did not say so."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] [:map [:drained :boolean]]]}
  [root] {:drained (boolean (re-find #"\(drained\)" (:out (root-sh root "kill" []))))})

(defn value-of
  "The value a response r showed (an eval's, :seon.eval/response), read back from its :text (the
  response printed under its handle; tagged literals kept), or nil when that text does not read
  back as EDN (a var, a cut)."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [r] (try (:value (clojure.edn/read-string {:default tagged-literal} (str (:text r)))) (catch Exception _ nil)))

(defn ask
  "One request m to a started root's server (bin/ask: its line on a socket, one reply line read,
  ending on the reply or when the root closes the connection or dies): the reply as data
  (tagged literals kept), or one recorded error value, {:error :eof} (closed with no reply),
  {:error :reset} or {:error :refused} (docs/failure-points.md), never a hang on nc's timeout,
  which macOS never fires. A caller asserts on :error."
  {:malli/schema [:=> [:cat [:map [:port :int]] :map] :map]}
  [{:keys [port]} m]
  (let [{:keys [out]} (sh/sh "bin/ask" (str port) :in (str (pr-str m) "\n"))]
    (if (clojure.string/blank? out)
      {:error :no-answer}
      (clojure.edn/read-string {:default tagged-literal} out))))

(defn stop-root!
  "Stop a root start-root! started (bin/root stop: its stdin closes, it stops cleanly, and the
  wait ends at its exit). Answers {:exit :log}: its exit status, and its whole output; or
  {:error :stopped-without-status :out out :log log} when bin/root printed none."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] :map]}
  [{:keys [runs] :as root}]
  (let [{:keys [out]} (root-sh root "stop" [])
        log (let [f (java.io.File. (str runs "/root.log"))] (if (.exists f) (slurp f) ""))
        status (second (re-find #"\(exit (\d+)\)" out))]
    (if status {:exit (parse-long status) :log log} {:error :stopped-without-status :out out :log log})))
