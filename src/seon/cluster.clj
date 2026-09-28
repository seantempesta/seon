(ns seon.cluster
  "Process entry for starting, inspecting, and stopping named clusters.

  `start!` resolves the closed bootstrap configuration, opens and
  advertises an io-prepl, then builds the remaining instance from the
  process-root store and executors, the published source commit, the
  cluster's database branch and configuration, recovered run facts,
  the root agent, agent and render flows, and the web server. The
  io-prepl and the partially built instance remain available when a
  later layer fails.

  A JVM may host several named cluster instances. They share the
  process-root store and executor pair; branch connections, flows,
  routing state, advertisements, and web servers remain per cluster.
  `readiness` derives its report from the instance and its database.
  `stop!` idempotently unwinds only the addressed instance and releases
  the shared store when its last holder stops."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.dispatch :as async.dispatch]
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.core.async.flow :as flow.core]
            [clojure.core.server]
            [clojure.set :as set]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.instruction :as instruction]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.wake :as wake]
            [seon.fault :as fault]
            [seon.turn :as turn]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [datahike.schema]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.source :as source]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [clojure.string :as str]
            [seon.await :as await]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [babashka.fs]
            [seon.instrument :as instrument]
            [seon.profile :as profile]
            [seon.cache :as cache]
            [seon.id :as id]
            [seon.test.cache :as test.cache]
            [seon.operator.runtime :refer [root-store-holder running-instances]]
            [seon.program :as program]
            [seon.render.value :as render.value]
            [seon.schedule :as schedule]
            [seon.render.web :as web]
            [seon.sci.eval :as sci.eval]
            [taoensso.timbre :as log]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal])
  (:import [java.nio.file InvalidPathException Paths]
           [java.util Date]
           [java.util.concurrent Executor]))

;;; ---------------------------------------------------------------------------
;;; Bootstrap configuration — the CLOSED pre-store key set.
;;; A key that the database could own does not belong here; the closed
;;; map makes that a review-time refusal, not a convention.
;;; ---------------------------------------------------------------------------

;;; The running instance value returned by start!. Named predicates for
;;; the genuinely opaque platform objects; everything else is ordinary
;;; data.

(def ^:dynamic ^:private *source-progress!*
  (constantly nil))

(defn- report-source-progress!
  {:malli/schema [:=> [:cat :string] :nil]}
  [phase]
  (*source-progress!* phase)
  ;; Clojure prepl writes through PrintWriter, which records an IOException
  ;; instead of throwing it. A departed observer must not leave queued
  ;; publications doing work after their lifecycle caller has timed out.
  (when (and (instance? java.io.PrintWriter *out*)
             (.checkError ^java.io.PrintWriter *out*))
    (let [message (str "Source publication observer closed in phase " phase ".")]
      (throw (ex-info message
                      {:seon.error/at (Date.)
                       :seon.error/layer :seon.cluster/publication
                       :seon.error/operation 'seon.cluster/report-source-progress!
                       :seon.error/message message
                       :seon.cluster/source-observer-closed phase
                       :seon.source/progress phase}))))
  nil)

(defn- report-analysis-warnings!
  "Report finding differences only for the files this publication replaced."
  {:malli/schema [:=> [:cat [:maybe [:vector :seon.lint/finding]]
                       [:vector :seon.lint/finding]] :nil]}
  [previous findings]
  (let [current (set findings)
        before (when previous (set previous))]
    (report-source-progress!
     (str "findings in analyzed files: " (count current)
          (if previous
            (str "; added=" (count (set/difference current before))
                 "; resolved=" (count (set/difference before current)))
            "; delta unavailable: no previous publication"))))
  nil)

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

(defn cluster-name?
  "True when `value` is one valid relative path segment.

  This is the ruled 2026-08-13 input narrowing: cluster names contain no path
  separator and are neither `.` nor `..`, so cluster-path derivation cannot
  escape or alias the cluster root."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (string? value)
       (not (empty? value))
       (not (#{"." ".."} value))
       (not (str/includes? value "/"))
       (not (str/includes? value "\\"))
       (try
         (let [path (Paths/get value (make-array String 0))]
           (and (not (.isAbsolute path))
                (= 1 (.getNameCount path))))
         (catch InvalidPathException _
           false))))

(def cluster-name-generator
  (gen/one-of
   [(gen/fmap (fn [characters] (apply str characters))
              (gen/vector
               (gen/elements
                (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"))
               1 48))
    (gen/elements ["default" "alpha.beta"])]))

(schema/register-core-predicate! 'seon.cluster/cluster-name?
                                 cluster-name?)

;;; Each generation makes a FRESH UNBOUND server socket. One shared
;;; delayed socket bound a real process port that no cluster owned and
;;; handed every later contract check the same object, so closing one
;;; sample invalidated the next. The no-argument constructor binds no
;;; port and opens no descriptor.
(def socket-server-generator
  (gen/fmap (fn [_] (java.net.ServerSocket.)) (gen/return nil)))

(defn- ref-identity
  [database ref attribute]
  (when (and database (:db/id ref))
    (get (db/pull database [attribute] (:db/id ref)) attribute)))

(def ^:private cluster-selector
  "What the cluster's AI face reads: its name, configuration, instructions and toolkit."
  [:seon.cluster/name {:seon.cluster/config [:seon.config/cluster]}
   :seon.cluster/instructions :seon.cluster/toolkit])

(defn render-ai
  "The cluster as data: its name, configuration, and how many shared
  instructions and toolkit namespaces it holds."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.cluster/view]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (let [config-name (or (get-in unit [:seon.cluster/config :seon.config/cluster])
                          (ref-identity (:seon.db/db unit) (:seon.cluster/config unit) :seon.config/cluster))]
      (cond-> {:seon.cluster/name name
               :seon.cluster/instruction-count (count (:seon.cluster/instructions unit))
               :seon.cluster/toolkit-count (count (:seon.cluster/toolkit unit))}
        config-name (assoc :seon.config/cluster config-name)))))

(defn render-form
  "The read that finds this cluster again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/read-form]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (list 'seon.db/pull (list 'quote cluster-selector) [:seon.cluster/name name])))

(defn render-html
  "`:seon.render/html` — one readable cluster card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (let [database (:seon.db/db unit)
          config-name (ref-identity database
                                    (:seon.cluster/config unit)
                                    :seon.config/cluster)]
      [:article {:class "seon-family-entry seon-cluster-entry"}
       [:h3 (str "Cluster " name)]
       [:dl
        [:div [:dt "Configuration"] [:dd (str (or config-name "Connected"))]]
        [:div [:dt "Shared instructions"]
         [:dd (str (count (:seon.cluster/instructions unit)))]]
        [:div [:dt "Toolkit namespaces"]
         [:dd (str (count (:seon.cluster/toolkit unit)))]]]])))



(def ^:dynamic ^:private *boot-progress!*
  (constantly nil))

;;; ---------------------------------------------------------------------------
;;; Loaded-code identity: a host evaluation that changed it re-renders pages
;;; (`seon.mcp`). The cluster's health is `seon.health/report`.
;;; ---------------------------------------------------------------------------

(defn identity-hash-map?
  "True for a java.util.IdentityHashMap."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? java.util.IdentityHashMap value))

(defn loaded-code-mark
  "This JVM's loaded-code identity: `clojure.lang.Var/rev`, which every Var
  creation and root change advances (`Var.java:78,188,282-314`), and each
  namespace's mappings map, which an intern or unmap replaces."
  {:malli/schema [:=> [:cat] [:tuple :int [:fn {:error/message "must be an IdentityHashMap"}
                                           seon.cluster/identity-hash-map?]]]}
  []
  (let [mappings (java.util.IdentityHashMap.)]
    (doseq [^clojure.lang.Namespace namespace-object (all-ns)]
      (.put mappings namespace-object (.getMappings namespace-object)))
    [clojure.lang.Var/rev mappings]))

(defn loaded-code-changed?
  "True when this JVM's loaded code differs from `mark` (`loaded-code-mark`)."
  {:malli/schema [:=> [:cat [:tuple :int [:fn seon.cluster/identity-hash-map?]]] :boolean]}
  [[rev ^java.util.IdentityHashMap mappings]]
  (let [[now-rev ^java.util.IdentityHashMap now-mappings] (loaded-code-mark)]
    (boolean
     (or (not= rev now-rev)
         (not= (.size mappings) (.size now-mappings))
         (some (fn [[namespace-object current]]
                 (not (identical? current (.get mappings namespace-object))))
               now-mappings)))))

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn- refused!
  {:malli/schema [:=> [:cat :string :seon.schema/value] :nil]}
  [message offense]
  (throw (ex-info message
                  (merge
                   {:seon.cluster.boot/disposition :refused
                    :seon.error/message message
                    ;; A projection is execution input, not refusal evidence.
                    :seon.boot/offense
                    (if (map? offense)
                      (dissoc offense :seon.schema/projection :seon.cluster.source/phase)
                      offense)}
                   (when (map? offense)
                     (select-keys offense [:seon.cluster.source/phase]))))))

(defn- require-candidate-value
  [projection schema-key value message]
  (if (schema/valid-candidate-value? projection schema-key value)
    value
    (refused! message
              {:seon.boot/schema schema-key
               :seon.boot/value value
               :seon.boot/explanation
               (schema/explain-candidate-value projection schema-key value)})))

(declare cluster-paths operator-root)

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs,
  store-dir derived as <operator-root>/data/store — the process-root
  store every cluster branches from.
  Refuses (throws ex-info {:seon.cluster.boot/disposition :refused ...}) when a
  declared key has an invalid value. Extra keys remain available for
  accretion."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  ;; ONE declaration population for the whole resolution — it asks two
  ;; questions and each refusal arm asks a third.
  (let [projection (schema/declaration-projection (schema.edn/packaged-forms))]
    (require-candidate-value
     projection
     :seon.boot/overrides
     overrides
     "The bootstrap overrides were refused.")
    (let [defaults {:seon.boot/cluster-name "default"
                    :seon.boot/root "data/clusters"
                    :seon.boot/prepl-host "127.0.0.1"
                    :seon.boot/prepl-port 0}
          base (merge defaults overrides)
          derived-store-dir
          (str (io/file (operator-root (:seon.boot/root base))
                        "data" "store"))
          derived-log-dir
          (:seon.boot/log-dir
           (cluster-paths (:seon.boot/root base)
                          (:seon.boot/cluster-name base)))]
      (require-candidate-value
       projection
       :seon.boot/config
       (merge {:seon.boot/log-dir derived-log-dir
               :seon.boot/store-dir derived-store-dir}
              base)
       "The resolved bootstrap configuration was refused."))))

(defn cluster-paths
  "Derive every per-cluster path from (root, cluster-name).
  Convention owns the layout: the cluster directory, its log directory,
  and its advertisement file. The STORE is per process root under
  branch-per-cluster (b2-plan section 0); its path is bootstrap config,
  never a per-cluster derivation. One derivation — no other code builds
  these paths."
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:map
                   [:seon.boot/cluster-dir :string]
                   [:seon.boot/advertisement-file :string]
                   [:seon.boot/log-dir :string]]]}
  [root cluster-name]
  (let [cluster-dir (io/file root cluster-name)]
    {:seon.boot/cluster-dir (str cluster-dir)
     :seon.boot/advertisement-file
     (str (io/file cluster-dir "prepl.edn"))
     :seon.boot/log-dir (str (io/file cluster-dir "logs"))}))

;;; ---------------------------------------------------------------------------
;;; The instance lifecycle
;;; ---------------------------------------------------------------------------

(defn- operator-root
  "The process root whose `data/store` this cluster root branches from.

  DERIVED FROM THE ROOT THE CALLER GENUINELY HOLDS, never from the process
  working directory. `<root>/data/clusters` names the operator root above it;
  any other cluster root names itself. The declared
  `-Dseon.operator.root` — set on every JVM `bin/seon [--root PATH]`
  launches — answers only when the cluster root is relative and therefore
  resolves to the working directory, which is the developer's checkout.

  The removed fallback is the pair that wiped the development store: the
  default cluster root \"data/clusters\" is relative, so an undeclared JVM
  derived the checkout, while a declared JVM let its property outrank an
  explicit `tmp/` fixture root and pointed that fixture at the developer's
  live `data/store`. An undeclared JVM whose cluster root resolves to the
  working directory is now a typed refusal naming it."
  [cluster-root]
  (let [declared (System/getProperty "seon.operator.root")
        root-file (.getCanonicalFile (io/file cluster-root))
        parent (.getParentFile root-file)
        derived (if (and (= "clusters" (.getName root-file))
                         parent (= "data" (.getName parent)))
                  (.getCanonicalPath (.getParentFile parent))
                  (.getCanonicalPath root-file))
        working (.getCanonicalPath (io/file (System/getProperty "user.dir")))]
    (cond
      ; the caller handed a root of its own: it wins over any property
      (not= derived working) derived

      (not (str/blank? declared)) declared

      :else
      (refused!
       "The operator root is undeclared and the cluster root resolves to the working directory."
       {:seon.boot/rule ::undeclared-operator-root
        :seon.boot/root cluster-root
        :seon.boot/working-directory working}))))

(defn warn-low-space!
  "Observe volume capacity and apply the supplied low-space policy."
  {:malli/schema [:=> [:cat :string :seon.config/effective]
                  [:map [:seon.operator.footprint/root :string]
                   [:seon.operator.footprint/usable-bytes [:int {:min 0}]]
                   [:seon.operator.footprint/total-bytes [:int {:min 0}]]
                   [:seon.operator.footprint/usable-ratio :double]
                   [:seon.operator.footprint/observed-at :inst]]]}
  [managed-root effective]
  ;; statfs only — the boot path must never pay a recursive directory
  ;; walk (a checkout carrying frozen tmp/ evidence took ~94 s, which is
  ;; the P19 boot-readiness failure of 2026-08-05).
  (let [footprint (fs/filesystem-space managed-root)
        low? (or (< (:seon.operator.footprint/usable-bytes footprint)
                    (:seon.config.maintenance/min-usable-bytes effective))
                 (< (:seon.operator.footprint/usable-ratio footprint)
                    (:seon.config.maintenance/min-usable-ratio effective)))]
    (when low?
      (log/warn
       (format "LOW DISK SPACE under %s: %.2f GiB usable (%.1f%%); the boot continues."
               managed-root
               (/ (double (:seon.operator.footprint/usable-bytes footprint))
                  1073741824.0)
               (* 100.0 (:seon.operator.footprint/usable-ratio footprint)))))
    footprint))

(defn write-advertisement!
  [paths advertisement]
  (fs/write-edn-atomically! (:seon.boot/advertisement-file paths) advertisement))

;;; ---------------------------------------------------------------------------
;;; The process-root store — opened once, shared by every instance
;;; ---------------------------------------------------------------------------

;;; The operator runtime owns the process-global holder. The count is the
;;; holder count, not a status flag — the last instance out releases the store,
;;; and the flock with it.

(defn- root-store-key
  "The canonical key for one physical process-root store."
  [store-dir]
  (.getCanonicalPath (io/file store-dir)))

(defn acquire-root-store!
  "The ONE store at `store-dir`, opened on first use and shared after.

  A supplied history policy is creation-fixed for the whole operator root.
  A later cluster in the same JVM must request the held representation."
  ([store-dir]
   (acquire-root-store! store-dir ::unspecified-history-policy))
  ([store-dir keep-history?]
   (acquire-root-store! store-dir keep-history? false))
  ([store-dir keep-history? destroy?]
   (let [store-key (root-store-key store-dir)
         requested? (not= ::unspecified-history-policy keep-history?)]
     (locking root-store-holder
       (if-let [held (get @root-store-holder store-key)]
         (let [_ (when destroy? (refused! "Cannot destroy a held store." {:seon.store/dir store-key}))
               store (:seon.store/store held)
               main-connection (:seon.store/connection-object store)
               held-keep-history?
               (get-in @main-connection [:config :keep-history?])]
           (when (and requested?
                      (not= keep-history? held-keep-history?))
             (refused!
              "The requested history policy conflicts with the held operator-root store."
              {:seon.boot/rule ::keep-history-mismatch
               :seon.config.db/keep-history? keep-history?
               :seon.store/keep-history? held-keep-history?
               :seon.store/dir store-key}))
           (swap! root-store-holder update-in [store-key ::holders] inc)
           store)
         ; open OUTSIDE the map first: a failed open must leave no entry
         (let [request (cond-> {:seon.store/dir store-key :seon.store/destroy? destroy?}
                         requested?
                         (assoc :seon.config.db/keep-history? keep-history?))
               store (store/open-store! request)]
           (swap! root-store-holder assoc store-key
                  {:seon.store/store store ::holders 1})
           store))))))

(defn release-root-store!
  "Drop one holder; the LAST one releases the store and its flock."
  [store-dir]
  (let [store-key (root-store-key store-dir)]
    (locking root-store-holder
      (when-let [held (get @root-store-holder store-key)]
        (let [remaining (dec (long (::holders held)))]
          (if (pos? remaining)
            (swap! root-store-holder assoc-in [store-key ::holders] remaining)
            (do
              ; release FIRST: a failure leaves this exact flock-held store
              ; addressable here for the stop retry
              (store/release-store! (:seon.store/store held))
              (swap! root-store-holder dissoc store-key)))))))
  nil)

;;; ---------------------------------------------------------------------------
;;; The default source population
;;; ---------------------------------------------------------------------------

(def boot-process-identity
  "The opaque provenance identity for the boot schema population."
  "seon.db.process/boot")

(declare require-committed!)

(defn- accretive-property-change?
  "Does Datahike apply this one property change to an INSTALLED attribute, data kept?

  The rule is the dependency's own acceptance rule,
  `datahike.schema/find-invalid-schema-updates`
  (`reference-code/datahike/src/datahike/schema.cljc:257`):

  - `:db/index` — an index may be added monotonically to an existing
    attribute; the transactor atomically backfills AVET before publishing the
    resulting database value, and removal remains unsupported
    (`reference-code/datahike/src/datahike/schema.cljc:277`, enforced again per
    datom at `reference-code/datahike/src/datahike/db/transaction.cljc:105`).
  - `:db/doc`, `:db/noHistory`, `:db/isComponent` — always updatable
    (`reference-code/datahike/src/datahike/schema.cljc:285`), dropping included:
    Datahike retracts such a property from an installed attribute without a
    current-data check (`reference-code/datahike/src/datahike/db/transaction.cljc:306`),
    so `declaration-changes` retracts a dropped one before transacting the
    declaration (an upsert alone cannot retract it — 2026-09-16,
    `docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`).
  - `:db/cardinality` — one may widen to many unless the installed attribute
    carries a `:db/unique` constraint
    (`reference-code/datahike/src/datahike/schema.cljc:264`).
  - `:db/unique` — a cardinality-one attribute that is already unique may
    switch between `:db.unique/value` and `:db.unique/identity`; ADDING or
    dropping uniqueness is not an update Datahike applies
    (`reference-code/datahike/src/datahike/schema.cljc:270`).

  Every other difference — `:db/valueType`, `:db/tupleType`, a dropped
  `:db/unique` or `:db/index`… — answers false, and `declaration-changes`
  replaces the attribute with its data dropped."
  {:malli/schema [:=> [:cat :map :map :keyword] :boolean]}
  [installed declaration property]
  (let [installed-value (get installed property)
        declared-value (get declaration property)]
    (case property
      :db/index (and (nil? installed-value) (true? declared-value))
      (:db/doc :db/noHistory :db/isComponent) true
      :db/cardinality (and (= :db.cardinality/one installed-value)
                           (= :db.cardinality/many declared-value)
                           (nil? (:db/unique installed)))
      :db/unique (and (some? installed-value)
                      (some? declared-value)
                      (= :db.cardinality/one (:db/cardinality installed)))
      false)))

(defn- declaration-property-changes
  "Every storage property where the installed attribute and the declaration differ.

  The comparison is the UNION of both declarations' properties. Selecting only the
  keys the CURRENT declaration carries read a DROPPED property as compatible — the
  branch kept an installed `:db/unique` the bridge no longer derives, and the
  stale identity surfaced much later as a program-indexing conflict naming the
  wrong cause (2026-09-16,
  `docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`).
  Absence of the property IS the signal, so it is compared rather than skipped.
  Datahike's `:schema` entry holds exactly the declaration datoms transacted
  for that attribute plus its `:db/ident`
  (`reference-code/datahike/src/datahike/db/transaction.cljc:90`), so the two
  maps are comparable once `:db/ident` is dropped."
  {:malli/schema [:=> [:cat :map :map] [:vector :keyword]]}
  [installed declaration]
  (let [installed (dissoc installed :db/ident)
        declaration (dissoc declaration :db/ident)]
    (into []
          (remove #(= (get installed %) (get declaration %)))
          (sort (into #{} (concat (keys installed) (keys declaration)))))))

(defn- attribute-retraction
  "Transaction data removing one installed attribute and every current datom of it.

  Datahike refuses to retract an attribute that still carries current datoms
  (`reject-schema-removal-with-current-data`,
  `reference-code/datahike/src/datahike/db/transaction.cljc:137`, which reads
  only the current AEVT index), and applies the operations of one transaction
  in order, so the data goes first and the attribute entity last.

  Ordinarily each current datom is retracted by its native `[e a v]`, and a
  component value as an entity; history keeps what existed. `purge?` is true
  only when the attribute is reinstalled with a DIFFERENT value type: a
  retained history datom of the old type would be compared against the new
  type's values in Datahike's temporal index and throw on the first write to
  the same entity (`ClassCastException` in `datahike.datom/compare-value`), so
  on a temporal store every entity the history names is purged with
  `:db.purge/attribute` (`reference-code/datahike/src/datahike/db/transaction.cljc:1095`),
  current and historical datoms and component values. Neither ever retracts
  the entity carrying the datom, which would delete its other attributes.
  Work is one AEVT range read of this attribute's datoms."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :boolean]
                  :seon.store/transaction-data]}
  [database attribute purge?]
  (conj
   (if (and purge? (:keep-history? (:config database)))
     (into []
           (comp (map :e) (distinct) (map #(vector :db.purge/attribute % attribute)))
           (d/datoms (d/history database) :aevt attribute))
     (let [component? (true? (get-in (:schema database) [attribute :db/isComponent]))]
       (into []
             (map (fn [datom]
                    (if component?
                      [:db/retractEntity (:v datom)]
                      [:db/retract (:e datom) attribute (:v datom)])))
             (d/datoms database :aevt attribute))))
   [:db/retractEntity attribute]))

(defn- retired-attributes
  "Installed attributes no declaration in `projection` or the branch still names.

  The projection's forms are the declaration authority: a key it still
  registers — as an attribute or as any other form, like the source seal's own
  attributes `seon.cluster.source` installs from registered forms — is not
  retired. Datahike's own implicit attributes are not Seon declarations
  (`datahike.schema/implicit-schema-spec`,
  `reference-code/datahike/src/datahike/schema.cljc:89`). An attribute whose
  `:seon.schema/key` row on this branch was admitted by an agent rather than
  the packaged population belongs to that agent's program and the turn's schema
  change owns it, so the packaged population never retires it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection]
                  [:vector :qualified-keyword]]}
  [database projection]
  (let [forms (:seon.schema.projection/forms projection)
        entities? (contains? (:schema database) :seon.schema/key)]
    ;; Datahike's `:schema` also maps each attribute's entity id to its ident
    ;; (`reference-code/datahike/src/datahike/db/transaction.cljc:120`), so the
    ;; keys mix longs and keywords: select idents BEFORE sorting.
    (into []
          (comp
           (remove #(contains? datahike.schema/implicit-schema-spec %))
           (remove #(contains? forms %))
           (remove (fn [attribute]
                     (some (fn [entity]
                             (some #(not= :core (:v %))
                                   (d/datoms database :eavt (:e entity)
                                             :seon.schema.admission/source)))
                           (when entities?
                             (d/datoms database :avet :seon.schema/key attribute))))))
          (sort (filter qualified-keyword? (keys (:schema database)))))))

(defn- declared-attributes
  "The Datahike attribute declarations `projection` derives, in attribute order.

  A function of the projection value alone, so it is memoized on the
  projection's own cache (`seon.schema/projection-cache-value`): a converged
  reopen under a held projection derives it once, not once per reopen."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :map]]}
  [projection]
  (schema/projection-cache-value
   projection [::declared-attributes]
   #(vec (schema.datahike/malli->datahike-schema-in
          projection
          (schema/canonical-database-attributes projection)))))

(defn- attribute-adoption
  "How one declaration adopts on this branch: `:install`, `:converged`, `:accrete` or `:replace`."
  {:malli/schema [:=> [:cat [:or :nil :map] :map]
                  [:enum :install :converged :accrete :replace]]}
  [installed declaration]
  (cond
    (nil? installed) :install
    (= installed declaration) :converged
    :else
    (let [changes (declaration-property-changes installed declaration)]
      (cond
        (empty? changes) :converged
        (every? #(accretive-property-change? installed declaration %) changes) :accrete
        :else :replace))))

(defn- dropped-attributes
  "Installed attributes whose current datoms the declaration changes drop.

  The replaced attributes (`attribute-adoption` answers `:replace`) and the
  retired ones (`retired-attributes`), both read from `database` before the
  change is transacted."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection]
                  [:vector :qualified-keyword]]}
  [database projection]
  (let [installed-schema (:schema database)]
    (into (into []
                (keep (fn [{attribute :db/ident :as declaration}]
                        (when (= :replace (attribute-adoption
                                           (get installed-schema attribute)
                                           declaration))
                          attribute)))
                (declared-attributes projection))
          (retired-attributes database projection))))

(def ^:private storage-type-properties
  "The declaration properties that fix the stored value type."
  [:db/valueType :db/tupleType :db/tupleTypes :db/tupleAttrs])

(declare invalid-value-retraction)

(defn attribute-change-tx
  "Transaction data bringing each named installed attribute to its declaration IN PLACE.

  THE one schema-change mechanism: the packaged population
  (`declaration-changes`) and an agent's schema declaration
  (`seon.turn/row-tx`) both call it. For each attribute, in the order given,
  `declarations` holds its new Datahike declaration or no entry when it is
  removed. `attribute-adoption` chooses the path:

  - `:install` — the declaration is transacted;
  - `:converged` — nothing;
  - `:accrete` — a dropped always-updatable property is retracted from the
    attribute entity, then the declaration is transacted; data kept;
  - `:replace` — `attribute-retraction` drops the data and the attribute,
    then the declaration is reinstalled after it in the same transaction;
  - no declaration but installed — `attribute-retraction` drops it.

  With `projection` (the declarations' new Malli world) every kept attribute
  — converged or accreted — also drops the current values its new form
  refuses (`invalid-value-retraction`), before its declaration. What must
  survive is enforced by the one transaction owner: a surviving writer of a
  removed attribute refuses the transaction by name
  (`seon.db/removed-definition-error`, plan 1.3e), and an entity left
  without a required value or ref refuses through final owning-value
  validation."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value [:sequential :qualified-keyword]
          [:map-of :qualified-keyword :map]]
     :seon.store/transaction-data]
    [:=> [:cat :seon.db/database-value [:sequential :qualified-keyword]
          [:map-of :qualified-keyword :map] [:or :nil :seon.schema/projection]]
     :seon.store/transaction-data]]}
  ([database attributes declarations]
   (attribute-change-tx database attributes declarations nil))
  ([database attributes declarations projection]
   (let [installed-schema (:schema database)
         invalid (fn [attribute]
                   (if projection
                     (invalid-value-retraction database projection attribute)
                     []))]
     (into []
           (mapcat
            (fn [attribute]
              (let [installed (get installed-schema attribute)
                    declaration (get declarations attribute)]
                (cond
                  (and (nil? declaration) (nil? installed)) []
                  (nil? declaration) (attribute-retraction database attribute false)
                  :else
                  (case (attribute-adoption installed declaration)
                    :install [declaration]
                    :converged (invalid attribute)
                    ;; a dropped property is retracted; an upsert cannot drop it
                    :accrete (-> (invalid attribute)
                                 (into (comp
                                        (remove #(contains? declaration %))
                                        (map #(vector :db/retract attribute % (get installed %))))
                                       (declaration-property-changes installed declaration))
                                 (conj declaration))
                    :replace (conj (attribute-retraction
                                    database attribute
                                    (not= (select-keys installed storage-type-properties)
                                          (select-keys declaration storage-type-properties)))
                                   declaration))))))
           attributes))))

(defn invalid-value-retraction
  "Transaction data retracting the current values of `attribute` its new form refuses.

  A form change that keeps the storage declaration (`[:int]` to `[:int {:min 1}]`)
  changes no Datahike attribute, yet current data may no longer satisfy it.
  Under the owner's schema-change ruling the problematic data is dropped:
  every current value is read in its logical form (EDN-encoded attributes are
  decoded against `projection`) and validated with the rule the writer applies
  to a stored value. A cardinality-many attribute stores members, not its
  declared collection, so each member is validated against the declaration's
  element schema (`seon.schema.datahike/value-schema`, as the writer's
  attribute plan does); only refused members are retracted, by their native
  `[e a v]`. Work is one AEVT range read of the attribute.

  Edge cases: a `[:vector …]` many attribute whose declaration is unchanged
  retracts nothing (validating a set against it refused every value). An
  element held in a vector cell is retracted from its parent, `[:db/retract
  parent a v]`, so the vector request renumbers the survivors instead of
  leaving an empty cell."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :qualified-keyword]
                  :seon.store/transaction-data]}
  [database projection attribute]
  (if-not (contains? (:schema database) attribute)
    []
    (let [many? (= :db.cardinality/many (get-in (:schema database) [attribute :db/cardinality]))
          valid? (if many?
                   (m/validator (schema.datahike/value-schema
                                 (mr/schema (:seon.schema.projection/registry projection) attribute)))
                   (schema/projection-validator projection attribute))
          decode #(schema.datahike/decode-attribute-value-in projection attribute %)]
      (into []
            (keep #(when-not (valid? (decode (:v %)))
                     ;; An element held in a vector cell is retracted from its
                     ;; parent's logical vector: the request renumbers the survivors.
                     [:db/retract (or (when (and (get (:schema database) :seon.db.vector/position)
                                                 (first (d/datoms database :eavt (:e %) :seon.db.vector/position)))
                                        (:e (first (d/datoms database :avet :seon.db.vector/cells (:e %)))))
                                      (:e %))
                      attribute (:v %)]))
            (d/datoms database :aevt attribute)))))

(defn- declaration-changes
  "Transaction data adopting every packaged declaration difference IN PLACE on this branch.

  Owner rulings 2026-09-23 (README §7 \"Schema change and reset\"): \"A schema
  change should not require a from scratch boot. Period.\" Every declared
  attribute, then every retired one (`retired-attributes`), goes through the
  one mechanism, `attribute-change-tx`. The data behind a replaced or retired
  attribute is dropped by design; `dropped-summary` counts it and names the
  source files that re-derive the program rows among it. A converged reopen
  compares each installed attribute map with its memoized declaration; the
  work of a change is proportional to the changed attributes' datoms.

  `retire?` false leaves the retired attributes installed: a population that
  also publishes program rows retires them after those rows
  (`retire-attributes!`), so the writer judges the retirement against the
  final program (`seon.db/removed-definition-error`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.schema/projection]
                   :seon.store/transaction-data]
                  [:=> [:cat :seon.db/database-value :seon.schema/projection :boolean]
                   :seon.store/transaction-data]]}
  ([database projection]
   (declaration-changes database projection true))
  ([database projection retire?]
   (let [declarations (declared-attributes projection)]
     (attribute-change-tx
      database
      (cond-> (mapv :db/ident declarations)
        retire? (into (retired-attributes database projection)))
      (schema/projection-cache-value
       projection [::declarations-by-attribute]
       #(into {} (map (juxt :db/ident identity)) declarations))))))

(defn- retire-attributes!
  "Retract every attribute `projection` retired, with its current data, in one transaction.

  Run after the population's program rows commit: a surviving writer
  (`:seon.fn/writes`) of a retired attribute then refuses by name, and a
  writer the same publication converted no longer does. Work is one AEVT
  range read per retired attribute."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.schema/projection]
                  [:or :nil :seon.db/transaction-report]]}
  [connection projection]
  (let [database (db/db connection)
        tx-data (attribute-change-tx database (retired-attributes database projection) {})]
    (when (seq tx-data)
      (require-committed!
       (db/transact! connection {:tx-data tx-data})
       {:seon.boot/population :seon.schema/declarations}))))

(defn- dropped-summary
  "The current datoms a declaration change drops, and the files that re-derive them.

  Counts every current datom of the dropped attributes in `database` (read
  before the change) and names the indexed source files of the program rows
  carrying them — function and test rows through `:seon.fn/file`, lint rows
  through `:seon.lint/file`, a component's row through the declaration that
  owns it — so the publication re-derives exactly those files. Other data is
  left for the agent that wrote it to reapply. Work is one AEVT range read per
  dropped attribute and one AVET seek per component attribute per owner step."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :qualified-keyword]]
                  [:map
                   [:seon.db/datom-count :seon.db/datom-count]
                   [:seon.fn/changed-paths :seon.fn/changed-paths]]]}
  [database attributes]
  (let [attributes (filterv #(contains? (:schema database) %) attributes)
        components (into [] (keep (fn [[attribute declaration]]
                                    (when (and (keyword? attribute) (:db/isComponent declaration)) attribute)))
                         (:schema database))
        ;; A dropped attribute on a component (an arity's, an argument's) is
        ;; re-derived by the file of the declaration that owns it: climb owners.
        owning-root (fn [entity]
                      (loop [entity entity seen #{}]
                        (let [owner (some #(:e (first (d/datoms database :avet % entity))) components)]
                          (if (and owner (not (seen owner)))
                            (recur owner (conj seen entity))
                            entity))))
        roots (delay (into #{} (comp (mapcat #(d/datoms database :aevt %)) (map :e) (distinct) (map owning-root))
                           attributes))
        paths (fn [file-attribute]
                (if (and (seq attributes) (contains? (:schema database) file-attribute))
                  (into []
                        (comp (keep #(:v (first (d/datoms database :eavt % file-attribute))))
                              (keep #(:v (first (d/datoms database :eavt % :seon.fn.file/relative-path)))))
                        @roots)
                  []))]
    {:seon.db/datom-count
     (transduce (map #(count (d/datoms database :aevt %))) + 0 attributes)
     :seon.fn/changed-paths
     (into #{} (concat (paths :seon.fn/file) (paths :seon.lint/file)))}))

(defn- missing-process-entities
  "Return required process entities absent from `db`, or its read refusal."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]]
                  [:or [:vector [:map
                                 [:seon.db.process/id
                                  :seon.db.process/id]]]
                   :seon.error/value]]}
  [db]
  (let [read-result
        (db/q '[:find [?id ...]
                :where [_ :seon.db.process/id ?id]]
              db)]
    (if (and (map? read-result)
             (contains? read-result :seon.error/at)
             (contains? read-result :seon.error/layer)
             (contains? read-result :seon.error/operation))
      read-result
      (let [present (set read-result)]
        (into
         []
         (comp
          (remove present)
          (map (fn [process-id] {:seon.db.process/id process-id})))
         [boot-process-identity config/managing-process-identity])))))

(defn- schema-lookup-ref?
  [value]
  (and (vector? value)
       (= 2 (count value))
       (= :seon.schema/key (first value))))

(defn- schema-reference-valued?
  [value]
  (or (schema-lookup-ref? value)
      (and (set? value) (seq value) (every? schema-lookup-ref? value))))

(defn- identity-reference?
  "Is `value`, under installed ref `attribute`, a nested entity map naming its
  target by one identity attribute alone?"
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value] :boolean]}
  [database attribute value]
  (and (map? value) (= 1 (count value)) (qualified-keyword? (key (first value)))
       (= :db.type/ref (get-in (:schema database) [attribute :db/valueType]))))

(defn- as-schema-lookup-refs
  "Normalize a pulled ref value into the canonical lookup-ref shape.

  Canonical rows reference other schema rows as `[:seon.schema/key k]`
  lookup refs; a pull returns those refs as entity maps (one map or a
  collection of them). Convergence compares in the canonical shape, so
  an already-installed reference graph reads as equal instead of
  re-transacting on every reopen."
  [value]
  (cond
    (map? value) [:seon.schema/key (:seon.schema/key value)]
    (coll? value) (into #{} (map as-schema-lookup-refs) value)
    :else value))

(defn- store-comparable-value
  "Normalize one attribute value into the store's own comparison semantics.

  Datahike holds a cardinality-many attribute as a SET and reads it back in
  an order it chooses, so a declaration's ordered value never equals its own
  stored reading. Such a value therefore compares as a set; every other value
  compares as it stands. The INSTALLED schema decides which — never the
  attribute's name. A vector stored in cells keeps its order: a reordered
  vector is a change."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword :seon.schema/value] :seon.schema/value]}
  [database attribute value]
  (if (= :db.cardinality/many
         (get-in (:schema database) [attribute :db/cardinality]))
    (db/stored-collection database attribute (if (coll? value) value [value]))
    value))

(defn- schema-entity-converged?
  "Does the stored row already carry every desired attribute value?

  Compares under the store's own semantics for each attribute, so a
  cardinality-many value read back in a different order is convergence, not a
  change to re-transact on every branch open."
  {:malli/schema [:=> [:cat :seon.db/database-value :map [:or :nil :map]] :boolean]}
  [database desired current]
  (letfn [(comparable [entity]
            (into {}
                  (map (fn [[attribute value]]
                         [attribute
                          (store-comparable-value
                           database attribute value)]))
                  entity))]
    (= (comparable desired)
       (comparable (select-keys current (keys desired))))))

(defn- schema-entity-changes
  "The canonical schema rows of `projection` that `db` does not already hold as written."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection] [:vector :map]]}
  [db projection]
  (into
   []
   (keep
    (fn [{schema-key :seon.schema/key :as desired}]
      (let [selector (mapv (fn [[attribute value]]
                             (cond
                               (schema-reference-valued? value) {attribute [:seon.schema/key]}
                               ;; A ref the bridge writes as a nested identity
                               ;; map (a render pair's `{:seon.fn/sym f}`) reads
                               ;; back through that identity, so a converged
                               ;; row compares equal.
                               (identity-reference? db attribute value) {attribute (vec (keys value))}
                               :else attribute))
                           desired)
            pulled (some-> (db/pull db selector
                                    [:seon.schema/key schema-key])
                           (dissoc :db/id))
            current
            (when pulled
              (into {}
                    (map (fn [[attribute value]]
                           (if (schema-reference-valued?
                                (get desired attribute))
                             [attribute (as-schema-lookup-refs value)]
                             [attribute value])))
                    pulled))]
        (when-not (schema-entity-converged? db desired current)
          desired))))
   (schema/canonical-schema-entities projection (:seon.schema.projection/forms projection))))

(defn- instruction-entity-changes
  [db entities]
  (into
   []
   (remove
    (fn [{instruction-id :seon.cluster.instruction/id}]
      (some? (db/q '[:find ?instruction .
                    :in $ ?instruction-id
                    :where
                    [?instruction :seon.cluster.instruction/id
                     ?instruction-id]]
                  db instruction-id))))
   entities))

(defn- lookup-refs-in
  [database value]
  (letfn [(lookup-ref? [candidate]
            (and (vector? candidate)
                 (= 2 (count candidate))
                 (qualified-keyword? (first candidate))
                 (:db/unique (get (:schema database) (first candidate)))))
          (walk [candidate]
            (cond
              (lookup-ref? candidate) [candidate]
              (map? candidate) (mapcat walk (vals candidate))
              (coll? candidate) (mapcat walk candidate)
              :else []))]
    (->> (walk value) distinct (sort-by pr-str) vec)))

(defn- lookup-resolution
  "How one lookup ref answers at `database`: resolved, absent, or REFUSED.

   The probe has THREE states, never two. Reading a refused read as an absent
   entity is the absence-as-health defect this seam shipped: on 2026-09-18 a
   `seon.db/pull` refusal on an EXISTING provider descriptor
   (`:seon.db/unknown-pull-schema`) made every model row look unready, and the
   boot reported \"Initialization lookup refs do not resolve\" about rows whose
   targets were in the database all along. The read's own refusal is the
   evidence; this seam never re-decides it."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.schema/value]]
                  [:or :nil [:map [::lookup [:sequential :seon.schema/value]]
                             [::refusal {:optional true} :seon.db/error-result]]]]}
  [database lookup]
  (let [result (db/pull database [:db/id] (vec lookup))]
    (cond
      (or (:seon.db/invalid-read result) (:seon.schema/expected-value result))
      {::lookup lookup ::refusal result}

      (:db/id result) nil

      :else {::lookup lookup})))

(defn- entity-lookup-refusal
  [database entity]
  (reduce (fn [_ lookup]
            (let [resolution (lookup-resolution database lookup)]
              (if (::refusal resolution) (reduced resolution) nil)))
          nil
          (lookup-refs-in database entity)))

(defn- entity-ready?
  [database entity]
  (every? (fn [lookup] (nil? (lookup-resolution database lookup)))
          (lookup-refs-in database entity)))

(defn- transact-initialization!
  [connection entities]
  (config/require-functions! (db/db connection) entities)
  (loop [pending (vec entities)]
    (when (seq pending)
      (let [database (db/db connection)
            refusal (some #(entity-lookup-refusal database %) pending)]
        ;; A refused readiness read is surfaced verbatim BEFORE any judgement
        ;; about what resolves: the readiness question cannot be answered at
        ;; all while the read seam is refusing.
        (when refusal
          (let [[attribute value] (::lookup refusal)]
            (refused!
             (str "An initialization readiness read was refused: "
                  (:seon.error/message (::refusal refusal)))
             {:seon.activation/lookup-attribute attribute
              :seon.activation/lookup-value value
              :seon.boot/population :seon.config/initialization
              :seon.boot/result (::refusal refusal)})))
        (let [ready (into [] (filter #(entity-ready? database %)) pending)
              waiting (into [] (remove #(entity-ready? database %)) pending)]
          (when (empty? ready)
            (refused!
             "Initialization lookup refs do not resolve."
             {:seon.activation/missing
              (into []
                    (comp
                     (mapcat #(lookup-refs-in database %))
                     ;; `(distinct)` — the transducer. Bare `distinct` is the
                     ;; one-argument COLLECTION arity, so composing it here
                     ;; handed `comp` a LazySeq where a reducing function
                     ;; belongs and the refusal threw a ClassCastException
                     ;; instead of naming the unresolved lookups (found
                     ;; 2026-08-08, the first time this branch ever ran).
                     (distinct)
                     (map (fn [[attribute value]]
                            {:seon.activation/lookup-attribute attribute
                             :seon.activation/lookup-value value})))
                    waiting)}))
          (require-committed!
           (db/transact! connection
                         {:tx-data ready
                          :tx-meta
                          {:seon.db/process
                           [:seon.db.process/id boot-process-identity]}})
           {:seon.boot/population :seon.config/initialization})
          (recur waiting)))))
  nil)

(defn accrete-schema-population!
  "Install the current additive schema population on one branch.

  Registration and database installation are separate in Datahike's
  `:write` schema mode. Every opened branch therefore passes through this
  choke point before any domain transaction. Every declaration difference
  adopts in place (`declaration-changes`): missing and accretive declarations
  are transacted, a non-accretive change replaces the attribute and a retired
  attribute is retracted, each with its current data. A converged reopen
  issues no transaction. `retire?` false leaves retirement to a caller that
  publishes program rows first (`populate-source!`).

  The population HANDS its transactions the projection its declarations come
  from — the same projection write admission validates against — so a caller
  that opened a branch without one (the canonical fixture base, the artifact
  initializer) populates through this seam instead of refusing
  `:seon.schema/missing-projection`. A projection the caller already handed
  (`refresh-source!`'s declaration projection, a cluster's advanceable
  projection state) wins, so this derives one only when nothing supplied it."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/connection [:or :nil :seon.boot/cluster-name]]
     [:or :nil :seon.db/transaction-report]]
    [:=> [:cat :seon.db/connection [:or :nil :seon.boot/cluster-name] :boolean]
     [:or :nil :seon.db/transaction-report]]
    [:=> [:cat :seon.db/connection [:or :nil :seon.boot/cluster-name] :boolean :boolean]
     [:or :nil :seon.db/transaction-report]]]}
  ([connection cluster-name]
   (accrete-schema-population! connection cluster-name true))
  ([connection cluster-name publish-schema-entities?]
   (accrete-schema-population! connection cluster-name publish-schema-entities? true))
  ([connection cluster-name publish-schema-entities? retire?]
  (let [forms (schema.edn/packaged-forms)
        projection (or (schema/handed-projection) (schema/declaration-projection forms))]
    (schema/call-with-forms
     forms
     (fn []
       (schema/call-with-projection
        projection
        (fn []
          (let [declarations
                (declaration-changes (db/db connection) projection retire?)]
            (when (seq declarations)
              (require-committed!
               (db/transact! connection {:tx-data declarations})
               {:seon.boot/population :seon.schema/declarations})))
          (let [process-entities (missing-process-entities (db/db connection))]
            (when (and (map? process-entities)
                       (contains? process-entities :seon.error/at)
                       (contains? process-entities :seon.error/layer)
                       (contains? process-entities :seon.error/operation))
              (throw (ex-info (:seon.error/message process-entities)
                              process-entities)))
            (when (seq process-entities)
              (require-committed!
               (db/transact! connection {:tx-data process-entities})
               {:seon.boot/population :seon.db/processes})))
          (let [database (db/db connection)
                ;; A projection that IS this branch's own carried projection
                ;; describes these entities already; diffing them against
                ;; themselves cost 2.8-4.2 s of per-entity pulls on every resume
                ;; (docs/prds/agent-platform/landing/lane-resume-in-seconds-2026-09-23.md (e)).
                schema-entities (when (and publish-schema-entities?
                                       (not (identical? projection (db/carried-projection database))))
                              (schema-entity-changes database projection))]
            (when (seq schema-entities)
              (require-committed!
               (db/transact! connection
                             {:tx-data schema-entities
                              :tx-meta
                              {:seon.db/process
                               [:seon.db.process/id boot-process-identity]}})
               {:seon.boot/population :seon.schema/rows}))))))))))

(defn populate-source!
  "The default `current-src` content: this code's schema and program rows.
  Named by symbol in `source/publish!`'s request, so the producer is
  data and N5's program-graph indexer replaces it without touching the
  boot path. The convergent population transactions are DERIVED, never
  hand-written:
  the Datahike declarations of every registered database attribute, the
  core process entities the provenance refs resolve to (genesis data —
  bootstrap content lives in the source branch), and the canonical schema rows
  asserted with that process provenance.

  Every transaction here is HANDED the one projection this population's
  declarations come from — the same projection write admission validates
  against — so the population never depends on its caller having bound one.
  A caller that already handed a projection (`refresh-source!`) wins."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection
                     :seon.db/connection]]]
    [:or :nil :seon.reconcile/result]]}
  [{connection :seon.db/connection
    analyzed :seon.program/rows
    manifest :seon.fn/manifest
    directory :seon.fn/root
    roots :seon.fn/roots
    previous :seon.fn/previous-manifest
    paths :seon.fn/changed-paths
    prior-database :seon.source/previous-database
    classes :seon.source/change-classes
    inputs :seon.source/relative-file-digests
    cache-root :seon.fn.analyzer/cache-root}]
  (let [forms (schema.edn/packaged-forms)]
    (schema/call-with-forms
     forms
     (fn []
       (schema/call-with-projection
        (or (schema/handed-projection)
            (schema/declaration-projection forms))
        (fn []
          ;; EACH KIND OF CHANGED INPUT HAS ONE OWNER. A complete publication
          ;; supplies no classes and runs every owner; an incremental one
          ;; names exactly the owners its changed inputs belong to, so a
          ;; schema resource never re-indexes the program and a config
          ;; document never re-accretes the schema.
          (let [schema? (or (nil? classes) (classes :schema-resource))
                program? (or (nil? classes) (classes :program))
                ;; Read BEFORE the population transacts: the datoms a replaced
                ;; or retired attribute drops, and the files whose program
                ;; entities carried them (owner 2026-09-23: drop the data, update
                ;; the schema, reapply what the files derive).
                dropped (when schema?
                          (let [database (db/db connection)]
                            (dropped-summary
                             database
                             (dropped-attributes database (schema/handed-projection)))))
                schema-report
                (when schema?
          (report-source-progress! "schema population started")
          ;; Schema entities land with their renderer definitions in index!'s one
          ;; final transaction whenever program entities publish too (always, for
          ;; a complete or an incremental publication); publishing them first
          ;; refused a schema naming a renderer its own publication defines.
          ;; Retired attributes likewise wait for the program entities: a writer
          ;; this publication converts must not refuse their retirement.
          (let [report (accrete-schema-population!
                        connection nil (and (some? classes) (not program?)) (not program?))]
            (report-source-progress!
             (str "schema population complete; dropped "
                  (:seon.db/datom-count dropped 0) " datoms"))
            report))]
          (when (nil? classes)
          (report-source-progress! "instruction entities")
          (let [entities (instruction-entity-changes
                      (db/db connection)
                      (instruction/seed-entities))]
            (when (seq entities)
              (require-committed!
               (db/transact! connection
                             {:tx-data entities
                              :tx-meta
                              {:seon.db/process
                               [:seon.db.process/id boot-process-identity]}})
               {:seon.boot/population :seon.cluster.instruction/rows}))))
          (let [result
                (when program?
                  (report-source-progress! "program entities started")
                  (let [result (seon.fn/index!
             (cond-> {:seon.db/connection connection
                      :seon.schema/projection (schema/handed-projection)
                      :seon.db/process
                      [:seon.db.process/id boot-process-identity]}
               analyzed (assoc :seon.program/rows analyzed)
               cache-root (assoc :seon.fn.analyzer/cache-root cache-root)
               directory (assoc :seon.fn/root directory)
               roots (assoc :seon.fn/roots roots)
               manifest (assoc :seon.fn/manifest manifest)
               inputs (assoc :seon.source/relative-file-digests inputs)
               (or classes prior-database)
               (assoc :seon.source/previous-database (or prior-database (db/db connection)))
               previous (assoc :seon.fn/previous-manifest previous)
               paths (assoc :seon.fn/changed-paths paths)
               (nil? manifest) (assoc :seon.fn/roots (or roots seon.fn/source-roots)))
             report-source-progress!)]
                    (report-source-progress! "program entities complete")
                    result))
                ;; The writer judges the retirement against the final program
                ;; entities, then the files behind its dropped datoms re-derive.
                _ (when (and schema? program? (not (:seon.error/at result)))
                    (retire-attributes! connection (schema/handed-projection)))
                reapply (:seon.fn/changed-paths dropped)
                ;; Re-derive only the files whose program entities lost datoms,
                ;; compared against the database AFTER the drop so every entity
                ;; missing a dropped value differs and is reasserted.
                reapplied
                (when (seq reapply)
                  (report-source-progress!
                   (str "re-deriving " (count reapply) " files behind dropped datoms"))
                  (seon.fn/index!
                   (cond-> {:seon.db/connection connection
                            :seon.schema/projection (schema/handed-projection)
                            :seon.db/process [:seon.db.process/id boot-process-identity]
                            :seon.fn/roots (or roots seon.fn/source-roots)
                            :seon.fn/changed-paths reapply
                            :seon.source/previous-database (db/db connection)}
                     cache-root (assoc :seon.fn.analyzer/cache-root cache-root)
                     directory (assoc :seon.fn/root directory))
                   report-source-progress!))
                result (if (and result reapplied)
                         (update result :seon.reconcile/adopt-identities (fnil into #{})
                                 (:seon.reconcile/adopt-identities reapplied))
                         (or result reapplied))
                dropped-datoms (:seon.db/datom-count dropped 0)
                ;; The count rides the adoption result so the agent whose data
                ;; was dropped can see it and reapply.
                result (cond
                         (zero? dropped-datoms) result
                         (:seon.error/at result) result
                         result (assoc result :seon.reconcile/dropped-datoms dropped-datoms)
                         :else {:seon.reconcile/converged? false
                                :seon.reconcile/operations 0
                                :seon.reconcile/dropped-datoms dropped-datoms})]
          ;; Initialization entities come LAST because they may name a program entity
          ;; by lookup ref — the call-preparation suppliers do — and program
          ;; entities are asserted by `index!` immediately above. Nothing earlier in
          ;; this population reads a config fact (`seon.fn` and
          ;; `seon.cluster.instruction` name no config attribute), so the move
          ;; costs no dependency and removes an ordering hazard that would
          ;; otherwise force every declared entity to predate the program graph.
          (when (or (nil? classes) (classes :config))
          (report-source-progress! "initialization entities")
          (let [entities (config/default-population)]
            (when (seq entities)
              (transact-initialization! connection entities))))
            (cond-> result
              (and result schema-report)
              (update :seon.reconcile/adopt-identities (fnil into #{})
                      (require-committed! (seon.fn/report-identities schema-report)
                                          {:seon.boot/population :seon.schema/rows})))))))))))

;;; ---------------------------------------------------------------------------
;;; Ordered boot above the REPL
;;; ---------------------------------------------------------------------------

;;; The roots the published source digest is computed over. Today the
;;; population above is derived from the Clojure program plus the
;;; classpath schema population. The indexer reads only Clojure files,
;;; while the source digest also covers the EDN declarations whose
;;; Datahike schema and canonical entities are installed into `current-src`.
(def source-roots
  "The complete file roots whose content identifies `current-src`."
  (into seon.fn/source-roots
        ["config/default.edn"]))

(defn- publication-roots
  "The root sets one publication analyzes, digests, and compares against.

  READ ONCE, AT THE TOP OF THE PUBLICATION, AND CARRIED AS A VALUE.
  Development adoption reloads the program's namespaces — `seon.fn` and
  `seon.cluster` among them — so every `def` above is re-evaluated while the
  publication that triggered the reload is still running. A seam that re-reads
  either root var after that point is acting on a value its own adoption
  re-decided, and the post-adoption digest compare then reports a source change
  that never happened."
  []
  {:seon.fn/root (fs/source-directory)
   :seon.source/roots source-roots
   :seon.fn/roots seon.fn/source-roots})

(defn- current-source!
  "The exact published source commit new clusters fork.
  Boot never indexes files: absent publication tells the operator to run
  `bin/seon init`."
  [store]
  (or (source/current store)
      (refused!
       "No `current-src` branch is published; run `bin/seon init` first."
       {:seon.source/branch source/current-branch})))

(defn source-base!
  "Acquire the immutable published program value shared by new cluster forks,
  its environment named for `cluster-name`, the cluster about to fork it."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.boot/cluster-name]
    [:map
     [:seon.source/commit-id :seon.source/commit-id]
     [:seon.source/digest :seon.source/digest]
     [:seon.store/store :seon.store/store]
     [:seon.store/branch :seon.store/branch]
     [:seon.db/db :seon.db/database-value]
     [:seon.schema/projection :seon.schema/projection]
     [:seon.sci.eval/ctx :seon.sci.eval/ctx]]]}
  [store cluster-name]
  (let [{commit-id :seon.source/commit-id} (current-source! store)
        connection (store/open-branch! store source/current-branch)]
    (try
      (let [database (db/db connection)
            projection (schema/projection-from-database database)
            ;; The cluster whose fork this base becomes names its environment.
            projection-state (sci.eval/projection-state database projection cluster-name)
            [source-digest base-ctx]
            (schema/call-with-projection-state
             projection-state
             #(vector
                    (db/q '[:find ?digest .
                       :where [_ :seon.source/digest ?digest]]
                     database)
               (sci.eval/cluster-ctx
                database connection projection-state)))]
        {:seon.source/commit-id commit-id
         :seon.source/digest source-digest
         :seon.store/store store
         :seon.store/branch source/current-branch
         :seon.db/db database
         :seon.schema/projection projection
         :seon.sci.eval/ctx base-ctx})
      (finally
        (d/release connection)))))

(defn- count-installed
  [db attribute]
  (if (contains? (:schema db) attribute)
    (or
     (db/q '[:find (count ?entity) .
            :in $ ?attribute
            :where [?entity ?attribute]]
          db
          attribute)
     0)
    0))

(defn- program-currentness
  [db]
  (let [recorded-digests
        (if (contains? (:schema db) :seon.source/digest)
          (into
           #{}
           (db/q '[:find [?digest ...]
                  :where [_ :seon.source/digest ?digest]]
                db))
          #{})
        namespace-count (count-installed db :seon.ns/name)
        function-count (count-installed db :seon.fn/sym)
        namespace-populated? (pos? namespace-count)
        function-populated? (pos? function-count)
        partial? (not= namespace-populated? function-populated?)
        populated? (and namespace-populated? function-populated?)
        one-digest? (= 1 (count recorded-digests))]
    {:seon.source/coherent? (and one-digest? populated?)
     :seon.source/partial? partial?
     :seon.source/recorded-digests recorded-digests
     :seon.source/namespace-count namespace-count
     :seon.source/function-count function-count}))

(defn require-coherent-program!
  [connection cluster-name]
  (let [currentness (program-currentness (db/db connection))]
    (when-not (:seon.source/coherent? currentness)
      (let [condition
            (cond
              (:seon.source/partial? currentness)
              (str "partial ("
                   (:seon.source/namespace-count currentness)
                   " namespace entities and "
                   (:seon.source/function-count currentness)
                   " function entities)")

              (empty? (:seon.source/recorded-digests currentness))
              "unprimed (no recorded source digest)"

              (> (count (:seon.source/recorded-digests currentness)) 1)
              (str "incoherent (multiple recorded source digests "
                   (render.value/bounded-text (:seon.source/recorded-digests currentness))
                   ")")

              :else
              "unprimed (the source-owned program entities are absent)")]
        (refused!
         (str
          "Cluster `" cluster-name "` was not started because its program "
          "graph is " condition ". "
          "`bin/seon init " cluster-name " --force` destroys that branch and "
          "reforks a complete cluster from `current-src`.")
         currentness)))
    currentness))

(defn- current-publication
  [store expected-digest]
  (when-let [{branch :seon.source/branch commit-id :seon.source/commit-id}
             (source/current store)]
    ;; Reading the seal needs no program projection. The changed publication
    ;; acquires its prior declaration world once, after this cheap comparison.
    (let [database (d/commit-as-db (:seon.store/connection-object store) commit-id)]
      (try
        (let [digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database)]
          (when (map? digest)
            (refused! "Published source digest could not be read." digest))
          (when (and digest (or (nil? expected-digest) (= expected-digest digest)))
            {:seon.source/branch branch :seon.source/commit-id commit-id
             :seon.source/digest digest :seon.source/built? false}))
        (finally (when database (d/release-materialized-db database)))))))

(declare selected-analyzer-digest launched-source-digests)

(defn- derivation-schema-keys
  "Per derivation input, the schema keys its entities' derivation reads: the
  declared keywords they hold (`:seon.fn/keywords`) and those their contracts
  name. `entities` are rows or stored entities, each naming its `:seon.fn/file`."
  {:malli/schema [:=> [:cat [:set :keyword] [:sequential :map]] [:map-of :string [:set :keyword]]]}
  [declared entities]
  (reduce (fn [by-path {file :seon.fn/file keywords :seon.fn/keywords spec :seon.fn/spec}]
            (if-let [path (if (map? file) (:seon.fn.file/relative-path file) (second file))]
              (update by-path path (fnil into #{})
                      (filter declared)
                      (concat keywords (when spec (tree-seq coll? seq (edn/read-string spec)))))
              by-path))
          {} entities))

(defn- stored-derivation-schema-keys
  "`derivation-schema-keys` of every stored entity in `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :keyword]] [:map-of :string [:set :keyword]]]}
  [database declared]
  (let [held (db/q '[:find ?path ?keyword
                     :where [?entity :seon.fn/keywords ?keyword] [?entity :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]] database)
        specs (db/q '[:find ?path ?spec
                      :where [?entity :seon.fn/spec ?spec] [?entity :seon.fn/file ?file]
                             [?file :seon.fn.file/relative-path ?path]] database)]
    (when-let [refusal (some #(when (map? %) %) [held specs])]
      (refused! "Stored derivation inputs could not be read." refusal))
    (derivation-schema-keys
     declared
     (concat (map (fn [[path keyword]] {:seon.fn/file [:seon.fn.file/relative-path path] :seon.fn/keywords [keyword]}) held)
             (map (fn [[path spec]] {:seon.fn/file [:seon.fn.file/relative-path path] :seon.fn/spec spec}) specs)))))

(defn- file-derivation-key
  "Hash one file's already selected derivation inputs, only on a content miss."
  {:malli/schema [:=> [:cat :seon.source/analyzer-digest [:vector :seon.schema/value]
                       [:maybe :seon.source/digest] [:map-of :keyword :seon.source/digest]]
                  :seon.source/digest]}
  [code kondo input form-digests]
  (id/digest 64 [code kondo input form-digests]))

(defn- derivation-form
  "The schema form read by entity derivation, without its root render pair.
  Invoker edges follow population targets separately through invoker-paths."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [form]
  (if (and (vector? form) (map? (second form)))
    (let [properties (dissoc (second form) :seon.render/ai :seon.render/html :seon.render/form)]
      (if (seq properties) (assoc form 1 properties)
          (into [(first form)] (nnext form))))
    form))

(defn- derivation-keys
  "Each derivation input's key, stored as `:seon.fn.file/derivation-digest`:
  its bytes, the identity of the code deriving it (`selected-analyzer-digest`),
  clj-kondo's pin and configuration, and the forms of the schema keys its
  derivation reads (`derivation-schema-keys`) with the program row shapes',
  each closed over `:seon.schema.projection/schema-dependencies`. A publication
  re-derives exactly the inputs whose stored key differs.

  Shared schema forms are hashed once per declaration world; each file selects
  those digests. No per-file copy of the shared schema digest map is retained.

  # Known issues
  - Cross-file inputs (the symbols a file's calls resolve to, a callee's
    host-bound flag, an invoked attribute's targets, clj-kondo findings over
    another namespace's signatures) are not in the key: the dependent and caller
    re-lint selection (`seon.fn/dependent-paths`) re-derives those files (task
    aca1b25fa16a).
  - The database owner's read path is not in the key: a read-path change that
    alters logical read results is covered by the owner's own tests, not by
    re-derivation (task 50a6a9afcb64).
  - An encoding-only storage change leaves reused entities in their old
    encoding; storage migration owns it (task 52875b443ef0)."
  {:seon/tasks #{"aca1b25fa16a" "50a6a9afcb64" "52875b443ef0"}
   :malli/schema [:=> [:cat :seon.source/analyzer-digest :seon.schema/projection
                       :seon.source/relative-file-digests [:map-of :string [:set :keyword]] [:set :string]]
                  [:map-of :string :seon.source/digest]]}
  [code projection inputs schema-keys paths]
  (let [forms (:seon.schema.projection/forms projection)
        dependencies (:seon.schema.projection/schema-dependencies projection)
        closure (fn [start]
                  (loop [pending (vec start) reached #{}]
                    (if-let [k (peek pending)]
                      (if (reached k)
                        (recur (pop pending) reached)
                        (recur (into (pop pending) (get dependencies k)) (conj reached k)))
                      reached)))
        row-shapes (program/shapes-in projection)
        world (cache/lookup
               ::derivation-schema-values [forms dependencies row-shapes]
               (constantly (* 4096 (count forms)))
               #(let [forms (update-vals forms derivation-form)]
                  (hash-map ::key (id/digest 64 [forms dependencies row-shapes])
                          ::absent (id/digest 64 [nil])
                          ::digests (into {} (map (fn [[k form]] [k (id/digest 64 [form])])) forms)
                          ::shapes (closure (into #{} (filter (partial contains? forms))
                                                (tree-seq coll? seq row-shapes))))))
        kondo [(get (source/loaded-dependencies) "reference-code/clj-kondo")
               (into (sorted-map) (filter #(str/starts-with? (key %) ".clj-kondo/")) inputs)]]
    (into {}
          (map (fn [path]
                 (let [start (get schema-keys path #{})
                       input (get inputs path)]
                   [path
                    (cache/lookup
                     ::file-derivation-key [code kondo input (::key world) start]
                     (constantly (+ 512 (* 64 (count start))))
                     #(file-derivation-key
                       code kondo input
                       (into (sorted-map)
                             (map (fn [k] [k (get (::digests world) k (::absent world))]))
                             (into (::shapes world) (closure start)))))])))
          paths)))


(defn- full-source-refresh!
  "Capture, compare, classify and analyze selected entities on the current lineage.

  The changed inputs are analyzed; every other derivation input whose stored
  key (`derivation-keys`) differs from its key under the code that analyzed
  them is then added (`::rederive`) and the analysis repeated once over both."
  {:malli/schema
   [:=> [:cat :seon.boot/root :seon.store/store
         [:map [:seon.fn/root :string] [:seon.source/roots :seon.source/roots]
          [:seon.source/changed-paths [:vector :string]]
          [::loaded-files {:optional true} :seon.source/relative-file-digests]
          [::rederive {:optional true} [:set :string]]
          [:seon.source/branch {:optional true} :seon.source/branch]
          [:seon.fn.analyzer/cache-root {:optional true} :string]]]
    :seon.source/publish-result]}
  [root store roots]
  (let [published (current-publication store nil)
        committed (when published
                    (d/commit-as-db (:seon.store/connection-object store)
                                    (:seon.source/commit-id published)))]
    (try
      (let [directory (:seon.fn/root roots)
            requested-paths (:seon.source/changed-paths roots)
            input-roots (test.cache/input-roots directory)
            requested (filterv #(or (test.cache/input-path? input-roots %)
                                    (and (test.cache/input-path? (set (:seon.source/roots roots)) %)
                                         (some (partial str/ends-with? %) [".clj" ".cljc" ".edn"])))
                               requested-paths)
            partial? (and committed (seq requested-paths))
            inventory (if partial? requested
                          (source/discover-paths directory (:seon.source/roots roots)))
            [observed captured] (source/capture-paths directory inventory)
            paths (if partial? requested
                      (vec (into (set inventory)
                                 (when committed
                                   (db/q '[:find [?path ...]
                                           :where [_ :seon.fn.file/relative-path ?path]] committed)))))
            prior (if committed (source/stored-path-digests committed paths) {})
            _ (when-let [unknown (seq (remove #(or (contains? observed %) (contains? prior %)) requested))]
                (refused! "Changed paths are neither in the JVM's source directory nor published."
                          {:seon.fn/root directory :seon.source/changed-paths (vec unknown)}))
            changed (into #{} (filter #(not= (get prior %) (get observed %))) paths)
            ;; The bytes the deriving code came from (`loaded-files`).
            loaded (::loaded-files roots launched-source-digests)
            rederive (::rederive roots #{})
            classification (cond
                             (nil? committed) :all
                             (empty? changed) :selected
                             ;; A changed directory input is a gitlink: capture digests
                             ;; directories only through their recorded pins.
                             :else (source/classify-paths
                                    changed (into #{} (filter #(.isDirectory (io/file directory %))) changed)
                                    observed (source/loaded-dependencies)))
            analysis-paths (if (= :all classification)
                             (into changed (if partial?
                                             (source/discover-paths directory (:seon.source/roots roots))
                                             inventory))
                             (into changed rederive))
            [additional additional-sources]
            (source/capture-paths directory (vec (remove (set inventory) analysis-paths)))
            captured (merge captured additional-sources)]
    (if (and committed (empty? changed) (empty? rederive)
             (not-any? (requiring-resolve 'seon.issue/note-path?) requested-paths))
      published
      (let [stored (when partial?
                     (into {} (db/q '[:find ?path ?digest
                                      :where [?file :seon.fn.file/relative-path ?path]
                                             [?file :seon.fn.file/digest ?digest]] committed)))
            inputs (merge (if partial? (merge (apply dissoc stored requested) observed) observed) additional)
            published-input-digest (when committed
                                     (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]]
                                           committed))
            _ (when (map? published-input-digest)
                (refused! "The published test-input digest could not be read." published-input-digest))
            test-inputs (source/snapshot-test-input-digest
                         (cond-> {:seon.fn/root directory
                                  :seon.cluster.source/roots input-roots
                                  :seon.source/relative-file-digests inputs}
                           partial? (assoc :seon.source/changed-paths (vec (into changed requested-paths)))
                           published-input-digest (assoc :seon.cluster.source/published published-input-digest)))
            snapshot {:seon.source/relative-file-digests inputs
                      :seon.source/digest (id/digest 64 [(into (sorted-map) inputs)])
                      :seon.source/test-input-digest (:seon.source/test-input-digest test-inputs)}
            digest (:seon.source/digest snapshot)]
      (let [database (when published (source/database store (:seon.source/commit-id published)))
            selected analysis-paths]
        (try
          (let [paths (when database selected)
                analyzed (seon.fn/analyze-entities
                          (cond-> (merge (select-keys roots [:seon.fn.analyzer/cache-root])
                                         {:seon.fn/root directory
                                          :seon.fn/roots (:seon.fn/roots roots)
                                          :seon.fn/changed-paths selected
                                          :seon.fn.analyzer/sources captured
                                          :seon.source/relative-file-digests inputs})
                            database (assoc :seon.source/previous-database database)))
                projection (schema/handed-projection)
                declared (set (keys (:seon.schema.projection/forms projection)))
                ;; The identity of the code that derived `analyzed`.
                code (selected-analyzer-digest database selected analyzed inputs loaded)
                derivation-inputs (into #{} (filter #(and (some (fn [root] (str/starts-with? % (str root "/")))
                                                                (:seon.fn/roots roots))
                                                          (some (partial str/ends-with? %) [".clj" ".cljc"])
                                                          (contains? inputs %)))
                                        (concat (keys inputs) selected))
                keyed (derivation-keys code projection inputs
                                       (merge (when database (stored-derivation-schema-keys database declared))
                                              (derivation-schema-keys declared analyzed))
                                       derivation-inputs)
                ;; A store that has not installed the key attribute (the first
                ;; publication after it was declared) holds no keys: every input
                ;; is stale, and this publication installs the attribute with the
                ;; keys it writes. An unreadable key is no key either: the input
                ;; re-derives, and the refusal travels with the result.
                held-keys (when (and database (contains? (:schema database) :seon.fn.file/derivation-digest))
                            (db/q '[:find ?path ?key
                                    :where [?file :seon.fn.file/relative-path ?path]
                                           [?file :seon.fn.file/derivation-digest ?key]]
                                  database))
                key-refusal (when (map? held-keys) held-keys)
                stale (when (and database (empty? rederive))
                        (let [stored-keys (if key-refusal {} (into {} held-keys))]
                          (into #{} (filter #(and (not (contains? selected %))
                                                  (not= (get stored-keys %) (get keyed %))))
                                derivation-inputs)))
                analyzed (mapv #(if-let [path (:seon.fn.file/relative-path %)]
                                  (cond-> % (contains? keyed path)
                                    (assoc :seon.fn.file/derivation-digest (get keyed path)))
                                  %)
                               analyzed)]
          (if (seq stale)
            (do (report-source-progress! (str "re-deriving " (count stale) " inputs whose derivation key changed"))
                (full-source-refresh! root store (assoc roots ::rederive stale)))
            (let [previous-findings (when database
                                      (seon.fn/file-entities database (vec paths) :seon.lint/file))
                  _ (when (:seon.error/at previous-findings)
                      (refused! "Published findings could not be read." previous-findings))
                  _ (report-analysis-warnings! previous-findings (filterv :seon.lint/id analyzed))
                  classes (when paths
                            (cond-> #{:program}
                              (some #(str/starts-with? % "resources/seon/schemas/") changed) (conj :schema-resource)
                              (contains? changed config/default-manifest-path) (conj :config)))
                  _ (report-source-progress! (str "branch publication started: "
                                                  (if paths (count paths) (count (filter :seon.fn.file/relative-path analyzed)))
                                                  " inputs"))
                  result (source/publish!
                          (cond->
                          (merge (select-keys roots [:seon.source/branch])
                          {:seon.store/store store :seon.fn/root (:seon.fn/root roots)
                           :seon.source/digest digest :seon.source/populate `populate-source!
                           :seon.source/test-input-digest (:seon.source/test-input-digest snapshot)
                           :seon.source/changed-paths (vec (into (into changed rederive) requested-paths))
                           :seon.source/relative-file-digests inputs
                           :seon.source/progress! report-source-progress!
                           :seon.source/populate-request
                           (cond-> (merge (select-keys roots [:seon.fn.analyzer/cache-root])
                                          {:seon.program/rows analyzed :seon.fn/root directory
                                           :seon.fn/roots (:seon.fn/roots roots)
                                           :seon.source/relative-file-digests inputs})
                             database (assoc :seon.source/previous-database database)
                             paths (assoc :seon.fn/changed-paths paths
                                          :seon.source/change-classes classes))})
                            ;; The analysis above derives from this head; the
                            ;; publisher refuses if another publication moved it.
                            published (assoc :seon.source/expected-commit-id
                                             (:seon.source/commit-id published))))]
              (report-source-progress! "branch publication complete")
              (cond-> result
                key-refusal (assoc :seon.source/derivation-key-refusal key-refusal)))))
          (finally (when database (d/release-materialized-db database))))))))
      (finally (when committed (d/release-materialized-db committed))))))

(defn reload-order
  "Namespaces ordered so each one's required namespaces reload first.

  `requires` maps a namespace name to the set of namespace names it requires.
  Only members of `namespaces` are ordered; edges leaving that set are
  ignored. Ties break by name, so the order is stable across boots. Clojure
  namespaces cannot require each other cyclically; inconsistent stored edges
  refuse before any namespace is reloaded."
  {:malli/schema
   [:=> [:cat [:set :seon.ns/name] [:map-of :seon.ns/name [:set :seon.ns/name]]]
    [:vector :seon.ns/name]]}
  [namespaces requires]
  (loop [remaining (into (sorted-set-by #(compare (str %1) (str %2))) namespaces)
         ordered []]
    (if (empty? remaining)
      ordered
      (let [ready (some (fn [namespace-name]
                          (when (empty? (set/intersection
                                         (get requires namespace-name #{})
                                         remaining))
                            namespace-name))
                        remaining)]
        (when-not ready
          (refused! "Development reload requires contain a cycle."
                    {:seon.ns/requires (set remaining)}))
        (recur (disj remaining ready) (conj ordered ready))))))

(defn- namespace-requires
  "The declared `:seon.ns/requires` edges among `namespaces`, by name."
  {:malli/schema [:=> [:cat :seon.db/database-value [:seqable :symbol]]
                  [:map-of :symbol [:set :symbol]]]}
  [database namespaces]
  (let [edges (db/q '[:find ?name ?required-name
                      :in $ [?name ...]
                      :where
                      [?namespace :seon.ns/name ?name]
                      [?namespace :seon.ns/requires ?required-name]]
                    database (vec namespaces))]
    (when (or (:seon.db/invalid-read edges) (:seon.schema/expected-value edges))
      (refused! "Development reload could not read namespace requires." edges))
    (reduce (fn [result [namespace-name required-name]]
              (update result namespace-name (fnil conj #{}) required-name))
            {}
            edges)))

(defn- reloadable-namespace?
  [namespace-name]
  (let [resource (.. (str namespace-name) (replace \- \_) (replace \. \/))]
    (boolean
     (and (find-ns namespace-name)
          (or (io/resource (str resource ".clj"))
              (io/resource (str resource ".cljc")))))))

(defn- load-development-definitions!
  "The one ordered JVM definition replacement used by development adoption."
  {:malli/schema [:=> [:cat [:set :symbol] [:map-of :symbol [:set :symbol]]] :seon.source/reloaded-namespaces]}
  [namespaces requires]
  (into []
        (comp (filter reloadable-namespace?)
              (map (fn [namespace-name]
                     (report-source-progress! (str "development reload " namespace-name))
                     (require namespace-name :reload)
                     namespace-name)))
        (reload-order namespaces requires)))

(defn- call-with-program-change
  "Call `change`, which reloads and arms toward `commit-id`, never waiting for anyone.

  Before `change` touches a root, `identity-atom` names the loaded program
  unknown; only `change` returning (its record committed) names `commit-id`.
  A throw keeps the unknown mark, with its cause. A member or evaluation that
  read another identity at its start refuses its own result
  (`seon.sci.eval/call-detecting-program-change`)."
  {:seon/tasks #{"431c6e828256"}
   :malli/schema [:=> [:cat :seon.sci.eval/loaded-identity :seon.source/commit-id
                       [:=> [:cat] :seon.source/adoption-result]]
                  :seon.source/adoption-result]}
  [identity-atom commit-id change]
  (let [unknown (fn [throwable]
                  (seon.error.refusal/diagnostic
                   (cond-> {:seon.error/at (Date.) :seon.error/layer :seon.cluster/adoption
                            :seon.error/operation `call-with-program-change
                            :seon.error/message (str "Development adoption of " commit-id
                                                     " changed loaded definitions without recording"
                                                     " their agreement; the loaded program is unknown"
                                                     " until an adoption records.")
                            :seon.test/not-runnable (str commit-id)
                            :seon.source/commit-id commit-id}
                     throwable (assoc :seon.error/throwable throwable))))]
    (sci.eval/advance-loaded-identity! identity-atom (unknown nil))
    (try (let [result (change)]
           (sci.eval/advance-loaded-identity! identity-atom {:seon.source/commit-id commit-id})
           result)
         (catch Throwable failure
           (sci.eval/advance-loaded-identity! identity-atom (unknown failure))
           (throw failure)))))

(declare process-identity)

(def ^:private reload-identity-attribute?
  "Declaration families reload and unmapping consume; files and findings are data."
  #{:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym})

(defn- reload-identities
  "Declaration identities reload and unmapping need, in input order."
  {:malli/schema [:=> [:cat [:seqable :seon.program/identity]] [:vector :seon.program/identity]]}
  [identities]
  (into [] (filter (comp reload-identity-attribute? first)) identities))

(def ^:private compiled-into-callers
  "Clojure expands macros/inline bodies and embeds protocol/type interfaces at
  caller compilation; these form heads require the stored reverse dependency
  closure. A plain `def` or `defmulti` is read through its Var, like a `defn`
  whose analyzed inline metadata is false."
  '#{clojure.core/defmacro clojure.core/defprotocol clojure.core/deftype
     clojure.core/defrecord clojure.core/definterface clojure.core/definline})

(defn- declaration-reload-rule
  "Missing compiler facts are unknown, never evidence for excluding callers.
  A `def` beside a `defmacro` may be read at a caller's macro expansion."
  {:malli/schema [:=> [:cat [:maybe :qualified-symbol]
                           [:or :boolean [:= :seon.reload/unknown]]
                           :boolean]
                  [:enum :seon.reload/compiled-into-callers
                   :seon.reload/var-indirection :seon.reload/unknown]]}
  [defined-by inline? macro-namespace?]
  (cond
    (or (compiled-into-callers defined-by) (true? inline?)
        (and macro-namespace? (= 'clojure.core/def defined-by)))
    :seon.reload/compiled-into-callers
    (or (#{'clojure.core/def 'clojure.core/defmulti} defined-by)
        (and (#{'clojure.core/defn 'clojure.core/defn-} defined-by) (false? inline?)))
    :seon.reload/var-indirection
    :else :seon.reload/unknown))

(defn development-namespaces
  "Changed namespaces plus dependents of compiled or unknown declarations.
  Both program values contribute: retirement must retain the old compiler fact.
  A namespace form that changed in place (docstring, requires) compiles nothing
  into callers; a namespace created or retired between the values seeds its
  dependents. A declaration whose definition digest is equal in both values
  was only moved or re-analyzed and seeds nothing."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.fn.file/identities] [:set :seon.ns/name]]
    [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.fn.file/identities]
     [:set :seon.ns/name]]]}
  ([database identities] (development-namespaces database database identities))
  ([previous database identities]
   (let [selected (into #{}
                        (keep (fn [[attribute value]]
                                (case attribute
                                  :seon.ns/name value
                                  (:seon.fn/sym :seon.test/sym) (symbol (namespace value))
                                  nil))) identities)
         databases (if (identical? previous database) [database] [previous database])
         symbols (into [] (keep (fn [[attribute value]]
                                  (when (= :seon.fn/sym attribute) value))) identities)
         rules (mapv (fn [database]
                       (let [facts (db/q '[:find ?symbol ?head ?inline
                                           :in $ [?symbol ...]
                                           :where [?e :seon.fn/sym ?symbol]
                                                  [?e :seon.fn/defined-by ?head]
                                                  [(get-else $ ?e :seon.fn/inline? :seon.reload/unknown) ?inline]]
                                         database symbols)
                             macro-namespaces (db/q '[:find [?name ...] :in $ [?symbol ...]
                                                      :where [?e :seon.fn/sym ?symbol] [?e :seon.fn/ns ?ns]
                                                             [?m :seon.fn/ns ?ns]
                                                             [?m :seon.fn/defined-by clojure.core/defmacro]
                                                             [?ns :seon.ns/name ?name]]
                                                    database symbols)]
                         (doseq [result [facts macro-namespaces]]
                           (when (:seon.db/invalid-read result)
                             (refused! "Declaration reload facts could not be read." result)))
                         (into {} (map (fn [[declared head inline?]]
                                         [declared (declaration-reload-rule
                                                  head inline?
                                                  (contains? (set macro-namespaces)
                                                             (symbol (namespace declared))))])) facts)))
                     databases)
         ;; Moving a declaration within its file changes its stored span, not
         ;; its definition digest; an equal digest in both values is no edit.
         digests (when (= 2 (count databases))
                   (mapv (fn [database]
                           (let [entities (db/q '[:find ?symbol ?digest :in $ [?symbol ...]
                                              :where [?e :seon.fn/sym ?symbol]
                                                     [?e :seon.program/definition-digest ?digest]]
                                            database symbols)]
                             (when (:seon.db/invalid-read entities)
                               (refused! "Declaration definition digests could not be read." entities))
                             (into {} entities)))
                         databases))
         unedited? (fn [symbol]
                     (and digests
                          (let [[before after] (map #(get % symbol) digests)]
                            (and (some? before) (= before after)))))
         namespace-names (into [] (keep (fn [[attribute value]]
                                          (when (= :seon.ns/name attribute) value))) identities)
         present (mapv (fn [database]
                         (let [names (db/q '[:find [?name ...] :in $ [?name ...]
                                             :where [_ :seon.ns/name ?name]]
                                           database namespace-names)]
                           (when (:seon.db/invalid-read names)
                             (refused! "Changed namespace entities could not be read." names))
                           (set names)))
                       databases)
         dependent-seeds
         (into #{}
               (keep (fn [[attribute value]]
                       (case attribute
                         :seon.ns/name (when-not (every? #(contains? % value) present) value)
                         :seon.fn/sym
                         (when (and (not (unedited? value))
                                    (some (fn [rules]
                                       (case (get rules value :seon.reload/unknown)
                                         :seon.reload/compiled-into-callers true
                                         :seon.reload/var-indirection false
                                         :seon.reload/unknown true)) rules))
                           (symbol (namespace value)))
                         nil)))
               identities)]
     (loop [dependents dependent-seeds pending dependent-seeds]
       (if (empty? pending)
         (into selected dependents)
         (let [callers (into #{}
                             (mapcat
                              (fn [value]
                                (let [result (db/q '[:find [?name ...] :in $ [?required ...]
                                                    :where [?ns :seon.ns/requires ?required]
                                                    [?ns :seon.ns/name ?name]]
                                                  value (vec pending))]
                                  (when (:seon.db/invalid-read result)
                                    (refused! "Development namespace dependents could not be read." result))
                                  result)))
                             databases)
               added (set/difference callers dependents)]
           (recur (into dependents added) added)))))))

(defn- verify-development-sources!
  "Refuse adoption when a reloaded namespace no longer has its published bytes."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:set :symbol]] :nil]}
  [database directory namespaces]
  (let [paths (db/q '[:find [?path ...] :in $ [?name ...]
                     :where [?ns :seon.ns/name ?name]
                            [?ns :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]]
                   database (vec namespaces))
        _ (when (:seon.error/at paths)
            (refused! "Reloaded namespace files could not be read." paths))
        expected (source/stored-path-digests database (vec paths))
        observed (source/path-digests directory (vec paths))
        changed (filterv #(or (nil? (get expected %))
                             (not= (get expected %) (get observed %))) paths)]
    (when (seq changed)
      (refused! "Source changed during development adoption."
                {:seon.cluster.source/phase :adoption
                 :seon.source/changed-paths changed})))
  nil)

(defn definition-digests
  "Definition digests of the named function symbols, or of every function row.

  The armed wrapper's profiling cell carries its callable's digest; a symbol
  without a digest row stays absent, so its cell's digest is unknown."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] [:map-of :qualified-symbol :seon.program/definition-digest]]
    [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
     [:map-of :qualified-symbol :seon.program/definition-digest]]]}
  ([database]
   (into {} (db/q '[:find ?sym ?digest
                    :where [?function :seon.fn/sym ?sym]
                           [?function :seon.program/definition-digest ?digest]]
                  database)))
  ([database symbols]
   (into {} (db/q '[:find ?sym ?digest
                    :in $ [?sym ...]
                    :where [?function :seon.fn/sym ?sym]
                           [?function :seon.program/definition-digest ?digest]]
                  database symbols))))

(defn- loaded-namespace-paths
  "The path, relative to the source directory, of the file behind each
  first-party namespace loaded now."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (let [root (str (.getCanonicalPath (io/file (fs/source-directory))) java.io.File/separator)]
    (vec (for [namespace-object (all-ns)
               :let [base (.. (str (ns-name namespace-object)) (replace \- \_) (replace \. \/))
                     url (some #(io/resource (str base %)) [".clj" ".cljc"])]
               :when (and url (= "file" (.getProtocol url)))
               :let [path (.getCanonicalPath (io/file (.toURI url)))]
               :when (str/starts-with? path root)]
           (subs path (count root))))))

(defonce ^:private launched-source-digests
  ;; The bytes of each first-party source file this JVM loaded at launch: the
  ;; namespaces loaded before this one read these files moments ago, and a
  ;; later reload leaves this record alone because adoption names what it
  ;; reloads (`sci.eval/loaded-identity`). The same digests the publication
  ;; captures (`source/path-digests`).
  (source/path-digests (fs/source-directory) (loaded-namespace-paths)))

(defn- loaded-program-digests
  "The digests of every input this JVM's arming read: each loaded first-party
  source file (launch bytes win over the disk for the namespaces loaded before
  this one) and every packaged schema resource, which compile the contracts
  and supplied defaults `arm-host-program!` arms."
  {:malli/schema [:=> [:cat] :seon.source/relative-file-digests]}
  []
  (let [directory (fs/source-directory)
        resources (filterv #(str/starts-with? % "resources/seon/schemas/")
                           (source/discover-paths directory source-roots))]
    (merge (source/path-digests
            directory
            (into resources (remove #(contains? launched-source-digests %)) (loaded-namespace-paths)))
           launched-source-digests)))

(defn loaded-commit
  "The loaded identity naming `commit-id` when `loaded` has its published bytes.

  `loaded` maps each first-party file a JVM loaded to its bytes' digest; the
  answer is `{:seon.source/commit-id commit-id}` when every one of those files
  that `database` (that commit's value) publishes carries the published digest,
  else nil. A loaded file the commit does not publish is no program of it.

  Costs: one indexed read per loaded file (about 500 at boot)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/commit-id
                       :seon.source/relative-file-digests]
                  [:maybe [:map [:seon.source/commit-id :seon.source/commit-id]]]]}
  [database commit-id loaded]
  (let [stored (source/stored-path-digests database (vec (keys loaded)))]
    (when (and (seq stored) (= stored (select-keys loaded (keys stored))))
      {:seon.source/commit-id commit-id})))

(defn name-loaded-program!
  "Name `commit-id` as the JVM's loaded program once its loaded bytes match.

  Sets `sci.eval/loaded-identity` when no adoption has named one yet and
  [[loaded-commit]] holds for the files this JVM loaded; answers the loaded
  identity either way. A JVM launched on the files a boot publication just
  indexed, and armed by `arm-host-program!` against that commit, already runs
  it: the adoption that follows writes the cluster's rows and record and never
  reloads or re-arms what the JVM loaded moments ago. Launch bytes win over the
  disk for namespaces loaded before this one; later loads are read now."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/commit-id] :map]}
  [store commit-id]
  (let [database (when (empty? @sci.eval/loaded-identity)
                   (source/commit-database store commit-id))]
    (if-not database
      @sci.eval/loaded-identity
      (try
        (if-let [loaded (loaded-commit database commit-id (loaded-program-digests))]
          (sci.eval/advance-loaded-identity! sci.eval/loaded-identity loaded)
          @sci.eval/loaded-identity)
        (finally (d/release-materialized-db database))))))

(defn- analyzer-reach
  "The declarations that determine a derived entity's value: reached through
  `calls` from its two producers, `seon.fn/analyze-entities` (the rows) and
  `seon.fn/desired-entities` (the stored entity; `index!` writes only the
  difference), without entering the database owner (`seon.db`,
  `seon.schema.datahike`) or the error owner (`seon.error.*`). Their values
  reach an entity only as read results or refusals: across two measured change
  sets, 35 and 325 changed definitions, none of them in this reach, left every
  re-derived entity equal (tmp/boot-source-perf/evidence.md)."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol [:sequential :qualified-symbol]]]
                  [:set :qualified-symbol]]}
  [calls]
  (let [plumbing? (fn [function]
                    (let [owner (namespace function)]
                      (or (#{"seon.db" "seon.schema.datahike" "seon.error"} owner)
                          (str/starts-with? owner "seon.error."))))]
    (loop [reached #{} pending (list `seon.fn/analyze-entities `seon.fn/desired-entities)]
      (if-let [[function & more] (seq pending)]
        (if (or (reached function) (plumbing? function))
          (recur reached more)
          (recur (conj reached function) (into more (remove reached) (get calls function))))
        reached))))

(defn- selected-analyzer-digest
  "The identity of the code that derived `entities`, which their derivation keys name:
  the digest of the definition digests `analyzer-reach` reaches through the
  stored calls of `database`, with the files at `paths`, and every file
  `entities` analyzed, replaced by `entities`.

  The JVM that analyzed them loaded the files `loaded` (`loaded-files`). A
  reached declaration whose file `inputs` publishes as those bytes contributes
  its definition digest; one whose file it loaded as other bytes contributes
  the loaded bytes' digest. So a publication of the files the JVM loaded names
  exactly the identity the stored entities name, and an unchanged tree
  publishes nothing after it; one
  analyzed by other code names an analyzer no stored program has, and every
  file republishes once that code is replaced. A nil `database` is the first
  publication."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] [:set :string] :seon.program/rows
                       :seon.source/relative-file-digests :seon.source/relative-file-digests]
                  :seon.source/analyzer-digest]}
  [database paths entities inputs loaded]
  (let [path-of #(let [file (:seon.fn/file %)]
                   (if (map? file) (:seon.fn.file/relative-path file) (second file)))
        analyzed (filterv #(and (:seon.fn/sym %) (path-of %)) entities)
        replaced (into paths (map path-of) analyzed)
        stored (if database
                 (db/q '[:find [(pull ?function [:seon.fn/sym :seon.program/definition-digest :seon.fn/calls
                                                 {:seon.fn/file [:seon.fn.file/relative-path]}]) ...]
                         :where [?function :seon.fn/sym]] database)
                 [])]
    (when (map? stored) (refused! "Published function entities could not be read." stored))
    (let [functions (into {} (map (juxt :seon.fn/sym identity))
                          (concat (sequence (comp (filter path-of)
                                                  (remove #(contains? replaced (path-of %)))
                                                  (map #(update % :seon.fn/calls set)))
                                            stored)
                                  analyzed))
          contribution (fn [function]
                         (let [path (path-of function)]
                           (if (= (get inputs path) (get loaded path))
                             (:seon.program/definition-digest function)
                             (get loaded path ::not-loaded))))]
      (id/digest 64 [(into (sorted-map)
                           (keep #(some->> (get functions %) contribution (vector %)))
                           (analyzer-reach (update-vals functions (comp vec :seon.fn/calls))))]))))

(defn- loaded-files
  "The bytes the code this JVM runs came from, which a publication's seal reads
  its entities through (`selected-analyzer-digest`): the files the commit its
  last adoption loaded publishes (`sci.eval/loaded-identity`); before any
  adoption, the files launch loaded; none when the loaded program is unknown
  (an adoption failed mid-reload) or the adopted commit is another store's (a
  fixture's root), so every reached declaration names another analyzer.

  Costs: one indexed read of the adopted commit's file digests; no projection
  and no program-wide read."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.source/relative-file-digests]}
  [store]
  (let [loaded @sci.eval/loaded-identity
        adopted (:seon.source/commit-id loaded)]
    (cond
      (not adopted) (if (seq loaded) {} launched-source-digests)
      :else
      (if-let [database (source/commit-database store adopted)]
        (try
          (into {} (d/q '[:find ?path ?digest
                          :where [?file :seon.fn.file/relative-path ?path]
                                 [?file :seon.fn.file/digest ?digest]]
                        database))
          (finally (d/release-materialized-db database)))
        {}))))

(defn published-program
  "The definition digests and program namespaces of the published `commit-id`.

  The loaded Vars derive from the files, which is what the published program
  indexes; a cluster branch may retain an older program. Boot reads
  `source/current` once and hands the same commit here and to
  [[name-loaded-program!]].
  Program namespaces are those whose file row lies under the `src/` source
  root; test namespaces load when a test request needs them."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/commit-id]
                  [:map
                   [:seon.profile/definition-digests :seon.profile/definition-digests]
                   [:seon.cluster/program-namespaces [:vector :symbol]]
                   [:seon.schema/projection :seon.schema/projection]]]}
  [store commit-id]
  (let [published (source/database store commit-id)]
    (try
      {;; The commit's own declaration world: its schema forms AND its
       ;; function contracts, which a wrapper's call-preparation plan reads.
       :seon.schema/projection (db/carried-projection published)
       :seon.profile/definition-digests (definition-digests published)
       :seon.cluster/program-namespaces
       (vec (sort (db/q '[:find [?name ...]
                          :where [?namespace :seon.ns/name ?name]
                                 [?namespace :seon.fn/file ?file]
                                 [?file :seon.fn.file/relative-path ?path]
                                 [(clojure.string/starts-with? ?path "src/")]]
                        published)))}
      (finally (d/release-materialized-db published)))))

(defn arm-host-program!
  "Load the program namespaces and arm every contracted Var, once, at boot.

  The loaded Vars derive from the files the published commit indexes, so
  their contracts compile against that commit's projection
  (`published-program`): its schema forms and its function contracts, from
  which each wrapper's call-preparation plan is decided with the cluster's
  supplied-default rows. The files' own schema declarations
  (`schema.edn/packaged-forms`) answer only when no projection is handed;
  they hold no function contract, so they arm no supplied default. Each
  profiling cell carries the digest the published program names for its
  symbol. A registration error refuses the boot: a running program never runs
  with its contracts silently unarmed."
  {:malli/schema [:=> [:cat [:map
                             [:seon.profile/definition-digests :seon.profile/definition-digests]
                             [:seon.cluster/program-namespaces [:vector :symbol]]
                             [:seon.db/db :seon.db/database-value]
                             [:seon.schema/projection {:optional true} :seon.schema/projection]]
                       :map [:or :nil :seon.flow/commit-fault!]]
                  :seon.instrument/applied]}
  [{digests :seon.profile/definition-digests namespaces :seon.cluster/program-namespaces
    database :seon.db/db published-projection :seon.schema/projection}
   effective commit-fault!]
  (doseq [namespace-name namespaces]
    (require namespace-name))
  (let [projection (or published-projection
                       (schema/declaration-projection (schema.edn/packaged-forms)))
        result (schema/call-with-projection
                projection
                #(instrument/apply!
                  (cond-> {:seon.sci.admit/caps (config/result-caps effective)
                           :seon.schema/projection projection
                           ;; The cluster's supplied-default entities: each contract's
                           ;; call preparation is decided from them at arming.
                           :seon.db/db database
                           :seon.profile/definition-digests digests}
                    (:seon.config.error/max-evidence-bytes effective)
                    (assoc :seon.config.error/max-evidence-bytes
                           (:seon.config.error/max-evidence-bytes effective))
                    commit-fault! (assoc :seon.flow/commit-fault! commit-fault!))))]
    (when (or (:seon.instrument/registration-observation result)
              (not (pos? (or (:seon.instrument/instrumented result) 0))))
      (refused! "Boot JVM instrumentation did not arm the loaded program." result))
    result))

(defn- development-arming-identities
  "Reloaded Vars and functions whose contracts refer to changed schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :symbol] :seon.fn.file/identities]
                  :seon.reconcile/adopt-identities]}
  [database namespaces identities]
  (let [schemas (into [] (keep (fn [[attribute value]]
                                (when (= :seon.schema/key attribute) value))) identities)
        referring (if (seq schemas)
                    (db/q '[:find [?sym ...]
                            :in $ % [?changed ...] [?attribute ...]
                            :where (affected ?key ?changed)
                                   [?arity ?attribute ?key]
                                   [?function :seon.fn/arities ?arity]
                                   [?function :seon.fn/sym ?sym]]
                          database
                          '[[(affected ?key ?changed)
                             [?schema :seon.schema/key ?changed]
                             [(identity ?changed) ?key]]
                            [(affected ?key ?changed)
                             [?schema :seon.schema/key ?key]
                             [?schema :seon.schema/references ?ref]
                             (affected ?ref ?changed)]]
                          schemas
                          [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                           :seon.fn.arity/guard-refs])
                    [])]
    (when (:seon.error/at referring)
      (refused! "Development contract referrers could not be read." referring))
    (into #{}
          (map #(vector :seon.fn/sym %))
          (into (set referring)
                (mapcat (fn [namespace-name]
                          (when-let [loaded (find-ns namespace-name)]
                            (map #(symbol (str namespace-name) (str %))
                                 (keys (ns-interns loaded))))))
                namespaces))))

(defn- adoption-guard-tx
  "Transaction data refusing an adoption record whose basis moved.

  Runs inside the writer: the cluster's adopted commit must still be the one
  this adoption started from, and `current-src` must still name the commit
  being adopted. Otherwise a newer publication or adoption owns the cluster."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store
                       [:tuple [:= :seon.cluster/name] :seon.boot/cluster-name]
                       [:maybe :seon.source/commit-id] :seon.source/commit-id]
                  [:= []]]}
  [database store cluster-ref prior-commit published-commit]
  (let [adopted (:seon.source/commit-id (d/pull database [:seon.source/commit-id] cluster-ref))
        head (:seon.source/commit-id (source/current store))]
    (when-not (and (= prior-commit adopted) (= published-commit head))
      (throw (ex-info "Development adoption basis moved before its record."
                      {:seon.error/message "Development adoption basis moved before its record."
                       :seon.source/expected-commit-id published-commit
                       :seon.source/commit-id head
                       :seon.source/prior-commit-id prior-commit
                       :seon.source/adopted-commit-id adopted})))
    []))

(defn- published-changes
  "Every identity `published` changed since `prior-commit`; all of it when none."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/published
                       [:maybe :seon.source/commit-id] :seon.db/database-value]
                  :seon.reconcile/adopt-identities]}
  [held-store published prior-commit published-database]
  (if prior-commit
    (source/changed-identities held-store published prior-commit)
    (into #{} (keep program/row-identity) (seon.fn/published-index-entities published-database))))

(defn- adopt-entities!
  "Write the schema, program, issue and task rows among `identities` from
  `published-database` onto `connection` in ONE transaction, and answer the
  program identities.

  Schema declarations go first: the writer applies schema datoms before the
  operations after them (`datahike/db/transaction.cljc:466`). Issues go last:
  their citations resolve the program rows. The write refuses once another
  publication moves `current-src` (`head-guard-tx`); refused, nothing lands."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/published
                       :seon.reconcile/adopt-identities :seon.db/database-value]
                  :seon.reconcile/adopt-identities]}
  [connection published identities published-database]
  (let [projection (db/carried-projection published-database)
        program-identities (into #{} (filter (comp (set program/identity-attributes) first)) identities)
        issue-identities (into #{} (keep (fn [[attribute value]]
                                         (when (= :seon.issue/id attribute) value))) identities)
        tx-data (into
                 (cond-> [[:db.fn/call registry/head-guard-tx
                           {:seon.source/branch source/current-branch
                            :seon.source/expected-commit-id (:seon.source/commit-id published)}]]
                   (some #(= :seon.schema/key (first %)) identities)
                   (conj [:db.fn/call (fn [database] (declaration-changes database projection))])
                   (seq program-identities)
                   (into (require-committed! (seon.fn/adoption-tx projection published-database program-identities)
                                             {:seon.boot/population :seon.fn/population}))
                   (seq issue-identities)
                   (into (require-committed! ((requiring-resolve 'seon.issue/adopt-call)
                                              published-database issue-identities)
                                             {:seon.boot/population :seon.issue/rows})))
                 ;; Tasks are data the publication admitted, never program entities;
                 ;; the whole set reconciles, empty when the cluster agrees.
                 (require-committed! ((requiring-resolve 'seon.issue/adopt-tasks-call)
                                      published-database (db/db connection))
                                     {:seon.boot/population :seon.task/rows}))]
    (when (next tx-data)
      (report-source-progress! "development entities")
      (require-committed!
       (schema/call-with-forms
        (:seon.schema.projection/forms projection)
        #(schema/call-with-projection projection (fn [] (db/transact! connection {:tx-data tx-data}))))
       {:seon.boot/population :seon.source/adoption-rows}))
    program-identities))

(defn- adoption-record
  "The adoption's cluster and changed file inputs, independent of declaration count."
  {:malli/schema [:=> [:cat :seon.db/ref [:seqable :string]] :seon.test/adoption]}
  [cluster-ref changed-paths]
  {:db/id :db/current-tx
   :seon.test/adoption-cluster cluster-ref
   :seon.test/adoption-inputs (set changed-paths)})

(defn- development-source-refresh!
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.boot/instance :seon.source/published
          [:vector :string]
          [:map [:seon.fn/root :string] [:seon.source/roots :seon.source/roots]]]
    [:or :seon.source/adoption-result :seon.source/publication-error]]}
  [held-store instance published changed-paths _roots]
  (let [connection (:seon.boot/cluster-connection instance)
        cluster-name (get-in instance [:seon.boot/advertisement :seon.boot/cluster-name])
        cluster-ref [:seon.cluster/name cluster-name]
        ctx (:seon.sci.eval/ctx instance)
        ;; A refused pull (invalid read or budget) is not "no adopted commit",
        ;; which would adopt every entity.
        prior (db/pull (db/db connection) [:seon.source/commit-id] cluster-ref)
        _ (when (or (:seon.db/invalid-read prior) (:seon.error/at prior))
            (refused! "The adopted source commit could not be read." prior))
        prior-commit (:seon.source/commit-id prior)]
    (cond
      (and prior-commit (= prior-commit (:seon.source/commit-id published)))
      (do (report-source-progress! "development cluster converged")
          {:seon.source/reloaded-namespaces []
           :seon.source/arming-identities #{}})
      ;; A newer publication owns adoption; this one never starts writing.
      (not= (:seon.source/commit-id published)
            (:seon.source/commit-id (source/current held-store)))
      (source/publication-error 'seon.cluster/refresh-source!
                                {:branch source/current-branch
                                 :expected-current-commit (:seon.source/commit-id published)
                                 :current-commit (:seon.source/commit-id (source/current held-store))})
      :else
      (let [previous-database (db/db connection)
        published-database (source/database held-store (:seon.source/commit-id published))]
       (try
        ;; Entities, then load, then arm, then the record: the adopter writes with
        ;; the code it started with, and the record names the loaded commit.
        ;; Entities ahead of the record are interpreted by contexts (B2 §2a).
        (let [identities (published-changes held-store published prior-commit published-database)
        program-identities (into #{} (filter (comp (set program/identity-attributes) first)) identities)
        projection (db/carried-projection published-database)
        changed-identities (reload-identities program-identities)
        deleted-identities (filterv #(empty? (db/pull published-database '[*] %)) changed-identities)
        ;; Reload and arming are the JVM's, entities and record the cluster's: a
        ;; JVM already running the published commit (booted on it, or adopted
        ;; for another cluster) only writes this cluster's entities and record.
        loaded? (= (:seon.source/commit-id published)
                   (:seon.source/commit-id @sci.eval/loaded-identity))
        namespaces (if loaded? #{}
                       (development-namespaces previous-database published-database changed-identities))]
    ;; `require :reload` reads the disk, so every namespace it will reload must
    ;; hold its published bytes FIRST: another lane's unpublished edit to a
    ;; dependent never enters this JVM. The check after reload catches an
    ;; edit that lands during it.
    (verify-development-sources! published-database (fs/source-directory) namespaces)
    (adopt-entities! connection published identities published-database)
    (report-source-progress! "development loaded definitions")
    ;; Unmap, reload and arm wait for no one: a member or evaluation spanning
    ;; them reads a changed loaded identity at its end and refuses its result.
    (let [;; The record commits before the loaded identity names it, so no
          ;; member starting between arming and the record reads it as converged.
          record!
          (fn [reloaded arming-identities]
            (report-source-progress! "development adoption record")
            (require-committed!
             (db/transact! connection
                           {:tx-data [[:db.fn/call adoption-guard-tx held-store cluster-ref
                                       prior-commit (:seon.source/commit-id published)]
                                      {:db/id cluster-ref
                                       :seon.source/commit-id (:seon.source/commit-id published)}
                                      (adoption-record cluster-ref changed-paths)]})
             {:seon.boot/population :seon.source/commit-id})
            {:seon.source/reloaded-namespaces reloaded
             :seon.source/arming-identities arming-identities})
          {reloaded :seon.source/reloaded-namespaces arming-identities :seon.source/arming-identities}
          (if loaded?
           (record! [] #{})
           (call-with-program-change
           sci.eval/loaded-identity (:seon.source/commit-id published)
           (fn []
          ;; Clojure reload leaves removed interns behind. Remove only definitions
          ;; whose identity is absent from the published database.
          (doseq [[attribute function-symbol] deleted-identities
                  :when (#{:seon.fn/sym :seon.test/sym} attribute)]
            (let [qualified (symbol function-symbol)
                  namespace-name (symbol (namespace qualified))
                  local-name (symbol (name qualified))]
              (when (find-ns namespace-name)
                (ns-unmap namespace-name local-name))))
          ;; A changed caller reloaded before its changed callee fails on the
          ;; callee's new Var, so the order follows the declared requires facts.
          (let [reloaded (load-development-definitions! namespaces (namespace-requires published-database namespaces))
                arming-identities (development-arming-identities published-database namespaces changed-identities)]
            (verify-development-sources! published-database (fs/source-directory) (set reloaded))
            (report-source-progress! "development JVM instrumentation")
            (schema/call-with-projection
             projection
             (fn []
               (let [effective (config/effective previous-database cluster-name)
                     _ (when (or (:seon.config/error-key effective)
                         (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                         (refused! "Development instrumentation configuration is unavailable."
                                   effective))
                     result (instrument/apply!
                             {:seon.flow/commit-fault!
                              (:seon.flow/commit-fault! @(:seon.sci.kernel/program-snapshot ctx))
                              :seon.sci.admit/caps (config/result-caps effective)
                              :seon.config.error/max-evidence-bytes
                              (:seon.config.error/max-evidence-bytes effective)
                              :seon.schema/projection projection
                              :seon.db/db previous-database
                              :seon.instrument/changed-identities
                              arming-identities
                              :seon.profile/definition-digests
                              (definition-digests published-database
                                                  (into [] (comp (filter #(= :seon.fn/sym (first %)))
                                                                 (map second))
                                                        arming-identities))})]
                 ;; a registration error refuses; arming nothing (a publication
                 ;; changing no definition) is not a refusal (#63)
                 (when (:seon.instrument/registration-observation result)
                   (refused! "Development JVM instrumentation did not restore contracts."
                             result)))))
            (record! reloaded arming-identities)))))]
      (let [database (db/db connection)]
        (env/advance-projection! (get ctx env/state-carrier)
                                 (db/basis-t database) (schema/projection-from-database database)))
      (when-let [channel (get-in instance
                                [:seon.render.web/view
                                 :seon.render.web/runtime-eval-channel])]
        (async/offer! channel :seon.render.web/runtime-eval))
      (report-source-progress! "development cluster converged")
      {:seon.source/reloaded-namespaces reloaded
       :seon.source/arming-identities arming-identities}))
    (finally (d/release-materialized-db published-database)))))))

(defn- reaching-run
  "The tests reaching `identities`, answered on `execution` and recorded on its
  connection. Every changed function and test is passed, deleted ones
  included: `seon.test/run` resolves a deleted symbol through history and its
  surviving incoming edges. No changed function or test, no request."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.reconcile/adopt-identities]
                  [:map [:seon.test/passed? :boolean] [:seon.source/tally :string]
                   [:seon.source/gate-run {:optional true}
                    [:or :seon.test/run-result :seon.error/value]]]]}
  [execution identities]
  (let [changed (into [] (keep (fn [[a v]] (when (#{:seon.fn/sym :seon.test/sym} a) v))) identities)
        run (when (seq changed)
              ((requiring-resolve 'seon.test/run)
               {:seon.test/policy :named :seon.test/execution execution
                :seon.test/recording-connection (:seon.db/connection execution)
                :seon.test/changed changed}))]
    (cond-> {:seon.test/passed? (if run (true? (:seon.test/passed? run)) true)
             :seon.source/tally (if run ((requiring-resolve 'seon.test/tally) run)
                                    "no changed function or test: no test selected")}
      run (assoc :seon.source/gate-run run))))

(defn candidate-gate!
  "Answer the tests reaching one change on `branch`, the source's candidate.

  `branch` is reset to the head of `source`'s connection unless it already
  names it, `write!` puts the change onto the candidate's execution handle and
  answers its identities, and `reaching-run` answers the tests reaching them
  there through the agent acquisition entrance. `source` is never written
  here: root's merge (`seon.cluster.source/prepare-merge!`) reads the verdict."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch
                       :seon.instrument/callable]
                  [:map [:seon.test/passed? :boolean] [:seon.source/tally :string]
                   [:seon.source/candidate :seon.store/branch]
                   [:seon.source/phase-ms [:map-of :keyword :double]]
                   [:seon.source/gate-run {:optional true}
                    [:or :seon.test/run-result :seon.error/value]]]]}
  [source held-store branch write!]
  (let [started (System/nanoTime)
        ms #(/ (- (System/nanoTime) %) 1000000.0)
        target {:seon.store/store held-store :seon.store/branch branch}
        head (db/commit-id (db/db (:seon.db/connection source)))
        held? (contains? (registry/roster held-store) branch)]
    (when-not (and held? (= head (registry/branch-commit-id target)))
      (when held? (registry/retire-branch! target))
      (registry/branch! (assoc target :seon.cluster.registry/from head)))
    (let [execution (cluster.agent/acquire-context! source nil {:seon.agent/branch branch})]
      ;; The candidate's write and its test run are the candidate branch's own
      ;; work: under a caller's custody the writer refuses writes naming
      ;; another branch (seon.db/foreign-connection-error).
      (try
        (db/call-with-custody
         {:seon.db/connection (:seon.db/connection execution)}
         #(let [identities (write! execution)
                candidate-ms (ms started)]
            (assoc (reaching-run execution identities)
                   :seon.source/candidate branch
                   :seon.source/phase-ms {:seon.source/candidate-ms candidate-ms
                                          :seon.source/tests-ms (- (ms started) candidate-ms)})))
        (finally (cluster.agent/release-context! execution))))))

(defn- publication-paths
  "Each changed path's identity relative to `directory`, or a refusal by name.
  A declared gitlink is its recorded pin, so its (archive-linked) directory is
  never followed; every other path must canonicalize inside `directory`, since
  one outside it would publish nothing while answering with a commit id."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:vector :string]]}
  [directory changed-paths]
  (let [escapes? #(str/starts-with? % "..")
        relative (mapv #(fs/relative-path directory %) changed-paths)
        ;; The pin inventory is read only when a canonical path escapes.
        pins (when (some escapes? relative) (test.cache/gitlink-digests directory))
        paths (mapv #(if (find pins %1) %1 %2) changed-paths relative)]
    (when-let [outside (seq (keep (fn [[given path]] (when (escapes? path) given))
                                  (map vector changed-paths paths)))]
      (refused! "Changed paths lie outside the JVM's source directory."
                {:seon.fn/root directory :seon.source/changed-paths (vec outside)}))
    paths))

(defn refresh-source!
  "Publish the current source tree onto the one `current-src` branch; a named
  development cluster adopts it.

  Content digests select changed inputs and declaration edges select affected
  files. The captured input bytes produce rows directly; the current database
  supplies prior identities. Analyzer configuration changes select every source.
  A refusal after reload began (source change, arming, record) leaves the
  record unchanged and the loaded program unknown
  (`seon.sci.eval/loaded-identity`): no test member or evaluation runs until
  the next request reconciles from the last adopted source database and records."
  {:malli/schema
   [:function
    [:=> [:cat :seon.boot/root] :seon.source/refresh-result]
    [:=> [:cat :seon.boot/root [:vector :string]] :seon.source/refresh-result]
    [:=> [:cat :seon.boot/root [:vector :string] [:maybe :seon.boot/cluster-name]]
     :seon.source/refresh-result]
    [:=> [:cat :seon.boot/root [:vector :string] [:maybe :seon.boot/cluster-name] :string]
     :seon.source/refresh-result]]}
  ([root]
   (refresh-source! root []))
  ([root changed-paths]
   (refresh-source! root changed-paths nil))
  ([root changed-paths development-cluster]
   (refresh-source! root changed-paths development-cluster (fs/source-directory)))
  ([root changed-paths development-cluster directory]
   (report-source-progress! "request accepted")
     (report-source-progress! "bootstrap configuration")
     (let [;; A publication over one second reports what the armed
           ;; definitions did during it, in its progress and its result.
           profile-mark (profile/begin)
           instance (when development-cluster
                      (get @running-instances development-cluster))
           _ (when (and development-cluster (not instance))
               (refused! "Development updates require the named cluster to be running."
                         {:seon.boot/cluster-name development-cluster}))
           changed (publication-paths directory changed-paths)
           config (resolve-bootstrap {:seon.boot/root root})
           store-dir (:seon.boot/store-dir config)
           _ (report-source-progress! "store acquisition")
           held-store (acquire-root-store! store-dir)
           ;; The publication's own roots, read before any adoption reload can
           ;; re-evaluate the vars that declare them.
           roots (assoc (publication-roots) :seon.fn/root directory
                        :seon.source/changed-paths changed
                        ::loaded-files (loaded-files held-store))]
       (try
         (report-source-progress! "source build")
         (schema/call-with-projection
          (schema/declaration-projection (schema.edn/packaged-forms))
          (fn []
            (let [published (full-source-refresh! root held-store roots)
                  adoption (cond
                             (and instance (not (:seon.error/at published)))
                             (development-source-refresh! held-store instance published changed-paths roots)
                             :else {:seon.source/reloaded-namespaces []
                                    :seon.source/arming-identities #{}})
                  result (cond
                           (:seon.error/at published) published
                           (:seon.error/at adoption) adoption
                           :else (merge published adoption))]
              (if-let [explanation (profile/explain-slow profile-mark)]
                (do (report-source-progress!
                     (str "profile: " (str/join "\n  " (:seon.profile/lines explanation))))
                    (assoc result :seon.profile/explanation explanation))
                result))))
         (finally
           (release-root-store! store-dir))))))

;;; Will this program boot? A boot publishes the files that differ from the
;;; published program and the cluster adopts that publication; both refuse
;;; committed files boot cannot install (a tag naming a task no file declares,
;;; a task file that is not one vector, an entity its schema refuses). The
;;; rehearsal runs those same steps in the running JVM on two disposable
;;; branches of the live store.

(defn- rehearsal-adoption!
  "Adopt `published`'s changes onto `fork`, a new branch of `cluster-name`'s
  branch, exactly as `development-source-refresh!` writes the cluster's
  entities (its reload, arming and record belong to the JVM and are not run);
  answer the adopted identity count. The adoption's head guard is handed
  `current-src`'s commit, which the rehearsal leaves in place."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/published :seon.boot/cluster-name
                       :seon.store/branch]
                  :int]}
  [held published cluster-name fork]
  (registry/branch! {:seon.store/store held :seon.store/branch fork
                     :seon.cluster.registry/from (registry/cluster-branch cluster-name)})
  (let [connection (store/open-branch! held fork)
        published-database (source/database held (:seon.source/commit-id published))]
    (try
      (let [prior (db/pull (db/db connection) [:seon.source/commit-id] [:seon.cluster/name cluster-name])
            _ (when (or (:seon.db/invalid-read prior) (:seon.error/at prior))
                (refused! "The adopted source commit could not be read." prior))
            prior-commit (:seon.source/commit-id prior)]
        ;; A cluster that adopted this commit is converged, as
        ;; `development-source-refresh!` answers it.
        (if (= prior-commit (:seon.source/commit-id published))
          0
          (let [identities (published-changes held published prior-commit published-database)]
            (db/call-with-custody {:seon.db/connection connection}
                                  ;; `published` names only the head guard's
                                  ;; commit: `current-src`'s, which stays put
                                  #(adopt-entities! connection (merge published (source/current held))
                                                    identities published-database))
            (count identities))))
      (finally
        (d/release-materialized-db published-database)
        (store/release-branch! connection)))))

(defn- candidate-resources
  "A class loader answering `seon/schemas` resources from `directory`'s
  `resources` and everything else from `parent`: the candidate's schema
  population as its own JVM's classpath would serve it
  (`schema.edn/packaged-forms` reads through `io/resource`, keyed by the URL)."
  {:malli/schema [:=> [:cat :string :seon.test/class-loader] :seon.test/class-loader]}
  [directory ^ClassLoader parent]
  (proxy [ClassLoader] [parent]
    (getResource [^String resource-name]
      (if (str/starts-with? resource-name "seon/schemas")
        (let [file (io/file directory "resources" resource-name)]
          (when (.exists file) (io/as-url file)))
        (.getResource parent resource-name)))))

(defn rehearse-source!
  "Answer whether `directory`'s program (a candidate commit's archive) boots on
  this store: its publication and `cluster-name`'s adoption of it, run as boot
  runs them, onto disposable branches unlinked before the answer.

  The files that differ from the published program publish through
  `full-source-refresh!` onto a new branch (`source/publish!`'s
  `:seon.source/branch`), reading the candidate's schema resources
  ([[candidate-resources]]) and analyzing with a copy of this JVM's analyzer
  cache, so neither the shared cache nor `current-src` changes. The cluster's
  entities then adopt it on a fork of its branch (`rehearsal-adoption!`). A
  refusal is returned as the error value boot would have stopped on.

  Known issues: host code does not reload, so a candidate's changed boot or
  publication code is not exercised (a boot write naming an attribute the
  cluster adopts only after boot passes here and refuses at boot).

  Costs: proportional to the files that differ from the published program,
  plus one copy of the analyzer cache (about 6 MB, 0.2 s). Measured
  2026-09-28 on default, unarmed rehearsal code: HEAD 1.6 s, one changed test
  file 5.1 s, three changed files with a schema resource 6.5 s."
  {:malli/schema [:=> [:cat :seon.boot/root :string :seon.boot/cluster-name]
                  [:or
                   [:map [:seon.source/commit-id :seon.source/commit-id]
                    [:seon.source/built? :boolean]
                    [::adopted-identities :int]
                    [::phase-ms [:map-of :keyword :int]]]
                   [:and :seon.error/base [:map [:seon.error/layer [:= :seon.source/publication]]]]]]}
  [root directory cluster-name]
  (let [began (System/nanoTime)
        ms #(quot (- (System/nanoTime) began) 1000000)
        store-dir (:seon.boot/store-dir (resolve-bootstrap {:seon.boot/root root}))
        held (acquire-root-store! store-dir)
        token (random-uuid)
        published-branch (keyword (str "rehearsal-" token))
        fork (keyword (str "rehearsal-" token "-" cluster-name))
        ;; Beside, never inside, the archive's `.clj-kondo/.cache`: a launched
        ;; archive links that path to the root's shared cache.
        cache-root (str (io/file directory (str ".rehearsal-" token)))
        thread (Thread/currentThread)
        loader (.getContextClassLoader thread)]
    (try
      (babashka.fs/copy-tree analyzer/cache-directory cache-root)
      (.setContextClassLoader thread (candidate-resources directory loader))
      (schema/call-with-projection
       (schema/declaration-projection (schema.edn/packaged-forms))
       (fn []
         (let [published (full-source-refresh!
                          root held (assoc (publication-roots)
                                           :seon.fn/root directory
                                           :seon.source/changed-paths []
                                           ::loaded-files (loaded-files held)
                                           :seon.source/branch published-branch
                                           :seon.fn.analyzer/cache-root cache-root))]
           (if (:seon.error/at published)
             published
             (let [published-ms (ms)
                   adopted (rehearsal-adoption! held published cluster-name fork)]
               (assoc (select-keys published [:seon.source/commit-id :seon.source/built?])
                      ::adopted-identities adopted
                      ::phase-ms {::published-ms published-ms
                                  ::adopted-ms (- (ms) published-ms)}))))))
      (catch Exception failure
        (seon.error.refusal/diagnostic
         {:seon.error/at (Date.) :seon.error/layer :seon.source/publication
          :seon.error/operation `rehearse-source! :seon.error/message (ex-message failure)
          :seon.error/throwable failure}))
      (finally
        (.setContextClassLoader thread loader)
        (try
          (doseq [branch [fork published-branch]
                  :when (contains? (registry/roster held) branch)]
            (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))
          (fs/delete-recursively! directory cache-root)
          (finally (release-root-store! store-dir)))))))

;;; ---------------------------------------------------------------------------
;;; Recovery — the pass that runs before anything resumes
;;; ---------------------------------------------------------------------------

(defn process-identity
  "Process provenance from its pid and start instant, carried on execution requests."
  ;; IT NEEDS (pid, start-instant) AND NOTHING ELSE, so that is what it
  ;; declares: `:seon.cluster.process/identity`, which every advertisement
  ;; satisfies (maps are open). Demanding the whole advertisement to compute
  ;; an identity was a lie about the dependency, and under armed contracts it
  ;; refused three fixtures that hand exactly the pair the identity IS.
  {:malli/schema [:=> [:cat :seon.cluster.process/identity]
                  :seon.db.process/id]}
  [advertisement]
  (cluster.process/id advertisement))

(defn recover-runs!
  "Close all prior open turns before arming agents. The writer decides
  which evaluations and effects remain unfinished; nothing is replayed."
  {:malli/schema
   [:=> [:cat :seon.db/connection]
    [:or
     [:map
      [:seon.boot/recovered-runs :seon.boot/recovered-runs]
      [:seon.boot/recovery-operations :seon.boot/recovery-operations]]
     :seon.error/value]]}
  [connection]
  (let [db (db/db connection)
        now (java.util.Date.)
        open-runs (db/q '[:find [?run-id ...]
                         :where
                         [?run :seon.turn/id ?run-id]
                         (not [?run :seon.turn/closed-tx _])]
                       db)
        result
        (if (and (map? open-runs)
                 (contains? open-runs :seon.error/at)
                 (contains? open-runs :seon.error/layer)
                 (contains? open-runs :seon.error/operation))
          open-runs
          (let [;; the decision moved INSIDE the transaction (custody revision,
                ;; Revision 4): `recover-call` reads each run's receipts at
                ;; transaction time, so this caller only names the open runs —
                ;; a stale-basis recovery stamping a settled receipt is
                ;; unrepresentable
                operations
                (into (schedule/recover-tx db now)
                      (mapcat
                       (fn [run-id]
                         (turn/recover-tx
                          {:seon.turn/id run-id
                           :seon.turn/now now})))
                      open-runs)]
            (when (seq operations)
              (require-committed!
               (db/transact! connection operations)
               {:seon.boot/population :seon.turn/recovery}))
            {:seon.boot/recovered-runs (count open-runs)
             :seon.boot/recovery-operations (count operations)}))]
    result))

;;; ---------------------------------------------------------------------------
;;; The armed layers — the fault consumer, the root agent, and the loop
;;; ---------------------------------------------------------------------------

;;; THE ROOT AGENT. One entity, ensured at boot through the same atomic
;;; id + namespace + cluster-ref transition every agent uses. It costs no
;;; process. It exists so escalation has somewhere honest to go — before it,
;;; the escalation dial had to ship absent because naming an agent that might
;;; not exist would have been a lie.
(def root-agent-id "root")

(defn- require-committed!
  [result offense]
  (when (:seon.error/at result)
    (refused! (str "The cluster population transaction was refused: " (:seon.error/message result))
              (assoc offense :seon.boot/result result)))
  result)

(defn ensure-cluster-entity!
  "Exactly converge the branch-local cluster entity's shared base set."
  {:malli/schema
   [:=> [:cat :seon.db/connection
         :seon.cluster/name
         :seon.db.process/id]
    :nil]}
  [connection cluster-name process]
  ;; Transaction metadata cannot resolve a lookup ref introduced by that same
  ;; transaction. Establish this process under the bootstrap provenance first;
  ;; subsequent cluster and agent transactions can then name it honestly.
  (when-not (db/q '[:find ?entity .
                   :in $ ?process
                   :where [?entity :seon.db.process/id ?process]]
                 (db/db connection) process)
    (require-committed!
     (db/transact!
      connection
      {:tx-data [{:seon.db.process/id process}]
       :tx-meta {:seon.db/process
                 [:seon.db.process/id boot-process-identity]}})
     {:seon.db.process/id process
      :seon.boot/population :seon.db.process/process}))
  (let [toolkit-namespaces (instruction/toolkit-namespaces (db/db connection))
        desired {:seon.cluster/name cluster-name
                 :seon.cluster/config
                 [:seon.config/cluster cluster-name]
                 :seon.cluster/instructions
                 (mapv (fn [instruction-id]
                         [:seon.cluster.instruction/id instruction-id])
                       instruction/instruction-ids)
                 :seon.cluster/toolkit
                 (mapv (fn [namespace-name]
                         [:seon.ns/name namespace-name])
                       toolkit-namespaces)}
        expected-current
        {:seon.cluster/name cluster-name
         :seon.cluster/config {:seon.config/cluster cluster-name}
         :seon.cluster/instructions
         (into #{}
               (map (fn [instruction-id]
                      {:seon.cluster.instruction/id instruction-id}))
               instruction/instruction-ids)
         :seon.cluster/toolkit
         (into #{}
               (map (fn [namespace-name]
                      {:seon.ns/name namespace-name}))
               toolkit-namespaces)}
        current (some-> (db/pull (db/db connection)
                                '[:seon.cluster/name
                                  {:seon.cluster/config
                                   [:seon.config/cluster]}
                                  {:seon.cluster/instructions
                                   [:seon.cluster.instruction/id]}
                                  {:seon.cluster/toolkit
                                   [:seon.ns/name]}]
                                [:seon.cluster/name cluster-name])
                        (dissoc :db/id)
                        (update :seon.cluster/instructions set)
                        (update :seon.cluster/toolkit set))]
    (when-not (= expected-current current)
      (require-committed!
       (db/transact!
        connection
        {:tx-data
         (cond-> []
           current
           (conj [:db/retract
                  [:seon.cluster/name cluster-name]
                  :seon.cluster/instructions]
                 [:db/retract
                  [:seon.cluster/name cluster-name]
                  :seon.cluster/toolkit])
           true (conj desired))
         :tx-meta {:seon.db/process [:seon.db.process/id process]}})
       {:seon.cluster/name cluster-name
        :seon.boot/population :seon.cluster/cluster})))
  nil)

(defn seed-cluster-facts!
  "Write the facts boot seeds for a cluster: its config row, then its entity.

  Boot and a test request's world both use this writer. No agent row is among
  these facts: boot seeds root separately (`seed-root-agent!`), and a test
  world holds only the agents its own seeds or body create. Returns the config
  reconciliation result; a refused write throws."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.cluster/name
                       :seon.config/compiled :seon.db.process/id]
                  :seon.reconcile/result]}
  [connection cluster-name compiled process]
  (let [result (config/apply-compiled! connection compiled)]
    (ensure-cluster-entity! connection cluster-name process)
    result))

(defn ensure-entity-call
  "Create an absent agent inside the transaction; otherwise change nothing."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         :seon.db.process/id
         :inst
         :seon.agent/creation-request]
    :seon.store/transaction-data]}
  [db process now
   {agent-id :seon.agent/id
    namespace-name :seon.ns/name
    :as request}]
  (if (db/q '[:find ?agent .
             :in $ ?agent-id
             :where [?agent :seon.agent/id ?agent-id]]
           db agent-id)
    []
    (into (cluster.agent/creation-tx request)
          (bootstrap/seed-tx
           db
           {:seon.agent/id agent-id :seon.cluster/name (:seon.cluster/name request) :seon.ns/name namespace-name :seon.db.process/id process :seon.turn/opened-tx "datomic.tx"}))))

(defn ensure-entity!
  "Create one absent agent atomically and return its durable useful identity.

  An existing agent resumes untouched, so the result is always derived from
  the committed database value rather than from the caller's request."
  {:malli/schema
   [:=> [:cat :seon.db/connection
         :seon.db.process/id
         :seon.agent/creation-request]
    [:or :seon.agent/creation-result :seon.error/value]]}
  [connection process request]
  (let [transaction-result
        (db/transact!
         connection
         {:tx-data [[:db.fn/call #'ensure-entity-call
                     process (java.util.Date.) request]]
          :tx-meta {:seon.db/process [:seon.db.process/id process]}})]
    (if (or (:seon.db.write.attempt/request-id transaction-result)
            (:seon.db/invalid-read transaction-result)
            (:seon.schema/expected-value transaction-result))
      transaction-result
      ;; Read back from the connection because this operation needs only the
      ;; current committed value, not the rest of the transaction report.
      (let [database (db/db connection)
            agent-id (:seon.agent/id request)
            bootstrap-run-id (bootstrap/run-id agent-id)
            agent
            (db/pull database
                     '[:seon.agent/id
                       {:seon.agent/namespace [:seon.ns/name]}]
                     [:seon.agent/id agent-id])
            run-agent-id
            (db/q '[:find ?agent-id .
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?run :seon.turn/agent ?agent]
                    [?agent :seon.agent/id ?agent-id]]
                  database bootstrap-run-id)
            namespace-name
            (get-in agent [:seon.agent/namespace :seon.ns/name])
            cluster-name (config/agent-cluster database agent-id)]
        (if (and namespace-name (string? cluster-name) (= agent-id run-agent-id))
          {:seon.agent/id agent-id
           :seon.ns/name namespace-name
           :seon.cluster/name cluster-name
           :seon.turn/id bootstrap-run-id}
          {:seon.agent/creation-incomplete agent-id
           :seon.error/at (java.util.Date.)
           :seon.error/layer :seon.cluster/operation
           :seon.error/operation 'seon.cluster/ensure-entity!
           :seon.error/message
           (str "Agent " (pr-str agent-id)
                " committed without its namespace, cluster, or bootstrap run.")
           :seon.error/data {:seon.agent/id agent-id}})))))

(defn seed-root-agent!
  "Ensure root and its agent-owned maintenance initialization exist.

  Boot runs this before any agent is armed and root is never retracted, so a
  pre-read of the current value cannot change before the writer acts: an
  existing root and complete maintenance seed commit no empty transaction
  (two per resume, 1,308 -> 386 ms, lane-resume-in-seconds-2026-09-23)."
  [connection cluster-name process]
  (when-not (db/q '[:find ?agent . :in $ ?agent-id :where [?agent :seon.agent/id ?agent-id]]
                  (db/db connection) root-agent-id)
    (require-committed!
     (ensure-entity!
      connection
      process
      {:seon.agent/id root-agent-id
       :seon.cluster/name cluster-name
       :seon.ns/name 'my.agents.root})
     {:seon.agent/id root-agent-id
      :seon.boot/population :seon.agent/agent}))
  (when (seq (schedule/root-maintenance-seed-call (db/db connection)))
    (require-committed!
     (db/transact!
      connection
      {:tx-data [[:db.fn/call #'schedule/root-maintenance-seed-call]]
       :tx-meta {:seon.db/process [:seon.db.process/id process]}})
     {:seon.agent/id root-agent-id
      :seon.boot/population :seon.schedule/root-maintenance})))

(defn serve!
  "Bind the cluster's web view and publish its actual URL and port.

  Every bind rewrites the existing PREPL advertisement before returning the
  updated instance. A publication failure closes the new listener and throws.

  The last layer, deliberately: everything it renders must already
  stand, and a failure here must not be able to cost the run loop. It
  throws like any other boot layer, so the REPL survives and the
  degraded instance carries what stood — which is the honest behaviour
  for a port that is already taken, rather than a silent fallback to a
  no-UI-today state that nobody would notice until they opened a
  browser.

  THE PORT IS DERIVED FROM THE CLUSTER NAME, so a named cluster answers
  on the same port after every restart and a browser tab keeps working.
  A manifest dial still wins — an explicit port is somebody's decision
  and outranks a derivation. When the derived port is taken, the view
  binds an ephemeral one and says BOTH numbers, because a name collision
  must not look like a broken build and a moved bookmark must not fail
  silently."
  {:malli/schema [:=> [:cat :seon.cluster/servable :seon.config/effective] :seon.cluster/served]}
  [instance dials]
  (let [advertisement (:seon.boot/advertisement instance)
        cluster-name (:seon.boot/cluster-name advertisement)
        connection (:seon.boot/cluster-connection instance)
        view (assoc (:seon.render.web/view instance)
                    :seon.turn.loop/cluster (:seon.turn.loop/cluster instance))
        wanted (or (:seon.config.web/port dials)
                   (web/derived-port cluster-name))
        served (web/start!
                ;; THE VIEW HALF comes from the armed layer, not from
                ;; here: the mult, the watched registration, and the
                ;; render wake channel all belong to the render proc's
                ;; pipeline, and the web service only taps and offers.
                ;; The coalesce floor is no longer passed — the PROC
                ;; reads it from the config facts per pass (F2 §1.2), so
                ;; a live dial change applies without restarting a tab.
                (merge {:seon.render.web/port wanted
                        :seon.store/connection-object connection
                        :seon.agent/id root-agent-id
                        :seon.sci.admit/caps (config/result-caps dials)}
                       (select-keys view
                                    [:seon.turn.loop/cluster
                                     :seon.agent/routing
                                     :seon.render.web/pages-mult
                                     :seon.render.web/registration
                                     :seon.render.web/latest-packages
                                     :seon.render.web/render-channel
                                     :seon.db.process/id
                                     :seon.sci.eval/ctx
                                     :seon.config.eval/time-limit-ms])))]
    (if-let [unavailable (:seon.render.web/wanted-port served)]
      (log/warn (str "seon " cluster-name " view: port " unavailable
                     " was taken, serving on "
                     (:seon.render.web/url served)
                     " instead — a bookmark on " unavailable
                     " will not reach this cluster"))
      (log/info (str "seon " cluster-name " view: "
                     (:seon.render.web/url served))))
    (let [advertisement (merge advertisement
                               (select-keys served [:seon.render.web/url
                                                    :seon.render.web/port]))]
      (try
        (write-advertisement!
         (cluster-paths (get-in instance [:seon.boot/config :seon.boot/root])
                        cluster-name)
         advertisement)
        (assoc instance
               :seon.render.web/served served
               :seon.boot/advertisement advertisement)
        (catch Throwable cause
          (web/stop! (merge served (select-keys view [:seon.render.web/registration :seon.config.eval/time-limit-ms])))
          (throw cause))))))

(defn- loop-handle
  "The process resources and structural dials the loop proc carries.

  AI settings are deliberately absent: the `:call` branch resolves them
  from current cluster and agent facts once per turn, so config apply and
  per-agent overrides take effect on the next turn without rebuilding the
  graph."
  [connection cluster-name process ctx work-launcher
   wake-channel stream-channel completion]
  (let [dials (config/effective (db/db connection) cluster-name)]
    (cond-> {;; The one environment value this cluster's procs and
             ;; submissions carry. The handle's remaining entries are
             ;; process-local ports and structural dials, which are not
             ;; environment members.
             :seon.env/environment (env/of ctx)
             :seon.sci.eval/projection-state
             (:seon.sci.eval/projection-state ctx)
             :seon.db/connection connection
              :seon.cluster/name cluster-name
              :seon.db.process/id process
              :seon.flow/work-launcher work-launcher
              :seon.sci.eval/ctx ctx
              :seon.cluster.wake/channel wake-channel
              ;; the cluster's ONE stream conn (F2 §2.1): sliding-1, so
              ;; the newest complete snapshot wins and the provider fold
              ;; is never parked by presentation
              :seon.turn.loop/stream-channel stream-channel
              :seon.turn.loop/completion completion
              :seon.sci.admit/caps (config/result-caps dials)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms dials)
              :seon.config.error/recurrence-limit
              (:seon.config.error/recurrence-limit dials)
              ;; the fault family's own bound rides with the loop's other
              ;; dials, so every committer this cluster owns hands it to
              ;; `seon.error/commit-tx` instead of falling back to a
              ;; bootstrap number nobody chose
              :seon.config.error/max-evidence-bytes
              (:seon.config.error/max-evidence-bytes dials)
              :seon.config.eval.result/blob-threshold
              (:seon.config.eval.result/blob-threshold dials)
              ;; the conversation bound: every delivery a turn makes is
              ;; measured against it, so the loop must carry it the same
              ;; way it carries every other dial — derived from facts
              ;; once, here, never read at the call site
              :seon.config.message/max-chain
              (:seon.config.message/max-chain dials)}
      (:seon.config.error/escalate-to dials)
      (assoc :seon.config.error/escalate-to
             (:seon.config.error/escalate-to dials)))))

(defn projection-executor
  "Run IO work on the process executor with one cluster projection bound."
  {:malli/schema [:=> [:cat :seon.sci.eval/projection-state]
                  :seon.flow/executor]}
  [projection-state]
  ;; core.async's memoized process `:io` executor (`impl/dispatch.clj`)
  (let [^Executor delegate (async.dispatch/executor-for :io)]
    (reify Executor
      (execute [_ command]
        (.execute
         delegate
         ^Runnable
         (reify Runnable
           (run [_]
             (schema/call-with-projection-state
              projection-state
              #(.run ^Runnable command)))))))))

(defn- cluster-graph-definition
  "The cluster's OWN small graph (F1 R7, F2 §1): armer, render, and the
  render proc — a schedule proc later. One graph per cluster,
  so the components that arm agents and derive pages have exactly the
  ping/error/pause uniformity every other proc has. The render proc's
  channels are external ports (created by `arm-agents!`, carried on the
  handle and the view), so the graph definition stays pure data."
  [handle routing view]
  (let [environment (env/of handle)]
    {:procs {:seon.agent/armer
             {:proc (flow/var-process
                     #'cluster.agent/armer-step :io
                     (env/carry {:seon.turn.loop/cluster handle
                                 :seon.agent/routing routing
                                 ;; THE ROUTING DELIVERY (F1 §4): one listener per
                                 ;; cluster, living exactly as long as the armer proc.
                                 :seon.cluster.wake/route
                                 {:seon.cluster.wake/connection (:seon.db/connection handle)
                                  :seon.cluster.wake/channels
                                  (fn [] (cluster.agent/channels routing))
                                  :seon.cluster.wake/fenced?
                                  (fn [agent-eid channel]
                                    (cluster.agent/fenced-route? routing agent-eid channel))
                                  :seon.cluster.wake/armer-channel (:seon.cluster.wake/channel handle)
                                  :seon.cluster.wake/render-channel
                                  (:seon.render.web/render-channel view)
                                  :seon.render.web/interest (:seon.render.web/interest view)
                                  :seon.cluster.wake/key :seon.agent/route}}
                                environment))}
             :seon.render.web/render
             {:proc (flow/var-process
                     #'web/render-step :io
                     (env/carry (assoc view
                                       :seon.turn.loop/cluster handle
                                       :seon.agent/routing routing)
                                environment))}}
     :conns []}))


(defn arm-agents!
  "Arm the cluster's shared graph, fan-out, routing listener and prime.
  The armer acquires agent graphs only for current work or stored schedules.
  Idle agents remain facts until a wake or schedule needs their graph. A
  scheduled agent needs its existing timer proc even when it has no turn;
  its SCI program remains unacquired until evaluation. Recovery has already
  closed interrupted turns, and pending durable work is derived by the prime.

  ORDER IS THE CONTRACT. The cluster graph starts first, and its armer
  proc registers the routing listener as it starts (the listener's
  lifetime is that proc's); the armer prime comes LAST, after the
  listener, so an agent created between the
  prime's derivation and the listener's registration cannot exist —
  anything committed earlier is in the facts the prime's pass reads.
  This is the wiring whose absence meant every core fault in a live
  cluster was dropped by a sliding buffer nobody read."
  [instance connection cluster-name]
  (let [process (process-identity (:seon.boot/advertisement instance))
        armer-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        io-executor
        (projection-executor
         (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance)))
        ;; The cluster owns its agents' routing; its handle carries it so each
        ;; live acquisition, and so each arm, holds the same one.
        routing (cluster.agent/routing)
        handle
        (assoc
         (loop-handle connection cluster-name process
                      (:seon.sci.eval/ctx instance)
                      (:seon.flow/work-launcher instance)
                      armer-channel stream-channel completion)
         :seon.flow/executor io-executor
         :seon.agent/routing routing)
        ;; the render pipeline's external ports (F2 §1): the wake
        ;; channel route! delivers into, the pages channel the proc's
        ;; snapshots exit on (multed here, tapped per tab), the watched
        ;; registration the feed writes, and the proc's own orderly-stop
        ;; completion — all process-local, all free to lose
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        latest-packages (atom {})
        render-interest (atom :all)
        view {:seon.render.web/render-channel render-channel
              :seon.render.web/runtime-eval-channel runtime-eval-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages latest-packages
              :seon.render.web/interest render-interest
              :seon.render.web/completion (async/promise-chan)
              :seon.render.web/root-agent-id "root"
              :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms handle)
              ;; Execution provenance for web effects.
              :seon.db.process/id (:seon.db.process/id handle)}
        {graph :seon.flow/graph
         executor :seon.flow/executor
         joins :seon.flow/joins}
        (flow/start-graph!
         {:seon.flow/projection-state
          (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance))
          :seon.flow/graph-definition
          (cluster-graph-definition handle routing view)
          :seon.flow/joins
          {::pages-mult
           (fn [_]
             (async/mult pages-channel))
           ;; A Flow proc has no handle on its own graph (core.async
           ;; flow/impl.clj:166, "the only connection to a running flow is
           ;; via channels"). Joins run before `resume`, so the render proc's
           ;; routing entry holds the graph before its first pass: oversight
           ;; reads the value it was handed, never a registry search.
           ::routing-graph
           (fn [{graph :seon.flow/graph}]
             (swap! routing assoc :seon.flow/graph graph))}})
        pages-mult (::pages-mult joins)]
    ;; The armer proc registered the routing listener as the graph started.
    ;; BOOT IS ONE WAKE through the ordinary render path. The listener is
    ;; already registered and the cold interest is `:all`, so commits before
    ;; this offer are covered by the same newest-database derivation as every
    ;; later pass.
    (async/offer! render-channel ::wake/render)
    ;; Derive agents with work or schedules through the ordinary armer. The listener is already registered, so every arm's
    ;; mailbox prime and every commit concurrent with it are conserved.
    ;; Direct invocation publishes readiness: a returned instance is
    ;; armed, while the running proc owns every later wake.
    (cluster.agent/armer-step
     (cluster.agent/armer-step
      {:seon.turn.loop/cluster handle
       :seon.agent/routing routing})
     :seon.agent/arm
     :seon.agent/boot)
    {:seon.turn.loop/cluster handle
     :seon.flow/graph graph
     :seon.flow/executor executor
     :seon.agent/routing routing
     ;; the view half `serve!` hands to the web service: one mult over
     ;; the proc's pages out-port, the shared registration, and the
     ;; wake channel a freshly opened tab offers into
     :seon.render.web/view
     (assoc view
            :seon.agent/routing routing
            :seon.render.web/pages-mult pages-mult)}))

(defn disarm-agents!
  "Unwind the armed layers of ONE instance, newest first.
  An explicit armer quiescence event removes the routing listener, so
  nothing new is routed while the graphs unwind, proves every
  earlier arm wake has settled and closes that input, while the render
  proc remains available to active agent turns. Each agent graph is
  then joined; only after those turns exit does the cluster graph stop
  and the fan-out detach its taps. Each layer is released only if it
  stands — a degraded instance disarms the same way.

  ORDERLY STOP JOINS THE PROC THREADS. `flow/stop` only queues
  `::flow/stop`; it does not join the proc (`flow/impl.clj:174-183`).
  Every graph runs on its own executor (`seon.flow/start-graph!`), whose
  termination is the exit of every proc loop, stop transition included.
  The view is joined first (its server thread and every tab's writer,
  `seon.render.web/stop!`); the joins after it share ONE deadline taken at
  entry from `:seon.config.agent/turn-completion-backstop-ms`.

  ## Errors

  - A join that misses its bound answers the await owner's timeout value
    naming the missing exit (`:seon.await/requested-member`); nothing after
    it is released, so the caller keeps custody of what still runs.

  This is orderly-stop behavior only. A process kill cannot await a
  completion and may lose an in-flight transaction by design; the crash
  model owns that row and the next boot settles its durable wreckage."
  {:malli/schema [:=> [:cat :map] [:or :nil :seon.await/timeout-error]]}
  [instance]
  (let [handle (:seon.turn.loop/cluster instance)
        view (:seon.render.web/view instance)
        backstop-ms (when handle
                      (:seon.config.agent/turn-completion-backstop-ms
                       (config/effective (db/db (:seon.db/connection handle))
                                         (:seon.cluster/name handle))))
        ;; ONE deadline from the stop request, shared by every join below
        bound {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
               :seon.await/config-value backstop-ms
               :seon.await/deadline-nanos
               (when backstop-ms (+ (System/nanoTime) (* 1000000 backstop-ms)))}
        missed (fn [member result]
                 (when (:seon.error/at result)
                   (assoc result :seon.await/requested-member member)))]
    (or
     ;; the VIEW goes first: it is the newest layer and the only one
     ;; holding sockets belonging to somebody outside this process
     (when-let [served (:seon.render.web/served instance)]
       (missed ::view-exited
               (web/stop! (merge served (select-keys view [:seon.render.web/registration
                                                           :seon.config.eval/time-limit-ms])))))
     (when handle
       (let [armer-channel (:seon.cluster.wake/channel handle)
             quiesced (async/promise-chan)]
         (when-not (async.protocols/closed? armer-channel)
           (or (missed ::armer-quiesced
                       (await/await!
                        {:seon.await/bound bound
                         :seon.await/diagnostic {:seon.error/layer :seon.cluster/stop
                                                 :seon.error/operation `disarm-agents!
                                                 :seon.error/expected ::armer-quiesced}
                         :seon.await/port-operations
                         [[armer-channel {:seon.agent/quiesce quiesced}] quiesced]
                         :seon.await/accept? #(= :seon.agent/quiesced %)}))
               ;; Closure is the observable completion fact a later stop derives
               ;; from. Publish it only after the armer acknowledged quiescence.
               (async/close! armer-channel)))))
     (when-let [routing (:seon.agent/routing instance)]
       (doseq [agent-id (sort (keys (:seon.agent/armed @routing)))]
         (cluster.agent/disarm! {:seon.agent/id agent-id
                                 :seon.agent/routing routing
                                 :seon.await/deadline-nanos
                                 (:seon.await/deadline-nanos bound)})))
     ;; BOTH cluster-graph procs are joined at their executor's termination:
     ;; `flow/stop` only queues `::flow/stop`, so a render pass holding the
     ;; branch connection would otherwise still be deriving when it is released
     (when-let [graph (:seon.flow/graph instance)]
       (flow.core/stop graph)
       (missed ::cluster-graph-exited
               (flow/join-graph!
                (into (select-keys instance [:seon.flow/executor])
                      {:seon.await/bound bound
                       :seon.await/diagnostic {:seon.error/layer :seon.cluster/stop
                                               :seon.error/operation `disarm-agents!
                                               :seon.error/expected ::cluster-graph-exited}}))))
     (do
       (some-> (:seon.turn.loop/stream-channel handle) async/close!)
       ;; the render pipeline's own ports, after the proc that reads them has
       ;; published its completion: a tab still looping on a tap sees its tap
       ;; close and falls out of the loop
       (when view
         (async/close! (:seon.render.web/render-channel view))
         (async/close! (:seon.render.web/runtime-eval-channel view))
         (async/close! (:seon.render.web/pages-channel view)))
       nil))))
