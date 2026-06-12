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
  "The #agents-dash fragment as an HTML string, for the given header
   view (`system?` = the ?system=1 machinery toggle)."
  [system?]
  (html/->string ((var seon.web.inspector/agents-dash-fragment) system?)))

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
              ;; Not zero: the wake-handler registration row lands at
              ;; conn-open, BEFORE boot-seed! stamps :substrate-seed —
              ;; one known pre-existing machinery row that /data's
              ;; default view also shows (reported as a smell, fix is
              ;; the boot path's, not the chip's). The chip mirrors
              ;; /data exactly — so assert the EXCLUSION did its job:
              ;; the boot index's thousands of rows don't count.
              (is (< fact-count 5)
                  "bootstrap-origin rows are excluded from FACTS —
                   only the conn-open stragglers remain")
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
