(ns seon.mcp-test
  "Total outward preparation through the real MCP PREPL."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.operator.runtime :refer [running-instances]]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(defn- prepl-events
  [cluster-name effective form]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                     (java.io.StringReader. (pr-str form)))
              writer (java.io.StringWriter.)]
    (binding [*in* reader *out* writer]
      (cluster/mcp-io-prepl cluster-name effective))
    (with-open [events (java.io.PushbackReader.
                       (java.io.StringReader. (str writer)))]
      (loop [result []]
        (let [event (edn/read {:eof ::end} events)]
          (if (= ::end event) result
              (recur (conj result event))))))))

(deftest outward-values-use-total-projection
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "mcp-total-" (id/id))
           effective (support/effective-config)
           profile (render/agent-render-profile effective)
           database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           request {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.schema/projection (db/carried-projection database)
                    :seon.render/profile profile
                    :seon.render/value {"a" 1 "b" 2}
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :record}]
       (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
       (swap! running-instances assoc cluster-name
              {:seon.boot/cluster-connection connection
               :seon.sci.eval/ctx
               (env/carry-state {} (:seon.sci.eval/projection-state (meta database)))})
       (try
         (doseq [[form expected]
                 [['(sorted-map "a" 1 "b" 2) {"a" 1 "b" 2}]
                  ['{:seon.sci.admit/record {} :seon.eval/shown "ordinary"}
                   {:seon.sci.admit/record {} :seon.eval/shown "ordinary"}]
                  ['{:via [] :trace [] :cause "ordinary"}
                   {:via [] :trace [] :cause "ordinary"}]]]
           (let [events (prepl-events
                         cluster-name effective
                         (list 'do '(seon.cluster/project-next-prepl-value!) form))
                 terminal (filterv #(= :ret (:tag %)) events)]
             (is (= 1 (count terminal)) (pr-str events))
             (is (not (:exception (first terminal))) (pr-str events))
             (is (= expected
                    (:seon.dev.mcp/value
                     (edn/read-string (:val (first terminal))))))))
         (let [events (prepl-events cluster-name effective
                       '(do (seon.cluster/project-next-prepl-value!)
                            (throw (ex-info "expected failure" {}))))
               terminal (filterv #(= :ret (:tag %)) events)
               result (:seon.dev.mcp/value
                       (edn/read-string (:val (first terminal))))]
           (is (= 1 (count terminal)))
           (is (true? (:exception (first terminal))))
           ;; The whole Throwable->map prepl hands MCP (R6, d1d6b0eb6): its
           ;; cause, its typed links and its frames, never a flat summary.
           (is (= "expected failure" (:cause result)) (pr-str result))
           (is (= 'clojure.lang.ExceptionInfo (:type (first (:via result)))))
           (is (contains? result :trace)))
         ;; A root without an identity is refused before selection: the
         ;; refusal is the whole answer, with no tree and no text. With a
         ;; root, the one selection yields the tree and its text.
         (let [refused (value/prepare request)]
           (is (string? (:seon.render.value/root-description refused)))
           (is (not (contains? refused :seon.render.value/tree)))
           (is (not (contains? refused :seon.render.value/text))))
         (let [prepared (value/prepare (assoc request :seon.render.value/root [::root]))]
           (is (= {"a" 1 "b" 2} (edn/read-string (:seon.render.value/text prepared))))
           (is (= (:seon.render.value/text prepared)
                  (:seon.print/text (print/emit-both (:seon.render.value/tree prepared)
                                                     (:seon.render.value/options prepared))))))
         ;; A value's own renderer that refuses the value is shown as its
         ;; declared unknown diagnostic, never as a missing value: the real
         ;; directory renderer refuses a map that is not a directory.
         (let [prepared (value/prepare
                         (assoc request :seon.render.value/root [::root]
                                :seon.render/value
                                {:seon.render/ai 'seon.repl/render-directory-ai
                                 :seon.error/message "probe"}))
               shown (edn/read-string (:seon.render.value/text prepared))]
           (is (= 'seon.repl/render-directory-ai (:seon.render.unknown/producer shown)) (pr-str shown))
           (is (= :refused (:seon.render.unknown/reason shown))))
         (finally (swap! running-instances dissoc cluster-name)))))))
