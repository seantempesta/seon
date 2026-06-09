# Data Modeling -- Fact Databases in Seon/Datahike

Datomic best practices (from <https://docs.datomic.com/reference/best.html>),
mapped onto seon's `schema/register!` + the Malli-to-Datahike bridge, with a
worked example that is **verified live in the JVM REPL** (every snippet below
ran green against an isolated `:memory` flow before being written here).

Datahike is Datomic-compatible for everything in this doc. The same modeling
principles apply in the CLJS agent-runtime pod; only the call shapes differ
(the pod uses its own async DB API, not the JVM `seon.db` positional fns shown
here).

## The `::` + deep-namespace convention (read this first)

**Attributes are developed inside specific, deeply-nested namespaces and
reference their own attrs with `::`.** The schema for a piece of data lives in
the namespace whose name it carries (colocation). This is the load-bearing
naming rule -- it is what lets a single Datalog query join function specs to the
data those functions operate on.

- Inside namespace `acme.people.person`, you write `::email` -- the reader
  auto-expands it to `:acme.people.person/email`. You never type the long form
  for your own attrs.
- A cross-namespace reference uses the **full** keyword. Inside
  `acme.comms.email`, the sender ref attr `::from` (=
  `:acme.comms.email/from`) points at a `acme.people.person` entity. The
  pointing is structural (it's a `:db.type/ref`); the *naming* still obeys
  colocation -- `::from` is named in the email namespace because the email owns
  the fact "this email has a sender".

Do **not** use shallow names like `:person/email`. They collapse distinct
domains into one flat namespace, defeat the join-specs-to-data goal, and read as
"belongs to a namespace `person`" that doesn't exist as code. Use the real,
nested namespace: `:acme.people.person/email`.

```clojure
;; INSIDE namespace acme.people.person
(schema/register! ::email [:string {:seon.db/identity true}])  ;; :acme.people.person/email
(schema/register! ::name  :string)                              ;; :acme.people.person/name

;; INSIDE namespace acme.comms.email -- a ref to a person in ANOTHER namespace
(schema/register! ::from :seon.db/ref)                          ;; :acme.comms.email/from
```

## Best-practices checklist (mapped to seon wiring)

### Schema

- **Group related attributes in a deeply-nested namespace.** One namespace per
  entity kind: `acme.people.person`, `acme.org.company`, `acme.comms.email`.
- **Unique identity for external / natural keys** -> UPSERT. Account numbers,
  emails, message-ids, domains. In seon: `[:string {:seon.db/identity true}]`,
  which the bridge maps to `:db/unique :db.unique/identity`. Re-transacting the
  same natural-key value updates the existing entity instead of creating a new
  one.
- **Model relationships in ONE direction.** Datahike indexes refs both ways
  (VAET), so the reverse is free -- via a reverse-ref pull (`:acme.comms.email/_from`)
  or a `[?e :acme.comms.email/to ?p]` clause. Never store a redundant reverse
  attr.
- **Reified relationships are entities.** An edge that carries its own facts
  (a Role: title + start date linking a Person to an Org) is its own entity with
  ref attrs to each end -- not a bare ref.
- **Plan for accretion.** Never assume an entity's full attr set; `select-keys`
  / pull what you need. Optional attrs are **absent, never nil** (seon:
  `{:optional true}`).
- **Grow schema, never break it.** Only add; never remove or repurpose a name.
- **Enums as idents / keywords.** Use `[:enum :a :b]` -- the bridge infers the
  value type from the enum members.

### Transactions

- **Reify the transaction -- add facts about the tx entity.** Include a map with
  `:db/id "datomic.tx"` (alias `:db/current-tx`) carrying provenance attrs.
  Datahike resolves `"datomic.tx"` to the current transaction's entity id, so
  the provenance datoms hang off the tx itself.

  ```clojure
  (db/transact! :acme [{:acme.people.person/email "a@x.com"}
                       {:db/id "datomic.tx" :acme.ingest.tx/source "inbox-export-2026"}])
  ;; later: which source did these facts come from?
  (db/query :acme '[:find ?s :where [?tx :acme.ingest.tx/source ?s]])
  ;; => #{["inbox-export-2026"]}
  ```

  `:acme.ingest.tx/source` is a normal registered attr -- nothing special about
  putting it on the tx entity.
- **Lookup-refs specify EXISTING entities only.** A lookup-ref
  (`[:acme.people.person/email "a@x.com"]`) resolves only against entities that
  are already committed (or that appear earlier in the same tx and get committed
  first). See the intra-tx rule below.

### Query

- **Most selective clause first** -- pin the entity by its natural key, then
  walk out.
- **Parameterize with `:in`** -- pass user data as query inputs (no string
  building, leverages caching).
- **Use pull to retrieve attribute values** -- `:where` finds entities, pull
  navigates their attrs (including across refs).

## Intra-tx ref rule (tempids vs lookup-refs) -- VERIFIED

This is load-bearing and was verified live (forward lookup-ref throws; tempid
link succeeds):

- **tempids resolve same-tx, order-independently, including upsert.** To upsert
  a person AND link an email to them in ONE tx, give the person a tempid (a
  string, e.g. `"person:a@x.com"`) and reference that tempid from the email.
  When the person's `:db.unique/identity` value matches an existing entity,
  datahike upserts the tempid onto that entity; both the person map and the
  email's ref resolve to the same eid.
- **lookup-refs do NOT resolve against same-tx, not-yet-committed entities.** A
  forward lookup-ref to an entity created later in the same tx throws
  `Nothing found for entity id ... :error :entity-id/missing`. (Verified live.)

So: **tempids for same-tx-new entities; lookup-refs only for the already
committed.** Programmatic ingest code should derive a stable tempid string from
the natural key (e.g. `(str "person:" email)`) -- identical strings collapse to
one entity within the tx, which cooperates with `:db.unique/identity` upsert.

Source-cited mechanics: `docs/prds/datahike-migration/ref-model-research.md`.

## Bridge wiring reference

| You register | Bridge produces |
|---|---|
| `[:string {:seon.db/identity true}]` | `:db.type/string` + `:db.unique/identity` (UPSERT) |
| `:seon.db/ref` | `:db.type/ref`, cardinality one |
| `[:vector :seon.db/ref]` | `:db.type/ref`, cardinality **many** |
| `:inst` | `:db.type/instant` |
| `{:optional true}` on a `:map` entry | field may be absent (never nil) |

Verified derivation for the example below:

```clojure
(seon.db.datahike.schema/malli-map->datahike-schema
  [:map [:acme.people.person/email :acme.people.person/email]
        [:acme.people.role/person   :acme.people.role/person]
        [:acme.comms.email/to       :acme.comms.email/to]
        [:acme.comms.email/deadline :acme.comms.email/deadline]])
;; =>
;; ({:db/valueType :db.type/string,  :unique :db.unique/identity, :ident :acme.people.person/email ...}
;;  {:db/valueType :db.type/ref,     :cardinality :db.cardinality/one,  :ident :acme.people.role/person ...}
;;  {:db/valueType :db.type/ref,     :cardinality :db.cardinality/many, :ident :acme.comms.email/to ...}
;;  {:db/valueType :db.type/instant, :cardinality :db.cardinality/one,  :ident :acme.comms.email/deadline ...})
```

## Worked example -- people / roles / orgs / emails with deadlines

A company knowledge graph (representative of ingesting company docs + emails).
`acme.*` namespaces are **illustrative** -- this is a teaching model, not
committed substrate domain code (consumer domain lives downstream).

### Schema (`schema/register!` -- single source of truth)

```clojure
;; --- acme.people.person ---  (write these INSIDE that ns as ::email / ::name)
(schema/register! :acme.people.person/email [:string {:seon.db/identity true}]) ;; natural key -> UPSERT
(schema/register! :acme.people.person/name  :string)

;; --- acme.org.company ---
(schema/register! :acme.org.company/domain [:string {:seon.db/identity true}])  ;; natural key -> UPSERT
(schema/register! :acme.org.company/name   :string)

;; --- acme.people.role ---  reified relationship: an edge that carries facts
(schema/register! :acme.people.role/person :seon.db/ref)   ;; -> acme.people.person
(schema/register! :acme.people.role/org    :seon.db/ref)   ;; -> acme.org.company
(schema/register! :acme.people.role/title  :string)
(schema/register! :acme.people.role/since  :inst)

;; --- acme.comms.email ---  from = one ref, to = many refs (one-directional)
(schema/register! :acme.comms.email/message-id [:string {:seon.db/identity true}]) ;; dedup key
(schema/register! :acme.comms.email/subject    :string)
(schema/register! :acme.comms.email/from       :seon.db/ref)          ;; -> acme.people.person
(schema/register! :acme.comms.email/to         [:vector :seon.db/ref]) ;; -> acme.people.person (many)
(schema/register! :acme.comms.email/sent-at    :inst)
(schema/register! :acme.comms.email/deadline   :inst)                  ;; OPTIONAL: absent != nil

;; --- acme.ingest.tx ---  provenance attached to the reified transaction entity
(schema/register! :acme.ingest.tx/source :string)

;; entity :map schemas -- the identity attr makes each upsertable
(schema/register! :acme.people.person/entity
  [:map [:acme.people.person/email :acme.people.person/email]
        [:acme.people.person/name {:optional true} :acme.people.person/name]])
(schema/register! :acme.org.company/entity
  [:map [:acme.org.company/domain :acme.org.company/domain]
        [:acme.org.company/name {:optional true} :acme.org.company/name]])
(schema/register! :acme.people.role/entity      ;; reified relationship as a :map
  [:map [:acme.people.role/person :acme.people.role/person]
        [:acme.people.role/org    :acme.people.role/org]
        [:acme.people.role/title {:optional true} :acme.people.role/title]
        [:acme.people.role/since {:optional true} :acme.people.role/since]])
(schema/register! :acme.comms.email/entity
  [:map [:acme.comms.email/message-id :acme.comms.email/message-id]
        [:acme.comms.email/subject  {:optional true} :acme.comms.email/subject]
        [:acme.comms.email/from     {:optional true} :acme.comms.email/from]
        [:acme.comms.email/to       {:optional true} :acme.comms.email/to]
        [:acme.comms.email/sent-at  {:optional true} :acme.comms.email/sent-at]
        [:acme.comms.email/deadline {:optional true} :acme.comms.email/deadline]])
```

### `record-email!` -- upsert by natural key, link via tempids, reify the tx

```clojure
;; request/response are registered Malli schemas (map-in / map-out).
(schema/register! :acme.comms.email/recipient
  [:map [:acme.people.person/email :acme.people.person/email]
        [:acme.people.person/name {:optional true} :acme.people.person/name]])
(schema/register! :acme.comms.email/record-request
  [:map [:acme.comms.email/db-name :keyword]
        [:acme.comms.email/message-id :acme.comms.email/message-id]
        [:acme.comms.email/subject :acme.comms.email/subject]
        [:acme.comms.email/sender :acme.comms.email/recipient]
        [:acme.comms.email/recipients [:vector :acme.comms.email/recipient]]
        [:acme.comms.email/sent-at :acme.comms.email/sent-at]
        [:acme.comms.email/deadline {:optional true} :acme.comms.email/deadline]
        [:acme.ingest.tx/source :acme.ingest.tx/source]])
(schema/register! :acme.comms.email/record-response
  [:map [:acme.comms.email/message-id :acme.comms.email/message-id]
        [:acme.comms.email/tempids [:map-of :any :int]]])

(defn record-email!
  "Ingest one email: upsert sender + recipients by their natural-key email (so
   the same person across many emails collapses to ONE entity), link via refs,
   dedup the email by :message-id, and reify the tx with provenance."
  {:malli/schema [:=> [:cat :acme.comms.email/record-request]
                  :acme.comms.email/record-response]}
  [{:acme.comms.email/keys [db-name message-id subject sender recipients sent-at deadline]
    src :acme.ingest.tx/source}]
  (let [tid          (fn [p] (str "person:" (:acme.people.person/email p)))  ;; tempid from natural key
        person-ents  (for [p (cons sender recipients)]
                       (cond-> {:db/id (tid p)                               ;; same key -> same entity (upsert)
                                :acme.people.person/email (:acme.people.person/email p)}
                         (:acme.people.person/name p)
                         (assoc :acme.people.person/name (:acme.people.person/name p))))
        email-ent    (cond-> {:acme.comms.email/message-id message-id        ;; dedup by identity
                              :acme.comms.email/subject subject
                              :acme.comms.email/from (tid sender)            ;; tempid link (same-tx-safe)
                              :acme.comms.email/to (mapv tid recipients)
                              :acme.comms.email/sent-at sent-at}
                       deadline (assoc :acme.comms.email/deadline deadline))  ;; optional: only when present
        tx-ent       {:db/id "datomic.tx" :acme.ingest.tx/source src}        ;; reify the tx
        report       (db/transact! db-name (concat person-ents [email-ent tx-ent]))]
    {:acme.comms.email/message-id message-id
     :acme.comms.email/tempids (:tempids report)}))
```

Note: `seon.db/transact!` returns `{:tempids ... :tx-data ...}` -- the flow
serializes reads, so it does **not** hand back a `:db-after` db value (the db
lives behind the conn-process). Read post-tx state with a fresh `db/query` /
`db/pull-by-name` against the same db-name (the flow serializes the read after
the write).

### `deadlines-for` -- selective clause first, parameterized, pull

```clojure
(schema/register! :acme.comms.email/deadline-row
  [:map [:acme.comms.email/subject :acme.comms.email/subject]
        [:acme.comms.email/deadline :acme.comms.email/deadline]
        [:acme.comms.email/from :acme.people.person/email]])
(schema/register! :acme.comms.email/deadlines-request
  [:map [:acme.comms.email/db-name :keyword]
        [:acme.people.person/email :acme.people.person/email]])
(schema/register! :acme.comms.email/deadlines-response
  [:map [:acme.comms.email/deadlines [:vector :acme.comms.email/deadline-row]]])

(defn deadlines-for
  "Emails involving a person (from OR to) that carry a deadline."
  {:malli/schema [:=> [:cat :acme.comms.email/deadlines-request]
                  :acme.comms.email/deadlines-response]}
  [{:acme.comms.email/keys [db-name] person-email :acme.people.person/email}]
  (let [rows (db/query db-name
               '[:find ?subject ?deadline ?from-email
                 :in $ ?person-email
                 :where
                 [?p :acme.people.person/email ?person-email]   ;; most selective: pin person by natural key
                 (or [?e :acme.comms.email/from ?p]             ;; one-directional refs; reverse is free
                     [?e :acme.comms.email/to ?p])
                 [?e :acme.comms.email/deadline ?deadline]      ;; only deadline-bearing (absent != nil)
                 [?e :acme.comms.email/subject ?subject]
                 [?e :acme.comms.email/from ?fp]
                 [?fp :acme.people.person/email ?from-email]]
               person-email)]                                   ;; parameterized input
    {:acme.comms.email/deadlines
     (mapv (fn [[s d f]] {:acme.comms.email/subject s
                          :acme.comms.email/deadline d
                          :acme.comms.email/from f})
           rows)}))
```

### Test -- upsert collapses to ONE person; refs + provenance + deadlines

Uses `tu/with-test-db` for an isolated `:memory` flow (never touches persistent
dbs). **This test runs green (8 assertions, 0 failures, verified live.)**

```clojure
(def acme-fixture-schema
  [:map
   [:acme.people.person/email {:optional true} :acme.people.person/email]
   [:acme.people.person/name {:optional true} :acme.people.person/name]
   [:acme.comms.email/message-id {:optional true} :acme.comms.email/message-id]
   [:acme.comms.email/subject {:optional true} :acme.comms.email/subject]
   [:acme.comms.email/from {:optional true} :acme.comms.email/from]
   [:acme.comms.email/to {:optional true} :acme.comms.email/to]
   [:acme.comms.email/sent-at {:optional true} :acme.comms.email/sent-at]
   [:acme.comms.email/deadline {:optional true} :acme.comms.email/deadline]
   [:acme.ingest.tx/source {:optional true} :acme.ingest.tx/source]])

(deftest record-email-upsert-and-deadlines-test
  (tu/with-test-db
    {:seon.test-utils/namespaces [:acme]
     :seon.test-utils/schemas {:acme acme-fixture-schema}}
    (fn [_]
      (let [deadline (java.util.Date.)
            base {:acme.comms.email/db-name :acme
                  :acme.comms.email/subject "Q3 report due"
                  :acme.comms.email/sender {:acme.people.person/email "alice@acme.com"
                                            :acme.people.person/name "Alice"}
                  :acme.comms.email/recipients
                  [{:acme.people.person/email "bob@acme.com" :acme.people.person/name "Bob"}
                   {:acme.people.person/email "carol@acme.com"}]
                  :acme.comms.email/sent-at (java.util.Date.)
                  :acme.comms.email/deadline deadline
                  :acme.ingest.tx/source "inbox-export-2026"}]
        ;; record TWO emails with the SAME sender
        (record-email! (assoc base :acme.comms.email/message-id "m-100"))
        (record-email! (assoc base :acme.comms.email/message-id "m-101"
                                   :acme.comms.email/subject "follow up"))

        ;; 1. UPSERT: exactly ONE Alice entity across both emails
        (is (= 1 (count (db/query :acme '[:find ?e
                                          :where [?e :acme.people.person/email "alice@acme.com"]]))))
        ;; 2. two distinct emails, deduped by message-id
        (is (= #{["m-100"] ["m-101"]}
               (db/query :acme '[:find ?mid :where [?e :acme.comms.email/message-id ?mid]])))
        ;; 3. refs link: from = Alice, to = [Bob Carol]
        (let [pulled (db/pull-by-name :acme
                       '[{:acme.comms.email/from [:acme.people.person/email]}
                         {:acme.comms.email/to [:acme.people.person/email]}]
                       [:acme.comms.email/message-id "m-100"])]
          (is (= "alice@acme.com" (get-in pulled [:acme.comms.email/from :acme.people.person/email])))
          (is (= #{"bob@acme.com" "carol@acme.com"}
                 (set (map :acme.people.person/email (:acme.comms.email/to pulled))))))
        ;; 4. reified-tx provenance is queryable
        (is (= #{["inbox-export-2026"]}
               (db/query :acme '[:find ?s :where [?tx :acme.ingest.tx/source ?s]])))
        ;; 5. deadlines-for finds both emails involving Bob (a recipient)
        (let [dls (:acme.comms.email/deadlines
                   (deadlines-for {:acme.comms.email/db-name :acme
                                   :acme.people.person/email "bob@acme.com"}))]
          (is (= 2 (count dls)))
          (is (= #{"Q3 report due" "follow up"}
                 (set (map :acme.comms.email/subject dls))))
          (is (every? #(= "alice@acme.com" (:acme.comms.email/from %)) dls)))))))
```

## Reverse-ref navigation (one-directional refs, free reverse)

No reverse attr is stored. Navigate the reverse with a pull `_`-prefix or a
where-clause:

```clojure
;; "who sent emails to this person?" -- via where clause
(db/query :acme '[:find ?sender-email
                  :in $ ?recipient-email
                  :where [?p :acme.people.person/email ?recipient-email]
                         [?e :acme.comms.email/to ?p]
                         [?e :acme.comms.email/from ?s]
                         [?s :acme.people.person/email ?sender-email]]
          "bob@acme.com")

;; reverse-ref pull: emails this person SENT (the _from underscore form)
(db/pull-by-name :acme '[:acme.people.person/email
                         {:acme.comms.email/_from [:acme.comms.email/subject]}]
                 [:acme.people.person/email "alice@acme.com"])
```
