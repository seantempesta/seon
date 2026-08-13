(ns my.background
  "Start and inspect capability requests that may finish later."
  (:refer-clojure :exclude [await])
  (:require [my.run :as run]
            [seon.db :as db]
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

(defn- invalid-call
  []
  {:seon.error/kind ::invalid-call
   :seon.error/message
   "background needs exactly one direct capability call with one request value, optionally preceded by an options map."
   :seon.error/data {}})

(defmacro background
  "Start one capability request without waiting for its result.

  Takes exactly one direct capability call and returns its
  `:seon.effect/id` lookup ref. Use it for work that can finish after the
  current run.

      (background (my.shell/run {:my.shell/command [\"make\" \"build\"]}))

  Background work is NOT bounded by the run that started it: it never
  inherits the submitting turn's arm, so the turn's time limit does not cut
  it and a turn ending never cancels work already in flight. It is bounded
  by its OWN limit instead. The default is the cluster's
  `:seon.config.effect.background/time-limit-ms` fact, and this form may
  name its own to go either way — tighter for a probe, longer for a slow
  build — with no clamp:

      (background {:seon.effect/time-limit-ms 3600000}
                  (my.web/fetch {:my.web/url url}))

  Reaching that limit interrupts the work and settles the receipt; poll or
  await the returned ref either way."
  [& forms]
  (let [[execution call] (if (= 2 (count forms)) forms [nil (first forms)])]
    (if (and (<= 1 (count forms) 2)
             (seq? call)
             (= 2 (count call))
             (symbol? (first call)))
      (let [[owner request] call]
        (list 'seon.effect/request!
              (list 'var owner)
              request
              (if execution
                (list 'merge {:seon.effect/background? true} execution)
                {:seon.effect/background? true})))
      (invalid-call))))

(defn poll
  "Read the current result of a background request.

  Takes a `:seon.effect/id` lookup ref and returns its bounded request/result
  descriptor or a flat error value. Use it to inspect work started with
  `background`."
  {:malli/schema
   [:=> [:cat :my.background/result]
    [:or :my.background/receipt :seon.error/value]]}
  [result-ref]
  (if-not (and (vector? result-ref)
               (= 2 (count result-ref))
               (= :seon.effect/id (first result-ref))
               (string? (second result-ref)))
    {:seon.error/kind ::invalid-result
     :seon.error/message "poll needs a :seon.effect/id lookup ref."
     :seon.error/data {:my.background/result result-ref}}
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
      {:seon.error/kind ::missing-result
       :seon.error/message "The background effect receipt does not exist."
       :seon.error/data {:my.background/result result-ref}})))

(defn await
  "Wait for a background request or return its finished result.

  Takes a `:seon.effect/id` lookup ref and a continuation note. Returns the
  finished descriptor, a `my.run/wait` value while pending, or a flat error.
  Use it when the next run should resume after the request settles."
  {:malli/schema
   [:=> [:cat :my.background/result :my.run/note]
    [:or :my.background/receipt :my.run/wait :seon.error/value]]}
  [result-ref note]
  (let [descriptor (poll result-ref)]
    (if (or (:seon.error/kind descriptor)
            (:seon.effect/result-edn descriptor)
            (:seon.effect/interrupted-at descriptor))
      descriptor
      (let [wait-value (run/wait note)]
        (if (:seon.error/kind wait-value)
          wait-value
          (assoc wait-value :my.background/result result-ref))))))
