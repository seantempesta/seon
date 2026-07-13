(ns seon.render.sci
  "SCI-bounded invocation for agent-authored canvas fns.

   ## Why this exists

   The pod is a SINGLE Node thread. A canvas lets an agent point
   `:seon.render.canvas/content` at a fn symbol that is invoked
   SYNCHRONOUSLY in the render path (`seon.render/html-render` →
   `(f input-map)`). A non-terminating agent canvas fn (a sync
   `(loop [] (recur))` / `(while true)` / runaway interpreted recursion)
   blocks the one thread and freezes the WHOLE pod — heartbeat, HTTP, SSE,
   every other agent — with no recovery but a manual restart. `try/catch`
   cannot catch a sync loop, and the eval timeout
   (`seon.eval` `race-timeout`) is `Promise.race` vs `setTimeout`: a blocked
   event loop never fires the timer.

   ## The fix

   Run the agent fn under SCI, an INTERPRETER that calls a caller-supplied
   `:interrupt-fn` at the top of every interpreted `fn`/`loop` entry. A
   wall-clock deadline there throws an UN-CATCHABLE, UN-FORGEABLE interrupt
   that aborts the loop IN-PROCESS — no worker, db passed by reference.
   Proven on Node/CLJS (the spike at
   `docs/prds/agent-runtime/spikes/sci-interrupt/`): a true sync
   `(loop [] (recur))` aborts at ~the deadline, the event loop is never
   yielded, the interrupt survives a hostile `(try … (catch :default _ …))`,
   and per-render overhead is ~0.2ms warm.

   ## How agent fns are made interrupt-protected (and still resolve)

   `:interrupt-fn` fires ONLY on INTERPRETED bodies — a COMPILED host fn
   (the agent fn already lives on `globalThis`) gets zero protection. So we
   re-evaluate the agent fn's stored SOURCE (`:seon.fn/source`) INTO the SCI
   ctx so SCI interprets its body. The fn's body references its namespace's
   `:as` aliases (`db/query`, `render/...`) and any own-ns helpers — exactly
   the lexical environment the COMPILED fn had. We rebuild that environment:

   - read the agent ns's STORED `:seon.ns/require-edges` datoms for its
     `:require` `:as` aliases + `:refer`s (analyzer facts teed at eval/setup
     time — M4; pre-structural rows fall back to parsing `:seon.ns/source`);
   - expose every required `seon.*`/agent namespace as SCI host vars by
     ENUMERATING its members from the `:seon.fn` index (code-as-data — the
     core IS indexed) and resolving each via `seon.eval/lookup-value`;
   - establish the agent's aliases via SCI `:ns-aliases`, expose own-ns
     helpers + `:refer`s under the agent ns, and `(in-ns agent-ns)` before
     the eval so simple-name refs resolve.

   `clojure.*`/`cljs.*` are SCI built-ins (aliased, not exposed). `js`
   interop is exposed via `:classes`. The exposed core/agent fns run COMPILED
   (fast, trusted) — only the canvas fn's own body is interpreted, so only its
   loops are bounded.

   ## What this does NOT cover (residual class — needs Layer 2, deferred)

   A canvas that calls a native host loop (compiled CLJS / JS `while(true)`,
   incl. a loop hidden in an exposed COMPILED helper) or a NATIVE regex (CLJS
   ReDoS) still blocks: `:interrupt-fn` never fires inside host code (the
   interrupt-aware overrides are JVM-only). Bounding that needs a killable
   worker (PRD Layer 2). Layer 1 bounds the reproduced freeze: an interpreted
   loop/recursion in the canvas fn's own body.

   Toggle: env `SEON_CANVAS_SCI=0` disables bounding (agent canvas fns fall
   back to the direct compiled call). Default on."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [sci.core :as sci]
    [sci.interrupt :as interrupt]
    [seon.agent.home :as home]
    [seon.analyzer-info :as analyzer-info]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error :as err]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    [seon.log :as log]
    [seon.schema :as schema]))

;; ============================================================
;; Config + flag
;; ============================================================

(def default-budget-ms
  "Wall-clock budget for one agent canvas render. The PRD test plan wants
   `budget ≥ max(250ms, 4 × baseline)`; measured live, a real agent canvas (a few
   DB queries → hiccup, under the full SCI reconstruction path) renders in
   ~52ms, so 4 × baseline ≈ 208ms < 250ms — the 250ms floor holds with headroom,
   and a blocked loop is bounded to roughly this + interpreter slack (~316ms
   observed for a cold fork). Revisit if real canvases ever exceed ~60ms p99."
  250)

(defn bounding-enabled?
  "Layer-1 SCI bounding is ON unless `SEON_CANVAS_SCI=0`.

   Independently shippable + reversible — PRD migration step 2."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (not= "0" (config/env-string "SEON_CANVAS_SCI")))

;; `agent-authored-sym?` MOVED to `seon.error` (2026-07-04,
;; error-blame-strict-gate phase 1): it now also decides
;; `:seon.error/fault`, and `seon.instrument` needs it without a require
;; cycle through this ns. Callers read `err/agent-authored-sym?`.

(defn- exposable-ns?
  "A namespace whose members we expose as SCI host vars — `seon.*`/agent
   namespaces. `clojure.*`/`cljs.*`/`goog.*` are SCI built-ins (we alias them
   via :ns-aliases but never enumerate)."
  [ns-sym]
  (not (re-find #"^(clojure|cljs|goog)(\.|$)" (name ns-sym))))

;; ============================================================
;; Per-invocation mutable holders.
;;
;; The pod is single-threaded and canvas renders are synchronous (html-render
;; returns before the next render begins), so a process-wide deadline +
;; input volatile read by each fresh ctx's interrupt-fn / host accessor is
;; safe — there is no overlapping invocation to race.
;; ============================================================

(def ^:private !deadline (volatile! 0))
(def ^:private !input    (volatile! nil))

(defn- current-input
  "Host accessor exposed to the SCI ctx as `seon.render.sci/current-input` —
   lets the eval'd canvas fn receive the live (non-serializable) input map by
   reference without inlining it into the eval string."
  []
  @!input)

(defn- deadline-interrupt-fn
  "Zero-arg `:interrupt-fn`: throws the un-catchable SCI interrupt once the
   wall clock passes the per-render deadline. Polls `js/Date.now` (proven to
   read correctly inside a blocked SCI loop on Node — the spike)."
  []
  (when (> (js/Date.now) @!deadline)
    (interrupt/interrupt!)))

(def ^:private base-classes {'js js/globalThis :allow :all})

;; One-time process-level warmup: JIT the SCI interpreter's loop/recur + fn
;; paths once at module load, so the FIRST real hang aborts at ~budget rather
;; than the cold ~100ms-slower path (spike TEST G). V8 JIT is process-level
;; (not per-ctx), so this warms every later fresh ctx.
(defonce ^:private _warmup
  (let [c (sci/init {:interrupt-fn deadline-interrupt-fn :classes base-classes})]
    (vreset! !deadline (+ (js/Date.now) 60000))
    (try (sci/eval-string* c "(loop [i 0] (if (< i 64) (recur (inc i)) i)) ((fn [x] (inc x)) 1)")
         (catch :default e
           ;; a literal warmup loop failing means SCI itself is broken —
           ;; OUR machinery (:core); record! buffers pre-conn at load.
           (err/record! {:seon.error/raw e :seon.error/fault :core})))
    :warmed))

;; ============================================================
;; Interrupt detection — walk the cause chain for the un-forgeable marker.
;; The interpreter may re-wrap the propagated interrupt into a :sci/error
;; whose top-level ex-data lacks the marker but whose cause carries it.
;; ============================================================

(defn- interrupt-ex?
  "True if `e` (or any exception in its cause chain) carries the private
   `:sci.impl/interrupt` marker — i.e. it is the deadline interrupt, not a
   plain canvas error."
  [e]
  (loop [x e, guard 0]
    (cond
      (or (nil? x) (> guard 16)) false
      (and (instance? cljs.core/ExceptionInfo x)
           (contains? (ex-data x) :sci.impl/interrupt)) true
      :else (recur (ex-cause x) (inc guard)))))

;; FAIL-LOUD: bounded rendering is a SAFETY property, not an optimization.
;; When SCI can't run an agent-editable fn for any reason OTHER than the
;; deadline interrupt — a missing `:seon.fn/source`, an env-reconstruction
;; gap (an alias neither the stored `:seon.ns/require-edges` nor the
;; fallback-parsed `:seon.ns/source` carries), or a genuine
;; runtime throw — invoke-bounded returns `{::error <:seon.db/error map>}`
;; and the caller renders a `:seon/error` block IN PLACE (the ONE error
;; mechanism). It NEVER falls back to the unbounded compiled path: a
;; non-terminating agent-editable fn must never be able to wedge the
;; single-threaded pod, unconditionally. The error surface is derived and
;; self-healing — fix the fn (or its ns requires) and the next render is
;; clean. One-time warn per sym so a persistently-failing fn stays visible
;; in the log without spam.
(def ^:private !bounding-warned (atom #{}))

(defn- warn-bounding-failure-once!
  "Warn + `record!` a bounding failure ONCE per [sym fault].

   The render path re-invokes a persistently-broken canvas on every
   fetch/feed tick — per-occurrence recording would flood the DB the
   same way per-occurrence warns would flood the log, so both ride one
   dedup. `raw` is the thrown value (or a minted ex-info for the
   string-message cases); an error already recorded by an inner funnel
   (the async wrapper arms) is skipped (`recorded?`)."
  [sym msg fault raw]
  (let [k (str sym " " fault)]
    (when-not (contains? @!bounding-warned k)
      (swap! !bounding-warned conj k)
      (log/warn! {:seon.log/source  ::invoke-bounded
                  :seon.log/message
                  (str "render fn " sym " could not run under SCI bounding ("
                       msg ") — rendering a :seon/error block in place "
                       "(fail-loud; the unbounded compiled fallback is "
                       "banned). Ensure the fn's :seon.fn/source and its ns "
                       ":require aliases are stored (:seon.ns/require-edges; "
                       "re-eval the ns form to tee them).")})
      (when-not (err/recorded? raw)
        (err/record! {:seon.error/raw raw :seon.error/fault fault})))))

(defn- instrument-env-in-causes
  "The malli instrument-error ENVELOPE (ex-data) on `e` or any exception in
   its cause chain, or nil. SCI re-wraps a thrown instrumented-call failure
   into a `:sci/error` whose top-level data is location-only; the legible
   envelope rides the cause."
  [e]
  (loop [x e, guard 0]
    (when (and x (< guard 16))
      (let [d (ex-data x)]
        (if (einstrument/instrument-error? d)
          d
          (recur (ex-cause x) (inc guard)))))))

(defn- legible-error-message
  "The error message for a fn SCI could not run — the bare message PLUS,
   when an instrumented inner call failed validation, the full humanized
   malli explain (fn, arg, expected vs got). A bare `:malli.core/invalid-input`
   names NOTHING the author can act on (drive-observed: the agent shipped
   around its broken view instead of fixing it); the explain names the
   failing function + value, so the ⚠ surface is actionable."
  [e]
  (let [base (err/->message e)
        env  (instrument-env-in-causes e)
        detail (when env
                 (try (einstrument/render-malli-error env)
                      (catch :default e2
                        ;; OUR renderer failing on OUR OWN malli envelope
                        ;; is a core bug; the caller still degrades to the
                        ;; bare base message (contract unchanged).
                        (err/record! {:seon.error/raw e2 :seon.error/fault :core})
                        nil)))]
    (if detail (str base "\n" detail) base)))

(defn- bounding-error
  "The fail-loud envelope for a fn SCI could not run: warn + `record!`
   once (per sym+fault) and return `{::error <:seon.db/error map>}` for
   the caller to render in place. `e-or-msg` is an exception or a plain
   message string. `fault` defaults `:agent` (invoke-bounded only wraps
   agent-authored syms — the agent's source/shape/throw is its own);
   the env-reconstruction/init outer catch passes `:core` explicitly
   (RULED: our machinery failing while PREPARING agent code). An
   instrumented inner-call failure carries its humanized malli explain
   ([[legible-error-message]])."
  ([sym e-or-msg] (bounding-error sym e-or-msg :agent))
  ([sym e-or-msg fault]
   (let [raw (if (string? e-or-msg) (ex-info e-or-msg {}) e-or-msg)
         em  (if (string? e-or-msg)
               {:seon.error/message e-or-msg}
               (assoc (err/->map e-or-msg)
                      :seon.error/message (legible-error-message e-or-msg)))]
     (warn-bounding-failure-once! sym (:seon.error/message em) fault raw)
     {:seon.render.sci/error em})))

;; ============================================================
;; Lexical-environment reconstruction — aliases, requires, sources.
;; All fail-soft: any failure degrades to a smaller surface, never throws
;; into the render path.
;; ============================================================

(defn- ns-source
  "The stored `:seon.ns/source` (the agent's `(ns … (:require …))` form) for
   `ns-kw`, or nil."
  [db ns-kw]
  (try
    (ffirst (db/query
              {:seon.db/db    db
               :seon.db/query '[:find ?src :in $ ?nm :where
                                [?ns :seon.ns/name ?nm]
                                [?ns :seon.ns/source ?src]]
               :seon.db/args  [ns-kw]}))
    (catch :default e
      ;; core machinery reading OUR stored ns source — a query throw is a
      ;; defect, not "no source"; caller still degrades to nil.
      (err/record! {:seon.error/raw e :seon.error/fault :core})
      nil)))

(defn- home-agent-id
  "The agent id when `ns-str` names an agent HOME ns (`my.agent.<id>` — the
   deterministic `seon.agent.home/home-ns` shape, structural, no list), else
   nil."
  [ns-str]
  (let [prefix "my.agent."]
    (when (and (str/starts-with? ns-str prefix)
               (not (str/includes? (subs ns-str (count prefix)) ".")))
      (subs ns-str (count prefix)))))

(defn- derived-home-ns-source
  "The canonical home-ns `(ns …)` source for a HOME ns with no stored
   `:seon.ns/source`. A fresh home ns (`my.agent.<id>`) is WIRED by
   `seon.eval/setup-agent-ns!` from the ONE canonical
   `seon.agent.home/home-ns-form` but only gets a stored source datom when the
   agent re-evals an `(ns …)` form itself — so derive the SAME form here
   (per-agent `home-requires-for` honored) and the cage rebuilds the exact
   aliases/refers the compiled fn had. nil for a non-home ns."
  [ns-str]
  (when-let [id (home-agent-id ns-str)]
    (try (home/home-ns-form ns-str (home/home-requires-for id))
         (catch :default e
           ;; deriving the canonical home-ns form is OUR machinery — a
           ;; throw is a core defect; caller still degrades to nil.
           (err/record! {:seon.error/raw e :seon.error/fault :core})
           nil))))

(defn- fn-source
  "The stored `:seon.fn/source` for `sym` (a string), or nil. nil means we
   can't interpret it (no source) → caller falls back to the compiled path."
  [db sym]
  (try
    (ffirst (db/query
              {:seon.db/db    db
               :seon.db/query '[:find ?src :in $ ?sym :where
                                [?f :seon.fn/sym ?sym]
                                [?f :seon.fn/source ?src]]
               :seon.db/args  [(str sym)]}))
    (catch :default e
      ;; core machinery reading OUR stored fn source — a query throw is a
      ;; defect, not "no source"; caller still degrades to nil.
      (err/record! {:seon.error/raw e :seon.error/fault :core})
      nil)))

;; The lexical require facts (aliases / refers / required nses) come from
;; the STORED `:seon.ns/require-edges` component rows — the analyzer-
;; derived facts the tee writes (M4 structural store; seon.eval/
;; stored-require-edges → edges->require-info). Re-parsing the
;; `:seon.ns/source` TEXT survives only as the documented fallback for
;; PRE-STRUCTURAL rows (an ns whose edges were never teed — old stores;
;; they self-backfill on the next replay/re-eval). The once-per-ns debug
;; note below makes the taken path observable: its ABSENCE on a render
;; proves the stored path ran.
(def ^:private !source-fallback-noted (atom #{}))

(defn- note-source-parse-fallback-once! [agent-ns]
  (when-not (contains? @!source-fallback-noted agent-ns)
    (swap! !source-fallback-noted conj agent-ns)
    (log/debug! {:seon.log/source  ::require-info
                 :seon.log/message
                 (str "no stored :seon.ns/require-edges for " agent-ns
                      " — rebuilding the SCI env by PARSING :seon.ns/source "
                      "(pre-structural row fallback; re-evaling the ns form "
                      "stores the edges)")})))

(defn- require-info
  "The `seon.eval/::require-info` map for `agent-ns` (a string): stored
   `:seon.ns/require-edges` when present; else the documented
   source-parse fallback over the stored (or derived home-ns)
   `(ns …)` source. Fail-soft → the empty info."
  [db agent-ns]
  (let [stored (seval/stored-require-edges db (keyword agent-ns))]
    (if (seq stored)
      (seval/edges->require-info stored)
      (do (note-source-parse-fallback-once! agent-ns)
          (let [ns-src (or (ns-source db (keyword agent-ns))
                           (derived-home-ns-source agent-ns))]
            (seval/edges->require-info
              (if ns-src
                (analyzer-info/require-edges-from-source ns-src)
                #{})))))))

(defn- expose-ns
  "`{simple-sym <value>}` for namespace `ns-sym`, UNIONing three sources:

   1. the COMPILED FN members of the ns's LIVE object on `js/globalThis`
      (`seon.eval/ns-fn-members`) — every own enumerable fn, INCLUDING unspecced
      helpers the `:seon.fn` index can't see;
   2. the COMPILED NON-FN data members of that same live object
      (`seon.eval/ns-data-members`) — every own enumerable `(def …)` data
      constant (set/map/vector/string/number/keyword); and
   3. the `:seon.fn` index members, resolved via `lookup-value`.

   (1) fixed the unspecced-helper miss: a canvas fn calling an aliased unspecced
   helper (`h/format-count`) found no entry when `expose-ns` enumerated only the
   specced index, so SCI threw 'Unable to resolve symbol' and the canvas fell to
   the UNBOUNDED compiled path. (2) fixes the SAME class for NON-fn own-ns vars:
   a canvas referencing an own-ns `(def grounded-dims #{…})` data constant likewise
   found no entry (fns-only enumeration) and fell off the bounded path. Both
   unions resolve the member under SCI so the canvas stays interrupt-bounded — and
   both reuse the SAME globalThis munge/demunge machinery as the index lookups.

   `exclude` is a set of full-sym strings to skip (the canvas fn itself —
   re-defined via the eval). Returns nil when the ns has no resolvable members
   (an indexed-but-unloaded ns: no globalThis object, no index hits) or on any
   failure — caller degrades the SCI surface, never throws."
  [db ns-sym exclude]
  (try
    (let [ns-kw  (keyword ns-sym)
          nm     (name ns-sym)
          ;; COMPILED members from the live ns object — keep everything not in
          ;; `exclude` (matched by full-sym string, the same shape `exclude`
          ;; carries). FNS include unspecced helpers absent from the index; the
          ;; DATA members are own-ns `(def …)` constants (sets/maps/vectors/…)
          ;; that a canvas body references by simple name. Both come from the same
          ;; live globalThis object via the one munge/demunge scheme; fns are
          ;; merged on top of data so a name collision keeps the fn (a canvas that
          ;; both defs and shadows a name is degenerate, but fn-wins matches the
          ;; index merge below).
          compiled (reduce-kv (fn [m simple-sym v]
                                (if (contains? exclude (str nm "/" simple-sym))
                                  m
                                  (assoc m simple-sym v)))
                              (reduce-kv (fn [m simple-sym v]
                                           (if (contains? exclude (str nm "/" simple-sym))
                                             m
                                             (assoc m simple-sym v)))
                                         {} (seval/ns-data-members nm))
                              (seval/ns-fn-members nm))
          syms  (map first
                  (db/query {:seon.db/db    db
                             :seon.db/query '[:find ?s :in $ ?nm :where
                                              [?ns :seon.ns/name ?nm]
                                              [?f :seon.fn/ns ?ns]
                                              [?f :seon.fn/sym ?s]]
                             :seon.db/args  [ns-kw]}))
          ;; Index members on top — same fn objects for shared keys; index hits
          ;; that the live enumeration somehow missed still land.
          m (reduce (fn [m s]
                      (let [qs (symbol s)]
                        (if (and (not (contains? exclude s))
                                 (= (namespace qs) nm))
                          (if-let [v (seval/lookup-value qs)]
                            (assoc m (symbol (name qs)) v)
                            m)
                          m)))
                    compiled syms)]
      (when (seq m) m))
    (catch :default e
      ;; env-reconstruction machinery (index query + lookup-value walk)
      ;; throwing is a core defect; caller still degrades the SCI surface.
      (err/record! {:seon.error/raw e :seon.error/fault :core})
      nil)))

;; ============================================================
;; The bounded invocation.
;; ============================================================

;; invoke-bounded's return: the ::interrupt/::error envelope maps, a render
;; fn's own envelope MAP, or any bare value seon.render's unwrap tolerates —
;; a hiccup VECTOR (:html), a STRING (:ai), or nil (renders nothing).
(schema/register! ::result
  [:maybe [:or :map :string [:vector :any]]])

(defn- valid-result-for-view?
  "True when SCI's eval result `r` is a value the caller can consume —
   mirrors `seon.render`'s unwrap tolerance (a render fn may return the
   html-response MAP envelope OR the bare view value, and nil renders
   nothing):
     :seon.render/html (or default) — a map, a bare hiccup VECTOR, or nil;
     :seon.render/ai                — a map, a bare STRING, or nil.
   Anything else is a broken render fn → a `:seon/error` block in place
   (fail-loud; re-running it on the unbounded compiled path is banned).
   The canvas caller (`seon.render/render-agent-canvas`) additionally requires
   the map envelope and fail-louds a bare value itself — the envelope-vs-
   bare tolerance is the caller's contract, not SCI's."
  [view r]
  (or (nil? r)
      (map? r)
      (case view
        :seon.render/ai (string? r)
        (vector? r))))

(defn invoke-bounded
  "Invoke an AGENT-authored render fn `sym` under SCI, time-bounded.

   A wall-clock
   deadline (`budget-ms`), passing `input` by reference. `view` selects the
   slot semantics (`:seon.render/ai` → a bare String result;
   `:seon.render/html` (default) → an html-response map) — one extra arg,
   same mechanism (context-render Decision 3).

   Resolves `sym`'s stored `:seon.fn/source`, rebuilds its namespace's
   lexical environment (aliases + required `seon.*`/agent nses + own-ns
   helpers, all from the DB index), evaluates the source INTO a fresh SCI ctx
   (so the body is INTERPRETED → interrupt protected), and calls it.

   Returns, always a map OR the view's bare value:
   - the render fn's value on success (an html-response map for `:html`, a
     String for `:ai`);
   - `{:seon.render.sci/interrupt true}` when the deadline tripped (caller
     does fallback + recovery);
   - `{:seon.render.sci/error <:seon.db/error map>}` when SCI could not run
     the fn for ANY reason other than the interrupt — no stored source, an
     env-reconstruction gap (an alias neither the stored
     `:seon.ns/require-edges` nor the fallback-parsed `:seon.ns/source`
     carries), a genuine runtime throw, or a result of the wrong shape. The
     caller renders a `:seon/error` block IN PLACE (fail-loud); it must
     NEVER run the fn on the unbounded compiled path — the never-wedge
     safety property holds unconditionally. Never throws itself."
  ;; One `:=>` per runtime arity (idiomatic multi-arity) — a single `:=>` with
  ;; `{:optional true}` catn slots leaves arities 2/3 loosely validated and
  ;; trips malli's arity dispatch under instrument/unstrument cycles. The
  ;; lower arities delegate schema-valid defaults (`:seon.render/html` keyword,
  ;; `default-budget-ms` int), so no delegation trap. The return is
  ;; `::result` — the envelope maps OR any bare value `valid-result-for-view?`
  ;; admits (a bare hiccup vector / a bare ai string / nil renders nothing),
  ;; mirroring `seon.render`'s unwrap tolerance.
  {:malli/schema
   [:function
    [:=> [:catn [::sym :symbol] [::input :map]] ::result]
    [:=> [:catn [::sym :symbol] [::input :map] [::view :keyword]] ::result]
    [:=> [:catn [::sym :symbol] [::input :map] [::view :keyword] [::budget-ms :int]]
         ::result]]}
  ([sym input] (invoke-bounded sym input :seon.render/html default-budget-ms))
  ([sym input view] (invoke-bounded sym input view default-budget-ms))
  ([sym input view budget-ms]
   ;; OUTER GUARD — invoke-bounded must NEVER throw (the user's hard rule: SCI
   ;; may fail but must not crash the pod). Any unexpected error (a failure in
   ;; sci/init or env reconstruction) becomes the ::error envelope too.
   (try
     (let [db     (:seon.db/db input)
           source (when db (fn-source db sym))]
       (if (nil? source)
         (bounding-error
           sym (str "no stored :seon.fn/source for " sym
                    " — the fn cannot be interpreted (bounded)"))
         (let [agent-ns (namespace sym)
               ;; Aliases/refers/required-nses from the STORED
               ;; `:seon.ns/require-edges` datoms (analyzer facts teed at
               ;; eval/setup time — M4); pre-structural rows fall back to
               ;; parsing the stored (or derived home-ns) source, noted
               ;; once per ns ([[require-info]]).
               ;; `::nses` carries every stored edge target — the edge
               ;; tee fires on EVERY successful eval (a bare
               ;; `(require '[x])` included), so `:seon.ns/require-edges`
               ;; IS the fresh dep-edge truth (C36: the flat
               ;; `:seon.ns/requires` twin + its union here are deleted).
               {:seon.eval/keys [aliases nses refers refer-all]}
               (require-info db agent-ns)
               nses     (or nses #{})
               ;; expose each required seon.*/agent ns (full name) from the index
               req-ns   (reduce (fn [m tns]
                                  (if (exposable-ns? tns)
                                    (if-let [e (expose-ns db tns #{})]
                                      (assoc m tns e)
                                      m)
                                    m))
                                {} nses)
               ;; own-ns helpers (exclude the canvas fn — re-defined via eval) +
               ;; any :refer'd vars + every member of a `:refer :all` ns, all
               ;; resolvable by simple name after (in-ns).
               own      (or (expose-ns db (symbol agent-ns) #{(str sym)}) {})
               refer-v  (reduce-kv (fn [m tns ss]
                                     (reduce (fn [m s]
                                               (if-let [v (seval/lookup-value
                                                            (symbol (name tns) (name s)))]
                                                 (assoc m s v) m))
                                             m ss))
                                   {} refers)
               refer-all-v (reduce (fn [m tns]
                                     (merge m (or (expose-ns db tns #{}) {})))
                                   {} refer-all)
               agent-m  (merge own refer-v refer-all-v)
               nsmap    (cond-> (assoc req-ns 'seon.render.sci {'current-input current-input})
                          (seq agent-m) (assoc (symbol agent-ns) agent-m))
               c        (sci/init {:interrupt-fn deadline-interrupt-fn
                                   :classes      base-classes
                                   :namespaces   nsmap
                                   :ns-aliases   aliases})
               call     (str "(in-ns '" agent-ns ")\n"
                             source
                             "\n(" (name sym) " (seon.render.sci/current-input))")]
           (vreset! !input input)
           (vreset! !deadline (+ (js/Date.now) budget-ms))
           (try
             ;; Deep-force the result INSIDE the deadline window. An
             ;; interpreted fn returning LAZY seqs in its hiccup (`(map …)`
             ;; is the classic) would otherwise realize its SCI thunks
             ;; LATER — during html serialization, outside this try, against
             ;; a stale `!deadline` — and the un-catchable interrupt would
             ;; escape straight into the feed/router (every push fails).
             ;; `postwalk identity` realizes every nested seq eagerly here,
             ;; so an over-budget lazy body becomes the honest
             ;; `::interrupt` envelope and a healthy one returns realized
             ;; data that can never throw after we return.
             (let [r (walk/postwalk identity (sci/eval-string* c call))]
               ;; The valid shape is view-dependent (a String for :ai, a map
               ;; for :html). A wrong-shape result is a broken render fn → a
               ;; :seon/error block in place (fail-loud; the fn is NOT re-run
               ;; on the unbounded compiled path).
               (if (valid-result-for-view? view r)
                 r
                 (bounding-error
                   sym (str sym " returned an invalid "
                            (name (or view :seon.render/html)) " result — "
                            "expected " (if (= view :seon.render/ai)
                                          "a string" "a map")))))
             (catch :default e
               (if (interrupt-ex? e)
                 ;; the deadline interrupt is NOT swallowed — the caller
                 ;; runs recovery (retract + message) and the derived
                 ;; canvas section is its data surface; no datom here.
                 {:seon.render.sci/interrupt true}
                 ;; any non-interrupt SCI failure → :agent-fault record! +
                 ;; a :seon/error block in place (fail-loud; never the
                 ;; unbounded compiled path)
                 (bounding-error sym e)))))))
     (catch :default e
       ;; reconstruction / init failure — OUR machinery preparing agent
       ;; code (:core, RULED); never crash the render; error in place.
       (bounding-error sym e :core)))))

;; ============================================================
;; Recovery — reset the hung canvas to welcome and inform the agent.
;;
;; Fire-and-forget async (db/transact! + message! are async; the render fn is
;; sync and returns the welcome fallback immediately). Deduped per agent-id so
;; repeated renders during the async write window don't double-fire; once the
;; content is retracted, wired-content falls back to welcome and the SCI path
;; is no longer taken for this agent.
;; ============================================================

(def ^:private !recovering
  "Agent-ids with an in-flight recovery (dedupe guard). Volatile runtime
   state, not derivable — sanctioned."
  (atom #{}))

(defn- warning-text
  [sym budget-ms]
  (str "Your canvas fn `" sym "` did not terminate within " budget-ms
       "ms and was reset to the welcome canvas. Canvas fns must be pure, fast, "
       "TERMINATING database→hiccup renders — no loops, blocking, or native "
       "regex. They return {:seon.render/hiccup … :seon.render/ai …} from a "
       "few DB queries. Re-wire :seon.render.canvas/content with a fn that "
       "renders a query, not one that computes."))

(defn recover-hung-canvas!
  "Reset agent `agent-id`'s hung canvas to the core welcome.

   Retracts `:seon.render.canvas/content` and posts the agent a force'd message
   explaining what happened. Async + deduped; returns nil."
  {:malli/schema [:=> [:catn [::agent-id :string] [::sym :symbol] [::budget-ms :int]]
                  :nil]}
  [agent-id sym budget-ms]
  (when (and agent-id (not (contains? @!recovering agent-id)))
    (swap! !recovering conj agent-id)
    (-> (js/Promise.resolve)
        (.then (fn [_]
                 (db/transact!
                   {:seon.db/tx-data
                    [[:db/retract [:seon.agent/id agent-id]
                      :seon.render.canvas/content]]})))
        (.then (fn [_]
                 ;; late-resolved to avoid a require cycle
                 ;; (render → render.sci → message → ctx → render). user-ref is
                 ;; the literal [:seon.user/id "user"] lookup ref.
                 (when-let [message! (seval/lookup-value 'seon.agent.message/message!)]
                   (message!
                     {:seon.agent.message/from    [:seon.user/id "user"]
                      :seon.agent.message/to       [[:seon.agent/id agent-id]]
                      :seon.agent.message/content (warning-text sym budget-ms)
                      ;; :core origin (#43) — a substrate nudge, NOT a human
                      ;; message: it must not wake an idle agent or move the
                      ;; halt baseline even though it sends from the user-ref.
                      :seon.agent.message/origin  :core}))))
        (.catch (fn [e]
                  (log/warn! {:seon.log/source  ::recover-hung-canvas!
                              :seon.log/message
                              (str "canvas recovery for " agent-id
                                   " hit an error: " (or (.-message e) (str e)))})))
        (.finally (fn [] (swap! !recovering disj agent-id)))))
  nil)

;; The active canvas-error push was dropped (#43 / D2, 2026-06-21). A
;; forged message wakes the agent — and the old notification sent
;; FROM the user-ref with :force, indistinguishable from a human message,
;; so a broken canvas re-armed the wake loop and defeated the halt. There is
;; no active intervention now: a broken canvas is a derived surface. The
;; `:seon.render/ai` render in seon.render.canvas/error-response carries
;; "YOUR CANVAS IS BROKEN — …" and the :seon.agent.ctx.canvas/canvas-
;; section re-derives it from the db value EVERY turn (a pure fn of state,
;; no stored error flag, self-healing when the canvas renders clean again).
;; The agent learns of breakage by reading its own context, not by being
;; woken. The deleted dedup atom existed only to throttle the
;; push went with it — a derived surface needs no dedup.
