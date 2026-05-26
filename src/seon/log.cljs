(ns seon.log
  "Structured event logging for the pod — error / warn / info / debug
   entries flow through TWO sinks:

     1. stdout/stderr via `console!` (captured by the supervisor into
        `logs/pod.log` as raw text — human-readable, grep-friendly).
     2. An NDJSON-EDN file (default `logs/pod-events.log`, configurable
        — see [[*log-file*]]). One `pr-str`'d entry map per line.
        Size-rotated (5 MB cap, last 3 kept). Agents read this via
        `seon.fs/read-file` as a normal file.

   ## tail reads the file

   [[tail]] is NOT magic. It opens the active log file, splits on
   newlines, parses each line as EDN, filters, and returns the last N
   newest-first. There is NO in-memory ring buffer — the file IS the
   buffer. The same bytes the agent reads through `seon.fs` are the
   bytes `tail` sees. One source of truth.

   For history older than the current file, agents enumerate the
   rotated files (`pod-events.log.1`, `.2`, `.3`) themselves via
   `seon.fs`. `tail` only reads the active file.

   ## Why no DB rows

   The DB used to carry `:seon.log/entry` entities (one tx per error
   for the renderer to query). That filled the DB with operational
   noise. Logs are NOT derivable from the eval/turn record; they are
   ephemeral process state. They belong on disk, not in the persistent
   EAV store.

   ## Path configuration — single source of truth

   The log file path is held in the dynvar [[*log-file*]] (default
   `\"logs/pod-events.log\"`). In the sidecar/WASI pod this will move
   to `\"/scratch/pod-events.log\"` (the per-session WASI preopen
   documented in `pod-host/sidecar-poc/README.md`). Override via
   [[configure!]] or by binding [[*log-file*]] in a test.

   ## Entry shape

     :seon.log/at      :inst       ; auto-stamped (js/Date)
     :seon.log/level   :enum       ; auto-stamped — :error/:warn/:info/:debug
     :seon.log/source  :keyword    ; caller, namespaced
     :seon.log/agent   :string     ; optional — agent id when applicable
     :seon.log/message :string     ; required
     :seon.log/stack   :string     ; optional — JS stack
     :seon.log/data    :any        ; optional — structured payload

   ## API

     (log/error! {:seon.log/source ::foo :seon.log/message \"oops\"})
     (log/info!  {:seon.log/source ::foo :seon.log/message \"hi\"})
     (log/tail   {:seon.log/n 50 :seon.log/level :error :seon.log/agent \"a1\"})

   Never throws — broken logging must never take down the caller."
  (:require
    ["node:fs"   :as fs]
    ["node:path" :as np]
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Console output — structured, grep-friendly stdout/stderr lines.
;; Format:
;;
;;   2026-05-19T18:23:45.123Z  INFO  [seon.web.serve] listening on …
;;
;; ISO timestamp + 5-char level + bracketed source + message. Errors
;; go to stderr (`console.error`); everything else to stdout. The
;; supervisor (`bin/seon`) tees both into `logs/pod.log`.
;; ============================================================

(defn- ->safe-str
  "Render a non-string log payload as a flat one-line edn string. JS's
   `console.log` prints CLJS persistent collections as their internal
   Object guts; pr-str gives a readable, grepable, machine-friendly
   string instead. Errors and primitive types pass through unchanged."
  [x]
  (cond
    (nil? x)     ""
    (string? x)  x
    (number? x)  (str x)
    (boolean? x) (str x)
    (instance? js/Error x)
    (str x (when-let [s (.-stack x)] (str "\n" s)))
    :else        (try (pr-str x) (catch :default _ (str x)))))

(defn- console!
  "Emit one structured line to stdout/stderr. Internal — public helpers
   below.

   Format: `2026-05-19T18:23:45.123Z  INFO  [source] message [edn ...]`"
  [level source msg & extra]
  (let [ts   (.toISOString (js/Date.))
        lvl  (str/upper-case (name level))
        pad  (str lvl (apply str (repeat (- 5 (count lvl)) " ")))
        body (if (seq extra)
               (str msg " " (str/join " " (map ->safe-str extra)))
               msg)
        line (str ts "  " pad " [" source "] " body)
        sink (case level
               :error js/console.error
               :warn  js/console.warn
               js/console.log)]
    (sink line)))

(defn error-console!
  "stderr log line — boot errors, request failures, anything you want
   in `logs/pod.log` but NOT in the structured file (this skips
   [[error!]] entirely)."
  [source msg & extra]
  (apply console! :error source msg extra))

(defn warn-console!  [source msg & extra] (apply console! :warn  source msg extra))
(defn info-console!  [source msg & extra] (apply console! :info  source msg extra))
(defn debug-console! [source msg & extra] (apply console! :debug source msg extra))

;; ============================================================
;; Schemas
;; ============================================================

;; :inst validates js/Date in CLJS (inst? returns true). The bridge
;; maps :inst → :db.type/instant on the JVM side.
(schema/register! :seon.log/at      :inst)
(schema/register! :seon.log/level   [:enum :error :warn :info :debug])
(schema/register! :seon.log/source  :keyword)
(schema/register! :seon.log/agent   :string)
(schema/register! :seon.log/message :string)
(schema/register! :seon.log/stack   :string)
;; :seon.log/data is genuinely polymorphic (arbitrary log payloads).
;; Kept as :any for in-memory validation only — pr-str'd when serialized.
(schema/register! :seon.log/data    :any)

;; Entry map shape — registered for use at the validation boundary
;; inside `log!`. NO LONGER a DB entity type.
(schema/register! :seon.log/entry
  [:map
   [:seon.log/at      :inst]
   [:seon.log/level   [:enum :error :warn :info :debug]]
   [:seon.log/source  :keyword]
   [:seon.log/agent   {:optional true} :string]
   [:seon.log/message :string]
   [:seon.log/stack   {:optional true} :string]
   [:seon.log/data    {:optional true} :any]])

(schema/register! :seon.log/n     :int)
(schema/register! :seon.log/tail-request
  [:map
   [:seon.log/n      {:optional true} :int]
   [:seon.log/level  {:optional true} [:enum :error :warn :info :debug]]
   [:seon.log/agent  {:optional true} :string]
   [:seon.log/source {:optional true} :keyword]])

(schema/register! :seon.log/tail-response
  [:vector :seon.log/entry])

;; ============================================================
;; Configuration — single source of truth for the log file path.
;;
;; `*log-file*` is the canonical path. Both writers (`error!`, `info!`,
;; ...) and the reader ([[tail]]) resolve through it. To migrate to
;; the WASI sidecar `/scratch/` preopen, change ONE value:
;;
;;   (binding [seon.log/*log-file* \"/scratch/pod-events.log\"] ...)
;;
;; or
;;
;;   (seon.log/configure! {:seon.log/file \"/scratch/pod-events.log\"})
;;
;; `:seon.log/file-cap` and `:seon.log/keep` control file-size
;; rotation. No ring buffer config — there is no ring buffer.
;; ============================================================

(def ^:dynamic *log-file*
  "Absolute or cwd-relative path to the active log file. The default
   `\"logs/pod-events.log\"` is V0/Node-pod-friendly; the WASI sidecar
   will rebind this to `\"/scratch/pod-events.log\"`."
  "logs/pod-events.log")

(defonce !config
  (atom {:seon.log/file-cap (* 5 1024 1024)
         :seon.log/keep     3}))

(defn configure!
  "Merge `updates` into the active log config. Recognized keys:
     :seon.log/file     — path to the active log file (also sets the
                          root binding of [[*log-file*]] for callers
                          that don't bind it explicitly).
     :seon.log/file-cap — file size cap in bytes; rotation triggers
                          when the file exceeds this on append.
     :seon.log/keep     — number of rotated files retained.

   Returns the new config (without `:seon.log/file` — that lives in
   the dynvar)."
  {:malli/schema [:=> [:cat :map] :map]}
  [updates]
  (when-let [file (:seon.log/file updates)]
    (set! *log-file* file))
  (let [next (merge @!config
                    (select-keys updates
                                 [:seon.log/file-cap
                                  :seon.log/keep]))]
    (reset! !config next)
    next))

;; ============================================================
;; File I/O — best-effort, never throws.
;; ============================================================

(defn- event-file-path []
  *log-file*)

(defn- ensure-dir! [path]
  (try
    (let [dir (.dirname np path)]
      (when-not (.existsSync fs dir)
        (.mkdirSync fs dir #js {:recursive true})))
    (catch :default _ nil)))

(defn- file-size [path]
  (try (.-size (.statSync fs path)) (catch :default _ 0)))

(defn- rotate-if-needed!
  "If the event file exceeds `:seon.log/file-cap`, shift
     pod-events.log     → pod-events.log.1
     pod-events.log.1   → pod-events.log.2
     ...
   up to `:seon.log/keep` rotations. The Nth+1 rotation is dropped."
  [path]
  (let [{:seon.log/keys [file-cap keep]} @!config]
    (when (> (file-size path) (or file-cap (* 5 1024 1024)))
      (try
        ;; Drop the oldest beyond `keep`.
        (let [oldest (str path "." (or keep 3))]
          (when (.existsSync fs oldest)
            (.unlinkSync fs oldest)))
        ;; Shift N → N+1 from oldest down to 1.
        (doseq [i (range (or keep 3) 0 -1)]
          (let [from (if (= i 1) path (str path "." (dec i)))
                to   (str path "." i)]
            (when (.existsSync fs from)
              (.renameSync fs from to))))
        (catch :default _ nil)))))

(defn- append-file!
  "Write one NDJSON-EDN line to the event file. Best-effort — failure
   degrades to stderr, never throws."
  [entry]
  (when (= :node (platform/host))
    (try
      (let [path (event-file-path)]
        (ensure-dir! path)
        (rotate-if-needed! path)
        (.appendFileSync fs path (str (pr-str entry) "\n") "utf-8"))
      (catch :default e
        (js/console.error "[seon.log] file write failed —"
                          (or (.-message e) (str e)))))))

;; ============================================================
;; Internal — emit one entry to the two sinks (console + file).
;; ============================================================

(defn- log!
  "Stamp + emit one entry. Returns a resolved js/Promise of the
   entry map (so callers can `await` if desired)."
  [level data]
  (let [entry (-> data
                  (assoc :seon.log/at    (js/Date.)
                         :seon.log/level level))
        ;; Echo to stdout/stderr for the human/supervisor log.
        source-str (or (some-> (:seon.log/source entry) (str))
                       "seon.log")
        msg        (:seon.log/message entry "")
        agent      (:seon.log/agent entry)
        extra      (cond-> []
                     agent (conj {:agent agent})
                     (:seon.log/data entry)
                     (conj (:seon.log/data entry))
                     (:seon.log/stack entry)
                     (conj (:seon.log/stack entry)))]
    (try
      (apply console! level source-str msg extra)
      (append-file! entry)
      (catch :default e
        (js/console.error "[seon.log] log! threw —"
                          (or (.-message e) (str e)))))
    (js/Promise.resolve entry)))

;; ============================================================
;; Public API — one entry per level. Map-in / Promise-out.
;; ============================================================

(defn error!
  "Emit a `:seon.log/level :error` entry. Required: `:seon.log/source`
   (keyword) and `:seon.log/message` (string). Optional:
   `:seon.log/agent`, `:seon.log/stack`, `:seon.log/data`.

   Side effects (best-effort, never throws):
     1. stderr line via console.error
     2. append NDJSON-EDN line to the active log file ([[*log-file*]])."
  {:malli/schema [:=> [:cat [:map
                             [:seon.log/source :keyword]
                             [:seon.log/message :string]
                             [:seon.log/agent {:optional true} :string]
                             [:seon.log/stack {:optional true} :string]
                             [:seon.log/data {:optional true} :any]]]
                  :any]}
  [data] (log! :error data))

(defn warn!  [data] (log! :warn data))
(defn info!  [data] (log! :info data))
(defn debug! [data] (log! :debug data))

;; ============================================================
;; tail — read the file. No ring, no magic. The same bytes the agent
;; sees through `seon.fs/read-file` are the bytes here.
;;
;; For V0 file sizes (≤5MB rotated cap) reading the whole file is
;; fine. If this ever bites, switch to a backward streaming reader.
;; ============================================================

(defn- read-active-file
  "Read the active log file as a string. Empty string if missing or
   unreadable. Never throws."
  []
  (when (= :node (platform/host))
    (let [path (event-file-path)]
      (try
        (if (.existsSync fs path)
          (.readFileSync fs path "utf-8")
          "")
        (catch :default _ "")))))

(defn- parse-line
  "Read one NDJSON-EDN line into an entry map. Returns nil if the line
   doesn't parse (corrupt tail of an interrupted write, blank line)."
  [line]
  (when-not (str/blank? line)
    (try (edn/read-string line) (catch :default _ nil))))

(defn tail
  "Return the most-recent log entries from the active log file
   ([[*log-file*]]), newest first. Reads the file each call — there
   is no in-memory buffer.

   Filter opts (all optional):

     :seon.log/n      — max entries to return (default 50)
     :seon.log/level  — only entries with this level
     :seon.log/agent  — only entries with this agent id
     :seon.log/source — only entries from this source keyword

   Only reads the ACTIVE file — entries that lived in a now-rotated
   `pod-events.log.1` (etc.) are not visible here. For deeper history,
   enumerate the rotated files via `seon.fs`."
  {:malli/schema [:=> [:cat :seon.log/tail-request] :seon.log/tail-response]}
  [{:seon.log/keys [n level agent source] :or {n 50}}]
  (let [pred (fn [e]
               (and (or (nil? level)  (= level  (:seon.log/level e)))
                    (or (nil? agent)  (= agent  (:seon.log/agent e)))
                    (or (nil? source) (= source (:seon.log/source e)))))
        text (read-active-file)]
    (->> (str/split-lines (or text ""))
         (map parse-line)
         (filter some?)
         reverse                  ;; newest-first
         (filter pred)
         (take n)
         vec)))
