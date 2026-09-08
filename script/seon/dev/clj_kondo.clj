(ns seon.dev.clj-kondo
  "Native clj-kondo dependency-cache ownership for development tools."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [seon.operator.state :as operator.state]
            [seon.id :as id]
            [seon.dev.state :as state]))

(defn- input-files
  [root]
  (let [reference-root (fs/path root "reference-code")
        exports
        (for [dependency (if (fs/directory? reference-root)
                           (fs/list-dir reference-root)
                           [])
              :let [directory
                    (fs/path dependency "resources/clj-kondo.exports")]
              :when (fs/directory? directory)
              file (fs/glob directory "**/config.edn")]
          file)]
    (->> (concat
          [(fs/path root "deps.edn")
           (fs/path root ".clj-kondo/config.edn")]
          exports)
       (filter fs/regular-file?)
       (sort-by str)
       vec)))

(defn- input-digest
  [root classpath]
  (let [project-paths (into #{}
                            (map #(str (fs/real-path (fs/path root %))))
                            (:paths (edn/read-string
                                     (slurp (str (fs/path root "deps.edn"))))))
        dependency-files
        (mapcat
         (fn [entry]
           (let [path (if (fs/absolute? entry)
                        (fs/path entry)
                        (fs/path root entry))
                 path (if (fs/exists? path) (fs/real-path path) path)]
             (cond
               (project-paths (str path)) []
               (fs/regular-file? path) [path]
               (fs/directory? path)
               (sort-by str (mapcat #(fs/glob path %)
                                    ["**/*.clj" "**/*.cljc" "**/*.cljs"]))
               :else [])))
         (enumeration-seq
          (java.util.StringTokenizer. classpath java.io.File/pathSeparator)))]
    (id/sha-256
     (cons (.getBytes classpath "UTF-8")
           (mapcat (fn [path] [(.getBytes (str path) "UTF-8")
                              (fs/read-all-bytes path)])
                   (distinct (concat (input-files root) dependency-files)))))))

(defn- state-path
  [root]
  (fs/path root "tmp/test-changed/dependency-cache.edn"))

(def ^:private subprocess-deadline-ms 300000)

(defn- cache-contents
  [root]
  (let [directory (fs/path root ".clj-kondo/.cache")]
    (into (sorted-map)
          (for [file (when (fs/directory? directory)
                       (fs/glob directory "**/*.transit.json"))
                :when (fs/regular-file? file)]
            [(str (fs/relativize directory file))
             (id/sha-256 [(fs/read-all-bytes file)])]))))

(defn ensure-dependency-cache!
  "Populate dependency analysis for the resolved classpath. A recorded input
   digest is current only while every recorded cache entry still matches."
  [root]
  (try
    (let [classpath-result
          (operator.state/run-process!
           {:seon.operator.subprocess/argv ["clojure" "-Spath"]
            :seon.operator.subprocess/directory root
            :seon.operator.subprocess/deadline-ms subprocess-deadline-ms})
          classpath (str/trim (:seon.operator.subprocess/output classpath-result))]
      (when (or (not (zero? (:seon.operator.subprocess/exit classpath-result)))
                (str/blank? classpath))
        (throw (ex-info "Could not derive the project classpath" classpath-result)))
      (let [digest (input-digest root classpath)
            recorded (state/read-edn (state-path root))
            contents (cache-contents root)]
        (if (and (= digest (::input-digest recorded))
                 (seq (::contents recorded))
                 (= contents (::contents recorded)))
          {::status :current}
          (let [result
                (operator.state/run-process!
                 {:seon.operator.subprocess/argv
                  ["clj-kondo" "--lint" classpath "--parallel" "--copy-configs"
                   "--skip-lint" "--config" "{:output {:analysis true}}"]
                  :seon.operator.subprocess/directory root
                  :seon.operator.subprocess/deadline-ms subprocess-deadline-ms})
                contents (cache-contents root)]
            ;; Without --dependencies, stale jar skip markers cannot suppress
            ;; parsing. Explicit analysis enables cache publication with skip-lint.
            (when (or (not (zero? (:seon.operator.subprocess/exit result)))
                      (empty? contents))
              (throw (ex-info "Dependency analysis did not populate its cache" result)))
            (state/write-edn! (state-path root)
                              {::input-digest digest ::contents contents})
            {::status :warmed}))))
    (catch Exception error
      {::status :unavailable ::reason (.getMessage error)})))
