(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.cluster.store :as store]
         '[seon.cluster.registry :as registry] '[seon.fn :as index] '[seon.fn.analyzer :as analyzer]
         '[seon.program :as program] '[seon.schema :as schema] '[seon.test-support :as support]
         '[seon.id :as id] '[clojure.java.io :as io] '[datahike.query :as query]
         '[datahike.db.utils :as db.utils])
(declare content-probe-analysis content-probe-root stale-after q-after)
(do (require '[seon.db :as db] '[seon.cluster.boot :as boot] '[datahike.db.utils :as db.utils] '[datahike.query :as query]) (import '[datalog.parser.type BindColl BindTuple]) (def diagnostic @#'seon.db/diagnostic) (defn- nil-query-binding?
  "Whether an input binding supplies nil at a scalar position. A scalar's
  nested data is one value; only tuple and collection bindings destructure it."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :boolean]}
  [binding value]
  (boolean
   (cond
     (nil? value) true
     (instance? BindColl binding)
     (some #(nil-query-binding? (:binding binding) %) value)
     (instance? BindTuple binding)
     (some true? (map nil-query-binding? (:bindings binding) value))
     :else false)))

(defn- nil-query-input-error
  "Refuse nil bindings before attribute lookup or index execution: Datahike
  can interpret substituted nil as an unbound pattern and read every value."
  {:malli/schema [:=> [:cat :map :seon.schema/value]
                  [:maybe :seon.db/invalid-read-error]]}
  [request parsed-query]
  (when (some true? (map nil-query-binding? (:qin parsed-query) (:args request)))
    (vary-meta
     (diagnostic
      {:seon.error/message "A query :in binding supplied nil; supply a value or omit the read when its subject is absent."
       :seon.db/invalid-read true
       :seon.error/layer :database-read
       :seon.error/operation 'seon.db/q
       :seon.error/expected :some
       :seon.error/offending nil
       :seon.error/data {::db/query (:query request) :seon.error/member :in}})
     assoc :seon.error/declared-schema :seon.db/invalid-read-error)))

 (def q-after (eval (let [[_ name doc metadata & body] (read-string "(defn q\n  \"Run a Datalog query over explicit inputs or the current database value.\n  Nil scalar inputs, including members of tuple and collection bindings,\n  return an invalid-read error before executing the query. False and empty\n  collections remain ordinary inputs.\"\n  {:malli/schema\n   [:=> [:catn [:seon.db/query-or-database [:or :seon.db/database-value :seon.error/value :seon.db/query :seon.db/query-args]] [:seon.db/arguments [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.\", :gen/elements [[]]} :seon.schema/value]]] [:or :seon.schema/value :seon.db/invalid-read-error] [:fn #:error{:message \"The supplied arguments must match the query's :in (default [$]); every source input must be a database value. Use (seon.db/q query input ...) with $ elided, or (seon.db/q database query input ...) with the database first.\", :fn seon.db/query-guard-message} seon.db/query-call-valid?]]}\n  [query-or-database & arguments]\n  (if-not (or (db.utils/db? query-or-database) (vector? query-or-database) (string? query-or-database)\n                (:find query-or-database) (:query query-or-database))\n    (refused-read query-or-database 'seon.db/q)\n    (let [explicit-database? (db.utils/db? query-or-database)\n        query-input\n        (if explicit-database?\n          (first arguments)\n          query-or-database)\n        argument-inputs\n        (if explicit-database?\n          (rest arguments)\n          arguments)]\n    (try\n      ;; This disambiguates a Datalog map query from Datahike's argument map\n      ;; before Seon decides where the ambient database belongs.\n      (let [normalized (query/normalize-q-input query-input argument-inputs)\n            aligned\n            (if (and (map? query-input) (contains? query-input :args) (seq argument-inputs))\n              (query-input-shape-error (:query normalized)\n                                       (into (vec (:args normalized)) argument-inputs))\n              (aligned-query-arguments\n               (when explicit-database? query-or-database)\n               (:query normalized)\n               (:args normalized)))]\n        (if-not (vector? aligned)\n          (refused-read aligned 'seon.db/q)\n          (let [request (assoc normalized :args aligned)\n                parsed-query (query/memoized-parse-query (:query request))]\n            (or (nil-query-input-error request parsed-query)\n                (malformed-query-pattern-error request parsed-query)\n                (query-attribute-error request parsed-query)\n                (with-declarations\n                  (some #(when (db.utils/db? %) %) aligned) 'seon.db/q\n                  ;; A RELATION-ONLY QUERY HAS NO DATABASE SOURCE AT ALL. Every\n                  ;; binding came from the caller's own relation, so there is no\n                  ;; stored value to decode and no installed schema to miss.\n                  ;; Refusing there is the mirror of the defect this seam fixes:\n                  ;; it reads \"nothing to decode\" as \"cannot decode\".\n                  :seon.db/relation-only\n                  (fn [declarations]\n                    (or (query-value-type-error declarations request parsed-query)\n                        (let [answer (vector-query-call declarations request\n                                                        (encode-query-request declarations request parsed-query)\n                                                        parsed-query)]\n                          (if-not (vector? answer)\n                            answer\n                            (let [[response result] answer]\n                              (append-query-evidence! request response result)\n                              result))))))))))\n        (catch clojure.lang.ExceptionInfo cause\n          (when explicit-database?\n            (append-database-evidence! query-or-database :all))\n          (dependency-error 'seon.db/q cause))))))\n\n")] (list 'let (read-string "[refused-read @#'seon.db/refused-read query-input-shape-error @#'seon.db/query-input-shape-error aligned-query-arguments @#'seon.db/aligned-query-arguments malformed-query-pattern-error @#'seon.db/malformed-query-pattern-error query-attribute-error @#'seon.db/query-attribute-error with-declarations @#'seon.db/with-declarations query-value-type-error @#'seon.db/query-value-type-error vector-query-call @#'seon.db/vector-query-call encode-query-request @#'seon.db/encode-query-request append-query-evidence! @#'seon.db/append-query-evidence! append-database-evidence! @#'seon.db/append-database-evidence! dependency-error @#'seon.db/dependency-error]") (list* 'fn name body))))))
