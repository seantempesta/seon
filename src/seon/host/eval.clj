(ns seon.host.eval
  "Serve recorded SCI eval batches for the JVM execution host."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [seon.ai.tokens :as tokens]
            [seon.db.transport.uds :as uds]
            [seon.error.sci :as error.sci]
            [seon.host.context :as context]
            [seon.host.record :as record]
            [seon.host.sample :as sample]
            [seon.host.session :as session]
            [seon.schema :as schema])
  (:import [java.io Writer]))

(set! *warn-on-reflection* true)

(def ^:private output-token-cap
  "Per-form SCI output budget; W1 moves it to a config fact."
  2048)
(def ^:private output-truncation-marker "…⟨output truncated⟩")

(defn agent-home-ns
  "The deterministic home-ns symbol for an agent id.

   Mirrors `seon.agent.home/home-ns` (the pod-side owner of the
   derivation): `(agent-home-ns \"seon\") => 'my.agent.seon`."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :symbol]}
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn- entry-source [entry]
  (or (:seon.repl/eval-source entry) (:seon.repl/source entry)))

(defn classified-error-value
  "Classify one SCI throwable as an execution error value."
  {:malli/schema [:=> [:cat :any :symbol :any] :map]}
  [ctx home-ns throwable]
  (let [classified
        (error.sci/classify
         {:seon.error.sci/throwable throwable
          :seon.error.sci/context ctx
          :seon.error.sci/home-ns home-ns})]
    (assoc classified :seon.error/message
           (error.sci/steering-head
            classified error.sci/default-error-head-token-cap))))

(defn- wire-safe-value
  "Keep a transit-encodable value; project anything else to its print form.

   sci vars (every `def`'s return) and other host objects cannot cross the
   protocol; their envelope keeps `:seon.eval/value-display` instead."
  [envelope]
  (if-not (contains? envelope :seon.eval/value)
    envelope
    (let [value (:seon.eval/value envelope)]
      (try
        (uds/encode {::probe value})
        envelope
        (catch Throwable _
          (-> envelope
              (dissoc :seon.eval/value)
              (assoc :seon.eval/value-display (pr-str value))))))))
(defn- output-capture []
  (let [limit (max 0 (- (tokens/estimate-chars output-token-cap)
                        (count output-truncation-marker)))
        text (StringBuilder.)
        truncated? (volatile! false)
        retain!
        (fn [x offset length]
          (let [remaining (max 0 (- limit (.length text)))
                retained (min remaining length)]
            (when (pos? retained)
              (if (string? x)
                (.append text ^CharSequence x (int offset)
                         (int (+ offset retained)))
                (.append text ^chars x (int offset) (int retained))))
            (when (> length retained)
              (vreset! truncated? true))))
        writer
        (proxy [Writer] []
          (write
            ([x]
             (if (string? x)
               (retain! x 0 (count x))
               (retain! (char-array [(char x)]) 0 1)))
            ([x offset length]
             (retain! x offset length)))
          (flush [] nil)
          (close [] nil))]
    {::output-writer writer
     ::output-text (fn []
                     (str text (when @truncated?
                                 output-truncation-marker)))}))

(defn finish-evaluation!
  "Apply the invocation interrupt state to one eval envelope."
  {:malli/schema [:=> [:cat ::session/session :map] :map]}
  [session envelope]
  (let [interrupted?
        (locking (::session/interrupt-lock session)
          (reset! (::session/worker-phase session) :recording)
          (let [fired? @(::session/interrupt-fired? session)
                flagged? (Thread/interrupted)]
            (or fired? flagged?)))]
    (if (and interrupted? (not (:seon.eval/interrupted? envelope)))
      {:seon.eval/ok? false
       :seon.eval/interrupted? true
       :seon/error
       (session/error-value "The invocation was interrupted." :agent
                    {:seon.error.sci/class :interrupt})}
      envelope)))

(defn eval-form!
  "Evaluate one prepared source in the agent context; every outcome a value.

   `::var-meta` (a returned sci var's metadata, the tee's projection
   input) is host-internal and stripped before the envelope crosses the
   protocol."
  {:malli/schema [:=> [:cat ::session/session :any :symbol :string] :map]}
  [session ctx home-ns source]
  (let [{::keys [output-writer output-text]} (output-capture)]
    (locking (::session/interrupt-lock session)
      (reset! (::session/worker-phase session) :evaluating))
    (let [envelope
          (try
            (let [value (sci/with-bindings {sci/out output-writer
                                            sci/err output-writer}
                          (sci/eval-string* ctx source))]
              (cond-> (assoc (sci.ctx-store/with-ctx ctx
                               (wire-safe-value {:seon.eval/ok? true
                                                 :seon.eval/value value}))
                             ::live-value value)
                (instance? sci.lang.Var value)
                (assoc ::var-meta (meta value))))
            (catch Throwable throwable
              (let [error (classified-error-value ctx home-ns throwable)
                    interrupted? (= :interrupt
                                    (get-in error [:seon.error/data
                                                   :seon.error.sci/class]))]
                {:seon.eval/ok? false
                 :seon.eval/interrupted? interrupted?
                 :seon/error error})))
          envelope (finish-evaluation! session envelope)
          output (output-text)]
      (cond-> envelope
        (seq output) (assoc ::output output)))))

(defn- read-error-envelope [entry]
  {:seon.eval/ok? false
   :seon/error (session/error-value
                (str "The form could not be read: "
                     (or (:seon.repl/message entry) "read error"))
                :agent)})

(defn- batch-summary
  [ids results]
  (let [evaluated (remove :seon.eval/skipped? results)]
    {:seon.eval/ids ids
     :seon.eval/n-ok (count (filter :seon.eval/ok? evaluated))
     :seon.eval/n-fail (count (remove :seon.eval/ok? evaluated))
     :seon.host/results (vec results)}))

(defn- declared-next-ns
  "The ns an executed source moves the batch to, when it moves it.

   An explicit `(ns X …)` or `(in-ns 'X)` as the FIRST form advances the
   fold; ordinary forms cannot move the REPL namespace."
  [forms]
  (let [form (first forms)]
    (cond
      (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)

      (and (seq? form) (= 'in-ns (first form))
           (seq? (second form)) (= 'quote (first (second form)))
           (symbol? (second (second form))))
      (second (second form))

      :else nil)))

(defn eval-batch-result
  "Serve `seon.execution.runtime/eval-batch!` over sci WITH recording.

   Each executed form records through the one corpus mechanism: a
   `:running` receipt with a managed `:seon.eval/id` commits BEFORE the
   form runs (the durable execution boundary — no receipt, no run), and
   one terminal transaction carries the CAS fence, the frozen eval row,
   and every program-graph row the form tees (`:seon.fn` for a single
   defn, `:seon.ns` + require edges for an ns declaration,
   `:seon.schema` for registrations detected by registry diff). The
   batch evals in the request's starting ns so defs land in the agent's
   home namespace, not scratch `user`. Recording engages only when the
   request names its owning turn; receiptless probes stay engine-only
   with empty `:seon.eval/ids`."
  {:malli/schema [:=> [:cat ::session/session :map :map :seon.db/db] :map]}
  [session {parsed :seon.eval/parsed
            starting-ns :seon.eval/starting-ns
            turn-id :seon.agent.turn/id-of-turn}
   sampling-limits database]
  (let [ctx (::session/ctx session)
        writer (::session/writer session)
        agent-id (:seon.execution/agent-id @(::session/startup session))
        record? (boolean (and writer turn-id agent-id))
        batch-ns (or starting-ns 'user)]
    (when-not (contains? record/transient-ns-syms batch-ns)
      (context/ensure-context-ns! ctx batch-ns))
    (loop [entries (vec (or parsed []))
           current-ns batch-ns
           ids []
           results []]
      (if (empty? entries)
        (batch-summary ids results)
        (let [entry (first entries)
              kind (:seon.repl/kind entry)]
          (if-not (contains? #{:form :read} kind)
            ;; comment/prose entries evaluate and record nothing.
            (recur (rest entries) current-ns ids
                   (conj results {:seon.eval/ok? true
                                  :seon.eval/skipped? true}))
            (let [source (or (entry-source entry) "")
                  narration (or (:seon.repl/narration entry) "")
                  at (java.util.Date.)
                  start-ms (session/now-ms)
                  started (when record?
                            (context/start-eval-receipt!
                             writer
                             {:seon.agent.turn/id turn-id
                              :seon.eval/at at
                              :seon.eval/source source
                              :seon.eval/narration narration
                              :seon.eval/ns current-ns
                              :seon.agent/id agent-id}))]
              (if (and record? (:seon/error started))
                ;; The receipt is the durable execution boundary: a form
                ;; whose receipt cannot commit never runs.
                (recur (rest entries) current-ns ids
                       (conj results {:seon.eval/ok? false
                                      :seon/error (:seon/error started)}))
                (let [schema-delta (schema/begin-registration-delta)
                      raw-envelope
                      (schema/call-with-registration-delta
                        schema-delta
                        #(if (= :form kind)
                           (eval-form! session ctx (agent-home-ns agent-id)
                                       (str "(in-ns '" current-ns ")\n"
                                            source))
                           (read-error-envelope entry)))
                      ok? (boolean (:seon.eval/ok? raw-envelope))
                      ;; A failed eval must not leave half a registration:
                      ;; discard only this form's isolated registration delta.
                      _ (when (and (= :form kind) (not ok?))
                          (schema/restore! schema-delta))
                      new-schema-keys (if ok?
                                        (schema/commit-registration-delta!
                                          schema-delta)
                                        #{})
                      forms (if (= :form kind)
                              (record/read-forms
                               {::record/source source
                                ::record/ns-sym current-ns})
                              [])
                      var-meta (::var-meta raw-envelope)
                      live-value (::live-value raw-envelope)
                      output (::output raw-envelope)
                      envelope (dissoc raw-envelope ::var-meta ::live-value
                                       ::output)
                      eval-id (:seon.eval/id started)
                      recorded
                      (when (and record? eval-id)
                        (context/record-eval-terminal!
                         writer
                         {:seon.eval/id eval-id
                          ::context/envelope envelope
                          ::context/at at
                          ::context/duration-ms (- (session/now-ms) start-ms)
                          ::context/source source
                          ::context/narration narration
                          ::context/ns-sym current-ns
                          ::context/agent-id agent-id
                          ::context/forms forms
                          ::context/var-meta var-meta
                          ::context/new-schema-keys new-schema-keys
                          ::context/output output}))
                      projection-change?
                      (true? (::context/projection-changed? recorded))
                      projection-refresh
                      (when projection-change?
                        (context/refresh-committed-projection!
                          writer (::session/projection-state session)
                          (get-in recorded [:db-after :t])))
                      _ (when (:seon/error projection-refresh)
                          (throw
                            (ex-info
                              (get-in projection-refresh
                                      [:seon/error :seon.error/message])
                              {:seon.error/kind :core-bug
                               :seon.host/projection-error
                               (:seon/error projection-refresh)})))
                      ids (if (and recorded (:seon.db/ok? recorded))
                            (conj ids eval-id)
                            ids)
                      _ (when (and ok? recorded (:seon.db/ok? recorded))
                          (sample/retain-live-value! session eval-id live-value
                                              sampling-limits database))
                      envelope (if (and recorded
                                        (not (:seon.db/ok? recorded)))
                                 ;; The outcome could not become durable —
                                 ;; surface it on the envelope as data.
                                 (assoc envelope ::record-error
                                        (:seon/error recorded))
                                 envelope)
                      next-ns (or (when ok? (declared-next-ns forms))
                                  current-ns)]
                  (if (:seon.eval/interrupted? envelope)
                    (batch-summary ids (conj results envelope))
                    (recur (rest entries) next-ns ids
                           (conj results envelope))))))))))))

(defn interrupted-batch?
  "Whether an eval-batch result contains an interrupted form."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [result]
  (boolean (some :seon.eval/interrupted? (:seon.host/results result))))

(defn interrupted-error
  "The first interrupted form's error value, when present."
  {:malli/schema [:=> [:cat :map] [:or :nil :map]]}
  [result]
  (some (fn [envelope]
          (when (:seon.eval/interrupted? envelope)
            (:seon/error envelope)))
        (:seon.host/results result)))
