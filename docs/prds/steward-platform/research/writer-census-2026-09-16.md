---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, schema, writers, data-model]
---

# Writer census — every seam that stores facts, derived from the program graph

R1, measured on cluster `default` (pid 7595, basis `536871970`) through
read-only MCP jvm-mode probes with explicit custody. Probe:
[writer-census-probe-2026-09-16.clj](writer-census-probe-2026-09-16.clj).
Read end to end first: AGENTS.md §2.2/§2.4/§3,
[namespace-data-model-2026-09-16.md](../plan/namespace-data-model-2026-09-16.md)
(§0, §3, §7, §8, §9), skills `data-modeling`, `datahike`, `repl`.

## 1. The query and its answer

The census is a Datalog query, not a list. The two `function-reaches` clauses
of `src/seon/fn.clj:863` are reused verbatim; the writer set is every function
reaching `seon.db/transact!`, joined to the installed attributes its indexed
source names literally (`:seon.fn/keywords` ∩ `(keys (:schema db))`).

| Measure | Number |
|---|---|
| Functions reaching `seon.db/transact!` (direct or transitive) | **311** (3 066 ms) |
| …in production namespaces (not `*-test`, not `seon.test-support`) | **138** |
| …calling `transact!` directly, in production namespaces | **55** |
| Distinct installed attributes those 138 name | **178** |
| Stored form of the 178: string / ref / long / instant / keyword / symbol / boolean / uuid | 68 / 46 / 31 / 12 / 10 / 6 / 4 / 1 |
| Production writers naming no installed attribute (they pass data through) | 31 |

The ten largest direct writers by attribute families touched:

| Writer (file:line) | Attribute families |
|---|---|
| `seon.turn/record-attempt!` `src/seon/turn.clj:3781` | `seon.ai.attempt`, `seon.ai.model`, `seon.ai`, `seon.agent`, `seon.error` |
| `seon.turn/call-turn` `src/seon/turn.clj:4723` | `seon.turn`, `seon.cluster.eval`, `seon.ai.attempt`, `seon.config.*` |
| `seon.effect/request*` `src/seon/effect.clj:551` | `seon.effect`, `seon.fn`, `seon.turn`, `seon.cluster.eval` |
| `seon.turn/system-turn` `src/seon/turn.clj:2081` | `seon.eval`, `seon.cluster.eval`, `seon.turn`, `seon.ns` |
| `seon.cluster/commit-fault!` `src/seon/cluster.clj:2471` | `seon.error`, `seon.turn`, `seon.agent`, `seon.config.error` |
| `seon.fn/backfill-contract-facts!` `src/seon/fn.clj:1643` | `seon.fn`, `seon.fn.arity`, `seon.ns`, `seon.ns.alias` |
| `seon.cluster/development-source-refresh!` `src/seon/cluster.clj:2042` | `seon.fn`, `seon.ns`, `seon.test`, `seon.source`, `seon.cluster` |
| `seon.fn/index!` `src/seon/fn.clj:2046` | `seon.fn`, `seon.ns`, `seon.test`, `seon.schema.admission` |
| `seon.test.runner/commit-results!` `src/seon/test/runner.clj:1655` | `seon.test`, `seon.test.run`, `seon.error` |
| `seon.issue/add!` `src/seon/issue.clj:446` | `seon.issue`, `seon.error` |

**Honest limit of this derivation.** `:seon.fn/keywords` records keywords the
source names LITERALLY, so the census over-reports (a reader that only mentions
an attribute counts) and under-reports (an attribute assembled from schema data
is invisible — `:seon.render/ai` has 388 stored datoms but only a fixture names
it literally). Nothing in the database records *which function asserted which
attribute*: transaction provenance is `:seon.db/user`/`:seon.db/process` only
(AGENTS.md §3). That missing fact is the reason this census cannot be exact, and
it is the cheapest future accretion (§5, item 6).

## 2. Classification of what the writers store

Judged by the installed `:db/valueType` plus one live sample value, never by the
name. Legitimate prose (source, shown text, messages, replies, note bodies,
contract `pr-str`) is omitted; the full 178-row inventory is in
`tmp/orchestrator/wave3/research/written-attributes-raw.edn`.

| # | Attribute (holders) | Writer (file:line) | Current form / live sample | Verdict | Fix at the writer | Datoms per event | Price |
|---|---|---|---|---|---|---|---|
| 1 | `:seon.fn/sym` (4 764), `:seon.test/sym` (1 779) | `src/seon/fn.clj:385`, `:409`; `src/seon/test/runner.clj:159`, `:575` | `db.type/string` — `"seon.fs/progress-reporter"`, while `:seon.ns/name` (437) is `db.type/symbol` `seon.dev.state` | (b) program identity as text; the one identity the whole graph joins on is a string in a system that already stores symbols | publish the identity as `db.type/symbol`; lookup refs `[:seon.fn/sym 'ns/f]`; the string form dies with the same commit | 0 extra (type change; 6 543 datoms re-asserted once per publication) | 6–10 h (every `(str qualified)` writer plus every `[:seon.fn/sym (str …)]` lookup) |
| 2 | `:seon.render/ai` (388), `:seon.render/html` (383) | schema row publication, `src/seon/cluster.clj:970` ← declarations `src/seon/schema.clj:1398` | `db.type/string` — `"seon.agent/render-settings-ai"` on schema row e729 | (b) a first-party function named by text; each named function HAS a `:seon.fn` entity | add `:seon.render/ai-fn` / `html-fn` refs beside them, exactly as `:seon.eval/renderer-fn` already does (`src/seon/turn.clj:84`) | +771 refs per publication | 2 h |
| 3 | `:seon.ns.alias/target-ns` (2 829) | `src/seon/fn.clj:247` | `db.type/symbol` — `seon.config` | (b) names a namespace entity that exists (437 of them) | `:seon.ns.alias/target` ref; keep the symbol as the authored text | +2 829 refs per publication | 2 h |
| 4 | `:seon.fn/arglists` (3 984) | `src/seon/fn.clj:1643` | `db.type/string` — `"([node])"` | (a) structured data as text; `:seon.fn.arity/arguments` (2 666 refs) already carries it as entities | retire the attribute once the arity entities cover every row | −3 984 datoms per publication | 3 h (readers first) |
| 5 | `:seon.error/process` (4) | `src/seon/error.clj:564`, `src/seon/cluster.clj:2471` | `db.type/string`, three incompatible conventions in one attribute: `"7595-1789522562086"`, `"seon.db.process/boot"`, `"error-graph-live"` | (b)+(d) mixed; a process identity, a sentinel and a probe label share one string slot | ref to the `:seon.db.process/id` entity (§9 of the data model); a sentinel becomes its own absent-key case | +1/−1 per occurrence | 2 h |
| 6 | `:seon.error/throwable-class` (3) | `src/seon/error.clj:569` (string) vs `:285` (symbol) | `db.type/string` — `"clojure.lang.ExceptionInfo"` | (b) a JVM class as text; **two writers disagree** and the installed type forces the string one | one writer, `db.type/symbol` | 0 (type change) | 1 h |
| 7 | `:seon.effect/capability` (10) | `src/seon/effect.clj:579` | `db.type/symbol` — `seon.fs.jvm/stat`; all ten name indexed functions | (b) | `:seon.effect/capability-fn` ref beside the symbol | +1 per effect request | 1 h |
| 8 | `:seon.sci.eval/ending-ns` (110) | `src/seon/sci/eval.clj:2029`, `:2073` | `db.type/symbol` — `my.agents.root` | (b) names a namespace entity | ref beside it; "which namespace did this evaluation end in" becomes a join | +1 per evaluation | 1 h |
| 9 | `:seon.ai.attempt/usage-edn` (24) | `src/seon/turn.clj:3781` | `db.type/string` — `"{\"prompt_tokens\" 13121, …}"`, provider JSON keys | (a) **and now redundant**: `:seon.ai.usage/{prompt,completion,total,cached}-tokens` are written with 24 holders each | stop writing the text; if the raw reply must survive, it is already in the attempt's reply evidence | −1 per attempt | 1 h |
| 10 | `:seon.ai.attempt/settings-edn` (24) | `src/seon/turn.clj:3781` | `db.type/string` — the whole effective config map as EDN | (a) | ref to the settings entity in force plus `:seon.ai.attempt/model` (already a ref, 24 holders) | −1, +1 per attempt | 3 h |
| 11 | `:seon.cluster.eval/triage-edn` (16) | settlement, read at `src/seon/repl.clj:132` | `db.type/string` — a small map of declared keys | (a) | store the declared triage keys as attributes on the evaluation | +3–5, −1 per failed evaluation | 3 h |
| 12 | `:seon.test/failing-assertions` (262), `:seon.test/failure-message` (95) | `src/seon/test/runner.clj:503`, `:505` | `db.type/string` — joined prose, identity strings | (a) | one `seon.test.failure` component per failing `is` (expected/actual as exact blobs, file, line); the identity stays | +5 per failing assertion | 5 h |
| 13 | `:seon.test/adoption-inputs` (23) | `src/seon/test.clj:239` | `db.type/string` — `"/Users/sean/src/seon/test/seon/issue_test.clj"` | (b) a file path that `:seon.fn.file/path` (332) already identifies | ref to the file entity | +1 per adoption check | 1 h |
| 14 | `:seon.test/subject` (**0 holders**) | `src/seon/fn.clj:320`, `:343` | declared ref, never written | (c)-adjacent: the link exists in the schema and no writer fills it | derive at index time from the test's direct calls into the namespace under test | +1 753 refs once | 5 h |
| 15 | `:seon.error/id` (4) | `src/seon/error.clj:279` | `db.type/string`, and **equal to `:seon.error/signature`** on every holder | (c) refuted: identity is already derived, not random | none | — | — |

Legitimate and confirmed as prose or exact evidence: `:seon.fn/source` (3 984),
`:seon.ns/source` (332), `:seon.test/source` (1 687), `:seon.fn/spec` (1 080),
`:seon.eval/shown` (110), `:seon.cluster.eval/source` (112), `:seon.turn/reply`
(38), `:seon.message/content` (9), `:seon.issue/path` (1 629),
`:seon.fn.file/path` (332), `:seon.schema.admission/source` (10 429),
`:seon.schema/key` (2 666), `:seon.test.run/program-digest` (370).

## 3. Reconciliation against the hand list in data-model §7.3

The hand list is stale in both directions, which is the derive-or-die law
demonstrating itself on a nine-hour-old list.

**Rows it got wrong (already landed since it was written).** #1 `:seon.error/fn`
is an installed ref (3 holders); the occurrence family of §9 is installed
(`:seon.error.occurrence/id`, `count`, 4 holders each); #3 `:seon.error/op` and
`proc` have **0 holders** (nothing to fix); #7 the four `:seon.ai.usage/*`
attributes are written (24 each) so `usage-edn` is now duplicate, not merely
unstructured; #10 `:seon.effect/request-edn` and `result-edn` have **0 holders**;
#11 `:seon.eval/renderer-fn` is a ref with 7 of 7 coverage (`src/seon/turn.clj:84`);
#13 `:seon.maintenance.request/handler` and `receipt/handler` are refs (119 each);
#27 issues ARE entities now (1 630 ids, 1 423 `:seon.issue/functions` refs, 225
`:seon.issue/tests` refs, 2 `:seon.issue/agent`); #28 lint findings are entities
(`:seon.lint/id`, 1 014). §8.1's "fault occurrence identity random today" is
refuted: `:seon.error/id` equals its signature on every holder.

**Rows it missed (found by the query).** The largest one first:
`:seon.fn/sym` and `:seon.test/sym` — 6 543 program identities stored as strings
while `:seon.ns/name` is a symbol (row 1 above); `:seon.render/ai` /
`:seon.render/html` as function names in text on 388/383 schema rows (row 2);
`:seon.ns.alias/target-ns` (row 3); `:seon.sci.eval/ending-ns` (row 8);
`:seon.test/adoption-inputs` (row 13); and the three-conventions-in-one-string
state of `:seon.error/process` (row 5). Also unmentioned: `:seon.ns/steward` now
has 6 holders, not 2, but 431 of 437 namespaces still have none.

## 4. The kill for the class

**One sentence:** a declaration-time refusal in the schema admission walk —
a *stored* attribute whose `:db/valueType` derives to `string`, `symbol` or
`keyword` while its declared value names a program entity family (function,
namespace, test, schema, agent, process, capability), or whose registry key
ends in `-edn`, does not admit unless the declaration carries an explicit
`:seon.schema.admission/reason`, so the next such attribute is unwritable
rather than merely regrettable.

The seam already exists and already has the justification vocabulary: the
advisory walk `seon.schema.internal/assert-complete-schema!`
(`src/seon/schema/internal.cljc:119`, findings at `:57`) is called for every
declared form from `src/seon/schema.clj:1179`, and `:seon.schema/justified?` /
`:seon.schema.admission/reason` is the existing escape hatch (used today on
`seon.db/datoms`). It needs two new advisory kinds —
`:seon.schema.advisory/program-name-as-scalar` and
`:seon.schema.advisory/serialized-structure` — and the storability question
answered by the bridge that already computes it
(`seon.schema.datahike/storable-attribute-in?`, facets at
`src/seon/schema/datahike.clj:122`, `:231`). Placement is an owner decision:

| Option | Where | Guarantee | Cost | What we give up |
|---|---|---|---|---|
| **A (recommended)** | advisory kinds in `assert-complete-schema!`, terminal only for storable attributes | every publication, every cluster, refuses at the declaration that introduces it | 6 h + the audit regression | the 15 existing rows must be justified or fixed in the same commit, or the refusal is non-terminal until they are |
| B | the bridge, `src/seon/schema/datahike.clj:231` | refuses at storage derivation, where storability is already known | 4 h | fires at install time, not at declaration review — a lane sees it later |
| C | a graph-derived regression only, no refusal | zero risk to boot | 2 h | a new bad attribute lands and is caught at the next gate, not at the edit |

**The regression** (same shape as the existing derived inventory, no roster):
`every-stored-program-name-is-a-ref-or-justified`, in
`seon.schema-audit-test` (`test/seon/schema_audit_test.clj:49` is its sibling).
Assertion: from the canonical fixture database, query every installed attribute
whose value type is string/symbol/keyword, keep those whose declaration names a
program entity family or whose key ends in `-edn`, and assert the set equals the
set carrying `:seon.schema.admission/reason`. It fails today naming
`:seon.fn/sym`, `:seon.test/sym`, `:seon.render/ai`, `:seon.render/html`,
`:seon.ns.alias/target-ns`, `:seon.sci.eval/ending-ns`, `:seon.effect/capability`,
`:seon.error/process`, `:seon.error/throwable-class`,
`:seon.ai.attempt/usage-edn`, `:seon.ai.attempt/settings-edn`,
`:seon.cluster.eval/triage-edn`, `:seon.fn/arglists`,
`:seon.test/failing-assertions`, `:seon.test/adoption-inputs`; it passes when
each is a ref, a symbol, or justified.

## 5. Order of landing, by leverage

1. **Row 2 (`:seon.render/ai-fn` / `html-fn`) — 2 h.** 771 refs, one writer, and
   it makes "which functions render this entity" a join for the steward view.
2. **Rows 5, 6, 7, 8, 13 — 6 h together.** Five one-line ref/symbol accretions
   whose targets all exist; each one closes a branch of the agent-rooted pull
   in data-model §8.4.
3. **Row 14 (`:seon.test/subject`) — 5 h.** The single most valuable missing
   edge: 1 753 tests currently reach their subject only through a 39 s call walk.
4. **The class kill (§4) — 6 h.** Land it after 1–3 so the refusal starts with
   the smallest possible justified set.
5. **Rows 9, 10, 11, 12 (EDN text → facts) — 12 h.** Usage first: it is pure
   deletion now that `:seon.ai.usage/*` is written.
6. **Row 1 (`:seon.fn/sym` as a symbol) and row 4 (retire `arglists`) — 9–13 h.**
   Highest blast radius, lowest urgency; do it when a lane owns `seon.fn` alone.
   Land with it the accretion this census wants: `:seon.fn/writes`, the
   attributes a function asserts, emitted by the same analyzer that already
   emits `:seon.fn/keywords`, so the census stops being an approximation.

## 6. Files

Owned by this lane: this note and
[writer-census-probe-2026-09-16.clj](writer-census-probe-2026-09-16.clj).
No source or test file was edited; no test JVM was run; `default` was never
stopped, reforked or restarted.

Protected at the time of writing (`git status`, other lanes' uncommitted work):
`resources/seon/schemas/seon.eval.edn`,
`docs/seon/issues/attempt-recorder-returns-error-identity-without-occurrence-evidence.md`,
`docs/seon/issues/complete-publication-takes-seventy-seconds.md`,
`docs/seon/issues/evaluation-reader-refuses-pulled-renderer-ref.md`,
`docs/seon/issues/runtime-schema-unregister-retains-installed-attribute.md`,
`docs/prds/context-generation/research/turn-test-reds-*`, `build/`, `workers/`.
