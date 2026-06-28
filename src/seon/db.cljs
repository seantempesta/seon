(ns seon.db
  "Database access. This is the only API you need to read, write, and
   react to the database.

   ## Your universe is one connection

   You have exactly ONE database. `seon.db/*conn*` is bound for you
   before your code runs; never thread it through your own calls, never
   open another conn. Every fn here defaults `:seon.db/conn` to
   `*conn*`. Alias `seon.db` as `db` and write `::db/foo`:

     (require '[seon.db :as db])
     (db/query     {::db/query '[:find ?n :where [_ ::name ?n]]})
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   Every map key in / out of seon.db is fully namespaced under
   `:seon.db/*` — that's what lets one Datalog query join the data in
   the database to the functions that operate on it.

   ## Register schemas before you transact

   `transact!` refuses any tx touching an attribute it doesn't
   recognize — register first:

     (require '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   `:db/*` system attributes bypass the gate. Vector tuples
   (`[:db/add e a v]`) get attribute checks only; entity maps get full
   Malli value validation.

   ## Reads are synchronous, writes return a Promise

   `query`, `pull`, `entity` resolve against the current db value
   (`@*conn*`) — compose them in straight-line code. `transact!` is
   `^:async`; await it and you get an ENVELOPE, never a throw:

     (let [{::db/keys [ok? tx-report error]}
           (await (db/transact! {::db/tx-data [...]}))]
       (if ok? (handle-success tx-report) (handle-failure error)))

   ## Reactions: listen!/unlisten! by key

   `listen!` installs a tx-listener under a key. Distinct keys coexist
   and each receives every tx-report; the same key replaces; `unlisten!`
   retracts by key. The handler gets one rich map (see [[listen!]])
   including `:seon.db/db`, the exact post-commit db value — no reaching
   back to `*conn*`, no stale reads.

   The canonical reaction is the agent's own wake-up: a listener over
   newly-added `:seon.agent.message/to` datoms targeting me (from ≠ me, so my
   own replies never re-trigger me; agent↔agent chains are bounded by
   `:seon.agent.message/hops`). `seon.trigger/register!` is the data-driven
   layer over this primitive — triggers persisted as DB entities."
  (:require
    [clojure.string :as str]
    [cljs.reader :as reader]
    [datahike.api :as d]
    [datahike.constants :as dconst]
    [datahike.db.interface :as dbi]
    [datahike.impl.entity :as dentity]
    [seon.db.internal :as internal]
    [seon.schema :as schema]))

;;; ──────────────────────────────────────────────────────────────────────
;;; Datalog cheat sheet — the minimal idiom set. Copy a shape, swap attrs.
;;; (`db` is OMITTED everywhere — it auto-injects from *conn*.)
;;;
;;; FIND shapes — pick by what you want back:
;;;   relation    [:find ?n :where [?e ::name ?n]]            ;=> #{["A"] ["B"]}
;;;   scalar  `.` [:find (count ?e) . :where [?e ::name]]     ;=> «one scalar — a count»
;;;   collection  [:find [?n ...] :where [?e ::name ?n]]      ;=> ["A" "B"]
;;;   tuple       [:find [?n ?r] :where [?e ::name ?n] [?e ::rank ?r]] ;=> ["A" 1]
;;;
;;; PREDICATE — filter inside :where:  [(> ?l 400)]
;;;   [:find ?s :where [?e ::doc ?d] [(count ?d) ?l] [(> ?l 400)] [?e ::sym ?s]]
;;;   (binding-expr `[(count ?d) ?l]` binds, predicate `[(> ?l 400)]` filters)
;;;
;;; :in PARAM — inputs come AFTER the query (db is implicit, $ is first):
;;;   (query '[:find ?n :in $ ?min :where [?e ::rank ?r] [(>= ?r ?min)] [?e ::name ?n]] 5)
;;;
;;; REF-JOIN — a ref attr stores an EID, not the target's value. To match by
;;; the target's name, JOIN through it; do NOT put the keyword in the ref slot:
;;;   GOOD [:find (count ?e) . :where [?e :seon.fn/ns ?n] [?n :seon.ns/name :seon.db]]
;;;   BAD  [:find ?e :where [?e :seon.fn/ns :seon.db]]   ;THROWS "Nothing found for entity id :seon.db"
;;;
;;; GROUPED AGGREGATE — pull the group's name in the SAME query (group var must
;;; be a NAME, not a ref-eid, or you can't read the result):
;;;   [:find ?nm (count ?t) :where [?t :seon.test/ns ?n] [?n :seon.ns/name ?nm]]
;;;   ;=> «set of [ns-name count] tuples»   (then sort/max in Clojure)
;;;
;;; LOOKUP-REF — address an entity by an identity attr instead of a raw eid.
;;; The value must be the STORED type. :seon.fn/sym is a :string, so use the
;;; STRING, never a quoted symbol ('seon.db/query THROWS String-vs-Symbol):
;;;   (pull '[*] [:seon.fn/sym "seon.db/query"])
;;;
;;; UPSERT — re-transacting an entity with the SAME identity-attr value
;;; UPDATES it in place; it does NOT create a duplicate. Add/overwrite a
;;; field on an existing entity by addressing it via a lookup-ref:
;;;   GOOD (transact! [{:my.kb.doc/id "d1" :my.kb.doc/title "New Title"}])
;;;        ;; d1 already exists → :title is updated, no second :id "d1" row
;;;   (omit attrs you don't want changed; absent ≠ retract — see retract above)
;;;
;;; Results are CLIPPED (~50 rows) for context. Want only a number? Use
;;; (count …)/aggregate, not a list. Empty #{} on a query that should match?
;;; The attr keyword is almost certainly misspelled — copy it exactly from a
;;; rendered ns source or (keys (installed-schema @*conn*)).
;;;
;;; REPORT WHAT YOU COMPUTED. Every `;=>` below is a SHAPE, not an answer —
;;; the numbers belong to a different db than yours. State only the value your
;;; LAST eval returned; if a count matters, re-eval and read it back. Never
;;; quote a number you remember or saw in source/an example.
;;; ──────────────────────────────────────────────────────────────────────

;; ---------------------------------------------------------------------------
;; Schemas — every request/response shape, registered at namespace load.
;; ---------------------------------------------------------------------------

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
;; LOAD-BEARING, registered early on purpose: `::transact-request` (just below)
;; references `:seon.db/conn`, and the schema load-order guard needs the referent
;; registered first. This is the CANONICAL `:seon.db/conn` registration; the
;; db/conn handle block further down registers only `:seon.db/db`. Do NOT "dedup"
;; this away — removing it breaks cold pod boot (the suite won't catch it; tests
;; reload into a warm registry).
(schema/register! ::conn :any)
(schema/register! ::tx-meta :map)   ; positional 3-arity convenience slot
(schema/register! ::return-report? :boolean)

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts           {:optional true} ::opts]
   [::conn           {:optional true} ::conn]
   ;; Escape hatch: include the raw datahike tx-report at
   ;; `::tx-report` in the success envelope. OFF by default — the
   ;; agent value stays the compact data summary.
   [::return-report? {:optional true} ::return-report?]])

(schema/register!
  ::error
  [:map
   [:seon.error/message :string]
   [:seon.error/data    {:optional true} :map]
   [:seon.error/ex-data {:optional true} :map]
   [:seon.error/stack   {:optional true} :string]
   [:seon.error/cause   {:optional true} :map]
   [:seon.error/raw     {:optional true} :any]
   [:seon.error/truncated {:optional true} :boolean]])

;; The success envelope is COMPACT DATA: a small summary the agent
;; reads as a value, never the raw datahike report (no `:db-before`/
;; `:db-after` db-value echo, no full per-datom `:tx-data`). `::tempids`
;; is load-bearing — callers resolve tempid→eid. The raw report rides at
;; `::tx-report` ONLY when the request set `::return-report? true`.
(schema/register!
  ::transact-response
  [:or
   [:map
    [::ok?        [:= true]]
    [::tempids    [:map-of :any :int]]
    [::tx         :int]
    [::tx-count   :int]
    [::added      :int]
    [::retracted  :int]
    ;; Escape hatch — present only under `::return-report? true`. `:any`
    ;; because the raw report carries datahike db-value handles (a
    ;; third-party boundary; the no-:any rule's documented exception).
    [::tx-report  {:optional true} :any]]
   [:map
    [::ok?       [:= false]]
    [::error     ::error]
    ;; When the core translated a cryptic datahike message into a
    ;; guiding one, the ORIGINAL message is preserved here verbatim.
    [::raw-error {:optional true} :string]]])

(schema/register!
  ::query-request
  [:map
   [::query :any]
   [::args  {:optional true} [:vector :any]]
   [::db    {:optional true} :any]
   [::conn  {:optional true} ::conn]])

(schema/register!
  ::pull-request
  [:map
   [::pull-pattern :any]
   [::ref          :any]
   [::db           {:optional true} :any]
   [::conn         {:optional true} ::conn]])

(schema/register!
  ::entity-request
  [:map
   [::ref  :any]
   [::db   {:optional true} :any]
   [::conn {:optional true} ::conn]])

;; The ONE canonical "a datahike db value" shape — referenced by every
;; positional :db slot below (shared-shape rule; never inline a map
;; check). `map?` is a malli DEFAULT-REGISTRY predicate schema — its
;; form is the bare symbol, reconstructed by registry LOOKUP, never
;; eval — so it satisfies the pure-data platform law (registered forms
;; must not embed fn objects; see seon.render.live-tile) while staying
;; honest about the value being a datahike runtime handle, not a
;; seon-authored map.
;; QUOTED — unquoted `map?` would pass the fn OBJECT (the exact poison
;; the law bans); the quoted symbol is what the registry resolves.
(schema/register! ::db-val 'map?)

;; Datahike db snapshot + conn handle — both opaque to validation
;; (genuinely runtime-opaque values; `:any` is the canonical Malli idiom
;; for "I am a runtime handle, validate by presence only"). Registered
;; HERE — `:seon.db/*` keywords live in their owning code ns — so they
;; are available the moment `seon.db` loads, before any ns that references
;; them (e.g. `seon.warn/check-request`'s `:seon.db/db` slot). `::db-val`
;; (`'map?`) above is the STRICTER positional-arg shape; these looser
;; `:any` handles are for request/response map slots that just carry a
;; runtime db through. (`:seon.db/conn` is registered EARLIER — `::transact-request`
;; references it and loads before this block — so only `:seon.db/db` is here.)
(schema/register! :seon.db/db   :any)

(schema/register!
  ::datom
  [:map
   [::e      :int]
   [::a      :keyword]
   [::v      :any]
   [::tx     :int]
   [::added? :boolean]])

(schema/register!
  ::handler-input
  [:map
   [::tx-report  :any]
   [::db         :any]
   [::db-before  :any]
   [::datoms     [:vector ::datom]]
   [::attr-index [:map-of :keyword [:vector ::datom]]]
   [::trigger    {:optional true} :any]])

(schema/register!
  ::listen-request
  [:map
   ;; QUOTED `fn?` = malli default-registry predicate schema (pure-data
   ;; form, registry lookup — never `[:fn ...]` or a bare fn object in
   ;; a register! form).
   [::handler 'fn?]
   [::key     {:optional true} :any]
   [::conn    {:optional true} ::conn]])

(schema/register!
  ::listen-response
  [:map [::key :any]])

(schema/register!
  ::unlisten-request
  [:map
   [::key  :any]
   [::conn {:optional true} ::conn]])

(schema/register!
  ::unlisten-response
  [:map [::ok? :boolean]])

;; Tx-meta attrs (v1.md §2.3) — the causality bundle auto-merged into
;; every tx (see [[with-tx-context]]). Id scalars reference the canonical
;; :seon.db/id shape registered in seon.schema.
(schema/register! ::agent-id        :seon.db/id)
(schema/register! ::session-id      :seon.db/id)
(schema/register! ::turn-id         :seon.db/id)
(schema/register! ::eval-id         :seon.db/id)
(schema/register! ::origin          [:enum :user :agent :system :replay :core-seed :test-run])
(schema/register! ::replay?         :boolean)
(schema/register! ::resume-marker?  :boolean)

;; ---------------------------------------------------------------------------
;; ID generation
;; ---------------------------------------------------------------------------

(def ^:private id-letters
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- id-pad-2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn new-id!
  "Fresh 14-char LLM-readable id, `<3-letter-random>-<YYMMDDHHmm>`.
   USE THIS for every id you store — id attrs are `[:string {:min 14
   :max 14}]`, so a hand-written string fails validation. Datahike's
   tx-id remains the canonical creation order for sub-minute sorting.

     (db/new-id!)   ;=> \"Kpx-2605232138\"
     (db/transact! {::db/tx-data [{::my-id (db/new-id!) ::title \"…\"}]})"
  {:malli/schema [:=> [:cat] :seon.db/id]}
  []
  (let [d        (js/Date.)
        time-str (str (id-pad-2 (mod (.getFullYear d) 100))
                      (id-pad-2 (inc (.getMonth d)))  ; JS month 0-based
                      (id-pad-2 (.getDate d))
                      (id-pad-2 (.getHours d))
                      (id-pad-2 (.getMinutes d)))
        rand-str (apply str (repeatedly 3 #(nth id-letters (rand-int 52))))]
    (str rand-str "-" time-str)))

(defn id->time-str
  "Extract the YYMMDDHHmm portion of an id for time sorting /
   comparison. Returns nil if the id doesn't match the expected shape."
  {:malli/schema [:=> [:catn [::id [:maybe :string]]] [:maybe :string]]}
  [id]
  (when (and (string? id) (= 14 (count id)) (= \- (nth id 3)))
    (subs id 4)))

;; ---------------------------------------------------------------------------
;; The agent's universe + fiber-local context scopes
;; ---------------------------------------------------------------------------

(defonce ^{:dynamic true
           :doc "The runtime's datahike connection. Bound at session start; never
   threaded through agent call sites. Reads default to `@*conn*` (a db
   value); writes route through this conn's writer. All sessions for
   the same user share this conn — sessions are entities in it, not
   partitions of it.

   `defonce`, NOT `def`, is load-bearing: the conn is `set!` onto the
   root at boot, and a reload of seon.db must NOT wipe it back to nil —
   that orphans the live agent. Both reload paths re-evaluate top-level
   forms (shadow hot-reload AND a manual `(require … :reload)`); `exists?`
   makes defonce a no-op on every reload after the first, so the bound
   conn survives. (seon.client/rearm-user-triggers! also re-asserts it on
   `^:dev/after-load` as belt-and-suspenders.)"}
  *conn*
  nil)

(defn current-tx-context
  "The active tx-context map, or nil outside a [[with-tx-context]]
   scope. Fiber-local across awaits (AsyncLocalStorage), safe under
   concurrent agents. Auto-merged into every `transact!`'s `:tx-meta`;
   explicit call-site `:tx-meta` keys win per-key."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (internal/current-tx-context))

(defn current-agent-id
  "The active agent-id (string), or nil outside a [[with-agent]] scope.
   Fiber-local across awaits. The standard accessor for any code that
   needs to know whose universe it's running in.

     (db/current-agent-id)   ;=> \"iCg-2606101519\"   (your own id)"
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (internal/current-agent-id))

;; Slot shapes for the two scope fns below (named-positional :catn slots
;; reference a registered shape). `::thunk` is the 0-arg fn run within the
;; scope — an opaque closure, hence :any.
(schema/register! ::thunk      :any)
(schema/register! ::tx-context :map)

(defn with-agent
  "Establish an agent-id scope for the dynamic extent of `f` (a 0-arg
   fn). Inside `f` — including across `await`s and any Promises it
   returns — `(current-agent-id)` returns `agent-id`. Nesting: the
   inner scope wins, the outer restores on exit. The loop sets this for
   you; you rarely call it — your own writes are already tagged.

     (db/with-agent agent-id
       (fn [] (db/transact! {::db/tx-data [...]})))   ; tx tagged with agent-id"
  {:malli/schema [:=> [:catn [::agent-id :string] [::thunk ::thunk]] :any]}
  [agent-id f]
  (internal/run-with-agent agent-id f))

(defn with-tx-context
  "Establish a tx-context for the dynamic extent of `f` (a 0-arg fn);
   nested calls MERGE. Returns whatever `f` returns (context propagates
   across `await` points). Keys are typically the 7 `:seon.db/*`
   tx-meta attrs registered above; any registered scalar attr works.

     (db/with-tx-context
       {::db/origin :agent ::db/agent-id agent-id}
       (fn [] (db/transact! {::db/tx-data [...]})))   ; auto-tagged"
  {:malli/schema [:=> [:catn [::tx-context ::tx-context] [::thunk ::thunk]] :any]}
  [ctx-map f]
  (internal/run-with-tx-context ctx-map f))

;; ---------------------------------------------------------------------------
;; Write path
;; ---------------------------------------------------------------------------

(schema/register! ::cas-ref   :any)   ; lookup-ref or eid (third-party boundary)
(schema/register! ::cas-attr  :keyword)
(schema/register! ::cas-value :any)   ; lookup-ref, eid, or scalar
(schema/register! ::cas-op    [:vector :any])

(defn cas-assert
  "Build a no-op compare-and-swap op (pure DATA) asserting `ref`'s `attr` is
   STILL `value` — `old == new == value`. LEAD a work-tx with this and the tx
   commits IFF the assertion holds; if `attr` moved to another value or was
   retracted the WHOLE tx aborts (`:transact/cas`) and the bundled work is
   rejected — surfacing as the `{::ok? false …}` envelope (errors are values).

   This is the in-tx WORK FENCE: the database, not a pre-read predicate, tells
   the writer it has lost authority. `ref` / `value` may be lookup-refs (they
   resolve against EXISTING entities in the same tx). The canonical fence is
   the agent's run pointer:

     (db/transact!
       {::db/tx-data
        (into [(db/cas-assert [:seon.agent/id id] :seon.agent/run
                              [:seon.agent.run/id run-id])]
              work-tx)})"
  {:malli/schema [:=> [:catn [::cas-ref ::cas-ref] [::cas-attr ::cas-attr]
                             [::cas-value ::cas-value]]
                  ::cas-op]}
  [ref attr value]
  [:db.fn/cas ref attr value value])

(defn ^:async transact!
  "Commit tx-data. Two call shapes:

   - map-in / map-out (preferred):
       (db/transact! {::db/tx-data [{::name \"A\"}]
                      ::db/opts {:tx-meta {…}}   ; optional
                      ::db/conn <conn>})          ; optional, defaults *conn*
   - positional, mirroring datahike `(d/transact! conn tx-data)` — conn
     FIRST and explicit, with a 3-arity tx-meta convenience:
       (db/transact! <conn> [{::name \"A\"}])
       (db/transact! <conn> [{::name \"A\"}] {:source :import})

   Both shapes resolve to the SAME envelope. SAFE BY DEFAULT: this
   never throws into your eval — every failure (bad invocation shape,
   unregistered attr, value fails its schema, datahike commit
   explosion) returns as data. SUCCESS is COMPACT DATA, never the raw
   datahike report:

     {::db/ok? true                ; success — a small data summary:
      ::db/tempids   {…}           ;   tempid→eid map (resolve your refs)
      ::db/tx        <tx-id>       ;   the committed tx (max-tx)
      ::db/tx-count  <n>           ;   datoms in the tx
      ::db/added <n> ::db/retracted <m>}
     {::db/ok? false ::db/error <error map>}             ; failure
     ;; + ::db/raw-error <original message> when the core
     ;; translated a cryptic datahike error into a guiding one

   Pass `::db/return-report? true` to ALSO get the raw datahike report at
   `::db/tx-report` (escape hatch — needs `:db-after`/`:db-before`); the
   default omits it so the agent value stays small.

   The error's `:seon.error/data` carries `:seon.error/kind` —
   `:user-input` (fix tx-data and retry) vs `:core-bug` (the pod
   survived; report it, don't retry blindly).

   Before committing it validates shape, attrs, and values; installs
   datahike schema for any newly-registered attr; and auto-merges the
   active [[with-tx-context]] / [[with-agent]] context into `:tx-meta`.

   Worked examples — REGISTER your attrs first, then transact (every key
   namespaced). NO `await`: an `^:async` call is auto-awaited for you, so
   you get the ENVELOPE directly — writing `await` is an error.

     ;; register what these examples store (an identity attr to upsert by,
     ;; plus a plain field) — `::foo` here is your own home-ns kind:
     (schema/register! ::doc-id [:string {:seon.db/identity true}])
     (schema/register! ::title :string)

     ;; ADD — and ALWAYS check the envelope: an eval can succeed yet the
     ;; write did NOT happen (ok? false). Read it.
     (let [{::db/keys [ok? error]}
           (db/transact! {::db/tx-data [{::doc-id \"d1\" ::title \"Intro\"}]})]
       (if ok? :saved error))

     ;; UPSERT BY IDENTITY — same identity value ⇒ same entity, no
     ;; duplicate. OMITTED keys are LEFT UNCHANGED (not cleared):
     (db/transact! {::db/tx-data [{::doc-id \"d1\" ::title \"Intro v2\"}]})

     ;; CLEAR one field — explicit retract, NOT omission:
     (db/transact! {::db/tx-data [[:db/retract [::doc-id \"d1\"] ::title]]})
     ;; verify by read-back — the title is gone, so this returns no rows:
     (db/query {::db/query '[:find ?t :where [?e ::doc-id \"d1\"] [?e ::title ?t]]})

     ;; DELETE the whole entity:
     (db/transact! {::db/tx-data [[:db.fn/retractEntity [::doc-id \"d1\"]]]})

     ;; LINK new entities in ONE tx via shared tempid strings (lookup-refs
     ;; do NOT resolve against not-yet-committed entities). ::author is a
     ;; REF, so the tempid \"p1\" in its slot resolves to the new person:
     (schema/register! ::person-id [:string {:seon.db/identity true}])
     (schema/register! ::author :seon.db/ref)
     (db/transact! {::db/tx-data [{:db/id \"p1\" ::person-id \"alice\"}
                                  {::doc-id \"d2\" ::author \"p1\"}]})"
  ;; Opted OUT of instrumentation — listed in `seon.instrument/skip-syms`.
  ;; SAFE BY DEFAULT means a bad invocation shape returns an error ENVELOPE
  ;; (`assert-invocation-shape!`), never throws; an instrumentation throw on
  ;; bad input would break that tested contract. The opt-out lives in
  ;; skip-syms (a FQ-symbol set) because the analyzer strips schema/metadata
  ;; markers. This schema stays the discoverable contract; guards enforce.
  {:malli/schema
   [:function
    [:=> [:cat ::transact-request] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data]] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data] [::tx-meta ::tx-meta]]
         ::transact-response]]}
  [& call-args]
  (try
    (let [arg (internal/normalize-transact-args call-args)]
      (internal/assert-invocation-shape! arg)
      ;; AWAIT is load-bearing: rejections must resolve to the envelope.
      (await (internal/transact!* (update arg ::conn #(or % *conn*)))))
    (catch :default e
      (internal/commit-error-envelope e))))

;; ---------------------------------------------------------------------------
;; Read path — synchronous over a db value. Each op has a map-in arity
;; AND a datahike-shaped positional arity (dispatch is by arg count; the
;; positional db slot is REQUIRED and explicit — no ambient *conn*).
;; ---------------------------------------------------------------------------

(declare assert-known-query-attrs!)

(defn query
  "Run a Datalog query. Two call shapes:

   - map-in:  (db/query {::db/query '[:find ?n :where [?e ::name ?n]]
                         ::db/db <db> | ::db/conn <conn>   ; default *conn*
                         ::db/args [...]})                  ; extra :in inputs
   - positional, mirroring datahike `(d/q query db & inputs)`:
       (db/query '[:find ?n :where [?e ::name ?n]] <db>)
       (db/query '[:find ?n :in $ ?t :where …] <db> \"Alice\")
   - positional, db OMITTED — auto-injects the db from `*conn*`
     (the read-side sibling of [[transact!]]'s auto-conn form):
       (db/query '[:find ?n :where [?e ::name ?n]])
       (db/query '[:find ?n :in $ ?t :where …] \"Alice\")
     The second arg is the explicit db only when it IS a db value
     (`internal/db-value?`); otherwise it's the first `:in` input.

   Worked examples (db omitted; see the cheat sheet at top of ns for the
   full idiom set):

     ;; scalar count — when you only need a number, COUNT, don't list
     ;; (results are clipped ~50 rows). The `;=>` is a SHAPE; report the
     ;; number YOUR eval returns, not the one written here:
     (db/query '[:find (count ?e) . :where [?e :seon.fn/sym]])  ;=> «a scalar count»
     ;; CLIPPED results — when a render shows a banner like «N rows; showing
     ;; first 50, +M more clipped», that N IS the total; READ it. Do NOT
     ;; recount the printed rows, and do NOT re-narrow the query to fit. Need
     ;; only the count? COUNT in the query (above), don't list-then-count.
     ;; registered-schema count — ONE :seon.schema/key row per registered
     ;; schema; this IS the count of registered schemas. Read it back live:
     (db/query '[:find (count ?e) . :where [?e :seon.schema/key]]) ;=> «a scalar count»
     ;; collection — one value per row:
     (db/query '[:find [?n ...] :where [?e :seon.ns/name ?n]]) ;=> «vector of ns-name keywords»
     ;; predicate + binding-expr:
     (db/query '[:find ?s :where [?e :seon.fn/doc ?d] [(count ?d) ?l]
                                 [(> ?l 400)] [?e :seon.fn/sym ?s]])
     ;; REF-JOIN — :seon.fn/ns is a ref (stores an eid); match the target
     ;; by joining through its name, NOT by putting the keyword in the slot:
     (db/query '[:find (count ?e) . :where [?e :seon.fn/ns ?n]
                                           [?n :seon.ns/name :seon.db]]) ;=> «a scalar count»
     ;;   (the keyword form [?e :seon.fn/ns :seon.db] THROWS.)
     ;; GROUPED AGGREGATE with the name pulled in the SAME query, so the
     ;; group is readable (a bare ref-eid is not):
     (db/query '[:find ?nm (count ?t)
                 :where [?t :seon.test/ns ?n] [?n :seon.ns/name ?nm]])
     ;;   ;=> «set of [ns-name count] tuples»   then (sort-by second > …) in Clojure

   GUARDED against silent typos (the sibling of [[pull]]'s guard): a
   `:where` clause naming an attribute that is neither installed on
   the db nor registered in seon.schema throws a legible error naming
   the attr(s) and the fix, instead of silently returning #{}.
   Registered attrs with no data yet behave exactly as datahike
   defines (empty result / get-else default)."
  ;; Pure-variadic body so CLJS malli.instrument wraps every arity.
  ;; Positional arities overlap (query[+db?][+inputs?]) so they can't be
  ;; enumerated as distinct fixed `:=>` arities (Malli would throw
  ;; ::duplicate-arities). Encoding: arity-1 accepts EITHER the request
  ;; map OR a bare query (vector / raw map-form datalog / string), since
  ;; `(query '[…])` (db omitted, no inputs) is a 1-arg call; arity ≥2 is
  ;; the varargs `:=>` (`[:+ :any]` forces ≥1 trailing arg, so it never
  ;; collides with arity-1). The body distinguishes request-map vs bare
  ;; query (`contains? ::query`) and explicit-db vs `:in` input
  ;; (`internal/db-value?`).
  {:malli/schema
   [:function
    [:=> [:cat [:or [:vector :any] :map :string]] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::rest [:+ :any]]] :any]]}
  [& args]
  (let [a0 (first args)]
    (if (and (map? a0) (contains? a0 ::query))
      ;; map-in request: a map that CONTAINS ::query
      (let [{::keys [query args db conn] :or {conn *conn* args []}} a0
            db (or db @(internal/resolve-conn conn))]
        (assert-known-query-attrs! db query)
        (apply d/q query db args))
      ;; positional: a0 IS the query (vector / string / raw map-form query).
      ;; If the next arg is a db VALUE it's the explicit db; otherwise the
      ;; db auto-injects from *conn* and all trailing args are :in inputs.
      (let [q a0]
        (if (internal/db-value? (second args))
          (let [[_ db & inputs] args]
            (assert-known-query-attrs! db q)
            (apply d/q q db inputs))
          (let [db     @(internal/resolve-conn *conn*)
                inputs (rest args)]
            (assert-known-query-attrs! db q)
            (apply d/q q db inputs)))))))

(defn installed-schema
  "The datahike schema map actually INSTALLED on `db` — attrs the conn
   has seen, keyed by ident keyword. FilteredDB-safe and nil-safe.

   THE TRAP this fn exists to gate: datahike installs an attr's schema
   lazily, at the attr's FIRST `transact!` — `seon.schema/register!`
   alone only teaches the Malli registry. Querying or pulling an attr
   the conn has never installed THROWS (`:transact/schema`,
   resolve-datom: \"Bad entity attribute … not defined in current
   schema\") under `:schema-flexibility :write`. So any code that
   names an attr in a `d/datoms` scan, a Datalog where-clause, or an
   explicit pull pattern must EITHER be sure data has landed OR gate
   on `(contains? (db/installed-schema db) <attr>)` — load-bearing,
   not defensive fluff. ([[pull]] gates its own patterns with this
   automatically.)

   The wrapper db values — FilteredDB (the inspector's per-agent view),
   AsOfDB/SinceDB/HistoricalDB (the time-travel values) — don't
   implement ILookup, so `(:schema db)` THROWS on them. Schema is
   conn-level (a filter or time-point can't change which attrs are
   installed), and every db type implements the `dbi/-schema` protocol
   method, which reads through to the underlying current db. Use it
   uniformly instead of the record field — that's what makes an
   explicit-pattern [[pull]] on an as-of/since value see the same
   installed attrs as the current db (otherwise it wrongly judged them
   uninstalled and silently dropped them). Returns `{}` for a
   nil/schema-less db. `:any` input — the db value is a datahike
   runtime handle (third-party boundary).

   This is the \"what attrs exist on this db, exactly?\" tool. It lists
   EVERY installed attr — including REGISTERED-BUT-DATALESS kinds that
   [[store-inventory]] omits (it shows only kinds with live rows). Check
   here before inventing a new attr — a kind you'd reach for may already
   exist with zero rows:

     (filter #(= \"seon.agent.todo\" (namespace %))
             (keys (db/installed-schema @db/*conn*)))
     ;;=> (:seon.agent.todo/id :seon.agent.todo/title :seon.agent.todo/status …)
     ;;   — registered, queryable, just no rows yet. Reuse it; don't fork."
  {:malli/schema [:=> [:catn [::db :any]] :map]}
  [db]
  (or (when (some? db)
        (try (dbi/-schema db) (catch :default _ nil)))
      {}))

;; --- pull-pattern guard -----------------------------------------------------
;; datahike-cljs throws the cryptic resolve-datom error above when an
;; explicit pull pattern names an attr the conn never installed.
;; [[pull]] guards that boundary; helpers below walk/rewrite patterns.

(defn- pattern-attr
  "The attr keyword an explicit pull-pattern item names: a bare
   keyword, or the head of an attr-with-opts seq like
   `(:attr :limit 10)` / `[:attr :as :x]`. nil for wildcards (`*`),
   recursion markers, and anything else."
  [item]
  (cond
    (keyword? item) item
    (and (sequential? item) (keyword? (first item))) (first item)
    :else nil))

(defn- forward-attr
  "Normalize a reverse-ref attr (`:ns/_attr`) to its forward form —
   the installed schema is keyed by forward idents only."
  [attr]
  (let [n (name attr)]
    (if (str/starts-with? n "_")
      (keyword (namespace attr) (subs n 1))
      attr)))

(defn- system-pull-attr?
  "datahike's own attrs (`:db/id`, `:db/ident`, `:db.*/…`) are exempt
   from schema-presence validation — mirror that exemption."
  [attr]
  (let [a-ns (namespace attr)]
    (and a-ns (or (= a-ns "db") (str/starts-with? a-ns "db.")))))

;; --- query attr guard --------------------------------------------------
;; The sibling of the pull guard below, adapted to what
;; datalog actually does: d/q NEVER throws on
;; an uninstalled attr — a never-installed attr in any clause shape
;; (pattern, get-else, or-join, not) yields the correct empty/default
;; result. So the only failure mode at this boundary is the SILENT
;; one: a typo'd attribute returns #{} and the caller concludes "no
;; data". The guard makes that legible: an attr that is neither
;; installed on the db NOR registered in seon.schema can never match
;; anything and is almost certainly a typo — throw with the fix.
;; Registered-but-uninstalled attrs pass through untouched (datahike's
;; empty/default result is already the honest answer).

(defn- where-clause-attrs
  "Every attribute keyword a `:where` clause names, recursively through
   `not`/`or`/`and`/`or-join`/`not-join` and the `get-else`/`missing?`
   fn clauses. Conservative: anything unrecognized contributes nothing
   (rules, predicates, bindings)."
  [clause]
  (cond
    ;; compound: (not …) (or …) (and …) (or-join [vars] …) (not-join …)
    (seq? clause)
    (let [[op & body] clause]
      (cond
        (contains? '#{not or and} op)
        (mapcat where-clause-attrs body)
        (contains? '#{or-join not-join} op)
        (mapcat where-clause-attrs (rest body))
        :else []))

    (vector? clause)
    (if (seq? (first clause))
      ;; fn/predicate clause — only the attr-naming builtins matter:
      ;; [(get-else $ ?e :attr default) ?x] / [(missing? $ ?e :attr)].
      (let [[f & fargs] (first clause)]
        (if (contains? '#{get-else missing?} f)
          (take 1 (filter keyword? fargs))
          []))
      ;; data pattern [e a v …] — skip a leading src symbol ($/$x);
      ;; the attr is the second slot when it's a keyword.
      (let [items (if (and (symbol? (first clause))
                           (str/starts-with? (str (first clause)) "$"))
                    (rest clause)
                    clause)
            a     (second items)]
        (if (keyword? a) [a] [])))

    :else []))

(defn- query-where-clauses
  "The `:where` clauses of a query in vector or map form; nil for
   string queries (unguarded — third-party passthrough)."
  [q]
  (cond
    (map? q)    (:where q)
    (vector? q) (->> q (drop-while #(not= :where %)) rest seq)
    :else       nil))

(defn- assert-known-query-attrs!
  "Throw the legible typo error when `q` names attrs that are neither
   installed on `db` nor registered in seon.schema (`:db/*` system
   attrs exempt). See the guard comment above — datalog returns a
   SILENT #{} for these, which reads as \"no data\" when the truth is
   \"no such attribute\"."
  [db q]
  (when-let [clauses (query-where-clauses q)]
    (let [named     (distinct (mapcat where-clause-attrs clauses))
          installed (installed-schema db)
          unknown   (->> named
                         (remove system-pull-attr?)
                         (remove #(contains? installed %))
                         (remove schema/registered?)
                         seq)]
      (when unknown
        (let [msg (str "Query names attribute(s) "
                       (pr-str (vec (sort unknown)))
                       " that this database has never seen — not installed "
                       "in the datahike schema and not registered in "
                       "seon.schema, so the query can only return empty. "
                       "Most likely a typo: check spelling against "
                       "(seon.db/installed-schema db). If the attr is new, "
                       "(seon.schema/register! <attr> <type>) and transact "
                       "data first.")]
          (throw (ex-info msg
                          {:seon.error/message msg
                           :seon.error/data
                           {:seon.error/kind :user-input
                            ::missing-attrs  (vec (sort unknown))
                            ::query          q}})))))))

(defn- pull-pattern-attrs
  "Every attr keyword an explicit pull pattern names, recursively
   through map specs (`{:ref-attr [subpattern]}`) and attr-with-opts.
   Wildcards and recursion limits contribute nothing."
  [pattern]
  (letfn [(walk [acc spec]
            (reduce
              (fn [acc item]
                (if (map? item)
                  (reduce-kv
                    (fn [acc k v]
                      (let [acc (if-let [a (pattern-attr k)] (conj acc a) acc)]
                        (if (sequential? v) (walk acc v) acc)))
                    acc item)
                  (if-let [a (pattern-attr item)] (conj acc a) acc)))
              acc spec))]
    (walk #{} pattern)))

(defn- filter-pull-pattern
  "Rewrite `pattern` without the items naming attrs in `drop-set`
   (forward forms). Map-spec entries whose subpattern filters to empty
   are dropped whole (their value could only have pulled nothing)."
  [pattern drop-set]
  (letfn [(drop-attr? [item]
            (when-let [a (pattern-attr item)]
              (contains? drop-set (forward-attr a))))
          (walk [spec]
            (into []
                  (keep (fn [item]
                          (cond
                            (map? item)
                            (let [m (reduce-kv
                                      (fn [m k v]
                                        (cond
                                          (drop-attr? k) m
                                          (sequential? v)
                                          (let [v' (walk v)]
                                            (if (seq v') (assoc m k v') m))
                                          :else (assoc m k v)))
                                      {} item)]
                              (when (seq m) m))
                            (drop-attr? item) nil
                            :else item)))
                  spec))]
    (walk pattern)))

(defn- guarded-pull
  "[[pull]]'s body: d/pull behind the uninstalled-attr guard. Returns
   the pulled map (or nil); throws the legible typo error. nil when
   the guard filters the whole pattern away (no attr could have
   matched ⇒ same result datahike returns for a pattern of
   installed-but-absent attrs)."
  [db pattern ref]
  (let [named       (pull-pattern-attrs pattern)
        installed   (installed-schema db)
        uninstalled (->> named
                         (map forward-attr)
                         (remove system-pull-attr?)
                         (remove #(contains? installed %))
                         distinct)
        {registered   true
         unregistered false} (group-by (comp boolean schema/registered?)
                                       uninstalled)]
    (when (seq unregistered)
      (let [msg (str "Pull pattern names attribute(s) "
                     (pr-str (vec (sort unregistered)))
                     " that this database has never seen — not installed in "
                     "the datahike schema and not registered in seon.schema. "
                     "Most likely a typo: check spelling against "
                     "(seon.db/installed-schema db). If the attr is new, "
                     "(seon.schema/register! <attr> <type>) and transact "
                     "data first — datahike installs attr schema lazily at "
                     "first transact!.")]
        (throw (ex-info msg
                        {:seon.error/message msg
                         :seon.error/data
                         {:seon.error/kind :user-input
                          ::missing-attrs  (vec (sort unregistered))
                          ::pull-pattern   pattern}}))))
    (if (empty? registered)
      (d/pull db pattern ref)
      (let [pattern' (filter-pull-pattern pattern (set registered))]
        (when (seq pattern')
          (d/pull db pattern' ref))))))

(defn pull
  "Pull an entity by ref using a pull pattern. Sync. Returns the pulled
   map, or nil if the ref doesn't resolve.

   - map-in:     (db/pull {::db/pull-pattern '[*] ::db/ref eid})
   - positional, mirroring datahike: (db/pull <db> selector eid)
   - positional, db OMITTED — auto-injects from `*conn*` (arity
     disambiguates): (db/pull selector eid)

   The `ref` is a raw eid OR a LOOKUP-REF `[identity-attr value]` — use
   the lookup-ref so you never hand-find a numeric eid. The value must be
   the attr's STORED type: :seon.fn/sym is a :string, so pass the STRING
   — a quoted symbol THROWS (\"Cannot compare String to Symbol\"):

     (db/pull '[:seon.fn/sym :seon.fn/arglists :seon.fn/doc]
              [:seon.fn/sym \"seon.db/query\"])     ; STRING value, not 'sym
     ;; wildcard everything: (db/pull '[*] [:seon.fn/sym \"seon.db/query\"])
     ;; follow a ref and see the full story of what it points at — pull
     ;; the whole entity AND expand its ref'd owner inline:
     (db/pull '[* {:owner [*]}] id)   ; {:db/id N … :owner {:db/id M …}}

   GUARDED against the lazy-install trap (see [[installed-schema]]):
   datahike installs an attr's schema at its first transact!, and
   raw `d/pull` THROWS a cryptic resolve-datom error when an explicit
   pattern names a never-installed attr. Here, per uninstalled attr:

   - REGISTERED in seon.schema → silently filtered from the pattern.
     Provably equivalent to the result had the attr been installed
     with zero rows (the key would be absent either way), so valid
     pulls are unchanged and nothing is masked — \"no data yet\"
     renders as no data.
   - NOT registered → a legible throw naming the attr(s) and the fix,
     because silently filtering an unknown attr would mask typos.
     (`:db/*` system attrs are exempt, mirroring datahike.)

   Valid pulls — every named attr installed — run exactly as before."
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::selector [:vector :any]] [::eid :any]] :any]
    [:=> [:catn [::db ::db-val] [::selector [:vector :any]] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [pull-pattern ref db conn] :or {conn *conn*}} req
         db (or db @(internal/resolve-conn conn))]
     (guarded-pull db pull-pattern ref)))
  ([selector eid]
   (guarded-pull @(internal/resolve-conn *conn*) selector eid))
  ([db selector eid]
   (guarded-pull db selector eid)))

(defn entity-lazy
  "INTERNAL: look up an entity and return the RAW datahike Entity (lazy,
   map-like). Ref attrs navigate lazily to nested Entities — the render
   hot-path (`seon.agent.ctx.transcript/session-turns` walks agent → sessions →
   turns → evals) depends on this lazy traversal, so it MUST NOT touch.

   Not part of the agent-taught surface — agents call [[entity]] (which
   returns a plain touched map) or [[pull]]. Same call shapes as [[entity]]."
  {:malli/schema
   [:function
    [:=> [:cat [:or ::entity-request :any]] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]
   (if (map? req)
     (let [{::keys [ref db conn] :or {conn *conn*}} req
           db (or db @(internal/resolve-conn conn))]
       (d/entity db ref))
     (d/entity @(internal/resolve-conn *conn*) req)))
  ([db eid]
   (d/entity db eid)))

(defn- touch->map
  "Touch a datahike Entity and return a PLAIN map — `:db/id` plus every
   loaded attr. nil-safe (an unresolved ref → nil). Ref values stay as
   datahike's loaded form (nested Entity / set of Entities), which prints
   as `{:db/id N}` — readable, and the agent drills via the eid + a
   follow-up `entity`/`pull` rather than walking lazily."
  [e]
  (when e
    (dentity/touch e)
    (into {:db/id (:db/id e)} e)))

(defn entity
  "Look up an entity by eid or lookup-ref. Sync. Returns a PLAIN MAP —
   `:db/id` plus every attr on the entity (a TOUCHED snapshot), nil if the
   ref doesn't resolve. The agent reads data, never a lazy datahike handle
   — a raw Entity prints opaquely and re-reads as `[object Object]`. Drill
   into a ref attr with a follow-up `entity`/`pull` on its
   `:db/id`.

   - map-in:     (db/entity {::db/ref [::name \"Alpha\"]})
   - positional, mirroring datahike: (db/entity <db> eid)
   - positional, db OMITTED — a bare eid/lookup-ref auto-injects the
     db from `*conn*`: (db/entity eid)

   `ref` is a raw eid OR a LOOKUP-REF `[identity-attr value]` whose value
   is the attr's STORED type. :seon.fn/sym is a :string ⇒ pass the STRING
   (a quoted symbol THROWS \"Cannot compare String to Symbol\"):

     (db/entity {::db/ref [:seon.fn/sym \"seon.db/transact!\"]})
     ;;=> {:db/id N :seon.fn/sym \"seon.db/transact!\" :seon.fn/arglists \"…\" …}"
  ;; The 1-arg arity accepts EITHER a request map OR a bare eid/lookup-ref
  ;; (auto-inject from *conn*) — one arity-1 `:=>` (the body branches on
  ;; map?); a separate eid-only `:=>` would collide with the request arity.
  {:malli/schema
   [:function
    [:=> [:cat [:or ::entity-request :any]] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]      (touch->map (entity-lazy req)))
  ([db eid]   (touch->map (entity-lazy db eid))))

;; ---------------------------------------------------------------------------
;; Temporal — derive a db VALUE at another point in time. Reads normally run
;; against the db injected from *conn*; these let you make your OWN db value
;; (history / as-of / since) and pass it positionally to query/pull/entity.
;; Datomic/datahike shape: db in, db out.
;; ---------------------------------------------------------------------------

;; A datahike time-point: a tx-id (int), a Date, or a txInstant. `:any`
;; because it is a datahike-domain value, not seon-authored data (the
;; documented third-party-boundary exception to the no-:any rule).
(schema/register! ::time-point :any)

(defn history
  "A db value spanning ALL of time — every assertion AND retraction ever,
   not just the now-true view. Read it with a 5-tuple `:where` so the tx and
   the add/retract flag bind. The db is injected from your one connection;
   omit it:

     (db/query '[:find ?v ?tx ?added
                 :where [?e :seon.ns/name ?v ?tx ?added]]
               (db/history))               ; ?added = true add, false retract

   Pass an explicit db to branch history off a snapshot you already hold."
  {:malli/schema [:function
                  [:=> [:cat] :any]
                  [:=> [:catn [::db ::db-val]] :any]]}
  ([]   (history @(internal/resolve-conn *conn*)))
  ([db] (d/history db)))

(defn as-of
  "A db value as it was AT `t` (a tx-id, Date, or txInstant) — time-travel
   for reads. query/pull/entity against it see only what was true then:

     (db/query '[:find ?title :where [?e ::doc-id \"d1\"] [?e ::title ?title]]
               (db/as-of last-week-tx))    ; db omitted ⇒ your *conn* at t

   2-arity rewinds an explicit db you already hold: (db/as-of db t)."
  {:malli/schema [:function
                  [:=> [:cat ::time-point] :any]
                  [:=> [:catn [::db ::db-val] [::time-point ::time-point]] :any]]}
  ([t]    (as-of @(internal/resolve-conn *conn*) t))
  ([db t] (d/as-of db t)))

(defn since
  "The complement of [[as-of]]: a db value reflecting only datoms added
   AFTER `t`. Diff \"what changed since\" a tx you remembered:

     (db/query '[:find ?e :where [?e ::status :done]] (db/since last-seen-tx))

   2-arity takes an explicit db: (db/since db t)."
  {:malli/schema [:function
                  [:=> [:cat ::time-point] :any]
                  [:=> [:catn [::db ::db-val] [::time-point ::time-point]] :any]]}
  ([t]    (since @(internal/resolve-conn *conn*) t))
  ([db t] (d/since db t)))

;; The two ENDS of a time-travel domain (the `as-of`/`since` `t` range).
;; `basis-t` is the latest tx reflected in a db value — the \"now\" end of a
;; scrubber. `origin-t` is datahike's origin tx (`tx0`) — the floor; the first
;; user tx is `origin-t`+1, so an `as-of` below it is the empty/pre-seed world.
;; Both are valid [[time-point]]s usable directly with `as-of`/`since`.

(def ^{:doc "Datahike's origin tx-id (`tx0`) — the floor of any time-travel
   domain. `(as-of … origin-t)` is the empty world before the first user tx."}
  origin-t dconst/tx0)

(defn basis-t
  "The basis tx-id of a db value — the latest tx it reflects, the \"now\" end
   of a time-travel domain. Omit db ⇒ your `*conn*`'s current basis. A
   [[time-point]] usable directly with `as-of`/`since`."
  {:malli/schema [:function
                  [:=> [:cat] ::time-point]
                  [:=> [:catn [::db ::db-val]] ::time-point]]}
  ([]   (basis-t @(internal/resolve-conn *conn*)))
  ([db] (dbi/-max-tx db)))

;; ---------------------------------------------------------------------------
;; Listeners
;; ---------------------------------------------------------------------------

(defn listen!
  "Install a tx-listener. SAFE BY DEFAULT — handler throws / rejections
   are caught and logged, never crash the pod. `::db/handler` is a fn
   of one map:

     {:seon.db/tx-report   <raw datahike report — escape hatch>
      :seon.db/db          <post-commit db value, ready to query>
      :seon.db/db-before   <pre-commit db value, for change-detection>
      :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                             :seon.db/tx :seon.db/added?} ...]
      :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...] ...}}

   Sync handler return blocks transact (back-pressure); Promise return
   is fire-and-forget. Without `::db/key` a random-uuid is used; the
   same key replaces. Returns `{:seon.db/key <key>}` for [[unlisten!]]."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [{::keys [handler key conn] :or {conn *conn*}}]
  (let [c (internal/resolve-conn conn)
        k (or key (random-uuid))]
    (d/listen c k (internal/wrap-listen-handler k handler))
    {::key k}))

(defn listen-sync!
  "Intent-revealing alias for [[listen!]] (sync handler, back-pressure)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn listen-async!
  "Intent-revealing alias for [[listen!]] (Promise handler, fire-and-forget)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn unlisten!
  "Remove a listener by key. Returns `{:seon.db/ok? true}`. Idempotent —
   unknown keys are a silent no-op."
  {:malli/schema [:=> [:cat ::unlisten-request] ::unlisten-response]}
  [{::keys [key conn] :or {conn *conn*}}]
  (let [c (internal/resolve-conn conn)]
    (d/unlisten c key)
    {::ok? true}))

;; ---------------------------------------------------------------------------
;; Schema-bridge + boot faces (impls in seon.db.internal)
;; ---------------------------------------------------------------------------

(defn malli->datahike-schema
  "Derive datahike attr declarations from seon.schema registrations.
   You normally never need this — `transact!` installs schema for
   registered attrs automatically."
  {:malli/schema [:=> [:catn [::attr-keys [:sequential :keyword]]] [:vector :any]]}
  [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema
  "Datahike schema entries for the 7 `:seon.db/*` tx-meta attrs."
  {:malli/schema [:=> [:cat] [:vector :any]]}
  []
  (internal/tx-meta-datahike-schema))

(defn decode-edn-value
  "Read-side inverse of the bridge's mixed-`:or` EDN-string storage
   (see `seon.db.internal/encode-edn-slot-values`): attrs whose Malli
   form is a mixed-type `:or` (the render slots `:seon.render/ai` /
   `:seon.render/html`) store as pr-str'd EDN strings; this decodes a
   pulled value back to its real shape. Values of other attrs — and
   non-string values from pre-encoding stores — pass through unchanged."
  {:malli/schema [:=> [:catn [::attr :keyword] [::value :any]] :any]}
  [attr v]
  (if (and (string? v) (internal/edn-encoded-attr? attr))
    (try (reader/read-string v)
         (catch :default _ v))
    v))

(defn assert-preconditions!
  "Validate boot preconditions (conn has `:keep-history? true`; tx-meta
   attrs registered). Throws ex-info on failure. Called at agent boot."
  {:malli/schema
   [:function
    [:=> [:cat] :boolean]
    [:=> [:catn [::opts [:map [::conn {:optional true} ::conn]]]] :boolean]]}
  ([] (assert-preconditions! {}))
  ([{::keys [conn] :or {conn *conn*}}]
   (internal/assert-preconditions! conn)))

;; ---------------------------------------------------------------------------
;; Store inventory — what's in the shared store, one query away.
;; ---------------------------------------------------------------------------

(schema/register! ::kind     :keyword)
(schema/register! ::attrs    [:map-of :keyword :int])
(schema/register! ::system?  :boolean)
(schema/register! ::row-ids  [:set :int])
(schema/register! ::kind-set [:set ::kind])
(schema/register! ::inventory-row
  [:map
   [::kind  ::kind]
   [::attrs ::attrs]])
(schema/register! ::kind-count  :int)
(schema/register! ::attr-count  :int)
(schema/register! ::datom-count :int)
(schema/register! ::inventory
  [:map
   [::kinds       [:vector ::inventory-row]]
   [::kind-count  ::kind-count]
   [::attr-count  ::attr-count]
   [::datom-count ::datom-count]])

(defn- row-origin-scan
  "ONE pass over every live datom `[e a tx]` — the provenance facts the
   inventory split needs:
     ::bootstrap-rows — entity ids whose IDENTITY datom (the entity's
       first assertion, min tx) landed under a tx carrying
       `:seon.db/origin :core-seed` (the boot index/seed);
     ::tx-rows — entity ids that ARE transactions (they appear in a
       datom's tx slot) — provenance machinery, not data rows;
     ::pairs — the distinct `[e a]` pairs (datalog results are sets,
       so cardinality-many attrs count each entity once)."
  [db]
  (let [seed-txs (into #{}
                       (map first)
                       (query {::db db
                               ::query '[:find ?tx
                                         :where
                                         [?tx :seon.db/origin :core-seed]]}))
        triples  (query {::db db ::query '[:find ?e ?a ?tx :where [?e ?a _ ?tx]]})
        first-tx (reduce (fn [m [e _ tx]]
                           (update m e #(if % (min % tx) tx)))
                         {} triples)]
    {::bootstrap-rows (into #{}
                            (keep (fn [[e tx]]
                                    (when (contains? seed-txs tx) e)))
                            first-tx)
     ::tx-rows        (into #{} (map (fn [[_ _ tx]] tx)) triples)
     ::pairs          (into #{} (map (fn [[e a _]] [e a])) triples)}))

(defn bootstrap-row-ids
  "Entity ids whose IDENTITY datom (the entity's first assertion) was
   transacted under a tx carrying `:seon.db/origin :core-seed` —
   the rows the boot index/seed minted (compiled core: the
   program-graph `:seon.fn`/`:seon.schema`/`:seon.test`/`:seon.ns`
   index, the soul/kb seed). Everything else is data this cluster
   added AFTER bootstrap. Per-ROW, never per-kind-name: an
   agent-authored `:seon.fn` row is NOT in this set; a boot-indexed
   one is. THE shared provenance derivation — [[store-inventory]]'s
   user/system split, [[core-kinds]], and the inspector's /data
   browser all read this one mechanism."
  {:malli/schema [:=> [:catn [::db ::db-val]] ::row-ids]}
  [db]
  (::bootstrap-rows (row-origin-scan db)))

(defn core-kinds
  "Attr namespaces (keywords) whose `:seon.schema/key` row is a
   BOOTSTRAP row ([[bootstrap-row-ids]]) — kinds the compiled
   core's boot index registered, as opposed to agent-registered
   kinds. Used by [[store-inventory]] for its user-domain-first
   ordering. The 2-arity takes a precomputed bootstrap set so one scan
   can serve multiple consumers."
  {:malli/schema
   [:function
    [:=> [:catn [::db ::db-val]] ::kind-set]
    [:=> [:catn [::db ::db-val] [::bootstrap-rows ::row-ids]] ::kind-set]]}
  ([db] (core-kinds db (bootstrap-row-ids db)))
  ([db bootstrap-rows]
   (into #{}
         (keep (fn [[s k]]
                 (when (contains? bootstrap-rows s)
                   (some-> (namespace k) keyword))))
         (query {::db db
                 ::query '[:find ?s ?k :where [?s :seon.schema/key ?k]]}))))

(defn store-inventory
  "Discovery call: WHICH ATTRIBUTES HOLD DATA in this cluster's store
   RIGHT NOW, so you know what you can query for. Returns a map:

     {:seon.db/kinds       [{:seon.db/kind  :my.kb           ; the attr namespace
                             :seon.db/attrs {:my.kb/question 3   ; attr -> row count
                                             :my.kb/answer   3}}
                            …
                            {:seon.db/kind :seon.eval …}]    ; core kinds last
      :seon.db/kind-count  9     ; distinct kinds (attr namespaces) with data
      :seon.db/attr-count  53    ; distinct attrs with data
      :seon.db/datom-count 124}  ; total entity/attr pairs in scope

   `:seon.db/kinds` is one row per attr NAMESPACE (the kind), each
   carrying every attr of that namespace that has ≥1 live row with its
   entity count. Pure query, not a snapshot — an attr appears the
   moment its first row lands and vanishes when all its rows retract.
   Attrs only REGISTERED (no rows yet) don't show; pair with
   [[installed-schema]] to see every registered attr-namespace.

   DEFAULT scope = data added AFTER bootstrap. Boot-index rows (the
   compiled core's `:seon.fn`/`:seon.schema`/`:seon.ns`/seed, minted
   under a `:core-seed` tx — thousands of datoms) and transaction
   entities are excluded by per-ROW provenance ([[bootstrap-row-ids]]),
   so agent-authored rows count while the boot index does not. Pass
   `{:seon.db/system? true}` for the FULL inventory including the boot
   index. Kinds are ordered user-domain-first ([[core-kinds]]),
   alphabetical within each group.

   Check this BEFORE researching or registering: a kind that exists
   means data you can query — datalog its listed attrs directly (the
   attr names are the exact :where keywords) — and a shape that exists
   must be REUSED, never forked.

   (def inv (seon.db/store-inventory))
   (keys inv)                                ; the section keys
   (count (:seon.db/kinds inv))              ; how many kinds hold data
   (seon.db/query {:seon.db/query            ; then read one
                   '[:find ?q :where [?e :my.kb/question ?q]]})"
  {:malli/schema
   [:function
    [:=> [:cat] ::inventory]
    [:=> [:cat [:map [::db {:optional true} :any]
                     [::conn {:optional true} ::conn]
                     [::system? {:optional true} ::system?]]]
         ::inventory]]}
  ([] (store-inventory {}))
  ([{::keys [db conn system?] :or {conn *conn*}}]
   (let [db (or db @(internal/resolve-conn conn))
         {::keys [bootstrap-rows tx-rows pairs]} (row-origin-scan db)
         sub-kinds (core-kinds db bootstrap-rows)
         counts (reduce (fn [m [e a]]
                          (if (or (system-pull-attr? a) (nil? (namespace a))
                                  ;; default view: post-bootstrap data
                                  ;; rows only — boot-index rows and tx
                                  ;; (provenance) entities are system.
                                  (and (not system?)
                                       (or (contains? bootstrap-rows e)
                                           (contains? tx-rows e))))
                            m
                            (update m a (fnil inc 0))))
                        {} pairs)
         rows   (->> counts
                     (group-by (fn [[a _]] (keyword (namespace a))))
                     ;; User-domain kinds FIRST (consult-first — see
                     ;; docstring), core kinds after; alphabetical within.
                     (sort-by (fn [[ns-kw _]]
                                [(if (contains? sub-kinds ns-kw) 1 0)
                                 (str ns-kw)]))
                     (mapv (fn [[ns-kw attr-counts]]
                             {::kind  ns-kw
                              ::attrs (into (sorted-map) attr-counts)})))]
     {::kinds       rows
      ::kind-count  (count rows)
      ::attr-count  (count counts)
      ::datom-count (reduce + 0 (vals counts))})))
