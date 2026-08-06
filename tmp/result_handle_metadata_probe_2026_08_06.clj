(ns result-handle-metadata-probe-2026-08-06
  (:require [sci.core :as sci]
            [seon.config :as config]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [my.message :as message]))

(deftype LazyResult [eid value]
  clojure.lang.IDeref
  (deref [_] value)
  clojure.lang.ILookup
  (valAt [_ lookup-key] (get value lookup-key))
  (valAt [_ lookup-key not-found] (get value lookup-key not-found)))

(defn- caught
  [f]
  (try
    {:value (f)}
    (catch Throwable failure
      {:throwable (.getName (class failure))
       :message (ex-message failure)
       :data (ex-data failure)})))

(defn- admission-request
  [value]
  {:seon.sci.admit/value value
   :seon.sci.admit/interrupt-fn (constantly nil)
   :seon.sci.admit/caps (config/result-caps (config/defaults))
   :seon.config/on-core-error :record})

(defn- admitted
  [value]
  (let [result (admit/admit (admission-request value))]
    {:input-meta (meta value)
     :admitted-value (:seon.sci.admit/value result)
     :admitted-meta (meta (:seon.sci.admit/value result))
     :result-edn (:seon.cluster.eval/result-edn result)
     :print-node (:seon.sci.admit/print-node result)}))

(defn- raw-sci-metadata
  []
  (let [ctx (sci/init {})]
    (sci/add-namespace! ctx 'result {})
    (sci/intern ctx 'result 'value
                (with-meta {:answer 42} {:seon.result/eid 88}))
    (sci/binding [sci/ns (sci/create-ns 'user)]
      (sci/eval-string*
       ctx
       "{:direct (meta result/value)
         :assoc (meta (assoc result/value :more true))
         :selected (meta (select-keys result/value [:answer]))
         :nested-meta (meta (get [result/value] 0))}"))))

(defn- full-evaluation
  []
  (let [evaluation
        (sci.eval/evaluate
         {:seon.cluster.run.form/source
          "(with-meta {:answer 42} {:seon.result/eid 88})"
          :seon.sci.eval/ctx (sci.eval/build-base-ctx)
          :seon.sci.eval/time-limit-ms 5000
          :seon.sci.admit/caps (config/result-caps (config/defaults))
          :seon.config/on-core-error :record})]
    {:value (:seon.sci.admit/value evaluation)
     :value-meta (meta (:seon.sci.admit/value evaluation))
     :result-edn (:seon.cluster.eval/result-edn evaluation)
     :error (:seon.cluster.eval/error evaluation)}))

(defn -main
  "Print metadata, admission, proxy, and message-boundary evidence."
  [& _]
  (let [marked-map (with-meta {:answer 42} {:seon.result/eid 88})
        marked-vector (with-meta [1 2 3] {:seon.result/eid 88})
        lazy-result (->LazyResult 88 {:answer 42})]
    (prn
     {:scalar-metadata
      {:string (caught #(with-meta "large bytes" {:seon.result/eid 88}))
       :number (caught #(with-meta 42 {:seon.result/eid 88}))
       :nil (caught #(with-meta nil {:seon.result/eid 88}))}
      :raw-sci-metadata (raw-sci-metadata)
      :admission
      {:map (admitted marked-map)
       :vector (admitted marked-vector)
       :lazy-result (admitted lazy-result)}
      :proxy
      {:deref @lazy-result
       :lookup (:answer lazy-result)
       :map? (map? lazy-result)
       :equals-value (= lazy-result {:answer 42})
       :seq (caught #(seq lazy-result))
       :invoke-as-fn (caught #(lazy-result :answer))
       :arithmetic (caught #(+ 1 lazy-result))}
      :current-message-boundary
      (message/send "recipient" marked-map)
      :full-evaluation (full-evaluation)})))

(apply -main *command-line-args*)
