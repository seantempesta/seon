(ns seon.db.examples
  "Worked, runnable database examples — your copyable DB manual. Every
   form here COMPILES and is exercised by `seon.db.examples-test`, so the
   patterns can't bit-rot.

   Read it as recipes: each `defn` is one pattern (schema, write, read,
   pull, inventory) over a neutral demo domain — a personal reading log
   (`:my.reading/*`). Copy a body, swap in your own `:my.<domain>/*`
   attrs. The aliases your home namespace already has are used
   throughout: `db` (seon.db) and `schema` (seon.schema). seon.db's own
   docstrings remain the full reference; this is the worked-example layer.

   ILLUSTRATIVE, NOT LIVE: the `:my.reading/*` schema and the reading-log
   rows these recipes build are NOT registered in your cluster and NOT in
   your store — they only come into being when YOU call these fns (the
   test exercises them in its own throwaway db, never yours). Your store
   starts empty; `(db/store-inventory)` is the truth for what's actually
   there. So never report the demo reading log as the human's real data.

   Two async facts to copy correctly:
     - reads (`db/query` / `db/pull` / `db/entity`) are SYNCHRONOUS.
     - `db/transact!` returns a Promise in compiled code; inside a fn you
       `(await …)` it (see [[build-reading-log!]]). At the REPL top level
       the runtime auto-awaits, so you read the envelope directly.

   Every call below omits the db arg — it's auto-injected."
  (:require
    [seon.db :as db]
    [seon.schema :as schema]))

;;; ───────────────────────────────────────────────────────────────────────
;;; The one map-out shape these examples return ([[reading-stats]]).
;;; ───────────────────────────────────────────────────────────────────────

(schema/register!
  ::reading-log-summary
  [:map
   [::count        :int]
   [::rating-total :int]
   [::tag-counts   [:map-of :keyword :int]]])

;;; ───────────────────────────────────────────────────────────────────────
;;; 1. SCHEMA — register the type, the system derives datahike storage.
;;;    register! teaches the Malli registry; the FIRST transact! that uses
;;;    an attr installs its datahike schema automatically.
;;; ───────────────────────────────────────────────────────────────────────

(defn- register-reading-schema!
  "The schema shapes you reuse everywhere: an `:seon.db/identity` natural
   key, plain scalars, a cardinality-many `:vector`, and refs — a PLAIN
   ref and a COMPONENT ref (its target is owned by the parent)."
  []
  (schema/register! :my.reading/id     [:string {:seon.db/identity true}]) ; identity / natural key
  (schema/register! :my.reading/title  :string)                            ; plain scalar
  (schema/register! :my.reading/rating :int)                               ; plain scalar
  (schema/register! :my.reading/tags   [:vector :keyword])                 ; cardinality-many
  (schema/register! :my.reading/author :seon.db/ref)                       ; plain ref → another entity
  (schema/register! :my.reading/notes                                      ; component ref (many)
                    [:vector {:seon.db/component true} :seon.db/ref])
  (schema/register! :my.reading.author/id   [:string {:seon.db/identity true}])
  (schema/register! :my.reading.author/name :string)
  (schema/register! :my.reading.note/id   [:string {:seon.db/identity true}])
  (schema/register! :my.reading.note/body :string))

;;; ───────────────────────────────────────────────────────────────────────
;;; 2. WRITES — transact!. Each returns the envelope Promise (await it in a
;;;    fn). ALWAYS read the envelope: an eval can succeed yet the write did
;;;    NOT happen (`:seon.db/ok? false`).
;;; ───────────────────────────────────────────────────────────────────────

(defn seed-readings!
  "ADD entity maps, linking two refs each the idiomatic way:

   - SAME-TX link (the author doesn't exist yet): give the author a
     `:db/id` TEMPID and put that SAME tempid in the reading's
     `:my.reading/author` ref slot — datahike resolves both to one new
     entity. A tempid is an arbitrary label (here `\"author-alice\"`,
     deliberately NOT the `\"alice\"` identity value, so it's clear the
     ref slot carries the tempid, not a data value). Lookup-refs do NOT
     resolve against not-yet-committed entities. To link to an
     ALREADY-COMMITTED author instead, put a lookup-ref
     `[:my.reading.author/id \"alice\"]` in the slot — never a bare value.
   - INLINE COMPONENT (`:my.reading/notes`): a component ref's child is
     built from the nested map — no tempid needed.

   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:cat] :any]}
  []
  (db/transact!
    {::db/tx-data
     [;; authors — arbitrary :db/id tempid labels the readings link to below
      {:db/id "author-alice" :my.reading.author/id "alice" :my.reading.author/name "Alice Munro"}
      {:db/id "author-basho" :my.reading.author/id "basho" :my.reading.author/name "Matsuo Basho"}
      ;; readings — :my.reading/author holds the author's TEMPID (same tx)
      {:my.reading/id "r1" :my.reading/title "Lives of Girls and Women"
       :my.reading/rating 5 :my.reading/tags [:fiction :canlit]
       :my.reading/author "author-alice"
       :my.reading/notes [{:my.reading.note/id "n1"
                           :my.reading.note/body "Re-read the opening chapter."}]}
      {:my.reading/id "r2" :my.reading/title "Dance of the Happy Shades"
       :my.reading/rating 4 :my.reading/tags [:fiction :short-stories]
       :my.reading/author "author-alice"}
      {:my.reading/id "r3" :my.reading/title "The Narrow Road to the Deep North"
       :my.reading/rating 5 :my.reading/tags [:poetry :travel]
       :my.reading/author "author-basho"}]}))

(defn rename-reading!
  "UPSERT by identity: transacting the SAME `:my.reading/id` updates that
   entity in place — no duplicate. OMITTED keys are left unchanged (absent
   ≠ retract). Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string] [::new-title :string]] :any]}
  [id new-title]
  (db/transact! {::db/tx-data [{:my.reading/id id :my.reading/title new-title}]}))

(defn clear-rating!
  "Clear ONE attr — retraction is EXPLICIT (omitting a key only leaves it
   unchanged). Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db/retract [:my.reading/id id] :my.reading/rating]]}))

(defn replace-tags!
  "Replace a cardinality-many attr. Transacting tags only ADDS to the
   set — to REPLACE you must clear the old values first. A value-less
   retract `[:db/retract ref attr]` retracts EVERY current value of the
   attr; bundle it BEFORE the add-map in ONE tx. tx-data is applied in
   order, so the old set is gone before the new set lands — correct even
   when the sets overlap (the surviving value is retracted, then re-added).
   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string] [::tags [:vector :keyword]]] :any]}
  [id tags]
  (db/transact!
    {::db/tx-data [[:db/retract [:my.reading/id id] :my.reading/tags]
                   {:my.reading/id id :my.reading/tags tags}]}))

(defn delete-reading!
  "Delete the whole entity (component children go with it).
   Returns the transact! envelope Promise."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db.fn/retractEntity [:my.reading/id id]]]}))

;;; ───────────────────────────────────────────────────────────────────────
;;; 3. QUERY — Datalog. db auto-injects from *conn* (omit it); `:in` inputs
;;;    come AFTER the query. Pick the :find shape by what you want back.
;;; ───────────────────────────────────────────────────────────────────────

(defn titles
  "Collection find `[?t ...]` — ONE column, back as a vector."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (db/query '[:find [?t ...] :where [?e :my.reading/title ?t]]))

(defn title+rating
  "Relation find — a SET of `[title rating]` tuples, JOINING two attrs on
   the same entity (`?e` binds both clauses)."
  {:malli/schema [:=> [:cat] [:set [:tuple :string :int]]]}
  []
  (db/query '[:find ?title ?rating
              :where [?e :my.reading/title ?title] [?e :my.reading/rating ?rating]]))

(defn titles-by-author
  "`:in`-bound input + REF-JOIN. A ref stores an EID — match the author by
   NAME by JOINING through `:my.reading/author`, never by putting the name
   in the ref slot."
  {:malli/schema [:=> [:catn [::author-name :string]] [:vector :string]]}
  [author-name]
  (db/query '[:find [?title ...] :in $ ?name
              :where [?a :my.reading.author/name ?name]
                     [?r :my.reading/author ?a]
                     [?r :my.reading/title ?title]]
            author-name))

(defn reading-stats
  "Aggregate the log toward a goal: total count (scalar `.`), summed
   ratings (`sum`), and per-tag counts (grouped aggregate → map). The
   analysis you build ON TOP of stored data — the point of storing it.

   FOOTGUN, and the cure: an aggregate runs over the DEDUPLICATED set of
   projected tuples. `(sum ?r)` alone would collapse two readings rated 5
   into one before summing. `:with ?e` keeps each entity's row distinct
   (without projecting `?e`), so repeated values still count."
  {:malli/schema [:=> [:cat] ::reading-log-summary]}
  []
  {::count        (or (db/query '[:find (count ?e) . :where [?e :my.reading/id]]) 0)
   ::rating-total (or (db/query '[:find (sum ?r) . :with ?e
                                  :where [?e :my.reading/rating ?r]]) 0)
   ::tag-counts   (into {} (db/query '[:find ?tag (count ?e)
                                       :where [?e :my.reading/tags ?tag]]))})

;;; ───────────────────────────────────────────────────────────────────────
;;; 4. PULL / ENTITY — read one entity by lookup-ref `[identity-attr value]`.
;;; ───────────────────────────────────────────────────────────────────────

(defn reading-detail
  "Pull by LOOKUP-REF. `[*]` inlines every attr; a COMPONENT child
   (`:my.reading/notes`) comes back as a full nested map, while a PLAIN
   ref (`:my.reading/author`) is just `{:db/id N}` UNTIL you NAME it with a
   sub-pattern to pull its fields."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/pull '[* {:my.reading/author [:my.reading.author/name]}]
           [:my.reading/id id]))

(defn reading-entity
  "Look up an entity by lookup-ref — a PLAIN touched map (`:db/id` + every
   attr), nil if it doesn't resolve. A ref attr reads back as `{:db/id N}`;
   drill in with a follow-up `entity`/`pull`."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/entity [:my.reading/id id]))

;;; ───────────────────────────────────────────────────────────────────────
;;; 5. INVENTORY — what's in the store right now, one search away.
;;; ───────────────────────────────────────────────────────────────────────

(defn inventory
  "Discovery call: every kind the store holds, one row per attribute
   namespace with per-attr counts. Run it BEFORE registering a new kind —
   if a shape already exists, reuse it."
  {:malli/schema [:=> [:cat] [:vector :any]]}
  []
  (db/store-inventory))

;;; ───────────────────────────────────────────────────────────────────────
;;; 6. SELF-CONSTRUCTION WORKFLOW — the disposition to adopt: store data,
;;;    then build functions that turn it into answers. Switch into a domain,
;;;    register its schema, transact rows, run the analysis fn you wrote.
;;; ───────────────────────────────────────────────────────────────────────

(defn ^:async build-reading-log!
  "End-to-end: register the `:my.reading/*` schema, seed rows, then run
   [[reading-stats]] over them. `^:async` because it AWAITS the write
   before reading (the REPL top level would auto-await for you; a fn does
   not). Resolves to the stats summary, or the failure envelope if the
   write was rejected."
  {:malli/schema [:=> [:cat] :any]}
  []
  (register-reading-schema!)
  (let [{::db/keys [ok?] :as envelope} (await (seed-readings!))]
    (if ok?
      (reading-stats)
      envelope)))
