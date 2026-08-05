(ns seon.cluster.loop
  "THE TURN: open → call → resume → close, and the custody law.

  THERE IS NO LOOP HERE ANY MORE (F2 §3.1). The central serial pass —
  settle-all → global `next-work` → turn → global `more-work?` rewake —
  is DELETED. Every agent is its own flow graph, and the pass that
  survives is `seon.cluster.agent/turn-step`: the same shape narrowed
  to ONE agent, with per-agent orphan settling, `next-agent-work`, this
  namespace's `turn`, and a self-rewake into that agent's own mailbox.
  A global `next-work` was wrong the moment two agents could run.

  The namespace keeps its name while its loop dies: the rename to
  `seon.cluster.turn` is a separate atomic wave (F2 R5), because
  mixing a rename into a cut blurs every diff the review depends on.

  WHAT SURVIVES, and why it is the durable half:

  THE TERMINAL TRANSACTION IS ONE COMMIT carrying the receipt AND the
  interpreted disposition. Splitting them reintroduces a torn window
  the quarry already closed (`driver.clj:289-297`). A rejected terminal
  transaction is followed by a separate minimal terminal commit carrying
  the admitted flat ERROR value, its durable error fact, and the run
  close, but no program row or agent disposition. Under
  `db/transact!` that is a branch on a returned value, not a catch,
  which is strictly better.

  NOTHING RETRIES A PAID CALL, and recovery is not a code path: a turn
  only ever acts on what `next-agent-work` derived from facts, and a
  crashed run reaches it as ordinary facts. `interruption` is settled
  with no reply, and the agent's next prompt carries the one warning.

  Crash walk: every row is a state `next-agent-work`/`interruption`
  already answer, so this namespace holds no durable state of its own
  and a killed turn leaves exactly the facts its last committed
  transaction wrote. The sealed suite drives the rows as kill positions
  in a state-machine property, per agent."
  (:require [clojure.core.async :as async]
            [sci.core :as sci]
            [clojure.string :as str]
            [clojure.core.async.flow :as flow]
            [seon.ai :as ai]
            [seon.blob :as blob]
            [seon.context :as context]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.reply :as reply]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.flow :as seon.flow]
            [seon.fn.analyzer :as fn.analyzer]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form])
  (:import [java.nio.charset StandardCharsets]
           [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn lint-form
  "Replace one error-bearing source form with a flat lint refusal.

  The namespace row and program functions come from the current database
  value so an earlier REPL form's committed resolver changes govern this
  form."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.ns/name :seon.ns/name]
      [::namespace-row {:optional true} :map]
      [::available-functions {:optional true} [:vector :map]]
      [::source :seon.cluster.reply/form]]]
    :seon.cluster.reply/form]}
  [{namespace-name :seon.ns/name
    namespace-row ::namespace-row
    available-functions ::available-functions
    source ::source}]
  (let [namespace-row
        (some-> namespace-row
                (update :seon.ns/requires
                        (fn [required-namespaces]
                          (into #{}
                                (map :seon.ns/name)
                                required-namespaces))))
        analyzed
        (first
         (fn.analyzer/analyze-forms
          (cond->
           {::fn.analyzer/namespace-name namespace-name
            ::fn.analyzer/available-functions available-functions
            ::fn.analyzer/sources
            [(:seon.cluster.run.form/source source)]}
            namespace-row
            (assoc ::fn.analyzer/namespace-row namespace-row))))
        findings (::fn.analyzer/findings analyzed)
        errors (filterv #(= :error (::fn.analyzer/level %)) findings)]
    (if (seq errors)
      (assoc source
             :seon.cluster.run.form/source
             (pr-str
              (list
               'quote
               {:seon.error/kind ::lint-rejected
                :seon.error/message
                (str "Static analysis rejected this source form with "
                     (count errors) " error finding(s).")
                :seon.error/data
                {:seon.cluster.run.form/source
                 (:seon.cluster.run.form/source source)
                 ::fn.analyzer/findings findings}})))
      source)))

(defn- available-functions
  [db ctx]
  (let [namespace-state (sci/namespace-state ctx)]
    (->> (concat
          (map (fn [[sym private? arglists]]
                 (cond-> {:seon.fn/sym sym
                          :seon.fn/private? private?}
                   (seq arglists)
                   (assoc :seon.fn/arglists arglists)))
               (db/q '[:find ?sym ?private ?arglists
                      :where
                      [?function :seon.fn/sym ?sym]
                      [(get-else $ ?function :seon.fn/private? false)
                       ?private]
                      [(get-else $ ?function :seon.fn/arglists "")
                       ?arglists]]
                    db))
          (mapcat
           (fn [[namespace-name intern-names]]
             (map (fn [intern-name]
                    (let [intern-meta
                          (meta (get-in namespace-state
                                        [namespace-name intern-name]))]
                      (cond->
                       {:seon.fn/sym
                        (str (symbol (str namespace-name) (str intern-name)))
                        :seon.fn/private? (boolean (:private intern-meta))}
                        (seq (:arglists intern-meta))
                        (assoc :seon.fn/arglists
                               (pr-str (:arglists intern-meta))))))
                  intern-names))
           (sci/namespace-interns ctx)))
         (reduce (fn [by-symbol row]
                   (assoc by-symbol (:seon.fn/sym row) row)) {})
         vals
         (sort-by :seon.fn/sym)
         vec)))

;;; ---------------------------------------------------------------------------
;;; The pure turn
;;; ---------------------------------------------------------------------------

(defn committed-attributes
  "Every attribute the loop's own transactions assert.
  Computed from the transitions this namespace commits, never a
  reviewed list — it exists so the wake/commit disjointness property
  (C2) has two computed sets to compare rather than one list to
  believe."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  ;; WHAT THIS SET IS NOT, since the messaging rung: it is the loop's
  ;; ROUTINE bookkeeping, not everything the loop can ever commit. A
  ;; turn that delivers an agent's message commits
  ;; `:seon.cluster.message/to` DELIBERATELY, and that commit wakes the
  ;; recipient — which is the whole transport, not a leak. The
  ;; invariant C2 states is the one that matters and is unchanged: no
  ;; ordinary turn wakes the loop as a side effect of recording itself,
  ;; so an idle cluster stays idle. A deliberate delivery is caused by
  ;; an agent, is bounded by `:seon.config.message/max-chain`, and is
  ;; asserted from the other direction in the messaging suite —
  ;; delivery MUST intersect the wake set or nothing would be woken.
  ;;
  ;; COMPUTED from the DECLARED ENTITIES this loop writes — the run,
  ;; its forms, and its receipts — plus the agent pointer a close
  ;; retracts. Reading the entity maps rather than filtering the
  ;; registry by namespace keeps out the things that live in those
  ;; namespaces without being attributes: the entity maps themselves,
  ;; and derived values like `:seon.cluster.run/missing-results`.
  ;;
  ;; Note what this set can and cannot prove. It is the right input for
  ;; the wake/commit disjointness property, but it CANNOT by itself
  ;; catch an attribute the boot path fails to install — a missing
  ;; entity map removes the attribute from this set and from the
  ;; installable set at once. The test that catches that class is the
  ;; one that transacts these rows into a database built the way boot
  ;; builds it.
  (into #{:seon.cluster.agent/run}
        (comp (mapcat (fn [entity]
                        (schema.form/map-entries
                         (schema/schema-definition entity))))
              (filter vector?)
              (map first))
        [:seon.cluster.run/run
         :seon.cluster.run.form/form
         :seon.cluster.eval/receipt
         ;; every model attempt is a durable row this loop writes, so it
         ;; belongs in the declared write set — and the class-killer
         ;; that asserts this set is installable is exactly what catches
         ;; a new entity family the boot path never learned about
         :seon.ai/attempt
         ;; the pre-provider context capture and its contribution rows
         ;; are turn-owned commits too (ruling 4, 2026-07-28)
         :seon.context.capture/capture
         :seon.context.contribution/contribution]))

(defn disposition
  "The disposition an admitted eval value carries, or nil.
  The loop reads `my.run`'s two values out of the LAST form's admitted
  result. Anything else — a number, a map that merely looks similar, an
  error value — is not a disposition, and a run whose plan ends without
  one simply stays open for the next wake."
  {:malli/schema [:=> [:cat :any] [:maybe :my.run/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.run/value value)
    value))

(defn messages
  "The messages an admitted eval value asks to send, or nil.
  The exact counterpart of `disposition`, over the second agent-facing
  value: one `my.message/send` result, or a vector of them. Anything
  else is not a delivery, and a form that returns an ordinary value
  simply sends nothing.

  Disposition and delivery schemas are open for accretion and are interpreted
  independently. A turn that intentionally sends and finishes uses two forms,
  which makes their order visible to a reader."
  {:malli/schema [:=> [:cat :any] [:maybe :my.message/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.message/value value)
    value))

(defn- receipt-request
  "Terminal receipt request projected from one completed evaluation."
  [{:keys [:seon.cluster.run/id :seon.cluster.run/process
           :seon.cluster.run.form/ordinal :seon.sci.eval/evaluation
           :seon.problems/form-problem :seon.def/rows :my.run/value]
    settlement-evaluation ::settlement-evaluation}]
  (let [error (or (:seon.cluster.eval/error evaluation)
                  (:seon.cluster.eval/error form-problem))
        kind (or (:seon.error/kind (:seon.sci.admit/value evaluation))
                 (:seon.error/kind form-problem))]
    (cond-> {:seon.cluster.run/id id
             :seon.cluster.run/process process
             :seon.cluster.run.form/ordinal ordinal}
      (:seon.cluster.eval/result-edn settlement-evaluation)
      (assoc :seon.cluster.eval/result-edn
             (:seon.cluster.eval/result-edn settlement-evaluation))
      (:seon.cluster.eval/result-blob settlement-evaluation)
      (assoc :seon.cluster.eval/result-blob
             (:seon.cluster.eval/result-blob settlement-evaluation))
      (:seon.cluster.eval/result-size settlement-evaluation)
      (assoc :seon.cluster.eval/result-size
             (:seon.cluster.eval/result-size settlement-evaluation))
      error (assoc :seon.cluster.eval/error error)
      (:seon.cluster.eval/triage-edn evaluation)
      (assoc :seon.cluster.eval/triage-edn
             (:seon.cluster.eval/triage-edn evaluation))
      (:seon.cluster.eval/interrupted-at evaluation)
      (assoc :seon.cluster.eval/interrupted-at
             (:seon.cluster.eval/interrupted-at evaluation))
      kind (assoc :seon.error/kind kind)
      (:seon.cluster.eval/output evaluation)
      (assoc :seon.cluster.eval/output
             (:seon.cluster.eval/output evaluation))
      (:seon.cluster.eval/ns evaluation)
      (assoc :seon.cluster.eval/ns (:seon.cluster.eval/ns evaluation))
      (:seon.sci.eval/ending-ns evaluation)
      (assoc :seon.sci.eval/ending-ns
             (:seon.sci.eval/ending-ns evaluation))
      (:seon.program/row evaluation)
      (assoc :seon.program/row
             (:seon.program/row evaluation))
      (seq rows) (assoc :seon.def/rows rows)
      value (assoc :my.run/value value))))

(defn terminal-tx
  "The ONE transaction ending a form: its receipt AND the disposition.
  Pure tx-data. When the admitted value carries a disposition, that
  disposition's own facts (close + completion message, or release) ride
  in this same commit — one transaction, no torn window. When it does
  not, this is the receipt alone."
  {:malli/schema [:=> [:cat :seon.cluster.loop/terminal-request :inst]
                  [:vector :some]]}
  [{:keys [:seon.cluster.run/id :seon.cluster.run/process
           :seon.cluster.run.form/ordinal
           :seon.cluster.eval/result-edn :seon.cluster.eval/result-blob
           :seon.cluster.eval/result-size :seon.cluster.eval/error
           :seon.cluster.eval/triage-edn
           :seon.cluster.eval/interrupted-at
           :seon.cluster.eval/output :seon.cluster.eval/ns
           :seon.sci.eval/ending-ns
           :seon.program/row :seon.def/rows :seon.error/kind
           :my.run/value]}
   now]
  (let [receipt (cond-> {:seon.cluster.run/id id
                         :seon.cluster.eval/ordinal ordinal}
                  result-edn (assoc :seon.cluster.eval/result-edn result-edn)
                  result-blob (assoc :seon.cluster.eval/result-blob result-blob)
                  result-size (assoc :seon.cluster.eval/result-size result-size)
                  error (assoc :seon.cluster.eval/error error)
                  triage-edn
                  (assoc :seon.cluster.eval/triage-edn triage-edn)
                  ;; the cut instant, present exactly when the time
                  ;; limit fired — its presence IS the interrupted state
                  interrupted-at (assoc :seon.cluster.eval/interrupted-at
                                        interrupted-at)
                  kind (assoc :seon.error/kind kind)
                  ns (assoc :seon.cluster.eval/ns ns)
                  ending-ns (assoc :seon.sci.eval/ending-ns ending-ns)
                  row (assoc :seon.program/row row)
                  (seq rows) (assoc :seon.def/rows rows)
                  ;; what the form printed is evidence, and evidence is
                  ;; durable or it is nothing
                  output (assoc :seon.cluster.eval/output output))]
    (into (run/receipt-settle-tx receipt)
          ;; ONE transaction: the disposition's own transition rides
          ;; here, so the receipt and what it means are never two
          ;; commits with a window between them. A `wait` CLOSES the
          ;; run exactly as `complete` does (README owner-decisions #4,
          ;; folded into F1): releasing instead left an
          ;; unheld-open-planned run at a committed basis — the P1
          ;; feeder state — and nothing could ever resume it, because
          ;; its plan was fully executed. What differs is only what
          ;; rides beside the close: a completion delivers a reply, a
          ;; wait delivers nothing and leaves its note in the receipt.
          (case (:my.run/disposition value)
            (:completed :wait)
            (run/close-tx {:seon.cluster.run/id id
                           :seon.cluster.run/process process
                           :seon.cluster.run/closed-at now})
            nil))))

(defn- capability-free-references?
  "True when no referenced program-graph function reaches a capability leaf.
  A called SCI Var absent from the program graph fails closed; SCI's
  independent host-interop observation closes the host-resolution side."
  [db roots unproven-called-vars]
  (let [row
        (fn [function-symbol]
          (db/pull db
                  [:seon.fn/sym :seon.effect/capability
                   {:seon.fn/calls [:seon.fn/sym]}]
                  [:seon.fn/sym (str function-symbol)]))]
    (if (some #(nil? (:seon.fn/sym (row %)))
              unproven-called-vars)
      false
      (loop [pending (seq (sort-by str roots))
             visited #{}]
        (if-let [function-symbol (first pending)]
          (if (contains? visited function-symbol)
            (recur (next pending) visited)
            (let [row (row function-symbol)
                  called (map (comp symbol :seon.fn/sym)
                              (:seon.fn/calls row))]
              (if (:seon.effect/capability row)
                false
                (recur (concat (next pending) called)
                       (conj visited function-symbol)))))
          true)))))

(defn- desk-rows
  "Restore-ladder rows admitted by the terminal receipt transaction."
  [db agent-id evaluation ordinal]
  (let [successful-evaluation?
        (= :ok (get-in evaluation
                       [:seon.sci.admit/record :seon.eval/outcome]))
        host-clean?
        (zero? (get-in evaluation
                       [:seon.sci.admit/record
                        :seon.eval/host-interop-count]
                       0))
        row-base
        (fn [candidate]
          (-> candidate
              (dissoc :seon.sci.eval/value
                      :seon.sci.eval/referenced-vars
                      :seon.sci.eval/unproven-called-vars
                      :seon.sci.eval/nondeterministic-calls
                      :seon.sci.eval/impure-calls)
              (assoc :seon.def/key
                     (pr-str [agent-id (:seon.def/id candidate)])
                     :seon.def/agent
                     [:seon.cluster.agent/id agent-id]
                     :seon.def/ordinal ordinal
                     :seon.schema.admission/source :agent)))
        rows
        (mapv
         (fn [candidate]
           (let [stored? (or (:seon.def/value-edn candidate)
                             (:seon.def/blob candidate))
                 atom? (:seon.def/atom? candidate)
                 unproven-called-vars
                 (:seon.sci.eval/unproven-called-vars candidate)
                 nondeterministic-calls
                 (:seon.sci.eval/nondeterministic-calls candidate)
                 impure-calls (:seon.sci.eval/impure-calls candidate)
                 pure? (and host-clean?
                            (empty? impure-calls)
                            (capability-free-references?
                             db
                             (:seon.sci.eval/referenced-vars candidate)
                             unproven-called-vars))
                 deterministic? (empty? nondeterministic-calls)]
             (cond
               (and atom? stored?)
               (-> (row-base candidate)
                   (dissoc :seon.def/source
                           :seon.def/unrestorable-reason))

               (and (not atom?) successful-evaluation? pure? deterministic?)
               (-> (row-base candidate)
                   (dissoc :seon.def/value-edn :seon.def/blob
                           :seon.def/size :seon.def/unrestorable-reason))

               (and (not atom?) stored?)
               (-> (row-base candidate)
                   (dissoc :seon.def/source
                           :seon.def/unrestorable-reason))

               :else
               (-> (row-base candidate)
                   (dissoc :seon.def/source :seon.def/value-edn
                           :seon.def/blob :seon.def/size)
                   (assoc :seon.def/unrestorable-reason
                          (cond
                            (and atom? (not stored?))
                            "The atom's settled value is not store-faithful."

                            (not successful-evaluation?)
                            "Defining evaluation did not complete successfully."

                            (not host-clean?)
                            "Defining form touched host interop."

                            (seq impure-calls)
                            "Defining form called an effectful SCI built-in."

                            (seq nondeterministic-calls)
                            "Defining form called a nondeterministic SCI built-in."

                            (seq unproven-called-vars)
                            "Defining form calls a Var absent from the program graph."

                            :else
                            "Defining form reaches a capability leaf."))))))
         (:seon.sci.eval/desk-defs evaluation))]
    rows))

(defn- result-blob-threshold
  [db]
  (db/q '[:find ?threshold .
         :where [_ :seon.config.eval.result/blob-threshold ?threshold]]
       db))

(defn- store-desk-values!
  [connection evaluation]
  (let [threshold (result-blob-threshold @connection)]
    (update
     evaluation :seon.sci.eval/desk-defs
     (fn [candidates]
       (mapv
        (fn [candidate]
          (if-let [serialized
                   (sci.eval/store-faithful-edn
                    (:seon.sci.eval/value candidate))]
            (let [size (long (count serialized))]
              (cond-> (assoc candidate :seon.def/size size)
                (and threshold (> size threshold))
                (assoc :seon.def/blob
                       (blob/put! connection serialized))

                (or (nil? threshold) (<= size threshold))
                (assoc :seon.def/value-edn serialized)))
            candidate))
        candidates)))))

(defn- result-window-page-size
  [db]
  (db/q '[:find ?size .
         :where [_ :seon.render.value/max-collection ?size]]
       db))

(def ^:private result-blob-fixed-growth-bytes
  ;; DERIVED 2026-08-03 from two production-format, one-commit file-store
  ;; cells after subtracting the measured payload terms. With result-edn
  ;; noHistory, inline payload recurs in EAVT + AEVT across the immutable
  ;; commit and mutable head (4R). Blob placement stores the window on those
  ;; same four paths plus the original once as binary (4W + R); digest, size,
  ;; and blob framing contribute the remaining 743 bytes. Both calibration
  ;; cells derive 743 exactly: R=258/W=72 grew 5,822 vs 6,079 bytes, and
  ;; R=358/W=72 grew 6,220 vs 6,177. The stored-shape comparison is therefore
  ;; blob < inline exactly when 743 + 4W + R < 4R.
  743)

(defn- utf8-size
  [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- result-blob-smaller?
  [result-edn window-edn]
  (< (+ result-blob-fixed-growth-bytes
        (* 4 (utf8-size window-edn))
        (utf8-size result-edn))
     (* 4 (utf8-size result-edn))))

(defn- settlement-result
  [cluster evaluation]
  (if-let [result-edn (:seon.cluster.eval/result-edn evaluation)]
    (let [connection (:seon.db/connection cluster)
          result-size (long (count result-edn))
          db @connection
          threshold (result-blob-threshold db)
          window-edn
          (when (and threshold (> result-size threshold))
            (render.value/result-window-edn
             {:seon.sci.admit/caps (:seon.sci.admit/caps cluster)
              :seon.render.value/options
              {:seon.render.value/max-collection
               (result-window-page-size db)}}
             result-edn))]
      (if (and window-edn (result-blob-smaller? result-edn window-edn))
        (assoc evaluation
               :seon.cluster.eval/result-edn window-edn
               :seon.cluster.eval/result-blob
               (blob/put! connection result-edn)
               :seon.cluster.eval/result-size result-size)
        (assoc evaluation :seon.cluster.eval/result-size result-size)))
    evaluation))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(declare turn settle-interruption!)

(defn- submission-time-limit-evaluation
  [time-limit-ms submission-wait-ms]
  (let [message
        (str "Evaluation submission did not settle within "
             time-limit-ms "ms.")
        value
        {:seon.error/kind :seon.flow/time-limit
         :seon.error/message message
         :seon.error/data
         {:seon.flow/submission-wait-ms submission-wait-ms}}]
    {:seon.sci.admit/value value
     :seon.cluster.eval/result-edn (pr-str value)
     :seon.cluster.eval/error message
     :seon.cluster.eval/interrupted-at (Date.)}))

(defn- submit-evaluation!!
  [cluster evaluate submission-id request]
  (let [submission
        (seon.flow/submit!!
         (:seon.flow/work-launcher cluster)
         {::seon.flow/submission-id submission-id
          ::seon.flow/workload :compute
          ::seon.flow/time-limit-ms
          (* 2 (:seon.sci.eval/time-limit-ms request))
          ::seon.flow/work-fn
          (fn [{::seon.flow/keys [started!]}]
            (started!)
            (evaluate request))})]
    (if (= ::seon.flow/completed (::seon.flow/outcome submission))
      (::seon.flow/value submission)
      (submission-time-limit-evaluation
       (:seon.sci.eval/time-limit-ms request)
       (::seon.flow/submission-wait-ms submission)))))

(defn- digest
  "The plan digest: SHA-256 over the ordered sources, so the same reply
  freezes to the same plan and N2's absent-to-digest fence is exact."
  [sources]
  (schema/sha-256 [(.getBytes (pr-str sources) "UTF-8")]))

(defn- error-tx
  "Transaction data recording one failure VALUE as a durable error fact.
  Pure over a database value — `seon.error/commit-tx` does the work and
  this is only the assembly of the dials the recorder needs. It exists
  because two callers need that assembly (a refused transition and a
  failed model attempt) and a second copy of it is how one of them
  quietly stops escalating.

  Attribution is passed in, never derived here: an `:open` that REFUSED
  has no run to point at, and a lookup ref to a run that does not exist
  would fail the very transaction that records the failure."
  [cluster db failure now attribution]
  (error/commit-tx
   db
   (merge {:seon.error/source failure
           :seon.error/id (str (random-uuid))
           :seon.error/at now
           :seon.error/process (:seon.cluster.run/process cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.error/basis-t (:max-tx db)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)}
          (when-let [escalate-to (:seon.config.error/escalate-to cluster)]
            {:seon.config.error/escalate-to escalate-to})
          attribution)))

(defn- asked-value
  "The message-family value one completed evaluation asks to deliver."
  [{db :seon.db/db
    evaluation :seon.sci.eval/evaluation
    settled ::settled
    problem :seon.problems/form-problem
    agent-id :seon.cluster.agent/id
    trigger :seon.cluster.message/trigger}]
  (or (messages (:seon.sci.admit/value evaluation))
      (when (= :completed (:my.run/disposition settled))
        (message/reply
         db
         (cond-> {:my.run/result (:my.run/result settled)
                  :seon.cluster.agent/id agent-id}
           trigger (assoc :seon.cluster.message/trigger trigger))))
      (when problem (problems/assignment-value problem))))

(defn- delivery-rows
  "Delivery rows and refusal transaction data for one asked value."
  [{db :seon.db/db
    cluster ::cluster
    asked ::asked
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    ordinal :seon.cluster.run.form/ordinal
    now ::now
    trigger :seon.cluster.message/trigger}]
  (let [delivery
        (when asked
          (message/delivery
           db
           (cond-> {:my.message/value asked
                    :seon.cluster.agent/id agent-id
                    :seon.cluster.run/id run-id
                    :seon.cluster.run.form/ordinal ordinal
                    :seon.cluster.message/at now
                    :seon.config.message/max-chain
                    (:seon.config.message/max-chain cluster)}
             trigger (assoc :seon.cluster.message/trigger trigger))))]
    {:seon.cluster.message/rows (:seon.cluster.message/rows delivery)
     :seon.error/values-tx
     (into []
           (mapcat
            (fn [failure]
              (error-tx cluster db failure now
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/id run-id})))
           (:seon.error/values delivery))}))

(defn- refused!
  "Record one refused transaction as a durable error, and say it refused.
  Returns true when `outcome` was a refusal, so a call site reads
  `(if (refused! …) :error :released)` and the recording is not a
  second branch to keep in sync.

  THIS IS THE HOLE D3 NAMED. `db/transact!` preserves a transition's
  own rule verbatim — the exact CAS fence, the exact schema violation —
  and four of these five branches threw it away one line later, reducing
  it to the keyword `:error` in a turn report that goes to an out
  documented \"for observation only\". The `:call` branch had already
  learned the lesson the expensive way (the live drive sat
  claimed-with-no-plan for two minutes because a model error evaporated);
  this is that fix applied to the other four, now that the error owner
  those values belong to exists.

  Attribution is passed in, never derived here: an `:open` that REFUSED
  has no run to point at, and a lookup ref to a run that does not exist
  would fail the very transaction that records the failure.

  The recording's own outcome is deliberately ignored. This is the
  recursion fence for a refusal that owns no running receipt: if the
  database refuses the error fact too, the answer is not to record
  THAT — `db/transact!` never throws, the loop keeps its pass, and
  the visible symptom stays the original refusal rather than an
  infinite regress of them. A refused terminal receipt uses
  `terminal-refused!` below instead, because recording alone cannot
  settle work derivation."
  [cluster outcome now attribution]
  (boolean
   (when-let [kind (:seon.error/kind outcome)]
     (let [connection (:seon.db/connection cluster)
           db @connection]
       (db/transact!
        connection
        (error-tx cluster db outcome now attribution)))
     kind)))

(defn- terminal-settlement-fault!
  "Stop this agent's passes and raise one named core settlement fault."
  [cluster message data]
  ;; This agent may not take another pass over the still-running receipt
  ;; before boot recovery marks it interrupted. Closing its process-local
  ;; mailbox loses no work — wakes are re-derivable from database facts
  ;; and arm! creates a fresh channel after reboot — while leaving Flow's
  ;; independent error channel alive to carry this fault.
  (async/close! (:seon.cluster.wake/channel cluster))
  (throw
   (ex-info
    message
    (merge
     {::terminal-refusal-settlement-failed true
      :seon.error/kind ::terminal-refusal-settlement-refused}
     data))))

(defn- terminal-refused!
  "Settle and record a refused terminal transaction, and say it refused.

  The original transaction remains commit-first: its program row,
  disposition, receipt, and context installation all stay absent when
  the database refuses any one of them. Before the second transaction
  exists, the refusal passes through the one bounded admission codec and
  the resulting error fact plus flat value must satisfy their registered
  shapes. This second transaction therefore carries only the SAME
  presence-fenced receipt settlement, the run close, and a pre-admitted
  normalized durable error record. There is no program row, disposition,
  unbounded source value, or schema-invalid error value left for this
  minimal transaction to refuse.

  The receipt terminal fact, run close, and error fact commit together.
  Success is checked rather than assumed. If construction is invalid or
  the commit still refuses, a named Throwable escapes as a core fault
  into Flow's error channel; this function never returns silent success.
  The receipt remains running in its held run, which boot recovery marks
  interrupted without re-executing it."
  [cluster outcome now attribution receipt]
  (boolean
   (when-let [kind (:seon.error/kind outcome)]
     (let [connection (:seon.db/connection cluster)
           db @connection
           admitted
           (admit/admit
            {:seon.sci.admit/value outcome
             :seon.sci.admit/interrupt-fn (constantly nil)
             :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
             ;; Error-path admission is total. R41 applies if the
             ;; resulting settlement is invalid or refuses below.
             :seon.config/on-core-error :record})
           source
           (select-keys
            (:seon.sci.admit/value admitted)
            [:seon.error/kind
             :seon.error/message
             :seon.error/data
             :seon.cluster.run/rule
             :seon.cluster.run/transition])
           ;; The receipt is the run provenance for this eval result.
           ;; Keeping a second run ref on the recorder fact would store
           ;; the same connection twice; agent attribution is the one
           ;; connection the next prompt needs to reach the fact.
           recording
           (try
             (error-tx cluster db source now
                       (dissoc attribution :seon.cluster.run/id))
             ;; Instrumentation guards `error/normalize`'s declared
             ;; output. If malformed source makes construction violate
             ;; that contract, translate the guardrail into this seam's
             ;; named core fault rather than letting instrumentation
             ;; replace the incident it was observing.
             (catch Throwable _
               (terminal-settlement-fault!
                cluster
                "Terminal refusal settlement could not be constructed."
                {::admitted-outcome
                 (:seon.sci.admit/value admitted)})))
           fact (first recording)
           failure (error/value fact)
           failure-edn (pr-str failure)
           valid?
           (and
            (schema/valid-candidate-value?
             :seon.error/fact
             (dissoc fact :db/id))
            (schema/valid-candidate-value?
             :seon.error/value
             failure))
           terminal
           (when valid?
             (run/receipt-refusal-tx
              {:seon.cluster.run/id (:seon.cluster.run/id receipt)
               :seon.cluster.eval/ordinal
               (:seon.cluster.run.form/ordinal receipt)
               :seon.cluster.run/closed-at now
               :seon.cluster.eval/result-edn failure-edn
               :seon.cluster.eval/result-size (long (count failure-edn))
               :seon.cluster.eval/error (:seon.error/message failure)
               :seon.error/kind (:seon.error/kind failure)}))]
       (when-not valid?
         (terminal-settlement-fault!
          cluster
          "Terminal refusal settlement could not be constructed."
          {::admitted-outcome
           (:seon.sci.admit/value admitted)}))
       (let [settlement
             (db/transact! connection (into terminal recording))]
         (when (:seon.error/kind settlement)
           ;; The fault committer may itself write an explanation
           ;; message. Fence that message from re-entering this exact
           ;; dangling receipt before recovery; its durable row remains
           ;; available to the fresh mailbox after reboot.
           (terminal-settlement-fault!
            cluster
            "Terminal refusal settlement was refused."
            {::admitted-outcome
             (:seon.sci.admit/value admitted)
             ::settlement settlement}))
         true))
     kind)))

(defn- attempt-id
  "One model attempt's identity: derived, so nothing allocates a uuid.
  `<run-id>-attempt-<ordinal>` — the same (run, ordinal) idiom receipts
  use, and the reason a re-entered `:call` pass appends to the chain
  instead of overwriting its first row."
  [run-id ordinal]
  (str run-id "-attempt-" ordinal))

(defn- attempts
  "How many model attempts this run has already recorded.
  DERIVED at the start of a `:call` pass so the next ordinal continues
  the chain. A run whose plan transaction refused stays claimed and
  reaches `:call` again; without this its second call would reuse
  ordinal 0 and upsert away the first attempt's evidence.

  LONG, not `count`'s Integer. Datahike's `:db.type/long` validator is
  `(= (class %) java.lang.Long)` exactly, so an Integer ordinal refuses
  the WHOLE transaction — taking the error fact down with the attempt
  row. Coerced here, where the number is born, rather than at the call
  sites that would each have to remember."
  [db run-id]
  (long
   (count (db/q '[:find ?attempt
                 :in $ ?run-id
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?attempt :seon.ai.attempt/run ?run]]
               db run-id))))

;;; The transport-phase evidence the leaf recorded, carried onto the
;;; attempt row under THE PRODUCER'S OWN KEYS. Selected rather than
;;; re-keyed one by one: a `cond->` per field is four chances to drop
;;; one silently, and `false` is a meaningful value here that a
;;; truthiness test would eat. OBSERVATIONS ONLY (owner ruling
;;; 2026-07-28): the error class and the disposition are pure functions
;;; of this evidence (`seon.ai/status-class`, `seon.ai/disposition`
;;; over the error fact's data-edn), derived at read, never stored
;;; beside the facts they restate.
(def ^:private evidence-attributes
  [:seon.ai/http-status :seon.ai/request-transmitted?
   :seon.ai/response-started? :seon.ai/output-observed?])

(defn- attempt-evidence
  "Provider evidence projected from one completion or failure value."
  [{completion :seon.ai/completion}]
  (cond-> {}
    (:seon.ai.model/last-latency-ms completion)
    (assoc :seon.ai.model/last-latency-ms
           (:seon.ai.model/last-latency-ms completion))
    (or (:seon.ai/usage completion)
        (get-in completion [:seon.error/data :seon.ai/usage]))
    (assoc :seon.ai/usage
           (or (:seon.ai/usage completion)
               (get-in completion [:seon.error/data :seon.ai/usage])))
    (or (:seon.ai/reasoning-content completion)
        (get-in completion [:seon.error/data :seon.ai/reasoning-content]))
    (assoc :seon.ai/reasoning-content
           (or (:seon.ai/reasoning-content completion)
               (get-in completion
                       [:seon.error/data :seon.ai/reasoning-content])))
    (or (:seon.ai/finish-reason completion)
        (get-in completion [:seon.error/data :seon.ai/finish-reason]))
    (assoc :seon.ai/finish-reason
           (or (:seon.ai/finish-reason completion)
               (get-in completion
                       [:seon.error/data :seon.ai/finish-reason])))))

(defn- attempt-request
  "One record-attempt request assembled from target, evidence, and provenance."
  [{:keys [:seon.ai/target :seon.ai/settings
           :seon.ai.attempt/ordinal :seon.error/value
           :seon.ai.attempt/failover-from :seon.ai.attempt/delay-ms]
    run-id :seon.cluster.run/id
    agent-id :seon.cluster.agent/id
    evidence ::attempt-evidence}]
  (cond-> (merge {:seon.ai/target target
                  :seon.ai/settings settings
                  :seon.cluster.run/id run-id
                  :seon.cluster.agent/id agent-id
                  :seon.ai.attempt/ordinal ordinal}
                 evidence)
    value (assoc :seon.error/value value)
    failover-from (assoc :seon.ai.attempt/failover-from failover-from)
    delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms)))

(defn- provider-targets
  "Resolved provider targets, settings, and finite schedule for one turn."
  [{db :seon.db/db
    cluster-name :seon.cluster/name
    agent-id :seon.cluster.agent/id}]
  (let [settings (ai/settings (config/effective db cluster-name)
                              (ai/agent-overlay db agent-id))
        targets (ai/targets db settings)
        primary (:seon.ai/primary targets)
        backup (:seon.ai/backup targets)
        strategy (ai/retry-strategy settings)]
    {:seon.ai/primary primary
     :seon.ai/backup backup
     :seon.ai/settings settings
     ::schedule (if backup [] (ai/delays strategy rand))}))

(defn- record-attempt!
  "Commit ONE model attempt — and its error fact when it failed.
  Returns the COMMITTED error fact on failure, nil otherwise.

  The error fact and the attempt row ride ONE transaction, with the
  attempt's `:seon.ai.attempt/error` pointing at the fact through the
  shared tempid. That is not tidiness: the caller may only build the
  backup's context from a fact that is already durable, and one
  transaction is what makes \"already durable\" true with no window.

  Returning nil after a REFUSED transaction is therefore load-bearing
  too — it means the story could not be recorded, and the caller
  correctly refuses to make a second paid call it would be unable to
  explain."
  [cluster request now]
  (let [{target :seon.ai/target
         failure :seon.error/value
         run-id :seon.cluster.run/id
         agent-id :seon.cluster.agent/id
         ordinal :seon.ai.attempt/ordinal
         usage :seon.ai/usage
         latency-ms :seon.ai.model/last-latency-ms
         settings :seon.ai/settings
         reasoning-content :seon.ai/reasoning-content
         finish-reason :seon.ai/finish-reason
         delay-ms :seon.ai.attempt/delay-ms
         failover-from :seon.ai.attempt/failover-from} request
        connection (:seon.db/connection cluster)
        db @connection
        reasoning-size (when (seq reasoning-content)
                         (long (count reasoning-content)))
        threshold (result-blob-threshold db)
        reasoning-blob (when (and reasoning-size threshold
                                  (> reasoning-size threshold))
                         (blob/put! connection reasoning-content))
        commit (when failure
                 (error-tx cluster db failure now
                           {:seon.cluster.agent/id agent-id
                            :seon.cluster.run/id run-id}))
        row (cond-> (merge
                     {:seon.ai.attempt/id (attempt-id run-id ordinal)
                      :seon.ai.attempt/run [:seon.cluster.run/id run-id]
                      :seon.ai.attempt/ordinal ordinal
                      :seon.ai.attempt/at now
                      :seon.ai/endpoint (:seon.ai/endpoint target)
                      :seon.ai/model (:seon.ai/model target)}
                     (select-keys (:seon.error/data failure)
                                  evidence-attributes))
              ;; the fact is created by THIS transaction, so the ref is
              ;; its tempid — a lookup ref to something the same
              ;; transaction is still creating is not a bet to take.
              ;; THE REF'S PRESENCE IS THE OUTCOME: an attempt failed
              ;; exactly when it points at an error fact, and there is
              ;; no stored :success/:error label restating that.
              commit (assoc :seon.ai.attempt/error (:db/id (first commit)))
              settings
              (assoc :seon.ai.attempt/settings-edn (pr-str settings))
              usage (assoc :seon.ai.attempt/usage-edn (pr-str usage))
              (and reasoning-size (nil? reasoning-blob))
              (assoc :seon.ai.attempt/reasoning reasoning-content)
              reasoning-blob
              (assoc :seon.ai.attempt/reasoning-blob reasoning-blob
                     :seon.ai.attempt/reasoning-size reasoning-size)
              finish-reason
              (assoc :seon.ai.attempt/finish-reason finish-reason)
              ;; ROLE BY CONNECTION: only the backup points back, so a
              ;; reader can tell a failover from a retry without a stamp
              failover-from (assoc :seon.ai.attempt/failover-from
                                   [:seon.ai.attempt/id failover-from])
              delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms))
        observation-tx
        (ai/model-observation-tx
         db
         (cond-> {:seon.ai.model/id (:seon.ai/model target)
                  :seon.ai.model/last-used-at now}
           (some? latency-ms)
           (assoc :seon.ai.model/last-latency-ms latency-ms)
           usage (assoc :seon.ai/usage usage)))
        outcome
        (db/transact! connection
                      (into (conj (vec commit) row) observation-tx))]
    (when-not (:seon.error/kind outcome)
      (some-> commit first (dissoc :db/id)))))

(defn- form-data
  "The source and parse-time namespace of one form of a run, by ordinal."
  [db run-id ordinal]
  (when-let [form-eid
             (db/q '[:find ?form .
                    :in $ ?run-id ?ordinal
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?form :seon.cluster.run.form/run ?run]
                    [?form :seon.cluster.run.form/ordinal ?ordinal]]
                  db run-id ordinal)]
    (let [form (db/pull db
                       [:seon.cluster.run.form/source
                        {:seon.cluster.run.form/ns [:seon.ns/name]}]
                       form-eid)]
      (cond-> {:seon.cluster.run.form/source
               (:seon.cluster.run.form/source form)}
        (:seon.cluster.run.form/ns form)
        (assoc :seon.cluster.run.form/ns
               [:seon.ns/name
                (get-in form [:seon.cluster.run.form/ns :seon.ns/name])])))))

(defn- fold-namespace
  "The committed namespace in effect immediately before `ordinal`."
  [db run-id ordinal]
  (or
   (ffirst
    (db/q {:query
           '[:find ?ending-ns ?previous
             :in $ ?run-id ?ordinal
             :where
             [?run :seon.cluster.run/id ?run-id]
             [?receipt :seon.cluster.eval/run ?run]
             [?receipt :seon.cluster.eval/ordinal ?previous]
             [(< ?previous ?ordinal)]
             [?receipt :seon.sci.eval/ending-ns ?ending-ns]]
           :args [db run-id ordinal]
           :order-by '[?previous :desc]
           :limit 1}))
   (db/q '[:find ?starting-ns .
           :in $ ?run-id
           :where
           [?run :seon.cluster.run/id ?run-id]
           [?run :seon.cluster.run/starting-ns ?namespace]
           [?namespace :seon.ns/name ?starting-ns]]
         db run-id)))

(defn- admitted-form
  "One run form after namespace-sensitive static admission."
  [{db :seon.db/db
    run-id :seon.cluster.run/id
    ordinal :seon.cluster.run.form/ordinal
    ctx :seon.sci.eval/ctx
    current-namespace ::current-namespace
    fallback-namespace ::fallback-namespace}]
  (let [form (form-data db run-id ordinal)
        evaluation-namespace
        (or current-namespace
            (second (:seon.cluster.run.form/ns form))
            fallback-namespace)
        namespace-row
        (db/pull db
                '[* {:seon.ns/requires [:seon.ns/name]}
                    {:seon.ns/aliases [*]}
                    {:seon.ns/imports [*]}
                    {:seon.ns/refers [*]}]
                [:seon.ns/name evaluation-namespace])
        admitted-source
        (lint-form
         (cond->
          {:seon.ns/name evaluation-namespace
           ::available-functions (available-functions db ctx)
           ::source
           {:seon.cluster.run.form/source
            (:seon.cluster.run.form/source form)
            :seon.ns/name evaluation-namespace}}
           namespace-row (assoc ::namespace-row namespace-row)))]
    (assoc form
           :seon.cluster.run.form/source
           (:seon.cluster.run.form/source admitted-source)
           :seon.cluster.run.form/ns
           [:seon.ns/name evaluation-namespace])))

(defn- evaluation-request
  "One admitted form projected into the guarded evaluation request."
  [{form ::admitted-form
    evaluation-namespace ::evaluation-namespace
    cluster ::cluster
    ctx :seon.sci.eval/ctx
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    form-ordinal :seon.cluster.run.form/ordinal}]
  (merge form
         (cond->
          {:seon.cluster.run.form/ns [:seon.ns/name evaluation-namespace]
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.sci.eval/ctx ctx
           :seon.cluster.agent/id agent-id
           :seon.cluster.run/id run-id
           :seon.cluster.run.form/ordinal form-ordinal
           :seon.boot/cluster-name (:seon.cluster/name cluster)
           :seon.sci.eval/time-limit-ms
           (:seon.config.eval/time-limit-ms cluster)
           :seon.config/on-core-error
           (:seon.config/on-core-error cluster)}
           (:seon.flow/work-launcher cluster)
           (assoc :seon.flow/work-launcher
                  (:seon.flow/work-launcher cluster)))))

(defn settle-interruption!
  "Bury one orphaned run so its agent stops being busy.
  Planned or unplanned, an unheld run is not work: there is no cold
  resume. Settling is claim-then-close through the ordinary
  transitions: a survivor cannot close a run it does not hold
  (`close-call` refuses `::not-the-holder`), so it takes custody by the
  takeover path first and closes as the holder.

  Boot recovery released the dead custody; this releases the AGENT.
  The two are deliberately separate: recovery states who no longer
  holds what, and settlement decides what to do about it — and only
  the loop is entitled to decide that.

  Settle-only for N3. The explanation an agent reads is derived from
  the settled run's own shape (no plan, closed) by
  `seon.cluster.prompt`; when a richer reason is wanted, this
  transaction is where it would ride."
  {:malli/schema [:=> [:cat :seon.cluster.loop/cluster
                       :seon.cluster.run/id :inst]
                  :boolean]}
  [cluster run-id now]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        claimed (db/transact!
                 connection
                 (run/claim-tx {:seon.cluster.run/id run-id
                                :seon.cluster.run/process process
                                ;; the only live process on this branch
                                ;; is this one — flock + single writer
                                :seon.cluster.run/live-processes #{process}
                                :seon.cluster.run/now now}))]
    (if (:seon.error/kind claimed)
      false
      (let [closed (db/transact!
                    connection
                    (run/close-tx {:seon.cluster.run/id run-id
                                   :seon.cluster.run/process process
                                   ;; the pass's ONE clock, not a second
                                   ;; reading of it — review-caught, and
                                   ;; the reason a state-machine property
                                   ;; over settlements can be exact
                                   :seon.cluster.run/closed-at now}))]
        (not (:seon.error/kind closed))))))

(defn- open-turn
  "Open and claim one run before any paid provider call."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)]
    ;; OPEN + CLAIM FIRST, model second. The busy fence has to exist
    ;; before the expensive part, and the run records its trigger in
    ;; this same transaction so answeredness is an ordinary fact.
    (let [id (str (random-uuid))
          outcome (db/transact!
                   connection
                   {:tx-data
                    (into (run/open-tx
                           (cond->
                            {:seon.cluster.run/id id
                             :seon.cluster.run/agent
                             [:seon.cluster.agent/id agent-id]
                             :seon.cluster.run/opening-commit-id
                             (db/commit-id @connection)
                             :seon.cluster.run/opened-at now}
                             (:seon.cluster.message/id work)
                             (assoc
                              :seon.cluster.run/trigger
                              [:seon.cluster.message/id
                               (:seon.cluster.message/id work)])))
                          (run/claim-tx {:seon.cluster.run/id id
                                         :seon.cluster.run/process process
                                         :seon.cluster.run/live-processes
                                         #{process}
                                         :seon.cluster.run/now now}))})]
      ;; a REFUSED open has no run to attribute to — the run is what
      ;; failed to exist
      (report (if (refused! cluster outcome now
                            {:seon.cluster.agent/id agent-id})
                :error
                :released)
              0))))

(defn- call-turn
  "Call the provider and freeze the returned plan."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)]
    ;; THE PAID CALL, and the ONE place a second one is ever made.
    ;;
    ;; NOTHING RE-CALLS A REQUEST THAT MAY HAVE BEEN TRANSMITTED. That
    ;; is not a rule this branch remembers to follow — `ai/disposition`
    ;; is the choke point, computed from the phase evidence the leaf
    ;; recorded, and every path out of a failure here goes through it.
    ;; The branch itself only reduces over its three ordinary values:
    ;;
    ;; - `:failover-now` — a conclusively unpaid failure WITH a backup
    ;;   configured. The primary's error fact commits FIRST, and the
    ;;   backup's system segment is the notice's `:seon.render/ai`
    ;;   projection through the one router over that committed fact;
    ;; - `:backoff` — a conclusively unpaid TRANSIENT failure with no
    ;;   backup. The schedule is derived once, is EMPTY whenever a
    ;;   backup exists, and each wait is one more attempt row;
    ;;   the no-backup path is therefore the backoff path by
    ;;   construction rather than by a second condition;
    ;; - `:fail` — the run closes with the error, and the step-2
    ;;   delivery machinery does the rest.
    ;;
    ;; Every attempt, successful or not, leaves one `:seon.ai/attempt`
    ;; row. That is what makes "exactly two calls" and "exactly one
    ;; call" queryable facts rather than claims.
    (let [;; ONE TURN, ONE RESOLUTION. Both reads use this immutable
          ;; database value, and resolution stays outside the attempt
          ;; reduce so failover/backoff cannot change settings halfway
          ;; through a turn. Applying config or retracting/asserting an
          ;; agent override therefore changes the NEXT turn, without a
          ;; graph rebuild or a cached derived projection.
          db @connection
          providers (provider-targets
                     {:seon.db/db db
                      :seon.cluster/name (:seon.cluster/name cluster)
                      :seon.cluster.agent/id agent-id})
          settings (:seon.ai/settings providers)
          primary (:seon.ai/primary providers)
          backup (:seon.ai/backup providers)
          schedule (::schedule providers)
          ;; STREAMING IS ON BY CONSTRUCTION (F2 §2.1): the sink is
          ;; one `offer!` of the run id plus the complete
          ;; `:seon.ai/partial` snapshot
          ;; onto the cluster's ONE sliding-1 stream conn — newest
          ;; wins, a slow render pass can never backpressure the
          ;; provider fold, and a streamed call and a one-shot call
          ;; return the same completion value. There is no dial; a
          ;; handle with no stream channel simply calls one-shot.
          stream-channel (:seon.cluster.loop/stream-channel cluster)
          sink (when stream-channel
                 (fn [snapshot]
                   (async/offer! stream-channel
                                 {:seon.cluster.agent/id agent-id
                                  :seon.cluster.run/id run-id
                                  :seon.ai/partial snapshot})))
          fail! (fn fail!
                  ([failure]
                   (fail! failure false))
                  ([failure record-refusal?]
                   ;; ONE transaction: the run closes and WHY it closed
                   ;; lands with it. A pre-provider refusal also commits its
                   ;; error fact here, so the existing episode derivation can
                   ;; distinguish it structurally from a failed model attempt.
                   ;; This terminal FACT is also the stream terminal: its
                   ;; render wake replaces any transient partial.
                   (let [db @connection
                         recording
                         (when record-refusal?
                           (error-tx
                            cluster db failure now
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id run-id}))
                         outcome
                         (db/transact!
                          connection
                          (into (vec recording)
                                (cons
                                 [:db/add [:seon.cluster.run/id run-id]
                                  :seon.cluster.run/error
                                  (:seon.error/message failure)]
                                 (run/close-tx
                                  {:seon.cluster.run/id run-id
                                   :seon.cluster.run/process process
                                   :seon.cluster.run/closed-at now}))))]
                     (when (and record-refusal?
                                (:seon.error/kind outcome))
                       (terminal-settlement-fault!
                        cluster
                        "Pre-provider refusal settlement was refused."
                        {::settlement outcome}))
                     (report :error 0))))
          freeze!
          (fn [completion]
            ;; Freeze the reply's exact ordered source. Static admission is
            ;; part of the REPL reduce below because an earlier form may
            ;; change the resolver state that makes the next form valid.
            ;; The frozen plan FACT is the stream terminal; no lossy channel
            ;; value carries "done".
            (let [namespace-name (sci.eval/agent-namespace @connection agent-id)
                  sources
                  (reply/sources (:seon.ai/text completion)
                                 namespace-name)]
              (if (:seon.error/kind sources)
                (fail! sources)
                (let [outcome (db/transact!
                               connection
                               (run/plan-tx
                                {:seon.cluster.run/id run-id
                                 :seon.cluster.run/process process
                                 :seon.cluster.run/plan-digest
                                 (digest sources)
                                 :seon.cluster.run/sources
                                 sources}))]
                  (report (if (refused! cluster outcome now
                                        {:seon.cluster.agent/id agent-id
                                         :seon.cluster.run/id run-id})
                            :error
                            :released)
                          0)))))
          ;; THE PROMPT REQUEST NAMES THE HELD RUN — `prompt` derives
          ;; the trigger from the run's own creating transaction
          ;; (`message/trigger`), never a re-asked queue: the recorded
          ;; cause is the prompt's cause. One derivation, one owner.
          ;; NOTHING THROWS INTO THE AGENT LOOP: the prompt owner
          ;; refuses by throwing (`::no-trigger`, `::missing-input`),
          ;; and this one call site turns that refusal into the flat
          ;; error value the loop already records — the same shape a
          ;; refused transaction takes through `db/transact!`.
          rendered (try
                     (prompt/prompt @connection
                                    {:seon.cluster.run/id run-id
                                     :seon.cluster.agent/id agent-id
                                     :seon.sci.admit/caps
                                     (:seon.sci.admit/caps cluster)
                                     :seon.sci.eval/ctx
                                     (:seon.sci.eval/ctx cluster)
                                     :seon.sci.eval/time-limit-ms
                                     (:seon.config.eval/time-limit-ms cluster)
                                     :seon.config/on-core-error
                                     (:seon.config/on-core-error cluster)
                                     :seon.render/context-channel
                                     (:seon.render/context-channel cluster)})
                     (catch Exception failure
                       ;; the kind fallback keeps this total: an
                       ;; exception carrying no flat error data still
                       ;; ends the turn as a recorded value rather
                       ;; than falling through to a nil-prompt call
                       (merge {:seon.error/kind ::prompt-failed
                               :seon.error/message (ex-message failure)}
                              (error/refusal failure))))
          ;; CAPTURE BEFORE THE PROVIDER (ruling 4, 2026-07-28): the
          ;; exact prompt text, the rendered basis and the ordered
          ;; contribution records commit in ONE turn-owned transaction
          ;; BEFORE the unobservable remote call. Writer ordering then
          ;; guarantees: no capture → the prompt was never derived;
          ;; capture with no attempt row → the call may never have
          ;; fired. Failover/backoff attempts inside this same pass
          ;; REUSE this one capture — the same prompt bytes go out,
          ;; and the backup's system segment is re-derivable from the
          ;; committed primary error fact, never re-captured.
          captured (if (:seon.error/kind rendered)
                     ;; a refused prompt derivation IS the turn's
                     ;; outcome — there is nothing to capture and no
                     ;; provider call to make
                     rendered
                     (db/transact!
                      connection
                      (context/capture-tx
                       {:seon.cluster.run/id run-id
                        :seon.cluster.prompt/rendered-context rendered})))
          ;; THE EXACT-TEXT HANDOFF: the loop extracts the rendered
          ;; text and alone places that string in `:seon.ai/prompt` —
          ;; the bytes the capture recorded are the bytes sent.
          text (:seon.cluster.prompt/text rendered)]
      (if (:seon.error/kind captured)
        ;; A refused prompt/capture closes this run and records the refusal.
        ;; The next pass derives correction from those facts below the ONE
        ;; episode cap; at the cap it derives no work. No provider call occurs
        ;; without durable prompt evidence.
        (fail! captured true)
        (loop [target primary
               ordinal (attempts @connection run-id)
               ;; ABSENT on the primary and on every backoff retry;
               ;; present only on the backup, where it is both the role
               ;; and the proof of which failure supplied its context
               failover-from nil
               delay-ms nil
               waits schedule
               system nil]
          (let [completion (ai/complete
                            (cond-> (assoc target :seon.ai/prompt text)
                              system (assoc :seon.ai/system system)
                              sink (assoc :seon.ai/stream? true
                                          :seon.ai/sink sink)))
                failure (when (:seon.error/kind completion) completion)
                evidence (attempt-evidence {:seon.ai/completion completion})
                ;; a backup is only ever a target ONCE: the attempt that
                ;; already failed over cannot fail over again, and that
                ;; is what bounds a failover at exactly two calls
                disposition (when failure
                              (ai/disposition
                               {:seon.error/value failure
                                :seon.ai/backup? (and (some? backup)
                                                      (nil? failover-from))}))
                fact (record-attempt! cluster
                                      (attempt-request
                                       (cond->
                                        {:seon.ai/target target
                                         :seon.ai/settings settings
                                         :seon.cluster.run/id run-id
                                         :seon.cluster.agent/id agent-id
                                         :seon.ai.attempt/ordinal ordinal
                                         ::attempt-evidence evidence}
                                         failure
                                         (assoc :seon.error/value failure)
                                         failover-from
                                         (assoc :seon.ai.attempt/failover-from
                                                failover-from)
                                         delay-ms
                                         (assoc :seon.ai.attempt/delay-ms
                                                delay-ms)))
                                      now)]
            (cond
              (nil? failure) (freeze! completion)

              ;; THE RECORD REFUSED. Nothing else here is safe: a second
              ;; paid call whose reason could not be committed is a call
              ;; nobody could explain afterwards, and the backup's own
              ;; context would have no fact to project.
              (nil? fact) (fail! failure)

              (= :failover-now disposition)
              (recur backup
                     (inc ordinal)
                     (attempt-id run-id ordinal)
                     nil
                     waits
                     ;; THE PROJECTION, over the fact that is now
                     ;; durable — never a notice written at this call
                     ;; site. The backup reads exactly what the agent,
                     ;; the escalation owner and the log read.
                     (render/render-ai
                      {:seon.db/db @connection
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                       :seon.render/value
                       (error/notice {:seon.error/fact fact
                                      :seon.error/reason :failover})
                       :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                       :seon.sci.eval/time-limit-ms
                       (:seon.config.eval/time-limit-ms cluster)
                       :seon.config/on-core-error
                       (:seon.config/on-core-error cluster)}))

              (and (= :backoff disposition) (seq waits))
              (do
                ;; `:workload :io` is load-bearing here as well as at
                ;; the model call: this proc may block, and the wait is
                ;; bounded by a finite schedule rather than a loop
                ;; condition
                (Thread/sleep (long (first waits)))
                (recur target
                       (inc ordinal)
                       nil
                       (first waits)
                       (rest waits)
                       system))

              ;; `:fail`, and an exhausted schedule reaches the same
              ;; place: the run closes with the error, and step 2's
              ;; delivery machinery does the rest
              :else (fail! failure))))))))

(defn- resume-turn
  "Reduce one held run over its remaining admitted forms."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)]
    ;; THE FOLD, in one turn, over a fresh fork of the cluster's live base.
    ;; Every form in this turn shares the fork; the next turn forks the then
    ;; current base and rehydrates this agent's desk again.
    ;;
    ;; Boot recovery closes interrupted runs before any agent graph is
    ;; armed, so this branch is only the ordinary live fold over one
    ;; ctx. A cold fold starting at ordinal k > 0 cannot reach it.
    (let [base-ctx (:seon.sci.eval/ctx cluster)
          {ctx :seon.sci.eval/ctx
           desk-notices :seon.sci.eval/desk-notices}
          (sci.eval/fork-for-turn
           {:seon.sci.eval/ctx base-ctx
            :seon.db/db @connection
            :seon.db/connection connection
            :seon.cluster.agent/id agent-id})
          compiled-evaluate
          (requiring-resolve (:seon.cluster.loop/evaluate cluster))
          ;; The public walk and every renderer share this evaluation's exact
          ;; cluster context. Bind it on the actual compute worker so nested
          ;; agent calls use the same database value, live SCI ctx, time limit,
          ;; and core-error disposition as the form that called them.
          evaluate
          (fn [request]
            (render/call-with-walk-context
             {:seon.db/db @connection
              :seon.db/connection connection
              :seon.cluster.agent/id agent-id
              :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
              :seon.sci.eval/ctx ctx
              :seon.sci.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms cluster)
              :seon.config/on-core-error
              (:seon.config/on-core-error cluster)}
             #(compiled-evaluate request)))
          ;; the message this run is answering, read ONCE per turn: it
          ;; is the head of the conversation chain every message this
          ;; turn sends extends, and it cannot change while the run is
          ;; held
          trigger (message/trigger @connection run-id)]
      (loop [ordinal (:seon.cluster.run.form/ordinal work)
             ran 0
             namespace-name
             (fold-namespace @connection run-id
                             (:seon.cluster.run.form/ordinal work))]
        (let [receipt-id (pr-str [run-id ordinal])
              problem-id (work/problem-id run-id ordinal)
              started
              (db/transact!
               connection
               (conj
                (run/receipt-start-tx
                 {:seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  :seon.cluster.eval/at now})
                [:db/add [:seon.cluster.eval/id receipt-id]
                 :seon.problems/id problem-id]))]
          (if (refused! cluster started now
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/id run-id})
            (report :error ran)
            (let [db-before-evaluation @connection
                  form
                  (admitted-form
                   {:seon.db/db db-before-evaluation
                    :seon.cluster.run/id run-id
                    :seon.cluster.run.form/ordinal ordinal
                    :seon.sci.eval/ctx ctx
                    ::current-namespace namespace-name
                    ::fallback-namespace
                    (sci.eval/agent-namespace db-before-evaluation agent-id)})
                  evaluation-namespace
                  (second (:seon.cluster.run.form/ns form))
                  evaluation
                  (submit-evaluation!!
                   cluster
                   evaluate
                   receipt-id
                   (evaluation-request
                    (cond->
                     {::admitted-form form
                      ::evaluation-namespace evaluation-namespace
                      ::cluster cluster
                      :seon.sci.eval/ctx ctx
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id run-id
                      :seon.cluster.run.form/ordinal ordinal}
                      (and (zero? ran) (seq desk-notices))
                      (assoc :seon.sci.eval/output-prefix
                             (str/join "\n" desk-notices)))))
                  problem
                  (problems/form-problem
                   @connection
                   {:seon.cluster.run/id run-id
                    :seon.cluster.run.form/ordinal ordinal
                    :seon.sci.eval/evaluation evaluation})
                  settled (disposition (:seon.sci.admit/value evaluation))
                  ;; THE SECOND AGENT-FACING VALUE, resolved against
                  ;; the same database value this receipt is about.
                  ;; Rows and refusal facts BOTH ride the terminal
                  ;; transaction: a message that exists without the
                  ;; receipt explaining where it came from is the torn
                  ;; window this loop has closed everywhere else.
                  ;; WHAT THIS FORM ASKS TO SEND — explicitly, or by
                  ;; completing a run somebody else asked for. The
                  ;; second is derived from the trigger rather than
                  ;; remembered by the agent: bob computed the right
                  ;; answer on the first live drive and called
                  ;; `complete`, which addressed nobody, and alice
                  ;; waited forever for a number that already existed.
                  ;; The reply is an ordinary `my.message` value, so
                  ;; it goes through the same bound, the same
                  ;; recipient check and the same derived id.
                  db-after-evaluation @connection
                  asked
                  (asked-value
                   (cond-> {:seon.db/db db-after-evaluation
                            :seon.sci.eval/evaluation evaluation
                            ::settled settled
                            :seon.cluster.agent/id agent-id}
                     problem (assoc :seon.problems/form-problem problem)
                     trigger (assoc :seon.cluster.message/trigger trigger)))
                  ;; an undeliverable message is a durable fact, never
                  ;; a drop — and `error/commit-tx` composes with
                  ;; itself now that its tempid derives from the
                  ;; error's own id rather than being a constant
                  delivery
                  (delivery-rows
                   (cond-> {:seon.db/db db-after-evaluation
                            ::cluster cluster
                            ::asked asked
                            :seon.cluster.agent/id agent-id
                            :seon.cluster.run/id run-id
                            :seon.cluster.run.form/ordinal ordinal
                            ::now now}
                     trigger (assoc :seon.cluster.message/trigger trigger)))
                  rows (:seon.cluster.message/rows delivery)
                  refusals (:seon.error/values-tx delivery)
                  settlement-evaluation
                  (settlement-result cluster evaluation)
                  desk-evaluation
                  (store-desk-values! connection evaluation)
                  desk-rows
                  (desk-rows @connection agent-id desk-evaluation ordinal)
                  receipt
                  (receipt-request
                   (cond-> {:seon.cluster.run/id run-id
                            :seon.cluster.run/process process
                            :seon.cluster.run.form/ordinal ordinal
                            :seon.sci.eval/evaluation evaluation
                            :seon.def/rows desk-rows
                            ::settlement-evaluation settlement-evaluation}
                     problem (assoc :seon.problems/form-problem problem)
                     settled (assoc :my.run/value settled)))
                  outcome
                  (db/transact!
                   connection
                   {:tx-data
                    (into (terminal-tx receipt now)
                          (concat rows
                                  refusals))})
                  _
                  (if (and (:seon.program/row evaluation)
                           (not (:seon.error/kind outcome)))
                    (let [row (:seon.program/row evaluation)
                          db-after (:db-after outcome)]
                      (sci.eval/install-row!
                       {:seon.sci.eval/ctx base-ctx
                        :seon.db/db db-after
                        :seon.program/row
                        (dissoc row :seon.sci.eval/evaluated?)})
                      (sci.eval/install-row!
                       {:seon.sci.eval/ctx ctx
                        :seon.db/db db-after
                        :seon.program/row row}))
                    nil)
                  ran (inc ran)
                  ;; THE FOLD'S OWN NEXT ORDINAL IS PER-AGENT (F1
                  ;; §5.2): asking the GLOBAL derivation here was the
                  ;; conservation audit's verified defect — wrong the
                  ;; moment two agents run, because another agent's
                  ;; earlier work would answer this run's question.
                  next-ordinal
                  (when-not (or settled (:seon.error/kind outcome))
                    (:seon.cluster.run.form/ordinal
                     (work/next-agent-work
                      @connection
                      {:seon.cluster.agent/id agent-id
                       :seon.cluster.run/process process
                       :seon.cluster.work/now now})))]
              (cond
                (terminal-refused!
                 cluster outcome now
                 {:seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id}
                 receipt)
                (report :error ran)

                ;; both dispositions CLOSE the run in the terminal
                ;; transaction now, so a settled fold always reports
                ;; the run closed
                settled (report :closed ran)
                next-ordinal
                (recur next-ordinal ran
                       (or (:seon.sci.eval/ending-ns evaluation)
                           evaluation-namespace))
                :else (report :released ran)))))))))

(defn- close-turn
  "Claim when needed and close one fully settled run."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)]
    ;; the fold is done and nothing said otherwise: close it, so the
    ;; agent stops being busy.
    ;;
    ;; CLAIM FIRST WHEN WE DO NOT HOLD IT, and this is a fix, not a
    ;; flourish: `next-agent-work` derives `:close` for any open planned run
    ;; whose forms are all settled, INCLUDING one nobody holds — a run
    ;; released by `my.run/wait`, or one whose holder died after the
    ;; last receipt. `close-call` refuses a run it is not the holder
    ;; of (`::not-the-holder`), so those closes failed, the derivation
    ;; kept returning `:close`, and the self-rewake kept firing:
    ;; a HOT LIVELOCK committing one error fact per pass. Measured on
    ;; the wait path — twelve passes, nine error facts, `next-agent-work`
    ;; still saying `:close`. Taking custody first is the same
    ;; takeover `settle-interruption!` already uses, and it is what
    ;; makes "only the holder may close a run" a rule the loop can
    ;; keep rather than one it repeatedly breaks.
    (let [held (db/pull @connection [:seon.cluster.run/process]
                       [:seon.cluster.run/id run-id])
          claimed (when-not (= process (:seon.cluster.run/process held))
                    (db/transact!
                     connection
                     (run/claim-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/live-processes
                                    #{process}
                                    :seon.cluster.run/now now})))
          outcome (if (:seon.error/kind claimed)
                    ;; somebody else holds it: not ours to close, and
                    ;; not an error of ours either
                    claimed
                    (db/transact!
                     connection
                     (run/close-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/closed-at now})))]
      (report (if (refused! cluster outcome now
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id run-id})
                :error
                :closed)
              0))))

(defn turn
  "Run one turn to its next durable boundary; returns the turn report.
  The sequence is the contract: claim → derive prompt → model (`:io`)
  → split reply → freeze plan → reduce over ordered forms (running
  receipt → guarded eval at the previous step's `:db-after` → terminal
  receipt + disposition in ONE transaction) → close or release.
  Every failure inside it is a VALUE: a model error, an unreadable
  reply, and a refused transaction each end the turn with facts the
  agent reads on its next wake. Nothing throws into the loop."
  {:malli/schema [:=> [:cat :seon.cluster.loop/turn-request :inst]
                  :seon.cluster.loop/turn-report]}
  [{:keys [:seon.cluster.loop/cluster] work :seon.cluster.work/next}
   now]
  (let [agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)
        report (fn [outcome forms-run]
                 (cond-> {:seon.cluster.agent/id agent-id
                          :seon.cluster.work/situation
                          (:seon.cluster.work/situation work)
                          :seon.cluster.loop/forms-run forms-run
                          :seon.cluster.loop/outcome outcome}
                   run-id (assoc :seon.cluster.run/id run-id)))
        request {::cluster cluster
                 ::work work
                 ::now now
                 ::report report}]
    (case (:seon.cluster.work/situation work)
      :open (open-turn request)
      :call (call-turn request)
      :resume (resume-turn request)
      :close (close-turn request))))
