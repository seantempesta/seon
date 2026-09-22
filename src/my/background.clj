(ns ^{:seon.ns/context-relevant? true} my.background
  "Start and inspect capability requests that may finish later."
  (:refer-clojure :exclude [await])
  (:require [seon.background :as background]
            [seon.error.refusal :as refusal]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- invalid-call
  [forms]
  {:seon.error/at (java.util.Date.)
    :seon.error/layer :my.background/call
    :seon.error/operation 'my.background/background
    :seon.error/message "background refused its call at [:forms]: expected one direct capability call with one request map, optionally preceded by an options map, but observed another form shape. Fix: inspect (doc my.background/background) and supply the documented direct call."
    :my.background/call-source (pr-str forms)
    :seon.error/expected '(my.background/background (capability request-map))
    :seon.error/offending forms
    :seon.error/data (merge {:my.background/authored-form forms} {:seon.error/member :forms})})

(defmacro background
  "Start one capability request without waiting for its result.

  Takes one direct capability call with one request map; an optional first
  options map can supply :seon.effect/time-limit-ms. Returns
  [:seon.effect/id string] for poll or await. The background operation has
  its own deadline and can outlive the submitting turn.

  Example:
  (my.background/background
    (my.shell/run! {:my.shell/argv [\"printf\" \"%s\" \"Verified.\"]
                    :my.shell/cwd \".\"}))"
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
      (invalid-call forms))))

(defn poll
  "Read a background request's current result without waiting.

  Supply :my.background/result as [:seon.effect/id string]. Returns a map
  with :seon.effect/id and :seon.effect/request-edn, plus result or
  interruption evidence when settled. Missing work returns a flat error.

  Example:
  (let [work (my.background/background
               (my.shell/run! {:my.shell/argv [\"printf\" \"%s\" \"Verified.\"]
                               :my.shell/cwd \".\"}))]
    (my.background/poll {:my.background/result work}))"
  {:malli/schema [:=> [:cat [:map [:my.background/result :my.background/result]]] [:or :my.background/receipt :my.background/invalid-result-error :my.background/missing-result-error]]}
  [request]
  (background/poll (:my.background/result request)))

(defn await
  "Return a background result or the condition I am waiting for.

  Returns settled effect evidence when available; otherwise returns
  {:my.turn/disposition :wait :my.turn/note string :my.background/result ref}.
  It does not block.
  Return that value as the form's result to pause the session.

  Example:
  (let [work (my.background/background
               (my.shell/run! {:my.shell/argv [\"printf\" \"%s\" \"Verified.\"]
                               :my.shell/cwd \".\"}))]
    (my.background/await {:my.background/result work
                          :my.turn/note \"Waiting for the process result.\"}))"
  {:malli/schema [:=> [:cat [:map [:my.background/result :my.background/result] [:my.turn/note :my.turn/note]]] [:or :my.background/receipt :my.turn/wait :my.background/invalid-result-error :my.background/missing-result-error]]}
  [request]
  (background/await (:my.background/result request) (:my.turn/note request)))
