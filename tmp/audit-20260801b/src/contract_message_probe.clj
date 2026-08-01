(ns contract-message-probe
  (:require [seon.instrument :as instrument]
            [seon.instrument-test :as instrument-test]))

(defn run-probe
  "Measure a many-problem contract headline when the optional caps are absent."
  [& _]
  (let [offending
        (into {:seon.instrument-test/expected 1}
              (map (fn [index]
                     [(keyword "seon.instrument-test.unexpected" (str index))
                      index]))
              (range 200))]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic})
      (let [failure (try
                      (instrument-test/closed-map-input offending)
                      (catch Exception thrown thrown))
            data (ex-data failure)]
        (prn {:audit/message-chars (count (:seon.error/message data))
              :audit/problem-count
              (get-in data [:seon.error/data :seon.instrument/problem-count])
              :audit/args-present?
              (contains? (:seon.error/data data) :seon.instrument/args)}))
      (finally
        (instrument/remove!)))))

(apply run-probe *command-line-args*)
