(ns seon.search-cost
  "Measure search through MCP in an operator-owned candidate JVM."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [seon.operator :as operator]))

(def probe
  "(let [database (seon.db/db (seon.cluster.boot/connection \"default\"))]
     (mapv (fn [query]
             (let [m (seon.profile/measure #(my.program/search database query))]
               (assoc (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads])
                      :query query
                      :functions (take 5 (:seon.profile/ranked-by-self m))
                      :uses (seon.profile/calls-of m 'my.program/use-count)
                      :matches (mapv #(or (:sym %) (:seon.schema/key %))
                                     (:matches (:seon.profile/value m))))))
           [:seon.message/id \"program context\" \"current-state block\" \"merge\" :seon.render/form]))")

(defn -main
  "Print three MCP measurements on revision sha, then release its disposable root."
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
        (let [endpoint (operator/advertisement disposable "default")
              form (str "(#'seon.mcp/eval-clj {:seon.mcp/code " (json/generate-string probe)
                        " :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})")]
          (dotimes [_ 3]
            (let [[texts] (operator/prepl-value! endpoint form 30000)]
              (doseq [text texts] (println text))))))
      (finally
        (operator/down! {:seon.operator/managed-root disposable}
                        (operator/selected-processes disposable))
        (fs/delete-tree disposable)
        (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot))))))
