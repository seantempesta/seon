(ns seon.dev.clj-kondo
  "Native clj-kondo dependency-cache ownership for development tools."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
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
                (process/sh {:cmd ["clojure" "-Spath"]
                             :dir root
                             :out :string
                             :err :string
                             :continue true})
                classpath (str/trim (:out classpath-result))]
            (if (or (not (zero? (:exit classpath-result)))
                    (str/blank? classpath))
              {:seon.dev.clj-kondo/status :unavailable
               :seon.dev.clj-kondo/reason
               (str "could not derive the project classpath"
                    (when-not (str/blank? (:err classpath-result))
                      (str ": " (str/trim (:err classpath-result)))))}
              (let [result
                    (process/sh
                     {:cmd ["clj-kondo" "--lint" classpath
                            "--dependencies" "--parallel" "--copy-configs"]
                      :dir root
                      :out :string
                      :err :string
                      :continue true})]
                (if (zero? (:exit result))
                  (do
                    (state/write-edn! (state-path root)
                                      {:seon.dev.clj-kondo/input-digest
                                       input-digest})
                    {:seon.dev.clj-kondo/status :warmed})
                  {:seon.dev.clj-kondo/status :unavailable
                   :seon.dev.clj-kondo/reason
                   (str "clj-kondo dependency analysis failed"
                        (when-not (str/blank? (:err result))
                          (str ": " (str/trim (:err result)))))}))))
          (catch Exception error
            {:seon.dev.clj-kondo/status :unavailable
             :seon.dev.clj-kondo/reason (.getMessage error)}))))))
