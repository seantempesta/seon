(ns seon.background
  "Start and inspect capability requests that may finish later."
  (:refer-clojure :exclude [await])
  (:require [seon.run :as run]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn render-ai
  "Render a background error as steering text."
  {:malli/schema
   [:=>
    [:cat [:or :my.background/invalid-call-error
           :my.background/invalid-result-error
           :my.background/missing-result-error]]
    :seon.render/ai]}
  [error]
  (:seon.error/message error))

(defn render-html
  "Render a background error as Hiccup."
  {:malli/schema
   [:=>
    [:cat [:or :my.background/invalid-call-error
           :my.background/invalid-result-error
           :my.background/missing-result-error]]
    :seon.render/hiccup]}
  [error]
  [:p (:seon.error/message error)])

(defn poll
  "Read the current result of a background request.

  Takes a `:seon.effect/id` lookup ref and returns its bounded request/result
  descriptor or a flat error value. Use it to inspect work started with
  `background`."
  {:malli/schema
   [:=> [:cat :my.background/result]
    [:or :my.background/receipt
     :my.background/invalid-result-error
     :my.background/missing-result-error
     :seon.db/invalid-read-error
     :seon.schema/missing-projection-error]]}
  [result-ref]
  (if-not (and (vector? result-ref)
               (= 2 (count result-ref))
               (= :seon.effect/id (first result-ref))
               (string? (second result-ref)))
    {:seon.error/at (java.util.Date.)
      :seon.error/layer :my.background/poll
      :seon.error/operation 'seon.background/poll
      :seon.error/message "poll needs a :seon.effect/id lookup ref."
      :seon.error/fix "Supply [:seon.effect/id <id>]."
      :seon.error/data {:my.background/result result-ref}
      :my.background/result-observation (pr-str result-ref)
      :seon.error/member :my.background/result
      :seon.error/expected "a :seon.effect/id lookup ref"
      :seon.error/offending result-ref}
    (if-let [receipt
             (db/pull (db/db db/*conn*)
                      [:seon.effect/id
                       :seon.effect/request-edn
                       :seon.effect/result-edn
                       :seon.effect/result-blob
                       :seon.effect/result-size
                       :seon.effect/duration-ms
                       :seon.effect/interrupted-at]
                      result-ref)]
      (cond-> (select-keys receipt
                          [:seon.effect/id :seon.effect/request-edn])
        (:seon.effect/result-edn receipt)
        (merge (select-keys receipt
                            [:seon.effect/result-edn
                             :seon.effect/result-blob
                             :seon.effect/result-size
                             :seon.effect/duration-ms]))

        (:seon.effect/interrupted-at receipt)
        (assoc :seon.effect/interrupted-at
               (:seon.effect/interrupted-at receipt)))
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.background/poll
        :seon.error/operation 'seon.background/poll
        :seon.error/message "The background effect receipt does not exist."
        :seon.error/fix "Use the lookup ref returned by background."
        :seon.error/data {:my.background/result result-ref}
        :my.background/missing-result-ref result-ref
        :seon.error/member :my.background/result
        :seon.error/expected "an existing background effect receipt"})))

(defn await
  "Wait for a background request or return its finished result.

  Takes a `:seon.effect/id` lookup ref and a continuation note. Returns the
  finished descriptor, a `my.turn/wait` value while pending, or a flat error.
  Use it when the next run should resume after the request settles."
  {:malli/schema
   [:=> [:cat :my.background/result :my.turn/note]
    [:or :my.background/receipt :my.turn/wait
     :my.background/invalid-result-error
     :my.background/missing-result-error
     :seon.db/invalid-read-error
     :seon.schema/missing-projection-error
     :my.turn/blank-note-error]]}
  [result-ref note]
  (let [descriptor (poll result-ref)]
    (if (or (contains? descriptor :my.background/result-observation)
            (contains? descriptor :my.background/missing-result-ref)
            (contains? descriptor :seon.db/read-operation)
            (contains? descriptor :seon.schema/missing-projection)
            (:seon.effect/result-edn descriptor)
            (:seon.effect/interrupted-at descriptor))
      descriptor
      (let [wait-value (run/wait note)]
        (if (contains? wait-value :my.turn/blank-note)
          wait-value
          (assoc wait-value :my.background/result result-ref))))))
