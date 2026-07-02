(ns seon.render.sci
  "SCI-bounded invocation for AGENT-authored live-tile fns (tile-isolation
   PRD Layer 1, docs/prds/agent-runtime/tile-isolation-prd-2026-06-21.md).

   ## Why this exists

   The pod is a SINGLE Node thread. A live tile lets an agent point
   `:seon.render.live-tile/content` at a fn symbol that is invoked
   SYNCHRONOUSLY in the render path (`seon.render/html-render` →
   `(f input-map)`). A non-terminating agent tile fn (a sync
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

   - parse the agent ns's stored `:seon.ns/source` for its `:require` `:as`
     aliases + `:refer`s (eval.cljs:441 — the ns source carries them);
   - expose every required `seon.*`/agent namespace as SCI host vars by
     ENUMERATING its members from the `:seon.fn` index (code-as-data — the
     core IS indexed) and resolving each via `seon.eval/lookup-value`;
   - establish the agent's aliases via SCI `:ns-aliases`, expose own-ns
     helpers + `:refer`s under the agent ns, and `(in-ns agent-ns)` before
     the eval so simple-name refs resolve.

   `clojure.*`/`cljs.*` are SCI built-ins (aliased, not exposed). `js`
   interop is exposed via `:classes`. The exposed core/agent fns run COMPILED
   (fast, trusted) — only the tile fn's own body is interpreted, so only ITS
   loops are bounded.

   ## What this does NOT cover (residual class — needs Layer 2, deferred)

   A tile that calls a NATIVE host loop (compiled CLJS / JS `while(true)`,
   incl. a loop hidden in an exposed COMPILED helper) or a NATIVE regex (CLJS
   ReDoS) still blocks: `:interrupt-fn` never fires inside host code (the
   interrupt-aware overrides are JVM-only). Bounding that needs a killable
   worker (PRD Layer 2). Layer 1 bounds the reproduced freeze: an interpreted
   loop/recursion in the tile fn's own body.

   Toggle: env `SEON_TILE_SCI=0` disables bounding (agent tiles fall back to
   the direct compiled call). Default on."
  (:require
    [cljs.reader :as reader]
    [sci.core :as sci]
    [sci.interrupt :as interrupt]
    [seon.config :as config]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]))

;; ============================================================
;; Config + flag
;; ============================================================

(def default-budget-ms
  "Wall-clock budget for one agent tile render. The PRD test plan wants
   `budget ≥ max(250ms, 4 × baseline)`; measured live, a real agent tile (a few
   DB queries → hiccup, under the full SCI reconstruction path) renders in
   ~52ms, so 4 × baseline ≈ 208ms < 250ms — the 250ms floor holds with headroom,
   and a blocked loop is bounded to roughly this + interpreter slack (~316ms
   observed for a cold fork). Revisit if real tiles ever exceed ~60ms p99."
  250)

(defn bounding-enabled?
  "Layer-1 SCI bounding is ON unless `SEON_TILE_SCI=0`.

   Independently shippable + reversible — PRD migration step 2."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (not= "0" (config/env-string "SEON_TILE_SCI")))

(defn agent-authored-sym?
  "True when `sym` names an AGENT-authored fn.

   Any agent-authored
   render/layout/handler (a tile fn, a context-block render, a layout, a
   `/call` handler) gets the SCI wrapper; the core
   (`seon.*`/`clojure.*`/`cljs.*`) compiled path does not. Core renders and
   every core block fn stay on the fast compiled path; only agent-chosen
   namespaces (e.g. `my.workouts/chart-tile`) are bounded."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [sym]
  (boolean
    (and (qualified-symbol? sym)
         (let [ns (namespace sym)]
           (not (or (= ns "seon")
                    (re-find #"^(seon|clojure|cljs|sci|goog)\." ns)))))))

(defn- exposable-ns?
  "A namespace whose members we expose as SCI host vars — `seon.*`/agent
   namespaces. `clojure.*`/`cljs.*`/`goog.*` are SCI built-ins (we alias them
   via :ns-aliases but never enumerate)."
  [ns-sym]
  (not (re-find #"^(clojure|cljs|goog)(\.|$)" (name ns-sym))))

;; ============================================================
;; Per-invocation mutable holders.
;;
;; The pod is single-threaded and tile renders are SYNCHRONOUS (html-render
;; returns before the next render begins), so a process-wide deadline +
;; input volatile read by each fresh ctx's interrupt-fn / host accessor is
;; safe — there is no overlapping invocation to race.
;; ============================================================

(def ^:private !deadline (volatile! 0))
(def ^:private !input    (volatile! nil))

(defn- current-input
  "Host accessor exposed to the SCI ctx as `seon.render.sci/current-input` —
   lets the eval'd tile fn receive the live (non-serializable) input map by
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
         (catch :default _ nil))
    :warmed))

;; ============================================================
;; Interrupt detection — walk the cause chain for the un-forgeable marker.
;; The interpreter may re-wrap the propagated interrupt into a :sci/error
;; whose top-level ex-data lacks the marker but whose cause carries it.
;; ============================================================

(defn- interrupt-ex?
  "True if `e` (or any exception in its cause chain) carries the private
   `:sci.impl/interrupt` marker — i.e. it is the deadline interrupt, not a
   plain tile error."
  [e]
  (loop [x e, guard 0]
    (cond
      (or (nil? x) (> guard 16)) false
      (and (instance? cljs.core/ExceptionInfo x)
           (contains? (ex-data x) :sci.impl/interrupt)) true
      :else (recur (ex-cause x) (inc guard)))))

;; Non-brittleness: a tile that runs fine on the COMPILED path must never be
;; broken by SCI. The interpreter only resolves what we reconstruct into the
;; ctx (aliases, required nses, own-ns helpers) — and that reconstruction can
;; be incomplete (a brand-new ns whose `:seon.ns/source` isn't stored yet, an
;; unusual `:require` shape, a member not in the index). When SCI can't run a
;; tile for any reason OTHER than the deadline interrupt, we DON'T error — we
;; fall through to the compiled `html-render` (the proven path). That tile is
;; simply unbounded for now (re-bounds once its source/ns settle). One-time
;; warn per sym so a persistently-unbounded tile stays visible without spam.
(def ^:private !fallback-warned (atom #{}))

(defn- warn-fallback-once! [sym e]
  (when-not (contains? @!fallback-warned (str sym))
    (swap! !fallback-warned conj (str sym))
    (log/warn! {:seon.log/source  ::invoke-bounded
                :seon.log/message
                (str "tile fn " sym " could not run under SCI bounding ("
                     (or (some-> e .-message) (str e))
                     ") — rendering it on the UNBOUNDED compiled path. If it "
                     "ever hangs it will freeze the pod; ensure its ns "
                     ":require aliases are stored (:seon.ns/source).")})))

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
    (catch :default _ nil)))

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
    (catch :default _ nil)))

(defn- ns-requires
  "Parse an agent ns `:seon.ns/source` string into
   `{:aliases {alias target} :nses #{target …} :refers {target #{sym …}}
     :refer-all #{target …}}` from its `:require` clause. `:refer-all` is the
   set of nses required with `:refer :all` (every member exposed by simple
   name). Fail-soft → empties on any read error."
  [ns-source-str]
  (try
    (let [form (reader/read-string ns-source-str)
          reqs (->> form (filter seq?) (some #(when (= :require (first %)) (rest %))))]
      (reduce
        (fn [acc r]
          (cond
            (symbol? r) (update acc :nses conj r)
            (vector? r)
            (let [tns  (first r)
                  opts (try (apply hash-map (rest r)) (catch :default _ {}))
                  as   (:as opts)
                  refr (:refer opts)]
              (cond-> (update acc :nses conj tns)
                as                  (assoc-in [:aliases as] tns)
                (sequential? refr)  (assoc-in [:refers tns] (set refr))
                (= :all refr)       (update :refer-all conj tns)))
            :else acc))
        {:aliases {} :nses #{} :refers {} :refer-all #{}}
        (or reqs [])))
    (catch :default _ {:aliases {} :nses #{} :refers {} :refer-all #{}})))

(defn- expose-ns
  "`{simple-sym <value>}` for namespace `ns-sym`, UNIONing three sources:

   1. the COMPILED FN members of the ns's LIVE object on `js/globalThis`
      (`seon.eval/ns-fn-members`) — every own enumerable fn, INCLUDING unspecced
      helpers the `:seon.fn` index can't see;
   2. the COMPILED NON-FN data members of that same live object
      (`seon.eval/ns-data-members`) — every own enumerable `(def …)` data
      constant (set/map/vector/string/number/keyword); and
   3. the `:seon.fn` index members, resolved via `lookup-value`.

   (1) fixed the unspecced-helper miss: a tile fn calling an aliased UNSPECCED
   helper (`h/format-count`) found no entry when `expose-ns` enumerated only the
   SPECCED index, so SCI threw 'Unable to resolve symbol' and the tile fell to
   the UNBOUNDED compiled path. (2) fixes the SAME class for NON-fn own-ns vars:
   a tile referencing an own-ns `(def grounded-dims #{…})` data constant likewise
   found no entry (fns-only enumeration) and fell off the bounded path. Both
   unions resolve the member under SCI so the tile stays interrupt-bounded — and
   both reuse the SAME globalThis munge/demunge machinery as the index lookups.

   `exclude` is a set of full-sym strings to skip (the tile fn itself —
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
          ;; that a tile body references by simple name. Both come from the same
          ;; live globalThis object via the one munge/demunge scheme; fns are
          ;; merged on TOP of data so a name collision keeps the fn (a tile that
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
    (catch :default _ nil)))

;; ============================================================
;; The bounded invocation.
;; ============================================================

(defn- valid-result-for-view?
  "True when SCI's eval result `r` is a USABLE value for `view`:
     :seon.render/html (or default) — a MAP (the html-response envelope);
     :seon.render/ai                — a STRING (the bare rendered text).
   Anything else is a bug / partial value → the caller falls through to the
   compiled path (SCI is never a correctness gate)."
  [view r]
  (case view
    :seon.render/ai (string? r)
    (map? r)))

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
   - `{:seon.render.sci/fallthrough true}` when SCI could not run the fn for
     ANY reason other than the interrupt — no stored source, an env-
     reconstruction gap (new ns this turn, unusual require), or even a genuine
     runtime throw. The caller renders it on the compiled path, which either
     succeeds (the SCI env was just incomplete — a working fn is never broken
     by bounding) or throws the real error into the caller's catch → a legible
     fallback. SCI is a pure safety net for hangs; it is never a correctness
     gate."
  ;; One `:=>` per runtime arity (idiomatic multi-arity) — a single `:=>` with
  ;; `{:optional true}` catn slots leaves arities 2/3 loosely validated and
  ;; trips malli's arity dispatch under instrument/unstrument cycles. The
  ;; lower arities delegate schema-valid defaults (`:seon.render/html` keyword,
  ;; `default-budget-ms` int), so no delegation trap.
  {:malli/schema
   [:function
    [:=> [:catn [::sym :symbol] [::input :map]] [:or :map :string]]
    [:=> [:catn [::sym :symbol] [::input :map] [::view :keyword]] [:or :map :string]]
    [:=> [:catn [::sym :symbol] [::input :map] [::view :keyword] [::budget-ms :int]]
         [:or :map :string]]]}
  ([sym input] (invoke-bounded sym input :seon.render/html default-budget-ms))
  ([sym input view] (invoke-bounded sym input view default-budget-ms))
  ([sym input view budget-ms]
   ;; OUTER GUARD — invoke-bounded must NEVER throw (the user's hard rule: SCI
   ;; may fail but must not crash the pod). Any unexpected error (a failure in
   ;; sci/init or env reconstruction) degrades to the compiled path too.
   (try
     (let [db     (:seon.db/db input)
           source (when db (fn-source db sym))]
       (if (nil? source)
         {:seon.render.sci/fallthrough true}
         (let [agent-ns (namespace sym)
               ns-src   (ns-source db (keyword agent-ns))
               {:keys [aliases nses refers refer-all]}
               (if ns-src
                 (ns-requires ns-src)
                 {:aliases {} :nses #{} :refers {} :refer-all #{}})
               ;; expose each required seon.*/agent ns (full name) from the index
               req-ns   (reduce (fn [m tns]
                                  (if (exposable-ns? tns)
                                    (if-let [e (expose-ns db tns #{})]
                                      (assoc m tns e)
                                      m)
                                    m))
                                {} nses)
               ;; own-ns helpers (exclude the tile fn — re-defined via eval) +
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
             (let [r (sci/eval-string* c call)]
               ;; Non-brittle: a fn that returns the wrong shape under SCI (a
               ;; bug, or a partial value) must NOT become an instrumentation
               ;; throw on our return contract — fall through to the compiled
               ;; path, which feeds the same value to the existing handling.
               ;; SCI is never a correctness gate. The valid shape is
               ;; view-dependent (a String for :ai, a map for :html).
               (if (valid-result-for-view? view r)
                 r
                 {:seon.render.sci/fallthrough true}))
             (catch :default e
               (if (interrupt-ex? e)
                 {:seon.render.sci/interrupt true}
                 ;; any non-interrupt SCI failure → run it on the compiled path
                 ;; (non-brittle: SCI never breaks a tile that works compiled)
                 (do (warn-fallback-once! sym e)
                     {:seon.render.sci/fallthrough true})))))))
     (catch :default e
       ;; reconstruction / init failure — never crash; render compiled.
       (warn-fallback-once! sym e)
       {:seon.render.sci/fallthrough true}))))

;; ============================================================
;; Recovery — reset the hung tile to welcome + inform the agent.
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
  (str "Your live tile fn `" sym "` did not terminate within " budget-ms
       "ms and was reset to the welcome tile. Tile fns must be PURE, FAST, "
       "TERMINATING database→hiccup renders — no loops, blocking, or native "
       "regex. They return {:seon.render/hiccup … :seon.render/ai …} from a "
       "few DB queries. Re-wire :seon.render.live-tile/content with a fn that "
       "renders a query, not one that computes."))

(defn recover-hung-tile!
  "Reset agent `agent-id`'s hung tile to the core welcome.

   Retracts `:seon.render.live-tile/content` and posts the agent a force'd message
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
                      :seon.render.live-tile/content]]})))
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
                  (log/warn! {:seon.log/source  ::recover-hung-tile!
                              :seon.log/message
                              (str "tile recovery for " agent-id
                                   " hit an error: " (or (.-message e) (str e)))})))
        (.finally (fn [] (swap! !recovering disj agent-id)))))
  nil)

;; The active tile-error PUSH was DROPPED (#43 / D2, 2026-06-21). A
;; forged message wakes the agent — and the old notify-tile-error! sent
;; FROM the user-ref with :force, indistinguishable from a human message,
;; so a broken tile re-armed the wake loop AND defeated the halt. There is
;; no active intervention now: a broken tile is a DERIVED surface. The
;; `:seon.render/ai` render in seon.render.live-tile/error-response carries
;; "YOUR LIVE TILE IS BROKEN — …" and the :seon.agent.ctx.live-tile/live-tile-
;; section re-derives it from the db value EVERY turn (a pure fn of state,
;; no stored error flag, self-healing when the tile renders clean again).
;; The agent learns of breakage by reading its own context, not by being
;; woken. The dedup atom + note-tile-ok! that existed only to throttle the
;; push went with it — a derived surface needs no dedup.
