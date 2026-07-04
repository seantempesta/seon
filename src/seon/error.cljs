(ns seon.error
  "Uniform error→map conversion for the safe-by-default boundary
   (spec-02 §2.5), plus `record!` — the catch-site verb that classifies,
   structures, persists, and escalates a caught error. Anywhere a seon
   surface catches an exception it should return `(error/->map e)` (or
   call [[record!]] when the catch site knows the fault population) so
   agents inspect a stable shape.

   The result map carries:
     :seon.error/message   string — best-effort human-readable summary
     :seon.error/ex-data   map    — ex-data of THIS level (per-layer)
     :seon.error/data      map    — ex-data merged across the entire
                                    cause chain, deepest-wins. Read this
                                    from renderers / agent code; it
                                    flattens cljs.js's wrap layers
                                    (`Could not eval …` → `ERROR` →
                                    original) into one map.
     :seon.error/stack     string — .-stack, truncated to ~4kb
     :seon.error/cause     map    — recursive ->map of (ex-cause e)
     :seon.error/raw       any    — the original error instance, opaque
     :seon.error/truncated true   — set when cause-chain hits depth 5

   [[record!]] adds (envelope + persisted datom projection):
     :seon.error/fault     :agent | :core — which POPULATION the failure
                                    belongs to (never authorship). RULED:
                                    the discriminator is \"what were we
                                    calling\" — our machinery throwing
                                    while PREPARING agent code is :core.
     :seon.error/at        int    — basis-t (tx eid) at the catch site;
                                    `(seon.db/as-of at)` freezes the db
                                    the failing code saw.
     :seon.error/frames    vector — EDN stack frames (component entities)
     :seon.error/args-edn  string — bounded full-args print (malli path)

   See docs/prds/agent-runtime/research/eval-error-envelope-2026-05-22.md
   for the cljs.js wrap analysis that motivates :seon.error/data, and
   docs/prds/agent-ctx/research/error-blame-strict-gate-2026-07-03.md for
   the fault/record!/dial design (RULED 2026-07-04)."
  (:require
    [cljs.stacktrace :as stacktrace]
    [goog.object :as gobj]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.error.instrument :as ei]
    [seon.schema :as schema]))

;; ============================================================
;; Persisted error-datom schema — the EDN-safe PROJECTION of the
;; in-memory envelope (datoms are projections; the envelope may carry
;; live objects like :seon.error/raw, the datom never does).
;; ============================================================

;; The two error populations. :agent = expected, the agent's learning
;; signal (NEVER escalates in any mode); :core = our bug (loud per the
;; :seon.config/on-core-error dial).
(schema/register! ::fault [:enum :agent :core])
;; basis-t (tx eid) of the db value live at the catch site — plugs
;; straight into `seon.db/as-of` (datahike's :max-tx IS the tx eid).
(schema/register! ::at :int)
(schema/register! ::message :string)
(schema/register! ::stack :string)
;; Bounded, fn-stubbed pr-str of the FULL args vector (malli reports
;; carry :args on every report type; see error/instrument.cljc).
(schema/register! ::args-edn :string)
;; Bounded, sanitized print of the flattened cause-chain ex-data (the
;; raw :seon.error.malli/errors leafs — live Schema objects — dropped).
(schema/register! ::data-edn :string)

;; Frame leaf attrs precede the frame entity shape (leaf-rule).
(schema/register! :seon.error.frame/index  [:int {:min 0}])
(schema/register! :seon.error.frame/fn     :string)
(schema/register! :seon.error.frame/file   :string)
(schema/register! :seon.error.frame/line   :int)
(schema/register! :seon.error.frame/column :int)

;; ONE stack frame — reified component entity so traces are
;; Datalog-queryable ("every :core fault whose top frame is in
;; render/sci"). Registered ONCE; ::frames references it.
(schema/register! ::frame
  [:map
   [:seon.error.frame/index  :seon.error.frame/index]
   [:seon.error.frame/fn     {:optional true} :seon.error.frame/fn]
   [:seon.error.frame/file   {:optional true} :seon.error.frame/file]
   [:seon.error.frame/line   {:optional true} :seon.error.frame/line]
   [:seon.error.frame/column {:optional true} :seon.error.frame/column]])
(schema/register! ::frames
  [:vector {:seon.db/component true} :seon.db/ref]) ; of ::frame entities

(defn ->message
  "Best-effort human-readable message for any error-ish value."
  {:malli/schema [:=> [:cat :any] :string]}
  [e]
  (or (when (some? e) (.-message e)) (str e)))

(defn- ex-data-chain
  "Walk e and its ex-cause chain (bounded depth 5), collecting each
   level's ex-data. Returns a seq ordered deepest-first, so a
   subsequent (apply merge ...) gives deepest-wins semantics — the
   ORIGINAL throw's ex-data takes precedence over wrap-layer
   ex-data added by cljs.js etc."
  [e]
  (loop [e e depth 0 acc ()]
    (if (or (nil? e) (>= depth 5))
      acc
      (let [data (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
            acc' (if (seq data) (cons data acc) acc)]
        (recur (ex-cause e) (inc depth) acc')))))

(defn ->map
  "Convert a CLJS error to an agent-inspectable map.

   Recursion on
   :cause is bounded to depth 5 to defend against cycles.

   The top-level `:seon.error/data` flattens the entire cause chain's
   ex-data into one map (deepest wins). Renderers + agent code should
   read THIS, not walk the per-level `:seon.error/ex-data` keys, so
   useful info like `:seon.eval/warning-type` surfaces regardless of
   how many layers cljs.js's `wrap-error` added on top."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :int] [:maybe :map]]]}
  ([e] (->map e 0))
  ([e depth]
   (when (some? e)
     (let [base   {:seon.error/message (->message e)
                   :seon.error/raw     e}
           data   (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
           stack  (when (some? (.-stack e))
                    (let [s (str (.-stack e))]
                      (subs s 0 (min 4096 (count s)))))
           cause  (when (< depth 5) (some-> (ex-cause e) (->map (inc depth))))
           trunc? (and (>= depth 5) (some? (ex-cause e)))
           ;; :seon.error/data only emitted at the top of the chain —
           ;; flattens cljs.js's wraps so renderers read one key.
           merged (when (zero? depth)
                    (apply merge {} (ex-data-chain e)))]
       (cond-> base
         data            (assoc :seon.error/ex-data data)
         (seq merged)    (assoc :seon.error/data merged)
         stack           (assoc :seon.error/stack stack)
         cause           (assoc :seon.error/cause cause)
         trunc?          (assoc :seon.error/truncated true))))))

;; ============================================================
;; Fault discriminator — "what were we calling", never "whose turn is
;; it". Moved here (the leaf error ns) from seon.render.sci so the
;; instrumentation wrapper can use it without a require cycle; render /
;; render.sci / render-fns all read THIS one.
;; ============================================================

(defn agent-authored-sym?
  "True when `sym` names an AGENT-authored fn.

   Any agent-authored render/layout/handler (a tile fn, a context-block
   render, a `/call` handler) gets the SCI wrapper, and a failure while
   CALLING it is `:agent`-fault; the core
   (`seon.*`/`clojure.*`/`cljs.*`/`sci.*`/`goog.*`) compiled path is
   unbounded and a failure there is `:core`-fault."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [sym]
  (boolean
    (and (qualified-symbol? sym)
         (let [ns (namespace sym)]
           (not (or (= ns "seon")
                    (re-find #"^(seon|clojure|cljs|sci|goog)\." ns)))))))

(defn fault-for
  "The `:seon.error/fault` population for a failed call of `sym`.

   `:agent` when [[agent-authored-sym?]], `:core` otherwise (an
   unclassified bug is still a bug — default loud)."
  {:malli/schema [:=> [:cat :any] ::fault]}
  [sym]
  (if (agent-authored-sym? sym) :agent :core))

(def agent-fault-kinds
  "The `:seon.error/kind` values that identify an AGENT-population failure:
   the agent's OWN input defect, whose message is crystal-clear guidance the
   agent acts on (a mistyped attr, a bad form, an unreadable expression, a
   REPL-parity slip). These are :agent no matter which seon.* conduit
   surfaces the throw — the discriminator here is the error's OWN content,
   not the wrapping fn (see `seon.instrument/wrapper-fault`). The SINGLE
   source of truth for this set — `seon.eval/known-error-kinds` references
   it too (the same 'self-contained agent-fixable error' concept)."
  #{:user-input :compile :read :seon.eval/repl-parity})

;; ============================================================
;; EDN stack frames — cljs.stacktrace's V8/Node parser (no source maps;
;; frames name compiled-JS coords, the munged fn names carry the ns).
;; ============================================================

(def ^:private max-frames
  "Frame-entity bound per error — datoms are projections, the raw
   4kb stack string keeps the full trace."
  20)

(defn parse-frames
  "Parse a V8/Node stack string into `:seon.error.frame/*` maps.

   `cljs.stacktrace/parse-stacktrace :nodejs` under the hood; bounded to
   [[max-frames]], nil-valued slots ABSENT (optional = absent). Returns
   nil (not []) when the string yields no frames — errors-as-values, a
   parse mishap just means no frames on the datom."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe [:vector ::frame]]]}
  [stack-str]
  (when (string? stack-str)
    (try
      (let [frames (stacktrace/parse-stacktrace
                     {} stack-str {:ua-product :nodejs} {:output-dir "out"})
            fs (into []
                     (map-indexed
                       (fn [i {:keys [file function line column]}]
                         (cond-> {:seon.error.frame/index i}
                           (string? file)     (assoc :seon.error.frame/file file)
                           (string? function) (assoc :seon.error.frame/fn function)
                           (int? line)        (assoc :seon.error.frame/line line)
                           (int? column)      (assoc :seon.error.frame/column column))))
                     (take max-frames frames))]
        (not-empty fs))
      (catch :default _ nil))))

;; ============================================================
;; DB hooks — the late-bound persistence seam. seon.db.internal requires
;; seon.error (this ns must stay below seon.db), so seon.db INJECTS its
;; transact!/basis-t here at ITS load (see the bottom of seon.db). Before
;; the hooks land (very early boot) record! buffers in memory.
;; ============================================================

(defonce ^:private !db-hooks
  ;; {:seon.error/transact! (fn [tx-data] Promise|nil)
  ;;  :seon.error/basis-t   (fn [] int|nil)}
  (atom nil))

(defn set-db-hooks!
  "Install the persistence hooks [[record!]] writes through.

   Called ONCE by `seon.db` at namespace load (the require direction is
   db→error, so the write path is injected, not required)."
  {:malli/schema [:=> [:cat :map] :nil]}
  [hooks]
  (reset! !db-hooks hooks)
  nil)

(def ^:private pending-cap
  "Bound of the in-memory not-yet-persisted error buffer (drop-oldest)."
  32)

(defonce ^:private !pending
  ;; Error-datom entity maps awaiting a live conn; flushed by the next
  ;; successful record! persist. Process-volatile by design (tier 3).
  (atom []))

(defn- buffer!
  "Queue `entity` for the next persist; drop-oldest at [[pending-cap]]."
  [entity]
  (swap! !pending
         (fn [v]
           (let [v (conj v entity)]
             (if (> (count v) pending-cap)
               (do (js/console.warn
                     (str "seon.error/record!: pending-error buffer full ("
                          pending-cap ") — dropping the oldest unpersisted error"))
                   (subvec v (- (count v) pending-cap)))
               v)))))

(defonce ^:private !persists-inflight
  ;; Count of error-persist transacts not yet settled — the recursion
  ;; fence [[record!]] reads (see self-persist-failure? there).
  (atom 0))

(defn- persist!
  "Fire-and-forget transact of error `entities`; re-buffers on failure.

   Returns the transact Promise (nil when no conn/hook) so the :crash
   escalation can sequence its exit AFTER the write settles. Never
   throws, never awaited by callers."
  [entities]
  (let [tx! (:seon.error/transact! @!db-hooks)
        p   (when tx! (try (tx! entities) (catch :default _ nil)))]
    (if (and p (fn? (.-then p)))
      (do (swap! !persists-inflight inc)
          (-> p
              (.then (fn [{ok? :seon.db/ok?}]
                       (when-not ok? (run! buffer! entities))))
              (.catch (fn [_] (run! buffer! entities)))
              (.finally (fn [] (swap! !persists-inflight dec)))))
      (do (run! buffer! entities) nil))))

(defn- basis-t-now
  "basis-t via the injected hook, nil when no conn is live yet."
  []
  (when-let [f (:seon.error/basis-t @!db-hooks)]
    (try (f) (catch :default _ nil))))

(defn- datom-projection
  "The EDN-safe datom entity for an envelope — bounded strings, reified
   frames, NO live objects (`:seon.error/raw`, malli Schema leafs)."
  [envelope]
  (let [{:seon.error/keys [message fault at stack frames args-edn data]} envelope
        ;; :seon.error.malli/errors carries live Schema objects + raw
        ;; values (research: malli-instrument-error-data-2026-07-04 §3) —
        ;; dropped from the projection; the EDN-safe malli fields
        ;; (schema form, got-edn, humanized …) remain.
        data-edn (when (seq data)
                   (tokens/clip-str
                     (ei/pr-str-readable (dissoc data :seon.error.malli/errors))
                     300))]
    (cond-> {:seon.error/fault   fault
             :seon.error/message (tokens/clip-str (str message) 100)}
      at             (assoc :seon.error/at at)
      stack          (assoc :seon.error/stack stack)
      (seq frames)   (assoc :seon.error/frames frames)
      args-edn       (assoc :seon.error/args-edn args-edn)
      data-edn       (assoc :seon.error/data-edn data-edn))))

;; ============================================================
;; Expected-fault bracket — a TEST-side marker so a deliberately-provoked
;; :core fault (an error-path fixture verifying graceful degradation) still
;; WRITES its datom but does NOT trip the gate. A process-global DEPTH
;; counter, NOT a dynamic binding: CLJS `binding` does not cross async
;; .then/.catch hops, and several fixtures provoke faults through async
;; renders/evals. Node-test runs sequentially, so a global depth is
;; race-free across the suite. (Genuinely-stateful test artifact — the
;; reactive-context rule permits these.)
;; ============================================================

(defonce ^:private !expecting-core-fault (atom 0))

(defn expecting-a-core-fault?
  "True while inside an [[expecting-core-fault!]] bracket."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (pos? @!expecting-core-fault))

(defn expecting-core-fault!
  "TEST bracket: mark `:core` faults provoked inside `thunk` as EXPECTED.

   A deliberately-provoked `:core` fault (an error-path fixture that proves
   graceful degradation) still WRITES its datom, but [[escalate!]] prints
   the DISTINCT `SEON-EXPECTED-CORE-FAULT` marker instead of
   `SEON-CORE-FAULT`, so bin/test-cljs's gate does not count it, and the
   `:crash` dial does NOT exit. Async-safe: if `thunk` returns a Promise the
   bracket stays open until it settles (returns that Promise, re-throwing a
   rejection); otherwise it closes synchronously (returns the value). A NEW
   fault-provoking test that FORGETS the bracket trips the gate — the
   intended forcing function; there is deliberately no blanket suppression."
  {:malli/schema [:=> [:cat fn?] :any]}
  [thunk]
  (swap! !expecting-core-fault inc)
  (let [close! (fn [] (swap! !expecting-core-fault dec))]
    (try
      (let [v (thunk)]
        (if (and (some? v) (fn? (.-then v)))
          (.then v
                 (fn [x] (close!) x)
                 (fn [e] (close!) (throw e)))
          (do (close!) v)))
      (catch :default e (close!) (throw e)))))

(defn- escalate!
  "Apply the `:seon.config/on-core-error` dial to a `:core` fault.

   `:agent` faults NEVER reach here (enforced in [[record!]], not at
   call sites). Logs the loud `SEON-CORE-FAULT` marker (the
   test-wrapper/hook gates grep it) — or, inside an
   [[expecting-core-fault!]] bracket, the DISTINCT
   `SEON-EXPECTED-CORE-FAULT` marker (datom still written, gate NOT
   tripped, `:crash` NOT taken). Under `:crash` (and not expected) exits
   the pod AFTER the persist Promise settles (datom first, then loud exit)."
  [projection persist-promise]
  (let [expected? (expecting-a-core-fault?)]
    (js/console.error
      (str (if expected? "SEON-EXPECTED-CORE-FAULT" "SEON-CORE-FAULT") " "
           (:seon.error/message projection)
           (when-let [at (:seon.error/at projection)] (str " @t=" at))))
    (when (and (not expected?) (= :crash (config/on-core-error)))
      (let [exit! (fn [& _]
                    (js/console.error
                      "seon.error/record!: on-core-error :crash — exiting after persisting the fault datom")
                    (.exit js/process 1))]
        (if (and persist-promise (fn? (.-then persist-promise)))
          (.then persist-promise exit! exit!)
          (exit!))))))

(defn recorded?
  "True when `e` already produced its datom via [[record!]].

   ONE error → ONE datom: a rejection propagates through every outer
   instrumented async wrapper AND the process net; the FIRST record!
   tags the raw error object and every later funnel checks this before
   recording again. Primitives/frozen values can't carry the tag —
   worst case a duplicate datom, never a lost one."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [e]
  (boolean (and (some? e)
                (try (gobj/get e "seon$error$recorded")
                     (catch :default _ false)))))

(defn- mark-recorded!
  "Tag the raw error object so later funnels skip it ([[recorded?]])."
  [e]
  (when (some? e)
    (try (gobj/set e "seon$error$recorded" true)
         (catch :default _ nil)))
  nil)

(schema/register! ::record-request
  [:map
   ;; the thrown value — a third-party boundary (any JS throwable)
   [::raw :any]
   [::fault ::fault]])

(defn record!
  "Convert, classify, persist (fire-and-forget), and escalate an error.

   The iron rule as a fn: nothing is caught without becoming data.
   Builds the [[->map]] envelope from `::raw`, stamps `::fault` (from
   the caller — the catch site is the only place that knows what was
   being called), `::at` (basis-t when a conn is live), parsed
   `::frames`, and `::args-edn` (lifted from the malli envelope when
   present). Persists the EDN-safe projection without awaiting — a
   failed/impossible write buffers in memory (bounded, drop-oldest) and
   flushes on the next successful record!. `:core` faults escalate per
   the `:seon.config/on-core-error` dial; `:agent` faults NEVER
   escalate in any mode. Never throws. Returns the enriched in-memory
   envelope so catch sites keep their current return contract."
  {:malli/schema [:=> [:cat ::record-request] :map]}
  [{::keys [raw fault]}]
  (try
    (mark-recorded! raw)
    (let [envelope (or (->map raw)
                       {:seon.error/message "seon.error/record!: nil error value"})
          ;; RECURSION FENCE: if THIS error is `seon.db/transact!` violating
          ;; its own contract while an error-persist write is in flight,
          ;; persisting it would re-trip the same violation forever (async
          ;; loop, not a stack overflow). Console-only for that one shape.
          self-persist-failure?
          (and (pos? @!persists-inflight)
               (= 'seon.db/transact!
                  (get-in envelope [:seon.error/data :seon.error.malli/fn-sym])))
          args-edn (get-in envelope [:seon.error/data :seon.error/args-edn])
          envelope (cond-> (assoc envelope :seon.error/fault fault)
                     :always  (as-> m (if-let [t (basis-t-now)]
                                        (assoc m :seon.error/at t) m))
                     args-edn (assoc :seon.error/args-edn args-edn)
                     :always  (as-> m (if-let [fs (parse-frames (:seon.error/stack m))]
                                        (assoc m :seon.error/frames fs) m)))
          projection (datom-projection envelope)
          p          (when-not self-persist-failure?
                       (let [batch (let [pend @!pending]
                                     (reset! !pending [])
                                     (conj pend projection))]
                         (persist! batch)))]
      (if self-persist-failure?
        (js/console.error
          "SEON-CORE-FAULT (unpersistable — error-persist transact violated its own contract):"
          (:seon.error/message projection))
        (when (= :core fault)
          (escalate! projection p)))
      envelope)
    (catch :default e
      ;; record! must never throw — last-resort console trace only.
      (js/console.error "seon.error/record! itself failed:" e)
      {:seon.error/message (->message raw)
       :seon.error/fault   fault})))
