(ns seon.test-store-test
  "The test store's own rules: the process tests' store is made once, before any shard, and a
  test process's agent tests share one root of it."
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [datahike.api :as d]
            [seon.config] [seon.db :as pdb] [seon.lib] [seon.test-store :as ts] [seon.agent-test :as at])
  (:import [clojure.lang ExceptionInfo]))

(deftest the-process-store-is-made-before-any-shard
  ;; task seon.test-store/the-process-store-is-made-before-any-shard: shards' roots that each made
  ;; target/runs/test-store when they found it missing raced (one opened the store another was
  ;; still creating: "Database does not exist", the first test of 2 of 4 shards red on a fresh
  ;; tree). bin/test makes it before any shard starts; a test asking for it where it is not
  ;; made is refused, naming who makes it, and never creates it
  (let [co (str (System/getProperty "java.io.tmpdir") "/seon-process-store-" (random-uuid))
        path (str co "/target/runs/test-store-" pdb/schema-digest)]
    (try
      (let [e (is (thrown? ExceptionInfo (ts/process-store co)))]
        (is (= :seon.test-store/no-process-store (:seon.error/type (ex-data e))))
        (is (re-find #"bin/test makes it" (ex-message e))))
      (is (not (pdb/store? path)) "refused, it made nothing")
      (d/release (:conn (pdb/open! :lmdb path 0 :db)))
      (is (= path (ts/process-store co)) "made, it is the store")
      (finally (seon.lib/remove-tree! co)))))

(deftest a-test-root-is-its-own
  ;; task seon.test-store/a-test-root-is-its-own: root_test's root-answers gave every root on the
  ;; one test store the runs directory <store>-runs; bin/root start in a runs directory whose root
  ;; still runs only reports that root, so under parallel load one test asked another test's
  ;; root (main-follows-its-source-tree-across-restarts read the whole Seon program, :removed-forms 71).
  ;; A second root asked for in a runs directory a live root holds is refused, never answered
  ;; The live root is the one bin/root's status reads: runs/root.pids names a running process. This
  ;; process stands in for it (a root booted for it was all of this test's time; that a started root
  ;; listens is every process test's claim, and n-roots-open-the-process-store-at-once's)
  (let [store (ts/process-store)
        runs (str (.getParent (java.io.File. ^String store)) "/own-root-" (random-uuid) "-runs")
        pid (seon.lib/c-getpid)]
    (try
      (.mkdirs (java.io.File. ^String runs))
      (spit (str runs "/root.pids") (str pid " " pid "\n"))
      (let [e (is (thrown? ExceptionInfo (ts/start-root! {:store store :main (str "own-b-" (random-uuid)) :runs runs})))]
        (is (= :seon.test-store/runs-in-use (:seon.error/type (ex-data e)))))
      (finally (seon.lib/remove-tree! runs)))))

(deftest a-test-root-runs-scripted-unless-its-test-names-another
  ;; seon.test-store/root-profile: start-root! gave a root the starting process's own profile, so a
  ;; DeepSeek agent's call started a --profile deepseek root. This asks the args, not a booted root:
  ;; the process under bin/test is itself scripted, so a booted root could not tell the two apart
  (let [profile-of #(:profile (seon.config/overrides (ts/start-args %)))]
    (is (= "scripted" ts/root-profile (profile-of {:runs "r"})))
    (is (= "other" (profile-of {:runs "r" :args ["--set" ":a/b=1" "--profile" "other"]})))))

(deftest a-kept-store-is-keyed-by-its-schema
  ;; task seon.test-store/a-kept-store-is-keyed-by-its-schema: the page merge changed three
  ;; attributes to refs, and every checkout's kept program store and process store refused to open
  ;; (:seon.db/types-differ): bin/test failed at its plan, process tests at root start. A kept store
  ;; of output is named by the schema's digest; one made with another schema is removed, and the
  ;; new path is made fresh, nothing migrated or refused
  (let [dir (str (java.io.File. (System/getProperty "java.io.tmpdir") (str "seon-kept-" (random-uuid))))
        base (str dir "/test-store")
        old (str base "-00000000")
        runs (str base "-" pdb/schema-digest "-some-main-runs")]
    (try
      (.mkdirs (java.io.File. dir))
      ;; a store made under another schema's digest (what it holds is not read: its name says it)
      (let [cfg (pdb/store-cfg :lmdb old)]
        (d/create-database cfg)
        (let [c (pdb/connect cfg)]
          (d/transact c {:tx-data [{:db/ident :task/name :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]})
          (d/release c)))
      (.mkdirs (java.io.File. base))
      (.mkdirs (java.io.File. runs))
      (let [path (pdb/kept-store! base)]
        (is (= (str base "-" pdb/schema-digest) path) "named by this schema's digest")
        (is (not (.exists (java.io.File. old))) "the store of another schema is removed")
        (is (not (.exists (java.io.File. base))) "and the unkeyed one")
        (is (.exists (java.io.File. runs)) "a runs directory beside them is no store, and stays")
        (let [{:keys [conn seeded?]} (pdb/open! :lmdb path 0 :db)]
          (is seeded? "the new path is made fresh")
          (d/release conn)))
      (finally (seon.lib/remove-tree! dir)))))

(deftest n-roots-open-the-process-store-at-once
  ;; task seon.test-store/a-process-root-opens-the-shared-store-under-load: under load one shard's
  ;; root died before listening, `lmdb env_open: Invalid argument` opening the shared process store
  ;; other shards' roots held open. n = 2 roots start on it at once (a race of two reproduces it; 4 bought
  ;; CPU contention): every one listens
  (let [store (ts/process-store)
        dir (.getParent (java.io.File. ^String store))
        roots (vec (for [i (range 2)] {:store store :main (str "n2-" i "-" (random-uuid)) :runs (str dir "/n2-" i "-" (random-uuid) "-runs")}))
        started (mapv deref (mapv #(future (ts/start-root! %)) roots))]
    (try
      (is (every? :port started)
          (str "every root listens; the logs of those that did not: " (pr-str (map :log (remove :port started)))))
      (testing "a root that did not listen is the test's error with its log, never an ask of no port"
        (is (= {:error :root-did-not-listen :log "lmdb env_open: Invalid argument"}
               (ts/ask {:port nil :log "lmdb env_open: Invalid argument"} {:op :agents}))))
      (finally
        (doseq [r started :when (:port r)] (ts/stop-root! r))
        (doseq [r started] (seon.lib/remove-tree! (:runs r)) (ts/drop-main! store (:main r)))))))

(deftest agent-tests-in-one-process-share-its-root
  ;; task seon.test-store/an-agent-test-shares-its-shards-root: a root booted per in-agent test
  ;; was most of its time; the tests of one process now share one root, each with an
  ;; agent and a task of its own, and what one made is gone when it ends. The root runs the
  ;; checkout's own source (its source fact names it), so a test sees the code it is run on
  (let [ask (fn [names] [{:op :eval :agent "root"
                          :code (str "[(seon.lib/c-getpid)"
                                     " (:root (:seon.root/source (datahike.api/pull @(:conn sys) [:seon.root/source] [:seon.root/id :root])))"
                                     " (vec (sort (map name (datahike.api/branches (:conn sys)))))"
                                     " (into #{} (for [[id v] (:agents (c/state sys)) :when (not (:ended-ms v))] (name id)))]")}
                         "(:seon.task/id (my.task/current))"])
        first-names (volatile! nil)
        [a] (at/in-agent (fn [n] (vreset! first-names n) (ask n)))
        [b task] (at/in-agent ask)
        [pid-a source _] (:value a)
        [pid-b _ branches agents] (:value b)]
    (is (int? pid-a) (pr-str a))
    (is (= pid-a pid-b) "one root answered both tests")
    (is (= (seon.config/checkout) source) "it runs the checkout's source")
    (is (not= (:task @first-names) (:value task)) "each test has a task of its own")
    (is (re-find #"^[a-p][0-9a-f]{15}/t$" (str (:value task))) (pr-str task))
    (is (not (contains? agents (:agent @first-names))) (str "the first test's agent has ended (live: " agents ")"))
    (is (not-any? #(re-find (re-pattern (str "task-" (:ns @first-names) "-")) %) branches)
        (str "the first test's branches are gone: " branches))))

(deftest the-program-store-is-named-by-the-programs-own-schema
  ;; task seon.test-store/the-kept-program-store-survives-unrelated-schema-changes: every schema change named the
  ;; store anew, and the next plan imported the whole program (18-28 s, 3-4 G ticks)
  (let [decl (fn [k] {:db/ident k :db/valueType :db.type/string :db/cardinality :db.cardinality/one})
        base (vec pdb/schema)
        digest ts/program-schema-digest]
    (is (= (digest base) (digest (conj base (decl :seon.agent/zzz)))) "an agent's attribute: the same store")
    (is (= (digest base) (digest (conj base (decl :app.task/zzz)))) "any other namespace too")
    (is (not= (digest base) (digest (conj base (decl :form/zzz)))) "a form's attribute: a new store")
    (is (not= (digest base) (digest (conj base (decl :var/zzz)))) "a var's too")
    (is (= ts/kept-program-digest (digest base)) "this process's")
    (is (re-matches #"[0-9a-f]{8}" ts/kept-program-digest))))

(deftest an-ask-is-one-line-each-way-on-a-socket-of-its-own
  ;; docs/testing.md#a-root-costs-what-it-boots: ask opens its socket in-process (bin/ask was a perl process a
  ;; request, ~17 ms of a cheap one's ~20 under load); its answers are bin/ask's: the reply as data, :eof, :refused
  (let [serve (fn [reply] (let [ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
                            [ss (future (with-open [c (.accept ss)]
                                          (let [line (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream c) "UTF-8")))]
                                            (when reply
                                              (doto (java.io.OutputStreamWriter. (.getOutputStream c) "UTF-8") (.write (str (reply line) "\n")) (.flush))))))]))]
    (let [[ss f] (serve (fn [line] (pr-str {:echo (clojure.edn/read-string line) :tag (tagged-literal 'x 1)})))]
      (is (= {:echo {:op :agents} :tag (tagged-literal 'x 1)} (ts/ask {:port (.getLocalPort ss)} {:op :agents}))
          "the reply line read as data, a tagged literal kept")
      @f (.close ss))
    (let [[ss f] (serve nil)]
      (is (= {:error :eof} (ts/ask {:port (.getLocalPort ss)} {:op :agents})) "closed with no reply")
      @f (.close ss))
    (let [port (let [ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1")) p (.getLocalPort ss)] (.close ss) p)]
      (is (= {:error :refused} (ts/ask {:port port} {:op :agents})) "no one listening"))))
