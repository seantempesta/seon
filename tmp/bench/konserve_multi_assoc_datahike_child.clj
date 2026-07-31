(ns konserve-multi-assoc-datahike-child
  (:require [datahike.api :as d]
            [konserve.filestore :as filestore])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn- config
  [path]
  {:store {:backend :file
           :path path
           :id (UUID/nameUUIDFromBytes (.getBytes ^String path StandardCharsets/UTF_8))}
   :writer {:backend :self}
   :keep-history? true
   :schema-flexibility :write})

(defn -main
  "Commit one transaction and pause at the requested filestore batch stage."
  [path pause-stage]
  (let [pause-stage (keyword pause-stage)
        stage-hook (fn [{::filestore/keys [stage]}]
                     (when (= pause-stage stage)
                       (println (str "READY " (name stage)))
                       (flush)
                       (read-line)))
        stage-var (or (ns-resolve 'konserve.filestore '*multi-write-stage-hook*)
                      (throw (ex-info "Local Konserve batch hook is not on the classpath."
                                      {})))
        conn (d/connect (config path))]
    (try
      ;; Datahike's writer owns another thread, so a thread-local binding would
      ;; not reach the filestore call. This process exists only to be killed.
      (alter-var-root stage-var (constantly stage-hook))
      (d/transact conn [{:probe/id "subject" :probe/n 1}
                        {:probe/id "new-marker" :probe/n 1}])
      (finally
        (alter-var-root stage-var (constantly (constantly nil)))
        (d/release conn)))))
