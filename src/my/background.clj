(ns my.background
  "Start and inspect capability requests that may finish later."
  (:refer-clojure :exclude [await])
  (:require [seon.background :as background]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- invalid-call
  []
  {:seon.error/kind ::invalid-call
   :seon.error/message
   "background needs exactly one direct capability call with one request value, optionally preceded by an options map."
   :seon.error/data {}
   :my.background/invalid-call true})

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
  "Read a background request’s current result."
  {:malli/schema [:=> [:cat [:map [:my.background/result :my.background/result]]] [:or :my.background/receipt :seon.error/value]]}
  [request]
  (background/poll (:my.background/result request)))

(defn await
  "Return a background result or the condition I am waiting for."
  {:malli/schema [:=> [:cat [:map [:my.background/result :my.background/result] [:my.turn/note :my.turn/note]]] [:or :my.background/receipt :my.turn/wait :seon.error/value]]}
  [request]
  (background/await (:my.background/result request) (:my.turn/note request)))
