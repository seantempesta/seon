(ns seon.reconciler.policy
  "Pure, zero-DB agent step policy kernel shared by `seon.reconciler/derive-agent-status` and `seon.health.liveness`.
   Eliminates cross-branch tx-ID bugs, bounce livelocks, crashed reviewer auto-merges, and orphan-paused parents.
"
  (:require [clojure.string :as str]))

(def system-bounce-types
  "System feedback message types that bounce an agent without resetting `tool-streak`, `turns-since-external`, or `bounce-streak`."
  #{:test-failure :merge-conflict :health-block :gate-blocked :protocol-nudge :turn-cap-wrapup})

(def failure-bounce-types
  "System bounce message types representing concrete branch test/merge/health failures that invalidate prior `:seon.agent/ready?`."
  #{:test-failure :merge-conflict :health-block})

(def default-max-tool-turns 24)

(def default-max-bounces 4)

(def default-max-total-turns 32)

(defn system-bounce-type?
  "True when message type `t` is an automated system bounce rather than external human/agent input."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 5000 :cost/bytes 16384}
  [t]
  (contains? system-bounce-types t))

(defn non-retryable-error-str?
  "True when model error string `s` represents a deterministic non-retryable failure (auth, missing API key, HTTP 400, context overflow, or `:retryable? false`)."
  {:malli/schema [:=> {:examples [[["Model error [model/auth]: Invalid API key"] true]
                                  [["Model HTTP 400: invalid_request_error maximum context length"] true]
                                  [["Model error [model/api-error]: transient 503"] false]
                                  [[nil] false]]}
                  [:cat [:maybe :string]] :boolean]
   :cost/ticks 50000 :cost/bytes 65536}
  [s]
  (boolean
   (and (string? s)
        (not (str/blank? s))
        (re-find #"\[(?:model/auth|missing-api-key)\]|(?i)(?:http\s*400|invalid_request_error|context_length|maximum\s+context\s+length|bad\s+request|:retryable\?\s+false|non-retryable)"
                 s))))

(defn- model-err-eval? [ev]
  (and (map? ev)
       (str/blank? (str (or (:seon.agent.eval/code ev) "")))
       (not (str/blank? (str (or (:seon.agent.eval/error ev) ""))))))

(defn- model-tool-eval? [ev]
  (and (map? ev)
       (some? (:seon.agent.eval/call-id ev))
       (not (str/blank? (str (or (:seon.agent.eval/code ev) ""))))))

(defn- seen-external? [ev system-mids]
  (let [sm (:seon.agent.eval/seen-messages ev)]
    (boolean (and (coll? sm) (not (map? sm))
                  (some #(not (contains? system-mids %)) sm)))))

(defn- turn-key [ev idx]
  (let [t (:seon.agent.eval/turn ev)]
    (if (integer? t) (long t) [:idx idx])))

(defn summarize-activity
  "Purely derives `tool-streak`, `turns-since-external`, `bounce-streak`, trailing model errors, and latest failure metadata from `eval-ents` + `inbox`."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] :map]
   :cost/ticks 500000 :cost/bytes 524288}
  [eval-ents inbox]
  (let [evs (vec (filter map? eval-ents))
        msgs (vec (filter map? inbox))
        system-mids (into #{}
                          (keep #(when (contains? system-bounce-types (:seon.agent.message/type %))
                                   (:seon.agent.message/id %)))
                          msgs)
        bounce-streak (count (take-while #(contains? system-bounce-types (:seon.agent.message/type %))
                                         (rseq msgs)))
        latest-fail (some #(when (contains? failure-bounce-types (:seon.agent.message/type %)) %)
                          (rseq msgs))
        last-eval (peek evs)
        trailing-errs (vec (take-while model-err-eval? (rseq evs)))
        last-err (first trailing-errs)
        last-err-msg (when last-err (str (or (:seon.agent.eval/error last-err) "")))
        non-retryable? (boolean (and last-err
                                     (or (false? (:retryable? last-err))
                                         (non-retryable-error-str? last-err-msg))))
        non-err-evals (filterv #(not (model-err-eval? %)) evs)
        last-non-err (peek non-err-evals)
        tool-streak (loop [i (dec (count non-err-evals)), turns #{}]
                      (if (neg? i)
                        (count turns)
                        (let [ev (nth non-err-evals i)]
                          (if (model-tool-eval? ev)
                            (let [turns' (conj turns (turn-key ev i))]
                              (if (seen-external? ev system-mids)
                                (count turns')
                                (recur (dec i) turns')))
                            (count turns)))))
        turns-since-ext (loop [i (dec (count evs)), turns #{}]
                          (if (neg? i)
                            (count turns)
                            (let [ev (nth evs i)
                                  turns' (conj turns (turn-key ev i))]
                              (if (seen-external? ev system-mids)
                                (count turns')
                                (recur (dec i) turns')))))]
    {:eval-count (count evs)
     :last-eval last-eval
     :last-was-tool? (boolean (and last-non-err (model-tool-eval? last-non-err)))
     :tool-streak tool-streak
     :turns-since-external turns-since-ext
     :bounce-streak bounce-streak
     :trailing-model-errors (count trailing-errs)
     :last-err-tx (when (integer? (:tx last-err)) (long (:tx last-err)))
     :last-err-inst (when (integer? (:tx-inst last-err)) (long (:tx-inst last-err)))
     :last-err-msg last-err-msg
     :non-retryable-model-error? non-retryable?
     :latest-failure-tx (when (integer? (:tx latest-fail)) (long (:tx latest-fail)))
     :latest-failure-inst (when (integer? (:tx-inst latest-fail)) (long (:tx-inst latest-fail)))}))

(defn model-error-halted?
  "True when trailing model errors should halt retries (1 turn for non-retryable errors, 3 for transient),
   comparing `:db/txInstant` across distinct DBs (`main-db` vs `branch-db`) and tx IDs within the same DB."
  {:malli/schema [:=> [:cat :map] :boolean]
   :cost/ticks 50000 :cost/bytes 65536}
  [{:keys [trailing-model-errors non-retryable-model-error? last-err-tx last-err-inst
           same-db? newest-external-unread-tx newest-external-unread-inst]}]
  (let [n (if (integer? trailing-model-errors) (long trailing-model-errors) 0)]
    (boolean
     (when (pos? n)
       (let [fresh-ext? (if same-db?
                          (and (integer? newest-external-unread-tx)
                               (> (long newest-external-unread-tx)
                                  (if (integer? last-err-tx) (long last-err-tx) 0)))
                          (and (integer? newest-external-unread-inst)
                               (or (not (integer? last-err-inst))
                                   (> (long newest-external-unread-inst) (long last-err-inst)))))]
         (and (not fresh-ext?)
              (or (boolean non-retryable-model-error?) (>= n 3))))))))

(defn effective-ready?
  "Derives effective `:ready?` state: true only when `:seon.agent/ready?` is newer than `:merged-tx`
   and has not been invalidated by an unread or subsequent `:test-failure` / `:merge-conflict` / `:health-block`."
  {:malli/schema [:=> [:cat :map] :boolean]
   :cost/ticks 50000 :cost/bytes 65536}
  [{:keys [raw-ready? ready-tx ready-inst merged-tx same-db?
           latest-failure-tx latest-failure-inst has-unread-failure? fail-after-ready?]}]
  (let [rtx (if (integer? ready-tx) (long ready-tx) 0)
        after-merge? (or (not (integer? merged-tx)) (> rtx (long merged-tx)))
        failed-later? (boolean
                       (or fail-after-ready?
                           has-unread-failure?
                           (if same-db?
                             (and (integer? latest-failure-tx) (> (long latest-failure-tx) rtx))
                             (and (integer? latest-failure-inst)
                                  (integer? ready-inst)
                                  (> (long latest-failure-inst) (long ready-inst))))))]
    (boolean (and raw-ready? after-merge? (not failed-later?)))))

(defn decide-agent-step
  "Single pure decision kernel shared by `seon.reconciler/derive-agent-status` and `seon.health.liveness`.
   Computes turn dispatch, wrap-up, test, merge, reviewer abort/cutoff, halt states, and derived `:status`."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 200000 :cost/bytes 131072}
  [{:keys [branch paused? busy? awaiting-blocked? waiting? raw-ready? ready-tx ready-inst
           tested-tx merged-tx head-tx same-db? auto-merge? gate-ok? has-merge-target?
           in-testing? in-merging? review-notified? has-unread? has-external-unread?
           has-unread-failure? fail-after-ready? newest-external-unread-tx newest-external-unread-inst
           latest-failure-tx latest-failure-inst post-ready-reply? has-pending-review-ops?
           eval-count last-was-tool? tool-streak turns-since-external bounce-streak
           trailing-model-errors last-err-tx last-err-inst non-retryable-model-error?
           max-tool-turns max-bounces max-total-turns]}]
  (let [non-main? (and (some? branch) (not= branch :main))
        paused? (boolean paused?)
        busy? (boolean busy?)
        awaiting-blocked? (boolean awaiting-blocked?)
        waiting? (boolean waiting?)
        raw-ready? (boolean raw-ready?)
        auto-merge? (boolean auto-merge?)
        gate-ok? (if (some? gate-ok?) (boolean gate-ok?) true)
        has-merge-target? (boolean has-merge-target?)
        has-unread? (boolean has-unread?)
        has-external-unread? (boolean has-external-unread?)
        max-tt (if (integer? max-tool-turns) (long max-tool-turns) default-max-tool-turns)
        max-b (if (integer? max-bounces) (long max-bounces) default-max-bounces)
        max-tot (if (integer? max-total-turns)
                  (long max-total-turns)
                  (if (pos? max-tt) (+ max-tt 8) default-max-total-turns))
        eff-tool-streak (if (integer? tool-streak) (long tool-streak) 0)
        eff-bounces (if has-external-unread? 0 (if (integer? bounce-streak) (long bounce-streak) 0))
        eff-turns (if has-external-unread? 0 (if (integer? turns-since-external) (long turns-since-external) 0))
        eval-n (if (integer? eval-count) (long eval-count) 0)
        ready? (effective-ready?
                {:raw-ready? raw-ready? :ready-tx ready-tx :ready-inst ready-inst
                 :merged-tx merged-tx :same-db? same-db?
                 :latest-failure-tx latest-failure-tx :latest-failure-inst latest-failure-inst
                 :has-unread-failure? has-unread-failure? :fail-after-ready? fail-after-ready?})
        err-halted? (model-error-halted?
                     {:trailing-model-errors trailing-model-errors
                      :non-retryable-model-error? non-retryable-model-error?
                      :last-err-tx last-err-tx :last-err-inst last-err-inst
                      :same-db? same-db?
                      :newest-external-unread-tx newest-external-unread-tx
                      :newest-external-unread-inst newest-external-unread-inst})
        bounce-halted? (and (pos? max-b) (>= eff-bounces max-b))
        turn-budget-halted? (and (pos? max-tot) (>= eff-turns max-tot))
        merged? (boolean (and (or raw-ready? has-merge-target?) (not has-unread?)
                              (some? merged-tx) (= tested-tx head-tx) (= merged-tx head-tx)))
        active? (and (not paused?) (not busy?) (not awaiting-blocked?))
        under-turn-cap? (or (<= max-tt 0) (< eff-tool-streak max-tt))
        cutoff-reached? (boolean
                         (and has-merge-target? non-main? (not raw-ready?) (not merged?)
                              (not err-halted?) (not has-unread?)
                              (or (and (pos? max-tt) (>= eff-tool-streak max-tt))
                                  (and waiting? (pos? eval-n))
                                  waiting?)))
        review-should-abort? (boolean
                              (and active? has-merge-target? non-main? (not merged?)
                                   (or err-halted?
                                       bounce-halted?
                                       (and turn-budget-halted?
                                            (not (and ready? gate-ok? (zero? eff-bounces))))
                                       (and cutoff-reached?
                                            (or (not auto-merge?) (not gate-ok?) (pos? eff-bounces)))
                                       (and raw-ready? (not auto-merge?) (not has-unread?)))))
        review-abort? (and review-should-abort? (not (boolean review-notified?)))
        cutoff-auto-merge? (and cutoff-reached? auto-merge? (not review-should-abort?) (not has-unread?))
        eligible-to-merge? (and non-main? (or ready? cutoff-auto-merge?) auto-merge? (not review-should-abort?))
        needs-turn? (boolean
                     (and active? (not err-halted?) (not bounce-halted?) (not turn-budget-halted?)
                          (not review-should-abort?) (not has-pending-review-ops?) (not cutoff-reached?)
                          (or has-unread? (boolean post-ready-reply?)
                              (and (not raw-ready?) (not waiting?) (boolean last-was-tool?) under-turn-cap?))))
        needs-wrapup? (boolean
                       (and (not cutoff-reached?) (not review-should-abort?) (not err-halted?)
                            (not bounce-halted?) (not turn-budget-halted?)
                            active? (not has-pending-review-ops?)
                            (pos? max-tt) (= eff-tool-streak max-tt)
                            (not raw-ready?) (not waiting?) (boolean last-was-tool?) (not has-unread?)))
        ready-idle? (and active? (not has-unread?) (not post-ready-reply?) eligible-to-merge?)
        needs-test? (and ready-idle? (not= tested-tx head-tx) (not (boolean in-testing?)))
        needs-merge? (and ready-idle? (= tested-tx head-tx) gate-ok?
                          (not= merged-tx head-tx) (not (boolean in-merging?)))
        status (cond
                 paused? :paused
                 busy? :running
                 awaiting-blocked? :awaiting
                 needs-turn? :runnable
                 (and eligible-to-merge? (not= tested-tx head-tx)) :testing
                 (and eligible-to-merge? (not gate-ok?)) :gate-blocked
                 (and eligible-to-merge? (not= merged-tx head-tx)) :merging
                 merged? :merged
                 :else :waiting)]
    {:ready? ready?
     :merged? merged?
     :active? active?
     :err-halted? err-halted?
     :bounce-halted? bounce-halted?
     :turn-budget-halted? turn-budget-halted?
     :under-turn-cap? under-turn-cap?
     :cutoff-reached? cutoff-reached?
     :review-should-abort? review-should-abort?
     :review-abort? review-abort?
     :cutoff-auto-merge? cutoff-auto-merge?
     :eligible-to-merge? eligible-to-merge?
     :needs-turn? needs-turn?
     :needs-wrapup? needs-wrapup?
     :needs-test? needs-test?
     :needs-merge? needs-merge?
     :bounce-streak eff-bounces
     :turns-since-external eff-turns
     :status status}))
