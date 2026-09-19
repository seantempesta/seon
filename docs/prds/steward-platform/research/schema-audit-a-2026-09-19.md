---
type: research
status: complete
created: 2026-09-19
tags: [schema, data-model, audit]
---

# Schema audit A — `datahike.read.edn` … `seon.config.web.edn`, 2026-09-19

Read-only audit of the first **76** resources under `resources/seon/schemas/`
(alphabetical, `datahike.read.edn` through `seon.config.web.edn`), **4 042
lines**. Every claim below names bytes I opened; every consumer claim is an
`rg` over `src/`, `test/`, `resources/` and `config/` run at this session's
HEAD. No JVM, no gate, no cluster operation, no write outside this file.

**Read end to end first:** AGENTS.md §2–§3;
[the data-modeling decision guide](../../../seon/architecture/data-modeling-guide.md)
(837 lines); `.agents/skills/data-modeling/SKILL.md`;
`.agents/skills/datahike/SKILL.md`;
[the schema design review](schema-design-review-2026-09-17.md) and
[the datahike modeling study](datahike-modeling-study-2026-09-17.md);
`src/seon/schema/datahike.clj` (the Malli→Datahike bridge) and
`src/seon/schema/edn.clj` (registry loading, `derive-config-forms` at `:66`,
`edn/read` at `:214`).

Slice inventory, parsed: **59** `:seon.db/ref` mentions, **61**
`:seon.db/attributes true` entity maps, **28** component declarations (all
**29** `:seon.db/component-schema` sites pair with one — the one apparent
mismatch, `seon.cluster.eval.edn:5`, writes `:component true` inside a
`#:seon.db{…}` namespace map and is correct), **18** `:enum` forms, **8**
`:seon.db/no-history?`, **4** `*-edn` attributes, **68** `:description`
properties and **117** EDN comment lines.

## 0. The one sentence

This slice's defects are not mostly bad types — they are **facts the registry
cannot answer**: three schema properties nobody declared, 117 lines of design
rationale in EDN comments `clojure.edn/read` throws away, a presentation table
hand-maintained on the wrong entity that is already wrong in both directions,
and a ref whose only reader pulls back the value stored beside it.

---

## 1. Ranked class table

| # | Class | Instances | Severity | One-line fix rule |
|---|---|---|---|---|
| C1 | A ref and the identity value it resolves to, both on one entity | 3 | blocker | Keep the value; delete the ref and join on it. |
| C2 | Hand-maintained mirror of declarable state | 2 | blocker | Declare the fact on the thing it describes, then read it there. |
| C3 | Rationale in EDN comments the registry discards | 20 files, 117 lines | friction | Rationale that a reader needs is a `:description`, never a `;;`. |
| C4 | Schema property used but never declared | 3 | friction | A property is an attribute: declare it in its own namespace's resource. |
| C5 | Weak type at the agent-facing boundary | 9 | friction | Alias the owner's registered key; never re-spell its base type. |
| C6 | Digest format re-spelled instead of aliased | 3 | friction | One SHA-256 format key; every use aliases it. |
| C7 | Boolean where nobody asserts the false | 2 | cleanup | `[:= true]`; absence is the false. |
| C8 | Ref whose deletion behaviour is undeclared | 14 of 14 documented refs | friction | Every ref docstring names cascade / sweep / refuse / value. |
| C9 | Dead or dangling declaration | 2 | cleanup | An attribute with no writer, or a component with no owner, is deleted. |
| C10 | Retired vocabulary live in a declared attribute | 3 families | friction | Rename at the reset; AGENTS.md §3 is the authority. |
| C11 | Two near-identical key names, different meanings, no docstring | 2 | friction | Different meaning is a different name, or at minimum two docstrings. |
| C12 | An entity schema that validates one key | 1 | friction | The component schema is the map that describes the stored row. |
| C13 | One shape declared inline more than once | 5 | cleanup | Register the shape; alias every use. |
| C14 | Instant where the recording transaction is the event | 2 | friction | `-tx` ref when the database causes the transition (J3). |
| C15 | Indexable token set stored through the EDN-string codec | 1 | friction | Value edges are members with an explicit index, not one opaque string. |

Already recorded by [the schema design review](schema-design-review-2026-09-17.md)
and **still present in the tree** at this HEAD, so not re-derived here:
N20 `:seon.cluster.eval/triage-edn` (`seon.cluster.eval.edn:1`),
N23 `:seon.ai.attempt/usage-edn` (`seon.ai.attempt.edn:9`),
N24 `:seon.cluster.eval/author` (`seon.cluster.eval.edn:8-13`),
N28 `:seon.ai.model/deepseek-pricing-schedule-status` (`seon.ai.model.edn:32-33`),
N42/J5 the `:seon.ai.model/last-*` triple (`seon.ai.model.edn:18-23`),
N43 `:seon.cluster.wake/offer-result [:enum true false nil]` (`seon.cluster.wake.edn:10`),
B.8/C.5 the twelve vendor-prefixed `seon.ai.model` attributes (`:24-39`),
B.11 the "receipt" families, B.12 `:seon.render/form`.

**One guide row is now stale.** The data-modeling guide (`:93`) lists
`:seon.agent/archived-tx` as **[TARGET]** with "no datom in
`resources/seon/schemas/seon.agent.edn` yet". It landed: `seon.agent.edn:1`
declares it with a docstring, and `:20` makes it an optional entry of
`:seon.agent/agent`. Update that row.

---

## 2. Findings per class

### C1 — a ref and the identity value it resolves to, both on one entity

**C1.1 `:seon.ai.attempt/model` — blocker.**
`resources/seon/schemas/seon.ai.attempt.edn:12`

```clojure
:model [:and {:description "The selected model descriptor row, when the
              provider target has a registered model." …} :seon.db/ref]
```

The attempt entity (`seon.ai.edn:352-406`) carries **both**
`[:seon.ai/model :seon.ai/model]` (`:361`, **required**, `[:string {:min 1}]`
at `seon.ai.edn:350`) and `[:seon.ai.attempt/model {:optional true} …]`
(`:364`). `:seon.ai.model/id` is `[:and {:seon.db/identity true}
:seon.config.ai/model]` (`seon.ai.model.edn:1-2`) over the same
`[:string {:min 1}]` domain (`seon.config.ai.edn:51-56`) — the ref's target is
identified by the string already on the row.

The tell the data-modeling skill names ("machinery whose whole job is telling
a reader how to get the name back out of a ref") is two adjacent lines of the
only reader, `src/seon/eval/drive.clj`:

```clojure
:seon.ai/model                                    ; :194
{:seon.ai.attempt/model [:seon.ai.model/id]}      ; :198
```

One pull, the string and the ref-narrowed-to-that-same-string. Writer:
`src/seon/turn.clj:3930`. Consequence today: retracting a model descriptor
row sweeps the optional ref off every historical attempt, silently, while the
string survives — which proves the string is the durable half.
**Fix:** delete `:seon.ai.attempt/model`; where the descriptor is wanted, join
`[?m :seon.ai.model/id ?model]`.
**Migration:** reset (attribute removal) plus `src/seon/turn.clj:3930`,
`src/seon/eval/drive.clj:198`, `test/seon/data_shapes_test.clj:314`, `:318`,
and two schema lines.

**C1.2 `:seon.ai.attempt/settings` / `/settings-edn` — friction.**
`seon.ai.attempt.edn:11`, `:13`. The docstring is honest ("This identifies the
settings owner; settings-edn preserves the exact effective dials"), but the
required/optional split is inverted in the entity: the opaque
`settings-edn` string is **required** (`seon.ai.edn:362-363`) while the
identifying ref is **optional** (`:365`). The row whose deletion behaviour
matters is the optional one, so a settings component's retraction sweeps the
only queryable half and leaves the blob.
**Fix:** require the ref or state in its docstring why the blob outranks it.
**Migration:** reset.

**C1.3 `:seon.ai.attempt/usage-edn` beside the four `:seon.ai.usage/*` keys**
— `seon.ai.edn:366-372`. This is N23 unchanged; recorded here only because it
sits inside the same entity map as C1.1 and C1.2, so one attempt row carries
**three** duplicated facts.

### C2 — hand-maintained mirrors

**C2.1 The `:seon.config/display` table — blocker (already drifted).**
`resources/seon/schemas/seon.config.edn:6-16` hangs a ten-row map of
`{:seon.config/display-label, -divisor, -unit}` off the **`:seon.config/settings`
schema's properties**, keyed by other attributes' names.

Measured against the tree:

- **15** millisecond dials are declared under `seon.config*`
  (`turn-completion-backstop-ms`, `ai.backup/timeout-ms`, three
  `ai.retry/*-ms`, `ai/timeout-ms`, `db/write-time-limit-ms`,
  `effect.background/time-limit-ms`, `eval/time-limit-ms`,
  `flow/ping-timeout-ms`, `operator/event-silence-backstop-ms`,
  `render/coalesce-ms`, two `shell/*-ms`, `web/timeout-ms`).
- **10** have a row. **5 have none** and render as raw milliseconds:
  `seon.config.db/write-time-limit-ms` (`seon.config.db.edn:2`, default
  600000), `seon.config.effect.background/time-limit-ms`,
  `seon.config.flow/ping-timeout-ms`,
  `seon.config.operator/event-silence-backstop-ms`,
  `seon.config.render/coalesce-ms`.
- **3 of the 10 rows can never fire.** The settings render's attribute set is
  `:seon.config/agent-overlay`'s declared references
  (`src/seon/ai.clj:322-336`, consumed at `src/seon/agent.clj:170`), i.e. the
  `:seon.config/per-agent true` dials; `seon.config.shell/time-limit-ms`,
  `seon.config.shell/termination-grace-ms` (`seon.config.shell.edn:25-28`) and
  `seon.config.web/timeout-ms` (`seon.config.web.edn:20`) declare no
  `:seon.config/per-agent`.

Wrong in both directions, which is what derive-or-die predicts. The right
pattern is **already in the same function**: `title` reads `:description` from
the attribute's own declaration (`src/seon/agent.clj:201-203`), while `label`
and `display` detour through the table (`:187`, `:190`).
**Fix:** diff in §3 R1.

**C2.2 `:seon.await/config-value` — friction, vacuous.**
`resources/seon/schemas/seon.await.edn:2-5`

```clojure
:config-value [:or :seon.config.eval/time-limit-ms
                   :seon.config.agent/turn-completion-backstop-ms]
```

A two-member roster of admissible bounds. Call sites supply at least **seven**
distinct dials: `src/seon/effect.clj:480`, `src/seon/shell/jvm.clj:119`,
`src/seon/render/web.clj:2666`, `:2692`, `src/seon/flow.clj:734`,
`src/seon/test.clj:164` and `:1474` (`:seon.test/check-time-limit-ms`).
Nothing fails, because both members are `[:int {:min 1}]` and the union
therefore admits every positive int — the declaration constrains nothing and
misinforms every reader of `doc`.
**Fix:** `:config-value [:int {:min 1 :description "The declared bound's
value, in the unit its :seon.await/config-attribute declares."}]`.
**Migration:** none stored; one schema line, no consumer change.

### C3 — rationale in EDN comments the registry discards

`src/seon/schema/edn.clj:214` reads every resource with `clojure.edn/read`,
which discards `;` comments; nothing retains them. So a `;;` block is
invisible to `packaged-forms`, to `doc`/`dir` (`src/seon/sci/eval.clj:1194`,
`:1222`), and to the settings render's `title` (`src/seon/agent.clj:201`).

**117 comment lines** in the slice. **20 of 76 files carry comments and
declare zero `:description` properties.** The two that matter:

- `seon.call-preparation.edn` — **65** comment lines, **0** descriptions. The
  entire supplied-default design (`:1-16`, `:18-21`, `:24-34`, `:63-69`,
  `:72-77`, `:107-108`, `:138-139`, `:146-164`, `:186-188`, `:211-214`) is
  invisible to an agent that asks the registry about `:seon.call-preparation/key`.
- `seon.activation.edn:24-33` — the ten-line statement of the G4 reasoning
  ("a cardinality-many attribute with no members has NO datoms …"), which is
  the best short account of that ruling anywhere in the resources, discarded.

**Fix rule:** rationale a reader needs at the attribute is a `:description` on
that attribute; a `;;` comment is for the file's maintainer only.
**Migration:** accretion, no reset.

### C4 — schema properties used but never declared

| Property | Uses | Readers | Declared? |
|---|---|---|---|
| `:seon.config/default` | `seon.config.db.edn:1`, `:2`, `seon.test.edn:177` | `src/seon/schema/edn.clj:116-117`, `src/seon/config.clj:195-196`, `src/seon/cluster.clj:1229`, `src/seon/schema.clj:345`, `src/seon/db.clj:4083`, `src/seon/cluster/source.clj:451` | **no** — `seon.config.edn` declares `:dial`, `:optional`, `:per-agent`, `:display*`, not `:default` |
| `:seon.shell/environment` | `seon.config.shell.edn:6`, `:14`, `:20` | `src/seon/shell/jvm.clj:89` | **no** — there is no `seon.shell.edn` resource |
| `:seon.render/derived` | `seon.cluster.status.edn:5`, `seon.error.edn:221` | `src/seon/render/walk.clj:679` | **no** — absent from `seon.render.edn` |

Nothing breaks: `storable-properties-in` (`src/seon/schema/datahike.clj:311`)
filters by `storable-attribute-in?`, so an unregistered property is simply not
stored and not queryable. That is the defect — "which dials declare a
default?" is answerable only by grepping EDN, which AGENTS §2.2 rules a defect
report about the data model.
**Fix:** declare each with a type and a docstring in its own namespace's
resource (`:seon.config/default :seon.schema/value`, `:seon.shell/environment
[:string {:min 1}]` in a new `seon.shell.edn`, `:seon.render/derived :boolean`).
**Migration:** accretion; `:seon.config/default` becomes storable, which is
additive.

### C5 — weak types at the agent-facing boundary

The `my.*` surfaces are the schemas an agent actually reads. Nine sites
re-spell a base type where the owner has a constrained key:

| Site | Declared | Owner's key | Evidence |
|---|---|---|---|
| `my.agent.edn:1` | `:my.agent/id :string` | `:seon.agent/id [:string {:min 1 …}]` | `src/seon/agent.clj:27` copies it |
| `my.agent.edn:3` | `:my.agent/steward :string` | `:seon.agent/id` | `src/seon/agent.clj:31` reads `[… :seon.ns/steward :seon.agent/id]` |
| `my.agent.edn:2` | `:my.agent/namespace :symbol` | `:seon.ns/name` | `src/seon/agent.clj:29` reads `:seon.ns/name` |
| `my.message.edn:50` | `:to [:string {:min 1}]` | `:seon.agent/id` | `:from` at `:5` already aliases it, 45 lines up |
| `my.web.edn:173` | `:url [:string {:min 1}]` | — | a URL that admits `"x"`; the writer validates instead (`:my.web/invalid-url-error`, `:37`) |
| `my.web.edn:18` | `:extraction-error :string` | `:seon.error/value` | the same file's `:my.web/error` (`:4-16`) is a union of eleven typed error classes |
| `seon.cluster.eval.edn:3` | `:error [:and … :string]` | — | an evaluation's error as prose, beside `:seon.error/kind` at `:77` |
| `seon.cluster.wake.edn:1` | `:attributes [:set :keyword]` | `:qualified-keyword` | every database attribute is qualified (AGENTS §3) |
| `seon.cluster.wake.edn:2`, `:8` | `:channels fn?`, `:fenced? fn?` | — | a bare predicate with no `:error/message` and no generator; the correct sibling form is `seon.agent.edn:96-100` |

Two more of the same shape outside `my.*`:
`seon.ai.model.edn:28-29`, `:deepseek-utc-start`/`-end [:string {:min 1}]`,
where `config/default.edn:599-612` supplies `"00:00"`, `"04:00"` and
**`"24:00"`** — not a clock time, and nothing refuses it (no `src/` reader
parses these at all; only `:deepseek-off-peak-windows` is read, at
`src/seon/ai.clj:127`, `:139-140`). And `seon.ai.model.edn:15`
`:input-modalities [:set :keyword]`, unbounded where the set is closed.

**Fix rule:** the surface aliases the owner's key. **Migration:** for the
`my.*` rows, none — these are read projections, not stored attributes, so it
is a schema-only edit that narrows an output (accretion for readers).

### C6 — digest formats re-spelled

Extends N35 with three new instances inside this slice:

| Site | Form | Fix |
|---|---|---|
| `seon.blob.edn:2` | `:digest [:re "^[0-9a-f]{64}$"]` | alias the one digest format |
| `seon.config.edn:29` | `:applied-manifest-digest [:re "^[0-9a-f]{64}$"]` | same |
| `seon.cluster.source.edn:29` | `:error-source [:string {:min 64, :max 64}]` | same — and it does **not** constrain hex, so any 64 characters store |

`:seon.cluster.source/error-source` also carries a false name: it reads as
"the source of the error" and holds a source digest.
**Migration:** blocked behind N36 (`:seon.source/digest` declares
`:seon.db/identity true` on the *format*, which every reuse must switch off);
do them in one publication.

### C7 — booleans where nobody asserts the false

- `seon.config.agent.edn:1-7`, `:show-all-settings :boolean`, docstring
  "absent **or false** shows model, no-provider, …" — absent and false are
  declared to mean the same thing.
- `seon.config.ai.edn:102-110`, `:retain-reasoning :boolean`, "when explicitly
  enabled".

The exemplar is 45 lines away in the same file: `seon.config.ai.edn:57-63`,
`:no-auth [:= {…} true]`.
**Fix:** `[:= true]` for both. **Migration:** reset for stored overlay rows
holding an explicit `false`.

### C8 — refs whose deletion behaviour is undeclared

Of the slice's ref declarations, **14 carry a `:description` and none of them
states cascade / sweep / refuse / value**, which the reset batch
(`reset-batch-2026-09-17.md:271`) and the datahike skill both require. The
ones whose consequence is behavioural:

- **`:my.plan.item/needs`** (`my.plan.item.edn:20-24`). The docstring settles
  ref-versus-component ("Dependency is not ownership, so this stays an
  ordinary ref, never a component") and is silent on the sweep. It is optional,
  so retracting a needed step **silently removes the blocker**, and
  `:my.plan/state` derives `:blocked` from exactly this set
  (`src/seon/plan.clj:249`, `:266`, `:306`, `:310`). A blocked step becomes
  ready with no signal — absence read as health, inside the agent's own plan.
- **`:my.plan.item/subject`** (`:48-50`) — swept, and `done-query` then runs
  without its declared `?subject` binding (`:45-47`).
- **`:seon.cluster/config`** (`seon.cluster.edn:10`), required at `:7` →
  refuse; **`/instructions`** (`:12`) and **`/toolkit`** (`:14`) optional sets
  → sweep. No docstring on any of the three.
- **`:seon.agent/namespace`** (`seon.agent.edn:84-88`) — docstring covers
  non-uniqueness, silent on the sweep.
- **`:seon.ai.model/provider`** (`seon.ai.model.edn:4`), required at `:73` →
  refuse. No docstring at all; nothing in that file has one.

**Fix:** one sentence per ref naming its chosen behaviour.
**Migration:** accretion.

### C9 — dead and dangling declarations

- **`:my.plan.item/agent`** (`my.plan.item.edn:15-19`, entity entry at `:75`).
  Its own docstring: *"A retained agent ref from the pre-component plan model
  … my.plan never writes this attribute."* Confirmed: **zero writers in
  `src/`**; the only uses are three test fixtures
  (`test/seon/transact_feedback_test.clj:140`, `:152`,
  `test/seon/render_simplification_test.clj:145`, `:168`) that need any ref
  attribute. It still renders as a legitimate key of the plan-item entity,
  inviting a writer to resurrect a superseded ownership model.
  **Fix:** delete the attribute and its entity entry. **Migration:** reset plus
  two test files.
- **`:my.web/error-request`** (`my.web.edn:176-180`) — a declared component
  with a `:seon.db/component-schema` and **zero references anywhere** in
  `src/`, `test/` or `resources/`. `my.web` is the one family the
  2026-09-18 "Additive error declaration manifest" left without a
  `:my.web/error` entity: `:my.web/error` (`:4-16`) is still the legacy `:or`
  union of eleven error classes, so nothing holds the component.
  **Fix:** either finish the manifest for `my.web` or delete the orphan.
  **Migration:** reset.

### C10 — retired vocabulary in live attributes

- **`:seon.cluster/toolkit`, `/toolkit-namespaces`** (`seon.cluster.edn:14-15`).
  AGENTS.md §3's vocabulary table lists "toolkit" as a legacy spelling of
  *every function is callable*. It is a stored attribute of the cluster entity
  (`:9`), rendered by `:seon.render/ai seon.cluster/render-ai` (`:4`), and its
  count is printed to the page at `src/seon/cluster.clj:234`. Consumers:
  `src/seon/cluster.clj:194`, `:214`, `:234`, `:2755`, `:2767`, `:2778`,
  `:2783`, `:2796`, `src/seon/cluster/instruction.clj:41`, plus
  `test/seon/cluster/instruction_test.clj:73`, `:130`, `:135`.
- **"receipt"** — `:seon.cluster.eval/receipt` (`seon.cluster.eval.edn:27`)
  and `:my.background/receipt` (`my.background.edn:1`). B.11 unchanged;
  recorded because both reach agent context.
- **`:seon.render/form`** — in this slice at `my.turn.edn:14`, `:21`;
  `my.note.edn:4`, `:13`, `:22`; `seon.agent.edn:127`;
  `my.plan.edn` uses the current pair only. B.12's unresolved question (is the
  vocabulary table stale, or are the schemas?) is still unresolved.

### C11 — near-identical names, different meanings, no docstrings

- **`:seon.ai.tokens/estimate` (`seon.ai.tokens.edn:20`) vs `/estimated`
  (`:21`).** Different facts: `/estimate` is the token figure for the
  **omitted remainder** of an elision (`src/seon/print.cljc:463`, written at
  `:486`, consumed in `seon.render.history.edn:14`); `/estimated` is the
  **prompt budget report total** (`src/seon/ai/tokens.cljc:201`, declared in
  `seon.ai.tokens.edn:46`, mirrored at `seon.render.cost.edn:3`). Both render
  to agents; **neither declares a `:description`**, and they differ by two
  characters.
- **`:seon.cluster.eval/read-basis-transaction` (`seon.cluster.eval.edn:2`)**
  is `:seon.db/basis-t`, i.e. an `:int` — not a transaction ref, despite the
  name. A reader who trusts the name expects `:db/txInstant` to be reachable.
  It is also `{:optional true}` (`:86-88`), which is N14's absence-as-state
  defect in the since-diff.
  **Fix:** rename to `/read-basis-t`, or make it the ref the name claims.

### C12 — an entity schema that validates one key

`:seon.config/settings` (`seon.config.edn:3-17`) declares exactly one entry,
`[:seon.config/agent :seon.config/agent]`, plus the C2.1 display table as a
property. It is the declared `:seon.db/component-schema` of
`:seon.agent/settings` (`seon.agent.edn:159`), so the final owning-value check
(`src/seon/db.clj:3071`) validates an agent's settings component against a map
that asserts only the agent ref.

The map that actually describes the stored row is the **derived**
`:seon.config/agent-overlay` (`src/seon/schema/edn.clj:88-93`), which carries
`:seon.db/attributes true`, every `:seon.config/per-agent true` dial, and —
the tell — **the same render pair**, `seon.agent/render-settings-ai` /
`-html`. That pair is now declared three times: `seon.agent.edn:160-161`,
`seon.config.edn:4-5`, and the derived overlay.

`:seon.config/settings`'s only non-`component-schema` reader is
`src/seon/agent.clj:178`, fetching the display table. Remove the table and the
schema has no reason to exist.

### C13 — one shape declared inline more than once

- `seon.boot.edn:5` and `:112` both inline `[:int {:min 1, :max 65535}]` while
  the registered `:seon.boot/prepl-port` (`:107`) is `[:int {:min 0, :max
  65535}]`. Three spellings, and nothing says why an advertised port may not
  be 0 while a configured one may.
- `seon.boot.edn:87` and `:124` both inline `[:int {:min 0}]` for
  `:seon.boot/ready-ms`, which is not registered at all.
- `seon.boot.edn:125-127` re-types `:seon.instrument/instrumented` inline.
- `seon.config.ai.backup.edn:1-18` copies `[:string {:min 1, …}]` for
  `api-key-variable`, `endpoint` and `model` instead of aliasing
  `:seon.config.ai/*` — while its sibling `seon.config.ai.retry.edn:1-24`
  aliases `:seon.ai.retry/*` correctly for all six dials. One family does it
  right, its neighbour does not.

### C14 — instants where the recording transaction is the event

- `:seon.cluster.eval/at` (`seon.cluster.eval.edn:199`), **required** at `:34`.
  An evaluation is recorded in the transaction that creates it, so `:db/txInstant`
  already holds this, and a `-tx` ref would also yield the basis `:t` (N38's
  reasoning, J3's A-arm). Two clocks on one row.
- `:seon.ai.attempt/at` (`seon.ai.attempt.edn:8`) is correctly an instant under
  J3's B-arm (the provider attempt predates its recording) — but no docstring
  says so, which is exactly what J3 requires ("the split is the rule, and it
  must be written in the docstrings").
- `:seon.ai.model/last-used-at` (`seon.ai.model.edn:22-23`) still lacks the
  D.4 docstring J5 made a condition of keeping it.

### C15 — an indexable token set stored as one opaque string

`my.plan.item.edn:25-34`:

```clojure
:about-token [:or :symbol :qualified-keyword],
;; The subset branch keeps the outer `:or` structurally present
;; so the Datahike bridge uses its scalar EDN codec and preserves
;; authored vector order.
:about [:or {:description "Ordered function, namespace, and schema tokens
             this step's work concerns."}
        [:vector :my.plan.item/about-token] [:= []]]
```

`:my.plan.item/about-token` is precisely the name-observation value shape G2
rules for — and the outer `:or` is kept **on purpose** so the bridge's mixed-union
fallback (`src/seon/schema/datahike.clj:157-168`) stores the whole vector as one
`:db.type/string`. The consequence: "which plan steps concern
`seon.turn/open-tx`" is not a Datalog clause over an indexed value edge; it is
string matching, which AGENTS §2.2 lists among the three banned substitutes.
This is the one place in the slice where the ruled shape exists and is then
deliberately made unqueryable, to buy ordering.
**Fix:** two indexed value edges plus an ordinal if order is load-bearing —
`:about-symbols [:set {:seon.db/index true} :qualified-symbol]` and
`:about-keys [:set {:seon.db/index true} :qualified-keyword]` — or an owned
child carrying `(token, position)`.
**Migration:** reset; consumers `src/seon/plan.clj:78`, `:455`,
`src/my/plan.clj:107`, `src/seon/issue.clj:924`.

---

## 2b. What this slice gets right (copy these)

- **`seon.activation.edn:24-33`** — the clearest live statement of G4 in the
  resources, paired with the right construction: `:seon.activation/source-digest`
  **required** (`:36-37`) as the positive provenance fact, every member
  collection optional. Its one weakness is that the reasoning is a comment
  (C3) and that the emptiness refusal it describes happens at submission
  (`seon.cluster.source/activation-seal-tx`), which is a second, weaker copy of
  a decision the required digest already makes.
- **`src/seon/turn.clj:3917-3921`** with `:seon.ai.attempt/error`
  (`seon.ai.attempt.edn:15`) — *"THE REF'S PRESENCE IS THE OUTCOME … there is
  no stored `:success`/`:error` label restating that."* The anti-kind-stamp
  ruling, implemented and commented.
- **`my.fs/content` (`my.fs.edn:13-27`), `my.shell/stdin` (`my.shell.edn:17-31`),
  `my.shell.output/value` (`my.shell.output.edn:8-51`)** — exactly-one-arm
  `[:fn {:error/message … :gen/schema …}]` predicates that state the invariant
  and keep the generator honest.
- **`seon.config.ai/no-auth [:= true]`** (`seon.config.ai.edn:57-63`) — a flag
  nobody asserts false.
- **`seon.cluster.edn:36-37`** `:graph-operation [:qualified-symbol
  {:seon.db/index true}]` — a symbol stored as a symbol, with the explicit
  index O9 requires.
- **`seon.await/bound`** (`seon.await.edn:6-9`) — a bound that carries the
  config attribute that set it, which is AGENTS §2.3's "the bound is part of
  the seam's contract" as data.

---

## 3. The three highest-leverage refactors in this slice

### R1 — dissolve the display table and `:seon.config/settings`

Deletes a mechanism, fixes a mirror already wrong in both directions (C2.1),
removes an entity schema that validates one key (C12), and collapses a render
pair declared three times.

`resources/seon/schemas/seon.config.edn` — delete `:seon.config/settings`
(`:3-17`) and `:seon.config/display` (`:21-25`) entirely. Keep
`:display-label`, `:display-divisor`, `:display-unit` (`:18-20`); they become
per-attribute properties:

```clojure
;; seon.config.eval.edn
 :time-limit-ms [:int {:min 1
                       :seon.config/per-agent true
                       :seon.config/dial true
                       :seon.config/display-label "Evaluation deadline"
                       :seon.config/display-divisor 1000
                       :seon.config/display-unit "s"
                       :description "…"}]
```

repeated on the other nine dials the table names, **and on the five it
misses**. `resources/seon/schemas/seon.agent.edn:159` retargets the component
schema at the map that describes the row:

```clojure
 :settings [:and {:seon.db/component true
                  :seon.db/component-schema :seon.config/agent-overlay
                  :seon.render/ai seon.agent/render-settings-ai
                  :seon.render/html seon.agent/render-settings-html} :seon.db/ref]
```

`src/seon/agent.clj` drops `display-metadata` (`:176-178`) and reads each
attribute's own properties exactly as `title` already does at `:201-203`.

**Cost:** 6 schema resources, `src/seon/agent.clj` (~10 lines),
`test/seon/html_views_test.clj:198`. A schema-key retraction (publication), not
a data reset.

### R2 — delete `:seon.ai.attempt/model`

The clearest C1 instance, with the reader's own selector as the proof.

```clojure
;; resources/seon/schemas/seon.ai.attempt.edn — delete line 12
-:model [:and {:description "The selected model descriptor row, …"} :seon.db/ref],

;; resources/seon/schemas/seon.ai.edn — delete line 364
-[:seon.ai.attempt/model {:optional true} :seon.ai.attempt/model]
```

`src/seon/turn.clj:3930` drops `model-ref`; `src/seon/eval/drive.clj:198`
drops `{:seon.ai.attempt/model [:seon.ai.model/id]}` and uses the
`:seon.ai/model` string it already pulls at `:194`;
`test/seon/data_shapes_test.clj:314`, `:318` assert the string.
While there, give `:seon.ai/model` on the attempt the docstring that says it is
the model identity a descriptor row is joined by
(`[?m :seon.ai.model/id ?model]`).

**Cost:** reset (attribute removal), 2 src files, 1 test file, 2 schema lines.

### R3 — alias the agent-surface identity formats

Four lines, no reset, and it closes the weakest types any agent ever reads (C5).

```clojure
;; resources/seon/schemas/my.agent.edn
-{:my.agent/id :string
- :my.agent/namespace :symbol
- :my.agent/steward :string
+{:my.agent/id [:and {:description "The agent this identity names."} :seon.agent/id]
+ :my.agent/namespace [:and {:description "Its assigned namespace's name."} :seon.ns/name]
+ :my.agent/steward [:and {:description "The agent that stewards that namespace."} :seon.agent/id]

;; resources/seon/schemas/my.message.edn:50
- :my.message/to [:string {:min 1}],
+ :my.message/to :seon.agent/id,
```

Each is exactly the value `src/seon/agent.clj:27-31` already copies out of the
owner's rows. Same edit shape applies to
`:seon.agent/error-agent-id` (`seon.agent.edn:172-173`),
`:seon.cluster/error-cluster-name` (`seon.cluster.edn:27-28`),
`:seon.cluster.prompt/error-agent-id` (`seon.cluster.prompt.edn:58-59`) and
`:seon.cluster.wake/error-recipient` (`seon.cluster.wake.edn:51-52`), which all
re-spell `[:string {:min 1}]` for an agent id **correctly stored as a value so
the fault outlives the agent** — the right modeling choice with the wrong
format.

**Cost:** schema only; `:seon.ns/name` must be confirmed as the namespace-name
key before R3 lands (see §4).

---

## 4. What I could not verify without a JVM

1. **Which of these attributes have datoms on `default` today.** Every
   required-versus-optional recommendation in §3.3 of the guide depends on a
   live population count (`:seon.cluster.eval/source` optional at
   `seon.cluster.eval.edn:35-37` is the one I most want counted: an evaluation
   with no source is unreadable, and I cannot tell whether that state exists or
   whether one writer is simply incomplete).
2. **`storable-attribute-in?` for every attribute I called stored.** I reasoned
   from the bridge's source (`src/seon/schema/datahike.clj:123-185`) and from
   membership in a `:seon.db/attributes true` map, not from an installed schema
   read. Specifically unconfirmed: whether `:my.fs/bytes [:vector :my.fs/byte]`
   (`my.fs.edn:8`) ever reaches a stored entity — if it did, the bridge would
   make it cardinality-many `:db.type/long`, i.e. an unordered **set of byte
   values**, which would be silent corruption. I found no `:seon.db/attributes`
   map containing it, so I state the risk, not a defect.
3. **`:seon.ns/name`'s declared type**, which R3 aliases. `seon.ns.edn` is
   outside this slice.
4. **Whether the five ms dials missing display rows actually reach a settings
   render.** I proved the attribute set is `:seon.config/agent-overlay`'s
   references from source (`src/seon/ai.clj:327-332`); I did not read the
   installed `:seon.schema/references` datoms.
5. **Any claim about writer behaviour.** Every "swept silently" statement is
   read from `reference-code/datahike/src/datahike/db/transaction.cljc:998-1015`
   plus the declaration, never measured.

## Verification boundary

Source read in the `steward-platform` working tree with other lanes'
uncommitted edits present (`git status` at entry lists `bin/test`,
`resources/seon/schemas/seon.db.edn`, `src/seon/db.clj`, `src/seon/schema.clj`,
`src/seon/test.clj`, `src/seon/test/runner.clj` and four test files). Line
numbers are the bytes I opened; a lane landing before the reset must re-anchor
them. Counts come from `grep -o` over the 76-file slice, not from an EDN parse,
except where a per-file list is printed above. No JVM was started, no gate was
run, no cluster was operated, and no MCP evaluation was spent.
