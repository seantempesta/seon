(ns seon.cluster.source
  "Publishes the latest complete source database value on one branch.

  Each changed digest is populated on a process-owned scratch branch. The
  first publication branches that complete head onto `:current-src`; later
  publications use Datahike's expected-head guard to advance the same branch.
  Failed and stale builds leave the previously published head untouched.
  Scratch branches are retired after publication and no connection to
  `:current-src` is opened or retained."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file Files]))

(schema.edn/load! {})

(def current-branch
  "The one branch that names the latest complete source database value."
  :current-src)

(def ^:private source-attributes
  [:seon.source/digest :seon.source/built-at])

(defn- refuse!
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::rule rule))))

(defn- require-committed!
  [result rule message data]
  (when (:seon.error/kind result)
    (refuse! rule message
             (assoc data :seon.source/transaction-result result)))
  result)

(defn- source-file?
  [filename]
  (or (str/ends-with? filename ".clj")
      (str/ends-with? filename ".cljc")
      (str/ends-with? filename ".edn")))

(defn snapshot
  "The source-tree digest and exact per-file digests of the declared roots."
  {:malli/schema [:=> [:cat :seon.source/digest-request]
                  :seon.source/snapshot]}
  [{roots :seon.source/roots}]
  (let [declared (->> roots
                      (map #(.getCanonicalFile (io/file %)))
                      distinct
                      (sort-by #(.getPath ^java.io.File %)))]
    (doseq [^java.io.File root declared]
      (when-not (or (.isDirectory root) (.isFile root))
        (refuse! ::root-absent
                 (str "the declared source root " (.getPath root)
                      " is neither a directory nor a file")
                 {::root (.getPath root)
                  :seon.source/roots roots})))
    (let [entries
          (for [^java.io.File root declared
                :let [directory? (.isDirectory root)
                      prefix (inc (count (.getPath (if directory?
                                                    root
                                                    (.getParentFile root)))))]
                entry (if directory?
                        (->> (file-seq root)
                             (filter #(.isFile ^java.io.File %))
                             (filter #(source-file? (.getName ^java.io.File %)))
                             (sort-by #(subs (.getPath ^java.io.File %) prefix)))
                        ;; an explicitly declared file root is one digest
                        ;; entry regardless of extension
                        [root])
                :let [file-digest
                      (schema/sha-256
                       [(Files/readAllBytes (.toPath ^java.io.File entry))])]]
            {:path (.getCanonicalPath ^java.io.File entry)
             :relative-path (subs (.getPath ^java.io.File entry) prefix)
             :digest file-digest})]
      {:seon.source/digest
       (schema/sha-256
        (map (fn [{:keys [relative-path digest]}]
               (.getBytes (str relative-path "\u0000" digest "\n") "UTF-8"))
             entries))
       :seon.source/file-digests
       (into (sorted-map) (map (juxt :path :digest)) entries)})))

(defn digest
  "The source-tree digest of the declared roots."
  {:malli/schema [:=> [:cat :seon.source/digest-request]
                  :seon.source/digest]}
  [request]
  (:seon.source/digest (snapshot request)))

(defn current
  "The published source branch and commit ID, or nil before publication."
  {:malli/schema [:=> [:cat :seon.store/store]
                  [:maybe :seon.source/current]]}
  [store]
  (when-let [commit-id
             (registry/branch-commit-id
              {:seon.store/store store
               :seon.store/branch current-branch})]
    {:seon.source/branch current-branch
     :seon.source/commit-id commit-id}))

(defn- scratch-branch
  []
  (let [{:seon.boot/keys [pid start-instant]}
        (cluster.process/current-identity)]
    (keyword (str "building-source-" pid "-"
                  (inst-ms start-instant) "-" (random-uuid)))))

(defn- resolve-population
  [populate source-digest]
  (or (try
        (requiring-resolve populate)
        (catch Throwable _ nil))
      (refuse! ::populate-unresolvable
               (str "the population " populate " does not resolve")
               {:seon.source/populate populate
                :seon.source/digest source-digest})))

(defn- retire-scratch!
  [store scratch]
  (try
    (registry/retire-branch! {:seon.store/store store
                              :seon.store/branch scratch})
    (catch Throwable _ nil)))

(defn publish!
  "Build and atomically publish one complete source database value."
  {:malli/schema [:=> [:cat :seon.source/publish-request]
                  :seon.source/published]}
  [{:keys [:seon.store/store]
    source-digest :seon.source/digest
    populate :seon.source/populate
    population-data :seon.source/population-data}]
  (let [populate-fn (resolve-population populate source-digest)
          expected-commit (:seon.source/commit-id (current store))
          scratch (scratch-branch)]
      (registry/branch! {:seon.store/store store
                         :seon.cluster.registry/from :db
                         :seon.store/branch scratch})
      (try
        (let [connection (store/open-branch! store scratch)]
          (try
            ;; Give the scratch a unique committed head before invoking the
            ;; population. If population fails immediately, this prevents its
            ;; branch from remaining an alias of `:db`, whose commit is an
            ;; ordinary ancestor of the already-published source history.
            (require-committed!
             (db/transact!
              connection
              {:tx-data (schema.datahike/malli->datahike-schema
                         source-attributes)})
             ::scratch-schema-refused
             "the source scratch schema transaction was refused"
             {:seon.source/digest source-digest})
            (populate-fn
             (merge population-data
                    {:seon.db/connection connection
                     :seon.source/digest source-digest}))
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (require-committed!
             (db/transact!
              connection
              {:tx-data [{:seon.source/digest source-digest
                          :seon.source/built-at (java.util.Date.)}]})
             ::source-seal-refused
             "the source seal transaction was refused"
             {:seon.source/digest source-digest})
            (if expected-commit
              ;; The scratch commit is deliberately NOT a parent. Published
              ;; history follows the prior `current-src` commit, keeping the
              ;; scratch outside descendant-retirement safety.
              (d/force-branch! @connection current-branch #{expected-commit}
                               {:expected-current-commit expected-commit})
              ;; Equal heads are not strict descendants, so initial scratch
              ;; retirement remains safe under the existing registry rule.
              (let [scratch-commit (db/commit-id @connection)
                    {:seon.cluster/keys [created?]}
                    (registry/branch! {:seon.store/store store
                                       :seon.cluster.registry/from scratch
                                       :seon.store/branch current-branch})]
                (when-not created?
                  (refuse! ::stale-publication
                           "another publisher created current-src first"
                           {:seon.source/branch current-branch
                            :seon.source/commit-id scratch-commit}))))
            (finally
              (d/release connection))))
        (let [commit-id
              (registry/branch-commit-id
               {:seon.store/store store
                :seon.store/branch current-branch})]
          (when-not (uuid? commit-id)
            (refuse! ::publish-readback-failed
                     "the published source branch has no commit ID"
                     {:seon.source/branch current-branch}))
          (registry/retire-branch! {:seon.store/store store
                                    :seon.store/branch scratch})
          {:seon.source/branch current-branch
           :seon.source/commit-id commit-id
           :seon.source/digest source-digest
           :seon.source/built? true})
        (catch Throwable failure
          (retire-scratch! store scratch)
          (throw failure)))))

(defn- assert-scalar-rows!
  [db rows]
  (let [attributes (into #{} (mapcat keys) rows)
        unsafe
        (into []
              (filter (fn [attribute]
                        (let [definition (get (:schema db) attribute)]
                          (or (nil? definition)
                              (not= :db.cardinality/one
                                    (:db/cardinality definition))
                              (:db/isComponent definition)))))
              attributes)]
    (when (seq unsafe)
      (refuse! ::unsafe-incremental-rows
               "incremental publication accepts scalar attributes only"
               {:seon.source/unsafe-attributes (vec (sort unsafe))}))))

(defn upsert!
  "Publish canonical safe upserts against one exact source commit."
  {:malli/schema [:=> [:cat :seon.source/upsert-request]
                  :seon.source/published]}
  [{:keys [:seon.store/store :seon.program/rows :seon.db/process]
    expected-commit :seon.source/expected-commit-id
    source-digest :seon.source/digest}]
  (let [scratch (scratch-branch)]
    (registry/branch! {:seon.store/store store
                       :seon.cluster.registry/from expected-commit
                       :seon.store/branch scratch})
    (try
      (let [connection (store/open-branch! store scratch)]
        (try
          (assert-scalar-rows! @connection rows)
          (let [digest-entities
                (db/q '[:find [?entity ...]
                       :where [?entity :seon.source/digest]]
                     @connection)]
            (when-not (= 1 (count digest-entities))
              (refuse! ::invalid-source-seal
                       "incremental publication requires one source digest entity"
                       {:seon.source/expected-commit-id expected-commit
                        ::digest-entity-count (count digest-entities)}))
            (let [digest-entity (first digest-entities)
                  old-digest (:seon.source/digest
                              (db/entity @connection digest-entity))]
              (require-committed!
               (db/transact!
                connection
                (cond->
                 {:tx-data
                  (into [[:db/retract digest-entity
                          :seon.source/digest old-digest]
                         [:db/add digest-entity
                          :seon.source/digest source-digest]]
                        rows)}
                  process (assoc :tx-meta {:seon.db/process process})))
               ::incremental-source-refused
               "the incremental source transaction was refused"
               {:seon.source/digest source-digest
                :seon.source/expected-commit-id expected-commit}))
            (d/force-branch! @connection current-branch #{expected-commit}
                             {:expected-current-commit expected-commit}))
            (finally
              (d/release connection))))
      (let [commit-id
            (registry/branch-commit-id
             {:seon.store/store store
              :seon.store/branch current-branch})]
        (when-not (uuid? commit-id)
          (refuse! ::publish-readback-failed
                   "the published source branch has no commit ID"
                   {:seon.source/branch current-branch}))
        (registry/retire-branch! {:seon.store/store store
                                  :seon.store/branch scratch})
        {:seon.source/branch current-branch
         :seon.source/commit-id commit-id
         :seon.source/digest source-digest
         :seon.source/built? true})
      (catch Throwable failure
        (retire-scratch! store scratch)
        (throw failure)))))
