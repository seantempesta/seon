;; Review driver only: run in the archived throwaway JVM, never default.
;; No test runner is invoked. See review-diff-astra-notes.md for inputs and limits.
(require '[seon.render.value :as v] '[seon.repl :as r] '[seon.render.hiccup :as h]
         '[seon.schema :as schema] '[seon.schema.edn :as sedn] '[seon.instrument :as inst]
         '[seon.batch :as batch] '[clojure.edn :as edn] '[seon.sci.eval :as ev]
         '[seon.contracts-compile-test :as cc] '[datahike.db :as dh])
(def projection (schema/declaration-projection (sedn/packaged-forms)))
(prn :contracts (cc/check {:seon.contracts-compile-test/root "."
                         :seon.contracts-compile-test/paths ["src/seon/print.cljc" "src/seon/render/value.clj" "src/seon/repl.clj" "src/seon/sci/eval.clj"]
                         :seon.contracts-compile-test/candidate projection}))
(def database (dh/init-db []))
(prn :arm (inst/apply! {:seon.schema/projection projection}))
(def profile {:seon.render.profile/id :seon.render.profile/agent :seon.render.profile/token-budget 1500
              :seon.render.profile/max-depth 8 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline})
(def inputs "/private/tmp/claude-501/-Users-sean-src-seon/2f0ff965-686f-4905-bf5c-7fe2ed6b5919/scratchpad/printer-code/")
(def context-text (slurp (str inputs "context.txt")))
(def composed (edn/read-string {:default tagged-literal} (slurp (str inputs "composed.edn"))))
(def help-text (slurp (str inputs "help.txt")))
(def big (subs (slurp (str inputs "composed.edn")) 0 108891))
(def dense (subs (pr-str (vec (range 30000))) 0 108891))
(def probes (into (sorted-map)
             (concat
              [[:ctx-ai #(count (v/text {:seon.db/db database :seon.render/value context-text :seon.render.value/root 'result/x1 :seon.render/profile profile}))]
               [:ctx-html #(count (h/->string (v/html {:seon.db/db database :seon.render/value context-text :seon.render.value/root [:seon.agent/id "root"] :seon.render/profile profile})))]
               [:map-html #(count (h/->string (v/html {:seon.db/db database :seon.render/value composed :seon.render.value/root [:ctx "root"] :seon.render/profile profile})))]
               [:help-reply #(count (r/response {:seon.cluster.eval/source "(help)" :seon.eval/shown help-text :seon.eval/renderer 'seon.bootstrap/render-help-ai :seon.repl/handle 'result/e9a11106a8315 :seon.eval/duration-ms 25 :seon.cluster.eval/call-count 444}))]
               [:reply-109k #(count (r/response {:seon.cluster.eval/source "x" :seon.eval/shown big :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))]
               [:reply-dense #(count (r/response {:seon.cluster.eval/source "x" :seon.eval/shown dense :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))]]
              (for [n [1000 10000 100000]
                    :let [s (.repeat "a \"q\" \\ <tag>\n" n)
                          unit {:seon.db/db database :seon.render/value s :seon.render.value/root 'result/review :seon.render/profile profile :seon.render.value/route-base "/review"}]]
                [(keyword (str "prepare-" n)) #(let [p (v/prepare unit)] (select-keys p [:seon.render.value/diagnostics :seon.render.value/elisions]))]))))
(def bean (java.lang.management.ManagementFactory/getThreadMXBean))
(def answer (promise))
(def thread (.unstarted (Thread/ofPlatform)
  (fn []
   (deliver answer
    (try
     (schema/call-with-projection projection
      (fn []
       (into (sorted-map)
        (for [[k f] probes]
         (let [sink (batch/sink)]
          (binding [batch/*world* {:seon.batch/sink sink}]
           (dotimes [_ 20] (f))
           [k (mapv (fn [_]
              (let [c0 (reduce + 0 (vals (batch/executed sink)))
                    id (.threadId (Thread/currentThread))
                    a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id)
                    t (System/nanoTime) out (f)
                    ms (/ (- (System/nanoTime) t) 1e6)
                    allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) a)
                    c1 (reduce + 0 (vals (batch/executed sink)))]
               {:ms ms :bytes allocated :calls (- c1 c0) :out out :platform? (not (.isVirtual (Thread/currentThread)))})) (range 5))]))))))
     (catch Throwable e {:error (ex-message e) :data (select-keys (ex-data e) [:seon.error/type :seon.error/operation :seon.error/member])}))))))
(.start thread)
(.join thread)
(prn :samples @answer)
(def database (dh/init-db []))
(prn :armed-subject-count (count (inst/instrumented)))
(shutdown-agents)
(System/exit 0)
