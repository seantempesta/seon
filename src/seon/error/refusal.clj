(ns seon.error.refusal
  "Pure diagnostic construction and cause-chain reading for error boundaries.")

(defn- known-or-unknown
  {:malli/schema [:=> [:cat :seon.error/source]
                  [:or :seon.error/source
     :seon.error/base
     :my.background/error :my.edit/error :my.fs/error :my.message/error
     :my.plan/error :my.shell/error :my.turn/error
     :seon.agent/error :seon.agent.graph/error :seon.ai/request-error
     :seon.artifact/error :seon.boot/error :seon.bootstrap/error
     :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
     :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
     :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
     :seon.turn.loop/error]]}
  [value]
  (if (nil? value) :seon.error/unknown value))

(defn diagnostic
  "Construct the declared base observation and its diagnostic evidence.
  Preserve every supplied domain member; owners declare their complete facets."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message :seon.error/message]
                [:seon.error/diagnostic-layer :seon.schema/value]
                [:seon.error/diagnostic-operation :seon.schema/value]
                [:seon.error/diagnostic-member :seon.schema/value]
                [:seon.error/diagnostic-expected :seon.schema/value]
                [:seon.error/diagnostic-offending :seon.schema/value]
                [:seon.error/diagnostic-cause :seon.schema/value]
                [:seon.error/diagnostic-evidence :seon.schema/value]
                [:seon.error/data {:optional true} :map]]]
    :seon.error/base]}
  [{:seon.error/keys [data diagnostic-layer
                      diagnostic-operation diagnostic-member diagnostic-expected
                      diagnostic-offending diagnostic-cause diagnostic-evidence] :as observation}]
  (assoc (dissoc observation
                 :seon.error/diagnostic-layer :seon.error/diagnostic-operation
                 :seon.error/diagnostic-member :seon.error/diagnostic-expected
                 :seon.error/diagnostic-offending :seon.error/diagnostic-cause
                 :seon.error/diagnostic-evidence)
   :seon.error/data
   (merge data
          {:seon.error/diagnostic-layer (known-or-unknown diagnostic-layer)
           :seon.error/diagnostic-operation (known-or-unknown diagnostic-operation)
           :seon.error/diagnostic-member (known-or-unknown diagnostic-member)
           :seon.error/diagnostic-expected (known-or-unknown diagnostic-expected)
           :seon.error/diagnostic-offending (known-or-unknown diagnostic-offending)
           :seon.error/diagnostic-cause (known-or-unknown diagnostic-cause)
           :seon.error/diagnostic-evidence-availability (if (some? diagnostic-evidence) :seon.error/known :seon.error/unknown)
           :seon.error/diagnostic-evidence (known-or-unknown diagnostic-evidence)})))

(defn refusal
  "Deepest classified `ex-data`, retaining its exception message when absent;
  else deepest non-empty data, or nil.

  This is a genuine pass-through: the data it returns is the data some other
  operation put on a throwable, so under program-facts PRD §1q it enumerates
  the COMPLETE canonical facet population alongside the base error schema and
  the ordinary `ex-data` map. The enumeration is explicit, never a
  projection-derived catch-all; `seon.error.refusal-test` fails on drift
  against `seon.error/facet-keys`."
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
     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
     :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
     :seon.flow/error :seon.fn/error :seon.fn.binding/error
     :seon.instrument/arity-error :seon.instrument/contract-error
     :seon.instrument/registration-error :seon.instrument/undeclared-error
     :seon.message/error :seon.operator/error :seon.operator.collect/error
     :seon.problems/error :seon.program/error :seon.reconcile/error
     :seon.render/error :seon.render.data/error :seon.render.value/error
     :seon.render.walk/error :seon.render.web/error :seon.schedule/error
     :seon.schema/error :seon.schema.datahike/error :seon.schema.shape/error
     :seon.sci.admit/error :seon.sci.eval/acquisition-error
     :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
     :seon.search/error :seon.test/error :seon.test.accretion/error
     :seon.test.run/error :seon.test.runner/error :seon.turn/error
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
               (if (some? (:seon.error/kind data))
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/kind data))))
                 classified))))))
