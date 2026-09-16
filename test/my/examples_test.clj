(ns my.examples-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.core :as datahike]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.cluster.instruction :as instruction]
            [seon.bootstrap :as bootstrap]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.sci.eval :as evaluation]
            [seon.turn :as turn]
            [seon.test-support :as support])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.io PushbackReader StringReader]
           [java.net InetSocketAddress]
           [java.nio.file Files Path]))

(defn- delete-tree! [root]
  (with-open [paths (Files/walk (.toPath root) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
      (Files/deleteIfExists path))))

(defn- web-fixture []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (with-open [body (.getResponseBody exchange)]
                          (let [content (.getBytes "{\"organic\":[{\"title\":\"Documentation\",\"link\":\"https://clojure.org\",\"position\":1}],\"credits\":1}" "UTF-8")]
                            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
                            (.sendResponseHeaders exchange 200 (alength content))
                            (.write body content))))))
    (.start server)
    server))

(deftest ^{:seon.test/fixture-observation
           "Runs public examples through real HTTP, filesystem, and background-effect fixtures; an ordinary database branch cannot prove these effects."}
  public-docstring-examples-run-in-the-canonical-agent-context
  (support/with-database
    (fn [connection]
      (with-open [root-resource (support/closeable
                                 (doto (io/file "tmp" (str "core-functions-examples-" (id/id))) .mkdirs)
                                 delete-tree!)
                  server-resource (support/closeable (web-fixture) #(.stop ^HttpServer % 0))]
        (let [root @root-resource
              example-url (str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer @server-resource)) "/")]
          (support/seed-cluster! connection "examples")
          (is (not (:seon.error/kind
                    (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "examples"
                                    :seon.config/manifest
                                    {:seon.config.fs/working-root (.getAbsolutePath root)
                                     :seon.config.fs/roots [(.getAbsolutePath root)]
                                     :seon.config.web/search-endpoint example-url
                                     :seon.config.web/search-api-key-variable "USER"}}))))
          (spit (io/file root "AGENTS.md") "Example input.\n")
          (is (:db-after (db/transact! connection
                                       (agent/creation-tx {:seon.agent/id "examples"
                                             :seon.ns/name 'my.examples-fixture
                                                           :seon.cluster/name "examples"}))))
          (is (:db-after (db/transact! connection
                                       [{:seon.agent/id "root"}
                                        {:seon.message/id "example-message"
                                         :seon.message/to [:seon.agent/id "examples"]
                                         :seon.message/content "Inspect the input."}])))
          (let [opened (db/transact! connection
                                     (turn/open-tx {:seon.turn/id "examples"
                                                    :seon.turn/agent [:seon.agent/id "examples"]
                                                    :seon.turn/opened-tx "datomic.tx"}))]
            (is (:db-after opened) (pr-str opened)))
          (with-open [launcher-resource
                      (support/closeable
                       (flow/start-work-launcher!
                        {:seon.env/environment (support/environment "examples" connection)
                         ::flow/configuration
                         (support/effective-config)})
                       flow/stop-work-launcher!)
                      events-resource (support/closeable (async/chan 1024) async/close!)
                      _listener-resource
                      (support/closeable
                       (let [listener-id (id/id)]
                         (datahike/listen! connection listener-id #(async/put! @events-resource %))
                         listener-id)
                       #(datahike/unlisten! connection %))]
            (let [base (support/fork-cluster-ctx connection "examples")
                  ctx (:seon.sci.eval/ctx
                       (evaluation/fork-for-turn {:seon.sci.eval/ctx base
                                                  :seon.db/db @connection
                                                  :seon.db/connection connection
                                                  :seon.agent/id "examples"}))
                  _ (sci/intern ctx (sci/find-ns ctx 'my.examples-fixture) 'example-url example-url)
                  ordinal (atom -1)
                  run (fn [source]
                        (let [index (swap! ordinal inc)
                              written (db/transact! connection
                                                    [{:seon.cluster.eval/id (id/evaluation "examples" index)
                                                      :seon.cluster.eval/run [:seon.turn/id "examples"]
                                                      :seon.cluster.eval/ordinal index
                                                      :seon.cluster.eval/at (java.util.Date.)
                                                      :seon.cluster.eval/source source
                                                    :seon.cluster.eval/ns [:seon.ns/name 'my.examples-fixture]
                                                      :seon.cluster.eval/author :agent}])]
                          (when-not (:db-after written)
                            (throw (ex-info "Example evaluation was not admitted." written)))
                          (evaluation/evaluate
                           {:seon.sci.eval/ctx ctx :seon.db/db @connection
                            :seon.db/connection connection :seon.agent/id "examples"
                            :seon.boot/cluster-name "examples" :seon.turn/id "examples"
                            :seon.flow/work-launcher @launcher-resource
                            :seon.cluster.eval/ordinal index
                            :seon.cluster.eval/ns [:seon.ns/name 'my.examples-fixture]
                            :seon.cluster.eval/source source
                            :seon.sci.admit/caps (config/result-caps (support/effective-config))
                            :seon.sci.eval/time-limit-ms 10000
                            :seon.config/on-core-error :panic})))
                  namespaces (instruction/toolkit-namespaces @connection)
                  rows (mapcat (fn [namespace-name]
                                 (let [directory (:seon.sci.admit/value (run (str "(dir " namespace-name ")")))]
                                   (is (seq (:functions directory)) (str namespace-name))
                                   (:functions directory))) namespaces)]
              (is (seq rows))
              (is (some #{'my.examples.nested-fixture} namespaces))
              (is (= (bootstrap/help-value @connection "examples")
                     (:seon.sci.admit/value (run "(help)"))))
              (doseq [{function-symbol :sym} rows]
                (is (true? (:seon.sci.admit/value
                            (run (str "(boolean (resolve '" function-symbol "))"))))
                    (str function-symbol)))
              (let [failed (run "(my.note/add! {:my.note/id \"bad-note\" :my.note/content 42})")
                    value (:seon.sci.admit/value failed)
                    documented (:seon.sci.admit/value (run "(doc my.note/add!)"))]
                (is (= :seon.instrument/contract-violated (:seon.error/kind value)) (pr-str failed))
                (is (= documented (:seon.error/doc value)))
                (is (str/includes? (:seon.error/message value "") "my.note/add!"))
                (is (str/includes? (:seon.error/message value "") ":my.note/content"))
                (is (some? (get-in value [:seon.error/data :seon.error/diagnostic-expected])))
                (is (empty? (db/q '[:find ?note :where [?note :my.note/id "bad-note"]] @connection))))
              (let [failed (run "(my.test/run {:seon.agent/id 17})")]
                (is (= :seon.instrument/contract-violated
                       (get-in failed [:seon.sci.admit/value :seon.error/kind]))
                    "A refused test lookup stays one flat error, never a vector of fake test results."))
              (doseq [{function-symbol :sym} rows
                      :let [metadata (some-> function-symbol requiring-resolve meta)]
                      :when (not (:seon.fn/internal? metadata))]
                (testing (str function-symbol)
                  (doseq [path ["example.txt" "example.clj"]]
                    (Files/deleteIfExists (.toPath (io/file root path))))
                  (let [documented (:seon.sci.admit/value (run (str "(doc " function-symbol ")")))
                        example (:example documented)]
                    (is (not (str/blank? example)) (pr-str documented))
                    (is (str/ends-with? (:summary documented "") ".") (pr-str documented))
                    (when (seq example)
                      (with-open [reader (PushbackReader. (StringReader. example))]
                        (loop []
                          (let [form (read {:eof ::eof} reader)]
                            (when-not (= ::eof form)
                              (let [result (run (pr-str form))]
                                (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
                                (when (= 'my.test/run function-symbol)
                                  (is (= [{:seon.test/pass-count 1 :seon.test/fail-count 0
                                           :seon.test/error-count 0}]
                                         (mapv #(select-keys % [:seon.test/pass-count
                                                                :seon.test/fail-count
                                                                :seon.test/error-count])
                                               (:seon.sci.admit/value result)))))
                                (is (nil? (get-in result [:seon.sci.admit/value :seon.error/kind]))
                                    (pr-str result)))
                              (recur)))))))))
              (let [effect-ids (db/q '[:find [?id ...] :where
                                       [?e :seon.effect/id ?id]
                                       [?e :seon.effect/run ?turn]
                                       [?turn :seon.turn/id "examples"]
                                       (or [?e :seon.effect/notify ?agent]
                                           [?e :seon.effect/to ?agent])
                                       [?agent :seon.agent/id "examples"]] @connection)]
                (is (vector? effect-ids) (pr-str effect-ids))
                (is (= 3 (count effect-ids)) "All three background examples submitted real work.")
                (doseq [effect-id effect-ids]
                  (let [settled? (fn [database]
                                   (:seon.effect/result-edn
                                    (db/pull database [:seon.effect/result-edn]
                                             [:seon.effect/id effect-id])))]
                    (when-not (settled? @connection)
                      (support/await-event! @events-resource [::background-settled effect-id]
                                            #(settled? (:db-after %))))))))))))))
