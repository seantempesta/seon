(ns seon.cluster.ancestor
  "The ancestor: one branch every cluster is born from, built once.

  CONTRACT LAYER (drafted + SEALED 2026-07-27 — the B2 rung, grounded
  in research/b2-plan-2026-07-27.md §0, §5.2-§5.3 and §9; implemented
  green a35c95d0a). The implementation lane fills
  the stubs until test/seon/cluster/ancestor_test.clj is green and may
  not loosen a schema or a test. Friction is reported, never resolved
  by weakening.

  The model:

  - ONE deliberate build indexes all code into the ancestor; a fresh
    cluster is a near-instant BRANCH of it, never a re-index (owner
    ruling 2026-07-27, plan README). The ancestor is a branch and not a
    directory, so its bytes are stored exactly once for every
    descendant — structural sharing on any backend.
  - ANCESTOR IDENTITY IS A DIGEST OVER THE DECLARED ROOTS, not over a
    build artifact and not over a cluster name (b2-plan §5.2 retires
    both halves of State A's `(application-digest, cluster-name)` key).
    `digest` is a pure function of file bytes: it is true in dev with no
    artifact chain, and the branch name carries it, so the roster alone
    answers \"which ancestors exist\".
  - THE ROSTER IS THE WHOLE CACHE. `ensure!` reads it; when
    `:ancestor-<digest>` is present the call is over — no connect, no
    comparison, no rebuild.
  - THE POPULATION IS INJECTED, AS DATA. `:seon.ancestor/populate` is a
    QUALIFIED SYMBOL, resolved with `requiring-resolve` and invoked with
    a live connection to the scratch branch; it transacts whatever the
    caller declares the ancestor to contain — schema facts today
    (`seon.schema.edn/load!` plus activation), program-graph facts when
    N5's indexer exists. A symbol keeps the request an ordinary
    printable value (no opaque function type on a contract boundary),
    so the fork mechanics do not wait on the producer and a suite can
    build a two-row ancestor by naming its own var.
  - PUBLISH BY RENAME-AT-END. The build runs on a scratch
    `:building-<pid>-<start-millis>-<uuid>` branch and only then
    branches `:ancestor-<digest>` from the finished scratch head and
    retires the scratch. `:ancestor-<digest>` therefore only ever
    appears COMPLETE — every crash row below depends on that
    discipline, and a partial ancestor under the real name would be
    undetectable.
  - The scratch name carries its owner's (pid, start-instant) because a
    live build and an abandoned one are the same durable state
    otherwise. A dead owner's scratch is reclaimed; a live owner's
    scratch refuses `::build-in-progress`.
  - EVERY branch operation goes through `seon.cluster.registry`, the one
    branch-lifecycle owner (b2-plan §0.6 condition 1). This namespace
    never calls `datahike.api/branch!` or `delete-branch!`.
  - Refusals are loud ex-info
    `{:seon.error/kind ::refused ::rule <which>}`, matching B0/B1
    (`src/seon/cluster/store.clj:161-167`).

  Crash walk (kill -9 at any point):

  - mid build, BEFORE the scratch branch reaches the roster: a head
    blob nothing points at. Invisible; GC sweeps it; `ensure!`
    rebuilds;
  - mid build, AFTER `:building-<…>` is in the roster: a partial
    ancestor under a scratch name. The next `ensure!` finds its owning
    process dead, retires it, and rebuilds. The `:ancestor-<digest>`
    name never appeared;
  - between the publishing `branch!`'s head write and its `:branches`
    update (`versioning.cljc:255-257`): an orphan head blob, not in the
    roster → GC sweeps it and `ensure!` re-runs;
  - after `:ancestor-<digest>` lands in the roster: a complete
    ancestor. Nothing to do — `ensure!` returns `::built? false` and
    does zero work;
  - while a scratch build is live in ANOTHER process: `ensure!` refuses
    `::build-in-progress` rather than racing a second build to the same
    name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file Files]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/ancestor.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn- refuse!
  "Refuse loudly with the one ancestor error shape."
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::rule rule))))

;;; The two facts the ancestor writes about ITSELF, so a descendant can
;;; answer \"what was I born from?\" as a query with no file read. Their
;;; Datahike declarations are DERIVED from the registered schema, never
;;; hand-written.
(def ^:private ancestor-attributes
  [:seon.ancestor/digest :seon.ancestor/built-at])

;;; What the ancestor is built FROM: source and schema files. One rule,
;;; not a list of names — anything else under a root (notes, artifacts,
;;; editor droppings) is not an input and cannot invalidate an ancestor.
(defn- source-file? [name]
  (or (str/ends-with? name ".clj")
      (str/ends-with? name ".cljc")
      (str/ends-with? name ".edn")))

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn digest
  "The ancestor digest of the declared source roots.
  SHA-256 over the sorted sequence of `[path, sha256(bytes)]` for every
  `.clj`, `.cljc`, and `.edn` file under each root — the schema EDN is
  inside `src/`, so one rule covers both halves of b2-plan §5.2. Pure,
  order-free (the roots are sorted, and each root's files are sorted by
  their path relative to it), and spelling-free (each root is
  canonicalized once, so `x` and `./x` are one root).
  Refuses `::root-absent` when a declared root is not a directory: an
  ancestor keyed by a digest of nothing is the one failure this
  function must never produce silently."
  {:malli/schema [:=> [:cat :seon.ancestor/digest-request]
                  :seon.ancestor/digest]}
  [{roots :seon.ancestor/roots}]
  (let [directories (->> roots
                         (map #(.getCanonicalFile (io/file %)))
                         distinct
                         (sort-by #(.getPath ^java.io.File %)))]
    (doseq [^java.io.File root directories]
      (when-not (.isDirectory root)
        (refuse! ::root-absent
                 (str "the declared source root " (.getPath root)
                      " is not a directory")
                 {::root (.getPath root)
                  :seon.ancestor/roots roots})))
    (schema/sha-256
     (for [^java.io.File root directories
           :let [prefix (inc (count (.getPath root)))]
           entry (->> (file-seq root)
                      (filter #(.isFile ^java.io.File %))
                      (filter #(source-file? (.getName ^java.io.File %)))
                      (sort-by #(subs (.getPath ^java.io.File %) prefix)))]
       (.getBytes
        (str (subs (.getPath ^java.io.File entry) prefix)
             "\u0000"
             (schema/sha-256 [(Files/readAllBytes
                               (.toPath ^java.io.File entry))])
             "\n")
        "UTF-8")))))

(defn ancestor-branch
  "The ONE branch name for a digest: `:ancestor-<digest>`.
  One derivation — the digest is discoverable from the roster alone."
  {:malli/schema [:=> [:cat :seon.ancestor/digest] :seon.ancestor/branch]}
  [digest]
  (keyword (str "ancestor-" digest)))

;;; ---------------------------------------------------------------------------
;;; The build
;;; ---------------------------------------------------------------------------

;;; One build per process at a time. Without it two threads would scan
;;; for abandoned scratch branches while the other's build is live, and
;;; each would read the other's pid as \"alive\" — true, but useless,
;;; because the pid is OURS. The monitor makes the (pid, start-instant)
;;; rule answer only about OTHER processes.
(defonce ^:private build-monitor (Object.))

(def ^:private scratch-pattern #"^building-(\d+)-(\d+)-.+$")

(defn- scratch-branch
  "A scratch name carrying this process's (pid, start-instant)."
  []
  (let [handle (java.lang.ProcessHandle/current)
        start (.startInstant (.info handle))]
    (when-not (.isPresent start)
      (refuse! ::process-start-unavailable
               "this process has no start instant to own a build with"
               {::pid (.pid handle)}))
    (keyword (str "building-" (.pid handle) "-"
                  (.toEpochMilli ^java.time.Instant (.get start)) "-"
                  (random-uuid)))))

(defn- owner-alive?
  "True when the (pid, start-instant) a scratch name carries is live.
  The start instant is what makes a recycled pid read as dead; the
  platform truncates to milliseconds on both sides
  (`src/seon/cluster.clj:344-359` answers the same question about an
  advertisement — see the issue note asking for one owner)."
  [pid start-millis]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (boolean
       (when (.isPresent optional)
         (let [handle (.get optional)
               start (.startInstant (.info handle))]
           (and (.isAlive handle)
                (.isPresent start)
                (= (long start-millis)
                   (.toEpochMilli ^java.time.Instant (.get start))))))))
    (catch Throwable _
      false)))

(defn- reclaim-scratch-branches!
  "Retire every abandoned scratch branch; refuse if one is still live."
  [store]
  (doseq [candidate (registry/roster store)
          :let [match (re-matches scratch-pattern (name candidate))]
          :when match]
    (let [[_ pid start-millis] match]
      (if (owner-alive? (parse-long pid) (parse-long start-millis))
        (refuse! ::build-in-progress
                 (str "branch " candidate
                      " is a live build owned by process " pid)
                 {::branch candidate ::pid (parse-long pid)})
        (registry/retire-branch! {:seon.store/store store
                                  :seon.store/branch candidate}))))
  nil)

(defn- resolve-population
  "The injected population function behind its qualified symbol."
  [populate ancestor-digest]
  (or (try
        (requiring-resolve populate)
        (catch Throwable _ nil))
      (refuse! ::populate-unresolvable
               (str "the population " populate " does not resolve")
               {:seon.ancestor/populate populate
                :seon.ancestor/digest ancestor-digest})))

(defn ensure!
  "Ensure `:ancestor-<digest>` exists on the store; build it if absent.
  Present in the roster → `{::branch b ::built? false}` and ZERO work:
  no connection, no population call, no transaction.
  Absent → branch a scratch `:building-<pid>-<start-millis>-<uuid>` off
  `:db`, connect to it through `seon.cluster.store/open-branch!`,
  `requiring-resolve` `:seon.ancestor/populate` and invoke it with
  `{:seon.store/branch-connection conn
  :seon.ancestor/digest d}`, transact the ancestor's own two facts
  (`:seon.ancestor/digest`, `:seon.ancestor/built-at`) with their
  attribute declarations DERIVED from the registered schema through
  `seon.schema.datahike/malli->datahike-schema` — never hand-written —
  release the connection, publish `:ancestor-<digest>` from the scratch
  head through `seon.cluster.registry/branch!`, and retire the scratch.
  Returns `{::branch b ::built? true}`.
  A `:building-*` branch whose owning process is DEAD is retired first
  and the build proceeds; one whose owner is ALIVE refuses
  `::build-in-progress` with that branch named — two builds of one
  digest must never race.
  A population function that throws retires its own scratch branch and
  propagates: the ancestor name did not appear, and the next `ensure!`
  simply rebuilds. (A kill -9 mid-population DOES leave the scratch
  behind — the dead-owner reclaim in the crash walk covers exactly
  that.) A `:seon.ancestor/populate` symbol
  that does not resolve refuses `::populate-unresolvable` BEFORE any
  branch is created."
  {:malli/schema [:=> [:cat :seon.ancestor/ensure-request]
                  :seon.ancestor/ensured]}
  [{:keys [:seon.store/store]
    ancestor-digest :seon.ancestor/digest
    populate :seon.ancestor/populate}]
  (let [branch (ancestor-branch ancestor-digest)]
    (if (contains? (registry/roster store) branch)
      ; the roster is the whole cache: no connection, no comparison
      {:seon.ancestor/branch branch :seon.ancestor/built? false}
      (locking build-monitor
        (if (contains? (registry/roster store) branch)
          {:seon.ancestor/branch branch :seon.ancestor/built? false}
          (let [populate-fn (resolve-population populate ancestor-digest)]
            (reclaim-scratch-branches! store)
            (let [scratch (scratch-branch)]
              (registry/branch! {:seon.store/store store
                                 :seon.cluster.registry/from :db
                                 :seon.store/branch scratch})
              (try
                (let [connection (store/open-branch! store scratch)]
                  (try
                    (populate-fn {:seon.store/branch-connection connection
                                  :seon.ancestor/digest ancestor-digest})
                    (d/transact connection
                                {:tx-data (schema.datahike/malli->datahike-schema
                                           ancestor-attributes)})
                    (d/transact connection
                                {:tx-data
                                 [{:seon.ancestor/digest ancestor-digest
                                   :seon.ancestor/built-at (java.util.Date.)}]})
                    (finally
                      (d/release connection))))
                ; rename-at-end: the ancestor name appears only now, over
                ; a branch that is already complete
                (registry/branch! {:seon.store/store store
                                   :seon.cluster.registry/from scratch
                                   :seon.store/branch branch})
                (catch Throwable failure
                  ; the ancestor name never appeared. Drop our own scratch
                  ; so a later build is not blocked by wreckage this
                  ; process can prove is abandoned — the kill -9 case has
                  ; no such luxury and is what the (pid, start-instant)
                  ; reclaim covers.
                  (try
                    (registry/retire-branch! {:seon.store/store store
                                              :seon.store/branch scratch})
                    (catch Throwable _ nil))
                  (throw failure)))
              (registry/retire-branch! {:seon.store/store store
                                        :seon.store/branch scratch})
              {:seon.ancestor/branch branch
               :seon.ancestor/built? true})))))))
