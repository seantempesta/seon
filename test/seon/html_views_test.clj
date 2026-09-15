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

