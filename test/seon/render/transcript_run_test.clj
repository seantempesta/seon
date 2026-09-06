(ns seon.render.transcript-run-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.transcript :as transcript]
            [seon.test-support :as support]))

(def ^:private caps
  (assoc (config/result-caps (support/effective-config))
         :seon.config.eval.result/max-string 4096
         :seon.config.eval.result/max-collection 64
         :seon.config.eval.result/max-depth 12
         :seon.config.eval.result/max-nodes 4096))

(deftest render-run-selects-only-the-requested-run
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.cluster.agent/id "run-render-agent"}
       {:seon.cluster.run/id "run-a"
        :seon.cluster.run/agent [:seon.cluster.agent/id "run-render-agent"]
        :seon.cluster.run/opened-at #inst "2026-09-06T00:00:00.000-00:00"}
       {:seon.cluster.run/id "run-b"
        :seon.cluster.run/agent [:seon.cluster.agent/id "run-render-agent"]
        :seon.cluster.run/opened-at #inst "2026-09-06T00:01:00.000-00:00"}
       {:seon.cluster.run.form/id "form-a"
        :seon.cluster.run.form/run [:seon.cluster.run/id "run-a"]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/author :agent
        :seon.cluster.run.form/source "(+ 1 1)"}
       {:seon.cluster.run.form/id "form-a-pending"
        :seon.cluster.run.form/run [:seon.cluster.run/id "run-a"]
        :seon.cluster.run.form/ordinal 1
        :seon.cluster.run.form/author :agent
        :seon.cluster.run.form/source "pending-form"}
       {:seon.cluster.run.form/id "form-b"
        :seon.cluster.run.form/run [:seon.cluster.run/id "run-b"]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/author :agent
        :seon.cluster.run.form/source "(* 9 9)"}
       {:seon.cluster.eval/id "eval-a"
        :seon.cluster.eval/run [:seon.cluster.run/id "run-a"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/at #inst "2026-09-06T00:00:01.000-00:00"
        :seon.cluster.eval/result-edn "2"}
       {:seon.cluster.eval/id "eval-b"
        :seon.cluster.eval/run [:seon.cluster.run/id "run-b"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/at #inst "2026-09-06T00:01:01.000-00:00"
        :seon.cluster.eval/result-edn "81"}])
     (let [unit {:seon.db/db (db/db connection)
                 :seon.cluster.agent/id "run-render-agent"
                 :seon.cluster.run/id "run-a"
                 :seon.render.transcript/token-budget 10000
                 :seon.sci.admit/caps caps}
           ai (transcript/render-run-ai unit)
           html (transcript/render-run-html unit)]
       (is (str/includes? ai "(+ 1 1)"))
       (is (str/includes? ai "2"))
       (is (str/includes? ai "pending-form"))
       (is (not (str/includes? ai "(* 9 9)")))
       (is (not (str/includes? ai "81")))
       (is (str/includes? (pr-str html) "pending-form"))
       (is (not (str/includes? (pr-str html) "(* 9 9)")))))))
