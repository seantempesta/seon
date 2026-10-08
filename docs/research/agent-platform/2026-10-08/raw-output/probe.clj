;; Run from the checkout: bb --classpath script:src:resources FILE ROOT sizes|suites|full|bench
;; PREPL is transport only: measurements enter seon.mcp/call with mode sci.
;; No production Vars are replaced. The supplied root must be disposable for suites.
(require '[seon.operator :as operator] '[cheshire.core :as json]
         '[clojure.string :as str])
(def root (first *command-line-args*))
(def action (second *command-line-args*))
(def advertisement (#'operator/advertisement root "default"))
(defn host
  "Transport a bounded form to the explicitly supplied root."
  [code]
  (#'operator/prepl-value! advertisement code 300000))
(defn mcp
  "Evaluate through the installed MCP SCI entrance as raw-output."
  [code]
  (json/parse-string
   (host (str "(seon.mcp/call "
              (pr-str (json/generate-string {:name "eval_clj"
                                             :arguments {:agent "raw-output" :cluster "default"
                                                         :mode "sci" :code code}})) ")")) true))
(def measurement "(let [start (System/nanoTime) v FORM elapsed (/ (- (System/nanoTime) start) 1e6) mstart (System/nanoTime) p (seon.render.value/prepare {:seon.render/value (seon.print/full v) :seon.render.call/id [:raw-output :measure] :seon.render.value/options {:seon.render.value/structural? true}}) t (:seon.render.value/text p)] {:probe LABEL :bytes (alength (.getBytes t \"UTF-8\")) :tokens (seon.ai.tokens/estimate t) :call-ms elapsed :measure-ms (/ (- (System/nanoTime) mstart) 1e6) :elisions (:seon.render.value/elisions p) :count (when (coll? v) (count v)) :keys (when (map? v) (vec (keys v)))})")
(def cases [
  ["task.open" "(my.task/open)"]
  ["task.steps" "(my.task/steps {})"]
  ["agent.identity" "(my.agent/identity)"]
  ["agent.settings" "(my.agent/settings)"]
  ["agent.branch" "(my.agent/branch)"]
  ["agent.branches" "(my.agent/branches)"]
  ["program.source" "(my.program/source 'seon.render/render-form)"]
  ["program.search" "(my.program/search :seon.error/message)"]
  ["program.sizes" "(my.program/sizes 'seon.db)"]
  ["program.callers" "(my.program/callers {:seon.program/subject 'seon.db/pull})"]
  ["program.tests-reaching" "(my.program/tests-reaching {:seon.program/subject 'seon.render/render-form})"]
  ["db.pull" "(seon.db/pull '[*] [:seon.ns/name 'seon.render])"]
  ["db.q" "(seon.db/q '[:find ?s :where [_ :seon.fn/sym ?s]])"]
  ["health" "(seon.health/report {:seon.boot/cluster-name \"default\"})"]
  ["doc" "(doc seon.render/render-form)"]
  ["dir" "(dir seon.render)"]
  ["program.proposals" "(my.program/proposals)"]
  ["test.empty" "(my.test/run-owned {:seon.ns/name 'raw-output.empty})"]
  ["source.test-run" "(my.program/source 'seon.test/run)"]
  ["source.eval" "(my.program/source 'seon.sci.eval/evaluate)"]
  ["program.breaks" "(my.program/breaks {:seon.program/subject 'seon.db/pull})"]
  ["cache" "(seon.cache/status)"]
  ["error.contract" "(try (seon.render.value/render-ai-data nil) (catch Exception e (ex-data e)))"]
  ["profile.top" "(seon.profile/top (seon.profile/cells) 15)"]])
(defn measure
  "Measure a finite raw value through the structural value printer."
  [label form]
  (mcp (-> measurement (str/replace "FORM" form) (str/replace "LABEL" (pr-str label)))))
(defn emit
  "Write the transport evidence as one JSON line."
  [value] (println (json/generate-string value)))
(case action
  "one" (emit (measure (nth *command-line-args* 2) (nth *command-line-args* 3)))
  "sizes" (doseq [[label form] cases] (emit {:label label :reply (measure label form)}))
  "full" (let [reply (mcp "{:nested {:seon.render/ai {:summary :rendered} :payload (vec (range 90))}}")
               text (get-in reply [:content 0 :text])
               handle (re-find #"result/e[0-9a-f]+" text)]
           (emit {:step :original :reply reply})
           (emit {:step :dig :reply (mcp (str "(:nested " handle ")"))})
           (emit {:step :whole :reply (mcp (str "(= (vec (range 90)) (get-in " handle " [:nested :payload]))"))})
           (emit {:step :elision :reply (mcp (str "(drop 32 (get-in " handle " [:nested :payload]))"))}))
  "suites"
  (do
    (when (= "." root) (throw (ex-info "Suites require an explicit disposable root." {})))
    (emit {:suite-result
           (host "(let [h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances \"default\"))
                         before (into {} (map (fn [c] [(:seon.profile/sym c) (:seon.profile/calls (seon.profile/observation c))])) (seon.profile/cells))
                         r (seon.test/run {:seon.test/execution h :seon.test/recording-connection (:seon.db/connection h)
                                           :seon.test/policy :named
                                           :seon.test/namespaces #{'my.program-test 'my.agent-test 'seon.profile-test 'seon.render.value-test}
                                           :seon.test/identities #{'seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings}})
                         p (seon.render.value/prepare {:seon.db/db @(:seon.db/connection h) :seon.render/value (seon.print/full r)
                                                       :seon.render.call/id [:raw-output :suite]
                                                       :seon.render.value/options {:seon.render.value/structural? true}})
                         t (:seon.render.value/text p)]
                     {:tally (seon.test/tally r)
                      :bytes (alength (.getBytes t \"UTF-8\")) :tokens (seon.ai.tokens/estimate t)
                      :results (mapv #(select-keys % [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.error/message]) (:seon.test/results r))
                      :frequency (->> (seon.profile/cells)
                                      (map (fn [c] [(:seon.profile/sym c) (- (:seon.profile/calls (seon.profile/observation c)) (get before (:seon.profile/sym c) 0))]))
                                      (filter #(pos? (second %))) (sort-by second >) vec)})")}))
  "bench"
  (emit
   {:benchmark
    (host
     "(letfn [(sample [value]
                (loop [pending (list value) n 0 chars 0]
                  (if (or (empty? pending) (= n 128))
                    {:nodes n :characters chars :complete? (empty? pending)}
                    (let [v (first pending) tail (rest pending)]
                      (cond
                        (string? v) (recur tail (inc n) (+ chars (count v)))
                        (or (keyword? v) (symbol? v)) (recur tail (inc n) (+ chars (count (name v)) (count (namespace v))))
                        (and (not (record? v)) (or (map? v) (vector? v) (set? v)))
                        (recur (concat (take (- 128 n) (if (map? v) (mapcat identity v) v)) tail) (inc n) (+ chars 2))
                        (seq? v) {:nodes n :characters chars :complete? false :unknown :lazy}
                        :else (recur tail (inc n) (inc chars)))))))
              (trial [value sampled?]
                (let [sink (java.util.concurrent.atomic.LongAdder.) start (System/nanoTime)]
                  (dotimes [i 20000]
                    (when (and sampled? (zero? (bit-and i 63)))
                      (.add sink (long (:characters (sample value))))))
                  {:ns-per-call (/ (- (System/nanoTime) start) 20000.0) :checksum (.sum sink)}))]
       (let [small {:x 1 :description \"ordinary result\"}
             large (vec (repeat 10000 {:x 1 :description \"ordinary result\"}))]
         (dotimes [_ 5] (trial small true) (trial large true))
         {:small (sample small) :large (sample large)
          :baseline (mapv (fn [_] (trial small false)) (range 5))
          :small-sampled (mapv (fn [_] (trial small true)) (range 5))
          :large-sampled (mapv (fn [_] (trial large true)) (range 5))}))")})
  (throw (ex-info "Unknown action" {:action action})))

