# Data Modeling — Fact Databases in Seon / Datahike

Datomic best-practice modeling (entities, natural-key identity/upsert, refs +
cardinality, reified relationships, reified transactions, intra-tx tempids vs
lookup-refs) mapped onto seon's `schema/register!` + the Malli→datahike bridge,
in **pod idiom**: ONE connection, map-in `transact!` returning an envelope,
synchronous reads with the db auto-injected. The modeling principles are
Datomic-compatible and unchanged; only the call shapes are pod-native.

`my.kb` is the runnable version of everything below (every recipe compiles
and is exercised by its own test ns). `my.plan` is the exemplar of a
tree/DAG model with derived datalog rules.

## The `::` + deep-namespace convention (read this first)

**Attributes are developed inside specific, deeply-nested namespaces and
reference their own attrs with `::`.** The schema for a piece of data lives in
the namespace whose name it carries (colocation). This is the load-bearing
naming rule — it lets a single Datalog query join function specs to the data
those functions operate on.

- Inside namespace `co.people.person`, you write `::email` — the reader
  expands it to `:co.people.person/email`. You never type the long form for
  your own attrs.
- A cross-namespace reference uses the **full** keyword. Inside
  `co.comms.email`, the sender ref `::from` (= `:co.comms.email/from`)
  points at an `co.people.person` entity. The pointing is structural (a
  `:db.type/ref`); the *naming* still obeys colocation — `::from` is named in
  the email namespace because the email owns the fact "this email has a sender".

Do **not** use shallow names like `:person/email`. They collapse distinct
domains into one flat namespace and read as "belongs to a namespace `person`"
that doesn't exist as code. Use the real nested namespace.

```clojure
;; INSIDE namespace co.people.person
(schema/register! ::email [:string {:seon.db/identity true}])  ; :co.people.person/email
(schema/register! ::name  :string)                              ; :co.people.person/name

;; INSIDE namespace co.comms.email — a ref to a person in ANOTHER namespace
(schema/register! ::from :seon.db/ref)                          ; :co.comms.email/from
```

## Best-practices checklist (mapped to seon wiring)

### Schema

- **Group related attributes in a deeply-nested namespace.** One namespace per
  conceptual thing: `co.people.person`, `co.org.company`, `co.comms.email`.
  (Remember: this is naming + colocation, NOT an entity "kind" — an entity is
  still just its attrs + refs. See SKILL.md "no kinds".)
- **Unique identity for external / natural keys ⇒ UPSERT.** Account numbers,
  emails, message-ids, domains. `[:string {:seon.db/identity true}]` → the
  bridge installs `:db/unique :db.unique/identity`. Re-transacting the same
  natural-key value updates the existing entity instead of creating a new one.
- **Model relationships in ONE direction.** Datahike indexes refs both ways, so
  the reverse is free — a reverse-ref pull (`:co.comms.email/_from`) or a
  `[?e :co.comms.email/to ?p]` clause. Never store a redundant reverse attr.
- **Reified relationships are entities.** An edge that carries its own facts (a
  Role: title + start date linking a Person to an Org) is its own entity with
  ref attrs to each end — not a bare ref.
- **Plan for accretion.** Never assume an entity's full attr set; pull what you
  need. Optional attrs are **absent, never nil** (`{:optional true}`).
- **Grow schema, never break it.** Only add; never remove or repurpose a name.
- **Enums as keywords.** `[:enum :a :b]` — the bridge infers the value type
  from the (keyword) enum members.

### Transactions

- **Reify the transaction — attach provenance to the tx entity.** Two paths:
  - **Idiomatic (auto-stamped):** wrap writes in `db/with-tx-context` /
    `db/with-agent` and the tx is auto-tagged with `:seon.db/origin`,
    `:seon.db/agent-id`, etc. The agent loop sets this for you, so your writes
    already carry provenance. This is what `store-inventory`'s user-vs-system
    split reads.
  - **Custom per-tx facts:** include a map keyed `:db/id :db/current-tx` (alias
    `"datomic.tx"`) in the same tx — datahike resolves it to the current
    transaction's entity, so your provenance datoms hang off the tx itself:

    ```clojure
    (db/transact! {::db/tx-data
                   [{:co.people.person/email "a@x.com"}
                    {:db/id :db/current-tx :co.ingest.tx/source "inbox-export-2026"}]})
    ;; later: which source did these facts come from?
    (db/query '[:find [?s ...] :where [?tx :co.ingest.tx/source ?s]])
    ; ⟹ ["inbox-export-2026"]
    ```

    `:co.ingest.tx/source` is a normal registered attr — nothing special
    about putting it on the tx entity.
- **Lookup-refs specify EXISTING entities only.** A lookup-ref
  (`[:co.people.person/email "a@x.com"]`) resolves only against entities
  already committed (or that appear earlier in the same tx and commit first).
  See the intra-tx rule below.

### Query

- **Most selective clause first** — pin the entity by its natural key, walk out.
- **Parameterize with `:in`** — pass user data as query inputs (inputs come
  AFTER the query; no string building).
- **Use pull to retrieve attribute values** — `:where` finds entities, pull
  navigates their attrs (including across refs).

## Intra-tx ref rule (tempids vs lookup-refs)

Load-bearing, proven live (forward lookup-ref throws, tempid link succeeds):

- **Tempids resolve same-tx, order-independently, including upsert.** To upsert
  a person AND link an email to them in ONE tx, give the person a tempid (a
  string, e.g. `"person:a@x.com"`) and reference that tempid from the email.
  When the person's `:db.unique/identity` value matches an existing entity,
  datahike upserts the tempid onto that entity; both the person map and the
  email's ref resolve to the same eid.
- **Lookup-refs do NOT resolve against same-tx, not-yet-committed entities.** A
  forward lookup-ref to an entity created later in the same tx throws
  `Nothing found for entity id … :error :entity-id/missing`.

So: **tempids for same-tx-new entities; lookup-refs only for the already
committed.** Programmatic ingest should derive a stable tempid string from the
natural key (e.g. `(str "person:" email)`) — identical strings collapse to one
entity within the tx, which cooperates with `:db.unique/identity` upsert.

## Bridge wiring reference

| You register | Bridge installs |
|---|---|
| `[:string {:seon.db/identity true}]` | `:db.type/string` + `:db.unique/identity` (UPSERT + lookup-ref) |
| `:seon.db/ref` | `:db.type/ref`, cardinality one |
| `[:vector :seon.db/ref]` | `:db.type/ref`, cardinality **many** |
| `[:vector {:seon.db/component true} :seon.db/ref]` | `:db.type/ref`, many, `:db/isComponent true` (cascade) |
| `:inst` | `:db.type/instant` |
| `{:optional true}` on a `:map` entry | field may be absent (never nil) |

Inspect any derivation live: `(db/malli->datahike-schema [:co.comms.email/from
:co.comms.email/to :co.comms.email/deadline])`.

## Worked example — people / roles / orgs / emails with deadlines

A company knowledge graph (representative of ingesting docs + emails). `co.*`
namespaces are **illustrative** teaching names, not committed code (consumer
domains live downstream).

### Schema (`schema/register!` — single source of truth)

```clojure
;; --- co.people.person ---  (write these INSIDE that ns as ::email / ::name)
(schema/register! :co.people.person/email [:string {:seon.db/identity true}]) ; natural key → UPSERT
(schema/register! :co.people.person/name  :string)

;; --- co.org.company ---
(schema/register! :co.org.company/domain [:string {:seon.db/identity true}])  ; natural key → UPSERT
(schema/register! :co.org.company/name   :string)

;; --- co.people.role ---  reified relationship: an edge that carries facts
(schema/register! :co.people.role/person :seon.db/ref)   ; → co.people.person
(schema/register! :co.people.role/org    :seon.db/ref)   ; → co.org.company
(schema/register! :co.people.role/title  :string)
(schema/register! :co.people.role/since  :inst)

;; --- co.comms.email ---  from = one ref, to = many refs (one-directional)
(schema/register! :co.comms.email/message-id [:string {:seon.db/identity true}]) ; dedup key
(schema/register! :co.comms.email/subject    :string)
(schema/register! :co.comms.email/from       :seon.db/ref)           ; → co.people.person
(schema/register! :co.comms.email/to         [:vector :seon.db/ref]) ; → co.people.person (many)
(schema/register! :co.comms.email/sent-at    :inst)
(schema/register! :co.comms.email/deadline   :inst)                  ; OPTIONAL: absent != nil

;; --- co.ingest.tx ---  provenance attached to the reified transaction entity
(schema/register! :co.ingest.tx/source :string)
```

### `record-email!` — upsert by natural key, link via tempids, reify the tx

Map-in request, map-out response (`::ok?`-discriminated envelope — errors are
values). `^:async` because it awaits the write before returning.

```clojure
(schema/register! :co.comms.email/recipient
  [:map [:co.people.person/email :co.people.person/email]
        [:co.people.person/name {:optional true} :co.people.person/name]])
(schema/register! :co.comms.email/record-request
  [:map [:co.comms.email/message-id :co.comms.email/message-id]
        [:co.comms.email/subject    :co.comms.email/subject]
        [:co.comms.email/sender     :co.comms.email/recipient]
        [:co.comms.email/recipients [:vector :co.comms.email/recipient]]
        [:co.comms.email/sent-at    :co.comms.email/sent-at]
        [:co.comms.email/deadline {:optional true} :co.comms.email/deadline]
        [:co.ingest.tx/source       :co.ingest.tx/source]])
(schema/register! :co.comms.email/record-response
  [:map [:co.comms.email/ok?     :boolean]
        [:co.comms.email/message-id {:optional true} :co.comms.email/message-id]
        [:co.comms.email/error  {:optional true} :string]])

(defn ^:async record-email!
  "Ingest one email: upsert sender + recipients by natural-key email (the same
   person across many emails collapses to ONE entity), link via refs, dedup the
   email by :message-id, and reify the tx with provenance."
  {:malli/schema [:=> [:cat :co.comms.email/record-request]
                  :co.comms.email/record-response]}
  [{:co.comms.email/keys [message-id subject sender recipients sent-at deadline]
    src :co.ingest.tx/source}]
  (let [tid       (fn [p] (str "person:" (:co.people.person/email p)))  ; tempid from natural key
        person-ents (for [p (cons sender recipients)]
                      (cond-> {:db/id (tid p)                              ; same key → same entity (upsert)
                               :co.people.person/email (:co.people.person/email p)}
                        (:co.people.person/name p)
                        (assoc :co.people.person/name (:co.people.person/name p))))
        email-ent (cond-> {:co.comms.email/message-id message-id         ; dedup by identity
                           :co.comms.email/subject subject
                           :co.comms.email/from (tid sender)             ; tempid link (same-tx-safe)
                           :co.comms.email/to (mapv tid recipients)
                           :co.comms.email/sent-at sent-at}
                    deadline (assoc :co.comms.email/deadline deadline))  ; optional: only when present
        tx-ent    {:db/id :db/current-tx :co.ingest.tx/source src}       ; reify the tx
        env       (await (db/transact!
                           {:seon.db/tx-data (concat person-ents [email-ent tx-ent])}))]
    (if (:seon.db/ok? env)
      {:co.comms.email/ok? true :co.comms.email/message-id message-id}
      {:co.comms.email/ok? false
       :co.comms.email/error (get-in env [:seon.db/error :seon.error/message])})))
```

Note: `db/transact!` returns the COMPACT envelope (`:seon.db/ok?`,
`:seon.db/tempids`, `:seon.db/tx`, counts) — never a `:db-after` db value. Read
post-tx state with a fresh synchronous `db/query` / `db/pull` against your one
connection.

### `deadlines-for` — selective clause first, parameterized, reverse-ref

```clojure
(schema/register! :co.comms.email/deadline-row
  [:map [:co.comms.email/subject  :co.comms.email/subject]
        [:co.comms.email/deadline :co.comms.email/deadline]
        [:co.comms.email/from     :co.people.person/email]])
(schema/register! :co.comms.email/deadlines-request
  [:map [:co.people.person/email :co.people.person/email]])
(schema/register! :co.comms.email/deadlines-response
  [:map [:co.comms.email/deadlines [:vector :co.comms.email/deadline-row]]])

(defn deadlines-for
  "Emails involving a person (from OR to) that carry a deadline. Sync read."
  {:malli/schema [:=> [:cat :co.comms.email/deadlines-request]
                  :co.comms.email/deadlines-response]}
  [{person-email :co.people.person/email}]
  (let [rows (db/query
               '[:find ?subject ?deadline ?from-email
                 :in $ ?person-email
                 :where
                 [?p :co.people.person/email ?person-email]  ; most selective: pin by natural key
                 (or [?e :co.comms.email/from ?p]            ; one-directional refs; reverse is free
                     [?e :co.comms.email/to ?p])
                 [?e :co.comms.email/deadline ?deadline]     ; only deadline-bearing (absent != nil)
                 [?e :co.comms.email/subject ?subject]
                 [?e :co.comms.email/from ?fp]
                 [?fp :co.people.person/email ?from-email]]
               person-email)]                                  ; parameterized input, AFTER the query
    {:co.comms.email/deadlines
     (mapv (fn [[s d f]] {:co.comms.email/subject s
                          :co.comms.email/deadline d
                          :co.comms.email/from f})
           rows)}))
```

### What this model demonstrates

- **UPSERT** — recording two emails with the same sender yields exactly ONE
  Alice entity (the natural-key identity collapses them).
- **Dedup** — `:message-id` identity keeps two distinct emails distinct.
- **Refs link** — `from` resolves to Alice, `to` to the recipient set.
- **Reified-tx provenance** — `[?tx :co.ingest.tx/source ?s]` is queryable.
- **Derived view** — `deadlines-for` finds every email involving a person via
  the one-directional refs (reverse is free), no stored reverse attr.

(Mirror this as a real `cljs.test/deftest` in a `my.<domain>-test` ns the way
`my.kb-test` exercises `my.kb` — transact, then assert the read-back.)

## Reverse-ref navigation (one-directional refs, free reverse)

No reverse attr is stored. Navigate the reverse with a where-clause or a pull
`_`-prefix:

```clojure
;; "who sent emails to this person?" — via where clause
(db/query '[:find [?sender-email ...]
            :in $ ?recipient-email
            :where [?p :co.people.person/email ?recipient-email]
                   [?e :co.comms.email/to ?p]
                   [?e :co.comms.email/from ?s]
                   [?s :co.people.person/email ?sender-email]]
          "bob@example.com")

;; reverse-ref pull: emails this person SENT (the _from underscore form)
(db/pull '[:co.people.person/email
           {:co.comms.email/_from [:co.comms.email/subject]}]
         [:co.people.person/email "alice@example.com"])
```
