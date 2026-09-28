(ns seon.sci.batch
  "The SCI stage values of the one REPL path: the live reader behind the
  forgiving input stage, the current namespace, and the batch's namespace
  binding. Evaluation is `seon.sci.eval/evaluate`.

  Arming and the write observer are `seon.sci.arm` and `seon.sci.observe`.
  This namespace requires no evaluator owner, so `seon.sci.eval` builds its
  acquisition entrant from it."
  (:require [sci.core :as sci]
            [seon.sci.forgive :as forgive]
            [seon.sci.reader :as reader]))

(defn parse-options
  "The live reader's options for `ctx`: Seon's reader policy with end
  locations (every form carries its complete span, which SCI's producer
  origin and the expansion observer report), no eof."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :map]}
  [ctx]
  (-> (#'reader/parse-options {::reader/ctx ctx ::reader/features #{:clj} ::reader/tags {}})
      (dissoc :eof)
      (assoc :end-location true)))

(defn read-next
  "One read step at the live cursor, through the forgiving input stage."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world mode :seon.batch/mode} state]
  (forgive/next-form ctx (parse-options ctx) mode state))

(defn read-code
  "One read step with no repair: acquisition and file entrants read code."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world} state]
  (forgive/next-form ctx (parse-options ctx) :code state))

(defn current-ns
  "The namespace the batch is in now."
  {:malli/schema [:=> [:cat :seon.batch/request] :symbol]}
  [_request]
  (sci/ns-name @sci/ns))

(defn around
  "The batch runs in its start namespace."
  {:malli/schema [:=> [:cat :seon.batch/request [:=> [:cat] :seon.batch/batch]] :seon.batch/batch]}
  [{start-ns :seon.batch/start-ns} body]
  (sci/with-bindings {sci/ns (sci/create-ns start-ns)}
    (body)))
