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
    [seon.derive :as derive]
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
;; NOT count toward turn-limit ([[seon.derive/run-turn-count]] excludes it, #66).
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
  [:map [:seon.agent.schedule/fired [:vector ::fired-entry]]])

(defn- minute-of
  "Whole-minute bucket of a Date — its epoch-ms floored to the minute."
  [d]
  (Math/floor (/ (.getTime d) 60000)))

(defn- fired-this-minute?
  "Does agent `agent-eid` already have a `:schedule`-triggered run STARTED in
   the same wall-clock minute as `now`? The double-fire guard."
  [agent-eid now]
  (let [nm (minute-of now)]
    (boolean
      (some (fn [started] (= nm (minute-of started)))
            (db/query {:seon.db/query
                       '[:find [?started ...]
                         :in $ ?a
                         :where
                         [?r :seon.agent.run/agent ?a]
                         [?r :seon.agent.run/trigger :schedule]
                         [?r :seon.agent.run/started-at ?started]]
                       :seon.db/args [agent-eid]})))))

(defn- agent-schedule-pairs
  "Every `[cron fn]` pair agent `id` owns (cron + fn are both required on a
   schedule, so this misses none). The fire path filters these by `due?` to get
   the fns to run."
  [id]
  (db/query {:seon.db/query
             '[:find ?cron ?fn
               :in $ ?id
               :where
               [?a :seon.agent/id ?id]
               [?a :seon.agent/schedules ?s]
               [?s :seon.agent.schedule/cron ?cron]
               [?s :seon.agent.schedule/fn ?fn]]
             :seon.db/args [id]}))

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
  {:malli/schema [:=> [:cat ::fire-due-request] ::fire-due-response]}
  [{now :seon.agent/now exec-fn! :seon.agent.schedule/exec-fn!
    drive! :seon.agent.schedule/drive!}]
  (let [ids (db/query {:seon.db/query
                       '[:find [?id ...]
                         :where
                         [?a :seon.agent/id ?id]
                         [?a :seon.agent/schedules _]]})
        ;; Piece 2d dials read ONCE per pass (config is boot-stable); the pure
        ;; breaker check takes them as args.
        breaker-n  (config/schedule-breaker-crash-count)
        breaker-w  (config/schedule-breaker-window-ms)]
    (loop [[id & more] ids
           fired []]
      (if (nil? id)
        {:seon.agent.schedule/fired fired}
        (let [a-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
              ;; SCHEDULE-WAKE CIRCUIT BREAKER (Piece 2d): with no auto-rewake,
              ;; a schedule is the ONE autonomous repeat-wake source — so a
              ;; deterministic wedge + a periodic schedule is a crash loop.
              ;; Refuse the schedule wake for an agent with ≥N recent :crashed
              ;; closes (derived, no stored state — the window sliding past
              ;; re-enables it). Human/agent MESSAGES still wake it (that path
              ;; is untouched); only THIS schedule gate is gated.
              tripped? (derive/schedule-breaker-tripped? @db/*conn* id now
                                                         breaker-n breaker-w)
              ;; The DUE schedules' fns at `now` (idle + double-fire gated),
              ;; BEFORE the breaker. A non-empty vector both decides `fire?` AND
              ;; is what exec-fn! runs; an agent with two schedules due this
              ;; minute runs BOTH on the one per-minute run.
              due-raw (when (and a-eid
                                 (derive/agent-idle? @db/*conn* id)
                                 (not (fired-this-minute? a-eid now)))
                        (->> (agent-schedule-pairs id)
                             (filter (fn [[cron _]]
                                       (due? {:seon.agent.schedule/cron cron
                                              :seon.agent.schedule/now  now})))
                             (mapv second)))
              ;; Breaker refusal (Piece 2d): visible on an ACTUAL refusal (a
              ;; schedule WAS due) — not per idle tick.
              _        (when (and tripped? (seq due-raw))
                         (js/console.warn
                           (str "seon.agent.schedule: schedule wake REFUSED for "
                                id " — circuit breaker tripped (≥" breaker-n
                                " :crashed closes in the last " breaker-w
                                "ms). A human/agent message still wakes it; the "
                                "window sliding past re-enables schedules.")))
              due-fns  (when-not tripped? due-raw)]
          (if-not (seq due-fns)
            (recur more fired)
            (let [snap (await (run/open-run!
                                {:seon.agent/id          id
                                 :seon.agent.run/trigger :schedule}))]
              (if (false? (:seon.db/ok? snap))
                (do
                  ;; open-run! is CAS-guarded (atomic idle→running). A failure
                  ;; here is usually BENIGN: a message woke the agent between
                  ;; agent-idle? above and this open (the wake race), so the
                  ;; CAS lost. If a run now exists that's the cause — skip
                  ;; quietly. Otherwise it's a real failure worth surfacing.
                  (when-not (derive/current-run @db/*conn* id)
                    (js/console.error
                      (str "seon.agent.schedule/fire-due-schedules!: open-run! "
                           "FAILED for " id ": "
                           (:seon.error/message (:seon.db/error snap)))))
                  (recur more fired))
                (do
                  ;; RUN the due fns on the just-opened run FIRST (awaited, so
                  ;; the result is in the transcript before the LLM drive renders
                  ;; its prompt), THEN kick the loop (same as a wake).
                  (when exec-fn!
                    (await (exec-fn! {:seon.agent/id           id
                                      :seon.agent.schedule/fns due-fns})))
                  (when (fn? drive!) (drive! {:seon.agent/id id}))
                  (recur more
                         (conj fired {:seon.agent/id     id
                                      :seon.agent.run/id (:seon.agent.run/id snap)})))))))))))
