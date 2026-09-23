(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.core.cache.wrapped :as cache]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [editscript.core :as editscript]
            [editscript.edit :as editscript.edit]
            [malli.core :as m]
            [datahike.api :as d]
            [datahike.connector :as connector]
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
            [seon.env :as env]
            [seon.error.refusal :as error.refusal]
            [seon.id :as id]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.registry :as mr]
            [seon.schema.internal :as internal])
  (:import [datahike.db AsOfDB DB]
           [java.util.concurrent TimeoutException]
           [datalog.parser.type And BindColl BindTuple BindScalar Constant FindColl FindRel FindScalar
            FindTuple Not Or Pattern Pull Variable]))

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

(defonce ^:private call-preparation-arities
  (delay (requiring-resolve 'seon.call-preparation/prepared-arities)))

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

(defn- schema-refusal
  "Return the actual unavailable value and the declared shape it cannot satisfy."
  {:malli/schema
   [:=> [:cat :qualified-symbol :seon.schema/key :seon.schema/value :string :map]
    :seon.schema/validation-refusal]}
  [operation expected value message evidence]
  {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.db/acquisition
    :seon.error/operation operation
    :seon.error/message message
    :seon.db/invalid-read true
    :seon.db/refused-read-operation operation
    :seon.schema/expected-value expected
    :seon.schema/refused-value value
    :seon.error/data (merge evidence {:seon.error/layer :database-read})})

(defn- dependency-error
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable] :seon.db/error-result]}
  [operation error]
  (if (:seon.schema/expected-value (ex-data error))
    (assoc (ex-data error) :seon.db/invalid-read true
           :seon.db/refused-read-operation
           operation)
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

(declare write-report-validator)

(defn carry-connection-projection-state!
  "Attach the owning projection state to a live connection at construction.

  Datahike exposes connection metadata from its wrapped atom. Keep only the
  state pointer there; each database acquisition captures its own snapshot."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.sci.eval/projection-state]
    :seon.db/connection]}
  [connection state]
  (write-report-validator (:seon.schema/projection @state))
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
  (if (and state (not (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))))
    (let [projection (or (:seon.schema/projection (meta database))
                         (:seon.schema/projection @state))]
      (cond-> (vary-meta database assoc :seon.sci.eval/projection-state state)
        projection (vary-meta assoc :seon.schema/projection projection)))
    database))

(defn carry-derived-projection
  "Derive and carry the immutable projection owned by `database` itself.

  Commit database values have no live connection whose projection state they
  can inherit.  Derive from that exact value's installed program facts once
  and attach the resulting snapshot, so later reads never depend on a caller's
  handed projection."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.db/database-value]}
  [database]
  (if (:seon.schema/projection (meta database))
    database
    (let [runtime-projection
          (schema/build-projection (schema.edn/packaged-forms))
          database-projection (schema/projection-from-database database)
          database-delta
          (schema/projection-delta runtime-projection database-projection)
          projection
          (schema/materialize-projection
           (schema/compose-projection-data runtime-projection database-delta))]
      (vary-meta database assoc :seon.schema/projection projection))))

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
    (catch Throwable cause
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

(defn connection-identity
  "Plain-data identity of a Datahike connection, or the typed unknown.
  A RELEASED connection has no derivable identity: its wrapped state is
  `:released`, so there is no `:config` to identify and the dependency's
  `connection-id` has nothing to dispatch on. That is an unavailable
  observation, which is a typed value here — never a throw out of an
  identity projection, and never a silent nil."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value]]
    [:or :seon.db/connection-identity :seon.error/value]]}
  [connection]
  (cond
    (and (map? connection) (inst? (:seon.error/at connection))
             (qualified-keyword? (:seon.error/layer connection))
             (qualified-symbol? (:seon.error/operation connection))) connection

    (not (map? (:config @connection)))
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

  `seon.test.runner/run-var!` is the caller this exists for. An agent running
  its own tests inside its evaluation is doing that cluster's work; a host
  REPL running the same test Var is not, and hands nothing."
  {:malli/schema [:=> [:cat :seon.db/custody-request [:fn clojure.core/ifn?]]
                  [:or :seon.schema/validation-refusal
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A scope wrapper returns its body's arbitrary result unchanged."
                         :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [request f]
  (binding [*conn* (:seon.db/connection request) *read-database* nil]
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
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
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
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
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

(defn- append-read-evidence!
  [evidence]
  (when *read-evidence-sink*
    (swap! *read-evidence-sink* conj evidence))
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
              (or (db.utils/db? value)
                  (connector/connection? value)
                  (record? value))
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

(defn- stable-read-result
  [request result]
  (if (bounded-read-request? request)
    (stable-value result)
    [false nil]))

(defn- read-result-digest
  [result]
  (let [[stable? value] (stable-value result)]
    (when stable?
      (try
        (schema/sha-256
         [(.getBytes ^String (schema/canonical-data-string value) "UTF-8")])
        (catch clojure.lang.ExceptionInfo cause
          (if (= :seon.schema/noncanonical-projection-data
                 (:seon.schema/error (ex-data cause)))
            nil
            (throw cause)))))))

(declare pull-index-patterns)

(defn- query-index-patterns
  "Retain pattern dependencies through conjunction, negation and disjunction.
  Nested clauses bind against their enclosing positive patterns, including
  entities negation excludes. Datahike resolves those bindings; this walker
  never implements joins. Unsupported clauses keep general evidence."
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
                    (or (get-in clause [:source :symbol]) inherited))]
    (let [groups
          (mapv
           (fn [bindings]
            (letfn [(supported? [clause]
              (or (instance? Pattern clause)
                  (and (or (instance? Not clause) (instance? Or clause)
                           (instance? And clause))
                       (every? supported? (:clauses clause)))))
            (resolve-bindings [clauses bound]
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
        (let [pulls (filterv #(and (instance? Pull %)
                                  (= source (source-of % '$)))
                            (parser/find-elements (:qfind parsed)))
              pull-groups
              (when (seq pulls)
                (let [bound-rows (resolve-bindings (:qwhere parsed) bindings)]
                  (for [element pulls
                        :let [selector (second (value-of bindings (:pattern element)))]]
                    (when (sequential? selector)
                      (pull-index-patterns
                       database [selector (mapv #(get % (get-in element [:variable :symbol]))
                                                 bound-rows)]
                       :pull-many (pull-api/compile-pull-plan database selector))))))]
          (when-not (some nil? pull-groups)
            (into [] (distinct)
                  (concat (scope-patterns (:qwhere parsed) bindings '$)
                          (mapcat identity pull-groups))))))))
           input-bindings)]
      (when (every? some? groups)
        (into [] (comp cat (distinct)) groups)))))

(defn- pull-index-patterns
  "Bind finite explicit pull dependencies to their selected entities.
  General selectors retain their existing semantic read evidence."
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
  [database arguments operation-key response result]
  (let [selector
        (pull-api/pull-plan-selector
         (:datahike.pull/plan response))
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
       patterns (assoc :seon.db/read-index-patterns patterns)))))

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

  Process-local caches may explicitly retain stable read results for semantic
  replay. The default omits them so evaluation evidence written as database
  facts cannot inline read payloads."
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
           (let [[replayable? stable-result]
                 (when (and (:seon.db/retain-read-results? options)
                            (find entry :seon.db/read-result))
                   (stable-read-result (:seon.db/read-request entry)
                                       (:seon.db/read-result entry)))
                 result-digest
                 (when (and (:seon.db/read-request entry)
                            (find entry :seon.db/read-result))
                   (read-result-digest (:seon.db/read-result entry)))]
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
                                   (assoc :seon.db/read-index-patterns patterns
                                          :seon.db/read-basis-t (basis-t database))))
                               sources)))
               plan)
             :datahike.read/revision
             (dependency-revision database plan source-position)}
             (:seon.db/read-request entry)
             (assoc :seon.db/read-request (:seon.db/read-request entry))

             result-digest
             (assoc :seon.db/read-result-digest result-digest)

             replayable?
             (assoc :seon.db/read-result stable-result))))
         captured)))

(declare decode-index-page decode-query-result decode-pull-result read-declarations
         with-declarations encode-query-request)

(defn- pull-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-plan-with-evidence
         arguments))

(defn- pull-many-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-many-plan-with-evidence
         arguments))

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
              parsed-query (query/memoized-parse-query (:query query-request))
              response (d/q-with-evidence
                        (encode-query-request declarations
                                              query-request parsed-query))]
          (decode-query-result declarations
                               query-request
                               parsed-query
                               (:datahike.query/result response)))

        :pull
        (let [arguments (:seon.db/pull-arguments request)
              response (apply pull-plan-with-evidence database arguments)]
          (decode-pull-result declarations
                              (:datahike.pull/plan response)
                              :datahike.pull/result
                              (:datahike.pull/result response)))

        :pull-many
        (let [arguments (:seon.db/pull-arguments request)
              response (apply pull-many-plan-with-evidence database arguments)]
          (decode-pull-result declarations
                              (:datahike.pull/plan response)
                              :datahike.pull-many/result
                              (:datahike.pull-many/result response)))

        :index-page
        (decode-index-page declarations
                           database
                           (d/index-page database
                                         (:seon.db/index-page-options request)))

        (diagnostic
         {:seon.error/message (str "seon.db/replay-read cannot replay the read operation "
               (pr-str (:seon.db/read-operation request)) ".")
          :seon.db.read/unknown-read-operation (:seon.db/read-operation request)
          :seon.db/invalid-read true
          :seon.error/layer :database-read
          :seon.error/operation 'seon.db/replay-read
          :seon.error/member :seon.db/read-operation
          :seon.error/expected :seon.db/read-operation
          :seon.error/data {:seon.db/read-request request}})))))

(defn- index-pattern-change
  [changes pattern]
  (let [entity (:seon.db/pattern-entity pattern)
        attribute (:seon.db/pattern-attribute pattern)
        value (find pattern :seon.db/pattern-value)
        [index components]
        (cond
          entity [:eavt (cond-> [entity] attribute (conj attribute))]
          (and attribute value (db.utils/indexing? changes attribute))
          [:avet [attribute (val value)]]
          attribute [:aevt [attribute]]
          :else [:eavt []])]
    (some #(when (or (nil? value) (= (val value) (:v %))) %)
          (apply d/datoms changes index components))))

(declare read-evidence-current? datom->data)

(defn read-evidence-changes
  "Return one changed datom witnessing each retained index pattern.
  Witnesses use the same matching operation as cache validity. Broad read
  plans contribute an attribute-scoped witness; they do not acquire an
  entity or value constraint that their reader did not retain."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:vector :seon.db/read-evidence] :seon.db/basis-t]
                  [:or :seon.db/datoms :seon.db/error-result]]}
  [database retained basis]
  (with-declarations database 'seon.db/read-evidence-changes
   (fn [declarations]
   (into []
        (comp (map #(datom->data declarations database %)) (distinct))
        (mapcat
         (fn [{position :seon.db/source-argument-position
               plan :datahike.read/dependency-plan :as evidence}]
           (when-not (read-evidence-current? database [evidence])
            (let [source (some #(when (= position (:datahike.query.source/argument-position %)) %)
                              (:datahike.query.dependency/sources plan))
                 attributes (:datahike.query.source/attributes source)
                 patterns (or (:seon.db/read-index-patterns source)
                              (if (set? attributes)
                                (mapv #(hash-map :seon.db/pattern-attribute %) attributes)
                                [{}]))
                 changes (d/since (d/history database)
                                  (or (:seon.db/read-basis-t source) basis))]
             (keep #(index-pattern-change changes %) patterns))))
         retained)))))

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
  "An exact index check when historical datoms retain the read's dependencies.
  Return no decision for a different database origin or discarded history.

  With `current`'s revisions, only the patterns of attributes whose revision
  moved are checked: an unchanged revision already proves its attribute's
  datoms (`changed-read-attributes`), and an entity-only pattern (a pulled
  `:db/id`) reads nothing outside the plan's explicit attribute set. A moved
  attribute without a pattern, or without history, gives no decision.
  Without narrowing, every pattern needs an attribute with history.

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
        history? (fn [attribute] (not (:db/noHistory (get installed attribute))))
        changed (changed-read-attributes revision current)
        checked (if changed
                  (let [covered (into #{} (keep :seon.db/pattern-attribute) patterns)]
                    (when (and (every? covered changed) (every? history? changed))
                      (filterv #(contains? changed (:seon.db/pattern-attribute %)) patterns)))
                  (when (every? (fn [pattern]
                                  (when-let [attribute (:seon.db/pattern-attribute pattern)]
                                    (history? attribute)))
                                patterns)
                    patterns))]
    (when (and patterns basis checked
               (instance? DB database)
               (= (select-keys revision [:datahike.cache/connection-id :datahike.cache/generation])
                  (select-keys context [:datahike.cache/connection-id :datahike.cache/generation])))
      (or (empty? checked)
          (let [changes (d/since (d/history database) basis)]
            (not-any? #(index-pattern-change changes %) checked))))))

(defn read-evidence-current?
  "True when `database` still satisfies every retained dependency revision."
  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
                       [:vector :seon.db/read-evidence]]
                  [:or :boolean :seon.db/error-result]]}
  [database retained]
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
    database
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
                 indexed (index-evidence-current database source revision current)]
             (if (some? indexed)
               indexed
               ;; A replay's declared failures are values (`q`, `pull` and
               ;; `datoms` return their refusal), which compare unequal. A
               ;; throw is not declared, so it propagates with its cause.
               (when (and (find evidence :seon.db/read-request)
                          (or (find evidence :seon.db/read-result)
                              (find evidence :seon.db/read-result-digest)))
                 (let [replayed
                       (replay-read database (:seon.db/read-request evidence))
                       [replayable? result]
                       (stable-read-result
                        (:seon.db/read-request evidence)
                        replayed)]
                   (or (and (find evidence :seon.db/read-result)
                            replayable?
                            (= (:seon.db/read-result evidence) result))
                       (when-let [expected
                                  (:seon.db/read-result-digest evidence)]
                         (when-let [actual
                                    (read-result-digest replayed)]
                           (= expected actual)))))))))))
     retained)))

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
  (->> (:schema (schema-database database))
       (keep (fn [[attribute properties]]
               (when (and (qualified-keyword? attribute)
                          (= :db.unique/identity (:db/unique properties)))
                 attribute)))
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
  {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.schema/projection
    :seon.error/operation 'seon.db/projection-fallback
    :seon.error/message "This operation requires a carried schema projection."
    :seon.db/invalid-read true
    :seon.db/refused-read-operation operation
    :seon.schema/expected-value :seon.schema/projection
    :seon.schema/refused-value {:seon.db/operation operation}})

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
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  (let [context (when (instance? DB database) (:cache-context database))
        candidates (keep (fn [[k cell]] (when (and (map? k) (realized? cell)) [k cell]))
                         @projection-cache)]
    (if (seq candidates)
      @(second (apply max-key #(revision-agreement (or context {}) (first %)) candidates))
      {})))

(defn- content-projection
  "The projection of `database`'s declaration content, memoized by that
  content. A miss derives from its `nearest-base`."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  @(cache/lookup-or-miss projection-cache [::declaration-content (declaration-content-key database)]
                         (fn [_] (delay (schema/load-projection database (nearest-base database))))))

(defn- value-projection
  "The projection of a value whose `value-key` names its declaration content
  exactly, memoized in the bounded value tier, then by content."
  {:malli/schema [:=> [:cat [:or [:map-of :qualified-keyword :seon.schema/value]
                                 [:tuple :qualified-keyword :uuid [:or :int :inst]]]
                            :seon.db/database-value]
                  :seon.schema/projection]}
  [value-key database]
  @(cache/lookup-or-miss value-projection-cache value-key
                         (fn [_] (delay (content-projection database)))))

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
  "True when this value holds declaration rows its projection can derive from."
  {:malli/schema [:=> [:cat :seon.db/database-value] :boolean]}
  [database]
  (boolean (and (get (dbi/-schema database) :seon.schema/key)
                (first (d/datoms database :avet :seon.schema/key)))))

(defn- construction-projection
  "The projection a cold boundary supplied for a value with no declaration rows.

  Genesis and first population write before any declaration row exists, so
  their value cannot derive a projection. This is the one remaining read of
  construction metadata; the projection-as-a-read sweep's cold-owner table
  replaces it with explicit arguments. Absent, it refuses by name."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  (or (:seon.schema/projection (meta database))
      (let [failure (projection-fallback 'seon.db/carried-projection)]
        (throw (ex-info (:seon.error/message failure) failure)))))

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
  rows reads its cold boundary's construction projection or refuses by name."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.schema/projection]}
  [database]
  (let [as-of? (instance? AsOfDB database)
        source (if as-of? database (schema-database database))]
    (cond
      (not (declares-program? source))
      (construction-projection database)

      ;; An as-of view keys by its own declaration datoms, never its origin's.
      as-of?
      (if-let [view-key (as-of-key source)]
        (value-projection view-key source)
        (content-projection source))

      (datahike.db/committed-value-identity source)
      ;; Store the delay before forcing it: concurrent misses share the winning
      ;; cell even when core.cache's atom retries its insertion. A revision
      ;; miss (a new connection or branch) tries the commit, which names one
      ;; value in every connection (`datahike/writing.cljc:363`), then the
      ;; declaration content.
      ;; A revision hit also stores its cell under the commit, so a new branch
      ;; at an equal commit hits too; Datahike's query cache keys committed
      ;; values the same way (fork `684d3290`). Both key kinds share the bound.
      (let [commit-key [::commit (:datahike.value/commit-id (datahike.db/committed-value-identity source))]
            cell (cache/lookup-or-miss
                  projection-cache (projection-cache-key source)
                  (fn [_] (cache/lookup-or-miss
                           projection-cache commit-key
                           (fn [_] (delay (content-projection source))))))]
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
      (let [revision-key (projection-cache-key source)]
        (if (cache/has? projection-cache revision-key)
          @(cache/lookup-or-miss projection-cache revision-key
                                 (fn [_] (delay (content-projection source))))
          (value-projection revision-key source)))

      ;; A detached value (Datahike gives it no context: an `empty-db` or
      ;; `load-entities` basis) keys by its declaration content alone.
      :else
      (content-projection source))))

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
      (diagnostic
       {:seon.error/message (str operation " cannot decode a read: the supplied value carries no installed database schema.")
        :seon.db.read/unreadable-declarations operation
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/member ::installed-schema
        :seon.error/expected :seon.db/database-value
        :seon.error/offending database
        :seon.error/data {:seon.db/db database}}))))

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
       (if (and (map? declarations) (inst? (:seon.error/at declarations))
             (qualified-keyword? (:seon.error/layer declarations))
             (qualified-symbol? (:seon.error/operation declarations)))
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
  (boolean (get (installed-attribute-declarations database) attribute)))

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
    (diagnostic
     {:seon.error/message (str operation " cannot read uninstalled attribute "
           (pr-str attribute) ".")
      :seon.db/invalid-read true
      :seon.error/layer :database-read
      :seon.error/operation operation
      :seon.error/expected {::installed-declaration :seon.error/unknown
       ::registered-candidates (::registered-candidates evidence)}
      :seon.error/offending offending
      :seon.error/data (merge evidence {:seon.error/member attribute})})))

(defn- lookup-ref-error
  {:malli/schema [:=> [:cat :qualified-symbol :seon.db/database-value :seon.schema/value] [:or :nil :seon.error/base]]}
  [operation database entity-id]
  (when (and (sequential? entity-id) (= 2 (count entity-id)))
    (let [[attribute value] entity-id
          evidence (when (keyword? attribute)
                     (attribute-observation database attribute))
          declaration (::installed-declaration evidence)
          valid-value? (and declaration
                            (datahike.schema/value-valid?
                             attribute value
                             (dbi/-schema (schema-database database))))]
      (cond
        (nil? declaration)
        (unknown-attribute-error operation database attribute entity-id)

        (nil? (:db/unique declaration))
        (diagnostic
         {:seon.error/message (str operation " requires a unique lookup-ref attribute; "
               (pr-str attribute) " is not unique.")
          :seon.db/invalid-read true
          :seon.error/layer :database-read
          :seon.error/operation operation
          :seon.error/expected declaration
          :seon.error/offending entity-id
          :seon.error/data (merge evidence {:seon.error/member attribute})})

        (not valid-value?)
        (diagnostic
         {:seon.error/message (str operation " received " (pr-str value) " for "
               (pr-str attribute) ", whose installed value type is "
               (pr-str (:db/valueType declaration)) ".")
          :seon.db/invalid-read true
          :seon.error/layer :database-read
          :seon.error/operation operation
          :seon.error/expected declaration
          :seon.error/offending {::attribute attribute ::value value}
          :seon.error/data (merge evidence {:seon.error/member attribute :seon.error/source {::validation ::value-does-not-match-installed-type
           ::value-type (:db/valueType declaration)}})})))))

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
      (diagnostic
       {:seon.error/message "seon.db/q received a data pattern with more than five positions."
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/operation 'seon.db/q
        :seon.error/expected [:entity :attribute :value :transaction :added]
        :seon.error/offending offending
        :seon.error/data {:seon.error/member offending :seon.error/source request}}))))

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
  [declarations attribute value]
  (cond
    (and (vector? attribute) (some? value))
    (loop [[candidate & remaining] attribute]
      (let [attempt (try {::value (decode-attribute-value declarations candidate value)}
                         (catch Exception failure {::failure failure}))]
        (if-let [failure (::failure attempt)]
          (if (seq remaining) (recur remaining) (throw failure))
          (::value attempt))))
    (and attribute (some? value)) (decode-attribute-value declarations attribute value)
    :else (decode-attribute-maps declarations value)))

(defn- decode-query-tuple
  [declarations attributes tuple]
  (mapv #(decode-query-field declarations
                               (if (map? %1) (nth tuple (::attribute-position %1)) %1) %2)
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
  [declarations return-maps attributes result]
  (let [mapping-keys (query-return-map-keys return-maps)
        attributes-by-key (zipmap mapping-keys attributes)]
    (mapv
     (fn [row]
       (reduce-kv
        (fn [decoded mapping-key value]
          (assoc decoded mapping-key
                 (decode-query-field
                  declarations
                  (let [attribute (get attributes-by-key mapping-key)]
                    (if (map? attribute)
                      (get row (nth mapping-keys (::attribute-position attribute)))
                      attribute)) value)))
        (empty row)
        row))
     result)))

(defn- decode-query-result
  [declarations normalized parsed-query result]
  (let [arguments (:args normalized)
        attributes (query-find-attributes declarations parsed-query arguments)
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
    (if (and (map? result) (contains? result :ret))
      (update result :ret decode-result)
      (decode-result result))))

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
   (if (and (map? connection) (inst? (:seon.error/at connection))
             (qualified-keyword? (:seon.error/layer connection))
             (qualified-symbol? (:seon.error/operation connection)))
     (assoc connection :seon.db/invalid-read true :seon.db/refused-read-operation 'seon.db/db)
     (resolve-database-value connection))))

;;; ---------------------------------------------------------------------------
;;; Suppliers — the two database values call preparation can supply
;;; ---------------------------------------------------------------------------
;;;
;;; These are the functions the two `:seon.call-preparation` rows name. Each
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
                    (catch Throwable cause
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
  [source-bindings arguments]
  (some (fn [source]
          (let [position (:datahike.query.source/argument-position source)]
            (when (< position (count arguments))
              (let [argument (nth arguments position)]
                (when (and (map? argument) (inst? (:seon.error/at argument))
             (qualified-keyword? (:seon.error/layer argument))
             (qualified-symbol? (:seon.error/operation argument)))
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
  (diagnostic
   {:seon.error/message (query-argument-message query-form arguments)
    :seon.db/invalid-read true
    :seon.error/layer :database-read
    :seon.error/operation 'seon.db/q
    :seon.error/expected (get query-form :in '[$])
    :seon.error/offending arguments
    :seon.error/data (merge {::query query-form} {:seon.error/member :in})}))

(defn- query-input-position
  "Return the omitted $ position, :explicit, or :invalid from parsed bindings."
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
                        (db.utils/db? value) (and (map? value) (inst? (:seon.error/at value))
             (qualified-keyword? (:seon.error/layer value))
             (qualified-symbol? (:seon.error/operation value))))))
                sources)]
    (cond
      (not valid-sources?) :invalid
      omitted? position
      (= input-count (count arguments)) :explicit
      :else :invalid)))

(defn- aligned-query-arguments
  [explicit-database query-form arguments]
  (let [arguments (vec arguments)]
    (or (source-argument-error (d/query-source-bindings query-form) arguments)
        (let [position (query-input-position (some? explicit-database) query-form arguments)]
          (case position
            :explicit arguments
            :invalid (query-input-shape-error query-form arguments)
            (let [database (or explicit-database (current-database-value))]
              (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
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
      (and (map? query-or-database) (inst? (:seon.error/at query-or-database))
           (qualified-keyword? (:seon.error/layer query-or-database))
           (qualified-symbol? (:seon.error/operation query-or-database)))
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
    (or (when (and (map? result) (inst? (:seon.error/at result))
             (qualified-keyword? (:seon.error/layer result))
             (qualified-symbol? (:seon.error/operation result))) (:seon.error/message result))
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
  (if (and (map? query-or-database) (inst? (:seon.error/at query-or-database))
             (qualified-keyword? (:seon.error/layer query-or-database))
             (qualified-symbol? (:seon.error/operation query-or-database)))
    (assoc query-or-database :seon.db/invalid-read true :seon.db/refused-read-operation 'seon.db/q)
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
        (if (and (map? aligned) (inst? (:seon.error/at aligned))
             (qualified-keyword? (:seon.error/layer aligned))
             (qualified-symbol? (:seon.error/operation aligned)))
          (assoc aligned :seon.db/invalid-read true :seon.db/refused-read-operation 'seon.db/q)
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
                    (let [response (d/q-with-evidence
                                    (encode-query-request declarations request parsed-query))
                          result (decode-query-result
                                  declarations
                                  request parsed-query
                                  (:datahike.query/result response))]
                      (append-query-evidence! request response result)
                      result)))))))
        (catch Throwable cause
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
      (diagnostic
       {:seon.error/message (str public-operation " argument maps require :selector.")
        :seon.db/invalid-read true
        :seon.error/layer :database-read
        :seon.error/operation public-operation
        :seon.error/expected [:map [:selector :seon.db/pull-selector]]
        :seon.error/offending request
        :seon.error/data {:seon.error/member :selector :seon.error/source request}}))))

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
    (if (and (map? projected) (inst? (:seon.error/at projected))
             (qualified-keyword? (:seon.error/layer projected))
             (qualified-symbol? (:seon.error/operation projected)))
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
          (diagnostic
           {:seon.error/message (str public-operation
                 " returned a value that does not satisfy the derived pulled form "
                 derived-key " of " schema-key ".")
            :seon.db.read/invalid-pulled-result derived-key
            :seon.db/invalid-read true
            :seon.error/layer :database-read
            :seon.error/operation public-operation
            :seon.error/expected (get (:seon.schema.projection/forms projected) derived-key)
            :seon.error/offending value
            :seon.error/data {:seon.schema/key schema-key :seon.db/pull-selector selector}}))))))

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
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
    (assoc database :seon.db/invalid-read true :seon.db/refused-read-operation public-operation)
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
            (diagnostic
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
              :seon.error/data (merge {::operation operation-key} {:seon.error/member :args})})))
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
                response (apply operation database arguments)
                result (decode-pull-result
                        declarations
                        (:datahike.pull/plan response)
                        result-key
                        (get response result-key))
                checked (validate-pulled-result
                         projection public-operation declared-key selector
                         result)]
            (append-pull-evidence! database arguments operation-key response result)
            checked)))
      (catch Throwable cause
        (append-database-evidence! database :all)
        (if (:datahike/budget-exceeded (ex-data cause))
          (pull-budget-error public-operation cause)
          (dependency-error public-operation cause)))))))

(defn- pull-call-valid?
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (or (db.utils/db? head) (and (map? head) (inst? (:seon.error/at head))
             (qualified-keyword? (:seon.error/layer head))
             (qualified-symbol? (:seon.error/operation head)))) tail arguments)]
    (or (and (map? head) (inst? (:seon.error/at head))
             (qualified-keyword? (:seon.error/layer head))
             (qualified-symbol? (:seon.error/operation head)))
        (and (= 1 (count inputs)) (map? (first inputs)))
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
   (if (or (db.utils/db? database-or-selector)
           (and (map? database-or-selector) (inst? (:seon.error/at database-or-selector))
             (qualified-keyword? (:seon.error/layer database-or-selector))
             (qualified-symbol? (:seon.error/operation database-or-selector))))
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
   (if (or (db.utils/db? database-or-selector)
           (and (map? database-or-selector) (inst? (:seon.error/at database-or-selector))
             (qualified-keyword? (:seon.error/layer database-or-selector))
             (qualified-symbol? (:seon.error/operation database-or-selector))))
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
  [database arguments]
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
    (assoc database :seon.db/invalid-read true :seon.db/refused-read-operation 'seon.db/datoms)
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (with-declarations database 'seon.db/datoms
       (fn [declarations]
        (let [result (mapv #(datom->data declarations database %)
                           (apply d/datoms database arguments))
              options (first arguments)
              index (if (map? options) (:index options) options)
              components (if (map? options) (:components options) (rest arguments))
              {plan :datahike.read/dependency-plan pattern :seon.db/read-index-pattern}
              (index-read-dependencies database index components)]
          (append-read-evidence!
           (cond-> {:seon.db/db database
                    :seon.db/source-argument-position 0
                    :datahike.read/dependency-plan plan}
             (instance? DB database)
             (assoc :seon.db/read-index-patterns [pattern])))
          result)))
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error 'seon.db/datoms cause)))))

(defn- datoms-call-valid?
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (db.utils/db? head) tail arguments)
        [index & components] inputs]
    (or (and (map? head) (inst? (:seon.error/at head))
             (qualified-keyword? (:seon.error/layer head))
             (qualified-symbol? (:seon.error/operation head)))
        (if (map? index)
          (empty? components)
          (and (#{:eavt :aevt :avet} index)
               (<= (count components) 4))))))

(defn datoms
  "Eager ordinary datoms from an explicit or current database value."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value :seon.db/index-lookup :keyword] [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike index components include arbitrary attribute values. The function guard checks index, component count and argument-map exclusivity.", :gen/elements [[]]} :seon.schema/value]] [:or :seon.db/datoms :seon.db/invalid-read-error] [:fn #:error{:message "Use (seon.db/datoms index & components) or (seon.db/datoms database index & components); an index argument map takes no trailing arguments, and an index has at most four components."} seon.db/datoms-call-valid?]]}
  [database-or-index & arguments]
  (if (or (db.utils/db? database-or-index)
          (and (map? database-or-index) (inst? (:seon.error/at database-or-index))
             (qualified-keyword? (:seon.error/layer database-or-index))
             (qualified-symbol? (:seon.error/operation database-or-index))))
    (datoms-call database-or-index arguments)
    (datoms-call (current-database-value)
                 (cons database-or-index arguments))))

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
   (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
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
       (catch Throwable cause
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
    (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
    (assoc database :seon.db/invalid-read true :seon.db/refused-read-operation 'seon.db/database-view)

    (not (dbi/-temporal-index? database))
    (do
      (append-database-evidence! database :all)
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.db/database-read
        :seon.error/operation 'seon.db/database-view
        :seon.error/message "The database does not retain temporal indices."
        :seon.db/invalid-read true
        :seon.db/refused-read-operation 'seon.db/database-view
        :seon.config/error-key :seon.config.db/keep-history?
        :seon.error/expected-shape (id/digest 64 [:= true])
        :seon.error/expected [:= true]
        :seon.error/offending false
        :seon.error/data (merge {::operation ::temporal-read
                          :seon.config.db/keep-history? false} {:seon.error/layer :database-read :seon.error/source {:seon.config.db/keep-history? false}})})

    :else
    (try
      (let [result (vary-meta (apply operation database arguments)
                              merge (meta database))]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error 'seon.db/temporal-read cause)))))

(defn- database-identity
  [operation operation-name database]
  (if (and (map? database) (inst? (:seon.error/at database))
             (qualified-keyword? (:seon.error/layer database))
             (qualified-symbol? (:seon.error/operation database)))
    database
    (try
      (let [result (operation database)]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
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

(defn- panic-on-core-error?
  [connection]
  (= :panic
     (d/q '[:find ?mode .
            :where [_ :seon.config/on-core-error ?mode]]
          (d/db connection))))

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
            [nil (error.refusal/diagnostic
                  {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.db/database-read
                   :seon.error/operation 'seon.db/unique-conflict
                   :seon.error/message (or (ex-message lookup) (.getName (class lookup)))
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

(defn- stamp-receipt
  [transaction]
  (if (nil? *receipt*)
    transaction
    (if (map? transaction)
      (update transaction :tx-data
              #(conj (vec %) [:db/add "datomic.tx" ::receipt *receipt*]))
      {:tx-data (conj (vec transaction)
                      [:db/add "datomic.tx" ::receipt *receipt*])})))

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

(defn- write-validator
  [projection compiled]
  (schema/projection-cache-value
   projection [::write-validator compiled]
   #(m/validator compiled)))

(defn- invalid-write
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :seon.schema/value :seon.schema/value [:sequential :seon.schema/value] :seon.schema/value :seon.schema/value :seon.schema/value] :seon.error/base]}
  [_projection attribute compiled value path entity-form cause candidates]
  (let [form (if (= ::attribute-not-installed cause) compiled (m/form compiled))
        problem
        (if (= ::attribute-not-installed cause)
          {:seon.error/argument "transaction data"
           :seon.error/path path
           :seon.error/expected :qualified-keyword
           :seon.error/expected-description "an installed attribute"
           :seon.error/offending attribute
           :seon.error/actual-description "an undeclared attribute"
           :seon.error/fix "Use a declared attribute from the entity's schema."}
          (@error-explain-problem
           {:seon.error/argument "transaction data"
            :seon.error/path path
            :seon.error/problem
            (cond-> {:schema compiled
                     :value value :in path}
              (= :malli.core/missing-key cause) (assoc :type cause))}))]
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
                       (not ((write-validator projection form)
                             (write-value database projection attribute value single?))))
              (invalid-write projection attribute form value path entity-form
                             ::invalid-value nil)))))))

(defn- write-key-candidates
  "Missing declared keys in the closest schema selected by present attributes."
  [projection row]
  (let [present (set (keys row))
        candidates
        (->> (schema/candidate-shapes-in projection row)
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
  [database projection row path]
  (let [schemas (write-entity-schemas projection)
        schema-keys
        (distinct
         (mapcat (fn [attribute]
                   (when (= :db.unique/identity
                            (get-in (dbi/-schema database) [attribute :db/unique]))
                     (get schemas attribute)))
                 (keys row)))
        forms (:seon.schema.projection/forms projection)
        entity-form (some->> schema-keys first (get forms))
        failure
        (some (fn [[attribute value]]
                (if (= :db/id attribute)
                  (write-ref-error database projection value (conj path attribute) entity-form)
                  (write-attribute-error database projection attribute value
                                         (conj path attribute) entity-form false)))
              row)]
    (if (and failure (= :seon.error/unknown (:seon.schema/form failure)))
      (let [candidates (write-key-candidates projection row)]
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
  "Compile final-datom validation and collection normalization on the projection.
   Datahike has already resolved refs; diagnostics still use the submission owner."
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
        ::normalize-many (if (= :set (some-> form m/type)) set vec)
        ::validate (when authored
                     (write-validator projection
                                      (if many?
                                        (schema.datahike/value-schema form)
                                        (mr/schema (:seon.schema.projection/registry projection) attribute))))}))))

(defn- write-entity-value
  "Read a resulting entity as logical values without expanding reference graphs."
  [database projection attribute-plans entity-id & [visit-child]]
  (reduce
   (fn [row datom]
     (let [attribute (:a datom)
           plan (or (get attribute-plans attribute)
                    (write-attribute-plan projection attribute
                                          (db.utils/multival? database attribute)))
           value ((::decode plan) (:v datom))]
       (when (and visit-child (:db/isComponent (get (dbi/-schema database) attribute)))
         (visit-child value))
       (if (db.utils/multival? database attribute)
         (update row attribute (fnil conj #{}) value)
         (assoc row attribute value))))
   {} (d/datoms database :eavt entity-id)))

(defn- write-error-identity
  [refusal identities]
  (-> refusal
      (update :seon.error/message #(str % " Entity: " (pr-str identities) "."))
      (assoc-in [:seon.error/data ::entity] identities)))

(defn- write-entity-error
  "Validate the whole resulting entity, including identities present before a retraction."
  [database projection attribute-plans entity-id identities row & [owned-schemas]]
  (when (seq row)
    (let [forms (:seon.schema.projection/forms projection)
          schemas (write-entity-schemas projection)
          schema-keys (distinct (concat owned-schemas (mapcat #(get schemas %) (keys identities))))
          normalized (reduce-kv (fn [result attribute value]
                                  (assoc result attribute
                                         ;; Final EAVT values are already resolved. A many-value
                                         ;; collection here cannot be submission lookup-ref syntax.
                                         (if (db.utils/multival? database attribute)
                                           ((::normalize-many (get attribute-plans attribute)) value)
                                           value)))
                                {} row)]
      (some
       (fn [schema-key]
         (when-not
          ((write-validator projection (mr/schema (:seon.schema.projection/registry projection) schema-key)) normalized)
           (let [explain (schema/projection-cache-value
                          projection [::write-explainer schema-key]
                          #(schema/projection-explainer projection schema-key))
                 failure (first (:errors (explain normalized)))
                 in (:in failure)
                 attribute (or (first in) (first (keys identities)))
                 value (if (= :malli.core/missing-key (:type failure))
                         :seon.error/unknown
                         (:value failure))
                 refusal (invalid-write projection attribute
                                        (:schema failure)
                                        value (into [entity-id] in) (get forms schema-key)
                                        (or (:type failure) ::invalid-entity) nil)]
             (-> refusal
                 (write-error-identity identities)
                 (update :seon.error/data merge
                         {::entity identities ::entity-value row ::path (into [entity-id] in)})))))
       schema-keys))))

(defn identity-attribute-accumulator?
  "True for the arity gate's accumulator.
  It is a volatile holding a set of qualified keywords."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean (and (volatile? value)
                (set? @value)
                (every? qualified-keyword? @value))))

(defn- write-owned-values-error
  "Validate complete owning values, reached through both sides of this report.
   EAVT supplies every child; AVET discovers owners without pull's 1,000 cap.
   Every changed arity-bearing root's identity attributes are added to
   `changed-identity-attributes`, the arity gate's input."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       ;; Datahike's `:schema` also maps each attribute's entity
                       ;; id to its ident, so the plans carry integer keys too.
                       [:map-of [:or :keyword :int] :map] [:set :qualified-keyword]
                       [:fn seon.db/identity-attribute-accumulator?]]
                  [:or :nil :seon.db/error-result]]}
  [projection report attribute-plans identity-attrs changed-identity-attributes]
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
        seen (volatile! #{})
        rows (volatile! {})
        owners (volatile! {})
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
                    (when-not (@seen cache-key)
                      (when (>= (count @seen) limit)
                        (fail! ::validation-node-limit {::entity entity-id ::visited (count @seen)}))
                      (vswap! seen conj cache-key))))
        row (fn [phase database entity-id]
              (let [cache-key [phase entity-id]]
                (charge! phase entity-id)
                (if-let [entry (find @rows cache-key)]
                  (val entry)
                  (let [value (write-entity-value database projection attribute-plans entity-id
                                                  #(charge! phase %))]
                    (vswap! rows assoc cache-key value)
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
                    (if-let [entry (find @owners cache-key)]
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
                        (vswap! owners assoc cache-key value)
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
            expanded (volatile! {})
            visited (volatile! #{})]
        ;; Iterative postorder avoids using JVM stack depth as the graph bound.
        (doseq [root roots]
          (loop [pending [[root false]] active #{}]
            (when-let [[entity-id exit?] (peek pending)]
              (let [value (row :after after entity-id)
                    child-edges (into []
                                      (mapcat (fn [[a v]]
                                                (when (components a)
                                                  (map #(vector a %) (if (db.utils/multival? after a) v [v])))))
                                      value)]
                (cond
                  exit?
                  (let [whole (reduce (fn [result [a child]]
                                        (if (db.utils/multival? after a)
                                          (update result a (fnil conj []) (get @expanded child))
                                          (assoc result a (get @expanded child))))
                                      (apply dissoc value components) child-edges)]
                    (vswap! expanded assoc entity-id whole)
                    (vswap! visited conj entity-id)
                    (recur (pop pending) (disj active entity-id)))

                  (@visited entity-id) (recur (pop pending) active)
                  (active entity-id) (fail! ::component-cycle {::entity entity-id ::root root})
                  :else
                  (do
                    (doseq [[a child] child-edges]
                      (when-not (get (:seon.schema.projection/forms projection) (get targets a))
                        (fail! ::missing-component-schema {::attribute a ::entity entity-id}))
                      (when (> (count (owners-of :after after child)) 1)
                        (fail! ::multiple-component-owners
                               {::entity child ::owners (owners-of :after after child)}))
                      (when (empty? (row :after after child))
                        (fail! ::missing-component {::entity child ::owner entity-id ::attribute a})))
                    (recur (into (conj (pop pending) [entity-id true])
                                 (map (fn [[_ child]] [child false]) child-edges))
                           (conj active entity-id))))))))
        (when-let [entity-id (first (remove @visited current))]
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
                             tx-data))))]
        (or
         (some (fn [root]
                 (let [value (get @expanded root)
                       identities (merge (select-keys (row :before before root) identity-attrs)
                                         (select-keys value identity-attrs))]
                   (when (or (not (some identities [:seon.fn/sym :seon.test/sym]))
                             (@changed-roots root))
                     (vswap! changed-identity-attributes into (keys identities)))
                   (when (and (seq value) (empty? identities))
                     (fail! ::unowned-entity {::entity root ::entity-value value}))
                   (write-entity-error after projection attribute-plans root identities value)))
               roots)
         (some (fn [[entity-id value]]
                 (when-let [[parent attribute] (first (owners-of :after after entity-id))]
                   (let [target (get targets attribute)
                         identities (select-keys value identity-attrs)]
                     (when-let [refusal (write-entity-error after projection attribute-plans entity-id identities value [target])]
                       (update refusal :seon.error/data assoc
                               ::owner parent ::attribute attribute ::component-schema target)))))
               @expanded))))
      (catch clojure.lang.ExceptionInfo exception
        (if-let [refusal (::owned-refusal (ex-data exception))] refusal (throw exception))))))

(defn- declared-arity-bounds
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/query :seon.db/database-value] [:or :seon.schema/value :seon.db/error-result]] :seon.db/database-value] [:or [:map-of :qualified-symbol [:set [:map [:seon.fn.arity/min :int] [:seon.fn.arity/max {:optional true} :int]]]] :seon.db/error-result]]}
  [query-fn database]
  (let [rows (query-fn '[:find ?function-symbol ?minimum ?maximum
                         :where
                         [?function :seon.fn/sym ?function-symbol]
                         [?function :seon.fn/arities ?arity]
                         [?arity :seon.fn.arity/min ?minimum]
                         [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
                       database)]
    (if (or (:seon.db/invalid-read rows) (:seon.schema/expected-value rows))
      rows
      (reduce
       (fn [bounds [function-symbol minimum maximum]]
         (update bounds function-symbol (fnil conj #{})
                 (cond-> {:seon.fn.arity/min minimum}
                   (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
       {}
       rows))))

(defn- arity-admitted?
  [declared arity]
  (boolean
   (some (fn [{minimum :seon.fn.arity/min maximum :seon.fn.arity/max}]
           (and (<= (long minimum) (long arity))
                (or (nil? maximum) (<= (long arity) (long maximum)))))
         declared)))

(def ^:private call-edges-query
  '[:find ?caller-symbol ?call
    :where [?caller :seon.fn/call-arities ?call]
    (or [?caller :seon.fn/sym ?caller-symbol]
        [?caller :seon.test/sym ?caller-symbol])])

(def ^:private arity-attributes
  "Every attribute the arity gate reads."
  [:seon.fn/call-arities :seon.fn/sym :seon.test/sym :seon.fn/arities
   :seon.fn.arity/min :seon.fn.arity/max])

(defn- error-value?
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (boolean (and (map? value) (inst? (:seon.error/at value))
                (qualified-keyword? (:seon.error/layer value))
                (qualified-symbol? (:seon.error/operation value)))))

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

(defonce ^:private arity-base-cache
  ;; Four recent bases; each key holds the read attributes' datom vectors,
  ;; whose unchanged datoms are the index's own objects.
  (cache/lru-cache-factory {} :threshold 4))

(defn- arity-base
  "The arity gate's whole-program input for a value, memoized by the datoms it
  reads: edges, bounds, the comparison, and the edges indexed by caller and
  callee so a report can replace only the ones it touched."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :map :seon.db/error-result]]}
  [database]
  (let [derive (fn []
                 (let [edges (d/q call-edges-query database)
                       bounds (declared-arity-bounds d/q database)]
                   (if (error-value? bounds)
                     bounds
                     (let [edges (set edges)]
                       (merge (arity-comparison edges bounds)
                              {::edge-set edges
                               ::bounds bounds
                               ::by-caller (group-by first edges)
                               ::by-callee (group-by (comp first second) edges)})))))]
    ;; Keyed by the datoms it reads. The writer's own `:db-before` carries no
    ;; committed identity (Datahike's writer loop threads each report's
    ;; `:db-after`, `datahike/writer.cljc:118`, whose context is speculative,
    ;; `datahike/db.cljc:444`), so no commit id can name it; its datoms can. Datom equality compares e, a
    ;; and v (`datahike/datom.cljc:106`), which is all the gate reads.
    @(cache/lookup-or-miss
      arity-base-cache
      [::arity-content (mapv (fn [attribute]
                               [attribute (if (get (dbi/-schema database) attribute)
                                            (vec (d/datoms database :aevt attribute))
                                            [])])
                             arity-attributes)]
      (fn [_] (delay (derive))))))

(defn- report-arity-comparison
  "The arity comparison of a report's final value from its `:db-before`'s
  memoized base: only edges and bounds under a caller or callee
  symbol this report touched are read again on `:db-after`. An edge belongs to
  its caller's symbol and a bound to its callee's, so every other edge and
  bound is unchanged. Without every read attribute installed before, the
  final value is compared whole."
  {:malli/schema [:=> [:cat :seon.db/transaction-report] [:or :map :seon.db/error-result]]}
  [report]
  (let [before (:db-before report)
        after (:db-after report)
        tx-data (:tx-data report)]
    (if-not (every? #(get (dbi/-schema before) %) arity-attributes)
      (let [edges (d/q call-edges-query after)
            bounds (declared-arity-bounds d/q after)]
        (if (error-value? bounds)
          bounds
          (assoc (arity-comparison (set edges) bounds) ::bounds bounds)))
      (let [base (arity-base before)]
        (if (error-value? base)
          base
          (let [symbols (fn [database entities attributes]
                          (into #{}
                                (for [entity entities attribute attributes
                                      :when (get (dbi/-schema database) attribute)
                                      datom (d/datoms database :eavt entity attribute)]
                                  (:v datom))))
                touched (fn [attributes] (into #{} (comp (filter (comp attributes :a)) (map :e)) tx-data))
                callers (touched #{:seon.fn/call-arities :seon.fn/sym :seon.test/sym})
                arity-owners (into #{}
                                   (for [arity (touched #{:seon.fn.arity/min :seon.fn.arity/max})
                                         database [before after]
                                         datom (dbi/search database [nil :seon.fn/arities arity])]
                                     (:e datom)))
                callees (into (touched #{:seon.fn/arities :seon.fn/sym}) arity-owners)
                caller-symbols (into (symbols before callers [:seon.fn/sym :seon.test/sym])
                                     (symbols after callers [:seon.fn/sym :seon.test/sym]))
                callee-symbols (into (symbols before callees [:seon.fn/sym])
                                     (symbols after callees [:seon.fn/sym]))]
            (if (and (empty? caller-symbols) (empty? callee-symbols))
              (select-keys base [::candidates ::checked ::edges ::bounds])
              (let [removed (into #{} (concat (mapcat (::by-caller base) caller-symbols)
                                              (mapcat (::by-callee base) callee-symbols)))
                    after-callers
                    (when (seq caller-symbols)
                      (d/q '[:find ?caller-symbol ?call
                             :in $ [?caller-symbol ...]
                             :where
                             (or [?caller :seon.fn/sym ?caller-symbol]
                                 [?caller :seon.test/sym ?caller-symbol])
                             [?caller :seon.fn/call-arities ?call]]
                           after caller-symbols))
                    after-bounds
                    (when (seq callee-symbols)
                      (reduce
                       (fn [bounds [function-symbol minimum maximum]]
                         (update bounds function-symbol (fnil conj #{})
                                 (cond-> {:seon.fn.arity/min minimum}
                                   (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
                       {}
                       (d/q '[:find ?function-symbol ?minimum ?maximum
                              :in $ [?function-symbol ...]
                              :where
                              [?function :seon.fn/sym ?function-symbol]
                              [?function :seon.fn/arities ?arity]
                              [?arity :seon.fn.arity/min ?minimum]
                              [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
                            after callee-symbols)))
                    bounds (merge (apply dissoc (::bounds base) callee-symbols) after-bounds)
                    affected (into (set after-callers)
                                   (remove #(caller-symbols (first %)))
                                   (mapcat (::by-callee base) callee-symbols))
                    prior (arity-comparison removed (::bounds base))
                    current (arity-comparison affected bounds)]
                {::candidates (into (reduce disj (::candidates base) removed) (::candidates current))
                 ::checked (+ (- (::checked base) (::checked prior)) (::checked current))
                 ::edges (+ (- (::edges base) (::edges prior)) (::edges current))
                 ::bounds bounds}))))))))

(defn- arity-verdict
  "Refuse or report the candidates whose prepared arities also refuse.

  The call-preparation snapshot is the report's (`report-snapshot`): a report
  that changed none of its read attributes reads its committed `:db-before`'s
  memo instead of deriving the whole program on the speculative value."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       :seon.db/transaction-report :map]
                  [:or :seon.fn/arity-mismatch-report :seon.db/error-result]]}
  [database projection report {candidates ::candidates bounds ::bounds checked ::checked edges ::edges}]
  (let [candidates (vec candidates)
        snapshot (when (seq candidates)
                   (@call-preparation-report-snapshot report projection))
        refusal (or (when (error-value? snapshot) snapshot)
                    (first (:seon.call-preparation/refusals snapshot)))]
    (or refusal
        (let [plans (into {} (map (fn [callee]
                                    [callee (@call-preparation-plan-for
                                             database snapshot callee)]))
                          (distinct (map (comp first second) candidates)))
              refused (some #(when (error-value? %) %) (vals plans))]
          (or refused
              {:seon.fn/arity-mismatches
               (->> candidates
                    (keep (fn [[caller [callee n]]]
                            (let [declared (vec (sort-by
                                                 (juxt :seon.fn.arity/min
                                                       #(get % :seon.fn.arity/max Long/MAX_VALUE))
                                                 (get bounds callee)))
                                  prepared (if-let [plan (get plans callee)]
                                             (@call-preparation-arities plan)
                                             declared)]
                              (when-not (arity-admitted? prepared n)
                                {:seon.fn/caller caller
                                 :seon.fn/callee callee
                                 :seon.fn/call-arity (long n)
                                 :seon.fn/declared-arities declared
                                 :seon.fn/prepared-arities prepared}))))
                    (sort-by (juxt :seon.fn/caller :seon.fn/callee :seon.fn/call-arity))
                    vec)
               :seon.fn/arity-checked checked
               :seon.fn/arity-unchecked (- edges checked)})))))

(defn- arity-mismatches-with
  [query-fn database projection]
  (let [edges (query-fn '[:find ?caller-symbol ?call
                         :where [?caller :seon.fn/call-arities ?call]
                         (or [?caller :seon.fn/sym ?caller-symbol]
                             [?caller :seon.test/sym ?caller-symbol])]
                       database)
        bounds (when-not (and (map? edges) (inst? (:seon.error/at edges))
             (qualified-keyword? (:seon.error/layer edges))
             (qualified-symbol? (:seon.error/operation edges)))
                 (declared-arity-bounds query-fn database))]
    (or (when (and (map? edges) (inst? (:seon.error/at edges))
             (qualified-keyword? (:seon.error/layer edges))
             (qualified-symbol? (:seon.error/operation edges))) edges)
        (when (and (map? bounds) (inst? (:seon.error/at bounds))
             (qualified-keyword? (:seon.error/layer bounds))
             (qualified-symbol? (:seon.error/operation bounds))) bounds)
        (let [checked (filterv (fn [[_ [callee _]]] (contains? bounds callee)) edges)
              candidates (filterv (fn [[_ [callee n]]]
                                    (not (arity-admitted? (get bounds callee) n)))
                                  checked)
              snapshot (when (seq candidates)
                         (@call-preparation-snapshot database projection))
              refusal (or (when (and (map? snapshot) (inst? (:seon.error/at snapshot))
             (qualified-keyword? (:seon.error/layer snapshot))
             (qualified-symbol? (:seon.error/operation snapshot))) snapshot)
                          (first (:seon.call-preparation/refusals snapshot)))]
          (or refusal
              (let [plans (into {} (map (fn [callee]
                                         [callee (@call-preparation-plan-for
                                                  database snapshot callee)]))
                                (distinct (map (comp first second) candidates)))
                    refused (some #(when (and (map? %) (inst? (:seon.error/at %))
             (qualified-keyword? (:seon.error/layer %))
             (qualified-symbol? (:seon.error/operation %))) %) (vals plans))]
                (or refused
                    {:seon.fn/arity-mismatches
                     (->> candidates
                          (keep (fn [[caller [callee n]]]
                                  (let [declared (vec (sort-by
                                                      (juxt :seon.fn.arity/min
                                                            #(get % :seon.fn.arity/max Long/MAX_VALUE))
                                                      (get bounds callee)))
                                        prepared (if-let [plan (get plans callee)]
                                                   (@call-preparation-arities plan)
                                                   declared)]
                                    (when-not (arity-admitted? prepared n)
                                      {:seon.fn/caller caller
                                       :seon.fn/callee callee
                                       :seon.fn/call-arity (long n)
                                       :seon.fn/declared-arities declared
                                       :seon.fn/prepared-arities prepared}))))
                          (sort-by (juxt :seon.fn/caller :seon.fn/callee :seon.fn/call-arity))
                          vec)
                     :seon.fn/arity-checked (count checked)
                     :seon.fn/arity-unchecked (- (count edges) (count checked))})))))))

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

(defn- write-render-target-error
  "Render declarations naming functions absent from `database`.
  With `declarations`, only those `[key form]` rows are read; without, every
  schema row is."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value] [:or :nil :seon.error/base]]
                  [:=> [:cat :seon.db/database-value [:sequential [:tuple [:or :keyword :symbol :string] :string]]]
                   [:or :nil :seon.error/base]]]}
  ([database]
   (write-render-target-error
    database
    (vec (d/q '[:find ?key ?form :where
                [?schema :seon.schema/key ?key]
                [?schema :seon.schema/form ?form]] database))))
  ([database declarations]
  (let [missing
        (into []
              (mapcat
               (fn [[schema-key encoded]]
                 (let [properties (m/properties (schema/structural-schema (edn/read-string encoded)))]
                   (keep (fn [property]
                           (let [renderer (get properties property)]
                             (when (and (qualified-symbol? renderer)
                                        (not (db.utils/entid database [:seon.fn/sym renderer])))
                               {:seon.schema/key schema-key
                                :seon.render/property property
                                :seon.render/function renderer})))
                         [:seon.render/ai :seon.render/html]))))
              (sort-by first declarations))]
    (when (seq missing)
      (diagnostic
       {::transaction-refused true
        :seon.error/message "Render declarations name functions absent from the final program. Admit the definitions or repair the declarations in the same transaction."
        :seon.error/data {:seon.render/declarations missing}
        :seon.error/layer :database-write
        :seon.error/operation 'seon.db/transact!
        :seon.error/member :seon.schema/form
        :seon.error/expected :seon.fn/sym
        :seon.error/offending missing})))))

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
                       obligations (concat
                                    (when function-symbol
                                      (map #(vector % function-symbol)
                                           [:seon.fn/calls :seon.fn/references :seon.test/subject
                                            :seon.effect/capability]))
                                    (when namespace-symbol
                                      [[:seon.ns/requires namespace-symbol]])
                                    (when schema-key
                                      (map #(vector % schema-key)
                                           [:seon.fn/writes :seon.schema/references
                                            :seon.fn.arity/input-refs :seon.fn.arity/output-refs
                                            :seon.fn.arity/guard-refs]))
                                    (when (qualified-keyword? attribute)
                                      [[:seon.fn/writes attribute]]))]
                   (for [[attribute target] obligations
                         datom (d/datoms database :avet attribute target)
                         :when (or (not= :seon.effect/capability attribute)
                                   (seq (d/datoms database :eavt (:e datom) :seon.fn/sym)))]
                     {:seon.program/subject target
                      :seon.program/relation attribute
                      :seon.program/referrer
                      (into {}
                            (keep (fn [fact]
                                    (when (identity-attrs (:a fact))
                                      [(:a fact) (:v fact)])))
                            (mapcat #(d/datoms database :eavt %)
                                    (cons (:e datom)
                                          (map :e (d/datoms database :avet :seon.fn/arities (:e datom))))))
                      :db/id (:e datom)}))))
              removed)]
    (when (seq breaks)
      (let [breaks (vec (sort-by pr-str breaks))]
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
          :seon.error/offending breaks})))))

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
  (let [removed
        (into []
              (keep (fn [entity-id]
                      (let [identities
                            (into {}
                                  (keep (fn [datom]
                                          (when (and (identity-attrs (:a datom))
                                                     (not (db.utils/entid database
                                                                          [(:a datom) (:v datom)])))
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
  of the projection and that installed schema."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value]
                  [:map-of [:or :keyword :int] :map]]}
  [projection database]
  (schema/projection-cache-value
   projection [::write-attribute-plans (dbi/-schema database)]
   #(into {}
          (map (fn [[attribute installed]]
                 [attribute (write-attribute-plan
                             projection attribute
                             (= :db.cardinality/many
                                (:db/cardinality installed)))]))
          (merge datahike.schema/implicit-schema-spec
                 (dbi/-schema database)))))

(defn- write-report-error
  "One final check for native operations and all expanded transaction-function output."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] [:or :nil :seon.db/error-result]]}
  [projection report]
  (let [database (:db-after report)
        before (:db-before report)
        attempted (:datahike/attempted-tx-data report)
        affected (distinct (map :e (concat attempted (:tx-data report))))
        changed-identity-attributes (volatile! #{})
        identity-attrs (report-identity-attributes report)
        attribute-plans (write-attribute-plans projection database)]
    (or
     (retention-report-check projection report affected)
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
     (write-owned-values-error projection report attribute-plans identity-attrs
                               changed-identity-attributes)
     ;; A render target breaks only by a declaration this report wrote or a
     ;; function it removed. Written rows are read alone; a removed function
     ;; can be named by any row, so that report reads them all.
     (let [tx-data (:tx-data report)]
       (if (some #(and (= :seon.fn/sym (:a %)) (not (:added %))
                       (not (db.utils/entid database [:seon.fn/sym (:v %)])))
                 tx-data)
         (write-render-target-error database)
         (when-let [written (seq (into #{}
                                       (comp (filter #(and (:added %) (#{:seon.schema/form :seon.schema/key} (:a %))))
                                             (map :e))
                                       tx-data))]
           (write-render-target-error
            database
            (into []
                  (keep (fn [entity]
                          (let [schema-key (:v (first (d/datoms database :eavt entity :seon.schema/key)))
                                form (:v (first (d/datoms database :eavt entity :seon.schema/form)))]
                            (when (and schema-key form) [schema-key form]))))
                  written)))))
     ;; Arity admission depends on declarations, their owned children, shared
     ;; shapes and supplied defaults. The owning-value walk already found
     ;; every changed root on both sides, including swept/deleted children.
     ;; An error, message or other ordinary fact cannot change call arities.
     (when (some @changed-identity-attributes
                 [:seon.fn/sym :seon.test/sym :seon.schema/key
                  :seon.schema.shape/fingerprint :seon.call-preparation/key])
       (let [comparison (report-arity-comparison report)
             result (if (error-value? comparison)
                      comparison
                      (arity-verdict database projection report comparison))
             mismatches (:seon.fn/arity-mismatches result)]
         (if (and (map? result) (inst? (:seon.error/at result))
             (qualified-keyword? (:seon.error/layer result))
             (qualified-symbol? (:seon.error/operation result)))
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

(defn- write-report-validator
  "Acquire the callback on its immutable projection, primed when the connection acquires it."
  [projection]
  (schema/projection-cache-value
   projection ::write-report-validator
   #(fn [report]
      (schema/call-with-projection projection
        (fn []
          (let [attribute :seon.config.db/validation-node-limit
                configured (when (get (dbi/-schema (:db-after report)) attribute)
                             (map :v (d/datoms (:db-after report) :aevt attribute)))
                ;; Every asserted policy on this branch applies to every writer.
                ;; Before config construction, the projection carries the declared default.
                limit (if (seq configured) (apply min configured) (get projection attribute))]
            (write-report-error (assoc projection attribute limit) report)))))))

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
  {:malli/schema [:=> [:cat :map :map [:or :nil :int]] [:vector :seon.schema/value]]}
  [before after actor]
  (doseq [key (set/union (set (keys before)) (set (keys after)))]
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
      (when (or (and active? (seq (:seon.db/values prior))
                     (empty? (:seon.db/values current)))
                (and active? (not authorized?) (or (seq removed) (seq erased) changed-assignment?))
                (and (:seon.db/creator prior) changed-authority?)
                (and (:seon.db/active? prior) (not authorized?) changed-authority?))
        (throw (ex-info
                "Started issue tests and their authority must be preserved; only the creator may remove tests."
                {:seon.error/message
                 (str "Cannot retract " (pr-str (first key)) " of "
                      (pr-str (or (:seon.db/entity prior) (:seon.db/entity current)))
                      "; tests " (pr-str (mapv #(get-in prior [:seon.db/value-identities %])
                                               (set/union removed erased))) ".")
                 :seon.error/data
                 {:seon.db/attribute (first key)
                  :seon.db/entity (or (:seon.db/entity prior) (:seon.db/entity current))
                  :seon.db/removed-values (set/union removed erased)
                  :seon.db/user actor :seon.db/creator creator}})))))
  [])

(defn- retention-report-check
  "Check touched owners and owners of touched retained targets in the final report.
   Reverse AVET seeks include an identity-only target retraction; transaction
   functions and reference sweeps are already expanded by this authority."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       [:sequential :int]] :nil]}
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
                     (retention-snapshot after rules entities) actor)
    nil))

(defn- agent-provenance?
  "True when the write's own provenance metadata names an agent user.

   Ruling 1r (owner, 2026-09-18 02:15-02:30Z): \"root access (system) for no
   limits, and then agents\" — and \"we don't have access control in the
   database explicitly but we have the user metadata so we can still write it
   in\". So the per-write bound is decided here, by the `:seon.db/user` the
   transaction already carries, and by nothing else. A write with no user
   (a `:seon.db/process` publication, adoption, reseed, boot recovery or
   collection) and a user that is not an agent are both system writes: their
   own lifecycle deadline is the bound that reports."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :map] :boolean]}
  [database prepared]
  (let [user (get-in prepared [:tx-meta :seon.db/user])]
    (boolean
     (when (some? user)
       (if (and (sequential? user) (= :seon.agent/id (first user)))
         true
         ;; A user that is not an entity id Datahike can parse names no agent.
         ;; Its declared unparseable cases (lookup-ref arity, a non-unique
         ;; lookup attribute, any other shape) answer the supplied error code
         ;; instead of raising (`datahike/db/utils.cljc:109-139`); every other
         ;; failure propagates.
         (let [eid (db.utils/entid database user ::unparseable-user)]
           (when (number? eid)
             (seq (d/datoms database :eavt eid :seon.agent/id)))))))))

(defn- write-observation
  "Carry the actual refused request and immutable pre-write basis as data.
  Recorder admission owns its bounded stored projection."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction :seon.error/base]
                  [:and :seon.db.write/validation-refusal :seon.db/error-result]]}
  [database transaction observation]
  (-> observation
      (assoc :seon.db.write.attempt/request-id (id/id)
             :seon.error/basis
             {:seon.error.basis/store (datahike.store/store-identity (:store (dbi/-config database)))
              :seon.error.basis/branch (:branch (dbi/-config database))
              :seon.error.basis/commit (d/commit-id database)
              :seon.error.basis/t (dbi/-max-tx database)})
      (assoc-in [:seon.error/data :seon.db.write.attempt/transaction] transaction)))

(defn- transact-call
  {:malli/schema
   [:=> [:cat :seon.db/database-value [:or :seon.db/connection :seon.error/value]
         :seon.store/transaction]
    [:or :seon.db/transaction-report :seon.db/error-result]]}
  [database connection transaction]
  (if (and (map? connection) (inst? (:seon.error/at connection))
             (qualified-keyword? (:seon.error/layer connection))
             (qualified-symbol? (:seon.error/operation connection)))
    connection
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
                  prepared
                  (jdk-integers->long
                   (let [request (stamp-receipt transaction)
                         request (if (map? request) request {:tx-data request})]
                     (assoc-in request [:tx-meta :datahike/validate-report]
                               (write-report-validator projection))))
                  ;; Ruling 1r (2026-09-18): the bound derives from the write's
                  ;; own provenance metadata, not from a second access-control
                  ;; mechanism. An agent user keeps the short, loud dial; root
                  ;; and system processes carry no per-write bound, because the
                  ;; operation's own lifecycle deadline is the one that reports
                  ;; and root can re-run a transaction an agent bound refused.
                  agent-write? (agent-provenance? database prepared)
                  write-time-limit-ms
                  (when agent-write?
                    (if (seq configured-bounds)
                      (apply min configured-bounds)
                      declared-bound))
                  request
                  (schema.datahike/encode-transaction-in projection prepared)
                  ;; Parents make it Datahike's merge: same fence and validator.
                  pending ((if (:parents request) d/merge-db! d/transact!) connection request)
                  timeout (Object.)
                  started (System/nanoTime)
                  report
                  (try
                    (if write-time-limit-ms
                      (deref pending write-time-limit-ms timeout)
                      (deref pending))
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
                       ;; Ruling 1r: the refusal hands back the transaction so
                       ;; root can re-run exactly what the agent bound stopped
                       ;; waiting for.
                       :seon.store/transaction transaction
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
        (let [data (error.refusal/refusal throwable)]
          (cond
            (let [observation (:datahike/validation-refusal data)]
              (and (map? observation) (inst? (:seon.error/at observation))
                   (qualified-keyword? (:seon.error/layer observation))
                   (qualified-symbol? (:seon.error/operation observation))))
            (:datahike/validation-refusal data)

            ;; A Seon transition refusal returns its own value verbatim.
            (and (map? data) (inst? (:seon.error/at data))
                 (qualified-keyword? (:seon.error/layer data))
                 (qualified-symbol? (:seon.error/operation data)))
            data

            ;; A Datahike abort keeps the dependency's classification.
            (some? (:error data))
            (rejected-value database transaction throwable data)

            :else
            (let [failure
                  (write-observation
                   database transaction
                   (error.refusal/diagnostic
                    {:seon.error/at (java.util.Date.)
                     :seon.error/layer :seon.db/database-write
                     :seon.error/operation 'seon.db/transact!
                     :seon.error/throwable throwable
                     :seon.error/message
                     (or (ex-message throwable)
                         (.getName (class throwable)))
                     :seon.error/data (or data {})
                     :seon.db/transaction-outcome-unknown true}))]
              (when (panic-on-core-error? connection)
                (throw
                 (ex-info (:seon.error/message failure)
                          failure
                          throwable)))
              failure)))))))

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
  "Summarize the facts and identified entities changed by a transaction."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] [:string {:min 1}]]}
  [unit]
  (let [{database :db-after before :db-before transaction-data :tx-data}
        (rendered-value unit)
        facts (remove #(= (:e %) (:tx %)) transaction-data)
        entities (sort (distinct (map :e facts)))
        identities (when (and (seq entities) (database-value? database))
                     (vec (sort (identity-attributes database))))
        labels (mapv (fn [entity]
                       (let [row (when identities
                                   (merge (when (database-value? before)
                                            (pull before identities entity))
                                          (pull database identities entity)))
                             identity (first (sort-by (comp str key)
                                                      (dissoc row :db/id)))]
                         (if identity
                           (str (namespace (key identity)) " " (pr-str (val identity)))
                           (str "entity " entity))))
                     entities)]
    (str "Wrote " (count facts) " facts on " (count entities) " entities"
         (when (seq labels) (str ": " (str/join ", " labels))) ".")))

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
  (let [{database :db-after
         transaction-data :tx-data}
        (rendered-value unit)
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
  "Render a rejected database transaction as readable steering text."
  {:malli/schema
   [:=> [:cat :seon.db.write/validation-refusal] [:string {:min 1}]]}
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
  (if (and (map? report) (inst? (:seon.error/at report))
           (qualified-keyword? (:seon.error/layer report))
           (qualified-symbol? (:seon.error/operation report)))
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

(defn transact!
  "Validate authored transaction data and commit through the calling connection.

  Attribute values and identified entity maps use the supplied cluster schema
  projection. Invalid writes return their authored form, offending value, path,
  and applicable entity form; unknown attributes include installed candidates.
  Datahike owns native schema declarations, reference resolution, uniqueness,
  and transaction-function execution, retaining its refusal classifications.

  With the connection omitted, return :seon.db/tx and :seon.db/datoms as
  [entity attribute value added?] vectors. Entities use installed identity
  lookup refs when available, including identities removed by this transaction.
  Tempids are resolved. The next read observes the changed database.
  Explicit-connection system callers retain Datahike's full report.
  A map may carry `:datahike/expected-basis-t` (a stale head refuses before
  any datom lands) and `:parents`, immutable commit ids that make the write
  Datahike's multi-parent merge commit through the same validation.

  Waiting for the Datahike writer is bounded by the branch's declared
  :seon.config.db/write-time-limit-ms fact. If that bound fires, this returns
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
   (let [database (when (connection? connection) (resolve-database-value connection))
         result
         (or
          (missing-transaction-data-error transaction)
          (cond
            (and (map? connection) (inst? (:seon.error/at connection))
             (qualified-keyword? (:seon.error/layer connection))
             (qualified-symbol? (:seon.error/operation connection))) connection

            (not (connection? connection))
            (dependency-error
             'seon.db/transact!
             (ex-info "The explicit transaction connection is not live."
                      {::connection connection}))

            (and (map? database) (inst? (:seon.error/at database))
                 (qualified-keyword? (:seon.error/layer database))
                 (qualified-symbol? (:seon.error/operation database))) database

            :else
            (or (foreign-connection-error database connection transaction)
                (transact-call database connection transaction))))]
     (if (and (map? result) (inst? (:seon.error/at result))
              (qualified-keyword? (:seon.error/layer result))
              (qualified-symbol? (:seon.error/operation result)))
       (let [refusal (assoc result :seon.db/transaction-refused true)]
         (if (:seon.db.write.attempt/request-id refusal)
           refusal
           (if (database-value? database)
             (write-observation database transaction refusal)
             (assoc refusal :seon.db.write.attempt/request-id (id/id)))))
       result))))
