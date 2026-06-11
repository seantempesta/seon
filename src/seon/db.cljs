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
    [seon.db.internal :as internal]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — every request/response shape, registered at namespace load.
;; ---------------------------------------------------------------------------

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::conn :any)
(schema/register! ::tx-meta :map)   ; positional 3-arity convenience slot

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts    {:optional true} ::opts]
   [::conn    {:optional true} ::conn]])

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

(schema/register!
  ::transact-response
  [:or
   [:map
    [::ok?       [:= true]]
    [::tx-report :any]]
   [:map
    [::ok?       [:= false]]
    [::error     ::error]
    ;; When the substrate translated a cryptic datahike message into a
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
(schema/register! ::origin          [:enum :user :agent :system :replay :substrate-seed :test-run])
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
  "Fresh 14-char LLM-readable id, `<3-letter-random>-<YYMMDDHHmm>`,
   e.g. `Kpx-2605232138`. Datahike's tx-id remains the canonical
   creation order for sub-minute sorting."
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
  [id]
  (when (and (string? id) (= 14 (count id)) (= \- (nth id 3)))
    (subs id 4)))

;; ---------------------------------------------------------------------------
;; The agent's universe + fiber-local context scopes
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "The runtime's datahike connection. Bound at session start; never
   threaded through agent call sites. Reads default to `@*conn*` (a db
   value); writes route through this conn's writer. All sessions for
   the same user share this conn — sessions are entities in it, not
   partitions of it."
  nil)

(defn current-tx-context
  "The active tx-context map, or nil outside a [[with-tx-context]]
   scope. Fiber-local across awaits (AsyncLocalStorage), safe under
   concurrent agents. Auto-merged into every `transact!`'s `:tx-meta`;
   explicit call-site `:tx-meta` keys win per-key."
  []
  (internal/current-tx-context))

(defn current-agent-id
  "The active agent-id (string), or nil outside a [[with-agent]] scope.
   Fiber-local across awaits. The standard accessor for any code that
   needs to know whose universe it's running in."
  []
  (internal/current-agent-id))

(defn with-agent
  "Establish an agent-id scope for the dynamic extent of `f` (a 0-arg
   fn). Inside `f` — including across `await`s and any Promises it
   returns — `(current-agent-id)` returns `agent-id`. Nesting: the
   inner scope wins, the outer restores on exit."
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
  [ctx-map f]
  (internal/run-with-tx-context ctx-map f))

;; ---------------------------------------------------------------------------
;; Write path
;; ---------------------------------------------------------------------------

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
   explosion) returns as data:

     {::db/ok? true  ::db/tx-report <datahike report>}   ; success
     {::db/ok? false ::db/error <error map>}             ; failure
     ;; + ::db/raw-error <original message> when the substrate
     ;; translated a cryptic datahike error into a guiding one

   The error's `:seon.error/data` carries `:seon.error/kind` —
   `:user-input` (fix tx-data and retry) vs `:substrate-bug` (the pod
   survived; report it, don't retry blindly).

   Before committing it validates shape, attrs, and values; installs
   datahike schema for any newly-registered attr; and auto-merges the
   active [[with-tx-context]] / [[with-agent]] context into `:tx-meta`."
  ;; NOTE: ^:async fns are skipped by instrumentation today, so this
  ;; schema is the discoverable contract; the internal guards enforce.
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

   GUARDED against silent typos (the sibling of [[pull]]'s guard): a
   `:where` clause naming an attribute that is neither installed on
   the db nor registered in seon.schema throws a legible error naming
   the attr(s) and the fix, instead of silently returning #{}.
   Registered attrs with no data yet behave exactly as datahike
   defines (empty result / get-else default)."
  ;; Pure-variadic body so CLJS malli.instrument wraps every arity.
  {:malli/schema
   [:function
    [:=> [:cat ::query-request] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]
                [::inputs [:+ :any]]] :any]]}
  [& args]
  (if (= 1 (count args))
    (let [{::keys [query args db conn] :or {conn *conn* args []}} (first args)
          db (or db @(internal/resolve-conn conn))]
      (assert-known-query-attrs! db query)
      (apply d/q query db args))
    (let [[q db & inputs] args]
      (assert-known-query-attrs! db q)
      (apply d/q q db inputs))))

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

   FilteredDB (the inspector's per-agent view) doesn't implement
   ILookup — `(:schema db)` THROWS; the schema is conn-level (a filter
   can't change it), so read through to the wrapped db. Returns `{}`
   for a nil/schema-less db. `:any` input — the db value is a datahike
   runtime handle (third-party boundary)."
  {:malli/schema [:=> [:catn [::db :any]] :map]}
  [db]
  (or (try (:schema db)
           (catch :default _
             (:schema (.-unfiltered-db ^js db))))
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
;; The sibling of the pull guard below (65dfc90), adapted to what
;; datalog actually does (probed live 2026-06-11): d/q NEVER throws on
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
    [:=> [:catn [::db ::db-val] [::selector [:vector :any]] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [pull-pattern ref db conn] :or {conn *conn*}} req
         db (or db @(internal/resolve-conn conn))]
     (guarded-pull db pull-pattern ref)))
  ([db selector eid]
   (guarded-pull db selector eid)))

(defn entity
  "Look up an entity by eid or lookup-ref. Sync. Returns a datahike
   entity (lazy map-like).

   - map-in:     (db/entity {::db/ref [::name \"Alpha\"]})
   - positional, mirroring datahike: (db/entity <db> eid)"
  {:malli/schema
   [:function
    [:=> [:cat ::entity-request] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [ref db conn] :or {conn *conn*}} req
         db (or db @(internal/resolve-conn conn))]
     (d/entity db ref)))
  ([db eid]
   (d/entity db eid)))

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
  [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema
  "Datahike schema entries for the 7 `:seon.db/*` tx-meta attrs."
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
  ([] (assert-preconditions! {}))
  ([{::keys [conn] :or {conn *conn*}}]
   (internal/assert-preconditions! conn)))

;; ---------------------------------------------------------------------------
;; Store inventory — what's in the shared store, one query away.
;; ---------------------------------------------------------------------------

(schema/register! ::kind    :keyword)
(schema/register! ::id-attr :keyword)
(schema/register! ::rows    :int)
(schema/register! ::inventory-row
  [:map
   [::kind    ::kind]
   [::id-attr ::id-attr]
   [::rows    ::rows]])

(defn store-inventory
  "What the shared store holds RIGHT NOW — one row per entity KIND:
   the kind (the id-attr's keyword namespace), its identity attribute,
   and a live row count. Derived entirely from the db (the installed
   schema's `:db.unique/identity` attrs + one count per kind), so a
   kind appears here the moment its first row lands and the counts are
   as-of the db you pass. Re-run it whenever you want fresh numbers —
   this is an ordinary query, not a snapshot.

   Check this BEFORE researching or registering anything new: a kind
   that already exists means prior agents stored knowledge you can
   query (datalog its id-attr's namespace), and a shape that already
   exists must be REUSED, never forked in parallel.

   ;; what's already here?
   (seon.db/store-inventory)
   ;; => [{:seon.db/kind :my.kb.codebase
   ;;      :seon.db/id-attr :my.kb.codebase/question
   ;;      :seon.db/rows 14}
   ;;     {:seon.db/kind :seon.agent  …}
   ;;     …]

   ;; then read a kind's rows, e.g.:
   (seon.db/query {:seon.db/query
                   '[:find ?q :where [?e :my.kb.codebase/question ?q]]})"
  {:malli/schema
   [:function
    [:=> [:cat] [:vector ::inventory-row]]
    [:=> [:cat [:map [::db {:optional true} :any]
                     [::conn {:optional true} ::conn]]]
         [:vector ::inventory-row]]]}
  ([] (store-inventory {}))
  ([{::keys [db conn] :or {conn *conn*}}]
   (let [db (or db @(internal/resolve-conn conn))]
     (->> (installed-schema db)
          (keep (fn [[attr props]]
                  (when (and (= :db.unique/identity (:db/unique props))
                             (not (system-pull-attr? attr)))
                    attr)))
          (sort-by str)
          (mapv (fn [attr]
                  {::kind    (keyword (namespace attr))
                   ::id-attr attr
                   ::rows    (count (query [:find '?e
                                            :where ['?e attr '_]]
                                           db))}))))))
