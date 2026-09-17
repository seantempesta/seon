(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.data :as data]
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
            [seon.ai.tokens :as tokens]
            [seon.env :as env]
            [seon.error.refusal :as error.refusal]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.form :as schema.form])
  (:import [datahike.db AsOfDB DB]
           [datalog.parser.type And BindScalar Constant FindColl FindRel FindScalar
            FindTuple Not Or Pattern Pull Variable]))

;;; ---------------------------------------------------------------------------
;;; Ambient custody and optional read evidence
;;; ---------------------------------------------------------------------------

;;; LOAD-CYCLE BOUNDARIES. `seon.error` and `seon.call-preparation` both
;;; require `seon.db`, so this namespace cannot require them back. One
;;; resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private error-diagnostic
  (delay (requiring-resolve 'seon.error/diagnostic)))
(defonce ^:private error-explain-problem
  (delay (requiring-resolve 'seon.error/explain-problem)))
(defonce ^:private error-render-ai
  (delay (requiring-resolve 'seon.error/render-ai)))
(defonce ^:private error-render-html
  (delay (requiring-resolve 'seon.error/render-html)))
(defonce ^:private call-preparation-snapshot
  (delay (requiring-resolve 'seon.call-preparation/snapshot)))
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
  IS the released case -- `seon.cluster/stop!` on a stopped instance,
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

(schema/register-core-predicate! 'seon.db/connection? connection?)
(schema/register-core-predicate! 'seon.db/connection-object? connection-object?)
(schema/register-core-predicate! 'seon.db/database-value? database-value?)

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
  [kind message data]
  {kind true
   :seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- diagnostic
  [request]
  (@error-diagnostic request))

(defn- dependency-error
  [operation error]
  (if (= :seon.schema/missing-projection (:seon.error/kind (ex-data error)))
    (ex-data error)
    (error-value
     ::invalid-read
     (or (ex-message error) "Datahike refused the database read.")
     (cond-> {::operation operation
              ::exception-class (.getName (class error))}
       (map? (ex-data error))
       (assoc ::dependency-data (ex-data error))))))

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

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
  (if (and state (not (error-value? database)))
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
    (catch Throwable cause
      (dependency-error ::db cause))))

(defn- missing-connection-error
  [needed]
  (error-value
   ::missing-connection-binding
   (str "This read needs " needed
        ", and no cluster connection is bound on this thread. Custody is "
        "elided only inside an agent evaluation; elsewhere — a raw or "
        "virtual thread, a fixture, a REPL — pass the database value or "
        "connection explicitly, as in (db/pull db selector eid). "
        "At a development REPL, (seon.operator/connection \"default\") "
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
    (error-value? connection) connection

    (not (map? (:config @connection)))
    (error-value
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
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A scope wrapper returns its body's arbitrary result unchanged."
                         :gen/elements [nil false 0 "" :k [] {}]}]]}
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
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A scope wrapper returns its body's arbitrary result unchanged."
                         :gen/elements [nil false 0 "" :k [] {}]}]]}
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
  (if (error-value? database)
    database
    (let [configuration (dbi/-config database)
          commit-id (d/commit-id database)]
      (if (uuid? commit-id)
        {:db-name (:branch configuration)
         :t (dbi/-max-tx database)
         :datahike/commit-id commit-id}
        (error-value
         ::uncommitted-database-value
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
  (if (error-value? database)
    database
    (long (dbi/-max-tx database))))

(defn- connection-branch
  [connection]
  (:branch (:config @connection)))

(defn- foreign-connection-error
  "Refuse a write naming a branch outside the writing custody, naming both.

  DECIDED WHERE THE WRITE IS ADMITTED, never from a pre-read: the two
  identities are read from the two connections this call actually holds,
  immediately before `transact-call` hands one to Datahike, so nothing can
  change between the decision and the write it governs."
  [connection]
  (when (some? *conn*)
    (let [ambient-connection-id (connection-id *conn*)
          explicit-connection-id (connection-id connection)]
      (when-not (= ambient-connection-id explicit-connection-id)
        (let [ambient-branch (connection-branch *conn*)
              explicit-branch (connection-branch connection)]
          (error-value
           ::foreign-connection
           (str "This write names branch " (pr-str explicit-branch)
                ", which is not the writing cluster's branch "
                (pr-str ambient-branch)
                ". A cluster writes only its own branch: send the work to "
                "the cluster that owns "
                (pr-str explicit-branch)
                " instead of transacting into its connection.")
           {::ambient-connection-id ambient-connection-id
            ::explicit-connection-id explicit-connection-id
            ::ambient-branch ambient-branch
            ::explicit-branch explicit-branch}))))))

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

(declare decode-index-page decode-query-result decode-pull-result read-declarations)

(defn- pull-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-plan-with-evidence
         arguments))

(defn- pull-many-plan-with-evidence
  [& arguments]
  (apply pull-api/pull-many-plan-with-evidence
         arguments))

(defn- replay-read
  [database request]
  (case (:seon.db/read-operation request)
    :q
    (let [query-request
          (update (:seon.db/query-request request) :args
                  (fn [arguments]
                    (mapv #(if (= ::database %) database %) arguments)))
          parsed-query (query/memoized-parse-query (:query query-request))
          response (d/q-with-evidence query-request)]
      (decode-query-result (read-declarations database 'seon.db/replay-read)
                           query-request
                           parsed-query
                           (:datahike.query/result response)))

    :pull
    (let [arguments (:seon.db/pull-arguments request)
          response (apply pull-plan-with-evidence database arguments)]
      (decode-pull-result (read-declarations database 'seon.db/replay-read)
                          (:datahike.pull/plan response)
                          :datahike.pull/result
                          (:datahike.pull/result response)))

    :pull-many
    (let [arguments (:seon.db/pull-arguments request)
          response (apply pull-many-plan-with-evidence database arguments)]
      (decode-pull-result (read-declarations database 'seon.db/replay-read)
                          (:datahike.pull/plan response)
                          :datahike.pull-many/result
                          (:datahike.pull-many/result response)))

    :index-page
    (decode-index-page (read-declarations database 'seon.db/replay-read)
                       database
                       (d/index-page database
                                     (:seon.db/index-page-options request)))))

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
                  :seon.db/datoms]}
  [database retained basis]
  (let [declarations (read-declarations database 'seon.db/read-evidence-changes)]
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
         retained))))

(defn- index-evidence-current
  "An exact index check when historical datoms retain the read's dependencies.
  Return no decision for a different database origin or discarded history."
  [database source revision]
  (let [patterns (:seon.db/read-index-patterns source)
        basis (:seon.db/read-basis-t source)
        context (:cache-context database)]
    (when (and patterns basis
               (instance? DB database)
               (= (select-keys revision [:datahike.cache/connection-id :datahike.cache/generation])
                  (select-keys context [:datahike.cache/connection-id :datahike.cache/generation]))
               (every? (fn [pattern]
                         (when-let [attribute (:seon.db/pattern-attribute pattern)]
                           (not (:db/noHistory (get (dbi/-schema database) attribute)))))
                       patterns))
      (let [changes (d/since (d/history database) basis)]
        (not-any? #(index-pattern-change changes %) patterns)))))

(defn read-evidence-current?
  "True when `database` still satisfies every retained dependency revision."
  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
                       [:vector :seon.db/read-evidence]]
                  [:or :boolean :seon.error/value]]}
  [database retained]
  (if (error-value? database)
    database
    (every?
     (fn [{source-position :seon.db/source-argument-position
           plan :datahike.read/dependency-plan
           revision :datahike.read/revision
           :as evidence}]
       (let [source (some #(when (= source-position (:datahike.query.source/argument-position %)) %)
                          (:datahike.query.dependency/sources plan))
             indexed (index-evidence-current database source revision)]
        (if (some? indexed)
          indexed
          (or (and (not (false? (:datahike.read/cache-eligible? revision)))
                (= revision (dependency-revision database plan source-position)))
           (when (and (find evidence :seon.db/read-request)
                      (or (find evidence :seon.db/read-result)
                          (find evidence :seon.db/read-result-digest)))
             (try
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
                         (= expected actual)))))
               (catch Throwable _ false)))))))
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
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.error/value]}
  [operation]
  (binding [*out* *err*]
    (println "WARN seon.db/projection-fallback caller=" operation
             "missing-projection count=1; supply the operation's projection."))
  {:seon.error/kind :seon.schema/missing-projection
   :seon.error/message "This operation requires a carried schema projection."
   :seon.error/data {:seon.db/operation operation
                     :seon.schema/missing-projection true}})

(defn carried-projection
  "The schema origin's immutable carried projection, or nil when absent."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] [:maybe :seon.schema/projection]]}
  [database]
  (:seon.schema/projection (meta (schema-database database))))

(defn- read-declarations
  [database operation]
  (let [origin (when database (schema-database database))]
    {::installed-schema (:schema origin)
     ::read-projection
     (delay (or (when origin (carried-projection origin))
                (schema/handed-projection)
                (let [failure (projection-fallback operation)]
                  (throw (ex-info (:seon.error/message failure) failure)))))}))

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
  [operation database attribute offending]
  (let [evidence (attribute-observation database attribute)]
    (diagnostic
     {:seon.error/kind ::invalid-read
      :seon.error/message
      (str operation " cannot read uninstalled attribute "
           (pr-str attribute) ".")
      :seon.error/diagnostic-layer :database-read
      :seon.error/diagnostic-operation operation
      :seon.error/diagnostic-member attribute
      :seon.error/diagnostic-expected
      {::installed-declaration :seon.error/unknown
       ::registered-candidates (::registered-candidates evidence)}
      :seon.error/diagnostic-offending offending
      :seon.error/diagnostic-cause ::attribute-not-installed
      :seon.error/diagnostic-evidence evidence :seon.db/invalid-read true})))

(defn- lookup-ref-error
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
         {:seon.error/kind ::invalid-read
          :seon.error/message
          (str operation " requires a unique lookup-ref attribute; "
               (pr-str attribute) " is not unique.")
          :seon.error/diagnostic-layer :database-read
          :seon.error/diagnostic-operation operation
          :seon.error/diagnostic-member attribute
          :seon.error/diagnostic-expected declaration
          :seon.error/diagnostic-offending entity-id
          :seon.error/diagnostic-cause ::lookup-attribute-not-unique
          :seon.error/diagnostic-evidence evidence :seon.db/invalid-read true})

        (not valid-value?)
        (diagnostic
         {:seon.error/kind ::invalid-read
          :seon.error/message
          (str operation " received " (pr-str value) " for "
               (pr-str attribute) ", whose installed value type is "
               (pr-str (:db/valueType declaration)) ".")
          :seon.error/diagnostic-layer :database-read
          :seon.error/diagnostic-operation operation
          :seon.error/diagnostic-member attribute
          :seon.error/diagnostic-expected declaration
          :seon.error/diagnostic-offending
          {::attribute attribute ::value value}
          :seon.error/diagnostic-cause
          {::validation ::value-does-not-match-installed-type
           ::value-type (:db/valueType declaration)}
          :seon.error/diagnostic-evidence evidence :seon.db/invalid-read true})))))

(defn- query-input-bindings
  [parsed-query arguments]
  (into {}
        (keep (fn [[input-binding value]]
                (let [variable (:variable input-binding)]
                  (when (and (instance? BindScalar input-binding)
                             (instance? Variable variable))
                    [(:symbol variable) value]))))
        (map vector (:qin parsed-query) arguments)))

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
  [request parsed-query]
  (when-let [pattern (some #(when (> (count (:pattern %)) 5) %)
                           (query-patterns parsed-query))]
    (let [offending (parsed-pattern-value pattern)]
      (diagnostic
       {:seon.error/kind ::invalid-read
        :seon.error/message
        "seon.db/q received a data pattern with more than five positions."
        :seon.error/diagnostic-layer :database-read
        :seon.error/diagnostic-operation 'seon.db/q
        :seon.error/diagnostic-member offending
        :seon.error/diagnostic-expected
        [:entity :attribute :value :transaction :added]
        :seon.error/diagnostic-offending offending
        :seon.error/diagnostic-cause ::malformed-data-pattern
        :seon.error/diagnostic-evidence request :seon.db/invalid-read true}))))

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
  (let [input-bindings (query-input-bindings parsed-query arguments)]
    (reduce
     (fn [attributes pattern]
       (let [attribute-node (nth (:pattern pattern) 1 nil)
             value-node (nth (:pattern pattern) 2 nil)
             attribute (if (instance? Constant attribute-node)
                         (:value attribute-node)
                         (get input-bindings (:symbol attribute-node)))
             variable (:symbol value-node)]
         (if (and (keyword? attribute)
                  (instance? Variable value-node))
           (update attributes variable (fnil conj #{}) attribute)
           attributes)))
     {}
     (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))))

(defn- query-find-attributes
  [declarations parsed-query arguments]
  (let [variable-attributes
        (query-variable-attributes parsed-query arguments)]
    (mapv
     (fn [element]
       (when (instance? Variable element)
         (let [attributes (get variable-attributes (:symbol element))]
           (when (= 1 (count attributes))
             (let [attribute (first attributes)]
               (when (edn-encoded? declarations attribute)
                 attribute))))))
     (parser/find-elements (:qfind parsed-query)))))

(defn- decode-query-field
  [declarations attribute value]
  (if (and attribute (some? value))
    (decode-attribute-value declarations attribute value)
    (decode-attribute-maps declarations value)))

(defn- decode-query-tuple
  [declarations attributes tuple]
  (mapv #(decode-query-field declarations %1 %2) attributes tuple))

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
                  (get attributes-by-key mapping-key) value)))
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
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/connection :seon.error/value]]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([]
   (current-database-value))
  ([connection]
   (if (error-value? connection)
     connection
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
  [needed]
  (error-value
   ::unsupplied-custody
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
                      (dependency-error ::db cause))))))
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
                (when (error-value? argument)
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
  [query-form arguments]
  (diagnostic
   {:seon.error/kind ::invalid-read
    :seon.error/message (query-argument-message query-form arguments)
    :seon.error/diagnostic-layer :database-read
    :seon.error/diagnostic-operation 'seon.db/q
    :seon.error/diagnostic-member :in
    :seon.error/diagnostic-expected (get query-form :in '[$])
    :seon.error/diagnostic-offending arguments
    :seon.error/diagnostic-cause ::query-input-shape
    :seon.error/diagnostic-evidence {::query query-form}
    :seon.db/invalid-read true}))

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
                        (db.utils/db? value) (error-value? value))))
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
              (if (error-value? database)
                database
                (into (conj (subvec arguments 0 position) database)
                      (subvec arguments position)))))))))

(defn- missing-query-error
  [query-input]
  (when (and (map? query-input)
             (contains? query-input :args)
             (not (contains? query-input :query)))
    (diagnostic
     {:seon.error/kind ::invalid-read
      :seon.error/message
      "seon.db/q argument maps require :query."
      :seon.error/diagnostic-layer :database-read
      :seon.error/diagnostic-operation 'seon.db/q
      :seon.error/diagnostic-member :query
      :seon.error/diagnostic-expected [:map [:query :seon.db/query]]
      :seon.error/diagnostic-offending query-input
      :seon.error/diagnostic-cause ::missing-required-key
      :seon.error/diagnostic-evidence query-input :seon.db/invalid-read true})))

(defn- query-call-valid?
  [[call-arguments _result]]
  (let [[query-or-database & arguments] call-arguments]
    (if (error-value? query-or-database)
      true
      (try
        (let [explicit? (db.utils/db? query-or-database)
              query-input (if explicit? (first arguments) query-or-database)
              supplied (if explicit? (rest arguments) arguments)
              normalized (query/normalize-q-input query-input supplied)]
          (and (not (and (map? query-input) (contains? query-input :args) (seq supplied)))
               (not= :invalid (query-input-position explicit? (:query normalized) (:args normalized)))))
        ; Malformed query syntax has no input count; the parser supplies its
        ; existing diagnostic at the read seam. This guard checks parsed inputs.
        (catch Exception _ true)))))

(defn- query-guard-message
  [{value :value} _options]
  (let [[[head & tail] result] value]
    (or (when (error-value? result) (:seon.error/message result))
        (try
          (let [explicit? (db.utils/db? head)
                query-input (if explicit? (first tail) head)
                supplied (if explicit? (rest tail) tail)
                normalized (query/normalize-q-input query-input supplied)]
            (query-argument-message (:query normalized)
                                    (into (vec (:args normalized))
                                          (when (and (map? query-input) (:args query-input)) supplied))))
          (catch Exception _ "The query and supplied arguments do not form a valid Datalog call.")))))

(defn q
  "Run a Datalog query over explicit inputs or the current database value."
  {:malli/schema
   [:=> [:catn [:seon.db/query-or-database [:or :seon.db/database-value :seon.error/value :seon.db/query :seon.db/query-args]] [:seon.db/arguments [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.", :gen/elements [[]]} :seon.schema/value]]] [:or :seon.schema/value :seon.error/value] [:fn #:error{:message "The supplied arguments must match the query's :in (default [$]); every source input must be a database value. Use (seon.db/q query input ...) with $ elided, or (seon.db/q database query input ...) with the database first.", :fn seon.db/query-guard-message} seon.db/query-call-valid?]]}
  [query-or-database & arguments]
  (if (error-value? query-or-database)
    query-or-database
    (let [explicit-database? (db.utils/db? query-or-database)
        query-input
        (if explicit-database?
          (first arguments)
          query-or-database)
        argument-inputs
        (if explicit-database?
          (rest arguments)
          arguments)]
    (or (missing-query-error query-input)
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
        (if (error-value? aligned)
          aligned
          (let [request (assoc normalized :args aligned)
                parsed-query (query/memoized-parse-query (:query request))]
            (or (malformed-query-pattern-error request parsed-query)
                (query-attribute-error request parsed-query)
                (let [response (d/q-with-evidence request)
                      result (decode-query-result
                              (read-declarations
                               (some #(when (db.utils/db? %) %) aligned)
                               'seon.db/q)
                              request parsed-query
                              (:datahike.query/result response))]
                  (append-query-evidence! request response result)
                  result)))))
        (catch Throwable cause
          (when explicit-database?
            (append-database-evidence! query-or-database :all))
          (dependency-error ::q cause)))))))

(defn- missing-pull-selector-error
  [public-operation arguments]
  (when (and (= 1 (count arguments))
             (map? (first arguments))
             (not (contains? (first arguments) :selector)))
    (let [request (first arguments)]
      (diagnostic
       {:seon.error/kind ::invalid-read
        :seon.error/message
        (str public-operation " argument maps require :selector.")
        :seon.error/diagnostic-layer :database-read
        :seon.error/diagnostic-operation public-operation
        :seon.error/diagnostic-member :selector
        :seon.error/diagnostic-expected
        [:map [:selector :seon.db/pull-selector]]
        :seon.error/diagnostic-offending request
        :seon.error/diagnostic-cause ::missing-required-key
        :seon.error/diagnostic-evidence request :seon.db/invalid-read true}))))

(defn- pull-entity-id
  [arguments]
  (if (map? (first arguments))
    (:eid (first arguments))
    (second arguments)))

(def ^:private pull-limit-operators #{'limit :limit "limit"})

(defn- limit-bearing-expression?
  [expression]
  (and (sequential? expression)
       (or (contains? pull-limit-operators (first expression))
           (boolean (some pull-limit-operators (take-nth 2 (rest expression)))))))

(declare total-pull-selector)

(defn- total-attribute-expression
  "One pull attribute expression carrying Datahike's own no-limit spelling.

  A wildcard names no attribute and a `:db/id` clause reads no datoms, so
  neither is widened; a caller that spelled its own `:limit` keeps it."
  [expression]
  (cond
    (#{'* "*" :db/id} expression) expression
    (keyword? expression) [expression :limit nil]
    (limit-bearing-expression? expression) expression
    (sequential? expression) (conj (vec expression) :limit nil)
    :else expression))

(defn- total-pull-selector
  "The selector with every attribute the caller named read in full.

  Datahike's pull cuts a cardinality-many attribute at 1 000 members and
  reports nothing about the cut: the limit defaults to `+default-limit+`
  and the surplus datoms are simply dropped
  (`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323`).
  A read that reports a short answer as a complete one is this project's
  named failure class, so every named attribute is read with the
  dependency's no-limit spelling (`:limit nil`) unless the caller asked for
  a limit itself. A wildcard clause names no attribute and cannot be
  widened here — a wildcard pull of a row with more than 1 000 members in
  one attribute is still cut."
  [selector]
  (if-not (vector? selector)
    selector
    (mapv (fn [clause]
            (if (map? clause)
              (into (empty clause)
                    (map (fn [[attribute nested]]
                           [(total-attribute-expression attribute)
                            (if (or (sequential? nested) (set? nested))
                              (total-pull-selector (vec nested))
                              nested)]))
                    clause)
              (total-attribute-expression clause)))
          selector)))

(defn- total-pull-arguments
  "Pull arguments whose selector reads every named attribute in full."
  [arguments]
  (let [head (first arguments)]
    (cond
      (and (map? head) (not (contains? head :datahike.pull/plan))
           (vector? (:selector head)))
      (cons (update head :selector total-pull-selector) (rest arguments))

      (vector? head)
      (cons (total-pull-selector head) (rest arguments))

      :else arguments)))

(defn- pull-call
  [database arguments operation operation-key result-key public-operation]
  (if (error-value? database)
    database
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
             {:seon.error/kind ::invalid-read
              :seon.error/message
              (str public-operation " received invalid arguments " (pr-str arguments)
                   ". Use (" public-operation " selector " (if many? "eids" "eid")
                   ") with the database elided, or (" public-operation
                   " database selector " (if many? "eids" "eid")
                   "). Argument maps require :selector and " (if many? ":eids" ":eid") ".")
              :seon.error/diagnostic-layer :database-read
              :seon.error/diagnostic-operation public-operation
              :seon.error/diagnostic-member :args
              :seon.error/diagnostic-expected expected
              :seon.error/diagnostic-offending arguments
              :seon.error/diagnostic-cause ::pull-input-shape
              :seon.error/diagnostic-evidence {::operation operation-key}
              :seon.db/invalid-read true})))
        (when (#{'seon.db/pull 'seon.db/entity} public-operation)
          (lookup-ref-error public-operation database
                            (pull-entity-id arguments)))
        (try
      (let [arguments (total-pull-arguments arguments)
            response (apply operation database arguments)
            result (decode-pull-result
                    (read-declarations database public-operation)
                    (:datahike.pull/plan response)
                    result-key
                    (get response result-key))]
        (append-pull-evidence! database arguments operation-key response result)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error result-key cause))))))

(defn- pull-call-valid?
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (or (db.utils/db? head) (error-value? head)) tail arguments)]
    (or (error-value? head)
        (and (= 1 (count inputs)) (map? (first inputs)))
        (and (= 2 (count inputs)) (vector? (first inputs))
             (not (map? (second inputs)))))))

(defn pull
  "Pull one entity over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-options]
     [:or :nil :map :seon.error/value]
     [:fn {:error/message "Use (seon.db/pull selector eid), (seon.db/pull database selector eid), or one {:selector selector :eid eid} argument map."} seon.db/pull-call-valid?]]
    [:=> [:cat
          [:or :seon.db/database-value :seon.error/value
           :seon.db/pull-selector]
          [:or :seon.db/pull-options :seon.db/entity-id]]
     [:or :nil :map :seon.error/value]
     [:fn {:error/message "Use (seon.db/pull selector eid), (seon.db/pull database selector eid), or one {:selector selector :eid eid} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      :seon.db/entity-id]
     [:or :nil :map :seon.error/value]
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
           (error-value? database-or-selector))
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
     [:or [:vector [:or :nil :map]] :seon.error/value]
     [:fn {:error/message "Use (seon.db/pull-many selector eids), (seon.db/pull-many database selector eids), or one {:selector selector :eids eids} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat
      [:or :seon.db/database-value :seon.error/value
       :seon.db/pull-selector]
      [:or :seon.db/pull-many-options
       [:sequential :seon.db/entity-id]]]
     [:or [:vector [:or :nil :map]] :seon.error/value]
     [:fn {:error/message "Use (seon.db/pull-many selector eids), (seon.db/pull-many database selector eids), or one {:selector selector :eids eids} argument map."} seon.db/pull-call-valid?]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      [:sequential :seon.db/entity-id]]
     [:or [:vector [:or :nil :map]] :seon.error/value]
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
           (error-value? database-or-selector))
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
     [:or :nil :map :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
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

(defn- datoms-call
  [database arguments]
  (if (error-value? database)
    database
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (let [declarations (read-declarations database 'seon.db/datoms)
            result (mapv #(datom->data declarations database %)
                         (apply d/datoms database arguments))]
        (let [options (first arguments)
              index (if (map? options) (:index options) options)
              components (if (map? options) (:components options) (rest arguments))
              pattern-keys (case index
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
                      value)
              pattern (cond-> {}
                        attribute (assoc :seon.db/pattern-attribute attribute)
                        entity (assoc :seon.db/pattern-entity entity)
                        (some? value) (assoc :seon.db/pattern-value value))
              plan {:datahike.query.dependency/sources
                    [{:datahike.query.source/symbol '$
                      :datahike.query.source/argument-position 0
                      :datahike.query.source/attributes (if attribute #{attribute} :all)}]}]
          (append-read-evidence!
           (cond-> {:seon.db/db database
                    :seon.db/source-argument-position 0
                    :datahike.read/dependency-plan plan}
             (instance? DB database)
             (assoc :seon.db/read-index-patterns [pattern]))))
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::datoms cause)))))

(defn- datoms-call-valid?
  [[arguments _result]]
  (let [[head & tail] arguments
        inputs (if (db.utils/db? head) tail arguments)
        [index & components] inputs]
    (or (error-value? head)
        (if (map? index)
          (empty? components)
          (and (#{:eavt :aevt :avet} index)
               (<= (count components) 4))))))

(defn datoms
  "Eager ordinary datoms from an explicit or current database value."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value :seon.db/index-lookup :keyword] [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike index components include arbitrary attribute values. The function guard checks index, component count and argument-map exclusivity.", :gen/elements [[]]} :seon.schema/value]] [:or :seon.db/datoms :seon.error/value] [:fn #:error{:message "Use (seon.db/datoms index & components) or (seon.db/datoms database index & components); an index argument map takes no trailing arguments, and an index has at most four components."} seon.db/datoms-call-valid?]]}
  [database-or-index & arguments]
  (if (or (db.utils/db? database-or-index)
          (error-value? database-or-index))
    (datoms-call database-or-index arguments)
    (datoms-call (current-database-value)
                 (cons database-or-index arguments))))

(defn index-page
  "One bounded, decoded page in native Datahike index order."
  {:malli/schema
   [:function
    [:=> [:catn [::options ::index-page-options]]
     [:or ::index-page-result :seon.error/value]]
    [:=> [:catn [::database [:or ::database-value :seon.error/value]]
          [::options ::index-page-options]]
     [:or ::index-page-result :seon.error/value]]]}
  ([options] (index-page (current-database-value) options))
  ([database options]
   (if (error-value? database)
     database
     (try
       (let [page (decode-index-page (read-declarations database 'seon.db/index-page)
                                     database
                                     (d/index-page database options))]
         (append-read-evidence!
          {:seon.db/db database
           :seon.db/source-argument-position 0
           :datahike.read/dependency-plan :all
           :seon.db/read-request {:seon.db/read-operation :index-page
                                  :seon.db/index-page-options options}
           :seon.db/read-result page})
         page)
       (catch Throwable cause
         (append-database-evidence! database :all)
         (dependency-error ::index-page cause))))))

(defn- database-view
  [operation database arguments]
  (cond
    (error-value? database)
    database

    (not (dbi/-temporal-index? database))
    (do
      (append-database-evidence! database :all)
      (error-value
       ::non-temporal-database
       "The database does not retain temporal indices."
       {::operation ::temporal-read}))

    :else
    (try
      (let [result (vary-meta (apply operation database arguments)
                              merge (meta database))]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::temporal-read cause)))))

(defn- database-identity
  [operation operation-name database]
  (if (error-value? database)
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
     [:or :nil :uuid :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :uuid :seon.error/value]]]}
  ([]
   (database-identity d/commit-id ::commit-id (current-database-value)))
  ([database]
   (database-identity d/commit-id ::commit-id database)))

(defn committed-value-identity
  "Process-local identity of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :map :seon.error/value]]]}
  ([]
   (database-identity d/committed-value-identity
                      ::committed-value-identity
                      (current-database-value)))
  ([database]
   (database-identity d/committed-value-identity
                      ::committed-value-identity
                      database)))

(defn history
  "Historical view of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([]
   (database-view d/history (current-database-value) []))
  ([database]
   (database-view d/history database [])))

(defn as-of
  "Database view at a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([time-point]
   (database-view d/as-of (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/as-of database [time-point])))

(defn since
  "Database view since a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([time-point]
   (database-view d/since (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/since database [time-point])))

;;; ---------------------------------------------------------------------------
;;; Identity-aware result differences
;;; ---------------------------------------------------------------------------

(def ^:private external-sink-reach-rules
  '[[(reaches-external-sink ?function ?sink)
     [?function :seon.fn/calls ?sink]]
    [(reaches-external-sink ?function ?sink)
     [?function :seon.fn/calls ?called]
     (reaches-external-sink ?called ?sink)]])

(defn- diff-refusal
  [message member expected offending cause evidence]
  (diagnostic
   {::diff-refused true
    :seon.error/kind ::diff-refused
    :seon.error/message message
    :seon.error/diagnostic-layer :agent-boundary
    :seon.error/diagnostic-operation 'seon.db/diff
    :seon.error/diagnostic-member member
    :seon.error/diagnostic-expected expected
    :seon.error/diagnostic-offending offending
    :seon.error/diagnostic-cause cause
    :seon.error/diagnostic-evidence evidence}))

(defn- callee-symbol
  [callee]
  (let [{namespace-value :ns name-value :name} (meta callee)]
    (when (and namespace-value name-value)
      (str namespace-value "/" name-value))))

(defn- external-sinks
  [database function-symbol]
  (let [direct
        (q '[:find [?sink ...]
             :in $ ?function-symbol
             :where
             [?function :seon.fn/sym ?function-symbol]
             [?function :seon.fn/external-sink ?sink]]
           database function-symbol)
        reached
        (q '[:find [?sink-kind ...]
             :in $ % ?function-symbol
             :where
             [?function :seon.fn/sym ?function-symbol]
             (reaches-external-sink ?function ?sink)
             [?sink :seon.fn/external-sink ?sink-kind]]
           database external-sink-reach-rules function-symbol)]
    (cond
      (error-value? direct) direct
      (error-value? reached) reached
      :else (into (set direct) reached))))

(defn- diff-plan
  [database projection function-symbol supplied-count]
  (let [snapshot
        (@call-preparation-snapshot
         database projection)]
    (if (error-value? snapshot)
      snapshot
      (let [plan
            (@call-preparation-plan-for
             database snapshot function-symbol)
            database-slots
            (when-not (error-value? plan)
              (->> (:seon.call-preparation/arities plan)
                   (mapcat :seon.call-preparation/slots)
                   (filter #(= :seon.db/db
                               (:seon.call-preparation/key %)))
                   vec))
            candidates
            (when-not (error-value? plan)
              (->> (:seon.call-preparation/arities plan)
                   (keep
                    (fn [arity]
                      (let [slots
                            (filterv #(= :seon.db/db
                                         (:seon.call-preparation/key %))
                                     (:seon.call-preparation/slots arity))]
                        (when (and (= 1 (count slots))
                                   (= (inc supplied-count)
                                      (:seon.fn.arity/argument-count arity)))
                          {:seon.fn.arity/order
                           (:seon.fn.arity/order arity)
                           :seon.fn.argument/index
                           (:seon.fn.argument/index (first slots))}))))
                   vec))]
        (cond
          (error-value? plan) plan

          (nil? plan)
          (diff-refusal
           "The supplied Var has no contracted program-graph row."
           :seon.fn/sym :seon.fn/fn function-symbol
           ::function-not-indexed
           {:seon.fn/sym function-symbol})

          (empty? database-slots)
          (diff-refusal
           "The supplied Var declares no database-value argument."
           :seon.db/db :seon.db/database-value function-symbol
           ::database-input-absent
           {:seon.fn/sym function-symbol})

          (empty? candidates)
          (diff-refusal
           "The remaining arguments match no diffable call shape."
           :seon.schema/arguments
           (:seon.call-preparation/arities plan)
           supplied-count
           ::call-shape-absent
           {:seon.fn/sym function-symbol
            :seon.call-preparation/supplied-count supplied-count})

          (> (count candidates) 1)
          (diff-refusal
           "The remaining arguments select more than one call shape."
           :seon.schema/arguments
           candidates
           supplied-count
           ::ambiguous-call-shape
           {:seon.fn/sym function-symbol
            :seon.call-preparation/candidates candidates})

          :else
          (first candidates))))))

(defn- output-schema-refs
  [database function-symbol arity-order]
  (q '[:find [?schema-key ...]
       :in $ ?function-symbol ?arity-order
       :where
       [?function :seon.fn/sym ?function-symbol]
       [?function :seon.fn/arities ?arity]
       [?arity :seon.fn.arity/order ?arity-order]
       [?arity :seon.fn.arity/output-refs ?schema]
       [?schema :seon.schema/key ?schema-key]]
     database function-symbol arity-order))

(defn- terminal-schema-key
  [forms schema-key]
  (loop [current schema-key, seen #{}]
    (let [definition (get forms current)]
      (cond
        (contains? seen current) nil
        (and (keyword? definition) (contains? forms definition))
        (recur definition (conj seen current))
        :else current))))

(defn- collection-entry-schemas
  [forms schema-value]
  (letfn [(entries [value seen]
            (cond
              (and (keyword? value)
                   (contains? forms value)
                   (not (contains? seen value)))
              (entries (get forms value) (conj seen value))

              (vector? value)
              (let [body (remove map? (rest value))]
                (case (first value)
                  :vector [(last body)]
                  :sequential [(last body)]
                  :set [(last body)]
                  :or (mapcat #(entries % seen) body)
                  :and (mapcat #(entries % seen) body)
                  []))

              :else []))]
    (entries schema-value #{})))

(defn- row-identity-attribute
  [forms row-schema identity-attributes]
  (let [row-form
        (loop [value row-schema, seen #{}]
          (if (and (keyword? value)
                   (contains? forms value)
                   (not (contains? seen value)))
            (recur (get forms value) (conj seen value))
            value))]
    (when (and (vector? row-form) (= :map (first row-form)))
      (->> (schema.form/map-entries row-form)
           (keep
            (fn [[entry-key & declaration]]
              (let [value-schema (last declaration)
                    terminal-key
                    (when (keyword? value-schema)
                      (terminal-schema-key forms value-schema))]
                (when (and terminal-key
                           (not (some map? declaration))
                           (contains? identity-attributes terminal-key))
                  entry-key))))
           (sort-by str)
           first))))

(defn- result-identity-attribute
  [database projection output-refs]
  (let [forms (:seon.schema.projection/forms projection)
        identities (set (identity-attributes database))]
    (->> output-refs
         (mapcat #(collection-entry-schemas forms %))
         (keep #(row-identity-attribute forms % identities))
         distinct
         (sort-by str)
         first)))

(defn- insert-database-argument
  [arguments position database]
  (let [arguments (vec arguments)]
    (into (conj (subvec arguments 0 position) database)
          (subvec arguments position))))

(defn- invoke-diff-function
  [function-var arguments position database]
  (try
    (apply function-var
           (insert-database-argument arguments position database))
    (catch Throwable cause
      (diff-refusal
       "The diffed function threw while replaying a database value."
       :seon.fn/sym :seon.fn/fn (callee-symbol function-var)
       ::function-threw
       {:seon.db/basis-t (basis-t database)
        :seon.error/exception-class (symbol (.getName (class cause)))
        :seon.error/dependency-data (ex-data cause)}))))

(defn- identity-diff
  [identity-attribute before after]
  (let [before-by-id (update-vals (group-by identity-attribute before) first)
        after-by-id (update-vals (group-by identity-attribute after) first)
        [only-before only-after _] (data/diff before-by-id after-by-id)
        before-keys (set (keys only-before))
        after-keys (set (keys only-after))
        added (sort-by pr-str (set/difference after-keys before-keys))
        removed (sort-by pr-str (set/difference before-keys after-keys))
        changed (sort-by pr-str (set/intersection before-keys after-keys))]
    #:seon.db.diff
    {:added (mapv after-by-id added)
     :removed (mapv before-by-id removed)
     :changed
     (mapv (fn [identity-value]
             (let [before-value (get before-by-id identity-value)
                   after-value (get after-by-id identity-value)
                   [before-only after-only _]
                   (data/diff before-value after-value)]
               #:seon.db.diff
               {:identity identity-value
                :changed-attributes
                (->> (concat (keys before-only) (keys after-only))
                     (filter qualified-keyword?)
                     distinct
                     (sort-by str)
                     vec)
                :before before-value
                :after after-value}))
           changed)}))

(defn render-diff-ai
  "Render one database result delta as concise replay guidance."
  {:malli/schema [:=> [:cat :seon.db.diff/result] :seon.render/ai]}
  [result]
  (let [added (:seon.db.diff/added result)
        removed (:seon.db.diff/removed result)
        changed (:seon.db.diff/changed result)
        requery-id (:seon.db.diff/requery-id result)
        full-size (tokens/estimate (pr-str result))
        change-lines
        (mapv (fn [change]
                (str "- " (pr-str (:seon.db.diff/identity change))
                     ": "
                     (str/join ", "
                               (map str
                                    (:seon.db.diff/changed-attributes change)))))
              changed)]
    (str "Database diff from t " (::basis-t result)
         " to " (::current-basis-t result)
         ": +" (count added) " -" (count removed)
         " ~" (count changed) "."
         (when (seq change-lines)
           (str "\nChanged attributes:\n" (str/join "\n" change-lines)))
         "\nFull data elided (approximately " full-size
         " tokens); requery by "
         (pr-str requery-id) ".")))

(defn- perform-diff
  [database projection plan basis function-var arguments function-symbol]
  (let [output-refs
        (output-schema-refs database function-symbol
                            (:seon.fn.arity/order plan))
        identity-attribute
        (when-not (error-value? output-refs)
          (result-identity-attribute database projection output-refs))]
    (cond
      (error-value? output-refs) output-refs

      (nil? identity-attribute)
      (diff-refusal
       "The declared result collection has no derivable row identity."
       :seon.fn.arity/output-refs
       :db.unique/identity output-refs
       ::row-identity-absent
       {:seon.fn/sym function-symbol
        :seon.fn.arity/order (:seon.fn.arity/order plan)
        :seon.fn.arity/output-refs output-refs})

      :else
      (let [historical (as-of database basis)]
        (if (error-value? historical)
          historical
          (let [position (:seon.fn.argument/index plan)
                before (invoke-diff-function function-var arguments
                                             position historical)
                after (when-not (error-value? before)
                        (invoke-diff-function function-var arguments
                                              position database))]
            (cond
              (error-value? before) before
              (error-value? after) after
              (or (not (coll? before)) (not (coll? after)))
              (diff-refusal
               "The diffed function did not return collections."
               :seon.fn.arity/output-refs
               [:sequential :seon.schema/value]
               [(type before) (type after)]
               ::result-not-collections
               {:seon.fn/sym function-symbol
                :seon.fn.arity/output-refs output-refs})
              :else
              (assoc (identity-diff identity-attribute before after)
                     ::basis-t basis
                     ::current-basis-t (basis-t database)
                     :seon.db.diff/requery-id
                     (list* 'seon.db/diff basis
                            (list 'var (symbol function-symbol))
                            arguments)))))))))

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
  "Compare shown values, or replay a pure database read since a basis."
  {:malli/schema
   [:function [:=> [:cat :seon.db.diff/values-request] :seon.db.diff/paths] [:=> [:cat :seon.db/basis-t :seon.test/var [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Arguments are forwarded to the supplied Var; its program-graph contract and read plan own their shapes and arity.", :gen/elements [[]]} :seon.schema/value]] [:or :seon.db.diff/result :seon.error/value]]]}
  ([{before :seon.db.diff/before after :seon.db.diff/after}]
   (value-changes before after))
  ([basis function-var & arguments]
  (let [database (current-database-value)
        function-symbol (callee-symbol function-var)]
    (cond
      (error-value? database) database

      (nil? function-symbol)
      (diff-refusal
       "The diffed function must be a Var with a program identity."
       :seon.fn/sym :seon.test/var function-var
       ::function-var-required
       {:seon.db/basis-t basis})

      :else
      (let [sinks (external-sinks database function-symbol)]
        (cond
          (error-value? sinks) sinks

          (seq sinks)
          (diff-refusal
           "The diffed function reaches an external sink and is not replayable."
           :seon.fn/external-sink #{} sinks
           ::external-sink-reachable
           {:seon.fn/sym function-symbol
            :seon.fn/external-sink (vec (sort sinks))})

          :else
          (let [projection @(::read-projection
                             (read-declarations database 'seon.db/diff))
                plan (diff-plan database projection function-symbol
                                (count arguments))]
            (if (error-value? plan)
              plan
              (perform-diff database projection plan basis function-var
                            arguments function-symbol)))))))))

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
  [transaction]
  (walk/postwalk
   (fn [value]
     (if (instance? Integer value)
       (long value)
       value))
   transaction))

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
  [connection throwable data]
  (let [conflict
        (try
          (unique-conflict (d/db connection) data)
          (catch Throwable _
            nil))]
    {:seon.error/kind ::rejected
     :seon.error/message (rejection-message conflict throwable)
     :seon.error/data
     (cond-> (merge data conflict)
       conflict
       (assoc :seon.error/diagnostic-operation 'seon.db/transact!
              :seon.error/problems
              [{:seon.error/argument "transaction data"
                :seon.error/path [(::conflict-attribute conflict)]
                :seon.error/expected
                (get (dbi/-schema (d/db connection)) (::conflict-attribute conflict))
                :seon.error/expected-description "a value satisfying the attribute's uniqueness constraint"
                :seon.error/offending conflict
                :seon.error/actual-description "a value already assigned to an entity"
                :seon.error/fix "Update the existing owner, or choose an unused value."}]))
     ::transaction-refused true}))

(defn- stamp-receipt
  [transaction]
  (if (nil? *receipt*)
    transaction
    (if (map? transaction)
      (update transaction :tx-meta
              #(assoc (or % {}) ::receipt *receipt*))
      {:tx-data transaction
       :tx-meta {::receipt *receipt*}})))

(defn- write-entity-schemas
  [projection]
  (schema/projection-cache-value
   projection ::write-required-identity-schemas
   (fn []
     (let [forms (:seon.schema.projection/forms projection)]
       (reduce-kv
        (fn [by-identity schema-key authored]
          (let [form (schema.datahike/resolve-malli-form-in projection authored)]
            (if (and (schema.form/map-shape? form)
                     (:seon.db/attributes (schema.form/schema-properties form)))
              (reduce
               (fn [result [attribute options]]
                 (if (and (not (and (map? options) (:optional options)))
                          (schema/identity-attr? forms attribute))
                   (update result attribute (fnil conj []) schema-key)
                   result))
               by-identity (schema.form/map-entries form))
              by-identity)))
        {} forms)))))

(defn- write-validator
  [projection form]
  (schema/projection-cache-value
   projection [::write-validator form]
   #(m/validator form {:registry (:seon.schema.projection/registry projection)})))

(defn- invalid-write
  [projection attribute form value path entity-form cause candidates]
  (let [problem
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
            (cond-> {:schema (m/schema form {:registry (:seon.schema.projection/registry projection)})
                     :value value :in path}
              (= :malli.core/missing-key cause) (assoc :type cause))}))]
  (diagnostic
   (cond->
    {:seon.error/kind ::invalid-write
     :seon.error/message
     (str "seon.db/transact! refused transaction data at " (pr-str path)
          ": expected " (:seon.error/expected-description problem)
          ", got " (:seon.error/actual-description problem)
          ". Fix: " (:seon.error/fix problem))
     ::transaction-refused true
     ::attribute attribute
     :seon.schema/form form
     ::offending value
     ::path path
     :seon.error/diagnostic-layer :database-write
     :seon.error/diagnostic-operation 'seon.db/transact!
     :seon.error/diagnostic-member attribute
     :seon.error/diagnostic-expected form
     :seon.error/diagnostic-offending value
     :seon.error/diagnostic-cause cause
     :seon.error/data {:seon.error/problems [problem]}
     :seon.error/diagnostic-evidence {::path path}}
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
           (not (and (= 2 (count value))
                     (keyword? (first value))
                     (db.utils/is-attr? database (first value) :db.unique/identity)
                     (not (datahike.schema/entity-spec-attr? attribute)))))
    value
    [value]))

(defn- write-value
  "Normalize Datahike's reference and many-value syntax for Malli only."
  [database projection attribute value single?]
  (let [form (schema.datahike/resolve-datahike-form-in projection attribute)
        many? (and (not single?) (db.utils/multival? database attribute))
        normalize (if (db.utils/ref? database attribute)
                    #(if (or (map? %) (sequential? %)) 0 %)
                    identity)]
    (if many?
      (let [values (write-many-values database attribute value)]
        (case (schema.datahike/form-head form)
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
                   (first (schema.datahike/form-children
                           (schema.datahike/resolve-datahike-form-in
                            projection attribute)))
                   attribute)]
        (or nested-error
            ;; Dependency-owned schema attributes have no authored Malli form;
            ;; Datahike continues to validate and classify those declarations.
            (when (and authored
                       (not ((write-validator projection form)
                             (write-value database projection attribute value single?))))
              (invalid-write projection attribute authored value path entity-form
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
    (if (and failure (= ::attribute-not-installed
                        (get-in failure [:seon.error/data :seon.error/diagnostic-cause])))
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
                  (schema.datahike/resolve-datahike-form-in projection attribute))
           decode (if (and authored (schema.datahike/edn-encoded-attr-in? projection attribute))
                    #(schema.datahike/decode-attribute-value-in projection attribute %)
                    identity)]
       {::decode decode
        ::normalize-many (if (= :set (schema.datahike/form-head form)) set vec)
        ::validate (when authored
                     (write-validator projection
                                      (if many?
                                        (first (schema.datahike/form-children form))
                                        attribute)))}))))

(defn- write-entity-value
  "Read a resulting entity as logical values without expanding reference graphs."
  [database projection attribute-plans entity-id]
  (reduce
   (fn [row datom]
     (let [attribute (:a datom)
           plan (or (get attribute-plans attribute)
                    (write-attribute-plan projection attribute
                                          (db.utils/multival? database attribute)))
           value ((::decode plan) (:v datom))]
       (if (db.utils/multival? database attribute)
         (update row attribute (fnil conj #{}) value)
         (assoc row attribute value))))
   {} (d/datoms database :eavt entity-id)))

(defn- write-error-identity
  [refusal identities]
  (-> refusal
      (update :seon.error/message #(str % " Entity: " (pr-str identities) "."))
      (assoc-in [:seon.error/data ::entity] identities)))

(defn- write-tombstone-validator
  "A retained program identity has no indexer-owned definition facts.
   Runtime-owned attributes remain declared and validated by their own entries."
  [projection schema-key]
  (schema/projection-cache-value
   projection [::write-tombstone-validator schema-key]
   (fn []
     (let [forms (:seon.schema.projection/forms projection)
           identity-attrs (into #{}
                                (keep (fn [[attribute form]]
                                        (when (= schema-key
                                                 (:seon.program/row-schema
                                                  (schema.form/attr-form-properties form)))
                                          attribute)))
                                forms)
           entries (schema.form/map-entries
                    (schema.datahike/resolve-malli-form-in projection schema-key))
           owned (into #{}
                       (comp (remove #(or (identity-attrs (first %))
                                          (:seon.program/written-by
                                           (when (map? (second %)) (second %)))))
                             (map first))
                       entries)]
       (when (seq identity-attrs)
         (m/validator
          [:and (into [:map] (remove #(owned (first %))) entries)
           [:fn (fn [row] (not-any? #(contains? row %) owned))]]
          {:registry (:seon.schema.projection/registry projection)}))))))

(defn- write-entity-error
  "Validate the whole resulting entity, including identities present before a retraction."
  [database projection attribute-plans entity-id identities row before]
  (when (seq row)
    (let [forms (:seon.schema.projection/forms projection)
          schemas (write-entity-schemas projection)
          schema-keys (distinct (mapcat #(get schemas %) (keys identities)))
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
          (or ((write-validator projection schema-key) normalized)
              (when-let [validate-tombstone
                         (write-tombstone-validator projection schema-key)]
                (and (some (fn [[attribute value]]
                             (and (= schema-key
                                     (:seon.program/row-schema
                                      (schema.form/attr-form-properties (get forms attribute))))
                                  (seq (d/datoms before :eavt entity-id attribute value))))
                           identities)
                     (validate-tombstone normalized))))
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
                                        (or (get forms attribute) (get forms schema-key))
                                        value (into [entity-id] in) (get forms schema-key)
                                        (or (:type failure) ::invalid-entity) nil)]
             (-> refusal
                 (write-error-identity identities)
                 (assoc-in [:seon.error/data :seon.error/diagnostic-evidence]
                           {::entity identities ::entity-value row ::path (into [entity-id] in)})))))
       schema-keys))))

(defn- declared-arity-bounds
  [query-fn database]
  (let [rows (query-fn '[:find ?function-symbol ?minimum ?maximum
                         :where
                         [?function :seon.fn/sym ?function-symbol]
                         [?function :seon.fn/arities ?arity]
                         [?arity :seon.fn.arity/min ?minimum]
                         [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
                       database)]
    (if (:seon.error/kind rows)
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

(defn- arity-mismatches-with
  [query-fn database projection]
  (let [edges (query-fn '[:find ?caller-symbol ?call
                         :where [?caller :seon.fn/call-arities ?call]
                         (or [?caller :seon.fn/sym ?caller-symbol]
                             [?caller :seon.test/sym ?caller-symbol])]
                       database)
        bounds (when-not (error-value? edges)
                 (declared-arity-bounds query-fn database))]
    (or (when (error-value? edges) edges)
        (when (error-value? bounds) bounds)
        (let [checked (filterv (fn [[_ [callee _]]] (contains? bounds callee)) edges)
              candidates (filterv (fn [[_ [callee n]]]
                                    (not (arity-admitted? (get bounds callee) n)))
                                  checked)
              snapshot (when (seq candidates)
                         (@call-preparation-snapshot database projection))
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
                  [:or :seon.fn/arity-mismatch-report :seon.error/value]]}
  [database]
  (arity-mismatches-with q database (or (carried-projection database)
                                           (schema/handed-projection))))

(defn- write-render-target-error
  [database]
  (let [missing
        (into []
              (mapcat
               (fn [[schema-key encoded]]
                 (let [properties (schema.form/attr-form-properties (edn/read-string encoded))]
                   (keep (fn [property]
                           (let [renderer (get properties property)]
                             (when (and (qualified-symbol? renderer)
                                        (not (db.utils/entid database [:seon.fn/sym (str renderer)])))
                               {:seon.schema/key schema-key
                                :seon.render/property property
                                :seon.render/function renderer})))
                         [:seon.render/ai :seon.render/html]))))
              (sort-by first
                       (d/q '[:find ?key ?form :where
                              [?schema :seon.schema/key ?key]
                              [?schema :seon.schema/form ?form]] database)))]
    (when (seq missing)
      (diagnostic
       {:seon.error/kind ::invalid-write
        ::transaction-refused true
        :seon.error/message "Render declarations name functions absent from the final program. Admit the definitions or repair the declarations in the same transaction."
        :seon.error/diagnostic-layer :database-write
        :seon.error/diagnostic-operation 'seon.db/transact!
        :seon.error/diagnostic-member :seon.schema/form
        :seon.error/diagnostic-expected :seon.fn/sym
        :seon.error/diagnostic-offending missing
        :seon.error/diagnostic-cause :seon.render/function
        :seon.error/diagnostic-evidence {:seon.render/declarations missing}
        :seon.error/data {:seon.render/declarations missing}}))))

(defn- write-report-error
  "One final check for native operations and all expanded transaction-function output."
  [projection report]
  (let [database (:db-after report)
        before (:db-before report)
        attempted (:datahike/attempted-tx-data report)
        affected (distinct (map :e (concat attempted (:tx-data report))))
        identity-attrs (set/union (set (identity-attributes before))
                                  (set (identity-attributes database)))
        attribute-plans (schema/projection-cache-value
                         projection [::write-attribute-plans (dbi/-schema database)]
                         #(into {}
                                (map (fn [[attribute installed]]
                                       [attribute (write-attribute-plan
                                                   projection attribute
                                                   (= :db.cardinality/many
                                                      (:db/cardinality installed)))]))
                                (merge datahike.schema/implicit-schema-spec
                                       (dbi/-schema database))))]
    (or
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
     (some (fn [entity-id]
             (let [row (write-entity-value database projection attribute-plans entity-id)
                   prior-identities (into {}
                                          (keep (fn [datom]
                                                  (when (identity-attrs (:a datom))
                                                    [(:a datom) (:v datom)])))
                                          (d/datoms before :eavt entity-id))
                   identities (merge prior-identities (select-keys row identity-attrs))]
               (write-entity-error database projection attribute-plans entity-id identities row before)))
           affected)
     (when (some (comp #{:seon.schema/form :seon.schema/key :seon.fn/sym} :a)
                 (concat attempted (:tx-data report)))
       (write-render-target-error database))
     (when (seq affected)
       (let [result (arity-mismatches-with d/q database projection)
             mismatches (:seon.fn/arity-mismatches result)]
         (if (error-value? result)
           (assoc result ::transaction-refused true)
           (when (seq mismatches)
           (diagnostic
            {:seon.error/kind ::invalid-write
             ::transaction-refused true
             :seon.error/message "Recorded source argument counts disagree with the final prepared arities. Repair the callers or declaration in the same transaction."
             :seon.error/diagnostic-layer :database-write
             :seon.error/diagnostic-operation 'seon.db/transact!
             :seon.error/diagnostic-member :seon.fn/call-arities
             :seon.error/diagnostic-expected :seon.fn/prepared-arities
             :seon.error/diagnostic-offending mismatches
             :seon.error/diagnostic-cause :seon.fn/arity-mismatches
             :seon.error/diagnostic-evidence {:seon.fn/arity-mismatches mismatches}
             :seon.error/data {:seon.fn/arity-mismatches mismatches}}))))))))

(defn- write-report-validator
  "Acquire the callback on its immutable projection, primed when the connection acquires it."
  [projection]
  (schema/projection-cache-value
   projection ::write-report-validator
   #(fn [report]
      (schema/call-with-projection projection
        (fn [] (write-report-error projection report))))))

(defn- retention-rules [projection]
  (schema/projection-cache-value
   projection ::retention-rules
   #(into []
          (keep (fn [[attribute form]]
                  (let [properties (schema.form/attr-form-properties
                                    (schema.datahike/resolve-malli-form-in projection form))]
                    (when-let [activation (:seon.db/append-only-after properties)]
                      {:seon.db/attribute attribute
                       :seon.db/activation activation
                       :seon.db/authority (:seon.db/retraction-authority properties)}))))
          (:seon.schema.projection/forms projection))))

(defn- retention-snapshot [database rules]
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
         (let [activated (into #{} (comp (filter :added) (map :e))
                               (d/datoms history :aevt activation))
               creators (when (and authority (db.utils/entid database authority))
                          (reduce (fn [result datom]
                                    (if (and (:added datom) (not (get result (:e datom))))
                                      (assoc result (:e datom) (:v datom)) result))
                                  {} (sort-by :tx (d/datoms history :aevt authority))))
               entities (into activated (map :e) (d/datoms database :aevt attribute))]
           (mapv
            (fn [entity]
              (let [values (into #{} (map :v) (d/datoms database :eavt entity attribute))]
                [[attribute entity]
                 {:seon.db/entity (d/pull database identities entity)
                  :seon.db/values values
                  :seon.db/value-identities
                  (into {} (map (fn [value] [value (d/pull database identities value)])) values)
                  :seon.db/active? (boolean (activated entity))
                  :seon.db/assignment (into #{} (map :v) (d/datoms database :eavt entity activation))
                  :seon.db/creator (get creators entity)
                  :seon.db/current-creator
                  (when (and authority (db.utils/entid database authority))
                    (:v (first (d/datoms database :eavt entity authority))))}]))
            entities)))
       (filter #(and (get (dbi/-schema database) (:seon.db/attribute %))
                     (get (dbi/-schema database) (:seon.db/activation %)))
               rules)))))

(defn- retention-check [before after actor]
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
                                   (not= (:seon.db/assignment prior)
                                         (:seon.db/assignment current)))]
      (when (or (and active? (seq (:seon.db/values prior))
                     (empty? (:seon.db/values current)))
                (and active? (not authorized?) (or (seq removed) (seq erased) changed-assignment?))
                (and (:seon.db/creator prior) changed-authority?)
                (and (:seon.db/active? prior) (not authorized?) changed-authority?))
        (throw (ex-info
                "Started issue tests and their authority must be preserved; only the creator may remove tests."
                {:seon.error/kind :seon.db/retention-refused
                 :seon.error/message
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

(defn- retain-transaction [projection transaction]
  (let [rules (retention-rules projection)]
    (if (empty? rules) transaction
      (let [request (if (map? transaction) transaction {:tx-data transaction})
            user (get-in request [:tx-meta :seon.db/user])]
        (assoc request :tx-data
          [[:db.fn/call
            (fn [database]
              (let [before (retention-snapshot database rules)
                    actor (when user (db.utils/entid database user))]
                (conj (vec (:tx-data request))
                      [:db.fn/call
                       (fn [after]
                         (retention-check before (retention-snapshot after rules) actor))])))] ])))))

(defn- transact-call
  [connection transaction]
  (if (error-value? connection)
    connection
    (try
      (let [database (d/db connection)
            carried-state (connection-projection-state connection)
            projection
            (or (some-> carried-state deref :seon.schema/projection)
                (carried-projection database)
                (schema/handed-projection)
                (let [failure (projection-fallback 'seon.db/transact!)]
                  (throw (ex-info (:seon.error/message failure) failure))))]
        (or (write-error database projection transaction)
            (let [report (d/transact connection
                    (retain-transaction projection
                     (schema.datahike/encode-transaction-in
                      projection
                      (jdk-integers->long
                       (let [request (stamp-receipt transaction)
                             request (if (map? request) request {:tx-data request})]
                         (assoc-in request [:tx-meta :datahike/validate-report]
                                   (write-report-validator projection)))))))
                  state (or (when (identical? projection
                                               (:seon.schema/projection
                                                (some-> carried-state deref)))
                              carried-state)
                            (env/environment-state
                         (env/environment
                          {:seon.db/connection connection
                           :seon.boot/cluster-name
                           (name (:branch (:config database)))
                           :seon.schema/projection projection})))]
              (-> report
                  (update :db-before carry-projection-state state)
                  (update :db-after carry-projection-state state)))))
      (catch Throwable throwable
        (let [data (error.refusal/refusal throwable)]
          (cond
            (error-value? (:datahike/validation-refusal data))
            (:datahike/validation-refusal data)

            ;; A Seon transition refusal returns its own value verbatim.
            (some? (:seon.error/kind data))
            data

            ;; A Datahike abort keeps the dependency's classification.
            (some? (:error data))
            (rejected-value connection throwable data)

            :else
            (let [failure
                  {:seon.error/kind :seon.db/unknown-failure
                   :seon.error/message
                   (or (ex-message throwable)
                       (.getName (class throwable)))
                   :seon.error/data (or data {}) :seon.db/transaction-outcome-unknown true}]
              (when (panic-on-core-error? connection)
                (throw
                 (ex-info (:seon.error/message failure)
                          failure
                          throwable)))
              failure)))))))

(defn- missing-transaction-data-error
  [transaction]
  (when (and (map? transaction)
             (not (contains? transaction :tx-data)))
    (diagnostic
     {:seon.error/kind ::invalid-request
      :seon.error/message
      "seon.db/transact! argument maps require :tx-data."
      :seon.error/diagnostic-layer :database-write
      :seon.error/diagnostic-operation 'seon.db/transact!
      :seon.error/diagnostic-member :tx-data
      :seon.error/diagnostic-expected
      [:map [:tx-data :seon.store/transaction-data]]
      :seon.error/diagnostic-offending transaction
      :seon.error/diagnostic-cause ::missing-required-key
      :seon.error/diagnostic-evidence transaction :seon.db/invalid-request true})))

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
   [:=> [:cat :seon.db/transaction-refused-error] [:string {:min 1}]]}
  [unit]
  (@error-render-ai unit))

(defn render-rejection-html
  "Render a rejected database transaction as readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-refused-error] :seon.render/hiccup]}
  [unit]
  (@error-render-html unit))

(defn- transaction-result
  [report]
  (if (error-value? report)
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

  When `*conn*` is bound, an explicit connection must have the same Datahike
  connection ID. An absent binding means the caller is outside an agent
  evaluation, so a live explicit connection is allowed."
  {:malli/schema
  [:function
    [:=> [:cat :seon.store/transaction]
     [:or :seon.db/transaction-result :seon.error/value]]
    [:=> [:cat [:or :seon.db/connection :seon.error/value] :seon.store/transaction]
     [:or :map :seon.error/value]]]}
  ([transaction]
   (or (missing-transaction-data-error transaction)
       (transaction-result (transact-call (current-connection) transaction))))
  ([connection transaction]
   (or
    (missing-transaction-data-error transaction)
    (cond
      (error-value? connection) connection

      (not (connection? connection))
      (dependency-error
       ::transact!
       (ex-info "The explicit transaction connection is not live."
                {::connection connection}))

      :else
      (or (foreign-connection-error connection)
          (transact-call connection transaction))))))
