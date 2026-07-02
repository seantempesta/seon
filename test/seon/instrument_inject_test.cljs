(ns seon.instrument-inject-test
  "The explicit-dependency INJECTION contract on the one instrumentation
   wrapper (`seon.instrument/injecting-fschema`): a map-in fn declares an
   injectable (`:seon.db/db`, `:seon.agent/id`) as an OPTIONAL request key,
   and the eval boundary fills every DECLARED-BUT-ABSENT one from the eval
   context — explicit caller args always win, a nil provider leaves the key
   absent, a fn declaring none is untouched.

   Design: `docs/prds/agent-fsm/research/explicit-deps-injection-2026-07-02.md`
   + `docs/seon/architecture/context.md` §\"Explicit dependencies\".

   The probe fns live in THIS ns; they route through the SAME
   `register-target!` → `mi/instrument!` path the pod boots with. Teardown
   unstruments them so no wrapper leaks. A fresh `:memory` conn is the db the
   `:seon.db/db` provider reads via `db/*conn*`; `db/with-agent` supplies the
   `:seon.agent/id` provider's value."
  (:require
    [cljs.test :refer [deftest is async use-fixtures]]
    [datahike.api :as d]
    [malli.core :as m]
    [malli.instrument :as mi]
    [seon.db :as db]
    [seon.instrument :as inst]))

;; ── probe fns — one declaring both injectables, one declaring none ──────────
;; Both are simple single-fixed-arity `:=>` map-in fns, so register-target!
;; routes each through injecting-fschema. Their bodies REPORT what landed in
;; the request map so a test can assert the injection outcome directly.

(defn probe-injects
  "Returns what the wrapper handed the body: whether db was present, the id
   value (nil = absent), and the passthrough x. Declares db+id OPTIONAL."
  {:malli/schema [:=> [:cat [:map
                             [:seon.db/db    {:optional true} :seon.db/db]
                             [:seon.agent/id {:optional true} :seon.agent/id]
                             [:probe/x :int]]]
                  [:map [:got-db :boolean] [:got-id [:maybe :string]] [:x :int]]]}
  [{:seon.db/keys [db] id :seon.agent/id x :probe/x}]
  {:got-db (some? db) :got-id id :x x})

(defn probe-no-inject
  "A map-in fn declaring NO injectable — must be handed the request map
   verbatim (no db/id keys added)."
  {:malli/schema [:=> [:cat [:map [:probe/x :int]]]
                  [:map [:key-count :int] [:x :int]]]}
  [{x :probe/x :as req}]
  {:key-count (count req) :x x})

(def ^:private target-set '#{[seon.instrument-inject-test probe-injects]
                             [seon.instrument-inject-test probe-no-inject]})

(defn- instrument-probes! []
  (inst/register-target! 'seon.instrument-inject-test 'probe-injects
                         (:malli/schema (meta #'probe-injects)) false)
  (inst/register-target! 'seon.instrument-inject-test 'probe-no-inject
                         (:malli/schema (meta #'probe-no-inject)) false)
  (mi/instrument! {:filters [(fn [n s _] (contains? target-set [n s]))]}))

(defn- uninstrument-probes! []
  (mi/unstrument! {:filters [(fn [n s _] (contains? target-set [n s]))]}))

(use-fixtures :once {:before instrument-probes! :after uninstrument-probes!})

(defn ^:async ^:private fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? true}]
    (await (d/create-database cfg))
    (await (d/connect cfg {:sync? false}))))

(def ^:private valid-id "INJECTtest0001") ; matches :seon.agent/id shape (14, dash-at-3? see below)

(deftest injects-declared-absent-deps
  (async done
    (let [orig db/*conn*]
      (-> (fresh-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   ;; declared + absent → filled from eval-ctx
                   (let [r (db/with-agent valid-id
                             (fn [] (probe-injects {:probe/x 1})))]
                     (is (true? (:got-db r)) "db injected from *conn*")
                     (is (= valid-id (:got-id r)) "id injected from with-agent scope")
                     (is (= 1 (:x r)) "passthrough arg preserved"))))
          (.catch (fn [e] (is false (str "threw: " (ex-message e)))))
          (.finally (fn [] (set! db/*conn* orig) (done)))))))

(deftest explicit-args-win
  (async done
    (let [orig db/*conn*]
      (-> (fresh-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   ;; caller supplies id → the scope's id must NOT overwrite it
                   (let [r (db/with-agent valid-id
                             (fn [] (probe-injects {:probe/x 2
                                                    :seon.agent/id "OTHERagent0002"})))]
                     (is (= "OTHERagent0002" (:got-id r))
                         "explicit id kept, not overwritten by the scope id")
                     (is (true? (:got-db r)) "db still injected (absent)"))))
          (.catch (fn [e] (is false (str "threw: " (ex-message e)))))
          (.finally (fn [] (set! db/*conn* orig) (done)))))))

(deftest nil-provider-leaves-key-absent
  (async done
    (let [orig db/*conn*]
      (-> (fresh-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   ;; OUTSIDE with-agent → current-agent-id nil → id key absent
                   ;; (optional, so input still validates; never store nil)
                   (let [r (probe-injects {:probe/x 3})]
                     (is (nil? (:got-id r)) "nil id provider leaves the key absent")
                     (is (true? (:got-db r)) "db provider non-nil → injected"))))
          (.catch (fn [e] (is false (str "threw: " (ex-message e)))))
          (.finally (fn [] (set! db/*conn* orig) (done)))))))

(deftest no-injectable-fn-untouched
  (async done
    (let [orig db/*conn*]
      (-> (fresh-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   ;; declares no injectable → request map handed through verbatim
                   (let [r (db/with-agent valid-id
                             (fn [] (probe-no-inject {:probe/x 9})))]
                     (is (= 1 (:key-count r)) "no db/id keys added to the request")
                     (is (= 9 (:x r))))))
          (.catch (fn [e] (is false (str "threw: " (ex-message e)))))
          (.finally (fn [] (set! db/*conn* orig) (done)))))))

(deftest declared-injectables-reads-keys
  ;; The static side: declared∩registry from a fn's :=> schema — inline maps,
  ;; registered refs, non-map args, and no-injectable maps.
  (is (= #{:seon.db/db :seon.agent/id}
         (inst/declared-injectables
           (m/schema [:=> [:cat [:map
                                 [:seon.db/db {:optional true} :seon.db/db]
                                 [:seon.agent/id {:optional true} :seon.agent/id]
                                 [:probe/x :int]]] :string]))))
  (is (= #{} (inst/declared-injectables
               (m/schema [:=> [:cat [:map [:probe/x :int]]] :string])))
      "a map with no injectable key declares none")
  (is (= #{} (inst/declared-injectables
               (m/schema [:=> [:cat :int] :string])))
      "a non-map first arg declares none"))
