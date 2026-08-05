;; P19 probe — run the boot directly, foreground, timestamps per phase.
;; Reproduces what the operator wrapper does minus the socket/detach/30s timeout.
(def t0 (System/nanoTime))
(defn stamp [label]
  (println (format "[%8.1fs] %s" (/ (- (System/nanoTime) t0) 1e9) label))
  (flush))
(stamp "requiring seon.cluster seon.config seon.instrument")
(require 'seon.cluster 'seon.config 'seon.instrument)
(stamp "namespaces loaded; calling start!")
(let [progress-var (ns-resolve 'seon.cluster (symbol "*boot-progress!*"))
      instance
      (with-bindings {progress-var (fn [phase] (stamp (str "phase " (name phase))))}
        ((ns-resolve 'seon.cluster (symbol "start!"))
         {:seon.boot/root "/Users/sean/src/seon/data/clusters"
          :seon.boot/cluster-name "default"}))]
  (stamp (str "start! returned; ready-ms=" (:seon.boot/ready-ms instance)
              " advertisement=" (boolean (:seon.boot/advertisement instance))))
  (stamp "stopping cluster for clean exit")
  (let [stop! (ns-resolve 'seon.cluster (symbol "stop!"))]
    (when stop! (stop! instance)))
  (stamp "done")
  (System/exit 0))
