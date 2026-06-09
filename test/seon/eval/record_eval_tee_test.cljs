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
     upserts onto the existing one for substrate/`(ns …)` namespaces.
   - `record-eval!` on tx failure logs console.error (NOT warn) and
     RETRIES without the tee rows so the `:seon.eval` row always survives.

   All tests open a FRESH `:memory` datahike conn — nothing here touches
   the live agent conn.

   Run interactively via MCP eval:
     (require 'seon.eval.record-eval-tee-test :reload)
     (cljs.test/run-tests 'seon.eval.record-eval-tee-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent]                          ; :seon.eval/:seon.turn/:seon.ns/:seon.schema registrations
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]))

;; ---------------------------------------------------------------------------
;; Fresh :memory conn per test, with the SAME datahike schema the pod
;; installs at boot (db/malli->datahike-schema over agent-bootstrap-attrs).
;; The boot install is required: record-eval!'s eval-map rides NESTED under
;; :seon.turn/evals, and ensure-datahike-attrs!/extract-tx-attrs only see
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
  {:eval-id     eval-id
   :turn-id     turn-id
   :at          (js/Date.)
   :duration-ms 1
   :narration   ""
   :source      source
   :ns          'cljs.user
   :result      {:ok true :value :ok}
   :tee         tee})

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
;; exists (substrate ns, or a prior `(ns …)` eval) upserts onto it — no
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
