(ns seon.sci.batch
  "The SCI stage values of the one REPL path: the live reader behind the
  forgiving input stage, one evaluation under the kernel arm against the
  working database value, the current namespace, and the batch's namespace
  and registration-delta binding.

  Arming and the write observer are `seon.sci.arm` and `seon.sci.observe`.
  This namespace requires no evaluator owner, so `seon.sci.eval` builds its
  acquisition entrant from it."
  (:require [sci.core :as sci]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.forgive :as forgive]
            [seon.sci.kernel :as kernel]
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

(defn evaluate
  "Evaluate `form` once in the request's context under the kernel arm, and
  answer its outcome (`:seon.batch/value`).

  The form reads `working` as its database, and registers into the batch's
  registration delta over `working`'s projection. A failure throws with its
  complete error value (`kernel/failure-value` under the arm's record) as
  `:seon.batch/error-value`, the thrown cause kept."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/form :seon.db/database-value] :seon.batch/outcome]}
  [{{ctx :seon.sci.eval/ctx limit :seon.sci.eval/time-limit-ms} :seon.batch/world delta :seon.sci.batch/delta} form working]
  (kernel/with-arm
   ctx (or limit 30000)
   (fn [armed]
     (try
       (binding [db/*read-database* working]
         (let [run #(sci/eval-form (assoc ctx :main-thread-id (.getId (Thread/currentThread))) form)]
           {:seon.batch/value
            (if delta
              (schema/call-with-registration-delta
               (assoc delta :seon.schema.delta/projection (db/carried-projection working)) run)
              (run))}))
       (catch Throwable t
         (throw (ex-info (or (ex-message t) (.getName (class t)))
                         {:seon.batch/error-value
                          (kernel/failure-value {::kernel/time-limit-kind :seon.sci.eval/time-limit
                                                 ::kernel/failure-kind :seon.sci.eval/evaluation-failed}
                                                t ((::kernel/record armed) (if (kernel/interrupted? t) :time :error)))}
                         t)))))))

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

(defn acquire-request
  "Acquisition of stored definitions: their source read as code and evaluated
  in the request's world, with no observation, derivation, recording or
  print (the facts already exist)."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [{{db :seon.db/db} :seon.batch/world :as request}]
  (merge {:seon.batch/read-state forgive/read-state
          :seon.batch/read-next read-code
          :seon.batch/evaluate evaluate
          :seon.batch/ns-name current-ns
          :seon.batch/around around
          :seon.batch/start-ns 'user
          :seon.batch/mode :code
          :seon.sci.batch/delta (schema/begin-registration-delta (db/carried-projection db))}
         request))
