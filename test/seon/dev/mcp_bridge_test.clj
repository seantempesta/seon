(ns seon.dev.mcp-bridge-test
  (:require [clojure.data.json :as json]
            [clojure.core.server :as core.server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.cluster]
            [seon.config]
            [seon.test-support]
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

(deftest jvm-mode-reads-through-the-one-reader
  ;; The bridge holds no reader: the JVM form reads with `seon.sci.reader/read`
  ;; in the target namespace and throws the reader's own refusal.
  (let [namespace-symbol (symbol (str "mcp.reader-" (random-uuid)))
        original (ns-name *ns*)
        run (fn [source]
              (try (eval (read-string ((bridge-var 'jvm-evaluation-form) source namespace-symbol true)))
                   (catch Throwable thrown
                     (let [cause (or (ex-cause thrown) thrown)]
                       [(ex-message cause) (ex-data cause)]))))]
    (try
      (is (= ["Evaluation accepts exactly one form; the second form starts at line 2, column 3. Wrap sequences in `(do ...)`."
              {:seon.sci.reader/line 2 :seon.sci.reader/column 3 :seon.sci.reader/source "{:a 1}"}]
             (run "(+ 1 2)\r\n  {:a 1}")))
      (is (= :seon.sci.reader/form (:seon.error/member (second (run "(+ 1 2")))))
      (is (= "Your reply had no form; only comments/prose. Send a form." (first (run "  ;; only"))))
      (is (= [(keyword (str namespace-symbol) "k") '(clojure.core/map java.lang.String)]
             (run "[::k `(map String)]"))
          "auto-resolved keywords and syntax-quote resolve in the target namespace")
      (is (= :invalid-form (:seon.dev.mcp/failure (ex-data (try ((bridge-var 'source!) 1)
                                                                 (catch clojure.lang.ExceptionInfo e e)))))
          "a non-string never crosses")
      (finally (in-ns original) (when (find-ns namespace-symbol) (remove-ns namespace-symbol))))))

(defn- write-advertisement!
  "Advertise `value` the way a cluster does: data/clusters/<dir>/prepl.edn."
  [root dir value]
  (let [file (io/file root "data/clusters" dir "prepl.edn")]
    (io/make-parents file)
    (spit file (pr-str value))
    file))

(defn- delete-fixture-root!
  [^java.io.File root]
  (doseq [file (reverse (file-seq root))
          :when (not (java.nio.file.Files/isSymbolicLink (.toPath ^java.io.File file)))]
    (.delete ^java.io.File file)))

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
            (try
              ((bridge-var 'read-clj-endpoint)
               (.getCanonicalPath fixture-root) "fixture")
              (catch clojure.lang.ExceptionInfo error error))]
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

(deftest discovery-reports-only-live-advertisements-and-observes-each
  ;; A dead process's advertisement is absent; an unreachable live one is
  ;; unknown; a reachable one whose cluster layer is not running is degraded.
  (let [fixture-root (io/file project-root "tmp" (str "mcp-status-" (random-uuid)))
        root (.getCanonicalPath (doto fixture-root .mkdirs))
        pid (.pid (java.lang.ProcessHandle/current))
        started (current-process-start-date)
        server-name (str "mcp-status-" (random-uuid))
        server (core.server/start-server {:name server-name :accept 'clojure.core.server/io-prepl
                                          :address "127.0.0.1" :port 0})]
    (try
      (write-advertisement! root "reachable" (assoc (advertisement "reachable" pid started)
                                                    :seon.boot/prepl-port (.getLocalPort server)))
      (write-advertisement! root "unreachable" (assoc (advertisement "unreachable" pid started)
                                                      :seon.boot/prepl-port 1))
      (write-advertisement! root "stale" (advertisement "stale" pid (java.util.Date. 0)))
      (let [by-cluster (into {} (map (juxt :seon.dev.mcp/cluster :seon.dev.mcp/state))
                             ((bridge-var 'discovery-rows) root))]
        (is (= {"reachable" :degraded "unreachable" :unknown} by-cluster)))
      (finally
        (core.server/stop-server server-name)
        (delete-fixture-root! fixture-root)))))
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
             :mode "sci"
             :session_id "coordinates"
             :code "(+ 1 2)"}))
        data (result-data result)]
    (is (true? (:isError result)))
    (is (= "sci" (:seon.dev.mcp/mode data)))
    (is (= "mcp.fixture.target" (:seon.dev.mcp/namespace data)))
    (is (= "fixture" (:seon.dev.mcp/cluster data)))
    (is (= "coordinates" (:seon.dev.mcp/session-id data)))
    (is (= "(+ 1 2)" (:seon.dev.mcp/form data)))
    (is (= "transport-fixture" (:seon.dev.mcp/failure data)))))

(deftest evaluation-response-reports-the-exact-caller-source-once
  (let [source "  (+ 20 22)\n"]
    (doseq [mode ["jvm" "sci"]
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
        (is (= source (:seon.dev.mcp/form data)))
        (is (every? #(not (contains? % :form)) events)
            (str mode " events retain their prepl structure on "
                 (if exception? "evaluation error" "success")))))))

(deftest duplicate-live-cluster-identities-fail-with-candidates
  (let [fixture-root (io/file project-root "tmp" (str "mcp-ambiguous-" (random-uuid)))
        root (.getCanonicalPath (doto fixture-root .mkdirs))
        candidate (assoc (advertisement "same" (.pid (java.lang.ProcessHandle/current))
                                        (current-process-start-date))
                         :seon.boot/prepl-port 1)]
    (try
      (write-advertisement! root "same" candidate)
      (write-advertisement! root "same-copy" candidate)
      (let [outcome (try ((bridge-var 'read-clj-endpoint) root "same")
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (= :ambiguous-cluster (:seon.dev.mcp/failure (ex-data outcome))))
        (is (= 2 (count (:seon.dev.mcp/candidates (ex-data outcome))))))
      (finally (delete-fixture-root! fixture-root)))))
(deftest namespace-coordinate-shapes-both-evaluation-modes
  (let [namespace-symbol 'mcp.chosen.namespace
        original-namespace (ns-name *ns*)
        evaluation ((bridge-var 'source!)
                    "(do (def answer 41) [(inc answer) (ns-name *ns*)])")
        jvm-form ((bridge-var 'remote-evaluation-form)
                  evaluation "jvm" "fixture" namespace-symbol)
        sci-form ((bridge-var 'remote-evaluation-form)
                   (assoc evaluation :seon.dev.mcp/namespace? true) "sci" "fixture" namespace-symbol)]
    (try
      (is (= [42 namespace-symbol] (eval (read-string jvm-form))))
      (is (= 41 (var-get (ns-resolve namespace-symbol 'answer))))
      (is (str/includes? sci-form
                         "[:seon.ns/name (quote mcp.chosen.namespace)]"))
      (is (str/includes? sci-form "seon.cluster.agent/evaluate-source!")
          "SCI without a branch is one ordinary run of root, not a bridge evaluator")
      (is (str/includes? sci-form "seon.cluster.reply/code-sources"))
      (finally
        (in-ns original-namespace)
        (when (find-ns namespace-symbol)
          (remove-ns namespace-symbol))))))

(deftest root-and-namespace-coordinates-cross-a-real-io-prepl
  (let [fixture-root (io/file project-root "tmp" (str "mcp-live-root-" (random-uuid)))
        root (.getCanonicalPath (doto fixture-root .mkdirs))
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
        session-key [root cluster "default"]]
    (try
      (let [result
            (do (write-advertisement! root cluster live-advertisement)
                ((bridge-var 'execute-clj-eval)
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
        (delete-fixture-root! fixture-root)
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
    (is (= #{"eval_clj" "runtime_status" "get_value" "branch"}
           (into #{} (map :name) (get-in listed [:result :tools]))))
    (is (not (contains? (get-in eval-tool [:inputSchema :properties])
                        :max_output_tokens)))
    (is (str/includes? (:description eval-tool)
                       "ONE ORDINARY RUN OF ROOT")
        "branchless SCI is described as the agent run it is")
    (is (not (str/includes? (:description eval-tool) "MUTATES the cluster's shared SCI ctx"))
        "the retired shared-context evaluation is no longer advertised")
    (is (str/includes? (:description eval-tool)
                       "agent/orchestrator context"))
    (is (str/includes? (:description eval-tool)
                       "(seon.cluster.boot/connection \"default\")"))
    (is (= ["jvm" "sci"]
           (get-in eval-tool [:inputSchema :properties :mode :enum])))
    (is (contains? (get-in eval-tool [:inputSchema :properties]) :root))
    (is (contains? (get-in eval-tool [:inputSchema :properties]) :namespace))
    (is (str/includes? (get-in eval-tool [:inputSchema :properties :code
                                          :description])
                       "SCI without a branch evaluates every form in order as one run"))))

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

(clojure.test/deftest read-only-intent-crosses-both-evaluation-forms
  (require 'seon.operator)
  (let [remote (bridge-var 'remote-evaluation-form)
        consume (ns-resolve 'seon.cluster 'consume-mcp-projection!)
        mark seon.cluster/project-next-prepl-value!]
    (doseq [mode ["jvm" "sci"] intent [true false]
            :let [source (remote {:seon.dev.mcp/form '(+ 1 2)
                                  :seon.dev.mcp/source "(+ 1 2)"
                                  :seon.dev.mcp/read-only? intent}
                                 mode "default" 'user)
                  form (read-string source)]]
      (eval (second form))
      (clojure.test/is (= {:seon.dev.mcp/read-only? intent}
                         (select-keys (consume) [:seon.dev.mcp/read-only?]))))
    (mark)))

(clojure.test/deftest discovery-census-returns-an-unprojected-observation
  ;; The census asks `:seon.dev.mcp/project? false`; a projected return would
  ;; wrap the observation and read as degraded for a running cluster.
  (let [cluster (first (sort (keys @@(ns-resolve 'seon.cluster (symbol "running-instances")))))
        fixture-root (io/file project-root "tmp" (str "mcp-census-" (random-uuid)))
        root (.getCanonicalPath (doto fixture-root .mkdirs))
        server-name (str "mcp-census-" (random-uuid))
        server (clojure.core.server/start-server
                {:name server-name :accept 'seon.cluster/mcp-io-prepl
                 :args [cluster seon.config/defaults]
                 :address "127.0.0.1" :port 0})]
    (try
      (is (some? cluster) "a running cluster answers the census")
      (write-advertisement! root cluster (assoc (advertisement cluster (.pid (java.lang.ProcessHandle/current))
                                                               (current-process-start-date))
                                                :seon.boot/prepl-port (.getLocalPort server)))
      (is (= [:alive] (mapv :seon.dev.mcp/state ((bridge-var 'discovery-rows) root))))
      (finally
        (clojure.core.server/stop-server server-name)
        (delete-fixture-root! fixture-root)))))

(deftest runtime-status-prints-the-observations-declared-ai-face
  ;; MCP runtime_status and `bin/seon status` print one face: the observation's
  ;; declared `seon.cluster/runtime-observation-ai`, never the whole value.
  (let [cluster (first (sort (keys @@(ns-resolve 'seon.cluster (symbol "running-instances")))))
        fixture-root (io/file project-root "tmp" (str "mcp-face-" (random-uuid)))
        root (.getCanonicalPath (doto fixture-root .mkdirs))
        server-name (str "mcp-face-" (random-uuid))
        server (clojure.core.server/start-server
                {:name server-name :accept 'seon.cluster/mcp-io-prepl
                 :args [cluster seon.config/defaults]
                 :address "127.0.0.1" :port 0})]
    (try
      (is (some? cluster) "a running cluster answers runtime_status")
      (write-advertisement! root cluster (assoc (advertisement cluster (.pid (java.lang.ProcessHandle/current))
                                                               (current-process-start-date))
                                                :seon.boot/prepl-port (.getLocalPort server)))
      (let [face ((bridge-var 'runtime-observation) root cluster)]
        (is (= cluster (:seon.dev.mcp/cluster face)) (pr-str face))
        (is (#{:observed :faulted :unknown} (:seon.dev.mcp/health face)))
        (is (not-any? #(contains? face %) [:seon.dev.mcp/open-fault-count
                                            :seon.dev.mcp/replaced-root-count
                                            :seon.dev.mcp/profile])
            "the face names totals inside each family, not the whole value's members")
        (when-let [faults (:seon.dev.mcp/open-faults face)]
          (is (<= (count (:seon.dev.mcp/top faults)) 5))
          (is (not-any? :seon.error/message (:seon.dev.mcp/top faults)))))
      (testing "discovery reads health alone and still classifies the live cluster"
        (is (= [:alive] (mapv :seon.dev.mcp/state ((bridge-var 'discovery-rows) root)))))
      (finally
        (clojure.core.server/stop-server server-name)
        (delete-fixture-root! fixture-root)))))

(deftest an-unreadable-prepl-value-keeps-its-raw-text-beside-the-reader-error
  (let [event {:tag :ret :val "#object[java.lang.Object 0x1]" :ns "user"}
        decoded ((bridge-var 'decoded-projection-event) event)]
    (is (= "#object[java.lang.Object 0x1]" (:val decoded)) "the raw text is never replaced")
    (is (string? (:seon.dev.mcp/reader-error decoded)) "the reader's failure rides beside it")))

(deftest a-lane-owns-its-branch-create-define-call-retire
  ;; The wanted behaviour: a lane's hosted agent keeps its defs across runs on
  ;; its branch, and retiring it leaves the cluster's context and roster untouched.
  (let [instances @@(ns-resolve 'seon.cluster (symbol "running-instances"))
        cluster (first (sort (keys instances)))
        store (:seon.store/store (get instances cluster))
        roster (:seon.agent/routing (:seon.turn.loop/cluster (get instances cluster)))
        consume (ns-resolve 'seon.cluster 'consume-mcp-projection!)
        branch (str "lane-mcp-test-" (random-uuid))
        ;; A fresh name per run: a symbol this file spells would be reached by
        ;; this very test, and the defn's entry gate would run it inside itself.
        probe (str "lane-probe-" (subs (str (random-uuid)) 0 8))
        run (fn [action b] (eval (read-string ((bridge-var 'branch-form) cluster action b))))
        sci (fn [source & [on-branch]]
              ;; The prepl evaluates the bridge's form with no custody bound; a test
              ;; member's custody would refuse the hosted branch's writes.
              (let [form ((bridge-var 'run-form) source cluster nil false on-branch)
                    result (try ((requiring-resolve 'seon.db/call-with-custody) {} #(eval (read-string form)))
                                (finally (consume)))]
                (or (-> result :seon.dev.mcp/evaluations last :seon.eval/shown)
                    (:seon.error/message result))))
        held #(some? (get-in @roster [:seon.agent/children (keyword branch)]))]
    (is (some? store) "a running cluster supplies the store")
    (try
      (is (:seon.cluster/created? (run "create" branch)))
      (is (:seon.error/message (run "create" branch)) "an existing name refuses; never shared silently")
      (is (= (str "#'user/" probe)
             (sci (str "(defn " probe " \"Add 41.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 41))") branch)))
      (is (= "42" (sci (str "(" probe " 1)") branch)) "the hosted agent's next run on the branch calls the def")
      (is (nil? ((requiring-resolve 'sci.core/resolve) (:seon.sci.eval/ctx (get instances cluster)) (symbol "user" probe)))
          "the shared context never sees it")
      (is (contains? (set (:seon.dev.mcp/branches (run "list" nil))) (keyword branch)))
      (is (:seon.dev.mcp/retired? (run "retire" branch)))
      (is (not (held)) "retire released the host's execution")
      (is (not (contains? (set (:seon.dev.mcp/branches (run "list" nil))) (keyword branch))))
      (is (:seon.error/message (run "retire" (name (get-in @(:seon.db/connection (:seon.turn.loop/cluster (get instances cluster))) [:config :branch]))))
          "the cluster's own branch refuses")
      (finally
        (run "retire" branch)))))

(deftest results-carry-the-printed-value-and-the-authored-form
  ;; Keywords and symbols read as themselves, name metadata crosses to the
  ;; JVM, and a branch reply carries its value, never the generated form.
  ;; The bridge reprints nothing: SCI's shown text is the value verbatim, and
  ;; a JVM return keeps the bytes the cluster printed.
  (let [shown (bridge-var 'shown-event)
        jvm {:tag :ret :val (pr-str {:seon.dev.mcp/value {:k :a.b/c :s 'd/e}})}]
    (is (= {:tag :ret :val "{:k :a.b/c, :s \"x\"}" :seon.sci.eval/ending-ns 'user}
           (shown {:tag :ret :val (pr-str {:seon.dev.mcp/value {:seon.sci.eval/ending-ns 'user
                                                                :seon.dev.mcp/text "{:k :a.b/c, :s \"x\"}"}})})))
    (is (= jvm (shown jvm)))
    (is (= {:tag :out :val "o"} (shown {:tag :out :val "o"}))))
  (let [namespace-symbol (symbol (str "mcp.meta-" (random-uuid)))
        original (ns-name *ns*)
        source "(do (defn ^{:probe/x 1} f \"doc\" [x] x) (:probe/x (meta #'f)))"
        form ((bridge-var 'remote-evaluation-form)
              ((bridge-var 'source!) source) "jvm" "fixture" namespace-symbol)]
    (try
      (is (= 1 (eval (read-string form))))
      (finally (in-ns original) (remove-ns namespace-symbol))))
  (let [instances @@(ns-resolve 'seon.cluster (symbol "running-instances"))
        cluster (first (sort (keys instances)))
        data (result-data ((bridge-var 'execute-branch)
                                  {:root @(bridge-var 'project-root) :cluster cluster :action "list"}))]
    (is (vector? (:seon.dev.mcp/branches (read-string (:seon.dev.mcp/value data)))) (pr-str data))
    (is (not (contains? data :seon.dev.mcp/events)))))

(deftest a-new-branch-window-opens-at-its-fork-basis
  ;; Requirement 8.3: a new branch's first evaluation shows none of the errors
  ;; it inherited; one written on it afterwards shows once, then never again.
  ;; The inherited error is the test's own: it is written on a lane branch P the
  ;; bridge created, and the window is read on a child C forked from P.
  (let [instances @@(ns-resolve 'seon.cluster (symbol "running-instances"))
        cluster (first (sort (keys instances)))
        store (:seon.store/store (get instances cluster))
        ;; the managed root the bridge resolves, never the program directory
        root (.getCanonicalPath (io/file @(bridge-var 'project-root)))
        parent (str "lane-mcp-window-" (random-uuid))
        child (keyword (str parent "-child"))
        consume (ns-resolve 'seon.cluster 'consume-mcp-projection!)
        window (fn [since]
                 (try (eval (read-string ((bridge-var 'window-form) cluster (name child) since)))
                      (finally (consume))))
        operations #(set (map :seon.error/operation (:seon.dev.mcp/errors %)))
        execute #((bridge-var 'execute-branch) {:root root :cluster cluster :action % :name parent})
        call (fn [sym & args] (apply (requiring-resolve sym) args))
        commit-error! (fn [connection operation]
                        (call 'seon.db/call-with-custody
                              {:seon.db/connection connection}
                              #(seon.test-support/transacted!
                                connection
                                (call 'seon.error/commit-tx (call 'seon.db/db connection)
                                      {:seon.schema/projection (call 'seon.schema/handed-projection)
                                       :seon.error/source {:seon.error/at (java.util.Date.) :seon.error/layer ::probe
                                                           :seon.error/operation operation :seon.error/message "A window probe."}
                                       :seon.error/declared-schema :seon.error/normalization-error
                                       :seon.error/id (str (random-uuid)) :seon.error/at (java.util.Date.)
                                       :seon.error/process "mcp-window-4242-1753650000000"
                                       :seon.sci.admit/caps (seon.config/result-caps seon.config/defaults)
                                       :seon.config.error/recurrence-limit 100
                                       :seon.config.error/max-evidence-bytes 16384}))))
        ;; Each branch is acquired through the agent entrance, which carries its
        ;; projection for the writer; the bridge's retire releases P's.
        acquire! #(call 'seon.cluster.agent/acquire-context!
                        (assoc (:seon.turn.loop/cluster (get instances cluster)) :seon.store/store store)
                        nil {:seon.agent/branch %})
        parent-connection (atom nil)
        child-execution (atom nil)
        child-connection #(:seon.db/connection @child-execution)]
    (try
      (is (not (:isError (execute "create"))))
      (is (int? (get @@(bridge-var 'window-bases) [root cluster parent]))
          "create records the fork basis for the branch's window")
      (reset! parent-connection (:seon.db/connection (acquire! (keyword parent))))
      (commit-error! @parent-connection `inherited)
      (call 'seon.cluster.registry/branch!
            {:seon.store/store store :seon.store/branch child
             :seon.cluster.registry/from (call 'seon.db/commit-id (call 'seon.db/db @parent-connection))})
      (reset! child-execution (acquire! child))
      (let [fork (call 'seon.db/basis-t (call 'seon.db/db (child-connection)))
            first-window (window fork)]
        (is (= #{`inherited} (set (map :seon.error/operation
                                        (call 'seon.error/occurred-since (call 'seon.db/db (child-connection)) 0))))
            "the child inherits the parent's error, so a window from 0 would list it")
        (is (= 0 (:seon.dev.mcp/count first-window)) (pr-str first-window))
        (commit-error! (child-connection) `probe)
        (let [second-window (window (:seon.db/basis-t first-window))]
          (is (= #{`probe} (operations second-window)) (pr-str second-window))
          (is (= 0 (:seon.dev.mcp/count (window (:seon.db/basis-t second-window))))
              "the next window does not repeat it")))
      (finally
        (some->> @child-execution (call 'seon.cluster.agent/release-context!))
        (call 'seon.cluster.registry/retire-branch! {:seon.store/store store :seon.store/branch child})
        (execute "retire")
        (is (nil? (get @@(bridge-var 'window-bases) [root cluster parent])))))))
