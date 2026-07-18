(ns seon.dev.mcp-test
  (:require [cheshire.core :as json]
            [clojure.core.server :as core-server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.mcp :as mcp])
  (:import [java.net ServerSocket]))

(defn- start-prepl! []
  (let [name (str "seon-mcp-test-" (random-uuid))
        socket (core-server/start-server
                {:name name
                 :address "127.0.0.1"
                 :port 0
                 :accept 'clojure.core.server/io-prepl})]
    {:socket socket :port (.getLocalPort ^ServerSocket socket)}))

(defn- response-data [response]
  (json/parse-string (get-in response [:content 0 :text]) true))

(deftest tool-contract-exposes-one-name-for-each-runtime
  (let [by-name (into {} (map (juxt :name identity)) mcp/tools)]
    (is (contains? by-name "eval_clj"))
    (is (contains? by-name "eval_cljs"))
    (is (not (contains? by-name "eval")))
    (doseq [tool-name ["eval_clj" "eval_cljs"]]
      (is (= ["code"] (get-in by-name [tool-name :inputSchema :required])))
      (is (= "integer"
             (get-in by-name [tool-name :inputSchema :properties
                              :max_output_tokens :type]))))))

(deftest eval-dispatch-has-no-compatibility-alias
  (with-redefs-fn {#'mcp/execute-eval identity}
    (fn []
      (is (= {:code "(+ 1 2)"}
             (#'mcp/execute-tool "eval_cljs" {"code" "(+ 1 2)"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown tool: eval"
                            (#'mcp/execute-tool "eval"
                                                {"code" "(+ 1 2)"}))))))

(deftest shadow-discovery-derives-every-artifact-flavor-port-file
  (let [root "/tmp/seon-mcp-root"]
    (with-redefs [mcp/project-root root]
      (is (= #{[:seon.dev.artifact.flavor/default
                "/tmp/seon-mcp-root/.shadow-cljs/nrepl.port"]
               [:seon.dev.artifact.flavor/acme
                "/tmp/seon-mcp-root/tmp/shadow/acme/nrepl.port"]}
             (into #{}
                   (map (juxt :seon.dev.mcp/artifact-flavor
                              :seon.dev.mcp/port-file))
                   (#'mcp/shadow-endpoints)))))))

(deftest agent-resolution-spans-isolated-shadow-servers
  (let [candidates
        [{:seon.dev.mcp/port 41001
          :seon.dev.runtime-id/cluster "default"
          :seon.dev.runtime-id/ids ["root"]
          :build ":client"
          :client-id 1}
         {:seon.dev.mcp/port 41002
          :seon.dev.runtime-id/cluster "acme"
          :seon.dev.runtime-id/ids ["root"]
          :build ":acme-client"
          :client-id 2}]]
    (with-redefs-fn {#'mcp/all-advertisements! (constantly candidates)}
      (fn []
        (is (= :ambiguous
               (:seon.dev.runtime-id/resolution
                (#'mcp/resolve-agent-runtime! "root"))))
        (is (= 41002
               (get-in (#'mcp/resolve-agent-runtime! "acme/root")
                       [:seon.dev.runtime-id/runtime :seon.dev.mcp/port])))))))

(deftest agent-session-pins-on-the-selected-shadow-server
  (let [sessions (atom {})
        pinned (atom [])
        runtime {:seon.dev.runtime-id/resolution :match
                 :seon.dev.runtime-id/runtime
                 {:seon.dev.mcp/port 41002
                  :seon.dev.runtime-id/cluster "acme"
                  :seon.dev.runtime-id/ids ["root"]
                  :build ":acme-client"
                  :client-id 2}}]
    (with-redefs-fn {#'mcp/agent-sessions sessions
                     #'mcp/resolve-agent-runtime! (constantly runtime)
                     #'mcp/pin-session!
                     (fn [port build client-id]
                       (swap! pinned conj [port build client-id])
                       "acme-session")}
      (fn []
        (is (= {:nrepl-session "acme-session"
                :port 41002
                :client-id 2
                :build ":acme-client"
                :cluster "acme"}
               (#'mcp/ensure-agent-session! "acme/root")))
        (is (= [[41002 ":acme-client" 2]] @pinned))))))

(deftest writer-prepl-sessions-are-stateful-bounded-and-restart-aware
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "seon-mcp-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        first-server (start-prepl!)
        port-file (io/file directory "tmp/seon-writer-repl-port-test")]
    (try
      (.mkdirs (.getParentFile port-file))
      (spit port-file (:port first-server))
      (with-redefs [mcp/project-root (.getPath directory)
                    mcp/own-cluster "test"
                    mcp/writer-port-file-override nil
                    mcp/all-advertisements! (constantly [])]
        (testing "a named session preserves io-prepl values"
          (is (= "41" (get-in (response-data
                                (#'mcp/execute-clj-eval
                                 {:code "41" :cluster "test"
                                  :session_id "stateful"}))
                               [:seon.dev.mcp/events 0 :val])))
          (is (= "42" (get-in (response-data
                                (#'mcp/execute-clj-eval
                                 {:code "(inc *1)" :cluster "test"
                                  :session_id "stateful"}))
                               [:seon.dev.mcp/events 0 :val]))))
        (testing "timeout closes the client session and returns data"
          (let [response (#'mcp/execute-clj-eval
                          {:code "(do (Thread/sleep 100) :late)"
                           :cluster "test" :session_id "slow"
                           :timeout_ms 10})]
            (is (:isError response))
            (is (= "timeout" (name (:seon.dev.mcp/failure
                                     (response-data response)))))))
        (testing "the deadline spans several io-prepl events"
          (let [response (#'mcp/execute-clj-eval
                          {:code "(do (Thread/sleep 15) (println \"tick\") (Thread/sleep 15) :late)"
                           :cluster "test" :session_id "overall-deadline"
                           :timeout_ms 20})]
            (is (:isError response))
            (is (= "timeout" (name (:seon.dev.mcp/failure
                                     (response-data response)))))))
        (testing "multiple forms are rejected without queuing later returns"
          (#'mcp/execute-clj-eval {:code "9" :cluster "test"
                                   :session_id "framing"})
          (let [rejected (#'mcp/execute-clj-eval
                          {:code "10 11" :cluster "test"
                           :session_id "framing"})
                next-call (#'mcp/execute-clj-eval
                           {:code "(inc *1)" :cluster "test"
                            :session_id "framing"})]
            (is (:isError rejected))
            (is (= "multiple-forms"
                   (name (:seon.dev.mcp/failure
                          (response-data rejected)))))
            (is (= "10" (get-in (response-data next-call)
                                [:seon.dev.mcp/events 0 :val])))))
        (testing "malformed input is normalized without destroying session state"
          (let [rejected (#'mcp/execute-clj-eval
                          {:code "(inc" :cluster "test"
                           :session_id "framing"})
                next-call (#'mcp/execute-clj-eval
                           {:code "(inc *1)" :cluster "test"
                            :session_id "framing"})]
            (is (:isError rejected))
            (is (= "invalid-form"
                   (name (:seon.dev.mcp/failure
                          (response-data rejected)))))
            (is (= "11" (get-in (response-data next-call)
                                [:seon.dev.mcp/events 0 :val])))))
        (testing "a port change self-heals default but invalidates named state"
          (#'mcp/execute-clj-eval {:code "1" :cluster "test"})
          (let [second-server (start-prepl!)]
            (try
              (spit port-file (:port second-server))
              (let [named (#'mcp/execute-clj-eval
                           {:code "*1" :cluster "test"
                            :session_id "stateful"})
                    default (#'mcp/execute-clj-eval
                             {:code "(+ 20 22)" :cluster "test"})]
                (is (:isError named))
                (is (true? (:seon.dev.mcp/retry-with-new-session
                            (response-data named))))
                (is (= "42" (get-in (response-data default)
                                    [:seon.dev.mcp/events 0 :val]))))
              (finally (.close ^ServerSocket (:socket second-server)))))))
      (finally
        (.close ^ServerSocket (:socket first-server))
        (doseq [key (keys @mcp/clj-sessions)]
          (when (= "test" (first key))
            (when-let [socket (:socket (get @mcp/clj-sessions key))]
              (try (.close socket) (catch Throwable _)))
            (swap! mcp/clj-sessions dissoc key)))
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest json-rpc-output-is-one-clean-response-line
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (#'mcp/handle-request {:jsonrpc "2.0" :id 7 :method "tools/list"}))
    (let [lines (str/split-lines (str out))
          response (json/parse-string (first lines) true)]
      (is (= 1 (count lines)))
      (is (= 7 (:id response)))
      (is (vector? (get-in response [:result :tools])))
      (is (= "" (str err))))))

(deftest cljs-nrepl-transport-times-out-and-recognizes-stale-runtime
  (let [server (ServerSocket. 0 (int 1) (java.net.InetAddress/getLoopbackAddress))
        accepted (future
                   (with-open [_socket (.accept server)]
                     (Thread/sleep 100)))]
    (try
      (is (some #{"timeout"}
                (:status (#'mcp/nrepl-eval (.getLocalPort server)
                                         "test-session" "(+ 1 1)" 10))))
      (is (true? (#'mcp/stale-runtime?
                  {:value nil :err "" :status ["error"]})))
      (finally
        (.close server)
        (deref accepted 500 nil)))))

(deftest replaced-shadow-port-is-a-reconnectable-runtime-failure
  (let [server (ServerSocket. 0)
        port (.getLocalPort server)]
    (.close server)
    (let [result (#'mcp/nrepl-eval port "stale-session" "(+ 1 1)" 10)]
      (is (= :transport (:seon.dev.mcp/failure result)))
      (is (true? (#'mcp/stale-runtime? result))))))

(deftest default-cljs-eval-retries-through-the-existing-session-owner
  (let [retried (atom [])
        session {:port 41001 :nrepl-session "replaced"}]
    (with-redefs-fn {#'mcp/get-or-create-session!
                     (constantly {:sid "default" :session-info session})
                     #'mcp/nrepl-eval
                     (constantly {:err "Connection refused"
                                  :status ["error"]
                                  :seon.dev.mcp/failure :transport})
                     #'mcp/retry-with-fresh-session!
                     (fn [session-id code timeout]
                       (swap! retried conj [session-id code timeout])
                       {:sid "default"
                        :result {:value "42" :ns "cljs.user"
                                 :out "" :err "" :status ["done"]}})}
      (fn []
        (let [response (#'mcp/execute-eval {:code "(+ 20 22)"})]
          (is (not (:isError response)))
          (is (str/includes? (get-in response [:content 0 :text]) "42"))
          (is (= [[nil "(+ 20 22)" 30000]] @retried)))))))

(deftest cljs-sentinel-results-are-tool-errors
  (doseq [sentinel [":repl/exception!" ":repl/print-error!"]]
    (let [response (#'mcp/render-eval-result
                    {:value sentinel :ns "cljs.user" :err "failed"
                     :status ["done"]}
                    "sentinel")]
      (is (:isError response))
      (is (= "evaluation"
             (name (:seon.dev.mcp/failure (response-data response))))))))

(deftest own-cluster-honors-operator-writer-port-file-override
  (let [override "tmp/not-opened-by-this-test"
        expected (io/file mcp/project-root override)]
    (with-redefs [mcp/writer-port-file-override override
                  mcp/all-advertisements! (constantly [])]
      (is (= (.getPath expected)
             (.getPath (#'mcp/writer-port-file mcp/own-cluster)))))))

(deftest branch-writer-selection-consumes-the-runtime-advertisement
  (let [port-file "tmp/source-writer.port"
        advertisement
        {:seon.dev.runtime-id/cluster "default-proof"
         :seon.dev.runtime-id/ids ["root"]
         :seon.launch/writer-cluster "default"
         :seon.launch/writer-repl-port-file port-file
         :build ":client"
         :client-id 17}]
    (with-redefs [mcp/all-advertisements! (constantly [advertisement])]
      (is (= (.getPath (io/file mcp/project-root port-file))
             (.getPath (#'mcp/writer-port-file "default-proof")))))))

(deftest non-own-writer-selection-rejects-missing-and-ambiguous-runtimes
  (testing "a missing branch runtime cannot manufacture a writer filename"
    (with-redefs [mcp/all-advertisements! (constantly [])]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No live runtime"
           (#'mcp/writer-port-file "default-proof")))))
  (testing "two pods claiming one runtime cluster are explicit ambiguity"
    (let [advertisement
          {:seon.dev.runtime-id/cluster "default-proof"
           :seon.dev.runtime-id/ids ["root"]
           :seon.launch/writer-cluster "default"
           :seon.launch/writer-repl-port-file "tmp/source-writer.port"}]
      (with-redefs [mcp/all-advertisements!
                    (constantly [(assoc advertisement :client-id 17)
                                 (assoc advertisement :client-id 18)])]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Several live runtimes"
             (#'mcp/writer-port-file "default-proof")))))))

(deftest production-container-omits-development-io-prepl
  (let [source (slurp "docker/seon-entrypoint")]
    (is (not (clojure.string/includes? source "SEON_WRITER_REPL_PORT")))
    (is (not (clojure.string/includes? source "--repl-port")))))

(deftest cljs-transport-fields-are-bounded-before-accumulation
  (binding [mcp/*requested-output-tokens* 2]
    (let [limit (#'mcp/transport-char-limit)
          [response remaining truncated?]
          (#'mcp/cap-response-fields {"out" (apply str (repeat 1000 "x"))}
                                     limit)]
      (is truncated?)
      (is (zero? remaining))
      (is (= limit (count (get response "out")))))))

(deftest temporary-shadow-session-is-closed-after-reload-deps
  (let [closed (atom [])]
    (with-redefs-fn {#'mcp/require-port! (constantly 12345)
                     #'mcp/nrepl-clone-session (constantly "temporary")
                     #'mcp/nrepl-eval (fn [& _]
                                        {:value ":ok" :ns "user"
                                         :out "" :err "" :status ["done"]})
                     #'mcp/nrepl-close-session
                     (fn [port session] (swap! closed conj [port session]))}
      (fn [] (#'mcp/execute-reload-deps {})))
    (is (= [[12345 "temporary"]] @closed))))
