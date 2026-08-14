(ns seon.dev.clj-kondo
  "Native clj-kondo dependency-cache ownership for development tools."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [seon.operator.state :as operator.state]
            [seon.dev.state :as state])
  (:import [java.security MessageDigest]))

(defn- cache-present?
  [root]
  (let [directory (fs/path root ".clj-kondo/.cache/v1")]
    (and (fs/directory? directory)
         (seq (fs/list-dir directory)))))

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
  [root]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [path (input-files root)]
      (.update digest (.getBytes (str path) "UTF-8"))
      (.update digest (fs/read-all-bytes path)))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- state-path
  [root]
  (fs/path root "tmp/test-changed/dependency-cache.edn"))

(def ^:private subprocess-deadline-ms 300000)

(defn ensure-dependency-cache!
  "Refresh native clj-kondo dependency context when its real inputs change."
  [root]
  (let [input-digest (input-digest root)
        recorded (:seon.dev.clj-kondo/input-digest
                  (state/read-edn (state-path root)))]
    (if (and (= input-digest recorded) (cache-present? root))
      {:seon.dev.clj-kondo/status :current}
      (if-not (and (fs/which "clojure") (fs/which "clj-kondo"))
        {:seon.dev.clj-kondo/status :unavailable
         :seon.dev.clj-kondo/reason
         "clojure and clj-kondo are required to warm dependency analysis"}
        (try
          (let [classpath-result
                (operator.state/run-process!
                 {:seon.operator.subprocess/argv ["clojure" "-Spath"]
                  :seon.operator.subprocess/directory root
                  :seon.operator.subprocess/deadline-ms
                  subprocess-deadline-ms})
                classpath
                (str/trim (:seon.operator.subprocess/output classpath-result))]
            (if (or (not (zero? (:seon.operator.subprocess/exit
                                 classpath-result)))
                    (str/blank? classpath))
              {:seon.dev.clj-kondo/status :unavailable
               :seon.dev.clj-kondo/reason
               (str "could not derive the project classpath"
                    (when-not
                     (str/blank?
                      (:seon.operator.subprocess/error-output classpath-result))
                      (str ": "
                           (str/trim
                            (:seon.operator.subprocess/error-output
                             classpath-result)))))}
              (let [result
                    (operator.state/run-process!
                     {:seon.operator.subprocess/argv
                      ["clj-kondo" "--lint" classpath
                       "--dependencies" "--parallel" "--copy-configs"]
                      :seon.operator.subprocess/directory root
                      :seon.operator.subprocess/deadline-ms
                      subprocess-deadline-ms})]
                (if (zero? (:seon.operator.subprocess/exit result))
                  (do
                    (state/write-edn! (state-path root)
                                      {:seon.dev.clj-kondo/input-digest
                                       input-digest})
                    {:seon.dev.clj-kondo/status :warmed})
                  {:seon.dev.clj-kondo/status :unavailable
                   :seon.dev.clj-kondo/reason
                   (str "clj-kondo dependency analysis failed"
                        (when-not
                         (str/blank?
                          (:seon.operator.subprocess/error-output result))
                          (str ": "
                               (str/trim
                                (:seon.operator.subprocess/error-output
                                 result)))))}))))
          (catch Exception error
            (cond->
             {:seon.dev.clj-kondo/status :unavailable
              :seon.dev.clj-kondo/reason (.getMessage error)}
              (:seon.error/kind (ex-data error))
              (assoc :seon.error/kind (:seon.error/kind (ex-data error))
                     :seon.error/data (ex-data error)))))))))
