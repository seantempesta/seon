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

(def ^:private activation-missing-sample-size 10)

(defn activation-refusal
  "Bound a missing activation set for an operator-facing refusal."
  {:malli/schema [:=> [:cat :seon.activation/missing]
                  :seon.activation/refusal]}
  [missing]
  (let [total (count missing)
        sample (vec (take activation-missing-sample-size missing))
        omitted (- total (count sample))
        elision
        (when (pos? omitted)
          {:seon.print/face :seon.print/elided
           :seon.print/omitted omitted
           :seon.print/elision-unit :children
           :seon.render.data/total total
           :seon.render.data/path [:seon.activation/missing]
           :seon.render.data/next-offset (count sample)
           :seon.render.profile/id :seon.render.profile/operator
           :seon.print/requery-refusal
           "Activation refusal facts are available only at the refused database value."})]
    (cond->
     {:seon.error/message
      (str "The source activation closure is missing " total
           (if (= 1 total) " fact: " " facts: ")
           (pr-str sample)
           (when (pos? omitted) (str " … " omitted " more.")))
      :seon.activation/missing-count total
      :seon.activation/missing sample}
      elision (assoc :seon.activation/missing-elision elision))))

(def ^:private source-attributes
  [:seon.source/digest
   :seon.source/built-at
   :seon.source/activation-closure
   :seon.activation/source-digest
   :seon.activation/schema-keys
   :seon.activation/required-attributes
   :seon.activation/config-defaults
   :seon.activation/config-required
   :seon.activation/executable-symbols
   :seon.activation/lookup-refs
   :seon.activation.lookup/id
   :seon.activation.lookup/attribute
   :seon.activation.lookup/value])

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

(defn- resolve-activation
  [activation source-digest]
  (or (try
        (requiring-resolve activation)
        (catch Throwable _ nil))
      (refuse! ::activation-unresolvable
               (str "the activation derivation " activation " does not resolve")
               {:seon.source/activation activation
                :seon.source/digest source-digest})))

(defn- activation-seal-tx
  [connection source-digest requested-symbols activation-fn]
  (let [{closure :seon.activation/closure
         lookup-rows :seon.activation/lookup-rows
         missing :seon.activation/missing}
        (activation-fn
         {:seon.db/connection connection
          :seon.source/digest source-digest
          :seon.activation/requested-symbols requested-symbols})
        requirement-count
        (+ (count (:seon.activation/schema-keys closure))
           (count (:seon.activation/required-attributes closure))
           (count (:seon.activation/config-defaults closure))
           (count (:seon.activation/config-required closure))
           (count (:seon.activation/executable-symbols closure))
           (count (:seon.activation/lookup-refs closure)))
        activation-tempid (str "activation:" source-digest)
        lookup-tempids
        (into {}
              (map (fn [{id :seon.activation.lookup/id}]
                     [id (str "activation-lookup:" id)]))
              lookup-rows)
        closure
        (assoc closure
               :db/id activation-tempid
               :seon.activation/lookup-refs
               (mapv (fn [[_ id]] (get lookup-tempids id))
                     (:seon.activation/lookup-refs closure)))
        lookup-rows
        (mapv (fn [{id :seon.activation.lookup/id :as row}]
                (assoc row :db/id (get lookup-tempids id)))
              lookup-rows)]
    (when (seq missing)
      (let [refusal (activation-refusal missing)]
        (refuse! ::activation-incomplete
                 (:seon.error/message refusal)
                 (assoc refusal :seon.source/digest source-digest))))
    (when-not (pos? requirement-count)
      (refuse! ::activation-empty
               "the source activation closure is empty"
               {:seon.source/digest source-digest}))
    (into
     [{:seon.source/digest source-digest
       :seon.source/built-at (java.util.Date.)
       :seon.source/activation-closure activation-tempid}
      closure]
     lookup-rows)))

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
    activation :seon.source/activation
    populate-request :seon.source/populate-request}]
  (let [populate-fn (resolve-population populate source-digest)
          activation-fn (resolve-activation activation source-digest)
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
             (merge populate-request
                    {:seon.db/connection connection
                     :seon.source/digest source-digest}))
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (require-committed!
             (db/transact!
              connection
              {:tx-data
               (activation-seal-tx
                connection source-digest #{populate activation} activation-fn)})
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
    source-digest :seon.source/digest
    activation :seon.source/activation}]
  (let [activation-fn (resolve-activation activation source-digest)
        scratch (scratch-branch)]
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
            (let [digest-entity (first digest-entities)]
              (require-committed!
               (db/transact!
                connection
                (cond->
                 {:tx-data rows}
                  process (assoc :tx-meta {:seon.db/process process})))
               ::incremental-source-refused
               "the incremental source transaction was refused"
               {:seon.source/digest source-digest
                :seon.source/expected-commit-id expected-commit})
              (require-committed!
               (db/transact!
                connection
                (cond->
                 {:tx-data
                  (into [[:db/retractEntity digest-entity]]
                        (activation-seal-tx
                         connection source-digest #{activation} activation-fn))}
                  process (assoc :tx-meta {:seon.db/process process})))
               ::incremental-activation-refused
               "the incremental source activation transaction was refused"
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
