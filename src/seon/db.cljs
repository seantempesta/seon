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
    [datahike.connector :as connector]
    [datahike.constants :as dconst]
    [datahike.db :refer [AsOfDB]]
    [datahike.db.interface :as dbi]
    [datahike.db.utils :as dbu]
    [datahike.index.interface :as di]
    [datahike.impl.entity :as dentity]
    [malli.core :as m]
    [seon.config :as config]
    [seon.db.coordinate :as db.coordinate]
    [seon.db.id]
    [seon.db.internal :as internal]
    [seon.db.protocol]
    [seon.db.process :as process]
    [seon.error :as error]
    [seon.schema :as schema]))

;;; ──────────────────────────────────────────────────────────────────────
;;; Datalog cheat sheet — the minimal idiom set. Copy a shape, swap attrs.
;;; (`db` is OMITTED everywhere — it auto-injects from *conn*.)
;;;
;;; FIND shapes — pick by what you want back:
;;;   relation    [:find ?n :where [?e ::name ?n]]            ; ⟹ #{["A"] ["B"]}
;;;   scalar  `.` [:find (count ?e) . :where [?e ::name]]     ; ⟹ «one scalar — a count»
;;;   collection  [:find [?n ...] :where [?e ::name ?n]]      ; ⟹ ["A" "B"]
;;;   tuple       [:find [?n ?r] :where [?e ::name ?n] [?e ::rank ?r]] ; ⟹ ["A" 1]
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
;;;   ; ⟹ «set of [ns-name count] tuples»   (then sort/max in Clojure)
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
;;; REPORT WHAT YOU COMPUTED. Every `; ⟹` below is a SHAPE, not an answer —
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
(schema/register! ::coordinate :seon.db.coordinate/coordinate)
(schema/register! ::expected-coordinate ::coordinate)

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts           {:optional true} ::opts]
   [::conn           {:optional true} ::conn]
   ;; Full-head precondition checked by the serialized database writer.
   [::expected-coordinate {:optional true} ::expected-coordinate]
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
    [::coordinate ::coordinate]
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

;; The shape of a Datalog query ITSELF: the standard quoted vector, the
;; raw map form, or a string. ONE registered shape — `::query-request`'s
;; `::query` key and both of [[query]]'s arities reference it (the
;; shared-shape rule; it was inlined three times before).
(schema/register! ::query-form [:or [:vector :any] :map :string])
(schema/register! ::max-work [:int {:min 1}])
(schema/register! ::max-results [:int {:min 1}])
(schema/register! ::max-result-weight [:int {:min 1}])

;; An entity ADDRESS as an agent writes one: a raw eid (int) or a
;; lookup-ref `[identity-attr value]`. THE shape to pass in `::ref` /
;; `::cas-ref` slots. (Core internals may thread live Entity handles
;; through some of these slots — the fn arities that must accept that
;; keep an `:any` escape; this registered shape is the taught contract.)
(schema/register! ::entity-ref [:or :int [:tuple :qualified-keyword :any]])

(schema/register!
  ::query-request
  [:map
   [::query ::query-form]
   [::args  {:optional true} [:vector :any]]
   [::max-work {:optional true} ::max-work]
   [::max-results {:optional true} ::max-results]
   [::max-result-weight {:optional true} ::max-result-weight]
   [::db    {:optional true} :any]
   [::conn  {:optional true} ::conn]])

(schema/register!
  ::pull-request
  [:map
   [::pull-pattern [:vector :any]]
   ;; `::ref` stays `:any` here: pull's map-in arity validates this map
   ;; DIRECTLY (no :any escape), and core render paths thread nested
   ;; Entity handles through it. Agents write [[::entity-ref]] shapes.
   [::ref          :any]
   [::max-work {:optional true} ::max-work]
   [::max-results {:optional true} ::max-results]
   [::max-result-weight {:optional true} ::max-result-weight]
   [::db           {:optional true} :any]
   [::conn         {:optional true} ::conn]])

(schema/register!
  ::entity-request
  [:map
   [::ref  ::entity-ref]
   [::db   {:optional true} :any]
   [::conn {:optional true} ::conn]])

;; The ONE canonical "a datahike db value" SHAPE — registered once and
;; referenced by every positional :db slot below (shared-shape rule;
;; never inline a map check). This is the BOUNDARY face of the same
;; concept the runtime predicate `internal/db-value?` names: the schema
;; slots use this; the runtime dispatch that must tell a db APART from a
;; Datalog `:in` input (e.g. `query`'s positional path) uses the strict
;; `db-value?`. They are deliberately TWO faces of one idea, not two
;; competing predicates:
;;
;;   - This schema is `'map?` — the only form that is BOTH clean
;;     pure-data (re-readable WITHOUT sci, no embedded fn object — the
;;     pure-data platform law, see seon.render.canvas + the
;;     `registered-forms-are-pure-data` test) AND true for every db
;;     flavor (DB / FilteredDB / HistoricalDB / AsOfDB / SinceDB are all
;;     `defrecord`s ⇒ `map?`-true). At these slots the arity has ALREADY
;;     guaranteed the arg is a db, so a coarse presence check is correct.
;;   - `db-value?` is `satisfies? IDB` — STRICTER (a plain request/input
;;     map is `map?`-true but NOT a db). The strict logic CANNOT be
;;     expressed as a clean pure-data malli form (sci can't resolve our
;;     ns; `satisfies?`/`contains?` aren't safe on temporal dbs), so it
;;     lives as the runtime predicate, not the schema.
;;
;; QUOTED — unquoted `map?` would embed the fn OBJECT (the exact poison
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

(schema/register! ::datoms [:vector ::datom])

(schema/register!
  ::handler-input
  [:map
   [::tx-report  :any]
   [::db         :any]
   [::db-before  :any]
   [::datoms     ::datoms]
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

;; Final transaction provenance. Each normal post-genesis transaction relates
;; the submitted facts to one EXISTING database user (root, human, or agent)
;; and one stable process identity. The refs are deliberately heterogeneous;
;; there is no duplicate database-user entity.
(schema/register! ::user            :seon.db/ref)
(schema/register! ::process         :seon.db/ref)

;; Retired scalar/origin attrs are deliberately NOT registered. Existing
;; stores retain their immutable native schema/history, but no live source path
;; reads or writes those datoms.

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

(defn attached?
  "True when this process has one live database attachment."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [conn *conn*]
    ;; Datahike owns the connection lifecycle. Its final `release` changes the
    ;; connection's wrapped cell to exactly `:released`; this is the same fact
    ;; its own connection spec uses (`datahike.connector/::connection`). Do not
    ;; mirror that state in another Seon atom.
    (and (connector/connection? conn)
         (not= :released @(:wrapped-atom conn)))))

(defn current-tx-context
  "The active tx-context map, or nil outside [[with-tx-context]].

   Fiber-local across awaits (AsyncLocalStorage), safe under
   concurrent agents. The map may carry runtime-only turn/eval/test/replay
   values, but the transaction boundary persists only `::user` and
   `::process`."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (internal/current-tx-context))

(defn current-agent-id
  "The active agent-id (string), or nil outside a [[with-agent]] scope.
   Fiber-local across awaits. The standard accessor for any code that
   needs to know whose universe it's running in.

     (db/current-agent-id)   ; ⟹ \"iCg-2606101519\"   (your own id)"
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (internal/current-agent-id))

;; Slot shapes for the two scope fns below (named-positional :catn slots
;; reference a registered shape). `::thunk` is the 0-arg fn run within
;; the scope (`'fn?` — the pure-data predicate form, like `::handler`).
(schema/register! ::thunk      'fn?)
(schema/register! ::tx-context :map)
(schema/register! ::read-operation :qualified-keyword)
(schema/register! ::read-source
  [:enum :seon.db.read.source/captured :seon.db.read.source/foreign])
;; `::read-request` stays broad at the observation envelope so a future or
;; malformed operation reaches `read-observation-changed?` and conservatively
;; misses instead of throwing at instrumentation. Known operations validate
;; against the exact closed shapes below before replay.
(schema/register! ::read-request :map)
(schema/register! ::query-read-request
  [:map {:closed true}
   [::query ::query-form]
   [::args [:vector :any]]
   [::max-work ::max-work]
   [::max-results ::max-results]
   [::max-result-weight ::max-result-weight]])
(schema/register! ::pull-read-request
  [:map {:closed true}
   [::pull-pattern [:vector :any]]
   [::ref :any]
   [::max-work ::max-work]
   [::max-results ::max-results]
   [::max-result-weight ::max-result-weight]])
(schema/register! ::entity-read-request
  [:map {:closed true}
   [::ref :any]])
(schema/register! ::index [:enum :eavt :aevt :avet])
(schema/register! ::components [:vector :any])
(schema/register! ::index-limit [:int {:min 1 :max 1000}])
(schema/register! ::seek? :boolean)
(schema/register! ::index-prefix? :boolean)
(schema/register! ::index-read-request
  [:map {:closed true}
   [::index ::index]
   [::components ::components]
   [::index-limit ::index-limit]
   [::seek? ::seek?]])
(schema/register! ::rseek-read-request
  [:map {:closed true}
   [::index ::index]
   [::components ::components]
   [::index-limit ::index-limit]
   [::index-prefix? ::index-prefix?]])
(schema/register! ::index-datoms-request
  [:map
   [::db ::db-val]
   [::index ::index]
   [::components {:optional true} ::components]
   [::index-limit ::index-limit]
   [::seek? {:optional true} ::seek?]])
(schema/register! ::rseek-datoms-request
  [:map
   [::db ::db-val]
   [::index ::index]
   [::components {:optional true} ::components]
   [::index-limit ::index-limit]
   [::index-prefix? {:optional true} ::index-prefix?]])
(schema/register! ::empty-read-request [:map {:closed true}])
(schema/register! ::read-result :any)
(schema/register! ::read-replayable? :boolean)
(schema/register! ::read-observation
  [:map
   [::read-operation ::read-operation]
   [::read-source ::read-source]
   [::read-request ::read-request]
   [::read-result ::read-result]
   [::read-replayable? ::read-replayable?]])
(schema/register! ::read-observations [:vector ::read-observation])
(schema/register! ::result :any)
(schema/register! ::capture-reads-request
  [:map
   [::db ::db-val]
   [::thunk 'fn?]])
(schema/register! ::capture-reads-response
  [:map
   [::result ::result]
   [::read-observations ::read-observations]])
(schema/register! ::read-observation-changed-request
  [:map
   [::db ::db-val]
   [::read-observation ::read-observation]])
(schema/register! ::read-observation-changed? :boolean)

(defn with-agent
  "Establish an agent-id scope for the dynamic extent of `f`.

   `f` is a 0-arg fn. Inside `f` — including across `await`s and any Promises it
   returns — `(current-agent-id)` returns `agent-id`. Nesting: the
   inner scope wins, the outer restores on exit. The loop sets this for
   you; you rarely call it — your own writes are already tagged.

     (db/with-agent agent-id
       (fn [] (db/transact! {::db/tx-data [...]}))) ; user=agent, process=REPL"
  {:malli/schema [:=> [:catn [::agent-id :string] [::thunk ::thunk]] :any]}
  [agent-id f]
  (internal/run-with-agent agent-id f))

(defn without-agent
  "Clear the agent-id scope for the dynamic extent of `f`.

   `f` is a 0-arg fn. Inside `f` — including across `await`s —
   `(current-agent-id)` is nil; the outer scope restores on exit. For
   Core writers use this when they must not inherit an agent user. Clearing
   the agent does not select root by itself: establish explicit `::user` and
   `::process` facts with [[with-tx-context]].

     (db/without-agent
       (fn []
         (db/with-tx-context
           {::db/user [:seon.agent/id \"root\"]
            ::db/process [:seon.db.process/id :seon.db.process/boot]}
           (fn [] (db/transact! {::db/tx-data [...]})))))"
  {:malli/schema [:=> [:catn [::thunk ::thunk]] :any]}
  [f]
  (internal/run-without-agent f))

(defn with-tx-context
  "Establish a tx-context for the dynamic extent of `f`.

   `f` is a 0-arg fn; nested calls merge and context propagates across awaits.
   Runtime code may carry its own fully namespaced execution values here.
   Ordinary transaction provenance reads only `::user` and `::process`;
   everything else remains process-local.

     (db/with-tx-context
       {::db/user [:seon.agent/id \"root\"]
        ::db/process [:seon.db.process/id :seon.db.process/config]}
       (fn [] (db/transact! {::db/tx-data [...]})))"
  {:malli/schema [:=> [:catn [::tx-context ::tx-context] [::thunk ::thunk]] :any]}
  [ctx-map f]
  (internal/run-with-tx-context ctx-map f))

(defn capture-reads
  "Run a synchronous thunk and return its result plus actual database reads.

   The captured `:seon.db/read-observations` contain normalized immutable
   request/result facts and never retain a database handle. A read against
   `:seon.db/db` is replayable; lazy, temporal, foreign-db, function-valued, or
   opaque host reads are recorded conservatively with
   `:seon.db/read-replayable? false`.

   Nested captures compose: the inner scope records its extent and every outer
   scope also sees those reads. Promise-returning thunks are rejected because
   this boundary is deliberately synchronous; returning before reads finish
   would make the capture incomplete."
  {:malli/schema [:=> [:cat ::capture-reads-request]
                  ::capture-reads-response]}
  [{::keys [db thunk]}]
  (let [bucket (atom [])
        result (internal/run-with-read-capture db bucket thunk)]
    (when (instance? js/Promise result)
      (throw (ex-info
               "seon.db/capture-reads requires a synchronous thunk"
               {::error :seon.db/asynchronous-read-capture
                :seon.error/kind :user-input})))
    {::result result
     ::read-observations @bucket}))

;; ---------------------------------------------------------------------------
;; Write path
;; ---------------------------------------------------------------------------

(schema/register! ::cas-ref   ::entity-ref)   ; lookup-ref or eid
(schema/register! ::cas-attr  :keyword)
(schema/register! ::cas-value :any)   ; lookup-ref, eid, or ANY scalar value
(schema/register! ::cas-op    [:vector :any])

(defn cas-assert
  "Build a no-op compare-and-swap op asserting `ref`'s `attr` is `value`.

   Pure DATA; `old == new == value`. LEAD a work-tx with this and the tx
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
  "Save records to the database, persisting new facts durably.

   Commits `::db/tx-data` (entity maps and/or tx ops) through the one
   writer and returns an envelope. Two call shapes:

   - map-in / map-out (preferred):
       (db/transact! {::db/tx-data [{::name \"A\"}]
                      ::db/expected-coordinate (db/head-coordinate) ; optional fence
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

   The error map carries a top-level `:seon.error/kind` —
   `:user-input` (fix tx-data and retry) vs `:core-bug` (the pod
   survived; report it, don't retry blindly).

   `::db/expected-coordinate` is an optional whole-database precondition. The
   serialized writer commits only when its current head still equals that full
   database/branch/commit/t coordinate; otherwise the request resolves to an
   error envelope and writes nothing. Freeze it AFTER any first-use schema
   installation—the automatic installation of a newly registered attribute is
   necessarily an earlier transaction. Desired-state reconcilers use this to
   compile from one immutable db value and retry if another writer wins before
   commit.

   Before committing it validates shape, attrs, and values; installs
   datahike schema for any newly-registered attr; and auto-merges the
   active [[with-tx-context]] / [[with-agent]] context into `:tx-meta` as the
   two resolvable refs `::user` and `::process`. Runtime execution values are
   never copied to the transaction.

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
  ;; Opted OUT of instrumentation — caught by the computed predicate
  ;; `seon.instrument/async-unwrappable?` (by SHAPE: variadic + :function
  ;; schema — not by name). SAFE BY DEFAULT means a bad invocation shape
  ;; returns an error ENVELOPE (`assert-invocation-shape!`), never throws;
  ;; an instrumentation throw on bad input would break that tested
  ;; contract. This schema stays the discoverable contract; guards enforce.
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
;; Provenance genesis.
;; ---------------------------------------------------------------------------

(schema/register! ::provenance-action
                  [:enum :fresh-genesis :converged])
(schema/register! ::genesis-tx :int)
(schema/register! ::human-tx   :int)
(schema/register! ::ensure-provenance-request
  [:map [::conn {:optional true} ::conn]])
(schema/register! ::ensure-provenance-response
  [:map
   [::provenance-action ::provenance-action]
   [::genesis-tx  {:optional true} :int]
   [::human-tx    {:optional true} :int]])

(def ^:private genesis-attrs
  "The minimal native capabilities required before provenance can self-host."
  [:seon.agent/id :seon.user/id ::user ::process ::process/id])

(defn- attr-installed?
  [db-value attr]
  (contains? (:schema db-value) attr))

(defn- lookup-present?
  [db-value [attr value]]
  (and (attr-installed? db-value attr)
       (boolean (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]]
                     db-value attr value))))

(defn- genesis-tx-data
  [db-value]
  (let [unregistered (into [] (remove schema/registered?) genesis-attrs)]
    (when (seq unregistered)
      (throw (ex-info
               (str "Database genesis requires registered schemas for "
                    (pr-str unregistered) ". Load their owning namespaces "
                    "before opening the cluster store.")
               {::error :seon.db/genesis-schema-unregistered
                ::attrs unregistered
                :seon.error/kind :core-bug})))
    (let [missing-attrs (remove #(attr-installed? db-value %) genesis-attrs)
          missing-root? (not (lookup-present? db-value [:seon.agent/id "root"]))
          missing-processes
          (remove #(lookup-present? db-value (process/lookup-ref %)) process/ids)]
      (into (internal/malli->datahike-schema missing-attrs)
            (concat
              (when missing-root? [{:seon.agent/id "root"}])
              (map (fn [id] {::process/id id}) missing-processes))))))

(defn ^:async ensure-provenance!
  "Establish the minimal transaction-provenance genesis.

   Call once immediately after connecting a store and before any ordinary
   `transact!`. The minimal native capability/root/process transaction is
   explicitly un-attributed because its own ref attrs and targets do not yet
   exist. The following root/boot transaction ensures the stable human. A
   converged store emits no transaction."
  {:malli/schema
   [:=> [:cat ::ensure-provenance-request] ::ensure-provenance-response]}
  [{::keys [conn] :or {conn *conn*}}]
  (let [c             (internal/resolve-conn conn)
        before        @c
        base-data     (genesis-tx-data before)
        base-report   (when (seq base-data)
                        (await (d/transact! c {:tx-data base-data})))
        after-base    @c
        human-missing? (not (lookup-present? after-base [:seon.user/id "user"]))
        human-env     (when human-missing?
                        (await
                          (with-tx-context
                            {::user [:seon.agent/id "root"]
                             ::process (process/lookup-ref ::process/boot)}
                            (fn []
                              (transact! {::conn c
                                          ::tx-data [{:seon.user/id "user"}]})))))
        _             (when (and human-env (false? (::ok? human-env)))
                        (throw (ex-info
                                 "Database provenance genesis could not ensure the human user."
                                 {::error (::error human-env)
                                  :seon.error/kind :core-bug})))
        action        (if (or base-report human-env)
                        :fresh-genesis
                        :converged)]
    (cond-> {::provenance-action action}
      base-report  (assoc ::genesis-tx (:max-tx (:db-after base-report)))
      human-env    (assoc ::human-tx (::tx human-env)))))

;; ---------------------------------------------------------------------------
;; Read path — synchronous over a db value. Each op has a map-in arity
;; AND a datahike-shaped positional arity (dispatch is by arg count; the
;; positional db slot is REQUIRED and explicit — no ambient *conn*).
;; ---------------------------------------------------------------------------

(declare assert-known-query-attrs!)

(def ^:private query-budget-ceilings
  "Hard synchronous resource ceilings for every application query.

   Callers may lower these through the namespaced request keys or a raw query
   map's Datahike option keys; no caller can raise them. Exhaustion is a
   structured `:datahike/budget-exceeded` error, never a partial answer."
  {::max-work 2000000
   ::max-results 50000
   ::max-result-weight (* 8 1024 1024)})

(def ^:private pull-budget-ceilings
  "Hard synchronous resource ceilings for every application pull."
  {::max-work 250000
   ::max-results 25000
   ::max-result-weight (* 4 1024 1024)})

(defn- clamp-budget
  "Translate namespaced Seon or raw Datahike options into clamped options."
  [ceilings request]
  (reduce-kv
    (fn [options seon-key ceiling]
      (let [library-key (keyword (name seon-key))
            requested (or (get request seon-key)
                          (get request library-key)
                          ceiling)]
        (assoc options library-key (min ceiling requested))))
    {}
    ceilings))

(defn- captured-budget
  "Translate clamped Datahike options back to the namespaced read contract."
  [options]
  {::max-work (:max-work options)
   ::max-results (:max-results options)
   ::max-result-weight (:max-result-weight options)})

(defn- raw-query
  "Run one guarded query without crossing the read-observation boundary."
  [db q inputs budget-request]
  (assert-known-query-attrs! db q)
  (d/q (merge {:query q :args (into [db] inputs)}
              (clamp-budget query-budget-ceilings budget-request))))

(defn- execute-query
  "Run one normalized query and record it only when a capture scope is active."
  [db q inputs budget-request]
  (let [budget (clamp-budget query-budget-ceilings budget-request)
        result (raw-query db q inputs budget)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/query db
        (merge {::query q ::args (vec inputs)} (captured-budget budget))
        result true))
    result))

(defn query
  "Ask the database a question: find, count, or sum stored facts.

   Runs a Datalog query and returns the result set. Two call shapes:

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
     ;; (results are clipped ~50 rows). The `; ⟹` is a SHAPE; report the
     ;; number YOUR eval returns, not the one written here:
     (db/query '[:find (count ?e) . :where [?e :seon.fn/sym]])  ; ⟹ «a scalar count»
     ;; CLIPPED results — when a render shows a banner like «N rows; showing
     ;; first 50, +M more clipped», that N IS the total; READ it. Do NOT
     ;; recount the printed rows, and do NOT re-narrow the query to fit. Need
     ;; only the count? COUNT in the query (above), don't list-then-count.
     ;; registered-schema count — ONE :seon.schema/key row per registered
     ;; schema; this IS the count of registered schemas. Read it back live:
     (db/query '[:find (count ?e) . :where [?e :seon.schema/key]]) ; ⟹ «a scalar count»
     ;; collection — one value per row:
     (db/query '[:find [?n ...] :where [?e :seon.ns/name ?n]]) ; ⟹ «vector of ns-name keywords»
     ;; predicate + binding-expr:
     (db/query '[:find ?s :where [?e :seon.fn/doc ?d] [(count ?d) ?l]
                                 [(> ?l 400)] [?e :seon.fn/sym ?s]])
     ;; REF-JOIN — :seon.fn/ns is a ref (stores an eid); match the target
     ;; by joining through its name, NOT by putting the keyword in the slot:
     (db/query '[:find (count ?e) . :where [?e :seon.fn/ns ?n]
                                           [?n :seon.ns/name :seon.db]]) ; ⟹ «a scalar count»
     ;;   (the keyword form [?e :seon.fn/ns :seon.db] THROWS.)
     ;; GROUPED AGGREGATE with the name pulled in the SAME query, so the
     ;; group is readable (a bare ref-eid is not):
     (db/query '[:find ?nm (count ?t)
                 :where [?t :seon.test/ns ?n] [?n :seon.ns/name ?nm]])
     ;;   ; ⟹ «set of [ns-name count] tuples»   then (sort-by second > …) in Clojure

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
    [:=> [:cat [:or ::query-request ::query-form]] :any]
    [:=> [:catn [::query ::query-form]
                [::rest [:+ :any]]] :any]]}
  [& args]
  (let [a0 (first args)]
    (cond
      ;; Seon's map-in request is identified by its fully qualified key.
      (and (map? a0) (contains? a0 ::query))
      (let [{::keys [query args db conn] :or {conn *conn* args []}} a0
            db (or db @(internal/resolve-conn conn))]
        (execute-query db query args a0))

      ;; Datahike's raw map query is a separate supported query form. Every
      ;; map-form query carries :find; do not confuse it with a malformed
      ;; Seon request map.
      (and (map? a0) (contains? a0 :find))
      (let [q a0]
        (if (internal/db-value? (second args))
          (let [[_ db & inputs] args]
            (execute-query db q inputs q))
          (let [db     @(internal/resolve-conn *conn*)
                inputs (rest args)]
            (execute-query db q inputs q))))

      ;; A map that is neither supported shape is almost always a bare-key
      ;; typo such as {:query ...}. Passing it to Datahike used to return #{}
      ;; silently, turning an invalid request into a plausible answer.
      (map? a0)
      (throw
        (ex-info
          (str "seon.db/query request maps require :seon.db/query. "
               "Use {:seon.db/query '[:find ...]} or a raw Datahike map "
               "query containing :find.")
          {:seon.error/kind :user-input
           ::error          :seon.db/invalid-query-request
           ::request        a0}))

      ;; Positional: a0 IS the vector/string query. If the next arg is a db
      ;; VALUE it is explicit; otherwise inject *conn* and treat the rest as
      ;; :in inputs.
      :else
      (let [q a0]
        (if (internal/db-value? (second args))
          (let [[_ db & inputs] args]
            (execute-query db q inputs q))
          (let [db     @(internal/resolve-conn *conn*)
                inputs (rest args)]
            (execute-query db q inputs q)))))))

(defn- raw-index-datoms
  "Read and normalize one bounded Datahike index window without observing it."
  [db index components limit seek?]
  (let [read-fn (if seek? d/seek-datoms d/datoms)]
    (->> (apply read-fn db index components)
         (take limit)
         (mapv internal/datom->map))))

(defn index-datoms
  "Read at most `:seon.db/index-limit` datoms from one database index.

   `:seon.db/components` are the ordinary Datahike index components.
   `:seon.db/seek? true` starts at or after them; false selects their exact
   prefix. The returned values are fully namespaced plain datom maps. Reads
   participate in the same exact observation/replay mechanism as query and
   pull, so a reactive surface rerenders only when its bounded window changes."
  {:malli/schema [:=> [:cat ::index-datoms-request] ::datoms]}
  [{::keys [db index components index-limit seek?]
    :or {components [] seek? false}}]
  (let [result (raw-index-datoms db index components index-limit seek?)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/index-datoms db
        {::index index
         ::components components
         ::index-limit index-limit
         ::seek? seek?}
        result true))
    result))

(defn- datom-index-components
  "Comparable component vector for one normalized Datahike index row."
  [index datom]
  (case index
    :eavt [(::e datom) (::a datom) (::v datom) (::tx datom)]
    :aevt [(::a datom) (::e datom) (::v datom) (::tx datom)]
    :avet [(::a datom) (::v datom) (::e datom) (::tx datom)]))

(defn- raw-rseek-datoms
  "Read and normalize one bounded descending Datahike index window."
  [db index components limit prefix?]
  (let [rows (map internal/datom->map
                  (apply d/rseek-datoms db index components))
        rows (if (and prefix? (seq components))
               (take-while #(= components
                               (subvec (datom-index-components index %)
                                       0 (count components)))
                           rows)
               rows)]
    (vec (take limit rows))))

(defn rseek-datoms
  "Read at most `:seon.db/index-limit` datoms descending from an index key.

   This is the bounded public face of Datahike's lazy `rseek-datoms`: results
   begin at or before `:seon.db/components` and walk toward the beginning of
   the selected index. `:seon.db/index-prefix? true` stops at the first row
   outside those concrete components. The bounded result participates in exact
   reactive-read replay, so unchanged windows do not rerender their consumers."
  {:malli/schema [:=> [:cat ::rseek-datoms-request] ::datoms]}
  [{::keys [db index components index-limit index-prefix?]
    :or {components [] index-prefix? false}}]
  (let [result (raw-rseek-datoms db index components index-limit index-prefix?)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/rseek-datoms db
         {::index index
          ::components components
         ::index-limit index-limit
         ::index-prefix? index-prefix?}
        result true))
    result))

(defn- installed-schema*
  "Read installed schema without crossing the public observation boundary."
  [db]
  (or (when (some? db)
        (try (dbi/-schema db) (catch :default _ nil)))
      {}))

(defn installed-schema
  "The datahike schema map actually INSTALLED on `db`.

   Attrs the conn has seen, keyed by ident keyword. FilteredDB-safe and nil-safe.

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

   The wrapper db values — FilteredDB (the web UI's per-agent view),
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
   EVERY installed attr, including registered attrs with no live values.
   Check here before inventing a new attr — the attribute you need may
   already exist with zero rows:

     (filter #(= \"my.plan\" (namespace %))
             (keys (db/installed-schema @db/*conn*)))
     ; ⟹ «(:my.plan/id :my.plan/title :my.plan/status …)»
     ;   — registered, queryable, just no rows yet. Reuse it; don't fork."
  {:malli/schema [:=> [:catn [::db :any]] :map]}
  [db]
  (let [result (installed-schema* db)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/installed-schema db {} result true))
    result))

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

(defn- resolve-existing-eid
  "Resolve a read ref without Datahike's strict missing-entity throw.

   Datahike's public pull/entity paths call `entid-strict`, but Seon's public
   contract treats an absent lookup ref as an ordinary absent value. Lookup
   refs and idents already prove existence while resolving. A numeric eid is
   syntax-valid without proving a row exists, so give it one bounded EAVT
   existence probe. Malformed refs and non-unique lookup attrs still throw."
  [db ref]
  (when-let [eid (dbu/entid db ref)]
    (when (or (not (number? ref))
              (first (dbi/datoms db :eavt [eid])))
      eid)))

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
          installed (installed-schema* db)
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
          ;; FLAT ex-data — `:seon.error/kind` at the top level, the ONE
          ;; convention every kind-bearing throw uses (C43); ->map lifts
          ;; it to the envelope top (the ONE read position, C45).
          (throw (ex-info msg
                          {:seon.error/kind :user-input
                           ::missing-attrs  (vec (sort unknown))
                           ::query          q})))))))

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
  [db pattern ref budget-request]
  (let [named       (pull-pattern-attrs pattern)
        installed   (installed-schema* db)
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
        ;; FLAT ex-data — same convention as the query guard above (C43).
        (throw (ex-info msg
                        {:seon.error/kind :user-input
                         ::missing-attrs  (vec (sort unregistered))
                         ::pull-pattern   pattern}))))
    (when-let [eid (resolve-existing-eid db ref)]
      (if (empty? registered)
        (d/pull db (merge {:selector pattern :eid eid}
                          (clamp-budget pull-budget-ceilings budget-request)))
        (let [pattern' (filter-pull-pattern pattern (set registered))]
          (when (seq pattern')
            (d/pull db (merge {:selector pattern' :eid eid}
                              (clamp-budget pull-budget-ceilings
                                            budget-request)))))))))

(defn- execute-pull
  "Run one guarded pull and capture its normalized request/result when bound."
  [db pattern ref budget-request]
  (let [budget (clamp-budget pull-budget-ceilings budget-request)
        result (guarded-pull db pattern ref budget)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/pull db
        (merge {::pull-pattern pattern ::ref ref} (captured-budget budget))
        result true))
    result))

(defn pull
  "Pull an entity by ref using a pull pattern (sync).

   Returns the pulled map, or nil if the ref doesn't resolve.

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
     (execute-pull db pull-pattern ref req)))
  ([selector eid]
   (execute-pull @(internal/resolve-conn *conn*) selector eid {}))
  ([db selector eid]
   (execute-pull db selector eid {})))

(defn- raw-entity
  "Resolve a Datahike Entity without crossing the public observation boundary."
  [db ref]
  (when-let [eid (resolve-existing-eid db ref)]
    (d/entity db eid)))

(defn- execute-entity-lazy
  "Resolve a lazy Entity and record a deliberately non-replayable read."
  [db ref]
  (let [result (raw-entity db ref)]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/entity-lazy db
        {::ref ref} result false))
    result))

(defn entity-lazy
  "INTERNAL: return the RAW datahike Entity for a ref (lazy, map-like).

   Ref attrs navigate lazily to nested Entities — the render
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
       (execute-entity-lazy db ref))
     (execute-entity-lazy @(internal/resolve-conn *conn*) req)))
  ([db eid]
   (execute-entity-lazy db eid)))

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

(defn- execute-entity
  "Resolve and touch an entity under one public read observation."
  [db ref]
  (let [result (touch->map (raw-entity db ref))]
    (when-let [captures (internal/current-read-captures)]
      (internal/record-read!
        captures :seon.db.read.operation/entity db {::ref ref} result true))
    result))

(defn entity
  "Fetch one stored record by its id, with all its fields.

   Looks up an entity by eid or lookup-ref, as a plain map (sync).
   Returns `:db/id` plus every attr on the entity (a TOUCHED snapshot), nil if the
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
     ; ⟹ «map: :db/id N, :seon.fn/sym \"seon.db/transact!\", :seon.fn/arglists \"…\", …»"
  ;; The 1-arg arity accepts EITHER a request map OR a bare eid/lookup-ref
  ;; (auto-inject from *conn*) — one arity-1 `:=>` (the body branches on
  ;; map?); a separate eid-only `:=>` would collide with the request arity.
  {:malli/schema
   [:function
    [:=> [:cat [:or ::entity-request :any]] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]
   (if (map? req)
     (let [{::keys [ref db conn] :or {conn *conn*}} req
           db (or db @(internal/resolve-conn conn))]
       (execute-entity db ref))
     (execute-entity @(internal/resolve-conn *conn*) req)))
  ([db eid]
   (execute-entity db eid)))

;; ---------------------------------------------------------------------------
;; Temporal — derive a db VALUE at another point in time. Reads normally run
;; against the db injected from *conn*; these let you make your OWN db value
;; (history / as-of / since) and pass it positionally to query/pull/entity.
;; Datomic/datahike shape: db in, db out.
;; ---------------------------------------------------------------------------

;; A datahike time-point: a tx-id (int), or a Date/txInstant (`inst?`).
(schema/register! ::time-point [:or :int 'inst?])

(defn history
  "A db value spanning ALL of time — assertions and retractions.

   Every datom ever, not just the now-true view. Read it with a 5-tuple `:where` so the tx and
   the add/retract flag bind. The db is injected from your one connection;
   omit it:

     (db/query '[:find ?v ?tx ?added
                 :where [?e :seon.ns/name ?v ?tx ?added]]
               (db/history))               ; ?added = true add, false retract

   Pass an explicit db to branch history off a snapshot you already hold."
  {:malli/schema [:function
                  [:=> [:cat] :any]
                  [:=> [:catn [::db ::db-val]] :any]]}
  ([] (history @(internal/resolve-conn *conn*)))
  ([db]
   (let [result (d/history db)]
     (when-let [captures (internal/current-read-captures)]
       (internal/record-read!
         captures :seon.db.read.operation/history db {} result false))
     result)))

(defn as-of
  "A db value as it was AT `t` — time-travel for reads.

   `t` is a tx-id, Date, or txInstant. query/pull/entity against it see only what was true then:

     (db/query '[:find ?title :where [?e ::doc-id \"d1\"] [?e ::title ?title]]
               (db/as-of last-week-tx))    ; db omitted ⇒ your *conn* at t

   2-arity rewinds an explicit db you already hold: (db/as-of db t)."
  {:malli/schema [:function
                  [:=> [:cat ::time-point] :any]
                  [:=> [:catn [::db ::db-val] [::time-point ::time-point]] :any]]}
  ([t] (as-of @(internal/resolve-conn *conn*) t))
  ([db t]
   (let [result (d/as-of db t)]
     (when-let [captures (internal/current-read-captures)]
       (internal/record-read!
         captures :seon.db.read.operation/as-of db
         {::time-point t} result false))
     result)))

(schema/register! ::at-coordinate-response [:or ::db-val ::error])

(defn ^:async at-coordinate
  "Resolve an exact immutable historical database value.

   A complete coordinate pins both the containing Datahike commit and the
   temporal cut inside it. `commit-as-db` touches storage and is asynchronous
   on CLJS, so this function is honestly `^:async`; agent eval awaits it.

   Omit `conn` to use `*conn*`. The selected coordinate must name that
   connection's database and branch. A missing retained commit, wrong
   attachment, partial coordinate, or out-of-range t returns a structured
   `:seon.error/*` value instead of throwing.

     (db/at-coordinate
       {:seon.db.coordinate/database-id #uuid \"...\"
        :seon.db.coordinate/branch :db
        :seon.db.coordinate/commit-id #uuid \"...\"
        :seon.db.coordinate/t 536870914})"
  {:malli/schema
   [:function
    [:=> [:catn [::coordinate ::coordinate]] ::at-coordinate-response]
    [:=> [:catn [::conn ::conn] [::coordinate ::coordinate]]
     ::at-coordinate-response]]}
  ([coordinate]
   (await (at-coordinate *conn* coordinate)))
  ([conn coordinate]
   (try
     (when-not (schema/valid-candidate-value? ::coordinate coordinate)
       (throw
        (ex-info "at-coordinate requires one complete database coordinate."
                 {::coordinate coordinate
                  :seon.error/kind :user-input})))
     (let [connection (internal/resolve-conn conn)
           current-coordinate (db.coordinate/resolved @connection)]
       (when-not (db.coordinate/same-attachment?
                  current-coordinate coordinate)
         (throw
          (ex-info "The coordinate names a different database attachment."
                   {::coordinate coordinate
                    ::current-coordinate current-coordinate
                    :seon.error/kind :user-input})))
       (let [container
             (await
              (d/commit-as-db
               connection (::db.coordinate/commit-id coordinate)))]
         (when-not container
           (throw
            (ex-info "The coordinate's retained commit was not found."
                     {::coordinate coordinate
                      :seon.error/kind :user-input})))
         (let [resolved
               (db.coordinate/at
                {::db.coordinate/db-value container
                 ::db.coordinate/attachment
                 (db.coordinate/attachment coordinate)
                 ::db.coordinate/target-t (::db.coordinate/t coordinate)})]
           (when-not (= coordinate resolved)
             (throw
              (ex-info "The retained commit does not resolve this coordinate."
                       {::coordinate coordinate
                        ::resolved-coordinate resolved
                        :seon.error/kind :user-input})))
           (d/as-of container (::db.coordinate/t coordinate)))))
     (catch :default e
       (error/->map e)))))

(defn since
  "The complement of [[as-of]] — a db value of datoms added after `t`.

   Diff \"what changed since\" a tx you remembered:

     (db/query '[:find ?e :where [?e ::status :done]] (db/since last-seen-tx))

   2-arity takes an explicit db: (db/since db t)."
  {:malli/schema [:function
                  [:=> [:cat ::time-point] :any]
                  [:=> [:catn [::db ::db-val] [::time-point ::time-point]] :any]]}
  ([t] (since @(internal/resolve-conn *conn*) t))
  ([db t]
   (let [result (d/since db t)]
     (when-let [captures (internal/current-read-captures)]
       (internal/record-read!
         captures :seon.db.read.operation/since db
         {::time-point t} result false))
     result)))

;; The two ENDS of a time-travel domain (the `as-of`/`since` `t` range).
;; `basis-t` is the latest tx reflected in a db value — the \"now\" end of a
;; scrubber. `origin-t` is datahike's origin tx (`tx0`) — the floor; the first
;; user tx is `origin-t`+1, so an `as-of` below it is the empty/pre-seed database.
;; Both are valid [[time-point]]s usable directly with `as-of`/`since`.

(def ^{:doc "Datahike's origin tx-id (`tx0`) — the floor of any time-travel
   domain. `(as-of … origin-t)` is the empty database before the first user tx."}
  origin-t dconst/tx0)

(defn- basis-t*
  "Read a db coordinate without crossing the read-observation boundary."
  [db]
  (if (instance? AsOfDB db)
    (dbi/-time-point db)
    (dbi/-max-tx db)))

(defn head-coordinate
  "The complete coordinate of one committed database value.

   Omit db to identify the current immutable head. Temporal `as-of` wrappers
   are not committed containers and fail; pin a complete coordinate before
   constructing a filtered historical view."
  {:malli/schema [:function
                  [:=> [:cat] ::coordinate]
                  [:=> [:catn [::db ::db-val]] ::coordinate]]}
  ([] (head-coordinate @(internal/resolve-conn *conn*)))
  ([db] (db.coordinate/resolved db)))

(defn basis-t
  "The selected tx coordinate of a db value.

   For an [[as-of]] value this is its selected time point, not the later head
   of its origin database. For current, history, and [[since]] values this is
   the origin head. Omit db ⇒ your `*conn*`'s current basis. The result is a
   [[time-point]] usable directly with `as-of`/`since`."
  {:malli/schema [:function
                  [:=> [:cat] ::time-point]
                  [:=> [:catn [::db ::db-val]] ::time-point]]}
  ([] (basis-t @(internal/resolve-conn *conn*)))
  ([db]
   (let [result (basis-t* db)]
     (when-let [captures (internal/current-read-captures)]
       (internal/record-read!
         captures :seon.db.read.operation/basis-t db {} result true))
     result)))

(def ^:private replayable-read-operations
  #{:seon.db.read.operation/query
    :seon.db.read.operation/index-datoms
    :seon.db.read.operation/rseek-datoms
    :seon.db.read.operation/installed-schema
    :seon.db.read.operation/pull
    :seon.db.read.operation/entity
    :seon.db.read.operation/basis-t})

(def ^:private replayable-read-request-validators
  ;; Malli's `validate` rebuilds a validator per call. Replay is a broadcast
  ;; hot path, so compile each stable registered request shape once, lazily.
  ;; A hot-reloaded namespace declares schemas into the replacement collector
  ;; before the coordinator activates that complete projection. Eager
  ;; top-level compilation would resolve a newly introduced reference against
  ;; the still-active prior projection and transiently fail the module load.
  {:seon.db.read.operation/query (delay (m/validator ::query-read-request))
   :seon.db.read.operation/index-datoms (delay (m/validator ::index-read-request))
   :seon.db.read.operation/rseek-datoms (delay (m/validator ::rseek-read-request))
   :seon.db.read.operation/installed-schema (delay (m/validator ::empty-read-request))
   :seon.db.read.operation/pull (delay (m/validator ::pull-read-request))
   :seon.db.read.operation/entity (delay (m/validator ::entity-read-request))
   :seon.db.read.operation/basis-t (delay (m/validator ::empty-read-request))})

(defn- valid-replay-request?
  "True when a known operation carries its exact normalized request shape."
  [operation request]
  (when-let [validator (get replayable-read-request-validators operation)]
    ((force validator) request)))

(defn- replay-read-result
  "Replay one known semantic read without recording another observation."
  [db operation request]
  (case operation
    :seon.db.read.operation/query
    (raw-query db (::query request) (or (::args request) []) request)

    :seon.db.read.operation/index-datoms
    (raw-index-datoms db
                      (::index request)
                      (::components request)
                      (::index-limit request)
                      (::seek? request))

    :seon.db.read.operation/rseek-datoms
    (raw-rseek-datoms db
                      (::index request)
                      (::components request)
                      (::index-limit request)
                      (::index-prefix? request))

    :seon.db.read.operation/installed-schema
    (installed-schema* db)

    :seon.db.read.operation/pull
    (guarded-pull db (::pull-pattern request) (::ref request) request)

    :seon.db.read.operation/entity
    (touch->map (raw-entity db (::ref request)))

    :seon.db.read.operation/basis-t
    (basis-t* db)))

(defn read-observation-changed?
  "True when a captured database read can no longer produce its result.

   Replays the same normalized semantic read against the supplied current db
   value. Query, guarded pull, touched entity, installed-schema, and basis-t
   observations use their private raw helpers, so calling this function inside
   [[capture-reads]] never creates observations of its own.

   Returns true conservatively when the observation is foreign,
   non-replayable, temporal, lazy, unknown, malformed, or produces a runtime
   value the observer cannot normalize. No database or Entity handle is stored
   or reconstructed from the observation. This is an invalidation predicate,
   not a result cache."
  {:malli/schema [:=> [:cat ::read-observation-changed-request]
                  ::read-observation-changed?]}
  [{::keys [db read-observation]}]
  (let [{::keys [read-operation read-source read-request read-result
                 read-replayable?]} read-observation]
    (if-not (and read-replayable?
                 (= :seon.db.read.source/captured read-source)
                 (contains? replayable-read-operations read-operation)
                 (valid-replay-request? read-operation read-request)
                 (internal/normalized-read-value? read-request)
                 (internal/normalized-read-value? read-result))
      true
      (let [[request request-safe?]
            (internal/denormalize-read-value read-request)]
        (if-not request-safe?
          true
          (try
            (let [current-result
                  (replay-read-result db read-operation request)
                  [normalized-current current-safe?]
                  (internal/normalize-read-value db current-result)]
              (or (not current-safe?)
                  (not= read-result normalized-current)))
            (catch :default _
              true)))))))

;; ---------------------------------------------------------------------------
;; Listeners
;; ---------------------------------------------------------------------------

(defn listen!
  "Install a tx-listener; safe by default, never crashes the pod.

   Handler throws / rejections are caught and logged. `::db/handler` is a fn
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
  "Alias of [[listen!]] for a Promise handler (fire-and-forget)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn unlisten!
  "Remove a listener by key; returns `{:seon.db/ok? true}`.

   Idempotent — unknown keys are a silent no-op."
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
  "Datahike schema entries for user/process transaction provenance."
  {:malli/schema [:=> [:cat] [:vector :any]]}
  []
  (internal/tx-meta-datahike-schema))

(defn decode-edn-value
  "Read-side inverse of the bridge's mixed-`:or` EDN-string storage.

   See `seon.db.internal/encode-edn-slot-values`: attrs whose Malli
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
  "Validate boot preconditions; throws ex-info on failure.

   Conn must keep history and have both provenance attrs registered.
   Called at agent boot."
  {:malli/schema
   [:function
    [:=> [:cat] :boolean]
    [:=> [:catn [::opts [:map [::conn {:optional true} ::conn]]]] :boolean]]}
  ([] (assert-preconditions! {}))
  ([{::keys [conn] :or {conn *conn*}}]
   (internal/assert-preconditions! conn)))

;; ---------------------------------------------------------------------------
;; Database counts and provenance-derived scopes.
;; ---------------------------------------------------------------------------

(schema/register! ::attr-ns      :keyword)
(schema/register! ::row-ids      [:set :int])
(schema/register! ::attr-ns-set  [:set ::attr-ns])
(schema/register! ::datom-count   :int)

(defn datom-count
  "Count the current datoms in a database value.

   The normal current-database path reads the persistent index's maintained
   subtree count instead of walking its datoms. Temporal or filtered database
   wrappers have no direct `:eavt` field, so they use Datahike's wrapper-aware
   datom view for correctness. With no argument, reads the ambient database."
  {:malli/schema
   [:function
    [:=> [:cat] ::datom-count]
    [:=> [:catn [::db ::db-val]] ::datom-count]]}
  ([] (datom-count @*conn*))
  ([db]
   (if-some [eavt (:eavt db)]
     (di/-count eavt)
     (count (d/datoms db :eavt)))))

(defn bootstrap-row-ids
  "Entity ids whose first assertion came through boot or config.

   The IDENTITY datom was transacted through the boot process (the program
   graph index + seed) or config process (the reconcile-managed
   declarative set: routes + skills) — the rows the boot minted.
   Everything else is data this cluster
   added AFTER bootstrap. Per-ROW, never per-kind-name: an
   agent-authored `:seon.fn` row is NOT in this set; a boot-indexed
   one is. THE shared provenance derivation — [[core-attr-namespaces]],
   findings, and the /data browser all read this one mechanism."
  {:malli/schema [:=> [:catn [::db ::db-val]] ::row-ids]}
  [db]
  (let [seed-txs (into #{}
                       (map first)
                       (query {::db db
                               ::query '[:find ?tx
                                         :where
                                         [?tx :seon.db/process ?process]
                                         (or
                                           [?process :seon.db.process/id
                                            :seon.db.process/boot]
                                           [?process :seon.db.process/id
                                            :seon.db.process/config])]}))
        triples  (query {::db db
                         ::query '[:find ?e ?a ?tx :where [?e ?a _ ?tx]]})
        first-tx (reduce (fn [m [e _ tx]]
                           (update m e #(if % (min % tx) tx)))
                         {} triples)]
    (into #{}
          (keep (fn [[e tx]]
                  (when (contains? seed-txs tx) e)))
          first-tx)))

;; --- provenance-scoped managed population (the reconcile handle) ----------
;; Generalizes [[bootstrap-row-ids]]'s boot/config first-tx derivation to an
;; arbitrary set of stable database process ids and pairs each managed entity
;; with the `:db.unique/identity`
;; datom(s) it carries — the population `seon.state/reconcile!` diffs a
;; desired set against. Same single `[?e ?a ?v ?tx]` scan + min-tx-process
;; reduce; NEVER a per-kind / per-identity-attr AEVT loop.
(schema/register! ::managed-scope [:set ::process/id])
(schema/register! ::managed-identity-attrs [:set :keyword])
;; `[identity-attr identity-value]`. The value spans heterogeneous registered
;; id types (string ids, keyword route names), hence `:any` for the value.
(schema/register! ::identity-pair [:tuple :keyword :any])
(schema/register! ::managed-identities [:map-of :int [:set ::identity-pair]])
(schema/register!
  ::managed-identities-request
   [:map
   [::managed-scope ::managed-scope]
   [::managed-identity-attrs {:optional true} ::managed-identity-attrs]
   [:seon.db/db   {:optional true} :seon.db/db]
   [::conn        {:optional true} ::conn]])

(defn managed-identities
  "Map each managed eid to its `[identity-attr identity-value]` set.

   Every entity
   whose FIRST-assertion (min-tx) process is in `:seon.db/managed-scope`,
   paired with the `:db.unique/identity` datom(s) it carries. PURE
   PROVENANCE — ONE `[?e ?a ?v ?tx]` scan + a min-tx-process reduce (the same
   derivation as [[bootstrap-row-ids]], generalized to an arbitrary process
   scope), never a per-kind / per-identity-attr
   AEVT loop. Eids carrying NO identity attr (component children, tx /
   schema-def rows) are OMITTED: they are removed via their parent's
   component cascade, never directly. THE managed-population
   [[seon.state/reconcile!]] diffs a desired set against. Optional
   `:seon.db/managed-identity-attrs` limits the population to entities carrying
   one of those identity attributes; this prevents a process that authors
   several independent desired sets from sweeping facts outside the set being
   reconciled. Reads default to `*conn*`; pass `:seon.db/db` or
   `:seon.db/conn` for another store."
  {:malli/schema [:=> [:cat ::managed-identities-request] ::managed-identities]}
  [{::keys [managed-scope managed-identity-attrs conn]
    db :seon.db/db :or {conn *conn*}}]
  (let [db        (or db @(internal/resolve-conn conn))
        triples   (query {::db db ::query '[:find ?e ?a ?v ?tx
                                            :where [?e ?a ?v ?tx]]})
        tx-process (into {} (query {::db db
                                    ::query '[:find ?tx ?pid
                                              :where
                                              [?tx :seon.db/process ?process]
                                              [?process :seon.db.process/id ?pid]]}))
        first-tx  (reduce (fn [m [e _ _ tx]]
                            (update m e #(if % (min % tx) tx)))
                          {} triples)
        managed   (into #{}
                        (keep (fn [[e tx]]
                                (when (contains? managed-scope (get tx-process tx)) e)))
                        first-tx)]
    (reduce (fn [m [e a v _]]
              (if (and (contains? managed e)
                       (schema/identity-attr? a)
                       (or (nil? managed-identity-attrs)
                           (contains? managed-identity-attrs a)))
                (update m e (fnil conj #{}) [a v])
                m))
            {} triples)))

(defn core-attr-namespaces
  "Attr namespaces whose `:seon.schema/key` row is a bootstrap row.

   ([[bootstrap-row-ids]].) The namespaces the compiled
   core's boot index registered, as opposed to agent-registered ones.
   The 2-arity takes a precomputed bootstrap set so one scan can serve
   multiple consumers."
  {:malli/schema
   [:function
    [:=> [:catn [::db ::db-val]] ::attr-ns-set]
    [:=> [:catn [::db ::db-val] [::bootstrap-rows ::row-ids]] ::attr-ns-set]]}
  ([db] (core-attr-namespaces db (bootstrap-row-ids db)))
  ([db bootstrap-rows]
   (into #{}
         (keep (fn [[s k]]
                 (when (contains? bootstrap-rows s)
                   (some-> (namespace k) keyword))))
         (query {::db db
                 ::query '[:find ?s ?k :where [?s :seon.schema/key ?k]]}))))

;; ---------------------------------------------------------------------------
;; Error-persistence hooks — `seon.error/record!`'s write path, INJECTED here
;; because the require direction is db→error (seon.db.internal requires
;; seon.error, so seon.error can never require this ns). Runs at namespace
;; load; a hot reload re-installs closures over the current fns. Both hooks
;; are nil-safe pre-boot (no conn yet ⇒ record! buffers in memory).
;; ---------------------------------------------------------------------------

(error/set-db-hooks!
  {:seon.error/transact! (fn [tx-data]
                           (when *conn*
                             (transact! {::tx-data tx-data})))
   :seon.error/coordinate (fn []
                            (when *conn*
                              (head-coordinate @*conn*)))})

;; ---------------------------------------------------------------------------
;; Config-view seam — `seon.config`'s accessors read the `:seon.config`
;; singleton through this INJECTED reader (require dir is db→config, so config
;; can't require db; mirror the error-hook seam above). Returns the DECODED
;; singleton map (the three mixed-`:or` collection knobs decoded) or nil when
;; no conn / the singleton is not yet seeded — `seon.config/config-view` then
;; falls back to the boot manifest resolve (the pre-conn sliver).
;; ---------------------------------------------------------------------------

;; Single-slot memo keyed on the immutable head's canonical coordinate — the config
;; accessors are hot (value.cljs reads several caps per rendered node), and the
;; conn's head is stable across a synchronous render stretch, so this collapses
;; those reads to ONE entity lookup. The cache retains no database value and
;; self-invalidates whenever database, branch, commit, or t changes. The key is
;; the LIVE `@*conn*` head, not the turn's frozen db (the zero-arg accessors
;; carry no db) — a transact landing mid-turn means later accessor reads see the
;; newer singleton; acceptable for dials.
(defonce ^:private !config-view-cache
  (atom {::cached-config-coordinate nil ::cached-config-view nil}))

(defn- read-config-singleton
  "Decode the `:seon.config` singleton off `db`, or nil when unseeded."
  [db]
  (when (contains? (installed-schema db) :seon.config/id)
    (let [ent (entity {::ref [:seon.config/id config/cluster-config-id] ::db db})]
      (when (:seon.config/id ent)
        (into {}
              (map (fn [[k v]]
                     [k (if (internal/edn-encoded-attr? k) (decode-edn-value k v) v)]))
              ent)))))

(config/set-db-config-view!
  (fn config-singleton-view []
    (when *conn*
      (let [db @*conn*
            coordinate (head-coordinate db)
            c  @!config-view-cache]
        (if (= coordinate (::cached-config-coordinate c))
          (::cached-config-view c)
          (let [view (read-config-singleton db)]
            (reset! !config-view-cache
                    {::cached-config-coordinate coordinate
                     ::cached-config-view view})
            view))))))
