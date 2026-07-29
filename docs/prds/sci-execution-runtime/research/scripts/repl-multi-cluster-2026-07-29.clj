;;; One JVM, N sovereign clusters, one REPL that reaches all of them.
;;;
;;;   clojure -M:dev -i tmp/repl-experiments/multi.clj a b c
;;;
;;; The root is tmp/repl-experiments/clusters so this process's flock on
;;; the process-root store never collides with the owner's default root.
;;; Every cluster's advertisement lands under that root, so the MCP tool
;;; will NOT discover them (it reads data/clusters) — that is the point:
;;; one prepl port here already reaches every cluster in the registry.
(require 'seon.cluster 'seon.config)

(def root "tmp/repl-experiments/clusters")

(def names (or (seq *command-line-args*) ["xp-a" "xp-b"]))

(def instances
  (into {}
        (map (fn [n]
               (let [began (System/nanoTime)
                     inst (seon.cluster/start!
                           {:seon.boot/cluster-name n
                            :seon.boot/root root})]
                 [n (assoc inst ::wall-ms
                           (quot (- (System/nanoTime) began) 1000000))])))
        names))

(doseq [[n inst] instances]
  (println (format "%-8s prepl=%-6s web=%-24s ready=%sms wall=%sms"
                   n
                   (:seon.boot/prepl-port (:seon.boot/advertisement inst))
                   (:seon.render.web/url (:seon.boot/advertisement inst))
                   (:seon.boot/ready-ms inst)
                   (::wall-ms inst))))

(println "REGISTRY" (sort (keys @@#'seon.cluster/running-instances)))
(println "READY")
(flush)
@(promise)
