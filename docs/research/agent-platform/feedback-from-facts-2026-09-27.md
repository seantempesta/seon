---
type: research
status: inventory, REPL prototype and measured feedback queries; no production source changed
created: 2026-09-27
lane: feedback-facts-opus
evidence: inventory and clj-kondo coverage on default pid 53665 (loaded 441988b49); feedback proofs on default pid 71396 (loaded cd4ca3d39, root replaced the JVM at 22:01Z mid-lane); disposable branch fb-facts (retired)
prototype: tmp/feedback-facts/proof.clj (namespace `proof`, evaluated form by form over MCP JVM mode, then removed)
---
# Feedback from facts: what is in the database, and feedback as one query

**What exists.** Every `src/` and `test/` definition is a program row: 5,573 function and var rows, 2,377 test rows, 491 namespaces and 827 files. Contracts are parsed into arity entities, call edges are stored as symbols, and tests leave run and member facts. Errors name their function. Task EDN and `:seon/tasks` annotations are ingested.

**Data flow and approach.** Each feedback item is one query over one database value: the batch's validated `db/with` db-after. Its scope is the batch's own changed rows. A render returns REPL entries, and the result goes in one new key of the one path's batch map. MCP and Seon agents receive it through the same projection.

**What blocks it.**

- The REPL refuses uncontracted definitions, so the growing warning has nothing to count.
- The per-reply test query is 0.6–2 s on a fresh head. The causes are an unindexed member symbol and two whole-program derivations per database value.
- The install gate's test results are not test facts.
- Faults link no task, and `[[links]]` are not parsed.

## 1. Inventory: facts per entity kind

Counts come from AEVT datom counts on default's head. Coverage is compared with a clj-kondo run over the loaded archive's `src test script` (`:cache false`, 2.6 s). "Fresh" compares the stored `:seon.fn.file/digest` with SHA-256 of the file. All 1,263 attributes were counted in 49 ms; 494 have datoms.

| Kind | Attributes | Default | Covers the program? | Fresh? | Query cost |
|---|---|---|---|---|---|
| Function and var rows | `:seon.fn/sym` `ns` `source` `form-span` `defined-by` `private?` `host-bound?` `constant?` `macro?` | 5,573 rows: defn 4,752, def 711, defonce 91, defmacro 8. 4,077 of them are in 123 non-test first-party namespaces | **src and test: 100%** (kondo defn 4,752/4,752 outside script; deftest 2,376). **script/: 0%**: 336 definitions in 13 stored files have no rows, including `seon.dev.mcp` itself | Every stored file digest equals the loaded archive (827/827, 66 ms). Nine differ from the working tree: other lanes' uncommitted edits | 28 ms for every kind count over AEVT |
| Contracts (present) | `:seon.fn/spec` (string), arities through vector cells (`:seon.db.vector/cells` → `:seon.fn/arities`), `:seon.fn.arity/input-refs` 3,623, `output-refs` 2,544, `guard-refs` 0 | 2,540 specs, all parsed (2,540 rows carry `:seon.db.vector/present :seon.fn/arities`) | Equals every `:malli/schema` defn in src+test (kondo 2,540) | as rows | Arities need a pull through cells (`seon.db/pull` reassembles them) |
| Contracts (missing) | absence of `:seon.fn/spec` | 2,206 of 4,757 defn/defn- rows have no contract. The most are in `seon.render.web` 98, `seon.turn` 70, `seon.render.transcript` 68, `seon.db` 65, `seon.fn` 62 | whole program | as rows | 23 ms for the program count on a fresh head |
| Contracts (malformed) | **none**: there is no attribute | 0 | AGENTS "preserve malformed authored contracts as data" has no storage; the file index has no malformed ones today | — | — |
| Namespaces | `:seon.ns/name` `requires` 4,591 `aliases` 3,921 `refers` 947 `imports` 399 `doc` 291 `source` | 491 (kondo 493 including script) | yes except script | as rows | — |
| Tests | `:seon.test/sym` `source` `ns` `:seon.fn/calls` `:seon.task/tasks` | 2,377 | src+test 100%; script tests absent (45, e.g. `seon.dev.markdown-test/*`) | as rows | — |
| Test-row result attributes | `:seon.test/failures` `fail-count` `error-count` `reach` `reach-digest` `reach-unknown` (declared `:seon.program/written-by seon.test.runner/record-tx`) | **0 datoms each** | — | — | Read by `seon.fn/test-reach-rules` (`src/seon/fn.clj:1777-1782`), so `currently-failing-functions` (`:2050`) is always `[]` |
| Test runs and results | `:seon.test.run/*` (id, branch, basis-t, program-digest, input-digest, members, covered-by); `:seon.test.member/*` (symbol, pass/fail/error-count, reach-digest, completed-tx, failures); `:seon.test.report/*` and `:seon.test.failure/*` | 654 runs, 10,768 members, 2,206 failure reports | Latest per test on default's branch: pass 731, fail 94, **stale 645, never-run 907** (of 2,377) | stale is judged against the current reach digest | `runner/latest-results` for all tests 217 ms; `reach-digests` 14 ms warm. `:seon.test.member/symbol` has **no index** (`d/schema`: no `:db/index`) |
| Install-gate evidence | on the evaluation: `:seon.test.accretion/gate-test-count` `gate-pass-count` `gate-fail-count` `executed-count` `report-blob` `status` | 0 on default; present on branch evaluations | the gate's test symbols are not stored (`gate-tests` has 0 datoms) | — | not queryable as test results |
| Reaching tests | derived: `seon.fn/gate-sets` over `:seon.fn/calls` 104,912, declared edges and `:seon.test/subject` (0) | not stored (`:seon.test/reach` 0) | whole program | derived per database value | 1–25 ms warm; **303 ms on a fresh head** (`declared-callers` over the whole program) |
| clj-kondo usages | `:seon.fn/calls` 104,912, `call-arities` 106,500, `references` 2,005, `keywords` 44,810, `writes` 1,057, file `unresolved-references` 1,329 | kondo reports 181,587 var-usages | symbol sets per definition. Positions and usages outside definitions are not stored | as rows | indexed (`:seon.fn/calls` has AVET) |
| Docstrings | `:seon.fn/doc` 3,061 (src 2,583 of 4,077), `:seon.ns/doc` 291 | — | the headings are not parsed: in src, Costs 21, Edge cases 9, Recommendations 5, Known issues 4, Examples 3, Errors 2 | as rows | — |
| `[[links]]` | **none** | 0 | 99 `[[sym]]` in src docstrings. Only `script/seon/dev/markdown.clj:213` parses them, and only for `docs/` | — | — |
| Tasks | `:seon.task/id` `ns` `name` `kind` `intent` `must-read` 373 `evidence` 318 | 204: function 188, namespace 14, schema 2 | 205 entries in the working tree's task files. The one-entry difference is an uncommitted edit | from the last ingestion | — |
| Task links in code | `:seon.task/tasks` on fn, test and ns rows (from `:seon/tasks`, `fn.clj:625` `task-links`) | 48 subject edges (45 fns), 38 proof edges (31 tests), 6 on namespaces. 0 dangling. 42 tasks have a subject, 24 have a proof, **134 have neither** | 102 `:seon/tasks` occurrences in the code (31 src files) | as rows | 2.3 ms for 300 functions |
| must-read / see-also | `:seon.fn/must-read` `:seon.fn/see-also` (parser exists) | 0 / 0 | none are authored | — | — |
| Test seeds and subjects | `:seon.test/seed` `:seon.test/subject` (parser `fn.clj:477,752`) | 0 / 0 | none are authored on real deftests (only inside fixture strings) | — | — |
| Faults | `:seon.error/signature` 37, occurrences 61, `:seon.instrument/fn` on **every** signature, `resolved-tx` 9 | 13 open (derived by `seon.error/resolution`, ~1.1 ms per signature) | every fault names its function | derived | `:seon.instrument/fn` has AVET |
| Fault → task | `:seon.error/issue` | **0**: every open fault is "a fault without a linked task" (AGENTS §2) | — | — | — |
| Legacy issues | `:seon.issue/*` | 463 issues, 831 function refs, 108 test refs | read by `seon.issue` only; migration to tasks is language 7.11 | — | — |
| Evaluations | `:seon.cluster.eval/*` (id, source, ordinal, run, ns, read-evidence 469, faults window) and `:seon.eval/*` (shown, duration-ms, value-tokens). `error` is stored as the **printed string** (11) | 138 on default's branch | — | — | — |
| Evaluation → changed definitions | **no edge.** The spec adds `:seon.cluster.eval/binds` | derivable only through history | — | — | `seon.program/changed-identities` 8 ms warm, 94–396 ms cold. A Datalog pattern with its tx position bound (`[?f :seon.program/definition-digest _ ?tx]`) returned all 5.5k rows, not one: the tx binding does not filter |
| Help topics | `:seon.help/*` | not declared | — | — | — |

**Stored with no reader outside its producer** (source search, excluding schemas):

- `:seon.fn.binding/*`: 6,221 binding entities. The producer is `program.cljc:923-949`.
- `:seon.fn.argument/label-edn`.
- The `:seon.test.accretion/*` gate counts and `report-blob`. They are written by `turn.clj:171-197` and delivered only as prose.
- The test-row result attributes above are declared but never written, while `fn.clj` still reads them.

## 2. The task language: construct → parsed → stored → consumed

| Construct (language spec) | Parsed today? | Producer → entity, attributes | Default vs codebase | Feedback that consumes it |
|---|---|---|---|---|
| Task EDN entry (`resources/seon/tasks/<ns>.edn`, 7.1) | yes | `seon.task` ingestion → `:seon.task/{id ns name kind intent must-read evidence}` | 204 / 205 | (a) the tasks each function in scope names |
| `:seon/tasks` on defn, def, ns and deftest (7.2, 7.3) | yes | `seon.fn/task-links` (`fn.clj:625`) → `:seon.task/tasks` string set on the row | 92 edges; all resolve | (a) per-function tasks. It should also group (b) and (e) by task |
| `:seon/must-read` / `:seon/see-also` in code (1.5, 5a.6) | yes (same function) | → `:seon.fn/must-read` / `:seon.fn/see-also` | 0 / 0 authored | none yet |
| `:seon.task/must-read` in EDN | yes | task entity | 373 edges; 12 unresolved (for example `seon.call-preparation/hook`, and `seon.dev.mcp/run-events`, a script definition) | context only |
| `:seon.test/seed`, `:seon.test/subject` (7.9b) | yes | test row components and value | 0 authored | (b) reach via subject |
| `[[sym]]` in docstrings (AGENTS "Documentation is data") | **no** | — | 99 in src | would give (a) and (e) "docs linking this function" |
| Docstring headings (Examples, Edge cases, Costs, Recommendations, Errors, Known issues) | **no** (prose in `:seon.fn/doc`) | — | 44 headings in src | Known issues should carry task handles (AGENTS: "every known issue links its task") |
| Known issue → task | **not represented** | — | 4 "Known issues" sections | — |
| Fault → task (AGENTS §2) | **not represented** | — | 0 of 37 | (e) |
| Help topics (v0.3 §2) | **no schema** | — | — | — |

Ranked:

- **In the language but not parsed:** `[[links]]`, docstring headings, known issue → task, help topics.
- **Parsed but not stored:** nothing. Every parser that exists writes its attribute; must-read, see-also, seed and subject are simply unauthored.
- **Stored but unconsumed by feedback:** `:seon.task/tasks` on tests (proof edges) and `:seon.task/must-read`. The prototype's (a) is the first feedback reader of `:seon.task/tasks`.

## 3. Feedback prototypes: one query plus one render each

Code is in `tmp/feedback-facts/proof.clj`, and `(proofs …)` returns one map of named proofs. Every proof's value validated against its schema (`:seon.feedback/valid? true` for all ten). Times are wall-clock milliseconds on the armed default JVM (pid 71396). "Fresh" means the first call on a new head, which is every reply, since each reply commits. Tokens are `seon.ai.tokens/estimate` of the printed transcript: `user=> form`, then the value.

The render returns REPL entries `{:seon.repl/form … :seon.repl/value …}`. The forms name the `my.*` call an agent re-runs, such as `(my.program/uncontracted 'seon.test)`; those calls are proposed and do not exist yet.

| Feedback | Query (owner reused) | Fresh / warm ms | Tokens | Measured value |
|---|---|---|---|---|
| **(a)** uncontracted: namespace, change, program, and task links | index walk: namespace AVET → `:seon.fn/ns` AVET → EAVT; program count by AEVT; task links by Datalog | ns `seon.turn` 4.6 / 3.3; branch `user` 2.3 / 1.3; the same query as Datalog took **132–192** on a fresh head; program count 23 | 50 (branch); **665** for `seon.turn` (70 names) | `seon.test`: 4 in the namespace, program 2,206 / 4,757 |
| **(b)** reaching test results: pass, fail, stale, never-run | `seon.fn/gate-sets` → `runner/latest-results` → `runner/reach-digests` | `[user/plain-add]` on a fresh branch head: **889** (gate 303, latest 56, digests 38 in a second fresh run), 5–40 warm. `[seon.turn/call-turn seon.fn/gate-set]`: **2,005 / 294**. New seed sets on a moved head: latest 41–188, digests **1,039–1,531** | 41; 383 for `seon.fn/gate-set` (14 tests); 776 for 42 tests | `seon.fn/gate-set`: stale 12, never-run 2. A branch's own gate result shows as `:never-run` (gap G5) |
| **(c)** the growing uncontracted warning | branch rows with `:seon.schema.admission/source :agent` and no spec (the one-path spec's clause) | 20 / 0.03 | 31 | always `[]`: the REPL refuses uncontracted defns (G1) |
| **(d)** changed functions reached by no test | `gate-sets` per changed symbol | 23 / 0.6–1.3 | 25–31 | `user/plain-add` before its deftest: `[user/plain-add]` |
| **(e)** open faults on functions in scope | `:seon.instrument/fn` AVET → `seon.error/resolution` | 200 `seon.turn` functions: 28.6 / 9.5. Changed functions only: 1.9 / 0.4 | 67; 298 for `seon.turn` (5 faults) | `seon.turn/record-write-refusal-fault!` 3 occurrences … |
| **Whole reply** (`reply-feedback`) | all five over one db-after | **641–1,147** fresh / 59 warm | 130 | — |
| **Scope** (changed functions, database only) | `seon.program/changed-identities` | 94–396 / 8 | — | `[user/plain-add]` |

Verdicts:

- **(a), (c), (d) and (e) meet the under-50 ms per-reply target once they are index walks.**
- **(b) does not meet it on a fresh head.** The time is three owners' per-database-value work, not the feedback query itself:
  - `latest-results` scans every member because `:seon.test.member/symbol` has no index. It took 40 ms even for zero tests.
  - `gate-sets` rebuilds the declared-reference edges over the whole program.
  - `reach-digests` re-queries every definition digest and schema form (`reach-content`, `runner.clj:937`) before it looks up its cache.
- **The render must render less.** (a) for a whole namespace (665 tokens) should show the count and the changed names, with the full list one requery away.

What the agent sees today for the same events:

- An uncontracted `defn` is **refused**: "declares no :malli/schema".
- A contracted defn with no test gets the prose `:out` string "No example test gates user/plain-add; add a deftest …". That is feedback (d), as prose.
- A redefinition whose reaching test fails is refused with "its gate failed". The evaluation stores `gate-fail-count 1` and a report blob. The test's name and its expected and actual values reach neither the reply nor any test fact.

## 4. Where the feedback enters the one path

**The one place.** The batch map of `seon.sci.eval/evaluate-batch` (one-path spec §2 `:batch`) gains one optional key, `:seon.eval/feedback`, under `:seon.feedback/*` schemas. It is computed after the loop's "one validated `db/with` over the basis" (spec §3.1).

- **Database value:** that `db/with` db-after, the value `settle-batch!` commits, so it includes the batch's own rows.
- **Scope:** the batch's `:seon.program/rows` identities plus its `:unmap`/`:remove-ns` effects. This needs no query and no history walk.
- The rendered shown text is saved in the same settlement on the batch's last evaluation. `with-fault-windows` (`src/seon/turn.clj:3797`) already saves the fault window there, which is (e) today, so the stored reply stays the history the agent saw.
- (c) is the spec's existing `:seon.eval/contract-note` / `:seon.eval/contract-warning`, not a second key.
- Program-wide counts are not recomputed per reply. They are rendered only when the namespace or the change has uncontracted functions.

**The same path for both consumers.**

- MCP projects the batch keys once, as the final `{"tag" "batch" …}` event (spec §4, "The MCP reply projection").
- A Seon agent's next context renders the same saved text.
- The only way to deliver feedback to one side is to compute it outside `evaluate-batch`. Places where feedback exists on one side only today:
  - `runtime_status` open faults, failed-test counts and over-second profile lines are MCP only. Seon agents get `faults-form`/`for-agent` only in their opening.
  - Openings and system-turn refreshed reads exist for Seon agents only.
  - MCP JVM-mode evaluations get no feedback and are not recorded (requirement 8.6, still open).
  - `bin/test-check` tally text is for the shell only.
  - The install-gate advisory reaches both sides, but as prose in `:seon.cluster.eval/output`.

**How a test agent's results arrive.** `docs/research/agent-platform/test-agents-concurrency-2026-09-27.md` §5.1–5.5 settles each member's `commit-results!` and `record-exit!` facts on the run entity.

- For feedback (b) to see them with one query, the recording connection must be **the requesting agent's branch**, with `:seon.test.run/branch` = that branch. `runner/latest-results` selects runs by the db's own branch (`runner.clj:1982-1986`); the request-world branch is not enough.
- The member facts must carry `:seon.test.member/reach-digest` and the counts.
- The requester's next batch then shows them:
  - a reaching test whose latest member completed after the change is `:pass` or `:fail`;
  - one that did not is `:stale` (tests reaching a just-changed definition are stale by construction, so the per-reply status needs no reach digest);
  - one with no member is `:never-run`.
- The requester learns of completion from listened member facts, not from a channel payload.

**What each producer must write so that feedback is only a query.**

1. **Evaluation settlement:** agent rows for every definition, uncontracted ones included, with `:seon.schema.admission/source :agent` (G1).
2. **Test runner and test agents:** members on the requester's branch, with an indexed `:seon.test.member/symbol` (G2, G6).
3. **Install gate:** its results as member facts, not evaluation counters (G5).
4. **Fault writer:** a task link beside `:seon.instrument/fn` (G7).
5. **Analyzer:** `[[links]]` edges and script rows (G8, G9).

## 5. Gaps, ranked by the feedback they block, with the smallest change

| # | Gap | Blocks | Smallest change (owner, estimated lines) |
|---|---|---|---|
| G1 | The REPL refuses an uncontracted `defn` (`:seon.sci.eval/declaration-absent-error`; note at `src/seon/repl.clj:143`), against AGENTS "never refused at the REPL" | (c) entirely; (a) for the agent's own change | Already the one-path spec's contract step: admit the row with `:seon.schema.admission/source :agent` and no spec. `src/seon/sci/eval.clj` `admit-program-change!`, about −10/+15 |
| G2 | `:seon.test.member/symbol` has no index, so `latest-results` scans 10,768 members (40–500 ms fresh) | (b) per reply | `{:seon.db/index true}` on the key (`resources/seon/schemas/seon.test.member.edn`, 1 line), and `latest-results` walks AVET → member → run (`src/seon/test/runner.clj:1967`, about ±10) |
| G3 | `gate-sets` rebuilds the whole-program declared-reference edges per database value (303 ms fresh) | (b), (d) | Key `declared-callers` by program content in `seon.cache`, the way `reach-cache` keys by content (`src/seon/fn.clj`, about +10). A per-reply caller passes the batch's changed rows |
| G4 | `reach-digests` re-queries every definition digest and schema form (`reach-content`) per call. The holders are `defonce` LRU atoms outside `seon.cache` (AGENTS "one central cache") | (b) program-wide staleness (1.0–1.5 s fresh) | Per reply: drop digests (stale is implied, §4; 0 lines). Owner task: key the holder by the commit's program identity (`src/seon/test/runner.clj:937-1015`) |
| G5 | Install-gate test results are counters and a blob on the evaluation. Test symbols are not stored and there are no member facts: two stores of test results, and the failing test is never named in the reply | (b) for the agent's own change | The gate records its run through `runner/record-tx` on the agent's branch (or becomes a test-agent request). Delete the `:seon.test.accretion/gate-*` counters and their `turn.clj:171-197` copy (`src/seon/turn.clj` `gate-function-install` `:3405`, `src/seon/test/accretion.clj`; about +25/−60) |
| G6 | Test-agent runs are recorded on the request-world branch in the design | (b) delivery of test-agent results | A design constraint for test-agents §5.1: the recording connection is the requester's branch. 0 lines now; one line in that design |
| G7 | No fault → task link (`:seon.error/issue` 0 of 37) | (e) "fault without a linked task" (AGENTS) | The fault writer mints or links a task through `seon.task/create!` (7.8) keyed by signature (`src/seon/fault.clj`, about +20) |
| G8 | `[[sym]]` links and docstring headings are not parsed. Known issues carry no task handle | (a) and (e) with "docs linking this function"; known-issue feedback | The analyzer extracts `[[sym]]` from `:seon.fn/doc` into a `:seon.program/edge-symbol` set, the way `task-links` does (`src/seon/fn.clj`, about +15; one schema key) |
| G9 | `script/` definitions are not rows (336, including `seon.dev.mcp`) | every feedback about the MCP entrance; 12 unresolved must-reads | Add `script` to the indexed source roots (`seon.cluster.source` input roots; a few lines) |
| G10 | Test-row result attributes are declared but never written, while `test-reach-rules` reads them (`fn.clj:1777-1782`). `currently-failing-functions` is always empty. The same rules' `call-edge` follows `:seon.fn/references` (`:1759-1761`), contradicting `call-reach`'s "a mention selects nothing" | a wrong answer for any "failing functions" feedback | Delete the unwritten attributes and the rule branch; derive from members (`src/seon/fn.clj`, about −25) |
| G11 | No malformed-contract representation | (a) "malformed" once G1 admits REPL rows | One optional string attribute `:seon.fn/malformed-spec` plus the explanation, written by admission (about +10) |
| G12 | A tx-bound Datalog pattern does not filter by tx | any "rows this transaction wrote" query | Verify against `reference-code/datahike` query planning before a task. Feedback avoids it by taking scope from the batch |

## 6. Other findings from the probes (for root to file)

- **The branch agent's namespace is refused, not created.** A branch agent created at first use in `user` refuses a later call with `namespace scratch.fb`: "Source submission requires an agent with an assigned namespace". The tool description says the agent is created in the call's namespace.
- **`(do (ns scratch.fb) (defn plain-add …))` in one SCI form refuses** as an undeclared change ("its analysis declares no definition for at the place it ran").
- **Runtime health.** `runtime_status` reports health `faulted`: 13 open faults, each without a task. The over-second lines were `seon.cluster.agent/evaluate-source!` max 46 s and `seon.test/run` max 78.6 s (not this lane's work).
- **Two definitions of "failing".** `runtime_status` counts 164–166 failed tests; the latest-member derivation counts 94 failing (plus 645 stale).

## 7. Verification limits

- These are prototypes in a scratch JVM namespace; no `my.*` entrance exists yet.
- Token counts use the prototype transcript printer, not the floor printer.
- "Fresh" means the first call on a newly committed head. It is not a measurement inside `evaluate-batch`.
- The inventory ran on the 441988b49 JVM; the feedback timings ran on cd4ca3d39.
- The projected sub-millisecond `latest-results` after G2 is an estimate from AVET lookups of the same shape. It is not measured.
