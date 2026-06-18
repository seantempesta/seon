(ns seon.debug
  "First-class, replayable per-turn debug capture (eval-robustness Part B).

   THE ASK, kept deliberately simple: a single on/off flag plus a
   configurable output directory. When ON, every turn writes — at the
   `run-turn!`/`ask-and-eval!` boundary, where the input prompt and the
   raw LLM reply are both in hand — the VERBATIM input prompt and the
   VERBATIM raw reply, keyed so a future debugger can pair them exactly:

     <dir>/<agent-id>/<turn-idx>-<turn-id>/
       prompt.txt     ; verbatim assembled input ctx (== today's prompt blob)
       response.txt   ; verbatim raw LLM reply — written EVEN WHEN BLANK
       response.edn   ; the response map (text/usage/provider-fields) —
                      ;   `pr-str`, round-trips straight into a test fixture

   `turn-idx` is the monotonic per-agent turn index (NOT `db/new-id!`,
   which is minute-resolution + non-monotonic): prefixing it makes the
   dirs sortable and order-reconstructible across a minute or a restart.

   THE FLAG. `enabled?` is true when EITHER:
     - the `SEON_DEBUG_CAPTURE` env var is set to a truthy value
       (unset / \"\" / \"0\" / \"off\" / \"false\" → OFF; anything else ON), OR
     - a process-level override is forced on via [[set-override!]] — the
       knob the gym driver flips so its prompt-blob evidence keeps
       working while live pods stay OFF by default (stopping the ~447 MB
       unbounded `logs/prompts` growth).
   The output base dir is `SEON_DEBUG_CAPTURE_DIR`, defaulting to
   `logs/turns` (a DATA path → CWD-relative, NEVER /tmp, NEVER
   `artifact-path`).

   WHY THIS IS A LEGITIMATE STATEFUL ARTIFACT, not a reactive violation:
   the raw bytes that left and entered the process at turn T are
   historical I/O — they CANNOT be re-derived (the ctx re-renders every
   turn, the model is non-deterministic, parsing destroys the raw
   reply). A flat-file blob keyed by agent/turn is the correct shape;
   the override atom is a process runtime knob, not derivable cluster
   state.

   Every write is best-effort and NEVER throws — losing a debug
   artifact must not abort a turn. The writer REFUSES TO OVERWRITE: a
   collision (minute reuse / restart) suffixes the filename rather than
   silently clobbering an episode."
  (:require [clojure.string :as str]
            [seon.log :as log]
            [seon.platform :as platform]
            [seon.schema :as schema]))

;; The per-turn pointer the turn entity carries (projection only; the
;; blob under `<dir>/...` holds the content). Mirrors the existing
;; `:seon.agent.turn/prompt-file` three-tier shape.
(schema/register! :seon.agent.turn/debug-dir :string)

(def ^:private off-values
  "Env strings that read as OFF (case-insensitive). Anything else (when
   the var is set non-blank) reads as ON."
  #{"" "0" "off" "false" "no"})

(defonce ^:private !override
  ;; Process-level force-on knob. nil = defer to env; true/false =
  ;; override the env entirely. `defonce` — survives hot reload.
  (atom nil))

(schema/register! :seon.debug/override [:enum :on :off :env])

(defn set-override!
  "Force capture ON, OFF, or back to env-driven (`:env`) for THIS
   process. Returns the value set. Used by the gym driver to enable
   capture around a scenario run (so its prompt-blob predicates keep
   reading what the agent saw) and restore it after."
  {:malli/schema [:=> [:cat :seon.debug/override] :seon.debug/override]}
  [v]
  (reset! !override (case v :on true :off false :env nil))
  v)

(defn enabled?
  "True when per-turn debug capture should run. The process override
   wins when set; otherwise the `SEON_DEBUG_CAPTURE` env var decides
   (truthy → on; unset/blank/`0`/`off`/`false`/`no` → off)."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [o @!override]
    (if (some? o)
      o
      (boolean
        (when-let [v (platform/env-val "SEON_DEBUG_CAPTURE")]
          (not (contains? off-values (str/lower-case (.trim v)))))))))

(defn capture-dir
  "The output base dir: `SEON_DEBUG_CAPTURE_DIR` when set, else the
   default `logs/turns`. Always CWD-relative (a DATA path)."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (platform/env-val "SEON_DEBUG_CAPTURE_DIR") "logs/turns"))

(defn turn-dir
  "The per-turn directory path (not created): `<base>/<agent-id>/
   <turn-idx>-<turn-id>`. Pure — string math only."
  {:malli/schema
   [:=> [:cat [:catn
               [:seon.debug/agent-id :string]
               [:seon.debug/turn-idx :int]
               [:seon.debug/turn-id :string]]]
    :string]}
  [agent-id turn-idx turn-id]
  (str (capture-dir) "/" agent-id "/" turn-idx "-" turn-id))

(defn- collision-free-path
  "Given a target file `path` (already inside an existing dir), return a
   path that does NOT exist: `path` itself if free, else `name-2.ext`,
   `name-3.ext`, … so a second capture for the same turn key never
   clobbers the first. Pure given `fs` (the node:fs module)."
  [fs path]
  (if-not (.existsSync fs path)
    path
    (let [dot   (.lastIndexOf path ".")
          [stem ext] (if (pos? dot)
                       [(subs path 0 dot) (subs path dot)]
                       [path ""])]
      (loop [n 2]
        (let [candidate (str stem "-" n ext)]
          (if (.existsSync fs candidate)
            (recur (inc n))
            candidate))))))

(defn write-turn-artifact!
  "Write ONE per-turn debug artifact (best-effort, never throws). Creates
   `<base>/<agent-id>/<turn-idx>-<turn-id>/` and writes `filename` there
   with `content`. REFUSES TO OVERWRITE — an existing file is preserved
   and the new one is suffixed (`response-2.txt`). Returns the path
   actually written, or nil on failure (logged). A no-op (returns nil)
   when capture is disabled."
  {:malli/schema
   [:=> [:cat [:catn
               [:seon.debug/agent-id :string]
               [:seon.debug/turn-idx :int]
               [:seon.debug/turn-id :string]
               [:seon.debug/filename :string]
               [:seon.debug/content :string]]]
    [:maybe :string]]}
  [agent-id turn-idx turn-id filename content]
  (when (enabled?)
    (try
      (let [fs   (js/require "node:fs")
            dir  (turn-dir agent-id turn-idx turn-id)
            _    (.mkdirSync fs dir #js {:recursive true})
            path (collision-free-path fs (str dir "/" filename))]
        (.writeFileSync fs path (str content) "utf8")
        path)
      (catch :default e
        (log/warn-console! "seon.debug"
                           (str "could not write debug artifact "
                                agent-id "/" turn-idx "-" turn-id "/" filename)
                           (or (.-message e) e))
        nil))))

(defn capture-prompt!
  "Write the turn's verbatim assembled input prompt to `prompt.txt`
   under the per-turn dir. Best-effort. Returns the path written (so the
   turn datom can point `:seon.agent.turn/prompt-file` at it), or nil
   when disabled / on failure. Replaces the old always-on
   `persist-prompt!` — same blob, now gated."
  {:malli/schema
   [:=> [:cat [:catn
               [:seon.debug/agent-id :string]
               [:seon.debug/turn-idx :int]
               [:seon.debug/turn-id :string]
               [:seon.debug/content :string]]]
    [:maybe :string]]}
  [agent-id turn-idx turn-id text]
  (write-turn-artifact! agent-id turn-idx turn-id "prompt.txt" text))

(defn capture-response!
  "Write the turn's VERBATIM raw LLM reply. Writes `response.txt` (the
   visible text, EVEN WHEN BLANK — a blank reply must be visible in the
   capture, not silently absent) and `response.edn` (the response map
   `pr-str`'d — text/usage/provider-fields — round-trips into a test
   fixture). Best-effort; returns the per-turn dir path (for the turn's
   `:seon.agent.turn/debug-dir` pointer) or nil when disabled / on
   failure.

   `reply-text` is the verbatim reply string; `resp` is the response map
   (cheaply in hand at `ask-and-eval!`)."
  {:malli/schema
   [:=> [:cat [:catn
               [:seon.debug/agent-id :string]
               [:seon.debug/turn-idx :int]
               [:seon.debug/turn-id :string]
               [:seon.debug/reply-text :string]
               [:seon.debug/resp :map]]]
    [:maybe :string]]}
  [agent-id turn-idx turn-id reply-text resp]
  (when (enabled?)
    (write-turn-artifact! agent-id turn-idx turn-id "response.txt" reply-text)
    (write-turn-artifact! agent-id turn-idx turn-id "response.edn" (pr-str resp))
    (turn-dir agent-id turn-idx turn-id)))

(defn prune!
  "Maintenance: delete debug dirs for `agent-id` keeping only the last
   `keep-n` turns (by turn-idx). Best-effort, never throws; a no-op when
   the agent's dir doesn't exist. Returns the count of dirs removed."
  {:malli/schema
   [:=> [:cat [:catn
               [:seon.debug/agent-id :string]
               [:seon.debug/keep-n :int]]]
    :int]}
  [agent-id keep-n]
  (try
    (let [fs       (js/require "node:fs")
          base     (str (capture-dir) "/" agent-id)]
      (if-not (.existsSync fs base)
        0
        (let [dirs (->> (.readdirSync fs base)
                        (js->clj)
                        ;; sort by the leading <turn-idx> integer, ascending
                        (sort-by (fn [d]
                                   (let [n (js/parseInt (first (str/split d #"-")) 10)]
                                     (if (js/isNaN n) -1 n)))))
              drop-n (max 0 (- (count dirs) keep-n))
              victims (take drop-n dirs)]
          (doseq [d victims]
            (.rmSync fs (str base "/" d) #js {:recursive true :force true}))
          (count victims))))
    (catch :default e
      (log/warn-console! "seon.debug" (str "prune! failed for " agent-id)
                         (or (.-message e) e))
      0)))
