user=> (seon.db/pull
  (seon.db/db)
  [:seon.task/id
   :seon.task/ns
   :seon.task/name
   :seon.task/kind
   :seon.task/intent
   :seon.task/must-read
   :seon.task/evidence]
  [:seon.task/id "d6e3b775f474"])
#:seon.task{:id "d6e3b775f474",
 :ns seon.db,
 :kind :function,
 :must-read [seon.db/q],
 :evidence
   ["docs/research/agent-platform/landing/fix-write-cost-2026-09-26.md"
    "docs/research/agent-platform/perf-profile-2026-09-26.md"],
 :name "the-query-wrapper-costs-36x-datahike",
 :intent
   "seon.db/q costs 107 us and 472 KB for a two-clause query against d/q's 3 us and 6 KB (36x time, 76x allocation): query-patterns/parsed-nodes are tree-seq walked 5 times per call, query-input-values 4 times, and armed private checks add about 70 us (encode-query-request 18, decode-query-result 16, query-value-type-error 12, query-attribute-error 9 us). A trivial root run issues 62 q and 43 pull, 10-35 ms. Compute patterns and input values once per call; db? was fixed in the fork (cefd8a2bf)."}

user=> (seon.task/task-edges (seon.db/db) "d6e3b775f474")
#:seon.task{:subjects #{seon.db/q},
 :proofs
   #{seon.db-overhead-test/query-preparation-has-bounded-armed-work},
 :referrers
   #{"126f25106b63" "d6e3b775f474" "2a6d6dd6c0c8" "b0251f09eba3"}}

user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.db-overhead-test/query-preparation-has-bounded-armed-work)
{:seon.test/sym
   seon.db-overhead-test/query-preparation-has-bounded-armed-work,
 :seon.test/error-count 0,
 :seon.test/fail-count 0,
 :seon.test/pass-count 3,
 :seon.test/run [:seon.test.run/id "d53047e80b84"],
 :seon.test/run-at #inst "2026-10-06T11:18:30.889Z",
 :seon.test/run-basis-t 536872188,
 :seon.test.member/completed-tx [:db/id 536872206],
 :seon.test.run/basis-t 536872188,
 :seon.test.member/reach-digest
   "7ed2746902f0b57290a2165f2bba72ca5d0ccb1d3a86b3f8ea3e4b01956f1c68",
 :seon.test.run/input-digest
   "8950c6a643cdbf8a9071bb5b1ba0bf8e46e257186f0125a20676df41a75b2c1b",
 :seon.test.run/program-digest
   "4a05c8c6f365d514c2345ff0aaf757cfb734a7bf1be146606f78e11391ae2ee3"}

user=> (clojure.repl/source-fn 'seon.db-overhead-test/query-preparation-has-bounded-armed-work)
(deftest ^{:seon/tasks #{"d6e3b775f474"}} query-preparation-has-bounded-armed-work
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           query '[:find ?key . :where [_ :seon.schema/key ?key]
                   [(= ?key :seon.cluster/name)]]
           {:seon.db-overhead/keys [value calls by-function]}
           (observe #(db/q database query))]
       (is (= :seon.cluster/name value))
       (is (= 1 (get by-function 'seon.db/q)) "the query entrant is armed")
       ;; Armed scratch prototype: 43 before, 35 after on the same value.
       ;; Five calls of margin; the old repeated preparation exceeds it.
       (is (<= calls 40) "a scalar query stays within forty armed calls")))))

user=> (doc seon.db/q)
{:example "",
 :summary
   "Run a Datalog query over explicit inputs or the current database value.",
 :body
   "Nil scalar inputs, including members of tuple and collection bindings, return an invalid-read error before executing the query. False and empty collections remain ordinary inputs.",
 :arglists ([query-or-database & arguments]),
 :in
   [:catn
    [:seon.db/query-or-database
     [:or :seon.db/database-value :seon.error/value :seon.db/query
      :seon.db/query-args]]
    [:seon.db/arguments
     [:*
      {:seon.schema.admission/exemption
         :seon.schema.admission/polymorphic-boundary,
       :seon.schema.admission/reason
         "Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.",
       :gen/elements [[]]}
      :seon.schema/value]]],
 :out [:or :seon.schema/value :seon.db/invalid-read-error]}

user=> (clojure.repl/source-fn 'seon.db/q)
(defn q
  "Run a Datalog query over explicit inputs or the current database value.
  Nil scalar inputs, including members of tuple and collection bindings,
  return an invalid-read error before executing the query. False and empty
  collections remain ordinary inputs."
  {:seon/tasks #{"d6e3b775f474"}
   :malli/schema
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
                parsed-query (query/memoized-parse-query (:query request))
                input-values (query-input-values parsed-query aligned)]
            (or (nil-query-input-error request parsed-query)
                (malformed-query-pattern-error request parsed-query)
                (with-declarations
                  (some #(when (db.utils/db? %) %) aligned) 'seon.db/q
                  ;; A RELATION-ONLY QUERY HAS NO DATABASE SOURCE AT ALL. Every
                  ;; binding came from the caller's own relation, so there is no
                  ;; stored value to decode and no installed schema to miss.
                  ;; Refusing there is the mirror of the defect this seam fixes:
                  ;; it reads "nothing to decode" as "cannot decode".
                  :seon.db/relation-only
                  (fn [declarations]
                    (or (query-value-type-error declarations request parsed-query input-values)
                        (let [answer (vector-query-call declarations request
                                                        (encode-query-request declarations request parsed-query input-values)
                                                        parsed-query input-values)]
                          (if-not (vector? answer)
                            answer
                            (let [[response result] answer]
                              (append-query-evidence! request response result)
                              result))))))))))
        (catch clojure.lang.ExceptionInfo cause
          (when explicit-database?
            (append-database-evidence! query-or-database :all))
          (dependency-error 'seon.db/q cause))))))

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/database-value])
{:seon.schema/form
   "[:fn {:error/message \"must be an immutable Datahike database value\", :gen/gen seon.db/database-value-generator, :seon.schema/identity-only true, :seon.schema/identity-projection seon.db/database-value-identity} seon.db/database-value?]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.error/value])
{:seon.schema/form ":seon.error/base"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/query])
{:seon.schema/form "[:or [:vector :seon.schema/value] :map :string]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/query-args])
{:seon.schema/form
   "[:map [:query :seon.db/query] [:args {:optional true} [:sequential :seon.schema/value]] [:request-id {:optional true} :string] [:limit {:optional true} :int] [:offset {:optional true} :int] [:max-work {:optional true} [:int {:min 1}]] [:max-results {:optional true} [:int {:min 1}]] [:max-result-weight {:optional true} [:int {:min 1}]]]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.schema/value])
{:seon.schema/form
   "[:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"The shared value schema represents arbitrary in-memory Clojure values, including nil and host objects; callers declaring concrete data must use their own shape.\", :gen/elements [nil false 0 \"\" :k [] {}]}]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/invalid-read-error])
{:seon.schema/form
   "[:and :seon.error/base [:map {:error/message \"must carry the :seon.db/invalid-read distinguishing member\"} [:seon.db/invalid-read :seon.db/invalid-read] [:seon.db/refused-read-operation :seon.db/refused-read-operation] [:seon.error/message :seon.error/message]]]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :error/message])
{:seon.schema/form "[:string {:min 1}]"}

user=> (seon.db/q
  '[:find
    [(pull ?t [:seon.task/id :seon.task/ns :seon.task/name :seon.task/intent]) ...]
    :in
    $
    [?f ...]
    ?h
    :where
    [?t :seon.task/must-read ?f]
    [?t :seon.task/id ?id]
    [(not= ?id ?h)]]
  (seon.db/db)
  [seon.db/q]
  "d6e3b775f474")
[#:seon.task{:id "b0251f09eba3",
  :ns seon.db,
  :name "an-entity-only-pattern-scans-past-the-query-work-bound",
  :intent
    "seon.db/q of [:find [?a ...] :in $ ?e :where [?e ?a]] with ?e 34746 (a task) exceeds the 65,536 query-work bound in 9.7 ms, and [?e ?a ?t] with a collection of 259 ref attributes does too, where (seon.db/datoms db :eavt 34746) reads its 12 datoms in 0.12 ms (default, 2026-09-28): the pattern does not use EAVT when only the entity is bound, so the cost grows with the entity id. seon.render.walk reads frontier datoms by EAVT instead. Done when a query binding only the entity reads EAVT (in the Datahike fork's planner) or refuses by name, with a regression."}
 #:seon.task{:id "126f25106b63",
  :ns seon.db,
  :name "a-query-refusal-holds-a-parser-object",
  :intent
    "A seon.db/q refusal's :seon.error/offending holds a datalog.parser.type.Placeholder host object instead of the authored clause, so an honest [:or [:vector ...] :seon.db/error-result] contract still refuses the returned refusal."}
 #:seon.task{:id "2a6d6dd6c0c8",
  :ns seon.db,
  :name "q-lets-a-dependency-throw-escape",
  :intent
    "seon.db/q threw a raw NullPointerException from Datahike's post-filter-not-joins instead of answering an error value (lowering fixed in f7a664c2f, E3). Catch the dependency's throw at the owner and answer the declared read refusal carrying the whole cause chain."}]
