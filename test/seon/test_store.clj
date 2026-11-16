(ns seon.test-store
  "The test store: this process's one in-memory store for tests and cost setups, seeded once
  on :db (bin/test makes it on first use), and each test takes a branch of it (one store,
  many branches, as in production; owner, 2026-10-21). Test-only, so it lives in test/, not
  in src (session DUP-HELPERS); it seeds and opens through seon.db's own seed! and open-main!."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.db :as pdb] [clojure.java.shell :as sh] [clojure.string] [clojure.edn] [clojure.test] [seon.lib] [seon.program :as program] [seon.agent] [seon.config] [seon.fx :as fx] [seon.task :as task] [seon.config :as config] [seon.proofs] [seon.profile] [hasch.core :as hasch]
            [seon.ref :as ref] [seon.doc :as doc] [seon.template :as template]))

(defn standing-targets-tx
  "The sections and vars every agent is made citing (:seon.config.agent/cites) and the task templates' instructions cite
  (seon.template/instructions), as this checkout holds them, as tx-data: each section as the one parse reads its page
  (seon.doc/page-sections: its heading, level, position, lead and text; its links are not stored, since they reach pages this
  store does not hold and a link to no section refuses the write), each var as this process holds it (its namespace and
  docstring: an import of its file would add a program namespace to the seed every program test counts). So a template's task and an agent are made on the test store as on main, where a cite names what
  exists (docs/references.md#an-entity-cites)."
  {:malli/schema [:=> [:cat] :seon.db/tx-data]}
  []
  (let [co (config/checkout)
        cs (concat (keep ref/address-citation (config/value :seon.config.agent/cites))
                   (mapcat #(:seon.ref/citations (ref/parse nil %)) (vals template/instructions)))
        wanted (into #{} (for [{:seon.ref/keys [path anchor]} cs :when (and path anchor)] [path anchor]))
        addresses (into #{} (map (fn [[p a]] (str p "#" a))) wanted)]
    (vec (concat (for [p (sort (into #{} (map first) wanted))
                       sec (doc/page-sections p (slurp (str co "/" p)))
                       :when (addresses (:seon.doc/address sec))]
                   (select-keys sec [:seon.doc/address :seon.doc/heading :seon.doc/level :seon.doc/position :seon.doc/lead :seon.doc/text]))
                 ;; each var as this process holds it, its namespace and docstring: an import of its file would add a program
                 ;; namespace to the seed every program test counts (the forms it loads, the files it writes back)
                 (for [{n :seon.ref/name} cs :when (qualified-symbol? n) :let [v (requiring-resolve n)] :when v]
                   (let [nsym (symbol (namespace n)) nd (:doc (meta (the-ns nsym)))]
                     (cond-> {:var/name n :var/ns (cond-> {:ns/name nsym} nd (assoc :ns/doc nd))}
                       (:doc (meta v)) (assoc :var/doc (:doc (meta v))))))))))

(def ^:private ^:seon.test/fixture store
  "The test store's config, made and seeded with about 2,000 datoms on first use, what every agent and template cites
  (standing-targets-tx) and a stub of the system prompt. A fixture: an agent forces it once before its first test fork
  (seon.agent/warm-fixtures!), so every fork inherits it seeded (one seeding a fork cost 65M ticks, 1 to 3 s, each in-process
  test item); never at load, where a binary that loads test namespaces would start its fibers before its main carrier. See
  docs/merging.md#a-test-child-is-a-fork-of-a-live-agent."
  (delay (let [cfg (pdb/made-cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                                   :writer pdb/writer-cfg})]
           (d/create-database cfg)
           (let [conn (pdb/connect cfg)]
             (pdb/ids! conn) (pdb/seed! conn 2000)
             (d/transact conn {:tx-data (standing-targets-tx)})
             ;; a stub of the system prompt, as a root's store holds the real one: every agent's request needs one (seon.ctx.wire/system-text),
             ;; and a world without it renders an error where the agent's page goes. A test of the prompt itself loads the wiki's
             (d/transact conn {:tx-data (doc/load-tx @conn "docs" {doc/system-page "# A stub of the system prompt\n\nBe brief."})})
             (d/release conn))
           cfg)))

(defn program-schema-digest
  "The digest (8 hex digits) of the declarations in `decls` (seon.db/schema's form) of the attributes
  that hold the program (seon.program/attr-namespaces), with the store's layout (seon.db/layout): what the test runner's program store is named
  by, so a change to any other attribute (a run's, a task's, a config key) keeps the store and its
  imported program, where the whole schema's digest (seon.db/schema-digest) made a full import."
  {:malli/schema [:=> [:cat [:sequential :map]] :string]}
  [decls]
  (subs (clojure.string/replace (str (hasch/uuid [(filterv #(some-> (:db/ident %) namespace program/attr-namespaces) decls) pdb/layout])) "-" "") 0 8))

(defn program-store-key
  "The 8 hex digits a kept program store is named by: schema-digest (program-schema-digest: the program's attributes and the
  store's layout) and pins, the revisions of the code that encodes what it stores (Jolt's runtime, konserve's codec, Datahike's
  nodes), as the checkout's git index pins them. Not jolt.host/runtime-fingerprint, which a built binary bakes and the bin/test
  runner computes from sources (\"dev-...\" against \"v0.8.23-...-tr\" at one pin): one checkout's agents and runner keyed apart,
  and their stores, past the count kept, were removed under each other. A new pin is a new store. See
  docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :string [:map-of :string :string]] :string]}
  [schema-digest pins]
  (subs (clojure.string/replace (str (hasch/uuid [schema-digest (into (sorted-map) pins)])) "-" "") 0 8))

(defn- store-pins
  "{path revision} of the checkouts whose code encodes a stored program (reference-code/jolt, konserve and datahike), as checkout's
  git index pins them (seon.fx/read-source's :pins reads all of them so). Through seon.lib/sh, on a fiber: it runs where a store
  is opened, and clojure.java.shell/sh's futures left two threads no proc declares (bin/check lifecycle's :threads-declared)."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [checkout]
  (into {} (for [[_ sha p] (re-seq #"(?m)^160000 ([0-9a-f]{40}) \d+\t(\S+)$"
                                   (:out (seon.lib/sh "git" "-C" checkout "ls-files" "-s" "--" "reference-code/jolt" "reference-code/konserve" "reference-code/datahike")))]
             [p sha])))

(def kept-program-digest
  "The key of this process's kept program store, deref'd where a store is opened: its schema's program-schema-digest and its
  checkout's pins of the code that encodes it (program-store-key). A delay, so the one git read runs once, in a process that
  opens a program store, never at this namespace's load in every root and test child that holds it."
  (delay (program-store-key (program-schema-digest pdb/schema) (store-pins (seon.config/checkout)))))

(def ^:private program-store-path
  "This process's store of kept program dbs, one branch per git tree (test-runner/program-db),
  under the temp dir (never the checkout): a test process's own, so shards running at once never share one; removed
  when the process ends (a shutdown hook)."
  (delay (let [path (str (System/getProperty "java.io.tmpdir") "/program-store-" (random-uuid))]
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (seon.lib/remove-tree! path))))
           path)))

(defn- held-open?
  "Does a process hold the LMDB store at path open (its data.mdb, lsof: one call, the store's file only)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (let [f (str path "/data.mdb")]
    (and (.isFile (java.io.File. f))
         (not (clojure.string/blank? (:out (seon.lib/sh "lsof" "-t" "--" f)))))))

(defn- numbered-generations
  "[[n path] ...] of the generations of the key's store at stem, newest first: stem-<n> for each n of them, and stem itself as 0.
  A stage a generation is made in (stem-<n>.stage-<uuid>, test-runner/program-db) is no generation."
  {:malli/schema [:=> [:cat :string] [:vector [:tuple :int :string]]]}
  [stem]
  (let [f (java.io.File. ^String stem) dir (.getParentFile f)
        pat (re-pattern (str "\\Q" (.getName f) "\\E-(\\d+)"))]
    (->> (concat (when (.isDirectory f) [[0 stem]])
                 (for [n (some-> dir .list seq) :let [m (re-matches pat n)]
                       :when (and m (.isDirectory (java.io.File. dir ^String n)))]
                   [(Long/parseLong (second m)) (str dir "/" n)]))
         (sort-by (comp - first))
         vec)))

(defn generations
  "The directories of the key's store at stem (kept-program-store's path), newest generation first: stem-<n> for each n of
  them, and stem itself as generation 0. A key's store is a series of generations, each one LMDB store holding a branch a git
  tree (test-runner/program-db): a store past :seon.config.proofs/program-store-max-bytes is never moved or removed under the
  runs reading it, and the next tree imported starts the next generation (next-generation). See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [stem] (mapv second (numbered-generations stem)))

(defn next-generation
  "The path of the generation after the key's newest at stem: stem-<n+1> (stem-1 when the key has only its generation 0 or none)."
  {:malli/schema [:=> [:cat :string] :string]}
  [stem] (str stem "-" (inc (or (ffirst (numbered-generations stem)) 0))))

(defn shared-program-store!
  "The path of the program store at base for key (program-store-key), base-<key>: the repository's, which checkouts at other
  keys share at once (another Jolt pin, another program schema), so a store of another key is never removed for being another
  key's (seon.db/kept-store!'s rule, made for a store one process owns, deleted a store a plan of the new key was opening,
  2026-11-12). This one is marked used (its newest generation's mtime). A key's store is its generations (generations, base-<key>-<n>):
  those of this key beyond the :seon.config.proofs/program-stores-kept most recently used, and the keys beyond that count
  (the most recently used generation of each ranks it), are removed, so the disk stays bounded, but never a generation a process
  holds open (held-open?), however many: a run reads its tree's generation to the end. A stage a generation was left in
  (<generation>.stage-<uuid>) that no process holds open is removed too. Nothing is made here.
  See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [base k]
  (let [f (java.io.File. ^String base) dir (.getParentFile f) stem (.getName f)
        path (str dir "/" stem "-" k)
        key-of (fn [n] (second (re-matches (re-pattern (str "\\Q" stem "\\E-([0-9a-f]{8})(?:-\\d+)?")) n)))
        used (fn [g] (.lastModified (java.io.File. ^String g)))
        mine (generations path)
        others (->> (for [n (some-> dir .list seq) :let [kk (key-of n)] :when (and kk (not= kk k))] [kk (str dir "/" n)])
                    (group-by first))]
    (when-let [g (first mine)] (.setLastModified (java.io.File. ^String g) (System/currentTimeMillis)))
    ;; never one a process holds open (its LMDB environment, data.mdb): removing it under that process failed its write
    ;; (NoSuchFileException under program-store-<key>/, 2026-11-12), whatever the count
    (doseq [g (drop (config/value :seon.config.proofs/program-stores-kept) (sort-by #(- (used %)) mine))
            :when (not (held-open? g))]
      (seon.lib/remove-tree! g))
    (doseq [n (some-> dir .list seq)
            :when (re-matches (re-pattern (str "\\Q" stem "\\E-[0-9a-f]{8}(?:-\\d+)?\\.stage-.+")) n)
            :let [g (str dir "/" n)]
            :when (not (held-open? g))]
      (seon.lib/remove-tree! g))
    (doseq [[_ gs] (drop (dec (config/value :seon.config.proofs/program-stores-kept))
                         (sort-by (fn [[_ gs]] (- (apply max (map (comp used second) gs)))) others))
            [_ g] gs
            :when (not (held-open? g))]
      (seon.lib/remove-tree! g))
    path))

(defn kept-program-store
  "The path of the plan's program store (:seon.config.proofs/program-store, keyed by kept-program-digest): the
  one bin/test's plan imported the checkout's tree into before any shard started, so a test that reads the
  checkout's own program (test-runner/program-db of the working directory) reads that tree's branch and
  imports nothing (3.4 G ticks a shard process, to the first test in it that read the program). A test that
  imports another tree takes program-store, a store of its process's own: its branches are scratch, and
  never the plan's to choose a nearest tree from. docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat] :string]}
  [] (shared-program-store! (config/value :seon.config.proofs/program-store) @kept-program-digest))

(defn reach-index
  "{key {:digest :process? :binary?}} of db's tests under inputs (or of each key of roots, {key [var ...]}):
  seon.program/graph-index over a graph built from db (seon.program/reach-graph). A test's helper: production
  reads a stored graph (test-runner/reach-graph-of) or the fork's, and never builds one only for an index."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] :map]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] :map]]}
  ([db inputs] (reach-index db inputs nil))
  ([db inputs roots] (program/graph-index (program/reach-graph db (:data-refs inputs)) inputs roots)))

(defn reach-digests
  "{key digest} of reach-index."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] :map]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] :map]]}
  ([db inputs] (reach-digests db inputs nil))
  ([db inputs roots] (update-vals (reach-index db inputs roots) :digest)))

(defn program-store
  "The path of this process's store of kept program dbs (program-store-path)."
  {:malli/schema [:=> [:cat] :string]}
  [] @program-store-path)

(defn process-store
  "The path of the one durable store the process tests' roots take a branch of (each its own
  main): <checkout>/target/runs/test-store-<schema digest> (seon.db/kept-store!), made once by bin/test's
  plan before any shard starts (make-process-store!), so no two shards' roots ever create it at once.
  A root that found it missing created it (seon.db/open!), and another root starting in
  another shard meanwhile saw its data file and opened a database not yet made: \"Database
  does not exist\" (task seon.test-store/the-process-store-is-made-before-any-shard). A missing
  store is refused, naming who makes it, never created here. checkout: default this process's."
  {:malli/schema [:function [:=> [:cat] :string] [:=> [:cat :string] :string]]}
  ([] (process-store (seon.config/checkout)))
  ([checkout]
   ;; the path its maker recorded (make-process-store!), never one this process derives: a test child of the standing system's
   ;; agent runs main's program and derived another schema digest than the checkout's seed made the store under (2026-11-12)
   (let [rec (java.io.File. (str checkout "/target/process-store.path"))
         path (if (.isFile rec) (clojure.string/trim (slurp rec)) (str checkout "/target/runs/test-store-" pdb/schema-digest))]
     (when-not (pdb/store? path)
       (throw (ex-info (str "no process store at " path ": bin/test makes it (its plan: make-process-store!) before any shard starts; run the test through bin/test, or as an agent through (my.agent/prove '#{its-name}), which runs it on the gate's checkout")
                       {:seon.error/type :seon.test-store/no-process-store :path path})))
     path)))

(defn make-process-store!
  "The process tests' store (process-store) of checkout, made when missing (the schema and no
  example program on :db, as a root's own store starts) and every one of another schema removed
  (seon.db/kept-store!): bin/test's plan and the service's seed call it before any test runs the binary. Its path is
  recorded in <checkout>/target/process-store.path, which process-store reads. Answers its path."
  {:malli/schema [:=> [:cat :string] :string]}
  [checkout]
  (let [path (pdb/kept-store! (str checkout "/target/runs/test-store"))]
    (.mkdirs (.getParentFile (java.io.File. ^String path)))
    (d/release (:conn (pdb/open! :lmdb path 0 :db)))
    ;; its path recorded where every reader looks (process-store): whoever makes the store names it
    (spit (str checkout "/target/process-store.path") path)
    path))

(defn clone-process-store!
  "A store of a test's own at path (made, its directory with it): an APFS copy-on-write clone of the
  process store's data file (the program, the wiki and the source fact seed-program! put on :db, as
  bin/check takes its store), so a root booting its first main there imports and loads nothing
  more than a root on a branch of the process store, and no other test sees what this one writes.
  For the tests whose subject is a store's restart or resume, which cannot share a main
  (docs/machine.md#a-root-boots-from-a-seeded-store). Answers path."
  {:malli/schema [:=> [:cat :string] :string]}
  [path]
  (.mkdirs (java.io.File. ^String path))
  (let [r (sh/sh "cp" "-c" (str (process-store) "/data.mdb") (str path "/data.mdb"))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "the process store could not be cloned to " path ": " (:err r))
                      {:seon.error/type :seon.test-store/clone-failed :path path})))
    path))

(def ^:private seed-holds-the-schema
  "The kept seed made to hold the declarations this process holds that it lacks (seon.db/install-missing!: a seed made before main
  added an attribute), once a process, before its first test reads or branches it: a test's branch is then the seed's, datom for datom."
  (delay (let [c (pdb/connect @store)] (try (pdb/install-missing! c) (finally (d/release c))))))

(defn test-db
  "The test store's seed as a db value (the schema and about 2,000 datoms of the example
  program), for a test that only reads or uses d/db-with: no branch is made."
  {:malli/schema [:=> [:cat] :seon.host/db]}
  []
  ;; with the declarations this process's branch adds (seon.db/missing-declarations), as a value: the seed stays as it was made
  (let [_ @seed-holds-the-schema
        c (pdb/connect @store) db (try @c (finally (d/release c))) ms (pdb/missing-declarations (:schema db))]
    (if (seq ms) (pdb/speculate-db db ms) db)))

(defn test-branch!
  "(f cfg conn) on a fresh branch of the test store (its seed: the schema and about 2,000
  datoms of the example program), cfg naming the branch; the branch is deleted when f
  returns or throws (a fixture: a test leaves the test store as it found it). Answers f's value."
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/cfg :seon.host/conn] :any]] :any]}
  [f]
  (let [_ @seed-holds-the-schema
        b (keyword (str "test-" (random-uuid)))
        [conn _] (pdb/open-main! @store b)
        ;; the declarations this process's branch adds, on the test's own branch (seon.db/install-missing!: nothing when none)
        _ (pdb/install-missing! conn)
        seed (pdb/connect @store)]
    (try (f (assoc @store :branch b) conn)
         (finally (d/release conn) (pdb/delete-main! seed b) (d/release seed)))))

(defn root-tx
  "Root's entity as a test states it: m's root attributes on root's entity and its seon.config.* keys as the config in force, an entity of its own
  (seon.task/config-tx), as root's boot writes them. See docs/config.md#facts-on-main."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [m]
  (let [config-key? #(some-> (namespace %) (clojure.string/starts-with? "seon.config."))
        cs (into {} (filter (comp config-key? key)) m)]
    (cond-> [(into {:seon.system/id :root} (remove (comp config-key? key)) m)]
      (seq cs) (into (seon.task/config-tx cs {})))))

(defn turn-tx
  "Model turn n of agent k (a key) as seon.turn/model-request! writes it first: the agent's number n and its model call
  <k>/<n> (origin :model), with more, the call's other attributes. A record the test writes
  in that turn names that call (:seon.eval/call, seon.task/call-ref). See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :string pos-int? :map] [:vector :map]]}
  [k n more]
  [{:seon.agent/key k :seon.agent/turn n}
   (merge {:seon.model-call/id (seon.lib/call-id k n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model}
          more)])

(defn work-tx
  "Task id's agent and how its work stands (outcome), as a root records them: what seon.task/start-tx writes (the task's
  outcome and agent; the agent's key (its id's name), task, branch, boot, start, turn budget and parent), with the members of
  :members (:seon.task/* of its judging) beside. Options: :boot (\"boot-1\"), :agent (:a1), :branch (its task's default), :model,
  :max-turns (8), :start-ms (1000), :parent (the key of who started it, \"user\"), :members."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/outcome :map] [:vector :map]]}
  [id outcome {:keys [boot agent branch model max-turns start-ms parent members] :or {boot "boot-1" agent :a1 max-turns 8 start-ms 1000 parent "user"}}]
  (let [k (name agent)]
    [(merge {:seon.task/id id :seon.task/outcome outcome :seon.task/agent k} members)
     (cond-> {:db/id k :seon.agent/key k :seon.agent/task [:seon.task/id id]
              :seon.agent/branch (or branch (task/default-branch id)) :seon.agent/boot boot :seon.agent/started-ms start-ms
              :seon.agent/max-turns max-turns :seon.agent/parent {:seon.agent/key parent}}
       model (assoc :seon.agent/model model))]))

(defn commit-of
  "The writer's main notice {:t :prev :ops :commit} for a db value made from the test store's seed
  (d/db-with, d/with): its datoms (what differs from conn's head, datahike.api/datoms-diff) are
  committed on conn, a test branch, so a proc that takes the notice makes the db with
  d/commit-as-db as it does in root (docs/machine.md#what-a-process-owns-in-memory). :t is the commit's."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db] :map]}
  [conn db]
  (let [prev (:max-tx @conn)
        [gone added] (d/datoms-diff @conn db :eavt [])
        tx (-> (mapv (fn [x] [:db/retract (:e x) (:a x) (:v x)]) (remove #(= :db/txInstant (:a %)) gone))
               (into (map (fn [x] [:db/add (:e x) (:a x) (:v x)])) (remove #(= :db/txInstant (:a %)) added)))
        r (d/transact conn {:tx-data tx})]
    {:t (:max-tx (:db-after r)) :prev prev :ops [] :commit (d/commit-id (:db-after r)) :ns 0}))

(defn stale-dbs
  "The db values a state holds that are older than t, as [path max-tx]: the guard of
  docs/machine.md#roots-heap (a kept db value is a reference). The state is walked as data (maps,
  sequences, sets, volatiles; an atom is a conn or a reaction and is not entered), and a db is
  never entered. A proc's db of its branch's current t is free to hold (its conn holds it
  anyway); an older one pins its own version of the index, and is kept as {:commit :t} instead
  (seon.db/at-commit makes it again). A db held inside a closure is not seen."
  {:malli/schema [:=> [:cat :any :int] [:vector [:tuple [:vector :any] :int]]]}
  [state t]
  (let [out (volatile! [])
        walk (fn walk [path depth x]
               (cond
                 (or (instance? datahike.db.DB x) (instance? datahike.db.AsOfDB x))
                 (when (< (:max-tx x) t) (vswap! out conj [path (:max-tx x)]))
                 (> depth 12) nil
                 (volatile? x) (walk path (inc depth) @x)
                 (map? x) (doseq [[k v] x] (walk (conj path k) (inc depth) v))
                 (coll? x) (doseq [[i v] (map-indexed vector x)] (walk (conj path i) (inc depth) v))))]
    (walk [] 0 state)
    @out))

(def ^:private starter
  "This test process's starter fifo: made once, named by its pid, and held open read-write for the
  process's life (its children do not inherit it), so its EOF says the test process is gone however
  it ended; what a test starts runs under bin/held, which ends it then. Removed by the next process's first use
  (bin/held --reclaim: its pid is no process) - task seon.check/a-killed-starter-leaves-no-fifo -
  never by a shutdown hook: hooks run at once, and one removing it raced the shared roots' stop
  (start-shared-root!), whose bin/held then refused to run (the roots outlived their shards).
  [path its-holder]."
  (delay (let [dir (.getCanonicalPath (java.io.File. (seon.config/checkout) "target/tmp"))
               path (str dir "/test-starter-" (seon.lib/c-getpid) ".fifo")]
           (.mkdirs (java.io.File. dir))
           (sh/sh (str (seon.config/checkout) "/bin/held") "--reclaim" dir)
           (sh/sh "rm" "-f" path)
           (sh/sh "mkfifo" path)
           [path (java.io.RandomAccessFile. path "rw")])))

(defn binary-sh
  "clojure.java.shell/sh of a command that runs the checkout's binary (target/seon, bin/system,
  bin/check): the one way a test does, so seon.program/tree-readers names it and such a test's
  reach digest covers the whole tree the binary is built from. It runs under bin/held with this
  process's starter fifo: what it starts ends when the test process ends."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& args] (let [[cmd opts] (split-with string? args)
                 d (:dir (apply hash-map opts) ".")   ; a relative :dir (or none) is the checkout's, never this process's cwd
                 dir (if (.startsWith ^String d "/") d (str (seon.config/checkout) "/" d))]
             (apply sh/sh "env" (str "SEON_STARTER_FIFO=" (first @starter)) (str (seon.config/checkout) "/bin/held")
                    (concat cmd (apply concat (assoc (apply hash-map opts) :dir dir))))))

(def root-profile
  "The profile a test's root, and any binary a test runs, runs under, whatever profile runs the process that starts it
  (a test child of the service runs under root's: ./seon units under deepseek measured an armed call at 6.1 ticks, 15.1 under
  this one): the
  scripted provider's, a test fixture, so no test helper starts a paid provider (a DeepSeek agent's
  start-root! inherited --profile deepseek, a root with six idle processes that outlived its eval).
  A test naming another gives its own --profile in start-root!'s args (the last one given wins,
  docs/config.md#layers)."
  "scripted")

(defn- root-sh
  "bin/system COMMAND ARGS for a test's root (root): its store, main and runs named in the
  environment it starts with, over the caller's, so nothing inherited redirects it; its server port -1, a port the kernel
  assigns, which bin/system next carries to the next generation."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] :map]}
  [{:keys [store main runs env]} command args]
  (apply binary-sh (concat ["env" (str "SEON_RUNS=" runs) (str "SEON_STORE=" store) (str "SEON_MAIN=" main)
                            "SEON_SERVER_PORT=-1" "SEON_WEB_PORT=0"
                            ;; the root ends with this test process, however it ends (:seon.config.root/end-with)
                            (str "SEON_END_WITH=" (seon.lib/c-getpid))]
                           (for [[k v] env] (str k "=" v))
                           ["bin/system" command] args)))

(defn drop-main!
  "Delete main and every branch under it from the store at store (seon.db/delete-main!), with its
  agents' directory, once the root that made them has stopped: what a test's root made leaves the
  one store with the test. In this process: through the binary (seon.check/drop-mains) it compiled
  the check driver each time, 1-1.8 s a process test. Answers {:deleted [branch ...]}."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [store main]
  (let [cfg (pdb/store-cfg :lmdb store)
        conn (pdb/connect cfg)
        gone (try (pdb/delete-main! conn (keyword main))
                  (finally (pdb/sync! cfg) (d/release conn)))]
    (seon.lib/remove-tree! (pdb/agents-dir (assoc cfg :branch (keyword main))))
    {:deleted gone}))

(defn start-args
  "bin/system start's arguments for a test's root in runs (start-root!): root-profile, its task files
  and verdict cache its own, no catch-up with the checkout at boot, the test running it as --started-by, then args, whose
  --profile and --set win."
  {:malli/schema [:=> [:cat [:map [:runs :string] [:args {:optional true} [:sequential :string]]]] [:vector :string]]}
  [{:keys [runs args]}]
  (-> ["--profile" root-profile
       "--set" (str ":seon.config.root/tasks-dir=" (pr-str (str runs "/tasks")))
       ;; its verdict cache is its own too, so no verdict an earlier test run cached is
       ;; reused (the store's results were per main; the cache outlives a run)
       "--set" (str ":seon.config.proofs/results-dir=" (pr-str (str runs "/results")))
       ;; its main is the seeded store's, the tree its run indexed: a session's edits since are no test's subject, and a boot
       ;; reads no git (docs/source.md#a-boot-catches-up-with-its-checkout); a test of the catch-up sets it again
       "--set" ":seon.config.root/catch-up-on-boot=false"]
      ;; the test running now started this root: its merge gate leaves it out (:seon.system/started-by)
      (into (when-let [{:keys [ns name]} (meta (first clojure.test/*testing-vars*))]
              ["--started-by" (str (ns-name ns) "/" name)]))
      (into args)))

(defn output
  "The text of the file at path that this test or a process it started wrote (a root's runs/root.log, a
  test child's mark), \"\" when there is none. It reads a run's own output, never the checkout, so it is
  named in seon.program/env-readers: a test that reads its outputs through it is not keyed by the whole
  tree, and an edit to the wiki or a task file reruns no process test. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :string] :string]}
  [path] (let [f (java.io.File. ^String path)] (if (.exists f) (slurp f) "")))

(defn start-root!
  "A built root (bin/system start, this checkout's binary) on store and main, both named and never
  inherited (a caller's SEON_STORE or SEON_MAIN, a bin/check run's, opened a store another run
  had written). Its server is on a port the kernel assigns (:seon.config.root/server-port -1),
  read back from its ROOT LISTENING line: bin/system returns at that line or at the root's death,
  never on a clock (:started, the last line bin/system start printed). runs: its pids, log and port; env: more variables {name value}; args: more
  root arguments. Answers the options with :port, nil when the root died before listening, and
  then its :log, which says why (ask answers it as the test's error). runs is one root's, named per main by its caller: a root still running there
  is refused, never answered (bin/system start in it would only report that root, and the test
  would ask another test's root: task seon.test-store/a-test-root-is-its-own). The refused start
  backs that rule, it is no lock: two starts at once in one runs could both pass it, which a
  runs per main makes unreachable. Its task files are its own (:seon.config.root/tasks-dir under
  runs, unless args name another): the checkout's are every session's, and an event on one (a
  merge's write, which FSEvents also delivers to a watcher begun just after it) made a test root
  load all of them (seon.system-test/a-second-boot-resumes-the-same-main read 810 tasks), and so is its
  verdict cache (:seon.config.proofs/results-dir under runs, docs/merging.md#verdicts-are-a-cache). The test
  running it (clojure.test/*testing-vars*) is the root's --started-by, so its merge gate never
  runs the test that started it (docs/merging.md#a-merge-runs-what-the-change-reaches); called
  outside a test, it names none, and every deferred test is gated. Its profile is root-profile unless
  args name another (start-args)."
  {:malli/schema [:=> [:cat [:map [:store :string] [:main :string] [:runs :string]
                             [:env {:optional true} [:map-of :string :string]] [:args {:optional true} [:sequential :string]]]]
                  :map]}
  [{:keys [runs] :as root}]
  (.mkdirs (java.io.File. ^String runs))
  (when (zero? (:exit (root-sh root "status" [])))
    (throw (ex-info (str "a root is already running in " runs ": a test's root takes a runs directory of its own")
                    {:seon.error/type :seon.test-store/runs-in-use :runs runs})))
  (let [started (:out (root-sh root "start" (start-args root)))
        port (fx/bound-port runs)]
    (cond-> (assoc root :port port :started (last (clojure.string/split-lines (str started))))
      ;; it died before listening: its log says why, kept with it for the test's error (ask)
      (nil? port) (assoc :log (output (str runs "/root.log"))))))

(def ^:private readers
  "This process's one read connection per store path (reader), made at its first read."
  (atom {}))

(defn reader
  "A read connection of this process to the store at path, one per path, kept for the process: a test reads a root's
  branches itself (read-branch), as a forked agent reads through an env of its own, never through the root. LMDB's readers
  see what any process committed. See docs/testing.md#a-test-reads-the-store-itself."
  {:malli/schema [:=> [:cat :string] :seon.host/conn]}
  [path]
  (let [d (or (get @readers path) (get (swap! readers #(cond-> % (not (contains? % path)) (assoc path (delay (pdb/connect (pdb/store-cfg :lmdb path)))))) path))]
    @d))

(defn read-branch
  "The committed head of branch b of root (the map start-root! or shared-root! answers: its :store and :main), read from
  the store in this process: b as the root names it (:db is its main; another, a branch on that main, seon.db/on-main).
  What an agent or root's writer committed before it answered is here: an agent commits before it replies
  (seon.repl/agent-step), and the writer before it answers a write. No request to root, no eval record on its main.
  See docs/testing.md#a-test-reads-the-store-itself."
  {:malli/schema [:=> [:cat :map :keyword] :seon.host/db]}
  [root b]
  (pdb/head (reader (:store root)) (pdb/on-main (keyword (:main root)) b)))

(defn read-task-branch
  "The committed head of the branch task id works on (its :seon.task/branch on root's main), read in this process
  (read-branch). See docs/testing.md#a-test-reads-the-store-itself."
  {:malli/schema [:=> [:cat :map :string] :seon.host/db]}
  [root id] (read-branch root (:seon.task/branch (task/task (read-branch root :db) id))))

(defn read-main
  "root's main as the store holds it now (read-branch of :db). See docs/testing.md#a-test-reads-the-store-itself."
  {:malli/schema [:=> [:cat :map] :seon.host/db]}
  [root] (read-branch root :db))

(defn kill-root!
  "SIGKILL the root (bin/system kill), answered once the root and every process it started are
  gone (bin/held --drain). Answers {:drained boolean}: false when bin/system did not say so."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] [:map [:drained :boolean]]]}
  [root] {:drained (boolean (re-find #"\(drained\)" (:out (root-sh root "kill" []))))})

(defn value-of
  "The value a response r showed (an eval's, :seon.eval/response): its :value as data when the answer
  carries it (plain data, seon.server's :eval answer), else read back from its :text (the response
  printed under its handle, as Clojure prints it: a var reads as (var x); tagged literals kept; nothing
  evaluated), or nil when that text does not read back (a cut, or an :out printed verbatim)."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [r] (if (contains? r :value)
        (:value r)
        (try (let [m (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read-string (str (:text r))))]
               (:value m))
             (catch Exception _ nil))))

(defn ask-bound-ms
  "The bound of an ask made now: in a test, the time left of its wall bound (seon.agent/wall-bound-ms) since it started
  (seon.proofs/*test-started-ns*: a boot or an earlier ask spent the rest), less :seon.config.proofs/ask-margin-ms, so the ask ends
  typed with the root's state before the wall ends the test with none; outside a test, :seon.config.proofs/ask-ms. At least 1.
  See docs/clocks.md#two-kinds-of-wait."
  {:malli/schema [:=> [:cat] pos-int?]}
  []
  (if-let [v (first clojure.test/*testing-vars*)]
    (max 1 (- (seon.agent/wall-bound-ms (str v)) (seon.config/value :seon.config.proofs/ask-margin-ms)
              (if-let [t0 seon.proofs/*test-started-ns*] (quot (- (seon.profile/now-ns) t0) 1000000) 0)))
    (seon.config/value :seon.config.proofs/ask-ms)))

(defn ask
  "One request m to a started root's server: its line on a socket this process opens, one reply line read,
  ending on the reply or when the root closes the connection or dies: the reply as data (tagged literals
  kept), or one recorded error value, {:error :eof} (closed with no reply), {:error :reset} or {:error
  :refused} (docs/failure-points.md). A root that died before listening (start-root!'s :port nil) answers
  {:error :root-did-not-listen :log}, its log, never a request to no port. A caller asserts on :error.
  In-process, not bin/ask: a perl process a request was ~17 ms of a cheap one's ~20 under load
  (docs/testing.md#a-root-costs-what-it-boots)."
  {:malli/schema [:function [:=> [:cat [:map [:port [:maybe :int]]] :map] :map]
                  [:=> [:cat [:map [:port [:maybe :int]]] :map pos-int?] :map]
                  [:=> [:cat [:map [:port [:maybe :int]]] :map pos-int? :boolean] :map]]}
  ([root m] (ask root m (ask-bound-ms)))
  ([root m bound-ms] (ask root m bound-ms false))
  ;; evidence?: this ask is itself the evidence ask, so a silence there is no second evidence ask
  ([{:keys [port log] :as root} m bound-ms evidence?]
  (if (nil? port)
    {:error :root-did-not-listen :log log}
    ;; the edge where the socket's failures become values (bin/ask's three), and its declared bound (docs/clocks.md#two-kinds-of-wait)
    (try
      (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
        (.setSoTimeout s (int bound-ms))
        (let [w (java.io.OutputStreamWriter. (.getOutputStream s) "UTF-8")
              r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))]
          (.write w (str (pr-str m) "\n"))
          (.flush w)
          (let [line (.readLine r)]
            (if (clojure.string/blank? line)
              {:error :eof}
              (clojure.edn/read-string {:default tagged-literal} line)))))
      ;; no answer within the bound: what was asked
      (catch java.net.SocketTimeoutException _
        (seon.contract/error :seon.test-store/no-answer #'ask
                             (str "the test root answered nothing within " bound-ms " ms: " (pr-str (select-keys m [:op :agent :names])))
                             {:asked m}))
      (catch java.net.ConnectException _ {:error :refused})
      (catch java.io.IOException _ {:error :reset})))))

(defn stop-root!
  "Stop a root start-root! started (bin/system stop: its stdin closes, it stops cleanly, and the
  wait ends at its exit). Answers {:exit :log}: its exit status, and its whole output; or
  {:error :stopped-without-status :out out :log log} when bin/system printed none."
  {:malli/schema [:=> [:cat [:map [:runs :string]]] :map]}
  [{:keys [runs] :as root}]
  (let [{:keys [out]} (root-sh root "stop" [])
        log (output (str runs "/root.log"))
        status (second (re-find #"\(exit (\d+)\)" out))]
    (if status {:exit (parse-long status) :log log} {:error :stopped-without-status :out out :log log})))

(defonce ^:private shared-roots
  ;; this test process's shared roots by [checkout args] (shared-root!): data, not a function
  (java.util.concurrent.ConcurrentHashMap.))

;; Estimate: one copy a shared root's log (~100 KB): ~1M ticks, at a shard's end, only when it had a red
(defn keep-root-logs!
  "The files written: each shared root's log copied into dir, before the shutdown hook deletes its runs; a shard with a red
  calls it. See docs/testing.md#a-root-costs-what-it-boots."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [dir]
  (vec (for [d (vals shared-roots)
             :let [r (when (realized? d) (try @d (catch Throwable _ nil)))
                   f (some-> (:runs r) (java.io.File. "root.log"))]
             :when (and f (.isFile f))
             :let [to (java.io.File. ^String dir (str "shared-root-" (.getName (java.io.File. ^String (:runs r))) ".log"))]]
         (do (java.nio.file.Files/copy (.toPath f) (.toPath to) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
             (str to)))))

(defn- dead-pid?
  "Is the pid of a name made by mark-of no live process (kill -0: a fact about a process, never a clock; a reused pid keeps
  a stale name, never loses a live one's)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [n] (if-let [[_ pid] (re-find #"-(\d+)-[0-9a-f]{8}-[0-9a-f]{4}-" n)] (not (zero? (seon.lib/c-kill (parse-long pid) 0))) false))

(defn mark-of
  "A name for what this test process makes outside itself (a store main, a merges branch, a fixture worktree): label, its pid
  and a uuid, so reclaim! can remove it once the process is gone, killed or not (a shutdown hook and a finally never run in a
  killed shard). See docs/testing.md#a-root-costs-what-it-boots."
  {:malli/schema [:=> [:cat :string] :string]}
  [label] (str label "-" (seon.lib/c-getpid) "-" (random-uuid)))

(defn reclaim-git!
  "Remove what dead test processes made in checkout's git (named by mark-of, their pid gone): the merges branches of their
  shared roots and the git worktrees their fixtures added. Answers {:branches :worktrees}, the counts removed. See
  docs/testing.md#a-root-costs-what-it-boots."
  {:malli/schema [:=> [:cat :string] :map]}
  [checkout]
  (let [git (fn [& args] (:out (apply sh/sh "git" "-C" checkout args)))
        branches (filterv dead-pid? (clojure.string/split-lines (git "for-each-ref" "--format=%(refname:short)" "refs/heads/jolt/seon-merges-*-shard-*")))
        wts (filterv #(and (re-find #"/seon-[a-z-]+-\d+-[0-9a-f-]+/wt$" %) (dead-pid? %))
                     (keep #(second (re-find #"^worktree (.*)$" %)) (clojure.string/split-lines (git "worktree" "list" "--porcelain"))))]
    (doseq [b branches] (git "branch" "-q" "-D" b))
    (doseq [w wts] (git "worktree" "remove" "--force" w) (seon.lib/remove-tree! (.getParent (java.io.File. ^String w))))
    {:branches (count branches) :worktrees (count wts)}))

(defn reclaim!
  "Remove what dead test processes made in checkout (named by mark-of, their pid gone): its git's (reclaim-git!), and their
  shared roots' mains in store with their runs directories. Run once a shard, where it starts its shared root. 30 such
  branches and 8 fixture worktrees were left by killed shards (2026-11-12). Answers {:branches :mains :worktrees}, the counts
  removed. See docs/testing.md#a-root-costs-what-it-boots."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [checkout store]
  (let [g (reclaim-git! checkout)
        mains (let [conn (pdb/connect (pdb/store-cfg :lmdb store))]
                (try (filterv #(and (re-matches #"shard-\d+-[0-9a-f-]{36}" %) (dead-pid? %)) (map name (d/branches conn)))
                     (finally (d/release conn))))]
    (doseq [m mains] (drop-main! store m) (seon.lib/remove-tree! (str checkout "/target/runs/" m "-runs")))
    (assoc g :mains (count mains))))

(defn- start-shared-root!
  "A root for this test process to share among its tests (shared-root!) on checkout with root
  arguments args, on a main of its own of the process store and a merges branch of its own
  (:seon.config.source/branch, named by that main: the roots of other shards in this checkout commit their
  merges on theirs, so a candidate the merge gate judged is still its branch's head when it is asked
  again, the real gate's case: task seon.agent/a-real-gate-is-proved-in-seconds); stopped, its main dropped and its branch
  deleted when the process ends (a shutdown hook)."
  {:malli/schema [:=> [:cat :string [:vector :string]] :map]}
  [checkout args]
  (let [main (mark-of "shard")
        branch (str (seon.config/value :seon.config.source/branch) "-" main)
        store (process-store checkout)
        ;; what killed shards left (their hooks never ran) goes before this one makes its own
        _ (reclaim! checkout store)
        root (start-root! {:store store :main main :runs (str checkout "/target/runs/" main "-runs")
                           ;; it says it is shared, so a wait on its whole quiet is refused (seon.await/await-quiet)
                           :args (into ["--set" (str ":seon.config.source/branch=" (pr-str branch)) "--set" ":seon.config.root/shared=true"] args)})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (let [s (stop-root! root)] (when-not (:exit s) (binding [*out* *err*] (println "shared root" main "not stopped:" (pr-str s)))))
                                               (drop-main! store main) (seon.lib/remove-tree! (:runs root))
                                               (sh/sh "git" "-C" checkout "branch" "-q" "-D" branch))))
    root))

(declare default-root-args)

(defn shared-root!
  "The root this test process shares among its tests for root arguments args: started at the
  first call for them (start-shared-root!), then the same one: a root boots once per shard, not
  once per test (the boot was most of an in-agent test; an attach on a running root costs
  milliseconds). A test sharing it works on branches of its main and deletes them when it ends
  (seon.agent-test/in-agent); a test whose subject is a root's life (boot, stop, kill, a
  generation) or main itself starts a root of its own."
  {:malli/schema [:=> [:cat [:sequential :string]] :map]}
  [args]
  ;; no arguments is the shard's shared root (docs/testing.md#test-types): default-root-args, the one shard-fixture warms, never a
  ;; second root booted inside the first asking test's budget (an-idle-connection-holds-no-os-thread: 1.2M ticks or 10.3M, by
  ;; whether another test had booted the [] root first)
  (let [args (if (empty? args) (default-root-args) args)
        k [(seon.config/checkout) (vec args)]
        ;; a start that is not run unless it is the one put: two callers at once share one root
        d (delay (start-shared-root! (first k) (second k)))]
    @(or (.putIfAbsent ^java.util.concurrent.ConcurrentHashMap shared-roots k d) d)))

(defn default-root-args
  "The root arguments an in-agent test's shared root takes when it names no dirs or settings: this checkout as its source root."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  [] ["--set" (str ":seon.config.source/root=" (pr-str (seon.config/checkout)))])

(def ^:seon.test/shard-fixture shared-root-default
  "The shard's shared root for default-root-args, a shard fixture: a bin/test shard whose tests reach it starts it before its
  first test (seon.proofs/warm-fixtures!), so no test pays the root's start inside its own budget; an agent never forces it (a
  test root booted inside an agent). See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent."
  (delay (shared-root! (default-root-args))))

(defn- with-pids
  "root (a map with :port) with its writer process's pid and its system process's (the fork server: seon.system/state's
  :fork-server :pid), read in one root eval, never from a pid file; root as it is when it has no port (start-root!'s :log says why)."
  {:malli/schema [:=> [:cat :map] :map]}
  [root]
  (if-not (:port root)
    root
    (let [r (ask root {:op :eval :agent "root" :code "[(seon.lib/c-getpid) (get-in (seon.system/state sys) [:fork-server :pid])]"})
          [w s] (value-of (:response r r))]
      (assoc root :writer-pid w :system-pid s))))

;; Estimate: the store a clone of the process store's data file (APFS copy-on-write: one cp, no bytes copied), one root's boot
;; (bin/system start of the built binary: 10.7 s of wall today against a 2 s target, docs/cost.md#the-test-systems-lower-bound), one
;; root eval answering two pids (~20 ms): once a candidate, by the runner
(defn boot-candidate!
  "The system the process tests run on: checkout's one build, booted on its own APFS clone of the process store and its own main,
  so a binary that writes another format never touches the system's store; never the running system forked (owner, 2026-11-16:
  one build, every process test as fast as possible on it, the standing system switching over to it once green). The runner
  boots it once and passes the answer to each test (on-candidate), and stops it (stop-candidate!): it is a value, held by no var.
  Answers start-root!'s answer with-pids, :dir and :booted true. See task seon.prove/tests-fork-from-the-branchs-warm-agent."
  {:malli/schema [:=> [:cat :string] :map]}
  [checkout]
  (let [main (mark-of "candidate")
        dir (str checkout "/target/runs/" main)
        _ (make-process-store! checkout)
        store (clone-process-store! (str dir "/store"))
        root (start-root! {:store store :main main :runs (str dir "/runs")
                           :args ["--set" (str ":seon.config.source/root=" (pr-str checkout))]})]
    (assoc (with-pids root) :dir dir :booted true)))

(defn on-candidate
  "Candidate c with a branch of a test's own (:branch): a test spawns its agent there through ask on :port."
  {:malli/schema [:=> [:cat :map] :map]}
  [c] (assoc c :branch (keyword (mark-of "candidate"))))

(defn stop-candidate!
  "Stop candidate c (stop-root!) and remove its store and runs. Answers stop-root!'s answer."
  {:malli/schema [:=> [:cat :map] :map]}
  [c] (let [s (stop-root! c)] (seon.lib/remove-tree! (:dir c)) s))
