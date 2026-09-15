(require 'clojure.java.shell 'seon.render.web 'seon.render.transcript)
(let [costs (atom {})
      measured [#'seon.db/read-evidence-current? #'seon.render/call-cache-evidence
                #'seon.render/retained-program-current? #'seon.render.web/derive-context!
                #'seon.render.walk/history #'seon.render.transcript/acquire-ledger-data
                #'seon.render.transcript/render-ledger]
      wrappers (into {} (map (fn [v]
                               (let [f @v]
                                 [v (fn [& args]
                                      (let [start (System/nanoTime)]
                                        (try (apply f args)
                                             (finally
                                               (swap! costs update (str v)
                                                      (fn [[n elapsed]]
                                                        [(inc (or n 0))
                                                         (+ (or elapsed 0.0)
                                                            (/ (- (System/nanoTime) start) 1e6))]))))))])) measured))]
  (with-redefs-fn wrappers
    #(println {:curl (clojure.java.shell/sh "curl" "--max-time" "45" "-s" "-o" "/dev/null"
                       "-w" "%{http_code} %{time_total}" "http://127.0.0.1:7994/agent/juniper/debug")
               :costs @costs})))
