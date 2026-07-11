(ns seon.worker-validator
  "LEAN, STANDALONE parse/syntactic oracle for CO-LOCATION on the diffusion
   GPU worker.

   The eval-renoise loop validates a PARTIAL code-buffer every diffusion
   checkpoint. Done over the internet that is a ~100ms round-trip per
   checkpoint; co-located ON the worker it is a ~0.4ms local Node call.
   This ns is the bundle the worker spawns to get that local call.

   Scope (FIRST version): the PARSE/SYNTACTIC tier ONLY — `parse-forms`
   from `seon.repl.internal`. That is the immediate need: the failure
   SPANS (`:error-kind` + char `[start end]`) drive the renoise (the
   Python side maps char-spans → code-buffer token positions via
   `build_offset_map`/`span_to_positions`). The heavier EVAL / program-graph
   tier is deliberately NOT pulled in here — see [[validate]]'s `:tier`
   seam and the design note
   (`docs/prds/diffusion-dynamic-context/research/worker-validator-colocation-2026-06-28.md`).

   Dependency surface is intentionally tiny: `seon.repl.internal` →
   `clojure.string` + `rewrite-clj` (pure CLJC). No DB, no pod state, no
   malli instrumentation, no datahike. `validate` is a PURE fn of the
   input string.

   ## Wire contract (how the Python worker calls it)

   Two modes, ONE bundle:

   - ONE-SHOT (default) — the worker pipes the candidate code to STDIN
     and reads ONE line of JSON from STDOUT, then the process exits:

         echo -n '(def mean [[v] ...)' | node out/worker-validator/main.js

     →  {\"forms\":0,\"errors\":[{\"error-kind\":\"unmatched-delimiter\",
                                \"span\":[0,19],\"source\":\"(def mean [[v] ...)\"}]}

     Simple to test, but a fresh node spawn is ~100ms (bundle load) — NO
     win over an internet round-trip. Use for testing, not the hot loop.

   - SERVE (`--serve`) — the worker spawns the process ONCE and keeps it
     alive, then per checkpoint writes ONE line (the code, JSON-string
     ENCODED so embedded newlines survive as \\n) and reads ONE JSON
     result line. This is the CO-LOCATION hot path: the parse itself is
     ~0.1ms, so a persistent process delivers the ~0.4ms-class local call
     the renoise loop needs (vs ~100ms over the wire). REQUIRED for the
     per-checkpoint unlock.

         node out/worker-validator/main.js --serve
         > \"(def x [)\"           (one JSON-encoded string per line, in)
         < {\"forms\":0, ...}       (one JSON result per line, out)

   `forms` = count of evaluable top-level forms parsed. `errors` = one
   entry per `:read` failure, each carrying the classified `error-kind`,
   the absolute char `span`, and the byte-faithful `source` of the bad
   span."
  (:require
    [clojure.string :as str]
    [goog.object :as gobj]
    [seon.repl.internal :as internal]
    ["fs" :as fs]
    ["readline" :as readline]))

;; ============================================================
;; The oracle — a PURE fn of the input string.
;; ============================================================

(defn validate
  "Parse `code` and return a plain (JS-serializable) clj map.

       {::forms  <int>           ; count of evaluable top-level forms
        ::tier   :parse          ; which oracle tier ran (seam for :eval)
        ::errors [{::error-kind <kw>   ; :eof / :unmatched-delimiter /
                                       ;   :invalid-token / :odd-map / …
                  ::span [<start> <end>] ; ABSOLUTE char offsets in `code`
                  ::source <string>}]}   ; byte-faithful bad span

   Keywords/vectors stay clj here; [[->js]] flattens them to the JSON wire
   shape (bare `forms`/`tier`/`errors`/`error-kind`/`span`/`source` keys —
   the Python worker's contract, UNCHANGED) at the wire boundary. A clean
   parse → `{::forms n ::tier :parse ::errors []}`.

   EVAL-TIER SEAM: a later version adds `(defn validate-eval …)` (or a
   `:tier` arg dispatch) that, AFTER a clean parse, compiles/evals the
   forms and appends eval-level errors. Keep that tier in its OWN fn so
   the heavy program-graph / cljs.js stack never loads into THIS lean
   bundle — the parse tier must stay sub-millisecond and dependency-light.

   `strip-fences?` (default true) controls markdown fence stripping. Pass
   false for the CODE-BUFFER-TEXT basis: spans then index the EXACT input
   string, aligning with the diffusion worker's `offset_map`. Mirrors the
   bb `bin/oracle-server` `parse-raw` op (closed-loop renoise). See
   `closed-loop-span-alignment-2026-06-28.md`."
  [code & [strip-fences?]]
  (let [entries (internal/parse-forms code {:strip-fences? (not (false? strip-fences?))})
        forms   (filterv #(= :form (:seon.repl/kind %)) entries)
        errors  (->> entries
                     (filter #(= :read (:seon.repl/kind %)))
                     (mapv (fn [{:seon.repl/keys [span source] :as entry}]
                             {::error-kind (-> entry :seon/error :seon.error/kind)
                              ::span       span
                              ::source     source})))]
    {::forms  (count forms)
     ::tier   :parse
     ::errors errors}))

;; ============================================================
;; Wire boundary — clj map → JS-serializable plain object.
;; ============================================================

(defn ->js
  "Flatten a [[validate]] result to a `JSON.stringify`-able JS value.

   Keywords → their NAME strings (`:unmatched-delimiter` → \"unmatched-delimiter\"),
   the span vector → a JS array. Done explicitly (not `clj->js`) so the
   exact wire shape the Python worker parses is visible and stable."
  [{::keys [forms tier errors]}]
  #js {:forms  forms
       :tier   (name tier)
       :errors (clj->js
                 (mapv (fn [{::keys [error-kind span source]}]
                         {:error-kind (name error-kind)
                          :span       span
                          :source     source})
                       errors))})

(defn validate-json
  "Pure string→string: `code` in, one line of JSON out.

   The exact
   transform the subprocess performs. Exposed (and instrument-free) so a
   test / the REPL can exercise the whole wire path without a subprocess.
   `strip-fences?` (default true) is threaded to [[validate]] — pass false
   for the code_buffer_text (no-fence-strip) basis."
  [code & [strip-fences?]]
  (.stringify js/JSON (->js (validate code (not (false? strip-fences?))))))

;; ============================================================
;; Subprocess entry — read ALL of stdin, emit one JSON line.
;; ============================================================

(defn- serve!
  "Persistent line server (the `--serve` hot path). Reads ONE JSON value
   per stdin line, validates it, and writes ONE JSON result line per
   request. The worker spawns this ONCE and reuses it for every checkpoint
   — that reuse is what turns the ~100ms cold spawn into a ~0.1ms warm
   parse.

   Two line framings, mirroring the bb `bin/oracle-server` wire:

   - a bare JSON STRING (`\"(def x [)\"`) → parse WITH fence stripping (the
     historical framing);
   - a JSON OBJECT (`{\"code\":\"…\",\"op\":\"parse-raw\"}` /
     `{\"code\":\"…\",\"strip-fences\":false}`) → `op` `parse-raw` OR an
     explicit `strip-fences:false` selects the no-fence-strip code_buffer_text
     basis. `op`/`id` are NOT echoed here (the lean bundle's result shape
     stays `{forms,tier,errors}`); the bb server echoes them.

   A parse-forms throw is caught per line so one bad input never crashes
   the persistent process."
  []
  (let [rl (.createInterface readline #js {:input (.-stdin js/process)})]
    (.on rl "line"
         (fn [line]
           (let [out (try
                       (let [parsed (.parse js/JSON line)]
                         (if (string? parsed)
                           (validate-json parsed)
                           (let [code (or (gobj/get parsed "code") "")
                                 op   (gobj/get parsed "op")
                                 sf   (gobj/get parsed "strip-fences")
                                 strip? (if (some? sf) sf (not= op "parse-raw"))]
                             (validate-json code strip?))))
                       (catch :default e
                         (.stringify js/JSON
                           #js {:forms 0 :tier "parse" :errors #js []
                                :error #js {:kind "bad-request"
                                            :message (str (.-message e))}})))]
             (println out))))))

(defn -main
  "Bundle entry — `--serve` line server, or one-shot stdin validate.

   Two modes:

   - `--serve` → [[serve!]] (persistent line server, the co-location hot
     path);
   - otherwise → read the ENTIRE stdin (fd 0) as the code string and write
     one JSON line (one-shot). Synchronous fd-0 read keeps the one-shot
     cold start minimal — no stream-assembly for the piped-input case."
  [& args]
  (if (some #{"--serve"} args)
    (serve!)
    (let [code (try
                 (.toString (.readFileSync fs 0) "utf8")
                 (catch :default _ ""))]
      (println (validate-json (str/trim-newline code))))))
