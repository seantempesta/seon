;; Run from the repository: bb --classpath script:src:resources \
;; docs/prds/agent-platform/landing/b1b-drill.clj ROOT CLUSTER QUALIFIED-TEST
;; ROOT must already host the adopted implementation and canonical exported
;; fixture base. This runner never starts or replaces that host.
(require '[seon.operator :as client])
(let [[root cluster test-name] *command-line-args*
      test-symbol (symbol test-name)
      endpoint (or (client/advertisement root cluster)
                   (throw (ex-info "Explicit scratch endpoint required." {:root root :cluster cluster})))
      form `(do
              (require '~(symbol (namespace test-symbol)) '~'seon.test '~'seon.test.arm)
              (let [connection# (~'seon.cluster.boot/connection ~cluster)
                    database# (~'seon.db/db connection#)
                    test-var# (resolve '~test-symbol)
                    bound# (or (:seon.test/long-ms (meta test-var#))
                               (* 1000 ~'seon.test-support/event-backstop-seconds))]
                (~'seon.test.arm/arm-contracts! (#'~'seon.test.arm/arming-decision)
                  (~'seon.db/carried-projection database#) "b1b" ['~(symbol (namespace test-symbol))])
                (let [began# (System/nanoTime)
                      result# (~'seon.test/run test-var# connection#
                                {:seon.boot/cluster-name ~cluster :seon.test/remaining-ms bound#
                                 :seon.test.run/provenance (~'seon.test.runner/provenance database#)})]
                  (pr-str {:seon.probe/elapsed-ms (quot (- (System/nanoTime) began#) 1000000)
                           :seon.probe/result result#}))))]
  (println (client/prepl-value! endpoint (pr-str form)
             (+ (client/operator-boot-bound-ms {}) 90000)
             (fn [line] (print line) (flush)))))
