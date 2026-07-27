(ns seon.config
  "Database configuration contracts: one manifest, one singleton row.

  CONTRACT DRAFT (2026-07-27, B2). Implementations are deliberately absent
  until the orchestrator seals this namespace with its suites.

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
  (:require [seon.reconcile]))

(def default-manifest-path
  "The one repository/artifact-relative shipped defaults document."
  "config/default.edn")

(def managing-process-identity
  "The opaque reconcile scope owned by configuration."
  "seon.db.process/config")

(defn defaults
  "The complete manifest from THE shipped defaults document."
  {:malli/schema [:=> [:cat] :seon.config/manifest]}
  []
  (throw (ex-info "awaits implementation" {::fn `defaults})))

(defn read-manifest
  "Read one override manifest and resolve absent keys from defaults.

  Refuses `::manifest-unreadable`, `::unknown-key`, and `::invalid-value`.
  The invalid-value refusal includes the Malli explanation."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (throw (ex-info "awaits implementation"
                  {::fn `read-manifest
                   ::path path})))

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
  (throw (ex-info "awaits implementation"
                  {::fn `desired-rows
                   ::manifest manifest
                   ::cluster-name cluster-name})))

(defn apply!
  "Reconcile one manifest into the cluster's config singleton.

  Uses `seon.reconcile/reconcile!` with `managing-process-identity`; the
  literal trust list already owned by `seon.schema` remains the admission
  input until its separately ruled computed replacement lands."
  {:malli/schema
   [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]}
  [request]
  (throw (ex-info "awaits implementation"
                  {::fn `apply!
                   ::request request})))

(defn effective
  "The effective dial map derived from one cluster singleton."
  {:malli/schema
   [:=> [:cat :any :seon.boot/cluster-name] :seon.config/effective]}
  [db cluster-name]
  (throw (ex-info "awaits implementation"
                  {::fn `effective
                   ::db db
                   ::cluster-name cluster-name})))
