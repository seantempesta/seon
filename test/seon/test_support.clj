(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.instruction :as instruction])
  (:import [java.util.concurrent CountDownLatch Future TimeUnit
            TimeoutException]))

(set! *warn-on-reflection* true)

(def event-backstop-seconds
  "The loud clock around test events whose publishers are observable."
  20)

(def committed
  "Returned when a boundary expected to refuse instead commits."
  ::committed)

(def unknown-refusal
  "Returned when a thrown boundary carries no classifiable ex-data."
  ::unknown-refusal)

(defn await-event!
  "Await one channel, latch, or future event with a loud backstop."
  ([event-source event]
   (await-event! event-source event (constantly true)))
  ([event-source event accept?]
   (cond
     (instance? CountDownLatch event-source)
     (if (.await ^CountDownLatch event-source
                 event-backstop-seconds
                 TimeUnit/SECONDS)
       true
       (throw
        (ex-info
         "The test did not observe its required latch event."
         {::event event})))

     (instance? Future event-source)
     (try
       (.get ^Future event-source
             event-backstop-seconds
             TimeUnit/SECONDS)
       (catch TimeoutException timeout
         (throw
          (ex-info
           "The test future did not publish its required completion."
           {::event event}
           timeout))))

     (satisfies? async.impl/ReadPort event-source)
     (let [backstop
           (async/timeout
            (.toMillis TimeUnit/SECONDS event-backstop-seconds))]
       (loop []
         (let [[value selected] (async/alts!! [event-source backstop])]
           (cond
             (= selected backstop)
             (throw
              (ex-info
               "The test channel did not publish its required event."
               {::event event}))

             (nil? value)
             (throw
              (ex-info
               "The test channel closed before its required event."
               {::event event}))

             (accept? value)
             value

             :else
             (recur)))))

     :else
     (throw
      (ex-info
       "The test event source is not a channel, latch, or future."
       {::event event
        ::event-source (class event-source)})))))

(defn delete-recursively!
  "Delete one project-local `tmp/` descendant or refuse the broad target.

  NEVER FOLLOWS SYMLINKS. A link is unlinked; whatever it points at is
  untouched. This is not a nicety: on 2026-07-29 this function's ancestor
  canonicalized the ROOT (correctly refusing anything outside `tmp/`) and then
  walked it with `file-seq`, which follows links — so a scratch operator root
  that linked the source tree for its classpath took the walk out of the
  sandbox and deleted 55 tracked paths, all of `src/` and thirteen vendored
  submodule trees, while a suite was running. A sandbox check on the root is
  worthless if the walk can leave the sandbox, so every entry is re-checked
  against the root as well."
  [path]
  ;; Paths are compared LEXICALLY (absolute + normalize), never canonically:
  ;; canonicalizing resolves the link at `path` itself, which would delete a
  ;; link's TARGET instead of the link, refuse a link pointing outside tmp/
  ;; instead of unlinking it, and silently skip a broken link. `normalize`
  ;; still collapses `..`, so `tmp/../src` is refused.
  (let [root-path (.normalize (.toAbsolutePath (.toPath (io/file "tmp"))))
        target-path (.normalize (.toAbsolutePath (.toPath (io/file path))))]
    (when (or (= root-path target-path)
              (not (.startsWith target-path root-path)))
      (throw
       (ex-info
        "Recursive test cleanup is restricted to descendants of tmp/."
        {::path (str target-path)})))
    (letfn [(under-root? [^java.nio.file.Path candidate]
              (.startsWith (.toAbsolutePath (.normalize candidate)) root-path))
            (delete-one! [^java.nio.file.Path entry]
              (when-not (under-root? entry)
                (throw
                 (ex-info
                  "Recursive test cleanup reached outside tmp/."
                  {::path (str entry)})))
              (java.nio.file.Files/deleteIfExists entry))
            (present? [^java.nio.file.Path entry]
              (java.nio.file.Files/exists
               entry
               (into-array java.nio.file.LinkOption
                           [java.nio.file.LinkOption/NOFOLLOW_LINKS])))
            (walk! [^java.nio.file.Path entry]
              ;; a link is a leaf: unlink it, never descend through it
              (when-not (java.nio.file.Files/isSymbolicLink entry)
                (when (java.nio.file.Files/isDirectory
                       entry
                       (into-array java.nio.file.LinkOption
                                   [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                  (with-open [children (java.nio.file.Files/newDirectoryStream
                                        entry)]
                    (run! walk! (vec children)))))
              (delete-one! entry))]
      (when (present? target-path)
        (walk! target-path)))
    nil))

(defn file-store-probe-schema
  "Return the one synthetic string marker attribute for a file-store test."
  [marker-attribute]
  [{:db/ident marker-attribute
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn file-store-markers
  "Read every value of a file-store test's synthetic marker attribute."
  [connection marker-attribute]
  (set
   (d/q
    '[:find [?marker ...]
      :in $ ?marker-attribute
      :where
      [_ ?marker-attribute ?marker]]
    @connection
    marker-attribute)))

(defn refusal-data
  "Return deepest thrown ex-data, `committed`, or `unknown-refusal`."
  [thunk]
  (try
    (thunk)
    committed
    (catch Throwable error
      (loop [throwable error
             found nil]
        (if throwable
          (recur (ex-cause throwable)
                 (or (not-empty (ex-data throwable)) found))
          (or found unknown-refusal))))))

(defn assert-check!
  "Assert one test.check result while retaining its complete shrink data."
  ([check]
   (assert-check! check "Generative check failed."))
  ([check message]
   (test/is (true? (:result check))
            (str message " " (pr-str check)))))

(defn with-database
  "Run `body` with a fresh canonical in-memory database.

   The production source population owns schema installation. Optional
   `:seon.test-support/extra-schema` rows are synthetic declarations whose
   installation is itself part of a test."
  ([body]
   (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema]} body]
   (let [configuration
         {:store {:backend :memory :id (or database-id (random-uuid))}
          :schema-flexibility :write}
         _ (d/create-database configuration)
         connection (d/connect configuration)]
     (try
       (cluster/populate-source!
        {:seon.store/branch-connection connection})
       ;; `populate-source!` is the contents step used by production
       ;; `source/publish!`; production seals that completed population in
       ;; the following transaction. Keep this canonical fixture on the same
       ;; side of that provenance boundary so indexed core contracts are not
       ;; misclassified as agent-authored rows.
       (d/transact
        connection
        {:tx-data
         [{:seon.source/digest
           (apply str (repeat 64 "0"))}]})
       (when (seq extra-schema)
         (d/transact connection {:tx-data extra-schema}))
       (body connection)
       (finally
         (d/release connection)
         (d/delete-database configuration))))))

(defn seed-cluster!
  "Seed one complete cluster/config path for tests that create agents."
  [connection cluster-name]
  (d/transact
   connection
   (into [{:seon.config/cluster cluster-name}
          {:seon.cluster/name cluster-name
           :seon.cluster/config [:seon.config/cluster cluster-name]
           :seon.cluster/instructions
           (mapv (fn [instruction-id]
                   [:seon.cluster.instruction/id instruction-id])
                 instruction/instruction-ids)}]
         (instruction/seed-rows
          {:seon.cluster.instruction/global-text "test AGENTS.md"})))
  nil)
