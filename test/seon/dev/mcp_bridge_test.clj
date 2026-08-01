(ns seon.dev.mcp-bridge-test
  (:require [clojure.data.json :as json]
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

(deftest runtime-status-leads-with-live-and-collapses-dormant-clusters
  (let [fixture-root (io/file project-root "tmp"
                              (str "mcp-status-" (random-uuid)))
        clusters-root (io/file fixture-root "data" "clusters")
        alive-dir (io/file clusters-root "alive")
        stale-dir (io/file clusters-root "stale")
        invalid-dir (io/file clusters-root "invalid")
        unreadable-dir (io/file clusters-root "unreadable")
        dormant-a-dir (io/file clusters-root "dormant-a")
        dormant-b-dir (io/file clusters-root "dormant-b")
        store-dir (io/file clusters-root "physical-data")
        alive-ad (io/file alive-dir "prepl.edn")
        stale-ad (io/file stale-dir "prepl.edn")
        invalid-ad (io/file invalid-dir "prepl.edn")
        unreadable-ad (io/file unreadable-dir "prepl.edn")
        store-entry (io/file store-dir "00000000.ksv")
        store-lock (io/file (str (.getPath store-dir) ".lock"))
        known-paths [fixture-root (io/file fixture-root "data") clusters-root
                     alive-dir stale-dir invalid-dir unreadable-dir
                     dormant-a-dir dormant-b-dir store-dir
                     alive-ad stale-ad invalid-ad unreadable-ad
                     store-entry store-lock]
        pid (.pid (java.lang.ProcessHandle/current))]
    (try
      (doseq [directory [alive-dir stale-dir invalid-dir unreadable-dir
                         dormant-a-dir dormant-b-dir store-dir]]
        (is (.mkdirs directory)))
      (spit alive-ad (pr-str (advertisement "alive" pid
                                           (current-process-start-date))))
      (spit stale-ad (pr-str (advertisement "stale" pid
                                           (java.util.Date. 0))))
      (spit invalid-ad (pr-str {:not :an-advertisement}))
      (spit unreadable-ad "{")
      (spit store-entry "store data")
      (spit store-lock "")
      (let [result
            (with-redefs-fn {(bridge-var 'project-root)
                             (.getCanonicalPath fixture-root)}
              #((bridge-var 'execute-runtime-status) {}))
            text (get-in result [:content 0 :text])
            lines (str/split-lines text)]
        (is (str/includes? (second lines) "alive state=alive") text)
        (is (str/includes? text "stale state=stale") text)
        (is (str/includes? text "invalid state=invalid") text)
        (is (str/includes? text "unreadable state=unreadable") text)
        (is (str/includes? text
                           "2 clusters with no advertisement: dormant-a, dormant-b")
            text)
        (is (not (str/includes? text "state=missing")) text)
        (is (not (str/includes? text "physical-data")) text))
      (finally
        (delete-known-files! known-paths)))))

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
