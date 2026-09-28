(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.cluster.store :as store] '[seon.cluster.registry :as registry] '[seon.fn :as index] '[seon.fn.analyzer :as analyzer] '[seon.program :as program] '[seon.schema :as schema] '[seon.test-support :as support] '[seon.id :as id] '[clojure.java.io :as io] '[clojure.string :as str] '[clj-kondo.impl.cache :as kondo.cache] '[clj-kondo.impl.core :as kondo.core] '[clj-kondo.core :as clj-kondo] '[clj-kondo.core :as kondo] '[cognitect.transit :as transit])
(declare record-after stale-after)
(let [directory (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file "tmp")) "astra-jar-content-" (make-array java.nio.file.attribute.FileAttribute 0)))
      jar-file (io/file directory "sample.jar")
      cache (.getPath (io/file directory "cache"))
      write-jar! (fn [args] (with-open [out (java.util.jar.JarOutputStream. (io/output-stream jar-file))]
                              (dotimes [i 100]
                                (.putNextEntry out (java.util.jar.JarEntry. (str "astra/jar/n" i ".clj")))
                                (.write out (.getBytes (str "(ns astra.jar.n" i ")\n(defn f " args " nil)\n") java.nio.charset.StandardCharsets/UTF_8))
                                (.closeEntry out))))]
  (try
    (write-jar! "[]")
    (let [raw (kondo/run! {:lint [(.getPath jar-file)] :cache-dir cache :config-dir ".clj-kondo" :config {:output {:analysis true :canonical-paths true}}})
          root (.getPath (kondo.core/resolve-cache-dir ".clj-kondo" true cache))
          definitions (vec (get-in raw [:analysis :namespace-definitions]))
          recorded (mapv (fn [n] (let [[_ calls] (support/armed-calls '#{seon.fn.analyzer/file-digest}
                                                 #(record-after root (assoc-in raw [:analysis :namespace-definitions] (subvec definitions 0 n))))] {:namespaces n :hashes (get calls 'seon.fn.analyzer/file-digest 0)})) [1 10 100])
          original-time (.lastModified jar-file)
          _ (write-jar! "[x]")
          _ (.setLastModified jar-file original-time)]
      {:recording recorded
       :checking (mapv (fn [n]
                         (let [input {:analysis {:namespace-definitions definitions :namespace-usages (mapv #(hash-map :to (:name %)) (subvec definitions 0 n))}}
                               parent (#'analyzer/stale-cache-entries root false input)
                               start (System/nanoTime)
                               [candidate calls] (support/armed-calls '#{seon.fn.analyzer/file-digest} #(stale-after root false input))]
                           {:namespaces n :parent-stale (count (:seon.fn.analyzer/stale parent))
                            :candidate-stale (count (:seon.fn.analyzer/stale candidate))
                            :hashes (get calls 'seon.fn.analyzer/file-digest 0)
                            :ms (/ (- (System/nanoTime) start) 1e6)})) [1 10 100])})
    (finally (support/delete-recursively! directory))))
