(ns seon.html-views-test
  "User-facing render pairs, on the canonical database with frozen AI bytes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.agent :as agent]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.error :as error]
            [seon.id :as id]
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

(deftest help-preserves-lines-and-ai
  (support/with-database
    (fn [_]
      (let [unit {:seon.help/groups [{:seon.help/title "Reading"
                                      :seon.help/lines ["Inspect (dir my.task) before acting." "Then (doc seon.db/q)."]}]}
            html (bootstrap/render-help-html unit)]
        (readable! html ["Reading" "<code>(dir my.task)</code>" "<code>(doc seon.db/q)</code>"])
        (is (= (:seon.help/groups unit) (bootstrap/render-help-ai unit)))))))

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
      (let [report (db/transact! connection [(support/task-row "prepare00000" "Prepare")])
            ;; The writer's own refusal: a task without its required intent.
            refusal (db/transact! connection [(dissoc (support/task-row "refusedtask0" "x") :seon.task/intent)])
            ;; A real report whose only datom is its transaction's own: the
            ;; declared report shape holds database values, never identity maps.
            shown-report (db/transact! connection [])]
        (readable! (db/render-transaction-html report) ["Committed transaction" "Prepare" "Changes"])
        (readable! (db/render-rejection-html refusal)
                   ["seon.db/transact!" "Supply :seon.task/intent with a string of length at least 1."])
        (is (= {:seon.db.report/facts 0 :seon.db.report/entities []}
               (select-keys (db/render-transaction-ai shown-report) [:seon.db.report/facts :seon.db.report/entities]))
            "a report is data: its facts counted and its changed entities by identity")
        (is (str/includes? (db/render-rejection-ai refusal)
                           "Supply :seon.task/intent with a string of length at least 1."))))))

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
                                     :seon.config.run/max-episode-runs 1000}
                                    connection "alice"))))
      (let [html (hiccup/->string
                  (agent/render-settings-html {:seon.db/db @connection :seon.agent/id "alice"}))]
        (is (str/includes? html "1.5 s"))
        (is (str/includes? html "1,000"))
        (is (not (str/includes? html "1,000 s")))))))

(deftest the-settings-render-shows-the-overrides-it-was-handed
  (let [written {:seon.config.run/max-episode-runs 10}
        unit {:seon.render/value written :seon.agent/id "settings-render-proof" :seon.db/db (db/db)}]
    (is (= written (:my.agent/settings (agent/render-settings-ai unit))))))
