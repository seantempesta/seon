(ns seon.help-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.bootstrap :as bootstrap]
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
     (let [setup (db/transact! connection
                   (into [[:db/add "cluster" :seon.cluster/name "help"]]
                         (agent/creation-tx {:seon.agent/id "help"
                                             :seon.ns/name 'my.agents.help
                                             :seon.cluster/name "help"})))]
       (is (:db-after setup) (pr-str setup)))
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
         (let [evaluate turn/evaluate-sources
               entered? (atom false)
               committed (atom nil)
               opening (with-redefs [turn/evaluate-sources
                                    (fn [evaluation-request]
                                      (when (compare-and-set! entered? false true)
                                        (reset! committed (turn/system-turn request)))
                                      (evaluate evaluation-request))]
                         (turn/system-turn request))
               saved (first (evaluation/of-agent @connection "help"))
               lines (some-> (:seon.eval/shown saved) str/split-lines)
               evidence (:seon.cluster.eval/read-evidence saved)
               shown (when saved (repl/render-ai saved))]
           (is (some? saved) (pr-str {:opening opening :committed @committed}))
           (is (empty? (keep :seon.cluster.eval/error (evaluation/of-agent @connection "help"))))
           (is (nil? (:seon.error/kind opening)) (pr-str opening))
           (is (nil? (:seon.turn/id opening))
               "a second real system pass committed while this pass evaluated")
           (is (string? (:seon.turn/id @committed)))
           (is (= "(help)" (:seon.cluster.eval/source saved)))
           (is (= ['help 'seon.db/pull 'seon.plan/plan 'seon.db/pull
                   'seon.agent/settings 'get 'dir 'seon.db/pull]
                  (mapv (comp first edn/read-string :seon.cluster.eval/source)
                        (evaluation/of-agent @connection "help"))))
           (let [entries (evaluation/of-agent @connection "help")
                 empty-reads (subvec entries 2 6)]
             (is (= ["nil" "[]"] (mapv :seon.eval/shown (mapv empty-reads [1 3]))))
             (is (= {} (edn/read-string (:seon.eval/shown (nth empty-reads 2)))))
             (is (every? #(seq (:seon.cluster.eval/read-evidence %)) empty-reads)))
           (is (vector? lines) (pr-str lines))
           (is (= 13 (count lines)))
           (is (every? #(and (string? %) (not (str/includes? % "\n"))) lines))
           (is (= "The prompt shows your namespace my.agents.help and is drawn for you. Send only ;; thinking comments and forms."
                  (first lines)))
           (is (not-any? #(str/includes? % (str (char 9650))) lines))
           (is (= lines (:seon.help/lines (bootstrap/help-value @connection "help"))))
           (is (= :ul (first (bootstrap/render-help-html {:seon.help/lines lines}))))
           (is (str/includes? (nth lines 6) "identity-less nested map replaces it"))
           (is (str/includes? (nth lines 7) ":my.message/to"))
           (is (str/includes? (nth lines 7) "retractEntity"))
           (is (str/includes? (nth lines 10) "datomic.tx"))
           (is (str/starts-with? (last lines) "Tools: "))
           (is (str/includes? (last lines) "my.plan"))
           (is (str/includes? (last lines) "my.turn"))
           (is (not (str/includes? (last lines) "my.run")))
           (is (not (str/includes? (last lines) "render-namespace-ai")))
           (is (not (str/includes? (last lines) "usage-form")))
           (is (true? (:seon.fn/internal?
                       (db/pull @connection [:seon.fn/internal?]
                                [:seon.fn/sym "my.turn/usage-form"]))))
           (is (not (seq (:seon.cluster.eval/output saved))))
           (is (seq evidence))
           (is (= 0 (turn/episode-runs @connection "help")))
           (let [again (turn/system-turn request)]
             (is (nil? (:seon.turn/id again)))
             (let [changed (filter #(= :changed (:seon.turn/status %)) (:seon.turn/forms again))]
               (is (empty? changed))))
           (is (true? (db/read-evidence-current? @connection evidence)))
           (is (= 'seon.bootstrap/render-help-ai (:seon.eval/renderer saved)))
           (is (= (:seon.eval/shown saved) (repl/response (repl/entity-emission saved))))
           (support/transacted! connection [{:seon.agent/id "unrelated"}])
           (is (true? (db/read-evidence-current? @connection evidence)))
           (is (= shown (repl/render-ai (first (evaluation/of-agent @connection "help")))))
           (let [source (:seon.fn/source
                         (db/pull @connection [:seon.fn/source]
                                  [:seon.fn/sym "seon.bootstrap/help-value"]))]
             (is (string? source))
             (support/transacted! connection
                                  [[:db/add [:seon.fn/sym "seon.bootstrap/help-value"]
                                    :seon.fn/source (str source "\n")]])
             (is (false? (db/read-evidence-current? @connection evidence))))
           (let [initial (subvec (evaluation/of-agent @connection "help") 2 6)
                 written (db/transact!
                          connection
                          [{:seon.agent/id "help"
                            :seon.agent/plan {:my.plan/objective "Observe the new plan"}
                            :seon.agent/settings {:seon.config.ai/no-provider true}}
                           {:seon.message/id "new-message" :seon.message/to [:seon.agent/id "help"] :seon.message/content "Observe the new message"}
                           {:my.note/id "first-note" :my.note/agent [:seon.agent/id "help"]
                            :my.note/content "Observe the first note"}])]
             (is (:db-after written) (pr-str written))
             (is (every? #(false? (db/read-evidence-current?
                                   @connection (:seon.cluster.eval/read-evidence %))) initial))
             (is (not (:seon.error/kind (turn/system-turn request))))
             (let [latest (into {} (map (juxt :seon.cluster.eval/source :seon.eval/shown))
                                (evaluation/of-agent @connection "help"))]
               (doseq [[entry text] (map vector initial
                                        ["Observe the new plan" "Observe the new message" "no-provider"
                                         "Observe the first note"])]
                 (is (str/includes? (get latest (:seon.cluster.eval/source entry)) text)))
               (is (true? (get-in (edn/read-string (get latest "(seon.agent/settings)"))
                                  [:seon.repl/changes [:seon.config.ai/no-provider]
                                   :seon.db.diff/after]))))))
         (finally
           (doseq [channel [(:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion handle)]]
             (async/close! channel))))))))
