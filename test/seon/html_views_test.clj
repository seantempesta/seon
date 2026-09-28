(ns seon.html-views-test
  "User-facing render pairs, on the canonical database with frozen AI bytes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.plan :as plan]
            [seon.agent :as agent]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.error :as error]
            [seon.id :as id]
            [seon.maintenance :as maintenance]
            [seon.note :as note]
            [seon.render.ns :as render.ns]
            [seon.render.hiccup :as hiccup]
            [seon.test-support :as support]))

(defn- golden [key]
  (get (edn/read-string (slurp (io/resource "seon/fixtures/html_views_ai.edn"))) key))

(defn- readable! [html texts]
  (let [printed (hiccup/->string html)]
    (when-let [directory (System/getenv "SEON_HTML_OUTPUT")]
      (let [file (io/file directory (str (id/id texts) ".html"))]
        (io/make-parents file)
        (spit file printed)))
    (is (seq printed) "The renderer must produce a visible value.")
    (doseq [text texts] (is (str/includes? printed text)))
    (doseq [literal ["#inst" ":db/id" "[:seon.agent/id"]]
      (is (not (str/includes? printed literal))))))

(deftest plan-pairs-are-readable-and-ai-is-data
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "alice"))
      (plan/plan! {:my.plan/objective "Ship a clear plan"
                   :my.plan/steps [{:my.plan.item/id "first" :my.plan.item/title "Prepare"}
                                   {:my.plan.item/id "second" :my.plan.item/title "Ship"
                                    :my.plan.item/done-when "The page reads clearly"
                                    ;; needs are prerequisite ids, not refs (71c6d1fb6)
                                    :my.plan.item/needs #{"first"}}]}
                  @connection connection "alice")
      (let [unit {:seon.agent/id "alice" :seon.db/db @connection}
            step (plan/item {:my.plan.item/id "first" :seon.db/db @connection})]
        (readable! (plan/render-plan-html unit) ["Ship a clear plan" "Blocked on: " "Prepare" "pending"])
        (readable! (plan/render-item-html step) ["Prepare" "pending"])
        (readable! (plan/render-ready-items-html [step]) ["Ready work (1)" "Prepare"])
        (is (= {:seon.agent/id "alice" :my.plan/objective "Ship a clear plan"
                :seon.plan/step-lines {"first" "1. ready — Prepare" "second" "2. blocked — Ship"}
                :seon.render/operations ['(my.plan/current! {:my.plan.item/id "first"})]}
               (plan/render-plan-ai unit)))
        (is (= (golden :item) (plan/render-item-ai step)))
        (is (= [{:my.plan.item/id "first" :my.plan.item/title "Prepare"}] (plan/render-ready-items-ai [step])))
      (plan/complete! "first" connection "alice")
      (plan/start! "second" connection "alice")
      (readable! (plan/render-plan-html {:seon.agent/id "alice" :seon.db/db @connection})
                 ["done" "current" "datetime=" "The page reads clearly"])))))

(deftest settings-pair-omits-absences-and-ai-is-data
  (support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "html-views"})
      (support/transacted! connection (support/agent-tx @connection "alice"))
      ;; An override is written by the agent's own writer onto the settings
      ;; component its creation made; unset dials are listed, not omitted (ed3f44225).
      (is (= 10000 (:seon.config.eval/time-limit-ms
                    (agent/settings! {:seon.config.eval/time-limit-ms 10000} connection "alice"))))
      (let [unit {:seon.db/db @connection :seon.agent/id "alice"}
            html (agent/render-settings-html unit)
            printed (hiccup/->string html)]
        (readable! html ["override" "10 s" "defaults (" "Unset settings ("])
        (is (= 1 (count (re-seq #"<table" printed))))
        (is (not (str/includes? printed "Not set")))
        (is (not (str/includes? printed "settings!")))
        (is (= {:my.agent/settings (agent/settings @connection "alice")
                :seon.render/operations ['(seon.agent/effective-settings (seon.db/db) "alice")]}
               (agent/render-settings-ai unit)))
        (is (= 10000 (get-in (agent/render-settings-ai unit) [:my.agent/settings :seon.config.eval/time-limit-ms])))))))

(deftest message-pairs-derive-unread-and-ai-is-data
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agents-tx @connection ["alice" "bob"]))
      (support/transacted! connection [{:seon.message/id "hello" :seon.message/content "Hello\nagain"
                                        :seon.message/from [:seon.agent/id "bob"]
                                        :seon.message/to [:seon.agent/id "alice"]}])
      (let [entity (db/pull @connection '[*] [:seon.message/id "hello"])
            unit (assoc entity :seon.db/db @connection)]
        (readable! (message/render-html unit) ["Hello\nagain" "unread" "alice" "bob" "title="])
        (readable! (message/render-inbox-html [entity] @connection) ["Messages (1)" "unread"])
        (is (= {:seon.message/id "hello" :seon.message/from "bob" :seon.message/to "alice"
                :seon.message/lines ["Hello" "again"] :seon.message/received? false}
               (dissoc (message/render-ai unit) :seon.message/at)))
        (is (= [['(my.message/received! "hello")]]
               (mapv :seon.render/operations
                     (:seon.message/unread (message/render-inbox-ai [entity] @connection)))))
        (message/received! "hello" connection "alice")
        (readable! (message/render-html (assoc (db/pull @connection '[*] [:seon.message/id "hello"])
                                              :seon.db/db @connection)) ["received"])))))

(deftest note-pairs-link-titles-and-ai-is-data
  (support/with-database
    (fn [connection]
      (support/transacted! connection (into (support/agent-tx @connection "alice")
                                        [{:my.plan.item/id "first" :my.plan.item/title "Prepare"}]))
      (support/transacted! connection [{:my.note/id "observation" :my.note/content "Verified"
                                        :my.note/agent [:seon.agent/id "alice"]
                                        :my.note/about [:my.plan.item/id "first"]}])
      (let [entity (assoc (db/pull @connection '[*] [:my.note/id "observation"]) :seon.db/db @connection)
            unit {:seon.agent/id "alice" :seon.db/db @connection}]
        (readable! (note/render-note-html entity) ["observation" "Verified" "Prepare" "datetime="])
        (readable! (note/render-notes-html unit) ["Current notes (1)" "Prepare"])
        (is (= {:my.note/id "observation" :my.note/content "Verified"}
               (note/render-note-ai {:my.note/id (:my.note/id entity)
                                     :my.note/content (:my.note/content entity)
                                     :my.note/agent [:seon.agent/id "alice"]})))
        (is (= [{:my.note/id "observation" :my.note/content "Verified" :my.note/about {:my.plan.item/id "first"}}]
               (note/render-notes-ai unit)))))))

(deftest help-preserves-lines-and-ai
  (support/with-database
    (fn [_]
      (let [unit {:seon.help/lines ["Inspect (dir my.plan) before acting." "Then (doc seon.db/q)."]}
            html (bootstrap/render-help-html unit)]
        (readable! html ["<code>(dir my.plan)</code>" "<code>(doc seon.db/q)</code>"])
        (is (= (golden :help) (bootstrap/render-help-ai unit)))))))

(deftest identity-pairs-preserve-ai
  (support/with-database
    (fn [connection]
      (support/transacted! connection (cluster.agent/creation-tx
                                      {:seon.agent/id "alice" :seon.ns/name 'my.agents.alice
                                       :seon.cluster/name "fixture"}))
      (let [unit {:seon.agent/id "alice" :seon.db/db @connection}
            creation {:seon.agent/id "alice" :seon.ns/name 'my.agents.alice
                      :seon.cluster/name "fixture" :seon.turn/id "opening"}]
        (readable! (cluster.agent/render-identity-html unit) ["Agent" "alice" "my.agents.alice" "Steward"])
        (readable! (cluster.agent/render-id-html "alice" @connection) ["alice" "Steward"])
        (readable! (cluster.agent/render-creation-html creation) ["alice" "Opening turn"])
        (is (= {:seon.agent/id "alice"
                :seon.agent/namespace {:seon.ns/name 'my.agents.alice :seon.ns/steward {:seon.agent/id "alice"}}
                :seon.render/operations ['(help)]}
               (cluster.agent/render-identity-ai unit)))
        (is (= (cluster.agent/render-identity-ai unit) (cluster.agent/render-id-ai "alice" @connection)))
        (is (= (golden :creation) (cluster.agent/render-creation-ai creation)))))))

(deftest fault-pairs-preserve-ai
  (support/with-database
    (fn [connection]
      (let [fault {:seon.error/operation 'example/connect :seon.error/message "Connection lost"}]
        ;; The kicker names the fault's operation; `:seon.error/kind` (once
        ;; `:example/failed`, shown as "failed") was retired in bc8152438.
        (readable! (error/render-html fault) ["example/connect" "Connection lost"])
        (readable! (error/render-faults-html [fault] @connection) ["Faults (1)" "Connection lost"])
        (is (= (golden :fault) (error/render-ai fault)))
        (is (= (golden :faults) (error/render-faults-ai {:seon.render/value [fault]})))
        ;; THE PAIR RENDERS THE FACT, NOT ITS EDN. The frozen bytes were
        ;; `(pr-str ...)` output until `render-ai` became prose over the
        ;; error's own data (`src/seon/error.clj:1558`); the golden kept
        ;; asserting the dump a value with NO declared pair would print.
        ;; These two assert the behaviour the bytes stand for, so neither
        ;; side can go stale silently again.
        (is (not (str/includes? (error/render-ai fault) "#:seon.error{"))
            "an AI pair renders the fact's data, never its attribute dump")
        (is (str/includes?
             (error/render-ai (assoc fault :seon.error/occurrence-count 3))
             "occurrence-count 3")
            "and it carries the recorded occurrence evidence")))))

(deftest maintenance-pair-preserves-ai
  (support/with-database
    (fn [connection]
      (let [report {:seon.maintenance/entries []}]
        (readable! (maintenance/render-report-html report @connection) ["No maintenance tasks are recorded."])
        ;; An entry's function is the qualified symbol the report reads from
        ;; its task entity (`:seon.fn/sym`, retyped from a string in 0e8f7d323).
        (readable! (maintenance/render-report-html {:seon.maintenance/entries
                     [{:seon.schedule.task/id "collect" :seon.fn/sym 'seon.maintenance/collect}]} @connection)
                   ["collect" "not run"])
        (is (= (golden :maintenance) (maintenance/render-report-ai report @connection)))))))

(deftest namespace-binding-pairs-show-the-binding-as-data
  (support/with-database
    (fn [_]
      (doseq [[value ai html expected]
              [[{:seon.ns.alias/local 'db :seon.ns.alias/target-ns 'seon.db}
                render.ns/render-alias-ai render.ns/render-alias-html "seon.db"]
               [{:seon.ns.refer/local 'q :seon.ns.refer/target-ns 'seon.db :seon.ns.refer/target-name 'q}
                render.ns/render-refer-ai render.ns/render-refer-html "seon.db/q"]
               [{:seon.ns.import/local 'Date :seon.ns.import/target-class 'java.util.Date}
                render.ns/render-import-ai render.ns/render-import-html "java.util.Date"]]]
        (readable! (html value) [expected])
        (is (= value (ai value)))))))

(deftest transactions-render-values-without-entity-id-dumps
  (support/with-database
    (fn [connection]
      (let [report (db/transact! connection [{:my.plan.item/id "first" :my.plan.item/title "Prepare"}])
            ;; The writer's own refusal: a step without its required title.
            refusal (db/transact! connection [{:my.plan.item/id "refused"}])
            ;; A real report whose only datom is its transaction's own: the
            ;; declared report shape holds database values, never identity maps.
            shown-report (db/transact! connection [])]
        (readable! (db/render-transaction-html report) ["Committed transaction" "Prepare" "Changes"])
        (readable! (db/render-rejection-html refusal)
                   ["seon.db/transact!" "Supply :my.plan.item/title with a string of length at least 1."])
        (is (= (golden :transaction) (db/render-transaction-ai shown-report)))
        (is (str/includes? (db/render-rejection-ai refusal)
                           "Supply :my.plan.item/title with a string of length at least 1."))))))

(deftest settings-display-follows-declarations-without-name-based-units
  ;; WANTED (ed3f44225): each dial declares its own label, divisor and unit,
  ;; and the settings view reads only those declarations from the database's
  ;; projection. A dial declaring a unit shows it; one declaring none shows
  ;; its bare value. The central display map this test once patched is gone.
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "alice"))
      (is (= 1500 (:seon.config.eval/time-limit-ms
                   (agent/settings! {:seon.config.eval/time-limit-ms 1500
                                     :seon.config.ai.retry/maximum-retries 1000}
                                    connection "alice"))))
      (let [html (hiccup/->string
                  (agent/render-settings-html {:seon.db/db @connection :seon.agent/id "alice"}))]
        (is (str/includes? html "1.5 s"))
        (is (str/includes? html "1,000"))
        (is (not (str/includes? html "1,000 s")))))))
