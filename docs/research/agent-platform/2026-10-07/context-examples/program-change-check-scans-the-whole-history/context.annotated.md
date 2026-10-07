;; why: Your task: what to change and why. (~342 tokens)
user=> (seon.db/pull
  (seon.db/db)
  [:seon.task/id
   :seon.task/ns
   :seon.task/name
   :seon.task/kind
   :seon.task/intent
   :seon.task/must-read
   :seon.task/evidence]
  [:seon.task/id "361088de3d56"])
#:seon.task{:id "361088de3d56",
 :ns seon.sci.eval,
 :kind :function,
 :must-read [seon.sci.eval/install-evaluated-rows!],
 :evidence
   ["docs/research/agent-platform/landing/perf-admit-tests-opus-2026-09-26.md"],
 :name "program-change-check-scans-the-whole-history",
 :intent
   "installation-covers-program-change? (install-evaluated-rows!, every defining batch) finds the entities written since the context's acquired program with [:find [?entity ...] :where [?entity]] over (db/since (db/history after) basis): an unbound pattern, so Datahike scans the whole EAVT history and filters by transaction (no transaction index exists). On default (pid 70849, 2026-09-26) the same query found 10 entities written in the last 3 transactions in 2,025 ms. It cost 1,636 ms of a 3,735 ms first contracted defn on a fresh MCP branch (pid 75849), 303-800 ms on warm branches, and 1,646-4,160 ms per test request whose members define functions. Cost must follow the written datoms: derive the program change from the settled reports' tx-data, or from attribute-bound reads of the program partition only."}

;; why: The task's edges: the proof tests that define done, the subjects it names, and who must read them. (~46 tokens)
user=> (seon.task/task-edges (seon.db/db) "361088de3d56")
#:seon.task{:subjects #{},
 :proofs
   #{seon.sci.program-change-report-test/a-batched-report-does-not-advance-the-acquired-program},
 :referrers #{}}

;; why: A proof test: the task is done when every proof is green on your branch. Its latest recorded result. (~109 tokens)
user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.sci.program-change-report-test/a-batched-report-does-not-advance-the-acquired-program)
{:seon.error/at #inst "2026-10-07T02:28:50.567Z",
 :seon.error/layer :seon.test/execution,
 :seon.error/operation seon.test/unknown,
 :seon.test/next-tier :none,
 :seon.test/unknown
   "seon.sci.program-change-report-test/a-batched-report-does-not-advance-the-acquired-program",
 :seon.error/message "No admitted execution is recorded for this test."}

;; why: The proof's source: what it asserts. (~568 tokens)
user=> (clojure.repl/source-fn
  'seon.sci.program-change-report-test/a-batched-report-does-not-advance-the-acquired-program)
(deftest ^{:seon/tasks #{"361088de3d56"}} a-batched-report-does-not-advance-the-acquired-program
  (support/with-database
   (fn [connection]
     ;; Tasks carry no program identity; the agent entity (with its seeded
     ;; namespace, a program entity) is setup.
     (support/transacted! connection (support/agent-tx @connection "report-writer"))
     (let [covers? #'sci.eval/installation-covers-program-change?
           note #(vector (support/task-row % "x"))
           ctx (support/fork-cluster-ctx connection)
           acquired (db/db connection)
           _ (support/transacted! connection (note "betweenreprt"))
           written-on (db/db connection)
           own (support/transacted! connection (note "ownreportrow"))
           later (support/transacted! connection (note "laterreportr"))
           projection (db/carried-projection (:db-after own))]
       (is (true? (covers? ctx written-on own [] projection))
           (str "a report written on a value holding the acquired program, ending at its own "
                "transaction, names every change (an unrelated commit before it changes nothing)"))
       (is (not (covers? ctx written-on (assoc own :db-after (:db-after later)) [] projection))
           "a batched report, whose after-value also holds a later transaction, is not covered")
       (is (not (covers? ctx acquired own [] projection))
           "nor is a report that did not start from the value the caller names")
       (support/transacted! connection (support/agent-tx @connection "report-program"))
       (let [on (db/db connection)
             after-program (support/transacted! connection (note "afterprogram"))]
         (is (not (covers? ctx on after-program [] projection))
             "nor one written on a value whose program the context did not acquire"))))))

;; why: A function the task names: its documentation and contract. (~1907 tokens)
user=> (doc seon.sci.eval/install-evaluated-rows!)
{:example "",
 :out [:vector :map],
 :supplied [:seon.db/db :seon.sci.eval/ctx],
 :summary
   "Install committed rows from the evaluations that produced them.",
 :body
   "The turn fork already owns the exact evaluated Var roots. Transfer the\ncomplete batch into the live base before advancing any database-derived\nprojection; installing one root at a time cannot resolve a same-turn test's\nreference to a same-turn function, while replaying source would execute the\ndefinitions twice after settlement.",
 :arglists
   ([{ctx :seon.sci.eval/ctx,
      db :seon.db/db,
      installations :seon.sci.eval/installations,
      report :seon.db/transaction-report,
      written-on :seon.sci.eval/written-on,
      agent-ctx :seon.sci.eval/agent-ctx}]),
 :in
   [:cat
    [:map
     [[:fn
       {:gen/gen seon.sci.eval/ctx-generator,
        :error/message "must be an SCI evaluation context"}
       seon.sci.eval/ctx?]
      [:fn
       {:gen/gen seon.sci.eval/ctx-generator,
        :error/message "must be an SCI evaluation context"}
       seon.sci.eval/ctx?]]
     [[:map
       {:seon.render/ai seon.db/render-transaction-ai,
        :seon.render/html seon.db/render-transaction-html,
        :description
          "The runtime-only Datahike transaction report returned after a committed explicit-connection write. Database values and Datom objects are process values; this registered non-entity schema is not stored."}
       [:db-before :seon.db/database-value]
       [:db-after :seon.db/database-value]
       [:tx-data [:vector :seon.db/transaction-report-datom]]
       [:tempids [:map-of :seon.schema/value :int]]
       [:tx-meta {:optional true} [:map-of :keyword :seon.schema/value]]]
      {:optional true}
      [:map
       {:seon.render/ai seon.db/render-transaction-ai,
        :seon.render/html seon.db/render-transaction-html,
        :description
          "The runtime-only Datahike transaction report returned after a committed explicit-connection write. Database values and Datom objects are process values; this registered non-entity schema is not stored."}
       [:db-before :seon.db/database-value]
       [:db-after :seon.db/database-value]
       [:tx-data [:vector :seon.db/transaction-report-datom]]
       [:tempids [:map-of :seon.schema/value :int]]
       [:tx-meta {:optional true} [:map-of :keyword :seon.schema/value]]]]
     [:seon.sci.eval/written-on
      {:optional true}
      [:fn
       {:gen/gen seon.db/database-value-generator,
        :seon.schema/identity-only true,
        :seon.schema/identity-projection seon.db/database-value-identity,
        :error/message "must be an immutable Datahike database value"}
       seon.db/database-value?]]
     [:seon.sci.eval/agent-ctx
      {:optional true}
      [:fn
       {:gen/gen seon.sci.eval/ctx-generator,
        :error/message "must be an SCI evaluation context"}
       seon.sci.eval/ctx?]]
     [:seon.sci.eval/installations
      [:vector
       [:map
        [[:or :seon.program/declaration-row :seon.program/deletion-row]
        [:or :seon.program/declaration-row :seon.program/deletion-row]]
        [[:map
        [:seon.sci.admit/value
        {:optional true}
        [:any
        {:seon.schema.admission/exemption
        :seon.schema.admission/polymorphic-boundary,
        :seon.schema.admission/reason
        "The evaluator accepts and returns arbitrary Clojure values; admission owns their bounded representation.",
        :gen/elements [nil true 0 "" :k [] {} #{}]}]]
        [:seon.eval/renderer {:optional true} :seon.eval/renderer]
        [:seon.eval/shown {:optional true} :seon.eval/shown]
        [:seon.cluster.eval/error
        {:optional true}
        :seon.cluster.eval/error]
        [:seon.cluster.eval/diagnostics
        {:optional true}
        :seon.cluster.eval/diagnostics]
        [:seon.cluster.eval/interrupted-at
        {:optional true}
        :seon.cluster.eval/interrupted-at]
        [:seon.cluster.eval/output
        {:optional true}
        :seon.cluster.eval/output]
        [:seon.cluster.eval/read-evidence
        {:optional true}
        [:vector :seon.db/read-evidence]]
        [:seon.cluster.eval/ns :seon.cluster.eval/ns]
        [:seon.sci.eval/ending-ns :seon.sci.eval/ending-ns]
        [:seon.program/row {:optional true} :seon.program/row]
        [:seon.sci.eval/bindings
        {:optional true}
        :seon.sci.eval/bindings]
        [:seon.print/options :seon.print/options]
        [:seon.sci.admit/record :seon.sci.admit/record]
        [:seon.sci.eval/untrusted
        {:optional true}
        :seon.sci.eval/untrusted]]
        [:map
        [:seon.sci.admit/value
        {:optional true}
        [:any
        {:seon.schema.admission/exemption
        :seon.schema.admission/polymorphic-boundary,
        :seon.schema.admission/reason
        "The evaluator accepts and returns arbitrary Clojure values; admission owns their bounded representation.",
        :gen/elements [nil true 0 "" :k [] {} #{}]}]]
        [:seon.eval/renderer {:optional true} :seon.eval/renderer]
        [:seon.eval/shown {:optional true} :seon.eval/shown]
        [:seon.cluster.eval/error
        {:optional true}
        :seon.cluster.eval/error]
        [:seon.cluster.eval/diagnostics
        {:optional true}
        :seon.cluster.eval/diagnostics]
        [:seon.cluster.eval/interrupted-at
        {:optional true}
        :seon.cluster.eval/interrupted-at]
        [:seon.cluster.eval/output
        {:optional true}
        :seon.cluster.eval/output]
        [:seon.cluster.eval/read-evidence
        {:optional true}
        [:vector :seon.db/read-evidence]]
        [:seon.cluster.eval/ns :seon.cluster.eval/ns]
        [:seon.sci.eval/ending-ns :seon.sci.eval/ending-ns]
        [:seon.program/row {:optional true} :seon.program/row]
        [:seon.sci.eval/bindings
        {:optional true}
        :seon.sci.eval/bindings]
        [:seon.print/options :seon.print/options]
        [:seon.sci.admit/record :seon.sci.admit/record]
        [:seon.sci.eval/untrusted
        {:optional true}
        :seon.sci.eval/untrusted]]]]]]]]}

;; why: Its stored source, the code to change. (~860 tokens)
user=> (clojure.repl/source-fn 'seon.sci.eval/install-evaluated-rows!)
(defn install-evaluated-rows!
  "Install committed rows from the evaluations that produced them.

  The turn fork already owns the exact evaluated Var roots. Transfer the
  complete batch into the live base before advancing any database-derived
  projection; installing one root at a time cannot resolve a same-turn test's
  reference to a same-turn function, while replaying source would execute the
  definitions twice after settlement."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.sci.eval/ctx :seon.sci.eval/ctx]
      [:seon.db/db :seon.db/database-value]
      [:seon.db/transaction-report {:optional true} :seon.db/transaction-report]
      ;; the committed value the report's write started from, on its connection
      [:seon.sci.eval/written-on {:optional true} :seon.db/database-value]
      [:seon.sci.eval/agent-ctx {:optional true} :seon.sci.eval/ctx]
      [:seon.sci.eval/installations
       [:vector
        [:map
         [:seon.program/row :seon.program/row]
         [:seon.sci.eval/evaluation :seon.sci.eval/evaluation]]]]]]
    [:vector :map]]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    installations :seon.sci.eval/installations
    report :seon.db/transaction-report
    written-on :seon.sci.eval/written-on
    agent-ctx :seon.sci.eval/agent-ctx}]
  (transfer-evaluated-roots! ctx installations)
    ;; THE COMMITTED VALUE'S PROJECTION: every schema and contract the
    ;; settlement wrote, and every registration an earlier run committed. The
    ;; context's own projection is stale for the latter, so a defn naming a
    ;; schema registered in an earlier run refused out of the turn (reds
    ;; group I); folding each row into it again also rebuilt the projection
    ;; once per row. Derived once per committed value (memoized).
    (let [projection (schema/projection-from-database db)
          installed (mapv (fn [{program-row :seon.program/row}]
                            (install-row!
                             {:seon.sci.eval/ctx ctx
                              :seon.db/db db
                              :seon.program/row program-row
                              ::evaluated? true
                              ::prepared-projection projection}))
                          installations)]
      (when-let [snapshot (::base-bindings agent-ctx)]
        (reset! snapshot (base-bindings ctx)))
      (when (and report written-on
                 (every? :seon.sci.eval/installed installed)
                 (installation-covers-program-change?
                  ctx written-on report installations (context-projection ctx)))
        (doseq [target (cond-> [ctx] agent-ctx (conj agent-ctx))]
          (swap! (::kernel/program-snapshot target) assoc :seon.db/db db)))
      installed))

;; why: A schema the contracts above name: the shape of the data in and out. (~44 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.sci.eval/ctx])
{:seon.schema/form
   "[:fn {:error/message \"must be an SCI evaluation context\", :gen/gen seon.sci.eval/ctx-generator} seon.sci.eval/ctx?]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~14 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/db])
{:seon.schema/form ":seon.db/database-value"}

;; why: A schema the contracts above name: the shape of the data in and out. (~81 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/database-value])
{:seon.schema/form
   "[:fn {:error/message \"must be an immutable Datahike database value\", :gen/gen seon.db/database-value-generator, :seon.schema/identity-only true, :seon.schema/identity-projection seon.db/database-value-identity} seon.db/database-value?]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~184 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.db/transaction-report])
{:seon.schema/form
   "[:map {:description \"The runtime-only Datahike transaction report returned after a committed explicit-connection write. Database values and Datom objects are process values; this registered non-entity schema is not stored.\", :seon.render/ai seon.db/render-transaction-ai, :seon.render/html seon.db/render-transaction-html} [:db-before :seon.db/database-value] [:db-after :seon.db/database-value] [:tx-data [:vector :seon.db/transaction-report-datom]] [:tempids [:map-of :seon.schema/value :int]] [:tx-meta {:optional true} [:map-of :keyword :seon.schema/value]]]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~27 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.program/row])
{:seon.schema/form
   "[:or :seon.program/declaration-row :seon.program/deletion-row]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~383 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.sci.eval/evaluation])
{:seon.schema/form
   "[:map [:seon.sci.admit/value {:optional true} [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"The evaluator accepts and returns arbitrary Clojure values; admission owns their bounded representation.\", :gen/elements [nil true 0 \"\" :k [] {} #{}]}]] [:seon.eval/renderer {:optional true} :seon.eval/renderer] [:seon.eval/shown {:optional true} :seon.eval/shown] [:seon.cluster.eval/error {:optional true} :seon.cluster.eval/error] [:seon.cluster.eval/diagnostics {:optional true} :seon.cluster.eval/diagnostics] [:seon.cluster.eval/interrupted-at {:optional true} :seon.cluster.eval/interrupted-at] [:seon.cluster.eval/output {:optional true} :seon.cluster.eval/output] [:seon.cluster.eval/read-evidence {:optional true} [:vector :seon.db/read-evidence]] [:seon.cluster.eval/ns :seon.cluster.eval/ns] [:seon.sci.eval/ending-ns :seon.sci.eval/ending-ns] [:seon.program/row {:optional true} :seon.program/row] [:seon.sci.eval/bindings {:optional true} :seon.sci.eval/bindings] [:seon.print/options :seon.print/options] [:seon.sci.admit/record :seon.sci.admit/record] [:seon.sci.eval/untrusted {:optional true} :seon.sci.eval/untrusted]]"}
