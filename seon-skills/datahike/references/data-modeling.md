# Data Modeling — Fact Databases in Seon / Datahike

Datomic best-practice modeling (entities, natural-key identity/upsert, refs +
cardinality, reified relationships, reified transactions, intra-tx tempids vs
lookup-refs) mapped onto seon's `schema/register!` + the Malli→datahike bridge,
in **pod idiom**: ONE connection, map-in `transact!` returning an envelope,
synchronous reads with the db auto-injected. The modeling principles are
Datomic-compatible and unchanged; only the call shapes are pod-native.

`src/my/kb.cljs` is the runnable version of everything below (every recipe
compiles and is exercised by `my.kb-test`). `src/seon/agent/todo.cljs` is the
exemplar of a tree/DAG model with derived datalog rules.

## The `::` + deep-namespace convention (read this first)

**Attributes are developed inside specific, deeply-nested namespaces and
reference their own attrs with `::`.** The schema for a piece of data lives in
the namespace whose name it carries (colocation). This is the load-bearing
naming rule — it lets a single Datalog query join function specs to the data
those functions operate on.

- Inside namespace `acme.people.person`, you write `::email` — the reader
  expands it to `:acme.people.person/email`. You never type the long form for
  your own attrs.
- A cross-namespace reference uses the **full** keyword. Inside
  `acme.comms.email`, the sender ref `::from` (= `:acme.comms.email/from`)
  points at an `acme.people.person` entity. The pointing is structural (a
  `:db.type/ref`); the *naming* still obeys colocation — `::from` is named in
  the email namespace because the email owns the fact "this email has a sender".

Do **not** use shallow names like `:person/email`. They collapse distinct
domains into one flat namespace and read as "belongs to a namespace `person`"
that doesn't exist as code. Use the real nested namespace.

```clojure
;; INSIDE namespace acme.people.person
(schema/register! ::email [:string {:seon.db/identity true}])  ; :acme.people.person/email
(schema/register! ::name  :string)                              ; :acme.people.person/name

;; INSIDE namespace acme.comms.email — a ref to a person in ANOTHER namespace
(schema/register! ::from :seon.db/ref)                          ; :acme.comms.email/from
```

## Best-practices checklist (mapped to seon wiring)

### Schema

- **Group related attributes in a deeply-nested namespace.** One namespace per
  conceptual thing: `acme.people.person`, `acme.org.company`, `acme.comms.email`.
  (Remember: this is naming + colocation, NOT an entity "kind" — an entity is
  still just its attrs + refs. See SKILL.md "no kinds".)
- **Unique identity for external / natural keys ⇒ UPSERT.** Account numbers,
  emails, message-ids, domains. `[:string {:seon.db/identity true}]` → the
  bridge installs `:db/unique :db.unique/identity`. Re-transacting the same
  natural-key value updates the existing entity instead of creating a new one.
- **Model relationships in ONE direction.** Datahike indexes refs both ways, so
  the reverse is free — a reverse-ref pull (`:acme.comms.email/_from`) or a
  `[?e :acme.comms.email/to ?p]` clause. Never store a redundant reverse attr.
- **Reified relationships are entities.** An edge that carries its own facts (a
  Role: title + start date linking a Person to an Org) is its own entity with
  ref attrs to each end — not a bare ref.
- **Plan for accretion.** Never assume an entity's full attr set; pull what you
  need. Optional attrs are **absent, never nil** (`{:optional true}`).
- **Grow schema, never break it.** Only add; never remove or repurpose a name.
- **Enums as keywords.** `[:enum :a :b]` — the bridge infers the value type
  from the (keyword) enum members.

### Transactions

- **Every transaction carries two durable provenance refs.** The transaction
  entity receives `:seon.db/user` and `:seon.db/process`. `db/with-agent`
  selects the current agent as the user through the REPL process; core boot and
  config work use `db/with-tx-context` to select root plus the appropriate
  process. Turn, eval, replay, and test execution values stay runtime-only.
- **Add a custom transaction fact only when it is a domain fact.** Include a
  map keyed `:db/id :db/current-tx` (alias
  `"datomic.tx"`) in the same tx — datahike resolves it to the current
  transaction entity. For example, an ingest source can be a useful durable
  fact without duplicating user or process:

    ```clojure
    (db/transact! {::db/tx-data
                   [{:acme.people.person/email "a@x.com"}
                    {:db/id :db/current-tx :acme.ingest.tx/source "inbox-export-2026"}]})
    ;; later: which source did these facts come from?
    (db/query '[:find [?s ...] :where [?tx :acme.ingest.tx/source ?s]])
    ;; => ["inbox-export-2026"]
    ```

  `:acme.ingest.tx/source` is a normal registered attr — nothing special about
  putting it on the tx entity.
- **Lookup-refs specify EXISTING entities only.** A lookup-ref
  (`[:acme.people.person/email "a@x.com"]`) resolves only against entities
  already committed (or that appear earlier in the same tx and commit first).
  See the intra-tx rule below.

### Query

- **Most selective clause first** — pin the entity by its natural key, walk out.
- **Parameterize with `:in`** — pass user data as query inputs (inputs come
  AFTER the query; no string building).
- **Use pull to retrieve attribute values** — `:where` finds entities, pull
  navigates their attrs (including across refs).

## Intra-tx ref rule (tempids vs lookup-refs)

Load-bearing and source-grounded (`datahike-primer.md`; forward lookup-ref
throws, tempid link succeeds):

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

Inspect any derivation live: `(db/malli->datahike-schema [:acme.comms.email/from
:acme.comms.email/to :acme.comms.email/deadline])`.

## Worked example — people / roles / orgs / emails with deadlines

A company knowledge graph (representative of ingesting docs + emails). `acme.*`
namespaces are **illustrative** teaching names, not committed code (consumer
domains live downstream).

### Schema (`schema/register!` — single source of truth)

```clojure
;; --- acme.people.person ---  (write these INSIDE that ns as ::email / ::name)
(schema/register! :acme.people.person/email [:string {:seon.db/identity true}]) ; natural key → UPSERT
(schema/register! :acme.people.person/name  :string)

;; --- acme.org.company ---
(schema/register! :acme.org.company/domain [:string {:seon.db/identity true}])  ; natural key → UPSERT
(schema/register! :acme.org.company/name   :string)

;; --- acme.people.role ---  reified relationship: an edge that carries facts
(schema/register! :acme.people.role/person :seon.db/ref)   ; → acme.people.person
(schema/register! :acme.people.role/org    :seon.db/ref)   ; → acme.org.company
(schema/register! :acme.people.role/title  :string)
(schema/register! :acme.people.role/since  :inst)

;; --- acme.comms.email ---  from = one ref, to = many refs (one-directional)
(schema/register! :acme.comms.email/message-id [:string {:seon.db/identity true}]) ; dedup key
(schema/register! :acme.comms.email/subject    :string)
(schema/register! :acme.comms.email/from       :seon.db/ref)           ; → acme.people.person
(schema/register! :acme.comms.email/to         [:vector :seon.db/ref]) ; → acme.people.person (many)
(schema/register! :acme.comms.email/sent-at    :inst)
(schema/register! :acme.comms.email/deadline   :inst)                  ; OPTIONAL: absent != nil

;; --- acme.ingest.tx ---  provenance attached to the reified transaction entity
(schema/register! :acme.ingest.tx/source :string)
```

### `record-email!` — upsert by natural key, link via tempids, reify the tx

Map-in request, map-out response (`::ok?`-discriminated envelope — errors are
values). `^:async` because it awaits the write before returning.

```clojure
(schema/register! :acme.comms.email/recipient
  [:map [:acme.people.person/email :acme.people.person/email]
        [:acme.people.person/name {:optional true} :acme.people.person/name]])
(schema/register! :acme.comms.email/record-request
  [:map [:acme.comms.email/message-id :acme.comms.email/message-id]
        [:acme.comms.email/subject    :acme.comms.email/subject]
        [:acme.comms.email/sender     :acme.comms.email/recipient]
        [:acme.comms.email/recipients [:vector :acme.comms.email/recipient]]
        [:acme.comms.email/sent-at    :acme.comms.email/sent-at]
        [:acme.comms.email/deadline {:optional true} :acme.comms.email/deadline]
        [:acme.ingest.tx/source       :acme.ingest.tx/source]])
(schema/register! :acme.comms.email/record-response
  [:map [:acme.comms.email/ok?     :boolean]
        [:acme.comms.email/message-id {:optional true} :acme.comms.email/message-id]
        [:acme.comms.email/error  {:optional true} :string]])

(defn ^:async record-email!
  "Ingest one email: upsert sender + recipients by natural-key email (the same
   person across many emails collapses to ONE entity), link via refs, dedup the
   email by :message-id, and reify the tx with provenance."
  {:malli/schema [:=> [:cat :acme.comms.email/record-request]
                  :acme.comms.email/record-response]}
  [{:acme.comms.email/keys [message-id subject sender recipients sent-at deadline]
    src :acme.ingest.tx/source}]
  (let [tid       (fn [p] (str "person:" (:acme.people.person/email p)))  ; tempid from natural key
        person-ents (for [p (cons sender recipients)]
                      (cond-> {:db/id (tid p)                              ; same key → same entity (upsert)
                               :acme.people.person/email (:acme.people.person/email p)}
                        (:acme.people.person/name p)
                        (assoc :acme.people.person/name (:acme.people.person/name p))))
        email-ent (cond-> {:acme.comms.email/message-id message-id         ; dedup by identity
                           :acme.comms.email/subject subject
                           :acme.comms.email/from (tid sender)             ; tempid link (same-tx-safe)
                           :acme.comms.email/to (mapv tid recipients)
                           :acme.comms.email/sent-at sent-at}
                    deadline (assoc :acme.comms.email/deadline deadline))  ; optional: only when present
        tx-ent    {:db/id :db/current-tx :acme.ingest.tx/source src}       ; reify the tx
        env       (await (db/transact!
                           {:seon.db/tx-data (concat person-ents [email-ent tx-ent])}))]
    (if (:seon.db/ok? env)
      {:acme.comms.email/ok? true :acme.comms.email/message-id message-id}
      {:acme.comms.email/ok? false
       :acme.comms.email/error (get-in env [:seon.db/error :seon.error/message])})))
```

Note: `db/transact!` returns the COMPACT envelope (`:seon.db/ok?`,
`:seon.db/tempids`, `:seon.db/tx`, counts) — never a `:db-after` db value. Read
post-tx state with a fresh synchronous `db/query` / `db/pull` against your one
connection.

### `deadlines-for` — selective clause first, parameterized, reverse-ref

```clojure
(schema/register! :acme.comms.email/deadline-row
  [:map [:acme.comms.email/subject  :acme.comms.email/subject]
        [:acme.comms.email/deadline :acme.comms.email/deadline]
        [:acme.comms.email/from     :acme.people.person/email]])
(schema/register! :acme.comms.email/deadlines-request
  [:map [:acme.people.person/email :acme.people.person/email]])
(schema/register! :acme.comms.email/deadlines-response
  [:map [:acme.comms.email/deadlines [:vector :acme.comms.email/deadline-row]]])

(defn deadlines-for
  "Emails involving a person (from OR to) that carry a deadline. Sync read."
  {:malli/schema [:=> [:cat :acme.comms.email/deadlines-request]
                  :acme.comms.email/deadlines-response]}
  [{person-email :acme.people.person/email}]
  (let [rows (db/query
               '[:find ?subject ?deadline ?from-email
                 :in $ ?person-email
                 :where
                 [?p :acme.people.person/email ?person-email]  ; most selective: pin by natural key
                 (or [?e :acme.comms.email/from ?p]            ; one-directional refs; reverse is free
                     [?e :acme.comms.email/to ?p])
                 [?e :acme.comms.email/deadline ?deadline]     ; only deadline-bearing (absent != nil)
                 [?e :acme.comms.email/subject ?subject]
                 [?e :acme.comms.email/from ?fp]
                 [?fp :acme.people.person/email ?from-email]]
               person-email)]                                  ; parameterized input, AFTER the query
    {:acme.comms.email/deadlines
     (mapv (fn [[s d f]] {:acme.comms.email/subject s
                          :acme.comms.email/deadline d
                          :acme.comms.email/from f})
           rows)}))
```

### What this model demonstrates

- **UPSERT** — recording two emails with the same sender yields exactly ONE
  Alice entity (the natural-key identity collapses them).
- **Dedup** — `:message-id` identity keeps two distinct emails distinct.
- **Refs link** — `from` resolves to Alice, `to` to the recipient set.
- **Reified-tx provenance** — `[?tx :acme.ingest.tx/source ?s]` is queryable.
- **Derived view** — `deadlines-for` finds every email involving a person via
  the one-directional refs (reverse is free), no stored reverse attr.

(Mirror this as a test the way `my.kb-test` exercises `my.kb` — a fresh database
via `bin/seon cluster reset default`, then assert read-back. See the
`clojure-testing` skill.)

## Reverse-ref navigation (one-directional refs, free reverse)

No reverse attr is stored. Navigate the reverse with a where-clause or a pull
`_`-prefix:

```clojure
;; "who sent emails to this person?" — via where clause
(db/query '[:find [?sender-email ...]
            :in $ ?recipient-email
            :where [?p :acme.people.person/email ?recipient-email]
                   [?e :acme.comms.email/to ?p]
                   [?e :acme.comms.email/from ?s]
                   [?s :acme.people.person/email ?sender-email]]
          "bob@acme.com")

;; reverse-ref pull: emails this person SENT (the _from underscore form)
(db/pull '[:acme.people.person/email
           {:acme.comms.email/_from [:acme.comms.email/subject]}]
         [:acme.people.person/email "alice@acme.com"])
```
