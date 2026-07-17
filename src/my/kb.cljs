(ns my.kb
  "Your knowledge base IS the database — schema'd data, never a text blob.
   The fns below are runnable RECIPES (every form compiles and is exercised
   by my.kb-test, so they can't bit-rot): copy a body, swap in your own
   `:my.<domain>/*` attrs. seon.db's own docstrings are the full reference.

   THE MODEL — datahike entities have NO type, class, or kind. An entity is
   just an id with a set of attributes. A thing 'is a source' because it
   carries `:my.kb.source/*` attrs, and 'is authored by' another entity
   because a REF connects them — never because of a `:kind`/`:type` field
   (don't add one). So:
     - RECORD by ADDING fully-namespaced attrs to an entity and LINKING
       entities with refs ([[remember-sources!]]).
     - IDENTIFY one entity by a `:db.unique/identity` attr — also how
       transact UPSERTS: same id ⇒ update in place, no duplicate.
     - FIND a set by ATTRIBUTE PRESENCE — `[?e :my.kb.source/id]` enumerates
       every source. There is no 'list all of kind K'.
     - ATTRIBUTE provenance through the tx's `:seon.db/user` and
       `:seon.db/process` refs; domain ownership remains an explicit domain ref.

   Design one `my.kb.<domain>` schema per thing you learn (the same skill as
   modelling your human's data); never default to a memory-markdown blob.
   The database starts without sample knowledge; query before answering. The
   `:my.kb.source/*` rows exist only once YOU run a recipe (my.kb-test uses
   its own throwaway db), so never report them as real data.

   Database reads and writes are asynchronous authority operations. Compose
   related reads at one immutable database value inside an `^:async` function."
  (:require
    [clojure.string :as str]
    [my.data :as data]
    [seon.db :as db]
    [seon.embed :as embed]
    ;; the shared `:seon.result/ok?` + `:seon.items/*` envelope shapes —
    ;; Core owns them; [[recall]] REFERENCES them (required for load order).
    [seon.items]
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

;; [[remember]]'s map-in / map-out. `::source` is an ERGONOMIC input only
;; (a "file:line" / "file" / url string that [[remember]] PARSES into the
;; shared `::source-path` + `::source-line`) — it is never itself stored.
;; `::confidence` references the shared enum (no inline fork). The grade is
;; REQUIRED so a guess can't be persisted as a bare fact.
(schema/register! ::source :string)
(schema/register!
  ::remember-request
  [:map
   [::claim      ::claim]
   [::source     ::source]
   [::confidence ::confidence]])

;; Returns the live handle to the stored row, or the transact failure
;; envelope (errors are values) when the write didn't land.
(schema/register! ::id :int)                    ; the stored finding's eid
(schema/register! ::remembered [:map [::id ::id]])
(schema/register! ::remember-response [:or ::remembered :seon.db/transact-response])

;; The map-out shape [[source-stats]] returns — itself a registered schema,
;; so the renderer and instrumentation can see it.
(schema/register!
  ::source-summary
  [:map
   [::count        :int]
   [::rating-total :int]
   [::topic-counts [:map-of :keyword :int]]])

;; [[recall]]'s map-in / map-out — the symmetric ASK to [[remember]]'s
;; store. `::match`/`::matched-tokens` are DERIVED response labels stamped
;; on each returned item at return time, never stored on any row;
;; `::matched` is the honest total before the `::limit` cap.
(schema/register! ::about [:string {:min 1}])
(schema/register! ::limit [:int {:min 1 :max 50}])
(schema/register! ::recall-request
  [:map
   [::about ::about]
   [::limit {:optional true} ::limit]])
(schema/register! ::match [:enum :text :semantic])
(schema/register! ::matched-tokens :int)
(schema/register! ::matched :int)
(schema/register! ::hint :string)
(schema/register! ::error :string)
(schema/register!
  ::recall-response
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [:seon.items/items {:optional true} :seon.items/items]
   [:seon.items/count {:optional true} :seon.items/count]
   [::matched {:optional true} ::matched]
   [::hint    {:optional true} ::hint]
   [::error   {:optional true} ::error]])

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

(defn ^{:async true :seon.fn/agent-facing? true} remember
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
                        {::source-path path ::source-line (js/parseInt line 10)}
                        {::source-path source})
        row           (merge {:db/id       "finding"
                              ::claim      claim
                              ::confidence confidence
                              ::verified-at (js/Date.)}
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

(defn ^:async source-stats
  "Summarize stored sources: count, rating total, and topic tally.

   Aggregation toward a question — the analysis built ON TOP of stored
   data. Delegates to my.data, so you never hand-roll a datalog aggregate:
   `rows` pulls each source to a MAP, then `sum-by` totals the ratings and a
   plain `frequencies` tallies the (cardinality-many) topics. Pulling to
   maps first makes the `(sum ?r)`/`:with` dedup collapse structurally
   impossible — two sources rated 5 stay 5+5=10."
  {:malli/schema [:=> [:cat] ::source-summary]}
  []
  (let [sources (await (data/rows {:my.data/attr :my.kb.source/id}))
        items   (:seon.items/items sources)]
    {::count        (:seon.items/count sources)
     ::rating-total (data/sum-by {:seon.items/items items
                                  :my.data/key :my.kb.source/rating})
     ::topic-counts (frequencies (mapcat :my.kb.source/topics items))}))

;;; RECALL — the symmetric ASK to [[remember]]'s store: a question in,
;;; ranked stored facts out, no datalog authoring.

(defn- tokens
  "Lowercase alphanumeric runs of length ≥ 2 — [[recall]]'s match unit."
  [s]
  (into #{} (filter #(>= (count %) 2)) (re-seq #"[a-z0-9]+" (str/lower-case s))))

(defn ^:async ^:private kb-text-rows
  "Every `[eid attr text]` of a `my.kb`-family STRING attr at one database value — the
   deterministic recall corpus: [[remember]] claims AND your own
   `my.kb.<domain>` rows alike. Attrs come from the db's installed schema
   (an attr installs at its first write); sorted for determinism."
  [database]
  (let [family? (fn [a] (when-let [ns (and (keyword? a) (namespace a))]
                          (or (= ns "my.kb") (str/starts-with? ns "my.kb."))))
        installed (await (db/installed-schema database))]
    (if (:seon.error/message installed)
      installed
      (let [attrs (->> installed
                       (filter (fn [[a m]] (and (family? a)
                                                (= :db.type/string (:db/valueType m)))))
                       (map first)
                       sort
                       vec)]
        (if (empty? attrs)
          []
          (await
           (db/query
            {:seon.db/query '[:find ?e ?a ?v
                              :in $ [?a ...]
                              :where [?e ?a ?v]]
             :seon.db/args [attrs]
             :seon.db/db database})))))))

(defn- recall-error
  [stage error]
  {:seon.result/ok? false
   ::error (str "recall failed during " stage ": "
                (or (:seon.error/message error) (pr-str error)))})

(defn- text-matches
  "Rank `[eid attr text]` rows against a question-token set: an entity's
   score counts the DISTINCT question tokens matched across all its
   texts (whole-token equality — no stemming). Returns `[[eid score]]`
   best-first (ties: lower eid)."
  [q-tokens rows]
  (->> rows
       (reduce (fn [m [e _ v]]
                 (let [hit (filter (tokens v) q-tokens)]
                   (if (seq hit) (update m e (fnil into #{}) hit) m)))
               {})
       (map (fn [[e ts]] [e (count ts)]))
       (sort-by (fn [[e n]] [(- n) e]))
       vec))

(defn ^{:async true :seon.fn/agent-facing? true} recall
  "Find what you already know about a topic: ranked facts with sources.

   \"What do we know about X?\" in ONE call. Map-in:
     ::about  the topic/question, plain words.
     ::limit  max facts returned (default 10).

   Matching is DETERMINISTIC: your words (lowercased whole tokens, no
   stemming) are matched against every stored `my.kb*` STRING value —
   [[remember]] claims and your own `my.kb.<domain>` rows alike. Facts
   rank by distinct words matched (`::matched-tokens`). When `SEON_EMBED`
   is set, unfilled slots TOP UP with semantic neighbours via
   `seon.embed/search-pull` (`::match :semantic`, distance attached);
   unset, recall is purely deterministic.

     (my.kb/recall {::about \"vendor API rate limits\"})
     ; ⟹ «map: :seon.result/ok? true, :seon.items/items [{:db/id …,
     ;    :my.kb/claim …, :my.kb/source-path …, ::match :text,
     ;    ::matched-tokens 2} …], :seon.items/count int, ::matched int»

   Each item is the FULL pulled row (claim + provenance + domain attrs)
   plus the derived `::match`/`::matched-tokens` labels; `::matched` is
   the honest total before the cap. No matches is SUCCESS (empty items); use
   direct database queries or `/data` before concluding nothing is known."
  {:malli/schema [:=> [:cat ::recall-request] ::recall-response]}
  [{::keys [about limit]}]
  (let [limit (or limit 10)
        database (await (db/db))]
    (if (:seon.error/message database)
      (recall-error "database acquisition" database)
      (let [rows (await (kb-text-rows database))]
        (if (:seon.error/message rows)
          (recall-error "text acquisition" rows)
          (let [hits (text-matches (tokens about) rows)
                selected (vec (take limit hits))
                pulled (if (seq selected)
                         (await
                          (db/pull-many
                           {:seon.db/db database
                            :seon.db/selector '[*]
                            :seon.db/eids (mapv first selected)}))
                         [])]
            (if (:seon.error/message pulled)
              (recall-error "entity acquisition" pulled)
              (let [items (mapv (fn [[_e score] entity]
                                  (assoc entity
                                         ::match :text
                                         ::matched-tokens score))
                                selected pulled)
                    want (- limit (count items))
                    seen (into #{} (map :db/id) items)
                    scope (into #{} (map first) rows)
                    sem (when (and (embed/enabled?) (pos? want) (seq scope))
                          (await
                           (embed/search-pull
                            {:seon.db/db database
                             :seon.embed/query about
                             :seon.embed/k limit
                             :seon.embed/eids scope})))
                    items (into items
                                (->> (:seon.embed/hits sem)
                                     (remove #(seen (:seon.embed/eid %)))
                                     (take want)
                                     (mapv
                                      (fn [{:seon.embed/keys
                                            [entity eid distance]}]
                                        (assoc (or entity {:db/id eid})
                                               ::match :semantic
                                               :seon.embed/distance
                                               distance)))))]
                (cond-> {:seon.result/ok? true
                         :seon.items/items items
                         :seon.items/count (count items)
                         ::matched (count hits)}
                  (:seon/error sem)
                  (assoc ::hint
                         (str "semantic top-up failed — "
                              (get-in sem
                                      [:seon/error
                                       :seon.error/message]))))))))))))

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

;;; WORKFLOW — store data, then build fns that turn it into answers: switch
;;; into a domain, register its schema, transact rows, run your analysis fn.

(defn ^:async build-kb-example!
  "Run the end-to-end example: register, seed rows, run [[source-stats]].
   `^:async` because it AWAITS the write before reading. Resolves to the stats
   summary, or the failure envelope if rejected. Run it once, then build your
   OWN domain the same way."
  {:malli/schema [:=> [:cat] :any]}
  []
  (register-kb-schema!)
  (let [{::db/keys [ok?] :as envelope} (await (remember-sources!))]
    (if ok?
      (await (source-stats))
      envelope)))
