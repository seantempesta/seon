---
type: research
status: active
tags: [research, schema, database, reference]
---

# Datomic best practices → seon/datahike, with a worked fact-model example (2026-06-08)

## TL;DR

Captured from the official Datomic best-practices reference
(<https://docs.datomic.com/reference/best.html>) and mapped onto seon's
`schema/register!` + datahike wiring. The load-bearing rules for modeling a
FACT database (people, roles, deadlines, emails, docs):

1. **Namespace related attributes** (`:person/email`, `:role/title`).
2. **Unique identity for external/natural keys** → `:db.unique/identity` → UPSERT.
   In seon: `[:string {:seon.db/identity true}]`.
3. **Model relationships in ONE direction** — datahike/Datomic index refs both
   ways (VAET), so the reverse is free via reverse-ref pull (`:email/_from`) or a
   `[?e :email/to ?p]` clause. Never store a redundant reverse ref attr.
4. **Reify the transaction** — model when/who/where/why on the tx entity. In
   seon this is exactly `seon.db/with-tx-context` (+ `:seon.db/origin`,
   `:seon.db/agent-id`) auto-merged into tx-meta. Use it to record provenance
   (which doc/email a fact came from).
5. **Reified relationships** — an edge that carries its own facts (a Role:
   title and start date linking Person↔Org) is its own ENTITY, not a bare ref.
6. **Plan for accretion** — never assume an entity's full attr set; `select-keys`
   what you need. Optional attrs are absent, never nil (seon: `{:optional true}`).
7. **Grow schema, never break it** — only add; never remove/repurpose a name. Use
   aliases + annotate (`:db/doc`) for evolution.
8. **Enums as idents** — Datomic models enumerated types as a ref to an
   ident entity (keyword), memory-efficient + referenceable in txs.

## Intra-tx ref mechanic (reconciles the two seon research docs)

From `ref-model-research.md` (line-cited against `reference-code/datahike/`):

- **tempids resolve same-tx**, including upsert via `:db.unique/identity` — so to
  upsert a person AND link an email to them in ONE tx, use a tempid for the
  person and reference that tempid from the email. Datahike upserts the tempid to
  the existing entity when the identity value matches.
- **lookup-refs (`[:person/email "x"]`) do NOT resolve against same-tx,
  not-yet-committed entities.** They work only for entities ALREADY committed (or
  appearing earlier in the same tx and committed first).
- Datomic best practice "use lookup refs to specify EXISTING entities" is exactly
  right — lookup-refs for the already-present, tempids for the same-tx-new.

## Full best-practices list (verbatim-ish from the reference)

### Schema

- **Group related attributes in a namespace** — reduces conflicts, clarifies
  domain, without restricting entity flexibility.
- **Plan for accretion** — don't assume an entity is limited to its current attr
  set; use `select-keys`, not logic over all possible attrs.
- **Model relationships in one direction only** — refs are auto-indexed both ways;
  a bidirectional attr is redundant.
- **Use idents for enumerated types** — an enum value is a ref to an entity with
  a `:db/ident`; memory-efficient, transactions reference idents directly.
- **Use unique identities for external keys** — account numbers, emails →
  `:db.unique/identity`.
- **Use noHistory for high-churn attributes** — counters/version incrementers set
  `:db/noHistory true` to cut DB size + indexing.

### Production schema

- **Grow schema and never break it** — add only; removing/repurposing is breakage.
- **Never remove or reuse names** — breaks dependents.
- **Use aliases** — multiple `:db/idents` → one schema entity, to rename safely.
- **Annotate schema** — `:db/doc`, `:schema/see-instead`, custom attrs; schema is
  data, document it.

### Transactions

- **Add facts about the transaction entity** — model when/who/where/why on the
  reified tx (`:db/id "datomic.tx"`).
- **Use lookup refs to specify existing entities** — flatten updates, no pre-query.
- **Use CAS for optimistic concurrency** — catch conflict, retry.
- **Use db-after to check transaction results** — the report's `:db-after`
  isolates your tx's impact.
- **Set txInstant on imports** — override timestamp (monotonic, ≤ transactor clock).
- **Pipeline transactions for throughput** — async API, several in-flight.

### Query

- **Most selective clauses first.**
- **Join along** — each clause binds on a variable from a preceding clause; avoid
  cross products.
- **Prefer query over raw index access** — declarative, optimizable, composable.
- **Pass collections as inputs** — collection binding = logical OR; one
  parameterized query beats N.
- **Use pull to retrieve attribute values** — `:where` to find entities, pull to
  navigate attrs.
- **Put blanks (`_`) in data patterns** — match-anything placeholders, clearer.
- **Use query inputs to parameterize + leverage caching** — equal arg data
  structures reuse the cache.
- **Work with data structures, not strings** — no injection; pass user data as
  inputs.

### Time & history

- **Use a consistent db value for a unit of work** — call `db` once per logical
  operation for consistent, testable reads.
- **Specify t instead of txInstant for precise asOf** — `t`/`tx` order exactly;
  wall-clock is imprecise (many txs per ms).
- **Use the history filter for audit-trail queries** — `d/history` sees all
  datoms incl. retracted ("what we knew then").
- **Pass multiple points-in-time to a single query** — join across time
  perspectives (unfiltered + filtered as separate inputs).
- **Use the log API if time is your most selective criterion** — `tx-range` for
  fast time-ordered lookups.

## The worked example (people / roles / emails with deadlines)

The full worked example — schema, `record-email!`, `deadlines-for`, and a
green `deftest` — was built and **verified live in the JVM REPL** (8 assertions,
0 failures, against an isolated `:memory` flow) and now lives as the canonical
copy in the datahike skill:

- **`.claude/skills/datahike/references/data-modeling.md`** — best-practices
  checklist mapped to `register!` wiring, the deep-namespace + `::` convention,
  the intra-tx tempid-vs-lookup-ref rule, and the verified example.

It demonstrates, in one coherent batch: natural-key identity → upsert, refs +
cardinality-many, a reified Role relationship, `:inst`, optional-as-absent, and
tx-provenance via the reified transaction entity.

Two corrections to the early framing in this doc, learned during live
verification, that the skill reference reflects:

- **Use deeply-nested namespaces, not shallow `:person/*`.** Attributes live in
  the namespace whose name they carry (`:acme.people.person/email`,
  `:acme.comms.email/from`), so an agent inside `acme.people.person` writes
  `::email`. Shallow names defeat the join-specs-to-data goal.
- **There is no `with-tx-context` in `seon.db`.** Reify the transaction by
  including a map with `:db/id "datomic.tx"` carrying provenance attrs (e.g.
  `:acme.ingest.tx/source`) in the tx-data — datahike resolves `"datomic.tx"`
  to the current tx entity. Also: `seon.db/transact!` returns
  `{:tempids ... :tx-data ...}`, **not** a `:db-after` value (the flow
  serializes reads and keeps the db behind the conn-process); read post-tx
  state with a fresh `db/query` / `db/pull-by-name`.

The same-tx upsert+link mechanic uses **tempids** (verified order-independent);
a forward lookup-ref to a same-tx-uncommitted entity throws
`:entity-id/missing` (verified live). See
`docs/prds/datahike-migration/ref-model-research.md` for the source-cited
intra-tx mechanics.
