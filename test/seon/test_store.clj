(ns seon.test-store
  "The test store: this process's one in-memory store for tests and cost setups, seeded once
  on :db (bin/test makes it on first use), and each test takes a branch of it (one store,
  many branches, as in production; owner, 2026-10-21). Test-only, so it lives in test/, not
  in src (session DUP-HELPERS); it seeds and opens through seon.db's own seed! and open-main!."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.db :as pdb] [clojure.java.shell :as sh] [clojure.string]))

(def ^:private store
  "The test store's config, made and seeded with about 2,000 datoms on first use."
  (delay (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                        :writer pdb/writer-cfg}]
           (d/create-database (pdb/made-cfg cfg))
           (let [conn (d/connect cfg)] (pdb/seed! conn 2000) (d/release conn))
           cfg)))

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
  "This test process's starter fifo: made once and held open read-write for the process's life
  (its children do not inherit it), so its EOF says the test process is gone however it ended;
  what a test starts runs under bin/held, which ends it then (task
  seon.check/a-test-root-names-its-store-and-main). [path its-holder]."
  (delay (let [path (str (.getCanonicalPath (java.io.File. "target/tmp")) "/test-starter-" (random-uuid) ".fifo")]
           (.mkdirs (java.io.File. "target/tmp"))
           (sh/sh "mkfifo" path)
           [path (java.io.RandomAccessFile. path "rw")])))

(defn binary-sh
  "clojure.java.shell/sh of a command that runs the checkout's binary (target/seon, bin/root,
  bin/check): the one way a test does, so seon.program/tree-readers names it and such a test's
  reach digest covers the whole tree the binary is built from. It runs under bin/held with this
  process's starter fifo: what it starts ends when the test process ends."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& args] (apply sh/sh "env" (str "SEON_STARTER_FIFO=" (first @starter)) (.getCanonicalPath (java.io.File. "bin/held")) args))

(defn- root-sh
  "bin/root VERB ARGS for a test's root (root): its store, main and runs named in the
  environment it starts with, over the caller's, so nothing inherited redirects it (task
  seon.check/a-test-root-names-its-store-and-main); its server port -1, a port the kernel
  assigns, which bin/root next carries to the next generation."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] :map]}
  [{:keys [store main runs env]} verb args]
  (apply binary-sh (concat ["env" (str "SEON_RUNS=" runs) (str "SEON_STORE=" store) (str "SEON_MAIN=" main)
                            "SEON_ENTRANCE_PORT=-1" "SEON_WEB_PORT=0"]
                           (for [[k v] env] (str k "=" v))
                           ["bin/root" verb] args)))

(defn- bound-port "The port the root in runs bound (bin/root wrote it from ROOT LISTENING), or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [runs]
  (let [f (java.io.File. (str runs "/root.port"))]
    (when (.exists f) (parse-long (clojure.string/trim (slurp f))))))

(defn start-root!
  "A built root (bin/root start, this checkout's binary) on store and main, both named and never
  inherited (a caller's SEON_STORE or SEON_MAIN, a bin/check run's, opened a store another run
  had written). Its server is on a port the kernel assigns (:seon.config.root/entrance-port -1),
  read back from its ROOT LISTENING line: bin/root returns at that line or at the root's death,
  never on a clock. runs: its pids, log and port; env: more variables {name value}; args: more
  root arguments. Answers the options with :port, nil when the root died before listening (its
  log says why)."
  {:malli/schema [:=> [:cat [:map [:store :string] [:main :string] [:runs :string]
                             [:env {:optional true} [:map-of :string :string]] [:args {:optional true} [:sequential :string]]]]
                  :map]}
  [{:keys [runs args] :as root}]
  (.mkdirs (java.io.File. ^String runs))
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
  "SIGKILL the root (bin/root kill), answered at its death as its holder reaped it."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] :map]}
  [root] (root-sh root "kill" []))

(defn ask
  "One request m to a started root's server: its line written on a socket, one reply line
  read. The read ends on the reply, or on EOF when the root closes the connection or dies
  (nil: a verdict, never a hang on nc's timeout, which macOS never fires)."
  {:malli/schema [:=> [:cat [:map [:port :int]] :map] [:maybe :string]]}
  [{:keys [port]} m]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (doto (.getOutputStream s) (.write (.getBytes (str (pr-str m) "\n") "UTF-8")) (.flush))
    (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8")))))

(defn stop-root!
  "Stop a root start-root! started (bin/root stop: its stdin closes, it stops cleanly, and the
  wait ends at its exit). Answers {:exit :log}: its exit status, and its whole output."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] [:map [:exit [:maybe :int]] [:log :string]]]}
  [{:keys [runs] :as root}]
  (let [{:keys [out]} (root-sh root "stop" [])
        log (java.io.File. (str runs "/root.log"))]
    {:exit (some-> (re-find #"\(exit (\d+)\)" out) second parse-long)
     :log (if (.exists log) (slurp log) "")}))
