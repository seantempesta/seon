(ns seon.podhost.datahike-harness.backends
  "Datahike config builders for each backend. Backends-as-data: each entry
   in the registry is a fn `(cfg run-id)` returning a complete datahike config.

   `run-id` is a short string scoping resources — e.g. 'bulk-load-10k-2026-05-16'
   — so concurrent harness runs don't collide.

   Built-in:
     :memory — in-process konserve.memory
     :file   — konserve.filestore on a temp dir
     :lmdb   — konserve-lmdb on a temp dir
     :gcs    — konserve-gcs on seon-datahike-harness bucket, store-id per run

   Loading :lmdb / :gcs registers the backends on konserve.store's dispatch via
   their `defmethod -connect-store` calls — just `:require` is enough."
  (:require [konserve-lmdb.store]              ;; side-effect: registers :lmdb
            [konserve-gcs.core :as kgcs]       ;; side-effect: registers :gcs
            [konserve.store :as kstore]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [superv.async :refer [go-try- <?-]]
            [datahike.api :as d]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.util UUID]))

(def ^:const bucket "seon-datahike-harness")
(def ^:const location "US-CENTRAL1")

;; ----------------------------------------------------------------------------
;; PATCH: konserve-gcs 0.1.9's `-create-store :gcs` is missing a `<?-` await on
;; its inner `connect-store` call (fixed in `org.replikativ/konserve-gcs 0.1.12`
;; under the wrong group-id for our dep tree). Without the await, it returns
;; a channel-of-channel and datahike crashes downstream with
;; `ManyToManyChannel cannot be cast to Associative` at `konserve.cache/ensure-cache`.
;; Re-installing the method here closes the gap.
;; ----------------------------------------------------------------------------
(defmethod kstore/-create-store :gcs
  [{:keys [bucket location] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (let [spec       (dissoc config :backend)
                     client     (#'kgcs/cloud-storage-client spec)
                     store-path (#'kgcs/spec->store-path spec)
                     exists     (#'kgcs/store-exists? spec)]
                 (when exists
                   (throw (ex-info (str "GCS store already exists at: " bucket "/" store-path)
                                   {:bucket bucket :config config})))
                 (when-not (#'kgcs/get-bucket client bucket)
                   (#'kgcs/create-bucket client location bucket))
                 (#'kgcs/write-store-marker client bucket store-path)
                 (<?- (kgcs/connect-store spec :opts opts))))))

(defn- temp-dir-path
  "Return the absolute path to a fresh temp dir BUT do not create it on disk —
   konserve's `-create-store` for :file / :lmdb refuses to write into an
   existing directory."
  [prefix]
  (.getAbsolutePath
   (io/file (System/getProperty "java.io.tmpdir")
            (str prefix "-" (System/currentTimeMillis)))))

(defn- store-uuid
  "Stable UUID derived from run-id — same run-id → same uuid, so resume works."
  [run-id]
  (UUID/nameUUIDFromBytes (.getBytes (str "datahike-harness/" run-id))))

(defn memory-cfg [run-id]
  {:store              {:backend :memory
                        :id      (store-uuid run-id)}
   :schema-flexibility :write
   :keep-history?      false})

(defn- with-cleanup-dir [cfg dir]
  ;; Stash the cleanup path on the cfg's metadata — keeps it invisible to
  ;; datahike's config-mismatch check while still letting `cleanup!` find it.
  (with-meta cfg {::cleanup-dir dir}))

(defn file-cfg [run-id]
  (let [dir (temp-dir-path (str "dh-file-" run-id))]
    (with-cleanup-dir
      {:store              {:backend :file
                            :path    dir
                            :id      (store-uuid run-id)}
       :schema-flexibility :write
       :keep-history?      false}
      dir)))

(defn file-cfg-at
  "Variant: explicit path, no auto-cleanup. Used by cold-resume which needs
   the same path across two processes."
  [run-id path]
  {:store              {:backend :file
                        :path    path
                        :id      (store-uuid run-id)}
   :schema-flexibility :write
   :keep-history?      false})

(defn lmdb-cfg [run-id]
  (let [dir (temp-dir-path (str "dh-lmdb-" run-id))]
    (with-cleanup-dir
      {:store              {:backend :lmdb
                            :path    dir
                            :id      (store-uuid run-id)}
       :schema-flexibility :write
       :keep-history?      false}
      dir)))

(defn lmdb-cfg-at [run-id path]
  {:store              {:backend :lmdb
                        :path    path
                        :id      (store-uuid run-id)}
   :schema-flexibility :write
   :keep-history?      false})

(defn gcs-cfg
  "konserve-gcs backend. store-id is the folder within the bucket — using
   the run-id keeps each harness run in its own GCS namespace so cleanup
   is just `gcloud storage rm -r gs://<bucket>/<run-id>/`. Region is locked
   to US-CENTRAL1 to match the bucket."
  [run-id]
  {:store              {:backend  :gcs
                        :bucket   bucket
                        :location location
                        :store-id (str "harness/" run-id)
                        :id       (store-uuid run-id)}
   :schema-flexibility :write
   :keep-history?      false})

(def builders
  "name → builder fn — used by the CLI to look up a backend by string."
  {:memory memory-cfg
   :file   file-cfg
   :lmdb   lmdb-cfg
   :gcs    gcs-cfg})

(defn build [backend run-id]
  (if-let [f (builders backend)]
    (f run-id)
    (throw (ex-info (str "unknown backend: " backend
                         " — known: " (keys builders))
                    {:backend backend}))))

(defn cleanup!
  "Best-effort post-run cleanup. For local dirs deletes them; for :gcs deletes
   the run-id namespace under the bucket. Always safe to skip."
  [backend cfg]
  (try
    (case backend
      (:file :lmdb)
      (when-let [d (::cleanup-dir (meta cfg))]
        (doseq [f (reverse (file-seq (io/file d)))]
          (.delete ^java.io.File f)))

      :gcs
      (let [{:keys [store-id]} (:store cfg)]
        (sh/sh "gcloud" "storage" "rm" "-r"
               (str "gs://" bucket "/" store-id "/")))

      nil)
    (catch Throwable t
      (println "[harness] cleanup err for" backend ":" (ex-message t)))))
