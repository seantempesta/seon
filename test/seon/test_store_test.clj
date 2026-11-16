(ns seon.test-store-test
  "The test store's own rules: the process tests' store is made once, before any shard, and a
  test process's agent tests share one root of it."
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [datahike.api :as d]
            [seon.config] [clojure.string] [seon.db :as pdb] [seon.lib] [seon.profile] [seon.agent] [seon.test-store :as ts] [seon.agent-test :as at])
  (:import [clojure.lang ExceptionInfo]))

(deftest an-agent-makes-its-fixtures-once-before-its-test-forks
  ;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent: the test store is a fixture (a delay, ^:seon.test/fixture); every fresh
  ;; test fork realized it again, 65M ticks and 1 to 3 s each in-process test item (2026-11-12). The agent forces its fixtures once
  ;; before its first fork, so a fork's test-db reads the seeded store; the second call forces nothing
  (with-redefs [seon.agent/state (atom {})]
    (let [n (#'seon.agent/warm-fixtures!)
          m (seon.profile/measure! #(ts/test-db))]
      (is (pos? n) "the test store is among the fixtures it forced")
      (is (< (:ticks m) 5000000) (str "test-db after the agent's warm reads the seeded store: " (:ticks m) " ticks"))
      (is (zero? (#'seon.agent/warm-fixtures!)) "once an agent"))))

(deftest ^{:seon.test/estimate {:ticks 300000}} the-process-store-is-made-before-any-shard
  ;; docs/testing.md#a-root-costs-what-it-boots, task seon.test-store/the-process-store-is-made-before-any-shard: shards' roots that each
  ;; made target/runs/test-store when they found it missing raced (one opened the store another was still creating: "Database does not
  ;; exist"). bin/test makes it before any shard starts; a test asking for it where it is not made is refused, naming who makes it, and
  ;; never creates it. A store is its data file (pdb/store?), so a file stands in for the one the maker seeds (seeding it was 17M ticks
  ;; of a 160 ms test). Estimate: a scratch dir, one refused read, one file: ~0.3M ticks
  (let [co (str (System/getProperty "java.io.tmpdir") "/seon-process-store-" (random-uuid))
        path (str co "/target/runs/test-store-" pdb/schema-digest)]
    (try
      (let [e (is (thrown? ExceptionInfo (ts/process-store co)))]
        (is (= :seon.test-store/no-process-store (:seon.error/type (ex-data e))))
        (is (re-find #"bin/test makes it" (ex-message e))))
      (is (not (pdb/store? path)) "refused, it made nothing")
      (.mkdirs (java.io.File. ^String path))
      (spit (str path "/data.mdb") "x")
      (is (= path (ts/process-store co)) "made, it is the store")
      (finally (seon.lib/remove-tree! co)))))

(deftest a-test-root-runs-scripted-unless-its-test-names-another
  ;; seon.test-store/root-profile: start-root! gave a root the starting process's own profile, so a
  ;; DeepSeek agent's call started a --profile deepseek root. This asks the args, not a booted root:
  ;; the process under bin/test is itself scripted, so a booted root could not tell the two apart
  (let [profile-of #(:profile (seon.config/overrides (ts/start-args %)))]
    (is (= "scripted" ts/root-profile (profile-of {:runs "r"})))
    (is (= "other" (profile-of {:runs "r" :args ["--set" ":a/b=1" "--profile" "other"]})))))

(deftest ^{:seon.test/estimate {:ticks 300000}} a-kept-store-is-keyed-by-its-schema
  ;; docs/merging.md#verdicts-are-a-cache, task seon.test-store/a-kept-store-is-keyed-by-its-schema: the page merge changed three
  ;; attributes to refs, and every checkout's kept program store and process store refused to open
  ;; (:seon.db/types-differ): bin/test failed at its plan, process tests at root start. A kept store
  ;; of output is named by the schema's digest; one made with another schema is removed, and the
  ;; new path is made fresh, nothing migrated or refused. Directory names are all kept-store! reads (what an old store holds is not
  ;; read), so a directory with a file stands in for each store; the new path is only named here, never opened (seeding it was
  ;; 14M ticks of a 320 ms test). Estimate: four scratch directories and a listing: ~0.3M ticks
  (let [dir (str (java.io.File. (System/getProperty "java.io.tmpdir") (str "seon-kept-" (random-uuid))))
        base (str dir "/test-store")
        old (str base "-00000000")
        runs (str base "-" pdb/schema-digest "-some-main-runs")]
    (try
      (.mkdirs (java.io.File. dir))
      (.mkdirs (java.io.File. old))
      (spit (str old "/data.mdb") "x")
      (.mkdirs (java.io.File. base))
      (.mkdirs (java.io.File. runs))
      (let [path (pdb/kept-store! base)]
        (is (= (str base "-" pdb/schema-digest) path) "named by this schema's digest")
        (is (not (.exists (java.io.File. old))) "the store of another schema is removed")
        (is (not (.exists (java.io.File. base))) "and the unkeyed one")
        (is (.exists (java.io.File. runs)) "a runs directory beside them is no store, and stays")
        (is (not (.exists (java.io.File. ^String path))) "the new path is named, made fresh by whoever opens it"))
      (finally (seon.lib/remove-tree! dir)))))

(deftest agent-tests-in-one-process-share-its-root
  ;; task seon.test-store/an-agent-test-shares-its-shards-root: a root booted per in-agent test
  ;; was most of its time; the tests of one process now share one root, each with an
  ;; agent and a task of its own, and what one made is gone when it ends. The root runs the
  ;; checkout's own source (its source fact names it), so a test sees the code it is run on
  (let [ask (fn [names] [{:op :eval :agent "root"
                          :code (str "[(seon.lib/c-getpid)"
                                     " (:root (:seon.system/source (datahike.api/pull @(:conn sys) [:seon.system/source] [:seon.system/id :root])))"
                                     " (vec (sort (map name (datahike.api/branches (:conn sys)))))"
                                     " (into #{} (for [[id v] (:agents (c/state sys)) :when (not (:ended-ms v))] (name id)))]")}
                         "(:seon.task/id (my.task/current agent))"])
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

(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/execs ["git"]} the-program-store-is-named-by-the-programs-own-schema
  ;; task seon.test-store/the-kept-program-store-survives-unrelated-schema-changes: every schema change named the
  ;; store anew, and the next plan imported the whole program (18-28 s, 3-4 G ticks)
  (let [decl (fn [k] {:db/ident k :db/valueType :db.type/string :db/cardinality :db.cardinality/one})
        base (vec pdb/schema)
        digest ts/program-schema-digest
        pins (#'ts/store-pins (seon.config/checkout))]
    (is (= (digest base) (digest (conj base (decl :seon.agent/zzz)))) "an agent's attribute: the same store")
    (is (= (digest base) (digest (conj base (decl :app.task/zzz)))) "any other namespace too")
    (is (not= (digest base) (digest (conj base (decl :form/zzz)))) "a form's attribute: a new store")
    (is (not= (digest base) (digest (conj base (decl :var/zzz)))) "a var's too")
    (is (= @ts/kept-program-digest (ts/program-store-key (digest base) pins))
        "this process's: its schema's and its checkout's pins of the code that encodes it")
    (is (= 3 (count pins)) "Jolt's, konserve's and Datahike's")
    (is (not= (ts/program-store-key (digest base) {"reference-code/jolt" "a"}) (ts/program-store-key (digest base) {"reference-code/jolt" "b"}))
        "another Jolt pin: another store, never an old encoding read by a new pin")
    (is (not= (ts/program-store-key (digest base) {"reference-code/konserve" "a"}) (ts/program-store-key (digest base) {"reference-code/konserve" "b"}))
        "another codec pin: another store")
    (is (re-matches #"[0-9a-f]{8}" @ts/kept-program-digest))))

;; docs/merging.md#verdicts-are-a-cache: a key's store is its generations (base-<key>, base-<key>-<n>), newest first; those of the key
;; beyond :seon.config.proofs/program-stores-kept most recently used go, never one a process holds open; other keys' generations go
;; with their key. Cost: directories in a scratch dir and one lsof a generation, ~5M.
(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/execs ["lsof"]} a-keys-generations-beyond-the-count-go-unless-held-open
  (let [dir (str (seon.config/checkout) "/target/tmp/gens-ps-" (random-uuid))
        base (str dir "/ps")
        mk (fn [n age] (let [f (java.io.File. (str base "-" n))] (.mkdirs f) (spit (str f "/data.mdb") "x")
                         (.setLastModified f (- (System/currentTimeMillis) (* age 60000))) f))
        there? (fn [n] (.isDirectory (java.io.File. (str base "-" n))))
        _ (do (mk "aaaaaaaa" 50) (mk "aaaaaaaa-1" 40) (mk "aaaaaaaa-2" 30) (mk "aaaaaaaa-3" 20) (mk "bbbbbbbb" 100) (mk "bbbbbbbb-1" 90) (mk "cccccccc" 5))
        holder (java.io.RandomAccessFile. (str base "-aaaaaaaa/data.mdb") "r")]
    (try
      (is (= [(str base "-aaaaaaaa-3") (str base "-aaaaaaaa-2") (str base "-aaaaaaaa-1") (str base "-aaaaaaaa")] (ts/generations (str base "-aaaaaaaa")))
          "the key's generations, newest first, generation 0 last")
      (is (= (str base "-aaaaaaaa-4") (ts/next-generation (str base "-aaaaaaaa"))) "the next one")
      (with-redefs [seon.config/value (let [v seon.config/value] (fn [k] (if (= k :seon.config.proofs/program-stores-kept) 2 (v k))))]
        (ts/shared-program-store! base "aaaaaaaa"))
      (is (there? "aaaaaaaa") "the least recently used, but held open by a process: kept")
      (is (not (there? "aaaaaaaa-1")) "past the count and held by none: removed")
      (is (and (there? "aaaaaaaa-2") (there? "aaaaaaaa-3")) "the two most recently used stay")
      (is (not (or (there? "bbbbbbbb") (there? "bbbbbbbb-1"))) "another key, past the count: its generations go with it")
      (is (there? "cccccccc") "and the most recently used other key stays")
      (finally (.close holder) (seon.lib/remove-tree! dir)))))

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
      (is (= {:error :refused} (ts/ask {:port port} {:op :agents})) "no one listening"))
    (is (= {:error :root-did-not-listen :log "lmdb env_open: Invalid argument"} (ts/ask {:port nil :log "lmdb env_open: Invalid argument"} {:op :agents}))
        "a root that did not listen is the test's error with its log, never an ask of no port")))

(deftest ^{:seon.test/estimate {:ticks 300000}} what-a-dead-test-process-made-is-reclaimed-and-a-live-ones-is-kept
  ;; docs/testing.md#a-root-costs-what-it-boots. Killed shards left 30 merges branches and 8 fixture worktrees (2026-11-12): their
  ;; shutdown hooks and finally blocks never ran. What a test process makes outside itself is named with its pid (ts/mark-of), and
  ;; reclaim! removes it once that pid is gone: dead-pid? is that decision (git only runs `branch -D` and `worktree remove` on what
  ;; it names, which git's own tests prove; the test made a repository, two worktrees and a fork for it, 256 ms). A pid no process
  ;; holds stands in for the dead one. Estimate: five name matches and five kill(pid, 0) calls: ~0.3M ticks
  (let [dead-pid 2147483646
        named (fn [label pid] (clojure.string/replace (ts/mark-of label) (str "-" (seon.lib/c-getpid) "-") (str "-" pid "-")))
        dead? #'ts/dead-pid?]
    (is (dead? (named "jolt/seon-merges-x-shard" dead-pid)) "the dead process's merges branch is reclaimed")
    (is (not (dead? (ts/mark-of "jolt/seon-merges-x-shard"))) "this live process's is kept")
    (is (dead? (str (named "/r-fix/seon-fixture" dead-pid) "/wt")) "the dead process's fixture worktree is reclaimed")
    (is (not (dead? (str (ts/mark-of "/r-fix/seon-fixture") "/wt"))) "this live process's is kept")
    (is (not (dead? "jolt/seon-merges-x-shard")) "a name no process made is never reclaimed")))

(deftest ^{:seon.test/estimate {:ticks 300000}} the-process-store-is-the-one-its-maker-recorded
  ;; docs/testing.md#a-root-costs-what-it-boots. Found 2026-11-12 (jolt/tests's land through the standing system): the seed made
  ;; the checkout's process store under the digest its own runtime derives, and a test child of the system's agent, running main's
  ;; program, derived another and found none: 197 process tests red. The maker records the path; every reader reads it. The
  ;; reader's claim: with a store at the derived path and another recorded, it takes the recorded. (The maker, make-process-store!,
  ;; seeds a store, 17M ticks and 184 ms; bin/test's plan runs it before every process test, and a reader it did not serve is red
  ;; there.) Estimate: two scratch stores as files and two reads: ~0.3M ticks
  (let [co (str (System/getProperty "java.io.tmpdir") "/seon-pstore-" (random-uuid))
        derived (str co "/target/runs/test-store-" pdb/schema-digest)
        other (str co "/target/runs/test-store-elsewhere")]
    (try
      (doseq [p [derived other]] (.mkdirs (java.io.File. ^String p)) (spit (str p "/data.mdb") "x"))
      (is (= derived (ts/process-store co)) "no record: the path it derives")
      (spit (str co "/target/process-store.path") (str other "\n"))
      (is (= other (ts/process-store co)) "a reader takes the recorded path, never one it derives")
      (finally (seon.lib/remove-tree! co)))))

;; docs/merging.md#verdicts-are-a-cache: the repository's program store is shared by checkouts at other keys at once (another
;; Jolt build, another program schema), so another key's store is kept, never removed for being another's (a plan opening one
;; failed "LMDB store does not exist" when each checkout removed the others', 2026-11-12); past
;; :seon.config.proofs/program-stores-kept by use, the least recently used goes. T1: directories in a scratch dir.
(deftest ^{:seon.test/estimate {:ticks 2000000}} the-shared-program-store-keeps-other-keys-stores
  (let [dir (str (seon.config/checkout) "/target/tmp/shared-ps-" (random-uuid))
        base (str dir "/ps")
        mk (fn [k age] (let [f (java.io.File. (str base "-" k))] (.mkdirs f) (.setLastModified f (- (System/currentTimeMillis) (* age 60000))) f))
        there? (fn [k] (.isDirectory (java.io.File. (str base "-" k))))]
    (try
      (mk "aaaaaaaa" 30) (mk "bbbbbbbb" 20)
      (is (= (str base "-cccccccc") (ts/shared-program-store! base "cccccccc")) "its own path, base-<key>")
      (is (and (there? "aaaaaaaa") (there? "bbbbbbbb")) "two other keys: both kept")
      (mk "cccccccc" 10)
      (ts/shared-program-store! base "dddddddd")
      (is (not (there? "aaaaaaaa")) "a fourth key: the least recently used other store goes")
      (is (and (there? "bbbbbbbb") (there? "cccccccc")) "the two most recently used others stay")
      (finally (seon.lib/remove-tree! dir)))))

;; docs/clocks.md#two-kinds-of-wait: an ask of a test root (seon.test-store/ask) ends at its declared bound, typed, with what it asked,
;; never at the test's wall (pilot 14's hang blocked in ask's readLine). Red before: ask had no bound, and this test ended at its wall
;; (OVERRAN, ~5 min). Estimate: one connection and one socket timeout, ~0.05M ticks; its wall time is its bound, 20 ms.
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-ask-no-root-answers-ends-at-its-bound
  (with-open [server (java.net.ServerSocket. 0 8)]
    (let [m {:op :prove :names ['a.b-test/t]}
          r (ts/ask {:port (.getLocalPort server)} m 500)]
      (is (= :seon.test-store/no-answer (:seon.error/type r)) (pr-str r))
      (is (= m (:asked r)) "naming what it asked"))))

;; docs/clocks.md#two-kinds-of-wait: an ask's bound in a test is the time LEFT of the test's wall, less the margin, never the whole
;; wall again: pilot 15's seon.server-test/a-sessions-checkout-is-proved-on-its-trees-branch booted a shared root and asked twice,
;; each ask bounded by the whole wall less 3 s, so the wall ended it (task seon.system/a-trees-prove-in-a-test-root-always-answers).
;; Red first by a stand-in that ignores the start (the old bound). Estimate: three config reads and a clock read, ~50k ticks.
(deftest ^{:seon.test/estimate {:ticks 200000}} an-asks-bound-is-the-time-left-of-its-tests-wall
  (let [wall (seon.agent/wall-bound-ms (str (first clojure.test/*testing-vars*)))
        margin (seon.config/value :seon.config.proofs/ask-margin-ms)
        spent 5000
        at-start (binding [seon.proofs/*test-started-ns* (seon.profile/now-ns)] (ts/ask-bound-ms))
        later (binding [seon.proofs/*test-started-ns* (- (seon.profile/now-ns) (* spent 1000000))] (ts/ask-bound-ms))
        past (binding [seon.proofs/*test-started-ns* (- (seon.profile/now-ns) (* (+ wall 1000) 1000000))] (ts/ask-bound-ms))]
    (is (<= (- wall margin 50) at-start (- wall margin)) "at its start: the wall less the margin")
    (is (<= later (- wall margin spent)) (str "5 s in, 5 s less: " later " of a " wall " ms wall"))
    (is (= 1 past) "past its wall: the least bound, never a negative one")))


;; Estimate: the candidate's one boot (~10.7 s of wall, docs/cost.md#the-test-systems-lower-bound) is its own processes' work; this
;; process's ticks are the process store's open and release (~10M), five shells (bin/system status, start and stop, cp, ps: ~1M
;; each), one root ask (~1M): ~16M ticks
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 16000000}} the-candidate-is-one-build-booted-once
  ;; seon.test-store/boot-candidate!: the process tests' one booted build, a value the runner passes, on a store of its own (never
  ;; runs/store), its writer process and system process (the fork server) live and distinct, the system process the writer's fork
  ;; (today's process model: seon.main/the-binary-main-is-the-system-process inverts it, and this test with it); each test a branch
  (let [c (ts/boot-candidate! (seon.config/checkout))
        ppid (fn [pid] (some-> (:out (sh/sh "ps" "-o" "ppid=" "-p" (str pid))) clojure.string/trim parse-long))]
    (try
      (let [a (ts/on-candidate c) b (ts/on-candidate c)]
        (is (pos-int? (:port a)) (str "it listens: " (subs (str (:log a)) (max 0 (- (count (str (:log a))) 3000)))))
        (is (= (dissoc a :branch) (dissoc b :branch)) "one system")
        (is (not= (:branch a) (:branch b)) "a branch each")
        (is (not= (:runs (seon.config/process)) (:runs a)) "never the standing system")
        (is (every? pos-int? [(:writer-pid a) (:system-pid a)]) (pr-str a))
        (is (not= (:writer-pid a) (:system-pid a)))
        (is (= (:writer-pid a) (ppid (:system-pid a))) "the system process is the writer's fork"))
      (finally (ts/stop-candidate! c)))))
