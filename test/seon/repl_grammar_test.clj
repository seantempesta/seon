(ns seon.repl-grammar-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.repl :as repl]
            [seon.render.transcript :as transcript]
            [seon.render.hiccup :as hiccup]
            [seon.render :as render]
            [seon.sci.eval :as sci.eval]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest prompt-precedes-exact-multiline-agent-input
  (let [emission {:seon.ns/name 'my.agents.juniper
                  :seon.cluster.eval/comment ";; I should inspect the rows.\n;; Then I can choose."
                  :seon.cluster.eval/source "(->> rows\n     (map :total))"
                  :seon.eval/shown "[40 60]"}
        expected "my.agents.juniper=> ;; I should inspect the rows.\n;; Then I can choose.\n(->> rows\n     (map :total))\n#:seon.repl{:value [40 60]}"
        html (repl/render-html emission)]
    (is (= expected (repl/text emission)))
    (is (= 128
           (alength (.getBytes (repl/text emission) "UTF-8"))))
    (is (= (subs expected 0 (str/index-of expected "\n#:seon.repl"))
           (get-in html [2 1 2])))))

(deftest stored-help-keeps-its-renderer-and-bare-response
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "grammar"})
     (support/seed-cluster! connection "grammar")
     (is (:db-after (db/transact! connection
                     (agent/creation-tx {:seon.agent/id "juniper"
                                         :seon.ns/name 'my.agents.juniper
                                         :seon.cluster/name "grammar"}))))
     (let [ctx (support/fork-cluster-ctx connection "grammar")
           handle (support/cluster-handle
                   {:seon.db/connection connection :seon.cluster/name "grammar"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})]
       (try
         (let [opened (turn/system-turn {:seon.turn.loop/cluster handle
                                         :seon.agent/id "juniper" :seon.turn/write? true})
               saved (first (evaluation/of-agent @connection "juniper"))
               expected-response (bootstrap/render-help-ai (bootstrap/help-value @connection "juniper"))
               expected (str "my.agents.juniper=> ;; I should understand how this REPL works before I act.\n(help)\n"
                             expected-response)]
           (is (not (:seon.error/kind opened)) (pr-str opened))
           (is (= "(help)" (:seon.cluster.eval/source saved)))
           (is (= 'seon.bootstrap/render-help-ai (:seon.eval/renderer saved)))
           (is (= expected-response (repl/response (repl/entity-emission saved))))
           (is (= expected (repl/render-ai saved)))
           (let [agent-ctx (:seon.sci.eval/ctx
                            (sci.eval/fork-for-turn
                             {:seon.sci.eval/ctx ctx :seon.db/db @connection
                              :seon.agent/id "juniper"}))
                 instructions (bootstrap/help-value @connection "juniper")]
             (sci.eval/bind-result! agent-ctx
                                   (admit/result-handle (:seon.cluster.eval/id saved))
                                   instructions)
             (let [html (hiccup/->string
                         (repl/render-html
                          (assoc handle :seon.render/value saved
                                        :seon.db/db @connection
                                        :seon.sci.eval/time-limit-ms 10000
                                        :seon.sci.eval/agent-ctx agent-ctx
                                        :seon.render/profile (render/agent-render-profile (config/defaults)))))]
               (is (str/includes? html "<ul class=\"seon-help"))
               (is (= (count (:seon.help/lines instructions))
                      (count (re-seq #"<li>" html))))
               (is (str/includes? html "seon.bootstrap/render-help-html"))
               (is (not (str/includes? html "live value unavailable")))))
           (is (= expected
                  (:seon.render.history/bytes
                   (first (transcript/history-entries
                           {:seon.db/db @connection :seon.agent/id "juniper"
                            :seon.render.transcript/selected-run-id (:seon.turn/id opened)
                            :seon.sci.admit/caps (config/result-caps (config/defaults))})))))
           (println "REPL-GRAMMAR-HELP-BYTES" (alength (.getBytes expected "UTF-8"))))
         (finally
           (doseq [channel [(:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion-channel handle)]]
             (when channel (async/close! channel)))))))))
