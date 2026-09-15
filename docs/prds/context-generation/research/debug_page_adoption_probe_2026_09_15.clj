(require 'clojure.java.shell 'seon.operator 'seon.sci.kernel 'seon.render)

(let [connection (seon.operator/connection "default")
      before (seon.render/source-generation (seon.db/db connection))
      invoke seon.sci.kernel/invoke
      calls (atom [])]
  (with-redefs [seon.sci.kernel/invoke
                (fn [request]
                  (when (= "seon.repl/render-ai" (:seon.fn/sym request))
                    (swap! calls conj (get-in request [:seon.sci.eval/args 0 :seon.render/value :seon.cluster.eval/id])))
                  (invoke request))]
    (let [gets (mapv (fn [_]
                       (let [basis-before (seon.db/basis-t (seon.db/db connection))
                             initial (count @calls)
                             result (clojure.java.shell/sh "curl" "--max-time" "45" "-s"
                                      "-o" "/dev/null" "-w" "%{http_code} %{time_total}"
                                      "http://127.0.0.1:7994/agent/juniper/debug")]
                         {:basis-before basis-before :basis-after (seon.db/basis-t (seon.db/db connection))
                          :curl result :evaluation-renders (- (count @calls) initial)}))
                     (range 3))]
      (println {:source-before before
                :source-after (seon.render/source-generation (seon.db/db connection))
                :gets gets}))))
