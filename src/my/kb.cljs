(ns my.kb
  "Your knowledge base — AND your worked manual for reading and writing the
   shared database. Knowledge here is SCHEMA'D DATA, never a pile of text:
   you design a real schema for each kind of thing you know, then transact
   and query it. The functions below are runnable RECIPES — every form
   COMPILES and is exercised by `my.kb-test`, so the patterns can't
   bit-rot. Read them as your DB manual: each `defn` is one pattern
   (schema, write, read, pull, aggregate, inventory). Copy a body, swap in
   your own `:my.<domain>/*` attrs.

   The aliases your home namespace already has are the ones used here: `db`
   (seon.db) and `schema` (seon.schema). seon.db's own docstrings remain the
   full reference; this is the worked-example layer.

   WHERE KNOWLEDGE LIVES. Each kind lives in its own `my.kb.<domain>`
   sub-namespace with a REAL schema (`my.kb.source/*`, `my.kb.paper/*`,
   `my.kb.codebase/*`, …) — designing one is the same skill as modelling
   your human's data. Do NOT build a general memory-markdown blob; storing
   large text is allowed when your human wants it, but it is never the
   default. The shared provenance attrs registered at the top (`:my.kb/*`)
   are the register-ONCE shapes every domain REFERENCES rather than
   re-inventing per domain (see [[remember-sources!]], where the findings
   reuse them).

   ILLUSTRATIVE DATA, REAL TOOL: the `:my.kb.source/*` schema and the rows
   the recipes below build are NOT registered or stored until YOU call these
   fns (`my.kb-test` exercises them in its own throwaway db, never yours).
   Your store starts EMPTY — `(db/store-inventory)` is the truth for what is
   actually there, so never report the example sources as your human's real
   data. The PATTERNS are real and the fns are callable; the sample domain
   is just the teaching vehicle.

   Two async facts to copy correctly:
     - reads (`db/query` / `db/pull` / `db/entity`) are SYNCHRONOUS.
     - `db/transact!` returns a Promise in compiled code; inside a fn you
       `(await …)` it (see [[build-kb-example!]]). At the REPL top level the
       runtime auto-awaits, so you read the envelope directly.

   Every call below omits the db/conn arg — it auto-injects from the bound
   connection. There is no store!/consult API: `db/transact!` and `db/query`
   over your domain schemas ARE the knowledge base."
  (:require
    [seon.db :as db]
    [seon.schema :as schema]))

;;; ───────────────────────────────────────────────────────────────────────
;;; SHARED PROVENANCE — registered ONCE, referenced by every domain.
;;; The register-once rule (don't fork your own source-path/confidence per
;;; domain): a fact ALWAYS carries :my.kb/source-line — the 1-based line you
;;; read it from; a fact spanning lines adds :my.kb/source-line-end (a range
;;; is TWO ints on these shared attrs, never a "460-470" string and never a
;;; forked plural attr); a single-line fact omits the end. These four attrs
;;; install at boot so any domain schema can reference them before its first
;;; tx lands.
;;; ───────────────────────────────────────────────────────────────────────

(schema/register! ::source-path :string)     ; repo-relative or absolute file path
(schema/register! ::source-line :int)        ; 1-based line the fact was read from
                                             ; (the FIRST line, when citing a range)
(schema/register! ::source-line-end :int)    ; inclusive last line of a multi-line
                                             ; fact; single-line facts omit it
(schema/register! ::verified-at :inst)       ; when the fact was last verified
(schema/register! ::confidence  [:enum :verified :inferred])

;;; ───────────────────────────────────────────────────────────────────────
;;; The one map-out shape the recipes return ([[source-stats]]). A map-out
;;; is itself a registered schema — the renderer and the next agent can
;;; discover its shape, and instrumentation checks it.
;;; ───────────────────────────────────────────────────────────────────────

(schema/register!
  ::source-summary
  [:map
   [::count        :int]
   [::rating-total :int]
   [::topic-counts [:map-of :keyword :int]]])

;;; ───────────────────────────────────────────────────────────────────────
;;; 1. SCHEMA — register the type, the system DERIVES datahike storage.
;;;    register! teaches the Malli registry; the FIRST transact! that uses
;;;    an attr installs its datahike schema automatically (lazy install).
;;;    These live inside a fn (not at ns load) so reading this manual never
;;;    registers the sample domain — it exists only once you run a recipe.
;;; ───────────────────────────────────────────────────────────────────────

(defn- register-kb-schema!
  "Register the sample `:my.kb.source/*` knowledge domain — the schema
   shapes you reuse everywhere: an `:seon.db/identity` natural key, plain
   scalars, a cardinality-many `:vector`, and refs — a PLAIN ref (the
   author) and a COMPONENT ref (the findings, owned by their source).
   Findings REUSE the shared `:my.kb/*` provenance attrs rather than
   forking their own — the register-once rule in action."
  []
  (schema/register! :my.kb.source/id     [:string {:seon.db/identity true}]) ; identity / natural key
  (schema/register! :my.kb.source/title  :string)                            ; plain scalar
  (schema/register! :my.kb.source/rating :int)                               ; plain scalar (how useful, 1-5)
  (schema/register! :my.kb.source/topics [:vector :keyword])                 ; cardinality-many
  (schema/register! :my.kb.source/author :seon.db/ref)                       ; plain ref → another entity
  (schema/register! :my.kb.source/findings                                   ; component ref (many)
                    [:vector {:seon.db/component true} :seon.db/ref])
  (schema/register! :my.kb.author/id   [:string {:seon.db/identity true}])
  (schema/register! :my.kb.author/name :string)
  (schema/register! :my.kb.finding/id   [:string {:seon.db/identity true}])
  (schema/register! :my.kb.finding/text :string))

;;; ───────────────────────────────────────────────────────────────────────
;;; 2. WRITES — transact!. Each returns the envelope Promise (await it in a
;;;    fn). ALWAYS read the envelope: an eval can succeed yet the write did
;;;    NOT happen (`:seon.db/ok? false`).
;;; ───────────────────────────────────────────────────────────────────────

(defn remember-sources!
  "ADD source entities, linking two refs each the idiomatic way:

   - SAME-TX link (the author doesn't exist yet): give the author a
     `:db/id` TEMPID and put that SAME tempid in the source's
     `:my.kb.source/author` ref slot — datahike resolves both to one new
     entity. A tempid is an arbitrary label (here `\"author-mccarthy\"`,
     deliberately NOT the `\"mccarthy\"` identity value, so it's clear the
     ref slot carries the tempid, not a data value). A lookup-ref does NOT
     resolve forward to an entity that appears only later in the tx. To
     link to an ALREADY-COMMITTED author instead, put a lookup-ref
     `[:my.kb.author/id \"mccarthy\"]` in the slot — never a bare value.
   - INLINE COMPONENT (`:my.kb.source/findings`): a component ref's child
     is built from the nested map — no tempid needed. Each finding REUSES
     the shared `:my.kb/*` provenance attrs (source-path/confidence) — the
     register-once rule, not a forked per-finding copy.

   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:cat] :any]}
  []
  (db/transact!
    {::db/tx-data
     [;; authors — arbitrary :db/id tempid labels the sources link to below
      {:db/id "author-mccarthy" :my.kb.author/id "mccarthy" :my.kb.author/name "John McCarthy"}
      {:db/id "author-okasaki"  :my.kb.author/id "okasaki"  :my.kb.author/name "Chris Okasaki"}
      ;; sources — :my.kb.source/author holds the author's TEMPID (same tx)
      {:my.kb.source/id "s1" :my.kb.source/title "Recursive Functions of Symbolic Expressions"
       :my.kb.source/rating 5 :my.kb.source/topics [:lisp :foundations]
       :my.kb.source/author "author-mccarthy"
       :my.kb.source/findings [{:my.kb.finding/id "f1"
                                :my.kb.finding/text "Code and data share one representation."
                                ::source-path "papers/mccarthy-1960.pdf"
                                ::confidence  :verified}]}
      {:my.kb.source/id "s2" :my.kb.source/title "LISP 1.5 Programmer's Manual"
       :my.kb.source/rating 4 :my.kb.source/topics [:lisp :reference]
       :my.kb.source/author "author-mccarthy"}
      {:my.kb.source/id "s3" :my.kb.source/title "Purely Functional Data Structures"
       :my.kb.source/rating 5 :my.kb.source/topics [:functional :data-structures]
       :my.kb.source/author "author-okasaki"}]}))

(defn retitle-source!
  "UPSERT by identity: transacting the SAME `:my.kb.source/id` updates that
   entity in place — no duplicate. OMITTED keys are left unchanged (absent
   ≠ retract). Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string] [::new-title :string]] :any]}
  [id new-title]
  (db/transact! {::db/tx-data [{:my.kb.source/id id :my.kb.source/title new-title}]}))

(defn clear-rating!
  "Clear ONE attr — retraction is EXPLICIT (omitting a key only leaves it
   unchanged). A value-less retract `[:db/retract ref attr]` removes the
   current value. Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db/retract [:my.kb.source/id id] :my.kb.source/rating]]}))

(defn replace-topics!
  "Replace a cardinality-many attr. Transacting topics only ADDS to the
   set — to REPLACE you must clear the old values first. A value-less
   retract `[:db/retract ref attr]` retracts EVERY current value of the
   attr; bundle it BEFORE the add-map in ONE tx. tx-data is applied in
   order, so the old set is gone before the new set lands — correct even
   when the sets overlap (the surviving value is retracted, then re-added).
   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string] [::topics [:vector :keyword]]] :any]}
  [id topics]
  (db/transact!
    {::db/tx-data [[:db/retract [:my.kb.source/id id] :my.kb.source/topics]
                   {:my.kb.source/id id :my.kb.source/topics topics}]}))

(defn forget-source!
  "Delete the whole entity (component children — the findings — go with it).
   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db.fn/retractEntity [:my.kb.source/id id]]]}))

;;; ───────────────────────────────────────────────────────────────────────
;;; 3. QUERY — Datalog. db auto-injects (omit it); `:in` inputs come AFTER
;;;    the query. Pick the :find shape by what you want back: a bare relation
;;;    is a SET of tuples; `[?x ...]` is one column as a vector; `?x .` is a
;;;    single scalar; `[?a ?b]` is one tuple.
;;; ───────────────────────────────────────────────────────────────────────

(defn titles
  "Collection find `[?t ...]` — ONE column, back as a vector."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (db/query '[:find [?t ...] :where [?e :my.kb.source/title ?t]]))

(defn title+rating
  "Relation find — a SET of `[title rating]` tuples, JOINING two attrs on
   the same entity (`?e` binds both clauses)."
  {:malli/schema [:=> [:cat] [:set [:tuple :string :int]]]}
  []
  (db/query '[:find ?title ?rating
              :where [?e :my.kb.source/title ?title] [?e :my.kb.source/rating ?rating]]))

(defn titles-by-author
  "`:in`-bound input + REF-JOIN. A ref stores an EID — match the author by
   NAME by JOINING through `:my.kb.source/author`, never by putting the name
   in the ref slot."
  {:malli/schema [:=> [:catn [::author-name :string]] [:vector :string]]}
  [author-name]
  (db/query '[:find [?title ...] :in $ ?name
              :where [?a :my.kb.author/name ?name]
                     [?s :my.kb.source/author ?a]
                     [?s :my.kb.source/title ?title]]
            author-name))

(defn source-stats
  "Aggregate the knowledge base toward a question: total count (scalar
   `.`), summed ratings (`sum`), and per-topic counts (grouped aggregate →
   map). The analysis you build ON TOP of stored data — the point of
   storing it.

   FOOTGUN, and the cure: an aggregate runs over the DEDUPLICATED set of
   projected tuples. `(sum ?r)` alone would collapse two sources rated 5
   into one before summing. `:with ?e` keeps each entity's row distinct
   (without projecting `?e`), so repeated values still count."
  {:malli/schema [:=> [:cat] ::source-summary]}
  []
  {::count        (or (db/query '[:find (count ?e) . :where [?e :my.kb.source/id]]) 0)
   ::rating-total (or (db/query '[:find (sum ?r) . :with ?e
                                  :where [?e :my.kb.source/rating ?r]]) 0)
   ::topic-counts (into {} (db/query '[:find ?topic (count ?e)
                                       :where [?e :my.kb.source/topics ?topic]]))})

;;; ───────────────────────────────────────────────────────────────────────
;;; 4. PULL / ENTITY — read one entity by lookup-ref `[identity-attr value]`.
;;;    The lookup-ref IS the "by name" addressing — there is no pull-by-name.
;;; ───────────────────────────────────────────────────────────────────────

(defn source-detail
  "Pull by LOOKUP-REF. `[*]` inlines every attr; a COMPONENT child
   (`:my.kb.source/findings`) comes back as a full nested map, while a PLAIN
   ref (`:my.kb.source/author`) is just `{:db/id N}` UNTIL you NAME it with
   a sub-pattern to pull its fields."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/pull '[* {:my.kb.source/author [:my.kb.author/name]}]
           [:my.kb.source/id id]))

(defn source-entity
  "Look up an entity by lookup-ref — a PLAIN touched map (`:db/id` + every
   attr), nil if it doesn't resolve. A ref attr reads back as `{:db/id N}`;
   drill in with a follow-up `entity`/`pull`."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/entity [:my.kb.source/id id]))

;;; ───────────────────────────────────────────────────────────────────────
;;; 5. INVENTORY — what's in the store right now, one call away. This is how
;;;    you CONSULT before researching: the inventory lists every attr
;;;    namespace with live rows; datalog those exact keywords for what you
;;;    need. Run it BEFORE registering a new kind — if a shape already
;;;    exists, reuse it.
;;; ───────────────────────────────────────────────────────────────────────

(defn inventory
  "Discovery call: which attributes hold data. Returns a map with
   `:seon.db/kinds` (one row per attr namespace with per-attr counts)
   plus `:seon.db/kind-count`/`:seon.db/attr-count`/`:seon.db/datom-count`."
  {:malli/schema [:=> [:cat] :map]}
  []
  (db/store-inventory))

;;; ───────────────────────────────────────────────────────────────────────
;;; 6. SELF-CONSTRUCTION WORKFLOW — the disposition to adopt: store data,
;;;    then build functions that turn it into answers. Switch into a domain,
;;;    register its schema, transact rows, run the analysis fn you wrote.
;;; ───────────────────────────────────────────────────────────────────────

(defn ^:async build-kb-example!
  "End-to-end: register the `:my.kb.source/*` schema, seed rows, then run
   [[source-stats]] over them. `^:async` because it AWAITS the write before
   reading (the REPL top level would auto-await for you; a fn does not).
   Resolves to the stats summary, or the failure envelope if the write was
   rejected. Run it once to see the whole loop, then build your OWN domain
   the same way."
  {:malli/schema [:=> [:cat] :any]}
  []
  (register-kb-schema!)
  (let [{::db/keys [ok?] :as envelope} (await (remember-sources!))]
    (if ok?
      (source-stats)
      envelope)))
