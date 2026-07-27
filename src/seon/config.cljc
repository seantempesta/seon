(ns seon.config
  "Database configuration contracts: one manifest, one singleton row.

  CONTRACT LAYER (drafted 2026-07-27, ORCHESTRATOR-SEALED same day —
  one revision at seal: the machine-derived concurrency default moved
  from a shipped literal into `defaults`' computed half). The
  implementation lane fills the stub bodies until the sealed suites
  are green and may not loosen a schema or a test.

  `config/default.edn` is THE defaults document. It is complete and explicit;
  user manifests are override maps whose absent keys inherit that document.
  Runtime consumers read only the database singleton identified by
  `:seon.config/cluster`; they never reread a manifest file.

  The closed gate is the global schema population. A manifest key without a
  registered dial schema refuses as `::unknown-key`; a registered dial whose
  value does not validate refuses as `::invalid-value` and carries the Malli
  explanation. Files that cannot be read as one EDN map refuse as
  `::manifest-unreadable`.

  Config reconciliation is provenance-scoped. `apply!` delegates to
  `seon.reconcile/reconcile!` with `managing-process-identity`, the config
  member of `seon.schema`'s owner-ruled literal three-name core trust list.
  Deriving that trust is a separate follow-up; this namespace does not create
  a second copy of the list.

  Crash walk: parsing and row derivation are pure. A non-empty apply is the
  one atomic reconcile transaction; a converged apply issues no transaction."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [datahike.api :as d]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema])
  (:import [java.nio.charset StandardCharsets]))

(def default-manifest-path
  "The one repository/artifact-relative shipped defaults document."
  "config/default.edn")

(def managing-process-identity
  "The opaque reconcile scope owned by configuration."
  "seon.db.process/config")

(defn- dial-attributes
  []
  ; entries are the [attr props? schema] vectors; filtering by shape
  ; instead of position keeps this correct whether or not the :map
  ; form carries a properties map
  (into #{}
        (comp (filter vector?) (map first))
        (schema/schema-definition :seon.config/manifest)))

(defn- required-dial-attributes
  "The dials the defaults document must carry a value for.
  A dial the EFFECTIVE shape marks `{:optional true}` MAY be absent from
  `config/default.edn`, and absence is then the state; an optional dial
  that does have an honest default carries one. The case that shaped
  this rule is `:seon.config.error/escalate-to`: it shipped absent while
  a cluster had no agent to name, and it ships as `\"root\"` now that boot
  seeds one — the requiredness question and the has-a-default question
  are DIFFERENT questions, and conflating them is what made an optional
  dial unrepresentable before. Every REQUIRED dial must still carry a
  provenanced value; that is the completeness rule that matters.

  READ FROM `:seon.config/effective`, NOT THE MANIFEST. Every manifest
  entry is `{:optional true}` by design — a user manifest declares only
  overrides — so deriving requiredness from it makes the rule vacuously
  empty and a defaults document missing every dial would pass. The
  effective shape is where a dial is required unless it genuinely is
  not, which is exactly the question being asked."
  []
  (into #{}
        (comp (filter vector?)
              (keep (fn [entry]
                      (when-not (and (map? (second entry))
                                     (:optional (second entry)))
                        (first entry)))))
        (schema/schema-definition :seon.config/effective)))

(defn- refuse!
  [rule data cause]
  (throw
   (ex-info
    (str "Configuration refused: " (name rule) ".")
    (merge {:seon.error/kind ::refused
            ::rule rule}
           data)
    cause)))

(defn- read-edn-map
  [path]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader path))]
      (let [eof (Object.)
            value (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when-not (and (map? value)
                       (identical? eof trailing))
          (throw
           (ex-info
            "A manifest must contain exactly one EDN map."
            {::path path})))
        value))
    (catch Throwable error
      (refuse! ::manifest-unreadable {::path path} error))))

(defn- validate-manifest
  [manifest]
  (let [dials (dial-attributes)]
    (doseq [key (keys manifest)]
      (when-not (contains? dials key)
        (refuse! ::unknown-key {::key key} nil)))
    (doseq [[key value] manifest]
      (when-not (schema/valid-candidate-value? key value)
        (refuse!
         ::invalid-value
         {::key key
          ::explanation (schema/explain-candidate-value key value)}
         nil)))
    manifest))

(defn- computed-defaults
  []
  {:seon.config.flow.compute/concurrency
   (long (.availableProcessors (Runtime/getRuntime)))})

(defn defaults
  "The complete default manifest — THE defaults document.
  The static half is `config/default.edn` (every constant with units
  and provenance); the computed half fills machine-derived dials —
  `:seon.config.flow.compute/concurrency` = available processors —
  because a shipped literal is only right on the machine that shipped
  it. The returned manifest is COMPLETE: every registered dial has a
  value."
  {:malli/schema [:=> [:cat] :seon.config/manifest]}
  []
  (let [manifest
        (validate-manifest
         (merge (read-edn-map default-manifest-path)
                (computed-defaults)))
        dials (required-dial-attributes)]
    (when-not (empty? (set/difference dials (set (keys manifest))))
      (refuse!
       ::invalid-value
       {::explanation
        {:seon.config/missing
         (set/difference dials (set (keys manifest)))}}
       nil))
    manifest))

(defn read-manifest
  "Read one override manifest and resolve absent keys from defaults.

  Refuses `::manifest-unreadable`, `::unknown-key`, and `::invalid-value`.
  The invalid-value refusal includes the Malli explanation."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (validate-manifest
   (merge (defaults)
          (read-edn-map path))))

(defn desired-rows
  "Derive the exact config singleton row for one cluster.

  The manifest is validated through the registered dial schemas. The row
  carries `:seon.config/cluster`, every effective dial, and the canonical
  `:seon.config/applied-manifest-digest`. Refuses `::unknown-key` and
  `::invalid-value` with the Malli explanation."
  {:malli/schema
   [:=> [:cat :seon.config/manifest :seon.boot/cluster-name]
    [:vector :map]]}
  [manifest cluster-name]
  (let [effective-manifest
        (validate-manifest (merge (defaults) manifest))]
    [(assoc effective-manifest
            :seon.config/cluster cluster-name
            :seon.config/applied-manifest-digest
            (schema/sha-256
             [(.getBytes
               ^String (schema/canonical-data-string effective-manifest)
               StandardCharsets/UTF_8)]))]))

(defn apply!
  "Reconcile one manifest into the cluster's config singleton.

  Uses `seon.reconcile/reconcile!` with `managing-process-identity`; the
  literal trust list already owned by `seon.schema` remains the admission
  input until its separately ruled computed replacement lands."
  {:malli/schema
   [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]}
  [request]
  (reconcile/reconcile!
   (:seon.config/connection request)
   {::reconcile/desired
    (desired-rows
     (:seon.config/manifest request)
     (:seon.boot/cluster-name request))
    ::reconcile/process managing-process-identity}))

(defn effective
  "The effective dial map derived from one cluster singleton."
  {:malli/schema
   [:=> [:cat :any :seon.boot/cluster-name] :seon.config/effective]}
  [db cluster-name]
  (select-keys
   (dissoc
    (or
     (d/pull
      db
      '[*]
      [:seon.config/cluster cluster-name])
     {})
    :db/id
    :seon.config/cluster
    :seon.config/applied-manifest-digest)
   (dial-attributes)))
