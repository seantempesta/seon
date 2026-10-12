(ns seon.cost-record2
  "Measure quiet requests in the operator's candidate JVM, then release it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [seon.operator :as operator]))

(def request-code
  "(let [m (seon.profile/measure #(#'seon.mcp/eval-clj {:seon.mcp/code \"(+ 1 2)\" :seon.mcp/agent \"cost-record2-measure\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"sci\"})) [texts failed?] (:seon.profile/value m)] {:measured (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/ranked-by-self]) :failed? failed? :request (some :seon.repl/request (map clojure.edn/read-string texts))})")

(defn -main
  "Boot the candidate by the check owner, measure eight requests without tests running, and stop it."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [root sha]
  (let [slot (#'operator/check-slot! root (operator/operator-boot-bound-ms {})
                                    (operator/operator-check-slots {})
                                    (.pid (java.lang.ProcessHandle/current)))
        disposable (.getCanonicalPath (io/file root "data/check" (str "slot-" (:seon.operator/slot slot))))]
    (try
      (fs/create-dirs disposable)
      (#'operator/seeded-store! root disposable (:seon.operator/slot slot))
      (let [boot (#'operator/launched-throwaway! root disposable sha)]
        (when-not (:seon.operator/ready? boot)
          (throw (ex-info "Candidate did not become ready." boot)))
        (#'operator/adopted! disposable)
        (let [endpoint (operator/advertisement disposable "default")
              form (str "(#'seon.mcp/eval-clj {:seon.mcp/code " (json/generate-string request-code)
                        " :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})")]
          (dotimes [_ 8]
            (let [[texts] (operator/prepl-value! endpoint form 30000)]
              (doseq [text texts] (println text))))))
      (finally
        (operator/down! {:seon.operator/managed-root disposable}
                        (operator/selected-processes disposable))
        (fs/delete-tree disposable)
        (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot))))))
