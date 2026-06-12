(ns seon.web.inspector-chips-test
  "The /agents header chips contract (task #32 — user-meaningful
   counts only): the default header shows AGENTS · TURNS · FACTS,
   where FACTS is the /data browser's default row count (post-bootstrap
   rows via the shared `seon.db/bootstrap-row-ids` provenance — the
   chip and /data can never disagree) and links to /data; machinery
   counts (datoms/fns/schemas/tests) appear ONLY under the `?system=1`
   toggle (same param as /data); zero-count chips are hidden in the
   default view. All on a boot-seeded scratch `:memory` world, never
   the live conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.ui.html :as html]
    ;; required explicitly: tests deref private fns by var-quote
    ;; (#'seon.web.inspector/cluster-stats etc.)
    [seon.web.inspector]))

(defn ^:async with-world
  "Boot-seeded scratch world around `body` (fn [conn] → Promise) —
   same harness as seon.agent.findings-test: real `:substrate-seed`
   tx provenance, root conn + schema registry restored after."
  [body]
  (let [prev-conn   db/*conn*
        keys-before (schema/current-keys)]
    (try
      (let [conn (await (client/open-agent-conn!))]
        (set! db/*conn* conn)
        (await (client/boot-seed! {:seon.db/conn conn}))
        (await (body conn)))
      (finally
        (set! db/*conn* prev-conn)
        (let [minted (remove keys-before (schema/current-keys))]
          (when (seq minted)
            (swap! schema/*schemas #(apply dissoc % minted))))))))

(defn ^:async seed-facts!
  "Register a scratch user kind + transact `rows` under a minted agent
   id — post-bootstrap agent provenance, like rows a live agent stores."
  [rows]
  (await
    (db/with-agent (db/new-id!)
      (fn ^:async seed-rows! []
        (schema/register! :my.acme.fact/claim :string)
        (let [{ok? :seon.db/ok? :as env}
              (await (db/transact! {:seon.db/tx-data rows}))]
          (when-not ok?
            (throw (ex-info "inspector-chips-test: seed failed" env))))))))

(defn- stats [db] ((var seon.web.inspector/cluster-stats) db))

(defn- dash-html
  "The #agents-dash fragment as an HTML string, for the given view
   (`system?` = the ?system=1 machinery toggle, `completed?` = the
   ?completed=1 completed-agents toggle)."
  ([system?] (dash-html system? false))
  ([system? completed?]
   (html/->string ((var seon.web.inspector/agents-dash-fragment)
                   system? completed?))))

(defn- chip?
  "True iff the rendered strip contains a chip labeled `label` — the
   label renders as the cell's own <span>label</span> text node."
  [html-str label]
  (str/includes? html-str (str ">" label "<")))

(deftest fresh-world-bootstrap-rows-excluded-and-zero-chips-hide
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [{:seon.web.inspector/keys [fact-count fn-count datom-count]}
                  (stats @conn)
                  s (dash-html false)]
              ;; ZERO, exactly: every boot-seed transact — including
              ;; the :wake/on-message handler registration row (demo-
              ;; polish fix 2026-06-12: it used to land at conn-open,
              ;; BEFORE the :substrate-seed tx-context) — carries seed
              ;; provenance. Handler registration rows are machinery,
              ;; never the cluster's "data": a fresh world has FACTS=0.
              (is (zero? fact-count)
                  "fresh world → FACTS=0 — every boot-seed row
                   (handler registration included) is bootstrap-origin")
              (is (pos? fn-count)
                  "the machinery counts DO see the boot index")
              (is (> datom-count fact-count)
                  "FACTS is rows, never raw datoms")
              (is (= (pos? fact-count) (chip? s "facts"))
                  "facts chip shows iff its count is positive")
              (is (not (chip? s "agents")) "zero agents → no chip")
              (is (not (chip? s "turns"))  "zero turns → no chip"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest default-header-is-user-meaningful-no-machinery
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (await (seed-facts! [{:my.acme.fact/claim "water is wet"}
                                 {:my.acme.fact/claim "fire is hot"}]))
            (let [s (dash-html false)]
              (is (chip? s "facts") "stored rows → FACTS chip appears")
              (is (str/includes? s "href=\"/data\"")
                  "FACTS chip links to /data — drill-down one click away")
              (doseq [label ["datoms" "fns" "schemas" "tests" "findings"]]
                (is (not (chip? s label))
                    (str label " is machinery (or retired) — never in the
                          default header"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest fact-count-tracks-the-data-browser-rows-live
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [before (:seon.web.inspector/fact-count (stats @conn))]
              (await (seed-facts! [{:my.acme.fact/claim "water is wet"}
                                   {:my.acme.fact/claim "fire is hot"}]))
              (let [after (:seon.web.inspector/fact-count (stats @conn))
                    eid   (ffirst (db/query
                                    {:seon.db/db @conn
                                     :seon.db/query
                                     '[:find ?e :where
                                       [?e :my.acme.fact/claim "fire is hot"]]}))]
                ;; >= not =: the first transact of a NEW attr also tees
                ;; lazy-install rows (:seon.schema/:seon.ns) — post-
                ;; bootstrap rows that /data shows too, so FACTS counts
                ;; them as well. The chip mirrors /data, not "+2".
                (is (>= after (+ before 2))
                    "two stored rows → FACTS grows by at least 2
                     (distinct post-bootstrap rows, the /data default
                     derivation — lazy-install tee rows count too)")
                (let [{ok? :seon.db/ok?}
                      (await (db/transact!
                               {:seon.db/tx-data [[:db/retractEntity eid]]}))]
                  (is ok? "retract tx lands"))
                (is (= (dec after)
                       (:seon.web.inspector/fact-count (stats @conn)))
                    "retracting one row decrements by exactly one —
                     derived, nothing stored, nothing to clear")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest completed-agents-hidden-by-default-toggle-reveals
  ;; Task #10 demo half (demo-polish 2026-06-12): completed agents are
  ;; HISTORY — the default grid hides them entirely; the `?completed=1`
  ;; query-param toggle (same pattern as ?system) reveals them. Active
  ;; cards carry the ✓ complete POST affordance.
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [{ok? :seon.db/ok?}
                  (await (db/transact!
                           {:seon.db/tx-data
                            [{:seon.agent/id "chips-active-1"
                              :seon.agent/state :idle}
                             {:seon.agent/id "chips-done-001"
                              :seon.agent/state :idle}]}))]
              (is ok? "two agent rows land"))
            (let [{done? :seon.agent/ok?}
                  (await (agent/complete!
                           {:seon.agent/id "chips-done-001"}))]
              (is done? "complete! stamps :seon.agent/completed-at"))
            (let [hidden (dash-html false false)
                  shown  (dash-html false true)]
              (is (str/includes? hidden "/agent/chips-active-1\"")
                  "active agent's card is in the default grid")
              (is (not (str/includes? hidden "/agent/chips-done-001\""))
                  "completed agent's card is HIDDEN by default")
              (is (str/includes? hidden "show completed (1)")
                  "default view offers the show-completed toggle")
              (is (str/includes? hidden "/agent/chips-active-1/complete")
                  "active card carries the ✓ complete POST affordance")
              (is (str/includes? shown "/agent/chips-done-001\"")
                  "?completed=1 reveals the completed agent's card")
              (is (not (str/includes? shown
                                      "/agent/chips-done-001/complete"))
                  "completed cards never offer ✓ again"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest system-toggle-reveals-the-machinery-row
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [s (dash-html true)]
              (doseq [label ["datoms" "fns" "schemas" "tests"]]
                (is (chip? s label)
                    (str "?system=1 reveals the " label " count")))
              (is (str/includes? s "system counts shown")
                  "toggle link reads as ON in the system view"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
