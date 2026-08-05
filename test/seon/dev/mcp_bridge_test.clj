(ns seon.dev.mcp-bridge-test
  (:require [clojure.data.json :as json]
            [clojure.core.server :as core.server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io PushbackReader]
           [java.util.concurrent TimeUnit TimeoutException]))

(def ^:private project-root
  (io/file (System/getProperty "user.dir")))

(def ^:private server-source
  (io/file project-root "script/seon/dev/mcp.clj"))

(def ^:private launcher
  (io/file project-root "bin/mcp-server"))

(def ^:private loaded-bridge
  (delay (load-file (.getPath server-source))))

(defn- bridge-var
  [var-symbol]
  @loaded-bridge
  (or (ns-resolve 'seon.dev.mcp var-symbol)
      (throw (ex-info "The MCP bridge var did not resolve."
                      {:seon.dev.mcp-test/symbol var-symbol}))))

(defn- result-data
  [result]
  (json/read-str (get-in result [:content 0 :text]) :key-fn keyword))

(defn- current-process-start-date
  []
  (java.util.Date/from
   (.get (.startInstant (.info (java.lang.ProcessHandle/current))))))

(defn- advertisement
  [cluster pid start-instant]
  {:seon.boot/cluster-name cluster
   :seon.boot/prepl-host "127.0.0.1"
   :seon.boot/prepl-port 31337
   :seon.boot/pid pid
   :seon.boot/start-instant start-instant})

(defn- delete-known-files!
  [files]
  (doseq [file (reverse files)]
    (when (.exists ^java.io.File file)
      (is (.delete ^java.io.File file)
          (str "Could not delete MCP fixture path " file)))))

(defn- reap-process-handle!
  [^java.lang.ProcessHandle handle]
  (when (.isAlive handle)
    (.destroy handle))
  (try
    (.get (.onExit handle) 10 TimeUnit/SECONDS)
    (catch TimeoutException _
      (.destroyForcibly handle)
      (.get (.onExit handle) 10 TimeUnit/SECONDS)))
  nil)

(defn- reap-process!
  [^Process process]
  (reap-process-handle! (.toHandle process)))

(defn- rpc-responses
  [requests]
  (let [process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List [(.getPath launcher)])
                   (.directory project-root)))
        stderr (future (slurp (.getErrorStream process)))]
    (with-open [writer (io/writer (.getOutputStream process))]
      (doseq [request requests]
        (.write writer (json/write-str request))
        (.write writer "\n"))
      (.flush writer))
    (let [responses (->> (slurp (.getInputStream process))
                         str/split-lines
                         (mapv #(json/read-str % :key-fn keyword)))
          completed? (.waitFor process 10 TimeUnit/SECONDS)]
      (when-not completed? (.destroyForcibly process))
      (is completed? @stderr)
      (is (= 0 (when completed? (.exitValue process))) @stderr)
      responses)))

(defn- namespace-form
  []
  (with-open [reader (PushbackReader. (io/reader server-source))]
    (loop []
      (let [form (read {:eof nil :read-cond :allow} reader)]
        (cond
          (nil? form) nil
          (and (seq? form) (= 'ns (first form))) form
          :else (recur))))))

(defn- require-targets
  [form]
  (->> (drop 2 form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))
       vec))

(deftest bridge-requires-no-application-namespace
  (let [targets (require-targets (namespace-form))
        application-targets
        (filterv #(str/starts-with? (str %) "seon.") targets)]
    (is (seq targets))
    (is (= [] application-targets)
        (str "The REPL bridge must not require application namespaces: "
             application-targets))))

(deftest bridge-loads-with-only-the-tooling-classpath
  (testing "Babashka does not need bb.edn, src/, or src-old/"
    (let [process
          (.start
           (doto
            (ProcessBuilder.
             ^java.util.List
             ["bb" "--classpath" "script" "-e"
              "(require 'seon.dev.mcp) (println :bridge-loaded)"])
             (.directory project-root)
             (.redirectErrorStream true)))
          completed? (.waitFor process 10 TimeUnit/SECONDS)
          _ (when-not completed? (.destroyForcibly process))
          output (slurp (.getInputStream process))]
      (is completed? "Babashka bridge load exceeded ten seconds.")
      (is (= 0 (when completed? (.exitValue process))) output)
      (is (str/includes? output ":bridge-loaded") output))))

(deftest bridge-content-has-no-second-truncation-budget
  (let [large (apply str (repeat 20000 "x"))
        payload {:seon.dev.mcp/events [{:tag :ret :val large}]}
        encoded ((bridge-var 'content-text) payload)
        decoded (json/read-str encoded :key-fn keyword)]
    (is (= large (get-in decoded [:seon.dev.mcp/events 0 :val])))
    (is (not (str/includes? encoded "truncated by MCP bridge")))))

(deftest multiple-form-refusals-name-the-second-form-position
  (let [code "(+ 1 2)\n(+ 3 4)"
        direct-result ((bridge-var 'execute-clj-eval)
                       {:code code :cluster "fixture"})
        [rpc-response]
        (rpc-responses
         [{:jsonrpc "2.0" :id 1 :method "tools/call"
           :params {:name "eval_clj" :arguments {:code code}}}])
        results [direct-result (:result rpc-response)]]
    (doseq [result results
            :let [data (result-data result)]]
      (is (true? (:isError result)))
      (is (= "multiple-forms" (:seon.dev.mcp/failure data)))
      (is (= 2 (:seon.dev.mcp/line data)))
      (is (= 1 (:seon.dev.mcp/column data)))
      (is (= "(+ 3 4)" (:seon.dev.mcp/preview data)))
      (is (str/includes? (:seon.dev.mcp/error data) "line 2, column 1"))
      (is (str/includes? (:seon.dev.mcp/error data) "(+ 3 4)"))
      (is (str/includes? (:seon.dev.mcp/error data) "(do ...)")))))

(deftest reader-position-does-not-depend-on-form-metadata
  (let [result ((bridge-var 'execute-clj-eval)
                {:code "(+ 1 2)\r\n  {:a 1}" :cluster "fixture"})
        data (result-data result)]
    (is (= "multiple-forms" (:seon.dev.mcp/failure data)))
    (is (= 2 (:seon.dev.mcp/line data)))
    (is (= 3 (:seon.dev.mcp/column data)))
    (is (= "{:a 1}" (:seon.dev.mcp/preview data)))))

(deftest invalid-forms-retain-the-reader-error
  (let [result ((bridge-var 'execute-clj-eval)
                {:code "(+ 1 2" :cluster "fixture"})
        data (result-data result)]
    (is (= "invalid-form" (:seon.dev.mcp/failure data)))
    (is (str/includes? (:seon.dev.mcp/reader-error data) "EOF while reading"))))

(deftest missing-advertisements-never-consult-old-writer-port-files
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-endpoint-" (random-uuid)))
        data-dir (io/file fixture-root "data")
        clusters-dir (io/file data-dir "clusters")
        cluster-dir (io/file clusters-dir "fixture")
        tmp-dir (io/file fixture-root "tmp")
        old-port-file (io/file tmp-dir "seon-writer-repl-port-fixture")
        known-paths [fixture-root data-dir clusters-dir cluster-dir
                     tmp-dir old-port-file]]
    (try
      (is (.mkdirs cluster-dir))
      (is (.mkdirs tmp-dir))
      (spit old-port-file "31337\n")
      (let [outcome
            (with-redefs-fn {(bridge-var 'operator-private)
                             (fn [& _]
                               {:seon.fresh-operator/advertisements []
                                :seon.fresh-operator/jvms []
                                :seon.fresh-operator/process-records []
                                :seon.fresh-operator/process-record-errors []})}
              #(try
                 ((bridge-var 'read-clj-endpoint)
                  (.getCanonicalPath fixture-root) "fixture")
                 (catch clojure.lang.ExceptionInfo error error)))]
        (is (instance? clojure.lang.ExceptionInfo outcome) (pr-str outcome))
        (is (= :repl-unavailable
               (:seon.dev.mcp/failure (ex-data outcome))))
        (is (= :missing
               (:seon.dev.mcp/advertisement-state (ex-data outcome))))
        (is (= (str "Start the cluster with: bin/seon --root "
                    (.getCanonicalPath fixture-root)
                    " start fixture.")
               (:seon.dev.mcp/remedy (ex-data outcome)))))
      (finally
        (delete-known-files! known-paths)))))

(deftest discovery-derives-live-stale-invalid-and-degraded-rows
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-status-" (random-uuid)))
        root (.getCanonicalPath fixture-root)
        pid (.pid (java.lang.ProcessHandle/current))
        started (current-process-start-date)
        alive (advertisement "alive" pid started)
        stale (advertisement "stale" pid (java.util.Date. 0))
        degraded (advertisement "degraded" pid started)
        unknown (advertisement "unknown" pid started)
        observations
        {:seon.fresh-operator/advertisements
         [{:seon.fresh-operator/name "alive"
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/alive/prepl.edn")
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/advertisement alive}
          {:seon.fresh-operator/name "stale"
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/stale/prepl.edn")
           :seon.fresh-operator/process-alive? false
           :seon.fresh-operator/advertisement stale}
          {:seon.fresh-operator/name "invalid"
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/invalid/prepl.edn")
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/advertisement {:not :an-advertisement}}
          {:seon.fresh-operator/name "degraded"
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/degraded/prepl.edn")
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/advertisement degraded}
          {:seon.fresh-operator/name "unknown"
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/unknown/prepl.edn")
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/advertisement unknown}]
         :seon.fresh-operator/jvms
         [{:seon.fresh-operator/root root
           :seon.fresh-operator/reachable? true
           :seon.fresh-operator/probe-advertisement alive
           :seon.fresh-operator/registrations
           [{:seon.fresh-operator/name "degraded"
             :seon.fresh-operator/root root
             :seon.fresh-operator/advertisement degraded}]}]
         :seon.fresh-operator/process-records []
         :seon.fresh-operator/process-record-errors []}
        rows
        (with-redefs-fn {(bridge-var 'operator-private)
                         (fn [var-symbol & _]
                           (case var-symbol
                             source-observations observations
                             prepl-value! {"alive" true
                                           "degraded" false}))
                         (bridge-var 'clj-sessions)
                         (atom {[root "alive" "investigation"] {}})}
          #((bridge-var 'discovery-rows) root))
        by-cluster (into {} (map (juxt :seon.dev.mcp/cluster identity)) rows)]
    (is (= "alive" (name (get-in by-cluster ["alive" :seon.dev.mcp/state]))))
    (is (= "degraded"
           (name (get-in by-cluster ["degraded" :seon.dev.mcp/state]))))
    (is (= "unknown"
           (name (get-in by-cluster ["unknown" :seon.dev.mcp/state]))))
    (is (= "stale" (name (get-in by-cluster ["stale" :seon.dev.mcp/state]))))
    (is (= "invalid"
           (name (get-in by-cluster ["invalid" :seon.dev.mcp/state]))))))

(deftest runtime-status-is-scoped-and-deduplicated
  (let [root (.getCanonicalPath
              (io/file project-root "tmp" (str "mcp-status-face-"
                                                (random-uuid))))
        selected "selected"
        selected-row {:seon.dev.mcp/root root
                      :seon.dev.mcp/cluster selected
                      :seon.dev.mcp/state :alive
                      :seon.dev.mcp/source :advertisement}
        unrelated-row {:seon.dev.mcp/root root
                       :seon.dev.mcp/cluster "unrelated"
                       :seon.dev.mcp/state :alive
                       :seon.dev.mcp/source :advertisement}
        observations (atom [])
        result
        (with-redefs-fn
          {(bridge-var 'discovery-rows)
           (fn [_] [selected-row selected-row unrelated-row])
           (bridge-var 'runtime-observation)
           (fn [_ cluster]
             (swap! observations conj cluster)
             {:seon.dev.mcp/health :observed})
           (bridge-var 'clj-sessions)
           (atom {[root selected "selected-session"] {}
                  [root "unrelated" "unrelated-session"] {}})}
          #((bridge-var 'execute-runtime-status)
            {:root root :cluster selected}))
        data (result-data result)
        rows (:seon.dev.mcp/clusters data)]
    (is (= "cluster-health-flow" (name (:seon.dev.mcp/view data))))
    (is (= [selected] @observations))
    (is (= 1 (count rows)))
    (is (= selected (:seon.dev.mcp/cluster (first rows))))
    (is (= 2 (:seon.dev.mcp/observation-count (first rows))))
    (is (= [{:seon.dev.mcp/root root
             :seon.dev.mcp/cluster selected
             :seon.dev.mcp/session-id "selected-session"}]
           (:seon.dev.mcp/sessions data)))
    (is (not (str/includes? (get-in result [:content 0 :text])
                            "unrelated")))))

(deftest transport-errors-echo-the-attempted-evaluation-coordinates
  (let [result
        (with-redefs-fn
          {(bridge-var 'current-clj-session!)
           (fn [& _]
             (throw (ex-info "fixture transport failed"
                             {:seon.dev.mcp/failure :transport-fixture})))}
          #((bridge-var 'execute-clj-eval)
            {:root (.getCanonicalPath project-root)
             :cluster "fixture"
             :namespace "mcp.fixture.target"
             :mode "door"
             :session_id "coordinates"
             :code "(+ 1 2)"}))
        data (result-data result)]
    (is (true? (:isError result)))
    (is (= "door" (:seon.dev.mcp/mode data)))
    (is (= "mcp.fixture.target" (:seon.dev.mcp/namespace data)))
    (is (= "fixture" (:seon.dev.mcp/cluster data)))
    (is (= "coordinates" (:seon.dev.mcp/session-id data)))
    (is (= "transport-fixture" (:seon.dev.mcp/failure data)))))

(deftest evaluation-events-report-the-exact-caller-source
  (let [source "  (+ 20 22)\n"]
    (doseq [mode ["jvm" "door"]
            exception? [false true]]
      (let [writer (java.io.StringWriter.)
            transport-form (atom nil)
            raw-events
            (if exception?
              [{:tag :ret :val "fixture failure" :exception true}]
              [{:tag :out :val "fixture output"}
               {:tag :ret :val "42" :ns "user"}])
            result
            (with-redefs-fn
              {(bridge-var 'current-clj-session!)
               (fn [& _]
                 {:writer writer
                  :endpoint {:seon.dev.mcp/cluster-state :alive}})
               (bridge-var 'collect-prepl-response!)
               (fn [_ _]
                 (let [generated (str/trim (str writer))]
                   (reset! transport-form generated)
                   (mapv #(assoc % :form generated) raw-events)))}
              #((bridge-var 'execute-clj-eval)
                {:root (.getCanonicalPath project-root)
                 :cluster "fixture"
                 :mode mode
                 :code source}))
            data (result-data result)
            events (:seon.dev.mcp/events data)]
        (is (= exception? (boolean (:isError result))))
        (is (not= source @transport-form)
            "the test must exercise a generated transport wrapper")
        (is (= (count raw-events) (count events)))
        (is (every? #(= source (:form %)) events)
            (str mode " events must report the caller source on "
                 (if exception? "evaluation error" "success")))))))

(deftest endpoint-selection-is-root-scoped-and-reaches-degraded-registrations
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-root-" (random-uuid)))
        root (.getCanonicalPath fixture-root)
        pid (.pid (java.lang.ProcessHandle/current))
        degraded (advertisement "partial" pid (current-process-start-date))
        observed-root (atom nil)
        observations
        {:seon.fresh-operator/advertisements []
         :seon.fresh-operator/jvms
         [{:seon.fresh-operator/root root
           :seon.fresh-operator/reachable? true
           :seon.fresh-operator/registrations
           [{:seon.fresh-operator/name "partial"
             :seon.fresh-operator/root root
             :seon.fresh-operator/advertisement degraded}]}]
         :seon.fresh-operator/process-records
         [{:seon.boot/pid pid}]
         :seon.fresh-operator/process-record-errors []}
        endpoint
        (with-redefs-fn
          {(bridge-var 'operator-private)
           (fn [_ requested-root _]
             (reset! observed-root requested-root)
             observations)}
          #((bridge-var 'read-clj-endpoint) root "partial"))]
    (is (= root @observed-root))
    (is (= root (:seon.dev.mcp/root endpoint)))
    (is (= :degraded (:seon.dev.mcp/cluster-state endpoint)))
    (is (= :operator-process-record (:seon.dev.mcp/source endpoint)))
    (is (= 31337 (:port endpoint)))))

(deftest duplicate-live-cluster-identities-fail-with-candidates
  (let [root (.getCanonicalPath
              (io/file project-root "tmp" (str "mcp-ambiguous-"
                                                (random-uuid))))
        pid (.pid (java.lang.ProcessHandle/current))
        started (current-process-start-date)
        candidate (advertisement "same" pid started)
        observations
        {:seon.fresh-operator/advertisements []
         :seon.fresh-operator/jvms
         [{:seon.fresh-operator/root root
           :seon.fresh-operator/reachable? true
           :seon.fresh-operator/registrations
           [{:seon.fresh-operator/name "same"
             :seon.fresh-operator/root root
             :seon.fresh-operator/advertisement candidate}]}
          {:seon.fresh-operator/root root
           :seon.fresh-operator/reachable? true
           :seon.fresh-operator/registrations
           [{:seon.fresh-operator/name "same"
             :seon.fresh-operator/root root
             :seon.fresh-operator/advertisement
             (assoc candidate :seon.boot/pid (inc pid))}]}]
         :seon.fresh-operator/process-records []
         :seon.fresh-operator/process-record-errors []}
        outcome
        (with-redefs-fn {(bridge-var 'operator-private)
                         (fn [& _] observations)}
          #(try
             ((bridge-var 'read-clj-endpoint) root "same")
             (catch clojure.lang.ExceptionInfo error error)))]
    (is (= :ambiguous-cluster
           (:seon.dev.mcp/failure (ex-data outcome))))
    (is (= 2 (count (:seon.dev.mcp/candidates (ex-data outcome)))))))

(deftest namespace-coordinate-shapes-both-evaluation-modes
  (let [namespace-symbol 'mcp.chosen.namespace
        original-namespace (ns-name *ns*)
        evaluation ((bridge-var 'require-single-clj-form!)
                    "(do (def answer 41) [(inc answer) (ns-name *ns*)])")
        jvm-form ((bridge-var 'remote-evaluation-form)
                  evaluation "jvm" "fixture" namespace-symbol)
        door-form ((bridge-var 'remote-evaluation-form)
                   evaluation "door" "fixture" namespace-symbol)]
    (try
      (is (= [42 namespace-symbol] (eval (read-string jvm-form))))
      (is (= 41 (var-get (ns-resolve namespace-symbol 'answer))))
      (is (str/includes? door-form
                         "[:seon.ns/name (quote mcp.chosen.namespace)]"))
      (is (str/includes? door-form "seon.sci.eval/evaluate"))
      (finally
        (in-ns original-namespace)
        (when (find-ns namespace-symbol)
          (remove-ns namespace-symbol))))))

(deftest root-and-namespace-coordinates-cross-a-real-io-prepl
  (let [root (.getCanonicalPath
              (io/file project-root "tmp" (str "mcp-live-root-"
                                                (random-uuid))))
        cluster "live"
        server-name (symbol (str "mcp-live-" (random-uuid)))
        server (core.server/start-server
                {:name server-name
                 :accept 'clojure.core.server/io-prepl
                 :address "127.0.0.1"
                 :port 0})
        live-advertisement
        (assoc (advertisement cluster
                              (.pid (java.lang.ProcessHandle/current))
                              (current-process-start-date))
               :seon.boot/prepl-port (.getLocalPort server))
        observations
        {:seon.fresh-operator/advertisements
         [{:seon.fresh-operator/name cluster
           :seon.fresh-operator/root root
           :seon.fresh-operator/path (str root "/live/prepl.edn")
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/advertisement live-advertisement}]
         :seon.fresh-operator/jvms []
         :seon.fresh-operator/process-records []
         :seon.fresh-operator/process-record-errors []}
        session-key [root cluster "default"]]
    (try
      (let [result
            (with-redefs-fn {(bridge-var 'operator-private)
                             (fn [& _] observations)}
              #((bridge-var 'execute-clj-eval)
                {:root root
                 :cluster cluster
                 :namespace "mcp.live.chosen"
                 :mode "jvm"
                 :code "[(ns-name *ns*) (+ 40 2)]"}))
            data (result-data result)]
        (is (not (:isError result)) (pr-str data))
        (is (= root (:seon.dev.mcp/root data)))
        (is (= "mcp.live.chosen" (:seon.dev.mcp/namespace data)))
        (is (= "[mcp.live.chosen 42]"
               (get-in data [:seon.dev.mcp/events 0 :val])))
        (is (= "mcp.live.chosen"
               (get-in data [:seon.dev.mcp/events 0 :ns]))))
      (finally
        ((bridge-var 'close-clj-session!) session-key)
        (core.server/stop-server server-name)
        (when (find-ns 'mcp.live.chosen)
          (remove-ns 'mcp.live.chosen))))))

(deftest registrations-use-one-jvm-neutral-server-name
  (let [claude-registration (slurp (io/file project-root ".mcp.json"))
        codex-registration (slurp (io/file project-root ".codex/config.toml"))]
    (is (str/includes? claude-registration "\"seon\""))
    (is (str/includes? claude-registration "bin/mcp-server\""))
    (is (str/includes? codex-registration "[mcp_servers.seon]"))
    (is (str/includes? codex-registration "bin/mcp-server\""))
    (is (not (str/includes? claude-registration "cljs")))
    (is (not (str/includes? codex-registration "cljs")))))

(deftest tool-discovery-advertises-only-live-jvm-operations
  (let [[initialized listed]
        (rpc-responses
         [{:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
          {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}])
        eval-tool (some #(when (= "eval_clj" (:name %)) %)
                        (get-in listed [:result :tools]))]
    (is (= "seon" (get-in initialized [:result :serverInfo :name])))
    (is (= #{"eval_clj" "runtime_status" "get_value"}
           (into #{} (map :name) (get-in listed [:result :tools]))))
    (is (not (contains? (get-in eval-tool [:inputSchema :properties])
                        :max_output_tokens)))
    (is (str/includes? (:description eval-tool)
                       "MUTATES that shared per-cluster ctx"))
    (is (str/includes? (:description eval-tool)
                       "creates NO run or receipts"))
    (is (str/includes? (:description eval-tool)
                       "agent/orchestrator context"))
    (is (= ["jvm" "door"]
           (get-in eval-tool [:inputSchema :properties :mode :enum])))
    (is (contains? (get-in eval-tool [:inputSchema :properties]) :root))
    (is (contains? (get-in eval-tool [:inputSchema :properties]) :namespace))
    (is (str/includes? (get-in eval-tool [:inputSchema :properties :code
                                          :description])
                       "Exactly one Clojure form"))))

(deftest deleted-tool-name-is-an-unknown-tool
  (let [[response]
        (rpc-responses
         [{:jsonrpc "2.0"
           :id 1
           :method "tools/call"
           :params {:name "eval_cljs" :arguments {:code "(+ 1 2)"}}}])
        result (:result response)]
    (is (true? (:isError result)))
    (is (str/includes? (get-in result [:content 0 :text])
                       "Unknown tool: eval_cljs"))))

(deftest deleted-list-sessions-name-is-an-unknown-tool
  (let [[response]
        (rpc-responses
         [{:jsonrpc "2.0"
           :id 1
           :method "tools/call"
           :params {:name "list_sessions" :arguments {}}}])
        result (:result response)]
    (is (true? (:isError result)))
    (is (str/includes? (get-in result [:content 0 :text])
                       "Unknown tool: list_sessions"))))

(deftest parent-watchdog-observes-the-captured-parent-exit
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-parent-" (random-uuid)))
        fifo (io/file fixture-root "mcp-input.fifo")
        child-error (io/file fixture-root "mcp-error.log")
        parent-error (io/file fixture-root "parent-error.log")
        known-paths [fixture-root fifo child-error parent-error]
        child-handle* (atom nil)
        fifo-output* (atom nil)
        parent* (atom nil)]
    (try
      (is (.mkdirs fixture-root))
      (let [mkfifo (.start
                    (doto (ProcessBuilder.
                           ^java.util.List ["mkfifo" (.getPath fifo)])
                      (.directory project-root)))
            completed? (.waitFor mkfifo 5 TimeUnit/SECONDS)]
        (when-not completed? (.destroyForcibly mkfifo))
        (is completed? "mkfifo did not complete.")
        (is (= 0 (when completed? (.exitValue mkfifo)))))
      (let [fifo-output-future (future (io/output-stream fifo))
            helper-form
            (str
             "(require '[clojure.java.io :as io]) "
             "(let [builder (doto (ProcessBuilder. ^java.util.List ["
             (pr-str (.getPath launcher)) "]) "
             "(.directory (java.io.File. "
             (pr-str (.getPath project-root)) ")) "
             "(.redirectInput (java.io.File. " (pr-str (.getPath fifo)) ")) "
             "(.redirectError (java.io.File. "
             (pr-str (.getPath child-error)) "))) "
             "child (.start builder) "
             "reader (io/reader (.getInputStream child))] "
             "(println (.pid child)) (flush) "
             "(println (.readLine reader)) (flush) "
             "(deref (promise)))")
            parent (.start
                    (doto (ProcessBuilder.
                           ^java.util.List
                           ["clojure" "-M:dev:test" "-e" helper-form])
                      (.directory project-root)
                      (.redirectError parent-error)))
            _ (reset! parent* parent)
            parent-reader (io/reader (.getInputStream parent))
            pid-line-future (future (.readLine parent-reader))
            pid-line (deref pid-line-future 10000 ::timeout)
            _ (is (not= ::timeout pid-line)
                  (str "The helper did not report the MCP child pid: "
                       (when (.isFile parent-error) (slurp parent-error))))
            child-pid (when (string? pid-line) (parse-long pid-line))
            child-optional (when child-pid
                             (java.lang.ProcessHandle/of child-pid))
            child-handle (when (and child-optional
                                    (.isPresent child-optional))
                           (.get child-optional))
            fifo-output (deref fifo-output-future 5000 ::timeout)]
        (reset! child-handle* child-handle)
        (is child-handle (str "No child ProcessHandle for " pid-line))
        (is (not= ::timeout fifo-output) "FIFO writer did not attach.")
        (reset! fifo-output* fifo-output)
        (when (and child-handle (not= ::timeout fifo-output))
          (let [fifo-writer (io/writer fifo-output)
                request {:jsonrpc "2.0" :id 1
                         :method "initialize" :params {}}]
            (.write fifo-writer (json/write-str request))
            (.write fifo-writer "\n")
            (.flush fifo-writer)
            (let [response-line-future (future (.readLine parent-reader))
                  response-line (deref response-line-future 5000 ::timeout)]
              (is (not= ::timeout response-line)
                  (str "MCP child did not become ready; log="
                       (when (.isFile child-error) (slurp child-error))))
              (when (string? response-line)
                (is (= "seon"
                       (get-in (json/read-str response-line :key-fn keyword)
                               [:result :serverInfo :name]))))))
          (is (.isAlive parent))
          (is (.isAlive ^java.lang.ProcessHandle child-handle))
          (let [observed-parent (.parent ^java.lang.ProcessHandle child-handle)]
            (is (.isPresent observed-parent))
            (is (= (.pid parent) (.pid (.get observed-parent)))))
          (.destroyForcibly parent)
          (is (.waitFor parent 3 TimeUnit/SECONDS)
              "The helper parent did not exit.")
          (.get (.onExit ^java.lang.ProcessHandle child-handle)
                3 TimeUnit/SECONDS)
          (is (not (.isAlive ^java.lang.ProcessHandle child-handle)))))
      (finally
        (when-let [parent @parent*]
          (reap-process! parent))
        (when-let [child-handle @child-handle*]
          (reap-process-handle! child-handle))
        (when-let [fifo-output @fifo-output*]
          (when-not (= ::timeout fifo-output)
            (.close ^java.io.OutputStream fifo-output)))
        (delete-known-files! known-paths)))
    (is (not (.exists fixture-root))
        "the parent-watchdog fixture root was deleted after exact child exit")))
