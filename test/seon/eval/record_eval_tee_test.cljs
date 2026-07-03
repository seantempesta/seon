(ns seon.eval.record-eval-tee-test
  "record-eval! must NEVER silently lose the eval row, and detect-and-tee
   `:seon.schema` rows must be transactable for DATA namespaces.

   Run-4 root cause (e2e-demo-findings-2026-06-08 §Run 4 CORRECTION):
   `build-tee-entities` gave `:seon.schema` rows a lookup-ref
   `:seon.schema/ns [:seon.ns/name <kw>]`. For a DATA namespace (an agent
   registers `:workout/duration-seconds`; keyword-ns `:workout` has no
   `(ns …)` form) no `:seon.ns` entity exists, datahike threw \"Nothing
   found for entity id …\", and the WHOLE record-eval! tx failed with only
   a console.warn — silently dropping BOTH the `:seon.schema` row AND the
   `:seon.eval` row (the agent's transcript memory).

   The fix, pinned here:

   - `:seon.schema/ns` is the NESTED-MAP upsert `{:seon.ns/name <kw>}` —
     creates a minimal `:seon.ns` entity for data namespaces, identity-
     upserts onto the existing one for core/`(ns …)` namespaces.
   - `record-eval!` on tx failure logs console.error (NOT warn) and
     RETRIES without the tee rows so the `:seon.eval` row always survives.

   All tests open a FRESH `:memory` datahike conn — nothing here touches
   the live agent conn.

   Run interactively via MCP eval:
     (require 'seon.eval.record-eval-tee-test :reload)
     (cljs.test/run-tests 'seon.eval.record-eval-tee-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent]                          ; :seon.eval/:seon.agent.turn/:seon.ns/:seon.schema registrations
    [seon.analyzer-info :as analyzer-info]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]
    [seon.warn :as warn]))

(defn- unregister!
  "Drop test keys from the in-memory registry (same helper as
   seon.schema-test) — keeps the process-shared registry clean across
   suite runs."
  [& ks]
  (swap! @#'schema/*schemas #(apply dissoc % ks)))

;; ---------------------------------------------------------------------------
;; Fresh :memory conn per test, with the SAME datahike schema the pod
;; installs at boot (db/malli->datahike-schema over agent-bootstrap-attrs).
;; The boot install is required: record-eval!'s eval-map rides NESTED under
;; :seon.agent.turn/evals, and ensure-datahike-attrs!/extract-tx-attrs only see
;; TOP-LEVEL attrs — nested attrs must already be in the conn's schema
;; (exactly the prod situation).
;; ---------------------------------------------------------------------------

(defn- fresh-conn
  "Promise of a fresh :memory datahike conn (history on — record-eval!'s
   tx-meta auto-tagging needs it on the real conn; keep parity), with the
   pod's boot schema transacted."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (db/malli->datahike-schema
                                   client/agent-bootstrap-attrs)})
                     (.then (fn [_] conn))))))))

(defn- record!
  "Call record-eval! against `conn` with `db/*conn*` bound for the SYNC
   extent (record-eval! captures the conn at entry, so the binding only
   needs to cover the synchronous prefix). Returns record-eval!'s Promise."
  [conn args]
  (binding [db/*conn* conn]
    (seval/record-eval! args)))

(defn- schema-tee-row
  "The exact `:seon.schema` tee shape build-tee-entities emits for a
   registered key `k` — nested-map ns upsert, NOT a lookup-ref."
  [k source]
  {:seon.schema/key        k
   :seon.schema/ns         {:seon.ns/name (keyword (namespace k))}
   :seon.schema/source     source
   :seon.schema/created-at (js/Date.)})

(defn- eval-args
  [eval-id turn-id source tee]
  {:seon.eval/id-of-eval       eval-id
   :seon.agent.turn/id-of-turn turn-id
   :seon.eval/at               (js/Date.)
   :seon.eval/duration-ms      1
   :seon.eval/narration        ""
   :seon.eval/source           source
   :seon.eval/ending-ns        'cljs.user
   :seon.eval/result           {:seon.eval/ok? true :seon.eval/value :ok}
   :seon.eval/tee              tee})

;; ---------------------------------------------------------------------------
;; THE fix, half (a): a data-namespace schema registration tees in the SAME
;; tx as the eval row — both rows land, and a minimal :seon.ns entity is
;; created so handlers.ns's [?s :seon.schema/ns ?n] join stays coherent.
;; ---------------------------------------------------------------------------

(deftest data-ns-schema-tee-lands-both-rows-and-upserts-the-ns
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [eval-id (db/new-id!)
                  turn-id (db/new-id!)
                  src     "(seon.schema/register! :probe.dom/dur-secs :int)"
                  tee     [(schema-tee-row :probe.dom/dur-secs src)]]
              (-> (record! conn (eval-args eval-id turn-id src tee))
                  (.then
                    (fn [_]
                      (let [db* @conn]
                        (testing "the :seon.eval row survived"
                          (is (= #{[eval-id true]}
                                 (d/q '[:find ?id ?ok :in $ ?id
                                        :where [?e :seon.eval/id ?id]
                                               [?e :seon.eval/ok? ?ok]]
                                      db* eval-id))))
                        (testing ":seon.schema row exists AND links to a real :seon.ns entity"
                          (is (= #{[:probe.dom/dur-secs :probe.dom]}
                                 (d/q '[:find ?k ?nm
                                        :where [?s :seon.schema/key ?k]
                                               [?s :seon.schema/ns ?n]
                                               [?n :seon.ns/name ?nm]]
                                      db*))))
                        (testing "exactly one :seon.ns entity for the data ns"
                          (is (= [[1]]
                                 (d/q '[:find (count ?n)
                                        :where [?n :seon.ns/name :probe.dom]]
                                      db*)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Regression: a schema registered for a ns whose :seon.ns entity ALREADY
;; exists (core ns, or a prior `(ns …)` eval) upserts onto it — no
;; duplicate entity, existing :seon.ns/source untouched.
;; ---------------------------------------------------------------------------

(deftest existing-ns-schema-tee-upserts-no-duplicate
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (-> (binding [db/*conn* conn]
                  (db/transact!
                    {:seon.db/tx-data [{:seon.ns/name   :probe.code
                                        :seon.ns/source "(ns probe.code)"}]}))
                (.then
                  (fn [seed-r]
                    (is (:seon.db/ok? seed-r) "ns seed tx ok")
                    (let [src "(seon.schema/register! :probe.code/x :int)"
                          tee [(schema-tee-row :probe.code/x src)]]
                      (record! conn (eval-args (db/new-id!) (db/new-id!)
                                               src tee)))))
                (.then
                  (fn [_]
                    (let [db* @conn]
                      (testing "still exactly ONE :probe.code ns entity"
                        (is (= [[1]]
                               (d/q '[:find (count ?n)
                                      :where [?n :seon.ns/name :probe.code]]
                                    db*))))
                      (testing "schema row linked through the EXISTING entity (source intact)"
                        (is (= #{[:probe.code/x "(ns probe.code)"]}
                               (d/q '[:find ?k ?nsrc
                                      :where [?s :seon.schema/key ?k]
                                             [?s :seon.schema/ns ?n]
                                             [?n :seon.ns/source ?nsrc]]
                                    db*))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; THE fix, half (b): a tee row that CANNOT transact (here: the old bug shape,
;; a lookup-ref to a nonexistent :seon.ns) must NOT lose the eval row —
;; record-eval! retries without the tee. The transcript is the agent's
;; memory; losing it is the worst outcome.
;; ---------------------------------------------------------------------------

(deftest bad-tee-row-never-loses-the-eval-row
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [eval-id (db/new-id!)
                  src     "(whatever)"
                  bad-tee [{:seon.schema/key        :no.such.ns/attr
                            ;; deliberately the OLD broken shape: lookup-ref
                            ;; to a :seon.ns that doesn't exist → datahike
                            ;; throws → full tx fails → retry path fires.
                            :seon.schema/ns         [:seon.ns/name :no.such.ns]
                            :seon.schema/source     src
                            :seon.schema/created-at (js/Date.)}]]
              (-> (record! conn (eval-args eval-id (db/new-id!) src bad-tee))
                  (.then
                    (fn [_]
                      (let [db* @conn]
                        (testing "the eval row SURVIVED the failing tee"
                          (is (= #{[eval-id]}
                                 (d/q '[:find ?id :in $ ?id
                                        :where [?e :seon.eval/id ?id]]
                                      db* eval-id))))
                        (testing "the unresolvable tee row was dropped, not half-written"
                          (is (= #{}
                                 (d/q '[:find ?k
                                        :where [?s :seon.schema/key ?k]]
                                      db*)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; A nil :seon.eval/source must NEVER drop the row. A comment-only / repaired
;; entry can hand record-eval! a nil source; the attr is registered :string,
;; so a nil would fail Malli and sink the WHOLE tx — the exact data loss
;; observed in live-drive-validation-2026-06-28 (3 eval rows vanished from
;; turn 1, "DATA LOSS — bare eval row … source: null"). record-eval! coerces
;; nil→"" at the write boundary so the row always lands.
;; ---------------------------------------------------------------------------

(deftest nil-source-still-records-the-eval-row
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [eval-id (db/new-id!)]
              ;; nil source + empty tee = the "bare eval row, no tee rows to
              ;; drop" path that silently dropped rows pre-fix.
              (-> (record! conn (eval-args eval-id (db/new-id!) nil []))
                  (.then
                    (fn [_]
                      (let [db* @conn]
                        (testing "the eval row landed despite a nil source"
                          (is (= #{[eval-id ""]}
                                 (d/q '[:find ?id ?src :in $ ?id
                                        :where [?e :seon.eval/id ?id]
                                               [?e :seon.eval/source ?src]]
                                      db* eval-id)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; HONEST RECORDS (task #24 symptom 3): when the tee tx fails and only the
;; bare eval row could be recovered, the eval row carries
;; :seon.eval/record-error and seon.warn/check-record-errors derives a
;; warning cluster from it — the partial record is loud, not silent.
;; ---------------------------------------------------------------------------

(deftest dropped-tee-stamps-record-error-and-the-warning-surfaces
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [eval-id (db/new-id!)
                  src     "(whatever)"
                  bad-tee [{:seon.schema/key        :no.such.ns/attr2
                            ;; the OLD broken lookup-ref shape — guaranteed
                            ;; tee tx failure, fires the recovery path.
                            :seon.schema/ns         [:seon.ns/name :no.such.ns]
                            :seon.schema/source     src
                            :seon.schema/created-at (js/Date.)}]]
              (-> (record! conn (eval-args eval-id (db/new-id!) src bad-tee))
                  (.then
                    (fn [_]
                      (let [db* @conn
                            err (ffirst
                                  (d/q '[:find ?err :in $ ?id
                                         :where [?e :seon.eval/id ?id]
                                                [?e :seon.eval/record-error ?err]]
                                       db* eval-id))]
                        (testing "the recovered eval row carries :seon.eval/record-error"
                          (is (string? err))
                          (is (str/includes? (str err) "DROPPED")))
                        (testing "seon.warn/check-record-errors derives the warning"
                          (let [{:seon.warn/keys [kind affected]}
                                (warn/check-record-errors {:seon.db/db db*})]
                            (is (= :record-errors kind))
                            (is (= [eval-id] (mapv :seon.warn/sym affected))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; deftest detection (board #33 part 1, live resume test 2026-06-10).
;;
;; cljs.analyzer stores the deftest marker as TOP-LEVEL `:test true` on the
;; var-map and explicitly dissoc's `:test` from `:meta` (analyzer.cljc ~1958,
;; "remove actual test metadata … cannot be present in analysis cached to
;; disk"). build-tee-entities used to check `(:test (:meta var-map))` —
;; ALWAYS nil — so agent deftests teed only as :seon.fn rows, never
;; :seon.test rows: the :seon.test replay lane, tests-referring-to and the
;; auto-run never saw agent tests. These tests pin the fix: a deftest evaled
;; through the eval surface tees EXACTLY one :seon.test row (core
;; index-tests shape: sym/ns/source/created-at) and NO :seon.fn row —
;; matching the core's disjoint core-vars/!indexed-test-vars split
;; and keeping resume single-lane. A plain defn still tees :seon.fn only.
;; ---------------------------------------------------------------------------

(defn- tee-for
  "Eval `source` in `ns-sym` on the bootstrap compile-state and return
   build-tee-entities' output for exactly that form — the same
   snapshot-before/eval/diff sequence eval-batch! performs."
  [cs ns-sym source]
  (let [defs-before    (analyzer-info/snapshot-defs cs)
        schemas-before (schema/current-keys)]
    (-> (seval/eval cs source {:seon.eval/starting-ns ns-sym :seon.eval/analyze-deps? false})
        (.then (fn [r]
                 (is (:seon.eval/ok? r) (str "eval ok: " source))
                 ((deref #'seval/build-tee-entities)
                  {:seon.eval/compile-state  cs
                   :seon.eval/defs-before    defs-before
                   :seon.eval/schemas-before schemas-before
                   :seon.eval/source         source
                   :seon.eval/at             (js/Date.)}))))))

(deftest agent-deftest-tees-a-seon-test-row-not-a-fn-row
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs "(ns probe.teetest (:require [cljs.test]))"
                            {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                (.then (fn [r] (is (:seon.eval/ok? r) "probe ns evals")))
                (.then (fn [_]
                         (tee-for cs 'probe.teetest
                                  "(cljs.test/deftest tee-probe-test (cljs.test/is (= 1 1)))")))
                (.then
                  (fn [tee]
                    (let [test-rows (filter :seon.test/sym tee)
                          fn-rows   (filter :seon.fn/sym tee)
                          row       (first test-rows)]
                      (testing "EXACTLY one :seon.test row for the deftest"
                        (is (= 1 (count test-rows)))
                        (is (= "probe.teetest/tee-probe-test" (:seon.test/sym row))))
                      (testing "core index-tests shape: sym/ns/source/created-at"
                        (is (= {:seon.ns/name :probe.teetest} (:seon.test/ns row))
                            "nested-map ns upsert, same as every tee row")
                        (is (= "(cljs.test/deftest tee-probe-test (cljs.test/is (= 1 1)))"
                               (:seon.test/source row)))
                        (is (some? (:seon.test/created-at row))))
                      (testing "NO :seon.fn row — deftests are test-lane only, like core"
                        (is (= [] (vec fn-rows))))
                      ;; And the row LANDS: record-eval! with this tee →
                      ;; :seon.test row queryable on a fresh conn.
                      (-> (fresh-conn)
                          (.then
                            (fn [conn]
                              (-> (record! conn (eval-args (db/new-id!) (db/new-id!)
                                                           (:seon.test/source row) tee))
                                  (.then
                                    (fn [_]
                                      (testing ":seon.test row EXISTS after record-eval!"
                                        (is (= #{["probe.teetest/tee-probe-test" :probe.teetest]}
                                               (d/q '[:find ?sym ?nm
                                                      :where [?e :seon.test/sym ?sym]
                                                             [?e :seon.test/ns ?n]
                                                             [?n :seon.ns/name ?nm]]
                                                    @conn))))))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest plain-defn-still-tees-a-seon-fn-row-only
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs "(ns probe.teefn)" {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                (.then (fn [_]
                         (tee-for cs 'probe.teefn "(defn tee-probe-fn [n] (+ n 1))")))
                (.then
                  (fn [tee]
                    (testing "defn detection unchanged: one :seon.fn row, zero :seon.test rows"
                      (is (= ["probe.teefn/tee-probe-fn"]
                             (mapv :seon.fn/sym (filter :seon.fn/sym tee))))
                      (is (= [] (vec (filter :seon.test/sym tee))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Detect-and-tee body-retry loss (live-proven 2026-06-27): a defn an agent
;; DEBUGS — define → fail in the body → fix the BODY keeping the SAME
;; signature → redefine — works in-session but SILENTLY does not persist.
;;
;; Mechanism: under `:def-emits-var true`, `cljs.analyzer`'s `parse 'def`
;; registers the var-map into `:defs` BEFORE analyzing the body and never
;; rolls it back. A body that analyzes cleanly but FAILS the eval (here a
;; warning-promoted `:undeclared-var` — the same failure mode the agent hit
;; with a bad query) leaves the FULL var-map (incl. `:fn-var`), whose
;; [[seon.analyzer-info/var-digest]] EQUALS what a successful same-signature
;; retry produces. That collision makes the retry's `defs-before` already
;; hold the digest, so `defs-since` sees no change and the tee SKIPS the
;; `:seon.fn` row — the fn resolves + is callable but vanishes on the next
;; restart (the program graph is what boot reconstitutes from).
;;
;; The cure: `eval-form-entry!` calls `analyzer-info/remove-phantom-defs!` on
;; the failure path, dropping the syms THIS form newly registered, so the
;; retry is genuinely-new and tees. A UNIQUE ns per run keeps the assertion
;; deterministic on the process-shared bootstrap compile-state. Without the
;; fix the final assertion is `#{}` (no row); with it, the row lands.
;; ---------------------------------------------------------------------------

(deftest failed-body-defn-then-same-signature-retry-tees
  (async done
    ;; open-agent-conn! (NOT fresh-conn): eval-batch! opens a tx-context with
    ;; the causality bundle (:seon.db/agent-id/eval-id/origin), so record-eval!
    ;; auto-tags the tx with those tx-meta attrs — they must be in the conn's
    ;; schema. open-agent-conn! installs pod-full-schema (entity + tx-meta);
    ;; fresh-conn here installs only entity attrs.
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs    (aget res 0)
                  conn  (aget res 1)
                  prev  db/*conn*
                  uniq  (str "probe.teeretry" (rand-int 1000000000))
                  fq    (str uniq "/recover")
                  ;; membership probe: #{[fq]} when the row exists, else #{}.
                  rows  (fn [] (d/q '[:find ?s :in $ ?s
                                      :where [?f :seon.fn/sym ?s]]
                                    @conn fq))
                  ;; one full eval-batch! per attempt (run-id nil → no fence),
                  ;; exercising eval-form-entry!'s failure-path cleanup wiring.
                  batch (fn [src] (seval/eval-batch! cs (repl-internal/parse-forms src)
                                                     (symbol uniq) "tee-retry-test"
                                                     (db/new-id!) nil))]
              ;; set! (not binding) so *conn* spans the async eval-batch!
              ;; internals (record-eval! reads it post-await), mirroring how
              ;; the live pod root-set!s the conn. Restored in .finally.
              (set! db/*conn* conn)
              (-> (seval/eval cs (str "(ns " uniq ")")
                              {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                  (.then (fn [_] (batch "(defn recover [x] (zzz-undeclared-probe x))")))
                  (.then
                    (fn [b1]
                      (testing "the body-undeclared defn's eval FAILS (errors-are-values)"
                        (is (= 1 (:seon.eval/n-fail b1)))
                        (is (= 0 (:seon.eval/n-ok b1))))
                      (testing "a failed eval tees nothing"
                        (is (= #{} (rows))))
                      (batch "(defn recover [x] (inc x))")))
                  (.then
                    (fn [b2]
                      (testing "the same-signature retry SUCCEEDS"
                        (is (= 1 (:seon.eval/n-ok b2)))
                        (is (= 0 (:seon.eval/n-fail b2))))
                      (testing "and NOW tees a :seon.fn row (the fix; #{} without it)"
                        (is (= #{[fq]} (rows))))))
                  (.finally (fn [] (set! db/*conn* prev)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #39: the eval is the transaction boundary — a `schema/register!` inside a
;; FAILED form persists NOTHING (neither a :seon.schema row NOR an in-memory
;; registry entry), and a subsequent SUCCESSFUL register! of the same key
;; tees normally.
;;
;; Mechanism: `register!`'s self-tee DEFERS its DB write when a `:seon.db/
;; eval-id` is in the tx-context (every eval-batch! per-form scope), because
;; the GATED detect-and-tee (build-tee-entities in record-eval!) owns the
;; :seon.schema row and writes it ONLY on success. On the failure path
;; eval-form-entry! also calls `schema/discard-registrations!` over the form's
;; newly-registered keys, the schema analog of remove-phantom-defs!. Without
;; the fix the eager self-tee wrote the row mid-eval and the registry kept the
;; key, so `(do (register! …) (broken))` registered the schema anyway.
;; ---------------------------------------------------------------------------

(deftest register-in-failed-form-persists-nothing-then-success-tees
  (async done
    ;; open-agent-conn! (like the body-retry test): eval-batch! tx-meta-tags
    ;; with the causality bundle, so the conn needs pod-full-schema.
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs    (aget res 0)
                  conn  (aget res 1)
                  prev  db/*conn*
                  uniq  (str "probe.tee39" (rand-int 1000000000))
                  ;; a unique DATA attr key per run (process-shared registry):
                  ;; multi-segment ns so register! accepts it; never collides.
                  attr  (keyword (str uniq ".dom") "metric")
                  ;; membership probe in the DB: #{[attr]} when a :seon.schema
                  ;; row exists, else #{}.
                  rows  (fn [] (d/q '[:find ?k :in $ ?k
                                      :where [?s :seon.schema/key ?k]]
                                    @conn attr))
                  reg?  (fn [] (contains? (schema/current-keys) attr))
                  batch (fn [src] (seval/eval-batch! cs (repl-internal/parse-forms src)
                                                     (symbol uniq) "tee39-test"
                                                     (db/new-id!) nil))
                  ;; the failed form: register! RUNS, then a deliberate throw
                  ;; fails the whole `do` (errors-are-values → :ok false).
                  fail-src (str "(do (seon.schema/register! " attr " :int)"
                                "    (throw (js/Error. \"deliberate #39 probe\")))")
                  ok-src   (str "(seon.schema/register! " attr " :int)")]
              (set! db/*conn* conn)
              (-> (seval/eval cs (str "(ns " uniq ")")
                              {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                  (.then (fn [_] (batch fail-src)))
                  (.then
                    (fn [b1]
                      (testing "the register!+throw form FAILS (errors-are-values)"
                        (is (= 1 (:seon.eval/n-fail b1)))
                        (is (= 0 (:seon.eval/n-ok b1))))
                      (testing "no :seon.schema row persisted — the self-tee deferred"
                        (is (= #{} (rows))))
                      (testing "and the in-memory registry rolled the key back"
                        (is (false? (reg?))))
                      (batch ok-src)))
                  (.then
                    (fn [b2]
                      (testing "the standalone register! SUCCEEDS"
                        (is (= 1 (:seon.eval/n-ok b2)))
                        (is (= 0 (:seon.eval/n-fail b2))))
                      (testing "NOW the :seon.schema row is teed (detect-and-tee, no regression)"
                        (is (= #{[attr]} (rows))))
                      (testing "and the registry holds the key"
                        (is (true? (reg?))))))
                  (.finally (fn []
                              (set! db/*conn* prev)
                              (unregister! attr)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; B9 tee-gate bug (db-is-the-running-system PRD): a usage-example
;; `(defn f {:test (fn [] …)} …)` carries the analyzer's TOP-LEVEL `:test
;; true` marker (deftest-def? TRUE) just like a real `(deftest …)`, so the
;; old gate `(not (deftest-def? …))` DROPPED the defn's :seon.fn row (lost!)
;; AND mis-filed it as a :seon.test row. Classifying on the FORM HEAD
;; (`defn-form?`) fixes it: a `:test`-bearing defn → :seon.fn row, NO
;; :seon.test row; a `(deftest …)` (defn-form? FALSE) → :seon.test row only.
;; ---------------------------------------------------------------------------

(deftest test-bearing-defn-tees-a-fn-row-not-a-test-row
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs "(ns probe.teeexample)" {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                (.then (fn [_]
                         (tee-for cs 'probe.teeexample
                                  "(defn tee-example-fn {:test (fn [] (assert true))} [a b] (+ a b))")))
                (.then
                  (fn [tee]
                    (testing "the :test-bearing defn produces EXACTLY one :seon.fn row"
                      (is (= ["probe.teeexample/tee-example-fn"]
                             (mapv :seon.fn/sym (filter :seon.fn/sym tee)))))
                    (testing "and NO :seon.test row — a defn is never a test, regardless of :test marker"
                      (is (= [] (vec (filter :seon.test/sym tee))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Replay lane — the teed :seon.test row (plus its ns row) replays through the
;; :seon.test lane via replay-program-graph!, reconstituting the deftest var.
;; ---------------------------------------------------------------------------

(deftest teed-deftest-replays-through-the-test-lane
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (client/open-agent-conn!)
                (.then
                  (fn [conn]
                    (binding [db/*conn* conn]
                      (-> (db/transact!
                            {:seon.db/tx-data
                             ;; the EXACT shapes the fixed tee emits: ns row
                             ;; from the (ns …) eval, :seon.test row from the
                             ;; deftest eval (nested-map ns upsert).
                             [{:seon.ns/name   :probe.teereplay
                               :seon.ns/source "(ns probe.teereplay (:require [cljs.test]))"}
                              {:seon.test/sym  "probe.teereplay/replayed-test"
                               :seon.test/ns   {:seon.ns/name :probe.teereplay}
                               :seon.test/source
                               "(cljs.test/deftest replayed-test (cljs.test/is (= 1 1)))"
                               :seon.test/created-at (js/Date.)}]})
                          (.then
                            (fn [_]
                              (client/replay-program-graph!
                                {:seon.client/conn conn :seon.client/compile-state cs
                                 :seon.client/agent-id "record-eval-tee-test"})))
                          (.then
                            (fn [stats]
                              (is (= 1 (:seon.client/replay-n-total stats))
                                  "the agent ns is the one load unit (its deftest is reconstituted into it)")
                              (is (= 0 (:seon.client/replay-n-fail stats))
                                  "the deftest reconstitutes via the ns's whole-source load")
                              (seval/eval cs "(some? probe.teereplay/replayed-test)"
                                          {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:seon.eval/ok? r) "replayed corpus is live")
                              (is (true? (:seon.eval/value r))
                                  "deftest var reconstituted on the compile-state"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Entity-schema tee (task #24 symptom 2, opus run2 live stack):
;; a `:map {:seon.db/entity true}` registration's key (`:probe.garden.plant`)
;; has NO keyword namespace — the old tee unconditionally wrote
;; `{:seon.ns/name (keyword nil)}` → Malli `:seon.ns/name … got nil` →
;; whole tx failed → tee row dropped. Fixed: schema-tee-row omits
;; :seon.schema/ns for nil-namespace keys (mirroring
;; seon.client/index-schemas), so the row lands and is replay-selectable
;; (its source is a `(…)` registration call).
;; ---------------------------------------------------------------------------

(deftest entity-schema-under-fresh-ns-tees-and-lands
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs "(ns probe.garden)" {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                (.then (fn [_]
                         (seval/eval cs "(seon.schema/register! :probe.garden.plant/id [:and {:seon.db/identity true} :seon.db/id])"
                                     {:seon.eval/starting-ns 'probe.garden :seon.eval/analyze-deps? false})))
                (.then (fn [r] (is (:seon.eval/ok? r) "id-attr registers")))
                (.then (fn [_]
                         (tee-for cs 'probe.garden
                                  "(seon.schema/register! :probe.garden.plant [:map {:seon.db/entity true} [:probe.garden.plant/id :probe.garden.plant/id]])")))
                (.then
                  (fn [tee]
                    (let [rows (filter #(= :probe.garden.plant (:seon.schema/key %)) tee)
                          row  (first rows)]
                      (testing "exactly one tee row for the entity-schema key"
                        (is (= 1 (count rows))))
                      (testing "NO :seon.schema/ns — nil keyword namespace must not produce a nil ns link"
                        (is (not (contains? row :seon.schema/ns))))
                      (testing "replay-selectable: source is a `(…)` registration call"
                        (is (str/starts-with? (str/trim (:seon.schema/source row)) "(")))
                      ;; And it LANDS: the whole tee (entity row included)
                      ;; transacts with the eval row — no recovery path.
                      (let [eval-id (db/new-id!)]
                        (-> (fresh-conn)
                            (.then
                              (fn [conn]
                                (-> (record! conn (eval-args eval-id (db/new-id!)
                                                             (:seon.schema/source row) tee))
                                    (.then
                                      (fn [_]
                                        (let [db* @conn]
                                          (testing "tee row present in the store"
                                            (is (= #{[:probe.garden.plant]}
                                                   (d/q '[:find ?k :in $ ?k
                                                          :where [?s :seon.schema/key ?k]]
                                                        db* :probe.garden.plant))))
                                          (testing "eval row carries NO record-error — nothing was dropped"
                                            (is (= #{}
                                                   (d/q '[:find ?err :in $ ?id
                                                          :where [?e :seon.eval/id ?id]
                                                                 [?e :seon.eval/record-error ?err]]
                                                        db* eval-id))))))))))))))))))
        (.then (fn [_]
                 (unregister! :probe.garden.plant/id :probe.garden.plant)
                 (done)))
        (.catch (fn [e]
                  (unregister! :probe.garden.plant/id :probe.garden.plant)
                  (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; register! self-tee (task #24 symptom 1, orchestrator-verified live
;; 2026-06-12): a REPL-scope register! with a bound conn tees its own
;; :seon.schema row (replayable call-form source); without a conn it
;; registers exactly as before — no tee, no throw. An agent-eval
;; registration writing through BOTH the self-tee and the eval tee still
;; yields exactly ONE row (identity upsert on :seon.schema/key).
;; ---------------------------------------------------------------------------

(deftest repl-scope-register-with-conn-tees-a-replayable-row
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [prev db/*conn*
                  k    :probe.selftee/attr]
              (set! db/*conn* conn)
              (-> (js/Promise.resolve (schema/register! k :string))
                  (.then (fn [ret]
                           (is (= k ret) "register! returns the key as before")
                           (or @schema/!last-tee (js/Promise.resolve nil))))
                  (.then
                    (fn [r]
                      (is (:seon.db/ok? r) "self-tee tx committed")
                      (testing "the row exists with the replayable call-form source"
                        (is (= #{[k "(seon.schema/register! :probe.selftee/attr :string)"]}
                               (d/q '[:find ?k ?src :in $ ?k
                                      :where [?s :seon.schema/key ?k]
                                             [?s :seon.schema/source ?src]]
                                    @conn k))))))
                  (.finally (fn []
                              (set! db/*conn* prev)
                              (unregister! k)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest register-without-conn-neither-throws-nor-tees
  (let [prev db/*conn*
        k    :probe.selftee/no-conn]
    (try
      (set! db/*conn* nil)
      (reset! schema/!last-tee :sentinel)
      (is (= k (schema/register! k :int)) "registers exactly as before")
      (is (nil? @schema/!last-tee) "tee hook ran and skipped — no tx, no throw")
      (finally
        (set! db/*conn* prev)
        (unregister! k)))))

(deftest agent-eval-register-yields-exactly-one-row
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (fresh-conn)])
        (.then
          (fn [res]
            (let [cs   (aget res 0)
                  conn (aget res 1)
                  prev db/*conn*
                  k    :probe.teeagent/x
                  src  "(seon.schema/register! :probe.teeagent/x :int)"]
              ;; conn bound for the WHOLE sequence, as in the live pod
              ;; (start-agent! root-set!s *conn*): the self-tee fires
              ;; during the eval AND record-eval! writes the eval tee.
              (set! db/*conn* conn)
              (-> (seval/eval cs "(ns probe.teeagent)" {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                  (.then (fn [_] (tee-for cs 'probe.teeagent src)))
                  (.then
                    (fn [tee]
                      (-> (or @schema/!last-tee (js/Promise.resolve nil))
                          (.then (fn [_] (record! conn (eval-args (db/new-id!) (db/new-id!)
                                                                  src tee)))))))
                  (.then
                    (fn [_]
                      (testing "exactly ONE :seon.schema row — self-tee + eval tee upsert, never duplicate"
                        (is (= [[1]]
                               (vec (d/q '[:find (count ?s) :in $ ?k
                                           :where [?s :seon.schema/key ?k]]
                                         @conn k)))))))
                  (.finally (fn []
                              (set! db/*conn* prev)
                              (unregister! k)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest core-claimed-row-is-never-overwritten-by-the-self-tee
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [prev db/*conn*
                  k    :probe.selftee.claimed/x]
              ;; Boot-index claim: shape-literal source under a
              ;; :core-seed-origin tx (same provenance rule
              ;; prune-core-ghosts! honors).
              (-> (db/with-tx-context {:seon.db/origin :core-seed}
                    (fn []
                      (db/transact!
                        {:seon.db/tx-data [{:seon.schema/key        k
                                            :seon.schema/source     ":string"
                                            :seon.schema/created-at (js/Date.)}]
                         :seon.db/conn    conn})))
                  (.then
                    (fn [seed-r]
                      (is (:seon.db/ok? seed-r) "core claim tx ok")
                      (set! db/*conn* conn)
                      (reset! schema/!last-tee :sentinel)
                      (schema/register! k :string)
                      (-> (js/Promise.resolve @schema/!last-tee)
                          (.then
                            (fn [tee-ret]
                              (is (nil? tee-ret)
                                  "self-tee SKIPPED a core-claimed row")
                              (testing "boot-indexed source untouched — boot stays the owner"
                                (is (= #{[":string"]}
                                       (d/q '[:find ?src :in $ ?k
                                              :where [?s :seon.schema/key ?k]
                                                     [?s :seon.schema/source ?src]]
                                            @conn k))))))
                          (.finally (fn []
                                      (set! db/*conn* prev)
                                      (unregister! k))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; The registry/store disagreement, closed end-to-end: a self-teed row
;; replays on boot (simulated registry rebuild — the key is NOT in the
;; in-memory registry until replay re-runs the stored call form).
(deftest teed-registration-replays-after-registry-rebuild
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs   (aget res 0)
                  conn (aget res 1)
                  k    :probe.selftee.replay/y]
              (is (false? (schema/registered? k))
                  "precondition: key absent from the registry (the post-restart state)")
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/tx-data
                       ;; the EXACT row shape the self-tee writes
                       [{:seon.schema/key        k
                         :seon.schema/source     "(seon.schema/register! :probe.selftee.replay/y :string)"
                         :seon.schema/ns         {:seon.ns/name :probe.selftee.replay}
                         :seon.schema/created-at (js/Date.)}]})
                    (.then
                      (fn [_]
                        (client/replay-program-graph!
                          {:seon.client/conn conn :seon.client/compile-state cs
                           :seon.client/agent-id "record-eval-tee-test"})))
                    (.then
                      (fn [stats]
                        (is (= 0 (:seon.client/replay-n-fail stats))
                            "the registration call replays cleanly")
                        (is (true? (schema/registered? k))
                            "registry rebuilt from the store — attr is live again")))
                    (.finally (fn [] (unregister! k))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; Task #37 — the tee-family remainder: a teed ENTITY-schema row has a
;; SINGLE-SEGMENT ident (:probe.selftee.entityreplay — keyword namespace
;; nil), so the tee files it with NO :seon.schema/ns link. The DB-layer
;; load reconstitutes schema rows through their ns link, so a ns-less
;; entity-schema row is loaded by `standalone-schema-sources` instead —
;; a fully-qualified register! call eval'd from 'cljs.user.
(deftest teed-entity-schema-row-replays-after-registry-rebuild
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs   (aget res 0)
                  conn (aget res 1)
                  k    :probe.selftee.entityreplay]
              (is (false? (schema/registered? k))
                  "precondition: entity key absent from the registry (post-restart state)")
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/tx-data
                       ;; the EXACT row shape the tee writes for an entity
                       ;; schema: nil keyword-ns → NO :seon.schema/ns link.
                       [{:seon.schema/key        k
                         :seon.schema/source     "(seon.schema/register! :probe.selftee.entityreplay [:map [:probe.selftee.entityreplay/x :string]])"
                         :seon.schema/created-at (js/Date.)}]})
                    (.then
                      (fn [_]
                        (client/replay-program-graph!
                          {:seon.client/conn conn :seon.client/compile-state cs
                           :seon.client/agent-id "record-eval-tee-test"})))
                    (.then
                      (fn [stats]
                        (is (= 0 (:seon.client/replay-n-fail stats))
                            (str "entity-schema registration replays cleanly — "
                                 (pr-str stats)))
                        (is (true? (schema/registered? k))
                            "registry rebuilt from the store — entity schema live again")))
                    (.finally (fn [] (unregister! k :probe.selftee.entityreplay/x))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Agent-no-override-core guard (db-is-the-running-system PRD; Sean: agents
;; must NOT override compiled core/third-party fns). core-origin-fn-syms
;; detects, by ORIGIN, which of the syms an agent eval just (re)defined are
;; existing compiled-core fns (current source datom's tx is :core-seed);
;; reject-core-overrides drops those :seon.fn tee rows so the core display
;; row stays intact and the override takes no ephemeral live effect. A NEW
;; sym, or one the agent itself owns, passes through.
;; ---------------------------------------------------------------------------

(defn- seed-fn-row!
  "Transact a :seon.fn row for `sym` under tx-origin `origin` on `conn`."
  [conn origin sym]
  (db/with-tx-context {:seon.db/origin origin}
    (fn []
      (db/transact!
        {:seon.db/tx-data [{:seon.fn/sym        sym
                            :seon.fn/source     (str "(defn x [] " (name origin) ")")
                            :seon.fn/created-at (js/Date.)}]
         :seon.db/conn    conn}))))

(defn- install-tx-meta-schema!
  "fresh-conn installs only agent-bootstrap-attrs (entity attrs); the
   tx-meta attrs (`:seon.db/origin`, …) are a SEPARATE schema set that
   the live pod installs at boot. The override guard reads
   `[?tx :seon.db/origin :core-seed]`, so the test conn must install
   it too (otherwise the origin datom never lands and the query is empty
   — exactly the prod schema, just split across two install calls)."
  [conn]
  (db/transact! {:seon.db/tx-data (db/tx-meta-datahike-schema)
                 :seon.db/conn    conn}))

(deftest core-origin-fn-syms-detects-only-core-seed-syms
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (binding [db/*conn* conn]
              (-> (install-tx-meta-schema! conn)
                  (.then (fn [_] (seed-fn-row! conn :core-seed "seon.core.demo/corefn")))
                  (.then (fn [_] (seed-fn-row! conn :agent "my.agent/agentfn")))
                  (.then
                    (fn [_]
                      (let [db* @conn]
                        (testing "only the :core-seed sym is flagged"
                          (is (= #{"seon.core.demo/corefn"}
                                 (seval/core-origin-fn-syms
                                   db* ["seon.core.demo/corefn"
                                        "my.agent/agentfn"
                                        "my.agent/brand-new-fn"]))))
                        (testing "an agent-origin sym is NOT flagged"
                          (is (= #{}
                                 (seval/core-origin-fn-syms db* ["my.agent/agentfn"]))))
                        (testing "a sym with no row at all is NOT flagged"
                          (is (= #{}
                                 (seval/core-origin-fn-syms db* ["my.agent/never-seen"])))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reject-core-overrides-drops-blocked-fn-rows-only
  (let [tee     [{:seon.fn/sym "seon.core.demo/corefn"
                  :seon.fn/source "(defn corefn [] :override)"}
                 {:seon.fn/sym "my.agent/agentfn"
                  :seon.fn/source "(defn agentfn [] :mine)"}
                 {:seon.ns/name :my.agent :seon.ns/source "(ns my.agent)"}
                 [:db/retract [:seon.ns/name :my.agent] :seon.ns/requires :foo]]
        blocked #{"seon.core.demo/corefn"}
        out     (seval/reject-core-overrides tee blocked)]
    (testing "the blocked core-override :seon.fn row is removed"
      (is (nil? (some #(and (map? %) (= "seon.core.demo/corefn" (:seon.fn/sym %))) out))))
    (testing "the agent's own :seon.fn row passes through"
      (is (some? (some #(and (map? %) (= "my.agent/agentfn" (:seon.fn/sym %))) out))))
    (testing "non-:seon.fn rows (ns map, retract vector) pass through untouched"
      (is (some? (some #(and (map? %) (= :my.agent (:seon.ns/name %))) out)))
      (is (some? (some vector? out))))
    (testing "empty blocked set is a no-op identity"
      (is (= tee (seval/reject-core-overrides tee #{}))))))

;; ---------------------------------------------------------------------------
;; #26 half (b) — the eval-reader multi-form behavior is CORRECT, not buggy.
;;
;; A multi-form SOURCE STRING is two distinct concerns:
;;   1. EVAL: `cljs.js/eval-str` runs EVERY top-level form in the string and
;;      returns the LAST form's value — nothing is silently dropped, the
;;      return value is well-defined (live-proven: "(def aaa 1)(def bbb 2)(+
;;      aaa bbb)" => 3 with aaa+bbb both bound).
;;   2. PERSISTENCE: the strict-head tee gate `defn-form?` is TRUE only for a
;;      lone single `(defn …)`; a multi-form source is FALSE → it RUNS as
;;      scratch but is never teed/replayed. Correct: re-evaling on boot can
;;      never re-fire a multi-form's side effects (#29 class).
;;
;; These don't conflict because the AGENT path never feeds a multi-form
;; string to one tee gate: `seon.repl.internal/parse-forms` SPLITS the
;; submission into one `:kind :form` entry PER top-level form, and
;; eval-batch! classifies EACH entry's single-form source — so every
;; individual `(defn …)` in a multi-defn submission DOES persist.
;; ---------------------------------------------------------------------------

(deftest multi-form-source-reader-behavior-is-correct
  (testing "read-all-forms returns ALL top-level forms (nothing dropped)"
    (is (= 3 (count (#'seval/read-all-forms
                      "(def a 1) (def b 2) (+ a b)")))))
  (testing "defn-form? tee gate: TRUE for a lone defn, FALSE for multi-form"
    (is (true?  (seval/defn-form? "(defn f [] 1)")))
    (is (false? (seval/defn-form? "(defn f [] 1) (defn g [] 2)")))
    (is (false? (seval/defn-form? "(def x 1) (def y 2)"))))
  (testing "the AGENT path splits multi-form so each defn tees individually:
            parse-forms yields one single-form entry per top-level form, and
            each lone (defn …) entry passes the same defn-form? tee gate"
    (let [entries (repl-internal/parse-forms
                    "(defn f1 [] 1)\n(defn f2 [] 2)\n(+ 1 2)")]
      (is (= 3 (count entries)))
      (is (= [:form :form :form] (mapv :seon.repl/kind entries)))
      (is (= [true true false]
             (mapv #(seval/defn-form? (:seon.repl/source %)) entries))
          "both defns tee; the trailing expr does not — single-lane resume"))))

;; ---------------------------------------------------------------------------
;; M4 + C28 structural store: the eval-batch! tee writes the reified
;; require edges for the ending ns (:seon.ns/require-edges — alias/refer
;; facts from the ANALYZER, seon.analyzer-info/ns-require-edges) and the
;; declared read-set for every teed fn (:seon.fn/read-attrs — qualified
;; keyword literals walked off the READ form), and a REDEF diffs both
;; (stale keywords are retracted, never accumulated).
;; ---------------------------------------------------------------------------

(deftest eval-batch-tees-require-edges-and-read-attrs
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs    (aget res 0)
                  conn  (aget res 1)
                  prev  db/*conn*
                  uniq  (str "probe.teeedge" (rand-int 1000000000))
                  fq    (str uniq "/watcher")
                  batch (fn [src] (seval/eval-batch! cs (repl-internal/parse-forms src)
                                                     (symbol uniq) "tee-edge-test"
                                                     (db/new-id!) nil))
                  read-attrs (fn [] (set (:seon.fn/read-attrs
                                           (db/pull @conn [:seon.fn/read-attrs]
                                                    [:seon.fn/sym fq]))))]
              (set! db/*conn* conn)
              (-> (batch (str "(ns " uniq " (:require [seon.db :as pdb]"
                              " [clojure.string :as pstr]))"))
                  (.then
                    (fn [b1]
                      (testing "the ns form evals ok"
                        (is (= 1 (:seon.eval/n-ok b1)) (pr-str b1)))
                      (testing "the tee stored the reified require edges (aliases as DATOMS)"
                        (let [edges (seval/stored-require-edges @conn (keyword uniq))]
                          (is (contains? edges {:seon.ns.require/target :seon.db
                                                :seon.ns.require/alias  'pdb})
                              (str "seon.db edge with alias — got " (pr-str edges)))
                          (is (contains? edges {:seon.ns.require/target :clojure.string
                                                :seon.ns.require/alias  'pstr}))))
                      (batch (str "(defn watcher [m]"
                                  " [(:seon.agent/purpose m) :probe.teeedge.data/metric])"))))
                  (.then
                    (fn [b2]
                      (testing "the defn evals ok"
                        (is (= 1 (:seon.eval/n-ok b2)) (pr-str b2)))
                      (testing "the fn row carries its declared read-set"
                        (is (= #{:seon.agent/purpose :probe.teeedge.data/metric}
                               (read-attrs))))
                      ;; REDEF dropping one literal — the stale keyword must
                      ;; be RETRACTED (diff, not accumulate).
                      (batch (str "(defn watcher [m] [(:seon.agent/purpose m) :redef])"))))
                  (.then
                    (fn [b3]
                      (testing "the redef evals ok"
                        (is (= 1 (:seon.eval/n-ok b3)) (pr-str b3)))
                      (testing "the read-set tracks the redef exactly (stale literal retracted)"
                        (is (= #{:seon.agent/purpose} (read-attrs))))))
                  (.finally (fn [] (set! db/*conn* prev)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; C24 — the body-only-redef rescue, GENERALIZED. var-digest covers only the
;; load-bearing META, so a redefinition changing ONLY the body is
;; digest-invisible to defs-since. The flagship rescue (c5d6f985) covered a
;; lone `(defn …)` source; a body-only redef via `(def f (fn …))` or inside a
;; multi-form source still reported NOTHING — silently dropping the
;; re-instrument (the redef replaced the wrapped var with a fresh unwrapped
;; fn) and the auto-test pass. A body-sensitive var-digest is not available
;; as the root (the analyzer var-map carries no body; snapshot-defs has no
;; source in scope), so the rescue is the mechanism: EVERY sym the source's
;; top-level def/defn/defn- forms define that produced no diff row is
;; synthesized from the live analyzer state.
;; ---------------------------------------------------------------------------

(deftest body-only-redef-rescue-covers-def-fn-and-multi-form
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (let [uniq  (str "probe.c24rescue" (rand-int 1000000000))
                  nssym (symbol uniq)
                  ev    (fn [src] (seval/eval cs src
                                              {:seon.eval/starting-ns nssym
                                               :seon.eval/analyze-deps? false}))
                  cd    (fn [before src]
                          (mapv :seon.analyzer-info/sym
                                ((deref #'seval/changed-defs) cs before src nssym)))]
              (-> (seval/eval cs (str "(ns " uniq ")")
                              {:seon.eval/starting-ns 'cljs.user
                               :seon.eval/analyze-deps? false})
                  ;; VERSION-A of all three shapes — these land in :defs.
                  (.then (fn [_] (ev "(def dfn (fn [x] (+ x 1)))")))
                  (.then (fn [_] (ev "(defn ma [x] (+ x 1)) (defn mb [x] (+ x 2))")))
                  (.then
                    (fn [_]
                      (let [before (analyzer-info/snapshot-defs cs)
                            srcA   "(def dfn (fn [x] (* x 10)))"
                            srcB   "(defn ma [x] (* x 100)) (defn mb [x] (* x 200))"]
                        ;; BODY-ONLY redefs: same names, same arglists, same
                        ;; (absent) meta — digest-identical, defs-since = ().
                        (-> (ev srcA)
                            (.then
                              (fn [_]
                                (testing "a (def f (fn …)) body-only redef is rescued"
                                  (is (= '[dfn] (cd before srcA))))
                                (ev srcB)))
                            (.then
                              (fn [_]
                                (testing "EVERY defn in a multi-form body-only redef is rescued"
                                  (is (= '[ma mb] (cd before srcB))))
                                (testing "a sym the analyzer never registered synthesizes nothing"
                                  (is (= [] (cd before "(defn ghost-c24 [x] x)"))))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; C37 — the `::`-keyword read-gate flywheel gap. cljs.tools.reader has NO
;; current-ns hook (a bare `::kw` is 'Invalid token' on every CLJS build;
;; `::alias/kw` needs *alias-map*), so a defn whose source used auto-resolved
;; keywords failed read-all-forms → defn-form? FALSE → NO :seon.fn row → the
;; fn was silently exempt from persist/auto-run/instrument/resume. The read
;; now rides seon.repl.internal/read-forms (rewrite-clj :auto-resolve): the
;; gate passes, and the tee resolves the keywords against the ending ns's
;; analyzer require-edges for the stored read-set.
;; ---------------------------------------------------------------------------

(deftest auto-resolved-keywords-pass-the-tee-gate
  (testing "defn-form? TRUE for a defn using ::kw / ::alias/kw (was FALSE)"
    (is (true? (seval/defn-form? "(defn f [m] (::purpose m))")))
    (is (true? (seval/defn-form? "(defn g [m] (::pdb/tx-data m))"))))
  (testing "read-all-forms reads ::-keyword sources (placeholder resolution)"
    (is (= 2 (count (#'seval/read-all-forms
                      "(defn f [] ::a) (defn g [] ::x/b)")))))
  (testing "the strict gates still hold on ::-keyword sources"
    (is (false? (seval/defn-form? "(def y ::a)")))
    (is (false? (seval/defn-form? "(defn f [] ::a) (defn g [] ::b)")))))

(deftest auto-resolved-keyword-defn-tees-fn-row-with-resolved-read-attrs
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs    (aget res 0)
                  conn  (aget res 1)
                  prev  db/*conn*
                  uniq  (str "probe.teeautokw" (rand-int 1000000000))
                  fq    (str uniq "/kw-user")
                  batch (fn [src] (seval/eval-batch! cs (repl-internal/parse-forms src)
                                                     (symbol uniq) "tee-autokw-test"
                                                     (db/new-id!) nil))
                  fn-row (fn [] (db/pull @conn [:seon.fn/sym :seon.fn/source
                                                :seon.fn/read-attrs]
                                         [:seon.fn/sym fq]))]
              (set! db/*conn* conn)
              (-> (batch (str "(ns " uniq " (:require [seon.db :as pdb]))"))
                  (.then
                    (fn [b1]
                      (testing "the ns form evals ok"
                        (is (= 1 (:seon.eval/n-ok b1)) (pr-str b1)))
                      ;; The C37 shape: bare `::kw` AND aliased `::pdb/kw`.
                      (batch (str "(defn kw-user [m]"
                                  " [(::purpose m) (::pdb/tx-data m)])"))))
                  (.then
                    (fn [b2]
                      (testing "the ::-keyword defn evals ok"
                        (is (= 1 (:seon.eval/n-ok b2)) (pr-str b2)))
                      (testing "the :seon.fn row EXISTS (the flywheel gap: absent before)"
                        (is (= fq (:seon.fn/sym (fn-row)))))
                      (testing "and its read-set carries the RESOLVED keywords"
                        (is (= #{(keyword uniq "purpose") :seon.db/tx-data}
                               (set (:seon.fn/read-attrs (fn-row))))))))
                  (.finally (fn [] (set! db/*conn* prev)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
