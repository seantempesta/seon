(require '[sci.core :as sci] '[clojure.data.json :as json] '[edamame.core :as edamame])
(import '[java.lang.management ManagementFactory])
(let [root "docs/research/agent-platform/2026-10-08/repl-audit/"
      evidence (json/read-str (slurp (str root "repl-auditor-evidence.json")) :key-fn keyword)
      allocation-bean (ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      measure (fn [f]
                (let [b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean tid)
                      t (System/nanoTime)
                      v (try {:value (pr-str (f))} (catch Throwable e {:error (ex-message e) :data (pr-str (ex-data e))}))]
                  (assoc v :ms (/ (- (System/nanoTime) t) 1e6)
                         :allocated_MB (/ (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean tid) b) 1e6))))
      serial (fn [c]
               (let [ctx (sci/init {})
                     rdr (sci/reader (:code c))]
                 (sci/with-bindings {sci/ns (sci/create-ns (symbol (:namespace c)))
                                     (sci/resolve ctx 'clojure.core/*data-readers*) {}}
                   (when-let [setup (:setup c)] (sci/eval-string* ctx setup))
                   (loop [out []]
                     (let [r (try {:form (sci/parse-next ctx rdr)}
                                  (catch Throwable e {:read-error (ex-message e)}))]
                       (cond
                         (:read-error r) (conj out r)
                         (= :sci.core/eof (:form r)) out
                         :else (recur (conj out (measure #(sci/eval-form ctx (:form r)))))))))))
      results (mapv (fn [c]
                      (let [ctx (sci/init {})]
                        (assoc c :serial (serial c)
                               :plain (measure #(sci/binding [sci/ns (sci/create-ns (symbol (:namespace c)))]
                                                  (when-let [setup (:setup c)] (sci/eval-string* ctx setup))
                                                  (sci/eval-string* ctx (:code c)))))))
                    (:cases evidence))
      source-files ["src/seon/mcp.clj" "src/seon/cluster/agent.clj" "src/seon/turn.clj"
                    "src/seon/sci/forgive.clj" "src/seon/sci/reader.cljc" "src/seon/sci/batch.clj"
                    "src/seon/batch.clj" "src/seon/sci/eval.clj" "src/seon/sci/observe.clj"
                    "src/seon/sci/arm.clj" "src/seon/sci/kernel.clj" "src/seon/turn/batch.clj"
                    "src/seon/render.clj" "src/seon/render/value.clj" "src/seon/repl.clj"
                    "src/seon/print.cljc" "src/seon/render/transcript.clj" "src/my/program.clj"
                    "src/seon/instrument.clj" "src/seon/fn.clj"]
      spans (into {} (map (fn [path]
                            [path (into {} (keep (fn [f]
                                                 (when (and (seq? f) (#{'defn 'defn-} (first f)))
                                                   [(str (second f)) (select-keys (meta f) [:row :end-row])]))
                                           (edamame/parse-string-all (slurp path)
                                             {:all true :read-cond :allow :features #{:clj}
                                              :auto-resolve (constantly 'audit)
                                              :readers (fn [tag] #(tagged-literal tag %))
                                              :location? seq? :end-location true})))]) source-files))
      bench (mapv (fn [source]
                    (let [ctx (sci/init {})]
                      (dotimes [_ 20] (sci/eval-string* ctx source))
                      {:code source :samples (vec (repeatedly 30 #(measure (fn [] (sci/eval-string* ctx source)))))}))
                  ["(+ 1 1)" "(defn f [x] (inc x))"])]
  (spit (str root "repl-auditor-plain.json")
        (json/write-str {:thread (str (Thread/currentThread)) :virtual (.isVirtual (Thread/currentThread))
                         :results results :bench bench :spans spans
                         :models (mapv (fn [c]
                                         (let [code (case (:id c)
                                                      "model-fenced-multiline" "(str \"a (\n```\n) b\")"
                                                      "model-fenced-literals" "42\n##NaN\n`(+ 1 2)"
                                                      "model-prose" "; Here is the answer.\n(+ 1 1)\n; Done."
                                                      (:code c))]
                                           {:id (:id c) :code code
                                            :plain (measure #(sci/binding [sci/ns (sci/create-ns (symbol (:namespace c)))]
                                                               (sci/eval-string* (sci/init {}) code)))}))
                                       (:model evidence))})))
