;; Review driver only: run in the archived throwaway JVM, never default.
;; No test runner is invoked. See review-diff-astra-notes.md for inputs and limits.
(require '[seon.render.value :as v] '[seon.repl :as r] '[seon.print :as p]
         '[seon.schema :as schema] '[seon.schema.edn :as sedn] '[seon.instrument :as inst]
         '[seon.batch :as batch] '[clojure.edn :as edn] '[seon.sci.eval :as ev]
         '[seon.contracts-compile-test :as cc] '[datahike.db :as dh] '[sci.core :as sci]
         '[clojure.string :as str] '[seon.bootstrap] '[seon.config :as config])
(def projection (schema/declaration-projection (sedn/packaged-forms)))
(def database (dh/init-db []))
(def paths ["src/seon/print.cljc" "src/seon/render/value.clj" "src/seon/repl.clj" "src/seon/sci/eval.clj"])
(prn :complete-contracts (mapv #(select-keys % [:filename :row :type :message])
                         (cc/check {:seon.contracts-compile-test/root "."
                                    :seon.contracts-compile-test/paths paths
                                    :seon.contracts-compile-test/baselines (into {} (map (fn [p] [p (slurp (str "../parent/" p))])) paths)
                                    :seon.contracts-compile-test/candidate projection})))
(prn :arm (inst/apply! {:seon.schema/projection projection}))
(def profile {:seon.render.profile/id :seon.render.profile/agent :seon.render.profile/token-budget 1500
              :seon.render.profile/max-depth 8 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline})
(def ctx (sci/init {}))
(defn unit [x] {:seon.render/value x :seon.db/db database :seon.schema/projection projection
                :seon.sci.eval/ctx ctx :seon.sci.admit/caps (config/result-caps config/defaults) :seon.sci.eval/time-limit-ms 5000 :seon.render/profile profile :seon.render.value/root 'result/e0123456789ab})
(defn attempt [k f]
 (let [t (System/nanoTime)]
  (try (let [out (f)] (prn k {:ms (/ (- (System/nanoTime) t) 1e6) :out out}))
       (catch Throwable e (prn k {:error (ex-message e) :data (select-keys (ex-data e) [:seon.error/type :seon.error/operation])})))))
(schema/call-with-projection projection
 (fn []
  (attempt :raw-lines
   #(mapv (fn [[label text]]
      (let [x (v/prepare (unit {:seon.render/ai text})) cut (:seon.render.value/tree x)]
       {:label label :face (:seon.print/face cut) :diagnostics (mapv (fn [d] (let [f (:seon.render.value/failure d)] (if (instance? Throwable f) {:message (ex-message f)} f))) (:seon.render.value/diagnostics x))
        :prefix-count (count (:seon.print/prefix cut)) :ends-line? (str/ends-with? (or (:seon.print/prefix cut) "") "\n")
        :omitted (:seon.print/omitted cut) :expected-omitted (- (count text) (count (:seon.print/prefix cut)))
        :continuation (:seon.print/continuation cut) :elisions (:seon.render.value/elisions x)}))
     [[:short-lines (.repeat "line \"q\" \\ <b>\n" 1000)]
      [:later-long-line (str "first\n" (.repeat "x" 10000))]
      [:sparse-lines (.repeat (str (.repeat "x" 1000) "\n") 10)]
      [:crlf (.repeat "line\r\n" 1000)]]))
  (attempt :explicit-length
   #(mapv (fn [[x length level]]
      (let [out (v/prepare (assoc (unit x) :seon.print/options {:seon.print/length length :seon.print/level level}
                                :seon.render.value/options {:seon.render.value/structural? true}))]
       {:value x :length length :level level :shown (:seon.render.value/text out) :elisions (:seon.render.value/elisions out)}))
      [[[1 2 3] 1 nil] [(vec (range 9)) 2 nil] [(vec (range 9)) 0 nil] [[[1 2]] nil 0]]))
  (attempt :reply-keys
   #(mapv (fn [shown]
      (let [emission {:seon.eval/shown shown :seon.cluster.eval/diagnostics "[{:kind :warning}]"
                      :seon.repl/errors "[{:kind :error}]" :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3
                      :seon.cluster.eval/call-count 2 :seon.cluster.eval/output "hello"
                      :seon.sci.eval/ending-ns 'other :seon.ns/name 'user}
            text (r/response emission)]
       {:shown shown :reply text :entry-keys (mapv first (#'r/response-entries emission))}))
      ["nil" "false" "\"a;b\"" "42 ; trailing" "42 ; trailing\n" "raw prose"]))
  (attempt :prose-error #(r/response {:seon.cluster.eval/error "bad\n\"quoted\"; prose" :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))
  (attempt :ties #(let [emission {:seon.eval/shown "1" :seon.eval/duration-ms 2000}
                       a (r/response (assoc emission :seon.cluster.eval/repeated-calls [['z/z 12 1] ['a/a 12 2]]))
                       b (r/response (assoc emission :seon.cluster.eval/repeated-calls [['a/a 12 2] ['z/z 12 1]]))]
                   {:equal (= a b) :reply a}))
  (attempt :poison #(let [x (v/prepare (assoc (unit (map (fn [_] (throw (ex-info "poison" {}))) [1]))
                                            :seon.render.value/options {:seon.render.value/structural? true}))]
                     {:text (:seon.render.value/text x) :diagnostics (mapv (fn [d] (let [f (:seon.render.value/failure d)] (if (instance? Throwable f) {:message (ex-message f)} f))) (:seon.render.value/diagnostics x))}))
  (attempt :nonfork-session
   #(let [ctx (ev/build-base-ctx projection)
          request {:seon.sci.eval/ctx ctx :seon.schema/projection projection :seon.render/profile profile
                   :seon.sci.admit/caps (config/result-caps config/defaults) :seon.sci.eval/time-limit-ms 5000
                   :seon.cluster.eval/source "(do (set! *print-length* 2) (vec (range 9)))"
                   :seon.sci.eval/form '(do (set! *print-length* 2) (vec (range 9)))}
          evaluated (ev/evaluate request)
          shown (ev/shown-result (:seon.sci.admit/value evaluated) (assoc request :seon.db/db database) nil)]
      {:ctx-has-session (contains? ctx :seon.sci.eval/print-session)
       :value (:seon.sci.admit/value evaluated) :options (:seon.print/options evaluated)
       :error (:seon.cluster.eval/error evaluated) :shown (:seon.eval/shown shown)}))))
(shutdown-agents)
(System/exit 0)
