(ns seon.error
  "Convert failures into Seon's uniform error values.

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
     :seon.error/store-id    uuid — catch-site Datahike store
     :seon.error/branch-name kw   — catch-site Proximum branch
     :seon.error/commit-id   uuid — immutable containing commit
     :seon.error/basis-t     int  — basis transaction inside that commit
     :seon.error/frames    vector — EDN stack frames (component entities)
     :seon.error/args-edn  string — bounded full-args print (malli path)

   See docs/prds/agent-runtime/research/eval-error-envelope-2026-05-22.md
   for the cljs.js wrap analysis that motivates :seon.error/data, and
   docs/prds/agent-ctx/research/error-blame-strict-gate-2026-07-03.md for
   the fault/record!/dial design (RULED 2026-07-04)."
  (:require
    [cljs.stacktrace :as stacktrace]
    [clojure.string :as str]
    [goog.object :as gobj]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db.branch :as branch]
    [seon.error.instrument :as ei]
    [seon.schema :as schema]))

;; ============================================================
;; Persisted error-datom schema — the EDN-safe PROJECTION of the
;; in-memory envelope (datoms are projections; the envelope may carry
;; live objects like :seon.error/raw, the datom never does).
;; ============================================================

;; The two error populations. :agent = a CALLER mistake — the learning
;; signal, covering BOTH caller kinds: an agent's own eval AND a dev/MCP
;; REPL eval in scope ([[in-dev-eval?]]). Recorded, NEVER escalates in
;; any mode. :core = our bug (loud per the :seon.config/on-core-error
;; dial).
(schema/register! ::fault [:enum :agent :core])
(schema/register! ::store-id ::branch/store-id)
(schema/register! ::branch-name ::branch/name)
(schema/register! ::commit-id ::branch/commit-id)
(schema/register! ::basis-t ::branch/basis-t)
(schema/register! ::message :string)
(schema/register! ::stack :string)
;; Bounded, fn-stubbed pr-str of the FULL args vector (malli reports
;; carry :args on every report type; see error/instrument.cljc).
(schema/register! ::args-edn :string)
;; Bounded, sanitized print of the flattened cause-chain ex-data (the
;; raw :seon.error.malli/errors leafs — live Schema objects — dropped).
(schema/register! ::data-edn :string)

(def persisted-attributes
  "Attributes emitted by the bounded persisted error projection."
  #{::fault ::message ::kind ::store-id ::branch-name ::commit-id ::basis-t
    ::stack ::frames ::args-edn ::data-edn
    :seon.error.frame/index :seon.error.frame/fn :seon.error.frame/file
    :seon.error.frame/line :seon.error.frame/column})

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
   level's ex-data. Returns a vector ordered shallowest-first, so a
   subsequent (apply merge ...) gives deepest-wins semantics — the
   ORIGINAL throw's ex-data takes precedence over wrap-layer
   ex-data added by cljs.js etc."
  [e]
  (loop [e e depth 0 acc []]
    (if (or (nil? e) (>= depth 5))
      acc
      (let [data (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
            acc' (if (seq data) (conj acc data) acc)]
        (recur (ex-cause e) (inc depth) acc')))))

(defn ->map
  "Convert a CLJS error to an agent-inspectable map.

   Recursion on
   :cause is bounded to depth 5 to defend against cycles.

   The top-level `:seon.error/data` flattens the entire cause chain's
   ex-data into one map (deepest wins). Renderers + agent code should
   read THIS, not walk the per-level `:seon.error/ex-data` keys, so
   useful info like `:seon.eval/warning-type` surfaces regardless of
   how many layers cljs.js's `wrap-error` added on top.

   A `:seon.error/kind` found in that flattened data is LIFTED to the
   envelope TOP level (C45) — the ONE position every consumer reads
   (`seon.eval/render-error-string`, `seon.instrument/wrapper-fault`,
   [[datom-projection]]). Synthesized envelopes (eval's read/compile/
   parity maps, worker_eval) already build the kind at the top, so
   after this lift there is no second position to `or` over."
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
                    (apply merge {} (ex-data-chain e)))
           ;; C45: the deepest kind is lifted to the envelope TOP — the
           ;; ONE position consumers read (no `or` over two positions).
           kind   (:seon.error/kind merged)]
       (cond-> base
         data            (assoc :seon.error/ex-data data)
         (seq merged)    (assoc :seon.error/data merged)
         kind            (assoc :seon.error/kind kind)
         stack           (assoc :seon.error/stack stack)
         cause           (assoc :seon.error/cause cause)
         trunc?          (assoc :seon.error/truncated true))))))

(defn deepest-message
  "The deepest non-generic `:seon.error/message` in a [[->map]] chain.

   cljs.js wraps an agent throw so the TOP message is a useless wrapper
   (`\"ERROR\"`, `\"Could not eval …\"`); the real message (the malli/db
   failure string, the agent's `(throw (js/Error. \"boom\"))` text) lives
   a level or two down the `:seon.error/cause` chain. Falls back to the
   top message. Bounded depth 6. Feeds the persisted error-datom message
   ([[record!]]'s projection, so the `SEON-CORE-FAULT` marker and
   `seon.agent.debug/errors` show the real cause) and
   `seon.eval/render-error-string`."
  {:malli/schema [:=> [:cat :any] :string]}
  [err]
  (loop [e err depth 0 best nil]
    (let [msg     (:seon.error/message e)
          useful? (and (string? msg)
                       (not (str/blank? msg))
                       (not (#{"ERROR" "Could not eval"} (str/trim msg))))
          best'   (if useful? msg best)]
      (if (or (nil? (:seon.error/cause e)) (>= depth 6))
        (or best' (:seon.error/message err) "")
        (recur (:seon.error/cause e) (inc depth) best')))))

;; ============================================================
;; Fault discriminator — "what were we calling", never "whose turn is
;; it". The instrumentation wrapper, renderer, and render-fn derivation all
;; read this leaf owner without a require cycle.
;; ============================================================

(defn agent-authored-sym?
  "True when `sym` names an AGENT-authored fn.

   Any agent-authored render/layout/handler (a canvas fn, a context-block
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
  ;; `:seon.eval/repl-form` was `:seon.eval/repl-verb` until 2026-07-12
  ;; (functions-not-verbs); error datoms persisted before then carry the
  ;; old keyword — historical values, deliberately not migrated.
  #{:user-input :compile :read :seon.eval/repl-parity :seon.eval/repl-form})

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
   parse mishap just means no frames on the datom. Absent ≠ nil: an
   error with no stack never reaches this fn (callers `some->`)."
  {:malli/schema [:=> [:cat :string] [:maybe [:vector ::frame]]]}
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
;; transact!/branch-head projection here at ITS load (see the bottom of
;; seon.db). Before
;; the hooks land (very early boot) record! buffers in memory.
;; ============================================================

(defonce ^:private !db-hooks
  ;; {:seon.error/transact! (fn [tx-data] Promise|nil)
  ;;  :seon.error/branch-head (fn [] ::branch/head|nil)}
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

;; One fiber-local scope store for error machinery. Process-global counters are
;; incorrect in the pod: while one Promise is pending, another agent can run on
;; the same event loop and must not inherit its expected-fault/dev-eval/persist
;; state. AsyncLocalStorage follows only the async work spawned in a scope.
(defonce ^:private scope-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn- current-scope []
  (or (.getStore scope-als) {}))

(defn- in-scope?
  [scope-key]
  (true? (get (current-scope) scope-key)))

(defn- run-with-scope-value
  "Run `thunk` with one fiber-local scope value; nested scopes merge."
  [scope-key scope-value thunk]
  (.run scope-als (assoc (current-scope) scope-key scope-value) thunk))

(defn- run-in-scope
  "Run `thunk` with `scope-key` true in this fiber; nested scopes merge."
  [scope-key thunk]
  (run-with-scope-value scope-key true thunk))

(defn with-configuration
  "Run one operation with its already-acquired immutable config singleton.

   Error instrumentation and process rejection handlers are cross-cutting and
   cannot receive application arguments. They inherit this exact map through
   the existing error AsyncLocalStorage scope; no database read or config cache
   occurs here."
  {:malli/schema [:=> [:cat :seon.config/singleton fn?] :any]}
  [configuration thunk]
  (run-with-scope-value :seon.error.scope/configuration configuration thunk))

(defn- persist!
  "Fire-and-forget transact of error `entities`; re-buffers on failure.

   Returns the transact Promise (nil when no conn/hook) so the :crash
   escalation can sequence its exit AFTER the write settles. Never
   throws, never awaited by callers."
  [entities]
  (let [tx! (:seon.error/transact! @!db-hooks)
        p   (when tx!
              (try
                (run-in-scope :seon.error.scope/persist? #(tx! entities))
                (catch :default _ nil)))]
    (if (and p (fn? (.-then p)))
      (-> p
          (.then (fn [{ok? :seon.db/ok?}]
                   (when-not ok? (run! buffer! entities))))
          (.catch (fn [_] (run! buffer! entities))))
      (do (run! buffer! entities) nil))))

(defn- branch-head-now
  "Complete Proximum branch head via the injected hook, or nil."
  []
  (when-let [f (:seon.error/branch-head @!db-hooks)]
    (try
      (let [branch-head (f)]
        (when (schema/valid-candidate-value? ::branch/head branch-head)
          branch-head))
      (catch :default _ nil))))

(def ^:private branch-head->error-attr
  {::branch/store-id ::store-id
   ::branch/name ::branch-name
   ::branch/commit-id ::commit-id
   ::branch/basis-t ::basis-t})

(defn- branch-head-error-attrs
  [branch-head]
  (into {}
        (map (fn [[branch-head-attr error-attr]]
               [error-attr (get branch-head branch-head-attr)]))
        branch-head->error-attr))

(schema/register!
  ::recorded-branch-head-response
  [:or
   ::branch/head
   [:map [::message ::message]
    [::kind :keyword]]])

(defn recorded-branch-head
  "Canonical Proximum branch head from one persisted or in-memory error.

   Old or partial rows return an error value; no current branch or commit is
   guessed from a bare basis transaction."
  {:malli/schema [:=> [:cat :map] ::recorded-branch-head-response]}
  [error]
  (let [branch-head (into {}
                          (map (fn [[branch-head-attr error-attr]]
                                 [branch-head-attr (get error error-attr)]))
                          branch-head->error-attr)]
    (if (schema/valid-candidate-value? ::branch/head branch-head)
      branch-head
      {::message "error has no complete recorded Proximum branch head"
       ::kind :missing-branch-head})))

(defn- datom-projection
  "The EDN-safe datom entity for an envelope — bounded strings, reified
   frames, NO live objects (`:seon.error/raw`, malli Schema leafs)."
  [envelope]
  (let [{:seon.error/keys [fault store-id branch-name commit-id basis-t
                           stack frames args-edn data kind]} envelope
        ;; The DEEPEST real cause, not cljs.js's top wrapper ("ERROR") —
        ;; this string is what the SEON-CORE-FAULT marker prints and what
        ;; seon.agent.debug/errors lists.
        message (deepest-message envelope)
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
      kind           (assoc :seon.error/kind kind)
      store-id       (assoc ::store-id store-id)
      branch-name    (assoc ::branch-name branch-name)
      commit-id      (assoc ::commit-id commit-id)
      basis-t        (assoc ::basis-t basis-t)
      stack          (assoc :seon.error/stack stack)
      (seq frames)   (assoc :seon.error/frames frames)
      args-edn       (assoc :seon.error/args-edn args-edn)
      data-edn       (assoc :seon.error/data-edn data-edn))))

;; ============================================================
;; Ambient scope brackets — ONE fiber-local mechanism, two thin faces.
;; AsyncLocalStorage crosses Promise/await hops without leaking between
;; concurrent agents. Faces:
;;   [[expecting-core-fault!]] — TEST-side expected-fault marker.
;;   [[dev-eval!]]             — the dev/MCP REPL conduit's CALLER scope.
;; ============================================================

(defn expecting-a-core-fault?
  "True while inside an [[expecting-core-fault!]] bracket."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (in-scope? :seon.error.scope/expecting-core-fault?))

(defn expecting-core-fault!
  "TEST bracket: mark `:core` faults provoked inside `thunk` as EXPECTED.

   A deliberately-provoked `:core` fault (an error-path fixture that proves
   graceful degradation) still WRITES its datom, but [[escalate!]] prints
   the DISTINCT `SEON-EXPECTED-CORE-FAULT` marker instead of
   `SEON-CORE-FAULT`, so bin/test-cljs's gate does not count it, and the
   `:crash` dial does NOT exit. Async-safe: work spawned by `thunk` inherits
   the marker through its Promise/await hops, while unrelated fibers do not.
   Returns `thunk`'s value unchanged. A NEW fault-provoking test that FORGETS
   the bracket trips the gate — the intended forcing function; there is
   deliberately no blanket suppression."
  {:malli/schema [:=> [:cat fn?] :any]}
  [thunk]
  (run-in-scope :seon.error.scope/expecting-core-fault? thunk))

(defn in-dev-eval?
  "True while a dev/MCP REPL form (or its returned Promise) is running."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (in-scope? :seon.error.scope/dev-eval?))

(defn dev-eval!
  "CALLER-scope bracket around one dev/MCP REPL-submitted form.

   Wrapped around the ONE dev-eval choke point (`js/SHADOW_NODE_EVAL`,
   patched in `seon.client` — both Shadow nREPL entry paths funnel through
   it) so `seon.instrument/wrapper-fault` can read [[in-dev-eval?]]: an
   INPUT-contract violation a dev eval provokes on a core fn is the
   CALLER's mistake → `:agent` population (recorded, never escalates —
   dev probing must not crash the pod), while a genuine internal `:core`
   bug exposed by the same eval still escalates per the dial. Covers
   Promise settlement and the Promise's `unhandledRejection` callback through
   Node async context propagation. Detached async work inherits the scope it was
   spawned in; unrelated fibers never do."
  {:malli/schema [:=> [:cat fn?] :any]}
  [thunk]
  (run-in-scope :seon.error.scope/dev-eval? thunk))

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
  (let [expected? (expecting-a-core-fault?)
        configuration (:seon.error.scope/configuration (current-scope))
        policy (config/on-core-error configuration)]
    (js/console.error
      (str (if expected? "SEON-EXPECTED-CORE-FAULT" "SEON-CORE-FAULT") " "
           (:seon.error/message projection)
           (when-let [data-edn (:seon.error/data-edn projection)]
             (str " " data-edn))
           (when-let [basis-t (::basis-t projection)]
             (str " @basis-t=" basis-t))))
    (when (and (not expected?) (= :crash policy))
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
   being called), the complete catch-site Proximum branch head when a database
   session is
   live, parsed
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
          (and (in-scope? :seon.error.scope/persist?)
               (= 'seon.db/transact!
                  (get-in envelope [:seon.error/data :seon.error.malli/fn-sym])))
          args-edn (get-in envelope [:seon.error/data :seon.error/args-edn])
          envelope (cond-> (assoc envelope :seon.error/fault fault)
                     :always  (as-> m (if-let [branch-head (branch-head-now)]
                                        (merge m (branch-head-error-attrs branch-head)) m))
                     args-edn (assoc :seon.error/args-edn args-edn)
                     :always  (as-> m (if-let [fs (some-> (:seon.error/stack m)
                                                          parse-frames)]
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
