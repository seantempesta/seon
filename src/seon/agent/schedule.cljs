(ns seon.agent.schedule
  "Cron-as-data — the SCHEDULE entity + the PURE cron logic. An agent owns a
   vector of schedule maps (`:seon.agent/schedules`); each carries a 5-field
   cron expression AND the qualified fn to invoke when due (code-as-data,
   resolved like tile-content / section-ai). This generalizes cron from
   'wake me' to 'at this schedule, run this fn.'

   This namespace OWNS the `:seon.agent.schedule/*` schemas and the pure cron
   fns: `parse` (5-field string → field sets), `due?` (does an instant
   match?), `next-fire-at` (the next matching instant). Each takes an EXPLICIT
   `now`/`after` instant — no implicit clock, so they're testable.

   The mechanism that FIRES a due schedule (`fire-due-schedules!`, the ticker)
   lands in a later pass; the data shape + the matching logic here stand
   either way. Matching uses the host process's LOCAL time — the schedule's
   `:timezone` field is stored but not yet honored in matching (flagged)."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; ============================================================
;; Schema — the schedule entity (per agent-runtime-spec §seon.agent.schedule).
;; ============================================================

(schema/register! :seon.agent.schedule/id       [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.schedule/cron     :string)   ; 5-field cron expression
(schema/register! :seon.agent.schedule/fn       :symbol)   ; qualified fn invoked when due
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
  "The host process's IANA timezone string (the default for a schedule with
   no explicit `:timezone`)."
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
  "Parse a 5-field cron string into `{::ok? true ::minute #{…} ::hour #{…}
   ::day-of-month #{…} ::month #{…} ::day-of-week #{…}}`. An invalid string
   (wrong field count / bad field) returns `{::ok? false ::error \"…\"}` —
   never throws."
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
  "Does `now` (truncated to the minute by the field sets) match `cron`? An
   unparseable cron is never due (false)."
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
  "The next instant STRICTLY AFTER `after` that matches `cron` (scanning
   minute-by-minute, seconds zeroed), or nil if none within ~366 days / the
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
