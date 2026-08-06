(ns result-handle-reader-probe-2026-08-06
  (:require [sci.core :as sci]
            [seon.sci.reader :as reader]))

(defrecord ResultRef [eid])

(defn- read-result
  [source tags]
  (let [read-value
        (reader/read {:seon.sci.reader/text source
                      :seon.sci.reader/ns 'user
                      :seon.sci.reader/aliases {}
                      :seon.sci.reader/refers {}
                      :seon.sci.reader/tags tags})]
    (if (vector? read-value)
      {:source source
       :form (:seon.sci.reader/form (first read-value))}
      {:source source
       :error read-value})))

(defn- caught
  [f]
  (try
    {:value (f)}
    (catch Throwable failure
      {:throwable (.getName (class failure))
       :message (ex-message failure)
       :data (ex-data failure)})))

(defn- eval-read
  [ctx source tags]
  (let [{:keys [form error]} (read-result source tags)]
    (if error
      {:source source :reader-error error}
      (assoc (caught #(sci/binding [sci/ns (sci/create-ns 'user)]
                        (sci/eval-form ctx form)))
             :source source
             :form form))))

(defn -main
  "Print reader and SCI evaluation evidence for candidate result faces."
  [& _]
  (let [ctx (sci/init {})
        _ (sci/add-namespace! ctx 'result {})
        _ (sci/intern ctx 'result 'eid-88 {:answer 42})
        record-tags {'seon/result (fn [eid] (->ResultRef eid))}
        symbol-tags {'seon/result (fn [eid]
                                    (symbol "result" (str "eid-" eid)))}
        map-tags {'seon/result (fn [eid]
                                {:seon.result/eid eid
                                 :answer 42})}
        tagged-tags {'seon/result (fn [eid]
                                   (tagged-literal 'seon/result eid))}
        list-tags {'seon/result (fn [_]
                                 '(+ 40 2))}]
    (prn
     {:pins {:sci "2db3358cba913b6fbbe49c7b5b34d7ac72715924"
             :edamame "38e627467daa3f6f1e5a8eb6421f702d2a940b7f"}
      :readability
      [(read-result "result/88" {})
       (read-result "result/eid-88" {})
       (read-result "#seon/result 88" record-tags)
       (read-result "'#seon/result 88" record-tags)
       (read-result "#unknown/result 88" {})]
      :record-tag
      [(eval-read ctx "#seon/result 88" record-tags)
       (eval-read ctx "'#seon/result 88" record-tags)
       (eval-read ctx "`#seon/result 88" record-tags)]
      :symbol-tag
      [(eval-read ctx "#seon/result 88" symbol-tags)
       (eval-read ctx "'#seon/result 88" symbol-tags)
       (eval-read ctx "`#seon/result 88" symbol-tags)]
      :map-tag
      [(eval-read ctx "#seon/result 88" map-tags)
       (eval-read ctx "'#seon/result 88" map-tags)]
      :tagged-literal-value
      {:printed (pr-str (tagged-literal 'seon/result 88))
       :evaluations
       [(eval-read ctx "#seon/result 88" tagged-tags)
        (eval-read ctx "'#seon/result 88" tagged-tags)]}
      :list-tag
      [(eval-read ctx "#seon/result 88" list-tags)
       (eval-read ctx "'#seon/result 88" list-tags)]
      :readable-symbol
      [(eval-read ctx "result/eid-88" {})
       (eval-read ctx "'result/eid-88" {})]})))

(apply -main *command-line-args*)
