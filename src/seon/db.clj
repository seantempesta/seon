(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.data :as data]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [datahike.constants :as const]
            [datahike.db :as datahike.db]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as db.utils]
            [datahike.query :as query]
            [datahike.schema :as datahike.schema]
            [datahike.store :as datahike.store]
            [datalog.parser.impl.proto :as parser]
            [clojure.test.check.generators :as gen]
            [seon.ai.tokens :as tokens]
            [seon.env :as env]
            [seon.error.refusal :as error.refusal]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.form :as schema.form]
            [seon.schema.internal :as schema.internal])
  (:import [datahike.db AsOfDB]
           [datalog.parser.type BindScalar Constant FindColl FindRel FindScalar
            FindTuple Pattern Variable]))

;;; ---------------------------------------------------------------------------
;;; Ambient custody and optional read evidence
;;; ---------------------------------------------------------------------------

(defn connection?
  "True for a live (unreleased) Datahike connection."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (connector/connection? value)
       (some? (:wrapped-atom value))
       (not= @(:wrapped-atom value) :released)))

(defn database-value?
  "True for any Datahike database value."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (db.utils/db? value))

(schema/register-core-predicate! 'seon.db/connection? connection?)
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
  ((requiring-resolve 'seon.error/diagnostic) request))

(defn- dependency-error
  [operation error]
  (error-value
   ::invalid-read
   (or (ex-message error) "Datahike refused the database read.")
   (cond-> {::operation operation
            ::exception-class (.getName (class error))}
     (map? (ex-data error))
     (assoc ::dependency-data (ex-data error)))))

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn- resolve-database-value
  [connection]
  (try
    ;; Resolve latest exactly once at the public-call boundary.
    (d/db connection)
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
  (if (nil? *conn*)
    (missing-connection-error "a database value")
    (resolve-database-value *conn*)))

(defn- current-connection
  []
  (if (nil? *conn*)
    (missing-connection-error "a connection")
    *conn*))

(defn- connection-id
  [connection]
  (datahike.store/connection-id (:config @connection)))

(defn connection-identity
  "Plain-data identity of a live Datahike connection."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value]]
    [:or :seon.db/connection-identity :seon.error/value]]}
  [connection]
  (if (error-value? connection)
    connection
    {:datahike/connection-id (connection-id connection)}))

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

(defn- foreign-connection-error
  [connection]
  (when (some? *conn*)
    (let [ambient-connection-id (connection-id *conn*)
          explicit-connection-id (connection-id connection)]
      (when-not (= ambient-connection-id explicit-connection-id)
        (error-value
         ::foreign-connection
         (str "The explicit transaction connection does not belong to "
              "the calling agent's cluster.")
         {::ambient-connection-id ambient-connection-id
          ::explicit-connection-id explicit-connection-id})))))

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

(defn- stable-read-result
  [result]
  (walk/postwalk (fn [value]
                   (if (map? value)
                     (dissoc value :seon.db/db)
                     value))
                 result))

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
              :when (db.utils/db? database)]
        (append-read-evidence!
         (cond->
          {:seon.db/db database
           :seon.db/source-argument-position position
           :datahike.read/dependency-plan plan}
           replayable?
           (assoc :seon.db/read-request
                  {:seon.db/read-operation :q
                   :seon.db/query-request replay-request}
                  :seon.db/read-result (stable-read-result result)))))))
  nil)

(defn- append-pull-evidence!
  [database arguments operation-key response result]
  (let [selector
        ((requiring-resolve 'datahike.pull-api/pull-plan-selector)
         (:datahike.pull/plan response))
        replay-arguments
        (if (map? (first arguments))
          (assoc arguments 0 (-> (first arguments)
                                 (assoc :selector selector)
                                 (dissoc :datahike.pull/plan)))
          (assoc arguments 0 selector))]
    (append-read-evidence!
     {:seon.db/db database
      :seon.db/source-argument-position 0
      :datahike.read/dependency-plan (:datahike.read/dependency-plan response)
      :seon.db/read-request {:seon.db/read-operation operation-key
                             :seon.db/pull-arguments replay-arguments}
      :seon.db/read-result (stable-read-result result)})))

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
  "Retain dependency revisions without retaining database values."
  {:malli/schema [:=> [:cat [:vector :seon.db/captured-read]]
                  [:vector :seon.db/read-evidence]]}
  [captured]
  (mapv (fn [{database :seon.db/db
              source-position :seon.db/source-argument-position
              plan :datahike.read/dependency-plan
              :as entry}]
          (cond->
           {:seon.db/source-argument-position source-position
            :datahike.read/dependency-plan plan
            :datahike.read/revision
            (dependency-revision database plan source-position)}
            (:seon.db/read-request entry)
            (assoc :seon.db/read-request (:seon.db/read-request entry)
                   :seon.db/read-result (:seon.db/read-result entry))))
        captured))

(declare decode-query-result decode-pull-result read-declarations)

(defn- pull-plan-with-evidence
  [& arguments]
  (apply (requiring-resolve 'datahike.pull-api/pull-plan-with-evidence)
         arguments))

(defn- pull-many-plan-with-evidence
  [& arguments]
  (apply (requiring-resolve
          'datahike.pull-api/pull-many-plan-with-evidence)
         arguments))

(defn- replay-read
  [database request]
  (case (:seon.db/read-operation request)
    :q
    (let [query-request
          (update (:seon.db/query-request request) :args
                  (fn [arguments]
                    (mapv #(if (= ::database %) database %) arguments)))
          response (d/q-with-evidence query-request)]
      (decode-query-result (read-declarations database)
                           query-request
                           (query/memoized-parse-query
                            (:query query-request))
                           (:datahike.query/result response)))

    :pull
    (let [arguments (:seon.db/pull-arguments request)
          response (apply pull-plan-with-evidence database arguments)]
      (decode-pull-result (read-declarations database)
                          (:datahike.pull/plan response)
                          :datahike.pull/result
                          (:datahike.pull/result response)))

    :pull-many
    (let [arguments (:seon.db/pull-arguments request)
          response (apply pull-many-plan-with-evidence database arguments)]
      (decode-pull-result (read-declarations database)
                          (:datahike.pull/plan response)
                          :datahike.pull-many/result
                          (:datahike.pull-many/result response)))))

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
       (or (and (not (false? (:datahike.read/cache-eligible? revision)))
                (= revision (dependency-revision database plan source-position)))
           (when-let [request (:seon.db/read-request evidence)]
             (try
               (= (:seon.db/read-result evidence)
                  (stable-read-result (replay-read database request)))
               (catch Throwable _ false)))))
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
    (if (satisfies? dbi/IHistory candidate)
      (let [origin (dbi/-origin candidate)]
        (if (identical? candidate origin)
          candidate
          (recur origin)))
      candidate)))

(defn- read-declarations
  [database]
  (delay
    (or (schema/handed-projection)
        (schema/projection-from-database (schema-database database)))))

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
  (let [projection @declarations]
    (schema/call-with-forms
     (:seon.schema.projection/forms projection)
     #(question projection))))

(defn- edn-encoded?
  [declarations attribute]
  (ask-declarations
   declarations
   #(schema.datahike/edn-encoded-attr-in? % attribute)))

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
        databases (query-source-databases (:query request) arguments)]
    (some
     (fn [pattern]
       (let [attribute-node (nth (:pattern pattern) 1 nil)
             attribute (if (instance? Constant attribute-node)
                         (:value attribute-node)
                         (get input-bindings (:symbol attribute-node)))
             source-symbol (or (:symbol (:source pattern)) '$)
             database (get databases source-symbol)]
         (when (and database
                    (keyword? attribute)
                    (nil? (get (dbi/-schema (schema-database database))
                               attribute)))
           (unknown-attribute-error 'seon.db/q database attribute
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
  (let [attributes (query-find-attributes
                    declarations parsed-query (:args normalized))
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
  (let [spec ((requiring-resolve 'datahike.pull-api/pull-plan-spec) plan)]
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
  "This environment's CURRENT database value, derefed at call time.

  Deriving rather than storing is the whole point of the current mode: a
  database value kept on the environment would go stale silently, while
  `(d/db connection)` at preparation time is always the latest committed
  value. A caller that needs one consistent basis passes its own database
  value and it wins — elide for current, pass for consistent."
  {:malli/schema
   [:=> [:cat :seon.env/environment]
    [:or :seon.db/database-value :seon.error/value]]}
  [environment]
  (if-not (env/environment? environment)
    (unsupplied-custody-error "a database value")
    (let [connection (:seon.db/connection environment)]
      (if (nil? connection)
        (unsupplied-custody-error "a database value")
        (resolve-database-value connection)))))

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

(defn- aligned-query-arguments
  [explicit-database query-form arguments]
  (let [arguments (vec arguments)
        input-count (d/query-input-count query-form)
        source-bindings (d/query-source-bindings query-form)
        upstream-error (source-argument-error source-bindings arguments)
        default-sources
        (filterv #(= '$ (:datahike.query.source/symbol %))
                 source-bindings)]
    (cond
      upstream-error
      upstream-error

      (= input-count (count arguments))
      arguments

      (and (= input-count (inc (count arguments)))
           (= 1 (count default-sources)))
      (let [position
            (:datahike.query.source/argument-position
             (first default-sources))
            database (or explicit-database (current-database-value))]
        (if (error-value? database)
          database
          (when (<= 0 position (count arguments))
            (into (conj (subvec arguments 0 position) database)
                  (subvec arguments position)))))

      ;; Datahike owns every other positional shape. Passing the normalized
      ;; arguments through is deliberately weaker than the dependency's own
      ;; acceptance; `q-with-evidence` alone decides whether they are valid.
      :else arguments)))

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

(defn q
  "Run a Datalog query over explicit inputs or the current database value."
  {:malli/schema
   [:=>
    [:catn
     [::query-or-database
      [:or
       :seon.db/database-value
       :seon.error/value
       :seon.db/query
       :seon.db/query-args]]
     [::arguments [:* :seon.schema/value]]]
    [:or :seon.schema/value :seon.error/value]]}
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
            (aligned-query-arguments
             (when explicit-database? query-or-database)
             (:query normalized)
             (:args normalized))]
        (if (error-value? aligned)
          aligned
          (let [request (assoc normalized :args aligned)
                parsed-query (query/memoized-parse-query (:query request))]
            (or (malformed-query-pattern-error request parsed-query)
                (query-attribute-error request parsed-query)
                (let [response (d/q-with-evidence request)
                      result (decode-query-result
                              (read-declarations
                               (some #(when (db.utils/db? %) %) aligned))
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

(defn- pull-call
  [database arguments operation operation-key result-key public-operation]
  (if (error-value? database)
    database
    (or (missing-pull-selector-error public-operation arguments)
        (when (#{'seon.db/pull 'seon.db/entity} public-operation)
          (lookup-ref-error public-operation database
                            (pull-entity-id arguments)))
        (try
      (let [response (apply operation database arguments)
            result (decode-pull-result
                    (read-declarations database)
                    (:datahike.pull/plan response)
                    result-key
                    (get response result-key))]
        (append-pull-evidence! database arguments operation-key response result)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error result-key cause))))))

(defn pull
  "Pull one entity over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-options]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat
          [:or :seon.db/database-value :seon.error/value
           :seon.db/pull-selector]
          [:or :seon.db/pull-options :seon.db/entity-id]]
     [:or :nil :map :seon.error/value]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
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
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat
      [:or :seon.db/database-value :seon.error/value
       :seon.db/pull-selector]
      [:or :seon.db/pull-many-options
       [:sequential :seon.db/entity-id]]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      [:sequential :seon.db/entity-id]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]]}
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

(defn- datoms-call
  [database arguments]
  (if (error-value? database)
    database
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (let [declarations (read-declarations database)
            result (mapv #(datom->data declarations database %)
                         (apply d/datoms database arguments))]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::datoms cause)))))

(defn datoms
  "Eager ordinary datoms from an explicit or current database value."
  {:malli/schema
   [:=>
    [:cat
     [:or :seon.db/database-value :seon.error/value
      :seon.db/index-lookup :keyword]
     [:* :seon.schema/value]]
    [:or :seon.db/datoms :seon.error/value]]}
  [database-or-index & arguments]
  (if (or (db.utils/db? database-or-index)
          (error-value? database-or-index))
    (datoms-call database-or-index arguments)
    (datoms-call (current-database-value)
                 (cons database-or-index arguments))))

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
      (let [result (apply operation database arguments)]
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
        ((requiring-resolve 'seon.call-preparation/snapshot)
         database projection)]
    (if (error-value? snapshot)
      snapshot
      (let [plan
            ((requiring-resolve 'seon.call-preparation/plan-for)
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
  [projection output-refs]
  (let [forms (:seon.schema.projection/forms projection)
        identity-attributes
        (into #{}
              (keep (fn [[_ definition]]
                      (schema.internal/derive-entity-id-attr
                       forms definition)))
              forms)]
    (->> output-refs
         (mapcat #(collection-entry-schemas forms %))
         (keep #(row-identity-attribute forms % identity-attributes))
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
        :seon.error/exception-class (.getName (class cause))
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
          (result-identity-attribute projection output-refs))]
    (cond
      (error-value? output-refs) output-refs

      (nil? identity-attribute)
      (diff-refusal
       "The declared result collection has no derivable row identity."
       :seon.fn.arity/output-refs
       :seon.entity/id-attr output-refs
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

(defn diff
  "Changes in one pure database read since a basis transaction."
  {:malli/schema
   [:=> [:cat :seon.db/basis-t :seon.test/var
         [:* :seon.schema/value]]
    [:or :seon.db.diff/result :seon.error/value]]}
  [basis function-var & arguments]
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
          (let [projection (schema/projection-from-database database)
                plan (diff-plan database projection function-symbol
                                (count arguments))]
            (if (error-value? plan)
              plan
              (perform-diff database projection plan basis function-var
                            arguments function-symbol))))))))

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
     :seon.error/data (merge data conflict)
     ::transaction-refused true}))

(defn- transact-call
  [connection transaction]
  (if (error-value? connection)
    connection
    (try
      (let [database (d/db connection)
            projection
            (or (schema/handed-projection)
                (when (seq (d/q '[:find [?key ...]
                                  :where [_ :seon.schema/key ?key]]
                                database))
                  (schema/projection-from-database database))
                (schema/declaration-projection
                 ((requiring-resolve 'seon.schema.edn/packaged-forms))))]
        (d/transact connection
                    (schema.datahike/encode-transaction-in
                     projection (jdk-integers->long transaction))))
      (catch Throwable throwable
        (let [data (error.refusal/refusal throwable)]
          (cond
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
  "Render a committed transaction report as bounded readable text."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] [:string {:min 1}]]}
  [unit]
  (let [{database :db-after
         transaction-data :tx-data
         tempids :tempids}
        (rendered-value unit)
        datom-count (count transaction-data)]
    (str "Committed transaction " (:t database)
         " at commit " (:datahike/commit-id database)
         " with " datom-count " datoms"
         "."
         (when (seq tempids)
           (str "\nTempids: " (pr-str tempids)))
         (when (seq transaction-data)
           (str "\nCommitted datoms:\n"
                (str/join "\n" (map pr-str transaction-data)))))))

(defn render-transaction-html
  "Render a committed transaction report as bounded readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] :seon.render/hiccup]}
  [unit]
  (let [{database :db-after
         transaction-data :tx-data
         tempids :tempids}
        (rendered-value unit)
        datom-count (count transaction-data)]
    [:article {:class "seon-family-entry seon-db-transaction-entry"}
     [:h3 "Committed transaction"]
     [:dl
      [:div [:dt "Transaction"] [:dd (str (:t database))]]
      [:div [:dt "Commit ID"] [:dd (str (:datahike/commit-id database))]]
      [:div [:dt "Datoms"]
       [:dd (str datom-count)]]
      [:div [:dt "Tempids"] [:dd (pr-str tempids)]]]
     (when (seq transaction-data)
       (into [:ol {:class "seon-db-transaction-datoms"}]
             (map (fn [datom] [:li [:code (pr-str datom)]]))
             transaction-data))]))

(defn render-rejection-ai
  "Render a rejected database transaction as readable steering text."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-refused-error] [:string {:min 1}]]}
  [unit]
  (:seon.error/message (rendered-value unit)))

(defn render-rejection-html
  "Render a rejected database transaction as readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-refused-error] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-value unit)
        conflict (:seon.error/data value)]
    [:article {:class "seon-family-entry seon-db-rejection-entry"}
     [:h3 (:seon.error/message value)]
     (when (::conflict-attribute conflict)
       [:dl
        [:div [:dt "Attribute"]
         [:dd (pr-str (::conflict-attribute conflict))]]
        [:div [:dt "Value"]
         [:dd (pr-str (::conflict-value conflict))]]
        [:div [:dt "Existing owner"]
         [:dd (pr-str (::conflict-owner conflict))]]])]))

(defn transact!
  "Commit a transaction through an explicit or ambient connection.

  When `*conn*` is bound, an explicit connection must have the same Datahike
  connection ID. An absent binding means the caller is outside an agent
  evaluation, so a live explicit connection is allowed."
  {:malli/schema
  [:function
    [:=> [:cat :seon.store/transaction]
     [:or :map :seon.error/value]]
    [:=> [:cat :seon.db/connection :seon.store/transaction]
     [:or :map :seon.error/value]]]}
  ([transaction]
   (or (missing-transaction-data-error transaction)
       (transact-call (current-connection) transaction)))
  ([connection transaction]
   (or
    (missing-transaction-data-error transaction)
    (cond
      (not (connection? connection))
      (dependency-error
       ::transact!
       (ex-info "The explicit transaction connection is not live."
                {::connection connection}))

      :else
      (or (foreign-connection-error connection)
          (transact-call connection transaction))))))
