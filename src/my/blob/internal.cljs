(ns my.blob.internal
  "Implement content-addressed blob storage and restore mechanics.

   The parent `my.blob` namespace owns schemas and the taught API. This
   namespace owns process-local storage configuration, verified overlay reads,
   fsync-backed publication, and retained-blob materialization."
  (:require
    ["node:crypto" :as crypto]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [seon.ai.tokens :as tokens]
    [seon.content-hash :as content-hash]
    [seon.db :as db]
    [seon.platform :as platform]
    [seon.schema :as schema]
    [my.blob.schema]))

(defonce ^:private !storage-view
  (atom {:my.blob/writable-dir
         (str (or (platform/env-val "SEON_CLUSTER_DIR")
                  "data/clusters/default")
              "/blobs")
         :my.blob/read-only-dirs []}))

(defn configure-storage-view!
  "Replace the process-local storage view and return the prior view."
  [view]
  (let [prior @!storage-view]
    (reset! !storage-view view)
    prior))

(defn sha256
  "SHA-256 hex digest of `s` as UTF-8 bytes."
  [s]
  (content-hash/sha-256 s))

(defn normalize-storage-view
  "Normalize one explicit storage view, or return an error envelope."
  [view]
  (if-not (schema/valid-candidate-value? :my.blob/storage-view view)
    {:my.blob/ok? false
     :my.blob/error (str "invalid blob storage view — expected "
                         "{:my.blob/writable-dir string "
                         ":my.blob/read-only-dirs [string …]}")}
    (let [writable-dir (.resolve npath (:my.blob/writable-dir view))
          read-only-dirs (mapv #(.resolve npath %)
                               (:my.blob/read-only-dirs view))
          all-dirs (into [writable-dir] read-only-dirs)]
      (if (= (count all-dirs) (count (distinct all-dirs)))
        {:my.blob/ok? true
         :my.blob/storage-view
         {:my.blob/writable-dir writable-dir
          :my.blob/read-only-dirs read-only-dirs}}
        {:my.blob/ok? false
         :my.blob/error
         "invalid blob storage view — every directory must be distinct"}))))

(defn validated-storage-view
  "The normalized ambient storage view, or an error envelope."
  []
  (normalize-storage-view @!storage-view))

(defn blob-path
  "Absolute path for `hash` below one archive directory."
  [dir hash]
  (.resolve npath (.join npath dir (subs hash 0 2) hash)))

(defn valid-hash?
  "True when `hash` is a lowercase SHA-256 hex string."
  [hash]
  (some? (re-matches #"[0-9a-f]{64}" hash)))

(defn bad-hash
  "Error envelope for a value that is not a SHA-256 hash."
  [hash]
  {:my.blob/ok? false
   :my.blob/hash hash
   :my.blob/error
   (str "not a sha-256 hex hash: " (pr-str hash)
        " — use the :my.blob/hash a put!/stat returned")})

(defn not-found
  "Error envelope for a well-formed hash with no stored blob."
  [hash]
  {:my.blob/ok? false
   :my.blob/hash hash
   :my.blob/error
   (str "no blob stored under " hash
        " — (my.blob/stat {:my.blob/hash …}) checks the DB projection")})

(defn inspect-path
  "Read one archive path through the SHA-256 verification owner."
  [hash path]
  (try
    (let [content (.readFileSync nfs path "utf8")
          actual (sha256 content)]
      (if (= hash actual)
        {:my.blob/ok? true
         :my.blob/hash hash
         :my.blob/path path
         :my.blob/actual-digest actual
         :my.blob/content content}
        {:my.blob/ok? false
         :my.blob/hash hash
         :my.blob/path path
         :my.blob/actual-digest actual
         :my.blob/error (str "blob integrity failure under " hash
                             " — stored bytes hash to " actual)}))
    (catch :default e
      {:my.blob/ok? false
       :my.blob/hash hash
       :my.blob/path path
       :my.blob/error (or (some-> e .-message) (str e))})))

(defn read-path
  "Read and verify one path without exposing filesystem evidence."
  [hash path]
  (select-keys (inspect-path hash path)
               [:my.blob/ok? :my.blob/hash :my.blob/content :my.blob/error]))

(defn resolve-blob-evidence
  "Resolve the first existing path and retain searched paths in order."
  [{:my.blob/keys [writable-dir read-only-dirs]} hash]
  (reduce
    (fn [{:my.blob/keys [searched-source-paths]} dir]
      (let [path (blob-path dir hash)
            searched (conj searched-source-paths path)]
        (if (.existsSync nfs path)
          (reduced
            (assoc (inspect-path hash path)
                   :my.blob/searched-source-paths searched))
          {:my.blob/searched-source-paths searched})))
    {:my.blob/searched-source-paths []}
    (into [writable-dir] read-only-dirs)))

(defn resolve-blob
  "Read the first existing path in overlay-to-base order."
  [{:my.blob/keys [writable-dir read-only-dirs]} hash]
  (reduce
    (fn [_ dir]
      (let [path (blob-path dir hash)]
        (when (.existsSync nfs path)
          (reduced (read-path hash path)))))
    nil
    (into [writable-dir] read-only-dirs)))

(def node-publication-effects
  {:my.blob/sync-file-descriptor! (fn [fd] (.fsyncSync nfs fd))
   :my.blob/atomic-rename! (fn [from to] (.renameSync nfs from to))
   :my.blob/transact! db/transact!})

(defn sync-directory!
  "Synchronize a directory entry before publication reports success."
  [effects dir]
  (let [fd (.openSync nfs dir "r")]
    (try
      ((:my.blob/sync-file-descriptor! effects) fd)
      (finally
        (.closeSync nfs fd)))))

(defn directory-plan
  "The nearest existing ancestor and missing directories in creation order."
  [dir]
  (loop [candidate (.resolve npath dir)
         missing ()]
    (if (.existsSync nfs candidate)
      {:my.blob/directory-anchor candidate
       :my.blob/missing-directories (vec missing)}
      (let [parent (.dirname npath candidate)]
        (when (= candidate parent)
          (throw (js/Error. (str "no existing directory ancestor for " dir))))
        (recur parent (conj missing candidate))))))

(defn ensure-directory-durable!
  "Create each missing directory and synchronize every parent entry."
  [effects dir]
  (let [{:my.blob/keys [directory-anchor missing-directories]}
        (directory-plan dir)
        anchor-parent (.dirname npath directory-anchor)]
    (when-not (= directory-anchor anchor-parent)
      (sync-directory! effects anchor-parent))
    (reduce
      (fn [parent child]
        (try
          (.mkdirSync nfs child)
          (catch :default e
            (when-not (and (= "EEXIST" (.-code e))
                           (.. nfs (statSync child) (isDirectory)))
              (throw e))))
        (sync-directory! effects parent)
        child)
      directory-anchor
      missing-directories)
    (.resolve npath dir)))

(defn sync-published!
  "Synchronize an existing verified file and its containing directory."
  [effects path]
  (let [fd (.openSync nfs path "r")]
    (try
      ((:my.blob/sync-file-descriptor! effects) fd)
      (finally
        (.closeSync nfs fd))))
  (sync-directory! effects (.dirname npath path)))

(defn publish!
  "Publish complete bytes through file sync, rename, and directory sync."
  [effects writable-dir hash content replace-existing?]
  (let [path (blob-path writable-dir hash)
        shard (.dirname npath path)
        tmp (.join npath shard (str hash "." (.randomUUID crypto) ".new"))]
    (try
      (ensure-directory-durable! effects writable-dir)
      (ensure-directory-durable! effects shard)
      (if (and (.existsSync nfs path) (not replace-existing?))
        (sync-published! effects path)
        (do
          (let [fd (.openSync nfs tmp "wx")]
            (try
              (.writeFileSync nfs fd content "utf8")
              ((:my.blob/sync-file-descriptor! effects) fd)
              (finally
                (.closeSync nfs fd))))
          ((:my.blob/atomic-rename! effects) tmp path)
          (sync-directory! effects shard)))
      nil
      (catch :default e
        (or (some-> e .-message) (str e)))
      (finally
        (when (.existsSync nfs tmp)
          (.unlinkSync nfs tmp))))))

(defn ^:async put-with-publication-effects!
  "Publish and project content using one immutable filesystem effect map."
  [{:my.blob/keys [content media]} effects]
  (let [hash (sha256 content)
        toks (tokens/estimate content)
        {view-ok? :my.blob/ok?
         view :my.blob/storage-view
         view-error :my.blob/error}
        (validated-storage-view)]
    (if-not view-ok?
      {:my.blob/ok? false :my.blob/hash hash :my.blob/error view-error}
      (let [existing (resolve-blob view hash)
            werr (cond
                   (and existing (false? (:my.blob/ok? existing)))
                   (:my.blob/error existing)

                   (and existing
                        (.existsSync nfs
                                     (blob-path (:my.blob/writable-dir view)
                                                hash)))
                   (publish! effects (:my.blob/writable-dir view)
                             hash content false)

                   existing nil

                   :else
                   (publish! effects (:my.blob/writable-dir view)
                             hash content false))]
        (if werr
          {:my.blob/ok? false :my.blob/hash hash :my.blob/error werr}
          (let [report
                (await
                 ((:my.blob/transact! effects)
                  {:seon.db/tx-data
                   [(cond-> {:my.blob/hash hash
                             :my.blob/tokens toks
                             :my.blob/at (js/Date.)}
                      media (assoc :my.blob/media media))]}))]
            (if-not (:seon.error/message report)
              {:my.blob/ok? true :my.blob/hash hash :my.blob/tokens toks}
              {:my.blob/ok? false
               :my.blob/hash hash
               :my.blob/error
               (or (:seon.error/message report)
                   "blob file written but the projection tx was rejected")})))))))

(defn canonical-retained-hashes
  "Canonicalize hashes acquired from one immutable database value."
  [hashes]
  (->> hashes distinct sort vec))

(defn observe-retained
  "Observe one immutable database value's bounded blob-set identity."
  [{:my.blob/keys [target-branch-head retained-hashes]}]
  (try
    (let [hashes (canonical-retained-hashes retained-hashes)
          invalid-hash (first (remove valid-hash? hashes))]
      (if invalid-hash
        {:my.blob/ok? false
         :my.blob/target-branch-head target-branch-head
         :my.blob/error
         "the retained database contains a malformed :my.blob/hash"}
        {:my.blob/ok? true
         :my.blob/target-branch-head target-branch-head
         :my.blob/reachable-hash-digest (sha256 (pr-str hashes))
         :my.blob/hash-count (count hashes)}))
    (catch :default error
      {:my.blob/ok? false
       :my.blob/target-branch-head target-branch-head
       :my.blob/error (or (some-> error .-message) (str error))})))

(defn materialization-failure
  [target-branch-head frozen-digest hash-count operation error evidence]
  (merge
    {:my.blob/ok? false
     :my.blob/target-branch-head target-branch-head
     :my.blob/reachable-hash-digest frozen-digest
     :my.blob/hash-count hash-count
     :my.blob/materialization-operation operation
     :my.blob/expected-digest
     (or (:my.blob/expected-digest evidence) frozen-digest)
     :my.blob/error error}
    (select-keys evidence
                 [:my.blob/hash :my.blob/searched-source-paths
                  :my.blob/destination-path :my.blob/actual-digest])))

(defn materialize-hash!
  [effects source-view destination-dir target-branch-head frozen-digest
   hash-count counts hash]
  (let [destination (blob-path destination-dir hash)
        source (resolve-blob-evidence source-view hash)
        source-paths (:my.blob/searched-source-paths source)]
    (cond
      (not (contains? source :my.blob/ok?))
      (reduced
        (materialization-failure
          target-branch-head frozen-digest hash-count
          :my.blob.materialization.operation/verify-source
          (str "no retained source blob exists under " hash)
          {:my.blob/hash hash
           :my.blob/searched-source-paths source-paths
           :my.blob/destination-path destination
           :my.blob/expected-digest hash}))

      (false? (:my.blob/ok? source))
      (reduced
        (materialization-failure
          target-branch-head frozen-digest hash-count
          :my.blob.materialization.operation/verify-source
          (:my.blob/error source)
          (cond->
            {:my.blob/hash hash
             :my.blob/searched-source-paths source-paths
             :my.blob/destination-path destination
             :my.blob/expected-digest hash}
            (:my.blob/actual-digest source)
            (assoc :my.blob/actual-digest
                   (:my.blob/actual-digest source)))))

      :else
      (let [before (when (.existsSync nfs destination)
                     (inspect-path hash destination))
            replace-existing? (and before
                                   (false? (:my.blob/ok? before)))
            publish-error
            (publish! effects destination-dir hash
                      (:my.blob/content source) replace-existing?)]
        (if publish-error
          (reduced
            (materialization-failure
              target-branch-head frozen-digest hash-count
              :my.blob.materialization.operation/publish-destination
              publish-error
              {:my.blob/hash hash
               :my.blob/searched-source-paths source-paths
               :my.blob/destination-path destination
               :my.blob/expected-digest hash}))
          (let [final (inspect-path hash destination)]
            (if (false? (:my.blob/ok? final))
              (reduced
                (materialization-failure
                  target-branch-head frozen-digest hash-count
                  :my.blob.materialization.operation/verify-destination
                  (:my.blob/error final)
                  (cond->
                    {:my.blob/hash hash
                     :my.blob/searched-source-paths source-paths
                     :my.blob/destination-path destination
                     :my.blob/expected-digest hash}
                    (:my.blob/actual-digest final)
                    (assoc :my.blob/actual-digest
                           (:my.blob/actual-digest final)))))
              (cond-> (update counts :my.blob/verified-count inc)
                (nil? before)
                (update :my.blob/newly-materialized-count inc)
                replace-existing?
                (update :my.blob/repaired-count inc)))))))))

(defn materialize-retained-with-effects!
  "Verify and materialize one frozen intent's exact retained blob set."
  [{:my.blob/keys [target-branch-head retained-hashes source-storage-view
                   destination-storage-view reachable-hash-digest]}
   effects]
  (let [source-result (normalize-storage-view source-storage-view)
        destination-result (normalize-storage-view destination-storage-view)
        source-view (:my.blob/storage-view source-result)
        destination-view (:my.blob/storage-view destination-result)
        destination-dir (:my.blob/writable-dir destination-view)]
    (cond
      (false? (:my.blob/ok? source-result))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        (:my.blob/error source-result) {})

      (false? (:my.blob/ok? destination-result))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        (:my.blob/error destination-result) {})

      (not= destination-dir
            (first (:my.blob/read-only-dirs source-view)))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        "the main writable archive must be the target view's first inherited base"
        {:my.blob/destination-path destination-dir})

      :else
      (try
        (let [hashes (canonical-retained-hashes retained-hashes)
              hash-count (count hashes)
              invalid-hash (first (remove valid-hash? hashes))
              derived-digest (sha256 (pr-str hashes))]
          (cond
            invalid-hash
            (materialization-failure
              target-branch-head reachable-hash-digest hash-count
              :my.blob.materialization.operation/derive-retained-set
              "the retained database contains a malformed :my.blob/hash"
              {:my.blob/hash invalid-hash})

            (not= reachable-hash-digest derived-digest)
            (materialization-failure
              target-branch-head reachable-hash-digest hash-count
              :my.blob.materialization.operation/derive-retained-set
              "the retained blob set does not match the frozen intent digest"
              {:my.blob/actual-digest derived-digest})

            :else
            (let [result
                  (reduce
                    (partial materialize-hash!
                             effects source-view destination-dir
                             target-branch-head reachable-hash-digest
                             hash-count)
                    {:my.blob/verified-count 0
                     :my.blob/newly-materialized-count 0
                     :my.blob/repaired-count 0}
                    hashes)]
              (if (false? (:my.blob/ok? result))
                result
                (merge
                  {:my.blob/ok? true
                   :my.blob/target-branch-head target-branch-head
                   :my.blob/reachable-hash-digest derived-digest
                   :my.blob/hash-count hash-count}
                  result)))))
        (catch :default e
          (materialization-failure
            target-branch-head reachable-hash-digest 0
            :my.blob.materialization.operation/derive-retained-set
            (or (some-> e .-message) (str e)) {}))))))

(defn materialize-retained!
  "Materialize one exact retained blob set through the node effects."
  [request]
  (materialize-retained-with-effects! request node-publication-effects))
