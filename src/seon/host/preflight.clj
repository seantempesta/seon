(ns seon.host.preflight
  "Own JVM-host delimiter repair and disposable SCI symbol preflight."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.impl.analyzer :as analyzer]
            [seon.error.sci :as error.sci]
            [seon.repair :as repair]
            [seon.repair.candidates :as candidates]))

(set! *warn-on-reflection* true)

(def ^:private repair-levels
  #{:off :safe-syntax :symbols :aggressive})

(def ^:private preflight-skip-heads
  '#{ns require require-macros use import in-ns})

(defn repair-policy
  "Normalize repair dials from the invocation's acquired configuration."
  {:malli/schema [:=> [:cat :map] :map]}
  [configuration]
  (let [level (:seon.config.repair/level configuration)]
    {:seon.config.repair/level
     (if (contains? repair-levels level) level :symbols)
     :seon.config.repair/classes
     (or (:seon.config.repair/classes configuration) {})
     :seon.config.repair/max-fixes-per-form
     (or (:seon.config.repair/max-fixes-per-form configuration) 1)
     :seon.config.repair/budget-ms
     (or (:seon.config.repair/budget-ms configuration) 50)}))

(defn- class-enabled?
  [policy class]
  (repair/class-enabled?
   {:seon.repair/level (:seon.config.repair/level policy)
    :seon.repair/classes (:seon.config.repair/classes policy)
    :seon.repair/class class}))

(defn- namespace-object!
  [ctx ns-sym]
  (or (sci/find-ns ctx ns-sym)
      (sci/eval-string* ctx (str "(create-ns '" ns-sym ")"))))

(defn- parsed-sources
  [ctx ns-sym source]
  (let [fork (sci/fork ctx)
        ns-obj (namespace-object! fork ns-sym)
        reader (sci/source-reader source)]
    (sci/with-bindings {sci/ns ns-obj}
      (loop [sources []]
        (let [[form form-source] (sci/parse-next+string fork reader)]
          (if (= :sci.core/eof form)
            sources
            (recur (conj sources form-source))))))))

(defn repair-read-entry
  "Repair one read failure into exact-source ordinary form entries."
  {:malli/schema [:=> [:cat :any :symbol :map :map] [:or :nil :map]]}
  [ctx ns-sym configuration entry]
  (let [policy (repair-policy configuration)
        source (or (:seon.repl/eval-source entry)
                   (:seon.repl/source entry)
                   "")]
    (when (and (= :read (:seon.repl/kind entry))
               (class-enabled? policy :seon.repair/delimiters))
      (let [reads? (fn [candidate]
                     (try
                       (boolean (seq (parsed-sources ctx ns-sym candidate)))
                       (catch Throwable _ false)))
            repaired (repair/repair-source
                      {:seon.repair/source source
                       :seon.repair/reads? reads?})]
        (when (:seon.repair/repaired? repaired)
          (let [note (repair/repair-note
                      {:seon.repair/changes
                       (:seon.repair/changes repaired)})]
            {:seon.host.preflight/entries
             (mapv
              (fn [index form-source]
                (cond-> {:seon.repl/kind :form
                         :seon.repl/source form-source}
                  (zero? index)
                  (assoc :seon.repl/narration
                         (str (when (seq (:seon.repl/narration entry))
                                (str (:seon.repl/narration entry) "\n"))
                              note)
                         :seon.repair/changes
                         (:seon.repair/changes repaired))))
              (range)
              (parsed-sources ctx ns-sym
                              (:seon.repair/source repaired)))}))))))

(defn- analysis-context
  [ctx]
  (let [upper-sym (gensym)
        closure-bindings (volatile! {upper-sym {0 {:syms {}}}})]
    (assoc ctx
           :parents [upper-sym 0]
           :closure-bindings closure-bindings)))

(defn analyze-disposable!
  "Analyze source on a fresh SCI fork and discard analysis mutations."
  {:malli/schema [:=> [:cat :any :symbol :string] :map]}
  [retained-ctx ns-sym source]
  (let [fork (sci/fork retained-ctx)
        ns-obj (namespace-object! fork ns-sym)
        ctx (analysis-context fork)]
    (try
      (sci/with-bindings {sci/ns ns-obj}
        (sci.ctx-store/with-ctx fork
          (analyzer/analyze ctx (sci/parse-string ctx source) true)))
      {:seon.host.preflight/ok? true}
      (catch Throwable throwable
        {:seon.host.preflight/ok? false
         :seon.host.preflight/throwable throwable}))))

(defn- resolution-data
  [throwable]
  (loop [current throwable]
    (when current
      (let [data (ex-data current)]
        (if (and (= "analysis" (:phase data))
                 (symbol? (:sci.impl/symbol data)))
          data
          (recur (.getCause ^Throwable current)))))))

(defn- sci-var-entry?
  [[key value]]
  (and (symbol? key) (instance? sci.lang.Var value)))

(defn- namespace-var-names
  [ctx ns-sym]
  (into #{}
        (comp (filter sci-var-entry?) (map (comp str key)))
        (get-in @(:env ctx) [:namespaces ns-sym])))

(defn- registry-var-names
  [registry]
  (into #{}
        (mapcat (fn [[_ entry]]
                  (map (comp str key)
                       (:seon.host.context/vars entry))))
        (if registry @registry {})))

(defn- resolved-prefix
  [ctx ns-sym token]
  (when-let [prefix (candidates/ns-part token)]
    (let [prefix-sym (symbol prefix)]
      (or (get-in @(:env ctx) [:namespaces ns-sym :aliases prefix-sym])
          prefix-sym))))

(defn- candidate-names
  [ctx registry ns-sym token]
  (if-let [target (resolved-prefix ctx ns-sym token)]
    (into (namespace-var-names ctx target)
          (map (comp str key))
          (if registry
            (get-in @registry [target :seon.host.context/vars])
            {}))
    (-> (namespace-var-names ctx ns-sym)
        (into (namespace-var-names ctx 'clojure.core))
        (into (registry-var-names registry))
        vec)))

(defn- contains-loader-form?
  [form]
  (cond
    (and (seq? form) (= 'quote (first form))) false
    (seq? form) (or (contains? preflight-skip-heads (first form))
                    (boolean (some contains-loader-form? (rest form))))
    (coll? form) (boolean (some contains-loader-form? form))
    :else false))

(defn- eligible?
  [policy ctx source]
  (and (or (class-enabled? policy :seon.repair/undeclared-var)
           (class-enabled? policy :seon.repair/def-vs-defn))
       (not (str/blank? source))
       (let [form (try (sci/parse-string ctx source)
                       (catch Throwable _ nil))]
         (and (not (and (symbol? form) (= "result" (namespace form))))
              (not (contains-loader-form? form))))))

(defn- error-envelope
  [ctx home-ns throwable suggestions ambiguous?]
  (let [classified
        (error.sci/classify
         {:seon.error.sci/throwable throwable
          :seon.error.sci/context ctx
          :seon.error.sci/home-ns home-ns})
        classified
        (cond-> classified
          (seq suggestions)
          (assoc-in [:seon.error/data :seon.repair/suggestions] suggestions)
          (seq suggestions)
          (assoc-in [:seon.error/data :seon.repair/ambiguous?]
                    (boolean ambiguous?)))
        classified
        (assoc classified :seon.error/message
               (error.sci/steering-head
                classified error.sci/default-error-head-token-cap))]
    {:seon.eval/ok? false :seon/error classified}))

(defn- terminal
  [ctx home-ns throwable suggestions ambiguous?]
  {:seon.host.preflight/status :terminal
   :seon.host.preflight/envelope
   (error-envelope ctx home-ns throwable suggestions ambiguous?)})

(defn preflight!
  "Run budgeted symbol preflight against disposable SCI forks."
  {:malli/schema [:=> [:cat :any :any :symbol :symbol :map :string] :map]}
  [retained-ctx registry home-ns ns-sym configuration source]
  (let [policy (repair-policy configuration)]
    (if-not (eligible? policy retained-ctx source)
      {:seon.host.preflight/status :skipped}
      (let [started-at (System/nanoTime)
            budget-ms (:seon.config.repair/budget-ms policy)
            over? #(> (/ (double (- (System/nanoTime) started-at)) 1000000.0)
                       budget-ms)
            max-fixes (:seon.config.repair/max-fixes-per-form policy)]
        (loop [candidate-source source
               fixes []
               applied-class nil]
          (let [{ok? :seon.host.preflight/ok?
                 throwable :seon.host.preflight/throwable}
                (analyze-disposable! retained-ctx ns-sym candidate-source)
                resolution (when throwable (resolution-data throwable))]
            (cond
              ok?
              (if (seq fixes)
                {:seon.host.preflight/status :fixed
                 :seon.repair/source candidate-source
                 :seon.repair/fixes fixes
                 :seon.repair/applied-class applied-class}
                {:seon.host.preflight/status :clean})

              resolution
              (let [token (str (:sci.impl/symbol resolution))
                    qualifier (candidates/ns-part token)
                    qualify (fn [name]
                              (if qualifier (str qualifier "/" name) name))
                    ranked (candidates/rank-candidates
                            (candidates/name-part token)
                            (candidate-names retained-ctx registry ns-sym token))
                    suggestions
                    (mapv #(update % :seon.repair/to qualify) ranked)]
                (if (or (over?)
                        (not (class-enabled? policy
                                             :seon.repair/undeclared-var))
                        (>= (count fixes) max-fixes))
                  (terminal retained-ctx home-ns throwable suggestions false)
                  (let [pick
                        (candidates/pick-winner
                         {:seon.repair/cands ranked
                          :seon.repair/over? over?
                          :seon.repair/passes?
                          (fn [candidate]
                            (let [to-token (qualify (:seon.repair/to candidate))
                                  trial-source
                                  (candidates/substitute-symbol
                                   candidate-source token to-token)]
                              (:seon.host.preflight/ok?
                               (analyze-disposable! retained-ctx ns-sym
                                                    trial-source))))})]
                    (if-let [winner (:seon.repair/winner pick)]
                      (let [to-token (qualify (:seon.repair/to winner))]
                        (recur (candidates/substitute-symbol
                                candidate-source token to-token)
                               (conj fixes {:seon.repair/from token
                                            :seon.repair/to to-token})
                               (or applied-class
                                   :seon.repair/undeclared-var)))
                      (terminal
                       retained-ctx home-ns throwable
                       (mapv #(update % :seon.repair/to qualify)
                             (or (:seon.repair/ambiguous pick) ranked))
                       (boolean (:seon.repair/ambiguous pick)))))))

              :else
              {:seon.host.preflight/status :clean})))))))
