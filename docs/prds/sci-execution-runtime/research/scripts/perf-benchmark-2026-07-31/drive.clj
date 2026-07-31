;; Benchmark driver — boots ONE scratch cluster inside its OWN operator root
;; (tmp/bench-head, a pinned `git archive HEAD` snapshot) in ITS OWN JVM, so
;; nothing touches `default` or another lane's cluster, and so the JVM serves
;; the code of the pinned commit rather than a long-lived process's stale Vars.
;;
;; Run:  cd tmp/bench-head && clojure -M:dev -i ../bench/drive.clj
(System/setProperty "seon.operator.root" "/Users/sean/src/seon/tmp/bench-head")

(require 'seon.cluster '[clojure.edn :as edn])

(def instance
  ((ns-resolve 'seon.cluster 'start!)
   {:seon.boot/cluster-name "bench"
    :seon.config/manifest
    (edn/read-string (slurp "/Users/sean/src/seon/tmp/bench/ollama.edn"))}))

(println "BENCH-READY" (pr-str (:seon.boot/advertisement instance)))
(flush)
@(promise)
