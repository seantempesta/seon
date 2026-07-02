(ns seon.server.reactive-test
  "M1 engine tests. The harness `with-engine` is the same setup we use live in the
  REPL: a fresh :memory conn + the engine wired as a ::reactive listener with a
  capturing emit!. Tests hunt the four failure modes (over-match, under-match,
  spurious emit, missed emit) via a brute-force `emit-iff-result-changed` oracle."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [malli.core :as m]
            [seon.server.reactive :as reactive]))

(defn with-engine
  "Fresh :memory conn + engine wired as a ::reactive listener with a capturing
  emit. Returns {:conn :emitted (atom []) :state}. Identical to the REPL setup."
  []
  (let [cfg {:store {:backend :memory :id (java.util.UUID/randomUUID)}
             :schema-flexibility :read :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        emitted (atom [])
        state (reactive/new-engine-state "test-db")]
    (dc/listen! conn ::reactive
                (fn [report]
                  (reactive/on-tx! {:db-name "test-db" :conn conn :state state
                                    :emit! (fn [ev] (swap! emitted conj ev))}
                                   report)))
    {:conn conn :emitted emitted :state state}))

(def units-query
  '[:find ?n ?p :where [?e :unit/name ?n] [?e :unit/pos ?p]])

(deftest pattern-extraction
  (is (= [['_ :unit/name '_] ['_ :unit/pos '_]]
         (reactive/query->patterns units-query)))
  (testing "literals in e/v positions are kept"
    (is (= [['_ :unit/pos "river"]]
           (reactive/query->patterns '[:find ?e :where [?e :unit/pos "river"]]))))
  (testing "reads from a source string too (code-as-data)"
    (is (= [['_ :unit/name '_]]
           (reactive/query->patterns "[:find ?n :where [?e :unit/name ?n]]")))))

(deftest two-gate-dispatch
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}
                      {:db/id -2 :unit/name "B" :unit/pos "y"}])
    (reactive/register-sub! state conn "s1" units-query)
    (let [run (fn [tx] (reset! emitted []) (d/transact conn tx) (count @emitted))]
      (testing "relevant + result changes → emit"
        (is (= 1 (run [{:db/id 1 :unit/pos "z"}]))))
      (testing "relevant attr but same value → no emit (change gate)"
        (is (= 0 (run [{:db/id 1 :unit/pos "z"}]))))
      (testing "irrelevant attr → no emit (cheap gate, no query run)"
        (is (= 0 (run [{:db/id 1 :supply/ammo 9}]))))
      (testing "new matching entity → emit"
        (is (= 1 (run [{:db/id -9 :unit/name "C" :unit/pos "w"}])))))))

(deftest emit-iff-result-changed
  ;; THE property (falsification): for ANY tx, the engine emits iff the query
  ;; result actually moved. Oracle = brute-force (d/q before) vs (d/q after).
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn (for [i (range 5)]
                       {:db/id (- (inc i)) :unit/name (str "U" i) :unit/pos (str "p" i)}))
    (reactive/register-sub! state conn "s" units-query)
    (doseq [tx [[{:db/id 1 :unit/pos "moved"}]                       ; change
                [{:db/id 1 :unit/pos "moved"}]                       ; same value
                [{:db/id 1 :supply/ammo 3}]                          ; irrelevant
                [{:db/id -99 :unit/name "New" :unit/pos "q"}]        ; add
                [[:db/retractEntity 2]]                              ; retract a unit
                [{:db/id 3 :morale 7}]]]                             ; irrelevant
      (let [before (d/q units-query (d/db conn))]
        (reset! emitted [])
        (d/transact conn tx)
        (let [after (d/q units-query (d/db conn))
              should-emit? (not= before after)]
          (is (= should-emit? (boolean (seq @emitted)))
              (str "tx=" (pr-str tx) " before=" before " after=" after)))))))

(deftest per-conn-isolation
  (let [a (with-engine) b (with-engine)]
    (d/transact (:conn a) [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reactive/register-sub! (:state a) (:conn a) "sa" units-query)
    (reset! (:emitted a) [])
    (reset! (:emitted b) [])
    (d/transact (:conn a) [{:db/id 1 :unit/pos "y"}])
    (is (= 1 (count @(:emitted a))) "engine A fires")
    (is (= 0 (count @(:emitted b))) "engine B untouched — per-conn isolation")))

(deftest persistence-and-register-after-tx
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reset! emitted [])
    (reactive/register-subscription! state conn "s1" units-query)
    (testing "the subscription is persisted as a durable datom (query as string)"
      (is (= #{["s1" (pr-str units-query)]}
             (d/q '[:find ?id ?q
                    :where [?s :seon.subscription/id ?id]
                           [?s :seon.subscription/query ?q]]
                  (d/db conn)))))
    (testing "the registration tx did NOT self-route (register-after-transact)"
      (is (= 0 (count @emitted))))
    (testing "the sub is live afterward"
      (reset! emitted [])
      (d/transact conn [{:db/id 1 :unit/pos "y"}])
      (is (= 1 (count @emitted))))))

(deftest cache-rebuild-from-datoms
  (let [{:keys [conn state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reactive/register-subscription! state conn "s1" units-query)
    (reactive/register-subscription! state conn "s2" '[:find ?n :where [?e :unit/name ?n]])
    (testing "a fresh (empty) engine rebuilds its cache from the active sub datoms"
      (let [fresh (reactive/new-engine-state "test-db")]
        (is (= 0 (count (:subs @fresh))) "fresh cache starts empty")
        (is (= 2 (reactive/rebuild! fresh conn)) "rebuilds 2 subs from datoms")
        (is (= #{"s1" "s2"} (set (keys (:subs @fresh)))))
        (testing "rebuilt cache (patterns + last-result) is identical to the live cache"
          (is (= (:subs @state) (:subs @fresh))))))))

(deftest inverted-index-narrows-candidates
  (let [{:keys [conn state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    ;; 20 subs on disjoint "noise" attributes + one on units
    (doseq [k (range 20)]
      (reactive/register-sub! state conn (str "noise-" k)
                              [:find '?e :where ['?e (keyword "noise" (str k)) '?v]]))
    (reactive/register-sub! state conn "units" units-query)
    (testing "a tx touching only :unit/pos yields ONLY the units sub as candidate"
      (let [r      (d/transact conn [{:db/id 1 :unit/pos "y"}])
            datoms (#'reactive/report->datoms r)
            cands  (#'reactive/candidate-subs (:index @state) datoms)]
        (is (= #{"units"} (set cands))
            "the 20 noise subs are excluded by the index, never scanned")))))

(deftest emit-conforms-to-registered-event-schema
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reactive/register-sub! state conn "s1" units-query)
    (reset! emitted [])
    (d/transact conn [{:db/id 1 :unit/pos "y"}])
    (let [ev (first @emitted)]
      (is (some? ev) "an event was emitted")
      (is (m/validate :seon.server.reactive/changed-summaries-event ev)
          (str "explain: "
               (pr-str (m/explain :seon.server.reactive/changed-summaries-event ev))))
      (testing "the changed-entry has exactly the canonical keys (rows is a vector, not a set)"
        (is (= #{:seon.subscription/id :seon.server.reactive/rows}
               (set (keys (first (:seon.server.reactive/changed ev))))))
        (is (vector? (:seon.server.reactive/rows (first (:seon.server.reactive/changed ev)))))))))

(deftest request-id-rides-the-event
  ;; R1 reactive-side: the engine surfaces the tx's request-id on the
  ;; changed-summaries event so a guest can dedup its own writeback (review issue 1).
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reactive/register-sub! state conn "s1" units-query)
    (reset! emitted [])
    (d/transact conn {:tx-data [{:db/id 1 :unit/pos "y"}]
                      :tx-meta {:seon.store.wire/write-id "r-123"}})
    (is (= "r-123" (:seon.server.reactive/request-id (first @emitted)))
        "on-tx! surfaces the tx's :seon.store.wire/write-id on the event")))

(deftest register-subscription-handler
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reset! emitted [])
    (let [req  {:seon.server.reactive/sub-id "s1"
                :seon.server.reactive/query (pr-str units-query)}
          resp (reactive/register-subscription state conn req)]
      (testing "request + response validate against the registered schemas"
        (is (m/validate :seon.server.reactive/register-subscription-request req))
        (is (m/validate :seon.server.reactive/register-subscription-response resp)))
      (testing "response carries the id + initial rows (as a vector)"
        (is (= "s1" (:seon.subscription/id resp)))
        (is (vector? (:seon.server.reactive/rows resp))))
      (testing "persisted + live + the registration tx did not self-route"
        (is (= 0 (count @emitted)))
        (d/transact conn [{:db/id 1 :unit/pos "y"}])
        (is (= 1 (count @emitted)))))))

(deftest unregister-subscription-handler
  (let [{:keys [conn emitted state]} (with-engine)]
    (d/transact conn [{:db/id -1 :unit/name "A" :unit/pos "x"}])
    (reactive/register-subscription state conn
                                    {:seon.server.reactive/sub-id "s1"
                                     :seon.server.reactive/query (pr-str units-query)})
    (let [resp (reactive/unregister-subscription state conn
                                                 {:seon.server.reactive/sub-id "s1"})]
      (is (m/validate :seon.server.reactive/unregister-subscription-response resp))
      (is (false? (:seon.subscription/active? resp)))
      (testing "no longer wakes after unregister"
        (reset! emitted [])
        (d/transact conn [{:db/id 1 :unit/pos "y"}])
        (is (= 0 (count @emitted)))))))
