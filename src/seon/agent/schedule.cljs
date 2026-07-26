(ns seon.agent.schedule
  "Model cron schedules and fire due agent work.

   This namespace owns schedule schemas, pure cron parsing and matching, and
   the ticker-facing transition that opens scheduled runs. Time inputs are
   explicit for deterministic logic; loop driving is injected to preserve the
   runtime dependency boundary."
  (:require
    [clojure.string :as str]
    [seon.agent.run.core]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as db.protocol]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]))

;; ============================================================
;; Schema — the schedule entity (per agent-runtime-spec §seon.agent.schedule).
;; ============================================================

(schema/register!
  :seon.agent.schedule/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.agent.schedule/cron     :string)   ; 5-field cron expression
(schema/register! :seon.agent.schedule/fn       :symbol)   ; qualified fn invoked when due
;; The DUE schedule fn symbols handed to the injected executor at fire time
;; (the qualified fns whose schedules matched `now`), so the fired run RUNS
;; them rather than waking with zero context.
(schema/register! :seon.agent.schedule/fns      [:vector :symbol])
(schema/register! :seon.agent.schedule/timezone :string)   ; IANA tz; default host tz
(schema/register! :seon.agent.schedule/concurrency-policy
                  [:enum :forbid :allow])  ; :forbid = don't open a 2nd run

(schema/register! :seon.agent.schedule
  [:map {:seon.db/entity true}
   [:seon.agent.schedule/id                 :seon.agent.schedule/id]
   [:seon.agent.schedule/cron               :seon.agent.schedule/cron]
   [:seon.agent.schedule/fn                 :seon.agent.schedule/fn]
   [:seon.agent.schedule/timezone           {:optional true} :seon.agent.schedule/timezone]
   [:seon.agent.schedule/concurrency-policy {:optional true} :seon.agent.schedule/concurrency-policy]])

(defn host-timezone
  "The host process's IANA timezone string.

   The default for a schedule with no explicit `:timezone`."
  {:malli/schema [:=> [:cat] :seon.agent.schedule/timezone]}
  []
  (.. (js/Intl.DateTimeFormat) (resolvedOptions) -timeZone))

;; ============================================================
;; Pure cron parsing — 5 fields: minute hour day-of-month month day-of-week.
;; Each field → a SET of allowed integers (so matching is set membership).
;; Supports: *  */n  n  a-b  a-b/n  and comma lists of those.
;; ============================================================

(schema/register! :seon.agent.schedule/ok?   :boolean)
(schema/register! :seon.agent.schedule/error :string)
;; One parsed field — the set of integers the field allows.
(schema/register! :seon.agent.schedule/field [:set :int])

(schema/register! :seon.agent.schedule/parsed
  [:map
   [:seon.agent.schedule/ok?          :seon.agent.schedule/ok?]
   [:seon.agent.schedule/minute       {:optional true} :seon.agent.schedule/field]
   [:seon.agent.schedule/hour         {:optional true} :seon.agent.schedule/field]
   [:seon.agent.schedule/day-of-month {:optional true} :seon.agent.schedule/field]
   [:seon.agent.schedule/month        {:optional true} :seon.agent.schedule/field]
   [:seon.agent.schedule/day-of-week  {:optional true} :seon.agent.schedule/field]
   [:seon.agent.schedule/error        {:optional true} :seon.agent.schedule/error]])

(def ^:private field-specs
  "Per-field key + inclusive [lo hi] range. day-of-week 0-6, 0 = Sunday
   (matches JS `Date.getDay`)."
  [[:seon.agent.schedule/minute       0 59]
   [:seon.agent.schedule/hour         0 23]
   [:seon.agent.schedule/day-of-month 1 31]
   [:seon.agent.schedule/month        1 12]
   [:seon.agent.schedule/day-of-week  0 6]])

(defn- parse-uint
  "Parse a string of ASCII digits to a non-negative int, or nil if it isn't
   all digits."
  [s]
  (when (re-matches #"\d+" s) (js/parseInt s 10)))

(defn- expand-part
  "Expand ONE comma-part of a field (`*`, `*/n`, `n`, `a-b`, `a-b/n`) into a
   set of ints within [lo hi], or `:invalid`."
  [part lo hi]
  (let [[base step-s] (str/split part #"/" 2)
        step (if step-s (parse-uint step-s) 1)]
    (if (or (nil? step) (<= step 0))
      :invalid
      (let [rng (cond
                  (= base "*") [lo hi]
                  (str/includes? base "-")
                  (let [[a b] (str/split base #"-" 2)
                        a (parse-uint a) b (parse-uint b)]
                    (when (and a b) [a b]))
                  :else
                  (let [n (parse-uint base)] (when n [n n])))]
        (if (nil? rng)
          :invalid
          (let [[a b] rng]
            (if (and (>= a lo) (<= b hi) (<= a b))
              (into #{} (range a (inc b) step))
              :invalid)))))))

(defn- parse-field
  "Parse one whole field (comma list of parts) → a set of ints, or
   `:invalid`."
  [field lo hi]
  (let [sets (map #(expand-part % lo hi) (str/split field #","))]
    (if (some #{:invalid} sets)
      :invalid
      (reduce into #{} sets))))

(schema/register! ::parse-request [:map [:seon.agent.schedule/cron :seon.agent.schedule/cron]])

(defn parse
  "Parse a 5-field cron string into per-field sets of allowed ints.

   → `{::ok? true ::minute #{…} ::hour #{…} ::day-of-month #{…} ::month #{…}
   ::day-of-week #{…}}`. An invalid string (wrong field count / bad field)
   returns `{::ok? false ::error \"…\"}` — never throws."
  {:malli/schema [:=> [:cat ::parse-request] :seon.agent.schedule/parsed]}
  [{cron :seon.agent.schedule/cron}]
  (let [fields (-> cron str/trim (str/split #"\s+"))]
    (if (not= 5 (count fields))
      {:seon.agent.schedule/ok? false
       :seon.agent.schedule/error
       (str "cron must have exactly 5 fields (minute hour day-of-month month "
            "day-of-week); got " (count fields) " in " (pr-str cron))}
      (let [parsed (map (fn [[k lo hi] f] [k (parse-field f lo hi)]) field-specs fields)]
        (if (some (fn [[_ v]] (= :invalid v)) parsed)
          {:seon.agent.schedule/ok? false
           :seon.agent.schedule/error (str "invalid cron field(s) in " (pr-str cron))}
          (into {:seon.agent.schedule/ok? true} parsed))))))

;; ============================================================
;; Matching — does an instant match a parsed cron? (local host time)
;; ============================================================

(def ^:private full-dom (into #{} (range 1 32)))   ; every day-of-month
(def ^:private full-dow (into #{} (range 0 7)))    ; every day-of-week

(defn- day-matches?
  "Cron day semantics: when BOTH day-of-month and day-of-week are restricted
   (not the full range), a match in EITHER is a match; when only one is
   restricted, that one must match; when neither, any day matches."
  [p ^js/Date d]
  (let [dom    (:seon.agent.schedule/day-of-month p)
        dow    (:seon.agent.schedule/day-of-week p)
        date   (.getDate d)
        wday   (.getDay d)                 ; 0 = Sunday … 6 = Saturday
        dom-r? (not= dom full-dom)
        dow-r? (not= dow full-dow)]
    (cond
      (and dom-r? dow-r?) (or (contains? dom date) (contains? dow wday))
      dom-r?              (contains? dom date)
      dow-r?              (contains? dow wday)
      :else               true)))

(defn- matches?
  "Does instant `d` (local time) match parsed cron `p`?"
  [p ^js/Date d]
  (and (contains? (:seon.agent.schedule/minute p) (.getMinutes d))
       (contains? (:seon.agent.schedule/hour p)   (.getHours d))
       (contains? (:seon.agent.schedule/month p)  (inc (.getMonth d)))  ; JS month 0-based
       (day-matches? p d)))

(schema/register! ::due-request
  [:map
   [:seon.agent.schedule/cron :seon.agent.schedule/cron]
   [:seon.agent.schedule/now  :inst]])

(defn due?
  "Does `now` (truncated to the minute) match `cron`?

   An unparseable cron is never due (false)."
  {:malli/schema [:=> [:cat ::due-request] :boolean]}
  [{cron :seon.agent.schedule/cron now :seon.agent.schedule/now}]
  (let [p (parse {:seon.agent.schedule/cron cron})]
    (boolean (and (:seon.agent.schedule/ok? p) (matches? p now)))))

(def ^:private scan-limit-minutes
  "Upper bound on the forward minute-scan (~366 days) — a 5-field cron repeats
   at most yearly, so no match within this window means none exists."
  (* 366 24 60))

(schema/register! ::next-fire-at-request
  [:map
   [:seon.agent.schedule/cron  :seon.agent.schedule/cron]
   [:seon.agent.schedule/after :inst]])

(defn next-fire-at
  "The next instant STRICTLY AFTER `after` that matches `cron`.

   Scans minute-by-minute, seconds zeroed; nil if none within ~366 days or the
   cron is unparseable."
  {:malli/schema [:=> [:cat ::next-fire-at-request] [:maybe :inst]]}
  [{cron :seon.agent.schedule/cron after :seon.agent.schedule/after}]
  (let [p (parse {:seon.agent.schedule/cron cron})]
    (when (:seon.agent.schedule/ok? p)
      (let [start (js/Date. (.getTime ^js/Date after))]
        (.setSeconds start 0 0)
        (.setMinutes start (inc (.getMinutes start)))   ; first candidate minute
        (loop [t start n 0]
          (cond
            (> n scan-limit-minutes) nil
            (matches? p t)           t
            :else (recur (js/Date. (+ (.getTime t) 60000)) (inc n))))))))

;; ============================================================
;; fire-due-schedules! — the schedule half of the one ticker. For each agent
;; that OWNS schedules, if any schedule is `due?` at `now` and the agent is
;; idle, commit one run carrying the exact due function symbols. The scheduler
;; stops there: database interest wakes the cluster JVM, whose synchronous
;; driver owns evaluation. No scheduler callback evaluates agent code.
;;
;; concurrency-policy: the default :forbid ("don't open a 2nd run on a
;; :running agent") is satisfied because we fire ONLY when the agent is :idle
;; AND the run transaction CASes the agent's absent run pointer (the atomic
;; backstop for the agent-idle? check racing a message wake). :allow (a
;; concurrent run on a :running agent) is
;; DEFERRED — a second concurrent run is a worker-isolation concern; today
;; every policy is effectively idle-gated.
;;
;; Double-fire guard: a 5-field cron is `due?` for the WHOLE minute and the
;; ticker fires more than once per minute, so we skip an agent that already
;; has a `:schedule` run STARTED in the same minute as `now` — a pure DB
;; derivation over the run log (open OR already-closed), so a run that opened
;; and closed fast within the minute still blocks a re-fire. Per-AGENT (not
;; per-schedule): runs are serial in a single-agent pod, so at most one
;; schedule fires per agent per minute — correct here.
;; ============================================================

;; agent-id VALUE schema is base `:string` (not `:seon.agent/id`): LOAD-TIME
;; register! in a LEAF ns (seon.agent.schedule loads before seon.agent registers
;; `:seon.agent/id`). `:string` admits the literal "root" id too; the readable
;; identity policy is enforced at create.
(schema/register! ::fired-entry
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]])

(schema/register! ::fire-due-request
  [:map
   [:seon.agent/now :inst]])

(schema/register! ::fire-due-response
  [:map
   [:seon.agent.schedule/fired [:vector ::fired-entry]]
   [:seon/error {:optional true} :map]])

(schema/register! ::direct-error
  [:map [:seon.error/message :string]])

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- query-member [database query arguments]
  {::db.protocol/operation db.protocol/query-operation
   ::db/db database
   ::db.protocol/query-form query
   ::db.protocol/arguments arguments})

(defn- successful-member? [member]
  (true? (::db.protocol/success? member)))

(defn- member-result [member]
  (or (::db.protocol/result member)
      (:datahike.query/result member)))

(defn- failed-member [member]
  {:seon.error/message
   (str "Schedule acquisition failed: "
        (or (::db.protocol/error member)
            "the database returned no error message."))})

(def ^:private schedule-rows-query
  '[:find ?id
           (pull ?agent
                 [:seon.agent/terminated-at
                  {:seon.agent/run
                   [:seon.agent.run/status]}])
           ?cron ?function
    :where
    [?agent :seon.agent/id ?id]
    [?agent :seon.agent/schedules ?schedule]
    [?schedule :seon.agent.schedule/cron ?cron]
    [?schedule :seon.agent.schedule/fn ?function]])

(def ^:private scheduled-starts-query
  '[:find ?id ?started-at
    :where
    [?agent :seon.agent/id ?id]
    [?agent :seon.agent/schedules _]
    [?run :seon.agent.run/agent ?agent]
    [?run :seon.agent.schedule/fns _]
    [?run :seon.agent.run/started-at ?started-at]])

(defn ^:async ^:private acquire-schedule-facts [database]
  (let [acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/members
           [(query-member database schedule-rows-query [])
            (query-member database scheduled-starts-query [])]
           ::db/max-result-weight 1048576}))
        members (::db/results acquired)]
    (cond
      (error-value? acquired) acquired
      (not= 2 (count members))
      {:seon.error/message
       "Schedule acquisition returned the wrong member count."}
      (not (every? successful-member? members))
      (failed-member (first (remove successful-member? members)))
      :else
      (let [[schedules starts]
            (map member-result members)]
        {:seon.agent.schedule/schedules schedules
         :seon.agent.schedule/starts starts}))))

(defn- minute-of
  "Whole-minute bucket of a Date — its epoch-ms floored to the minute."
  [d]
  (Math/floor (/ (.getTime d) 60000)))

(defn- fired-this-minute?
  "Did one of the agent's schedule runs start in this wall-clock minute?"
  [started-at now]
  (let [nm (minute-of now)]
    (boolean (some #(= nm (minute-of %)) started-at))))

(defn- derived-state [agent]
  (let [run (:seon.agent/run agent)]
    (cond
      (:seon.agent/terminated-at agent) :terminated
      (= :open (:seon.agent.run/status run)) :running
      :else :idle)))

(defn- due-functions [rows started-at now]
  (let [agent (second (first rows))]
    (when (and (= :idle (derived-state agent))
               (not (fired-this-minute? started-at now)))
      (->> rows
           (map (fn [[_ _ cron function]] [cron function]))
           (filter (fn [[cron _]]
                     (due? {:seon.agent.schedule/cron cron
                            :seon.agent.schedule/now now})))
           (mapv second)))))

(defn ^:async ^:private fire-schedule! [database id due-fns now]
  (let [allocation
        (await
         (db.id/allocate!
          {::db/db database
           ::db.id/allocations
           [{::db.id/key :seon.agent.run/id
             ::db.id/identity-attr :seon.agent.run/id}]
           ::db.id/transaction-builder
           (fn [ids]
             (let [run-id (get ids :seon.agent.run/id)
                   run-ref [:seon.agent.run/id run-id]]
               {::db/tx-data
                [{:seon.agent.run/id run-id
                  :seon.agent.run/agent [:seon.agent/id id]
                  :seon.agent.run/started-at now
                  :seon.agent.run/status :open
                  :seon.agent.schedule/fns due-fns}
                 [:db.fn/cas [:seon.agent/id id]
                  :seon.agent/run nil run-ref]]}))}))]
    (if (error-value? allocation)
      allocation
      {:seon.agent/id id
       :seon.agent.run/id
       (get-in allocation [::db.id/ids :seon.agent.run/id])})))

(defn ^:async fire-due-schedules!
  "Open a `:schedule` run for every due idle agent.

   For every idle agent with a schedule due at `now`, respecting the
   double-fire guard, transact one run and return
   `{:seon.agent.schedule/fired [{:seon.agent/id _ :seon.agent.run/id _} …]}` for
   the runs opened. The cluster JVM wakes from database interest and owns all
   later execution; the scheduler never invokes agent code or drives the run."
  {:malli/schema [:=> [:cat ::fire-due-request]
                  [:or ::fire-due-response ::direct-error]]}
  [{now :seon.agent/now}]
  (if-not (admission/available?)
    {:seon.agent.schedule/fired []
     :seon/error (:seon/error (admission/unavailable))}
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [facts (await (acquire-schedule-facts database))]
          (if (error-value? facts)
            facts
            (let [schedule-rows (:seon.agent.schedule/schedules facts)
                  schedules-by-id (group-by first schedule-rows)
                  starts-by-id (->> (:seon.agent.schedule/starts facts)
                                    (group-by first)
                                    (reduce-kv
                                     (fn [result id rows]
                                       (assoc result id (mapv second rows)))
                                     {}))
                  ids (sort (keys schedules-by-id))]
              (loop [[id & more] ids
                     fired []]
                (if (nil? id)
                  {:seon.agent.schedule/fired fired}
                  (let [rows (get schedules-by-id id)
                        due-fns
                        (due-functions rows (get starts-by-id id []) now)]
                    (if-not (seq due-fns)
                      (recur more fired)
                      (let [outcome
                            (await
                             (fire-schedule!
                              database id due-fns now))]
                        (cond
                          (error-value? outcome) outcome
                          (:seon/error outcome)
                          (assoc outcome :seon.agent.schedule/fired fired)
                          outcome (recur more (conj fired outcome))
                          :else (recur more fired))))))))))))))
