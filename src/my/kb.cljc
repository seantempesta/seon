(ns my.kb
  "Model durable knowledge as database attributes and connections.

   This namespace is the worked agent-facing guide to schema-first knowledge:
   defining identity and reference attributes, transacting source facts, and
   querying them by attribute presence. It demonstrates asynchronous database
   authority operations without supplying a universal domain model or sample
   knowledge; agents own their domain schemas in dedicated namespaces."
  (:require
    [seon.db :as db]
    [seon.schema :as schema]))

;;; SHARED PROVENANCE — registered ONCE, referenced by every domain (never
;;; fork your own). A line RANGE is two ints, never a "10-20" string.

(schema/register! ::source-path :string)       ; file the fact was read from
(schema/register! ::source-line :int)          ; 1-based line (FIRST line of a range)
(schema/register! ::source-line-end :int)      ; inclusive last line; omit if single-line
(schema/register! ::verified-at :inst)         ; when last verified
(schema/register! ::confidence  [:enum :verified :inferred])

;; The CLAIM itself — a shared content attr (the fact, in any domain), and
;; the natural identity of a single-claim finding so the same claim UPSERTS
;; (re-grades) instead of duplicating. The [[remember]] one-call fast path
;; writes it; multi-field domains design their own my.kb.<domain> schema.
(schema/register! ::claim [:string {:seon.db/identity true}])

(schema/register! ::finding
  [:map {:seon.db/entity true}
   [::claim ::claim]
   [::source-path ::source-path]
   [::source-line {:optional true} ::source-line]
   [::source-line-end {:optional true} ::source-line-end]
   [::verified-at ::verified-at]
   [::confidence ::confidence]])

;; [[remember]]'s map-in / map-out. `::source` is an ERGONOMIC input only
;; (a "file:line" / "file" / url string that [[remember]] PARSES into the
;; shared `::source-path` + `::source-line`) — it is never itself stored.
;; `::confidence` references the shared enum (no inline fork). The grade is
;; REQUIRED so a guess can't be persisted as a bare fact.
(schema/register! ::source :string)
(schema/register!
  ::remember-request
  [:map {:closed true}
   [::claim      ::claim]
   [::source     ::source]
   [::confidence ::confidence]])

;; Returns the live handle to the stored row, or the transact failure
;; envelope (errors are values) when the write didn't land.
(schema/register! ::id :int)                    ; the stored finding's eid
(schema/register! ::remembered [:map [::id ::id]])
(schema/register! ::remember-response [:or ::remembered :seon.db/transact-response])

;;; SCHEMA — register the attr; the system DERIVES datahike storage, and the
;;; FIRST transact! using an attr installs it (lazy). Inside a fn so reading
;;; this manual never registers the sample domain.

(defn- register-kb-schema!
  "Register the sample `:my.kb.source/*` domain — the shapes you reuse: an
   `:seon.db/identity` natural key, scalars, a cardinality-many `:vector`, a
   PLAIN ref (author) and a COMPONENT ref (findings, owned by their source,
   reusing the shared `:my.kb/*` provenance attrs)."
  []
  (schema/register! :my.kb.source/id     [:string {:seon.db/identity true}]) ; natural key
  (schema/register! :my.kb.source/title  :string)
  (schema/register! :my.kb.source/rating :int)                               ; how useful, 1-5
  (schema/register! :my.kb.source/topics [:vector :keyword])                 ; cardinality-many
  (schema/register! :my.kb.source/author :seon.db/ref)                       ; plain ref → entity
  (schema/register! :my.kb.source/findings                                   ; component ref (many)
                    [:vector {:seon.db/component true} :seon.db/ref])
  (schema/register! :my.kb.author/id   [:string {:seon.db/identity true}])
  (schema/register! :my.kb.author/name :string)
  (schema/register! :my.kb.finding/id   [:string {:seon.db/identity true}])
  (schema/register! :my.kb.finding/text :string))

;;; WRITES — transact! returns the envelope Promise (await it in a fn).
;;; ALWAYS read it: an eval can succeed yet `:seon.db/ok? false`.

(defn remember-sources!
  "Store the sample sources, linking authors and findings by ref.

   The two idiomatic ref links:

   - SAME-TX link (author not committed yet): give the author a `:db/id`
     TEMPID and put that SAME tempid in the source's `:my.kb.source/author`
     slot — datahike resolves both to one entity. To link an ALREADY
     committed author, use a lookup-ref `[:my.kb.author/id \"mccarthy\"]` —
     never a bare value (a lookup-ref won't resolve forward to an entity
     defined later in the same tx).
   - COMPONENT child (`:my.kb.source/findings`): built from the nested map,
     no tempid; each finding REUSES the shared `:my.kb/*` provenance attrs."
  {:malli/schema [:=> [:cat] :any]}
  []
  (db/transact!
    {::db/tx-data
     [{:db/id "author-mccarthy" :my.kb.author/id "mccarthy" :my.kb.author/name "John McCarthy"}
      {:db/id "author-okasaki"  :my.kb.author/id "okasaki"  :my.kb.author/name "Chris Okasaki"}
      {:my.kb.source/id "s1" :my.kb.source/title "Recursive Functions of Symbolic Expressions"
       :my.kb.source/rating 5 :my.kb.source/topics [:lisp :foundations]
       :my.kb.source/author "author-mccarthy"                ; the author's TEMPID (same tx)
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

(defn ^{:async true} remember
  "Store ONE finding as a durable, provenance-stamped knowledge row.

   The one-call way to persist what you verified, with NO schema design, NO
   register!, NO hand-written transact. GENERAL: any domain claim, not just
   code. The grade is REQUIRED, so a guess can't masquerade as a fact.

   Three keys, all required (map-in):
     ::claim       the fact, one sentence.
     ::source      where you verified it — \"file:line\", \"file\", or a url.
     ::confidence  :verified (you saw it) | :inferred (you reasoned it).

   Resolves to `{::id <eid>}` — the live handle: point a message/complete at
   it (REPORT=DATA, MESSAGE=POINTER) or `(seon.db/pull '[*] <eid>)` it back.
   IDEMPOTENT — the same claim UPSERTS (re-grades), never a duplicate. The
   row reuses the shared `::source-path`/`::source-line`/`::confidence`
   provenance attrs, so it renders in the NEXT agent's stored-findings block
   and answers their question without a re-research. Store each claim the
   MOMENT you verify it; don't batch to the end of a task you may not reach.

     (my.kb/remember
       {::claim \"transact! Malli-validates every entity value before the tx reaches datahike\"
        ::source \"src/seon/db/internal.cljs:694\"
        ::confidence :verified})
     ; returns «map: :my.kb/id 1234»

   For a multi-field DOMAIN model (linked refs, component children, your own
   identity key) design a my.kb.<domain> schema instead — see
   [[remember-sources!]]. `remember` is the fast path for a single claim."
  {:malli/schema [:=> [:cat ::remember-request] ::remember-response]}
  [{::keys [claim source confidence]}]
  (let [[_ path line] (re-matches #"(.+):(\d+)" source)
        prov          (if path
                        {::source-path path
                         ::source-line #?(:clj (Long/parseLong line 10)
                                          :cljs (js/parseInt line 10))}
                        {::source-path source})
        row           (merge {:db/id       "finding"
                              ::claim      claim
                              ::confidence confidence
                              ::verified-at #?(:clj (java.util.Date.)
                                               :cljs (js/Date.))}
                            prov)
        {::db/keys [ok? tempids] :as env} (await (db/transact! {::db/tx-data [row]}))]
    (if ok?
      {::id (get tempids "finding")}
      env)))

(defn retitle-source!
  "Rename one source's title in place, by its id.

   UPSERT by identity — the same `:my.kb.source/id` updates in place, no
   duplicate. Omitted keys are left unchanged (absent ≠ retract)."
  {:malli/schema [:=> [:catn [::id :string] [::new-title :string]] :any]}
  [id new-title]
  (db/transact! {::db/tx-data [{:my.kb.source/id id :my.kb.source/title new-title}]}))

(defn clear-rating!
  "Remove one source's rating, an explicit retraction.

   `[:db/retract ref attr]` (no value) removes the current value (omitting
   a key only leaves it unchanged)."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db/retract [:my.kb.source/id id] :my.kb.source/rating]]}))

(defn replace-topics!
  "Replace a source's whole topics set with a new one.

   Transacting topics only ADDS to the set; to REPLACE, retract every
   current value first — `[:db/retract ref attr]` (no value) — bundled
   BEFORE the add-map in ONE ordered tx."
  {:malli/schema [:=> [:catn [::id :string] [::topics [:vector :keyword]]] :any]}
  [id topics]
  (db/transact!
    {::db/tx-data [[:db/retract [:my.kb.source/id id] :my.kb.source/topics]
                   {:my.kb.source/id id :my.kb.source/topics topics}]}))

(defn forget-source!
  "Delete one source and all its findings, by id.

   `:db.fn/retractEntity` removes the whole entity; component children
   (the findings) cascade with it."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (db/transact! {::db/tx-data [[:db.fn/retractEntity [:my.kb.source/id id]]]}))

;;; QUERY — Datalog (db auto-injects; `:in` inputs come AFTER the query). The
;;; :find shape picks the result: bare relation = SET of tuples; `[?x ...]` =
;;; one column as a vector; `?x .` = a single scalar.

(defn ^:async titles
  "List every stored source title.

   FIND by attribute presence: every entity asserting `:my.kb.source/title`.
   Collection find `[?t ...]` → one column as a vector."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (await (db/query '[:find [?t ...] :where [?e :my.kb.source/title ?t]])))

(defn ^:async title+rating
  "List every source's title and rating as `[title rating]` pairs.

   Relation find — a SET of tuples, JOINING two attrs on one entity
   (`?e` binds both clauses)."
  {:malli/schema [:=> [:cat] [:set [:tuple :string :int]]]}
  []
  (await
   (db/query
    '[:find ?title ?rating
      :where
      [?e :my.kb.source/title ?title]
      [?e :my.kb.source/rating ?rating]])))

(defn ^:async titles-by-author
  "List the titles of every source by the named author.

   `:in`-bound input + REF-JOIN.
   A ref stores an EID — match the author by NAME by JOINING through
   `:my.kb.source/author`, never by putting the name in the ref slot."
  {:malli/schema [:=> [:catn [::author-name :string]] [:vector :string]]}
  [author-name]
  (await
   (db/query '[:find [?title ...] :in $ ?name
               :where [?a :my.kb.author/name ?name]
                      [?s :my.kb.source/author ?a]
                      [?s :my.kb.source/title ?title]]
             author-name)))

;;; PULL / ENTITY — read one entity by lookup-ref `[identity-attr value]`,
;;; which IS the "by name" addressing.

(defn ^:async source-detail
  "Fetch one source with its author and findings, by id.

   Pull by LOOKUP-REF.
   `[*]` inlines every attr; a COMPONENT child comes back as a nested map,
   a PLAIN ref as `{:db/id N}` until you NAME it with a sub-pattern to pull
   its fields."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (await
   (db/pull '[* {:my.kb.source/author [:my.kb.author/name]}]
            [:my.kb.source/id id])))

(defn ^:async source-entity
  "Fetch one source as a plain map, nil when the id is unknown.

   Looks up the entity by lookup-ref.
   The returned map is `:db/id` plus every attribute. A plain ref reads back as
   `{:db/id N}`; use [[source-detail]] or a narrower `seon.db/pull` pattern to
   traverse it."
  {:malli/schema [:=> [:catn [::id :string]] :any]}
  [id]
  (await (db/pull '[*] [:my.kb.source/id id])))
