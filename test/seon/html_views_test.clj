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
    (doseq [text texts] (is (str/includes? printed text) text))
    (doseq [literal ["#inst" ":db/id" "[:seon.agent/id"]]
      (is (not (str/includes? printed literal)) literal))))

(deftest plan-pairs-are-readable-with-unchanged-ai
  (support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.agent/id "alice"}])
      (plan/plan! {:my.plan/objective "Ship a clear plan"
                   :my.plan/steps [{:my.plan.item/id "first" :my.plan.item/title "Prepare"}
                                   {:my.plan.item/id "second" :my.plan.item/title "Ship"
                                    :my.plan.item/done-when "The page reads clearly"
                                    :my.plan.item/needs #{[:my.plan.item/id "first"]}}]}
                  @connection connection "alice")
      (let [unit {:seon.agent/id "alice" :seon.db/db @connection}
            step (plan/item {:my.plan.item/id "first" :seon.db/db @connection})]
        (readable! (plan/render-plan-html unit) ["Ship a clear plan" "Blocked on: " "Prepare" "pending"])
        (readable! (plan/render-item-html step) ["Prepare" "pending"])
        (readable! (plan/render-ready-items-html [step]) ["Ready work (1)" "Prepare"])
        (is (= (golden :plan) (plan/render-plan-ai unit)))
        (is (= (golden :item) (plan/render-item-ai step)))
        (is (= (golden :ready) (plan/render-ready-items-ai [step])))
      (plan/complete! "first" connection "alice")
      (plan/start! "second" connection "alice")
      (readable! (plan/render-plan-html {:seon.agent/id "alice" :seon.db/db @connection})
                 ["done" "current" "datetime=" "The page reads clearly"])))))

(deftest settings-pair-omits-absences-and-preserves-ai
  (support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "html-views"})
      (db/transact! connection [{:seon.agent/id "alice"
                                 :seon.agent/settings {:seon.config.eval/time-limit-ms 10000}}])
      (let [unit {:seon.db/db @connection :seon.agent/id "alice"}
            html (agent/render-settings-html unit)
            printed (hiccup/->string html)]
        (readable! html ["override" "10 s" "defaults (" "unset settings omitted"])
        (is (= 1 (count (re-seq #"<table" printed))))
        (is (not (str/includes? printed "Not set")))
        (is (not (str/includes? printed "settings!")))
        (is (= (golden :settings) (agent/render-settings-ai unit)))))))

(deftest message-pairs-derive-unread-and-preserve-ai
  (support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.agent/id "alice"} {:seon.agent/id "bob"}])
      (db/transact! connection [{:seon.message/id "hello" :seon.message/content "Hello\nagain"
                                 :seon.message/from [:seon.agent/id "bob"]
                                 :seon.message/to [:seon.agent/id "alice"]
                                 :seon.message/inbox [:seon.agent/id "alice"]}])
      (let [row (db/pull @connection '[*] [:seon.message/id "hello"])
            unit (assoc row :seon.db/db @connection)]
        (readable! (message/render-html unit) ["Hello\nagain" "unread" "alice" "bob" "title="])
        (readable! (message/render-inbox-html [row] @connection) ["Messages (1)" "unread"])
        (is (= (golden :message) (message/render-ai unit)))
        (is (= (golden :inbox) (message/render-inbox-ai [row])))
        (db/transact! connection [[:db/retract [:seon.message/id "hello"] :seon.message/inbox]])
        (readable! (message/render-html (assoc (db/pull @connection '[*] [:seon.message/id "hello"])
                                              :seon.db/db @connection)) ["handled"])))))

(deftest note-pairs-link-titles-and-preserve-ai
  (support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.agent/id "alice"}
                                 {:my.plan.item/id "first" :my.plan.item/title "Prepare"}])
      (db/transact! connection [{:my.note/id "observation" :my.note/content "Verified"
                                 :my.note/agent [:seon.agent/id "alice"]
                                 :my.note/about [:my.plan.item/id "first"]}])
      (let [row (assoc (db/pull @connection '[*] [:my.note/id "observation"]) :seon.db/db @connection)
            unit {:seon.agent/id "alice" :seon.db/db @connection}]
        (readable! (note/render-note-html row) ["observation" "Verified" "Prepare" "datetime="])
        (readable! (note/render-notes-html unit) ["Current notes (1)" "Prepare"])
        (is (= (golden :note) (note/render-note-ai {:my.note/id (:my.note/id row)
                                                   :my.note/content (:my.note/content row)
                                                   :my.note/agent [:seon.agent/id "alice"]})))
        (is (= (golden :notes) (note/render-notes-ai unit)))))))

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
      (db/transact! connection (cluster.agent/creation-tx
                               {:seon.agent/id "alice" :seon.ns/name 'my.agents.alice
                                :seon.cluster/name "fixture"}))
      (let [unit {:seon.agent/id "alice" :seon.db/db @connection}
            creation {:seon.agent/id "alice" :seon.ns/name 'my.agents.alice
                      :seon.cluster/name "fixture" :seon.turn/id "opening"}]
        (readable! (cluster.agent/render-identity-html unit) ["Agent" "alice" "my.agents.alice" "Steward"])
        (readable! (cluster.agent/render-id-html "alice" @connection) ["alice" "Steward"])
        (readable! (cluster.agent/render-creation-html creation) ["alice" "Opening turn"])
        (is (= (golden :identity) (cluster.agent/render-identity-ai unit)))
        (is (= (golden :id) (cluster.agent/render-id-ai "alice")))
        (is (= (golden :creation) (cluster.agent/render-creation-ai creation)))))))

