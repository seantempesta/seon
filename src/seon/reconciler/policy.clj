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

(def block-bounce-types
  "System bounce message types that invalidate prior implicit or explicit readiness until a newer edit lands."
  (conj failure-bounce-types :gate-blocked))

(defn summarize-activity
  "Purely derives `tool-streak`, `turns-since-external`, `bounce-streak`, `last-was-prose?`, trailing model errors, and latest failure metadata from `eval-ents` + `inbox`."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] :map]
   :cost/ticks 500000 :cost/bytes 524288}
  [eval-ents inbox]
  (let [evs (vec (filter map? eval-ents))
        msgs (vec (filter map? inbox))
        system-mids (into #{} (keep #(when (contains? system-bounce-types (:seon.agent.message/type %)) (:seon.agent.message/id %))) msgs)
        bounce-streak (count (take-while #(contains? system-bounce-types (:seon.agent.message/type %)) (rseq msgs)))
        latest-fail (some #(when (contains? failure-bounce-types (:seon.agent.message/type %)) %) (rseq msgs))
        latest-block (some #(when (contains? block-bounce-types (:seon.agent.message/type %)) %) (rseq msgs))
        last-eval (peek evs)
        trailing-errs (vec (take-while model-err-eval? (rseq evs)))
        last-err (first trailing-errs)
        last-err-msg (when last-err (str (or (:seon.agent.eval/error last-err) "")))
        non-retryable? (boolean (and last-err (non-retryable-error-str? last-err-msg)))
        non-err-evals (filterv #(not (model-err-eval? %)) evs)
        last-non-err (peek non-err-evals)
        last-tool? (boolean (and last-non-err (model-tool-eval? last-non-err)))
        last-prose? (boolean (and last-non-err (not last-tool?) (not (str/blank? (str (or (:seon.agent.eval/thought last-non-err) ""))))))
        tool-streak (loop [i (dec (count non-err-evals)), turns #{}]
                      (if (neg? i) (count turns)
                          (let [ev (nth non-err-evals i)]
                            (if (model-tool-eval? ev)
                              (let [turns-next (conj turns (turn-key ev i))]
                                (if (seen-external? ev system-mids) (count turns-next) (recur (dec i) turns-next)))
                              (count turns)))))
        turns-since-ext (loop [i (dec (count evs)), turns #{}]
                          (if (neg? i) (count turns)
                              (let [ev (nth evs i) turns-next (conj turns (turn-key ev i))]
                                (if (seen-external? ev system-mids) (count turns-next) (recur (dec i) turns-next)))))]
    {:eval-count (count evs)
     :last-eval last-eval
     :last-was-tool? last-tool?
     :last-was-prose? last-prose?
     :tool-streak tool-streak
     :turns-since-external turns-since-ext
     :bounce-streak bounce-streak
     :trailing-model-errors (count trailing-errs)
     :last-err-tx (when (integer? (:tx last-err)) (long (:tx last-err)))
     :last-err-inst (when (integer? (:tx-inst last-err)) (long (:tx-inst last-err)))
     :last-err-msg last-err-msg
     :non-retryable-model-error? non-retryable?
     :latest-failure-tx (when (integer? (:tx latest-fail)) (long (:tx latest-fail)))
     :latest-failure-inst (when (integer? (:tx-inst latest-fail)) (long (:tx-inst latest-fail)))
     :latest-block-tx (when (integer? (:tx latest-block)) (long (:tx latest-block)))
     :latest-block-inst (when (integer? (:tx-inst latest-block)) (long (:tx-inst latest-block)))}))

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

(defn implicit-ready?
  "Derives automatic merge readiness purely from Datahike branch edits + prose turn completion (no manual `ready!` required)."
  {:malli/schema [:=> [:cat :map] :boolean]
   :cost/ticks 50000 :cost/bytes 65536}
  [{:keys [non-main? auto-merge? has-branch-edits? last-was-prose? has-unread? err-halted? bounce-halted?
           merged-tx last-edit-tx last-edit-inst same-db? latest-block-tx latest-block-inst has-unread-failure?]}]
  (let [etx (if (integer? last-edit-tx) (long last-edit-tx) 0)
        after-merge? (or (not (integer? merged-tx)) (> etx (long merged-tx)))
        failed-after? (boolean
                       (or has-unread-failure?
                           (if same-db?
                             (and (integer? latest-block-tx) (>= (long latest-block-tx) etx))
                             (and (integer? latest-block-inst)
                                  (or (not (integer? last-edit-inst))
                                      (>= (long latest-block-inst) (long last-edit-inst)))))))]
    (boolean (and non-main? auto-merge? has-branch-edits? (pos? etx) last-was-prose?
                  (not has-unread?) (not err-halted?) (not bounce-halted?)
                  after-merge? (not failed-after?)))))

(defn decide-agent-step
  "Single pure decision kernel shared by `seon.reconciler/derive-agent-status` and `seon.health.liveness`."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 200000 :cost/bytes 131072}
  [{:keys [branch paused? busy? awaiting-blocked? waiting? raw-ready? ready-tx ready-inst
           tested-tx merged-tx head-tx same-db? auto-merge? gate-ok? has-merge-target?
           in-testing? in-merging? review-notified? has-unread? has-external-unread?
           has-unread-failure? fail-after-ready? newest-external-unread-tx newest-external-unread-inst
           latest-failure-tx latest-failure-inst latest-block-tx latest-block-inst
           has-branch-edits? last-edit-tx last-edit-inst last-was-prose?
           post-ready-reply? has-pending-review-ops? last-was-tool? tool-streak turns-since-external bounce-streak
           trailing-model-errors last-err-tx last-err-inst non-retryable-model-error?
           max-tool-turns max-bounces max-total-turns]}]
  (let [non-main? (and (some? branch) (not= branch :main))
        paused? (boolean paused?) busy? (boolean busy?) awaiting-blocked? (boolean awaiting-blocked?)
        waiting? (boolean waiting?) raw-ready? (boolean raw-ready?) auto-merge? (boolean auto-merge?)
        gate-ok? (if (some? gate-ok?) (boolean gate-ok?) true)
        has-mt? (boolean has-merge-target?) has-unread? (boolean has-unread?) has-ext? (boolean has-external-unread?)
        max-tt (if (integer? max-tool-turns) (long max-tool-turns) default-max-tool-turns)
        max-b (if (integer? max-bounces) (long max-bounces) default-max-bounces)
        max-tot (if (integer? max-total-turns) (long max-total-turns) (if (pos? max-tt) (+ max-tt 8) default-max-total-turns))
        streak (if (integer? tool-streak) (long tool-streak) 0)
        bounces (if has-ext? 0 (if (integer? bounce-streak) (long bounce-streak) 0))
        turns (if has-ext? 0 (if (integer? turns-since-external) (long turns-since-external) 0))
        err-h? (model-error-halted? {:trailing-model-errors trailing-model-errors :non-retryable-model-error? non-retryable-model-error?
                                     :last-err-tx last-err-tx :last-err-inst last-err-inst :same-db? same-db?
                                     :newest-external-unread-tx newest-external-unread-tx :newest-external-unread-inst newest-external-unread-inst})
        bounce-h? (and (pos? max-b) (>= bounces max-b))
        budget-h? (and (pos? max-tot) (>= turns max-tot))
        exp-r? (effective-ready? {:raw-ready? raw-ready? :ready-tx ready-tx :ready-inst ready-inst :merged-tx merged-tx :same-db? same-db?
                                  :latest-failure-tx latest-failure-tx :latest-failure-inst latest-failure-inst
                                  :has-unread-failure? has-unread-failure? :fail-after-ready? fail-after-ready?})
        auto-r? (implicit-ready? {:non-main? non-main? :auto-merge? auto-merge? :has-branch-edits? (boolean has-branch-edits?)
                                  :last-was-prose? (boolean last-was-prose?) :has-unread? has-unread?
                                  :err-halted? err-h? :bounce-halted? bounce-h?
                                  :merged-tx merged-tx :last-edit-tx last-edit-tx :last-edit-inst last-edit-inst
                                  :same-db? same-db? :latest-block-tx (or latest-block-tx latest-failure-tx)
                                  :latest-block-inst (or latest-block-inst latest-failure-inst) :has-unread-failure? has-unread-failure?})
        ready? (or exp-r? auto-r?)
        merged? (boolean (and (not has-unread?) (some? merged-tx) (= tested-tx head-tx) (= merged-tx head-tx)))
        active? (and (not paused?) (not busy?) (not awaiting-blocked?))
        under-cap? (or (<= max-tt 0) (< streak max-tt))
        cutoff? (boolean (and has-mt? non-main? (not ready?) (not merged?) (not err-h?) (not has-unread?)
                              (or (and (pos? max-tt) (>= streak max-tt)) waiting?)))
        rev-abort-req? (boolean (and active? has-mt? non-main? (not merged?)
                                     (or err-h? bounce-h?
                                         (and budget-h? (not (and ready? gate-ok? (zero? bounces))))
                                         (and cutoff? (or (not auto-merge?) (not gate-ok?) (pos? bounces)))
                                         (and raw-ready? (not auto-merge?) (not has-unread?)))))
        rev-abort? (and rev-abort-req? (not (boolean review-notified?)))
        cutoff-merge? (and cutoff? auto-merge? (not rev-abort-req?) (not has-unread?))
        elig? (and non-main? (or ready? cutoff-merge?) auto-merge? (not rev-abort-req?))
        needs-turn? (boolean (and active? (not err-h?) (not bounce-h?) (not budget-h?) (not rev-abort-req?)
                                  (not has-pending-review-ops?) (not cutoff?)
                                  (or has-unread? (boolean post-ready-reply?) (and (not raw-ready?) (not waiting?) (boolean last-was-tool?) under-cap?))))
        needs-wrapup? (boolean (and (not cutoff?) (not rev-abort-req?) (not err-h?) (not bounce-h?) (not budget-h?)
                                    active? (not has-pending-review-ops?) (pos? max-tt) (= streak max-tt)
                                    (not raw-ready?) (not waiting?) (boolean last-was-tool?) (not has-unread?)))
        ready-idle? (and active? (not has-unread?) (not post-ready-reply?) elig?)
        needs-test? (and ready-idle? (not= tested-tx head-tx) (not (boolean in-testing?)))
        needs-merge? (and ready-idle? (= tested-tx head-tx) gate-ok? (not= merged-tx head-tx) (not (boolean in-merging?)))
        status (cond paused? :paused busy? :running awaiting-blocked? :awaiting needs-turn? :runnable
                     (and elig? (not= tested-tx head-tx)) :testing
                     (and elig? (not gate-ok?)) :gate-blocked
                     (and elig? (not= merged-tx head-tx)) :merging
                     merged? :merged :else :waiting)]
    {:ready? ready? :auto-ready? auto-r? :merged? merged? :active? active?
     :err-halted? err-h? :bounce-halted? bounce-h? :turn-budget-halted? budget-h?
     :under-turn-cap? under-cap? :cutoff-reached? cutoff?
     :review-should-abort? rev-abort-req? :review-abort? rev-abort?
     :cutoff-auto-merge? cutoff-merge? :eligible-to-merge? elig?
     :needs-turn? needs-turn? :needs-wrapup? needs-wrapup? :needs-test? needs-test? :needs-merge? needs-merge?
     :bounce-streak bounces :turns-since-external turns :status status}))
