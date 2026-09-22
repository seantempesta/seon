(ns seon.error.refusal
  "Pure diagnostic construction and cause-chain reading for error boundaries.")

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable.
  Derive the exception class and first complete stack frame at this leaf."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message {:optional true} :seon.error/message]
                [:seon.error/throwable {:optional true} :seon.error/throwable]]]
    :seon.error/base]}
  [{:seon.error/keys [throwable] :as observation}]
  (if throwable
    (let [frame (first (.getStackTrace ^Throwable throwable))
          file (when frame (.getFileName ^StackTraceElement frame))]
      (cond-> (assoc (dissoc observation :seon.error/throwable)
                     :seon.error/exception-class (symbol (.getName (class throwable))))
        file (assoc :seon.error/frame
                    [(symbol (.getClassName ^StackTraceElement frame))
                     (symbol (.getMethodName ^StackTraceElement frame))
                     file (long (.getLineNumber ^StackTraceElement frame))])))
    observation))

(defn refusal
  "Deepest structural error in `ex-data`, retaining its exception message;
  else deepest non-empty data, or nil.

  This reader returns another operation's exception data, preserving its
  declared domain members and ordinary ex-data maps. Its copied output
  alternatives await conversion to the error-handling base contract."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or
     :nil
     :map
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema/validation-refusal :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error :seon.sci.eval/row-acquisition-error :seon.sci.eval/reader-event-count-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.test/admission-error :seon.test/execution-error :seon.test/expired
     :seon.test/not-runnable-error :seon.test/resolution-error
     :seon.test/selection-error :seon.test/unknown-error
     :seon.test.run/immutable-error :seon.test.run/unavailable-error
     :seon.search/error :seon.source/test-evidence-error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error :seon.turn/refused-error
     :seon.turn.loop/error]]}
  [throwable]
  (loop [candidate throwable
         deepest nil
         classified nil]
    (if (nil? candidate)
      (or classified deepest)
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest)
               (if (and (:seon.error/at data)
                        (:seon.error/layer data)
                        (:seon.error/operation data))
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/operation data))))
                 classified))))))
