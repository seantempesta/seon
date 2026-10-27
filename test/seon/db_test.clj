(ns seon.db-test
  "One store, many branches, as in production (owner, 2026-10-21): bin/test seeds one test
  store and each test takes a branch of it through one helper (seon.test-store/test-branch!)."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [clojure.java.io] [datahike.api :as d] [datahike.writer :as dw] [datahike.db.transaction :as dbt]
            [seon.db :as pdb] [seon.schema.datahike] [malli.core] [malli.registry] [seon.config :as cfg] [seon.contract :as contract] [seon.edn]
            [konserve.jolt.lmdb :as klmdb] [konserve.jolt.lmdb-ffi :as lmdb] [konserve.jolt.lz4] [konserve.jolt.zstd] [clojure.string :as str] [konserve.core :as k] [hasch.core] [datahike.gc] [seon.cost :as cost] [seon.lib :as lib]
            [datahike.datom :as dd] [datahike.index.persistent-set :as psi]))

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

(deftest each-id-is-issued-once-across-branches
  ;; the store's one page of ids (seon.db/ids!): two branches forked at one commit each take
  ;; their own entity ids and transaction ids, and an id carried in tx-data moves the page past it
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ids"))
           _ (d/branch! conn (:branch cfg) b)
           bc (d/connect (assoc cfg :branch b))
           write (fn [c tag] (let [r (d/transact c [{:db/id "n" :note/text tag}])]
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
               (d/transact bc [[:db/add far :note/text "carried"]])
               (is (< far (first (write conn "after")))))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest a-reopened-store-issues-past-every-head
  ;; the page is reseeded from the heads when it opens, so a page lost (a crash, a deleted
  ;; file) re-issues no id a committed head holds; a store on disk of its own, removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (let [r (d/transact conn [{:db/id "n" :note/text "before"}])
            e (get-in r [:tempids "n"]) t (:max-tx (:db-after r))]
        (clojure.java.shell/sh "rm" (str path "/ids"))
        (pdb/ids! conn)
        (let [r2 (d/transact conn [{:db/id "n" :note/text "after"}])]
          (is (< e (get-in r2 [:tempids "n"])))
          (is (< t (:max-tx (:db-after r2))))))
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

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
        ;; each process's exit status and stderr, so a process that ended before its branches says why
        cmd (str "'" jolt "' -A:test -e " (pr-str (code "x")) " > /dev/null 2> '" path "-x.err' & x=$!; "
                 "'" jolt "' -A:test -e " (pr-str (code "y")) " > /dev/null 2> '" path "-y.err' & y=$!; "
                 "wait $x; ex=$?; wait $y; ey=$?; echo \"$ex $ey\"")
        r (clojure.java.shell/sh "nice" "-n" "10" "zsh" "-c" cmd)
        ends (zipmap ["x" "y"] (map parse-long (str/split (str/trim (:out r)) #" ")))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        made (for [t ["x" "y"] i (range 50)] (keyword (str t "-" i)))]
    (try
      (doseq [t ["x" "y"]]
        (is (= 0 (ends t)) (str "process " t " ended " (ends t) ": " (slurp (str path "-" t ".err")))))
      (is (= 100 (count (filter #(pdb/branch? conn %) made))) "every branch's head was written")
      (is (= (set made) (disj (d/branches conn) :db)) "and every branch is listed")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path (str path "-x.err") (str path "-y.err"))))))

(deftest opening-a-kept-store-installs-the-attributes-it-lacks
  ;; a store on disk of this test's own, the last resort: what is proved is what a root's
  ;; open does to a kept store seeded before an attribute existed; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        lacking (:db/ident (last pdb/schema))
        _ (d/create-database cfg)
        c0 (d/connect cfg)
        ;; and one attribute installed before it was indexed
        _ (d/transact c0 {:tx-data (mapv #(if (= :seon.vector/text-t (:db/ident %)) (dissoc % :db/index) %) (butlast pdb/schema))})
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (= lacking (:db/ident (get (d/schema @conn) lacking))) "the attribute the store lacked is installed")
      (is (true? (get-in (d/schema @conn) [:seon.vector/text-t :db/index])) "and the index an attribute lacked")
      (let [t (:max-tx @conn)
            _ (d/release conn)
            {c2 :conn} (pdb/open! :lmdb path 0 :db)]
        (is (= t (:max-tx @c2)) "an open that finds nothing missing writes nothing")
        (d/release c2))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-kept-store-gains-content-ids-and-keeps-its-entities
  ;; a store seeded before content ids (its identities without :db/contentId): opening it
  ;; gives them their content ids; an entity it holds keeps its id, a new identity takes its
  ;; content id. A store on disk of this test's own, the last resort; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database (pdb/made-cfg cfg))
        c0 (d/connect cfg)
        _ (d/transact c0 {:tx-data (mapv #(dissoc % :db/contentId) pdb/schema)})
        old (get-in (d/transact c0 [{:db/id "f" :form/id "app.k/old" :form/src "(def old 1)" :form/ns "n"} {:db/id "n" :ns/name 'app.k}]) [:tempids "f"])
        old-ns (pdb/eid @c0 :ns/name 'app.k)
        _ (d/release c0)
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)]
    (try
      (is (< old datahike.constants/tx0) "minted before: a counter id")
      (is (true? (get-in (d/schema @conn) [:form/id :db/contentId])) "the identity gained its content id")
      (is (= old (pdb/eid @conn :form/id "app.k/old")) "the entity it held keeps its id")
      (let [r (d/transact conn [{:db/id "f" :form/id "app.k/new" :form/src "(def new 1)" :form/ns "n"} {:db/id "n" :ns/name 'app.k}])
            new (get-in r [:tempids "f"])]
        (is (= (datahike.db.transaction/content-eid :form/id "app.k/new") new) "a new identity takes its content id")
        (is (= old-ns (get-in r [:tempids "n"]) (:db/id (:form/ns (d/entity @conn new))))
            "a tempid naming an identity the store holds is that entity, refs made before its map included"))
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-release-commits-what-was-applied
  ;; a write applied run-ahead (datahike.writer/apply!, an agent's transact!) and not yet
  ;; committed is committed by the release (the :sync writer's shutdown drains it), never
  ;; dropped. A store on disk of this test's own, the last resort; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (map? (dw/apply! conn {:tx-data [{:note/text "applied, not committed"}] :tx-meta (pdb/tx-meta :db)})))
        (is (dw/pending? conn))
        (d/release conn true))
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (is (some #(= "applied, not committed" (:v %)) (d/datoms @conn :aevt :note/text)) "the applied write is in the store")
        (d/release conn true))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest database-exists-answers-whether-a-store-is-there
  ;; datahike.api/database-exists? answered through a go block that put! its value on
  ;; Datahike's throwable-promise, which on Jolt is no channel: the fiber threw and the caller
  ;; waited for ever (pure-tests' store-release-all-hang, after a store was removed). A store
  ;; on disk of this test's own, the last resort; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))]
    (try
      (is (false? (d/database-exists? (pdb/store-cfg :lmdb path))) "no store at the path")
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (d/release conn true))
      (is (true? (d/database-exists? (pdb/store-cfg :lmdb path))) "a store made there")
      (clojure.java.shell/sh "rm" "-rf" path)
      (is (false? (d/database-exists? (pdb/store-cfg :lmdb path))) "and none once it is removed")
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-store-made-again-at-a-removed-path-is-a-new-store
  ;; a store released and removed, then made again at its path in the same process: the
  ;; process's LMDB env for the path closed with the last store over it
  ;; (konserve.jolt.lmdb/release-env!), so the new store opens its own files, never the old
  ;; mapping (which still held :db: "Database already exists"). A store on disk of this
  ;; test's own, the last resort; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)]
        (d/transact conn {:tx-data [{:note/text "the first store's"}] :tx-meta (pdb/tx-meta :db)})
        (d/release conn true))
      (clojure.java.shell/sh "rm" "-rf" path)
      (let [{:keys [conn seeded?]} (pdb/open! :lmdb path 0 :db)]
        (is (true? seeded?) "made, not found")
        (is (not-any? #(= "the first store's" (:v %)) (d/datoms @conn :aevt :note/text)) "none of the removed store's facts")
        (d/release conn true))
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-create-that-fails-leaves-no-store
  ;; task seon.db/a-store-is-made-whole-or-not-at-all: the full run of 2026-10-26 failed a create
  ;; after its env had made data.mdb, and the path then held a file with no database that no open
  ;; recovered ("Database does not exist"). Here a child creates a store and is killed the moment a
  ;; data file shows, at the path or in a directory beside it (where a create builds it): each path
  ;; then holds a whole store or none, and the next open answers either. Ten creates (n <= 20); the
  ;; kill's moment moves with the machine, the verdict holds for any. Stores on disk of this test's
  ;; own, removed at the end
  (let [base (str (System/getProperty "user.dir") "/target/db-test-create-" (random-uuid))
        data-shown? (fn [path]
                      (or (.exists (java.io.File. (str path "/data.mdb")))
                          (some #(and (str/starts-with? (.getName ^java.io.File %) (str (.getName (java.io.File. path)) ".making-"))
                                      (.exists (java.io.File. ^java.io.File % "data.mdb")))
                                (.listFiles (java.io.File. base)))))
        killed (fn [path]
                 (lib/fork! {:child #(pdb/open! :lmdb path 0 :db)
                             :refused (fn [n] {:refused n})
                             ;; a bound on looking, never a verdict: a kill at any moment must leave a whole store or none
                             :parent (fn [pid] (loop [i 0] (when (and (not (data-shown? path)) (< i 10000000)) (recur (inc i))))
                                       (lib/c-kill pid 9) (lib/wait-status pid))}))
        opens (fn [path] (try (let [{:keys [conn]} (pdb/open! :lmdb path 0 :db)] (d/release conn true) :opens)
                              (catch Exception x (ex-message x))))]
    (.mkdirs (java.io.File. base))
    (try
      (is (= (repeat 10 :opens) (vec (for [i (range 10) :let [p (str base "/store-" i)]] (do (killed p) (opens p)))))
          "every path a killed create left opens")
      (testing "a create that completes leaves nothing beside its store"
        (let [p (str base "/whole")]
          (is (= :opens (opens p)))
          (is (empty? (filter #(str/starts-with? (.getName ^java.io.File %) "whole.making-") (.listFiles (java.io.File. base)))))))
      (finally (clojure.java.shell/sh "rm" "-rf" base)))))

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
        (let [r (d/transact conn {:tx-data [{:note/text "after"}] :tx-meta (pdb/tx-meta :db)})]
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
       (let [v '[b/x a/y b/x c/z b/x]
             e (get (:tempids (d/transact conn [{:db/id "p" :seon.proof.run/skipped-process v}])) "p")]
         (is (= v (:seon.proof.run/skipped-process (d/pull @conn [:seon.proof.run/skipped-process] e)))))))))

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
  "Turn k of a run as seon.turn/act! records it: the basis its context was built from
  (the commit, its red assertion; the text is made again from it, seon.context/render), its
  reply (1,500 bytes) and its eval call, whose eval's record holds what it evaluated to as the
  next turn shows it (2,000). The reply is real prose and the evaluated value real source, each turn its own
  (a real turn's reply is the model's prose and code; its evals are mostly source and
  printed data: a slurp, a source, a test's report): a repeated letter compressed ~3x better
  than real turn text and made LZ4 look like the fix (2026-10-22)."
  [k]
  [{:seon.turn/id (str "att/" k) :seon.turn/run {:seon.run/id "att"} :seon.turn/n k :seon.turn/origin :model
    :seon.turn/basis {:commit (java.util.UUID. 0 k) :task "t/x" :run "att" :turn k :max-turns 8 :origin :model
                                       :observed {:results {'t-test/x :fail} :test-run-errors [] :reasons [{:reason :proof-red :symbol 't-test/x}]
                                                  :reds [{:test 't-test/x :type :fail :expected "(= 6 (f 2))" :actual "(not (= 6 4))"}]}}
    :seon.turn/reply (slice @prose k 1500)
    :seon.turn/calls [{:id "call-0" :code "(f 2)" :eval (str "e" k)}]}
   ;; the call's eval, whose record holds its response as the next turn reads it
   {:seon.eval/id (str "e" k) :seon.eval/by "a1" :seon.eval/record {:code "(f 2)" :response {:handle (symbol "result" (str "e" k)) :value (slice @code k 2000)}}}])

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
      (let [r1 (record! (pdb/collect! conn true))]
        (is (= #{:db} (:branches r1)) "the first collection records the branches it saw")
        (is (zero? (:swept r1)) "and sweeps nothing: a new main must not hold its first work behind a full sweep"))
      (is (:skipped (pdb/collect! conn true)) "the same branches: nothing to reclaim, nothing written")
      (d/branch! conn :db :gone)
      (on :gone #(d/transact % {:tx-data [{:seon.turn/id "att/0" :seon.turn/reply reply}]}))
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
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

;; the rule a collection keeps beside "no agent live" (docs/storage.md#collection)
(deftest a-root-that-shares-its-store-never-collects
  ;; seon.db/collect! with sole? false (:seon.config.store/sole-writer?, false where roots share
  ;; one store: bin/test's shards, bin/check's steps) sweeps nothing, though a branch is gone:
  ;; another process's commit could be reusing a value the sweep would delete (Datahike's
  ;; collection sees only its own process's commits). A store on disk of this test's own, the
  ;; last resort: the in-memory store has no nodes to collect; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/db-test-shared-" (random-uuid))
        {:keys [conn]} (pdb/open! :lmdb path 0 :db)
        reply (slice @prose 11 3000)
        vk (hasch.core/uuid reply)]
    (try
      (d/branch! conn :db :gone)
      (let [c (d/connect (assoc (:config @conn) :branch :gone))]
        (try (d/transact c {:tx-data [{:seon.turn/id "att/0" :seon.turn/reply reply}]}) (finally (d/release c))))
      (d/delete-branch! conn :gone)
      (is (= {:skipped true :shared true} (pdb/collect! conn false)) "a shared store: nothing swept, nothing recorded")
      (is (k/exists? (:store @conn) vk {:sync? true}) "the gone branch's value out of line is kept")
      (is (not (:skipped (pdb/collect! conn true))) "while the sole writer's collection of the same store runs")
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

(deftest two-branches-diff-by-what-each-wrote
  ;; datoms-diff walks both trees and skips every subtree they share (Jolt's HashSet needs
  ;; retainAll for that), so a branch's own writes are its side of the diff against its fork
  (ts/test-branch!
   (fn [cfg conn]
     (let [fork @conn
           b (keyword (str (name (:branch cfg)) "-b"))
           _ (d/branch! conn (:branch cfg) b)
           bc (d/connect (assoc cfg :branch b))]
       (try
         (d/transact bc [{:note/text "b's 1"} {:note/text "b's 2"}])
         (d/transact conn [{:note/text "main's"}])
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
     (let [e (get-in (d/transact conn [{:db/id "f" :form/id "h/f" :form/src "(defn f [] 0)" :form/order 1}]) [:tempids "f"])]
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
           bc (d/connect (assoc cfg :branch b))
           mint (fn [c] (let [r (d/transact c [{:db/id "f" :form/id "app.ce/f" :form/src "(defn f [])" :form/ns "n" :form/defines ["v"]}
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
             (is (< (get-in (d/transact conn [{:db/id "x" :note/text "counter"}]) [:tempids "x"]) datahike.constants/tx0)))
           (testing "an identity is never replaced on its entity: a rename is refused"
             (is (= :entity-id/collision
                    (try (d/transact conn [[:db/add fa :form/id "app.ce/g"]]) nil
                         (catch Exception e (some (comp :error ex-data) (take-while some? (iterate ex-cause e)))))))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest content-ids-cost-the-writes-the-model-says
  ;; content eids scatter a commit's new identities over the indexes (notes/content-eids-2026-10-23.md
  ;; §3): nodes written per commit against counter eids, the ratio the model priced (1.3-1.7x at
  ;; 2 new identities, 2.3-5.1x at 10), red when steeper. Measured 2026-10-23 at 110k datoms:
  ;; 0.97x at 1, 1.51x at 2, 4.50x at 10. A store on disk of this test's own, the last resort:
  ;; the in-memory store writes no nodes to count
  (let [nodes (fn [content?]
                (let [path (str (System/getProperty "user.dir") "/target/db-test-" (random-uuid))
                      cfg (pdb/made-cfg (pdb/store-cfg :lmdb path))
                      _ (d/create-database cfg)
                      conn (d/connect (dissoc cfg :keep-history?))
                      ent (fn [k] (into {:w/id (str "entity " k)} (for [i (range 10)] [(keyword "w" (str "a" i)) (str "value " i " of " k)])))]
                  (try
                    (pdb/ids! conn)
                    (d/transact conn (into [(cond-> {:db/ident :w/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                              content? (assoc :db/contentId true))]
                                           (for [i (range 10)] {:db/ident (keyword "w" (str "a" i)) :db/valueType :db.type/string :db/cardinality :db.cardinality/one})))
                    (doseq [part (partition-all 1000 (range 3000))] (d/transact conn (mapv ent part)))
                    (let [writes #(:writes @(:stats (-> @conn :store :storage)))]
                      (into {} (for [m [2 10]]
                                 (let [w0 (writes)]
                                   (doseq [c (range 10)] (d/transact conn (mapv #(ent (str "new " m " " c " " %)) (range m))))
                                   [m (/ (- (writes) w0) 10.0)]))))
                    (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))
        counter (nodes false) content (nodes true)
        ratio (fn [m] (/ (content m) (counter m)))]
    (is (< (ratio 2) 2.0) (str "2 new identities: " (ratio 2) "x the counter's nodes"))
    (is (< (ratio 10) 6.0) (str "10 new identities: " (ratio 10) "x the counter's nodes"))
    (is (< (ratio 2) (ratio 10)) "more new identities per commit scatter more")))

(defn plain-datoms
  "d/db-with's arguments for n plain datoms: the test store's seed and n [:db/add e a v] of
  two cardinality-one attributes (a string and a long), two to an entity, every entity new."
  [n]
  [(ts/test-db) (vec (for [i (range n)]
                       [:db/add (+ 100000000 (quot i 2)) (if (even? i) :form/src :form/order) (if (even? i) (str "(defn f" i ")") i)]))])

(defn identity-datoms
  "d/db-with's arguments for n datoms as n/2 entity maps, each named by an identity (attribute
  a, :db/unique, with or without :db/contentId) and holding one plain value."
  [a n]
  [(ts/test-db) (vec (for [i (range (quot n 2))] {a (str "proof entity " i) :form/src (str "(defn f" i ")")}))])

(defn form-refs
  "d/db-with's arguments for n datoms of one form's :form/refs (cardinality many, symbols):
  every index comparison past the entity and attribute reaches the value."
  [n]
  [(ts/test-db) (vec (for [i (range n)] [:db/add 100000000 :form/refs (symbol "app.m0" (str "f" i))]))])

(def content-id-datoms (partial identity-datoms :form/id))
(def unique-datoms (partial identity-datoms :seon.task/id))

(deftest a-datom-costs-its-index-inserts
  ;; task datahike.transaction/a-datom-costs-its-index-inserts. The floor, measured on this store's
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
     :sizes [100 300 1000 10000]}))

(deftest a-value-costs-its-index-inserts
  ;; task datahike.transaction/an-index-insert-allocates-nothing-extra: a card-many value's
  ;; comparisons all reach the value, so its floor is a plain datom's (two inserts, no card-one
  ;; descent). Before Jolt's 2-arity == and Datahike's compare-value without a try, 38 KB a
  ;; datom at 1,000 and growing with n (46 KB at 10,000); the overrun below is what stays (a
  ;; deftype method call's dispatch, task jolt.backend/a-deftype-method-call-takes-a-site-cache)
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/form-refs
     :n "values of one form's cardinality-many :form/refs"
     :work "per value: its insert into :eavt and :aevt, each descent comparing up to the value, the datom, its place in the tx-data"
     :growth :linear
     :per-n {:ticks 7000 :bytes 10500}
     :overrun {:task "datahike.transaction/an-index-insert-allocates-nothing-extra" :measured {:ticks 12969032.0 :bytes 17008064.0}}
     ;; the overrun's measure is at 1,000, the largest size in every run (--all too)
     :sizes [100 300 1000]}))

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
     :overrun {:task "datahike.transaction/an-identity-costs-its-seek" :measured {:ticks 11543218.0 :bytes 34428528.0}}
     ;; the overrun's measure is at 1,000, the largest size in every run (--all too)
     :sizes [10 100 1000]})
  (cost/proof
    {:of 'datahike.api/db-with :input 'seon.db-test/content-id-datoms
     :n "datoms, half a content-id identity naming its entity map"
     :work "per datom: as a :db/unique identity's, and the identity's [attribute value] hashed once to its entity id"
     :growth :linear
     :per-n {:ticks 11000 :bytes 16000}
     :overrun {:task "datahike.transaction/an-identity-costs-its-seek" :measured {:ticks 16945178.0 :bytes 64847328.0}}
     :sizes [10 100 1000]}))

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

(defn- attr-db
  "An in-memory db of eight long attributes and 200 random facts, with :attribute-refs? refs?
  (an attribute is then an entity id, not a keyword): a db of each kind a datom's :a can be."
  [refs? ^java.util.Random rnd]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false :attribute-refs? refs?}
        _ (d/create-database cfg)
        conn (d/connect cfg)
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

