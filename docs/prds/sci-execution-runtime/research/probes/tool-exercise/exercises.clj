(ns tool-exercise.exercises
  "The tool-exercise matrix, driven through real runs by `tool-exercise.probe`.

  Every exercise is a vector of agent-authored form sources. The harness
  writes the complete result value to `tmp/tool-exercise/results/<name>.edn`
  so an oversized face never has to enter an agent's context, and returns a
  compact summary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [tool-exercise.probe :as probe])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.file Files Path Paths LinkOption]
           [java.nio.file.attribute FileAttribute]))

(def scratch "/Users/sean/src/seon/tmp/tool-exercise-scratch")
(def results-dir "/Users/sean/src/seon/tmp/tool-exercise/results")

(defn ensure-dirs! []
  (doseq [d [scratch results-dir]]
    (.mkdirs (io/file d)))
  {:scratch scratch :results results-dir})

(defn- token-report
  "Estimated token size of every settled payload in one drive result."
  [result]
  {:eval-result-tokens
   (mapv (fn [r] [(:seon.cluster.eval/ordinal r)
                  (tokens/estimate (str (:seon.cluster.eval/result-edn r)))
                  (:seon.cluster.eval/result-size r)])
         (:evals result))
   :effect-result-tokens
   (mapv (fn [r] [(get-in r [:seon.effect/owner :seon.fn/sym])
                  (tokens/estimate (str (:seon.effect/result-edn r)))
                  (:seon.effect/result-size r)])
         (:effects result))})

(defn- provenance-report
  "Whether every effect receipt carries run identity and ordered identity."
  [result]
  (let [effects (:effects result)]
    {:effect-count (count effects)
     :all-carry-run?
     (every? #(= (:run-id result)
                 (get-in % [:seon.effect/run :seon.cluster.run/id]))
             effects)
     :identities (mapv :seon.effect/id effects)
     :distinct-identities? (apply distinct? (or (seq (map :seon.effect/id effects))
                                                [::none]))
     :all-terminal?
     (every? #(or (:seon.effect/result-edn %) (:seon.effect/interrupted-at %))
             effects)}))

(defn run-exercise!
  "Drive one named exercise and persist its complete result."
  [name sources options]
  (ensure-dirs!)
  (let [agent-id (:agent-id options "ex1")]
    (probe/close-stale! agent-id)
    (let [started (System/nanoTime)
          result (probe/drive! sources (merge {:starting-ns
                                               (symbol (str "my.agents." agent-id))}
                                              options))
          wall (quot (- (System/nanoTime) started) 1000000)]
      (spit (str results-dir "/" name ".edn") (pr-str result))
      (merge {:exercise name
              :wall-ms wall
              :run-id (:run-id result)
              :eval-errors (into [] (keep :seon.cluster.eval/error) (:evals result))}
             (when (:seon.error/kind result) {:refused result})
             (provenance-report result)
             (token-report result)))))

;;; --- local HTTP server, for my.web exercises -------------------------------

(defonce ^:private servers (atom {}))

(defn start-server!
  "Start a local HTTP server whose handlers are slow/large on purpose."
  [port]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
        respond (fn [exchange ^bytes body status]
                  (.sendResponseHeaders exchange (int status) (alength body))
                  (with-open [out (.getResponseBody exchange)]
                    (.write out body))
                  (.close exchange))]
    (.createContext server "/small"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond exchange (.getBytes "<html><body>hello seon</body></html>" "UTF-8") 200))))
    (.createContext server "/slow"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (Thread/sleep 45000)
                        (respond exchange (.getBytes "late" "UTF-8") 200))))
    (.createContext server "/huge"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond exchange
                                 (.getBytes (str "<html><body>"
                                                 (str/join (repeat 400000 "abcdefghij"))
                                                 "</body></html>")
                                            "UTF-8")
                                 200))))
    (.setExecutor server (java.util.concurrent.Executors/newFixedThreadPool 8))
    (.start server)
    (swap! servers assoc port server)
    {:port port :url (str "http://127.0.0.1:" port)}))

(defn stop-server! [port]
  (when-let [server (get @servers port)]
    (.stop ^HttpServer server 0)
    (swap! servers dissoc port)
    {:stopped port}))

;;; --- filesystem fixtures ---------------------------------------------------

(defn make-large-file!
  "Write `mib` mebibytes of ASCII to `path` outside the capability door."
  [path mib]
  (ensure-dirs!)
  (let [chunk (.getBytes (str/join (repeat 1024 "0123456789abcdef")) "UTF-8")
        file (io/file path)]
    (io/make-parents file)
    (with-open [out (io/output-stream file)]
      (dotimes [_ (* mib 64)] (.write out chunk)))
    {:path path :bytes (.length file)}))

(defn make-symlink-tree!
  "A directory containing one real file plus a symlink pointing outside it."
  []
  (ensure-dirs!)
  (let [root (str scratch "/linktree")
        inside (str root "/inside.txt")
        outside (str scratch "/outside-target")
        link (str root "/escape")]
    (.mkdirs (io/file root))
    (.mkdirs (io/file outside))
    (spit inside "inside\n")
    (spit (str outside "/secret.txt") "outside\n")
    (let [link-path (Paths/get link (into-array String []))]
      (when-not (Files/exists link-path
                              (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
        (Files/createSymbolicLink link-path
                                  (Paths/get outside (into-array String []))
                                  (into-array FileAttribute []))))
    {:root root :inside inside :outside outside :link link}))

(defn scratch-listing
  "Every path below the scratch directory with its size, read outside sci."
  []
  (->> (file-seq (io/file scratch))
       (filter #(.isFile ^java.io.File %))
       (mapv (fn [^java.io.File f] [(.getPath f) (.length f)]))
       (sort-by first)
       vec))

(defn process-table
  "Descendant OS processes of this JVM whose command line matches `needle`."
  [needle]
  (->> (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))
       (keep (fn [^java.lang.ProcessHandle h]
               (let [info (.info h)
                     line (str (.orElse (.commandLine info) ""))]
                 (when (str/includes? line needle)
                   {:pid (.pid h) :alive? (.isAlive h) :command line}))))
       vec))
