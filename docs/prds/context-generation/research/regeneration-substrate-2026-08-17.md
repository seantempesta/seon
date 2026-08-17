---
type: research
status: current
tags: [research, context, render, database, basis, regeneration]
---

# Regeneration substrate — can `Context(namespace, db)` be a pure function?

*Bounded source audit, 2026-08-17. Grounding read end to end before writing:
[runtime-first vision PRD](../plan/runtime-first-vision-prd-2026-08-17.md) and
[one-renderer PRD §0-§1](../plan/one-renderer-prd-2026-08-14.md). The question
put to the tree: is basis-primary ordering plus receipted-read regeneration
buildable on the facts we already store? Method: read the schemas and the
owning namespaces at the bytes. NO live cluster was running
(`bin/seon status`: `0/0 clusters alive`), so every claim here is source
evidence, not a live probe — the three claims marked **[UNPROBED]** are the
ones a live falsifier should attack first. Passing observation, not this
document's subject: the shared root footprint reads 15.38 GiB with 8 invalid
external claims.*

---

## 1. Receipts and basis — is the interleaved order recoverable from facts?

### 1.1 What each family actually carries

**Eval receipts** (`resources/seon/schemas/seon.cluster.eval.edn`):
`/id` (identity, `(pr-str [run-id ordinal])`), `/run` ref, `/ordinal`, `/at`
(inst), `/read-basis-transaction` (`:seon.db/basis-t` — line 11),
`/read-evidence` (component vector, lines 9-25), plus the terminal facts
`/result-edn`, `/result-blob`, `/error`, `/interrupted-at`.

**Run forms** (`seon.cluster.run.form.edn`): `/id`, `/run`, `/ordinal`,
`/author` — `[:enum :agent :system]` at line 33 — `/source`, `/ns`, and
`/refreshes`, a **unique** ref (line 38). There is **no instant and no basis
attribute on a form at all**.

**Messages** (`seon.cluster.message.edn`): `/id`, `/at`, `/ordinal`
(optional), `/from`, `/to`, `/caused-by`, `/about`.

### 1.2 Basis-t is derivable, and the codebase already derives it three ways

1. `seon.cluster.run/opening-db` (`src/seon/cluster/run.clj:258-279`) binds
   the transaction of the `opened-at` datom (`[?run :seon.cluster.run/opened-at _ ?tx]`)
   and hands back `(db/as-of database opening-tx)`. Its docstring states the
   principle exactly: *"The opening transaction is derived from the `opened-at`
   datom rather than stored as another run attribute."* This is the precedent
   the whole design rests on.
2. `seon.render.transcript/message-order-facts` (`src/seon/render/transcript.clj:358-372`)
   and `recent-message-rows` (`:182`) bind `?tx` off the `/at` datom and use it
   as an order key.
3. `seon.render.transcript/entry-basis` (`:901-906`) reduces
   `(db/datoms db :eavt <eid>)` to the max `:tx` — a per-entity basis with no
   stored attribute.

So **the answer to "can basis-t be recovered" is yes, for every family**, and
by an existing idiom rather than a new mechanism. `seon.db/basis-t`
(`src/seon/db.clj:182-195`) and `seon.db/as-of` (`:1357-1369`) are the
supporting owners.

### 1.3 What today's ordering actually uses — and why it is wrong for this

`seon.render.transcript/entry-order` (`:464-492`) sorts **instant-primary**:
`[1 at kind-rank run-opened-at ordinal id]`. That is not basis order, and the
instant is degenerate: `src/seon/cluster/loop.clj:1616-1622` stamps
`:seon.cluster.eval/at now` where `now` is bound **once outside** the fold loop
(`:1590`), so **every receipt in one run fold shares one instant**. Ordering by
`at` therefore collapses to the `[run-opened-at, ordinal]` tiebreakers within a
run, and across concurrently-settling runs it is a wall-clock race. Basis-t is
strictly better information that the system already has.

### 1.4 Is the full interleave derivable at any later time?

Yes, with one required refinement. The key is **(basis-t, run-ordinal)
lexicographic**, not basis-t alone, because assertion is not one-form-at-a-time
everywhere:

- **Generated (system) openings** append one form per transaction —
  `run/append-generated-call` (`run.clj:769-827`) refuses unless the requested
  ordinal equals the form count AND the prior ordinal already has a terminal
  receipt (`:797-816`). Basis-t alone orders these correctly.
- **Digest-frozen runs** (`run/system-plan-tx` via `system-run-tx`,
  `run.clj:735-767`) and an ordinary model reply's frozen ordered forms assert
  **all their forms in ONE transaction**. Those N forms share one basis;
  `:seon.cluster.run.form/ordinal` is the only separator.
- **Receipts span two transactions**: `receipt-start-call` (`run.clj:1034-1056`)
  asserts id/run/ordinal/at; settlement (`receipt-settle-tx`, `:1096-1117`)
  asserts the terminal facts later. "The receipt's basis" is therefore
  ambiguous unless defined as *the transaction asserting a terminal attribute*.

**System-authored reads are already receipted, by fact.**
`:seon.cluster.run.form/author :system` (`run.clj:824`, `:993`) plus a receipt
at the same `[run, ordinal]` is exactly the "replay receipts in basis order,
generate only the unreceipted frontier" substrate. `bootstrap/next-entry-in`
(`src/seon/bootstrap.clj:536-606`) already implements that loop against
receipts — see §2.

**Diff-stale re-reads are already a fact chain.** `run/refresh-call`
(`run.clj:927-1001`) opens a one-form system run whose form carries
`:seon.cluster.run.form/refreshes <prior form>`, refusing when the prior form
is agent-authored (`:951`), when its receipt is not terminal (`:967`), when it
recorded no read evidence (`:969`), or when a successor already exists
(`:971`). The `unique` constraint on `/refreshes` makes double-refresh
unrepresentable. This is the PRD's "only diff-stale reader calls re-enter",
already built.

**Gaps named:** G1, G2, G3, G4 (§5).

---

## 2. The fixpoint precedent — what readiness uses today, and what env-grounding costs

### 2.1 The current mechanism, exactly

`seon.render.walk/ordered-episode` (`src/seon/render/walk.clj:753-815`) is the
fixpoint. Its loop carries three accumulators:

- **`frontier`** — seeded from `:my.plan/intent-subjects` (`:782`), extended
  after each settled entry by `(print/references identity-attributes settled-node)`
  (`:810-812`). This is "the subject appeared in an earlier settled *value*".
- **`explained`** — extended only by `(reference-keys (:seon.repl/subject selected))`
  (`:813-814`). This is "an earlier entry taught this subject".
- **`episode`** — the emitted prefix.

Selection (`:785-797`): the root key first; thereafter the first remaining
candidate satisfying `introduced-subject?` (frontier), `previous-key` already
emitted, and `(every? #(explained-symbol? explained subject %) (form-symbols entry))`.

`form-symbols` (`:737-746`) is the readiness input, and it is **purely
syntactic**: `(tree-seq coll? seq form)` keeping symbols that are either
namespace-qualified or contain a `.`. Bare symbols are invisible; an alias is
kept as its literal spelling (`db/q` survives as the symbol `db/q`).
`explained-symbol?` (`:748-751`) then asks only whether that spelling — or one
of its `reference-keys` normalizations (`:715-731`, which relates the
string spelling of a program identity to the real symbol) — is in `explained`,
or equals the candidate's own subject.

**Nothing consults an environment.** There is no `resolve`, no alias table, no
namespace state anywhere in `ordered-episode` or its helpers.

### 2.2 Where the bookkeeping lives

**Not in the database.** `frontier`/`explained` are loop-locals, reconstructed
from scratch on every call. The persistent inputs are:

- `:seon.repl/candidates` and `:seon.repl/settled`, built fresh by
  `bootstrap/pull-result` (`bootstrap.clj:470-519`) from one walk acquisition
  plus the plan's ready subjects and the beyond-closure token budget
  (`:269-290`);
- the settled prefix, read back from facts by `next-entry-in`
  (`bootstrap.clj:536-606`): a `:order-by '[?ordinal :asc]` join over
  `run.form/source` × `eval/result-edn`, each result `edn/read-string`ed into a
  `:seon.sci.admit/print-node` (`:572-586`).

`next-entry-in` then re-derives the whole episode and **asserts prefix
identity**: if the stored sources differ from the regenerated ones it throws
`::prefix-drift` (`:594-605`), and a stored form outside the current pull throws
the same (`:574-583`). That check is the existing, load-bearing statement that
generation is already expected to be reproducible from facts. It is also the
strongest single piece of evidence for the verdict in §5.

Candidates are keyed by *source string* (`candidate-by-source`, `:560-570`),
retaining the first candidate for a duplicated source — a deliberate
determinism choice, commented in place.

### 2.3 Making readiness consult the LIVE env

The env introspection needed already exists on the vendored SCI, and Seon
already uses all of it:

| call | `reference-code/sci/src/sci/core.cljc` | Seon use today |
|---|---|---|
| `sci/resolve ctx sym` | `:837-838` | `sci/eval.clj:613`, `:1109`, `:1137`, `:2283`; `sci/kernel.clj:570` |
| `sci/namespace-bindings ctx ns` → `{:aliases :refers :requires :imports}` | `:712-731` | `sci/eval.clj:466-472` (`reader-context`) |
| `sci/namespace-interns ctx` → `{ns #{names}}` | `:732-750` | `sci/eval.clj:370`, `:1771`, `:2067` |
| `sci/namespace-state ctx` (the raw `(:namespaces @(:env ctx))`) | `:751-761` | `sci/eval.clj:356`, `:384`, `:1772`, `:2065` |
| `sci/all-ns`, `sci/find-ns` | `:686-696` | `sci/eval.clj:1561` |

The changes readiness needs, stated as a delta rather than a rewrite:

1. **Replace `form-symbols`' syntactic filter with reader-grounded symbol
   extraction.** The right upstream is already there: `reader-context`
   (`sci/eval.clj:457-472`) projects the ns-in-effect's aliases/refers into the
   one reader, precisely because *"executing `require` mutates SCI's namespace
   table, and the following form must be read with those exact aliases and
   refers."* The episode generator must read candidate forms the same way
   instead of `tree-seq`-ing an already-read form; bare and refer-introduced
   symbols then become visible.
2. **Replace `explained-symbol?` with resolution.** A symbol is *ready* when
   `(sci/resolve env-so-far sym)` is non-nil, where `env-so-far` is the fork
   advanced by the entries already emitted — not when a previous entry happened
   to name it as its subject. That deletes `explained` and `reference-keys`'
   string/symbol normalization dance entirely, which is the dissolution the
   design laws ask for.
3. **Carry the env as a value in the loop**, alongside `frontier`. The fork is
   the accumulator: `sci/fork` is copy-on-write and already the admissible
   candidate-context mechanism (`sci/eval.clj:2228-2233`,
   `fork-candidate-ctx`). Readiness becomes: parse-closure resolves in the fork
   built so far.
4. **Keep `frontier`.** The value side — "the subject appeared in an earlier
   settled value" via `print/references` — is orthogonal to name resolution and
   still needed; it is what makes results extend the frontier.

Cost note: `ordered-episode`'s Malli contract is
`[:=> [:cat :seon.repl/pull-result] :seon.repl/episode]` (`:771`) — a pure
function of one map. Threading a ctx keeps that shape (the ctx rides the
request, per law 2.1) but makes the function's purity conditional on the fork
being fact-derived, which §4 examines.

**Gap named:** G13.

---

## 3. Determinism and byte-stability

### 3.1 Receipts do NOT store rendered bytes — everything re-renders at read

`transcript/receipt-text` (`transcript.clj:635-664`) takes the stored
`result-edn` string, `read-result`s it back to a value (`:537-548`), and then
**re-prints it now**: `print/emit-text` when the value carries a
`:seon.print/face`, else the shared floor `value/render-ai` (`:604-618`,
`:550-558`). The receipt entity is then handed to `rendered-family`
(`:566-581`) → `render/render-call`, i.e. full renderer selection at read time.
`history-entries` (`:908-969`) composes the same path into
`:seon.render.history/bytes`.

Consequences, stated plainly: **prompt bytes are a function of (facts, loaded
render code, render profile, caps), not of facts alone.** The only stored bytes
are the whole-prompt capture: `:seon.context.capture/prompt` plus
`:seon.ai.tokens/characters` and per-segment
`:seon.context.capture/contributions` with a `contribution-hash`
(`resources/seon/schemas/seon.context.capture.edn:13-26`;
`src/seon/cluster/prompt.clj:181-210`). That capture is an audit record, not a
replay source — which is the right stance for a regeneration design, and it
gives us a ready-made byte-diff oracle.

### 3.2 Threats to a byte-identical prefix at basis B, each with a location

| # | Threat | Location |
|---|---|---|
| T1 | **Process-local prompt accumulation.** The emitted transcript order is `::ai-entries`, a vector held in the render proc's flow state, grown by `append-history` across turns; `history-text`/`history-segments` join *that vector*. Nothing in facts records the order the model actually saw. | `src/seon/render/web.clj:1286-1320`, `:1340-1350`, `:1352-1396` |
| T2 | **Retained-call reuse.** When `static-evidence` matches and `read-evidence-current?` holds, `render-call` returns `(:seon.render.call/output previous)` — bytes produced at an *earlier* basis, emitted at the current one. A fresh regeneration re-renders and can differ. | `src/seon/render.clj:646-671` |
| T3 | **Rendering transacts.** A non-reused render with a call-id, a run-id and a connection writes a `render-cost-fact`. Replay is therefore not read-only: it advances basis. The read-only page path is protected only by *incidentally* lacking a run-id/connection, not by a declared property. | `src/seon/render.clj:704-710` |
| T4 | **Membership depends on the CURRENT schema.** `root-selector` enumerates `installed-attributes`, read from `(:schema (db/schema-database database))`. Datahike's `AsOfDB` delegates `-schema` to its **origin** (`reference-code/datahike/src/datahike/db.cljc:565-610`), so as-of-B still sees today's schema. One new installed attribute changes the selector, hence the walk, hence the order. | `src/seon/render/walk.clj:68-73`, `:83-118` |
| T5 | **Budget-driven distance shrink with an accumulating calibration.** `acquire-within-budget` decrements `:seon.render/distance` until the prompt fits, and the budget verdict uses `model-calibration`, fitted from accumulated `:seon.ai.attempt` usage facts. Regenerating at B against the *current* attempt population can pick a different distance, hence different membership. | `src/seon/cluster/prompt.clj:226-277`, `:74-128` |
| T6 | **Renderers are host Vars loaded from files.** `install-first-party-namespaces!` binds first-party program namespaces to real compiled JVM Vars, loading them when the JVM has not required them yet. The generator for the render code is a file, not a fact — the PRD's own acknowledged gap, and the deepest byte-stability threat. | `src/seon/sci/eval.clj:906-932`, called at `:1459-1461` |
| T7 | **noHistory carries the payload.** `run.form/source`, `eval/result-edn`, `eval/output`, `message/content` are all `:seon.db/no-history? true` → `:db/noHistory` (`src/seon/schema/datahike.clj:260`). Datahike merges *current* datoms for such attributes into temporal reads (`reference-code/datahike/src/datahike/db/utils.cljc:229-240`), so as-of-B shows the CURRENT value. Write-once holds by construction today; nothing enforces it. | schemas as cited |
| T8 | **A hardcoded projection constant.** `recent-entry-count` = 6, the "measured stable full-detail tail", is a source constant rather than a config fact — so a regeneration under a different build silently re-cuts the tail. | `src/seon/render/transcript.clj:27-31` |
| T9 | **Unobserved-basis fallback.** `walk/history`'s `observation-basis` falls back to `(db/basis-t database)` when a call was not captured, stamping a *current* basis onto an entry whose content is older. | `src/seon/render/walk.clj:821-825`, `:874-876` |
| T10 | **A dynamic var at call time.** `render/request-profile` and `bootstrap/next-entry` both branch on `schema/handed-projection`, a dynamic var, rather than a handed value — the class/p1 fetch-at-call-time shape. | `src/seon/render.clj:68-103`, `src/seon/bootstrap.clj:613-618` |

### 3.3 What is already deterministic (the good news)

- **Attribute enumeration is sorted**: `installed-attributes` returns a
  `sorted-map-by` on `(str …)` (`walk.clj:68-73`), so no map-iteration order
  leaks into the selector.
- **Reverse connections are explicitly ordered**: newest-first by `:db/id`,
  truncated at the cap, then re-sorted ascending (`walk.clj:242-248`) — a
  deliberate, reproducible cut.
- **Acquisition is topological with alphabetical ties** and refuses on a cycle
  (`sci/eval.clj:1404-1427`); functions and tests install in `sort-by first`
  order (`:1476-1500`).
- **Def restoration is ordered** by `(juxt :seon.def/ordinal :seon.def/key)`
  (`sci/eval.clj:1551-1556`).
- **No entropy in the render path**: a grep of `seon.render`, `seon.render.walk`,
  `seon.print`, `seon.render.value`, `seon.render.block` for `gensym`, `rand`,
  `currentTimeMillis`, `nanoTime`, `Date.` finds exactly one hit —
  `System/identityHashCode` inside `DatabaseSchemaIdentity` (`walk.clj:56-62`),
  used **only** as a memoization cache key for the pull plan
  (`walk.clj:344-349`). It cannot reach output bytes.
- **The prompt already renders at a FIXED as-of value**, not at "now":
  `loop.clj:1366` takes `(run/opening-db observed-db run-id)`. `seon.db`'s
  revision-source comment (`src/seon/db.clj:305-334`) documents the incident
  that made this explicit and why an as-of value is a legitimate fixed point.

**[UNPROBED]** T2, T4 and T5 are the three whose *magnitude* is unmeasured: how
often a retained call is reused inside one episode, how often the installed
schema changes between a render and its regeneration, and how often the
calibration moves the chosen distance. Each is a one-cluster live experiment.

---

## 4. Rehydration — is the turn fork a pure function of facts?

**Structurally yes, with two named leaks.**

`sci.eval/fork-for-turn` (`src/seon/sci/eval.clj:1535-1609`) is the whole
mechanism: `(sci/fork base-ctx)`, then a Datalog query for the agent's
`:seon.def/agent` entries, `pull-many` with `{:seon.def/ns [:seon.ns/name]}`,
**sorted by `[ordinal, key]`** (`:1551-1556`). It interns in two passes —
declare every name first (`:1559-1565`), then bind roots (`:1566-1607`) — so
mutual references restore. Values come from `:seon.def/value-edn` or a blob
digest (`def-entry`, `:1518-1525`); a function restores through
`sci/install-var-roots!` on its `:sci.root/*` descriptor (`:1599-1601`); an
atom re-wraps its last settled value (`:1590-1592`); an unrestorable def
becomes a **typed value plus a loud notice** (`:1513-1516`, `:1504-1506`) rather
than silence — the honest-unknown law, correctly applied.

`:seon.def/value-edn` is **not** `no-history?`
(`resources/seon/schemas/seon.def.edn`), and `:seon.def/key` is the identity, so
upserts retain history and `as-of B` yields the def value as of B. That is what
makes "rehydrate at basis B" meaningful at all.

**Requires and aliases are facts too.** At settlement, `namespace-context-row`
(`sci/eval.clj:556-570`) emits a contracted agent-authored declaration row
whenever the ns's aliases/refers/imports/requires changed, built by
`binding-rows` (`:493-524`) directly from `sci/namespace-bindings`. Acquisition
replays them: every namespace's aliases first (`:1453-1455`), then full bindings
once the target namespaces have published (`:1467-1469`), via
`sci/install-namespace-bindings!`. `install-row!` (`:714-751`) applies the same
for a single committed ns row. So "previous-session requires persist as facts"
is **built and load-bearing**, not aspirational.

**Leak 1 — host namespaces.** `install-first-party-namespaces!`
(`:906-932`) binds first-party program namespaces to compiled JVM Vars,
loading them from the classpath. The base ctx is therefore a function of
(facts, the loaded file image). This is exactly the PRD's "the remaining gap is
first-party host namespaces, whose generator is still a file", confirmed at the
bytes.

**Leak 2 — the turn fork inherits accumulated live mutation, and never
re-derives at the run's basis.** `fork-for-turn` restores *defs* only; namespace
bindings are whatever the shared cluster base ctx has accumulated since
acquisition (via `install-row!` side effects). Within one process this matches
the facts; it does **not** reconstruct the env as of basis B, and it does not
see a binding fact written by another process. For regeneration-at-B the fork
must install the agent namespace's `:seon.ns/*` bindings from the as-of-B
value.

Nothing else process-local enters: `cluster-ctx` (`:1611-1650`) and
`fork-cluster-ctx` (`:1652-1679`) rebuild custody, projection and
supplied-default state from the receiving branch explicitly, with the docstring
stating the sibling-cluster isolation property.

**Gap named:** G14.

---

## 5. GAPS

| # | What is missing | One-line fix direction |
|---|---|---|
| **G1** | Ordering is instant-primary (`transcript.clj:464-492`) and the instant is one `now` per run fold (`loop.clj:1590`, `:1616-1622`), so `at` cannot separate receipts. | Make the sort key `(basis-t, run, ordinal)`; keep `at` as display only. |
| **G2** | Basis-t is re-derived ad hoc in three places (`run.clj:268-274`, `transcript.clj:182`/`:358-372`, `transcript.clj:901-906`). | One `seon.db` function — "the assertion transaction of this entity's attribute" — and one regression that it agrees across all three call sites. |
| **G3** | `:seon.cluster.eval/read-basis-transaction` is stamped only when read evidence is non-empty (`loop.clj:1584-1589`); a form that reads nothing records no basis. | Always stamp the evaluating database's basis on settlement; read evidence stays orthogonal. |
| **G4** | A receipt spans two transactions (start `run.clj:1053-1056`, settle `:1096-1117`); "the receipt's basis" is undefined. | Define and derive it as the transaction asserting a terminal attribute; state it in the schema docstring. |
| **G5** | Frozen runs assert N forms in ONE transaction (`run.clj:735-767`), so basis-t alone cannot order within a reply. | Lexicographic `(basis-t, ordinal)` is the contract, written down — not an implementation accident. |
| **G6** | The emitted prompt order lives in the render proc's flow state (`web.clj:1357-1393`), not in facts. | Derive the order from `(basis-t, ordinal)` and delete `append-history`; keep the capture facts as the audit trail. |
| **G7** | Bytes are re-rendered at read (`transcript.clj:604-618`, `:635-664`) with no record of which code image produced them. | Record the render generation (the `current-src` commit id) on `:seon.context.capture`, so a byte diff attributes to code vs data. |
| **G8** | Retained-call reuse can emit bytes rendered at an earlier basis (`render.clj:646-671`). | In the regeneration path, either disable reuse or prove equality; make the choice a declared property of the request. |
| **G9** | Rendering transacts a cost fact (`render.clj:704-710`); regeneration is not read-only. | Declare regeneration connectionless (the gate already exists at `:707-708`) and add a regression that a regeneration pass transacts nothing. |
| **G10** | Membership depends on the current schema; `AsOfDB` returns the origin's schema (`datahike/db.cljc:565-610`, `walk.clj:68-73`). | Derive the selector from the as-of-B schema projection, or record the schema fingerprint in the capture and treat a change as a deliberate cache break. |
| **G11** | Distance is budget-driven and the budget's calibration accumulates (`prompt.clj:226-277`, `:74-128`). | Compute the calibration from the same as-of value that supplies membership. |
| **G12** | The transcript payload sits on `:db/noHistory` attributes, so as-of-B yields the CURRENT value (`datahike/db/utils.cljc:229-240`). | A checker asserting these four attributes are never re-asserted with a different value — write-once by enforcement, not by habit. |
| **G13** | Readiness is syntactic: `form-symbols` keeps only qualified/dotted symbols and `explained-symbol?` compares subject spellings (`walk.clj:737-751`). No env, no resolution. | Read candidates through `reader-context`'s alias table and replace `explained` with `sci/resolve` against the fork built so far; `frontier` stays. |
| **G14** | `fork-for-turn` restores defs but not namespace bindings (`eval.clj:1535-1609`); the env is fact-derived only via the cluster's start-time acquisition. | Install the agent namespace's `:seon.ns/*` bindings from the run's basis inside `fork-for-turn`. |
| **G15** | `recent-entry-count` is a source constant (`transcript.clj:27-31`); `handed-projection` is a dynamic var read at call time (`render.clj:74`, `bootstrap.clj:613-618`). | Move the constant to a config fact; hand the projection as a value (class/p1). |

---

## 6. Verdict

**Basis-primary ordering plus receipted-read regeneration IS buildable on the
facts we already store.** The substrate is not merely adequate — the load-bearing
pieces exist and are already in production use:

1. Basis-t is derivable per entity from datom transactions, by an idiom the
   codebase states as a principle and uses in three places
   (`run.clj:258-279`).
2. System-authored reads are already receipted and already distinguished from
   agent forms by `:seon.cluster.run.form/author` (`run.clj:824`).
3. Replay-then-generate-the-frontier already runs: `bootstrap/next-entry-in`
   reads the settled prefix from receipts, re-derives the episode, and
   **throws on any divergence** (`bootstrap.clj:594-605`). A mechanism that
   already asserts prefix reproducibility as an invariant is the strongest
   possible evidence that reproducibility holds at that seam.
4. Diff-stale re-entry is already a fact chain with a uniqueness fence
   (`run.clj:927-1001`).
5. The turn env is already a fact-derived rebuild — defs, aliases, refers,
   requires, imports all round-trip through `:seon.def/*` and `:seon.ns/*`.
6. Turns already render at a fixed as-of value, not at "now"
   (`loop.clj:1366`).

**The work is subtraction, which is the right shape.** The single biggest
change is a deletion: the prompt's order today lives in a proc's memory (G6),
and basis-primary regeneration replaces it with a query. Two more are
deletions: `explained` and the string/symbol normalization dance dissolve into
`sci/resolve` (G13); the retained-call cache stops being correctness-bearing
(G8).

**The honest caveat, stated once:** byte-identical prefixes across regenerations
are achievable *with respect to a fixed code image and a fixed schema*, never
absolutely — because renderers are host Vars loaded from files (T6/G7) and
membership is generated from the installed schema which as-of does not rewind
(T4/G10). That is not a defect to fix before starting; it is the boundary to
**record** (the render generation and schema fingerprint on the capture), so
that a byte diff is always attributable to code, schema, or data rather than
being a mystery. Compaction is already priced as a deliberate cache break; a
code or schema change is the same kind of event and deserves the same
treatment.

**Recommended order of attack:** G1+G2+G4+G5 (the ordering key, one derivation,
one contract) → G6 (delete the proc-held order) → G13 (env-grounded readiness)
→ G9+G8 (make regeneration provably read-only and reuse-free) → G7+G10 (record
the code and schema boundary) → the rest.
