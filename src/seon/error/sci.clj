(ns seon.error.sci
  "Classify SCI failures into Seon's structural error value."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [sci.lang]
            [seon.ai.tokens :as tokens]
            [seon.error.instrument :as instrument]
            [seon.repair.candidates :as candidates]
            [seon.schema :as schema]))

(schema/register!
 :seon.error.sci/class
 [:enum :schema-input :schema-output :schema-arity :resolution :arity
  :interrupt :refusal :preflight :runtime])
(schema/register! ::throwable 'some?)
(schema/register! ::context 'some?)
(schema/register! ::home-ns :symbol)
(schema/register!
 ::classify-request
 [:map {:closed true}
  [::throwable ::throwable]
  [::context {:optional true} ::context]
  [::home-ns {:optional true} ::home-ns]])
(schema/register! ::symbol :symbol)
(schema/register! ::line :int)
(schema/register! ::column :int)
(schema/register! ::ns :symbol)
(schema/register! ::fn-sym :symbol)
(schema/register! ::refused-var :symbol)
(schema/register! ::callstack-head [:vector :map])
(schema/register! ::frames [:vector :map])
(schema/register! ::ex-chain [:vector :map])
(schema/register! ::error-value :map)
(schema/register! ::token-budget :seon.ai.tokens/budget)

(def default-error-head-token-cap
  "Default abridged-error budget; W1 moves it to the config fact
   `:seon.config.render/error-head-token-cap`."
  120)

(defn- cause-chain
  [^Throwable throwable]
  (vec (take-while some? (iterate ex-cause throwable))))

(defn- first-line
  [value]
  (str (first (str/split-lines (str value)))))

(defn- frame-value
  [{frame-ns :ns frame-name :name :keys [line column]}]
  (cond-> {}
    (and frame-ns frame-name)
    (assoc ::fn-sym (symbol (str frame-ns) (str frame-name)))

    frame-ns
    (assoc ::ns (symbol (str frame-ns)))

    (int? line)
    (assoc ::line line)

    (int? column)
    (assoc ::column column)))

(defn- frames
  [^Throwable throwable]
  (mapv frame-value (or (sci/stacktrace throwable) [])))

(defn- namespace-symbols
  [ctx]
  (try
    (into []
          (mapcat
           (fn [sci-ns]
             (let [ns-sym (sci/ns-name sci-ns)
                   publics (sci/eval-form
                            ctx (list 'ns-publics (list 'quote ns-sym)))]
               (map (fn [name-sym]
                      (symbol (str ns-sym) (str name-sym)))
                    (keys publics)))))
          (sci/all-ns ctx))
    (catch Throwable _
      [])))

(defn- candidate-priority
  [home-ns candidate]
  [(cond
     (= (namespace candidate) (str home-ns)) 0
     (= (namespace candidate) "clojure.core") 2
     :else 1)
   (str candidate)])

(defn- resolution-suggestions
  [ctx home-ns unresolved]
  (if-not ctx
    []
    (let [candidate-syms (namespace-symbols ctx)
          unresolved-name (name unresolved)
          qualified-ns (namespace unresolved)
          eligible (if qualified-ns
                     (filter #(= qualified-ns (namespace %)) candidate-syms)
                     candidate-syms)
          by-name (group-by name eligible)]
      (mapv
       (fn [{:seon.repair/keys [to] :as ranked}]
         (let [chosen (first (sort-by #(candidate-priority home-ns %)
                                      (get by-name to)))]
           (assoc ranked :seon.repair/to (str chosen))))
       (candidates/rank-candidates unresolved-name (keys by-name))))))

(defn- sci-var-symbol
  [value]
  (when (instance? sci.lang.Var value)
    (sci/var->symbol value)))

(defn- refusal-data
  [causes]
  (some (fn [^Throwable cause]
          (let [value (:var (ex-data cause))]
            (when (and (instance? sci.lang.Var value)
                       (:sci/built-in (meta value)))
              {::refused-var (sci-var-symbol value)})))
        causes))

(defn- interrupt-data
  [causes]
  (some (fn [^Throwable cause]
          (let [data (ex-data cause)]
            (when (identical? sci.utils/interrupt-marker
                              (:sci.impl/interrupt data))
              (dissoc data :sci.impl/interrupt))))
        causes))

(defn- resolution-data
  [causes]
  (some (fn [^Throwable cause]
          (let [data (ex-data cause)]
            (when (and (= "analysis" (:phase data))
                       (symbol? (:sci.impl/symbol data)))
              data)))
        causes))

(defn- instrument-data
  [causes]
  (some (fn [^Throwable cause]
          (let [data (ex-data cause)]
            (when (instrument/instrument-error? data) data)))
        causes))

(defn- arity-cause
  [causes]
  (some #(when (instance? clojure.lang.ArityException %) %) causes))

(defn- arglist-arity
  [arglist]
  (let [[required tail] (split-with #(not= '& %) arglist)
        minimum (count required)]
    (if (seq tail)
      {:min minimum}
      {:min minimum :max minimum})))

(defn- raw-callstack-frames
  [causes]
  (some (fn [^Throwable cause]
          (some-> cause ex-data :sci.impl/callstack deref seq))
        causes))

(defn- frame-fn-meta
  [causes]
  (some (fn [frame]
          (let [fn-meta (:f-meta frame)]
            (when (and (:name fn-meta) (:ns fn-meta)) fn-meta)))
        (raw-callstack-frames causes)))

(defn- fn-meta-symbol
  [fn-meta]
  (let [ns-value (:ns fn-meta)
        ns-sym (if (symbol? ns-value) ns-value (sci/ns-name ns-value))]
    (symbol (str ns-sym) (str (:name fn-meta)))))

(defn- arity-data
  [causes ^clojure.lang.ArityException arity-ex]
  (let [fn-meta (frame-fn-meta causes)
        fn-sym (when fn-meta (fn-meta-symbol fn-meta))
        arglists (:arglists fn-meta)]
    (cond-> {:seon.error.malli/arity (.-actual arity-ex)}
      fn-sym (assoc ::symbol fn-sym)
      (seq arglists) (assoc :seon.error.malli/arities
                            (into #{} (map arglist-arity) arglists)))))

(defn- error-value
  [message data]
  {:seon.error/message message
   :seon.error/kind :agent
   :seon.error/data data})

(defn classify
  "Classify one SCI throwable using its originating context."
  {:malli/schema [:=> [:cat ::classify-request] :map]}
  [{::keys [throwable context home-ns]}]
  (let [causes (cause-chain throwable)
        malli (instrument-data causes)
        resolution (resolution-data causes)
        interrupt (interrupt-data causes)
        refusal (refusal-data causes)
        arity (arity-cause causes)
        runtime-frames (frames throwable)
        [class data]
        (cond
          malli
          [(case (:seon.error/kind malli)
             :seon.error.kind/malli-instrument-input :schema-input
             :seon.error.kind/malli-instrument-output :schema-output
             :seon.error.kind/malli-instrument-arity :schema-arity
             :runtime)
           malli]

          resolution
          [:resolution
           (cond-> {::symbol (:sci.impl/symbol resolution)
                    :seon.repair/suggestions
                    (resolution-suggestions context home-ns
                                            (:sci.impl/symbol resolution))}
             (int? (:line resolution)) (assoc ::line (:line resolution))
             (int? (:column resolution)) (assoc ::column (:column resolution)))]

          arity
          [:arity (arity-data causes arity)]

          interrupt
          [:interrupt interrupt]

          refusal
          [:refusal refusal]

          :else
          [:runtime {::callstack-head (vec (take 3 runtime-frames))}])]
    (error-value (first-line (ex-message throwable))
                 (cond-> (assoc data ::class class)
                   (and home-ns (not malli)) (assoc ::home-ns home-ns)))))

(defn- render-arity
  [{::keys [symbol] :seon.error.malli/keys [arity arities]}]
  (str symbol " takes " (pr-str arities) " — called with " arity " args."))

(defn- render-place
  [{::keys [fn-sym line ns]}]
  (when (or fn-sym ns)
    (str "in " (or fn-sym ns) (when line (str " (line " line ")")))))

(defn steering-head
  "Render one classified error as a token-bounded steering head."
  {:malli/schema [:=> [:catn [::error-value :map]
                             [::token-budget :seon.ai.tokens/budget]]
                  :string]}
  [{message :seon.error/message data :seon.error/data :as error-value}
   token-budget]
  (let [{::keys [class symbol line refused-var home-ns callstack-head]
         :seon.repair/keys [suggestions]} data
        head
        (case class
          :schema-input
          (instrument/render-malli-error data)

          :schema-output
          (instrument/render-malli-error data)

          :schema-arity
          (instrument/render-malli-error data)

          :resolution
          (str "Unable to resolve " symbol
               (when line (str " (line " line ")")) "."
               (when-let [suggestion (:seon.repair/to (first suggestions))]
                 (str " Did you mean " suggestion "?"))
               (when home-ns (str " Your fns live in " home-ns ".")))

          :arity
          (render-arity data)

          :interrupt
          "Interrupted at the eval deadline. The form stopped mid-run; check committed writes before re-running."

          :refusal
          (str refused-var " is a shared built-in and is read-only. Define your own function"
               (when home-ns (str " in " home-ns)) ".")

          :runtime
          (str message
               (when-let [place (some render-place callstack-head)]
                 (str " — " place ".")))

          message)]
    (tokens/clip-str head token-budget)))

(defn- detail-ex-data
  [data]
  (cond-> (dissoc data :sci.impl/callstack :sci.impl/interrupt)
    (contains? data :var) (assoc :var (sci-var-symbol (:var data)))))

(defn detail
  "Build full addressable SCI error detail from the originating context."
  {:malli/schema [:=> [:cat ::classify-request] :map]}
  [{::keys [throwable] :as request}]
  (let [classified (classify request)
        data (:seon.error/data classified)]
    (cond-> {::class (::class data)
             ::frames (frames throwable)
             ::ex-chain
             (mapv (fn [^Throwable cause]
                     {:seon.error/message (first-line (ex-message cause))
                      :seon.error/data (detail-ex-data (or (ex-data cause) {}))})
                   (cause-chain throwable))}
      (seq (:seon.repair/suggestions data))
      (assoc :seon.repair/suggestions (:seon.repair/suggestions data))

      (instrument/instrument-error? data)
      (merge data))))
