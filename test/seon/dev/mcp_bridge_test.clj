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
                             :data {:large "not projected"}
                             :at ['clojure.lang.Compiler 'eval "Compiler.java" 1]}
                            {:type 'java.lang.IllegalStateException
                             :message "The root cause."}]
                      :trace [['clojure.lang.Compiler 'eval "Compiler.java" 1]
                              ['seon.alpha$fail 'invokeStatic "alpha.clj" 20]
                              ['user$eval123 'invokeStatic "NO_SOURCE_FILE" 3]
                              ['repl_context_prototype$door 'invoke "prototype.clj" 44]
                              ['seon.omega$outer 'invokeStatic "omega.clj" 50]]})
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
    (is (= [['seon.alpha$fail 'invokeStatic "alpha.clj" 20]
            ['user$eval123 'invokeStatic "NO_SOURCE_FILE" 3]
            ['repl_context_prototype$door 'invoke "prototype.clj" 44]]
           (:trace value)))
    (is (= 2 (:seon.dev.mcp/frames-omitted value)))
    (is (not (str/includes? (:val projected) "not projected")))))

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
          {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}])]
    (is (= "seon" (get-in initialized [:result :serverInfo :name])))
    (is (= #{"eval_clj" "list_sessions" "runtime_status"}
           (into #{} (map :name) (get-in listed [:result :tools]))))))

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
