(ns seon.dev.mcp-bridge-test
  (:require [clojure.data.json :as json]
            [clojure.core.server :as core.server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io PushbackReader]
           [java.util.concurrent TimeUnit]))

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

(deftest evaluation-exceptions-keep-only-model-legible-frames
  (let [event {:tag :ret
               :val (pr-str
                     {:cause "The root cause."
                      :phase :execution
                      :via [{:type 'clojure.lang.ExceptionInfo
                             :message "The wrapper."
                             :data {:opaque (Object.)}
                             :at ['clojure.lang.Compiler 'eval "Compiler.java" 1]}
                            {:type 'java.lang.IllegalStateException
                             :message "The root cause."}]
                      :trace [['clojure.lang.Compiler 'eval "Compiler.java" 1]
                              ['seon.dev.mcp_bridge_test$fail
                               'invokeStatic "mcp_bridge_test.clj" 20]
                              ['user$eval123 'invokeStatic "NO_SOURCE_FILE" 3]
                              ['seon.dev.mcp_bridge_test$outer
                               'invokeStatic "mcp_bridge_test.clj" 44]
                              ['seon.dev.mcp_bridge_test$fourth
                               'invokeStatic "mcp_bridge_test.clj" 50]]})
               :ns "user"
               :ms 7
               :form "(fail)"
               :exception true}
        projected ((bridge-var 'project-exception-event) event)
        value (read-string (:val projected))]
    (is (= (dissoc event :val) (dissoc projected :val))
        "The prepl tag/namespace/timing/form/exception vocabulary survives.")
    (is (= "The root cause." (:cause value)))
    (is (= :execution (:phase value)))
    (is (= [{:type 'clojure.lang.ExceptionInfo :message "The wrapper."}
            {:type 'java.lang.IllegalStateException
             :message "The root cause."}]
           (:via value)))
    (is (= [['seon.dev.mcp_bridge_test$fail
             'invokeStatic "mcp_bridge_test.clj" 20]
            ['user$eval123 'invokeStatic "NO_SOURCE_FILE" 3]
            ['seon.dev.mcp_bridge_test$outer
             'invokeStatic "mcp_bridge_test.clj" 44]]
           (:trace value)))
    (is (= 2 (:seon.dev.mcp/frames-omitted value)))
    (is (not (str/includes? (:val projected) "java.lang.Object")))))

(deftest compiled-agent-namespace-frames-derive-from-source-provenance
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-frame-" (random-uuid)))
        source-root (io/file fixture-root "src")
        my-root (io/file source-root "my")
        agents-root (io/file my-root "agents")
        source (io/file agents-root "mcp_frame_fixture.clj")
        known-paths [fixture-root source-root my-root agents-root source]
        namespace-symbol 'my.agents.mcp-frame-fixture]
    (try
      (is (.mkdirs agents-root))
      (spit source
            (str "(ns my.agents.mcp-frame-fixture)\n"
                 "(defn explode [] (throw (ex-info \"agent boom\" {})))\n"))
      (load-file (.getPath source))
      (let [throwable (try
                        ((ns-resolve namespace-symbol 'explode))
                        nil
                        (catch Throwable error error))
            event {:tag :ret
                   :val (pr-str (Throwable->map throwable))
                   :ns "user"
                   :form "(my.agents.mcp-frame-fixture/explode)"
                   :exception true}
            projected
            (with-redefs-fn {(bridge-var 'project-root)
                             (.getCanonicalPath fixture-root)}
              #((bridge-var 'project-exception-event) event))
            trace (:trace (read-string (:val projected)))]
        (is (some #(str/starts-with? (str (first %))
                                     "my.agents.mcp_frame_fixture$")
                  trace))
        (is (every? #(or (str/starts-with? (str (first %))
                                         "my.agents.mcp_frame_fixture$")
                         (str/starts-with? (str (first %)) "user$"))
                    trace)
            (pr-str trace)))
      (finally
        (when (find-ns namespace-symbol) (remove-ns namespace-symbol))
        (delete-known-files! known-paths)))))

(deftest oversized-event-values-trim-in-place-largest-first
  (let [payload {:seon.dev.mcp/runtime "clj"
                 :seon.dev.mcp/cluster "default"
                 :seon.dev.mcp/session-id "trim-test"
                 :seon.dev.mcp/events
                 [{:tag :out :val (apply str (repeat 700 "x"))}
                  {:tag :ret :val (apply str (repeat 100 "y"))
                   :ns "user" :ms 3 :form "(large-value)"}]}
        encoded (with-bindings {(bridge-var '*requested-output-tokens*) 128}
                  ((bridge-var 'content-text) payload))
        decoded (json/read-str encoded :key-fn keyword)
        [large terminal] (:seon.dev.mcp/events decoded)]
    (is (= "clj" (:seon.dev.mcp/runtime decoded)))
    (is (= "default" (:seon.dev.mcp/cluster decoded)))
    (is (= "trim-test" (:seon.dev.mcp/session-id decoded)))
    (is (= {:tag "ret" :ns "user" :ms 3 :form "(large-value)"}
           (select-keys terminal [:tag :ns :ms :form])))
    (is (= (apply str (repeat 100 "y")) (:val terminal))
        "The smaller member survives byte-for-byte.")
    (is (true? (:seon.dev.mcp/truncated? large)))
    (is (= 700 (:seon.dev.mcp/total-chars large)))
    (is (= (count (:val large))
           (:seon.dev.mcp/retained-chars large)))
    (is (not (contains? decoded :seon.dev.mcp/preview)))
    (is (<= (count encoded) (* 4 128)))))

(deftest minimum-output-budget-preserves-the-envelope
  (let [prefix "quoted=\" slash=\\ newline=\n "
        payload {:seon.dev.mcp/runtime "clj"
                 :seon.dev.mcp/cluster "default"
                 :seon.dev.mcp/session-id "minimum-test"
                 :seon.dev.mcp/events
                 [{:tag :ret
                   :val (str prefix (apply str (repeat 1000 "z")))
                   :ns "user" :ms 9 :form "(large-value)"
                   :exception true}]}
        encoded (with-bindings {(bridge-var '*requested-output-tokens*) 64}
                  ((bridge-var 'content-text) payload))
        decoded (json/read-str encoded :key-fn keyword)
        event (first (:seon.dev.mcp/events decoded))]
    (is (= "clj" (:seon.dev.mcp/runtime decoded)))
    (is (= "default" (:seon.dev.mcp/cluster decoded)))
    (is (= "minimum-test" (:seon.dev.mcp/session-id decoded)))
    (is (= {:tag "ret" :ns "user" :ms 9 :form "(large-value)"
            :exception true}
           (select-keys event [:tag :ns :ms :form :exception])))
    (is (string? (:val event)))
    (is (true? (:seon.dev.mcp/truncated? event)))
    (is (= (+ (count prefix) 1000)
           (:seon.dev.mcp/total-chars event)))
    (is (= (count (:val event))
           (:seon.dev.mcp/retained-chars event)))
    (is (not (contains? decoded :seon.dev.mcp/preview)))))

(deftest oversized-terminal-fields-stay-inside-the-structured-budget
  (let [huge-form (apply str (repeat 20000 "f"))
        huge-error (apply str (repeat 20000 "e"))
        payload {:seon.dev.mcp/runtime "clj"
                 :seon.dev.mcp/cluster "default"
                 :seon.dev.mcp/session-id "terminal-field-test"
                 :seon.dev.mcp/error huge-error
                 :seon.dev.mcp/events
                 [{:tag :ret :val "refusal" :ns "user" :ms 4
                   :form huge-form :exception true}]}
        limit (* 4 128)
        encoded (with-bindings {(bridge-var '*requested-output-tokens*) 128}
                  ((bridge-var 'content-text) payload))
        decoded (json/read-str encoded :key-fn keyword)
        terminal (first (:seon.dev.mcp/events decoded))]
    (is (<= (count encoded) limit) (str (count encoded) " > " limit))
    (is (= {:tag "ret" :ns "user" :ms 4 :exception true}
           (select-keys terminal [:tag :ns :ms :exception])))
    (is (contains? terminal :form))
    (is (= (count (:form terminal))
           (first (get-in terminal
                          [:seon.dev.mcp/truncated-fields :form]))))
    (is (= (count huge-form)
           (second (get-in terminal
                           [:seon.dev.mcp/truncated-fields :form]))))
    (is (contains? decoded :seon.dev.mcp/error))
    (is (= (count huge-error)
           (second (get-in decoded
                           [:seon.dev.mcp/truncated-fields
                            :seon.dev.mcp/error]))))))

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

(deftest runtime-status-derives-live-stale-invalid-and-degraded-rows
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-status-" (random-uuid)))
        root (.getCanonicalPath fixture-root)
        pid (.pid (java.lang.ProcessHandle/current))
        started (current-process-start-date)
        alive (advertisement "alive" pid started)
        stale (advertisement "stale" pid (java.util.Date. 0))
        degraded (advertisement "degraded" pid started)
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
           :seon.fresh-operator/advertisement degraded}]
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
        result
        (with-redefs-fn {(bridge-var 'operator-private)
                         (fn [var-symbol & _]
                           (case var-symbol
                             source-observations observations
                             prepl-value! {"alive" true
                                           "degraded" false}))}
          #((bridge-var 'execute-runtime-status) {:root root}))
        text (get-in result [:content 0 :text])
        lines (str/split-lines text)]
    (is (str/includes? (second lines) "alive state=alive") text)
    (is (str/includes? text "degraded state=degraded") text)
    (is (str/includes? text "cluster-layer=degraded") text)
    (is (str/includes? text "stale state=stale") text)
    (is (str/includes? text "invalid state=invalid") text)
    (is (str/includes? text (str "under " root)) text)))

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
         [{:seon.dev.process/pid pid}]
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
    (is (= #{"eval_clj" "list_sessions" "runtime_status"}
           (into #{} (map :name) (get-in listed [:result :tools]))))
    (is (str/includes? (:description eval-tool) "max_output_tokens"))
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
          (when (.isAlive ^Process parent) (.destroyForcibly ^Process parent)))
        (when-let [child-handle @child-handle*]
          (when (.isAlive ^java.lang.ProcessHandle child-handle)
            (.destroyForcibly ^java.lang.ProcessHandle child-handle)))
        (when-let [fifo-output @fifo-output*]
          (when-not (= ::timeout fifo-output)
            (.close ^java.io.OutputStream fifo-output)))
        (delete-known-files! known-paths)))))
