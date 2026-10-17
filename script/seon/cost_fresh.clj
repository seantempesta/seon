(ns seon.cost-fresh
  "Measure fresh requests in the operator's quiet candidate JVM."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.operator :as operator]))

(def request-code
  "(let [agent (str \"cost-fresh-\" (seon.id/id)) m (seon.profile/measure #(#'seon.mcp/eval-clj {:seon.mcp/code \"(+ 1 2)\" :seon.mcp/agent agent :seon.mcp/cluster \"default\" :seon.mcp/mode \"sci\"})) [texts failed?] (:seon.profile/value m)] {:agent agent :measured (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/ranked-by-self :seon.cost/jvm-window]) :failed? failed? :branches (seon.profile/calls-of m 'seon.cluster.registry/branch!) :transactions (seon.profile/calls-of m 'seon.db/transact-call) :request (some :seon.repl/request (map clojure.edn/read-string texts))})")

(defn- median
  "The median request time after the first fresh request, from the saved replies."
  {:malli/schema [:=> [:cat :string] :double]}
  [path]
  (let [times (->> (edn/read-string (str "[" (slurp path) "]"))
                   (keep :seon.repl/value)
                   rest
                   (map #(get-in % [:measured :seon.profile/ms]))
                   sort vec)
        n (count times)]
    (/ (+ (times (quot n 2)) (times (quot (dec n) 2))) 2.0)))

(defn assert-improvement!
  "Assert a smaller median for the same fresh-request probe on parent and change."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [before after]
  (assert (< (median after) (median before)) "The complete fresh request must become cheaper.")
  nil)

(defn -main
  "Measure seven fresh requests and release the candidate."
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
        (let [endpoint (operator/advertisement disposable "default")]
          (dotimes [_ 7]
            (let [form (str "(#'seon.mcp/eval-clj {:seon.mcp/code " (json/generate-string request-code)
                            " :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})")
                  [texts] (operator/prepl-value! endpoint form 30000)]
              (doseq [text texts] (println text))))))
      (finally
        (operator/down! {:seon.operator/managed-root disposable}
                        (operator/selected-processes disposable))
        (fs/delete-tree disposable)
        (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot))))))
