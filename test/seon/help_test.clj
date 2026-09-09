(ns seon.help-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest help-is-first-stored-data-and-tracks-its-code-version
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "help"})
     (db/transact! connection
                   (into [{:seon.cluster/name "help"}]
                         (agent/creation-tx {:seon.agent/id "help"
                                             :seon.ns/name 'my.agents.help
                                             :seon.cluster/name "help"})))
     (let [ctx (support/fork-cluster-ctx connection "help")
           handle (support/cluster-handle
                   {:seon.db/connection connection
                    :seon.cluster/name "help"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           request {:seon.turn.loop/cluster handle
                    :seon.agent/id "help"
                    :seon.turn/write? true}]
       (try
         (is (identical? (sci/resolve ctx 'clojure.core/help)
                         (sci/resolve ctx 'seon.bootstrap/help))
             "bare help refers to the acquired macro rather than a boot-time copy")
         (let [opening (turn/system-turn request)
               saved (first (evaluation/of-agent @connection "help"))
               lines (some-> (:seon.eval/value saved) edn/read-string)
               evidence (:seon.cluster.eval/read-evidence saved)
               shown (repl/render-ai saved)]
           (is (nil? (:seon.error/kind opening)) (pr-str opening))
           (is (string? (:seon.turn/id opening)))
           (is (= "(help)" (:seon.cluster.eval/source saved)))
           (is (vector? lines) (pr-str lines))
           (is (= 13 (count lines)))
           (is (every? #(and (string? %) (not (str/includes? % "\n"))) lines))
           (is (= "You are at a Clojure REPL in your namespace my.agents.help. Every function in the program is callable."
                  (first lines)))
           (is (= 3 (count (filter #(str/includes? % "▲") lines))))
           (is (str/starts-with? (last lines) "Tools: "))
           (is (str/includes? (last lines) "my.plan"))
           (is (not (seq (:seon.cluster.eval/output saved))))
           (is (seq evidence))
           (is (true? (db/read-evidence-current? @connection evidence)))
           (is (str/includes? shown ":ms"))
           (is (str/includes? shown "result/e"))
           (db/transact! connection [{:seon.agent/id "unrelated"}])
           (is (true? (db/read-evidence-current? @connection evidence)))
           (is (= shown (repl/render-ai (first (evaluation/of-agent @connection "help")))))
           (let [source (:seon.fn/source
                         (db/pull @connection [:seon.fn/source]
                                  [:seon.fn/sym "seon.bootstrap/help-value"]))]
             (is (string? source))
             (db/transact! connection
                           [[:db/add [:seon.fn/sym "seon.bootstrap/help-value"]
                             :seon.fn/source (str source "\n")]])
             (is (false? (db/read-evidence-current? @connection evidence)))))
         (finally
           (doseq [channel [(:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion handle)]]
             (async/close! channel))))))))
