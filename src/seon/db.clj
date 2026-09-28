(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.core.cache.wrapped :as cache]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [editscript.core :as editscript]
            [editscript.edit :as editscript.edit]
            [malli.core :as m]
            [clojure.core.async :as async]
            [datahike.api :as d]
            [datahike.writer]
            [datahike.connector :as connector]
            [datahike.connections :as connections]
            [datahike.constants :as const]
            [datahike.datom :as datahike.datom]
            [datahike.db :as datahike.db]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as db.utils]
            [datahike.query :as query]
            [datahike.pull-api :as pull-api]
            [datahike.schema :as datahike.schema]
            [datahike.store :as datahike.store]
            [datalog.parser.impl.proto :as parser]
            [datalog.parser.impl :as parser.impl]
            [datalog.parser.type :as parser.type]
            [clojure.test.check.generators :as gen]
            [seon.cache]
            [seon.batch :as batch]
            [seon.cluster.process :as process]
            [seon.env :as env]
            [seon.error.refusal :as error.refusal]
            [seon.id :as id]
            [seon.print :as print]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [malli.registry :as mr]
            [seon.schema.internal :as internal])
  (:import [datahike.db AsOfDB DB HistoricalDB SinceDB]
           [java.util.concurrent TimeoutException]
           [datalog.parser.type And BindColl BindTuple BindScalar Constant FindColl FindRel FindScalar
            FindTuple Not Or Pattern Pull RulesVar Variable]))

;;; ---------------------------------------------------------------------------
;;; Ambient custody and optional read evidence
;;; ---------------------------------------------------------------------------

;;; LOAD-CYCLE BOUNDARIES. `seon.error` and `seon.call-preparation` both
;;; require `seon.db`, so this namespace cannot require them back. One
;;; resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private error-explain-problem
  (delay (requiring-resolve 'seon.error/explain-problem)))
(defonce ^:private error-problem-sentence
  (delay (requiring-resolve 'seon.error/problem-sentence)))
(defonce ^:private error-scalar-text
  (delay (requiring-resolve 'seon.error/scalar-text)))
(defonce ^:private error-render-ai
  (delay (requiring-resolve 'seon.error/render-ai)))
(defonce ^:private error-render-html
  (delay (requiring-resolve 'seon.error/render-html)))
(defonce ^:private call-preparation-snapshot
  (delay (requiring-resolve 'seon.call-preparation/snapshot)))
(defonce ^:private call-preparation-report-snapshot
  (delay (requiring-resolve 'seon.call-preparation/report-snapshot)))
(defonce ^:private call-preparation-plan-for
  (delay (requiring-resolve 'seon.call-preparation/plan-for)))

(defn connection?
  "True for a live (unreleased) Datahike connection."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (connector/connection? value)
       (some? (:wrapped-atom value))
       (not= @(:wrapped-atom value) :released)))

(defn connection-object?
  "True for a Datahike connection, live or RELEASED.
  The shape question, as distinct from `connection?`'s liveness question:
  a connection object outlives its own liveness, and the callers whose job
  IS the released case -- `seon.cluster.boot/stop!` on a stopped instance,
  `seon.cluster.wake/unlisten!` after a store release -- hold exactly that
  value. Liveness stays where it is DECIDED: `transact!` answers a typed
  value for a released connection, and Datahike refuses at the write."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (connector/connection? value))

(defn database-value?
  "True for any Datahike database value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (db.utils/db? value))

(defn transaction-report-datom?
  "True for a native Datahike transaction-report datom.

  It has the documented
  five fields: integer entity, keyword attribute, value, integer transaction,
  and boolean added flag."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (datahike.datom/datom? value)
       (int? (:e value))
       (keyword? (:a value))
       (int? (:tx value))
       (boolean? (:added value))))

(schema/register-core-predicate! 'seon.db/connection? connection?)
(schema/register-core-predicate! 'seon.db/connection-object? connection-object?)
(schema/register-core-predicate! 'seon.db/database-value? database-value?)
(schema/register-core-predicate! 'seon.db/transaction-report-datom?
                                 transaction-report-datom?)

(defn- fresh-connection
  []
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (d/connect configuration)))

(def connection-generator
  (gen/fmap (fn [_] (fresh-connection)) (gen/return nil)))

(def database-value-generator
  (gen/fmap
   (fn [variant]
     (let [database @(fresh-connection)]
       (case variant
         :current database
         :as-of (d/as-of database (dbi/-max-tx database))
         :since (d/since database 0)
         :history (d/history database))))
   (gen/elements [:current :as-of :since :history])))

(def transaction-report-datom-generator
  (gen/fmap (fn [[entity attribute value transaction added]]
              (datahike.datom/datom entity attribute value transaction added))
            (gen/tuple (gen/choose 1 100)
                       (gen/elements [:sample/attribute])
                       (gen/elements [false 0 "" :sample/value [] {}])
                       (gen/choose 1 100)
                       (gen/elements [true false]))))

(def ^:dynamic *conn*
  "The current cluster's live branch connection, bound by its owning pass."
  nil)

(def ^:dynamic *read-database*
  "An explicitly handed immutable read basis, scoped by an evaluation.

  The connection remains separate: blob and effect owners require its live
  connection semantics. Absence preserves current reads through *conn*."
  nil)

(def ^:dynamic ^:private *receipt*
  "The current evaluation receipt lookup ref, bound with connection custody."
  nil)

(def ^:dynamic ^:private *open*
  "Transaction data opening the current evaluation's run when that run is not
  written yet, bound with connection custody: every write the evaluation makes
  carries it first, and the writer applies it once (`absent-call`), so the
  run, its evaluation rows and the receipt they name exist before any
  reference to them."
  nil)

(def ^:dynamic *read-evidence-sink*
  "An optional invocation-local atom collecting Datahike read evidence."
  nil)

(defn- error-value
  {:malli/schema [:=> [:cat :qualified-symbol :qualified-keyword :string :map] :seon.error/base]}
  [operation member message data]
  {member true
   :seon.db/refused-read-operation operation
   :seon.error/at (java.util.Date.)
   :seon.error/layer :seon.db/database-read
   :seon.error/operation operation
   :seon.error/message message
   :seon.error/data data})

(defn- diagnostic
  {:malli/schema [:=> [:cat :map] :seon.error/base]}
  [request]
  (let [operation (:seon.error/operation request)
        operation (if (symbol? operation) operation
                      (symbol (namespace operation) (name operation)))]
    (cond-> (assoc request
                    :seon.error/at (java.util.Date.)
                    :seon.error/layer (keyword "seon.db" (name (:seon.error/layer request)))
                    :seon.error/operation operation)
       (:seon.db/invalid-read request)
       (assoc :seon.db/refused-read-operation operation))))

(defn read-refusal?
  "True for this owner's declared read refusals: a `:seon.db/invalid-read-error`
  (its `:seon.db/invalid-read` member) or a missing-projection refusal (its
  `:seon.schema/expected-value` member). THE ONE TEST every reader applies to
  a value a read handed it in place of an answer."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (boolean (and (map? value)
                (or (:seon.db/invalid-read value) (:seon.schema/expected-value value)))))

(defn- refused-read
  "`value` as the refusal of the read `operation` was handed it in place of a
  database. A read refusal passes on unchanged, still naming the read that
  produced it; any other error value is stamped as refused here."
  {:malli/schema [:=> [:cat :seon.db/error-result :qualified-symbol] :seon.db/error-result]}
  [value operation]
  (if (:seon.db/invalid-read value)
    value
    (vary-meta (assoc value :seon.db/invalid-read true :seon.db/refused-read-operation operation) assoc :seon.error/declared-schema :seon.db/invalid-read-error)))

(defn- schema-refusal
  "Return the actual unavailable value and the declared shape it cannot satisfy."
  {:malli/schema
   [:=> [:cat :qualified-symbol :seon.schema/key :seon.schema/value :string :map]
    :seon.schema/validation-refusal]}
  [operation expected value message evidence]
  (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.db/acquisition operation
   {:seon.error/message message
    :seon.db/invalid-read true
    :seon.db/refused-read-operation operation
    :seon.schema/expected-value expected
    :seon.schema/refused-value value
    :seon.error/data (merge evidence {:seon.error/layer :database-read})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))

(defn- dependency-error
  "Classify one declared read refusal: an `ExceptionInfo`, the one carrier of
  Datahike's `log/raise` (`replikativ/logging.cljc:82`, data carrying its
  `:error` member, e.g. `:query/where`, `:lookup-ref/unique`), the Datalog
  parser's `raise` (`datalog/parser/impl/util.cljc:10`) and Seon's codec.
  Reads catch only that carrier; every other throwable — the stop's
  interrupt, a host failure — propagates unchanged with its whole cause."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable] :seon.db/error-result]}
  [operation error]
  (if (:seon.schema/expected-value (ex-data error))
    (vary-meta (assoc (ex-data error) :seon.db/invalid-read true
           :seon.db/refused-read-operation
           operation) assoc :seon.error/declared-schema :seon.db/invalid-read-error)
    ;; Every other dependency failure keeps its whole cause: the diagnostic
    ;; derives the class, root frame and cause chain from the Throwable.
    (error.refusal/diagnostic
     (assoc (error-value
             operation
             ::invalid-read
             (or (ex-message error) "Datahike refused the database read.")
             (cond-> {::operation operation
                      ::exception-class (.getName (class error))}
               (map? (ex-data error))
               (assoc ::dependency-data (ex-data error))))
            :seon.error/throwable error))))

(defn- connection-projection-state
  [connection]
  ;; The operator owns this table. Do not load the operator during database
  ;; bootstrap, and match the actual connection rather than a branch name
  ;; that another store could also use.
  (or (:seon.sci.eval/projection-state (meta connection))
      (when-let [instances (some-> (find-ns 'seon.operator.runtime)
                              (ns-resolve 'running-instances)
                              deref)]
    (some (fn [instance]
            (let [state (get-in instance [:seon.sci.eval/ctx env/state-carrier])]
              (when (and state
                         (identical? connection (:seon.db/connection @state)))
                state)))
          (vals @instances)))))

(defn carry-connection-projection-state!
  "Attach the owning projection state to a live connection at construction.

  Datahike exposes connection metadata from its wrapped atom. Keep only the
  state pointer there; each database acquisition captures its own snapshot."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.sci.eval/projection-state]
    :seon.db/connection]}
  [connection state]
  (alter-meta! (:wrapped-atom connection)
               assoc :seon.sci.eval/projection-state state)
  connection)

(defn carry-projection-state
  "Carry a cluster's state and immutable projection on a database value.

  Reads take their schema projection from the value itself (law 2.1), so a
  value minted anywhere else — a turn's bound read database, a fixture —
  must carry the state before reads see it. Capture its projection once;
  subsequent state changes cannot change a retained database's projection.
  Existing snapshot metadata wins. An error value is returned unchanged;
  a nil state leaves the value as it was."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value]
         [:maybe :seon.sci.eval/projection-state]]
    [:or :seon.db/database-value :seon.error/value]]}
  [database state]
  (if (and state (db.utils/db? database))
    (let [projection (or (:seon.schema/projection (meta database))
                         (:seon.schema/projection @state))]
      (cond-> (vary-meta database assoc :seon.sci.eval/projection-state state)
        projection (vary-meta assoc :seon.schema/projection projection)))
    database))

(defn- resolve-database-value
  [connection]
  (try
    ;; Resolve latest exactly once at the public-call boundary.
    (let [database (carry-projection-state
                    (d/db connection) (connection-projection-state connection))]
      ;; Acquisition captures the caller's supplied construction projection.
      ;; Reads subsequently consult only their immutable database value.
      (if-let [projection (and (nil? (:seon.schema/projection (meta database)))
                               (schema/handed-projection))]
        (vary-meta database assoc :seon.schema/projection projection)
        database))
    (catch clojure.lang.ExceptionInfo cause
      (dependency-error 'seon.db/db cause))))

(defn- missing-connection-error
  {:malli/schema [:=> [:cat :string] :seon.schema/validation-refusal]}
  [needed]
  (schema-refusal
   'seon.db/missing-connection-error :seon.db/connection nil
   (str "This read needs " needed
        ", and no cluster connection is bound on this thread. Custody is "
        "elided only inside an agent evaluation; elsewhere — a raw or "
        "virtual thread, a fixture, a REPL — pass the database value or "
        "connection explicitly, as in (db/pull db selector eid). "
        "At a development REPL, (seon.cluster.boot/connection \"default\") "
        "supplies that connection.")
   {::binding 'seon.db/*conn*
    ::needed needed}))

(defn- current-database-value
  []
  (or *read-database*
      (if (nil? *conn*)
        (missing-connection-error "a database value")
        (resolve-database-value *conn*))))

(defn- current-connection
  []
  (if (nil? *conn*)
    (missing-connection-error "a connection")
    *conn*))

(defn- connection-id
  [connection]
  (datahike.store/connection-id (:config @connection)))

(defn branch-connection
  "This process's open connection to `branch` of `database`'s own store, or nil
  when none is open. Borrowed from Datahike's registry under the same key every
  connection has (`datahike/store.cljc:44-55`, `connections.cljc:5-9`); the
  caller never releases it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch]
                  [:maybe :seon.db/connection]]}
  [database branch]
  (connections/active-connection
   (datahike.store/connection-id (assoc (dbi/-config database) :branch branch))))

(defn branch-database
  "The head database value of `branch` in `database`'s store, else nil.
  A read: it opens no connection and owns nothing to release. The head is
  loaded as its commit through `database` (`datahike.versioning/branch-as-db`
  then `commit-as-db`, `versioning.cljc:503`, `:473`), so it carries a
  committed identity under `database`'s connection generation and retained
  render calls on it are reused; a bare `branch-as-db` value is detached and
  would re-render every face.

  # Costs
  Unarmed, about 7 ms for both loads on the default store (measured
  2026-09-27); its projection derives once per declaration content."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch]
                  [:maybe :seon.db/database-value]]}
  [database branch]
  (when-let [head (d/branch-as-db database branch)]
    (d/commit-as-db database (d/commit-id head) {:secondary-indices? false})))

(defn connection-identity
  "Plain-data identity of a Datahike connection, or the typed unknown.
  A RELEASED connection has no derivable identity: its wrapped state is
  `:released`, so there is no `:config` to identify and the dependency's
  `connection-id` has nothing to dispatch on. That is an unavailable
  observation, which is a typed value here — never a throw out of an
  identity projection, and never a silent nil. Liveness is read from the
  wrapped state by `connection?`, because dereferencing a released
  connection throws in Datahike's `deref-conn`."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value]]
    [:or :seon.db/connection-identity :seon.error/value]]}
  [connection]
  (cond
    (not (connection-object? connection)) connection

    (not (connection? connection))
    (error-value
     'seon.db/connection-identity
     ::released-connection
     "This Datahike connection is released; a released connection has no identity."
     {::connection (str connection)})

    :else {:datahike/connection-id (connection-id connection)}))

(defn call-with-custody
  "Call `f` with EXACTLY the cluster custody its caller handed over.

  The custody is a VALUE on the request: `:seon.db/connection` present means
  `f` runs as that cluster's own work and the elided `seon.db` arities reach
  it; the key ABSENT means no custody at all, and those arities refuse and
  name what they needed. Nothing here re-reads a dynamic var to decide — the
  caller that knows whose work this is says so, and this binds that answer.

  A read basis is never inherited: `*read-database*` is scoped by ONE
  evaluation, so a scope that switches custody starts from the connection.
  An evaluation's receipt travels only with its own connection's custody: its
  lookup ref names an evaluation on that branch alone, so a switch to another
  connection stamps no receipt.

  `seon.test.runner/run-var!` is the caller this exists for. An agent running
  its own tests inside its evaluation is doing that cluster's work; a host
  REPL running the same test Var is not, and hands nothing."
  {:malli/schema [:=> [:cat :seon.db/custody-request [:fn clojure.core/ifn?]]
                  [:or :seon.schema/validation-refusal
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A scope wrapper returns its body's arbitrary result unchanged."
                         :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [request f]
  (binding [*conn* (:seon.db/connection request)
            *read-database* nil
            *receipt* (when (identical? (:seon.db/connection request) *conn*) *receipt*)
            *open* (when (identical? (:seon.db/connection request) *conn*) *open*)]
    (f)))

(defn call-without-custody
  "Call `f` with NO ambient cluster custody bound on this thread.

  The elided `seon.db` arities exist so an AGENT's evaluation writes its own
  cluster without naming it. Anything else running on a thread that inherited
  those bindings — an in-process test body, a fixture helper that elides its
  connection — silently writes the LIVE cluster instead of failing. On
  2026-09-17 that put a test's synthetic schema rows into `default`'s datoms
  and every later write on the cluster was refused, which filled the store.

  Removing the bindings turns that silence into the ordinary loud refusal
  `seon.db` already has: a read or write with no connection names what it
  needed. Absence is the honest answer here, never a fallback to whichever
  cluster happens to be in scope.

  This is `call-with-custody` with nothing handed: ONE scope, and absence is
  one of its two ordinary answers."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]]
                  [:or :seon.schema/validation-refusal
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A scope wrapper returns its body's arbitrary result unchanged."
                         :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [f]
  (call-with-custody {} f))

(defn database-value-identity
  "Plain-data identity of a COMMITTED immutable Datahike database value.

  Only a committed value has a commit id. An as-of, since, history, or
  speculative value does not, and this returns a flat error value for it
  rather than a map with a nil commit id — use `basis-t` when the question is
  only the basis transaction, which every value shape answers."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
    [:or :seon.db/database-value-identity :seon.error/value]]}
  [database]
  (if-not (db.utils/db? database)
    database
    (let [configuration (dbi/-config database)
          commit-id (d/commit-id database)]
      (if (uuid? commit-id)
        {:db-name (:branch configuration)
         :t (dbi/-max-tx database)
         :datahike/commit-id commit-id}
        (schema-refusal
         'seon.db/database-value-identity :seon.db/database-value-identity
         {:db-name (:branch configuration) :t (dbi/-max-tx database)}
         (str "This database value has no commit id, so it has no committed "
              "identity. Read its basis transaction with seon.db/basis-t.")
         {:db-name (:branch configuration)
          :t (dbi/-max-tx database)})))))

(defn basis-t
  "The database value's basis transaction, through Datahike's interface.

  Takes any database value shape — current, as-of, since, or history — and
  returns its basis transaction as a long, or the error value unchanged. Use
  this instead of reading :max-tx as a map key: an as-of/history value
  carries no top-level :max-tx entry."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
    [:or :int :seon.error/value]]}
  [database]
  (if-not (db.utils/db? database)
    database
    (long (dbi/-max-tx database))))

(defn- connection-branch
  [connection]
  (:branch (:config @connection)))

(declare write-observation)

(defn- foreign-connection-error
  "Refuse a write naming a branch outside the writing custody, naming both.

  DECIDED WHERE THE WRITE IS ADMITTED, never from a pre-read: the two
  identities are read from the two connections this call actually holds,
  immediately before `transact-call` hands one to Datahike, so nothing can
  change between the decision and the write it governs."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.db/connection :seon.store/transaction]
    [:or :nil :seon.db.write/validation-refusal]]}
  [database connection transaction]
  (when (some? *conn*)
    (let [ambient-connection-id (connection-id *conn*)
          explicit-connection-id (connection-id connection)]
      (when-not (= ambient-connection-id explicit-connection-id)
        (let [ambient-branch (connection-branch *conn*)
              explicit-branch (connection-branch connection)]
          (write-observation
           database transaction
           {:seon.error/at (java.util.Date.)
            :seon.error/layer :seon.db/database-write
            :seon.error/operation 'seon.db/transact!
            :seon.error/message
            (str "This write names branch " (pr-str explicit-branch)
                ", which is not the writing cluster's branch "
                (pr-str ambient-branch)
                ". A cluster writes only its own branch: send the work to "
                "the cluster that owns "
                (pr-str explicit-branch)
                " instead of transacting into its connection.")
            :seon.error/data
           {::ambient-connection-id ambient-connection-id
            ::explicit-connection-id explicit-connection-id
            ::ambient-branch ambient-branch
            ::explicit-branch explicit-branch}}))))))

(def ^:private read-index-pattern-bound
  "The most index patterns one read source keeps: the `:max` of
  `:seon.db/read-index-patterns` (seon.db.edn), which the writer enforces."
  128)

(defn- append-read-evidence!
  "Collect one captured read, keeping its index patterns only within the bound.

  A plan references its read (sources, attributes, basis) and never copies
  what the read returned. Patterns bound per pulled entity grow with the
  result: root's opening `(seon.error/for-agent (seon.db/db) \"root\")` kept
  30,729, stored as 4.5 M characters and decoded on every turn (default,
  2026-09-27). Past the bound, patterns bound to one entity collapse to that
  entity; a read still past it keeps its request and the digest of its answer,
  which `read-evidence-current?` replays, so the plan stays exactly as precise
  as its read, never \"any write of these attributes\"."
  {:malli/schema [:=> [:cat :map] :nil]}
  [evidence]
  (when *read-evidence-sink*
    (let [patterns (:seon.db/read-index-patterns evidence)
          ;; Patterns bound to one pulled entity collapse to that entity: the
          ;; root pull's 1,280 patterns name one entity (r8, root-pull-test).
          collapsed (when (< read-index-pattern-bound (count patterns))
                      (into [] (comp (map #(if-let [entity (:seon.db/pattern-entity %)]
                                             {:seon.db/pattern-entity entity}
                                             %))
                                     (distinct))
                            patterns))]
      (swap! *read-evidence-sink* conj
             (cond
               (nil? collapsed) evidence
               (<= (count collapsed) read-index-pattern-bound)
               (assoc evidence :seon.db/read-index-patterns collapsed)
               ;; Still past the bound: the plan keeps its read instead (the
               ;; request and the digest of its answer), which currency replays.
               :else (-> evidence
                         (dissoc :seon.db/read-index-patterns)
                         (assoc ::replay-past-bound true))))))
  nil)

(defn- append-database-evidence!
  [database plan]
  (when (db.utils/db? database)
    (append-read-evidence!
     {:seon.db/db database
      :seon.db/source-argument-position 0
      :datahike.read/dependency-plan plan})))

(defn- bounded-read-request?
  [request]
  (let [bounds (case (:seon.db/read-operation request)
                 :q (:seon.db/query-request request)
                 :pull (first (:seon.db/pull-arguments request))
                 :pull-many (first (:seon.db/pull-arguments request))
                 :index-page (:seon.db/index-page-options request)
                 nil)]
    (and (map? bounds)
         (pos-int? (:max-result-weight bounds))
         (or (= :index-page (:seon.db/read-operation request))
             (and (pos-int? (:max-work bounds))
                  (pos-int? (:max-results bounds)))))))

(defn- stable-value
  [result]
  (letfn [(stable [value]
            (cond
              ;; Every Datahike value is a record (`datahike/db.cljc:307-685`)
              ;; and a connection is none of the shapes below, so both fall
              ;; out without `db?`: its three `satisfies?` misses per node
              ;; were 98% of this walk (677 of 689 ms over one form's 468
              ;; namespace-entity pulls, lane-fix-cost-db-2026-09-25).
              (record? value)
              [false nil]

              (map? value)
              (reduce-kv
               (fn [[replayable? result] map-key child]
                 (let [[key-replayable? stable-key] (stable map-key)
                       [child-replayable? stable-child] (stable child)]
                   [(and replayable? key-replayable? child-replayable?)
                    (assoc result stable-key stable-child)]))
               [true (empty value)]
               value)

              (vector? value)
              (reduce (fn [[replayable? result] child]
                        (let [[child-replayable? stable-child] (stable child)]
                          [(and replayable? child-replayable?)
                           (conj result stable-child)]))
                      [true []]
                      value)

              (set? value)
              (reduce (fn [[replayable? result] child]
                        (let [[child-replayable? stable-child] (stable child)]
                          [(and replayable? child-replayable?)
                           (conj result stable-child)]))
                      [true #{}]
                      value)

              (and (sequential? value) (counted? value))
              (let [children (map stable value)]
                [(every? first children) (mapv second children)])

              (or (nil? value) (boolean? value) (number? value)
                  (string? value) (keyword? value) (symbol? value)
                  (char? value) (inst? value) (uuid? value))
              [true value]

              :else [false nil]))]
    (stable result)))

(defn- read-result-digest
  "The digest of one `stable-value` answer, computed once per read by its caller."
  [[stable? value]]
  (when stable?
    (try
      (schema/sha-256
       [(.getBytes ^String (schema/canonical-data-string value) "UTF-8")])
      (catch clojure.lang.ExceptionInfo cause
        (if (= :seon.schema/noncanonical-projection-data
               (:seon.schema/error (ex-data cause)))
          nil
          (throw cause))))))

(declare pull-index-patterns)

(defn- query-index-patterns
  "Retain pattern dependencies through conjunction, negation and disjunction.
  Nested clauses bind against their enclosing positive patterns, including
  entities negation excludes. Datahike resolves those bindings; this walker
  never implements joins. Unsupported clauses keep general evidence."
  {:malli/schema [:=> [:cat :map :int] [:maybe [:vector :map]]]}
  [request source-position]
  (let [parsed (query/memoized-parse-query (:query request))
        input-context (query/resolve-ins {:rels [] :consts {} :sources {} :rules {}}
                                        (:qin parsed) (:args request))
        input-variables (vec (sort (into (set (keys (:consts input-context)))
                                         (mapcat (comp keys :attrs))
                                         (:rels input-context))))
        input-bindings (mapv #(zipmap input-variables %)
                             (query/collect input-context input-variables))
        source (get-in parsed [:qin source-position :variable :symbol])
        database (nth (:args request) source-position)
        value-of (fn [bound argument]
                   (cond
                     (instance? Constant argument) [:bound (:value argument)]
                     (instance? Variable argument) (find bound (:symbol argument))))
        source-of (fn [clause inherited]
                    (or (get-in clause [:source :symbol]) inherited))
        resolve-bindings (fn [clauses bound]
              (let [variables (into [] (comp (map :symbol) (distinct))
                                    (parser.type/collect-vars clauses))
                    extra (apply dissoc bound
                                 (map :symbol (parser.type/collect-vars (:qin parsed))))]
                (if (empty? variables)
                  [bound]
                  (mapv #(merge bound (zipmap variables %))
                        (d/q (assoc (dissoc request :limit :offset :order-by) :query
                                    {:find variables
                                     :in (into (mapv parser.impl/get-source (:qin parsed))
                                               (keys extra))
                                     :where (mapv parser.impl/get-source clauses)}
                                    :args (into (:args request) (vals extra))))))))
        ;; The original collection arguments already select ALL matching rows.
        ;; Resolve them once, then walk each distinct selector once, not once
        ;; per input row followed by a quadratic distinct.
        pull-groups
        (delay
          (let [pulls (filterv #(and (instance? Pull %)
                                    (= source (source-of % '$)))
                              (parser/find-elements (:qfind parsed)))
                rows (when (seq pulls) (resolve-bindings (:qwhere parsed) {}))]
            (for [element pulls
                  [selector bindings] (group-by #(second (value-of % (:pattern element)))
                                                input-bindings)
                  :let [variable (get-in element [:variable :symbol])
                        references (if (some #(contains? % variable) rows)
                                     (mapv #(get % variable) rows)
                                     (when (seq rows) (mapv #(get % variable) bindings)))]]
              (when (sequential? selector)
                (pull-index-patterns database [selector (vec (distinct references))]
                                     :pull-many (pull-api/compile-pull-plan database selector))))))]
    (let [groups
          (mapv
           (fn [bindings]
            (letfn [(supported? [clause]
              (or (instance? Pattern clause)
                  (and (or (instance? Not clause) (instance? Or clause)
                           (instance? And clause))
                       (every? supported? (:clauses clause)))))
            (pattern [clause bound inherited]
              (when (= source (source-of clause inherited))
                (let [[e a v] (:pattern clause)
                      entity (value-of bound e)
                      attribute (second (value-of bound a))
                      value (value-of bound v)
                      resolved-entity (when entity (db.utils/entid database (second entity)))
                      resolved-value (when value
                                       (if (and attribute (db.utils/ref? database attribute))
                                         (db.utils/entid database (second value))
                                         (second value)))]
                  [(cond-> {}
                    resolved-entity (assoc :seon.db/pattern-entity resolved-entity)
                    (keyword? attribute) (assoc :seon.db/pattern-attribute attribute)
                    (some? resolved-value) (assoc :seon.db/pattern-value resolved-value))])))
            (scope-patterns [clauses bound inherited]
              (let [positive (filterv #(instance? Pattern %) clauses)
                    candidates
                    (delay
                      (resolve-bindings
                       (mapv (fn [clause]
                               (let [raw (parser.impl/get-source clause)]
                                 (parser.impl/with-source
                                  clause
                                  (into [(source-of clause inherited)]
                                        (if (get-in clause [:source :symbol])
                                          (rest raw) raw)))))
                             positive)
                       bound))]
                (mapcat
                 (fn [clause]
                   (if (instance? Pattern clause)
                     (pattern clause bound inherited)
                     (mapcat
                      (fn [candidate]
                        (let [joined (cond
                                       (instance? Not clause) (:vars clause)
                                       (instance? Or clause)
                                       (concat (get-in clause [:rule-vars :required])
                                               (get-in clause [:rule-vars :free]))
                                       :else (map parser.type/->Variable (keys candidate)))
                              scoped (merge bindings
                                            (select-keys candidate (map :symbol joined)))
                              inherited (source-of clause inherited)]
                          (if (instance? Or clause)
                            (mapcat #(scope-patterns [%] scoped inherited) (:clauses clause))
                            (scope-patterns (:clauses clause) scoped inherited))))
                      @candidates)))
                 clauses)))]
      (when (every? supported? (:qwhere parsed))
        (into [] (distinct)
              (scope-patterns (:qwhere parsed) bindings '$)))))
           input-bindings)]
      (when (and (every? some? groups) (not-any? nil? @pull-groups))
        (into [] (comp cat (distinct)) (concat groups @pull-groups))))))

(defn- pull-index-patterns
  "Bind finite explicit pull dependencies to their selected entities.
  General selectors retain their existing semantic read evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.schema/value] :keyword :map]
                  [:maybe [:vector :map]]]}
  [database arguments operation plan]
  (let [options (when (map? (first arguments)) (first arguments))
        references (if (= :pull operation)
                     [(if options (:eid options) (second arguments))]
                     (if options (:eids options) (second arguments)))]
    (letfn [(patterns [spec reference]
              (let [eid (db.utils/entid database reference)
                    identity-pattern
                    (cond
                      (keyword? reference)
                      {:seon.db/pattern-attribute :db/ident :seon.db/pattern-value reference}
                      (sequential? reference)
                      {:seon.db/pattern-attribute (first reference)
                       :seon.db/pattern-value (second reference)})]
                (when-not (:wildcard? spec)
                  (reduce-kv
                   (fn [result display options]
                     (let [attribute (:attr options)
                           nested (:subpattern options)
                           forward? (= display attribute)]
                       (if (or (not (keyword? attribute))
                               (:recursion options)
                               (and (not= :db/id attribute) (not nested)
                                    (db.utils/component? database attribute)))
                         (reduced nil)
                         (if-not eid
                           result
                           (let [pattern (cond
                                           (= :db/id attribute) {:seon.db/pattern-entity eid}
                                           forward? {:seon.db/pattern-entity eid
                                                     :seon.db/pattern-attribute attribute}
                                           :else {:seon.db/pattern-attribute attribute
                                                  :seon.db/pattern-value eid})
                                 children
                                 (when nested
                                   (mapv #(patterns nested (if forward? (:v %) (:e %)))
                                         (if forward?
                                           (d/datoms database :eavt eid attribute)
                                           (d/datoms database :avet attribute eid))))]
                             (if (some nil? children)
                               (reduced nil)
                               (into (conj result pattern) (mapcat identity) children)))))))
                   (cond-> [] identity-pattern (conj identity-pattern))
                   (:attrs spec)))))]
      (let [groups (mapv #(patterns (:spec plan) %) references)]
        (when-not (some nil? groups)
          (into [] (distinct) (mapcat identity groups)))))))

(defn- append-query-evidence!
  [request response result]
  (when *read-evidence-sink*
    (let [arguments (:args request)
          database-positions (into []
                                   (keep-indexed
                                    (fn [position argument]
                                      (when (db.utils/db? argument) position)))
                                   arguments)
          replayable? (= 1 (count database-positions))
          replay-request
          (when replayable?
            (update request :args
                    (fn [values]
                      (mapv (fn [value]
                              (if (db.utils/db? value) ::database value))
                            values))))
          plan (:datahike.read/dependency-plan response)
          positions
          (if (= :all plan)
            (keep-indexed
             (fn [position argument]
               (when (db.utils/db? argument) position))
             arguments)
            (map :datahike.query.source/argument-position
                 (:datahike.query.dependency/sources plan)))]
      (doseq [position (distinct positions)
              :let [database (nth arguments position nil)]
              :when (db.utils/db? database)
              :let [patterns (when (instance? DB database)
                               (query-index-patterns request position))]]
        (append-read-evidence!
         (cond->
          {:seon.db/db database
           :seon.db/source-argument-position position
           :datahike.read/dependency-plan plan}
           patterns (assoc :seon.db/read-index-patterns patterns)
           replayable?
           (assoc :seon.db/read-request
                  {:seon.db/read-operation :q
                   :seon.db/query-request replay-request}
                  :seon.db/read-result result))))))
  nil)

(defn- append-pull-evidence!
  "Record one pull: the physical plan's dependencies and index patterns, and
   the replay request with `logical-selector` (nil: the plan's own selector),
   which replay lowers again."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.schema/value] :keyword :map
                       :seon.schema/value [:or :nil :seon.db/pull-selector]]
                  :seon.schema/value]}
  [database arguments operation-key response result logical-selector]
  (when *read-evidence-sink*
   (let [selector
        (or logical-selector
            (pull-api/pull-plan-selector
             (:datahike.pull/plan response)))
        replay-arguments
        (if (map? (first arguments))
          (assoc arguments 0 (-> (first arguments)
                                 (assoc :selector selector)
                                 (dissoc :datahike.pull/plan)))
          (assoc arguments 0 selector))
        patterns (when (instance? DB database)
                   (pull-index-patterns database arguments operation-key
                                        (:datahike.pull/plan response)))]
    (append-read-evidence!
     (cond-> {:seon.db/db database
      :seon.db/source-argument-position 0
      :datahike.read/dependency-plan (:datahike.read/dependency-plan response)
      :seon.db/read-request {:seon.db/read-operation operation-key
                             :seon.db/pull-arguments replay-arguments}
      :seon.db/read-result result}
       patterns (assoc :seon.db/read-index-patterns patterns))))))

;;; The committed identity a retained read's revision is keyed on. Datahike
;;; derives its OWN query-cache key exactly this way
;;; (`reference-code/datahike/src/datahike/query.cljc:2658-2671`):
;;;
;;; - a committed raw `datahike.db.DB` is identified by its `:cache-context`;
;;; - an `AsOfDB` over a committed origin at a strictly-past integer time point
;;;   is identified by the ORIGIN's context plus that fixed point, reached
;;;   through `dbi/-origin` and `dbi/-time-point` (the `IHistory` interface,
;;;   `reference-code/datahike/src/datahike/db/interface.cljc:118-120`);
;;; - every other shape — since, history, filtered, speculative, detached —
;;;   has no committed identity at all, which is precisely what datahike's
;;;   `committed-value-identity` reports by returning nil.
;;;
;;; Reading `:cache-context` off a value that is not a raw `DB` was the class
;;; defect this replaces, and it failed SILENTLY: `select-keys` over an
;;; `AsOfDB` returns `{}` without a word, so the two required identity keys
;;; vanished and the first complaint arrived frames later at `read-evidence`'s
;;; output arm. The run loop renders each turn at its run's opening basis — an
;;; as-of value — so on 2026-08-08 every agent prompt on the default cluster
;;; collapsed to one 509-character contract error, and nine paid provider calls
;;; answered it instead of the waiting human message.
(defn- revision-source
  [database]
  (if (instance? AsOfDB database)
    (let [origin (dbi/-origin database)
          time-point (dbi/-time-point database)]
      ;; The upper bound is `<=`, where datahike's own `db-cache-key` uses
      ;; `<`. An as-of value is a fixed point at ANY committed time point —
      ;; its content is the datoms with tx <= that point and the origin
      ;; advancing never changes them — and the revision already carries the
      ;; origin's commit id, so nothing is claimed fresh that is not.
      ;; Datahike's stricter bound is its own cache-admission policy; taking
      ;; it literally cost the 2026-08-08 re-drive its context a second time,
      ;; because a run renders at the instant it opens, when its opening
      ;; transaction IS the origin's max-tx and no as-of is yet strictly past.
      (when (and (some? (datahike.db/committed-value-identity origin))
                 (integer? time-point)
                 (<= const/tx0 (long time-point))
                 (<= (long time-point) (long (dbi/-max-tx origin))))
        {::context (:cache-context origin)
         ::fixed-point (long time-point)}))
    (when (some? (datahike.db/committed-value-identity database))
      {::context (:cache-context database)})))

(defn- basis-instant
  "The :db/txInstant of `database`'s basis transaction, or nil for a value
  that is not a plain database. Datahike asserts it once per transaction and
  never retracts it, so the current index answers it (one EAVT seek)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :inst]]}
  [database]
  (when (instance? DB database)
    (:v (first (dbi/search database [(basis-t database) :db/txInstant])))))

(defn- dependency-revision
  [database plan source-position]
  (let [{context ::context fixed-point ::fixed-point} (revision-source database)
        attributes (d/dependency-plan-attributes plan source-position)]
    (if (nil? context)
      ;; No committed identity, so no revision can ever prove a retained read
      ;; current. The revision says so in the open, and
      ;; `read-evidence-current?` replays the read rather than comparing.
      {:datahike.read/attributes attributes
       :datahike.read/cache-eligible? false}
      (let [source-identity
            (cond-> (select-keys context [:datahike.cache/connection-id
                                          :datahike.cache/generation])
              fixed-point (assoc :datahike.read/time-point fixed-point))]
        (if (= :all attributes)
          (assoc source-identity
                 :datahike.read/attributes :all
                 :datahike.read/revision
                 (:datahike.cache/commit-id context))
          (cond->
           (assoc source-identity
                  :datahike.read/attributes attributes
                  :datahike.cache/attribute-revisions
                  (select-keys (:datahike.cache/attribute-revisions context)
                               attributes))
            (:datahike.cache/conservative-revision context)
            (assoc :datahike.cache/conservative-revision
                   (:datahike.cache/conservative-revision context))))))))

(defn read-evidence
  "Retain dependency revisions without retaining database values.

  Process-local caches may explicitly retain the read request and its stable
  result for semantic replay. The default, the evidence an evaluation stores
  as facts, holds neither: a request is code (a `#\"…\"` literal is a host
  Pattern, not EDN), and the evaluation that owns this evidence already
  references its source, whose preview `seon.turn/system-turn` compares by
  shown value."
  {:malli/schema
   [:function
    [:=> [:cat [:vector :seon.db/captured-read]]
     [:vector :seon.db/read-evidence]]
    [:=> [:cat [:vector :seon.db/captured-read]
          :seon.db/read-evidence-options]
     [:vector :seon.db/read-evidence]]]}
  ([captured] (read-evidence captured {}))
  ([captured options]
   (mapv (fn [{database :seon.db/db
               source-position :seon.db/source-argument-position
               plan :datahike.read/dependency-plan
               :as entry}]
           (let [past-bound? (::replay-past-bound entry)
                 retain? (:seon.db/retain-read-results? options)
                 request (when (or retain? past-bound?) (:seon.db/read-request entry))
                 stable (when (and request (find entry :seon.db/read-result))
                          (stable-value (:seon.db/read-result entry)))
                 [replayable? stable-result]
                 (when (bounded-read-request? request)
                   stable)
                 result-digest (when stable (read-result-digest stable))]
           (cond->
            {:seon.db/source-argument-position source-position
             :datahike.read/dependency-plan
             (if-let [patterns (:seon.db/read-index-patterns entry)]
               (update plan :datahike.query.dependency/sources
                       (fn [sources]
                         (mapv (fn [source]
                                 (cond-> source
                                   (= source-position
                                      (:datahike.query.source/argument-position source))
                                   (-> (assoc :seon.db/read-index-patterns patterns
                                              :seon.db/read-basis-t (basis-t database))
                                       (as-> source (if-let [instant (basis-instant database)]
                                                      (assoc source :seon.db/read-basis-instant instant)
                                                      source)))))
                               sources)))
               plan)
             :datahike.read/revision
             (dependency-revision database plan source-position)}
             request
             (assoc :seon.db/read-request request)

             result-digest
             (assoc :seon.db/read-result-digest result-digest)

             ;; A read kept past the pattern bound is replayed and compared by
             ;; its digest; its result is never copied into the plan.
             (and replayable? (not past-bound?))
             (assoc :seon.db/read-result stable-result))))
         captured)))

(declare decode-index-page decode-query-result decode-pull-result read-declarations
         with-declarations encode-query-request query-value-type-error)

(defn- pull-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-plan-with-evidence
         arguments))

(defn- pull-many-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-many-plan-with-evidence
         arguments))

(defn- pull-logical
  "One pull through `operation` (a plan-with-evidence pull or pull-many),
   lowered and decoded when it reads vector cells (`schema.datahike/vector-pull`,
   `decode-pull-result`, then `schema.datahike/decode-vectors`). Answers
   `[physical-arguments response result]`, or a refusal: a vector is whole only
   in a complete database value, never in a history or since view. Used by
   `pull-call` and `replay-read`, so a replayed pull reads alike."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.schema/projection :seon.instrument/callable
                       [:vector :seon.schema/value] :keyword]
                  [:or [:tuple [:vector :seon.schema/value] :map :seon.schema/value] :seon.db/error-result]]}
  [database declarations projection operation arguments result-key]
  (let [selector (if (map? (first arguments)) (:selector (first arguments)) (first arguments))
        [physical spec] (schema.datahike/vector-pull projection selector)
        arguments (cond (nil? physical) arguments
                        (map? (first arguments)) (assoc-in arguments [0 :selector] physical)
                        :else (assoc arguments 0 physical))]
    (if (and physical
             (or (instance? HistoricalDB database) (instance? SinceDB database))
             (some #(or (= '* %) (and (map? %) (contains? % :seon.db.vector/cells))) physical))
      (vary-meta
       (diagnostic
        {:seon.error/message (str "A vector is whole only in a complete database value; this pull reads "
                                  "vectors over a history or since view. Pull from (as-of db t); query "
                                  "membership changes with Datalog over history.")
         :seon.db/vector-temporal-view true
         :seon.db/invalid-read true
         :seon.error/layer :database-read
         :seon.error/operation 'seon.db/pull
         :seon.error/expected :seon.db/database-value
         :seon.error/offending selector
         :seon.error/data {:seon.db/pull-selector selector}})
       assoc :seon.error/declared-schema :seon.db/invalid-read-error)
      (let [response (apply operation database arguments)
            result (decode-pull-result declarations (:datahike.pull/plan response) result-key
                                       (get response result-key))]
        [arguments response
         (cond (nil? spec) result
               (= :datahike.pull-many/result result-key)
               (mapv #(schema.datahike/decode-vectors projection spec %) result)
               :else (schema.datahike/decode-vectors projection spec result))]))))

(declare vector-query-call)

(defn- replay-read
  "Replay one recorded read against `database`.

   The declarations are refused once, before any decoding, so a value that
   could not be decoded is never returned as though it had been (critical
   finding #18). The operation itself is a declared enum and an unknown
   member is a typed refusal naming the attribute, never `case`'s
   `IllegalArgumentException` into the since-diff that runs before every
   agent turn (critical finding #19)."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.db/read-request]
    [:or :seon.schema/value :seon.db/error-result]]}
  [database request]
  (with-declarations database 'seon.db/replay-read
    (fn [declarations]
      (case (:seon.db/read-operation request)
        :q
        (let [query-request
              (update (:seon.db/query-request request) :args
                      (fn [arguments]
                        (mapv #(if (= ::database %) database %) arguments)))
              parsed-query (query/memoized-parse-query (:query query-request))]
          ;; Evidence recorded before reads were type-checked can still hold
          ;; a mistyped value; it refuses here as the read now would. A query
          ;; recorded before a schema change can name an attribute since
          ;; retired (a pull selector derived from the old schema): Datahike
          ;; raises it, and the replay classifies it as `q` does, a value, so
          ;; the read is not current instead of throwing into the turn.
          (or (query-value-type-error declarations query-request parsed-query)
              (try
                (let [answer (vector-query-call declarations query-request
                                                (encode-query-request declarations query-request parsed-query)
                                                parsed-query)]
                  (if (vector? answer) (second answer) answer))
                (catch clojure.lang.ExceptionInfo cause
                  (dependency-error 'seon.db/replay-read cause)))))

        (:pull :pull-many)
        (let [many? (= :pull-many (:seon.db/read-operation request))
              answer (pull-logical database declarations @(::read-projection declarations)
                                   (if many? pull-many-plan-with-evidence pull-plan-with-evidence)
                                   (vec (:seon.db/pull-arguments request))
                                   (if many? :datahike.pull-many/result :datahike.pull/result))]
          (if (vector? answer) (nth answer 2) answer))

        :index-page
        (decode-index-page declarations
                           database
                           (d/index-page database
                                         (:seon.db/index-page-options request)))

        (vary-meta (diagnostic
         {:seon.error/message (str "seon.db/replay-read cannot replay the read operation "
               (pr-str (:seon.db/read-operation request)) ".")
          :seon.db.read/unknown-read-operation (:seon.db/read-operation request)
          :seon.db/invalid-read true
          :seon.error/layer :database-read
          :seon.error/operation 'seon.db/replay-read
          :seon.error/member :seon.db/read-operation
          :seon.error/expected :seon.db/read-operation
          :seon.error/offending (:seon.db/read-operation request)
          :seon.error/data {:seon.db/read-request request}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error)))))

(defn- index-pattern-change
  "The first changed datom matching a retained read pattern, found by the
  query's own matcher (`datahike.db.interface/search`, db/interface.cljc:77;
  index choice in db/search.cljc:186). `d/datoms` refuses an attribute the
  schema does not install (db/utils.cljc:189) where the query that retained
  the pattern matched nothing, so a witness through it could throw on
  evidence its read settled."
  [changes {entity :seon.db/pattern-entity
            attribute :seon.db/pattern-attribute
            value :seon.db/pattern-value}]
  (first (dbi/search changes [entity attribute value])))


(defn- changed-read-attributes
  "The read attributes whose Datahike revision differs between `revision`
  (retained) and `current`, or nil when revisions cannot narrow the check: a
  plan reading every attribute, a moved conservative revision, or a current
  value with no committed revision."
  {:malli/schema [:=> [:cat :map :map] [:or :nil [:set :keyword]]]}
  [revision current]
  (let [attributes (:datahike.read/attributes revision)]
    (when (and (set? attributes)
               (= attributes (:datahike.read/attributes current))
               (not (false? (:datahike.read/cache-eligible? current)))
               (= (:datahike.cache/conservative-revision revision)
                  (:datahike.cache/conservative-revision current)))
      (let [before (:datahike.cache/attribute-revisions revision)
            after (:datahike.cache/attribute-revisions current)]
        (into #{} (remove #(= (get before %) (get after %))) attributes)))))

(defn- index-evidence-current
  "Whether any datom changed since the read's basis matches one of its bound
  patterns, by the query's own matcher: the one answer to whether a retained
  read still holds. The captured patterns are the read's whole dependency (an attribute
  of the plan with no pattern had no bound value to read, e.g. the steps of a
  plan with none), so a write elsewhere to one of the plan's attributes leaves
  the read current. Return no decision when the patterns cannot answer.

  - Lineage: the retained revision's connection (store and branch) must be
    this value's. Within its generation the history is the read's own; a
    later connection (a fresh JVM) must hold the read's basis transaction
    with the retained `:seon.db/read-basis-instant`, which a branch recreated
    under the same name does not.
  - Within one generation, only patterns of attributes whose revision moved
    are checked (`changed-read-attributes`); across generations revisions are
    not comparable (datahike/query.cljc:3008-3012) and every pattern is.
  - A checked pattern must be witnessable in the since view: its attribute
    keeps history, or is `:db/txInstant` (asserted once per transaction,
    never retracted). An entity-only pattern (a pulled entity collapsed past
    the bound, `append-read-evidence!`) reads the plan's every attribute.

  A speculative value keeps its basis's connection and generation
  (`datahike.db/speculative-cache-context`, datahike db.cljc:444), so a read
  retained on that connection is checked against the speculative value's own
  history, which holds its uncommitted datoms above the read's basis. That
  answers whether the read still holds on this value; it never makes the value
  committed evidence, because `dependency-revision` gives it no revision."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :map :map] [:or :nil :boolean]]}
  [database source revision current]
  (let [patterns (:seon.db/read-index-patterns source)
        basis (:seon.db/read-basis-t source)
        context (:cache-context database)
        installed (dbi/-schema database)
        history? (fn [attribute]
                   (or (= :db/txInstant attribute)
                       (not (:db/noHistory (get installed attribute)))))
        read-attributes (:datahike.query.source/attributes source)
        same-connection? (= (:datahike.cache/connection-id revision)
                            (:datahike.cache/connection-id context))
        same-generation? (and same-connection?
                              (= (:datahike.cache/generation revision)
                                 (:datahike.cache/generation context)))
        same-lineage? (and same-connection?
                           (or same-generation?
                               (when-let [instant (:seon.db/read-basis-instant source)]
                                 (= instant (:v (first (dbi/search database [basis :db/txInstant])))))))
        changed (when same-generation? (changed-read-attributes revision current))
        entity-only? #(and (:seon.db/pattern-entity %) (nil? (:seon.db/pattern-attribute %)))
        checked (if changed
                  (filterv #(or (entity-only? %) (contains? changed (:seon.db/pattern-attribute %))) patterns)
                  patterns)
        witnessable? (fn [pattern]
                       (if-let [attribute (:seon.db/pattern-attribute pattern)]
                         (history? attribute)
                         (and (set? read-attributes) (every? history? read-attributes))))]
    (when (and patterns basis
               (instance? DB database)
               (every? witnessable? checked)
               same-lineage?)
      (or (empty? checked)
          (let [changes (d/since (d/history database) basis)]
            (not-any? #(index-pattern-change changes %) checked))))))

(defn read-evidence-current?
  "True when `database` still satisfies every retained dependency revision.

  The database is a declared :seon.db/database-value, so an agent's call that
  omits it has it supplied by schema name, as `q` and `pull` do."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.db/read-evidence]]
                  [:or :boolean :seon.db/error-result]]}
  [database retained]
  (every?
     (fn [{source-position :seon.db/source-argument-position
           plan :datahike.read/dependency-plan
           revision :datahike.read/revision
           :as evidence}]
       ;; Cheapest proof first. Equal attribute revisions mean no commit since
       ;; the read changed any attribute it depends on (Datahike advances one
       ;; revision per changed attribute, `datahike/query.cljc:2568`), so the
       ;; history scan could only agree; it runs when revisions differ, where
       ;; another entity's write may still leave this read's patterns intact.
       (let [current (dependency-revision database plan source-position)]
        (or (and (not (false? (:datahike.read/cache-eligible? revision)))
                (= revision current))
           (let [source (some #(when (= source-position (:datahike.query.source/argument-position %)) %)
                              (:datahike.query.dependency/sources plan))
                 indexed (when source (index-evidence-current database source revision current))]
             (if (some? indexed)
               indexed
               ;; A replay's declared failures are values (`q`, `pull` and
               ;; `datoms` return their refusal), which compare unequal. A
               ;; throw is not declared, so it propagates with its cause.
               (when (and (find evidence :seon.db/read-request)
                          (or (find evidence :seon.db/read-result)
                              (find evidence :seon.db/read-result-digest)))
                 (let [request (:seon.db/read-request evidence)
                       stable (stable-value (replay-read database request))
                       [replayable? result]
                       (if (bounded-read-request? request) stable [false nil])]
                   (or (and (find evidence :seon.db/read-result)
                            replayable?
                            (= (:seon.db/read-result evidence) result))
                       (when-let [expected
                                  (:seon.db/read-result-digest evidence)]
                         (when-let [actual (read-result-digest stable)]
                           (= expected actual)))))))))))
     retained))

;;; THE declaration population for ONE read operation. Every decode walker
;;; below takes it as its first argument and never resolves it again: asking
;;; `edn-encoded-attr-in?` per attribute re-read all 152 schema resources per
;;; question whenever nothing was supplied on the calling thread, which wedged
;;; two tests at the 300 s liveness backstop and cost one `config/effective`
;;; 84,664 file reads (2026-08-07).
;;;
;;; It is a `delay` so a read that decodes nothing — the common `q` — still
;;; pays nothing, while a read that decodes a thousand attributes pays exactly
;;; one resolution. The delay is created fresh per operation and dies with it:
;;; operation-local, never a process-global cache of declaration facts.
(defn schema-database
  "The database value that owns schema for a possibly temporal view."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.db/database-value]}
  [database]
  (loop [candidate database]
    (if (instance? DB candidate)
      candidate
      (if (satisfies? dbi/IHistory candidate)
        (let [origin (dbi/-origin candidate)]
          (if (identical? candidate origin)
            candidate
            (recur origin)))
        candidate))))

(defn identity-attributes
  "Installed `:db.unique/identity` attributes for one database value."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] [:vector :qualified-keyword]]}
  [database]
  ;; Datahike's reverse schema already holds the set (`rschema`,
  ;; datahike/db/utils.cljc:380): no scan of every installed attribute.
  (->> (:db.unique/identity (dbi/-rschema (schema-database database)))
       (filter qualified-keyword?)
       (sort-by str)
       vec))

(defn populated-identity-attributes
  "Installed identity attributes that occur in actual datoms."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] [:vector :qualified-keyword]]}
  [database]
  (into []
        (filter (fn [attribute]
                  (seq (d/datoms database :avet attribute))))
        (identity-attributes database)))

(defn projection-fallback
  "Report one missing carried projection; never rebuild declarations.

  Each refused operation emits one occurrence at this shared seam. Callers
  retain the returned flat error, including the operation that lacked input."
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.schema/validation-refusal]}
  [operation]
  (binding [*out* *err*]
    (println "WARN seon.db/projection-fallback caller=" operation
             "missing-projection count=1; supply the operation's projection."))
  (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.schema/projection 'seon.db/projection-fallback
   {:seon.error/message "This operation requires a carried schema projection."
    :seon.db/invalid-read true
    :seon.db/refused-read-operation operation
    :seon.schema/expected-value :seon.schema/projection
    :seon.schema/refused-value {:seon.db/operation operation}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))

(def projection-cache-policy
  "Bound retained compiled projections independently of the number of commits."
  {::projection-cache-size 24
   ::projection-cache-reason
   "Retain eight recent declaration populations under each of their three durable keys (a committed value's attribute revisions, its commit, and the declaration content itself) for active branches and retained reports; older populations derive again without retaining an unbounded compiled program population."
   ::value-cache-size 4
   ::value-cache-reason
   "Retain the four most recent speculative revision keys and as-of points, so the reads of one in-transaction value that wrote a declaration cost one lookup instead of one content key each; a burst of speculative values cannot evict the durable keys, and an evicted value's projection is collectable."})

;; The same core.cache wrapped LRU used by datahike.schema-cache:8.
(defonce ^:private projection-cache
  (cache/lru-cache-factory {} :threshold (::projection-cache-size projection-cache-policy)))

;; The value tier of the same memo, in its own bound (`value-projection`).
;; Its keys are plain data (a speculative value's revision key, an as-of
;; point), so core.cache's LRU evicts them by ordinary equality.
(defonce ^:private value-projection-cache
  (cache/lru-cache-factory {} :threshold (::value-cache-size projection-cache-policy)))

(defn- projection-cache-key
  "The part of Datahike's cache-context a projection derivation depends on.

  `schema/projection-attributes` is the loader's own read set. Datahike
  advances one attribute revision per changed attribute and the conservative
  revision on a schema or unknown change (`datahike.query/
  advance-query-cache-context`, query.cljc:2568); its query cache compares
  exactly these members (`source-context-unchanged?`, query.cljc:2963). A
  commit touching no declaration attribute therefore keys the same population."
  {:malli/schema
   [:=> [:cat :seon.db/database-value]
    [:map
     [:datahike.cache/connection-id [:tuple :uuid :keyword]]
     [:datahike.cache/generation :uuid]
     [:datahike.cache/conservative-revision {:optional true} :uuid]
     [:datahike.cache/attribute-revisions [:map-of :qualified-keyword :uuid]]]]}
  [database]
  (let [context (:cache-context database)]
    (assoc (select-keys context [:datahike.cache/connection-id
                                 :datahike.cache/generation
                                 :datahike.cache/conservative-revision])
           :datahike.cache/attribute-revisions
           (select-keys (:datahike.cache/attribute-revisions context)
                        schema/projection-attributes))))

(defn- declaration-content-key
  "The complete input `schema/load-projection` reads: every datom of
  `schema/projection-attributes`, with its transaction.

  Two values holding these datoms derive the same projection whatever their
  connection, branch or committed identity, so this key hits across a new
  connection at an equal commit, an in-transaction value that wrote a
  declaration (its fresh revision names no cached entry) and an as-of view
  with its own rows. Datom equality ignores the
  transaction (`datahike/datom.cljc:106`), so each attribute's transactions
  ride beside its datoms. Unchanged datoms are the index's own objects, so
  equality is mostly `identical?`; the cost is one pass over these
  attributes (about 40k datoms, ~5 ms, in default on 2026-09-22; an as-of
  view adds its sort, 17-21 ms)."
  {:malli/schema
   ;; The shape of each attribute's entry, not each of its ~30k datoms, which
   ;; the index produced: validating every datom cost 148 ms per armed key
   ;; (cache-invalidation audit, 2026-09-23).
   [:=> [:cat :seon.db/database-value]
    [:vector [:tuple :qualified-keyword [:fn clojure.core/vector?] [:fn clojure.core/vector?]]]]}
  [database]
  (let [installed (dbi/-schema (schema-database database))]
    (mapv (fn [attribute]
            ;; Datahike's own AEVT order: an as-of view yields the equal
            ;; datoms in another order and must key the equal content. Sorting
            ;; already-ordered input costs ~nothing (regression-bisect,
            ;; 2026-09-23: 1.42 vs 1.49 ms over 30,387 datoms).
            (let [datoms (if (get installed attribute)
                           (vec (sort datahike.datom/cmp-datoms-aevt-quick
                                      (d/datoms database :aevt attribute)))
                           [])]
              [attribute datoms (into [] (map :tx) datoms)]))
          schema/projection-attributes)))

(defn- revision-agreement
  "How many members of a revision key `candidate` shares with `context`:
  its connection, generation and conservative revision, and each declaration
  attribute's revision."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.schema/value]
                       [:map-of :qualified-keyword :seon.schema/value]]
                  :int]}
  [context candidate]
  (let [revisions (:datahike.cache/attribute-revisions context)
        candidate-revisions (:datahike.cache/attribute-revisions candidate)]
    (+ (count (filter #(= (get context %) (get candidate %))
                      [:datahike.cache/connection-id :datahike.cache/generation
                       :datahike.cache/conservative-revision]))
       (count (filter #(= (get revisions %) (get candidate-revisions %))
                      schema/projection-attributes)))))

(defn- nearest-base
  "The derived projection nearest `database`, the base its derivation replaces
  from (`schema/load-projection`), or `{}` when none is derived yet.

  Nearest is the committed revision key sharing the most declaration-attribute
  revisions with `database`'s cache-context: its basis for a speculative value
  (`datahike.db/speculative-cache-context` keeps every untouched revision) and
  the previous commit for a committed one. Any base derives the same
  projection; a near one recompiles only what differs. Only realized entries
  are candidates, so no derivation waits on another."
  {:malli/schema [:=> [:cat :seon.source/digest :seon.db/database-value] :seon.schema/projection]}
  [builder database]
  (let [context (when (instance? DB database) (:cache-context database))
        ;; Another builder's entities would survive the replacement unrebuilt.
        candidates (keep (fn [[k cell]] (when (and (map? k) (= builder (::builder k)) (realized? cell)) [k cell]))
                         @projection-cache)]
    (if (seq candidates)
      @(second (apply max-key #(revision-agreement (or context {}) (first %)) candidates))
      {})))

(defn- content-projection
  "The projection of `database`'s declaration content, memoized by that
  content and the loaded `builder`. A miss takes the cell a speculative value
  of the same content derived (`speculative-projection`), else derives from
  its `nearest-base`."
  {:malli/schema [:=> [:cat :seon.source/digest :seon.db/database-value] :seon.schema/projection]}
  [builder database]
  (let [content-key [::declaration-content builder (declaration-content-key database)]]
    @(cache/lookup-or-miss projection-cache content-key
                           (fn [_] (or (cache/lookup value-projection-cache content-key)
                                       (delay (schema/load-projection database (nearest-base builder database))))))))

(defn- value-projection
  "The projection of a value whose `value-key` names its declaration content
  exactly, memoized with the loaded `builder` in the bounded value tier, then
  by content."
  {:malli/schema [:=> [:cat :seon.source/digest
                            [:or [:map-of :qualified-keyword :seon.schema/value]
                                 [:tuple :qualified-keyword :uuid [:or :int :inst]]]
                            :seon.db/database-value]
                  :seon.schema/projection]}
  [builder value-key database]
  @(cache/lookup-or-miss value-projection-cache [builder value-key]
                         (fn [_] (delay (content-projection builder database)))))

(defn- speculative-projection
  "The projection of a speculative value (a `with` result, a report's
  `:db-after`, a `:db.fn/call` argument) that wrote a declaration attribute:
  derived from its basis (`nearest-base`, the committed entry sharing its
  untouched revisions) and retained only in the bounded value tier, keyed by
  its revision key. It is never inserted into the committed projection cache,
  so a batch of speculative values cannot evict committed entries (one-path
  spec 12, ruling B: a write after a 22-registration batch derived 30 ms before
  the batch and 642 ms after it, from a full rebuild)."
  {:malli/schema [:=> [:cat :seon.source/digest [:map-of :qualified-keyword :seon.schema/value]
                       :seon.db/database-value]
                  :seon.schema/projection]}
  [builder value-key database]
  ;; Also held by its declaration content: the commit of this very value keys
  ;; that content, and `content-projection` promotes the cell instead of
  ;; deriving again (90 ms per committed declaration write without it).
  ;; The content key (~5 ms, one pass over the declaration datoms) is read only
  ;; on a revision miss: a transaction function reads this value's projection
  ;; once per stored collection it compares.
  @(cache/lookup-or-miss value-projection-cache [builder value-key]
                         (fn [_] (cache/lookup-or-miss
                                  value-projection-cache
                                  [::declaration-content builder (declaration-content-key database)]
                                  (fn [_] (delay (schema/load-projection database (nearest-base builder database))))))))

(defn- as-of-key
  "An as-of view's exact identity: its committed origin's commit and its time
  point, which fix every datom it holds. Absent for an uncommitted origin."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :nil [:tuple :qualified-keyword :uuid [:or :int :inst]]]]}
  [database]
  (let [time-point (dbi/-time-point database)]
    (when-let [identity (datahike.db/committed-value-identity (dbi/-origin database))]
      (when (or (integer? time-point) (inst? time-point))
        [::as-of (:datahike.value/commit-id identity) time-point]))))

(defn- declares-program?
  "True when this value holds declaration entities its projection can derive from."
  {:malli/schema [:=> [:cat :seon.db/database-value] :boolean]}
  [database]
  (boolean (and (get (dbi/-schema database) :seon.schema/key)
                (first (d/datoms database :avet :seon.schema/key)))))

(defn- construction-projection
  "The projection a cold boundary supplied for a value with no declaration rows.

  Genesis and first population write before any declaration row exists, so
  their value cannot derive a projection: it reads the construction metadata
  the value carries, else the projection its caller handed
  (`schema/handed-projection`). The writer hands its transaction functions a
  value without metadata, so a population reconciled inside the writer onto an
  empty store (`seon.fn/reconcile-call`, which binds its projection) reads the
  handed one; that is what lets a first population take the same path as every
  later one. Absent both, it refuses by name."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  (or (:seon.schema/projection (meta database))
      (schema/handed-projection)
      (let [failure (projection-fallback 'seon.db/carried-projection)]
        (throw (ex-info (:seon.error/message failure) failure)))))

(defn call-reach
  "Every symbol reached from `root` through `calls`, the root included.
  `calls` maps a symbol to its callees: stored rows (`stored-call-reach`) or
  a proposed overlay. The visited set ends cycles; absent symbols are leaves."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?] :symbol] [:set :symbol]]}
  [calls root]
  (loop [pending (list root) reached #{}]
    (if-let [[function & more] (seq pending)]
      (if (reached function)
        (recur more reached)
        (recur (into more (calls function)) (conj reached function)))
      reached)))

(defn stored-call-reach
  "`call-reach` over `database`'s stored `:seon.fn/calls`.
  Two index lookups per reached symbol, so cost follows the root's reach.
  Pull recursion follows only refs (datahike pull_api.cljc:340) and a
  recursive rule derives the whole graph first (3.3 s). Records no read
  evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:set :symbol]]}
  [database root]
  ;; A store that has not yet installed the function family (genesis, first
  ;; population) holds no entities: every symbol is a leaf, never an index read of
  ;; an attribute Datahike does not know.
  (if-not (get (dbi/-schema database) :seon.fn/sym)
    #{root}
    (call-reach (fn [function]
                  (when-let [entity (:e (first (d/datoms database :avet :seon.fn/sym function)))]
                    (map :v (d/datoms database :eavt entity :seon.fn/calls))))
                root)))

(defn- builder-digest
  "The projection builder's identity in `database`: the digest of the
  definition digests reached from `seon.schema/load-projection`, ~2 ms."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.source/digest]}
  [database]
  (id/digest 64 [(into (sorted-map)
                       (keep (fn [function]
                               (when (get (dbi/-schema database) :seon.fn/sym)
                               (when-let [entity (:e (first (d/datoms database :avet :seon.fn/sym function)))]
                                 (some->> (first (d/datoms database :eavt entity :seon.program/definition-digest))
                                          :v (vector function))))))
                       (stored-call-reach database 'seon.schema/load-projection))]))

(defn- loaded-builder
  "The builder digest of the program this JVM loaded, memoized by its commit.

  The authority is the process, never the value read: `loaded` is
  `seon.sci.eval/loaded-identity`, which development adoption names by commit
  after its record commits; before any adoption it is empty and the JVM runs
  its boot commit, which each running instance holds. A value's own adoption
  record names its branch's historical program. While an adoption loads (an
  unknown identity), the loaded code is mixed: an unchanged builder keeps its
  digest, a changed one keys a projection to that adoption alone, so no mixed
  construction is ever reused. Boot commits that disagree refuse by name. A JVM
  with no running instance keys by the value's own commit or rows.

  A value whose store lacks the loaded commit (another store's value) keys by
  that commit and its own builder, and a miss that materializes nothing caches
  nothing: `delay` rethrows a stored failure on every deref, so one such read
  refused every later read of the commit (lane-fix-unknown-identity-blocks-reads)."
  {:malli/schema [:=> [:cat :seon.db/database-value :map [:maybe [:sequential :map]]]
                  :seon.source/digest]}
  [database loaded instances]
  (let [memo (fn [commit]
               (when commit
                 (or (some-> (cache/lookup projection-cache [::builder commit]) deref)
                     (when-let [program (d/commit-as-db database commit {:secondary-indices? false})]
                       @(cache/lookup-or-miss projection-cache [::builder commit]
                                              (fn [_] (delay (builder-digest program))))))))
        ;; An uncommitted value (a transaction function's argument, a report's
        ;; :db-after) is keyed by the revisions of the attributes
        ;; `builder-digest` reads: during boot no instance names its commit yet,
        ;; and deriving per read cost a whole reach query each time a
        ;; transaction compared a stored collection (298 of 581 writer samples,
        ;; one cold boot publication, 2026-09-27).
        revisions (fn [] (let [context (:cache-context database)]
                           (when (:datahike.cache/connection-id context)
                             [::builder-revisions
                              (select-keys context [:datahike.cache/connection-id :datahike.cache/generation
                                                    :datahike.cache/conservative-revision])
                              (select-keys (:datahike.cache/attribute-revisions context)
                                           [:seon.fn/sym :seon.fn/calls :seon.program/definition-digest])])))
        own (fn [] (if-let [commit (:datahike.value/commit-id (datahike.db/committed-value-identity database))]
                     (or (memo commit) (builder-digest database))
                     (if-let [k (revisions)]
                       @(cache/lookup-or-miss value-projection-cache k (fn [_] (delay (builder-digest database))))
                       (builder-digest database))))
        loaded-key (fn [commit] (or (memo commit) (id/digest 64 [::foreign-store commit (own)])))
        boots (into #{} (keep :seon.source/commit-id) instances)]
    (cond
      (:seon.error/at loaded)
      (let [target (memo (:seon.source/commit-id loaded))
            ;; Each adopting cluster's record still names the program before this adoption.
            priors (into #{} (keep #(:seon.source/commit-id
                                     (d/pull (d/db (:seon.boot/cluster-connection %)) [:seon.source/commit-id]
                                             [:seon.cluster/name (get-in % [:seon.boot/advertisement
                                                                            :seon.boot/cluster-name])])))
                         instances)]
        (if (and target (seq priors) (every? #(= target (memo %)) priors))
          target
          (id/digest 64 [::mixed-program (str (:seon.error/at loaded)) (str (:seon.source/commit-id loaded))])))

      (:seon.source/commit-id loaded) (loaded-key (:seon.source/commit-id loaded))
      (= 1 (count boots)) (loaded-key (first boots))
      (seq boots) (throw (ex-info "The running instances booted different programs; the loaded projection builder is ambiguous."
                                  {:seon.error/expected :seon.source/commit-id
                                   :seon.error/offending boots}))
      :else (own))))

(defn carried-projection
  "Derive this database value's projection, memoized by what it reads.

  A committed value keys by its declaration attributes' revisions, then by
  its commit. A speculative value reuses its basis's entry through the same
  revision key when it wrote no declaration attribute. Any other value, and a
  miss, keys by the declaration datoms themselves
  (`declaration-content-key`), so equal populations share one derivation
  across connections, branches and in-transaction values. An as-of view keys
  by its own declaration datoms: a view older than a declaration change sees
  the older population. History and since views read their origin's current
  population, as their installed schema does; they hold several versions or
  a suffix, never one older population. A speculative value's key includes
  every earlier operation of its transaction. A value holding no declaration
  rows reads its cold boundary's construction projection or refuses by name.
  Every key also names the loaded builder (`loaded-builder`), so a projection
  built by other builder code is never reused or used as a base."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  (let [as-of? (instance? AsOfDB database)
        source (if as-of? database (schema-database database))
        origin (if as-of? (schema-database database) source)
        ;; The JVM's loaded builder (`loaded-builder`); a converged identity's
        ;; memo is read here, since one more armed call per read doubled the hit cost.
        builder (delay (let [loaded (or (some-> (find-ns 'seon.sci.eval) (ns-resolve 'loaded-identity) deref deref) {})
                             instances (some-> (find-ns 'seon.operator.runtime) (ns-resolve 'running-instances)
                                               deref deref vals)
                             boots (into #{} (keep :seon.source/commit-id) instances)
                             commit (when-not (:seon.error/at loaded)
                                      (or (:seon.source/commit-id loaded) (when (= 1 (count boots)) (first boots))))]
                         (or (some-> (when commit (cache/lookup projection-cache [::builder commit])) deref)
                             (loaded-builder origin loaded instances))))
        ;; An as-of view keys by its own declaration datoms, never its origin's.
        ;; An uncommitted origin (the writer's in-transaction value) names them
        ;; by its revision key and the time point: within one connection an
        ;; attribute's revision advances with every datom it gains or loses
        ;; (`datahike.db/advance-cache-context`, db.cljc:423), so the pair fixes
        ;; the view's declaration datoms without reading them (25 ms per read
        ;; before, lane-writer-cost-2-2026-09-23.md).
        view-key (when as-of?
                   (or (as-of-key source)
                       (when (:datahike.cache/connection-id (:cache-context (dbi/-origin source)))
                         (assoc (projection-cache-key (dbi/-origin source))
                                ::as-of (dbi/-time-point source)))))
        committed (when-not as-of? (datahike.db/committed-value-identity source))
        ;; A cell exists only under a key whose value declared its program, and
        ;; a key names its declaration datoms, so a hit answers before
        ;; `declares-program?`: on an as-of view that first datom merges the
        ;; temporal index, 1.6 ms per call (lane-fix-cost-db-2026-09-25).
        cached (fn [cache-atom k]
                 (when-let [cell (cache/lookup cache-atom k)]
                   (cache/hit cache-atom k)
                   @cell))]
    (or
     (when view-key (cached value-projection-cache [@builder view-key]))
     (when committed
       ;; A commit hit on a new connection (a fresh branch of a seen commit)
       ;; also names the cell by this connection's revision key, so the
       ;; speculative values the branch derives (`with`, a transaction
       ;; function's argument) find their basis without reading the
       ;; declaration datoms.
       (when-let [cell (cache/lookup projection-cache [::commit (:datahike.value/commit-id committed) @builder])]
         (cache/hit projection-cache [::commit (:datahike.value/commit-id committed) @builder])
         (when (:datahike.cache/connection-id (:cache-context source))
           (cache/lookup-or-miss projection-cache (assoc (projection-cache-key source) ::builder @builder)
                                 (fn [_] cell)))
         @cell))
     (cond
      (not (declares-program? source))
      (construction-projection database)

      as-of?
      (if view-key
        (value-projection @builder view-key source)
        (content-projection @builder source))

      committed
      ;; Store the delay before forcing it: concurrent misses share the winning
      ;; cell even when core.cache's atom retries its insertion. A revision
      ;; miss (a new connection or branch) tries the commit, which names one
      ;; value in every connection (`datahike/writing.cljc:363`), then the
      ;; declaration content.
      ;; A revision hit also stores its cell under the commit, so a new branch
      ;; at an equal commit hits too; Datahike's query cache keys committed
      ;; values the same way (fork `684d3290`). Both key kinds share the bound.
      (let [commit-key [::commit (:datahike.value/commit-id committed) @builder]
            cell (cache/lookup-or-miss
                  projection-cache (assoc (projection-cache-key source) ::builder @builder)
                  (fn [_] (cache/lookup-or-miss
                           projection-cache commit-key
                           (fn [_] (delay (content-projection @builder source))))))]
        @(cache/lookup-or-miss projection-cache commit-key (fn [_] cell)))

      ;; A speculative value (a report's db-after, a `:db.fn/call` argument,
      ;; a `with` result) carries Datahike's revision context derived from its
      ;; basis: untouched attributes keep the basis revision and each written
      ;; attribute a fresh one (`datahike.db/speculative-cache-context`,
      ;; `reference-code/datahike/src/datahike/db.cljc:444`). Its revision key
      ;; therefore equals its basis's exactly when it wrote no declaration
      ;; attribute, and it only reads that durable tier. Otherwise the fresh
      ;; revisions name this value's declaration content alone, so the key
      ;; memoizes it in the bounded value tier, never among the durable keys.
      (:datahike.cache/connection-id (:cache-context source))
      (let [revision-key (assoc (projection-cache-key source) ::builder @builder)]
        (if (cache/has? projection-cache revision-key)
          @(cache/lookup-or-miss projection-cache revision-key
                                 (fn [_] (delay (content-projection @builder source))))
          (speculative-projection @builder revision-key source)))

      ;; A detached value (Datahike gives it no context: an `empty-db` or
      ;; `load-entities` basis) keys by its declaration content alone.
      :else
      (content-projection @builder source)))))

(defn- read-declarations
  "The declaration table for one read, or the flat refusal naming what is missing.

   Before this returned a table whose `::installed-schema` was `nil` whenever
   the supplied value was not a database, and `edn-encoded?` then answered
   false for EVERY attribute: each decoded value silently lost its declared
   decoding, with no signal anywhere (critical finding #18). An absent
   installed schema is the refusal; consumers branch on it."
  {:malli/schema [:=> [:cat :seon.schema/value :qualified-symbol] [:or [:map [:seon.db/installed-schema :map] [:seon.db/read-projection [:fn clojure.core/delay?]]] :seon.db.read/unreadable-declarations-error]]}
  [database operation]
  (let [origin (when (db.utils/db? database) (schema-database database))
        installed (:schema origin)]
    (if (seq installed)
      {::installed-schema installed
       ::read-projection
       (delay (carried-projection database))}
      (vary-meta (diagnostic
       {:seon.error/message (str operation " cannot decode a read: the supplied value carries no installed database schema.")
        :seon.db.read/unreadable-declarations operation
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/operation operation
        :seon.error/member ::installed-schema
        :seon.error/data {:seon.db/db database}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))))

(def ^:private relation-only-declarations
  "The declaration table for a read with no database source.

   Its installed schema is EMPTY because there are no stored values in the
   answer, not because the table could not be read. Every walker therefore
   decodes nothing, correctly."
  {::installed-schema {}
   ::read-projection (delay (schema/handed-projection))})

(defn- with-declarations
  "Call `continue` with the read's declarations, or return their refusal.

   `absent` is what a read that genuinely has no database source passes for
   `database`; anything else missing an installed schema is the refusal.
   The candidate database is deliberately polymorphic so malformed values reach
   that diagnostic; the continuation owns its result shape (query, pull,
   datoms, or diff), which this seam returns unchanged."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/value :qualified-symbol
          [:=> [:cat :map] :seon.schema/value]]
     [:or :seon.schema/value :seon.db/error-result]]
    [:=> [:cat :seon.schema/value :qualified-symbol
          [:or :nil [:= :seon.db/relation-only]]
          [:=> [:cat :map] :seon.schema/value]]
     [:or :seon.schema/value :seon.db/error-result]]]}
  ([database operation continue]
   (with-declarations database operation nil continue))
  ([database operation absent continue]
   (if (and (nil? database) (= :seon.db/relation-only absent))
     (continue relation-only-declarations)
     (let [declarations (read-declarations database operation)]
       (if-not (::read-projection declarations)
         declarations
         (continue declarations))))))

(defn- ask-declarations
  "Ask one declaration question with the population both PASSED and SUPPLIED.

   Passing answers `edn-encoded-attr-in?` itself. Supplying answers the
   registered core predicates Malli calls underneath it with the value alone —
   `schema/malli-form?` builds its own registry and cannot take an argument —
   which is the instance no amount of threading can reach. Measured live
   2026-08-07 on cluster `dbread`, ONE `edn-encoded-attr-in?` over a config
   attribute: projection merely passed = 1,824 resource reads / 76.6 ms;
   projection also supplied = 0 reads / 0.17 ms. Supplying is the same one
   value made visible for one call, never a cache."
  [declarations question]
  (let [projection @(::read-projection declarations)]
    (schema/call-with-projection
     projection
     #(question projection))))

(defn- edn-encoded?
  [declarations attribute]
  ;; The bridge's EDN fallback is physically a string. Native storage types
  ;; therefore need no logical schema projection merely to rule out decoding.
  (and (= :db.type/string
          (get-in declarations [::installed-schema attribute :db/valueType]))
       (let [projection @(::read-projection declarations)]
         (schema/projection-cache-value
          projection [::edn-encoded? attribute]
          #(ask-declarations
            declarations
            (fn [supplied]
              (schema.datahike/edn-encoded-attr-in? supplied attribute)))))))

(defn- decode-attribute-value
  [declarations attribute value]
  (ask-declarations
   declarations
   #(schema.datahike/decode-attribute-value-in % attribute value)))

(defn- decode-attribute-maps
  [declarations value]
  (cond
    (map? value)
    (reduce-kv
     (fn [decoded attribute child]
       (assoc decoded attribute
              (if (and (keyword? attribute)
                       (edn-encoded? declarations attribute))
                (decode-attribute-value declarations attribute child)
                (decode-attribute-maps declarations child))))
     (empty value)
     value)

    (vector? value)
    (mapv #(decode-attribute-maps declarations %) value)

    (set? value)
    (into #{} (map #(decode-attribute-maps declarations %)) value)

    (sequential? value)
    (doall (map #(decode-attribute-maps declarations %) value))

    :else value))

(defn- parsed-nodes
  [root]
  (tree-seq #(or (map? %) (coll? %))
            #(cond
               (map? %) (vals %)
               (coll? %) %
               :else nil)
            root))

(defn- installed-attribute-declarations
  [database]
  (into (sorted-map)
        (keep (fn [[attribute declaration]]
                (when (qualified-keyword? attribute)
                  [attribute declaration])))
        (dbi/-schema (schema-database database))))

(defn attribute-installed?
  "Whether `database` holds `attribute` as an installed declaration.

  The database is the authority on what can be a datom: a projection may
  map an attribute the bridge could store while this branch has never
  installed it, and transacting that attribute refuses. Callers deciding
  what to record ask here, never a roster."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword]
                  :boolean]}
  [database attribute]
  (boolean (get (dbi/-schema (schema-database database)) attribute)))

(defn- registered-attribute-candidates
  [declarations attribute]
  (let [same-namespace
        (when (qualified-keyword? attribute)
          (filter #(= (namespace attribute) (namespace %))
                  (keys declarations)))]
    (into [] (take 12) (or (seq same-namespace) (keys declarations)))))

(defn- attribute-observation
  [database attribute]
  (let [declarations (installed-attribute-declarations database)]
    {::attribute attribute
     ::installed-declaration (get declarations attribute)
     ::registered-candidates
     (registered-attribute-candidates declarations attribute)}))

(defn- unknown-attribute-error
  {:malli/schema [:=> [:cat :qualified-symbol :seon.db/database-value :seon.schema/value :seon.schema/value] :seon.error/base]}
  [operation database attribute offending]
  (let [evidence (attribute-observation database attribute)]
    (vary-meta (diagnostic
     {:seon.error/message (str operation " cannot read uninstalled attribute "
           (pr-str attribute) ".")
      :seon.db/invalid-read true
      :seon.error/layer :database-read
      :seon.error/operation operation
      :seon.error/expected {::installed-declaration :seon.error/unknown
       ::registered-candidates (::registered-candidates evidence)}
      :seon.error/offending offending
      :seon.error/data (merge evidence {:seon.error/member attribute})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error)))

(defn- value-type-error
  {:malli/schema [:=> [:cat :qualified-symbol :seon.db/database-value :map :qualified-keyword :seon.schema/value] :seon.error/base]}
  [operation database declaration attribute value]
  (vary-meta (diagnostic
   {:seon.error/message (str operation " received " (pr-str value) " for "
         (pr-str attribute) ", whose installed value type is "
         (pr-str (:db/valueType declaration)) ".")
    :seon.db/invalid-read true
    :seon.error/layer :database-read
    :seon.error/operation operation
    :seon.error/expected declaration
    :seon.error/offending {::attribute attribute ::value value}
    :seon.error/data (merge (attribute-observation database attribute)
                            {:seon.error/member attribute :seon.error/source {::validation ::value-does-not-match-installed-type
     ::value-type (:db/valueType declaration)}})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))

(defn- lookup-ref-error
  {:malli/schema [:=> [:cat :qualified-symbol :seon.db/database-value :seon.schema/value] [:or :nil :seon.error/base]]}
  [operation database entity-id]
  (when (and (sequential? entity-id) (= 2 (count entity-id)))
    ;; One declaration read; the whole-schema observation only on refusal.
    (let [[attribute value] entity-id
          installed (dbi/-schema (schema-database database))
          declaration (when (qualified-keyword? attribute) (get installed attribute))
          valid-value? (and declaration
                            (datahike.schema/value-valid? attribute value installed))]
      (cond
        (nil? declaration)
        (unknown-attribute-error operation database attribute entity-id)

        (nil? (:db/unique declaration))
        (vary-meta (diagnostic
         {:seon.error/message (str operation " requires a unique lookup-ref attribute; "
               (pr-str attribute) " is not unique.")
          :seon.db/invalid-read true
          :seon.error/layer :database-read
          :seon.error/operation operation
          :seon.error/expected declaration
          :seon.error/offending entity-id
          :seon.error/data (merge (attribute-observation database attribute)
                                  {:seon.error/member attribute})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error)

        (not valid-value?)
        (value-type-error operation database declaration attribute value)))))

(defn- query-binding-values
  "Collect every scalar value admitted by a Datalog input binding."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :map]}
  [binding value]
  (cond
    (instance? BindScalar binding)
    (if (instance? Variable (:variable binding))
      {(:symbol (:variable binding)) #{value}} {})
    (instance? BindColl binding)
    (or (apply merge-with set/union (map #(query-binding-values (:binding binding) %) value)) {})
    (instance? BindTuple binding)
    (or (apply merge-with set/union (map query-binding-values (:bindings binding) value)) {})
    :else {}))

(defn- query-input-values
  {:malli/schema [:=> [:cat :seon.schema/value [:sequential :seon.schema/value]] :map]}
  [parsed-query arguments]
  (or (apply merge-with set/union
             (map query-binding-values (:qin parsed-query) arguments)) {}))

(defn- query-input-bindings
  [parsed-query arguments]
  (into {} (keep (fn [[variable values]]
                   (when (= 1 (count values)) [variable (first values)])))
        (query-input-values parsed-query arguments)))

(defn- query-patterns
  [parsed-query]
  (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))

(defn- parsed-node-value
  [node]
  (cond
    (instance? Variable node) (:symbol node)
    (instance? Constant node) (:value node)
    :else node))

(defn- parsed-pattern-value
  [pattern]
  (let [source-symbol (:symbol (:source pattern))
        values (mapv parsed-node-value (:pattern pattern))]
    (cond-> values source-symbol (into [source-symbol]))))

(defn- malformed-query-pattern-error
  {:malli/schema [:=> [:cat :map :map] [:or :nil :seon.error/base]]}
  [request parsed-query]
  (when-let [pattern (some #(when (> (count (:pattern %)) 5) %)
                           (query-patterns parsed-query))]
    (let [offending (parsed-pattern-value pattern)]
      (vary-meta (diagnostic
       {:seon.error/message "seon.db/q received a data pattern with more than five positions."
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/operation 'seon.db/q
        :seon.error/expected [:entity :attribute :value :transaction :added]
        :seon.error/offending offending
        ;; the query form, never the request: its arguments carry the database
        :seon.error/data {:seon.error/member offending ::query (:query request)}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))))

(defn- query-source-databases
  [query-form arguments]
  (into {}
        (keep (fn [{source-symbol :datahike.query.source/symbol
                    position :datahike.query.source/argument-position}]
                (let [argument (nth arguments position nil)]
                  (when (db.utils/db? argument)
                    [source-symbol argument]))))
        (d/query-source-bindings query-form)))

(defn- query-attribute-error
  [request parsed-query]
  (let [arguments (:args request)
        input-bindings (query-input-bindings parsed-query arguments)
        databases (query-source-databases (:query request) arguments)
        schemas
        (update-vals
         databases
         #(dbi/-schema (schema-database %)))]
    (some
     (fn [pattern]
       (let [attribute-node (nth (:pattern pattern) 1 nil)
             attribute (if (instance? Constant attribute-node)
                         (:value attribute-node)
                         (get input-bindings (:symbol attribute-node)))
             source-symbol (or (:symbol (:source pattern)) '$)
             database-schema (get schemas source-symbol)]
         (when (and database-schema
                    (keyword? attribute)
                    (nil? (get database-schema attribute)))
           (unknown-attribute-error
            'seon.db/q
            (get databases source-symbol)
            attribute
            (parsed-pattern-value pattern)))))
     (query-patterns parsed-query))))

;; A data pattern's value must satisfy its attribute's installed type, as a
;; transaction's must (`datahike.schema/value-valid?`). Datahike's planner
;; compares the value against the AVET index only on some database shapes
;; (a quoted `'sym` inside a quoted query is the list `(quote sym)`, which
;; Symbol.compareTo throws on over a raw DB and a filtered DB answers nil), so
;; an unchecked read could succeed, become read evidence, and throw on replay.
;; Refs take lookup refs and idents; EDN-encoded values are encoded later.
(defn- query-value-type-error
  {:malli/schema [:=> [:cat :map :map :seon.schema/value] [:or :nil :seon.error/base]]}
  [declarations request parsed-query]
  (let [arguments (:args request)
        input-values (query-input-values parsed-query arguments)
        databases (query-source-databases (:query request) arguments)]
    (some
     (fn [pattern]
       (let [[_ attribute-node value-node] (:pattern pattern)
             attribute (if (instance? Constant attribute-node)
                         (:value attribute-node)
                         (let [values (get input-values (:symbol attribute-node))]
                           (when (= 1 (count values)) (first values))))
             database (get databases (or (:symbol (:source pattern)) '$))
             installed (when (and database (keyword? attribute))
                         (dbi/-schema (schema-database database)))
             declaration (get installed attribute)
             values (cond (instance? Constant value-node) [(:value value-node)]
                          (instance? Variable value-node) (get input-values (:symbol value-node)))
             invalid (when (and declaration (not= :db.type/ref (:db/valueType declaration)))
                       (remove #(datahike.schema/value-valid? attribute % installed) values))]
         ;; Only a failing value resolves the declarations (a delay most
         ;; reads never force).
         (when (and (seq invalid) (not (edn-encoded? declarations attribute)))
           (value-type-error 'seon.db/q database declaration attribute (first invalid)))))
     (query-patterns parsed-query))))

(defn- query-variable-attributes
  [parsed-query arguments]
  (let [input-bindings (query-input-values parsed-query arguments)]
    (reduce
     (fn [attributes pattern]
       (let [attribute-node (nth (:pattern pattern) 1 nil)
             value-node (nth (:pattern pattern) 2 nil)
             candidates (if (instance? Constant attribute-node)
                          #{(:value attribute-node)}
                          (get input-bindings (:symbol attribute-node)))
             variable (:symbol value-node)]
         (if (and (seq candidates)
                  (every? keyword? candidates)
                  (instance? Variable value-node))
           (update attributes variable (fnil into #{}) candidates)
           attributes)))
     {}
     (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))))

(defn- query-find-attributes
  {:malli/schema [:=> [:cat :map :map [:sequential :seon.schema/value]] [:vector [:or :nil :keyword [:map [:seon.db/attribute-position :int]] [:vector :keyword]]]]}
  [declarations parsed-query arguments]
  (let [variable-attributes (query-variable-attributes parsed-query arguments)
        elements (vec (parser/find-elements (:qfind parsed-query)))
        positions (into {} (keep-indexed #(when (instance? Variable %2) [(:symbol %2) %1])) elements)]
    (mapv
     (fn [element]
       (when (instance? Variable element)
         (let [attributes (get variable-attributes (:symbol element))
               encoded (filter #(edn-encoded? declarations %) attributes)]
           (when (seq encoded)
             (if (= 1 (count attributes))
               (first attributes)
               (if-let [position
                        (some (fn [pattern]
                                (let [[_ attribute value] (:pattern pattern)]
                                  (when (= (:symbol element) (:symbol value))
                                    (get positions (:symbol attribute)))))
                              (query-patterns parsed-query))]
                 {::attribute-position position}
                 (if (= (count encoded) (count attributes))
                   (vec encoded)
                   (throw (ex-info "Select the attribute alongside values with different storage codecs."
                                   {::attributes attributes})))))))))
     elements)))

(defn- encode-query-request
  "Encode values at parsed Datalog value positions, using the write codec."
  {:malli/schema [:=> [:cat :map :map :seon.schema/value] :map]}
  [declarations request parsed-query]
  (let [bindings (query-input-bindings parsed-query (:args request))
        attributes (query-variable-attributes parsed-query (:args request))
        encode (fn [attribute value]
                 (if (and attribute (edn-encoded? declarations attribute))
                   (ask-declarations declarations
                     #(schema.datahike/encode-attribute-value-in % attribute value))
                   value))
        replacements
        (into {}
              (keep (fn [pattern]
                      (let [[_ attribute value] (:pattern pattern)
                            attribute (if (instance? Constant attribute) (:value attribute)
                                          (get bindings (:symbol attribute)))]
                        (when (and (keyword? attribute) (instance? Constant value)
                                   (edn-encoded? declarations attribute))
                          (let [form (parser.impl/get-source pattern)
                                position (if (:symbol (:source pattern)) 3 2)]
                            [form (assoc (vec form) position (encode attribute (:value value)))])))))
              (query-patterns parsed-query))]
    (letfn [(encode-binding [binding value]
              (cond
                (instance? BindScalar binding)
                (let [candidates (get attributes (:symbol (:variable binding)))]
                  (if (= 1 (count candidates)) (encode (first candidates) value) value))
                (instance? BindColl binding) (mapv #(encode-binding (:binding binding) %) value)
                (instance? BindTuple binding) (mapv encode-binding (:bindings binding) value)
                :else value))]
      (assoc request
             :query (walk/postwalk #(get replacements % %) (:query request))
             :args (mapv encode-binding (:qin parsed-query) (:args request))))))

(defn- decode-query-field
  {:malli/schema [:=> [:cat :map [:or :nil :keyword [:vector :keyword] [:map [:seon.db/attribute-position :int]] [:map [:seon.db/pull-spec :map]]] :seon.schema/value] :seon.schema/value]}
  [declarations attribute value]
  (cond
    (::pull-spec attribute)
    (schema.datahike/decode-vectors @(::read-projection declarations) (::pull-spec attribute)
                                    (decode-attribute-maps declarations value))

    (and (vector? attribute) (some? value))
    ;; One variable binds values of several attributes: a plain string of a
    ;; later attribute can read as EDN under an earlier EDN-backed one, so an
    ;; earlier candidate is taken only when its decoded value fits its schema.
    ;; This picks the producing attribute; it judges no committed value (a
    ;; decode never re-applies the write schema).
    (let [projection @(::read-projection declarations)]
      (loop [[candidate & remaining] attribute]
        (let [attempt (try {::value (decode-attribute-value declarations candidate value)}
                           (catch Exception failure {::failure failure}))]
          (cond
            (::failure attempt)
            (if (seq remaining) (recur remaining) (throw (::failure attempt)))

            ;; Validated as the decode did before 53ab87bac: with the projection
            ;; supplied (`ask-declarations`), which registered predicates such
            ;; as `schema/malli-form?` read; unsupplied, they refuse valid values.
            (and (seq remaining)
                 (schema.datahike/edn-encoded-attr-in? projection candidate)
                 (not (ask-declarations
                       declarations
                       #((schema/projection-validator % candidate) (::value attempt)))))
            (recur remaining)

            :else (::value attempt)))))
    (and attribute (some? value)) (decode-attribute-value declarations attribute value)
    :else (decode-attribute-maps declarations value)))

(defn- decode-query-tuple
  {:malli/schema [:=> [:cat :map [:vector [:or :nil :keyword [:vector :keyword] [:map [:seon.db/attribute-position :int]] [:map [:seon.db/pull-spec :map]]]] [:sequential :seon.schema/value]] [:vector :seon.schema/value]]}
  [declarations attributes tuple]
  (mapv #(decode-query-field declarations
                               (if-let [position (::attribute-position %1)] (nth tuple position) %1) %2)
        attributes tuple))

(defn- query-return-map-keys
  [return-maps]
  (let [mapping-keys (map :mapping-key (:mapping-keys return-maps))]
    (case (:mapping-type return-maps)
      :keys (mapv keyword mapping-keys)
      :strs (mapv str mapping-keys)
      :syms (mapv symbol mapping-keys)
      [])))

(defn- decode-query-return-maps
  {:malli/schema [:=> [:cat :map :map [:vector [:or :nil :keyword [:vector :keyword] [:map [:seon.db/attribute-position :int]] [:map [:seon.db/pull-spec :map]]]] [:sequential :map]] [:vector :map]]}
  [declarations return-maps attributes result]
  (let [mapping-keys (query-return-map-keys return-maps)
        attributes-by-key (zipmap mapping-keys attributes)]
    (mapv
     (fn [entity-map]
       (reduce-kv
        (fn [decoded mapping-key value]
          (assoc decoded mapping-key
                 (decode-query-field
                  declarations
                  (let [attribute (get attributes-by-key mapping-key)]
                    (if-let [position (::attribute-position attribute)]
                      (get entity-map (nth mapping-keys position))
                      attribute)) value)))
        (empty entity-map)
        entity-map))
     result)))

(defn- native-query-result?
  "True when no find element can carry an encoded value or a decodable map.
   Pull, aggregate and function-bound elements keep the decoding walk."
  {:malli/schema [:=> [:cat :map [:vector [:or :nil :keyword [:map [:seon.db/attribute-position :int]] [:vector :keyword]
                                           [:map [:seon.db/pull-spec :map]]]]] :boolean]}
  [parsed-query attributes]
  (let [bound (into #{}
                    (comp (mapcat :pattern)
                          (filter #(instance? Variable %))
                          (map :symbol))
                    (query-patterns parsed-query))]
    (and (every? nil? attributes)
         (every? #(and (instance? Variable %) (bound (:symbol %)))
                 (parser/find-elements (:qfind parsed-query))))))

(defn- decode-query-result
  "`result` decoded column by column: EDN slots, and each find element in
   `pulls` (index -> logical pull spec) to its logical vectors."
  {:malli/schema [:=> [:cat :map :map :map :seon.schema/value [:map-of :int :map]] :seon.schema/value]}
  [declarations normalized parsed-query result pulls]
  (let [arguments (:args normalized)
        attributes (reduce-kv #(assoc %1 %2 {::pull-spec %3})
                              (query-find-attributes declarations parsed-query arguments) pulls)
        find-clause (:qfind parsed-query)
        return-maps (:qreturnmaps parsed-query)
        decode-result
        (fn [value]
          (cond
            return-maps
            (decode-query-return-maps
             declarations return-maps attributes value)

            (instance? FindRel find-clause)
            (into (empty value)
                  (map #(decode-query-tuple declarations attributes %))
                  value)

            (instance? FindColl find-clause)
            (mapv #(decode-query-field declarations (first attributes) %) value)

            (instance? FindScalar find-clause)
            (decode-query-field declarations (first attributes) value)

            (instance? FindTuple find-clause)
            (some->> value (decode-query-tuple declarations attributes))

            :else
            (decode-attribute-maps declarations value)))]
    (cond
      ;; Every find element is a data-pattern variable over no EDN-encoded
      ;; attribute: each value is a stored datom component the walk would
      ;; return unchanged, so the result is already native. Walking it cost
      ;; 5 ms / 21 MB for 19,232 tuples against Datahike's cached 0.002 ms.
      (native-query-result? parsed-query attributes) result
      (and (map? result) (contains? result :ret)) (update result :ret decode-result)
      :else (decode-result result))))

(defn- lowered-query-request
  "`encoded` read through vector storage, from `parsed-query`, the parse of
   the logical query: every data pattern over a vector attribute is membership
   through cells (`schema.datahike/lower-where`, rule bodies alike) and every
   pull find element, literal or input-bound, reads its physical selector.
   Answers `[request pulls]`, `pulls` mapping each lowered find index to its
   logical pull spec; `encoded` itself when nothing names a vector."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :seon.schema/value]
                  [:tuple :map [:map-of :int :map]]]}
  [projection encoded parsed-query]
  (if-not (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
    [encoded {}]
    (let [query (let [q (:query encoded)] (if (map? q) q (parser.impl/query->map q)))
          arguments (vec (:args encoded))
          inputs (vec (:qin parsed-query))
          input-position (fn [variable]
                           (first (keep-indexed #(when (= variable (some-> %2 :variable :symbol)) %1) inputs)))
          pulls (into {}
                      (keep-indexed
                       (fn [index element]
                         (when (instance? Pull element)
                           (let [pattern (:pattern element)
                                 position (when (instance? Variable pattern) (input-position (:symbol pattern)))
                                 selector (if position (nth arguments position) (:value pattern))]
                             (when-let [[physical spec] (schema.datahike/vector-pull projection selector)]
                               [index {::position position ::selector selector ::physical physical ::pull-spec spec}])))))
                      (parser/find-elements (:qfind parsed-query)))
          rules (first (keep-indexed #(when (instance? RulesVar (:variable %2)) %1) inputs))
          rule-set (when rules (nth arguments rules))
          lowered-rules (when rules
                          (mapv (fn [[head & body]] (into [head] (schema.datahike/lower-where projection {} body)))
                                rule-set))
          where (some->> (:where query) (schema.datahike/lower-where projection (query-input-values parsed-query arguments)))]
      (if (and (empty? pulls) (identical? where (:where query)) (= lowered-rules rule-set))
        [encoded {}]
        [(assoc encoded
                :query (assoc (cond-> query where (assoc :where where))
                              :find (walk/prewalk-replace
                                     (into {} (keep #(when-not (::position %) [(::selector %) (::physical %)])) (vals pulls))
                                     (:find query)))
                :args (cond-> (reduce #(cond-> %1 (::position %2) (assoc (::position %2) (::physical %2)))
                                      arguments (vals pulls))
                        rules (assoc rules lowered-rules)))
         (update-vals pulls ::pull-spec)]))))

(defn- may-read-vectors?
  "Whether a query can read vector storage, judged from the installed schema
   alone, so a query over cardinality-one attributes never derives the
   declarations: cells are installed and the query has a pull, rules,
   `missing?`/`get-else`/`get-some`, or a data pattern whose attribute is
   unknown or installed cardinality-many (as every vector attribute is)."
  {:malli/schema [:=> [:cat :map :map :map] :boolean]}
  [installed parsed-query encoded]
  (boolean
   (and (contains? installed :seon.db.vector/cells)
        (let [bound (query-input-values parsed-query (:args encoded))]
          (or (some #(instance? Pull %) (parser/find-elements (:qfind parsed-query)))
              (some #(instance? RulesVar (:variable %)) (:qin parsed-query))
              (some #{'missing? 'get-else 'get-some} (tree-seq coll? seq (:query encoded)))
              (some (fn [pattern]
                      (let [node (second (:pattern pattern))
                            attributes (cond (instance? Constant node) [(:value node)]
                                             (instance? Variable node) (get bound (:symbol node)))]
                        (or (empty? attributes)
                            (some #(= :db.cardinality/many (get-in installed [% :db/cardinality])) attributes))))
                    (query-patterns parsed-query)))))))

(defn- vector-query-call
  "Run one encoded Datalog `request`: lowered for vector storage once
   ([[lowered-query-request]]), executed with evidence, decoded once
   (`decode-query-result`). Answers `[response result]`, or the refusal of
   `get-else`/`get-some` over a vector attribute. `q` and `replay-read` both
   run through it."
  {:malli/schema [:=> [:cat :map :map :map :seon.schema/value]
                  [:or [:tuple :map :seon.schema/value] :seon.db/error-result]]}
  [declarations logical encoded parsed-query]
  (let [projection (when (may-read-vectors? (::installed-schema declarations) parsed-query encoded)
                     @(::read-projection declarations))
        lowered (if-not projection
                  [encoded {}]
                  (try
                    (lowered-query-request projection encoded parsed-query)
                    (catch clojure.lang.ExceptionInfo cause
                      (if-let [f (::schema.datahike/vector-scalar-function (ex-data cause))]
                        (vary-meta
                         (diagnostic
                          {:seon.error/message (ex-message cause)
                           :seon.db/vector-scalar-function f
                           :seon.db/invalid-read true
                           :seon.error/layer :database-read
                           :seon.error/operation 'seon.db/q
                           :seon.error/expected :seon.db/query
                           :seon.error/offending (::schema.datahike/clause (ex-data cause))
                           :seon.error/data {:seon.db/query (:query logical)}})
                         assoc :seon.error/declared-schema :seon.db/invalid-read-error)
                        (throw cause)))))]
    (if (:seon.error/at lowered)
      lowered
      (let [[request pulls] lowered
            response (d/q-with-evidence request)]
        [response (decode-query-result declarations logical parsed-query (:datahike.query/result response) pulls)]))))

(defn- pull-output-options
  [spec]
  (into {}
        (map (fn [[display-key options]]
               [(or (:as options) display-key)
                [display-key options]]))
        (:attrs spec)))

(declare decode-pull-entity)

(defn- decode-pull-child
  [declarations spec value]
  (cond
    (map? value) (decode-pull-entity declarations spec value)
    (vector? value) (mapv #(decode-pull-child declarations spec %) value)
    (set? value) (into #{} (map #(decode-pull-child declarations spec %)) value)
    (sequential? value)
    (doall (map #(decode-pull-child declarations spec %) value))
    :else value))

(defn- decode-pull-entity
  [declarations spec entity]
  (let [options-by-output (pull-output-options spec)]
    (reduce-kv
     (fn [decoded output-key value]
       (let [[display-key options] (get options-by-output output-key)
             attribute (:attr options)
             subpattern (:subpattern options)]
         (assoc decoded output-key
                (cond
                  subpattern
                  (decode-pull-child declarations subpattern value)

                  (and (keyword? attribute)
                       (= display-key attribute)
                       (edn-encoded? declarations attribute))
                  (decode-attribute-value declarations attribute value)

                  (and (keyword? output-key)
                       (edn-encoded? declarations output-key))
                  (decode-attribute-value declarations output-key value)

                  :else
                  (decode-attribute-maps declarations value)))))
     (empty entity)
     entity)))

(defn- decode-pull-result
  [declarations plan result-key result]
  (let [spec (pull-api/pull-plan-spec plan)]
    (if (= :datahike.pull-many/result result-key)
      (mapv #(when % (decode-pull-entity declarations spec %)) result)
      (when result (decode-pull-entity declarations spec result)))))

;;; ---------------------------------------------------------------------------
;;; Reads over immutable database values
;;; ---------------------------------------------------------------------------

(defn db
  "Current immutable database value for an explicit or ambient connection."
  {:malli/schema
   [:function
    ;; A SUPPLIER'S DECLARED RETURN IS READ BY `seon.call-preparation`, which
    ;; refuses a default whose declaration is wider than the argument it fills.
    ;; Widening these three to the full declared-schema union made every prepared
    ;; `:seon.db/db` and `:seon.db/connection` inadmissible, and `seon.db/diff`
    ;; then reported `:seon.db/database-input-absent` (measured 2026-09-18).
    [:=> [:cat]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]
    [:=> [:cat [:or :seon.db/connection :seon.error/value]]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]]}
  ([]
   (current-database-value))
  ([connection]
   (if-not (connection-object? connection)
     (refused-read connection 'seon.db/db)
     (resolve-database-value connection))))

;;; ---------------------------------------------------------------------------
;;; Suppliers — the two database values call preparation can supply
;;; ---------------------------------------------------------------------------
;;;
;;; These are the functions the two `:seon.call-preparation` entities name. Each
;;; takes exactly one argument — the environment the call is running in — and
;;; returns its value or a flat error. They read NO dynamic var (seon-env PRD
;;; ruling 2), so a closure handed to a virtual thread still resolves its own
;;; cluster's custody: the environment travels with the code on the sci ctx,
;;; and the hook hands it to the supplier.

(defn- unsupplied-custody-error
  {:malli/schema [:=> [:cat :string] :seon.schema/validation-refusal]}
  [needed]
  (schema-refusal
   'seon.db/unsupplied-custody-error :seon.db/connection nil
   (str "This call's environment carries no cluster connection, so " needed
        " cannot be supplied. Pass the database value or connection "
        "explicitly, as in (db/pull db selector eid).")
   {::needed needed}))

(defn supplied-database-value
  "Supply an explicitly scoped database value, or the connection's current value.

  A computation carrying a snapshot keeps that basis across its nested
  calls. Without a supplied snapshot, preparation dereferences the live
  connection as before. Explicit function arguments always win."
  {:malli/schema
   [:=> [:cat :seon.env/environment]
    [:or :seon.db/database-value :seon.error/value]]}
  [environment]
  (if-not (env/environment? environment)
    (unsupplied-custody-error "a database value")
    (let [database
          (or (:seon.db/db environment)
              (let [connection (:seon.db/connection environment)]
                (if (nil? connection)
                  (unsupplied-custody-error "a database value")
                  (try
                    (d/db connection)
                    (catch clojure.lang.ExceptionInfo cause
                      (dependency-error 'seon.db/db cause))))))
          state (or (:seon.sci.eval/projection-state environment)
                    (:seon.sci.eval/projection-state (meta database))
                    (when (:seon.schema/projection environment)
                      (env/environment-state environment)))]
      (carry-projection-state database state))))

(defn supplied-connection
  "This environment's live branch connection."
  {:malli/schema
   [:=> [:cat :seon.env/environment]
    [:or :seon.db/connection :seon.error/value]]}
  [environment]
  (if-not (env/environment? environment)
    (unsupplied-custody-error "a connection")
    (or (:seon.db/connection environment)
        (unsupplied-custody-error "a connection"))))

(defn- source-argument-error
  {:malli/schema [:=> [:cat [:sequential :map] [:vector :seon.schema/value]] [:or :nil :seon.error/value]]}
  [source-bindings arguments]
  (some (fn [source]
          (let [position (:datahike.query.source/argument-position source)]
            (when (< position (count arguments))
              (let [argument (nth arguments position)]
                (when (:seon.db/invalid-read argument)
                  argument)))))
        source-bindings))

(defn- query-argument-message
  [query-form arguments]
  (str "seon.db/q :in " (pr-str (get query-form :in '[$]))
       " does not match supplied arguments "
       (pr-str (mapv #(if (db.utils/db? %) 'database %) arguments))
       ". Every source input must be a database value. "
       "Use (seon.db/q query input ...) with the database elided, "
       "or (seon.db/q database query input ...) with an explicit database first."))

(defn- query-input-shape-error
  {:malli/schema [:=> [:cat :map [:sequential :seon.schema/value]] :seon.error/base]}
  [query-form arguments]
  (vary-meta (diagnostic
   {:seon.error/message (query-argument-message query-form arguments)
    :seon.db/invalid-read true
    :seon.error/layer :database-read
    :seon.error/operation 'seon.db/q
    :seon.error/expected (get query-form :in '[$])
    ;; a database (or any other world) among the arguments is its reference
    :seon.error/offending (error.refusal/references
                           (some-> (some #(when (db.utils/db? %) %) arguments) carried-projection)
                           (vec arguments))
    :seon.error/data (merge {::query query-form} {:seon.error/member :in})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))

(defn- query-input-position
  "Return the omitted $ position, :explicit, or :invalid from parsed bindings."
  {:malli/schema [:=> [:cat :boolean :map [:or :nil [:sequential :seon.schema/value]]] [:or :int [:enum :explicit :invalid]]]}
  [explicit-database? query-form arguments]
  (let [arguments (vec arguments)
        input-count (d/query-input-count query-form)
        sources (d/query-source-bindings query-form)
        dollar (first (filter #(= '$ (:datahike.query.source/symbol %)) sources))
        position (:datahike.query.source/argument-position dollar)
        omitted? (and dollar (= input-count (inc (count arguments)))
                      (or explicit-database?
                          (not (db.utils/db? (get arguments position)))))
        valid-sources?
        (every? (fn [source]
                  (let [index (:datahike.query.source/argument-position source)
                        value (get arguments (if (and omitted? (> index position))
                                               (dec index) index))]
                    (or (and omitted? (= index position))
                        (db.utils/db? value) (:seon.db/invalid-read value))))
                sources)]
    (cond
      (not valid-sources?) :invalid
      omitted? position
      (= input-count (count arguments)) :explicit
      :else :invalid)))

(defn- aligned-query-arguments
  {:malli/schema [:=> [:cat [:or :nil :seon.db/database-value] :map [:or :nil [:sequential :seon.schema/value]]] [:or [:vector :seon.schema/value] :seon.db/error-result]]}
  [explicit-database query-form arguments]
  (let [arguments (vec arguments)]
    (or (source-argument-error (d/query-source-bindings query-form) arguments)
        (let [position (query-input-position (some? explicit-database) query-form arguments)]
          (case position
            :explicit arguments
            :invalid (query-input-shape-error query-form arguments)
            (let [database (or explicit-database (current-database-value))]
              (if-not (db.utils/db? database)
                database
                (into (conj (subvec arguments 0 position) database)
                      (subvec arguments position)))))))))

(defn- query-call-valid?
  "Whether `q`'s supplied inputs match the parsed query's `:in`.

  A result that is `q`'s own dependency refusal already answers the call:
  the body caught Datahike's throwable (a malformed query among them) and
  carries its whole cause, so the guard has no input count to check. Any
  other failure while parsing here is not health: it escapes the guard."
  {:malli/schema [:=> [:cat [:tuple [:sequential :seon.schema/value] :seon.schema/value]]
                  :boolean]}
  [[call-arguments result]]
  (let [[query-or-database & arguments] call-arguments]
    (cond
      ;; Not one of the union's database or query members: its error member.
      (not (or (db.utils/db? query-or-database) (vector? query-or-database) (string? query-or-database)
                (:find query-or-database) (:query query-or-database)))
      true

      (and (map? result) (::invalid-read result)
           (= 'seon.db/q (:seon.db/refused-read-operation result))
           (:seon.error/exception-class result))
      true

      :else
      (let [explicit? (db.utils/db? query-or-database)
            query-input (if explicit? (first arguments) query-or-database)
            supplied (if explicit? (rest arguments) arguments)
            normalized (query/normalize-q-input query-input supplied)]
        (and (not (and (map? query-input) (contains? query-input :args) (seq supplied)))
             (not= :invalid (query-input-position explicit? (:query normalized) (:args normalized))))))))

(defn- query-guard-message
  "The refusal sentence for a `q` call whose inputs do not match `:in`.
  Reached only after `query-call-valid?` parsed these inputs and answered
  false, so parsing here cannot fail differently."
  {:malli/schema [:=> [:cat [:map [:value [:tuple [:sequential :seon.schema/value]
                                             :seon.schema/value]]]
                        :seon.schema/value]
                  :string]}
  [{value :value} _options]
  (let [[[head & tail] result] value]
    (or (when (:seon.db/invalid-read result) (:seon.error/message result))
        (let [explicit? (db.utils/db? head)
              query-input (if explicit? (first tail) head)
              supplied (if explicit? (rest tail) tail)
              normalized (query/normalize-q-input query-input supplied)]
          (query-argument-message (:query normalized)
                                  (into (vec (:args normalized))
                                        (when (and (map? query-input) (:args query-input)) supplied)))))))

(defn q
  "Run a Datalog query over explicit inputs or the current database value."
  {:malli/schema
   [:=> [:catn [:seon.db/query-or-database [:or :seon.db/database-value :seon.error/value :seon.db/query :seon.db/query-args]] [:seon.db/arguments [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.", :gen/elements [[]]} :seon.schema/value]]] [:or :seon.schema/value :seon.db/invalid-read-error] [:fn #:error{:message "The supplied arguments must match the query's :in (default [$]); every source input must be a database value. Use (seon.db/q query input ...) with $ elided, or (seon.db/q database query input ...) with the database first.", :fn seon.db/query-guard-message} seon.db/query-call-valid?]]}
  [query-or-database & arguments]
  (if-not (or (db.utils/db? query-or-database) (vector? query-or-database) (string? query-or-database)
                (:find query-or-database) (:query query-or-database))
    (refused-read query-or-database 'seon.db/q)
    (let [explicit-database? (db.utils/db? query-or-database)
        query-input
        (if explicit-database?
          (first arguments)
          query-or-database)
        argument-inputs
        (if explicit-database?
          (rest arguments)
          arguments)]
    (try
      ;; This disambiguates a Datalog map query from Datahike's argument map
      ;; before Seon decides where the ambient database belongs.
      (let [normalized (query/normalize-q-input query-input argument-inputs)
            aligned
            (if (and (map? query-input) (contains? query-input :args) (seq argument-inputs))
              (query-input-shape-error (:query normalized)
                                       (into (vec (:args normalized)) argument-inputs))
              (aligned-query-arguments
               (when explicit-database? query-or-database)
               (:query normalized)
               (:args normalized)))]
        (if-not (vector? aligned)
          (refused-read aligned 'seon.db/q)
          (let [request (assoc normalized :args aligned)
                parsed-query (query/memoized-parse-query (:query request))]
            (or (malformed-query-pattern-error request parsed-query)
                (query-attribute-error request parsed-query)
                (with-declarations
                  (some #(when (db.utils/db? %) %) aligned) 'seon.db/q
                  ;; A RELATION-ONLY QUERY HAS NO DATABASE SOURCE AT ALL. Every
                  ;; binding came from the caller's own relation, so there is no
                  ;; stored value to decode and no installed schema to miss.
                  ;; Refusing there is the mirror of the defect this seam fixes:
                  ;; it reads "nothing to decode" as "cannot decode".
                  :seon.db/relation-only
                  (fn [declarations]
                    (or (query-value-type-error declarations request parsed-query)
                        (let [answer (vector-query-call declarations request
                                                        (encode-query-request declarations request parsed-query)
                                                        parsed-query)]
                          (if-not (vector? answer)
                            answer
                            (let [[response result] answer]
                              (append-query-evidence! request response result)
                              result))))))))))
        (catch clojure.lang.ExceptionInfo cause
          (when explicit-database?
            (append-database-evidence! query-or-database :all))
          (dependency-error 'seon.db/q cause))))))

(defn- missing-pull-selector-error
  {:malli/schema [:=> [:cat :qualified-symbol [:sequential :seon.schema/value]] [:or :nil :seon.error/base]]}
  [public-operation arguments]
  (when (and (= 1 (count arguments))
             (map? (first arguments))
             (not (contains? (first arguments) :selector)))
    (let [request (first arguments)]
      (vary-meta (diagnostic
       {:seon.error/message (str public-operation " argument maps require :selector.")
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/operation public-operation
        :seon.error/expected [:map [:selector :seon.db/pull-selector]]
        :seon.error/offending request
        :seon.error/data {:seon.error/member :selector :seon.error/source request}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))))

(defn- pull-entity-id
  [arguments]
  (if (map? (first arguments))
    (:eid (first arguments))
    (second arguments)))

(defn- validate-pulled-value
  "Validate one pulled map against the form derived for (schema-key, selector).

   This is the derived pulled form of the pulled-form study, not a
   hand-written mirror: `seon.schema/pulled-form-in` derives it from the
   entity schema and the exact literal selector Datahike was handed, and a
   refused derivation refuses the read."
  {:malli/schema
   [:=> [:cat :seon.schema/projection :qualified-symbol
         :seon.schema/registry-key :seon.db/pull-selector :map]
    [:or :map :seon.db/error-result]]}
  [projection public-operation schema-key selector value]
  (let [projected (schema/projection-with-pulled-form-in
                   projection schema-key selector)]
    (if-not (:seon.schema.projection/registry projected)
      ;; THE DERIVATION REFUSED, NOT THE READ. `pulled-form-in` declines
      ;; recursion and wildcard component cycles, so a `'[*]` pull over
      ;; `:seon.fn/fn` (whose bindings revisit `:seon.fn.binding/row`) has no
      ;; derivable form. Returning that refusal turned a gap in OUR machinery
      ;; into a failed read for a whole entity family — the same disease one
      ;; level up. The value passes through, and the gap is a finding.
      value
      (let [derived-key (schema/pulled-schema-key schema-key selector)
            validator (schema/projection-validator projected derived-key)]
        (if (validator value)
          value
          (vary-meta (diagnostic
           {:seon.error/message (str public-operation
                 " returned a value that does not satisfy the derived pulled form "
                 derived-key " of " schema-key ".")
            :seon.db.read/invalid-pulled-result derived-key
            :seon.db/invalid-read true
            :seon.error/layer :database-read
            :seon.error/operation public-operation
            :seon.error/expected (get (:seon.schema.projection/forms projected) derived-key)
            :seon.error/offending value
            :seon.error/data {:seon.schema/key schema-key :seon.db/pull-selector selector}}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))))))

(defn- validate-pulled-result
  "Check pulled values only against the schema explicitly named by the caller."
  {:malli/schema
   [:=> [:cat :seon.schema/projection :qualified-symbol
         [:or :nil :seon.schema/registry-key] :seon.db/pull-selector
         [:or :nil :seon.db/pulled-entity
          [:vector [:or :nil :seon.db/pulled-entity]]]]
    [:or :nil :seon.db/pulled-entity
     [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/error-result]]}
  [projection public-operation declared-key selector value]
  (if-not declared-key
    value
    (or (reduce
         (fn [_ element]
           (when element
             (let [checked (validate-pulled-value
                            projection public-operation declared-key selector element)]
               (when (:seon.db.read/invalid-pulled-result checked)
                 (reduced checked)))))
         nil
         (if (vector? value) value [value]))
        value)))

(defn- pull-budget-error
  "Preserve Datahike's resource refusal without claiming a partial result."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable]
                  :seon.db/pull-budget-error]}
  [operation cause]
  (merge (error.refusal/diagnostic
          (cond-> {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.db/database-read
                   :seon.error/operation operation
                   :seon.error/throwable cause}
            (ex-message cause) (assoc :seon.error/message (ex-message cause))))
         (select-keys (ex-data cause)
                      [:datahike.budget/name :datahike.budget/observed
                       :datahike.budget/allowed])))

(defn- pull-call
  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.db/error-result] [:sequential :seon.schema/value] [:function [:=> [:cat :seon.db/database-value :map] :map] [:=> [:cat :seon.db/database-value :seon.db/pull-selector :seon.schema/value] :map]] :keyword :qualified-keyword :qualified-symbol] [:or :nil :seon.db/pulled-entity [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/error-result :seon.db/pull-budget-error]]}
  [database arguments operation operation-key result-key public-operation]
  (if-not (db.utils/db? database)
    (refused-read database public-operation)
    (or (missing-pull-selector-error public-operation arguments)
        (let [many? (= :pull-many operation-key)
              options (first arguments)
              expected (if many? :seon.db/pull-many-options :seon.db/pull-options)
              valid? (if (= 1 (count arguments))
                       (and (map? options) (vector? (:selector options))
                            (some? (get options (if many? :eids :eid))))
                       (and (= 2 (count arguments))
                            (vector? options) (not (map? (second arguments)))))]
          (when-not valid?
            (vary-meta (diagnostic
             {:seon.error/message (str public-operation " received invalid arguments " (pr-str arguments)
                   ". Use (" public-operation " selector " (if many? "eids" "eid")
                   ") with the database elided, or (" public-operation
                   " database selector " (if many? "eids" "eid")
                   "). Argument maps require :selector and " (if many? ":eids" ":eid") ".")
              :seon.db/invalid-read true
              :seon.error/layer :database-read
              :seon.error/operation public-operation
              :seon.error/expected expected
              :seon.error/offending arguments
              :seon.error/data (merge {::operation operation-key} {:seon.error/member :args})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error)))
        (when (#{'seon.db/pull 'seon.db/entity} public-operation)
          (lookup-ref-error public-operation database
                            (pull-entity-id arguments)))
        (try
      (with-declarations database public-operation
        (fn [declarations]
          (let [projection @(::read-projection declarations)
                options (when (map? (first arguments)) (first arguments))
                declared-key (:schema-key options)
                arguments (cond-> (vec arguments)
                            options (update 0 dissoc :schema-key))
                selector (if (map? (first arguments))
                           (:selector (first arguments))
                           (first arguments))
                answer (pull-logical database declarations projection operation arguments result-key)]
            (if-not (vector? answer)
              answer
              (let [[physical response result] answer]
                (append-pull-evidence! database physical operation-key response result
                                       (when-not (= physical arguments) selector))
                (validate-pulled-result projection public-operation declared-key selector result))))))
      (catch clojure.lang.ExceptionInfo cause
        (append-database-evidence! database :all)
        (if (:datahike/budget-exceeded (ex-data cause))
          (pull-budget-error public-operation cause)
          (dependency-error public-operation cause)))))))

(defn- pull-call-valid?
  {:malli/schema [:=> [:cat [:tuple [:sequential :seon.schema/value] :seon.schema/value]] :boolean]}
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (or (vector? head) (:selector head)) arguments tail)]
    (or (and (= 1 (count inputs)) (map? (first inputs)))
        (and (= 2 (count inputs)) (vector? (first inputs))
             (not (map? (second inputs)))))))

(defn pull
  "Pull one entity over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-options]
     [:or :nil :seon.db/pulled-entity :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull selector eid), (seon.db/pull database selector eid), or one {:selector selector :eid eid} argument map."} seon.db/pull-call-valid?]]
    [:=> [:cat
          [:or :seon.db/database-value :seon.error/value
           :seon.db/pull-selector]
          [:or :seon.db/pull-options :seon.db/entity-id]]
     [:or :nil :seon.db/pulled-entity :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull selector eid), (seon.db/pull database selector eid), or one {:selector selector :eid eid} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      :seon.db/entity-id]
     [:or :nil :seon.db/pulled-entity :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull selector eid), (seon.db/pull database selector eid), or one {:selector selector :eid eid} argument map."} seon.db/pull-call-valid?]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              pull-plan-with-evidence
              :pull
              :datahike.pull/result
              'seon.db/pull))
  ([database-or-selector options-or-eid]
   (if-not (vector? database-or-selector)
     (pull-call database-or-selector
                [options-or-eid]
                pull-plan-with-evidence
                :pull
                :datahike.pull/result
                'seon.db/pull)
     (pull-call (current-database-value)
                [database-or-selector options-or-eid]
                pull-plan-with-evidence
                :pull
                :datahike.pull/result
                'seon.db/pull)))
  ([database selector entity-id]
   (pull-call database
              [selector entity-id]
              pull-plan-with-evidence
              :pull
              :datahike.pull/result
              'seon.db/pull)))

(defn pull-many
  "Pull aligned entities over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-many-options]
     [:or [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull-many selector eids), (seon.db/pull-many database selector eids), or one {:selector selector :eids eids} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat
      [:or :seon.db/database-value :seon.error/value
       :seon.db/pull-selector]
      [:or :seon.db/pull-many-options
       [:sequential :seon.db/entity-id]]]
     [:or [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull-many selector eids), (seon.db/pull-many database selector eids), or one {:selector selector :eids eids} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      [:sequential :seon.db/entity-id]]
     [:or [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/invalid-read-error :seon.db/pull-budget-error]
     [:fn {:error/message "Use (seon.db/pull-many selector eids), (seon.db/pull-many database selector eids), or one {:selector selector :eids eids} argument map."} seon.db/pull-call-valid?]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              pull-many-plan-with-evidence
              :pull-many
              :datahike.pull-many/result
              'seon.db/pull-many))
  ([database-or-selector options-or-eids]
   (if-not (vector? database-or-selector)
     (pull-call database-or-selector
                [options-or-eids]
                pull-many-plan-with-evidence
                :pull-many
                :datahike.pull-many/result
                'seon.db/pull-many)
     (pull-call (current-database-value)
                [database-or-selector options-or-eids]
                pull-many-plan-with-evidence
                :pull-many
                :datahike.pull-many/result
                'seon.db/pull-many)))
  ([database selector entity-ids]
   (pull-call database
              [selector entity-ids]
              pull-many-plan-with-evidence
              :pull-many
              :datahike.pull-many/result
              'seon.db/pull-many)))

(defn- entity-call
  [database entity-id]
  ;; Wildcard pull is Datahike's eager ordinary-data form: component refs
  ;; expand recursively and ordinary refs remain plain {:db/id ...} maps.
  (pull-call database
             [['*] entity-id]
             pull-plan-with-evidence
             :pull
             :datahike.pull/result
             'seon.db/entity))

(defn entity
  "Eager ordinary data for one entity in an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/entity-id]
     [:or :nil :seon.db/pulled-entity :seon.db/error-result :seon.db/pull-budget-error]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/entity-id]
     [:or :nil :seon.db/pulled-entity :seon.db/error-result :seon.db/pull-budget-error]]]}
  ([entity-id]
   (entity-call (current-database-value) entity-id))
  ([database entity-id]
   (entity-call database entity-id)))

(defn- datom->data
  [declarations database datom]
  (let [stored-attribute (:a datom)
        attribute (:ident (db.utils/attr-info database stored-attribute))
        value (:v datom)]
    {:e (:e datom)
     :a stored-attribute
     :v (if (and (keyword? attribute)
                 (edn-encoded? declarations attribute))
          (decode-attribute-value declarations attribute value)
          (decode-attribute-maps declarations value))
     :tx (:tx datom)
     :added (:added datom)}))

(defn- decode-index-page
  [declarations database page]
  (update page :datahike.index-page/datoms
          (fn [page-datoms]
            (mapv (fn [datom]
                    (let [decoded (datom->data declarations database datom)]
                      (cond-> decoded
                        (not= (:v datom) (:v decoded))
                        (assoc :seon.db/stored-value (:v datom)))))
                  page-datoms))))

(defn- index-read-dependencies
  "The dependency plan and index pattern of one read of `index` under the
  prefix `components`: the attribute the prefix names, else every attribute.
  A cursor or limit narrows the read inside this prefix, so the prefix's
  pattern is a conservative cover."
  {:malli/schema
   [:=> [:cat :seon.db/database-value [:enum :eavt :aevt :avet] [:sequential :seon.schema/value]]
    [:map [:datahike.read/dependency-plan :seon.db/read-dependency-plan]
     [:seon.db/read-index-pattern :map]]]}
  [database index components]
  (let [pattern-keys (case index
                       :eavt [:seon.db/pattern-entity :seon.db/pattern-attribute :seon.db/pattern-value]
                       :aevt [:seon.db/pattern-attribute :seon.db/pattern-entity :seon.db/pattern-value]
                       :avet [:seon.db/pattern-attribute :seon.db/pattern-value :seon.db/pattern-entity])
        supplied (zipmap pattern-keys components)
        attribute (some->> (:seon.db/pattern-attribute supplied)
                           (db.utils/attr-info database) :ident)
        entity (some->> (:seon.db/pattern-entity supplied)
                        (db.utils/entid database))
        value (:seon.db/pattern-value supplied)
        value (if (and attribute (db.utils/ref? database attribute) (some? value))
                (db.utils/entid database value)
                value)]
    {:datahike.read/dependency-plan
     {:datahike.query.dependency/sources
      [{:datahike.query.source/symbol '$
        :datahike.query.source/argument-position 0
        :datahike.query.source/attributes (if attribute #{attribute} :all)}]}
     :seon.db/read-index-pattern
     (cond-> {}
       attribute (assoc :seon.db/pattern-attribute attribute)
       entity (assoc :seon.db/pattern-entity entity)
       (some? value) (assoc :seon.db/pattern-value value))}))

(defn- datoms-call
  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value] [:or :nil [:sequential :seon.schema/value]]] [:or :seon.db/datoms :seon.db/invalid-read-error]]}
  [database arguments]
  (if-not (db.utils/db? database)
    (refused-read database 'seon.db/datoms)
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (with-declarations database 'seon.db/datoms
       (fn [declarations]
        (let [options (first arguments)
              index (if (map? options) (:index options) options)
              components (if (map? options) (:components options) (rest arguments))
              ;; Datahike answers an AVET read of an attribute it does not
              ;; index with no datoms, which a reader takes for absence.
              _ (when (and (= :avet index) (keyword? (first components))
                           (not (db.utils/indexing? database (first components))))
                  (throw (ex-info (str "An AVET read of " (first components) " finds nothing: the attribute is not indexed.")
                                  {:seon.error/expected :db/index :seon.error/offending (first components)})))
              result (mapv #(datom->data declarations database %)
                           (apply d/datoms database arguments))
              {plan :datahike.read/dependency-plan pattern :seon.db/read-index-pattern}
              (index-read-dependencies database index components)]
          (append-read-evidence!
           (cond-> {:seon.db/db database
                    :seon.db/source-argument-position 0
                    :datahike.read/dependency-plan plan}
             (instance? DB database)
             (assoc :seon.db/read-index-patterns [pattern])))
          result)))
      (catch clojure.lang.ExceptionInfo cause
        (append-database-evidence! database :all)
        (dependency-error 'seon.db/datoms cause)))))

(defn- datoms-call-valid?
  {:malli/schema [:=> [:cat [:tuple [:sequential :seon.schema/value] :seon.schema/value]] :boolean]}
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (or (keyword? head) (:index head)) arguments tail)
        [index & components] inputs]
    (if (map? index)
      (empty? components)
      (boolean (and (#{:eavt :aevt :avet} index)
                    (<= (count components) 4))))))

(defn datoms
  "Eager ordinary datoms from an explicit or current database value."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value :seon.db/index-lookup :keyword] [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike index components include arbitrary attribute values. The function guard checks index, component count and argument-map exclusivity.", :gen/elements [[]]} :seon.schema/value]] [:or :seon.db/datoms :seon.db/invalid-read-error] [:fn #:error{:message "Use (seon.db/datoms index & components) or (seon.db/datoms database index & components); an index argument map takes no trailing arguments, and an index has at most four components."} seon.db/datoms-call-valid?]]}
  [database-or-index & arguments]
  (if (or (keyword? database-or-index) (:index database-or-index))
    (datoms-call (current-database-value) (cons database-or-index arguments))
    (datoms-call database-or-index arguments)))

(defn index-page
  "One bounded, decoded page in native Datahike index order."
  {:malli/schema
   [:function
    [:=> [:catn [::options ::index-page-options]]
     [:or ::index-page-result :seon.db/error-result]]
    [:=> [:catn [::database [:or ::database-value :seon.error/value]]
          [::options ::index-page-options]]
     [:or ::index-page-result :seon.db/error-result]]]}
  ([options] (index-page (current-database-value) options))
  ([database options]
   (if-not (db.utils/db? database)
     database
     (try
       (with-declarations database 'seon.db/index-page
        (fn [declarations]
          (let [page (decode-index-page declarations
                                        database
                                        (d/index-page database options))]
            ;; The page reads one index prefix, so it depends on that
            ;; prefix's attribute only. It carries no index pattern: a change
            ;; under the prefix may leave the bounded page itself unchanged,
            ;; and the recorded request replays to answer exactly that.
            (append-read-evidence!
             {:seon.db/db database
              :seon.db/source-argument-position 0
              :datahike.read/dependency-plan
              (:datahike.read/dependency-plan
               (index-read-dependencies database (:index options) (:components options)))
              :seon.db/read-request {:seon.db/read-operation :index-page
                                     :seon.db/index-page-options options}
              :seon.db/read-result page})
            page)))
       (catch clojure.lang.ExceptionInfo cause
         (append-database-evidence! database :all)
         (dependency-error 'seon.db/index-page cause))))))

(defn- database-view
  {:malli/schema
   [:=> [:cat [:or [:=> [:cat :seon.db/database-value] :seon.db/database-value]
                   [:=> [:cat :seon.db/database-value :seon.db/time-point]
                    :seon.db/database-value]]
         [:or :seon.db/database-value :seon.error/value]
         [:vector {:max 1} :seon.db/time-point]]
    [:or :seon.db/database-value :seon.db/invalid-read-error]]}
  [operation database arguments]
  (cond
    (not (db.utils/db? database))
    (refused-read database 'seon.db/database-view)

    (not (dbi/-temporal-index? database))
    (do
      (append-database-evidence! database :all)
      (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.db/database-read 'seon.db/database-view
       {:seon.error/message "The database does not retain temporal indices."
        :seon.db/invalid-read true
        :seon.db/refused-read-operation 'seon.db/database-view
        :seon.config/error-key :seon.config.db/keep-history?
        :seon.error/expected-shape (id/digest 64 [:= true])
        :seon.error/expected [:= true]
        :seon.error/offending false
        :seon.error/data (merge {::operation ::temporal-read
                          :seon.config.db/keep-history? false} {:seon.error/layer :database-read :seon.error/source {:seon.config.db/keep-history? false}})}) assoc :seon.error/declared-schema :seon.db/invalid-read-error))

    :else
    ;; Building a view reads no datom: each read on the view records its own
    ;; evidence against it. Recording `:all` on the argument made a form that
    ;; opened an as-of view stale on every later commit of its branch.
    (try
      (vary-meta (apply operation database arguments) merge (meta database))
      (catch clojure.lang.ExceptionInfo cause
        (append-database-evidence! database :all)
        (dependency-error 'seon.db/temporal-read cause)))))

(defn- database-identity
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/database-value] :seon.schema/value] :qualified-symbol [:or :seon.db/database-value :seon.error/value]] [:or :seon.schema/value :seon.db/error-result]]}
  [operation operation-name database]
  (if-not (db.utils/db? database)
    database
    (try
      (let [result (operation database)]
        (append-database-evidence! database :all)
        result)
      (catch clojure.lang.ExceptionInfo cause
        (append-database-evidence! database :all)
        (dependency-error operation-name cause)))))

(defn commit-id
  "Commit ID of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :nil :uuid :seon.db/error-result]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :uuid :seon.db/error-result]]]}
  ([]
   (database-identity d/commit-id 'seon.db/commit-id (current-database-value)))
  ([database]
   (database-identity d/commit-id 'seon.db/commit-id database)))

(defn committed-value-identity
  "Process-local identity of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :nil :map :seon.db/error-result]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :map :seon.db/error-result]]]}
  ([]
   (database-identity d/committed-value-identity
                      'seon.db/committed-value-identity
                      (current-database-value)))
  ([database]
   (database-identity d/committed-value-identity
                      'seon.db/committed-value-identity
                      database)))

(defn history
  "Historical view of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]]}
  ([]
   (database-view d/history (current-database-value) []))
  ([database]
   (database-view d/history database [])))

(defn as-of
  "Database view at a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]]}
  ([time-point]
   (database-view d/as-of (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/as-of database [time-point])))

(defn since
  "Database view since a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.db/error-result]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
     [:or :seon.db/database-value :seon.db/error-result]]]}
  ([time-point]
   (database-view d/since (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/since database [time-point])))

;;; ---------------------------------------------------------------------------
;;; Identity-aware result differences
;;; ---------------------------------------------------------------------------

(defn- value-changes
  [before after]
  (let [edits (editscript/get-edits
               (editscript/diff before after
                                {:algo :quick :str-diff :none
                                 :vec-timeout Long/MAX_VALUE}))
        ;; Paths name coordinates in snapshots, not shifting edit indices.
        ;; A sequence insertion/deletion replaces its containing sequence.
        sequence-paths
        (into #{} (keep (fn [[path operation]]
                         (when (and (seq path) (#{:+ :-} operation)
                                    (sequential? (print/value-at before (pop path))))
                           (pop path)))) edits)
        paths (distinct (concat sequence-paths (map first edits)))
        covered? (fn [path]
                   (some #(and (< (count %) (count path))
                               (= % (subvec path 0 (count %)))) sequence-paths))
        operations (into {} (map (juxt first second)) edits)]
    (into (sorted-map-by print/compare-values)
          (comp (remove covered?)
                (map (fn [path]
                       [path (if (and (= :- (get operations path))
                                      (not (contains? sequence-paths path)))
                               {:seon.db.diff/removed? true}
                               {:seon.db.diff/after (print/value-at after path)})])))
          paths)))

(defn apply-diff
  "Apply plain changed paths to a previously shown value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A prior shown Clojure value may be scalar, nil or any collection; editscript paths determine the changed subvalues.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.db.diff/paths] :seon.schema/value]}
  [before changes]
  (editscript/patch
   before
   (editscript.edit/edits->script
    (mapv (fn [[path change]]
            (if (:seon.db.diff/removed? change)
              [path :-]
              [path :r (:seon.db.diff/after change)]))
          changes))))

(defn diff
  "Compare previously shown and current values by changed paths."
  {:malli/schema [:=> [:cat :seon.db.diff/values-request] :seon.db.diff/paths]}
  [{before :seon.db.diff/before after :seon.db.diff/after}]
  (value-changes before after))

;;; ---------------------------------------------------------------------------
;;; Writes through the one synchronous transaction boundary
;;; ---------------------------------------------------------------------------

(defn- jdk-integers->long
  "Normalize submitted storage data, preserving transaction-function arguments.
   A function receives its in-memory request unchanged; the same conversion
   handles its returned transaction data before Datahike validates it."
  {:malli/schema [:=> [:cat :seon.store/transaction] :seon.store/transaction]}
  [transaction]
  (letfn [(normalize [value]
            (walk/postwalk #(if (instance? Integer %) (long %) %) value))
          (entries [values]
            (if-not (sequential? values)
              values
              (mapv (fn [entry]
                      (if (and (sequential? entry) (= :db.fn/call (first entry)))
                        (let [[operation f & args] entry]
                          (into [operation (fn [database & supplied]
                                             (entries (apply f database supplied)))]
                                args))
                        (normalize entry)))
                    values)))]
    (if (map? transaction)
      (cond-> transaction
        (contains? transaction :tx-data) (update :tx-data entries)
        (contains? transaction :tx-meta) (update :tx-meta normalize))
      (entries transaction))))

(def ^:private entity-identity-query
  '[:find ?identity-attribute ?identity-value
    :in $ ?entity
    :where
    [?schema :db/ident ?identity-attribute]
    [?schema :db/unique :db.unique/identity]
    [?entity ?identity-attribute ?identity-value]])

(defn- entity-identity
  [database entity-id]
  (some->> (d/q entity-identity-query database entity-id)
           (sort-by (comp str first))
           first
           vec))

(defn- conflict-value
  [database attribute value]
  (if (db.utils/ref? database attribute)
    (or (entity-identity database value) value)
    value))

(defn- unique-conflict
  [database data]
  (when (= :transact/unique (:error data))
    (let [attribute (:attribute data)
          datom (:datom data)
          stored-value (:v datom)
          owner
          (d/q '[:find ?owner .
                 :in $ ?attribute ?value
                 :where [?owner ?attribute ?value]]
               database attribute stored-value)]
      (cond-> {::conflict-attribute attribute
               ::conflict-value
               (conflict-value database attribute stored-value)}
        owner (assoc ::conflict-owner
                     (or (entity-identity database owner) owner))))))

(defn- rejection-message
  [conflict throwable]
  (if conflict
    (str "Transaction rejected: "
         (pr-str (::conflict-attribute conflict))
         " value " (pr-str (::conflict-value conflict))
         " is already held by "
         (pr-str (or (::conflict-owner conflict) "an existing entity"))
         ".")
    (loop [failure throwable]
      (if-let [cause (ex-cause failure)]
        (recur cause)
        (or (ex-message failure) "Transaction rejected.")))))

(defn- rejected-value
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.store/transaction :seon.error/throwable :map]
    :seon.db.write/validation-refusal]}
  [database transaction throwable data]
  (let [;; Naming the conflicting owner is a further read. When it fails the
        ;; rejection still stands; the lookup's own whole cause rides with it.
        [conflict lookup-failure]
        (try
          [(unique-conflict database data) nil]
          (catch Throwable lookup
            [nil (seon.error.refusal/diagnostic (java.util.Date.) :seon.db/database-read 'seon.db/unique-conflict
                  {:seon.error/message (or (ex-message lookup) (.getName (class lookup)))
                   :seon.error/throwable lookup})]))]
    (write-observation
     database transaction
     (error.refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.db/database-write
       :seon.error/operation 'seon.db/transact!
       :seon.error/throwable throwable
       :seon.error/message (rejection-message conflict throwable)
       :seon.error/data
       (cond-> (merge data conflict)
         lookup-failure
         (assoc ::conflict-lookup-failure lookup-failure)
         conflict
         (assoc :seon.error/operation 'seon.db/transact!
                :seon.error/problems
                [{:seon.error/argument "transaction data"
                  :seon.error/path [(::conflict-attribute conflict)]
                  :seon.error/expected
                  (get (dbi/-schema database) (::conflict-attribute conflict))
                  :seon.error/expected-description "a value satisfying the attribute's uniqueness constraint"
                  :seon.error/offending conflict
                  :seon.error/actual-description "a value already assigned to an entity"
                  :seon.error/fix "Update the existing owner, or choose an unused value."}]))}))))

(defn- stamp-transaction
  "Attach writer observations once, including the first schema-installing write.
  Process and agent names are historical observations, not living entity refs.
  A caller may name its operation; otherwise the first named transaction function
  identifies it, or `transact!` does. No agent is invented for a system caller."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction
                       [:maybe :map] :seon.db.process/id] :seon.store/transaction]}
  [database transaction world process-id]
  (let [request (if (map? transaction) transaction {:tx-data transaction})
        attributes [:seon.db.transaction/process :seon.db.transaction/operation :seon.db.transaction/agent
                    :seon.db.transaction/ms :seon.db.transaction/calls :seon.db.transaction/hot :seon.db.transaction/repeated]
        missing (filterv #(nil? (get (dbi/-schema database) %)) attributes)
        forms (when (seq missing) (select-keys (schema.edn/packaged-forms) attributes))
        projection (when forms (schema/declaration-projection forms))
        declarations (when forms
                       (into (schema.datahike/malli->datahike-schema-in projection missing)
                             (when (get (dbi/-schema database) :seon.schema/key)
                               (schema/canonical-schema-entities projection forms))))
        operation (or (:seon.db.transaction/operation request)
                      (some (fn [op]
                              (when (and (vector? op) (= :db.fn/call (first op)))
                                (let [f (second op)]
                                  (cond (qualified-symbol? f) f
                                        (var? f) (symbol (str (ns-name (:ns (meta f)))) (str (:name (meta f))))))))
                            (:tx-data request))
                      'seon.db/transact!)
        agent (or (:seon.db.transaction/agent request) (:seon.agent/id world))
        stamp (cond-> (merge (profile/transaction-observation world)
                             {:db/id :db/current-tx
                              :seon.db.transaction/process process-id
                              :seon.db.transaction/operation operation})
                agent (assoc :seon.db.transaction/agent agent)
                *receipt* (assoc ::receipt *receipt*))]
    (assoc request :tx-data (conj (into (into (vec declarations) (when *receipt* *open*))
                                       (:tx-data request)) stamp))))

(defn absent-call
  "`tx-data` when `lookup-ref` names no entity in `database`, else nothing.

  Invoked as `[:db.fn/call #'absent-call lookup-ref tx-data]`, so the writer
  decides on its own mid-transaction value: data that several writes may carry
  (a run's open that the first of them writes) is applied exactly once."
  {:malli/schema [:=> [:cat :seon.db/database-value [:tuple :qualified-keyword :seon.schema/value]
                       :seon.store/transaction-data]
                  :seon.store/transaction-data]}
  [database lookup-ref tx-data]
  (if (db.utils/entid database lookup-ref) [] (vec tx-data)))

(defn- write-entity-schemas
  "Identity attribute -> every entity schema requiring that attribute."
  [projection]
  (schema/projection-cache-value
   projection ::write-required-identity-schemas
   (fn []
     (let [forms (:seon.schema.projection/forms projection)]
       (reduce-kv
        (fn [by-identity schema-key _authored]
          (let [compiled (mr/schema (:seon.schema.projection/registry projection) schema-key)]
            (if (and (internal/entity-schema? compiled)
                     (:seon.db/attributes (internal/entity-properties compiled)))
              (reduce
               (fn [result [attribute options]]
                 (if (and (not (and (map? options) (:optional options)))
                          (schema/identity-attr? projection attribute))
                   (update result attribute (fnil conj []) schema-key)
                   result))
               by-identity (internal/entity-entries compiled))
              by-identity)))
        {} forms)))))

(defn- write-problem
  "One refused write as a problem description (`seon.error/explain-problem`).
  A missing key's offending value is its parent map, never a placeholder."
  {:malli/schema [:=> [:cat :qualified-keyword :seon.schema/value :seon.schema/value [:sequential :seon.schema/value] :seon.schema/value [:maybe :map]]
                  :seon.error/problem-description]}
  [attribute compiled value path cause parent]
  (if (= ::attribute-not-installed cause)
          {:seon.error/argument "transaction data"
           :seon.error/path path
           :seon.error/expected :qualified-keyword
           :seon.error/expected-description "an installed attribute"
           :seon.error/offending attribute
           :seon.error/actual-description "an undeclared attribute"
           :seon.error/fix "Use a declared attribute from the entity's schema."}
          (@error-explain-problem
           (cond-> {:seon.error/argument "transaction data"
                    :seon.error/path path
                    :seon.error/problem
                    (cond-> {:schema compiled
                             :value value :in path}
                      (= :malli.core/missing-key cause) (assoc :type cause))}
             (map? parent) (assoc :seon.error/parent parent)))))

(defn- invalid-write
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :seon.schema/value :seon.schema/value [:sequential :seon.schema/value] :seon.schema/value :seon.schema/value :seon.schema/value] :seon.error/base]}
  [_projection attribute compiled value path entity-form cause candidates]
  (let [form (if (= ::attribute-not-installed cause) compiled (m/form compiled))
        problem (write-problem attribute compiled value path cause nil)]
  (diagnostic
   (cond->
    {:seon.error/message (@error-problem-sentence
      'seon.db/transact! problem nil
      (@error-scalar-text (:seon.error/offending problem)))
     ::transaction-refused true
     ::attribute attribute
     :seon.schema/form form
     ::offending value
     ::path path
     :seon.error/data {:seon.error/problems [problem]}
     :seon.error/layer :seon.db/database-write
     :seon.error/operation 'seon.db/transact!}
     entity-form (assoc ::entity-form entity-form)
     candidates (assoc ::registered-candidates candidates)))))

(declare write-map-error write-attribute-error)

(defn- write-ref-error
  [database projection value path entity-form]
  (cond
    (map? value) (write-map-error database projection value path)
    (and (sequential? value) (= 2 (count value)))
    (write-attribute-error database projection (first value) (second value)
                           (conj path 1) entity-form false)
    :else nil))

(defn- write-many-values
  "Datahike map syntax admits a collection or one scalar/identity lookup ref."
  [database attribute value]
  (if (and (coll? value) (not (map? value))
           (not (and (db.utils/ref? database attribute)
                     (sequential? value)
                     (= 2 (count value))
                     (keyword? (first value))
                     (db.utils/is-attr? database (first value) :db.unique/identity)
                     (not (datahike.schema/entity-spec-attr? attribute)))))
    value
    [value]))

(defn- write-value
  "Normalize Datahike's reference and many-value syntax for Malli only."
  [database projection attribute value single?]
  (let [form (schema.datahike/storage-schema (mr/schema (:seon.schema.projection/registry projection) attribute))
        many? (and (not single?) (db.utils/multival? database attribute))
        normalize (if (db.utils/ref? database attribute)
                    #(if (or (map? %) (sequential? %)) 0 %)
                    identity)]
    (if many?
      (let [values (write-many-values database attribute value)]
        (case (m/type form)
          :set (into #{} (map normalize) values)
          (mapv normalize values)))
      (normalize value))))

(defn- write-attribute-error
  "The refusal of one submitted attribute value, or nil when it validates."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.schema/value :seon.schema/value
                       [:vector :seon.schema/value] :seon.schema/value :boolean]
                  [:or :nil :seon.error/base]]}
  [database projection attribute value path entity-form single?]
  (let [installed (get (dbi/-schema database) attribute)
        authored (get (:seon.schema.projection/forms projection) attribute)]
    (cond
      (and (not single?) (db.utils/reverse-ref? attribute)
           (db.utils/ref? database (db.utils/reverse-ref attribute)))
      (let [values (write-many-values database attribute value)
            collection-value? (identical? values value)]
        (some (fn [[index child]]
                (write-ref-error database projection child
                                 (if collection-value? (conj path index) path)
                                 entity-form))
              (map-indexed vector values)))

      (and (nil? installed) (not (contains? datahike.schema/schema-keys attribute)))
      (invalid-write projection attribute :seon.error/unknown value path entity-form
                     ::attribute-not-installed
                     (registered-attribute-candidates
                      (installed-attribute-declarations database) attribute))

      :else
      (let [many? (and (not single?) (db.utils/multival? database attribute))
            children (if (and many?
                              (identical? value (write-many-values database attribute value)))
                       (map-indexed vector value) [[nil value]])
            nested-error
            (when (db.utils/ref? database attribute)
              (some (fn [[index child]]
                      (write-ref-error database projection child
                                       (if (some? index) (conj path index) path)
                                       entity-form))
                    children))
            form (if (and single? (db.utils/multival? database attribute))
                   (schema.datahike/value-schema (mr/schema (:seon.schema.projection/registry projection) attribute))
                   (mr/schema (:seon.schema.projection/registry projection) attribute))]
        (or nested-error
            ;; Dependency-owned schema attributes have no authored Malli form;
            ;; Datahike continues to validate and classify those declarations.
            (when (and authored
                       (not ((m/validator form)
                             (write-value database projection attribute value single?))))
              (invalid-write projection attribute form value path entity-form
                             ::invalid-value nil)))))))

(defn- write-key-candidates
  "Missing declared keys in the closest schema selected by present attributes."
  [projection entity-map]
  (let [present (set (keys entity-map))
        candidates
        (->> (schema/candidate-shapes-in projection entity-map)
             (map (fn [shape]
                    (let [required (:seon.schema/required-attrs shape)]
                      {:matched (count (filter present required))
                       :missing (set (remove present required))})))
             (filter #(and (pos? (:matched %)) (seq (:missing %)))))
        rank (fn [candidate] [(- (:matched candidate)) (count (:missing candidate))])
        best (some->> candidates (sort-by rank) first rank)]
    (into (sorted-set)
          (comp (filter #(= best (rank %))) (mapcat :missing)) candidates)))

(defn- write-map-error
  [database projection entity-map path]
  (let [schemas (write-entity-schemas projection)
        schema-keys
        (distinct
         (mapcat (fn [attribute]
                   (when (= :db.unique/identity
                            (get-in (dbi/-schema database) [attribute :db/unique]))
                     (get schemas attribute)))
                 (keys entity-map)))
        forms (:seon.schema.projection/forms projection)
        entity-form (some->> schema-keys first (get forms))
        failure
        (some (fn [[attribute value]]
                (if (= :db/id attribute)
                  (write-ref-error database projection value (conj path attribute) entity-form)
                  (write-attribute-error database projection attribute value
                                         (conj path attribute) entity-form false)))
              entity-map)]
    (if (and failure (= :seon.error/unknown (:seon.schema/form failure)))
      (let [candidates (write-key-candidates projection entity-map)]
        (if (= 1 (count candidates))
          (-> failure
              (assoc ::registered-candidates (vec candidates))
              (assoc-in [:seon.error/data :seon.error/problems 0 :seon.error/fix]
                        (str "Use " (first candidates)
                             "; it is the missing declared key for the attributes in this map.")))
          failure))
      failure)))

(defn- write-error
  [database projection transaction]
  (some
   (fn [[index entry]]
     (cond
       (map? entry) (write-map-error database projection entry [index])
       (and (sequential? entry) (= :db/add (first entry)))
       (or (write-ref-error database projection (second entry) [index 1] nil)
           (write-attribute-error database projection (nth entry 2 nil)
                                  (nth entry 3 nil) [index 3] nil true))))
   (map-indexed vector (if (map? transaction) (:tx-data transaction) transaction))))

(defn- write-attribute-plan
  "Compile final-datom decoding and validation on the projection. Datahike has
   already resolved refs; diagnostics still use the submission owner."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword :boolean] :map]}
  [projection attribute many?]
  (schema/projection-cache-value
   projection [::write-attribute-plan attribute many?]
   (fn []
     (let [authored (get (:seon.schema.projection/forms projection) attribute)
           form (when authored
                  (schema.datahike/storage-schema (mr/schema (:seon.schema.projection/registry projection) attribute)))
           decode (if (and authored (schema.datahike/edn-encoded-attr-in? projection attribute))
                    #(schema.datahike/decode-attribute-value-in projection attribute %)
                    identity)]
       {::decode decode
        ::validate (when authored
                     (m/validator
                                      (if many?
                                        (schema.datahike/value-schema form)
                                        (mr/schema (:seon.schema.projection/registry projection) attribute))))}))))

(defn- cell-rows
  "`cells` as rows `{::cell ::position ::members}`: every datom a cell holds
   beside its position is a member, so a value whose declarations do not yet
   name the cells (the publication writes them after the attributes) reads
   them alike. One EAVT seek per cell."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.db/entity-id]]
                  [:vector [:map [::cell :seon.db/entity-id] [::position [:or :nil :int]] [::members [:vector :seon.db/transaction-report-datom]]]]]}
  [database cells]
  (mapv (fn [cell]
          (let [cell-datoms (d/datoms database :eavt cell)]
            {::cell cell
             ::position (some #(when (= :seon.db.vector/position (:a %)) (:v %)) cell-datoms)
             ::members (filterv #(not= :seon.db.vector/position (:a %)) cell-datoms)}))
        cells))

(defn- cell-vectors
  "The logical vectors of `parent`, read from its `cells` and `present`
   attributes: each present attribute's elements in position order, `[]` when
   it has none. `refuse!` receives the first storage fault (design §3.2): a
   cell whose element a sweep removed, a cell holding more than one element,
   positions other than 0..n-1, or cells of an attribute not present."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.instrument/callable
                       :seon.db/entity-id [:vector :seon.db/entity-id] [:set :qualified-keyword]
                       [:or :nil :seon.instrument/callable] [:or :nil :seon.instrument/callable]]
                  [:map-of :qualified-keyword [:vector :seon.schema/value]]]}
  [database plan parent cells present visit-child refuse!]
  (when visit-child (run! visit-child cells))
  (let [rows (cell-rows database cells)
        by-attribute (group-by #(:a (first (::members %))) (filter #(= 1 (count (::members %))) rows))]
    (when refuse!
      (when-let [[cause data]
                 (or (some (fn [{::keys [cell position members]}]
                             (case (count members)
                               0 [::vector-element-swept {::entity parent ::cell cell ::position position}]
                               1 nil
                               [::vector-cell-ambiguous {::entity parent ::cell cell
                                                         ::attributes (into [] (map :a) members)}]))
                           rows)
                     (some (fn [[attribute group]]
                             (let [positions (sort (map ::position group))]
                               (cond
                                 (not= positions (range (count positions)))
                                 [::vector-positions {::entity parent ::attribute attribute ::positions (vec positions)}]
                                 (not (contains? present attribute))
                                 [::vector-cells-without-presence {::entity parent ::attribute attribute}])))
                           by-attribute))]
        (refuse! cause data)))
    (into {}
          (map (fn [attribute]
                 (let [component? (:db/isComponent (get (dbi/-schema database) attribute))]
                   [attribute
                    (mapv (fn [{::keys [members]}]
                            (let [value ((::decode (plan attribute)) (:v (first members)))]
                              (when (and visit-child component?) (visit-child value))
                              value))
                          (sort-by ::position (get by-attribute attribute)))])))
          present)))

(defn- write-entity-value
  "Read a resulting entity as logical values without expanding reference
   graphs: each cardinality-many value in its declared collection
   (`schema.datahike/logical-collection`), each stored vector read from its
   cells ([[cell-vectors]]) with the bridge keys removed. The cells read are
   the value's `::cells` metadata, so the owned-value walk counts them as
   visited. `refuse!`, when given, receives a vector storage fault and a
   vector attribute's datom stored outside a cell."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection [:map-of [:or :keyword :int] :map]
                       :seon.db/entity-id [:* [:or :nil :seon.instrument/callable]]]
                  :map]}
  [database projection attribute-plans entity-id & [visit-child refuse!]]
  (let [plan (fn [attribute]
               (or (get attribute-plans attribute)
                   (write-attribute-plan projection attribute (db.utils/multival? database attribute))))
        entity-datoms (d/datoms database :eavt entity-id)
        cell? (some #(= :seon.db.vector/position (:a %)) entity-datoms)
        cell-attribute? (when (and refuse! (not cell?)) (schema.datahike/vector-attributes-in projection))
        stored
        (reduce
         (fn [entity-map {:keys [a v]}]
           (case a
             :seon.db.vector/cells (update entity-map ::cells (fnil conj []) v)
             :seon.db.vector/present (update entity-map ::present (fnil conj #{}) v)
             (let [value ((::decode (plan a)) v)]
               (when (and cell-attribute? (cell-attribute? a))
                 (refuse! ::vector-member-outside-cell {::entity entity-id ::attribute a}))
               (when (and visit-child (:db/isComponent (get (dbi/-schema database) a)))
                 (visit-child value))
               (if (db.utils/multival? database a)
                 (update entity-map a (fnil conj []) value)
                 (assoc entity-map a value)))))
         {} entity-datoms)
        entity-map (reduce-kv (fn [entity-map a value]
                                (cond
                                  (contains? #{::cells ::present} a) entity-map
                                  (db.utils/multival? database a)
                                  (assoc entity-map a (schema.datahike/logical-collection projection a value))
                                  :else (assoc entity-map a value)))
                              {} stored)]
    (if-not (or (::cells stored) (::present stored))
      entity-map
      (with-meta (merge entity-map
                        (cell-vectors database plan entity-id (vec (::cells stored))
                                      (or (::present stored) #{}) visit-child refuse!))
        {::cells (::cells stored)}))))

(defn- write-error-identity
  [refusal identities]
  (-> refusal
      (update :seon.error/message #(str % " Entity: " (pr-str identities) "."))
      (assoc-in [:seon.error/data ::entity] identities)))

(defn- write-entity-error
  "Validate the whole resulting entity, including identities present before a
   retraction. `entity-map` is [[write-entity-value]]'s logical value, each many
   value already in its declared collection."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection [:map-of [:or :keyword :int] :map]
                       :seon.db/entity-id :map :map [:? [:sequential :keyword]]]
                  [:or :nil :seon.db.write/entity-refusal]]}
  [_database projection _attribute-plans entity-id identities entity-map & [owned-schemas]]
  (when (seq entity-map)
    (let [forms (:seon.schema.projection/forms projection)
          schemas (write-entity-schemas projection)
          schema-keys (distinct (concat owned-schemas (mapcat #(get schemas %) (keys identities))))]
      (some
       (fn [schema-key]
         (when-not
          ((m/validator (mr/schema (:seon.schema.projection/registry projection) schema-key)) entity-map)
           (let [explain (schema/projection-cache-value
                          projection [::write-explainer schema-key]
                          #(schema/projection-explainer projection schema-key))
                 failures (:errors (explain entity-map))
                 ;; every failing path, each with its own offending value: a
                 ;; missing key's is the map it is missing from
                 attribute-of #(or (first (:in %)) (first (keys identities)))
                 offending-of #(if (= :malli.core/missing-key (:type %))
                                 (get-in entity-map (pop (vec (:in %))))
                                 (:value %))
                 failure (first failures)
                 in (:in failure)
                 refusal (invalid-write projection (attribute-of failure)
                                        (:schema failure)
                                        (offending-of failure) (into [entity-id] in) (get forms schema-key)
                                        (or (:type failure) ::invalid-entity) nil)
                 problems (mapv #(write-problem (attribute-of %) (:schema %) (offending-of %)
                                                (into [entity-id] (:in %))
                                                (or (:type %) ::invalid-entity) nil)
                                failures)]
             (-> refusal
                 (write-error-identity identities)
                 (update :seon.error/data merge
                         {:seon.error/problems problems
                          ::entity identities ::entity-value entity-map ::path (into [entity-id] in)})))))
       schema-keys))))

(def ^:private stored-check-limit
  "Distinct stored entities a registration's stored-data check judges."
  200)

(defn stored-value-check
  "What `definition`, registered in place of attribute `k`'s form, makes of
  the values stored now.

  The first 200 distinct entities holding `k` (AEVT order) are each read as
  the writer reconstructs them (`write-entity-value`: a cardinality-many
  value in its declared collection, a vector from its cells), and `k`'s
  value is validated by `definition` compiled on this database's projection.
  `:seon.schema.usage/more?` says more entities hold `k`. An attribute not
  installed holds no values. A definition the projection cannot compile is
  the declared refusal, never a verdict.

  ## Costs

  One AEVT walk to the 201st distinct entity and one EAVT read per checked
  entity; run once, when the registration is evaluated
  (`registration-check`), never by a render."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value]
                  [:or :seon.schema.usage/stored-check :seon.error/base]]}
  [database k definition]
  (let [projection (carried-projection database)
        valid? (when projection
                 (try (m/validator definition {:registry (:seon.schema.projection/registry projection)})
                      ;; Declared case: Malli's `::m/invalid-schema` for a form
                      ;; naming a schema this projection does not hold.
                      (catch clojure.lang.ExceptionInfo failure failure)))]
    (cond
      (nil? projection)
      (diagnostic {:seon.error/layer :database-read
                   :seon.error/operation 'seon.db/stored-value-check
                   :seon.error/message "This database value carries no schema projection to reconstruct stored values with."
                   :seon.error/expected :seon.schema/projection})

      (instance? Throwable valid?)
      (diagnostic {:seon.error/layer :database-read
                   :seon.error/operation 'seon.db/stored-value-check
                   :seon.error/message (str "The form registered for " k " does not compile on this database's projection: "
                                            (ex-message valid?))
                   :seon.error/expected :seon.schema/definition
                   :seon.error/offending definition})

      (nil? (get (dbi/-schema database) k))
      {:seon.schema.usage/checked 0 :seon.schema.usage/failing 0}

      :else
      (let [entities (into [] (comp (map :e) (dedupe) (take (inc stored-check-limit)))
                           (d/datoms database :aevt k))
            failing (into [] (keep (fn [entity-id]
                                     (let [value (get (write-entity-value database projection {} entity-id) k)]
                                       (when-not (valid? value) [entity-id value]))))
                          (take stored-check-limit entities))]
        (cond-> {:seon.schema.usage/checked (min stored-check-limit (count entities))
                 :seon.schema.usage/failing (count failing)}
          (> (count entities) stored-check-limit) (assoc :seon.schema.usage/more? true)
          (seq failing) (assoc :seon.schema.usage/failing-example (first failing)))))))

(defn registration-check
  "The stored-data check `seon.schema/register!` records on its
  registration: `stored-value-check` over the database in custody, when the
  registration changes an installed attribute's committed form.

  Nil outside custody (a namespace load), for an attribute not installed and
  for an unchanged form, so those read nothing more. A custody read the
  database refuses is returned as its refusal."
  {:malli/schema [:=> [:cat :qualified-keyword :seon.schema/value]
                  [:or :nil :seon.schema.usage/stored-check :seon.error/base]]}
  [k definition]
  (when (or *read-database* *conn*)
    (let [database (db)]
      (cond
        (read-refusal? database) database
        (and (database-value? database)
             (some? (get (dbi/-schema database) k))
             (not= definition (get (:seon.schema.projection/forms (carried-projection database)) k)))
        (stored-value-check database k definition)))))

(defn- write-owned-values-error
  "Validate complete owning values, reached through both sides of this report.
   EAVT supplies every child; AVET discovers owners without pull's 1,000 cap.
   Returns the refusal, or on success every changed arity-bearing root's
   identity attributes as `::changed-identity-attributes`, the arity gate's input."
  {:seon/tasks #{"4bb788513862"}
   :malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       ;; Datahike's `:schema` also maps each attribute's entity
                       ;; id to its ident, so the plans carry integer keys too.
                       [:map-of [:or :keyword :int] :map] [:set :qualified-keyword]]
                  [:or :seon.db/error-result
                   [:map [:seon.db/changed-identity-attributes [:set :qualified-keyword]]]]]}
  [projection report attribute-plans identity-attrs]
  (let [before (:db-before report)
        after (:db-after report)
        limit (:seon.config.db/validation-node-limit projection)
        components (into #{} (keep (fn [[a properties]]
                                     (when (:db/isComponent properties) a)))
                         (merge (dbi/-schema before) (dbi/-schema after)))
        targets (into {} (map (fn [a]
                               [a (some-> (mr/schema (:seon.schema.projection/registry projection) a)
                                          m/properties :seon.db/component-schema)]))
                      components)
        ;; One validation traversal's memo: charged [phase eid] visits, entities
        ;; and owner edges by [phase eid]. A volatile because the entity reader
        ;; charges through a callback (`write-entity-value`) that no loop owns.
        traversal (volatile! {::seen #{} ::rows {} ::owners {}})
        ;; Cell -> the vector attribute a sweep retracted from it in this report.
        swept (let [cell-attribute? (schema.datahike/vector-attributes-in projection)]
                (into {} (keep #(when (and (not (:added %)) (cell-attribute? (:a %)))
                                  [(:e %) (:a %)]))
                      (:tx-data report)))
        fail! (fn [cause data]
                (throw (ex-info "Owned entity validation refused."
                                {::owned-refusal
                                 (assoc
                                  (diagnostic
                                   {::owned-value-refusal cause
                                    :seon.error/message (str "Complete component validation refused: " (name cause) "; bound "
                                         :seon.config.db/validation-node-limit "=" limit "; " (pr-str data) ".")
                                    :seon.error/data (merge {::validation-bound :seon.config.db/validation-node-limit
                                            ::validation-limit limit}
                                           data)
                                    :seon.error/layer :seon.db/database-write
                                    :seon.error/operation 'seon.db/transact!
                                    :seon.error/expected :seon.db/component-schema})
                                  ::transaction-refused true)})))
        charge! (fn [phase entity-id]
                  (let [cache-key [phase entity-id]]
                    (when-not ((::seen @traversal) cache-key)
                      (when (>= (count (::seen @traversal)) limit)
                        (fail! ::validation-node-limit {::entity entity-id ::visited (count (::seen @traversal))}))
                      (vswap! traversal update ::seen conj cache-key))))
        entity-map (fn [phase database entity-id]
              (let [cache-key [phase entity-id]]
                (charge! phase entity-id)
                (if-let [entry (find (::rows @traversal) cache-key)]
                  (val entry)
                  (let [value (write-entity-value database projection attribute-plans entity-id
                                                  #(charge! phase %)
                                                  ;; A vector storage fault refuses as an owned value;
                                                  ;; a swept element names the attribute its sweep retracted.
                                                  (when (= phase :after)
                                                    (fn [cause data]
                                                      (fail! cause (cond-> data
                                                                     (swept (::cell data))
                                                                     (assoc ::attribute (swept (::cell data))))))))]
                    (vswap! traversal assoc-in [::rows cache-key] value)
                    value))))
        ;; The component attributes that can reference anything in each
        ;; phase: installed there and holding at least one datom, paired with
        ;; the attribute Datahike's own index search takes (its reverse lookup
        ;; in `retract-entity`, `datahike/db/transaction.cljc:998-1011`).
        ;; Refs are always AVET-indexed (`datahike/db/utils.cljc:312`), so
        ;; each owner question is one seek per such attribute, never a scan.
        new-after (:max-eid before)
        new-owner-edges (reduce (fn [edges datom]
                                  (if (and (:added datom) (components (:a datom))
                                           (> (long (:v datom)) (long new-after)))
                                    (update edges (:v datom) (fnil conj #{}) [(:e datom) (:a datom)])
                                    edges))
                                {} (:tx-data report))
        owner-attributes
        (memoize
         (fn [database]
           (into []
                 (keep (fn [attribute]
                         (when (and (get (dbi/-schema database) attribute)
                                    (first (d/datoms database :aevt attribute)))
                           [attribute (if (db.utils/attr-has-ref? database attribute)
                                        (dbi/-ref-for database attribute)
                                        attribute)])))
                 components)))
        owners-of (fn [phase database entity-id]
                  (let [cache-key [phase entity-id]]
                    (charge! phase entity-id)
                    (if-let [entry (find (::owners @traversal) cache-key)]
                      (val entry)
                      (let [value
                            (if (> (long entity-id) (long new-after))
                              ;; An entity id beyond `:db-before`'s max-eid did
                              ;; not exist before, so nothing referenced it
                              ;; there, and after only this report's own
                              ;; component datoms can: no index seek.
                              (if (= phase :before)
                                []
                                (let [order (zipmap (map first (owner-attributes database)) (range))]
                                  ;; The order an index seek would give:
                                  ;; attribute order, then owner id.
                                  (into []
                                        (keep (fn [[owner attribute]]
                                                (when (first (d/datoms database :eavt owner attribute entity-id))
                                                  (charge! phase owner)
                                                  [owner attribute])))
                                        (sort-by (fn [[owner attribute]] [(get order attribute Long/MAX_VALUE) owner])
                                                 (get new-owner-edges entity-id)))))
                              (into [] (mapcat (fn [[attribute searched]]
                                                 (map (fn [datom]
                                                        (charge! phase (:e datom))
                                                        [(:e datom) attribute])
                                                      (dbi/search database [nil searched entity-id]))))
                                    (owner-attributes database)))]
                        (vswap! traversal assoc-in [::owners cache-key] value)
                        value))))
        owning-ancestors (fn [phase database seeds]
                    (loop [pending (vec seeds) visited #{}]
                      (if-let [entity-id (peek pending)]
                        (if (visited entity-id)
                          (recur (pop pending) visited)
                          (let [edges (owners-of phase database entity-id)]
                            (when (and (= phase :after) (> (count edges) 1))
                              (fail! ::multiple-component-owners {::entity entity-id ::owners edges}))
                            (recur (into (pop pending) (map first edges)) (conj visited entity-id))))
                        visited)))
        report-datoms (concat (:datahike/attempted-tx-data report) (:tx-data report))
        seeds (into #{} (comp (mapcat #(cond-> [(:e %)] (components (:a %)) (conj (:v %))))
                              (filter #(< (long %) const/tx0))) report-datoms)]
    (try
      (when-not (pos-int? limit)
        (fail! ::missing-validation-bound {}))
      (let [prior (owning-ancestors :before before seeds)
            current (owning-ancestors :after after (into seeds prior))
            roots (filterv #(empty? (owners-of :after after %)) current)
            ;; Iterative postorder avoids using JVM stack depth as the graph
            ;; bound; the complete values and completed ids are its loop state.
            {expanded ::expanded visited ::visited}
            (reduce
             (fn [walk root]
               (loop [pending [[root false]] active #{} {expanded ::expanded visited ::visited :as walk} walk]
                 (if-let [[entity-id exit?] (peek pending)]
                   (let [value (entity-map :after after entity-id)
                         child-edges (into []
                                           (mapcat (fn [[a v]]
                                                     (when (components a)
                                                       (map #(vector a %) (if (db.utils/multival? after a) v [v])))))
                                           value)]
                     (cond
                       exit?
                       (let [whole (reduce (fn [result [a child]]
                                             (if (db.utils/multival? after a)
                                               (update result a (fnil conj []) (get expanded child))
                                               (assoc result a (get expanded child))))
                                           (apply dissoc value components) child-edges)
                             whole (reduce-kv (fn [result a children]
                                                (if (and (components a) (db.utils/multival? after a))
                                                  (assoc result a (schema.datahike/logical-collection projection a children))
                                                  result))
                                              whole whole)]
                         ;; The entity's vector cells were read with it: visited.
                         (recur (pop pending) (disj active entity-id)
                                {::expanded (assoc expanded entity-id whole)
                                 ::visited (into (conj visited entity-id) (::cells (meta value)))}))

                       (visited entity-id) (recur (pop pending) active walk)
                     (active entity-id) (fail! ::component-cycle {::entity entity-id ::root root})
                     :else
                     (do
                       (doseq [[a child] child-edges]
                         (when-not (get (:seon.schema.projection/forms projection) (get targets a))
                           (fail! ::missing-component-schema {::attribute a ::entity entity-id}))
                         (when (> (count (owners-of :after after child)) 1)
                           (fail! ::multiple-component-owners
                                  {::entity child ::owners (owners-of :after after child)}))
                         (when (empty? (entity-map :after after child))
                           (fail! ::missing-component {::entity child ::owner entity-id ::attribute a})))
                       (recur (into (conj (pop pending) [entity-id true])
                                    (map (fn [[_ child]] [child false]) child-edges))
                              (conj active entity-id)
                              walk))))
                   walk)))
             {::expanded {} ::visited #{}}
             roots)]
        (when-let [entity-id (first (remove visited current))]
          (fail! ::component-cycle {::entity entity-id}))
        (let [;; A function's documentation/source coordinates do not alter
              ;; prepared arities. Only its call facts, arity relation,
              ;; identity, or changed owned components can do that. One pass
              ;; over the report names every such root: a datom on the root
              ;; itself through an arity-bearing attribute, or any datom on
              ;; an entity the root owns on either side. Each entity's owning
              ;; ancestors are walked once, so the work is linear in the
              ;; report, never roots x datoms. A from-zero write asserts every
              ;; root's own identity datom, so there every root is changed.
              changed-roots
              (delay
               ;; A root's own arity-bearing datom counts whatever its id,
               ;; a transaction entity included; only the ancestor walk is
               ;; bounded to ordinary entities.
               (let [tx-data (:tx-data report)]
                 (into (into #{}
                             (comp (filter #(#{:seon.fn/sym :seon.test/sym
                                               :seon.fn/call-arities :seon.fn/arities}
                                             (:a %)))
                                   (map :e))
                             tx-data)
                       (mapcat (fn [entity]
                                 (disj (into (owning-ancestors :before before [entity])
                                             (owning-ancestors :after after [entity]))
                                       entity)))
                       (into #{} (comp (map :e) (filter #(< (long %) const/tx0)))
                             tx-data))))
              root-identities
              (mapv (fn [root]
                      (let [value (get expanded root)]
                        [root value (merge (select-keys (entity-map :before before root) identity-attrs)
                                           (select-keys value identity-attrs))]))
                    roots)]
        (or
         (some (fn [[root value identities]]
                 (when (and (seq value) (empty? identities))
                   (fail! ::unowned-entity {::entity root ::entity-value value}))
                 (write-entity-error after projection attribute-plans root identities value))
               root-identities)
         (some (fn [[entity-id value]]
                 (when-let [[parent attribute] (first (owners-of :after after entity-id))]
                   (let [target (get targets attribute)
                         identities (select-keys value identity-attrs)]
                     (when-let [refusal (write-entity-error after projection attribute-plans entity-id identities value [target])]
                       (update refusal :seon.error/data assoc
                               ::owner parent ::attribute attribute ::component-schema target)))))
               expanded)
         {::changed-identity-attributes
          (into #{}
                (mapcat (fn [[root _ identities]]
                          (when (or (not (some identities [:seon.fn/sym :seon.test/sym]))
                                    (@changed-roots root))
                            (keys identities))))
                root-identities)})))
      (catch clojure.lang.ExceptionInfo exception
        (if-let [refusal (::owned-refusal (ex-data exception))] refusal (throw exception))))))

;;; ---------------------------------------------------------------------------
;;; Vectors in reports: logical facts for every first-party report reader
;;; ---------------------------------------------------------------------------

(defn- cell-owner
  "The parent that owns cell `cell` in `database`, or nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] [:or :nil :seon.db/entity-id]]}
  [database cell]
  (when (get (dbi/-schema database) :seon.db.vector/cells)
    (:e (first (d/datoms database :avet :seon.db.vector/cells cell)))))

(defn- attribute-holders
  "The entities whose `attribute` holds `value`: through the cell holding it
   when the attribute is stored in cells, else directly."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value] [:vector :seon.db/entity-id]]}
  [database attribute value]
  (into []
        (keep (fn [datom]
                (if (and (get (dbi/-schema database) :seon.db.vector/position)
                         (first (d/datoms database :eavt (:e datom) :seon.db.vector/position)))
                  (cell-owner database (:e datom))
                  (:e datom))))
        (d/datoms database :avet attribute value)))

(defn logical-datoms
  "`report`'s datoms as facts about the entities an agent reads: `[cell a v]`
   reads `[parent a v]`; a moved cell reads its element asserted on the parent
   with `:seon.db.vector/position`; bridge links and presence drop.

   # Costs
   O(tx-data), one AVET seek per touched cell (unarmed prototype: 13 ms for a
   2000-parent report)."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/transaction-report] [:sequential [:or :seon.db/transaction-report-datom :map]]]
                  [:=> [:cat :seon.schema/projection :seon.db/transaction-report]
                   [:sequential [:or :seon.db/transaction-report-datom :map]]]]}
  ([report] (logical-datoms (carried-projection (:db-after report)) report))
  ([projection {before :db-before after :db-after tx-data :tx-data}]
   (if-not (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
     tx-data
     (let [owner #(or (cell-owner after %) (cell-owner before %))
           ;; Asked once: a per-datom question is a cache lookup per datom.
           cell-attribute? (schema.datahike/vector-attributes-in projection)
           ;; A cell whose element this report wrote is new: its element datom says it all.
           written (into #{} (comp (filter #(cell-attribute? (:a %))) (map :e)) tx-data)
           element (fn [cell]
                     (some #(when (cell-attribute? (:a %)) %)
                           (concat (d/datoms after :eavt cell) (d/datoms before :eavt cell))))]
       (into []
             (keep (fn [datom]
                     (let [attribute (:a datom)]
                       (cond
                         ;; A cell no value owns (made and removed within one report) says nothing.
                         (cell-attribute? attribute)
                         (when-let [parent (owner (:e datom))]
                           {:e parent :a attribute :v (:v datom) :tx (:tx datom) :added (:added datom)})

                         (and (= :seon.db.vector/position attribute) (:added datom) (not (written (:e datom))))
                         (when-let [held (element (:e datom))]
                           (when-let [parent (owner (:e datom))]
                             {:e parent :a (:a held) :v (:v held) :tx (:tx datom) :added true
                              :seon.db.vector/position (:v datom)}))

                         (= "seon.db.vector" (namespace attribute)) nil
                         :else datom))))
             tx-data)))))

(defn- storage-q-in
  "Datalog for the writer's own questions over logical attributes: `query`
   lowered by `projection`, then Datahike's `q`. A vector attribute's pattern
   is membership through its cells. The writer passes the projection it judges
   the write by, so a value holding no declaration rows yet (a first
   population's `:db-before`) is read by the declarations being installed."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/query :seon.db/database-value [:* :seon.schema/value]]
                  :seon.schema/value]}
  [projection query database & inputs]
  (let [[request] (lowered-query-request projection
                                         {:query query :args (into [database] inputs)}
                                         (query/memoized-parse-query query))]
    (apply d/q (:query request) (:args request))))


(defn logical-missing?
  "Datalog predicate, the lowering of `[(missing? $ e a)]`: true when `e`
   holds no value of `a`. A vector attribute is missing only without its
   presence, so an empty vector is not missing."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value :keyword] :boolean]}
  [database entity-id attribute]
  (and (empty? (d/datoms database :eavt entity-id attribute))
       (or (nil? (get (dbi/-schema database) :seon.db.vector/present))
           (empty? (d/datoms database :eavt entity-id :seon.db.vector/present attribute)))))

(defn- declared-arity-bounds
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/query :seon.db/database-value] [:or :seon.schema/value :seon.db/error-result]] :seon.db/database-value] [:or [:map-of :qualified-symbol [:set [:map [:seon.fn.arity/min :int] [:seon.fn.arity/max {:optional true} :int]]]] :seon.db/error-result]]}
  [query-fn database]
  (let [entity-maps (query-fn '[:find ?function-symbol ?minimum ?maximum
                         :where
                         [?function :seon.fn/sym ?function-symbol]
                         [?function :seon.fn/arities ?arity]
                         [?arity :seon.fn.arity/min ?minimum]
                         [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
                       database)]
    (if (read-refusal? entity-maps)
      entity-maps
      (reduce
       (fn [bounds [function-symbol minimum maximum]]
         (update bounds function-symbol (fnil conj #{})
                 (cond-> {:seon.fn.arity/min minimum}
                   (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
       {}
       entity-maps))))

(defn- arity-admitted?
  [declared arity]
  (boolean
   (some (fn [{minimum :seon.fn.arity/min maximum :seon.fn.arity/max}]
           (and (<= (long minimum) (long arity))
                (or (nil? maximum) (<= (long arity) (long maximum)))))
         declared)))

(def ^:private call-edges-queries
  ;; One plain join per caller family, whole program then bound to named
  ;; callers. One `or` over both families cost 1,208 ms cold on a speculative
  ;; value of default (102,063 edges), these two 87 ms with the identical
  ;; union; bound to one caller, 12 ms against 1.3 ms (2026-09-26).
  {:all (mapv (fn [family]
                [:find '?caller-symbol '?call
                 :where ['?caller :seon.fn/call-arities '?call] ['?caller family '?caller-symbol]])
              [:seon.fn/sym :seon.test/sym])
   :callers (mapv (fn [family]
                    [:find '?caller-symbol '?call :in '$ '[?caller-symbol ...]
                     :where ['?caller family '?caller-symbol] ['?caller :seon.fn/call-arities '?call]])
                  [:seon.fn/sym :seon.test/sym])})

(defn- call-edges
  "Every `[caller-symbol call]` edge of `database`'s functions and tests, or
  only those of `callers`, or the first read refusal `query-fn` answered."
  {:malli/schema [:function
                  [:=> [:cat [:=> [:cat :seon.db/query :seon.db/database-value] [:or :seon.schema/value :seon.db/error-result]]
                        :seon.db/database-value]
                   [:or [:set :seon.schema/value] :seon.db/error-result]]
                  [:=> [:cat [:=> [:cat :seon.db/query :seon.db/database-value [:set :symbol]] [:or :seon.schema/value :seon.db/error-result]]
                        :seon.db/database-value [:set :symbol]]
                   [:or [:set :seon.schema/value] :seon.db/error-result]]]}
  ([query-fn database]
   (reduce (fn [edges query]
             (let [entity-maps (query-fn query database)]
               (if (read-refusal? entity-maps) (reduced entity-maps) (into edges entity-maps))))
           #{} (:all call-edges-queries)))
  ([query-fn database callers]
   (reduce (fn [edges query]
             (let [entity-maps (query-fn query database callers)]
               (if (read-refusal? entity-maps) (reduced entity-maps) (into edges entity-maps))))
           #{} (:callers call-edges-queries))))

(defn- arity-comparison
  "Candidate edges of `edges` under `bounds`: callees declaring arities none
  of which admits the recorded count, with the comparison's coverage."
  {:malli/schema [:=> [:cat [:set :seon.schema/value] :map]
                  [:map [::candidates [:set :seon.schema/value]] [::checked :int] [::edges :int]]]}
  [edges bounds]
  (let [checked (filterv (fn [[_ [callee _]]] (contains? bounds callee)) edges)]
    {::candidates (into #{} (remove (fn [[_ [callee n]]] (arity-admitted? (get bounds callee) n))) checked)
     ::checked (count checked)
     ::edges (count edges)}))

;; Retire the former producers' entries when this owner is reloaded.
(seon.cache/evict-kinds! #{::arity-base ::arity-revision ::arity-commit})

(defn- report-arity-comparison
  "Check only final call edges whose caller or callee this write changes.

  EAVT reads written callers; the calls AVET index finds callers of changed
  callees. Schema/default changes follow reverse schema references to the
  arities using them. Bounds are read only for the reached callees. No
  whole-program base or cache key is retained; coverage names this write."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] :map]}
  [projection report]
  (let [before (:db-before report) after (:db-after report)
        tx-data (logical-datoms projection report)
        touched (fn [attributes] (into #{} (comp (filter (comp attributes :a)) (map :e)) tx-data))
        values (fn [database entities attributes]
                 (into #{} (for [e entities a attributes :when (get (dbi/-schema database) a)
                                 datom (d/datoms database :eavt e a)] (:v datom))))
        holders (fn [database attribute value]
                  (when (get (dbi/-schema database) attribute)
                    (attribute-holders database attribute value)))
        both (fn [entities attributes]
               (into (values before entities attributes) (values after entities attributes)))
        changed-schemas (both (touched #{:seon.schema/key :seon.schema/form}) [:seon.schema/key])
        defaults (touched #(= "seon.call-preparation" (namespace %)))
        supplied (into #{} (for [database [before after]
                                 e (values database defaults [:seon.call-preparation/schema])
                                 k (values database [e] [:seon.schema/key])] k))
        schemas (loop [pending (into changed-schemas supplied) seen #{}]
                  (if-let [k (first pending)]
                    (recur (into (disj pending k)
                                 (remove (conj seen k))
                                 (values after (holders after :seon.schema/references k) [:seon.schema/key]))
                           (conj seen k))
                    seen))
        arities (into (touched #{:seon.fn.arity/min :seon.fn.arity/max})
                      (mapcat #(holders after :seon.fn.arity/input-refs %)) schemas)
        owners (into #{} (for [database [before after] arity arities
                               e (holders database :seon.fn/arities arity)] e))
        callees (both (into owners (touched #{:seon.fn/arities :seon.fn/sym :seon.fn/spec})) [:seon.fn/sym])
        written (touched #{:seon.fn/call-arities :seon.fn/sym :seon.test/sym})
        written-symbols (values after written [:seon.fn/sym :seon.test/sym])
        callers (into written-symbols
                      (values after (mapcat #(holders after :seon.fn/calls %) callees)
                              [:seon.fn/sym :seon.test/sym]))
        storage-q (partial storage-q-in projection)
        edges (if (seq callers)
                (into #{} (filter (fn [[caller [callee _]]]
                                    (or (written-symbols caller) (callees callee))))
                      (call-edges storage-q after callers))
                #{})
        targets (into #{} (map (comp first second)) edges)
        bounds (if (seq targets)
                 (reduce (fn [out [sym minimum maximum]]
                           (update out sym (fnil conj #{})
                                   (cond-> {:seon.fn.arity/min minimum}
                                     (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
                         {}
                         (storage-q '[:find ?sym ?minimum ?maximum :in $ [?sym ...]
                                      :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arities ?a]
                                             [?a :seon.fn.arity/min ?minimum]
                                             [(get-else $ ?a :seon.fn.arity/max -1) ?maximum]] after targets))
                 {})]
    (assoc (arity-comparison edges bounds) ::bounds bounds)))

(defn- prepared-arity-bounds
  "The call counts `callee`'s STORED arities admit once its invocation plan's
  suppliable slots may be omitted: each stored arity row's minimum widened by
  the slot count of the plan arity at the same position of the callee's
  arity vector. The stored rows are what the program graph knows, so a
  changed arity row is compared as written; the compiled contract contributes
  only which slots a call may omit."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol [:maybe :map]]
                  [:or :seon.fn/declared-arities :seon.db/error-result]]}
  [database callee plan]
  (let [pulled (pull database [{:seon.fn/arities [:seon.fn.arity/min :seon.fn.arity/max]}] [:seon.fn/sym callee])
        slots (into {} (map (juxt :seon.fn.arity/order (comp count :seon.call-preparation/slots)))
                    (:seon.call-preparation/arities plan))]
    (if (:seon.db/invalid-read pulled)
      pulled
      (->> (:seon.fn/arities pulled)
           (map-indexed (fn [order {minimum :seon.fn.arity/min maximum :seon.fn.arity/max}]
                          (cond-> {:seon.fn.arity/min (max 0 (- (long minimum) (long (get slots order 0))))}
                            (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
           distinct
           (sort-by (juxt :seon.fn.arity/min #(get % :seon.fn.arity/max Long/MAX_VALUE)))
           vec))))

(defn- arity-mismatch-report
  "Refuse or report `candidates` (calls no stored arity admits) that the
  callee's stored arities do not admit even with its plan's suppliable slots
  omitted. `snapshot` is the call-preparation snapshot its caller acquired."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :map] :map [:vector :seon.schema/value] :int :int]
                  [:or :seon.fn/arity-mismatch-report :seon.db/error-result]]}
  [database snapshot bounds candidates checked edges]
  (let [refusal (or (when (and snapshot (nil? (:seon.call-preparation/basis-t snapshot))) snapshot)
                    (first (:seon.call-preparation/refusals snapshot)))]
    (or refusal
        (let [callees (distinct (map (comp first second) candidates))
              plans (into {} (map (fn [callee]
                                    [callee (@call-preparation-plan-for database snapshot callee)]))
                          callees)
              refused (some #(when (and % (nil? (:seon.fn/sym %))) %) (vals plans))
              prepared (into {} (map (fn [callee] [callee (prepared-arity-bounds database callee (get plans callee))]))
                             callees)
              unreadable (some #(when (:seon.db/invalid-read %) %) (vals prepared))]
          (or refused
              unreadable
              {:seon.fn/arity-mismatches
               (->> candidates
                    (keep (fn [[caller [callee n]]]
                            (let [declared (vec (sort-by
                                                 (juxt :seon.fn.arity/min
                                                       #(get % :seon.fn.arity/max Long/MAX_VALUE))
                                                 (get bounds callee)))
                                  admitted (get prepared callee)]
                              (when-not (arity-admitted? admitted n)
                                {:seon.fn/caller caller
                                 :seon.fn/callee callee
                                 :seon.fn/call-arity (long n)
                                 :seon.fn/declared-arities declared
                                 :seon.fn/prepared-arities admitted}))))
                    (sort-by (juxt :seon.fn/caller :seon.fn/callee :seon.fn/call-arity))
                    vec)
               :seon.fn/arity-checked checked
               :seon.fn/arity-unchecked (- edges checked)})))))

(defn- arity-verdict
  "Refuse or report the candidates whose prepared arities also refuse.

  The call-preparation snapshot is the report's (`report-snapshot`): a report
  that changed none of its read attributes reads its committed `:db-before`'s
  memo instead of deriving the whole program on the speculative value."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       :seon.db/transaction-report :map]
                  [:or :seon.fn/arity-mismatch-report :seon.db/error-result]]}
  [database projection report {candidates ::candidates bounds ::bounds checked ::checked edges ::edges}]
  (let [candidates (vec candidates)]
    (arity-mismatch-report database
                           (when (seq candidates) (@call-preparation-report-snapshot report projection))
                           bounds candidates checked edges)))

(defn- arity-mismatches-with
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/query :seon.db/database-value] [:or :seon.schema/value :seon.db/error-result]] :seon.db/database-value :seon.schema/projection] [:or :seon.fn/arity-mismatch-report :seon.db/error-result]]}
  [query-fn database projection]
  (let [edges (call-edges query-fn database)
        bounds (when-not (:seon.db/invalid-read edges)
                 (declared-arity-bounds query-fn database))]
    (or (when (:seon.db/invalid-read edges) edges)
        (when (:seon.db/invalid-read bounds) bounds)
        (let [checked (filterv (fn [[_ [callee _]]] (contains? bounds callee)) edges)
              candidates (filterv (fn [[_ [callee n]]]
                                    (not (arity-admitted? (get bounds callee) n)))
                                  checked)]
          (arity-mismatch-report database
                                 (when (seq candidates) (@call-preparation-snapshot database projection))
                                 bounds candidates (count checked) (count edges))))))

(defn arity-mismatches
  "Call sites whose source count no prepared arity of the callee admits.

  An arity error becomes a query over stored call sites instead of a
  load-time surprise. Only a callee whose contract declares arities can be
  compared, so the report carries its own coverage: an empty mismatch list
  beside a zero `:seon.fn/arity-checked` reports that nothing was compared,
  never that everything agrees."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.fn/arity-mismatch-report :seon.db/error-result]]}
  [database]
  (arity-mismatches-with q database (carried-projection database)))

(defn- render-face?
  "Is `attribute` an installed ref naming a function by its identity: a schema
  row's render face (`:seon.fn/reference-to` on its declaration)?"
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value :seon.schema/value] :boolean]}
  [projection database attribute]
  (boolean (and (keyword? attribute)
                (= :db.type/ref (get-in (dbi/-schema database) [attribute :db/valueType]))
                (:seon.fn/reference-to (some-> (mr/schema (:seon.schema.projection/registry projection) attribute)
                                               m/properties)))))

(defn- render-declaration-touched?
  "Does this report write a schema row's key or form, or one of its face refs?"
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] :boolean]}
  [projection report]
  (let [database (:db-after report)]
    (boolean (some (fn [{a :a added :added}]
                     (or (and added (#{:seon.schema/key :seon.schema/form} a))
                         (render-face? projection database a)))
                   (:tx-data report)))))

(defn- write-render-target-error
  "Render declarations this report touched whose function is absent or unmatched.

  A schema row stores each face it declares, own or inherited, as a ref to
  that function's entity (`seon.schema.datahike/storable-properties-in`). A
  report touches a row by writing its key or form, or by asserting or
  retracting one of its face refs: removing a function sweeps every ref to it
  (datahike/db/transaction.cljc:1003-1014), so the rows it breaks are in the
  report. Each touched row that declares a face (`declared-reference` over the
  final projection) must name a defined function, and a stored ref must point
  at it. Work is proportional to the touched rows, never the population."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report]
                  [:or :nil :seon.error/base]]}
  [projection report]
  (let [database (:db-after report)
        registry (:seon.schema.projection/registry projection)
        face? #(render-face? projection database %)
        tx-data (:tx-data report)
        touched-faces (into #{} (comp (map :a) (distinct) (filter face?)) tx-data)
        touched (into (sorted-set)
                      (keep (fn [{e :e a :a added :added}]
                              (when (or (touched-faces a)
                                        (and added (#{:seon.schema/key :seon.schema/form} a)))
                                e)))
                      tx-data)
        faces (when (seq touched) (sort (filter face? (keys (dbi/-schema database)))))
        value-of (fn [e a] (:v (first (d/datoms database :eavt e a))))
        missing
        (into []
              (for [entity touched
                    :let [schema-key (value-of entity :seon.schema/key)
                          compiled (some->> schema-key (mr/schema registry))]
                    :when compiled
                    face faces
                    :let [renderer (schema.datahike/declared-reference compiled face)]
                    :when renderer
                    :let [function (:e (first (d/datoms database :avet :seon.fn/sym renderer)))
                          stored (value-of entity face)]
                    :when (not (and function
                                    (value-of function :seon.program/definition-digest)
                                    (or (nil? stored) (= stored function))))]
                {:seon.schema/key schema-key
                 :seon.render/property face
                 :seon.render/function renderer}))]
    (when (seq missing)
      (diagnostic
       {::transaction-refused true
        :seon.error/message "Render declarations name functions absent from the final program. Admit the definitions or repair the declarations in the same transaction."
        :seon.error/data {:seon.render/declarations missing}
        :seon.error/layer :database-write
        :seon.error/operation 'seon.db/transact!
        :seon.error/member :seon.schema/form
        :seon.error/expected :seon.fn/sym
        :seon.error/offending missing}))))

(defn- removed-definition-error
  "Check surviving names against the identities removed by an admitted change.
   A removed schema row or a retracted Datahike attribute (`:db/ident`) refuses
   while a program row still writes it (plan 1.3e)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :map] [:set :qualified-keyword]] [:or :nil :seon.program/deletion-refused-error]]}
  [database removed identity-attrs]
  (let [breaks
        (into []
              (mapcat
               (fn [identities]
                 (let [function-symbol (:seon.fn/sym identities)
                       namespace-symbol (:seon.ns/name identities)
                       schema-key (:seon.schema/key identities)
                       ;; A retracted Datahike attribute entity is identified by
                       ;; `:db/ident`; a surviving writer still names it.
                       attribute (:db/ident identities)
                       ;; [relation target removed-identity]: the family is known
                       ;; here, so each break carries its typed removed identity.
                       obligations (concat
                                    (when function-symbol
                                      (map #(vector % function-symbol [:seon.fn/sym function-symbol])
                                           [:seon.fn/calls :seon.fn/references :seon.test/subject
                                            :seon.effect/capability]))
                                    (when namespace-symbol
                                      [[:seon.ns/requires namespace-symbol [:seon.ns/name namespace-symbol]]])
                                    (when schema-key
                                      (map #(vector % schema-key [:seon.schema/key schema-key])
                                           [:seon.fn/writes :seon.schema/references
                                            :seon.fn.arity/input-refs :seon.fn.arity/output-refs
                                            :seon.fn.arity/guard-refs]))
                                    (when (qualified-keyword? attribute)
                                      [[:seon.fn/writes attribute [:db/ident attribute]]]))]
                   (for [[attribute target removed-identity] obligations
                         datom (d/datoms database :avet attribute target)
                         :when (or (not= :seon.effect/capability attribute)
                                   (seq (d/datoms database :eavt (:e datom) :seon.fn/sym)))]
                     {:seon.program/subject target
                      :seon.program/identity removed-identity
                      :seon.program/relation attribute
                      :seon.program/referrer
                      (into {}
                            (keep (fn [fact]
                                    (when (identity-attrs (:a fact))
                                      [(:a fact) (:v fact)])))
                            (mapcat #(d/datoms database :eavt %)
                                    (cons (:e datom)
                                          (attribute-holders database :seon.fn/arities (:e datom)))))
                      :db/id (:e datom)}))))
              removed)]
    (when (seq breaks)
      (let [breaks (vec (sort-by pr-str breaks))]
        ;; The producer declares what it refused; `write-observation` carries
        ;; that identity as `:seon.db.write/validator-schema`.
        (vary-meta
         (diagnostic
         {::transaction-refused true
          :seon.program/referrers breaks
          :seon.error/message (str "Program deletion leaves surviving referrers: "
                                   (pr-str breaks)
                                   ". Repair or retract them in the same transaction.")
          :seon.error/layer :database-write
          :seon.error/operation 'seon.db/transact!
          :seon.error/member :seon.program/referrer
          :seon.error/expected :seon.program/deletion-row
          :seon.error/offending breaks})
         assoc :seon.error/declared-schema :seon.program/deletion-refused-error)))))

(defn deletion-error
  "Refuse a publication removing definitions still named in its final database.
   The publisher compares its immutable expected head with the completed candidate;
   its branch compare-and-set must still verify that expected head when publishing."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:maybe :seon.error/value]]}
  [before database]
  (let [identity-attrs (set/union (set (identity-attributes before))
                                  (set (identity-attributes database)))
        removed (into []
                      (mapcat (fn [attribute]
                                (keep (fn [datom]
                                        (when-not (db.utils/entid database [attribute (:v datom)])
                                          {attribute (:v datom)}))
                                      (d/datoms before :avet attribute))))
                      [:seon.fn/sym :seon.ns/name :seon.schema/key])]
    (removed-definition-error database removed identity-attrs)))

(defn- write-agent-retraction-error
  {:malli/schema [:=> [:cat [:sequential :seon.db/transaction-report-datom]] [:or :nil :seon.error/base]]}
  [effective-datoms]
  (let [removed
        (into []
              (comp (filter #(and (= :seon.agent/id (:a %))
                                  (false? (:added %))))
                    (map #(hash-map :seon.agent/id (:v %) :db/id (:e %)))
                    (distinct))
              effective-datoms)]
    (when (seq removed)
      (diagnostic
       {::transaction-refused true
        :seon.error/message "Agents retain their identities. Archive the agent instead of retracting or renaming it."
        :seon.error/data {:seon.program/referrers removed}
        :seon.error/layer :database-write
        :seon.error/operation 'seon.db/transact!
        :seon.error/member :seon.agent/id
        :seon.error/expected :seon.agent/archived-tx
        :seon.error/offending removed}))))

(defn- write-deletion-error
  [before database affected identity-attrs]
  ;; An identity whose attribute this transaction retired no longer resolves
  ;; as a lookup ref (Datahike refuses one on a non-unique attribute,
  ;; `datahike/db/utils.cljc:124`); its entity's identity is removed.
  (let [surviving (set (identity-attributes database))
        removed
        (into []
              (keep (fn [entity-id]
                      (let [identities
                            (into {}
                                  (keep (fn [datom]
                                          (when (and (identity-attrs (:a datom))
                                                     (or (not (surviving (:a datom)))
                                                         (not (db.utils/entid database
                                                                              [(:a datom) (:v datom)]))))
                                            [(:a datom) (:v datom)])))
                                  (d/datoms before :eavt entity-id))]
                        (when (seq identities) identities))))
              affected)]
    (removed-definition-error database removed identity-attrs)))

(declare retention-report-check)

(defn- report-identity-attributes
  "Every identity attribute installed on either side of a report."
  {:malli/schema [:=> [:cat :seon.db/transaction-report] [:set :keyword]]}
  [report]
  (set/union (set (identity-attributes (:db-before report)))
             (set (identity-attributes (:db-after report)))))

(defn- write-attribute-plans
  "The write plan for every attribute installed in `database`, a function
  of the projection and that installed schema. Datahike's schema also maps
  each attribute entity's id to its ident (`893 :some/attr`); datoms name
  attributes by ident, so only ident keys have plans."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value]
                  [:map-of :keyword :map]]}
  [projection database]
  (schema/projection-cache-value
   projection [::write-attribute-plans (dbi/-schema database)]
   #(into {}
          (comp
           (filter (comp keyword? key))
           (map (fn [[attribute installed]]
                 [attribute (write-attribute-plan
                             projection attribute
                             (= :db.cardinality/many
                                (:db/cardinality installed)))])))
          (merge datahike.schema/implicit-schema-spec
                 (dbi/-schema database)))))

;; `seon.program` and `seon.fn` require `seon.db` (through `seon.fn.schema-shape`).
(defonce ^:private program-definition-digest
  (delay (requiring-resolve 'seon.program/definition-digest)))
;; The one owner of a namespace entity's resolver context (`seon.fn` digests with it).
(defonce ^:private stored-namespace-context
  (delay (requiring-resolve 'seon.fn/stored-namespace-context)))
;; The one answer to which attributes a declaration entity owns: the digest's input.
(defonce ^:private program-shapes-in (delay (requiring-resolve 'seon.program/shapes-in)))
(defonce ^:private program-canonical-entity (delay (requiring-resolve 'seon.program/canonical-row)))

;;; ---------------------------------------------------------------------------
;;; Vectors: the writer's transaction function (vector-storage design §3.1)
;;; ---------------------------------------------------------------------------
;;; The encoder (`schema.datahike/encode-transaction-in`) turns every vector
;;; write on a possibly existing entity into a request and batches a
;;; transaction's requests into ONE call of [[vectors-tx]] at its end, which
;;; runs on the in-transaction value, so an upserted parent resolves. Its
;;; output is logical transaction data the encoder encodes in turn.

(defn- element-kind
  "How a vector attribute's element is stored: `:component`, `:peer` or `:scalar`."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] [:enum :component :peer :scalar]]}
  [database attribute]
  (let [installed (get (dbi/-schema database) attribute)]
    (cond (:db/isComponent installed) :component
          (= :db.type/ref (:db/valueType installed)) :peer
          :else :scalar)))

(defn- comparable-component
  "A component as the value it is matched by, stored (an entity id or lookup
   ref) or submitted (a map): its attributes without `:db/id`, nested
   components as values, peers as entity ids, collections in their declared
   shape."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :map :seon.schema/value] :map]}
  [database projection plans component]
  (into {}
        (keep (fn [[a v]]
                (when (not= :db/id a)
                  (let [one (fn [x]
                              (case (element-kind database a)
                                :component (comparable-component database projection plans x)
                                :peer (db.utils/entid database (if (map? x) (:db/id x) x))
                                :scalar x))]
                    [a (if (db.utils/multival? database a)
                         (schema.datahike/logical-collection projection a (map one (if (coll? v) v [v])))
                         (one v))]))))
        (if (map? component)
          component
          (write-entity-value database projection plans (db.utils/entid database component)))))

(defn- element-key
  "What makes a desired element the same element as a stored one: the entity
   for a peer or a component named by an existing id, the logical value for a
   scalar or a component map named by no stored id; nil when it can only be
   new (a peer tempid)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:enum :component :peer :scalar] [:=> [:cat :seon.schema/value] :map]
                       :seon.schema/value]
                  [:or :nil [:tuple [:enum :id :value] :seon.schema/value]]]}
  [database kind comparable element]
  (let [named (if (map? element) (:db/id element) element)]
    (cond
      (= :scalar kind) [:value element]
      (and (some? named) (not (string? named)) (not (and (number? named) (neg? named))))
      (some->> (db.utils/entid database named) (vector :id))
      (and (= :component kind) (map? element)) [:value (comparable element)]
      :else nil)))

(defn- matched-cells
  "Desired position -> the stored cell it keeps: first every element whose key
   has a cell at its own position, then each remaining element, in order, the
   first unused cell of its key. Linear in cells plus elements."
  {:malli/schema [:=> [:cat [:vector [:or :nil [:tuple [:enum :id :value] :seon.schema/value]]]
                       [:sequential [:tuple [:tuple [:enum :id :value] :seon.schema/value] :map]]]
                  [:map-of :int :map]]}
  [wanted keyed]
  (let [exact (into {} (map (fn [[k cell]] [[k (::position cell)] cell])) keyed)
        first-pass (into {} (keep-indexed (fn [i k] (when-let [cell (and k (get exact [k i]))] [i cell]))) wanted)]
    (first
     (reduce (fn [[matched queues used] [i k]]
               (if (or (nil? k) (contains? matched i))
                 [matched queues used]
                 (let [queue (drop-while #(used (::cell %)) (get queues k))]
                   (if-let [cell (first queue)]
                     [(assoc matched i cell) (assoc queues k (rest queue)) (conj used (::cell cell))]
                     [matched (assoc queues k queue) used]))))
             [first-pass
              (reduce (fn [queues [k cell]] (update queues k (fnil conj []) cell)) {} keyed)
              (into #{} (map ::cell) (vals first-pass))]
             (map-indexed vector wanted)))))

(defn- vector-diff
  "Operations bringing `parent`'s vector `attribute` from its stored cells
   `old` (rows in position order with `::value`) to `desired` (nil = absent),
   keeping the identity of every retained element: a retained element keeps
   its cell and only its position moves; an unmatched cell is retracted,
   destroying a removed component and nothing else; an unmatched element gets
   a new cell. A stored component's value is read only when a desired
   component is matched by value."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.db/entity-id :qualified-keyword
                       [:vector :map] :boolean [:or :nil [:vector :seon.schema/value]]]
                  :seon.store/transaction-data]}
  [database projection parent attribute old present? desired]
  (let [kind (element-kind database attribute)
        plans (delay (write-attribute-plans projection database))
        comparable #(comparable-component database projection @plans %)
        wanted (mapv #(element-key database kind comparable %) desired)
        by-value? (and (= :component kind) (some #(= :value (first %)) wanted))
        keyed (for [{value ::value :as cell} old
                    k (case kind
                        :scalar [[:value value]]
                        :peer [[:id value]]
                        :component (cond-> [[:id value]] by-value? (conj [:value (comparable value)])))]
                [k cell])
        matched (matched-cells wanted keyed)
        used (into #{} (map ::cell) (vals matched))]
    (cond-> (into (into []
                        (mapcat (fn [[position element]]
                                  (if-let [same (get matched position)]
                                    (cond-> []
                                      (not= position (::position same))
                                      (conj [:db/add (::cell same) :seon.db.vector/position position])
                                      ;; A retained component named by id may carry changed attributes.
                                      (and (map? element) (= :id (first (nth wanted position))) (> (count element) 1))
                                      (conj (assoc element :db/id (::value same))))
                                    (let [cell (str "seon.db.vector/" (id/id))
                                          named? (and (map? element) (some? (:db/id element)) (not (string? (:db/id element))))]
                                      (cond-> [{:db/id cell :seon.db.vector/position position
                                                attribute [(if named? (db.utils/entid database (:db/id element)) element)]}
                                               [:db/add parent :seon.db.vector/cells cell]]
                                        (and named? (> (count element) 1)) (conj element))))))
                        (map-indexed vector desired))
                  (comp (remove #(used (::cell %))) (map #(vector :db/retractEntity (::cell %))))
                  old)
      (and (some? desired) (not present?)) (conj [:db/add parent :seon.db.vector/present attribute])
      (and (nil? desired) present?) (conj [:db/retract parent :seon.db.vector/present attribute]))))

(defn- vectors-tx
  "Transaction function: apply every vector request of one transaction.
   Requests on one [entity attribute] fold in order (`:assert` replaces, `:add`
   appends, `:retract` removes every occurrence, `:clear` makes it absent),
   then one [[vector-diff]] per vector. Cells a sweep emptied on a requested
   parent are retracted with them.

   # Costs
   Per parent: one EAVT seek per cell, read once for all its vectors; per
   vector an O(n log n) sort and linear matching."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.schema.datahike/vector-requests]
                  :seon.store/transaction-data]}
  [database projection requests]
  (into []
        (mapcat
         (fn [[parent parent-requests]]
           (let [rows (cell-rows database (mapv :v (d/datoms database :eavt parent :seon.db.vector/cells)))
                 present (into #{} (map :v) (d/datoms database :eavt parent :seon.db.vector/present))]
             (into (mapv #(vector :db/retractEntity (::cell %)) (filter (comp empty? ::members) rows))
                   (mapcat
                    (fn [[attribute vector-requests]]
                      (let [kind (element-kind database attribute)
                            old (into [] (comp (filter #(= attribute (:a (first (::members %)))))
                                               (map #(assoc % ::value (:v (first (::members %))))))
                                      (sort-by ::position rows))
                            resolved (fn [x] (if (and (not= :scalar kind) (not (map? x))) (db.utils/entid database x) x))
                            desired (reduce (fn [current [_ _ operation argument]]
                                              (case operation
                                                :assert argument
                                                :add (conj (or current []) argument)
                                                :retract (filterv #(not= (resolved argument) (resolved %)) current)
                                                :clear nil))
                                            (when (present attribute) (mapv ::value old))
                                            vector-requests)]
                        (vector-diff database projection parent attribute old (contains? present attribute) (some-> desired vec))))
                   (group-by second parent-requests)))))
         (group-by first
                   (mapv (fn [[handle attribute operation argument]]
                           [(or (db.utils/entid database handle)
                                (throw (ex-info (str "The entity whose vector " attribute " is written does not exist.")
                                                {::vector-parent-absent handle ::attribute attribute})))
                            attribute operation argument])
                         requests)))))

(defn stored-collection
  "`values` of cardinality-many `attribute` as `database` stores them: a vector
   in order when the attribute is stored in cells, otherwise a set (order and
   duplicates are not stored, so they must not count as a change)."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword [:or [:sequential :seon.schema/value] [:set :seon.schema/value]]]
                  [:or [:vector :seon.schema/value] [:set :seon.schema/value]]]}
  [database attribute values]
  (if (schema.datahike/vector-attribute-in? (carried-projection database) attribute)
    (vec values)
    (set values)))

(defn stored-entity
  "`entity-id`'s attributes in `database` as values: refs and components as
   entity ids, EDN slots decoded, each cardinality-many value in its declared
   collection and each stored vector read from its cells in order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] [:map-of :keyword :seon.schema/value]]}
  [database entity-id]
  (let [projection (carried-projection database)]
    (write-entity-value database projection (write-attribute-plans projection database) entity-id)))

(defn vector-migration-tx
  "Encoded transaction data moving every member stored directly on a parent
   into cells with presence, in datom order (the order digests were computed
   over): `[:db/retract p a v]`, which does not cascade, so components keep
   their identity. Empty until all three bridge attributes are installed, and
   when every member already lives in a cell.

   # Costs
   One AEVT range per installed vector-shaped attribute of `projection`, one
   EAVT seek per member."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection] :seon.store/transaction-data]}
  [database projection]
  (let [installed (dbi/-schema database)]
    ;; Every bridge attribute must be installed before a cell or presence
    ;; fact is written; until then the members stay where they are.
    (if-not (every? #(get installed %) [:seon.db.vector/cells :seon.db.vector/position :seon.db.vector/present])
      []
      (schema.datahike/already-encoded
       (into []
            (comp
             (filter #(and (keyword? %) (get installed %) (schema.datahike/vector-shaped-attr-in? projection %)))
             (mapcat
             (fn [attribute]
               (let [legacy (remove #(first (d/datoms database :eavt (:e %) :seon.db.vector/position))
                                    (d/datoms database :aevt attribute))]
                 (mapcat (fn [members]
                           (let [parent (:e (first members))]
                             (conj (mapv #(vector :db/retract parent attribute (:v %)) members)
                                 {:db/id parent
                                  :seon.db.vector/present #{attribute}
                                  :seon.db.vector/cells
                                  (into [] (map-indexed (fn [position datom]
                                                          {:seon.db.vector/position position attribute [(:v datom)]}))
                                        members)})))
                         (partition-by :e legacy))))))
            (keys installed))))))

(defn- declared-collections
  "`entity-map`, read datom by datom, with each attribute of `many` in its
   declared collection and each present vector restored from its cell maps."
  {:malli/schema [:=> [:cat :seon.schema/projection :map [:set :keyword]] :map]}
  [projection entity-map many]
  (let [cells (:seon.db.vector/cells entity-map)
        present (:seon.db.vector/present entity-map)
        entity-map (dissoc entity-map :seon.db.vector/cells :seon.db.vector/present)]
    (into (reduce (fn [result a]
                    (if (contains? result a)
                      (update result a #(schema.datahike/logical-collection projection a %))
                      result))
                  entity-map many)
          (map (fn [a]
                 [a (into [] (comp (filter #(contains? % a))
                                   (map #(first (get % a))))
                          (sort-by :seon.db.vector/position cells))]))
          present)))

(defn- declaration-value
  "`entity-id` in `database` as the row that wrote it: a component as its nested
  row, another reference as its target's identity lookup ref, a
  cardinality-many value in its declared collection (the writer's own
  `schema.datahike/logical-collection`: a declared vector, such as ordered seed
  occurrences, in position order when stored in cells, else in datom order).
  `seon.program/definition-digest` of its canonical row
  reproduced all 7,040 stored function and test digests (2026-09-25)."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value [:vector :qualified-keyword] :int] :map]
                  [:=> [:cat :seon.schema/projection :seon.db/database-value
                        [:vector :qualified-keyword] :int] :map]]}
  ([database identity-attrs entity-id]
   (declaration-value (carried-projection database) database identity-attrs entity-id))
  ([projection database identity-attrs entity-id]
   ;; Components recurse through a local function: one armed call per
   ;; declaration, where each component cost another (15,797 calls for 4,166
   ;; declarations in a 306-file publication).
   (let [installed-schema (dbi/-schema database)]
     (letfn [(value-of [entity-id]
               (let [[entity-map many]
                     (reduce (fn [[entity-map many] {:keys [a v]}]
                               (let [installed (get installed-schema a)
                                     v (cond (not= :db.type/ref (:db/valueType installed)) v
                                             (:db/isComponent installed) (value-of v)
                                             :else (or (some #(some->> (first (d/datoms database :eavt v %)) :v (vector %))
                                                             identity-attrs)
                                                       v))]
                                 (if (= :db.cardinality/many (:db/cardinality installed))
                                   [(update entity-map a (fnil conj []) v) (conj many a)]
                                   [(assoc entity-map a v) many])))
                             [{} #{}] (d/datoms database :eavt entity-id))]
                 (declared-collections projection entity-map many)))]
       (value-of entity-id)))))

(defn definition-digest-of
  "The definition digest the writer admits for function or test `row`:
  `seon.program/definition-digest` of the attributes its family owns
  (`seon.program/canonical-row`) with the resolver context
  (`seon.fn/stored-namespace-context`) of `namespace-row`, the namespace row
  the same transaction leaves stored. The one digest owner: the writer checks
  every changed row against it, and every producer digests through it."
  {:malli/schema [:=> [:cat :seon.schema/projection :map [:maybe :map]]
                  :seon.program/definition-digest]}
  [projection entity-map namespace-entity]
  (@program-definition-digest
   (@program-canonical-entity
    (schema/projection-cache-value projection ::program-shapes #(@program-shapes-in projection))
    entity-map)
   (some-> namespace-entity (@stored-namespace-context))))

(defn- namespace-digests-tx
  "Transaction data re-deriving, on the transaction's own value `database`, the
  digest of every function and test of `namespaces` (the namespace rows this
  transaction writes) that its definition under the stored namespace row no
  longer produces: a require or alias change changes their resolver context.
  Declarations the transaction writes itself (`written`) keep the writer's check."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection [:set :symbol]
                       [:set :seon.program/declaration-identity]]
                  [:vector [:tuple [:= :db/add] :int [:= :seon.program/definition-digest] :string]]]}
  [database projection namespaces written]
  (let [identity-attrs (identity-attributes database)]
    (vec (for [namespace-name namespaces
               :let [namespace-entity (:e (first (d/datoms database :avet :seon.ns/name namespace-name)))]
               :when namespace-entity
               :let [namespace-entity-map (declaration-value projection database identity-attrs namespace-entity)]
               [family namespace-attribute] [[:seon.fn/sym :seon.fn/ns] [:seon.test/sym :seon.test/ns]]
               :when (get (dbi/-schema database) namespace-attribute)
               entity (d/q '[:find [?e ...] :in $ ?a ?n :where [?e ?a ?n]] database namespace-attribute namespace-entity)
               :let [entity-map (declaration-value projection database identity-attrs entity)
                     derived (definition-digest-of projection entity-map namespace-entity-map)]
               :when (and (not (written [family (get entity-map family)])) (not= derived (:seon.program/definition-digest entity-map)))]
           [:db/add entity :seon.program/definition-digest derived]))))

(defn- write-digest-error
  "The first function or test row this report changed whose stored definition
  digest is not the one its final value produces with its namespace's resolver
  context, as `seon.fn` produces it. Every digest comparison (acquisition,
  install, test reach) trusts the stored digest, so the writer decides it.
  Only a change to an attribute the row's family owns (`seon.program/shapes-in`,
  the attributes `canonical-row` hands the digest) is checked: evidence written
  onto a declaration entity (results, reach) is not its definition."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       [:vector :qualified-keyword]]
                  [:or :nil :seon.error/base]]}
  [projection report identity-attrs]
  (let [database (:db-after report)
        shapes (schema/projection-cache-value projection ::program-shapes
                                              #(@program-shapes-in projection))
        ;; Only the families this store installed: Datahike refuses a datom
        ;; read naming an attribute its schema lacks (a raw blob branch).
        families (into {} (keep (fn [family]
                                  (when (get (dbi/-schema database) family)
                                    [family (set (:seon.program/owned-attributes (get shapes family)))])))
                       [:seon.fn/sym :seon.test/sym])
        touched (reduce (fn [result {:keys [e a]}] (update result e (fnil conj #{}) a))
                        (sorted-map) (logical-datoms projection report))
        ;; One namespace entity per namespace in the report, not per entity.
        namespace-of (memoize #(declaration-value projection database identity-attrs %))]
    (some (fn [[entity-id attributes]]
            (when-let [identity-attribute
                       (some (fn [[family owned]]
                               (when (and (some owned attributes)
                                          (first (d/datoms database :eavt entity-id family)))
                                 family))
                             families)]
              (let [entity-map (declaration-value projection database identity-attrs entity-id)
                    namespace-name (second (or (:seon.fn/ns entity-map) (:seon.test/ns entity-map)))
                    namespace-entity (some->> namespace-name (d/datoms database :avet :seon.ns/name) first :e)
                    stored (:seon.program/definition-digest entity-map)
                    produced (definition-digest-of projection entity-map (some-> namespace-entity namespace-of))]
                (when (not= stored produced)
                  (diagnostic
                   {::transaction-refused true
                    :seon.error/message
                    (str "The stored definition digest of " (pr-str [identity-attribute (get entity-map identity-attribute)])
                         " is not the one its definition produces. Write the entity its canonical writer produced.")
                    :seon.error/layer :database-write
                    :seon.error/operation 'seon.db/transact!
                    :seon.error/member :seon.program/definition-digest
                    :seon.error/expected produced
                    :seon.error/offending (or stored :seon.error/unknown)
                    :seon.error/data {::entity {identity-attribute (get entity-map identity-attribute)}}})))))
          touched)))

(defn- write-report-error
  "One final check for native operations and all expanded transaction-function output."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] [:or :nil :seon.db/error-result]]}
  [projection report]
  (let [database (:db-after report)
        before (:db-before report)
        attempted (:datahike/attempted-tx-data report)
        affected (distinct (map :e (concat attempted (:tx-data report))))
        identity-attrs (report-identity-attributes report)
        attribute-plans (write-attribute-plans projection database)
        ;; Forced in its place in the check order below; the arity gate
        ;; reads its changed identity attributes after it has passed.
        owned (delay (write-owned-values-error projection report attribute-plans identity-attrs))]
    (or
     (retention-report-check projection report affected)
     (when (render-declaration-touched? projection report)
       (write-render-target-error projection report))
     (some (fn [datom]
             (when (:added datom)
               (let [attribute (:a datom)
                     plan (get attribute-plans attribute)
                     value ((or (::decode plan) identity) (:v datom))
                     validate (::validate plan)]
                 (when (or (not (or (get (dbi/-schema database) attribute)
                                    (contains? datahike.schema/schema-keys attribute)))
                           (nil? plan) (and validate (not (validate value))))
                   (when-let [failure (write-attribute-error
                                       database projection attribute value
                                       [(:e datom) attribute] nil true)]
                     (write-error-identity
                      failure
                      (select-keys (write-entity-value database projection attribute-plans (:e datom))
                                   identity-attrs)))))))
           attempted)
     (write-agent-retraction-error (:tx-data report))
     (write-deletion-error before database affected identity-attrs)
     (when-not (find @owned ::changed-identity-attributes) @owned)
     (write-digest-error projection report (identity-attributes database))
     ;; Arity admission depends on declarations, their owned children and
     ;; supplied defaults. The owning-value walk already found
     ;; every changed root on both sides, including swept/deleted children.
     ;; An error, message or other ordinary fact cannot change call arities.
     (when (some (::changed-identity-attributes @owned)
                 [:seon.fn/sym :seon.test/sym :seon.schema/key :seon.call-preparation/key])
       (let [comparison (report-arity-comparison projection report)
             result (arity-verdict database projection report comparison)
             mismatches (:seon.fn/arity-mismatches result)]
         (if-not mismatches
           (assoc result ::transaction-refused true)
           (when (seq mismatches)
           (diagnostic
            {::transaction-refused true
             :seon.error/message "Recorded source argument counts disagree with the final prepared arities. Repair the callers or declaration in the same transaction."
             :seon.error/data {:seon.fn/arity-mismatches mismatches}
             :seon.error/layer :database-write
             :seon.error/operation 'seon.db/transact!
             :seon.error/member :seon.fn/call-arities
             :seon.error/expected :seon.fn/prepared-arities
             :seon.error/offending mismatches}))))))))

(defn- written-declaration-identities
  "The schema keys and function symbols whose declaration datoms this report
  asserted or retracted, named as the final value holds them: `[keys symbols]`.
  An identity the write removed has no final name here; the writer's render
  target check owns a removed renderer."
  {:malli/schema [:=> [:cat :seon.db/transaction-report]
                  [:tuple [:set :keyword] [:set :qualified-symbol]]]}
  [report]
  (let [after (:db-after report)
        name-of (fn [entity attribute]
                  (when (get (dbi/-schema after) attribute)
                    (:v (first (d/datoms after :eavt entity attribute)))))]
    (reduce (fn [[schema-keys function-symbols] {entity :e attribute :a}]
              (case attribute
                (:seon.schema/key :seon.schema/form)
                [(if-let [k (name-of entity :seon.schema/key)] (conj schema-keys k) schema-keys)
                 function-symbols]
                (:seon.fn/sym :seon.fn/spec)
                [schema-keys
                 (if-let [f (name-of entity :seon.fn/sym)] (conj function-symbols f) function-symbols)]
                :seon.schema.admission/source
                [(if-let [k (name-of entity :seon.schema/key)] (conj schema-keys k) schema-keys)
                 (if-let [f (name-of entity :seon.fn/sym)] (conj function-symbols f) function-symbols)]
                [schema-keys function-symbols]))
            [#{} #{}]
            (:tx-data report))))

(defn- validate-write-report
  "Datahike's final-report validator (`validate-report`,
  datahike/db/transaction.cljc:1224-1234): the refusal of the database this
  report leaves, judged by that database's own declarations. A write that
  changes declarations (adoption retiring a required key with its data) is
  judged by the population it installs, never the one it replaces; any other
  write's final value reuses its basis's projection (`carried-projection`).
  A value holding no declaration rows is judged by the construction
  projection `prepared` the write was prepared with.

  Deriving a projection re-admits nothing (reading history must open it), so
  the declarations this report changed are admitted here, by the projection
  it leaves (`schema/admit-declarations!`): completeness and renderer
  coherence judge writes, never reads."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report]
                  [:or :nil :seon.db/error-result]]}
  [prepared report]
  (let [database (:db-after report)
        declared? (declares-program? database)
        projection (if declared? (carried-projection database) prepared)]
    (when declared?
      (let [[schema-keys function-symbols] (written-declaration-identities report)]
        (when (or (seq schema-keys) (seq function-symbols))
          (schema/admit-declarations! projection schema-keys function-symbols))))
    (schema/call-with-projection projection
      (fn []
        (let [attribute :seon.config.db/validation-node-limit
              configured (when (get (dbi/-schema database) attribute)
                           (map :v (d/datoms database :aevt attribute)))
              ;; Every asserted policy on this branch applies to every writer.
              ;; Before config construction, the projection carries the declared default.
              limit (if (seq configured) (apply min configured) (get projection attribute))]
          (write-report-error (assoc projection attribute limit) report))))))

(defn- retention-rules [projection]
  (schema/projection-cache-value
   projection ::retention-rules
   #(into []
          (keep (fn [[attribute _form]]
                  (let [properties (m/properties (mr/schema (:seon.schema.projection/registry projection) attribute))]
                    (when-let [activation (:seon.db/append-only-after properties)]
                      {:seon.db/attribute attribute
                       :seon.db/activation activation
                       :seon.db/authority (:seon.db/retraction-authority properties)}))))
          (:seon.schema.projection/forms projection))))

(defn- retention-snapshot [database rules entities]
  (let [identities (vec (identity-attributes database))
        ;; A non-temporal database keeps no history, and Datahike throws
        ;; rather than answering `history` for one. Its current datoms ARE
        ;; its complete record, so activation and authority derive from the
        ;; database itself; asking for history there refused every write to
        ;; every non-temporal store the moment one retention rule existed.
        history (if (dbi/-temporal-index? database) (d/history database) database)]
    (into {}
      (mapcat
       (fn [{attribute :seon.db/attribute activation :seon.db/activation authority :seon.db/authority}]
         (let [activated (into #{} (filter #(some :added (d/datoms history :eavt % activation)))
                               entities)
               creators (when (and authority (db.utils/entid database authority))
                          (into {} (keep (fn [entity]
                                           (when-let [creator (first (sort-by :tx (filter :added
                                                                            (d/datoms history :eavt entity authority))))]
                                             [entity (:v creator)]))) entities))]
           ;; An entity holding none of the rule's facts on this side has no
           ;; entry: `retention-check` reads an absent entry exactly as one with
           ;; no values, assignment, activation or creators, so only entities
           ;; that carry the rule pay for their identity pulls.
           (into []
            (keep
             (fn [entity]
               (let [values (into #{} (map :v) (d/datoms database :eavt entity attribute))
                     assignment (into #{} (map :v) (d/datoms database :eavt entity activation))
                     active? (boolean (activated entity))
                     creator (get creators entity)
                     current-creator
                     (when (and authority (db.utils/entid database authority))
                       (:v (first (d/datoms database :eavt entity authority))))]
                 (when (or (seq values) (seq assignment) active? creator current-creator)
                   [[attribute entity]
                    {:seon.db/entity (d/pull database identities entity)
                     :seon.db/values values
                     :seon.db/value-identities
                     (into {} (map (fn [value] [value (d/pull database identities value)])) values)
                     :seon.db/active? active?
                     :seon.db/assignment assignment
                     :seon.db/creator creator
                     :seon.db/current-creator current-creator}]))))
            entities)))
       (filter #(and (get (dbi/-schema database) (:seon.db/attribute %))
                     (get (dbi/-schema database) (:seon.db/activation %)))
               rules)))))

(defn- retention-check
  "The first retained membership this write would break, as a returned write
   refusal; nil accepts. It returns rather than throws: Datahike's report
   validator rejects on any returned value (`validate-report`,
   datahike/db/transaction.cljc:1224-1234) and `transact-call` returns that
   `:datahike/validation-refusal`, whereas a throw becomes an unknown outcome.
   Retracting the whole entity as its authority (its creator) is a deletion,
   not an authority change, so it is admitted; `deleted` holds the entities
   this write left with no datoms."
  {:malli/schema [:=> [:cat :map :map [:or :nil :int] [:set :int]] [:or :nil :seon.error/base]]}
  [before after actor deleted]
  (some
   (fn [[_ entity :as key]]
    (let [prior (get before key) current (get after key)
          creator (or (:seon.db/creator prior) (:seon.db/creator current))
          authorized? (and actor creator (= actor creator))
          active? (or (:seon.db/active? prior) (:seon.db/active? current))
          removed (set/difference (:seon.db/values prior #{}) (:seon.db/values current #{}))
          erased (into #{} (keep (fn [[value identity]]
                                  (when (and ((:seon.db/values current #{}) value)
                                             (not (set/subset? (set (keys identity))
                                                               (set (keys (get-in current [:seon.db/value-identities value]))))))
                                    value)))
                       (:seon.db/value-identities prior))
          changed-authority? (and (or creator (:seon.db/active? prior))
                                  (not= (:seon.db/creator prior)
                                        (:seon.db/current-creator current)))
          changed-assignment? (and (:seon.db/active? prior)
                                   (not= (:seon.db/assignment prior #{})
                                         (:seon.db/assignment current #{})))]
      (when (and (not (and authorized? (deleted entity)))
                 (or (and active? (seq (:seon.db/values prior))
                          (empty? (:seon.db/values current)))
                     (and active? (not authorized?) (or (seq removed) (seq erased) changed-assignment?))
                     (and (:seon.db/creator prior) changed-authority?)
                     (and (:seon.db/active? prior) (not authorized?) changed-authority?)))
        (diagnostic
         {::transaction-refused true
          :seon.error/message
          (str "Started issue tests and their authority must be preserved; only the creator may remove tests. "
               "Cannot retract " (pr-str (first key)) " of "
               (pr-str (or (:seon.db/entity prior) (:seon.db/entity current)))
               "; tests " (pr-str (mapv #(get-in prior [:seon.db/value-identities %])
                                        (set/union removed erased))) ".")
          :seon.error/layer :database-write
          :seon.error/operation 'seon.db/transact!
          :seon.error/member (first key)
          :seon.error/data
          {:seon.db/attribute (first key)
           :seon.db/entity (or (:seon.db/entity prior) (:seon.db/entity current))
           :seon.db/removed-values (set/union removed erased)
           :seon.db/user actor :seon.db/creator creator}}))))
   (set/union (set (keys before)) (set (keys after)))))

(defn- retention-report-check
  "Check touched owners and owners of touched retained targets in the final report.
   Reverse AVET seeks include an identity-only target retraction; transaction
   functions and reference sweeps are already expanded by this authority."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       [:sequential :int]] [:or :nil :seon.error/base]]}
  [projection report affected]
  (let [before (:db-before report)
        after (:db-after report)
        rules (retention-rules projection)
        rule-attributes (into #{} (mapcat (juxt :seon.db/attribute :seon.db/activation :seon.db/authority)) rules)
        new-after (:max-eid before)
        ;; An entity beyond `:db-before`'s max-eid had no facts before; it
        ;; carries a rule's facts after only through this report's datoms.
        carrying (into #{} (comp (filter #(rule-attributes (:a %))) (map :e)) (:tx-data report))
        affected (remove #(and (> (long %) (long new-after)) (not (carrying %))) affected)
        entities (into (set affected)
                       (for [database [before after]
                             {attribute :seon.db/attribute} rules
                             :when (get (dbi/-schema database) attribute)
                             entity affected
                             datom (d/datoms database :avet attribute entity)]
                         (:e datom)))
        user (get-in report [:tx-meta :seon.db/user])
        actor (when user (db.utils/entid before user))]
    (retention-check (retention-snapshot before rules entities)
                     (retention-snapshot after rules entities) actor
                     (into #{} (remove #(first (d/datoms after :eavt %))) entities))))

(defn- attempted-transaction
  "`transaction` as the operations it submitted, each transaction function named
  by its function and never carried with its arguments.

  The arguments are the caller's live inputs (a projection, a request holding
  a world), which the caller still holds. Copied into an error, one config
  apply printed 8.1 MB that could not be read back (duplicate keys in the
  projection's compiled cache). Every other operation is kept as submitted."
  {:malli/schema [:=> [:cat :seon.store/transaction] :seon.store/transaction]}
  [transaction]
  (let [named (fn [operations]
                (mapv #(if (and (sequential? %) (= :db.fn/call (first %)))
                         [:db.fn/call (second %)]
                         %)
                      operations))]
    (if (map? transaction)
      (cond-> transaction (contains? transaction :tx-data) (update :tx-data named))
      (named transaction))))

(defn- write-observation
  "Carry the refused request's operations and immutable pre-write basis as data.
  Recorder admission owns its bounded stored projection."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction :seon.error/base]
                  [:and :seon.db.write/validation-refusal :seon.db/error-result]]}
  [database transaction observation]
  (vary-meta
   (-> observation
      ;; The validator's own declared identity (e.g. a deletion refusal) is
      ;; provenance beside the generic write-attempt identity, never replaced.
      (cond-> (some-> (meta observation) :seon.error/declared-schema
                      (not= :seon.db.write/validation-refusal))
        (assoc :seon.db.write/validator-schema (:seon.error/declared-schema (meta observation))))
      (assoc :seon.db.write.attempt/request-id (id/id)
             :seon.error/basis
             {:seon.error.basis/store (datahike.store/store-identity (:store (dbi/-config database)))
              :seon.error.basis/branch (:branch (dbi/-config database))
              :seon.error.basis/commit (d/commit-id database)
              :seon.error.basis/t (dbi/-max-tx database)})
      (assoc-in [:seon.error/data :seon.db.write.attempt/transaction] (attempted-transaction transaction)))
   assoc :seon.error/declared-schema :seon.db.write/validation-refusal))

(defn- prepared-write
  "The request the writer applies for `transaction` on `database`: JDK
  integers as longs, the final-report validator in its metadata, values
  encoded by `projection`. Vectors are written as cells once `database` has
  the bridge attributes installed, even before schema rows declare them (a
  publication installs attributes, then writes its rows); before that, the
  members a transaction writes move into cells at its end
  (`vector-migration-tx`), once it installs them."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value :seon.store/transaction] :map]}
  [projection database transaction]
  (let [installed? (every? #(get (dbi/-schema database) %)
                           [:seon.db.vector/cells :seon.db.vector/position :seon.db.vector/present])
        declared? (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
        encoding (cond-> projection (and installed? (not declared?)) (assoc :seon.schema.datahike/cells-installed? true))
        request (schema.datahike/encode-transaction-in
                 encoding #'vectors-tx
                 (jdk-integers->long
                  (assoc-in (if (map? transaction) transaction {:tx-data transaction})
                            [:tx-meta :datahike/validate-report] #(validate-write-report projection %))))
        entity-maps (filter map? (:tx-data request))
        ;; A namespace entity written here re-derives its declarations' digests in this transaction.
        namespaces (into #{} (keep #(when (:seon.program/definition-digest %) (:seon.ns/name %))) entity-maps)
        written (into #{} (keep #(some (fn [a] (when-some [v (get % a)] [a v])) [:seon.fn/sym :seon.test/sym])) entity-maps)]
    (cond-> request
      ;; A transaction on a store without the bridge attributes may install
      ;; them (a publication's declarations): the members it and earlier
      ;; transactions wrote move into cells at its end, once installed.
      (not (or installed? declared?))
      (update :tx-data #(conj (vec %) [:db.fn/call vector-migration-tx projection]))
      (seq namespaces) (update :tx-data #(conj (vec %) [:db.fn/call namespace-digests-tx projection namespaces written])))))

(defn- write-refusal
  "The declared value of a write that threw: the validator's refusal, a Seon
  transition refusal verbatim, Datahike's classified abort, else a diagnostic
  whose outcome is unknown."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction :seon.error/throwable]
                  :seon.db/error-result]}
  [database transaction throwable]
  (let [data (error.refusal/refusal throwable)]
    (cond
      (:datahike/validation-refusal data) (:datahike/validation-refusal data)
      (:seon.error/at data) data
      (some? (:error data)) (rejected-value database transaction throwable data)
      :else
      (write-observation
       database transaction
       (error.refusal/diagnostic
        {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.db/database-write
         :seon.error/operation 'seon.db/transact!
         :seon.error/throwable throwable
         :seon.error/message (or (ex-message throwable) (.getName (class throwable)))
         :seon.error/data (or data {})
         :seon.db/transaction-outcome-unknown true})))))

(defn- durable-watch!
  "Hand `pending`'s durable outcome to `durable-failed!` when it is a failure.
  A virtual thread waits on the commit, so neither the writer nor the caller
  does; a committed report is the ordinary outcome and calls nothing."
  {:malli/schema [:=> [:cat :seon.await/blocking-deref :seon.instrument/callable] :nil]}
  [pending durable-failed!]
  (Thread/startVirtualThread
   ^Runnable (fn []
               (when-let [failure (try (let [report @pending]
                                         (when-not (map? report) report))
                                       (catch Throwable failure failure))]
                 (durable-failed! failure))))
  nil)

(defn- transact-call
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.db/connection :seon.store/transaction
         [:? :seon.instrument/callable]]
    [:or :seon.db/transaction-report :seon.db/error-result]]}
  ;; An omitted `durable-failed!` stays absent: the write answers committed.
  [database connection transaction & [durable-failed!]]
  (try
    (let [projection (carried-projection database)]
      (or (write-error database projection transaction)
          (let [bound-attribute :seon.config.db/write-time-limit-ms
                configured-bounds
                (when (get (dbi/-schema database) bound-attribute)
                  (map :v (d/datoms database :aevt bound-attribute)))
                declared-bound
                (:seon.config/default
                 (m/properties (mr/schema (:seon.schema.projection/registry projection) bound-attribute)))
                ;; Every write, system writes included, is bounded by the
                ;; branch's dial (lane-flow-owns-running-machinery.md N3,
                ;; R6): a synchronous fault writer must never park forever.
                write-time-limit-ms
                (if (seq configured-bounds)
                  (apply min configured-bounds)
                  declared-bound)
                request (prepared-write projection database
                                        (stamp-transaction database transaction batch/*world*
                                                           (process/id (process/current-identity))))
                timeout (Object.)
                ;; One deadline covers admission and acknowledgement: the
                ;; writer's put! into its transaction queue runs inside the
                ;; go block whose promise this derefs
                ;; (datahike/writer.cljc:46-55, 393-402).
                started (System/nanoTime)
                ;; Parents make it Datahike's merge: same fence and validator.
                ;; An applied write awaits the writer's application, in order,
                ;; and hands the commit to `durable-watch!` (datahike fork,
                ;; writer.cljc `transact!` with an `applied` port).
                applied (when durable-failed! (async/promise-chan))
                pending (cond (:parents request) (d/merge-db! connection request)
                              applied (datahike.writer/transact! connection request applied)
                              :else (d/transact! connection request))
                remaining #(max 0 (- write-time-limit-ms
                                     (quot (- (System/nanoTime) started) 1000000)))
                report
                (try
                  (if applied
                    (let [[value port] (async/alts!! [applied (async/timeout (remaining))] :priority true)]
                      (cond (not= port applied) timeout
                            (instance? Throwable value) (throw value)
                            ;; only an applied write has a commit to watch: a
                            ;; refusal at application is answered above
                            :else (do (durable-watch! pending durable-failed!)
                                      (assoc value :seon.db/durable pending))))
                    (deref pending (remaining) timeout))
                  (catch Throwable throwable
                    ;; Datahike's throwable-promise wraps the JDK timeout in
                    ;; ExceptionInfo. Preserve every delivered writer error.
                    (if (or (instance? TimeoutException throwable)
                            (instance? TimeoutException (ex-cause throwable)))
                      timeout
                      (throw throwable))))]
            (if (identical? timeout report)
              (let [elapsed-ms
                    (quot (+ (- (System/nanoTime) started) 999999) 1000000)
                    evidence
                    {:seon.db/connection-identity
                     (connection-identity connection)
                     :seon.store/branch (:branch (:config database))
                     :seon.config.db/write-time-limit-ms write-time-limit-ms
                     :seon.db/write-wait-elapsed-ms elapsed-ms
                     ;; The refusal hands back its operations; the caller
                     ;; must not retry it blindly (the outcome is unknown).
                     :seon.store/transaction (attempted-transaction transaction)
                     :seon.db/transaction-outcome-unknown true}]
                (diagnostic
                 {:seon.error/message (str "seon.db/transact! stopped waiting after " elapsed-ms
                       " ms (bound " write-time-limit-ms
                       " ms). Datahike may still commit the queued transaction; "
                       "its outcome is unknown.")
                  :seon.db/transaction-outcome-unknown true
                  :seon.error/layer :database-write
                  :seon.error/operation 'seon.db/transact!
                  :seon.error/expected {:seon.config.db/write-time-limit-ms write-time-limit-ms}
                  :seon.error/offending (select-keys evidence
                               [:seon.db/connection-identity
                                :seon.store/branch])
                  :seon.error/data (merge evidence {:seon.error/member bound-attribute})}))
              report))))
    (catch Throwable throwable
      (write-refusal database transaction throwable))))

(defn with
  "The value `transaction` would leave on `database`, never committed: the
  writer's own checks and final-report validator (`prepared-write`), applied
  by Datahike's `with`, carrying `database`'s projection state. A refusal is
  the writer's declared value (`write-refusal`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction]
                  [:or :seon.db/database-value :seon.db/error-result]]}
  [database transaction]
  (try
    (let [projection (carried-projection database)]
      (or (write-error database projection transaction)
          (carry-projection-state (:db-after (d/with database (prepared-write projection database transaction)))
                                  (:seon.sci.eval/projection-state (meta database)))))
    (catch Throwable throwable
      (write-refusal database transaction throwable))))

(defn- missing-transaction-data-error
  {:malli/schema [:=> [:cat :seon.schema/value] [:or :nil :seon.db/invalid-request-error]]}
  [transaction]
  (when (and (map? transaction)
             (not (contains? transaction :tx-data)))
    (diagnostic
     {:seon.error/message "seon.db/transact! argument maps require :tx-data."
      :seon.db/missing-request-member :tx-data
      :seon.db/invalid-request true
      :seon.error/layer :database-write
      :seon.error/operation 'seon.db/transact!
      :seon.error/expected [:map [:tx-data :seon.store/transaction-data]]
      :seon.error/offending transaction
      :seon.error/data {:seon.error/source transaction}})))

(defn- rendered-value
  [unit]
  (if (map? (:seon.render/value unit))
    (:seon.render/value unit)
    unit))

(defn render-transaction-ai
  "A transaction's identities, changed attributes, tempids and bases as data.
  Pull each distinct entity once per database value, sharing the identities
  between changed-entity rows and tempids. No database contents are returned."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report]
    [:or :seon.db.report/view :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [unit]
  (let [{database :db-after before :db-before tempids :tempids :as report} (rendered-value unit)
        transaction-data (if (database-value? database) (logical-datoms report) (:tx-data report))
        facts (filterv #(not= (:e %) (:tx %)) transaction-data)
        grouped (group-by :e facts)
        temporary (into {} (filter (fn [[k _]] (and (string? k) (not= "datomic.tx" k)))) tempids)
        ids (vec (sort (into (set (keys grouped)) (vals temporary))))
        selector (when (and (seq ids) (database-value? database))
                   (vec (sort (identity-attributes database))))
        read-rows (fn [basis]
                    (if (= 1 (count ids))
                      (let [row (pull basis selector (first ids))]
                        (if (or (:seon.db/invalid-read row) (:datahike.budget/name row)) row [row]))
                      (pull-many basis selector ids)))
        old-rows (if (and selector (database-value? before)) (read-rows before) [])
        new-rows (if (and selector (vector? old-rows)) (read-rows database) [])]
    (cond
      (not (vector? old-rows)) old-rows
      (not (vector? new-rows)) new-rows
      :else
      (let [rows (merge-with merge (zipmap ids old-rows) (zipmap ids new-rows))
            priority (sort-by str selector)
            identities (into {} (keep (fn [[entity row]]
                                        (when-let [identity (some #(find row %) priority)]
                                          [entity (vec identity)]))) rows)
            entities (mapv (fn [[entity datoms]]
                             (assoc (if-let [identity (get identities entity)]
                                      {:seon.db.report/identity identity} {:seon.db.report/entity entity})
                                    :seon.db.report/changed (vec (sort (distinct (map :a datoms))))))
                           (sort-by key grouped))
            resolved (into (sorted-map) (keep (fn [[tempid entity]]
                                               (when-let [identity (get identities entity)] [tempid identity]))) temporary)
            tx (some :tx transaction-data)]
        (cond-> {:seon.db.report/facts (count facts) :seon.db.report/entities entities}
          tx (assoc :seon.db.report/tx tx)
          (database-value? before) (assoc :seon.db.report/basis-before (basis-t before))
          (database-value? database) (assoc :seon.db.report/basis-after (basis-t database))
          (seq resolved) (assoc :seon.db.report/tempids resolved)
          (some :seon.db.report/identity entities)
          (assoc :seon.render/operations
                 (into [] (comp (keep :seon.db.report/identity) (take 3)
                                (map #(list 'seon.db/pull ''[*] %))) entities)))))))

(defn- transaction-value-html
  [value]
  (cond
    (inst? value) (.format (java.text.SimpleDateFormat. "MMM d, yyyy HH:mm:ss") value)
    (map? value) (into [:dl]
                       (map (fn [[attribute child]]
                              [:div [:dt (if (keyword? attribute) (name attribute) (str attribute))]
                               [:dd (transaction-value-html child)]]))
                       (sort-by (comp str key) (dissoc value :db/id)))
    (and (vector? value) (= 2 (count value)) (qualified-keyword? (first value)))
    (transaction-value-html (second value))
    (coll? value) (into [:ul] (map #(vector :li (transaction-value-html %))) value)
    (keyword? value) (name value)
    :else (str value)))

(defn render-transaction-html
  "Render a committed transaction report as bounded readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] :seon.render/hiccup]}
  [unit]
  (let [{database :db-after :as report} (rendered-value unit)
        transaction-data (if (database-value? database) (logical-datoms report) (:tx-data report))
        datom-count (count transaction-data)
        identity-value (if (database-value? database)
                         (database-value-identity database) database)]
    [:article {:class "seon-family-entry seon-db-transaction-entry"}
     [:h3 "Committed transaction"]
     [:p (str "Changes: " datom-count)]
     [:details {:data-preserve-attr "open"} [:summary "Transaction details"]
      [:dl
       [:div [:dt "Transaction"] [:dd (str (:t identity-value))]]
       (when-let [commit (:datahike/commit-id identity-value)]
         [:div [:dt "Commit"] [:dd (str commit)]])]]
     (when (seq transaction-data)
       (into [:ol {:class "seon-db-transaction-datoms"}]
             (map (fn [datom]
                    (let [attribute (:a datom)
                          value (:v datom)
                          reference? (and (database-value? database)
                                          (db.utils/ref? database attribute))
                          shown (if reference?
                                  (pull database (vec (identity-attributes database)) value)
                                  value)]
                      [:li [:span {:class "seon-datom-action"} (if (:added datom) "Added" "Removed")]
                       " " [:strong (str attribute)] " "
                       (if (and reference? (empty? (dissoc shown :db/id)))
                         "related record" (transaction-value-html shown))])))
             transaction-data))]))

(defn render-rejection-ai
  "A rejected database transaction's AI face: `seon.error/render-ai`'s data."
  {:malli/schema
   [:=> [:cat :seon.db.write/validation-refusal] [:or [:string {:min 1}] :seon.error/reply]]}
  [unit]
  (@error-render-ai unit))

(defn render-rejection-html
  "Render a rejected database transaction as readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db.write/validation-refusal] :seon.render/hiccup]}
  [unit]
  (@error-render-html unit))

(defn- transaction-result
  {:malli/schema
   [:=> [:cat [:or :seon.db/transaction-report :seon.db/error-result]]
    [:or :seon.db/transaction-result :seon.db/error-result]]}
  [report]
  (if-not (:db-after report)
    report
    (let [before (:db-before report)
          after (:db-after report)
          identities (set (identity-attributes after))]
      (letfn [(reference [eid visited]
                (if (visited eid) eid
                    (or (some (fn [database]
                                (some (fn [datom]
                                        (when (identities (:a datom))
                                          [(:a datom)
                                           (if (db.utils/ref? database (:a datom))
                                             (reference (:v datom) (conj visited eid))
                                             (:v datom))]))
                                      (sort-by (comp str :a)
                                               (d/datoms database :eavt eid))))
                              [after before])
                        eid)))]
        {::tx (get (:tempids report) :db/current-tx)
         ::datoms (mapv (fn [datom]
                          [(reference (:e datom) #{}) (:a datom)
                           (:v datom) (:added datom)])
                        (:tx-data report))}))))

(defn- transact-through
  "The one validated write path of `transact!` and `transact-applied!`: answers
  the committed report, or with `durable-failed!` the applied one."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value] :seon.store/transaction
         [:? :seon.instrument/callable]]
    [:or :seon.db/transaction-report :seon.db/transaction-refused-error]]}
  [connection transaction & [durable-failed!]]
   (let [database (when (connection? connection) (resolve-database-value connection))
         result
         (or
          (missing-transaction-data-error transaction)
          (cond
            (not (connection-object? connection)) connection

            (not (connection? connection))
            (dependency-error
             'seon.db/transact!
             (ex-info "The explicit transaction connection is not live."
                      {::connection (connection-identity connection)}))

            (not (db.utils/db? database)) database

            :else
            (or (foreign-connection-error database connection transaction)
                (if durable-failed!
                  (transact-call database connection transaction durable-failed!)
                  (transact-call database connection transaction)))))]
     (if-not (:db-after result)
       (let [refusal (assoc result :seon.db/transaction-refused true)]
         (if (:seon.db.write.attempt/request-id refusal)
           refusal
           (if (database-value? database)
             (write-observation database transaction refusal)
             (vary-meta (assoc refusal :seon.db.write.attempt/request-id (id/id)) assoc :seon.error/declared-schema :seon.db.write/validation-refusal))))
       result)))

(defn transact!
  "Validate authored transaction data and commit through the calling connection.

  Attribute values and identified entity maps use the supplied cluster schema
  projection. Invalid writes return their authored form, offending value, path,
  and applicable entity form; unknown attributes include installed candidates.
  Datahike owns native schema declarations, reference resolution, uniqueness,
  and transaction-function execution, retaining its refusal classifications.

  Each commit records its process generation, operation and calling agent when
  one exists. A request map may supply :seon.db.transaction/operation and
  :seon.db.transaction/agent; otherwise the first named transaction function
  (or seon.db/transact!) and the evaluation's agent supply them. These observed
  names remain readable after their subjects retire. Attribution adds no commit.

  With the connection omitted, return :seon.db/tx and :seon.db/datoms as
  [entity attribute value added?] vectors. Entities use installed identity
  lookup refs when available, including identities removed by this transaction.
  Tempids are resolved. The next read observes the changed database.
  Explicit-connection system callers retain Datahike's full report.
  A map may carry `:datahike/expected-basis-t` (a stale head refuses before
  any datom lands) and `:parents`, immutable commit ids that make the write
  Datahike's multi-parent merge commit through the same validation.

  Every write, system writes included, is bounded by the branch's declared
  :seon.config.db/write-time-limit-ms fact; one deadline covers writer
  admission and acknowledgement. If that bound fires, this returns
  :seon.db/write-bound-exceeded with :seon.db/transaction-outcome-unknown
  true. The queued transaction is not cancelled and may still commit, so the
  caller must not assume rollback or retry it blindly.

  When `*conn*` is bound, an explicit connection must have the same Datahike
  connection ID. An absent binding means the caller is outside an agent
  evaluation, so a live explicit connection is allowed."
  {:malli/schema
  [:function
    [:=> [:cat :seon.store/transaction]
     [:or :seon.db/transaction-result :seon.db/transaction-refused-error]]
    [:=> [:cat [:or :seon.db/connection :seon.error/value] :seon.store/transaction]
     [:or :seon.db/transaction-report :seon.db/transaction-refused-error]]]}
  ([transaction]
   (transaction-result (transact! (current-connection) transaction)))
  ([connection transaction]
   (transact-through connection transaction)))

(defn transact-applied!
  "Write `transaction` through `transact!`'s validated path and answer once the
  writer has APPLIED it, in writer order, without waiting for its durable
  commit: the report's `:db-after` is the writer's uncommitted value, which
  every later transaction on this connection builds on, and its
  `:seon.db/durable` is the pending commit (deref answers the committed report
  or the writer's failure). Readers of the connection see it once the commit
  lands; listeners fire then.

  Durability follows. A failed commit is terminal for the writer (Datahike
  refuses every later write with `:writer-shut-down`) and is handed to
  `durable-failed!` with its throwable, off the writer's thread: the caller
  names what it lost and stops loudly there. Refusals at application answer
  here exactly as `transact!`'s do.

  Use it only for writes nobody needs durable before their caller is
  answered (AGENTS \"Recover facts, not execution\"): an agent form's own
  evaluation settlement is the one caller."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value] :seon.store/transaction
         :seon.instrument/callable]
    [:or :seon.db/transaction-report :seon.db/transaction-refused-error]]}
  [connection transaction durable-failed!]
  (transact-through connection transaction durable-failed!))
