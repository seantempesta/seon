;; The durable layer on real data: every declaration name, cold, then in a new JVM (a restart), then warm.
(require '[seon.embed :as embed] 'seon.cache 'clojure.edn)
(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(def settings #:seon.config.embed{:endpoint "http://127.0.0.1:8701/v1/embed" :model "eg2-text-bf16" :dimension 256 :batch-size 256 :timeout-ms 30000})
(def names (mapv (comp str :sym) (clojure.edn/read-string (slurp "tmp/embeddings/decls.edn"))))
(def store (embed/open-store (str (System/getProperty "user.dir") "/tmp/embeddings/durable")))
(def cache (seon.cache/create 512000000))
(let [[t r] (ms (embed/embed! settings store cache embed/request! "similarity" names))]
  (println (format "run %s: %.0f ms, hot %d durable %d requested %d" (first *command-line-args*) t (:seon.embed/hot r) (:seon.embed/durable r) (:seon.embed/requested r))))
(let [[t r] (ms (embed/embed! settings store cache embed/request! "similarity" names))]
  (println (format "same JVM again: %.0f ms, hot %d durable %d requested %d" t (:seon.embed/hot r) (:seon.embed/durable r) (:seon.embed/requested r))))
(let [[t r] (ms (embed/embed! settings store cache embed/request! "similarity" ["seon.db/transcat!"]))]
  (println (format "one new name: %.1f ms requested %d" t (:seon.embed/requested r))))
(let [[t r] (ms (embed/embed! settings store cache embed/request! "similarity" ["seon.db/transcat!"]))]
  (println (format "the same name again: %.2f ms requested %d" t (:seon.embed/requested r))))
(shutdown-agents) (System/exit 0)
