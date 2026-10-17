(ns seon.cost-record2
  "Measure quiet requests in the operator's candidate JVM, then release it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [seon.operator :as operator]))

(def request-code
  "(let [m (seon.profile/measure #(#'seon.mcp/eval-clj {:seon.mcp/code \"(+ 1 2)\" :seon.mcp/agent \"cost-record2-measure\" :seon.mcp/namespace \"seon.test\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"sci\"})) [texts failed?] (:seon.profile/value m)] {:measured (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/ranked-by-self]) :failed? failed? :request (some :seon.repl/request (map clojure.edn/read-string texts))})")

(def revisions-code
  "(let [running (get @seon.operator.runtime/running-instances \"default\") store (:seon.store/store running) conn (seon.cluster.boot/connection \"default\") branch (:seon.agent/branch (seon.db/pull @conn [:seon.agent/branch] [:seon.agent/id \"cost-record2-measure\"])) candidate (seon.cluster.source/database store (seon.cluster.registry/branch-commit-id {:seon.store/store store :seon.store/branch branch})) base (seon.cluster.source/database store (#'seon.test/change-base \"default\" candidate (:seon.source/commit-id (seon.db/pull candidate [:seon.source/commit-id] [:seon.cluster/name \"default\"])))) families (vec (:seon.program/compared-families (seon.program/declaration-families (seon.db/carried-projection candidate)))) attributes (conj families :seon.program/definition-digest) before (seon.db/content-revisions base attributes) after (seon.db/content-revisions candidate attributes) m (seon.profile/measure #(#'seon.test/digest-differences base candidate families))] {:branch branch :different (into {} (filter (fn [[a v]] (not= v (get before a)))) after) :known? (boolean before) :measured (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/ranked-by-self]) :pulls (seon.profile/calls-of m 'seon.db/pull-many) :value (:seon.profile/value m)})")

(defn -main
  "Measure eight quiet candidate requests, then release the candidate."
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
          (doseq [code (concat (repeat 8 request-code) [revisions-code])]
            (let [form (str "(#'seon.mcp/eval-clj {:seon.mcp/code " (json/generate-string code)
                            " :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})")
                  [texts] (operator/prepl-value! endpoint form 30000)]
              (doseq [text texts] (println text))))))
      (finally
        (operator/down! {:seon.operator/managed-root disposable}
                        (operator/selected-processes disposable))
        (fs/delete-tree disposable)
        (.close ^java.nio.channels.FileChannel (:seon.operator/channel slot))))))
