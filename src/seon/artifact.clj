(ns seon.artifact
  "Thin standalone entry over the fresh cluster boot path."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]))

(def ^:private initialization-resource
  "seon/artifact/current-src.edn")

(defn- refuse!
  [message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::refused true))))

(defn- parse-arguments
  [arguments]
  (loop [remaining (seq arguments)
         options {:seon.artifact/operator-root
                  (or (System/getProperty "seon.operator.root")
                      (System/getProperty "user.dir"))
                  :seon.boot/cluster-name "default"}]
    (if-not remaining
      options
      (let [[argument value & tail] remaining]
        (case argument
          "--root"
          (if value
            (recur tail (assoc options :seon.artifact/operator-root value))
            (refuse! "`--root` requires a path." {}))

          "--cluster"
          (if value
            (recur tail (assoc options :seon.boot/cluster-name value))
            (refuse! "`--cluster` requires a name." {}))

          (refuse! "The standalone artifact received an unknown argument."
                   {:seon.artifact/argument argument}))))))

(defn- cluster-root
  [operator-root]
  (.getCanonicalPath (io/file operator-root "data" "clusters")))

(defn- packaged-initialization-pages
  []
  (or (some-> initialization-resource io/resource slurp edn/read-string)
      (refuse! "The artifact has no packaged initialization pages."
               {:seon.artifact/resource initialization-resource})))

(defn install-initialization-pages!
  "Install packaged `current-src` rows when an operator root is empty."
  {:malli/schema [:=> [:cat :seon.boot/root] :seon.source/current]}
  [root]
  (let [store-dir (str (io/file root "store"))
        held-store (store/open-store! {:seon.store/dir store-dir})]
    (try
      (or
       (source/current held-store)
       (let [{source-digest :seon.source/digest
              manifest :seon.fn/manifest}
             (packaged-initialization-pages)]
         (source/publish!
          {:seon.store/store held-store
           :seon.source/digest source-digest
           :seon.source/populate `cluster/populate-source!
           :seon.source/activation `cluster/derive-activation
           :seon.source/populate-request {:seon.fn/manifest manifest}})
         (source/current held-store)))
      (finally
        (store/release-store! held-store)))))

(defn -main
  "Install packaged source rows and run one fresh cluster."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& arguments]
  (let [{operator-root :seon.artifact/operator-root
         cluster-name :seon.boot/cluster-name}
        (parse-arguments arguments)
        root (cluster-root operator-root)
        direct-entry-time (System/nanoTime)
        namespace-load-started
        (or (Long/getLong "seon.artifact.namespace-load-started-nanos")
            direct-entry-time)
        namespace-load-completed
        (or (Long/getLong "seon.artifact.namespace-load-completed-nanos")
            direct-entry-time)
        installation-started (System/nanoTime)]
    (install-initialization-pages! root)
    (let [installation-completed (System/nanoTime)]
      (println
       {:seon.artifact/namespace-load-ms
        (/ (double (- namespace-load-completed namespace-load-started)) 1e6)
        :seon.artifact/initialization-install-ms
        (/ (double (- installation-completed installation-started)) 1e6)})
      (flush))
    (let [instance (cluster/start! {:seon.boot/root root
                                    :seon.boot/cluster-name cluster-name})]
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. #(cluster/stop! instance) "seon-artifact-shutdown"))
      (println (cluster/banner (cluster/readiness instance)))
      (flush)
      @(promise))))
