;; Restart drill for dependency changes (lane publication-work; issue
;; docs/seon/issues/restart-needed-refuses-the-publication-a-restart-should-make.md).
;; Run twice from a checkout snapshot holding a published tmp/leaf-root:
;;   clojure -J-Ddrill.phase=running   -M:test -e '(load-file "<this file>")'
;;   clojure -J-Ddrill.phase=restarted -M:test -e '(load-file "<this file>")'
;; `running`: deps.edn changes under this JVM; its publication must refuse RESTART NEEDED.
;; `restarted`: a JVM launched on the changed deps.edn; its publication must proceed.
;; The restarted phase restores deps.edn afterwards (the next JVM publishes the restore).
(require '[clojure.java.io :as io]
         '[seon.cluster :as cluster]
         '[seon.schema :as schema])

(def directory (.getCanonicalPath (io/file ".")))
(def root (str directory "/tmp/leaf-root/data/clusters"))
(def original (io/file directory "tmp/deps.edn.original"))
(def phase (System/getProperty "drill.phase"))

(defn- publish []
  (let [t0 (System/nanoTime)
        result (try (cluster/refresh-source! root ["deps.edn"] nil directory)
                    (catch clojure.lang.ExceptionInfo failure
                      {::refused (ex-message failure)
                       ::rule (some #(:seon.cluster.source/rule (ex-data %))
                                    (take-while some? (iterate ex-cause failure)))}))]
    (assoc (select-keys result [:seon.source/built? ::refused ::rule :seon.error/message])
           ::ms (Math/round (/ (- (System/nanoTime) t0) 1e6)))))

(schema/call-with-projection
 (schema/call-with-forms ((requiring-resolve 'seon.schema.edn/packaged-forms))
                         schema/declaration-projection)
 (fn []
   (case phase
     "running"
     (do (io/make-parents original)
         (io/copy (io/file directory "deps.edn") original)
         (spit (io/file directory "deps.edn") "\n;; restart drill\n" :append true)
         (prn {:phase phase :result (publish)}))
     "restarted"
     (try (prn {:phase phase :result (publish)})
          (finally (io/copy original (io/file directory "deps.edn")))))))
(shutdown-agents)
(System/exit 0)
