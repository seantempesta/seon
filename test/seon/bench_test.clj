(ns seon.bench-test
  "Unit and local-loopback tests for `seon.bench.proxy`, `seon.bench.cluster`, and Harbor adapter files."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.http.core :as http]
            [ring-chez.adapter :as adapter]
            [seon.bench.cluster :as cluster]
            [seon.bench.proxy :as proxy]
            [seon.git :as git]
            [seon.model :as model]
            [seon.schema :as schema]
            [seon.fork.container :as fcontainer]
            [seon.render.tiles :as tiles]
            [seon.bench.helpers]))

(defn- http-get! [url]
  (let [u (http/parse-url url)
        s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
    (try
      (http/s-write s (http/build-request "GET" u [["Accept" "application/json"]] ""))
      (let [r (http/read-response s nil "GET" nil true false)
            bs (if-let [pull (:body-pull r)]
                 (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                 (or (:body r) (byte-array 0)))]
        {:status (:status r) :body (String. ^bytes bs "UTF-8")})
      (finally
        (http/s-close s)))))

(deftest ^{:cost/ms 3000} bench-proxy-streaming-usage-and-budget-cap-test
  (let [upstream-handler
        (fn [req]
          (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))
                payload (try (json/read-str body-str :key-fn keyword) (catch Throwable _ {}))]
            (if (:stream payload)
              (let [ch (a/chan 8)]
                (a/go
                  (a/>! ch (str "data: " (json/write-str {:choices [{:delta {:content "hel"}}]}) "\n\n"))
                  (a/>! ch (str "data: " (json/write-str {:choices [{:delta {:content "lo"}}]
                                                          :usage {:prompt_tokens 100
                                                                  :completion_tokens 30
                                                                  :prompt_cache_hit_tokens 40
                                                                  :total_tokens 130}}) "\n\n"))
                  (a/>! ch "data: [DONE]\n\n")
                  (a/close! ch))
                {:status 200
                 :headers {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"}
                 :body ch})
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/write-str {:choices [{:message {:content "ok"}}]
                                      :usage {:prompt_tokens 50
                                              :completion_tokens 15
                                              :total_tokens 65}})})))
        upstream (adapter/run-server upstream-handler {:port 0 :host "127.0.0.1"})
        up-url (str "http://127.0.0.1:" (:port upstream))
        p (proxy/start! {:upstream-url up-url :port 0 :max-in-flight 2 :max-requests 2})]
    (try
      (testing "JSON completion forwards and records usage"
        (let [r1 (model/http-post! (str (:base-url p) "/chat/completions")
                                   [["Content-Type" "application/json"]]
                                   (json/write-str {:model "test" :stream false}))]
          (is (= 200 (:status r1)))
          (is (str/includes? (:body r1) "\"ok\""))))

      (testing "SSE streaming completion forwards chunks and extracts usage"
        (let [r2 (model/http-post! (str (:base-url p) "/v1/chat/completions")
                                   [["Content-Type" "application/json"]
                                    ["Accept" "text/event-stream"]]
                                   (json/write-str {:model "test" :stream true}))]
          (is (= 200 (:status r2)))
          (is (str/includes? (:body r2) "\"hel\""))
          (is (str/includes? (:body r2) "[DONE]"))))

      (testing "Proxy stats & GET /stats match accumulated usage with 0 in-flight"
        (let [st (proxy/stats p)
              http-st (http-get! (str (:base-url p) "/stats"))
              parsed (json/read-str (:body http-st) :key-fn keyword)]
          (is (= 2 (:requests st)))
          (is (= 0 (:in-flight st)))
          (is (= 0 (:rejected st)))
          (is (= 150 (:prompt-tokens st)))
          (is (= 45 (:completion-tokens st)))
          (is (= 40 (:cache-hit-tokens st)))
          (is (= 195 (:total-tokens st)))
          (is (= 200 (:status http-st)))
          (is (= 195 (:total-tokens parsed)))))

      (testing "Hard request budget returns 429 proxy_budget_exceeded"
        (let [r3 (model/http-post! (str (:base-url p) "/chat/completions")
                                   [["Content-Type" "application/json"]]
                                   (json/write-str {:model "test" :stream false}))]
          (is (= 429 (:status r3)))
          (is (str/includes? (:body r3) "proxy_budget_exceeded"))
          (is (= 1 (:rejected (proxy/stats p))))))
      (finally
        (proxy/stop! p)
        (try (adapter/stop-server upstream {:drain-timeout-ms 500}) (catch Throwable _))))))

(deftest ^{:cost/ms 1500} bench-cluster-helpers-and-harbor-files-test
  (testing "Pure cluster helpers pass examples + seed=42 generative checks"
    (let [reg nil]
      (doseq [sym ['seon.bench.helpers/parse-listening-port
                  'seon.bench.helpers/build-instance-cmd
                  'seon.bench.helpers/agent-settled?]]
        (let [res (schema/stress-var reg sym {:samples 50 :seed 42})]
          (is (:ok? res) (str "stress-var failed for " sym ": " (pr-str res)))))))

  (testing "build-instance-cmd emits expected multi-instance flags"
    (let [cmd (cluster/build-instance-cmd
               {:bin-path "/opt/seon/seon"
                :store-path "/tmp/s1"
                :seed-store "/app/runs/store"
                :repo-dir "/app"
                :model-base-url "http://127.0.0.1:18080"
                :model "deepseek-chat"
                :max-os-procs 2
                :max-test-workers 2})]
      (is (= "/opt/seon/seon" (first cmd)))
      (is (some #{"--headless"} cmd))
      (is (some #{"--seed-store"} cmd))
      (is (some #{"--model-base-url"} cmd))
      (is (some #{"--auto-switch-over"} cmd))))

  (testing "Harbor bench/ files are tracked paths"
    (is (git/tracked-path? "bench/harbor/seon_agent.py"))
    (is (git/tracked-path? "bench/harbor/README.md"))))

(deftest
  container-bridge-helpers-and-ctx-tile-mode-test
  (testing
    "clip-output preserves short text and clips huge logs cleanly"
    (is (= "hello" (fcontainer/clip-output "hello" 100)))
    (let [big (apply str (repeat 500 "abcd"))
          clipped (fcontainer/clip-output big 200)]
      (is (< (count clipped) 260))
      (is (str/includes? clipped "chars omitted"))))
  (testing
    "bridge helpers return structured :no-exec-bridge when SEON_EXEC_BRIDGE_URL is unset"
    (binding [fcontainer/*bridge-url* ""]
    (is (= :no-exec-bridge (:error (fcontainer/sh! "echo hi"))))
    (is (= false (:ok? (fcontainer/fs-read "/app/a.txt"))))
    (is (= false (:ok? (fcontainer/fs-write "/app/a.txt" "hi"))))
    (is (= false (:ok? (fcontainer/fs-patch "/app/a.txt" "a" "b"))))))
  (testing
    "bridge helpers round-trip JSON cleanly over a local loopback HTTP server"
    (let [srv (adapter/run-server
              (fn [req]
                (let [uri (:uri req)
                      b (:body req)
                      bstr (when b (if (string? b) b (slurp b)))
                      body (when bstr
                             (json/read-str bstr :key-fn keyword))]
                  {:status 200,
                   :headers {"Content-Type" "application/json"},
                   :body
                   (json/write-str
                     (case
                       uri
                       "/exec"
                       {:exit 0,
                        :out (str "ran:" (:command body)),
                        :err ""}
                       "/read"
                       {:ok true, :lines 2, :content "line1\nline2"}
                       "/write"
                       {:ok true, :bytes (count (str (:content body)))}
                       "/patch"
                       {:ok true, :replacements 1}
                       {:ok false, :error "unknown"}))}))
              {:port 0, :host "127.0.0.1"})
        port (:port srv)]
    (try
      (binding [fcontainer/*bridge-url* (str "http://127.0.0.1:" port)]
      (is (= {:exit 0, :out "ran:ls -la", :err ""} (fcontainer/sh! "ls -la")))
      (is (= {:ok? true, :path "/app/x", :lines 2, :content "line1\nline2"} (fcontainer/fs-read "/app/x")))
      (is (= {:ok? true, :path "/app/x", :bytes 5} (fcontainer/fs-write "/app/x" "hello")))
      (is (= {:ok? true, :path "/app/x", :replacements 1} (fcontainer/fs-patch "/app/x" "he" "yo"))))
      (finally (adapter/stop-server srv)))))
  (testing
    "ctx-tile renders compact Container Workspace tools in :mode :container"
    (let [h (tiles/ctx-tile
              {:mode :agent}
              {:seon.agent/id :bench/harbor,
               :seon.agent/branch :branch/harbor,
               :seon.agent/context-prefs {:mode :container}})
          s (pr-str h)]
      (is (str/includes? s "user/sh!"))
      (is (str/includes? s "user/fs-patch"))
      (is (not (str/includes? s "user/edit-fn!"))))))