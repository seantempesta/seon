---
type: research
status: active
tags: [research, agent, architecture]
---

# Testing states by attribute presence, never kinds

Owner question (2026-07-28, verbatim intent): *"All the Datomic research and
my own experience tells me that if we are using kinds we are recording data
incorrectly. Something is true by the presence of attributes and values on
entities and the refs to other entities. How do we best test for different
STATES without calling something a kind?"*

## 1. The principle, crisply

An entity is an id plus the datoms it carries. **A state is true when the
facts that constitute it are present; a transition asserts new facts (and
retracts pointers) rather than mutating a label.** The current state is
therefore a *derivation* — a query over which attributes and refs exist at a
database value — never a stored field somebody has to keep in sync.

The repository already legislates this in three layers:

- `.agents/skills/data-modeling/SKILL.md` (Step 0): "there are NO entity
  kinds; you model ATTRIBUTES + connections … If you write 'for each kind' or
  a `:kind` enum, stop and reframe." FIND by presence, IDENTIFY by a
  `{:seon.db/identity true}` attr, RELATE/REMOVE by refs, SCOPE by tx
  provenance.
- `.agents/skills/datahike/SKILL.md`: the four moves with worked queries;
  "the grouping label is always an attribute … never a kind."
- `docs/seon/architecture/data-model.md` §3 draws the load-bearing
  distinction this note applies: an **entity-kind discriminator** (a stored
  field whose value selects which schema a row obeys) is BANNED; a **value
  enum** flavoring one already-identified entity is FINE. §4.1: "State is
  derived, never stored — there is no `:seon.agent/state` datom."
  `docs/conventions.md:378` keeps `:seon.error/kind` as the diagnostic tag on
  the flat error value.

The owner's question sharpens the middle ground the doctrine leaves open:
even a "value enum" on one identified entity is **recording data incorrectly
when its value is derivable from other facts on the same entity**. That is
the derive-don't-store rule (`data-oriented-clojure`) applied to state:

> A stored status is legitimate only when it is a genuine non-derivable
> fact — a decision taken, an observation made — never when it restates
> which other attributes are present.

## 2. Query patterns for testing state by presence — with Datahike cost

All citations are the vendored fork, `reference-code/datahike/`.

### 2.1 "Is/are entity(ies) in state S" — the attribute-presence clause

```clojure
[?e :seon.cluster.run/closed-at]        ; every closed run
[?e :seon.cluster.agent/run]            ; every busy agent (absence = idle)
(d/q '[:find (count ?e) . :where [?e :attr]] db)
```

Cost: a `[_ a _ _]` pattern selects the **`:aevt` strategy**
(`src/datahike/db/search.cljc:153`, `get-search-strategy-impl`) — one
contiguous slice of the AEVT index for that attribute, no scan of anything
else. This is the cheapest shape Datahike has for "all entities carrying X",
which is exactly why presence is the right physical representation of state,
not merely the right logical one.

### 2.2 "In state S with value v" — `[?e :attr v]`, index-aware

A `[_ a v _]` pattern uses `:aevt` with an equality filter
(`search.cljc:151`); adding `{:seon.db/index true}` to the registration flips
it to a direct **`:avet`** lookup (`search.cljc:150`, gated by
`indexing-for-pattern?`, `search.cljc:182-184`). Rule of thumb: an attribute
you query BY VALUE at volume (e.g. `:seon.error/signature`, already indexed,
`src/seon/schema/error.edn:63`) earns the index; a pure presence test never
needs it.

### 2.3 "NOT in state S" — `missing?`

```clojure
[(missing? $ ?e :seon.cluster.run/closed-at)]   ; still-open runs
```

`-missing?` is `(nil? (get (d/entity db e) a))` —
`src/datahike/query.cljc:493-495`, registered as a query built-in at
`query.cljc:617`. Cost: one per-entity EAVT probe for entities already bound;
so bind `?e` by a cheap presence clause first, then exclude.

### 2.4 "State with a default" — `get-else`

```clojure
[(get-else $ ?e :seon.cluster.run/claim-epoch 0) ?epoch]
```

`-get-else` is one `dbi/search [e a]` probe with a fallback
(`query.cljc:473-479`; nil default refused at `:475-476`). Datahike itself
models valid-time this way — an *open* interval is the **absence** of
`:db.valid/to`, defaulted at query time (`query.cljc:705-718`). The
dependency's own internals use presence-as-state.

### 2.5 Identity, not kind, answers "which one"

Upsert resolution walks the identity attribute (`upsert-eid`,
`src/datahike/db/transaction.cljc:630`; conflict check `:616`): the natural
key is both the lookup handle and the merge target. "What the entity is" is
its identity attr plus whatever else it carries — one entity may carry a run
id AND an interaction id (data-model.md §4.3) with no discriminator.

### 2.6 Transitions: win a presence race, decide inside the tx

- **CAS on absence**: `compare-and-swap`
  (`transaction.cljc:963`) with `old = nil` asserts "this attribute must be
  ABSENT" — the canonical way to enter a state exactly once (claim a run,
  freeze a plan). A state transition is then literally "assert the fact if
  the facts say I may."
- **`:db.fn/call`** (`transaction.cljc:1142`) applies a pure fn to the
  mid-transaction db; it tests eligibility by presence (`(d/entity db …)`)
  and refuses by throwing, atomically. `src/seon/cluster/run.cljc` is the
  live worked example (`claim-call` etc.).
- **Cascade is a connection property**: `retract-components`
  (`transaction.cljc` — only `retractEntity`/`retractAttribute` cascade,
  cf. data-model.md §2.1) — "removing a state's substructure" is ref
  topology, never a per-kind delete routine.
- **Past states** are the same derivation over `d/as-of` / `d/history`
  values; nothing needs a status log because the datom log IS one.

### 2.7 The recipe, assembled

For a lifecycle with N states: register one **timestamp or ref per state-
entering event** (`opened-at`, `closed-at`, `process`, `paused-at`,
`terminated-at`), make each transition a tx that asserts its fact (fenced by
CAS-on-absence or a `:db.fn/call` that inspects presence), and write ONE pure
`derive-state` projection that pattern-matches on which facts are present.
Every consumer calls the projection; no consumer reads a status field. This
is exactly the shipped run model: `src/seon/schema/run.edn:9` — "`/run` is
optional because an idle agent has no current run, and **absence IS idle
(there is no status to read)**."

## 3. Datomic community practice

**Marked as training knowledge — no fabricated citations; these are the
patterns as I know them from the Datomic community corpus (Hickey's talks,
day-of-datomic material, community writing), not verified page references.**

- **Accretion of facts / event-sourcing-lite**: the widely taught pattern is
  that a state machine over an order is *timestamps*: `:order/submitted-at`
  present ⇒ submitted, `:order/shipped-at` present ⇒ shipped. A transition
  ADDS a fact; nothing is overwritten; "current state" = the most advanced
  present fact. You get the audit trail for free from the tx log instead of
  a hand-built history table.
- **Refs-as-states vs enum keywords**: where a status is stored at all,
  classic Datomic style points a ref at an *ident entity*
  (`:order/status → :order.status/shipped`) so the state itself is an entity
  you can hang docs/attributes/queries on — better than a bare keyword, but
  still a stored label. Honest note: the community is NOT unanimous —
  stored status-ident fields are common in the wild. The sharper community
  rule, and the one Seon adopts, is **derived vs asserted**: store a status
  only when it records a non-derivable fact (a decision, an external
  observation); never store one that restates presence of other facts,
  because two representations of one truth WILL drift.
- **The transition is the fact, the state is the query**: `db.fn/cas` (and
  transaction functions generally) exist in Datomic precisely so "may I
  enter this state?" is decided against the in-transaction db rather than a
  caller's stale read — the same discipline §2.6 grounds in the Datahike
  fork.
- **`:db/noHistory` for churny presence**: high-frequency current-value
  state (heartbeats, stream partials) keeps presence semantics without
  temporal accumulation — Seon already applies this
  (`src/seon/schema/stream.edn`, all three stream attrs).

## 4. Audit — every stored discriminator/state enum in the fresh tree

Scope: `src/seon/`, `src/my/`, `src/seon/schema/*.edn` (`rg` for
`:seon.error/kind`, `:seon.render/kind`, `disposition`, `outcome`,
`status`, plus every `:enum` in a `{:seon.db/entity true}` map). Verdicts:
**(a)** genuine closed enum at a boundary / non-derivable fact — keep;
**(b)** derived value computed then discarded (in-flight) — fine;
**(c)** STORED discriminator that presence-of-facts could replace — the
defect the owner names.

| Site | Stored? | Verdict | Judgment |
|---|---|---|---|
| `:seon.cluster.eval/status [:enum :running :done :error :interrupted]` — `src/seon/schema/run.edn:25`, required on the receipt entity (`run.edn:59-70`); written `:running` at claim (`src/seon/cluster/run.cljc:502`), settled under the fence (`run.cljc:563-565`); queried `src/seon/problems.clj:127`, `src/seon/cluster/work.cljc:127` | yes | **(c)** — the headline defect | Three of four values restate presence on the same entity: `:done` ⟺ `result-edn` present, `:error` ⟺ `error` present, `:running` ⟺ neither. The receipt schema's own comment (`run.edn:54-58`) says "absence is the state" — and then stores the label anyway. Only `:interrupted` adds information. Presence rewrite: settle by asserting `result-edn` OR `error`; crash takeover asserts `:seon.cluster.eval/interrupted-at :inst`; the `:running`-only-settles-once fence becomes CAS-on-absence of the terminal fact (or the existing `:db.fn/call` refusing when one is present). `problems.clj:127`'s `[?receipt … :error]` becomes `[?receipt :seon.cluster.eval/error _]` — same `:aevt` walk, same cost (§2.2). |
| `:seon.ai.attempt/outcome [:enum :success :error]` — `src/seon/schema/ai.edn:108`, required on the stored attempt entity (`ai.edn:123`); written as `(if failure :error :success)` at `src/seon/cluster/loop.cljc:350` | yes | **(c)** — the purest specimen | The write site is literally a stored derivation of `:seon.ai.attempt/error` presence, computed in the same expression that decides whether to assoc the error ref (`loop.cljc:356`). Delete the attribute; "failed attempts" is `[?a :seon.ai.attempt/error]`, "succeeded" is `missing?` of it (§2.3). Nothing else distinguishes the two. (Contrast the target design's richer `:seon.ai.attempt/outcome` in `data-model.md` §4.4, where `:open`→terminal is a CAS cursor — see open question 3.) |
| `:seon.ai/disposition` stored on the attempt — `src/seon/schema/ai.edn:148` `[:enum :failover-now :backoff :fail]`, optional on `ai.edn:123`; written `loop.cljc:357,690`; computed by the pure `seon.ai/disposition` (`src/seon/ai.cljc:361-400`) from stored evidence (`error-class`, `output-observed?`, `request-transmitted?`, backup config) | yes | **(c)/borderline** | The disposition is a pure function of facts the attempt already stores, so persisting it is stored-derived state. Counterargument: it records the decision *actually acted on*, robust to the fn evolving. But `as-of` at the attempt's tx re-derives exactly what was decidable then, and if the fn's output at the same evidence changed, that drift is precisely what we'd want visible, not frozen. Recommend: derive at read; keep only the evidence booleans. |
| `:seon.error/kind :keyword` — `src/seon/schema/error.edn:34`, required on the stored `:seon.error/fact` (`error.edn:85`), optional on the eval receipt (`run.edn:69`) | yes | **(a)** — keep | Not an entity discriminator (the fact is identified by its own attrs) and not derivable: it names *which rule failed* — a genuine observation. Deliberately an open `:keyword`, never an enum (`error.edn:18-25`: "KINDS ARE NEVER ENUMERATED HERE"), fail-closed to `:seon.error/unclassified` (`error.edn:81-85`). This is the doctrine's sanctioned "value enum on one identified kind", done right. |
| `:seon.render/kind :qualified-keyword` — `src/seon/schema/render.edn:15`; `kinds` in `src/seon/render.clj:96-115` | request value only | **already presence-based** | Honest answer to the spec's question 5: this is NOT a kind-stamp. `:seon.render/kind` is the *name of the requested output* — a positional argument to `render`, never stored on an entity. What a unit CAN become is **computed from key presence** (`kinds`: every `seon.render`-namespaced key whose value is a `declaration?`), and "a kind IS its key" (`render.edn:12-14`) — the declaration attribute's presence is the state. The stored attributes (`:seon.render/ai`, `:seon.render/html`, `:seon.render/log`) are qualified symbols; the block entity carries no discriminator and "presence decides placement" (`src/seon/schema/block.edn:49-55`). The word "kind" here means "which projection", the same way `:find` means "which shape" — grounded, keep. |
| `:my.run/disposition [:enum :wait :completed]` — `src/seon/schema/dispositions.edn:6`; agent-facing return value, durable only as the last form's `result-edn` string | wire value | **(a)** with a note | A closed tag on a value crossing the agent/driver boundary — the first of the three agent-facing shapes, and boundaries are where closed enums are sanctioned. Note honestly: the tag duplicates key presence (`:my.run/note` ⟺ wait, `:my.run/result` ⟺ completed; the two maps are closed and disjoint), so the discriminator-free union idiom `:seon.render/surface` uses (`block.edn`, "A UNION OF TWO CLOSED MAPS … and no discriminator") would work here too. Kept-as-is is defensible for agent legibility; see open question 2. |
| `:seon.cluster.loop/outcome [:enum :closed :released :error :interrupted]` — `src/seon/schema/loop.edn:42,44` (turn-report) | no | **(b)** | In-flight report value, computed then discarded. Fine. |
| `:seon.eval/outcome [:enum :ok :time :error]` — `src/seon/schema/admit.edn:18` (sci admission record) | no | **(b)** | Diagnostic record shape; never an entity attribute. Fine. |
| `:seon.flow/fix-outcome`, `:seon.flow/lineage-status`, `:seon.flow/workload` — `src/seon/schema/flow.edn:26,33,57` | no | **(b)** | Harness/request-response values over process-local machinery. Fine. |
| `:seon.error/reason [:enum :your-run :no-attributable-agent :recurring :failover]` — `error.edn:135` | no | **(b)** — exemplary | Explicitly derived per-recipient and "never stored … because it is per-RECIPIENT" (`error.edn:123-128`). The model case of a derived label kept out of the database. |
| `:seon.error/notification [:enum :final]` — `error.edn:67` (notice value) | no | **(b)**, smell noted | A one-member enum is a presence flag wearing an enum costume — the key's presence already says everything its only value can. Harmless (in-flight), but worth flattening to key-presence when the notice shape is next touched. |
| `:seon.ai.attempt/request-transmitted?` / `response-started?` / `output-observed?` — `ai.edn:123`, optional stored booleans | yes | **(a)**, smell noted | Genuine non-derivable transport evidence (they feed `disposition`). Mild smell: optional booleans make "absent" vs "false" two states; the schema treats absence as unknown, which is honest, but each site must use explicit `or`, never `:or` destructuring defaults. Not a kind. |
| `:seon.ai/error-class` (`:authentication`/`:rate-limit`/… via `status-class`, `src/seon/ai.cljc:328-341`) stored on the attempt | yes | **(a)/borderline (b)** | Derived from the HTTP status — but the raw `:seon.ai/http-status` is ALSO stored (`ai.edn:137`), so the class is stored-derived. Cheap to re-derive at read (`status-class` is pure over one stored int). Low-leverage cleanup; fold into the disposition rewrite. |

**Exemplary presence-based sites already in the fresh tree** (the standard
the rewrites should match): `run.edn:9` (absence of `:seon.cluster.agent/run`
IS idle); `run.edn:42` (the run entity has NO status — `closed-at` presence =
closed, `process` presence = claimed, `error` presence = failed-before-plan);
`message.edn:14-25` (absence of `from` = human/system origin — explicitly
deleting the quarry's stored origin enum); `boot.edn:27-31` ("Counts, not
status flags: absence means the tower stopped before recovery");
`problems.edn:10` ("`(seq problems)` rather than a status anybody has to
maintain").

## 5. Recommended rewrites, ranked by leverage

1. **Delete `:seon.ai.attempt/outcome`** (clearest cut, smallest blast
   radius): drop the attribute from `ai.edn:108/123` and the write at
   `loop.cljc:350`; consumers test `:seon.ai.attempt/error` presence /
   `missing?`. One schema line, one write site, queries get simpler.
2. **Replace `:seon.cluster.eval/status` with terminal-fact presence**:
   settle asserts `result-edn` or `error` (already written); add
   `:seon.cluster.eval/interrupted-at :inst` for crash takeover; the
   only-settle-once fence becomes CAS-on-absence (or the existing
   `:db.fn/call` refusing on any-terminal-present); rewrite the four query
   sites (`problems.clj:127`, `work.cljc:127`, `run.cljc:119-129,563-601`,
   `loop.cljc:173-180,847-848`) plus the in-flight `evaluation` /
   `terminal-request` shapes in `loop.edn`. Highest state-model leverage:
   the receipt becomes the same shape as the run entity, and "absence is the
   state" stops being a comment contradicted by its own map.
3. **Stop storing `:seon.ai/disposition` (and `:seon.ai/error-class`)**:
   derive both at read from the stored evidence
   (`disposition` at `ai.cljc:361`, `status-class` at `ai.cljc:328`);
   attempts keep only observations. Removes the last stored-derived values
   from the attempt receipt.
4. **When next touching the notice shape**, flatten
   `:seon.error/notification [:enum :final]` to key presence; and consider
   the discriminator-free union for `:my.run/value` (open question 2) —
   both are polish, not defects.

## 6. Open questions for the owner

1. **Is a durable CAS cursor ever worth a stored enum?** The presence
   alternative for receipt settlement fences on "terminal fact absent",
   which under cardinality-one CAS semantics means the fence attribute
   differs per terminal arm (`result-edn` vs `error`), pushing the guard
   into `:db.fn/call` (co-located only). Fine for today's one-JVM cluster —
   but if a receipt transition ever has to cross a wire (pure-data CAS
   only), a single always-present attribute is the natural CAS target. Rule
   now, or defer until a wire exists?
2. **Agent-facing tags**: `:my.run/disposition` duplicates key presence but
   makes the agent's returned value self-describing. Keep explicit tags on
   agent-facing wire values as a legibility convention, or extend the
   discriminator-free-union idiom (`:seon.render/surface`) to them?
3. **The target design** (`data-model.md` §4.3-4.4) still specifies stored
   `:seon.agent.run/status [:enum :open :closed]`, `closed-reason`, turn
   `status` + `phase`, and a five-value interaction `status` — while the
   FRESH tree's run entity already proves the presence model
   (`closed-at`/`process`/`error`, no status). Should the architecture doc
   be revised now to the presence model (closed-reason as its own
   presence-carrying facts, phase as per-phase timestamps), so N-lane
   implementers don't re-import the enums the fresh tree just designed out?
4. **`closed-reason`-shaped facts**: when a state has many *causes*
   (`:completed :waited :turn-limit …`), is the preferred shape one optional
   keyword attr on the closed entity (a value enum, doctrine-legal), or one
   fact per cause family (e.g. `deadline-exceeded-at`, `superseded-by` ref)
   whose presence carries more structure? The latter is more presence-pure;
   the former is one attribute instead of nine. A ruling here settles the
   whole "reason" class at once.
