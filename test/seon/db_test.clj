(ns seon.db-test
  "One store, many branches, as in production (owner, 2026-10-21): bin/test seeds one test
  store and each test takes a branch of it through one helper (seon.test-store/test-branch!)."
  (:require [seon.task :as task] [seon.vector] [seon.test-store :as ts] [my.test :as mt] [seon.profile :as profile] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [clojure.java.io] [clojure.walk] [datahike.api :as d] [datahike.writer :as dw] [datahike.db.transaction :as dbt]
            [seon.db :as pdb] [seon.schema.datahike] [malli.core] [malli.registry] [seon.config :as cfg] [seon.contract :as contract] [seon.edn] [seon.repl] [seon.print] [datahike.schema]
            [konserve.jolt.lmdb :as klmdb] [konserve.jolt.lmdb-ffi :as lmdb] [konserve.jolt.lz4] [konserve.jolt.zstd] [clojure.string :as str] [konserve.core :as k] [hasch.core] [datahike.gc] [seon.cost :as cost] [seon.lib :as lib] [seon.intr :as intr]
            [datahike.datom :as dd] [datahike.index.persistent-set :as psi] [seon.merge] [org.replikativ.persistent-sorted-set]
            [jolt.scheme :as scm] [superv.async :as sv] [konserve.metrics :as km] [jolt.ffi :as ffi]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest eid-is-the-entity-of-a-unique-value
  (let [db (ts/test-db)]
    (testing "a unique attribute's value names its entity: the one a query finds"
      (is (= (d/q '[:find ?e . :where [?e :var/name app.m0/f1]] db) (pdb/eid db :var/name 'app.m0/f1)))
      (is (= 'app.m0/f1 (:var/name (d/entity db (pdb/eid db :var/name 'app.m0/f1))))))
    (testing "a value no entity holds is nil, not an id"
      (is (nil? (pdb/eid db :var/name 'app.m0/no-such)))
      (is (nil? (pdb/eid db :seon.system/id :root))))
    (testing "root's facts pulled by lookup ref are nil before root's first boot: no eid guard is needed"
      (is (nil? (d/pull db [:seon.system/boot] [:seon.system/id :root]))))))

(deftest a-test-takes-a-branch-of-the-test-store
  (ts/test-branch! (fn [cfg-a a] (ts/test-branch! (fn [cfg-b b] (let [seed (pdb/connect (dissoc cfg-a :branch))]
    (testing "both are branches of one store, each named apart"
      (is (= (get-in cfg-a [:store :id]) (get-in cfg-b [:store :id])))
      (is (not= (:branch cfg-a) (:branch cfg-b)))
      (is (pdb/branch? seed (:branch cfg-a)))
      (is (pdb/branch? seed (:branch cfg-b))))
    (testing "each starts at the seed: its merge base with :db is the seed's commit, and it holds the example program"
      (is (= (d/commit-id @seed) (d/merge-base a (:branch cfg-a) :db) (d/merge-base b (:branch cfg-b) :db)))
      (is (= (count (d/datoms @seed :eavt)) (count (d/datoms @a :eavt)) (count (d/datoms @b :eavt))))
      (is (= "f1 of module 0" (d/q '[:find ?d . :where [?e :var/name app.m0/f1] [?e :var/doc ?d]] @a))))
    (testing "what a test writes stays on its branch"
      (d/transact a [{:note/text "a's" :note/agent :t}])
      (d/transact b [{:note/text "b's" :note/agent :t}])
      (is (= #{"a's"} (notes @a)))
      (is (= #{"b's"} (notes @b)))
      (is (= #{} (notes @seed))))
    (testing "the five fresh-store copies went with the memory config"
      (is (nil? (resolve 'seon.db/create!)))
      (is (nil? (resolve 'seon.db/mem-cfg))))
    (d/release seed)))))))

(deftest each-id-is-issued-once-across-branches
  ;; the store's one page of ids (seon.db/ids!): two branches forked at one commit each take
  ;; their own entity ids and transaction ids, and an id carried in tx-data moves the page past it
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ids"))
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))
           write (fn [c tag] (let [r (d/transact c [{:db/id "n" :note/text tag :note/agent :t}])]
                               [(get-in r [:tempids "n"]) (get-in r [:tempids :db/current-tx])]))]
       (try
         (let [[ea ta] (write conn "a") [eb tb] (write bc "b")]
           (testing "two branches of one commit: different entity ids, different transaction ids"
             (is (not= ea eb))
             (is (not= ta tb))
             (is (= ta (:max-tx @conn)) "a branch's t is its last transaction's id")
             (is (< ta tb) "issued in order across branches"))
           (testing "an explicit id is never issued again"
             (let [far (+ 1000 (max ea eb))]
               (d/transact bc [{:db/id far :note/text "carried" :note/agent :t}])
               (is (< far (first (write conn "after")))))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest a-reopened-store-issues-past-every-head
  ;; the page is reseeded from the heads when it opens, so a page lost (a crash, a deleted
  ;; file) re-issues no id a committed head holds; a store on disk of its own, removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (let [r (d/transact conn [{:db/id "n" :note/text "before" :note/agent :t}])
            e (get-in r [:tempids "n"]) t (:max-tx (:db-after r))]
        (clojure.java.shell/sh "rm" (str path "/ids"))
        (pdb/ids! conn)
        (let [r2 (d/transact conn [{:db/id "n" :note/text "after" :note/agent :t}])]
          (is (< e (get-in r2 [:tempids "n"])))
          (is (< t (:max-tx (:db-after r2))))))
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(def ^:private stores-made
  "The paths of the stores on disk branched-store made, removed by the test that measured them."
  (atom []))

(defn branched-store
  "The arguments of open-and-release: the path of an LMDB store of its own (seon.db/open!'s, no example
  program) holding n branches besides :db, each made from :db as a root's main is and given a commit
  of its own, opened once since, so its ids page is stamped. Its path is kept in stores-made."
  [n]
  (let [path (str (seon.config/checkout) "/target/db-test-branched-" (random-uuid))
        {:keys [conn cfg]} (pdb/open! :lmdb path 0 :db)]
    (swap! stores-made conj path)
    (doseq [i (range n) :let [b (keyword (str "b" i))]]
      (d/branch! conn :db b)
      (let [c (pdb/connect (assoc cfg :branch b))]
        (d/transact c {:tx-data [{:note/text (str "n" i) :note/agent :t}] :tx-meta (pdb/tx-meta b)})
        (d/release c)))
    (d/release conn)
    (d/release (:conn (pdb/open! :lmdb path 0 :db)))
    [path]))

(defn open-and-release
  "The store at path opened as root opens it (seon.db/open!, :db its main) and released."
  [path] (d/release (:conn (pdb/open! :lmdb path 0 :db))) nil)

(deftest an-open-reads-no-head-but-its-own
  ;; docs/storage.md#ids: a store whose ids page is stamped with this boot and its file is opened
  ;; without reading every branch's head, so an open costs the same at any number of branches. Before
  ;; (every open moved the page past every head, datahike.writing/set-ids!'s roster walk): 2 reads,
  ;; 23.7k ticks and 68 KB a branch (0.55 M ticks at 1 branch, 24.2 M at 1,000; this proof read
  ;; 539k ticks and 2.46 MB at 1 and 753k and 3.06 MB at 10, red). After, measured 2026-11-10: 373k
  ;; ticks and 1.86 MB at 1, 10, 100 and 1,000 branches (10 reads). Stores on disk of this test's
  ;; own, the last resort: the page is a file beside data.mdb; removed at the end
  (try
    (cost/proof
      {:of 'seon.db-test/open-and-release :input 'seon.db-test/branched-store
       :n "branches the store holds besides :db"
       :work "the store's id read, :db connected, the ids page mapped and its stamp compared, the schema compared: none of it per branch"
       :growth :constant
       :fixed {:ticks 400000 :bytes 2000000}
       :sizes [1 10 100]})
    (finally (run! seon.lib/remove-tree! @stores-made) (reset! stores-made []))))

(defn- heads-read
  "[f's value, how many times f folded a store's roster]: the roster fold (konserve's reduce-log) is
  how datahike.writing/set-ids! reaches every branch's head, and an open makes no other."
  [f]
  (let [n (atom 0)]
    (km/add-sink! ::heads-read (fn [e] (when (= [:api :reduce-log] [(:level e) (:op e)]) (swap! n inc))))
    (try [(f) @n] (finally (km/remove-sink! ::heads-read)))))

(defn- page-words
  "The ids page beside the store at path, mapped again here: {:eid :tx} (words 0 and 1, the last ids issued)."
  [path]
  (let [[p _] (pdb/c-ids-open (str path "/ids"))] {:eid (ffi/read p :uint64 0) :tx (ffi/read p :uint64 8)}))

(defn- opened
  "The store at path opened as root opens it: [{:conn :heads (the highest :max-eid and :max-tx over every
  branch's head, the scan's own answer: the oracle) :page (page-words, read after the open)} roster folds]."
  [path]
  (let [[{:keys [conn]} folds] (heads-read #(pdb/open! :lmdb path 0 :db))]
    [{:conn conn :heads (#'datahike.writing/roster-max (:store @conn)) :page (page-words path)} folds]))

(defn- issues-past-heads
  "Is the page past every head's ids, and does a transaction on conn take ids past them? Then conn is released."
  [{:keys [conn heads page]}]
  (try
    (let [r (d/transact conn {:tx-data [{:db/id "n" :note/text "after" :note/agent :t}] :tx-meta (pdb/tx-meta :db)})]
      (and (<= (:max-eid heads) (:eid page)) (<= (:max-tx heads) (:tx page))
           (< (:max-eid heads) (get-in r [:tempids "n"])) (< (:max-tx heads) (:max-tx (:db-after r)))))
    (finally (d/release conn))))

(defn- commit-notes!
  "k transactions of a note each on branch b of the store conn opened, made from :db when the store lacks it."
  [conn b k]
  (when-not (pdb/branch? conn b) (d/branch! conn :db b))
  (let [c (pdb/connect (assoc (:config @conn) :branch b))]
    (try (dotimes [i k] (d/transact c {:tx-data [{:note/text (str (name b) " " i) :note/agent :t}] :tx-meta (pdb/tx-meta b)}))
         (finally (d/release c)))))

(deftest a-killed-writer-leaves-the-page-past-its-commits
  ;; docs/storage.md#ids: a process killed loses nothing of the page, which every writer takes its ids
  ;; from (both files' pages are in the OS page cache), so the next open trusts it and reads no head. A
  ;; child commits on two branches, takes ids it never commits, and is SIGKILLed with no sync and no
  ;; release; the open after it folds no roster, and its page and its next transaction are past every
  ;; head's ids (found by the scan itself, the oracle). A store on disk of its own, the last resort: the
  ;; page is a file beside data.mdb; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-killed-" (random-uuid))]
    (try
      (d/release (:conn (pdb/open! :lmdb path 0 :db)))
      (is (= {:signal 9}
             (lib/fork! {:child #(let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
                                   (commit-notes! conn :killed-a 3)
                                   (commit-notes! conn :killed-b 3)
                                   (dotimes [_ 5] (pdb/take-eid @conn))
                                   (lib/kill! (lib/c-getpid) 9))
                         :refused (fn [n] {:refused n})
                         :parent lib/wait-status}))
          "the writer was killed")
      (let [[o folds] (opened path)]
        (is (= 0 folds) "the open read no head: the page is trusted")
        (is (= 3 (d/q '[:find (count ?n) . :where [?n :note/text]] (pdb/head (:conn o) :killed-b)))
            "the killed writer's commits are in the store")
        (is (issues-past-heads o) "the page and the next transaction are past every head's ids"))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-copied-or-new-page-is-moved-past-the-heads
  ;; docs/storage.md#ids: a page made apart from its store is behind it, and its stamp says so: a store
  ;; cloned without its ids file (as bin/check takes its stores), one cloned with a copy of an ids file
  ;; taken before its last commits (cp: another inode), and one whose ids file was removed (a new file is
  ;; all zero, so a reused inode number is no stamp) each fold the roster at their open, and issue past
  ;; every head. Stores on disk of this test's own, the last resort; removed at the end
  (let [base (str (seon.config/checkout) "/target/db-test-copied-" (random-uuid))
        path (str base "/store")
        sh (fn [& args] (is (zero? (:exit (apply clojure.java.shell/sh args))) (str/join " " args)))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (commit-notes! conn :copied 2)
        (d/release conn))
      ;; the page as it was, then more commits: the copy of it is behind the copy of data.mdb
      (sh "cp" (str path "/ids") (str base "/ids-before"))
      (let [{:keys [conn cfg]} (pdb/open! :lmdb path 0 :db)]
        (commit-notes! conn :copied 3)
        (pdb/sync! cfg)
        (d/release conn))
      (doseq [[case ids] [["no ids file" nil] ["a copied ids file, behind" (str base "/ids-before")]]]
        (let [to (str base "/" (str/replace case #"\W" "-"))]
          (sh "mkdir" "-p" to)
          (sh "cp" "-c" (str path "/data.mdb") (str to "/data.mdb"))
          (when ids (sh "cp" ids (str to "/ids")))
          (testing case
            (let [[o folds] (opened to)]
              (is (= 1 folds) "the open read the heads")
              (is (issues-past-heads o) "and issues past them")))))
      (testing "the ids file removed and made again"
        (sh "rm" (str path "/ids"))
        (let [[o folds] (opened path)]
          (is (= 1 folds) "the open read the heads")
          (is (issues-past-heads o) "and issues past them")))
      (testing "the original store, reopened: its page is trusted"
        (let [[o folds] (opened path)]
          (is (= 0 folds))
          (is (issues-past-heads o))))
      (finally (seon.lib/remove-tree! base)))))

(deftest a-page-from-another-boot-is-moved-past-the-heads
  ;; docs/storage.md#ids: after the OS boots again the page may have lost what it was past (power lost,
  ;; the OS crashed), so a stamp of another boot is not trusted. A reboot is told to the page by changing
  ;; its stamp's boot identity (word 2, native/ids.c's layout) through a mapping of the file: the open
  ;; folds the roster and stamps it again, and the open after that trusts it. A store on disk of its own,
  ;; the last resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-reboot-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (commit-notes! conn :booted 2) (d/release conn))
      (let [[p _] (pdb/c-ids-open (str path "/ids"))
            boot (ffi/read p :uint64 16)]
        (is (= 1 (pdb/c-ids-trusted p (str path "/ids") (pdb/data-file :lmdb path))) "stamped by the open")
        (ffi/write p :uint64 (inc boot) 16)
        (is (= 0 (pdb/c-ids-trusted p (str path "/ids") (pdb/data-file :lmdb path))) "another boot's stamp is not trusted")
        (let [[o folds] (opened path)]
          (is (= 1 folds) "the open read the heads")
          (is (issues-past-heads o)))
        (is (= boot (ffi/read p :uint64 16)) "and stamped this boot")
        (let [[o folds] (opened path)] (d/release (:conn o)) (is (= 0 folds) "the next open trusts it")))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-page-beside-a-replaced-data-file-is-moved-past-the-heads
  ;; docs/storage.md#ids: the stamp names the store's data file too, so a data.mdb renamed in under a
  ;; kept ids file is not trusted: an older copy of the store (its page is ahead, but the open cannot
  ;; know it), and a copy that went further than the page (a clone that committed on: the kept page is
  ;; behind it, and trusting it would issue ids the store holds). Each open folds the roster and issues
  ;; past every head. Stores on disk of this test's own, the last resort; removed at the end
  (let [base (str (seon.config/checkout) "/target/db-test-replaced-" (random-uuid))
        path (str base "/store")
        clone (str base "/clone")
        sh (fn [& args] (is (zero? (:exit (apply clojure.java.shell/sh args))) (str/join " " args)))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (commit-notes! conn :replaced 2) (d/release conn))
      (sh "cp" "-c" (pdb/data-file :lmdb path) (str base "/older.mdb"))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (commit-notes! conn :replaced 2) (d/release conn))
      (testing "an older copy of the store renamed over its data file"
        (sh "mv" (str base "/older.mdb") (pdb/data-file :lmdb path))
        (let [[o folds] (opened path)]
          (is (= 1 folds) "the open read the heads")
          (is (issues-past-heads o) "and issues past them")))
      (testing "a copy that went further than the page renamed over its data file"
        (sh "mkdir" "-p" clone)
        (sh "cp" "-c" (pdb/data-file :lmdb path) (pdb/data-file :lmdb clone))
        (let [{:keys [conn]} (pdb/open! :lmdb clone 0 :db)] (commit-notes! conn :replaced 5) (d/release conn))
        (sh "mv" (pdb/data-file :lmdb clone) (pdb/data-file :lmdb path))
        (let [[o folds] (opened path)]
          (is (= 1 folds) "the open read the heads")
          (is (issues-past-heads o) "and issues past them")))
      (finally (seon.lib/remove-tree! base)))))

(deftest the-store-reports-its-map-use
  ;; a store on disk of this test's own, the last resort: what is proved is how an LMDB
  ;; store reports its map; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (let [{:keys [used-bytes map-bytes]} (pdb/map-use cfg)]
        (is (= (cfg/value :seon.config.store/lmdb-map-bytes) map-bytes))
        (is (< 0 used-bytes map-bytes))
        (d/transact conn [{:note/text (apply str (repeat 100000 "x")) :note/agent :t}])
        (pdb/sync! cfg)
        (is (< used-bytes (:used-bytes (pdb/map-use cfg))) "it grows as the store does"))
      (is (nil? (pdb/map-use (ts/test-branch! (fn [c _] c)))) "an in-memory store has no map")
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest two-processes-branch-one-store
  ;; two processes at once each make 50 branches of one store (two roots on one store, as
  ;; parallel check steps or a standing root beside a run): every branch is listed. A store on
  ;; disk of this test's own, the last resort: the race is between processes; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-race-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 0 :db)))
        jolt (str (seon.config/checkout) "/target/jolt/jolt")
        code (fn [tag] (pr-str `(do (require 'seon.db 'datahike.api)
                                    (let [c# (:conn (seon.db/open! :lmdb ~path 0 :db))]
                                      (dotimes [i# 50] (datahike.api/branch! c# :db (keyword (str ~tag "-" i#))))
                                      (System/exit 0)))))
        ;; each process's exit status and stderr, so a process that ended before its branches says why
        cmd (str "'" jolt "' -A:test -e " (pr-str (code "x")) " > /dev/null 2> '" path "-x.err' & x=$!; "
                 "'" jolt "' -A:test -e " (pr-str (code "y")) " > /dev/null 2> '" path "-y.err' & y=$!; "
                 "wait $x; ex=$?; wait $y; ey=$?; echo \"$ex $ey\"")
        r (clojure.java.shell/sh "nice" "-n" "10" "zsh" "-c" cmd :dir (seon.config/checkout))   ; -A:test reads the checkout's deps.edn: the test's own working directory is not it
        ends (zipmap ["x" "y"] (map parse-long (str/split (str/trim (:out r)) #" ")))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        made (for [t ["x" "y"] i (range 50)] (keyword (str t "-" i)))]
    (try
      (doseq [t ["x" "y"]]
        (is (= 0 (ends t)) (str "process " t " ended " (ends t) ": " (slurp (str path "-" t ".err")))))
      (is (= 100 (count (filter #(pdb/branch? conn %) made))) "every branch's head was written")
      (is (= (set made) (disj (d/branches conn) :db)) "and every branch is listed")
      (finally (d/release conn) (seon.lib/remove-tree! path (str path "-x.err") (str path "-y.err"))))))

(deftest a-deleted-store-leaves-nothing
  ;; docs/failure-points.md#lmdb-locks: a store's locks are record locks on its lock file, so nothing of it
  ;; outlives its files, whoever last used it and however it ended. Five stores of this test's own (the race is
  ;; between processes): two last used by a process that exits with them open, two by one SIGKILLed, one still
  ;; open in this process; seon.lib/remove-tree! deletes them: the tree is gone, this process holds no env
  ;; under it, and a store made again at a removed path is a new store that opens and writes.
  (let [base (str (seon.config/checkout) "/target/tmp/deleted-store-test-" (random-uuid))
        dir (str base "/stores")
        paths (mapv #(str dir "/s" %) (range 5))
        jolt (str (seon.config/checkout) "/target/jolt/jolt")
        code (fn [ps end] (pr-str `(do (require 'seon.db 'datahike.api 'seon.lib)
                                       (doseq [p# ~ps] (datahike.api/transact (:conn (seon.db/open! :lmdb p# 0 :db)) [{:note/text "x" :note/agent :t}]))
                                       ~end)))
        child (fn [ps end] (str "'" jolt "' -A:test -e " (pr-str (code ps end)) " > /dev/null 2>&1; echo $?"))
        held-under? (fn [] (some #(str/starts-with? % dir) (keys @@#'konserve.jolt.lmdb/envs)))]
    (try
      (run! #(d/release (:conn (pdb/open! :lmdb % 0 :db))) paths)
      (let [ends (str/split-lines (str/trim (:out (clojure.java.shell/sh "nice" "-n" "10" "zsh" "-c"
                                                   (str (child (subvec paths 0 2) '(seon.lib/c-exit 0)) "; "
                                                        (child (subvec paths 2 4) '(seon.lib/kill! (seon.lib/c-getpid) 9)))
                                                   :dir (seon.config/checkout)))))
            _held (pdb/open! :lmdb (paths 4) 0 :db)]
        (is (= ["0" "137"] ends) "one child exits with its stores open, the other is SIGKILLed")
        (is (held-under?) "this process holds the fifth store")
        (seon.lib/remove-tree! dir)
        (is (not (.exists (java.io.File. ^String dir))) "the tree is gone")
        (is (not (held-under?)) "this process holds no env under it")
        (let [{:keys [conn seeded?]} (pdb/open! :lmdb (paths 4) 0 :db)]
          (is (true? seeded?) "a store made again at a removed path is made, not found")
          (is (not-any? #(= "x" (:v %)) (d/datoms @conn :aevt :note/text)) "none of the removed store's facts")
          (d/release conn true)))
      (finally (seon.lib/remove-tree! base)))))

(deftest opening-a-kept-store-installs-the-attributes-it-lacks
  ;; a store on disk of this test's own, the last resort: what is proved is what a root's
  ;; open does to a kept store seeded before an attribute existed; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        lacking (:db/ident (last pdb/schema))
        _ (d/create-database cfg)
        c0 (pdb/connect cfg)
        ;; and one attribute installed before it was indexed
        _ (d/transact c0 {:tx-data (mapv #(if (= :config/file (:db/ident %)) (dissoc % :db/index) %) (butlast pdb/schema))})
        ;; a datom written before the index: an AVET read of an unindexed attribute answers empty, with no error
        _ (d/transact c0 {:tx-data [{:db/id "f" :form/id "v/1" :form/src "(def v 1)" :form/ns {:ns/name (quote v)} :form/order 0} {:config/key :db-test/k :config/form "1" :config/file "x42"}]})
        _ (is (empty? (d/datoms @c0 :avet :config/file)) "unindexed, an AVET read finds nothing")
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (= lacking (:db/ident (get (d/schema @conn) lacking))) "the attribute the store lacked is installed")
      (is (true? (get-in (d/schema @conn) [:config/file :db/index])) "and the index an attribute lacked")
      (is (= ["x42"] (mapv :v (d/datoms @conn :avet :config/file))) "and the datoms it already held read through that index")
      (let [t (:max-tx @conn)
            _ (d/release conn)
            {c2 :conn} (pdb/open! :lmdb path 0 :db)]
        (is (= t (:max-tx @c2)) "an open that finds nothing missing writes nothing")
        (d/release c2))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-kept-store-gains-content-ids-and-keeps-its-entities
  ;; a store seeded before content ids (its identities without :db/contentId): opening it
  ;; gives them their content ids; an entity it holds keeps its id, a new identity takes its
  ;; content id. A store on disk of this test's own, the last resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database (pdb/made-cfg cfg))
        c0 (pdb/connect cfg)
        _ (d/transact c0 {:tx-data (mapv #(dissoc % :db/contentId) pdb/schema)})
        old (get-in (d/transact c0 [{:db/id "f" :form/id "app.k/old" :form/src "(def old 1)" :form/ns "n" :form/order 0} {:db/id "n" :ns/name 'app.k}]) [:tempids "f"])
        old-ns (pdb/eid @c0 :ns/name 'app.k)
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (< old datahike.constants/tx0) "minted before: a counter id")
      (is (true? (get-in (d/schema @conn) [:form/id :db/contentId])) "the identity gained its content id")
      (is (= old (pdb/eid @conn :form/id "app.k/old")) "the entity it held keeps its id")
      (let [r (d/transact conn [{:db/id "f" :form/id "app.k/new" :form/src "(def new 1)" :form/ns "n" :form/order 1} {:db/id "n" :ns/name 'app.k}])
            new (get-in r [:tempids "f"])]
        (is (= (datahike.db.transaction/content-eid :form/id "app.k/new") new) "a new identity takes its content id")
        (is (= old-ns (get-in r [:tempids "n"]) (:db/id (:form/ns (d/entity @conn new))))
            "a tempid naming an identity the store holds is that entity, refs made before its map included"))
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest a-release-commits-what-was-applied
  ;; a write applied run-ahead (datahike.writer/apply!, an agent's transact!) and not yet
  ;; committed is committed by the release (the :sync writer's shutdown drains it), never
  ;; dropped. A store on disk of this test's own, the last resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (map? (dw/apply! conn {:tx-data [{:note/text "applied, not committed" :note/agent :t}] :tx-meta (pdb/tx-meta :db)})))
        (is (dw/pending? conn))
        (d/release conn true))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (some #(= "applied, not committed" (:v %)) (d/datoms @conn :aevt :note/text)) "the applied write is in the store")
        (d/release conn true))
      (finally (seon.lib/remove-tree! path)))))

(deftest database-exists-answers-whether-a-store-is-there
  ;; datahike.api/database-exists? answered through a go block that put! its value on
  ;; Datahike's throwable-promise, which on Jolt is no channel: the fiber threw and the caller
  ;; waited for ever (pure-tests' store-release-all-hang, after a store was removed). A store
  ;; on disk of this test's own, the last resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))]
    (try
      (is (false? (d/database-exists? (pdb/store-cfg :lmdb path))) "no store at the path")
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (d/release conn true))
      (is (true? (d/database-exists? (pdb/store-cfg :lmdb path))) "a store made there")
      (seon.lib/remove-tree! path)
      (is (false? (d/database-exists? (pdb/store-cfg :lmdb path))) "and none once it is removed")
      (finally (seon.lib/remove-tree! path)))))

(deftest a-store-made-again-at-a-removed-path-is-a-new-store
  ;; a store released and removed, then made again at its path in the same process: the
  ;; process's LMDB env for the path closed with the last store over it
  ;; (konserve.jolt.lmdb/release-env!), so the new store opens its own files, never the old
  ;; mapping (which still held :db: "Database already exists"). A store on disk of this
  ;; test's own, the last resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (d/transact conn {:tx-data [{:note/text "the first store's" :note/agent :t}] :tx-meta (pdb/tx-meta :db)})
        (d/release conn true))
      (seon.lib/remove-tree! path)
      (let [{:keys [conn seeded?]} (pdb/open! :lmdb path 0 :db)]
        (is (true? seeded?) "made, not found")
        (is (not-any? #(= "the first store's" (:v %)) (d/datoms @conn :aevt :note/text)) "none of the removed store's facts")
        (d/release conn true))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-read-after-release-is-a-typed-error
  ;; task seon.db/a-read-after-release-is-a-typed-error: a db value reads its nodes lazily from
  ;; its store's LMDB env, and the last release closes the env (an open env costs descriptors and
  ;; address space, docs/storage.md#a-value-is-read-before-its-last-release). A read after it was an invalid memory
  ;; reference that killed the process; it answers :lmdb-env-closed naming the store, and the
  ;; value is read before its last release. A store on disk of this test's own, the last
  ;; resort; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)
            _ (d/transact conn {:tx-data [{:note/text "kept" :note/agent :t}] :tx-meta (pdb/tx-meta :db)})
            db @conn]
        (d/release conn true)
        (is (= {:type :lmdb-env-closed :path path}
               (try (count (d/datoms db :eavt)) :read
                    (catch Throwable e (some #(when (= :lmdb-env-closed (:type (ex-data %))) (ex-data %))
                                             (take-while some? (iterate ex-cause e))))))
            "the read after the last release answers the typed error naming the store"))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-create-that-fails-leaves-no-store
  ;; task seon.db/a-store-is-made-whole-or-not-at-all: the full run of 2026-10-26 failed a create
  ;; after its env had made data.mdb, and the path then held a file with no database that no open
  ;; recovered ("Database does not exist"). Here a child creates a store and kills itself (SIGKILL,
  ;; nothing cleaned up) once the create has run its tick budget (seon.intr/run): four budgets (0.1, 0.8, 6.4 and 25.6 M; ten, doubling from 0.1 M, before) spread
  ;; over a create's work (~22 M ticks) kill it at four points of that work, the same on any machine,
  ;; and one create made here finishes. Each path then holds a whole store or none, and
  ;; the next open answers either. (It killed at the moment a data file showed, read by a busy loop of up
  ;; to 10 M directory listings: 0.25-147 G ticks a run, red at its budget.) Stores of this test's own
  (let [base (str (seon.config/checkout) "/target/db-test-create-" (random-uuid))
        killed (fn [path ticks]
                 (lib/fork! {:child #(do (try (intr/run ticks (fn [] (pdb/open! :lmdb path 0 :db))) (catch Throwable _ nil))
                                         (lib/kill! (lib/c-getpid) 9))
                             :refused (fn [n] {:refused n})
                             :parent (fn [pid] (lib/wait-status pid))}))
        opens (fn [path] (try (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (d/release conn true) :opens)
                              (catch Exception x (ex-message x))))]
    (.mkdirs (java.io.File. base))
    (try
      (is (= (repeat 4 :opens) (vec (for [i [0 3 6 8] :let [p (str base "/store-" i)]] (do (killed p (long (* 100000 (Math/pow 2.0 i)))) (opens p)))))
          "every path a killed create left opens")
      (testing "a create that completes leaves nothing beside its store"
        ;; made here, not by a budget past the create's ~22 M: a create that came to cost more would make that case a kill
        (is (= :opens (opens (str base "/whole"))))
        (is (empty? (filter #(str/includes? (.getName ^java.io.File %) ".making-") (.listFiles (java.io.File. base))))))
      (finally (seon.lib/remove-tree! base)))))

(deftest a-kept-store-from-before-the-runtime-attribute-opens
  ;; the transaction that installs :seon.tx/runtime cannot carry it in its tx-meta: a kept
  ;; store made before the attribute existed (every store before 2026-10-22) failed to open
  ;; (bin/check seed: "Bad entity attribute :seon.tx/runtime"). A store on disk of this test's
  ;; own, the last resort: what is proved is a root's open of a kept store; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-runtime-" (random-uuid))
        made (pdb/made-cfg (pdb/store-cfg :lmdb path))]
    (try
      (d/create-database made)
      (let [c0 (pdb/connect made)] (d/transact c0 {:tx-data (vec (remove #(= :seon.tx/runtime (:db/ident %)) pdb/schema))}) (d/release c0))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (= :seon.tx/runtime (:db/ident (get (d/schema @conn) :seon.tx/runtime))) "the attribute is installed")
        (let [r (d/transact conn {:tx-data [{:note/text "after" :note/agent :t}] :tx-meta (pdb/tx-meta :db)})]
          (is (string? (:seon.tx/runtime (d/entity @conn (:db/current-tx (:tempids r))))) "and the next transaction names its runtime"))
        (d/release conn))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-moved-store-opens
  ;; task seon.db/a-store-opens-where-it-lies: a store was named by (hasch/uuid path) of the
  ;; path it was given (seon.db/store-cfg), so a store renamed or copied (kept runs:
  ;; next/runs/store-full-20261021, renamed from next/runs/store) was refused at its new path
  ;; with "Store identity mismatch". Now it is named by the id it records. A store on disk of
  ;; this test's own, the last resort: what is proved is a move and a copy; removed at the end
  (let [base (str (seon.config/checkout) "/target/db-test-move-" (random-uuid))
        moved (str base "-moved")
        copied (str base "-copied")
        made (do (d/release (:conn (pdb/open! :lmdb base 0 :db))) (get-in (pdb/store-cfg :lmdb base) [:store :id]))
        _ (clojure.java.shell/sh "mv" base moved)
        _ (clojure.java.shell/sh "cp" "-R" moved copied)
        opens (fn [path] (let [r (try (pdb/open! :lmdb path 0 :db) (catch Exception e {:refused (ex-message e)}))]
                           (is (some? (:conn r)) (str path " opens: " (:refused r)))
                           (when-let [c (:conn r)]
                             (is (= made (get-in r [:cfg :store :id])) "named by the id it was made with")
                             (is (= path (get-in r [:cfg :store :path])) "its config names where it lies now")
                             (is (= path (get-in @c [:config :store :path])) "and so does its db")
                             (is (seq (d/datoms @c :eavt)) "and it holds its data")
                             (d/release c))))]
    (try
      (opens moved)
      (opens copied)
      (finally (seon.lib/remove-tree! base moved copied)))))

(deftest a-kept-store-whose-types-differ-is-refused
  ;; an attribute's value type is fixed once installed (Datahike refuses to alter it), so a
  ;; kept store holding an attribute as another type than the schema declares (the EDN-text
  ;; attributes before they became plain data, 2026-10-22) is refused at open, naming each such
  ;; attribute: never opened to fail at its first write. A store on disk of this test's own,
  ;; the last resort: what is proved is a root's open of a kept store; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-types-" (random-uuid))
        made (pdb/made-cfg (pdb/store-cfg :lmdb path))
        old (mapv #(if (= :seon.model-call/basis (:db/ident %)) (assoc % :db/valueType :db.type/string) %) pdb/schema)]
    (try
      (d/create-database made)
      (let [c0 (pdb/connect made)] (d/transact c0 {:tx-data old}) (d/release c0))
      (let [r (try (pdb/open! :lmdb path 0 :db) (catch Exception e (ex-data e)))]
        (is (= :seon.db/types-differ (:seon.error/type r)) (pr-str r))
        (is (= {:seon.model-call/basis {:stored :db.type/string :declared :db.type/data}} (:seon.db/attributes r))))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-kept-store-on-a-retired-name-is-refused
  ;; a stored keyword is renamed by erasing the store, never by a migration (docs/storage.md#a-renamed-attribute): a store holding a retired name (here :schema.file/path, before
  ;; :file/path) would open, gain the new name and hold no text under it, and its next write-back would rewrite each resource from nothing. Refused at open, saying what to do.
  ;; A store on disk of this test's own, the last resort: what is proved is a root's open of a kept store
  (let [path (str (seon.config/checkout) "/target/db-test-retired-" (random-uuid))
        made (pdb/made-cfg (pdb/store-cfg :lmdb path))
        old (conj (vec pdb/schema) {:db/ident :schema.file/path :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity})]
    (try
      (d/create-database made)
      (let [c0 (pdb/connect made)] (d/transact c0 {:tx-data old}) (d/release c0))
      (let [r (try (pdb/open! :lmdb path 0 :db) (catch Exception e (ex-data e)))]
        (is (= :seon.db/name-retired (:seon.error/type r)) (pr-str r))
        (is (= {:schema.file/path :file/path} (:seon.db/retired r))))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-value-with-no-one-type-is-plain-data-by-its-shape
  ;; a schema says plain data by its shape, with no mark (owner, 2026-10-25: "support vectors and
  ;; then under the hood serialize them"): a vector, tuple, map or mixed union is :db.type/data, a
  ;; vector keeping its order and duplicates; a set of scalars stays cardinality many
  (let [reg (malli.registry/composite-registry
             {:lab/v (malli.core/schema [:vector :keyword]) :lab/t (malli.core/schema [:tuple :keyword :int])
              :lab/m (malli.core/schema [:map [:a :int]]) :lab/u (malli.core/schema [:or :string :int])
              :lab/s (malli.core/schema [:or :string [:re #"x"]]) :lab/k (malli.core/schema [:set :keyword])
              :lab/q (malli.core/schema [:sequential :int]) :lab/mo (malli.core/schema [:map-of :keyword :int])
              :lab/a (malli.core/schema :any) :lab/r (malli.core/schema [:or :seon.db/ref :string] {:registry contract/registry})
              :lab/ks (malli.core/schema [:or [:set :keyword] :keyword])
              :lab/n (malli.core/schema [:or :string [:maybe :string]]) :lab/sm (malli.core/schema [:set [:map [:a :int]]])}
             contract/registry)
        decl (fn [a] (let [d (seon.schema.datahike/declaration reg a)] [(:db/valueType d) (:db/cardinality d)]))]
    (is (= {:lab/v [:db.type/data :db.cardinality/one] :lab/t [:db.type/data :db.cardinality/one]
            :lab/m [:db.type/data :db.cardinality/one] :lab/u [:db.type/data :db.cardinality/one]
            :lab/s [:db.type/string :db.cardinality/one] :lab/k [:db.type/keyword :db.cardinality/many]
            :lab/q [:db.type/data :db.cardinality/one] :lab/mo [:db.type/data :db.cardinality/one]
            :lab/a [:db.type/data :db.cardinality/one] :lab/r [:db.type/data :db.cardinality/one]
            :lab/ks [:db.type/data :db.cardinality/one]}
           (into {} (map (juxt identity decl)) [:lab/v :lab/t :lab/m :lab/u :lab/s :lab/k :lab/q :lab/mo :lab/a :lab/r :lab/ks])))
    (testing "an unstorable schema stays unstorable, with its reason: a nilable arm, a set of maps"
      (is (= {:lab/n :seon.schema.datahike/nilable :lab/sm :seon.schema.datahike/nested-collection}
             (into {} (map (juxt identity #(:seon.schema/reason (seon.schema.datahike/declaration reg %)))) [:lab/n :lab/sm])))))
  (testing "no registered schema carries a storage mark for plain data: its shape is the one statement"
    (is (empty? (for [[k s] (malli.registry/schemas contract/registry)
                      :when (and (qualified-keyword? k) (malli.core/schema? s)
                                 (contains? (malli.core/properties s) :seon.db/data))]
                  k))))
  (testing "a stored vector comes back in its order, duplicates kept"
    (ts/test-branch!
     (fn [_ conn]
       (let [v '[["b/x" 0.5] ["a/y" 0.9] ["b/x" 0.5] ["c/z" 0.1] ["b/x" 0.5]]
             e (get (:tempids (d/transact conn [{:db/id "p" :seon.agent/key "db-test/order" :seon.ctx/pattern v}])) "p")]
         (is (= v (:seon.ctx/pattern (d/pull @conn [:seon.ctx/pattern] e))))))))
  (testing "a stored vector of any length retracts, by value and by attribute (our Datahike fork, jolt/a-data-vector-retracts)"
    (ts/test-branch!
     (fn [_ conn]
       (let [v '[["b/x" 0.5] ["a/y" 0.9] ["c/z" 0.1]]
             e (get (:tempids (d/transact conn [{:db/id "p" :seon.agent/key "db-test/retract" :seon.ctx/pattern v}])) "p")]
         (d/transact conn [[:db/retract e :seon.ctx/pattern v]])
         (is (nil? (:seon.ctx/pattern (d/pull @conn [:seon.ctx/pattern] e))))
         (d/transact conn [[:db/add e :seon.ctx/pattern v]])
         (d/transact conn [[:db.fn/retractAttribute e :seon.ctx/pattern]])
         (is (nil? (:seon.ctx/pattern (d/pull @conn [:seon.ctx/pattern] e)))))))))

;; ---- history (task seon.db/history-is-kept: v1's ruling 23, Datahike's default) --------------

(deftest the-store-keeps-history
  ;; with :seon.config.store/keep-history? (default true) a store keeps its temporal indexes:
  ;; a retraction stays visible in d/history, and a transaction keeps its :db/txInstant
  ;; (Datahike applies a transaction's tx-meta only with history: off, it was silently dropped)
  (ts/test-branch!
   (fn [_ conn]
     (let [{tx :tempids} (d/transact conn [{:db/id "n" :note/text "kept" :note/agent :t}])
           e (get tx "n")
           r (d/transact conn [[:db/retractEntity e]])
           tx-e (:db/current-tx (:tempids r))]
       (is (true? (:keep-history? (:config @conn))))
       (is (= #{["kept" true] ["kept" false]}
              (set (for [d (d/datoms (d/history @conn) :eavt e :note/text)] [(:v d) (:added d)])))
           "the note's assertion and its retraction are both in the history")
       (is (inst? (:db/txInstant (d/entity @conn tx-e))) "the retracting transaction keeps its instant")))))

(deftest a-kept-store-keeps-the-history-it-was-made-with
  ;; :keep-history? is fixed when a store is made (Datahike's store-fixed-record-keys): an open
  ;; adopts what the store records, so a kept store made either way opens, and keeps its layout
  ;; (the standing root's store was made without history). A store on disk of this test's own,
  ;; the last resort: what is proved is a root's open of a kept store; removed at the end
  (doseq [kept? [false true]]
    (let [path (str (seon.config/checkout) "/target/db-test-history-" (random-uuid))
          made (assoc (pdb/store-cfg :lmdb path) :keep-history? kept?)]
      (try
        (d/create-database made)
        (let [c0 (pdb/connect made)] (d/transact c0 {:tx-data pdb/schema}) (d/release c0))
        (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
          (is (= kept? (:keep-history? (:config @conn))) (str "a store made with :keep-history? " kept? " opens keeping it"))
          (d/release conn))
        (finally (seon.lib/remove-tree! path))))))

(defn- stored-bytes
  "Value bytes of every key in the LMDB store at path, by key."
  [path]
  (let [db (klmdb/open-env! path nil)]
    (lmdb/read-txn db (fn [txn] (into {} (for [k (lmdb/all-keys db txn)] [k (alength ^bytes (lmdb/get-bytes db txn k))]))))))

(defn- corpus
  "The text of the checkout's files under dir ending in ext, sorted by path and joined: real
  text (docs/*.md) or real code (src/**/*.clj) for turn text that compresses as a turn's does."
  [dir ext]
  ;; the checkout's, not the working directory's: an agent's test child runs in a run directory of its own
  (->> (file-seq (clojure.java.io/file (seon.config/checkout) dir)) (map str) (filter #(clojure.string/ends-with? % ext)) sort
       (map slurp) (apply str)))

(def ^:private text (delay (corpus "docs" ".md")))
(def ^:private code (delay (corpus "src" ".clj")))

(defn- slice
  "n characters of text from k*n on, wrapping at its end: turn k's own text."
  [text k n]
  (let [i (mod (* k n) (- (count text) n))] (subs text i (+ i n))))

(defn- turn-facts
  "Turn k of an agent as seon.turn/act! records it: the basis its context was built from
  (the commit, its red assertion; the text is made again from it, seon.ctx.wire/render), its
  reply (1,500 bytes) and its eval call, whose eval's record holds what it evaluated to as the
  next turn shows it (2,000). The reply is real text and the evaluated value real source, each turn its own
  (a real turn's reply is the model's message and code; its evals are mostly source and
  printed data: a slurp, a source, a test's report): a repeated letter compressed ~3x better
  than real turn text and made LZ4 look like the fix (2026-10-22)."
  [k]
  [{:seon.model-call/id (str "att/" k) :seon.model-call/agent {:seon.agent/key "att"} :seon.model-call/n k :seon.model-call/origin :model
    :seon.model-call/reply (slice @text k 1500) :seon.model-call/forms 1
    :seon.model-call/basis {:commit (java.util.UUID. 0 k) :task "t/x" :turn k :max-turns 8 :origin :model}}
   ;; the call's eval, whose record holds its response as the next turn reads it
   {:seon.eval/id (str "e" k) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(f 2)" :seon.eval/response {:handle (symbol "result" (str "e" k)) :value (slice @code k 2000)}}])

(defn- write-turns!
  "Bytes the store at path gains when branch b gets n turns through the agent's run-ahead
  writer (datahike.writer/apply!), committed every `every` turns."
  [conn cfg path b n every]
  (d/branch! conn :db b)
  (let [c (pdb/connect (assoc cfg :branch b))
        before (stored-bytes path)]
    (doseq [k (range n)]
      (dw/apply! c {:tx-data (turn-facts k)})
      (when (zero? (mod (inc k) every)) (dw/commit-pending! c)))
    (pdb/sync! cfg)
    (d/release c)
    (reduce + 0 (for [[k v] (stored-bytes path) :when (not= v (get before k))] v))))

;; ---- node compression (task seon.db/nodes-compress: :seon.config.store/compressor) ----------

(deftest stored-values-compress-through-the-codecs-the-build-made
  ;; the store's values are zstd frames (the default) or LZ4 frames, through the libzstd and
  ;; liblz4 bin/build makes from their pinned sources (reference-code/zstd; Chez's vendored
  ;; lz4), target/lib, bin/env's JOLT_ZSTD_LIB and JOLT_LZ4_LIB, never a system library; a
  ;; frame round-trips, and real text (the checkout's docs) compresses
  (let [text (.getBytes ^String (slice @text 0 65536) "UTF-8")]
    (doseq [[lib compress decompress] [[konserve.jolt.zstd/lib-path konserve.jolt.zstd/compress konserve.jolt.zstd/decompress]
                                       [konserve.jolt.lz4/lib-path konserve.jolt.lz4/compress konserve.jolt.lz4/decompress]]]
      (is (str/starts-with? lib (str (cfg/checkout) "/target/lib/")) lib)
      (let [frame (compress text)]
        (is (= (seq text) (seq (decompress frame))) (str lib ": a frame round-trips"))
        (is (< (alength frame) (/ (alength text) 1.5)) (str lib ": 64 KiB of docs compress to " (alength frame) " bytes"))))
    (is (= :zstd (cfg/value :seon.config.store/compressor)) "the store's default codec")))

(deftest a-large-value-lives-once-under-its-own-key
  ;; a value past :seon.config.store/value-out-of-line-bytes is stored once under its content
  ;; key (hasch's uuid of the value, Datahike's own content address) inside Datahike's store;
  ;; the index holds a reference, read back as the value by the first read since the write
  ;; (a written node is not cached: the storage counts the nodes it read from the store; one
  ;; read only, as a later connection may reuse the store's storage and its cache); GC's mark
  ;; keeps the value while a branch reaches it and sweeps it once none does. A store on disk of this test's own, the last resort: what is
  ;; proved is the store's bytes and its collection; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-ool-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        _ (d/release conn)
        reply (slice @text 7 3000)
        vk (hasch.core/uuid reply)
        on (fn [b f] (let [c (pdb/connect (assoc cfg :branch b))] (try (f c) (finally (d/release c)))))
        ;; the large reply's own call, by its id: the branch holds turn 0's reply too, so a query for any reply answered
        ;; whichever of the two Datahike found first (red on main 210ad0bcf1, when the docs both are cut from changed)
        cold-read (fn [] (on :turns (fn [c] [(:seon.model-call/reply (d/pull @c [:seon.model-call/reply] [:seon.model-call/id (seon.lib/call-id "att" 0)]))
                                             (:reads @(:stats (:storage (:store @c))))])))
        stored? (fn [] (on :db #(k/exists? (:store @%) vk {:sync? true})))
        gc! (fn [] (on :db #(datahike.gc/gc-storage! @% (java.util.Date. 0) {:sync? true})))]
    (try
      (on :db #(d/branch! % :db :turns))
      (on :turns (fn [c]
                   (d/transact c {:tx-data (turn-facts 0)})
                   (d/transact c {:tx-data [{:seon.model-call/id (seon.lib/call-id "att" 0) :seon.model-call/reply reply}]})
                   (pdb/sync! cfg)))
      (is (= reply (on :db #(k/get (:store @%) vk nil {:sync? true}))) "the value is stored under its content key")
      (testing "GC keeps a value a branch reaches, and a cold read swaps the reference back for it"
        (gc!)
        (is (stored?))
        (let [[r reads] (cold-read)] (is (= reply r)) (is (pos? reads) "nodes were read from the store")))
      (testing "and sweeps it once no branch does"
        (on :db #(d/delete-branch! % :turns))
        (gc!)
        (is (not (stored?))))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-large-value-holding-ratios-commits-and-reads-back
  ;; A value past :seon.config.store/value-out-of-line-bytes is stored
  ;; under its content key, hasch's uuid of the value (docs/storage.md#values-out-of-line): hasch.platform had no
  ;; encoding for clojure.lang.Ratio, so a large value holding one threw at its commit, and a small one did not (its
  ;; datom is no key). A store on disk of this test's own, the last resort: the in-memory store has no out-of-line
  ;; values; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-ratio-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        v {:value (mapv #(/ % 3) (range 1 3000)) :small (/ 1 3) :kinds [1.5 2N 3M 'a :b "c" \d (java.util.UUID. 0 1) (java.util.Date. 0)]}]
    (try
      (d/transact conn {:tx-data [{:seon.eval/id "ratios" :seon.eval/code "(f)" :seon.eval/response v}]})
      (pdb/sync! cfg)
      (d/release conn)
      (let [c (pdb/connect cfg)]
        (try (is (= [v] (d/q '[:find [?r ...] :where [_ :seon.eval/response ?r]] @c)) "the value reads back equal, ratios and all")
             (finally (d/release c))))
      (finally (seon.lib/remove-tree! path)))))

(deftest hasch-ids-are-the-jvms
  ;; A content key is hasch's uuid of the value (docs/storage.md#values-out-of-line), and reference-code/jolt-overlay's
  ;; hasch.platform is Jolt's copy of hasch's encoding, tuned for Jolt's host calls: every id below was computed by
  ;; hasch 0.4.100 on the JVM, so a change to the copy that changes one byte of a digest is red here
  (let [u #uuid "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        vs [nil true false 0 1 -7 42 3.5 "" "hello" "a\u0001b" :k :ns/k 'sym 'ns/sym u #uuid "00000000-0000-0000-0000-000000000000"
            [] [1 2 3] [u #uuid "6ba7b811-9dad-11d1-80b4-00c04fd430c8"] (vec (for [i (range 64)] (java.util.UUID. (* 7919 i) (* 104729 (inc i)))))
            '(1 :a "b") {} {:a 1 :b [1 2]} #{1 2 3} {:nested {:v [u {:x #{:y}}]}} #inst "2026-11-10T00:00:00.000Z" (apply str (repeat 2000 "x"))]
        jvm ["38244d02-8981-5693-af7b-456af8efa4ca" "1ddffc2c-6730-53c7-87b8-9cbbc98c2363" "36006e3f-9e89-5059-9ceb-6bd5549f1b19" "0b0e9316-7a2b-5336-9cdb-7b1bf5f46758"
             "1b1349bd-d9c2-5f35-a58c-ee0d8495eef1" "25f89a35-a881-5cab-a043-30c864f31246" "0fc20a9a-c153-592d-888a-707ff306fbb6" "013738d3-bda2-533e-9f9c-f5e1d18dd33d"
             "245bf581-7ddf-54aa-af7a-407071f0eedc" "327209f3-0396-5084-acd8-3c576c220223" "290fdec6-0ee8-5054-980a-0194180e0fdd" "16578c9d-cf43-5729-8969-04d3ea45964f"
             "345868c2-6019-54a9-bcb6-cbfdd3e30f0f" "1e03fec1-7877-5030-9a6c-40c4ef2d81cd" "37565de9-2bfa-5f99-b755-e85f044b6cb4" "2ae63b86-cb22-5a0b-9416-c1919bdf5cdf"
             "3a3ed45a-46cd-5357-af3f-b0c12a87982d" "117935a6-8abd-57b5-974f-4eff93989ada" "00bd2dcb-5c15-555e-a1c8-6166a78fc808" "3cf45474-ca87-5c37-8517-35103d3bc363"
             "0d46f929-9dd6-5c1e-bba1-f925cbed46f1" "288078dd-6c67-5916-9b90-e61e242d2847" "3e688838-ca86-56e5-8906-89bf2ab585ce" "194c8971-4e3c-5157-a8f8-0b28d73b856c"
             "01f79275-5eae-546e-97d9-c74e79081428" "397b6583-eceb-5568-aeb9-8c9fa6fd000f" "2c937c35-50a3-5159-91ed-2747306a8b66" "3b4ec618-caca-5161-8602-695fada7ce97"]]
    (is (= jvm (mapv #(str (hasch.core/uuid %)) vs)))))

(deftest gc-runs-through-the-sync-writer
  ;; datahike.api/gc-storage through Seon's :sync writer (seon.db/writer-cfg) answers what it
  ;; swept: the writer applied every op as a transaction and committed its result, so a
  ;; collection, whose result holds no :db-after, failed in commit! ("Parent cannot be nil").
  ;; A store on disk of this test's own, the last resort: the in-memory store has no nodes to
  ;; collect; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-gc-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (d/transact conn [{:note/text "kept" :note/agent :t}])
      (let [r @(d/gc-storage conn (java.util.Date. 0))]
        (is (set? r) (pr-str r))
        (is (= #{"kept"} (notes @conn)) "the branch reads as before"))
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest a-collection-runs-when-a-branch-is-gone
  ;; seon.db/collect! (root's writer runs it, :collect, at quiescence when no agent is live:
  ;; owner, 2026-10-22, simplest first): the first collection of a main records the branches it
  ;; saw and sweeps nothing (every check step boots a new main on one store of hundreds of
  ;; branches); the same branches again: nothing; a branch made
  ;; since is recorded as seen, nothing swept; once a branch named in the record is gone, its nodes and its values out of line are swept and
  ;; every live branch reads as before. A store on disk of this test's own, the last resort:
  ;; the in-memory store has no nodes to collect; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-collect-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        reply (slice @text 11 3000)
        vk (hasch.core/uuid reply)
        on (fn [b f] (let [c (pdb/connect (assoc (:config @conn) :branch b))] (try (f c) (finally (d/release c)))))
        record! (fn [r] (when-not (:skipped r) (d/transact conn [{:seon.system/id :root :seon.system/collected r}])) r)]
    (try
      (d/transact conn [{:note/text "main's" :note/agent :t}])
      ;; garbage before the first collection: a branch made and deleted
      (d/branch! conn :db :early)
      (on :early #(d/transact % {:tx-data [{:seon.model-call/id "att/e" :seon.model-call/reply (slice @text 3 3000)}]}))
      (d/delete-branch! conn :early)
      (let [r1 (record! (pdb/collect! conn true))]
        (is (= #{:db} (:branches r1)) "the first collection records the branches it saw")
        (is (zero? (:swept r1)) "and sweeps nothing: a new main must not hold its first work behind a full sweep"))
      (is (:skipped (pdb/collect! conn true)) "the same branches: nothing to reclaim, nothing written")
      (d/branch! conn :db :gone)
      (on :gone #(d/transact % {:tx-data [{:seon.model-call/id (seon.lib/call-id "att" 0) :seon.model-call/reply reply}]}))
      (let [r (record! (pdb/collect! conn true))]
        (is (= {:swept 0 :gone 0 :branches #{:db :gone}} (select-keys r [:swept :gone :branches]))
            "a branch made since: recorded as seen, nothing swept"))
      (is (k/exists? (:store @conn) vk {:sync? true}))
      (d/delete-branch! conn :gone)
      (let [r2 (record! (pdb/collect! conn true))]
        (is (pos? (:swept r2)) "the gone branch's nodes are swept")
        (is (= 1 (:gone r2)))
        (is (= #{:db} (:branches r2))))
      (is (not (k/exists? (:store @conn) vk {:sync? true})) "and its value out of line")
      (is (= #{"main's"} (notes @conn)) "main reads as before")
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

;; docs/storage.md#collection: a sweep frees pages, and only a compaction makes the file smaller
(deftest a-store-a-sweep-freed-is-compacted-at-its-next-boot
  (let [path (str (seon.config/checkout) "/target/tmp/compact-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        head #(vec (map (juxt :e :a :v :tx) (d/datoms (pdb/head % :db) :eavt)))
        size #(.length (java.io.File. (str path "/data.mdb")))]
    (try
      (is (nil? (pdb/compact-if-due! path)) "nothing swept: no compaction")
      (d/transact conn [{:seon.system/id :root :seon.system/collected (pdb/collect! conn true)}])
      (d/branch! conn :db :gone)
      (let [c (pdb/connect (assoc (:config @conn) :branch :gone))]
        (d/transact c {:tx-data (vec (for [i (range 3000)] {:note/text (str "note " i (apply str (repeat 400 "x"))) :note/agent :t}))})
        (d/release c))
      (d/transact conn [{:seon.system/id :root :seon.system/collected (pdb/collect! conn true)}])
      (d/delete-branch! conn :gone)
      (let [r (pdb/collect! conn true)]
        (is (pos? (:swept r)) "the gone branch swept")
        (d/transact conn [{:seon.system/id :root :seon.system/collected r}]))
      (let [before (head conn) big (size) cfg (:config @conn)]
        (pdb/sync! cfg)
        (d/release conn)
        (let [c (pdb/compact-if-due! path)]
          (is (< (:bytes-after c) (:bytes-before c)) (pr-str c))
          (is (= big (:bytes-before c))))
        (is (< (size) big) "the file is smaller")
        (is (nil? (pdb/compact-if-due! path)) "and it is done once, until another sweep")
        (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
          (is (= before (head conn)) "and main's head reads the same")
          (d/transact conn [{:note/text "after" :note/agent :t}])
          (is (some #(= "after" (:v %)) (d/datoms @conn :aevt :note/text)) "and it takes a write")
          (d/release conn)))
      (finally (seon.lib/remove-tree! path (str path ".compact"))))))

;; docs/storage.md#collection: old snapshots go when enough commits have landed, and nothing a merge or a page reads goes with them
(deftest a-store-keeps-what-is-read-and-sweeps-old-snapshots
  (let [path (str (seon.config/checkout) "/target/tmp/retain-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        note! (fn [c s] (d/transact c [{:note/text s :note/agent :t}]) (d/commit-id @c))
        notes-of (fn [db] (sort (map :v (d/datoms db :aevt :note/text))))
        hist (fn [db] (count (d/datoms (d/history db) :eavt)))]
    (try
      (is (nil? (pdb/retain! conn 3)) "too few commits: none due")
      (note! conn "a")
      (d/branch! conn :db :b)
      (let [b (pdb/connect (assoc (:config @conn) :branch :b))
            _ (note! b "on b")
            base (d/merge-base conn :b :db)
            named (note! conn "named")
            _ (d/transact b [{:seon.agent/key "k" :seon.agent/main-commit named}])
            old (note! conn "old")
            _ (doseq [i (range 5)] (note! conn (str "n" i)))
            _ (d/branch! conn :db :c)
            c (pdb/connect (assoc (:config @conn) :branch :c))
            _ (note! c "on c")
            base-c (d/merge-base conn :c :db)
            _ (doseq [i (range 5)] (note! conn (str "m" i)))
            main-before (notes-of @conn) hist-before (hist @conn) tail (d/commit-id @conn)
            r (pdb/retain! conn 3)]
        (is (pos? (:swept r)) (pr-str r))
        (is (= (:generation r) (:generation (:seon.system/retained (d/pull @conn [:seon.system/retained] [:seon.system/id :root])))) "and it is written on main")
        (is (nil? (pdb/retain! conn 3)) "with nothing new landed, none is due")
        (is (= (conj (vec main-before)) (notes-of @conn)) "main's head reads the same")
        (is (<= hist-before (hist @conn)) "its history too")
        (is (= base (d/merge-base conn :b :db)) "the merge base is found: a branch off a commit past the cutoff")
        (is (= base-c (d/merge-base conn :c :db)) "and so is another's")
        (is (some #(= "n4" %) (notes-of (d/commit-as-db conn base-c))) "and it reads whole")
        (is (= (notes-of (d/commit-as-db conn base)) ["a"]) "and it reads whole")
        (is (seq (notes-of (d/commit-as-db conn named))) "a commit an agent's fact names reads whole")
        (is (nil? (d/commit-as-db conn old)) "an old one no one names is gone")
        (is (= ["a" "on b"] (notes-of @b)) "the branch reads the same")
        (is (= tail (d/commit-id (d/commit-as-db conn tail))) "and the newest commits stay")
        (d/release b) (d/release c))
      (let [cfg (:config @conn)]
        (pdb/sync! cfg) (d/release conn)
        (is (:bytes-after (pdb/compact-if-due! path)) "the sweep left its freed pages to compact"))
      (finally (seon.lib/remove-tree! path (str path ".compact"))))))

;; the rule a collection keeps beside "no agent live" (docs/storage.md#collection)
(deftest a-root-that-shares-its-store-never-collects
  ;; seon.db/collect! with sole? false (:seon.config.store/sole-writer?, false where roots share
  ;; one store: bin/test's shards, bin/check's steps) sweeps nothing, though a branch is gone:
  ;; another process's commit could be reusing a value the sweep would delete (Datahike's
  ;; collection sees only its own process's commits). A store on disk of this test's own, the
  ;; last resort: the in-memory store has no nodes to collect; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-shared-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        reply (slice @text 11 3000)
        vk (hasch.core/uuid reply)]
    (try
      (d/branch! conn :db :gone)
      (let [c (pdb/connect (assoc (:config @conn) :branch :gone))]
        (try (d/transact c {:tx-data [{:seon.model-call/id (seon.lib/call-id "att" 0) :seon.model-call/reply reply}]}) (finally (d/release c))))
      (d/delete-branch! conn :gone)
      (is (= {:skipped true :shared true} (pdb/collect! conn false)) "a shared store: nothing swept, nothing recorded")
      (is (k/exists? (:store @conn) vk {:sync? true}) "the gone branch's value out of line is kept")
      (is (not (:skipped (pdb/collect! conn true))) "while the sole writer's collection of the same store runs")
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest a-commit-costs-what-it-writes
  ;; docs/storage.md#the-proof-what-a-commit-costs. Estimate, from what a commit must
  ;; write: its own datoms (each value twice, in :eavt and :aevt) and the root-to-leaf path
  ;; it touched in each index, whose nodes hold small datoms: at branching factor 64 about 11
  ;; keys of ~1.5-3 KB (a 1-datom commit measured 16.8-27.7 KB at 5k-225k datoms),
  ;; so <= 32 KB per commit above its values. So n turns
  ;; committed one by one (an agent commits each turn) cost what the same turns cost in
  ;; one commit plus n paths. Measured 2026-10-22: a turn with an 8 KB context, committed
  ;; per turn, wrote ~15x the one commit, because every commit rewrites the whole tail leaf
  ;; of :eavt and :aevt (up to 64 contexts) holding the values inline. A store on disk of
  ;; this test's own, the last resort: the in-memory store writes no nodes; removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-commit-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        n 20]
    (try
      (let [once (write-turns! conn cfg path :once n n)
            per-turn (write-turns! conn cfg path :per-turn n 1)
            bound (+ once (* n 32 1024))]
        (println (str "a-commit-costs-what-it-writes: " n " turns, one commit " once " B, per turn " per-turn " B, bound " bound " B (compressor " (cfg/value :seon.config.store/compressor)
                      ", out of line from " (cfg/value :seon.config.store/value-out-of-line-bytes) " B)"))
        (is (<= per-turn bound)
            (str n " per-turn commits wrote " per-turn " bytes; the same turns in one commit wrote " once
                 ", so the bound is " bound " (" (format "%.1f" (/ (double per-turn) bound)) "x over)")))
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest two-branches-diff-by-what-each-wrote
  ;; datoms-diff walks both trees and skips every subtree they share (Jolt's HashSet needs
  ;; retainAll for that), so a branch's own writes are its side of the diff against its fork
  (ts/test-branch!
   (fn [cfg conn]
     (let [fork @conn
           b (keyword (str (name (:branch cfg)) "-b"))
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc [{:note/text "b's 1" :note/agent :t} {:note/text "b's 2" :note/agent :t}])
         (d/transact conn [{:note/text "main's" :note/agent :t}])
         (let [[only-fork only-b] (d/datoms-diff fork @bc :eavt [])
               [only-b' only-main] (d/datoms-diff @bc @conn :eavt [])]
           (is (empty? only-fork) "the branch only added")
           (is (= #{"b's 1" "b's 2"} (set (keep #(when (= :note/text (:a %)) (:v %)) only-b))))
           (is (= #{"b's 1" "b's 2"} (set (keep #(when (= :note/text (:a %)) (:v %)) only-b'))))
           (is (= #{"main's"} (set (keep #(when (= :note/text (:a %)) (:v %)) only-main)))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest a-planned-query-reads-every-version-in-the-history
  ;; the planner's history path opens sorted-set cursors with a java.util.Comparator
  ;; (datahike.query.execute/history-versions!); every version of a replaced value answers
  (ts/test-branch!
   (fn [_ conn]
     (let [e (get-in (d/transact conn [{:db/id "f" :form/id "h/f" :form/src "(defn f [] 0)" :form/ns {:ns/name (quote h)} :form/order 1}]) [:tempids "f"])]
       (d/transact conn [[:db/add e :form/order 2]])
       (d/transact conn [[:db/add e :form/order 3]])
       (is (= #{[1 true] [1 false] [2 true] [2 false] [3 true]}
              (set (d/q '[:find ?o ?added :in $ ?id :where [?f :form/id ?id] [?f :form/order ?o _ ?added]]
                        (d/history @conn) "h/f"))))
       (is (= #{[3]} (d/q '[:find ?o :in $ ?id :where [?f :form/id ?id] [?f :form/order ?o]] @conn "h/f")))))))

(deftest an-identity-names-its-entity-on-every-branch
  ;; a :db/contentId attribute's value is its entity's id (datahike.db.transaction/content-eid):
  ;; two branches minting one identity mint one entity, so their datoms coincide; a ref to the
  ;; tempid made before its map takes it; the identity is never replaced on its entity
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ce"))
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))
           mint (fn [c] (let [r (d/transact c [{:db/id "f" :form/id "app.ce/f" :form/src "(defn f [])" :form/ns "n" :form/order 0 :form/defines ["v"]}
                                               {:db/id "v" :var/name 'app.ce/f :var/form "f"}
                                               {:db/id "n" :ns/name 'app.ce}])]
                          (mapv #(get-in r [:tempids %]) ["f" "v" "n"])))]
       (try
         (let [[fa va na] (mint conn) [fb vb nb] (mint bc)]
           (testing "one identity, one entity id, on both branches"
             (is (= [fa va na] [fb vb nb]))
             (is (= fa (datahike.db.transaction/content-eid :form/id "app.ce/f")))
             (is (<= datahike.constants/ce0 fa datahike.constants/emax)))
           (testing "the ref named the tempid before its map: it holds the content id"
             (is (= va (:db/id (first (:form/defines (d/entity @conn fa)))))))
           (testing "a counter entity is no content entity"
             (is (< (get-in (d/transact conn [{:db/id "x" :note/text "counter" :note/agent :t}]) [:tempids "x"]) datahike.constants/tx0)))
           (testing "an identity is never replaced on its entity: a rename is refused"
             (is (= :entity-id/collision
                    (try (d/transact conn [[:db/add fa :form/id "app.ce/g"]]) nil
                         (catch Exception e (some (comp :error ex-data) (take-while some? (iterate ex-cause e)))))))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest a-nested-map-takes-its-content-id
  ;; docs/storage.md#ids: a map nested under a ref takes its content id as a top-level map does, so an eval's
  ;; result (seon.db/with-response, which names no id) is the entity at its hash's content id on every branch
  (let [db (mt/world)
        e (pdb/with-response {:seon.eval/code "x"} {:value 1})
        h (get-in e [:seon.eval/result :seon.result/hash])
        ce (dbt/content-eid :seon.result/hash h)]
    (is (not (contains? (:seon.eval/result e) :db/id)) "with-response names no id")
    (is (= ce (pdb/eid (d/db-with db [e]) :seon.result/hash h)))
    (is (= (dbt/content-eid :seon.result/hash "h")
           (pdb/eid (d/db-with db [{:seon.eval/code "x" :seon.eval/result {:seon.result/hash "h"}}]) :seon.result/hash "h")))))

(deftest content-ids-cost-the-writes-the-model-says
  ;; content eids scatter a commit's new identities over the indexes (the content-eids pricing study, 2026-10-23, not kept
  ;; §3): nodes written per commit against counter eids, the ratio the model priced (1.3-1.7x at
  ;; 2 new identities, 2.3-5.1x at 10), red when steeper. Measured 2026-10-23 at 110k datoms:
  ;; 0.97x at 1, 1.51x at 2, 4.50x at 10; at 300 entities (3.3k datoms,
  ;; the size now, 91 M ticks against 584 M at 3,000) 1.55x at 2 and 3.08x at 10. A store on disk of this test's own, the last resort:
  ;; the in-memory store writes no nodes to count
  (let [nodes (fn [content?]
                (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
                      cfg (pdb/made-cfg (pdb/store-cfg :lmdb path))
                      _ (d/create-database cfg)
                      conn (pdb/connect (dissoc cfg :keep-history?))
                      ent (fn [k] (into {:w/id (str "entity " k)} (for [i (range 10)] [(keyword "w" (str "a" i)) (str "value " i " of " k)])))]
                  (try
                    (pdb/ids! conn)
                    (d/transact conn (into [(cond-> {:db/ident :w/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                              content? (assoc :db/contentId true))]
                                           (for [i (range 10)] {:db/ident (keyword "w" (str "a" i)) :db/valueType :db.type/string :db/cardinality :db.cardinality/one})))
                    (doseq [part (partition-all 1000 (range 300))] (d/transact conn (mapv ent part)))
                    (let [writes #(:writes @(:stats (-> @conn :store :storage)))]
                      (into {} (for [m [2 10]]
                                 (let [w0 (writes)]
                                   (doseq [c (range 10)] (d/transact conn (mapv #(ent (str "new " m " " c " " %)) (range m))))
                                   [m (/ (- (writes) w0) 10.0)]))))
                    (finally (d/release conn) (seon.lib/remove-tree! path)))))
        counter (nodes false) content (nodes true)
        ratio (fn [m] (/ (content m) (counter m)))]
    (is (< (ratio 2) 2.0) (str "2 new identities: " (ratio 2) "x the counter's nodes"))
    (is (< (ratio 10) 6.0) (str "10 new identities: " (ratio 10) "x the counter's nodes"))
    (is (< (ratio 2) (ratio 10)) "more new identities per commit scatter more")))

(defn plain-datoms
  "d/db-with's arguments for n plain datoms: the world (my.test, in memory: the test store's seed cost 70 M ticks to open in the first test of a process) and n [:db/add e a v] of
  two cardinality-one attributes (a string and a long), two to an entity, every entity new."
  [n]
  [(mt/world) (vec (for [i (range n)]
                       [:db/add (+ 100000000 (quot i 2)) (if (even? i) :form/src :form/order) (if (even? i) (str "(defn f" i ")") i)]))])

(defn identity-datoms
  "d/db-with's arguments for n datoms as n/2 entity maps, each named by an identity (attribute
  a, :db/unique, with or without :db/contentId) and holding one plain value."
  [a n]
  [(mt/world) (vec (for [i (range (quot n 2))] {a (str "proof entity " i) :form/src (str "(defn f" i ")")}))])

(defn form-refs
  "d/db-with's arguments for n datoms of one form's :form/refs (cardinality many, symbols):
  every index comparison past the entity and attribute reaches the value."
  [n]
  [(mt/world) (vec (for [i (range n)] [:db/add 100000000 :form/refs (symbol "app.m0" (str "f" i))]))])

(def content-id-datoms (partial identity-datoms :form/id))
(def unique-datoms (partial identity-datoms :seon.task/id))

(deftest a-datom-costs-its-index-inserts
  ;; The floor, measured on this store's
  ;; seed at 1,000 (the task's plan): its two inserts (:eavt and :aevt, current indexes,
  ;; transient) 5.5k ticks and 7.0 KB; the card-one check, one :eavt descent, 0.95k and 1.4 KB;
  ;; the datom 0.5 KB; its conj onto the tx-data twice 0.55 KB; five copies of the db record
  ;; (indexes, op-count, max-eid, hash) 0.3k and 1.0 KB. Bytes decide: ticks have 3x slack
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/plain-datoms
     :n "plain datoms, each of a new entity's cardinality-one attribute"
     :work "per datom: its insert into :eavt and :aevt, one :eavt descent for the attribute's current value, the datom, its place in the tx-data"
     :growth :linear
     :per-n {:ticks 7000 :bytes 10500}
     :sizes [30 100 300]}))

(deftest a-value-costs-its-index-inserts
  ;; a card-many value's
  ;; comparisons all reach the value, so its floor is a plain datom's (two inserts, no card-one
  ;; descent). Before Jolt's 2-arity == and Datahike's compare-value without a try, 38 KB a
  ;; datom at 1,000 and growing with n (46 KB at 10,000), 17 KB after; the overrun below is what
  ;; stays (a deftype method call's dispatch, task jolt.backend/a-deftype-method-call-takes-a-site-cache)
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/form-refs
     :n "values of one form's cardinality-many :form/refs"
     :work "per value: its insert into :eavt and :aevt, each descent comparing up to the value, the datom, its place in the tx-data"
     :growth :linear
     :per-n {:ticks 7000 :bytes 10500}
     ;; the overrun's :measured is the proof's at 300, its largest size, taken 2026-11-10: 1.63x the ticks and 1.35x the bytes
     ;; of its bound (before, a record at 1,000 of 12.97 M ticks and 17.0 MB, 3.8x what 300 measures, so no regression showed)
     ;; re-taken 2026-11-10 on the sorted set's upstream fixes and geometric leaf growth: 3,373,602 ticks and 3,527,280 B at
     ;; 300 (was 3,416,299 and 4,259,728): 1.61x the bound's ticks, 1.12x its bytes
     :overrun {:task "jolt.backend/a-deftype-method-call-takes-a-site-cache" :n 300 :measured {:ticks 3373602.0 :bytes 3527280.0}}
     :sizes [30 100 300]}))

(deftest an-identity-costs-its-seek
  ;; task datahike.transaction/an-identity-costs-its-seek: an identity adds its :avet insert,
  ;; the unique check's :avet descent and the entity's upsert seek, ~1.5x a plain datom's
  ;; floor; a content id adds hashing [attribute value] once. The overruns below are what
  ;; 1,000 datoms cost when the task was filed: :db/unique 11.5k ticks and 34 KB per datom,
  ;; a content id 16.9k and 65 KB
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/unique-datoms
     :n "datoms, half a :db/unique identity naming its entity map"
     :work "per datom: its inserts (:avet too for the identity), one descent for a card-one value, one :avet seek per identity"
     :growth :linear
     :per-n {:ticks 10000 :bytes 15000}
     ;; the overruns' :measured are the proofs' at 100, their largest size, taken 2026-11-10: :db/unique 1.28x the bytes of its
     ;; bound (8.3k ticks and 19 KB a datom), a content id 2.68x (12.9k ticks and 43 KB)
     :overrun {:task "datahike.transaction/an-identity-costs-its-seek" :n 100 :measured {:ticks 834866.0 :bytes 1916704.0}}
     :sizes [10 30 100]})
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/content-id-datoms
     :n "datoms, half a content-id identity naming its entity map"
     :work "per datom: as a :db/unique identity's, and the identity's [attribute value] hashed once to its entity id"
     :growth :linear
     :per-n {:ticks 11000 :bytes 16000}
     :overrun {:task "datahike.transaction/an-identity-costs-its-seek" :n 100 :measured {:ticks 1289618.0 :bytes 4288032.0}}
     :sizes [10 30 100]}))

(defn newest-evals
  "d/pull's arguments for the newest five of n: an agent and n evals referring to it (each a
  :seon.eval/agent ref), on the test store's seed, and the pattern that reads its newest five,
  [:seon.eval/_agent :limit 5 :reverse true]."
  [n]
  (let [r (d/with (ts/test-db) (into [{:db/id "a" :seon.agent/key "newest"}]
                                     (for [i (range n)] {:seon.eval/id (str "newest-" i) :seon.eval/agent "a"})))]
    [(:db-after r) '[{[:seon.eval/_agent :limit 5 :reverse true] [:seon.eval/id]}] (get (:tempids r) "a")]))

(deftest the-newest-n-cost-what-they-hold
  ;; Datahike 872bfa03: :reverse reads the reverse ref's :avet datoms by rseek-datoms from the
  ;; agent's end and stops after five, so the pull costs its five members and the index's depth,
  ;; whatever the collection holds. Without it a newest-n read realizes every member (the window
  ;; ctx's pattern holds, docs/ctx.md)
  (is (= ["newest-9" "newest-8" "newest-7" "newest-6" "newest-5"]
         (let [[db p e] (newest-evals 10)] (mapv :seon.eval/id (:seon.eval/_agent (d/pull db p e)))))
      "the newest five, newest first")
  (cost/proof
    {:of 'datahike.api/pull :input 'seon.db-test/newest-evals
     :n "evals referring to the agent"
     :work "the pattern compiled; one :avet descent to the agent's last referrer, five datoms read back, five members pulled"
     :growth :constant
     ;; measured: 112, 114 and 122 KB, 23k, 23k and 26k ticks at 10, 1,000 and 100,000 (one more index
     ;; level); 100,000 because flat must hold at a collection an agent's history reaches (root's ask),
     ;; and a prove run (cost-max-n 1,000) builds only the two smaller inputs
     :fixed {:ticks 30000 :bytes 125000}
     :sizes [10 100 1000 100000]}))

(defn held-store
  "[cfg conn] of a fresh in-memory store (the schema and no example program) holding n notes on :db:
  what a branch is made from, whatever it holds."
  [n]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :writer pdb/writer-cfg}
        _ (d/create-database (pdb/made-cfg cfg))
        conn (pdb/connect cfg)]
    (pdb/ids! conn)
    (pdb/install-missing! conn)
    (doseq [part (partition-all 1000 (range n))]
      (d/transact conn {:tx-data (mapv #(hash-map :note/text (str "note " %) :note/agent :t) part) :tx-meta (pdb/tx-meta :db)}))
    [cfg conn]))

(defn branch-round-trip
  "A branch of the store of cfg made, its connection released, and deleted (seon.test-store/test-branch!'s
  own work, whose body is f): the number of branches deleted."
  [cfg conn]
  (let [b (keyword (str "round-trip-" (random-uuid)))
        [c _] (pdb/open-main! cfg b)]
    (d/release c)
    (count (pdb/delete-main! conn b))))

(deftest a-branch-costs-what-it-holds-not-the-store
  ;; a test's branch of the test store (seon.test-store/test-branch!) is one pointer write and one
  ;; delete (docs/storage.md#branches-as-in-production): measured 2026-10-29 at 0.55 M ticks and 1.7 MB
  ;; at 1,350 datoms and 0.63 M and 1.8 MB at 147,569, so the 1.6 G ticks the audit charged
  ;; test-branch! in seon.vector-test are that test's own bodies (a closure is no contracted call: its
  ;; work is its caller's self cost), not the branch
  (is (= 1 (let [[_ conn] (held-store 10)] (branch-round-trip (:config @conn) conn))) "a branch made and deleted")
  (cost/proof
    {:of 'seon.db-test/branch-round-trip :input 'seon.db-test/held-store
     :n "datoms the store holds, on :db"
     :work "one pointer write to branch, one delete of the head key, whatever the branch's index holds"
     :growth :constant
     :fixed {:ticks 1000000 :bytes 2500000}
     :sizes [10 100 1000 10000]}))

(defn- attr-db
  "An in-memory db of eight long attributes and 200 random facts, with :attribute-refs? refs?
  (an attribute is then an entity id, not a keyword): a db of each kind a datom's :a can be."
  [refs? ^java.util.Random rnd]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false :attribute-refs? refs?}
        _ (d/create-database cfg)
        conn (pdb/connect cfg)
        attrs (mapv #(keyword "t" (str "a" %)) (range 8))]
    (d/transact conn (mapv #(hash-map :db/ident % :db/valueType :db.type/long :db/cardinality :db.cardinality/one) attrs))
    (d/transact conn (vec (for [i (range 200)] {:db/id (str "e" (.nextInt rnd 60)) (nth attrs (.nextInt rnd 8)) (long i)})))
    [conn attrs]))

(deftest an-attribute-slice-is-the-same-without-the-try
  ;; datahike.datom/cmp-attr-nil compares two attributes with no try (a db's attributes are one
  ;; type: all keywords, or with :attribute-refs? all entity ids); the try/catch it replaced
  ;; (cmp-nil's safe-compare) cost 417 B a slice comparison. Under both configs, for random datom
  ;; pairs its order is today's (cmp-nil's), and each attribute's :aevt slice is every datom of it
  (doseq [refs? [false true]]
    (let [rnd (java.util.Random. 17)
          [conn attrs] (attr-db refs? rnd)
          db @conn
          all (vec (d/datoms db :aevt))
          ds (vec (filter #(contains? (set (map (fn [a] (if refs? (:db/id (d/entity db a)) a)) attrs)) (:a %)) all))
          sign #(compare (long (or % 0)) 0)]
      (testing (str ":attribute-refs? " refs?)
        (is (< 60 (count ds)) "the facts are there")
        (is (every? true? (for [_ (range 500) :let [x (nth ds (.nextInt rnd (count ds))) y (nth ds (.nextInt rnd (count ds)))]]
                            (= (sign (dd/cmp-attr-nil (:a x) (:a y))) (sign (dd/cmp-nil (:a x) (:a y))))))
            "the order of any two attributes is cmp-nil's")
        (doseq [a attrs :let [aid (if refs? (:db/id (d/entity db a)) a)]]
          (is (= (filterv #(= aid (:a %)) all) (vec (d/datoms db :aevt a))) (str a "'s slice is every datom of it")))
        (d/release conn)))))

(deftest a-slice-comparison-allocates-nothing
  ;; the :aevt slice comparator's attribute step entered a try on every comparison (cmp-nil's
  ;; safe-compare): 417 B a call on Jolt, about 1.9 MB of one seon.program/diff-plan. Bytes are
  ;; deterministic: the least of five runs of 10,000 calls
  (let [d (dd/datom 42 :form/id "x" 536870913)
        from (dd/datom 0 :form/defines nil 0) to (dd/datom Long/MAX_VALUE :form/defines nil Long/MAX_VALUE)
        c (psi/slice-comparator-constructor :aevt from to)
        bytes-now #(jolt.host/scheme-eval-string "(sstats-bytes (statistics))")
        n 10000
        per (fn [f] (f) (/ (double (apply min (for [_ (range 5)] (let [b0 (bytes-now)] (dotimes [_ n] (f)) (- (bytes-now) b0))))) n))
        base (per #(do nil))
        slice (per #(c d from))]
    (is (<= (- slice base) 8.0) (str "a slice comparison allocates " slice " B, an empty call " base " B"))))


(deftest a-fork-keeps-its-parents-node-caches-and-no-connection
  ;; seon.db/after-fork!: a forked agent forgets its parent's connections and LMDB envs, and keeps
  ;; each node cache alone, which its own first connect to that store adopts (measured on a root of
  ;; 908 tasks: a fresh agent's first observe 50M ticks with an empty cache, 21.6M with its parent's;
  ;; its first render no dearer than a later one). The connections map is this process's, so it is
  ;; restored; the envs entry forgotten is a path no store is at
  (ts/test-branch!
    (fn [cfg conn]
      (let [cs datahike.connections/*connections*
            before @cs
            entry (some (fn [[_ e]] (when (:node-cache e) e)) before)]
        (is (some? entry) "the test's own connection holds a node cache")
        (try
          (pdb/after-fork! (assoc-in cfg [:store :path] "/nonexistent/a-fork-keeps-its-parents-node-caches"))
          (let [after @cs]
            (is (not-any? :conn (vals after)) "no connection is kept")
            (is (contains? (into #{} (keep :node-cache) (vals after)) (:node-cache entry))
                "each cache is kept, the same object")
            (is (every? #(and (zero? (:count %)) (:node-store-key %) (:threshold %)) (vals after))
                "held by node store and threshold, as a connect looks one up")
            ;; the first connect to that store reserves it: Datahike's own reservation, run over
            ;; a copy of what the fork kept, so no store is opened here
            (binding [datahike.connections/*connections* (atom after)]
              (let [r (datahike.connections/reserve-connection-opening!
                        [:a-fork-keeps :conn] :a-completion :acquisition (:physical-store-key entry)
                        (:node-store-key entry) (:threshold entry) (fn [] :a-fresh-cache))]
                (is (= :owner (:state r)))
                (is (identical? (:node-cache entry) (:node-cache r)) "the agent's first connect reads through its parent's cache"))))
          (finally (reset! cs before)))))))

(deftest an-idle-store-sets-no-timer
  ;; task seon.db/an-idle-store-sets-no-timer: superv.async's simple-supervisor (Datahike's, a
  ;; top-level def, superv.async/S) re-armed a 10 s stale-exception sweep for the life of the
  ;; process, so loading a store kept a timer pending forever: an idle root woke every 10 s, and a
  ;; process whose every thread waited could never be told nothing would wake it (Jolt's all
  ;; asleep). The sweep is armed while an exception is pending, and only then. Counted as the
  ;; timers pending in this process, before and after
  (let [pending (scm/eval-string "(lambda () (timeout-pending-count))")
        n0 (pending)
        s (sv/simple-supervisor :stale-timeout 60000 :error-fn (fn [_]))
        a (ex-info "tracked" {}) b (ex-info "tracked too" {})]
    (is (= n0 (pending)) "a supervisor with no exception pending sets no timer")
    (sv/-track-exception s a)
    (is (= (inc n0) (pending)) "an exception tracked arms its sweep")
    (sv/-track-exception s b)
    (is (= (inc n0) (pending)) "one sweep, however many are pending")
    (sv/-free-exception s a)
    (sv/-free-exception s b)))

(deftest a-stored-value-is-serialized-once
  ;; task seon.db/a-commit-of-a-few-datoms-writes-what-it-changed: konserve wrote each value through
  ;; ByteArrayOutputStreams (its serializer, then its compressor: each stream's buffer doubling, then copied
  ;; out), six copies or more; konserve.impl.defaults/direct-bytes makes the same bytes once a link. A store
  ;; of this test's own (the write path is the subject), removed at the end
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)
            _ (d/transact conn {:tx-data (vec (for [i (range 200)] {:note/text (str "note " i) :note/agent :t})) :tx-meta (pdb/tx-meta :db)})
            store (:store @conn)
            ser ((:compressor store) (get (:serializers store) (:default-serializer store)))
            stream (fn [v] (let [bos (java.io.ByteArrayOutputStream.)] (konserve.protocols/-serialize ser bos (:write-handlers store) v) (vec (.toByteArray bos))))
            direct (fn [v] (some-> (#'konserve.impl.defaults/direct-bytes ser v) vec))
            ;; the commit's record (the db's storage layout) and the root node of its :eavt index
            record (konserve.core/get store (get-in @conn [:meta :datahike/commit-id]) nil {:sync? true})
            node (datahike.index.interface/-root-node (:eavt @conn))
            values [record node]
            m (fn [f] (let [b (seon.profile/alloc-bytes)] (f) (- (seon.profile/alloc-bytes) b)))]
        (is (= (mapv stream values) (mapv direct values)) "the same bytes as the streams wrote")
        ;; each stream copied the encoded bytes at least twice (the inner stream's write and its toByteArray):
        ;; serialized once saves at least that
        (let [s (m #(doseq [v values] (stream v))) o (m #(doseq [v values] (direct v)))
              encoded (reduce + (map #(alength (konserve.jolt.fasl-store/serialize %)) values))]
          (is (<= (* 2 encoded) (- s o)) (str "serialized once: " o " bytes against the streams' " s ", " encoded " bytes encoded")))
        (d/release conn true))
      (finally (seon.lib/remove-tree! path)))))

(deftest a-store-names-its-encoding-where-konserve-reads-it
  ;; docs/storage.md#compression: seon.db/store-cfg names the serializer and the compressor under :config :encoding,
  ;; the one place konserve reads them (konserve.impl.defaults/normalize-store-config). An older spelling still
  ;; worked and made every open log a deprecation, which a script reading a process's output once took for the
  ;; store's path (bin/check, batch 3). Either backend: no old spelling is handed over, the serializer is ours,
  ;; and the compressor the config names is the store's (the :file backend's config map once replaced it).
  (doseq [backend [:lmdb :file]]
    (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
          seen (atom [])]
      (try
        (with-redefs [konserve.impl.defaults/deprecated! (fn [from _] (swap! seen conj from))]
          (let [{:keys [conn]} (pdb/open! backend path 0 :db)
                store (:store @conn)]
            (is (= [] @seen) (str backend ": no deprecated spelling"))
            (is (= :FaslSerializer (:default-serializer store)) (str backend))
            (is (= (seon.config/value :seon.config.store/compressor) (get-in store [:config :compressor :type])) (str backend))
            (d/release conn true)))
        (finally (seon.lib/remove-tree! path))))))

(deftest a-file-store-reads-what-it-wrote-before-it-compressed
  ;; docs/storage.md#compression: a value's header names its codec, so a store holds values of either kind. A file
  ;; store's config once dropped its compressor (store-cfg's :file branch replaced :config), so file stores made
  ;; before hold uncompressed values: such a store, opened by the config that compresses, reads the old values,
  ;; writes compressed ones beside them, and reads both after another open.
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        cfg-of pdb/store-cfg
        notes (fn [conn] (set (map :v (d/datoms @conn :aevt :note/text))))
        write! (fn [conn from] (d/transact conn {:tx-data (vec (for [i (range from (+ from 50))] {:note/text (str "note " i) :note/agent :t})) :tx-meta (pdb/tx-meta :db)}))
        codec (fn [conn] (get-in (:store @conn) [:config :compressor :type]))
        all (fn [n] (set (map #(str "note " %) (range n))))]
    (try
      (with-redefs [pdb/store-cfg (fn [backend p] (update-in (cfg-of backend p) [:store :config :encoding] dissoc :compressor))]
        (let [{:keys [conn]} (pdb/open! :file path 0 :db)]
          (is (nil? (codec conn)) "made as before: no compressor")
          (write! conn 0)
          (d/release conn true)))
      (let [{:keys [conn]} (pdb/open! :file path 0 :db)]
        (is (= :zstd (codec conn)) "opened by the config that compresses")
        (is (= (all 50) (notes conn)) "the uncompressed values read")
        (write! conn 50)
        (d/release conn true))
      (let [{:keys [conn]} (pdb/open! :file path 0 :db)]
        (is (= (all 100) (notes conn)) "old and new read from one store")
        (d/release conn true))
      (finally (seon.lib/remove-tree! path)))))

(defrecord PlainR [x])

(deftest plain-is-what-the-store-keeps
  ;; docs/storage.md#plain-data: what seon.contract/plain? passes the store keeps and gives back equal, through its
  ;; serializer: a store on disk of its own, reopened, since a db-with serializes nothing
  (let [path (str (seon.config/checkout) "/target/db-test-" (random-uuid))
        samples [nil true 1 10N 1.5 1/2 1.5M \a "s" :k 'n/s (random-uuid) (java.util.Date. 0)
                 {:a [1 #{2} (list 3)]} {[1] {"k" 2}} [] #{} ()]]
    (try
      (is (every? contract/plain? samples) "each is plain")
      (is (every? datahike.schema/plain-data? samples) "and Datahike's own test on a data value passes it")
      (is (every? #(malli.core/validate :seon.db/plain % {:registry contract/registry}) samples) "and the schema says the same")
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (d/transact conn (vec (map-indexed (fn [i v] {:seon.eval/id (str "p" i) :seon.eval/code "(f)" :seon.eval/response {:value v}}) samples)))
        (d/release conn))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)
            back (fn [i] (get-in (d/pull @conn [:seon.eval/response] [:seon.eval/id (str "p" i)]) [:seon.eval/response :value]))]
        (doseq [[i v] (map-indexed vector samples)]
          (is (= v (back i)) (str "kept and given back: " (pr-str v))))
        (d/release conn))
      (finally (seon.lib/remove-tree! path))))
  (testing "what is not plain is refused, a lazy or infinite seq unrealized"
    (let [realized (volatile! false)]
      (doseq [v [(->PlainR 1) {:a (->PlainR 1)} {(->PlainR 1) 1} (sorted-map :a 1) (sorted-set 1) (byte-array 1) inc (Object.) (range) (map inc [1])
                 (lazy-seq (vreset! realized true) [1])]]
        (is (not (contract/plain? v)) (str (type v))))
      (is (false? @realized) "telling does not realize it")))
  (testing "seon.contract/plainly makes any value plain: a record as a key too, which it left as it was"
    (doseq [v [{(->PlainR 1) (->PlainR 2)} [(->PlainR 1)] (sorted-map :a 1) (range 3)]]
      (is (contract/plain? (contract/plainly v)) (str (type v)))))
  (testing "seon.contract/kept is the same walk, within its budget"
    (is (= {:a [1 2]} (contract/kept {:a [1 2]})))
    (is (= (seon.print/code (seon.print/text (sorted-map :a 1) (:part seon.print/budgets))) (contract/kept (sorted-map :a 1))) "a sorted map is its print")
    (is (not= (vec (range 100000)) (contract/kept (vec (range 100000)))) "past the budget, its print")))


;; ---- history: what an entity's attributes have been (docs/storage.md#history) ----

(def ^:private task-e [:seon.task/id "app.calc/total-is-exact"])

(defn- set-outcome [db o] (mt/with db [[:db/add task-e :seon.task/outcome o]]))

(deftest history-reads-a-non-var-entitys-replaced-attribute
  ;; docs/storage.md#history: any entity, a row per datom, oldest first, a retraction before its assertion
  (let [db (-> (mt/world {:history? true}) (set-outcome :running) (set-outcome :ready) (set-outcome :done))
        rows (pdb/history db task-e {:attrs #{:seon.task/outcome}})]
    (is (= [[:running true] [:running false] [:ready true] [:ready false] [:done true]]
           (mapv (juxt :v :added?) rows))
        "three outcomes: each replaced one retracted, in its transaction, before the assertion that replaced it")
    (is (apply <= (map :t rows)) "oldest first (a retraction shares its transaction with the assertion that replaced it)")
    (is (every? #(= #{:a :v :added? :t :at} (set (keys %))) rows) "plain data rows")
    (is (every? #(and (= :seon.task/outcome (:a %)) (inst? (:at %))) rows))
    (is (= (mapv :t rows) (mapv :t (pdb/history db (:db/id (d/entity db task-e)) {:attrs #{:seon.task/outcome}})))
        "an entity id reads as the lookup ref does")))

(deftest history-shows-a-retraction
  (let [db (-> (mt/world {:history? true}) (set-outcome :running) (mt/with [[:db/retract task-e :seon.task/outcome :running]]))
        rows (pdb/history db task-e {:attrs #{:seon.task/outcome}})]
    (is (= [[:running true] [:running false]] (mapv (juxt :v :added?) rows)) "the retraction is a row with :added? false")))

(deftest history-attrs-narrows-and-default-is-all
  (let [db (set-outcome (mt/world {:history? true}) :running)
        all (pdb/history db task-e)
        one (pdb/history db task-e {:attrs #{:seon.task/outcome}})]
    (is (< 1 (count (set (map :a all)))) "no :attrs: every attribute the entity has had")
    (is (= #{:seon.task/outcome} (set (map :a one))) ":attrs narrows to the attributes named")
    (is (= (filter #(= :seon.task/outcome (:a %)) all) one) "and is the same rows")))

(deftest history-refuses-an-unknown-option
  (let [db (mt/world {:history? true})
        said (try (pdb/history db task-e {:attr #{:seon.task/outcome}}) nil (catch Exception e (str (ex-message e) (pr-str (ex-data e)))))]
    (is (some? said) "a misspelt option is refused, never ignored")
    (is (str/includes? (str said) ":attr") (str said))))

(deftest history-of-an-entity-that-does-not-resolve-says-so
  (let [db (mt/world {:history? true})]
    (doseq [e [[:seon.task/id "no/such-task"] 12345]
            :let [a (pdb/history db e)]]
      (is (= :seon.db/unresolved (:seon.error/type a)) (pr-str a))
      (is (= :entity (:kind a)))
      (is (= e (:value a))))))

(deftest history-on-a-store-with-history-off-says-so
  ;; Datahike's d/history throws "history is only allowed on temporal indexed databases" on such a db
  (let [a (pdb/history (mt/world) task-e)]
    (is (= :seon.db/no-history (:seon.error/type a)) (pr-str a))))

(deftest a-deleted-entitys-history-is-still-read
  (let [db (mt/with (mt/world {:history? true}) (mt/without 'app.calc/inc2))
        rows (pdb/history db [:var/name 'app.calc/inc2] {:attrs #{:var/name}})]
    (is (= [true false] (mapv :added? rows)) "its name asserted, then retracted: the lookup ref resolves through history")))

(deftest history-pull-reads-a-ref-as-it-was-at-that-transaction
  (let [w (mt/world {:history? true})
        db1 (mt/with w (mt/code 'app.h "(ns app.h)\n(defn f [x] (+ x 1))\n"))
        old-form (:db/id (:var/form (d/entity db1 [:var/name 'app.h/f])))
        db2 (mt/with db1 (mt/code 'app.h "(ns app.h)\n(defn f [x] (+ x 2))\n"))
        ;; the old form's text changes after the fact: a pull as of now would show this
        db3 (mt/with db2 [{:db/id old-form :form/id (:form/id (d/entity db1 old-form)) :form/ns (:db/id (:form/ns (d/entity db1 old-form))) :form/order (:form/order (d/entity db1 old-form)) :form/src "(defn f [x] :changed-later)"}])
        rows (pdb/history db3 [:var/name 'app.h/f] {:attrs #{:var/form} :pull [:form/src]})
        added (filter :added? rows)]
    (is (= 2 (count added)))
    (is (str/includes? (:form/src (:v (first added))) "(+ x 1)") "the replaced form shows the text it had then")
    (is (str/includes? (:form/src (:v (second added))) "(+ x 2)"))
    (is (= "(defn f [x] :changed-later)" (:form/src (d/pull db3 [:form/src] old-form))) "while now it reads otherwise")
    (is (= 'app.h/f (:v (first (pdb/history db3 [:var/name 'app.h/f] {:attrs #{:var/name} :pull [:form/src]}))))
        "a value that is no ref is not pulled")))

;; ---- at: the db at a named point (docs/storage.md#history) ----

(defn- note-text [db e] (:note/text (d/entity db e)))

(defn- two-writes
  "On a test branch: a note written, then replaced. [conn e report-1 report-2]."
  [conn]
  (let [r1 (d/transact conn {:tx-data [{:db/id "n" :note/text "first" :note/agent :x}]})
        e (get (:tempids r1) "n")
        r2 (d/transact conn {:tx-data [[:db/add e :note/text "second"]]})]
    [conn e r1 r2]))

(deftest at-names-the-db-at-a-point
  (ts/test-branch!
   (fn [cfg conn]
     (let [[_ e r1 r2] (two-writes conn)
           t1 (:max-tx (:db-after r1)) t2 (:max-tx (:db-after r2))
           now @conn
           rows (pdb/history now e {:attrs #{:note/text}})]
       (is (identical? now (pdb/at conn now {})) "no point named: the db it was given")
       (is (= "first" (note-text (pdb/at conn now {:t t1}) e)) "{:t t} is the db as of t")
       (is (= "second" (note-text (pdb/at conn now {:t t2}) e)))
       (is (= (first (map :t rows)) t1) "a t history gave reads the old value")
       (is (= "first" (note-text (pdb/at conn now {:t (:t (first rows))}) e)))
       (is (= "first" (note-text (pdb/at conn now {:commit (d/commit-id (:db-after r1))}) e)) "{:commit} is that commit")
       (is (= "second" (note-text (pdb/at conn now {:branch (:branch cfg)}) e)) "{:branch b} is b's last committed value")
       (is (= "first" (note-text (pdb/at conn now {:branch (:branch cfg) :t t1}) e)) "{:branch b :t t} is b as of t")))))

(deftest at-refuses-a-t-the-branch-never-had
  (ts/test-branch!
   (fn [cfg conn]
     (let [[_ _ r1 _] (two-writes conn)
           t1 (:max-tx (:db-after r1))]
       (ts/test-branch!
        (fn [cfg2 conn2]
          (let [[_ _ s1 _] (two-writes conn2)
                foreign (:max-tx (:db-after s1))
                a (pdb/at conn @conn {:t foreign})]
            (is (not= foreign t1))
            (is (= [:seon.db/unresolved :t foreign] ((juxt :seon.error/type :kind :value) a))
                "another branch's t is not 'around then' on this one")
            (is (= :seon.db/unresolved (:seon.error/type (pdb/at conn @conn {:t (inc (:max-tx @conn))}))) "nor is a t after its last transaction")
            (is (= :seon.db/unresolved (:seon.error/type (pdb/at conn @conn {:branch (:branch cfg) :t foreign}))))
            (is (= :seon.db/unresolved (:seon.error/type (pdb/at conn @conn {:t 0})))))))))))

(deftest at-says-an-unknown-commit-or-branch
  (ts/test-branch!
   (fn [cfg conn]
     (let [c (random-uuid)
           a (pdb/at conn @conn {:commit c})
           b (pdb/at conn @conn {:branch :no-such-branch})]
       (is (= [:seon.db/unresolved :commit c] ((juxt :seon.error/type :kind :value) a)))
       (is (= [:seon.db/unresolved :branch :no-such-branch] ((juxt :seon.error/type :kind :value) b)))))))

(deftest at-refuses-an-unknown-or-contradictory-option
  (ts/test-branch!
   (fn [cfg conn]
     (let [said #(try (pdb/at conn @conn %) nil (catch Exception e (str (ex-message e) (pr-str (ex-data e)))))]
       (is (str/includes? (str (said {:time 1})) ":time") "an unknown option")
       (is (some? (said {:commit (random-uuid) :t 1})) ":commit with :t")
       (is (some? (said {:commit (random-uuid) :branch :db})) ":commit with :branch")))))

(deftest at-on-a-store-with-history-off-says-so
  ;; the base db keeps no history: a t cannot be read, and d/as-of would throw
  (ts/test-branch!
   (fn [cfg conn]
     (let [a (pdb/at conn (mt/world) {:t 536870913})]
       (is (= :seon.db/no-history (:seon.error/type a)) (pr-str a))))))
(deftest a-write-holds-a-value-to-the-schema-its-branch-registered
  ;; docs/storage.md#the-write-checks-every-datom: the check read the binary's forms alone, so an agent's own attribute and a key its branch
  ;; changed were not held to the schema the branch states
  (let [db (ts/test-db)
        changes {:app.shape/size [:int {:min 100}] :seon.task/execution [:enum :held]}
        registry (seon.program/schema-registry changes)
        check #(pdb/invalid-datom db % {:forms changes :registry registry})]
    (is (= :app.shape/size (:attribute (check [{:app.shape/size 3}]))) "a value the branch's own schema refuses")
    (is (nil? (check [{:app.shape/size 500}])) "a value it admits")
    (is (= :seon.task/execution (:attribute (check [{:seon.task/id "t" :seon.task/execution :running}]))) "a shared key is held to the branch's form of it")
    (is (nil? (check [{:seon.task/id "t" :seon.task/execution :held}])))
    (is (nil? (check [{:app.other/thing 1}])) "a key no schema names is still the store's to judge")
    (is (= :seon.task/execution (:attribute (pdb/invalid-datom db [{:seon.task/id "t" :seon.task/execution :no-such}]))) "with no branch's forms, the binary's")))

(deftest a-write-holds-each-entity-it-touches-to-the-schemas-its-keys-select
  ;; docs/storage.md#the-write-checks-every-entity-it-touches: an entity holding an attribute an entity schema owns holds that
  ;; schema's required keys, and a write is refused for what it broke; a boot written alone (a hand edit, 2026-11-08) was
  ;; stored, and the reconciler's read of it failed later, on agent-value's own stricter copy of the agent's shape
  (ts/test-branch!
    (fn [_ conn]
      (let [e (try (d/transact conn [{:seon.agent/key "half" :seon.agent/boot "b"}]) nil
                   (catch Exception x (ex-data x)))]
        (is (= :seon.db/invalid-entity (:seon.error/type e)) (pr-str e))
        (is (= :seon.db/agent-start-fact (:schema e)) "it names the schema that owns what was written")
        (is (= [:seon.agent/started-ms] (:missing e)) "and what the write left out")
        (is (nil? (pdb/eid @conn :seon.agent/key "half")) "nothing of the refused write is stored"))
      (is (map? (d/transact conn [{:seon.agent/key "whole" :seon.agent/boot "b" :seon.agent/started-ms 1}])) "written together, it is stored")
      (testing "the one path a value takes refuses as the writer does"
        (is (thrown? clojure.lang.ExceptionInfo (pdb/with @conn [{:seon.agent/key "half" :seon.agent/boot "b"}]))))
      (testing "it refuses only what the write broke"
        (let [db (:db-after (d/with @conn [{:seon.agent/key "half" :seon.agent/boot "b"}]))]
          (is (nil? (pdb/invalid-entity (d/with db [{:seon.agent/key "whole" :seon.agent/agents [:seon.agent/key "half"]}])))
              "a ref to an incomplete entity is a datom of the referrer only")
          (is (nil? (pdb/invalid-entity (d/with db [{:seon.agent/key "half" :seon.agent/status :idle}])))
              "an entity incomplete before is not refused for a write that broke nothing")
          (is (nil? (pdb/invalid-entity (d/with db [{:seon.agent/key "half" :seon.agent/boot "b2"}])))
              "nor for one that writes again an attribute of the very schema it was incomplete in (the audit of 2026-11-09)")
          (let [plain (:db-after (d/with @conn [{:seon.agent/key "plain"}]))]
            (is (= [:seon.agent/started-ms] (:missing (pdb/invalid-entity (d/with plain [{:seon.agent/key "plain" :seon.agent/boot "b"}]))))
                "an agent a write makes a started one holds what a started agent requires: what the entity held before of another schema counts for nothing"))
          (is (= [:seon.agent/boot] (:missing (pdb/invalid-entity (d/with @conn [[:db/retract [:seon.agent/key "whole"] :seon.agent/boot "b"]]))))
              "retracting a key the entity held and still needs is refused")
          (is (nil? (pdb/invalid-entity (d/with @conn [[:db.fn/retractEntity [:seon.agent/key "whole"]]])))
              "an entity retracted whole has stopped being that thing")))
      (testing "an upsert of an unknown identity is no stub (seon.system's release! of a task id main does not hold)"
        (is (= :seon.task/task-fact (:schema (pdb/invalid-entity (d/with @conn (task/execution-tx ["no/such"] :running))))))))))

(defn written-tasks
  "The arguments of seon.db/invalid-entity for n whole tasks written at once on the world: the transaction's report."
  {:malli/schema [:=> [:cat :int] [:vector :any]]}
  [n]
  [(d/with (mt/world) (vec (for [i (range n)] {:seon.task/id (str "app/t" i) :seon.task/ns 'app :seon.task/name (str "t" i) :seon.task/plan "p"
                                                 :seon.task/execution :held :seon.task/branch (keyword (str "task-app-t" i)) :seon.task/kind :work})))])

(deftest the-entity-check-costs-the-entities-a-write-touches
  ;; docs/storage.md#the-write-checks-every-entity-it-touches: one seek per (touched entity, required key) on the value after,
  ;; and per own attribute on the value before; never an entity's size or the store's (the audit of 2026-11-09: no proof)
  (cost/proof
    {:of 'seon.db/invalid-entity :input 'seon.db-test/written-tasks
     :n "whole task entities one write touches"
     :work "per entity: its datoms grouped by the schema that owns them; a required key the write added is read from its datoms, any other one :eavt seek after (and before, where it is missing)"
     :growth :linear
     ;; 15.7k ticks and 43 KB a task with a seek for every required key; reading the keys a write added from its own datoms
     ;; made a creating write seek nothing (2026-11-09)
     :per-n {:ticks 3500 :bytes 14000}
     :sizes [30 100 300]}))

(deftest a-branchs-own-nodes-follow-its-own-datoms
  ;; docs/sharing.md#what-a-branchs-nodes-are: an index node's address is
  ;; its content's hash, so a branch that takes main's changes by merge names main's nodes for them, and holds as its own
  ;; only the nodes its own datoms are in. With random addresses every node a merge rewrote stayed the branch's own: 66 to
  ;; 81 percent of a long-lived branch's nodes after 100 merges in the lab, against 6 to 9 with content addresses. A store
  ;; on disk of its own, the last resort: a node has an address only once a durable store holds it; removed at the end.
  ;; Its seed is 1000 datoms of the example program: at 550 (87 nodes) one node is 1.1% of the tree, so one more config
  ;; key on main moved a B-tree split and turned it red, 9 of 87 against the tenth (batch 20, 2026-11-10); measured on
  ;; that tree: 5 of 108 own at 1000, 4 of 158 at 2000, the bound five nodes clear, for about 1 s more than 550
  (let [path (str (seon.config/checkout) "/target/db-test-nodes-" (random-uuid))
        {cfg :cfg main :conn} (pdb/open! :lmdb path 1000 :db)]
      (let [b (keyword (str "test-" (random-uuid)))
            _ (d/branch! main :db b)
            br (pdb/connect (assoc cfg :branch b))
            addrs (fn [db] (let [v (volatile! #{})] (org.replikativ.persistent-sorted-set/walk-addresses (:eavt db) (fn [a] (when a (vswap! v conj a)) true)) @v))
            merge! (fn []
                     (let [base (d/commit-as-db br (d/merge-base br b :db))
                           r (seon.merge/delta-three-way base @br @main #{})]
                       (d/merge-db br #{:db} (vec (seon.merge/merge-tx @br base @main (:apply r) #{})))))
            private (fn [] (let [m (addrs @main) o (addrs @br)] [(count (remove m o)) (count o)]))]
        (try
          ;; main grows by a namespace a round, and the branch takes each by merge; then the branch writes one note
          (doseq [i (range 20)]
            (let [nsym (symbol (str "app.c" i))]
              (d/transact main (vec ((mt/code nsym (str "(ns " nsym ")\n(defn f [] " i ")\n")) @main)))
              (merge!)))
          (d/transact br [{:note/agent :t :note/text "the branch's own"}])
          (let [[own total] (private)]
            (is (:crypto-hash? (:config @br)) "the store's nodes are addressed by their content")
            ;; the branch's own nodes are its one write's path and a few leaves, under a tenth of the tree (Sean, 2026-11-10; measured 9 of
            ;; 156 on a seed of 2000, 5 of 85 on this one): persistent-sorted-set is a plain B-tree whose leaf cut depends on insertion order, and seon.merge/merge-tx inserts
            ;; main's datoms in another order than main did, so a branch can own leaves holding only main's datoms (the literal 8 passed
            ;; by luck). Random addresses kept 76 of 157 (48%) after the same 20 merges, red here; docs/sharing.md#what-a-branchs-nodes-are
            (is (<= own (quot total 10)) (str own " of " total " nodes are the branch's own after 20 merges and one write of its own")))
          (finally (d/release br) (d/release main) (seon.lib/remove-tree! path))))))

(deftest a-branchs-own-entity-schema-is-the-one-its-writes-are-held-to
  ;; docs/storage.md#the-write-checks-every-entity-it-touches: the per-datom check read a branch's own schemas and the
  ;; entity check the binary's, so an agent that changed what an entity is was judged by the old definition (the audit of
  ;; 2026-11-09); a write's {:forms :registry} give the entity index for that write too
  (let [db (ts/test-db)
        half [{:seon.agent/key "half" :seon.agent/boot "b"}]
        ;; the branch's agent-start-fact: started-ms optional
        own (clojure.walk/postwalk #(if (and (vector? %) (= :seon.agent/started-ms (first %)) (= 2 (count %)))
                                      [:seon.agent/started-ms {:optional true} (second %)] %)
                                   (get seon.contract/forms :seon.db/agent-start-fact))]
    (is (not= own (get seon.contract/forms :seon.db/agent-start-fact)) "the branch's form differs")
    (is (thrown? clojure.lang.ExceptionInfo (pdb/with db half)) "the binary's definition refuses a boot with no start time")
    (is (map? (pdb/with db half {:forms {:seon.db/agent-start-fact own} :registry nil})) "the branch's own accepts it")
    (is (thrown? clojure.lang.ExceptionInfo (pdb/with db half {:forms {:app.m0/note [:string]} :registry nil}))
        "a branch whose changes name no entity schema is held to the binary's")))

(deftest an-entity-named-by-a-ref-ends-with-its-target
  ;; docs/storage.md#the-write-checks-every-entity-it-touches: a vector's identity is a ref to the entity it embeds
  ;; (:seon.vector/of), so retracting that entity used to drop the ref and leave the rest of the vector behind, an
  ;; orphan swept a round later; the one path ends it with its target, so no writer retracts it by hand
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn [{:form/id "f/1" :form/src "(defn f [])" :form/ns [:ns/name 'app.m0] :form/order 0}])
      (d/transact conn [(seon.vector/fact [:form/id "f/1"] "1" [0.5 0.25 0.125] "m")])
      (let [ve (pdb/eid @conn :seon.vector/of (pdb/eid @conn :form/id "f/1"))]
        (is (some? ve) "the form has its vector")
        (is (map? (d/transact conn [[:db.fn/retractEntity [:form/id "f/1"]]])) "retracting the form is not refused")
        (is (empty? (d/datoms @conn :eavt ve)) "and its vector is gone with it, nothing of it left")))))

(deftest each-attribute-is-named-by-one-entity-schema
  ;; docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together: what an entity is comes from the attributes it holds
  ;; (seon.db/entity-schemas), so an attribute two schemas name has two definitions and one of them decides by accident
  ;; (2026-11-08: the task's and root's schemas listed config keys, so root's config read as a task missing its keys)
  (let [named (for [[k f] seon.contract/forms :when (:seon.db/attributes (seon.contract/properties k)) a (pdb/own-keys f)] [a k])
        twice (into (sorted-map) (keep (fn [[a ks]] (when (next ks) [a (mapv second ks)]))) (group-by first named))]
    (is (= {} twice) "each attribute's one entity schema")))

(deftest an-agents-own-entity-schema-is-recognized-on-its-branch
  ;; docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together: what an entity is is read in the index its reader takes, so a
  ;; schema an agent registered on its branch is recognized by its branch's index and unknown to the binary's (2026-11-09: the
  ;; renders saw a branch's schemas through a dynamic binding, recognition through the binary's registry)
  (let [forms {:lab.idx/name [:and {:seon.db/identity true} :string]
               :lab.idx/thing [:map {:seon.db/attributes true} [:lab.idx/name {:optional true}]]}
        e {:lab.idx/name "x"}]
    (is (nil? (pdb/most-specific (pdb/index) e)) "the binary's index does not know the branch's schema")
    (is (= :lab.idx/thing (pdb/most-specific (pdb/index-of {:forms forms}) e)) "the branch's index names it")
    (is (= [:lab.idx/name :db/id] (pdb/attributes-of (pdb/index-of {:forms (assoc forms :lab.idx/sub [:and :lab.idx/thing [:map [:db/id :int]]])}) :lab.idx/sub))
        "and what a schema extends is read in the same index")
    (is (identical? (pdb/index) (pdb/index-of nil)) "a branch that changed nothing holds the binary's")))

(deftest a-datom-its-attribute-refuses-is-invalid-and-names-it
  ;; docs/storage.md#the-write-checks-every-datom: the store checks a value's type, and a data attribute's only that it is
  ;; plain, so a declared schema was not enforced; the write holds each value to its attribute's schema
  (let [db (ts/test-db)
        e (pdb/invalid-datom db [{:seon.eval/id "x" :seon.eval/response {:handle "not a symbol"}}])]
    (is (= :seon.db/invalid-datom (:seon.error/type e)) (pr-str e))
    (is (= :seon.eval/response (:attribute e)) "it names the attribute")
    (is (some #(= [:handle] (:in %)) (:seon.error/problems e)) (str "and where in the value: " (pr-str (:seon.error/problems e))))
    (testing "every way a transaction holds a value"
      (is (= :seon.task/execution (:attribute (pdb/invalid-datom db [{:seon.task/id "t" :seon.task/execution :no-such}]))) "a scalar's narrower schema")
      (is (= :seon.task/execution (:attribute (pdb/invalid-datom db [[:db/add "t" :seon.task/execution :no-such]]))) ":db/add")
      (is (= :seon.model-call/sent-hash (:attribute (pdb/invalid-datom db [{:seon.eval/id "x" :seon.eval/call {:seon.model-call/id "c" :seon.model-call/sent-hash "short"}}]))) "a nested entity"))
    (testing "what it does not ask"
      (is (nil? (pdb/invalid-datom db [{:seon.eval/id "x" :seon.eval/response {:handle 'result/e1 :value 1}}])) "a valid value")
      (is (nil? (pdb/invalid-datom db [{:seon.eval/id "x" :seon.eval/call [:seon.model-call/id "c"]} [:db/retract 1 :seon.task/execution :held] [:db.fn/retractAttribute 1 :seon.task/execution]])) "a ref's lookup, a retraction, a function")
      (is (nil? (pdb/invalid-datom db [{:lab.own/thing (Object.)}])) "an attribute no schema declares: the store's to judge"))))


(deftest the-datom-check-costs-a-validator-call-a-datom
  ;; docs/storage.md#the-write-checks-every-datom: each datom's value is asked of its attribute's schema, whose validator is
  ;; found once a transaction from the schema the registry in effect holds (seon.contract/held). The check made a new
  ;; reference to the schema for every datom ((m/schema a) of a name) and so a new validator for every value: 1,105 ticks
  ;; and 5.3 KB a datom on every write through a connection (measured 2026-11-10, 1,000 plain datoms: 1.105 M ticks, 5.33 MB)
  (cost/proof
    {:of 'seon.db/invalid-datom :input 'seon.db-test/plain-datoms
     :n "datoms of two declared attributes, every one valid"
     :work "per datom: its attribute's schema entry, the validator already found, one call of it"
     :growth :linear
     ;; measured 2026-11-10: 7.1k / 19.6k / 63.2k ticks and 12.2 / 31.4 / 98.6 KB at 100 / 300 / 1,000: 63 ticks and 96 B a datom
     :per-n {:ticks 80 :bytes 120}
     :fixed {:ticks 2000 :bytes 4000 :calls 4}
     :sizes [100 300 1000]}))

(deftest a-datom-is-held-to-the-schema-in-effect-at-its-write
  ;; docs/storage.md#the-write-checks-every-datom: nothing of the check is kept from one write to the next, so a branch that
  ;; changes what an attribute's schema accepts is held to the new one at once (red if a validator were kept by the name)
  (let [db (ts/test-db)
        own (fn [form] {:forms {:app.dc/n form} :registry (contract/registry-of (assoc contract/forms :app.dc/n form))})
        tx [[:db/add 1 :app.dc/n "seven"]]]
    (is (= :seon.db/invalid-datom (:seon.error/type (pdb/invalid-datom db tx (own :int)))) "an int refuses the string")
    (is (nil? (pdb/invalid-datom db tx (own :string))) "and the same name, now a string, accepts it")
    (is (= :seon.db/invalid-datom (:seon.error/type (pdb/invalid-datom db tx (own :int)))) "and is refused again when it is an int again")))

(defn preloaded
  "preload!'s argument: the world with n plain datoms added."
  [n]
  (let [[db tx] (plain-datoms n)] [(d/db-with db tx)]))

(deftest preload-counts-each-index-leaf-by-leaf
  ;; docs/machine.md#roots-heap: the fork server reads every node of main's indexes once so its agents inherit them
  ;; decoded. The read is each index's own reduce (a leaf's datoms stepped over, nothing made), and it answers what the
  ;; three indexes hold. Counting (d/datoms db index) made a lazy cell a datom: 1.2 s of a root's first fork
  (let [[db tx] (plain-datoms 300)
        db (d/db-with db tx)]
    (is (= (+ (count (d/datoms db :aevt)) (count (d/datoms db :avet)) (count (d/datoms db :eavt))) (pdb/preload! db))
        "the datoms of the three indexes"))
  (cost/proof
    {:of 'seon.db/preload! :input 'seon.db-test/preloaded
     :n "datoms added to the world, each in two or three indexes"
     :work "per datom per index: one step of the sorted set's reduce"
     :growth :linear
     ;; measured 2026-11-10 (seon.cost/measure, the world + n datoms, nodes in memory): 7,579 ticks and 4.5 KB at 100,
     ;; 23,683 and 14.0 KB at 1,000: 18 ticks and 10 B a datom added. Counting (d/datoms db index) three times on the same
     ;; dbs: 65,399 ticks and 387 KB at 100, 205,556 ticks and 1.23 MB at 1,000 (78 ticks and 470 B a datom in an index)
     ;; On Seon's own program db, read from its store in a fresh process (609,913 datoms in the three indexes, measured the
     ;; same day, one run each): 504 M ticks and 1,836 ms counting d/datoms, 462 M ticks and 1,774 ms by this reduce: 8 percent.
     ;; The rest is not the walk: it is decoding the leaves, about 750 ticks a datom, paid again on a second pass in that
     ;; process (495 M ticks), so its node cache did not hold them. This proof bounds the walk; the decode is another row
     :per-n {:ticks 22 :bytes 13}
     :fixed {:ticks 7000 :bytes 4500 :calls 1}
     :sizes [100 300 1000]}))

;; docs/storage.md#the-write-checks-every-entity-it-touches (the owner, 2026-11-11: "We should never record two transaction
;; times that are the same"; Point's ruling, option A): the store stamps a transaction's time, after its branch's last, so the
;; one write path refuses a supplied :db/txInstant, in tx-data (a speculation through seon.db/with) or in tx-meta (a
;; connection's transact). Red before: both were written, the second back-dated before the branch's last
(deftest a-transaction-never-supplies-its-own-time
  (let [past (java.util.Date. 0)
        refused (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :db/txInstant (:attribute (refused #(pdb/with (ts/test-db) [{:seon.agent/key "t1"} [:db/add :db/current-tx :db/txInstant past]]))))
        "in tx-data, through the one path a speculation takes")
    (is (= :db/txInstant (:attribute (refused #(pdb/with (ts/test-db) [{:seon.agent/key "t2"} {:db/id :db/current-tx :db/txInstant past}]))))
        "as a map on the current transaction")
    (ts/test-branch! (fn [_ conn]
      (is (= :db/txInstant (:attribute (refused #(d/transact conn {:tx-data [{:seon.agent/key "t3"}] :tx-meta {:db/txInstant past}}))))
          "in tx-meta, through a connection's checked transact")
      (is (some? (:db-after (d/transact conn {:tx-data [{:seon.agent/key "t4"}]}))) "and a write that supplies none is the store's to stamp")))))
