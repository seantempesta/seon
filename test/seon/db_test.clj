(ns seon.db-test
  "One store, many branches, as in production (owner, 2026-10-21): bin/test seeds one test
  store and each test takes a branch of it through one helper (seon.test-store/test-branch!)."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [clojure.java.io] [datahike.api :as d] [datahike.writer :as dw] [datahike.db.transaction :as dbt]
            [seon.db :as pdb] [seon.config :as cfg] [seon.contract :as contract] [seon.edn]
            [konserve.jolt.lmdb :as klmdb] [konserve.jolt.lmdb-ffi :as lmdb] [konserve.jolt.lz4] [konserve.jolt.zstd] [clojure.string :as str] [konserve.core :as k] [hasch.core] [datahike.gc]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest eid-is-the-entity-of-a-unique-value
  (let [db (ts/test-db)]
    (testing "a unique attribute's value names its entity: the one a query finds"
      (is (= (d/q '[:find ?e . :where [?e :var/name app.m0/f1]] db) (pdb/eid db :var/name 'app.m0/f1)))
      (is (= 'app.m0/f1 (:var/name (d/entity db (pdb/eid db :var/name 'app.m0/f1))))))
    (testing "a value no entity holds is nil, not an id"
      (is (nil? (pdb/eid db :var/name 'app.m0/no-such)))
      (is (nil? (pdb/eid db :seon.root/id :root))))
    (testing "root's facts pulled by lookup ref are nil before root's first boot: no eid guard is needed"
      (is (nil? (d/pull db [:seon.root/boot] [:seon.root/id :root]))))))

(deftest a-test-takes-a-branch-of-the-test-store
  (ts/test-branch! (fn [cfg-a a] (ts/test-branch! (fn [cfg-b b] (let [seed (d/connect (dissoc cfg-a :branch))]
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
      (d/transact a [{:note/text "a's"}])
      (d/transact b [{:note/text "b's"}])
      (is (= #{"a's"} (notes @a)))
      (is (= #{"b's"} (notes @b)))
      (is (= #{} (notes @seed))))
    (testing "the five fresh-store copies went with the memory config"
      (is (nil? (resolve 'seon.db/create!)))
      (is (nil? (resolve 'seon.db/mem-cfg))))
    (d/release seed)))))))

(deftest minting-past-the-lease-is-refused-not-shared
  ;; this process takes a lease on a test branch as a forked agent does (seon.db/after-fork!
  ;; -> lease!), mints all but two of its ids, then asks for five more
  (ts/test-branch! (fn [c conn] (let [sid (get-in c [:store :id])
        lease (pdb/lease-block! @conn)
        ceiling (+ lease (cfg/value :seon.config.store/eid-lease))
        notes (fn [n tag] (vec (for [i (range n)] {:note/text (str tag i)})))]
    (try
      (pdb/lease! c lease)
      (let [r1 (dw/apply! conn {:tx-data (notes (- (cfg/value :seon.config.store/eid-lease) 2) "a")})
            before (:db-after r1)
            r2 (dw/apply! conn {:tx-data (notes 5 "b")})
            e (pdb/lease-spent r2 lease)]
        (testing "the lease mints up to its end"
          (is (map? r1))
          (is (= (- ceiling 2) (:max-eid before)))
          (is (= {:from lease :used (- (cfg/value :seon.config.store/eid-lease) 2) :of (cfg/value :seon.config.store/eid-lease)} (pdb/lease-use before lease))))
        (testing "past it the transaction is refused with the typed error, nothing applied"
          (is (not (map? r2)))
          (is (= :seon.db/lease-spent (:seon.error/type e)))
          (is (contract/valid? :seon.db/lease-spent e))
          (is (= {:lease lease :ceiling ceiling} (select-keys e [:lease :ceiling])))
          (is (string? (:seon.error/fix e)))
          (is (nil? (pdb/lease-spent r2 nil)) "a process without a lease (root) is never refused this way"))
        (testing "the next lease's ids were never minted"
          (let [run-ahead (:db-after (dw/apply! conn {:tx-data []}))]
            (is (= (- ceiling 2) (:max-eid run-ahead)))
            (is (empty? (d/datoms run-ahead :eavt ceiling))))))
      (finally (dbt/set-eid-ceiling! sid nil)))))))

(deftest the-store-reports-its-map-use
  ;; a store on disk of this test's own, the last resort: what is proved is how an LMDB
  ;; store reports its map; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (let [{:keys [used-bytes map-bytes]} (pdb/map-use cfg)]
        (is (= (cfg/value :seon.config.store/lmdb-map-bytes) map-bytes))
        (is (< 0 used-bytes map-bytes))
        (d/transact conn [{:note/text (apply str (repeat 100000 "x"))}])
        (pdb/sync! cfg)
        (is (< used-bytes (:used-bytes (pdb/map-use cfg))) "it grows as the store does"))
      (is (nil? (pdb/map-use (ts/test-branch! (fn [c _] c)))) "an in-memory store has no map")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-lease-mints-in-its-own-block-after-a-merge
  ;; main merged into the branch brings entities minted in a later agent's block (ids past
  ;; this lease's ceiling): this process still mints inside its own block, never above them
  (ts/test-branch! (fn [c conn] (let [sid (get-in c [:store :id])
        lease (pdb/lease-block! @conn)
        ceiling (+ lease (cfg/value :seon.config.store/eid-lease))
        theirs (+ ceiling 10)
        new-id (fn [r] (d/q '[:find ?e . :in $ ?t :where [?e :note/text ?t]] (:db-after r) "mine"))]
    (try
      (pdb/lease! c lease)
      (let [r0 (dw/apply! conn {:tx-data [[:db/add theirs :note/text "theirs"]]})
            r1 (dw/apply! conn {:tx-data [{:note/text "mine"}]})]
        (is (map? r0))
        (is (<= theirs (:max-eid (:db-after r0))) "the branch holds the later block's entity")
        (is (map? r1) "minting goes on")
        (is (< lease (new-id r1) ceiling) "inside its own block"))
      (finally (dbt/set-eid-ceiling! sid nil)))))))

(deftest two-processes-branch-one-store
  ;; two processes at once each make 50 branches of one store (two roots on one store, as
  ;; parallel check steps or a standing root beside a run): every branch is listed. A store on
  ;; disk of this test's own, the last resort: the race is between processes; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-race-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 0 :db)))
        jolt (str (System/getProperty "user.dir") "/target/jolt/jolt")
        code (fn [tag] (pr-str `(do (require 'seon.db 'datahike.api)
                                    (let [c# (:conn (seon.db/open! :lmdb ~path 0 :db))]
                                      (dotimes [i# 50] (datahike.api/branch! c# :db (keyword (str ~tag "-" i#))))
                                      (System/exit 0)))))
        cmd (str "'" jolt "' -A:test -e " (pr-str (code "x")) " > /dev/null 2>&1 & "
                 "'" jolt "' -A:test -e " (pr-str (code "y")) " > /dev/null 2>&1 & wait")
        r (clojure.java.shell/sh "nice" "-n" "10" "zsh" "-c" cmd)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        made (for [t ["x" "y"] i (range 50)] (keyword (str t "-" i)))]
    (try
      (is (zero? (:exit r)))
      (is (= 100 (count (filter #(pdb/branch? conn %) made))) "every branch's head was written")
      (is (= (set made) (disj (d/branches conn) :db)) "and every branch is listed")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest opening-a-kept-store-installs-the-attributes-it-lacks
  ;; a store on disk of this test's own, the last resort: what is proved is what a root's
  ;; open does to a kept store seeded before an attribute existed; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        lacking (:db/ident (last pdb/schema))
        _ (d/create-database cfg)
        c0 (d/connect cfg)
        _ (d/transact c0 {:tx-data (vec (butlast pdb/schema))})
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (= lacking (:db/ident (get (d/schema @conn) lacking))) "the attribute the store lacked is installed")
      (let [t (:max-tx @conn)
            _ (d/release conn)
            {c2 :conn} (pdb/open! :lmdb path 0 :db)]
        (is (= t (:max-tx @c2)) "an open that finds nothing missing writes nothing")
        (d/release c2))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-kept-store-from-before-the-runtime-attribute-opens
  ;; the transaction that installs :seon.tx/runtime cannot carry it in its tx-meta: a kept
  ;; store made before the attribute existed (every store before 2026-10-22) failed to open
  ;; (bin/check seed: "Bad entity attribute :seon.tx/runtime"). A store on disk of this test's
  ;; own, the last resort: what is proved is a root's open of a kept store; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-runtime-" (random-uuid))
        made (pdb/made-cfg (pdb/store-cfg :lmdb path))]
    (try
      (d/create-database made)
      (let [c0 (d/connect made)] (d/transact c0 {:tx-data (vec (remove #(= :seon.tx/runtime (:db/ident %)) pdb/schema))}) (d/release c0))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (= :seon.tx/runtime (:db/ident (get (d/schema @conn) :seon.tx/runtime))) "the attribute is installed")
        (let [r (d/transact conn {:tx-data [{:note/text "after"}] :tx-meta (pdb/tx-meta)})]
          (is (string? (:seon.tx/runtime (d/entity @conn (:db/current-tx (:tempids r))))) "and the next transaction names its runtime"))
        (d/release conn))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-moved-store-opens
  ;; task seon.db/a-store-opens-where-it-lies: a store was named by (hasch/uuid path) of the
  ;; path it was given (seon.db/store-cfg), so a store renamed or copied (kept runs:
  ;; next/runs/store-full-20261021, renamed from next/runs/store) was refused at its new path
  ;; with "Store identity mismatch". Now it is named by the id it records. A store on disk of
  ;; this test's own, the last resort: what is proved is a move and a copy; removed at the end
  (let [base (str (System/getProperty "user.dir") "/target/db-test-move-" (random-uuid))
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
                             (is (= path (get-in @c [:config :store :path])) "and so does its db (task seon.db/deref-keeps-the-connections-config)")
                             (is (seq (d/datoms @c :eavt)) "and it holds its data")
                             (d/release c))))]
    (try
      (opens moved)
      (opens copied)
      (finally (clojure.java.shell/sh "rm" "-rf" base moved copied)))))

(deftest a-kept-store-whose-types-differ-is-refused
  ;; an attribute's value type is fixed once installed (Datahike refuses to alter it), so a
  ;; kept store holding an attribute as another type than the schema declares (the EDN-text
  ;; attributes before they became plain data, 2026-10-22) is refused at open, naming each such
  ;; attribute: never opened to fail at its first write. A store on disk of this test's own,
  ;; the last resort: what is proved is a root's open of a kept store; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-types-" (random-uuid))
        made (pdb/made-cfg (pdb/store-cfg :lmdb path))
        old (mapv #(if (= :seon.turn/basis (:db/ident %)) (assoc % :db/valueType :db.type/string) %) pdb/schema)]
    (try
      (d/create-database made)
      (let [c0 (d/connect made)] (d/transact c0 {:tx-data old}) (d/release c0))
      (let [r (try (pdb/open! :lmdb path 0 :db) (catch Exception e (ex-data e)))]
        (is (= :seon.db/types-differ (:seon.error/type r)) (pr-str r))
        (is (= {:seon.turn/basis {:stored :db.type/string :declared :db.type/data}} (:seon.db/attributes r))))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

;; ---- history (task seon.db/history-is-kept: v1's ruling 23, Datahike's default) --------------

(deftest the-store-keeps-history
  ;; with :seon.config.store/keep-history? (default true) a store keeps its temporal indexes:
  ;; a retraction stays visible in d/history, and a transaction keeps its :db/txInstant
  ;; (Datahike applies a transaction's tx-meta only with history: off, it was silently dropped)
  (ts/test-branch!
   (fn [_ conn]
     (let [{tx :tempids} (d/transact conn [{:db/id "n" :note/text "kept"}])
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
    (let [path (str (System/getProperty "user.dir") "/target/db-test-history-" (random-uuid))
          made (assoc (pdb/store-cfg :lmdb path) :keep-history? kept?)]
      (try
        (d/create-database made)
        (let [c0 (d/connect made)] (d/transact c0 {:tx-data pdb/schema}) (d/release c0))
        (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
          (is (= kept? (:keep-history? (:config @conn))) (str "a store made with :keep-history? " kept? " opens keeping it"))
          (d/release conn))
        (finally (clojure.java.shell/sh "rm" "-rf" path))))))

(defn- stored-bytes
  "Value bytes of every key in the LMDB store at path, by key."
  [path]
  (let [db (klmdb/open-env! path nil)]
    (lmdb/read-txn db (fn [txn] (into {} (for [k (lmdb/all-keys db txn)] [k (alength ^bytes (lmdb/get-bytes db txn k))]))))))

(defn- corpus
  "The text of the checkout's files under dir ending in ext, sorted by path and joined: real
  prose (docs/*.md) or real code (src/**/*.clj) for turn text that compresses as a turn's does."
  [dir ext]
  (->> (file-seq (clojure.java.io/file dir)) (map str) (filter #(clojure.string/ends-with? % ext)) sort
       (map slurp) (apply str)))

(def ^:private prose (delay (corpus "docs" ".md")))
(def ^:private code (delay (corpus "src" ".clj")))

(defn- slice
  "n characters of text from k*n on, wrapping at its end: turn k's own text."
  [text k n]
  (let [i (mod (* k n) (- (count text) n))] (subs text i (+ i n))))

(defn- turn-facts
  "Turn k of an attempt as seon.turn/act! records it: the basis its context was built from
  (the commit, its red assertion; the text is made again from it, seon.context/render), its
  reply (1,500 bytes) and its eval call with what it evaluated to as the next turn shows it
  (2,000). The reply is real prose and the evaluated value real source, each turn its own
  (a real turn's reply is the model's prose and code; its evals are mostly source and
  printed data: a slurp, a source, a test's report): a repeated letter compressed ~3x better
  than real turn text and made LZ4 look like the fix (2026-10-22)."
  [k]
  [{:seon.turn/id (str "att/" k) :seon.turn/attempt "att" :seon.turn/n k :seon.turn/origin :model
    :seon.turn/basis {:commit (random-uuid) :task "t/x" :attempt "att" :turn k :max-turns 8 :origin :model
                                       :observed {:results {'t-test/x :fail} :run-errors [] :refusals [{:refusal :proof-red :symbol 't-test/x}]
                                                  :reds [{:test 't-test/x :type :fail :expected "(= 6 (f 2))" :actual "(not (= 6 4))"}]}}
    :seon.turn/reply (slice @prose k 1500)
    :seon.turn/calls [{:id "call-0" :code "(f 2)" :evals (slice @code k 2000)}]}])

(defn- write-turns!
  "Bytes the store at path gains when branch b gets n turns through the agent's run-ahead
  writer (datahike.writer/apply!), committed every `every` turns."
  [conn cfg path b n every]
  (d/branch! conn :db b)
  (let [c (d/connect (assoc cfg :branch b))
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
  (let [text (.getBytes ^String (slice @prose 0 65536) "UTF-8")]
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
  (let [path (str (System/getProperty "user.dir") "/target/db-test-ool-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        _ (d/release conn)
        reply (slice @prose 7 3000)
        vk (hasch.core/uuid reply)
        on (fn [b f] (let [c (d/connect (assoc cfg :branch b))] (try (f c) (finally (d/release c)))))
        cold-read (fn [] (on :turns (fn [c] [(d/q '[:find ?r . :where [_ :seon.turn/reply ?r]] @c)
                                             (:reads @(:stats (:storage (:store @c))))])))
        stored? (fn [] (on :db #(k/exists? (:store @%) vk {:sync? true})))
        gc! (fn [] (on :db #(datahike.gc/gc-storage! @% (java.util.Date. 0) {:sync? true})))]
    (try
      (on :db #(d/branch! % :db :turns))
      (on :turns (fn [c]
                   (d/transact c {:tx-data (turn-facts 0)})
                   (d/transact c {:tx-data [{:seon.turn/id "att/0" :seon.turn/reply reply}]})
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
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest gc-runs-through-the-sync-writer
  ;; datahike.api/gc-storage through Seon's :sync writer (seon.db/writer-cfg) answers what it
  ;; swept: the writer applied every op as a transaction and committed its result, so a
  ;; collection, whose result holds no :db-after, failed in commit! ("Parent cannot be nil").
  ;; A store on disk of this test's own, the last resort: the in-memory store has no nodes to
  ;; collect; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-gc-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (d/transact conn [{:note/text "kept"}])
      (let [r @(d/gc-storage conn (java.util.Date. 0))]
        (is (set? r) (pr-str r))
        (is (= #{"kept"} (notes @conn)) "the branch reads as before"))
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-collection-runs-when-a-branch-is-gone
  ;; seon.db/collect! (root's writer runs it, :collect, at quiescence when no agent is live:
  ;; owner, 2026-10-22, simplest first): the first collection of a main records the branches it
  ;; saw and sweeps nothing (every check step boots a new main on one store of hundreds of
  ;; branches); the same branches again: nothing; a branch made
  ;; since is recorded as seen, nothing swept; once a branch named in the record is gone, its nodes and its values out of line are swept and
  ;; every live branch reads as before. A store on disk of this test's own, the last resort:
  ;; the in-memory store has no nodes to collect; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-collect-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        reply (slice @prose 11 3000)
        vk (hasch.core/uuid reply)
        on (fn [b f] (let [c (d/connect (assoc (:config @conn) :branch b))] (try (f c) (finally (d/release c)))))
        record! (fn [r] (when-not (:skipped r) (d/transact conn [{:seon.root/id :root :seon.root/collected r}])) r)]
    (try
      (d/transact conn [{:note/text "main's"}])
      ;; garbage before the first collection: a branch made and deleted
      (d/branch! conn :db :early)
      (on :early #(d/transact % {:tx-data [{:seon.turn/id "att/e" :seon.turn/reply (slice @prose 3 3000)}]}))
      (d/delete-branch! conn :early)
      (let [r1 (record! (pdb/collect! conn))]
        (is (= #{:db} (:branches r1)) "the first collection records the branches it saw")
        (is (zero? (:swept r1)) "and sweeps nothing: a new main must not hold its first work behind a full sweep"))
      (is (:skipped (pdb/collect! conn)) "the same branches: nothing to reclaim, nothing written")
      (d/branch! conn :db :gone)
      (on :gone #(d/transact % {:tx-data [{:seon.turn/id "att/0" :seon.turn/reply reply}]}))
      (let [r (record! (pdb/collect! conn))]
        (is (= {:swept 0 :gone 0 :branches #{:db :gone}} (select-keys r [:swept :gone :branches]))
            "a branch made since: recorded as seen, nothing swept"))
      (is (k/exists? (:store @conn) vk {:sync? true}))
      (d/delete-branch! conn :gone)
      (let [r2 (record! (pdb/collect! conn))]
        (is (pos? (:swept r2)) "the gone branch's nodes are swept")
        (is (= 1 (:gone r2)))
        (is (= #{:db} (:branches r2))))
      (is (not (k/exists? (:store @conn) vk {:sync? true})) "and its value out of line")
      (is (= #{"main's"} (notes @conn)) "main reads as before")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-commit-costs-what-it-writes
  ;; task seon.db/the-store-grows-with-what-it-keeps. Estimate, from what a commit must
  ;; write: its own datoms (each value twice, in :eavt and :aevt) and the root-to-leaf path
  ;; it touched in each index, whose nodes hold small datoms: at branching factor 64 about 11
  ;; keys of ~1.5-3 KB (a 1-datom commit measured 16.8-27.7 KB at 5k-225k datoms,
  ;; test/seon/bench_commit.clj), so <= 32 KB per commit above its values. So n turns
  ;; committed one by one (an agent commits each turn) cost what the same turns cost in
  ;; one commit plus n paths. Measured 2026-10-22: a turn with an 8 KB context, committed
  ;; per turn, wrote ~15x the one commit, because every commit rewrites the whole tail leaf
  ;; of :eavt and :aevt (up to 64 contexts) holding the values inline. A store on disk of
  ;; this test's own, the last resort: the in-memory store writes no nodes; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-commit-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        n 20]
    (try
      (let [once (write-turns! conn cfg path :once n n)
            t0 (profile/now-ns)
            per-turn (write-turns! conn cfg path :per-turn n 1)
            ms (profile/ms-since t0)
            bound (+ once (* n 32 1024))]
        (println (str "a-commit-costs-what-it-writes: " n " turns, one commit " once " B, per turn " per-turn " B, bound " bound " B, "
                      (format "%.1f" (/ ms n)) " ms per turn (compressor " (cfg/value :seon.config.store/compressor)
                      ", out of line from " (cfg/value :seon.config.store/value-out-of-line-bytes) " B)"))
        (is (<= per-turn bound)
            (str n " per-turn commits wrote " per-turn " bytes; the same turns in one commit wrote " once
                 ", so the bound is " bound " (" (format "%.1f" (/ (double per-turn) bound)) "x over)")))
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))
