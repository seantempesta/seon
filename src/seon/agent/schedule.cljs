(ns seon.agent.schedule
  "Cron-as-data — the SCHEDULE entity + the PURE cron logic. An agent owns a
   vector of schedule maps (`:seon.agent/schedules`); each carries a 5-field
   cron expression AND the qualified fn to invoke when due (code-as-data,
   resolved like canvas content / section AI). This generalizes cron from
   'wake me' to 'at this schedule, run this fn.'

   This namespace OWNS the `:seon.agent.schedule/*` schemas and the pure cron
   fns: `parse` (5-field string → field sets), `due?` (does an instant
   match?), `next-fire-at` (the next matching instant). Each takes an EXPLICIT
   `now`/`after` instant — no implicit clock, so they're testable.

   The mechanism that FIRES a due schedule (`fire-due-schedules!`, the
   schedule half of the one ticker — [[seon.agent.loop/install-ticker!]])
   lives below; the pure matching logic above stands either way. Matching
   uses the host process's LOCAL time — the schedule's `:timezone` field is
   stored but not yet honored in matching (flagged).

   Dependency direction: this ns requires `seon.agent.run` (open a `:schedule`
   run) but NOT `seon.agent.loop` — the loop's ticker calls US, so an edge
   back would cycle. The run DRIVER is INJECTED (`:seon.agent.schedule/drive!`
   = `seon.agent.loop/drive-run!`)."
  (:require
    [clojure.string :as str]
    [seon.agent.run :as run]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as db.protocol]
    [seon.derive :as derive]
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
;; fire-due-schedules! — the schedule half of the one ticker
;; ([[seon.agent.loop/install-ticker!]]). For each agent that OWNS schedules,
;; if any of its schedules is `due?` at `now` AND the agent is :idle, open a
;; `:schedule`-triggered run and drive it. "Firing" = the wake-on-schedule
;; semantics (open + drive a `:schedule` run).
;;
;; fn-exec: each schedule carries `:seon.agent.schedule/fn` (code-as-data,
;; "run THIS fn when due"). On a fire, the DUE schedules' fns are handed to the
;; injected `:seon.agent.schedule/exec-fn!` (seon.agent.loop/exec-scheduled-fns!,
;; injected to avoid a require cycle — same pattern as `drive!`): it eval-batches
;; `(the-fn)` as a SCHEDULE-FIRE turn on the just-opened run, in the agent's
;; scope, so the agent's first driven turn re-reads "the schedule fired and ran
;; THIS" instead of waking blind. That turn is stamped
;; `:seon.agent.turn/scheduled? true` — it renders in the transcript but does
;; NOT count toward the run's work bound (scheduled turns are excluded).
;; A broken scheduled fn records a failed eval (errors are values) and never
;; crashes the ticker. Absent exec-fn! (tests) ⇒ open-only.
;;
;; concurrency-policy: the default :forbid ("don't open a 2nd run on a
;; :running agent") is satisfied because we fire ONLY when the agent is :idle
;; AND [[seon.agent.run/open-run!]] is itself CAS-guarded (it refuses to open
;; when a run is already open — the atomic backstop for the agent-idle? check
;; racing a message wake). :allow (a concurrent run on a :running agent) is
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
   [:seon.agent/now             :inst]
   ;; The scheduled-fn executor (seon.agent.loop/exec-scheduled-fns!), injected
   ;; by the ticker to avoid a require cycle. Given {:seon.agent/id _
   ;; :seon.agent.schedule/fns [syms]}, it eval-batches the due fns as a
   ;; schedule-fire turn (`:seon.agent.turn/scheduled? true` — doesn't count
   ;; toward turn-limit) on the just-opened run. AWAITED (so the fire's effect
   ;; lands before the LLM drive). Absent (tests) ⇒ open-only, no fn-exec.
   [:seon.agent.schedule/exec-fn! {:optional true} 'fn?]
   ;; The run driver (seon.agent.loop/drive-run!), injected by the ticker to
   ;; avoid a require cycle. Absent (tests) ⇒ open-only, no drive.
   [:seon.agent.schedule/drive! {:optional true} 'fn?]])

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

(defn- pull-member [database selector entity-id]
  {::db.protocol/operation db.protocol/pull-operation
   ::db/db database
   ::db.protocol/selector selector
   ::db.protocol/entity-id entity-id})

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
                   [:seon.agent.run/status :seon.agent.run/paused-at]}])
           ?cron ?function
    :where
    [?agent :seon.agent/id ?id]
    [?agent :seon.agent/schedules ?schedule]
    [?schedule :seon.agent.schedule/cron ?cron]
    [?schedule :seon.agent.schedule/fn ?function]])

(def ^:private crashed-runs-query
  '[:find ?id ?closed-at
    :where
    [?agent :seon.agent/id ?id]
    [?agent :seon.agent/schedules _]
    [?run :seon.agent.run/agent ?agent]
    [?run :seon.agent.run/closed-reason :crashed]
    [?run :seon.agent.run/closed-at ?closed-at]])

(def ^:private scheduled-starts-query
  '[:find ?id ?started-at
    :where
    [?agent :seon.agent/id ?id]
    [?agent :seon.agent/schedules _]
    [?run :seon.agent.run/agent ?agent]
    [?run :seon.agent.run/trigger :schedule]
    [?run :seon.agent.run/started-at ?started-at]])

(def ^:private breaker-config-selector
  [:seon.config/id
   :seon.config.breaker/crash-count
   :seon.config.breaker/window-ms])

(defn ^:async ^:private acquire-schedule-facts [database]
  (let [acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/members
           [(query-member database schedule-rows-query [])
            (query-member database crashed-runs-query [])
            (query-member database scheduled-starts-query [])
            (pull-member database breaker-config-selector
                         [:seon.config/id config/cluster-config-id])]
           ::db/max-result-weight 1048576}))
        members (::db/results acquired)]
    (cond
      (error-value? acquired) acquired
      (not= 4 (count members))
      {:seon.error/message
       "Schedule acquisition returned the wrong member count."}
      (not (every? successful-member? members))
      (failed-member (first (remove successful-member? members)))
      :else
      (let [[schedules crashes starts stored-configuration]
            (map member-result members)]
        {:seon.agent.schedule/schedules schedules
         :seon.agent.schedule/crashes crashes
         :seon.agent.schedule/starts starts
         :seon.config/configuration
         (db/decode-edn-values (or stored-configuration {}))}))))

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
    (derive/state-from-primitives
     (cond-> {:seon.agent.run/open? (= :open (:seon.agent.run/status run))}
       (:seon.agent/terminated-at agent)
       (assoc :seon.agent/terminated-at (:seon.agent/terminated-at agent))
       (:seon.agent.run/paused-at run)
       (assoc :seon.agent.run/paused-at (:seon.agent.run/paused-at run))))))

(defn- breaker-tripped? [closed-at cutoff n]
  (>= (count (filter #(>= (.getTime ^js %) cutoff) closed-at)) n))

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

(defn ^:async ^:private fire-schedule! [id due-fns exec-fn! drive!]
  (let [snapshot
        (await
         (run/open-run! {:seon.agent/id id
                         :seon.agent.run/trigger :schedule}))]
    (if (error-value? snapshot)
      (let [current (await (run/current-run {:seon.agent/id id}))]
        (cond
          (error-value? current) current
          current nil
          :else snapshot))
      (if-not (admission/available?)
        {:seon/error (:seon/error (admission/unavailable))}
        (do
          (when exec-fn!
            (await (exec-fn! {:seon.agent/id id
                              :seon.agent.schedule/fns due-fns})))
          (when (fn? drive!)
            (drive! {:seon.agent/id id}))
          {:seon.agent/id id
           :seon.agent.run/id (:seon.agent.run/id snapshot)})))))

(defn ^:async fire-due-schedules!
  "Open, run, and drive a `:schedule` run for every due :idle agent.

   For every :idle agent with a schedule `due?` at
   `now` (respecting the double-fire guard), RUN the due schedules' fns on the
   opened run via the injected `:seon.agent.schedule/exec-fn!` (the ticker passes
   [[seon.agent.loop/exec-scheduled-fns!]] — eval-batched as a schedule-fire turn
   in the agent's scope, not counted toward turn-limit), then drive each run via the injected
   `:seon.agent.schedule/drive!` ([[seon.agent.loop/drive-run!]]). Both injections
   absent (tests) ⇒ open-only. Map-in / map-out: returns
   `{:seon.agent.schedule/fired [{:seon.agent/id _ :seon.agent.run/id _} …]}` for
   the runs it opened. A pure function of the DB otherwise (no stored firing
   state). `^:async`."
  {:malli/schema [:=> [:cat ::fire-due-request]
                  [:or ::fire-due-response ::direct-error]]}
  [{now :seon.agent/now exec-fn! :seon.agent.schedule/exec-fn!
    drive! :seon.agent.schedule/drive!}]
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
                  configuration (:seon.config/configuration facts)
                  schedules-by-id (group-by first schedule-rows)
                  crashes-by-id (->> (:seon.agent.schedule/crashes facts)
                                     (group-by first)
                                     (reduce-kv
                                      (fn [result id rows]
                                        (assoc result id (mapv second rows)))
                                      {}))
                  starts-by-id (->> (:seon.agent.schedule/starts facts)
                                    (group-by first)
                                    (reduce-kv
                                     (fn [result id rows]
                                       (assoc result id (mapv second rows)))
                                     {}))
                  ids (sort (keys schedules-by-id))
                  breaker-n
                  (config/schedule-breaker-crash-count configuration)
                  breaker-w
                  (config/schedule-breaker-window-ms configuration)
                  crash-cutoff (- (.getTime ^js now) breaker-w)]
              (loop [[id & more] ids
                     fired []]
                (if (nil? id)
                  {:seon.agent.schedule/fired fired}
                  (let [rows (get schedules-by-id id)
                        tripped?
                        (breaker-tripped? (get crashes-by-id id [])
                                          crash-cutoff breaker-n)
                        due-raw
                        (due-functions rows (get starts-by-id id []) now)
                        _
                        (when (and tripped? (seq due-raw))
                          (js/console.warn
                           (str "seon.agent.schedule: schedule wake refused for "
                                id " — circuit breaker tripped (≥" breaker-n
                                " crashed closes in the last " breaker-w
                                "ms). A human or agent message still wakes it; "
                                "the sliding window re-enables schedules.")))
                        due-fns (when-not tripped? due-raw)]
                    (if-not (seq due-fns)
                      (recur more fired)
                      (let [outcome
                            (await
                             (fire-schedule! id due-fns exec-fn! drive!))]
                        (cond
                          (error-value? outcome) outcome
                          (:seon/error outcome)
                          (assoc outcome :seon.agent.schedule/fired fired)
                          outcome (recur more (conj fired outcome))
                          :else (recur more fired))))))))))))))
