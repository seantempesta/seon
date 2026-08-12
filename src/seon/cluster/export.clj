(ns seon.cluster.export
  "Export: a self-contained copy of a store, re-identified to its new path.

  The model:

  - Branch-per-cluster won the creation path (b2-plan §0), so clone is
    NO LONGER how a cluster is made. It survives for the three jobs
    branches cannot do (§0.8): export/backup, import/move to another
    process or machine, and shipping a base system built offline. That
    is also the escape hatch from the one-store-one-process topology
    constraint (§0.4).
  - A cloned store directory CANNOT simply be opened. Datahike compares
    the stored `[:config :store :id]` against the connect-time id and
    raises `:store-identity-mismatch`
    (`reference-code/datahike/src/datahike/connector.cljc:159-169`),
    and B1 derives the id from the canonical path
    (`src/seon/cluster/store.clj:152-153`), so a copy at a new path
    always mismatches. Re-identifying is MANDATORY, not optional:
    reusing the source id instead collides on Datahike's
    `[store-id branch]` connection id and the second open is refused
    (§2.4).
  - `:allow-unsafe-config` is REJECTED as the fix (§2.2): the flag
    rides the live config into the next commit's stored config, so
    every later flag-free connect fails a different way. A one-time
    fork problem must not become a permanent config asymmetry.
  - EVERY BRANCH HEAD AND REACHABLE COMMIT CARRIES ITS OWN STORED CONFIG, so
    `reidentify!` rewrites the whole reachable commit graph, not only `:db`.
    A future branch may fork an exact commit ID rather than a branch head;
    leaving that immutable record stamped with the source identity makes the
    newly created branch refuse on first open. Probed live
    (`tmp/b2-draft-probe/head_config_probe.clj`): with only `:db`
    rewritten, `d/connect` to `:db` succeeded and `:ancestor-x` refused
    `:store-identity-mismatch`. An export whose cluster branches cannot
    be opened is not an export.
  - THE TEMP NAME IS THE SAFETY. Clone into
    `<parent>/.store.<uuid>.tmp`, re-identify there, and only then move
    it atomically to `<parent>/store` — quarried verbatim from State
    A's operator (`script/seon/dev/cluster.clj:58-90`). A
    mis-identified store therefore never exists at a path anything
    opens.
  - The source is passed as the OPEN, flock-held store value: this
    process is provably the only writer while the copy is taken, and
    Datahike's values-then-pointer barrier means even a copy taken mid
    commit opens at the previous head with the new values unreachable.
  - Refusals are loud ex-info
    `{:seon.error/kind ::refused ::rule <which>}`, matching B0/B1
    (`src/seon/cluster/store.clj:161-167`).

  Crash walk (kill -9 at any point; the export path owns no durable
  state in the SOURCE store, so a killed export is always garbage the
  next export overwrites):

  - mid clone: a partial `.store.<uuid>.tmp`. Never named `store`,
    never opened, discarded by the next export;
  - after the clone, before `reidentify!`: a temp directory carrying
    the SOURCE's store id. Same answer — the name is the fence;
  - mid `reidentify!` (some branch heads rewritten, some not): still
    only the temp name; a partially re-identified store is never
    reachable under `store`;
  - after the atomic move: a complete, openable export. `reidentify!`
    is idempotent on a store already carrying its own path-derived id,
    so a re-run over the finished export is a no-op."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike.migrate :as migrate]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.fs :as fs]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file CopyOption Files StandardCopyOption]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn- refuse!
  "Refuse loudly with the one export error shape."
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::rule rule))))

(defn- warn!
  "Say loudly, on stderr, that the slow path is running.
  The fresh tree has no logging owner yet; when it lands this becomes
  one call to it. Silence here would be the real defect — a copy that
  quietly takes minutes instead of milliseconds must announce itself."
  [message data]
  (binding [*out* *err*]
    (println "WARNING" message (pr-str data))
    (flush)))

;;; ---------------------------------------------------------------------------
;;; Copying the bytes — the fast path and the never-unavailable one
;;; ---------------------------------------------------------------------------

;;; Quarried verbatim from State A's operator
;;; (`script/seon/dev/cluster.clj:79-84`): copy-on-write where the
;;; filesystem provides it, a byte copy where it does not, and the
;;; command's own diagnostic when it fails.
(defn- clone-command [source target]
  (case (System/getProperty "os.name")
    "Mac OS X" ["/bin/cp" "-cR" source target]
    "Linux" ["cp" "--reflink=auto" "-a" source target]
    nil))

(defn- clone!
  "Copy `source` to `target` with the host's clone command.
  Returns false when the host has no known command; throws with the
  command's own output when it has one and it fails."
  [source ^java.io.File target]
  (if-let [command (clone-command source (.getPath target))]
    (let [process (.start (doto (ProcessBuilder. ^java.util.List command)
                            (.redirectErrorStream true)))
          output (slurp (.getInputStream process))
          exit (.waitFor process)]
      (when-not (zero? exit)
        (throw (ex-info (str "the clone command failed: " command)
                        {::command command ::exit exit ::output output})))
      true)
    false))

(defn- retransact!
  "Rebuild the source store at `target` by re-transacting its datoms.
  The never-unavailable path: a fresh store, EVERY branch created while
  `:db` is still empty genesis, then each branch's complete datom set
  imported into its own branch through Datahike's own flat-file
  migration (`reference-code/datahike/src/datahike/migrate.clj:8,32`).
  Creating the branches first is what keeps this faithful — importing
  into a branch forked from an already-populated `:db` would assert
  every shared datom twice."
  [store ^java.io.File target]
  (let [source-connection (:seon.store/connection-object store)
        others (disj (set (d/branches source-connection)) :db)
        configuration (store/datahike-configuration (.getPath target))
        datoms-dir (io/file (str (.getPath target) ".datoms"))]
    (.mkdirs datoms-dir)
    (try
      (d/create-database configuration)
      (let [target-main (d/connect configuration)]
        (try
          (doseq [branch others]
            (d/branch! target-main :db branch))
          (doseq [branch (cons :db others)]
            (let [file (io/file datoms-dir (str (name branch) ".cbor"))
                  active-reader
                  (when-not (= :db branch)
                    (registry/active-branch-connection
                     {:seon.store/store store
                      :seon.store/branch branch}))
                  reader (or active-reader
                             (when-not (= :db branch)
                               (store/open-branch! store branch))
                             source-connection)
                  opened-reader? (and (not= :db branch)
                                      (nil? active-reader))]
              (try
                (migrate/export-db reader (.getPath file))
                (finally
                  (when opened-reader?
                    (d/release reader))))
              (let [writer (if (= :db branch)
                             target-main
                             (d/connect (assoc configuration :branch branch)))]
                (try
                  (migrate/import-db writer (.getPath file))
                  (finally
                    (when-not (= :db branch)
                      (d/release writer)))))))
          (finally
            (d/release target-main))))
      (finally
        (fs/delete-recursively! (.getPath datoms-dir)
                                (.getPath datoms-dir))))
    nil))

(defn- copy-store!
  "Put a byte-equivalent of the source store at `target`.
  Clone when the host can; otherwise re-transact, loudly. Only when
  BOTH fail does the export refuse, carrying both causes."
  [store ^java.io.File target]
  (let [source (:seon.store/dir store)
        cause (try
                (when-not (clone! source target)
                  (ex-info (str "no clone command for this host: "
                                (System/getProperty "os.name"))
                           {::os (System/getProperty "os.name")}))
                (catch Throwable failure
                  failure))]
    (when cause
      (warn! "export is falling back to create + re-transact; this is
              slower by a factor of tens and is not copy-on-write"
             {::os (System/getProperty "os.name")
              ::cause (ex-message cause)})
      ; a failed clone may have left a partial tree behind
      (fs/delete-recursively! (.getPath target) (.getPath target))
      (try
        (retransact! store target)
        (catch Throwable fallback-failure
          (refuse! ::clone-unsupported
                   (str "this host can neither clone nor rebuild the store: "
                       (ex-message fallback-failure))
                   {:seon.store/dir source
                    ::os (System/getProperty "os.name")
                    ::clone-cause (ex-message cause)
                    ::fallback-cause (ex-message fallback-failure)}))))
    nil))

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn- reidentify-at!
  "Stamp the store in `store-dir` with the identity `identity-dir` derives.
  The two differ for exactly one caller: `export!` stamps the FINAL
  identity onto the directory while it still has its temp name, so the
  move that publishes it is the last step and a mis-identified store is
  never reachable under a name anything opens. Every other caller
  passes one directory twice."
  [store-dir identity-dir]
  (let [path (.getCanonicalPath (io/file store-dir))
        identity-path (.getCanonicalPath (io/file identity-dir))
        konserve (filestore/connect-fs-store path :opts {:sync? true})
        head (k/get konserve :db nil {:sync? true})
        _ (when-not (some? head)
            (refuse! ::no-branch-head
                     (str "there is no :db branch head at " path
                          " — this is not a store")
                     {:seon.store/dir path}))
        branches (k/get konserve :branches nil {:sync? true})
        _ (when-not (some? branches)
            (refuse! ::genesis-incomplete
                     (str "the store at " path " has no branch roster")
                     {:seon.store/dir path}))
        store-id (get-in (store/datahike-configuration identity-path)
                         [:store :id])]
    ;; Branching from an exact commit reads that immutable commit record, not
    ;; the branch head. Walk heads -> their own commit IDs -> parents so every
    ;; value Datahike may later use as a branch source carries the new identity.
    (loop [pending (seq (conj (set branches) :db))
           visited #{}]
      (when-let [record-key (first pending)]
        (if (contains? visited record-key)
          (recur (next pending) visited)
          (let [record (k/get konserve record-key nil {:sync? true})
                related (when record
                          (conj (set (get-in record [:meta :datahike/parents]))
                                (get-in record [:meta :datahike/commit-id])))]
            (when record
              (k/assoc konserve record-key
                       (-> record
                           (assoc-in [:config :store :id] store-id)
                           (assoc-in [:config :store :path] identity-path))
                       {:sync? true}))
            (recur (concat (next pending) (remove nil? related))
                   (conj visited record-key))))))
    identity-path))

(defn reidentify!
  "Rewrite a copied store's stored identity to match its own path.
  One `k/get` / `k/assoc` pair per reachable branch head and commit record,
  setting `[:config :store :id]` to the path-derived id
  `seon.cluster.store/datahike-configuration` would present and
  `[:config :store :path]` to the canonical path — measured at 13.8 ms
  for a 15,000-datom store (§2.3). Runs BEFORE any `d/connect` and
  before the directory takes its final name. Returns the canonical
  store directory.
  IDEMPOTENT: a store already carrying its own path-derived id is
  rewritten to the same values.
  Refuses `::no-branch-head` (`:db` absent — the directory is not a
  store) and `::genesis-incomplete` (`:branches` absent — the
  first-create kill window, which B1 repairs by recreate and which an
  export must never carry forward)."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/dir]}
  [store-dir]
  (reidentify-at! store-dir store-dir))

(defn export!
  "Copy an open store to `<parent-dir>/store` as an independent store.
  Clone the source directory into `<parent-dir>/.store.<uuid>.tmp`
  (`/bin/cp -cR` on macOS, `cp --reflink=auto -a` on Linux — copy-on-
  write where the filesystem provides it, a byte copy where it does
  not), `reidentify!` the temp, then move it atomically onto
  `<parent-dir>/store`. Returns that canonical path; the result opens
  through `seon.cluster.store/open-store!` with its own flock, its own
  store id, and every branch of the source intact.
  Refuses `::export-exists` (`<parent-dir>/store` is already present —
  an export never overwrites a store). A host with no known
  copy-on-write command FALLS BACK, loudly (one warning naming the
  fallback and the host): create a fresh store at the temp path and
  re-transact every branch's datoms from the source — slower, never
  unavailable (robust-and-roll-with-it is the standing owner lean;
  b2-plan §9's original shape). `::clone-unsupported` refuses only
  when the fallback ALSO fails, carrying both causes."
  {:malli/schema [:=> [:cat :seon.export/request] :seon.export/path]}
  [{:keys [:seon.store/store] parent :seon.export/parent-dir}]
  (let [target (io/file parent "store")]
    (when (.exists target)
      (refuse! ::export-exists
               (str "a store already exists at " (.getPath target)
                    "; an export never overwrites one")
               {:seon.export/parent-dir parent
                :seon.export/path (.getPath target)}))
    (.mkdirs (io/file parent))
    (let [temp (io/file parent (str ".store." (random-uuid) ".tmp"))]
      (try
        (copy-store! store temp)
        (reidentify-at! (.getPath temp) (.getPath target))
        ; the temp name is the fence: only a complete, re-identified
        ; store ever takes the name anything opens
        (Files/move (.toPath temp) (.toPath target)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
        (.getCanonicalPath target)
        (catch Throwable failure
          (fs/delete-recursively! (.getPath temp) (.getPath temp))
          (throw failure))))))
