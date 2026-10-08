---
title: Agent-loop ledger (the one ordered slice list)
type: reference
status: active
created: 2026-10-08
---

# Agent-loop ledger

This is the only ordered list of agent-loop work. The behavior it implements is specified in
[agent-loop.md](../specs/agent-loop.md) and [merge.md](../specs/merge.md); the authority above both is
`docs/research/agent-platform/2026-10-08/review/owner-rulings.md`, then CLAUDE.md. A row here says what to change,
where, how it is proven and in which order. It never restates a law; if a row and the spec disagree, the spec wins and
the row is corrected.

It absorbs, and supersedes as execution order: `split/reconciled-plan.md` (S1, S3-S7, PB, CS, AR, Q answers),
`repl-audit/repl-plan.md` §5 (P0-P14), `s5-admission/admission-design.md` §7 (S5-04a-j), merge.md §7 (M0-M13),
`competency/competency-spec.md` §9 (C, A, D, B, X, Q, H), the printer audits (`printer-rules/`, prefix `PR-`), the
message audits (`agent-messages/`, prefix `MS-`), the raw-output audit (`raw-output/raw-output.md`, prefix `RO-`),
errors.md §7 (prefix `E-`) and the three redesigns under `redo/` (prefixes `RP-`, `F`, `MA-`). Those documents stay as
dated evidence and design; their old ids appear here only as aliases.

## How to read a row

- **Status.** `landed <commit>`; `in flight (<lane>)`; `ready` (fully specified: files, contract, proof, budget);
  `blocked-on-owner (<question>)` (an owner or root decision is missing; for a redesign's rows, the owner's approval
  of that core design and the answers to its questions). A row launches only when it is `ready` **and** every
  dependency is landed.
- **Group** is the file-ownership group. Rows in one group run in series; rows in different groups run in parallel
  when their claimed files are disjoint (`bin/lane status` is the ledger of claims).
  A render/printer (`print.cljc`, `render/value.clj`, `render.clj`, `repl.clj`) · B turn (`turn.clj`, `turn/batch.clj`,
  `batch.clj`) · C SCI (`sci/eval.clj`, `sci/arm.clj`) · D context and wire (`context.clj`, `ai.clj`,
  `cluster/prompt.clj`, `render/web.clj`) · E stores and tests (`db.clj`, `blob.clj`, `health.clj`, `effect.clj`,
  `test.clj`, `task.clj`) · F errors (`error.clj`, `instrument.clj`, `fault.clj`) · H help and lookups (`bootstrap.clj`)
  · K competency (`test/seon/competency*_test.clj`, `resources/seon/tasks/seon.competency.edn`) · M merge
  (`cluster/source.clj`, `my/program.clj`, `cluster.clj`) · O operator (`deps.edn`, `script/seon/operator.clj`)
  · T tooling (`bin/*`, `.clj-kondo/`).
- **Budget codes** (each row reports parent and change numbers; a regression over 20% or 50 ms blocks it):
  **O** `seon.pass-probe/run-mcp` SCI and JVM plus `run-model` through the HTTP fixture: ordinary pass < 200 ms and
  20-40 MB transient (never GB), one machinery transaction; definition pass < 300 ms / < 30 MB; the pass after it
  < 150 ms / < 10 MB. **L** `seon.pass-probe/window` around one lookup through MCP SCI: < 1 s, < 10 MB.
  **H** history of 1/10/200 exchanges at ~2 KB: append < 10 ms and ≤ 3× the appended text. **I** one model wake with
  the current-state block: block render < 10 ms / < 2 MB, the pass as O. **T** `seon.pass-probe/run-check`: runner
  overhead < 300 ms cold, < 150 ms warm, < 20 MB, member and request commits counted apart. **F** injected
  refusal/timeout/late exit: attempts, durable commits and held resources counted. **D** documentation or deletion:
  the narrow proof, zero calls on the deleted path. **R** reply size: `seon.ai.tokens/estimate` of the MCP reply.
  **M** `docs/research/agent-platform/2026-10-08/memory/probe.clj`: live heap after full GC and RSS.
- **Proof** names a deftest asserting DATA through the real MCP entrance (`seon.mcp/call`), new unless marked
  existing. A slice lands with `bin/lane commit LANE --test <its proofs>`.

## 1. Redesigns

Both plan reviews ordered three areas redone. The redesigns have landed as designs; each is a core design the owner
approves, with owner questions (recommendation first) in its last section. Their slices are imported below under their
own ids, in their own order, `blocked-on-owner` until approved; a slice that fixes a defect independent of the design
is `ready`.

| Redesign | Commit | Replaces | Imported rows | Owner questions |
|---|---|---|---|---|
| [`task-finish.md`](../../research/agent-platform/2026-10-08/redo/task-finish.md) | `7087c463c` | AR-01..AR-04, S6-06b, S1-10's session-end content, the step API, the one-test cost (TR) | F0-F9 (§3.9) | §7: question vs code by task kind; delete steps; replace an open proposal; 400 ms interim one-test budget; CLAUDE.md Var-render sentence |
| [`pass-and-recording.md`](../../research/agent-platform/2026-10-08/redo/pass-and-recording.md) | `48c1d5f45` | P7, P8 (S5-04a-j), P9, P12, P13, P14, S6-03, S6-04a/b, DATA; breaks the cycle P7 → P14 → S6 → S6-03 → P7 | RP-0..RP-17 (§3.2) | §8: overwrite another writer's change with a finding; sweep required-ref referrers on branches; same-pass program reads; Seon's own diagnostics stored as data; a lost reply accepted; DATA read at the cursor; closure `defn` recorded with its form |
| [`merge-acceptance.md`](../../research/agent-platform/2026-10-08/redo/merge-acceptance.md) | `b4c2d0a12` | M1-M7, M9-M13, G10, R-01; T12's expected outcome | MA-0..MA-17 (§3.10) | §8: migrations on the merge task; review proc through root's path; 10,000-datom migration cap; head's row kept for a conflict; delete the `uncovered` gate; rebase once on head moved |

Not covered by any redesign, and still undecided: the S5-07 entrant adapters and S6-02 (the test entrant on the one
fold). RP-14 depends on S6-02b, so the one-commit model pass (C0) waits on that decision (§3.8).

## 2. Landed

| Id (aliases) | What became true | Commit |
|---|---|---|
| S0 | baseline probe `seon.pass-probe` | installed |
| S1-01, S1-02, S1-03, S1-04, S1-05 | lookups return data on SCI `clojure.repl` (first five) | `f50ef6059`, `438328b34`, `d11dbe2b6`, `7876f594e`, `28c141d56` |
| S2a, S2b-1 | dead debug views deleted; reader rules in the shared stepper | `25bb94be0`, `84a3c5e4d` |
| S3-01 | origin `:model :outside` | `2bf3e10db` |
| X-01 | REPL and Flow skill claims re-anchored | `6761a1fd8`, `e59e762e6` |
| I-Q4, I-Q10, I-Q14 | caller-closure inventories | `fe58197b2`, `c2b87bdf7`, `bb4c5a311` |
| S5-03a-k | admission case notes (evidence for the pass redo) | `77aa9d05e` … `e24fda839` |
| P0 | `seon.repl-differential-test` table | `c816e3fde` |
| P1 | SCI-side native veto deleted (the settlement-side veto remains: RP-1) | `ded5e35de` |
| P2 | SCI owns reader policy | `e21fc4674` |
| P3 | namespace kept after a throw | `f9dc29814` |
| P4 | malformed contract keeps the function callable | `0667ee448` |
| P5 | printed output through the printer's budget | `d78473124` |
| P6 | forgiveness is one linear scan | `c98ade404` |
| P7a | repair notes beside the value | `7569761aa` |
| P11 | `set!` of print options no longer invalidates the base | `666c767f7` |
| PB-01 | one cluster base per loaded commit; agent overlay | `6191eace4`, `48191071f` |
| M8 (G6) | a definition keeps its namespace row | `76daa8fd5` |
| C1 | competency task file, T1 proof and oracle | `c6ae8875d` |
| E-S1 | an error names its variant on `:seon.error/type` | `33fb994f2` |

## 3. Rows

### 3.1 Pass budget (GB class first)

| Id | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| PB-02 | The agent overlay advances by changed definition digests: install added/changed rows plus their `reverse-closure` in place, unmap removed ones; unchanged digests do no read | `src/seon/sci/eval.clj`, its schema, `test/seon/sci/branch_execution_test.clj`, `test/seon/cluster/evaluate_source_test.clj`, `test/seon/sci/eval_test.clj` | `seon.sci.branch-execution-test/a-one-definition-change-installs-only-its-closure`; `seon.cluster.evaluate-source-test/a-pass-after-a-definition-constructs-no-base` | MCP pass after a one-line `defn` ≤ 60 MB / 100 ms; model pass after a `defn` drops ≥ 400 MB | PB-01 | C | ready |
| PB-02b | Fork once, advance in place: an unchanged program pass reuses the agent's ctx without regeneration | `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj` | `seon.sci.eval-test/an-unchanged-program-pass-reuses-the-agents-context` | `fork-for-turn` ≤ 1 ms unchanged | PB-02 | C | ready |
| PB-03 | Adoption advances the cluster base by its reloaded namespaces; the previous commit's base is released | `src/seon/sci/eval.clj`, `src/seon/cluster.clj`, `test/seon/sci/branch_execution_test.clj` | `…branch-execution-test/an-adoption-advances-the-cluster-base-by-its-reloaded-namespaces` | adopting one namespace ≤ 50 MB / 100 ms in the base | PB-02 | C | ready |
| PB-04 | A new agent's first pass: host and agent creation (`cluster.host/host!` 83 ms, three `transact!` 85 ms; 137-141 MB) brought inside O | owners named by its probe (`pb-01-probes.clj`) | `seon.cluster.evaluate-source-test/a-new-agents-first-pass-is-an-ordinary-pass` | O | PB-01 | B/E | ready |

### 3.2 REPL law and the pass (P, RP; design: `redo/pass-and-recording.md`)

Every RP row's budget: the redesign's §1 MCP rows do not regress over 20% or 50 ms (O). Proofs live in
`seon.repl-differential-p8-test` unless named otherwise.

| Id (aliases) | Change | Files | Proof | Deps | Group | Status |
|---|---|---|---|---|---|---|
| P10 (MS-opus S5, MS-astra 3) | Delete `evaluated-source` and `def-note`; a Var shows as `#'ns/x`; a regex shows its source; help states lifetime once | `src/seon/sci/eval.clj`, `src/seon/render.clj`, `src/seon/repl.clj`, `src/seon/render/value.clj`, `src/seon/bootstrap.clj`, tests | `seon.repl-differential-test` rows `a-var-shows-as-a-var`, `a-regex-shows-its-source`, `a-def-adds-no-note`; R: `(def x 1)` reply ≤ 20 tokens | P0; out-fix releases `render/value.clj` | A | ready |
| RP-0 | Measure the owners of the `defn` and five-`defn` passes | `test/seon/pass_probe.clj` | the probe's `:defn` row with owners | — | B | blocked-on-owner (redesign approval) |
| RP-1 (S5-04i; task `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone`) | `removal-facts` returns retractions plus `[:dangling …]`/`[:swept …]` findings, never the refusal | `src/seon/turn/batch.clj`, `seon.batch.edn` | `…/a-core-unmap-with-a-surviving-caller-stays-unmapped` | — | B | blocked-on-owner (redesign §8.2) |
| RP-2 (S5-04a) | The analysis batch partitions per namespace row | `src/seon/fn.clj`, `test/seon/fn_test.clj` | `seon.fn-test/a-batch-analyzes-each-request-under-its-own-namespace-row` | — | B′ | blocked-on-owner (redesign approval) |
| RP-3 (S5-04b) | Write capture adds meta and resolver row | `src/seon/sci/arm.clj`, `src/seon/sci/observe.clj`, `seon.batch.edn` | `…/redefinition-keeps-each-calls-value-and-stores-the-last` | RP-2 | C | blocked-on-owner (redesign approval) |
| RP-4 (S5-04g) | `register!` in an evaluation commits through the world writer | `src/seon/schema.clj`, `src/seon/sci/arm.clj`, `seon.schema.edn` | `…/register-then-transact-then-read-in-one-pass` | RP-3 | C | blocked-on-owner (redesign approval) |
| RP-5 (S5-04h1, j) | `derive-form` takes the one analysis; cross-namespace and locals in the same call | `src/seon/turn/batch.clj`, `src/seon/fn/analyzer.clj` | `…/nested-and-cross-namespace-definitions-analyze-once` | RP-3 | B | blocked-on-owner (redesign approval) |
| RP-6 (S5-04h2, d, e, f) | End stage derive → fold → record; `apply-pending` and the facts stages deleted | `src/seon/batch.clj`, `src/seon/turn/batch.clj`, `seon.batch.edn` | `…/a-successful-form-is-never-marked-failed`, `…/a-value-def-over-a-function-retires-its-row` | RP-1, RP-4, RP-5 | B | blocked-on-owner (redesign approval) |
| RP-7 (MS-opus S1) | Delete `changed-since-basis`/`revalidate`/`rebase`/`not-admitted`; `[:overwrote …]`; a refused recording keeps values and rows | `src/seon/turn/batch.clj`, `src/seon/turn.clj` | `…/another-writers-change-is-overwritten-with-a-finding`, `…/a-refused-recording-keeps-values-and-rows` | RP-6, RP-11 | B | blocked-on-owner (redesign §8.1) |
| RP-8 (S5-04c) | Digest under the final row, edges under the captured row; `[:alias-retargeted …]` | `src/seon/turn/batch.clj` | `…/an-alias-retarget-keeps-both-calls-and-notes-the-reload-change` | RP-6 | B | blocked-on-owner (redesign approval) |
| RP-9 (P9) | A nested closure `defn` is recorded with its enclosing form | `src/seon/turn/batch.clj` | `…/a-closure-defn-is-a-program-entity` | RP-6 | B | blocked-on-owner (redesign §8.7) |
| RP-10 (P14, ONE) | A model reply becomes an in-memory submission like MCP's; `stage-reply!`/`plan-tx`/`plan-call`/`source-rows` deleted | `src/seon/turn.clj`, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/a-model-reply-pass-commits-its-evaluations-once` | — | B | blocked-on-owner (redesign §8.5) |
| RP-11 (P7, S6-03, CUR) | One live cursor for model and MCP text; repairs attach per entry | `src/seon/turn.clj`, `src/seon/cluster/agent.clj`, `src/seon/sci/forgive.clj`, `test/seon/repl_differential_p7_test.clj` | `seon.repl-differential-p7-test/an-alias-required-by-one-form-resolves-in-the-next` | RP-10 | B | blocked-on-owner (redesign approval) |
| RP-12 | Repairs, findings and diagnostics stored as data; schema published and adopted on a live-store branch | `seon.cluster.eval.edn`, `src/seon/turn.clj`, `src/seon/turn/batch.clj` | `seon.repl-differential-p7-test/repairs-ride-beside-the-form-they-repaired` | RP-11, RP-7 | B | blocked-on-owner (redesign §8.4) |
| RP-13 (P12) | Code input with no form answers `nil` | `src/seon/turn.clj` | `…/a-discarded-form-answers-nil` | RP-11 | B | blocked-on-owner (redesign approval) |
| RP-14 (S6-04a) | Run open and attempt facts ride the one settlement; `*open*`, `pending-open`, `*receipt*` deleted | `src/seon/turn.clj`, `src/seon/db.clj`, `src/seon/sci/eval.clj`, `seon.db.edn`, `test/seon/db_test.clj` | `seon.db-test/a-transaction-stamp-carries-no-ref-to-an-unrecorded-evaluation`; model row 1 commit per pass | RP-10, S6-01, S6-02b | B | blocked-on-owner (redesign approval; S6-02 undecided) |
| RP-15a (S6-04b) | Boot recovery is `effect/interruption-stamps` by pass id; `recover-tx` families and `:seon.boot/recovered-runs` deleted | `src/seon/turn.clj`, `src/seon/cluster/boot.clj`, `seon.boot.edn` | `seon.cluster.turn-test/a-crash-before-record-keeps-only-durable-effects` | RP-14 | B | blocked-on-owner (redesign approval) |
| RP-15b-d | The 24 `turn/open-tx` fixture files convert to the pass entrance, three groups of eight (I-Q10) | per I-Q10 | each group's existing tests | RP-15a | B | blocked-on-owner (redesign approval) |
| RP-16 (DATA, competency C6) | The `:data` read stage and the `:seon.config.ai/entrance` dial | `src/seon/sci/forgive.clj`, `src/seon/sci/batch.clj`, `seon.batch.edn` | `…/data-forms-read-at-the-cursor` | RP-11 | B/C | blocked-on-owner (redesign §8.6) |
| RP-17a-b (P13) | Convert the eight `seon.sci.reader/read` callers, then delete the declaration machinery | the eight callers, `src/seon/sci/reader.cljc` | `seon.sci.reader-test/stored-source-reads-as-it-was-evaluated` | RP-11, RP-6 | B/C | blocked-on-owner (redesign approval) |

### 3.3 The one value printer (PR)

Printer core first (group A, serial: `src/seon/print.cljc`, `src/seon/render/value.clj`, their schemas,
`test/seon/printer_elision_test.clj`), then producer conversions by file, enforcement last. Every proof reads the
reply as data, evaluates the offered elision form through the same entrance and compares it with clojure.core on the
value. Every conversion row also takes the printer audit's Appendix A/B checkpoints that fall in its files
(`printer-rules/printer-audit-printer-rules.md`); checkpoints in no listed file land in appendix order under PR-A/PR-B.

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| PR-07 (opus S3, astra P07, MS-opus S10, out-fix) | Output shares the reply's one budget; the `:out` elision form reads the live output, never a database pull | `src/seon/repl.clj`, `src/seon/render/value.clj`, `src/seon/sci/eval.clj`, `test/seon/repl_differential_p5_test.clj` | `seon.printer-elision-test/the-out-elision-form-reads-the-live-output` | R: 3,000 printed lines ≤ 260 tokens | P5 | A | in flight (out-fix; partial `9c88ae5b8`) |
| PR-01 (astra P01) | One budget covers value, diagnostics and cuts; `plain-projection`'s independent quotas deleted | printer core | `…/one-printer-budget-includes-diagnostics` | R | PR-07 | A | ready |
| PR-02 (opus S1+S2, astra P02, MS-opus S2, MS-astra 12; task `seon.print/a-member-cut-costs-more-than-it-hides`) | Every cut continues: set and map cuts give `(drop n (seq v))`/key paths; elision forms are clojure.core only (`seon.print/value-at` leaves offered forms); a member is kept whole when its form costs more than it hides | printer core, `src/seon/render.clj:1310-1318`, `src/seon/error.clj:2264-2271` | `…/a-cut-set-or-map-continues-where-it-stopped`, `…/elision-forms-name-only-clojure-core` | R | PR-01 | A | ready |
| PR-03 (astra P03, opus S5a) | Strings that fit print whole; Unicode-safe text and source cuts | printer core | `…/text-cuts-preserve-source-and-unicode` | R | PR-02 | A | ready |
| PR-04 (astra P04, opus S5b) | A Throwable prints as a compact cause chain of error data; the frame erasure in `sci/eval.clj:290-297` deleted | printer core, `src/seon/sci/eval.clj` | `…/throwables-are-compact-data` | R: erroring test report < 300 tokens | PR-03 | A/C | ready |
| PR-05 (astra P05) | Off custody, cuts name a real retrieval or typed unavailable data; no fake handle | printer core | `…/off-custody-cuts-name-real-retrieval` | R | PR-04 | A | ready |
| PR-06 (astra P06, render-audit 5) | Render data is selected once; semantic acquisition (related data, schema identity) leaves the printer; a render that lists an edge suppresses the walk's copy | printer core, `src/seon/render.clj` | `…/render-data-is-selected-once` | R: namespace render −450 tokens | PR-05 | A | ready |
| PR-08 (astra P08) | Bounded lazy stepping; cycles become reference nodes; unknown tails are unknown | printer core | `…/printer-never-counts-an-unknown-tail` | R | PR-06 | A | ready |
| PR-09 (opus S6) | One walker: `core-text` through `select-nodes`; `datum`/`bounded-text` deleted | `src/seon/render/value.clj` | `…/core-text-equals-the-profiled-printer` | D | PR-08 | A | ready |
| PR-11 (owner ruling: display override) | `seon.print/*budget*` dynamic (session `set!`, one-form `binding`, works for strings); `:seon.print/budget` metadata on a collection; a render may put the key on its data; hard ceiling from the model's context window; an override above the default adds one finding with its token count | printer core, `src/seon/sci/eval.clj` (session print options), schemas | `…/a-budget-override-widens-one-form`, `…/an-override-past-the-ceiling-is-capped-with-a-finding` | R | PR-01 | A/C | ready |
| PR-10 (opus S4) | "dig form" becomes "elision form" everywhere (CLAUDE.md Presentation, docstrings, specs, test names); `my.program`'s `:dig` key becomes an operation key | CLAUDE.md, `src/seon/{render/value,error,health,bootstrap}.clj`, `src/seon/print.cljc`, `docs/seon/specs/errors.md`, tests | the PR-02 proof | D | PR-02 | A | ready |
| PR-12 (render-audit 1-2) | Renders return maps, never printed text: `seon.ai/attempt-ai`, `seon.problems/ai-prose`, `seon.print/render-elision-ai`, `seon.render/unknown-ai`, `seon.error/elision-prose`, `mcp-prose`, `render-database-identity-ai`, `effect/render-ai` | those owners | `…/printer-renderers-return-semantic-data` | R | PR-06 | A/F | ready |
| PR-13 (opus S8, render-audit 3, 8) | Renders stop cutting: `test/accretion`, `db/render-transaction-ai`, `health`, `render/schema`, `ai.clj:80-92`, `render/transcript.clj:870` (the Var render waits for task-finish) | those owners | `…/a-render-returns-every-group-and-the-printer-cuts` | R | PR-02 | A/E | ready |
| PR-15 (render-audit 6) | Delete dead renders: `capture-ai`, `render-history-ai`, the four unattached prose functions | owners per render-audit §2 | the D proof of each owner | D | none | A | ready |
| PR-16 (render-audit 7) | Facts as maps: `situation`, `creation`, `config`, `model`, `provider`, `missing-model` renders | `src/seon/cluster/agent.clj`, `src/seon/config.clj`, `src/seon/ai.clj` | `…/fact-renders-return-maps` | R | PR-12 | D | ready |
| PR-17 (opus S7) | Shown text built outside the printer: `turn.clj:2214`, `render.clj:1478`, `render/transcript.clj:418,463`, `repl.clj:101-107` with `error.clj:2451`, `error.clj:2503-2507`, `test/runner.clj:123-128` | those | `…/a-changes-reply-value-reads-back-as-data` | R | PR-09 | A/B | ready |
| PR-18 (opus S9) | Report functions return whole ranked data: `my.program` search/sizes, `error.clj:1977-1980`, `db.clj:2003,5809`, `test.clj:1712`, `edit.clj:56-79`, `shell/jvm.clj:303-322` | those | `…/search-holds-a-whole-long-line` | R | PR-02 | M/E | ready |
| PR-19 (opus S10) | Pages print through the printer's HTML face (`render/web.clj` 8 sites, `render/transcript.clj:786`) | `src/seon/render/web.clj`, `src/seon/render/transcript.clj` | `seon.render.web-test/the-subject-renders-as-printer-hiccup` | D | PR-09 | D | ready |
| PR-20a-d (opus S11-S14) | Messages are fixed text with values as data keys, one owner group per row (a: db, fn, call-preparation; b: turn, agent, env, effect; c: error, schema, instrument, schema.admission, test, fault; d: cluster.message, cluster, cluster.registry, fs, sci.eval) | per group | per group: one refusal's `:seon.error/message` equals its literal | R | E-P2 | per group | ready |
| PR-24 (opus S15, astra B structural) | Identity, compare and sort by data, never printed text | `src/seon/fn.clj:2674`, `schema/datahike.clj:525`, `env.clj:88-90`, `ai.clj:82`, `config.clj:603`, `render/web.clj:853,870`, `cluster/agent.clj:764`, `bootstrap.clj:310`, `cluster/message.clj:197` | `…/printer-identity-is-structural` | D | none | per file | ready |
| PR-25 (opus S16) | Program text members stored as data; schema published and adopted on a live-store branch | `program.cljc`, `sci/reader.cljc`, `sci/arm.clj`, `turn/batch.clj`, `fn.clj:541,766`, `schema.clj:3694`, schemas | `…/printer-logical-storage-keeps-data` | D | RP-17b | B/C | blocked-on-owner (first measure which readers parse the text; split in two) |
| PR-26 (opus S17) | Error evidence holds references, not printed copies | `src/seon/error.clj:337-490`, `render/hiccup.clj`, `fn/signature.cljc`, `test/runner.clj:2120` | `…/printer-errors-retain-native-members` | D | E-CUT | F | ready |
| PR-27 (opus S18) | Turn records keep data (`turn.clj:3856,4242,4244`, `background.clj:55`, `effect.clj:541,866`) | those | `…/printer-logical-storage-keeps-data` | D | RP-10 | B/E | ready |
| PR-28 (opus S19, astra A001-A009, MS-astra tail) | Tooling prints through the printer or returns data: `bin/seon-hook`, `script/seon/dev/{docstring,markdown,mcp}.clj`, `bin/test-check`, `script/seon/operator.clj`, log lines | those | `…/printer-hook-feedback-keeps-findings`, `…/printer-operator-and-bridge-return-data` | D | PR-09 | T/O | ready |
| PR-A, PR-B | Appendix A/B checkpoints of the printer audit not absorbed above, in appendix order | per checkpoint | per checkpoint | per checkpoint | PR-09 | per file | ready |
| PR-E1..E4 (astra E01-E04, opus S20) | `:seon/printer-law` at `:error` with a per-file ratchet; hook, BB and candidate wiring; reviewed class-(c) boundaries only | `.clj-kondo/`, `bin/seon-hook`, `bin/lane` | `…/printer-rule-refuses-bare-and-aliased-printers`, `…/printer-rule-refuses-hidden-cuts-and-copies`, `…/printer-law-cannot-be-bypassed-by-a-bb-or-shell-edit`, `…/printer-rule-keeps-query-and-transport-semantics` | D | PR-28, PR-A, PR-B | T | ready |

### 3.4 Agent-facing messages (MS)

Rules: data first, one fixed message per variant, Clojure's own wording with names and counts as data members,
fixes as runnable clojure.core forms (`agent-messages/` audits). Duplicates were folded: not-admitted diagnostic → RP-7 (deleted, not polished); def note → P10; `:out` →
PR-07; map cuts → PR-02; help → H-01; doc/dir → S1-06/S1-07a; domain prose renders → E-SRENDERS; tooling → PR-28.

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| MS-00 (astra 0) | Tests read the entrance's own response value (no second formatter) | `test/seon/test_support.clj`, `test/seon/mcp_entrance_test.clj` | `seon.mcp-entrance-test/message-proof-reads-the-same-response-value` | D | none | E | ready |
| MS-01 (astra 1) | A narrow requested map stays the data shown; no attached history | `src/seon/render.clj` | `…/a-selected-agent-map-stays-selected` | R ≤ 80 | MS-00 | A | ready |
| MS-03 (opus S3, astra 8; tasks `seon.sci.eval/an-arity-error-names-the-sci-closure`, `seon.turn.batch/a-jvm-compile-error-shows-the-wrapper-message`) | Arity, resolution and reader errors keep the dependency's own message as evidence; names and counts are data members; JVM failures use `clojure.main/ex-triage` | `src/seon/error.clj` (`reply-value`), `src/seon/sci/kernel.clj`, `src/seon/sci/reader.cljc` | `…/native-errors-keep-their-clojure-meaning` | R ≤ 80 per row | MS-00 | F | ready |
| MS-04 (opus S4, astra 5; task `seon.repl/the-slow-fix-ranks-the-whole-process`) | Findings carry kind and numbers only; `:repeated` and `:calls` leave the reply (kept on the record) | `resources/seon/schemas/seon.config.eval.edn`, `seon.repl.edn`, `src/seon/repl.clj` | `…/findings-name-measures-and-runnable-operations` | R ≤ 40 | PR-07 | A | ready |
| MS-07 (opus S7 as corrected by the Astra review, RO audit 6) | `my.test/run`/`check` return their contract's value without echoed copies (stored proof digests stay readable by run id); the test result's declared AI render returns `{:test :pass :fail :error :failures [{:test :expected :actual}]}` plus the run id; the runner's `:out` copy is not echoed | `src/seon/render/test.clj`, `src/my/test.clj` (render attachment only) | `…/a-failing-test-renders-its-summary-as-data` | R ≤ 80 | PR-12 | A | ready |
| MS-08 (opus S8, astra 2+6, render-audit 4) | Contract refusals: fixed message, Malli problems with paths, expected shown by registered name, no generated `:example` | `src/seon/instrument.clj`, `src/seon/error.clj`, schemas | `…/contract-errors-use-malli-problems` | R ≤ 250 | E-P4 | F | ready |
| MS-11a (opus S11, astra 7) | Empty neighbour list omitted; an undeclared attribute is one error with candidates and no invented example | `src/seon/error.clj`, `src/seon/db.clj`, `seon.db.write.edn` | `…/an-undeclared-attribute-is-one-error`, `…/an-unresolved-symbol-with-nothing-near-has-no-neighbours` | R ≤ 160 | MS-00 | F/E | ready |
| MS-11b (opus S11 same-pass check; task-finish §0.2) | A same-pass `my.test/check` reads the live world for names the pass defined (the REPL law), not a better refusal | `src/seon/test.clj` (`selection-seeds`) | `seon.test-test/a-same-pass-check-runs-the-new-definition` | — | RP-6 | E | blocked-on-owner (pass redesign §8.3) |
| MS-12 (opus S12, corrected) | `runtime_status`'s AI render returns state, ranked subjects and counts as data; the printer, not a "top 3" quota, cuts | `src/seon/cluster/status.clj`, `src/seon/health.clj` | `…/runtime-status-is-ranked-data` | R ≤ 250 | PR-02 | E | ready |
| MS-14 (astra 11) | The profile summary returns call data once, no string rows | `src/seon/profile.clj`, `seon.profile.edn` | `…/profile-summary-returns-call-data-once` | R ≤ 220 | MS-00 | E | ready |
| MS-16 (astra 14) | MCP tool descriptions and transport failures are compact; outer error flag and inner error data kept | `src/seon/mcp.clj` | `…/mcp-descriptions-and-failures-are-compact` | R ≤ 100 / 160 | MS-00 | E | ready |

### 3.5 Errors cut (E; spec: [errors.md](../specs/errors.md) §7, tests §8)

| Id | Change | Proof | Deps | Group | Status |
|---|---|---|---|---|---|
| E-S1b | Converted error keeps its declared variant; missed metadata readers converted | errors.md §7 S1b assertions | E-S1 | F | ready |
| E-P1 | Accrete error schemas (variants, Malli report variants, row members); publish+adopt on a live-store branch | publish+adopt | E-S1b | F | ready |
| E-P2 | Constructor arities `[type operation message]`, `[… data]` and `fail!` | errors T8, T12 | E-P1 | F | ready |
| E-P3 | Type producers, one owner family per slice, each with its render and signature change | errors T1 plus per-family rows | E-P2 | per family | ready |
| E-P4 | Pure calculations (report conversion, signature, row-writer tx), unwired | errors T3, T9 | E-P2 | F | ready |
| E-CUT | The dependency-complete cut (base drops `at`/`layer`, `caught` at every entrant, report adapter, row writer, receipts, readers) | errors T1-T12 | E-P3, E-P4 | F/B | blocked-on-owner (root splits it into ~15-minute loadable rows before launch) |
| E-SREADS | Each read family `fail!`s; its callers and contracts in one commit | per family | E-CUT | per family | ready |
| E-SRENDERS (MS-astra 13) | `render-ai`/`render-html` over the error map; prose renderers deleted; errors printed by the one value printer | golden transcripts in estimated tokens | E-CUT, PR-04 | F | ready |
| E-SDELETE | §5/§6 spans and `seon.fault`'s remainder fold into `seon.error` | errors §8 kept list | E-SRENDERS | F | ready |

### 3.6 Lookups and help (S1, H)

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| S1-06 (MS-astra 10 budget) | `doc` of a task (home declaration, intent, current step) and of a test (`{:sym :recorded :verified?}`) | `src/seon/sci/eval.clj`, schema, `test/seon/sci/documentation_test.clj` | `seon.sci.documentation-test/task-doc-reads-home-declaration-and-branch-proofs`, `…/lookup-does-not-certify-a-stale-test-result` | L; R ≤ 180 overhead | S1-05 | C | ready |
| S1-07a (MS-opus S9, astra 9, RO audit 1 budgets) | `directory-value [ctx authored]` from SCI `dir-fn`; `program-documentation`, `arglists-reader` and the `seon.repl` directory render deleted; `program-documentation-selector` kept | eval.clj, schema, documentation_test, `src/seon/repl.clj`, `seon.repl.edn`, `test/seon/render/value_test.clj` | `…/directory-answers-data` (R: `(dir my.task)` ≤ 60 tokens; 30 public rows raw ≤ 2,000) | L | S1-06 | C | ready |
| S1-07b | `apropos-value`/`find-doc-value [ctx pattern]` as qualified SCI Vars | eval.clj, schema, documentation_test | `…/searches-answer-data` | L | S1-07a | C | ready |
| S1-08 | `source-value [ctx database authored]`: stored source, then host `source-fn`, else unavailable; no `sci.ctx-store/get-ctx` | eval.clj, schema, documentation_test | `…/source-at-the-agent-repl-prints-the-stored-declaration` (converted to data) | L | S1-04 | C | ready |
| S1-09 | Lookups installed once in `sci/init` `clojure.repl`; `install-program-doc!` and the `seon.bootstrap/doc`/`dir` macros deleted | eval.clj, `seon.sci.binding.edn`, documentation_test, `test/seon/fn_test.clj`, `src/seon/bootstrap.clj`, `resources/seon/tasks/seon.sci.eval.edn` | `…/bare-and-qualified-lookups-share-vars`, `seon.fn-test/lookup-analysis-names-the-installed-repl-vars` | O + L | S1-07b, S1-08 | C/H | ready |
| H-01 (S1-10, F9 help, competency H1, MS-opus S6, MS-astra 4; tasks `seon.bootstrap/help-is-flagged-as-a-large-value`, `…/session-end-is-taught-three-ways`) | `help-value` is literal data teaching only installed forms, once; it is the static prefix; one session-end form | `src/seon/bootstrap.clj`, `seon.help.edn`, `test/seon/help_test.clj`, `src/seon/render/ns.clj`, `src/seon/run.clj`, `src/seon/cluster/agent.clj` (situation render) and the help tests | `seon.help-test/help-names-only-installed-forms` (every head resolves; R ≤ 220) | L | S1-09, F7 | H | blocked-on-owner (task-finish §7; this row is F9's help part) |
| S1-11 | Delete `my.program` `file-line`/`nearest`/`source`; correct `search`/`declaration-diff` docs | `src/my/program.clj`, `test/my/program_read_test.clj`, `test/my/program_test.clj`, `resources/seon/tasks/my.program.edn` | existing `my.program-read-test/search-ranks-bounds-and-offers-more` | D | S1-08, H-01 | M | ready |

### 3.7 Context: static prefix, history, current-state block (S4, CS, DBG)

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| S4-01 | Unique `:seon.cluster.eval/agent-ordinal` assigned per ordered writer batch; `seon.db/last-agent-ordinal` | `src/seon/db.clj`, `src/seon/turn.clj`, `src/seon/eval.clj`, `seon.cluster.eval.edn`, `test/seon/turn_test.clj` | `seon.turn-test/evaluation-ordinals-follow-writer-order` | H | S3-01 | B | ready |
| S4-02a | The call row carries origin and an assignment ref | `turn.clj`, `seon.turn.edn`, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/a-pass-call-row-names-its-assignment-and-origin` | O | S4-01 | B | ready |
| S4-02b (absorbs CS-06) | The static prefix: `(help)`'s value per loaded commit in a `seon.cache` kind, sent as the system message. Deletes the opening, `declared-sources`, `seed-tx` and the system turn's opening units (the one deleting row) | `turn.clj`, `src/seon/bootstrap.clj`, `src/seon/ai.clj`, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/the-static-prefix-is-byte-identical-across-calls-of-one-commit` | I | S4-02a | B/D | ready |
| S4-03 | History = call rows of the active assignment with origin `:model`, evaluations by position, from stored shown text | `src/seon/render/transcript.clj`, `src/seon/eval.clj`, `src/seon/repl.clj`, `test/seon/render/transcript_run_test.clj` | `seon.render.transcript-run-test/reacquired-history-is-the-finished-exchange` | H | S4-02a | A/D | ready |
| S4-04 | The proc holds the history; over the window the call refuses, no clipping | `turn.clj`, `src/seon/cluster/prompt.clj`, `test/seon/cluster/prompt_test.clj` | `seon.cluster.prompt-test/an-over-window-transcript-refuses-without-dropping-exchanges` | H | S4-03 | B/D | ready |
| S4-05a | `my.agent/inputs` returns undelivered items with identity and home basis (the block's Unread source and elision form) | `src/my/agent.clj`, `my.agent.edn`, `src/seon/context.clj`, test | `seon.cluster.turn-test/inputs-read-each-home-at-its-own-basis` | L | none | D | ready |
| S4-06 | Delete the refresh and stored freshness evidence (its CS-04 dependency is dropped: no file or data link) | `turn.clj`, `src/seon/render.clj`, schema, `test/seon/turn_test.clj`, `test/seon/turn_error_test.clj` | `seon.turn-test/settlement-does-not-store-refresh-evidence` | O | S4-02b, S4-04 | B | ready |
| S4-07a | `:seon.ai.tokens/characters` moves to the attempt row; `message-custody` moves out of the capture section | `cluster/prompt.clj`, `context.clj`, `turn.clj`, schema, `test/seon/cluster/prompt_test.clj` | existing `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior` | O | S4-04 | D | ready |
| S4-07b1 | Delete the web context-action POST with `change-context`, `system-turn`, `virtual-turn!`, `compact!`; convert `web_context_test`, `web_debug_test` | `src/seon/render/web.clj`, `src/seon/render.clj`, tests | `seon.render.web-context-test/the-context-view-is-computed-only-when-opened` | D | S4-07a, S4-06 | D | ready |
| S4-07b2 | Delete the prompt selection closure (`prompt.clj:171-402`) and its pricing tests | `cluster/prompt.clj`, `seon.cluster.prompt.edn`, `test/seon/cluster/prompt_test.clj` | `seon.cluster.prompt-test/a-model-call-stores-basis-without-prompt-capture` | O | S4-07b1 | D | ready |
| S4-07b3 | Delete capture, agent-owned selection and compaction (`context.clj:76-250,309-415`), walk composition (`walk.clj:840`), their schemas and tests | `context.clj`, `walk.clj`, `turn.clj:3124,4559-4580`, `render/transcript.clj:617`, schemas `seon.context.capture.edn`, `seon.context.contribution.edn`, `seon.context.edn` | existing `seon.context-test/message-custody-accepts-an-absent-run-when-instrumented` | O | S4-07b2 | D/B | ready |
| CS-05 | A task update by root or the user advances the assignment's revision in the same transaction | `src/seon/task.clj`, `test/seon/task_ingestion_test.clj` | `seon.task-ingestion-test/a-revised-task-shows-on-the-next-call-without-reassignment` | L | none | E | ready |
| CS-01a | `seon.context/current-state [database agent-id]`: Task (current declaration, steps) and Turns | `context.clj`, `seon.context.edn`, `test/seon/context_test.clj` | `seon.context-test/current-state-shows-the-current-task-declaration` | L | CS-05 | D | ready |
| CS-01b | Proofs: name, stored source, status by the basis rule, reds as `{:test :expected :actual :message}` | `context.clj`, schema, test | `…/a-red-proof-shows-expected-actual-and-message-as-data`, `…/a-proof-result-before-the-latest-reached-definition-is-pending` | L | CS-01a | D | ready |
| CS-01c | Must-reads (arglists, contract, first doc line, `doc`/`source` operations) and Unread | `context.clj`, schema, test | `…/unread-shows-each-undelivered-item-once-with-its-identity` | L | CS-01a, S4-05a | D | ready |
| CS-02 | The block's AI render returns pruned data with its order; the printer prints it under `:seon.print/budget` 2,000 (PR-11's key, one budget mechanism) | `context.clj`, schema, test, `test/seon/pass_probe.clj` | `…/an-over-budget-block-offers-its-elision-forms`; render-garbage check (PR-12's proof shape) | I | CS-01b, CS-01c, PR-11 | D | ready |
| CS-03a | `seon.ai/messages [wrapping prefix exchanges block]`, format A: prefix as system, transcript, block last | `src/seon/ai.clj`, `test/seon/ai_test.clj` | `seon.ai-test/transcript-ends-with-the-block-and-keeps-its-prefix` | L | CS-02, S4-04 | D | ready |
| CS-03b (absorbs competency C5) | Dial `:seon.config.ai/wrapping` (`:transcript :chat :prefix`, model row plus agent overlay); format B with the block in the last user message | `ai.clj`, config schema, test | `seon.ai-test/chat-puts-the-block-in-the-last-user-message`, `…/each-wrapping-builds-its-wire-messages` | L | CS-03a | D | ready |
| CS-03c | Format C: B plus the assistant prefix after the block where the provider declares continuation; the prefix is not echoed into history | `ai.clj`, test | `seon.ai-test/prefix-continues-after-the-block` | L | CS-03b | D | ready |
| CS-04 | A committed model pass records the unread items its block showed as `:seon.turn/delivered` (cardinality many) on its call row, the `:seon.turn/*` entity, inside the one settlement; the writer checks each exists and is undelivered; a failed call delivers nothing; the message-history append retires | `src/seon/turn.clj` (settlement, `delivery-rows`), `seon.turn.edn`, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/a-committed-model-pass-delivers-what-its-block-showed`, `…/a-failed-provider-call-delivers-nothing` | I | CS-01c, S4-04, RP-10 | B | ready |
| DBG-01 (ruling round 10) | One debug view: a call's context exactly as sent, produced by `seon.ai/messages` over the static prefix, the history and the block at that call's stored basis, printed by the printer's HTML face | `src/seon/render/web.clj`, `ai.clj` (basis members on the attempt row if missing), test | `seon.render.web-debug-test/the-debug-view-is-the-assembled-request` (the view's data `=` the request body the call sent) | L | CS-03a, S4-07a, S4-07b1 | D | ready |

### 3.8 Recording (S5) and the cut (S6)

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| S5-01 | `transact-call` returns a runtime pending on timeout; `with-publication!` awaits the terminal outcome before releasing the permit | `src/seon/db.clj`, `src/seon/blob.clj`, schemas, `test/seon/db_test.clj`, `test/seon/blob_publication_test.clj` | `seon.db-test/a-write-timeout-keeps-its-pending-completion`, `seon.blob-publication-test/an-unknown-root-commit-keeps-publication-owned` | F | none | E | ready |
| S5-02 | Health reads the live profile; `significant-tx` and stored measurements deleted | `src/seon/health.clj`, `src/seon/profile/measurement.clj`, `turn.clj`, `turn/batch.clj`, schemas, tests | `seon.health-storage-test/health-uses-live-profile-without-writing-measurements` | O | none | E/B | ready |
| S5-05 | Observed fault occurrences replace the fault-window query | `turn.clj`, `turn/world.clj`, `src/seon/batch.clj`, test | `seon.cluster.turn-test/a-pass-delivers-only-the-errors-it-observed` | F/O | S5-01, E-SREADS | B | ready |
| S5-06 | JVM mode stops acquiring: `seon.cluster.agent/held-context` or gone-value with class | `src/seon/mcp.clj`, `turn/batch.clj`, `turn/world.clj`, `cluster/agent.clj`, `test/seon/mcp_entrance_test.clj` | `seon.mcp-entrance-test/a-jvm-call-does-not-acquire-an-sci-world` | O/F | none | E | ready |
| S5-07a-f | Every entrant (writer, JVM, SCI, model, member, retire old) on one recording owner; values kept through recording failure | Sol's table (`split/spec-split-sol-split.md` S5-07) | `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure` | O/F/T | S5-01, S5-05, S5-06, RP-7 | B/E | blocked-on-owner (the common settlement request contract: whether RP-7's `settle` is the surviving owner and which adapters remain after RP-10/RP-11) |
| S6-01 | Effect receipt stores the pass id value plus form ordinal under a new attribute; `:seon.effect/run`, `evaluation-eid` and `:seon.effect/eval` retired | `src/seon/effect.clj`, `seon.effect.edn`, `turn.clj:1905`, `test/seon/effect_test.clj` and the receipt fixtures | existing effect proofs converted to identity values | F/O | none | E | ready |
| S6-02a | Bounded test body through the one fold; member pass on the member branch, aggregate on the requester | `src/seon/test.clj`, `turn/batch.clj`, `turn/world.clj`, schemas, `test/seon/test_test.clj` | `seon.test-test/a-member-records-one-pass-before-its-request-aggregate` | T/F | S5-07d, S6-01 | E | blocked-on-owner (with S5-07) |
| S6-02b | The effect attempt agent/turn preparation deleted; a background effect in a test attempt refused by name | `test.clj:179-216`, `effect.clj:872`, schemas, tests | `seon.test-test/a-test-effect-needs-no-attempt-agent-or-turn` | T/F | S6-02a | E | blocked-on-owner (with S5-07) |
| S6-05 | Answeredness to the presented basis; the cap counted per outside-wake anchor at the writer; continuation | `turn.clj:2584-2908`, schemas, `test/seon/turn_work_test.clj` | `seon.turn-work-test/model-passes-answer-only-the-basis-they-presented` | O/I/F | RP-15a | B | ready |
| S6-06a | `assign!` refuses a task with no proofs or all-current-green proofs, checked at the home | `src/seon/task.clj`, `seon.task.edn`, `test/seon/task_ingestion_test.clj` | `seon.task-ingestion-test/assignment-requires-a-non-green-proof` | F/O | none | E | ready |
| S6-07a | `disarm!`/`disarm-agents!` take an explicit deadline from every caller (scripted sweep of ~40 test sites), give each agent its own slice and return the typed timeout error | `src/seon/cluster/agent.clj`, `src/seon/cluster.clj`, schemas, `test/seon/cluster/agent_test.clj` and the callers in I-Q14 §2 | `seon.cluster.agent-test/a-stuck-agent-does-not-zero-the-others-deadline-slices`, `…/a-missed-agent-join-answers-the-typed-timeout-error` | F | S6-05 | B | ready |
| S6-07b | Delete the backstop timer, parking and permit; the actual-exit owner keeps one detector naming the missing terminal event | `turn.clj:5593-5856`, `src/seon/flow.clj`, `test/seon/turn_backstop_test.clj`, `test/seon/loop_proof_test.clj`, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/a-timed-out-pass-keeps-ownership-until-it-exits` | F | S6-07a | B | ready |

### 3.9 Proofs and finishing (S7, F; design: `redo/task-finish.md`)

| Id (aliases) | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| F0 (task `seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass`) | A caller keeps an earlier pass's callee (REPL-law violation; proofs go green on stale code); fix the pass-boundary advance or overlay | `src/seon/sci/eval.clj` | `seon.repl-differential-test/a-later-pass-redefinition-reaches-earlier-callers` (`[(h2) (g2)]` = `[3 3]`) | O | none (pb-01 releases `eval.clj`) | C | ready |
| S7-01 | `agent-run-test-var [var] → summary counters` in the SCI `clojure.test` adapter | `src/seon/sci/eval.clj`, `test/seon/sci/documentation_test.clj`, `resources/seon/tasks/seon.sci.eval.edn` | `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data` | T | S1-04 | C | ready |
| F1 (TR-01, AR-01 selection) | A request naming identities without `:changed` runs only those; reuse by member reach digest | `src/seon/test.clj` | `seon.test-test/a-proof-request-runs-only-its-proofs` | T: < 400 ms | F0 | E | blocked-on-owner (task-finish §7.4) |
| F2 | The member overlay is one branch transaction | `src/seon/test.clj` | existing `run-test-var-answers-one-branch-test-as-data` | T: < 250 ms | F1 | E | blocked-on-owner (redesign approval) |
| F3 | Member recording in one transaction (4 → 1) | `src/seon/test/runner.clj` | `seon.pass-probe` row: one proof < 150 ms / 20 MB | T | F2 | E | blocked-on-owner (redesign approval) |
| F4 (AR-01, AR-03) | The proof proc: branch-advanced signal, `{:running? :dirty?}`, proof-only request; restart reruns pending proofs | `src/seon/turn.clj` (settlement flag), the agent graph's proc table, `test/seon/cluster/turn_test.clj` | `seon.cluster.turn-test/a-defn-reaching-a-proof-records-its-result-without-a-check-call`, `…/changes-during-a-run-are-checked-by-the-next-run`, `…/a-restarted-proc-runs-pending-proofs` | I | F3, S6-05, S7-01 | B | blocked-on-owner (redesign approval) |
| F5 (AR-02) | Outcome: question → done; code with a change → propose (reused run); code, no change → done | `src/seon/task.clj`, `src/my/program.clj` | `seon.task-ingestion-test/green-proofs-propose-once`, `…/a-green-question-completes-without-a-proposal` | T | F4, MA rows | E/M | blocked-on-owner (task-finish §7.1, §7.3) |
| F6 | `:question` task kind; `my.task/answer` reads the tested branch's agent answer | `seon.task.edn`, `seon.test.member.edn`, `src/my/task.clj` | `seon.competency-test/an-answer-without-code-runs-its-proof`, `…/a-changed-answer-invalidates-green` | T | F5, A1 | E | blocked-on-owner (task-finish §7.1) |
| F7 (S6-06b) | Delete `my.turn/complete`, `my.agent/done`, `seon.run/complete`; `my.task/complete!` bails with a required reason | `src/my/turn.clj`, `src/my/agent.clj`, `src/seon/run.clj`, `src/my/task.clj`, schemas, tests | `seon.task-ingestion-test/completion-with-a-reason-gives-the-task-back` | F/O | S6-06a | E | blocked-on-owner (redesign approval) |
| F8 | Delete task steps | `src/my/task.clj`, `src/seon/task.clj:520-640`, `src/seon/render/ns.clj`, schema, `test/seon/task_steps_test.clj` | `seon.contracts-compile-test`, `seon.render.ns-test` green; no `:seon.step/` | D | none | E | blocked-on-owner (task-finish §7.2) |
| F9 (AR-04, printer S8 Var case) | The Var render's test status and `check` offer deleted (its help part is H-01) | `src/seon/render/var.clj` | `seon.render.var-test/a-var-render-shows-no-test-status` | R | F4 | A | blocked-on-owner (task-finish §7.5) |

### 3.10 Merge (MA; spec [merge.md](../specs/merge.md); design: `redo/merge-acceptance.md`)

Serial in group M, in this order. MA-0, MA-1, MA-2, MA-4 and MA-15 keep the pre-redesign design and are ready.

| Id (aliases) | Change | Proof | Deps | Group | Status |
|---|---|---|---|---|---|
| M0 | The `competency` cluster starts in the running JVM, hosts an oracle agent, merges, resets | `seon.competency-cluster-test/a-competency-merge-never-reaches-default` | none | K/O | ready |
| MA-0 (M9, G4, G5) | Merge tools return their errors; a test-only namespace prepares | `my.program-test/merge-tools-return-their-errors` | none | M | ready |
| MA-1 (M7, G1) | A new contracted function arms on S | `seon.cluster.source-test/a-new-function-in-a-new-namespace-prepares-green` | MA-0 | M | ready |
| MA-2 (M10, G2, G3) | Schema keys compared; proofs see branch schemas | `my.program-test/a-branch-schema-shows-in-the-diff` | MA-1 | M | ready |
| MA-3 (M4, G9) | S always created; `:seon.task/candidate`, `:seon.task/unresolved`; S retires on close | `seon.cluster.source-test/a-conflict-stays-on-the-merge-branch` | MA-2 | M | blocked-on-owner (redesign §8.4) |
| MA-4 (M3, G7) | `diff`/`declaration-diff` for `{:seon.task/merge m}` from root | `my.program-test/root-reads-a-proposals-declaration-diff` | MA-2 | M | ready |
| MA-5a (M11) | `take!`/`drop!` resolve unresolved ids; the `unselected-reach` refusal deleted | `my.program-test/take-and-drop-move-one-identity` | MA-3 | M | blocked-on-owner (redesign approval) |
| MA-5b | A merge task's holder evaluates with custody S; repair by `defn` | `my.program-test/root-repairs-a-conflict-by-defn-on-the-merge-branch` | MA-5a | M/B | blocked-on-owner (redesign approval) |
| MA-6 (M5) | Dangling callers in the diff and the accept's integrity | `seon.cluster.source-test/a-renamed-callee-names-its-surviving-caller` | MA-3 | M | blocked-on-owner (redesign approval) |
| MA-7 (M6) | `:schema-holds` on S data and readers | `…/a-retracted-attribute-names-the-head-data-and-readers` | MA-6 | M | blocked-on-owner (redesign approval) |
| MA-8 | Migrations named on the merge task, run on S at prepare and in the accept writer | `…/a-split-attribute-migrates-head-rows-written-after-prepare` | MA-7 | M | blocked-on-owner (redesign §8.1, §8.3) |
| MA-9 | Writer revalidation: a moved head program refuses `:seon.merge/head-moved`; dangling and schema re-checked | `…/a-caller-added-after-prepare-refuses-the-accept` | MA-8 | M | blocked-on-owner (redesign §8.6) |
| MA-10 | Delete the `uncovered` acceptance gate | `…/an-uncovered-carried-function-is-shown-not-refused` | MA-9 | M | blocked-on-owner (redesign §8.5) |
| MA-11 (M1) | `review!`, the dial, `:accepted-by`, `:held`, `:review-run` | `seon.merge-policy-test/auto-accepts-green-and-holds-red` | MA-10 | M | blocked-on-owner (redesign approval) |
| MA-12 | The `:seon.merge/review` proc evaluates `review!` through root's path with no provider | `seon.merge-policy-test/a-proposal-auto-accepts-with-no-provider` | MA-11 | M | blocked-on-owner (redesign §8.2) |
| MA-13 (M2) | `:no-new-red`, unknown on the head counted as red | `seon.merge-policy-test/a-head-test-turned-red-holds` | MA-11 | M | blocked-on-owner (redesign approval) |
| MA-14 (M12) | `decline!` with a reason that messages the agent; `:seon.task/target` | `my.program-test/a-declined-merge-tells-its-agent` | MA-11 | M | blocked-on-owner (redesign approval) |
| MA-15 (M13, G8) | Accept retires a hosted proposer's branch | `my.program-test/accepting-a-hosted-proposal-retires-its-branch` | MA-0 | M | ready |
| MA-16 (G10, TR-02) | Profile diff, prepare and accept; fix the owners to the redesign's §6 targets | `seon.merge-cost-test/prepare-and-accept-within-budget` | MA-1 | M | ready |
| MA-17 (R-01) | Competency T16: a model root repairs a held merge with ordinary tools | `seon.competency-oracle-test/t16-root-repairs-a-held-merge` | MA-5b, X1 | K | blocked-on-owner (redesign approval) |

### 3.11 Competency suite (C, A, D, B, X, Q, R; design: `competency/competency-spec.md` §1-§8)

| Id | Change | Proof | Deps | Group | Status |
|---|---|---|---|---|---|
| C2 | T2-T4 setups, proofs, oracles | `seon.competency-oracle-test/t2-…` to `t4-…` | C1 | K | ready |
| C3 | T5-T8 | `…/t5-…` to `t8-…` | C2 | K | ready |
| C4 | `seon.competency/scores` with merge outcome, review time and bail columns | `seon.competency-test/scores-read-an-oracle-run` | C1 | K | ready |
| D1 | `:seon.cluster.eval/result-reads` from the read forms, recorded by the current settlement owner | `seon.cluster.turn-test/a-form-naming-a-handle-records-its-result-read` | none | B | ready |
| D2 | T14, T14s, T14n | `seon.competency-oracle-test/t14-reads-the-held-result-past-the-cut` and siblings | D1 | K | ready |
| D3 | T14q (a set cut continues) | `…/t14q-continues-rather-than-re-runs` | D2, PR-02 | K | ready |
| B1a | T9h (possible) | `…/t9h-goes-red-to-green` | C1 | K | ready |
| B1b, Q2 | T9 and T15x bail oracles | `…/t9-bail-returns-the-task-with-its-reason`, `…/t15x-bails-on-an-absent-answer` | F7, A1 | K | blocked-on-owner (with F7) |
| A1 | Answer attributes on the agent and `my.agent/answer!`/`answer` with complete contracts (competency spec §4.5) | `my.agent-test/an-answer-is-data-on-the-agent` | none | E | ready |
| Q1 | T15 seeded orders, `comp.price`, oracle | `seon.competency-oracle-test/t15-answers-from-a-query-and-source` | A1, F6 | K | blocked-on-owner (with F6) |
| X1-X4 | T10, T10c, T11, T12 (accepts when the agent names its migration, holds without one), T13 conflict drivers and oracles | `…/t10-holds-on-a-dangling-caller` and siblings | MA-6, MA-7, MA-8, MA-11 | K | blocked-on-owner (with the MA rows) |
| C0 | The gate: `seon.pass-probe/run-model` model pass inside the ordinary row (O) | `seon.pass-probe-test` model row | PB-02b, PB-03, PB-04, S4-06, S4-07b3, RP-10, RP-14, RP-15a | K | ready |
| C7 | Launch function `{task variant n}` with the meanwhile step | `seon.competency-test/a-fixture-provider-run-scores-green` | C0, C4 | K | ready |
| C8 | Smoke T1 on DeepSeek flash (A × REPL), then the matrix | the committed score table | see §4 | K | ready |

### 3.12 Process memory and retention (MEM; the one-test cost is F1-F3)

| Id | Change | Files | Proof | Budget | Deps | Group | Status |
|---|---|---|---|---|---|---|---|
| MEM-01 (16 GB machine) | The operator sets an explicit heap cap of 4 GB (today `MaxRAMPercentage` gives 11 GB on a 128 GB host, 1.3 GB on 16 GB); `:dev`/`:test` aliases agree; replay the memory probe under the cap | `deps.edn`, `script/seon/operator.clj`, `docs/research/agent-platform/2026-10-08/memory/probe.clj` | the probe's 10 SCI + 10 model simultaneous passes complete with heap ≤ 4 GB and RSS ≤ 6 GB, recorded as profile evidence | M | PB-02b | O | ready |
| MEM-02 (task `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads`) | Blocking storage work runs on core.async's virtual-thread I/O seam, not the mixed pool | `src/seon/flow.clj` and the storage callers the task names | `seon.flow-test/storage-io-does-not-grow-the-mixed-pool` (platform threads < 100 under the probe) | M | none | E | ready |
| MEM-03 (task `seon.sci.eval/live-results-are-held-for-the-contexts-lifetime`) | Live result retention policy: which handles a context keeps and when an old one answers gone-value | `src/seon/sci/eval.clj` (`:579-599` result owner) | `seon.sci.eval-test/an-old-result-answers-gone-value-after-the-policy-bound` | M: ≤ 20 MB retained per idle agent | PB-02b | C | blocked-on-owner (the bound changes handle lifetime; recommend: a context keeps its newest 64 result handles, an older handle answers gone-value with its saved shown text, values the agent holds in its own Vars are unaffected) |
| MEM-04 (task `seon.turn/each-trivial-evaluation-retains-two-hundred-kilobytes`) | Diagnose retained old database roots over repeated passes on one agent; fix the holder | named by the diagnosis | the probe's retained-heap row flat over 200 passes | M | S4-07b3 | B | ready |

### 3.13 Raw returns and output bounds (RO; audit: `raw-output/raw-output.md`, `e9a0defec`)

The defect bound is the owner's 2,000 estimated tokens (the audit proposed a default of 4,096; the ruling wins).
Overlaps folded: audit slice 1 (directory) → S1-07a's budget; 3 (profile) → MS-14; 6 (test check) → MS-07, whose
raw value drops its echoed copies; 7 (contract example) → MS-08; 13 (continuations) → PR-02's proof.

| Id (audit slice) | Change | Files | Proof | Deps | Group | Status |
|---|---|---|---|---|---|---|
| RO-2 (2) | Caller sites carry one row per caller; deletion report and unknown-arity reader converted | `src/my/program.clj`, program schema | `caller-sites-carry-one-row-per-caller` (raw ≤ 500, MCP ≤ 550) | none | M | ready |
| RO-4 (4) | Health findings do not copy owner values | `src/seon/health.clj`, health schema | `findings-do-not-copy-owner-values` (three findings raw ≤ 1,500) | none | E | ready |
| RO-5 (5) | Source answers drop the redundant `:doc` | `src/my/program.clj`, program-read tests | `source-retains-code-contract-and-location-once` | RO-2 | M | ready |
| RO-8 (8) | Arm-time bulk declaration `:seon.profile/output-bulk` (reason, bound) on the function's contract; config dials for sampling and the 2,000 bound | `src/seon/instrument.clj`, `src/seon/profile.clj`, `seon.config.eval.edn`, schemas | `bulk-output-policy-is-read-from-the-armed-contract` | none | E | ready |
| RO-9 (9) | Bounded sample counters in the profile cell; the value and laziness unchanged | `src/seon/profile.clj` | `output-observation-preserves-value-and-laziness` | RO-8 | E | ready |
| RO-10 (10) | Sampled scalar deltas settle with the definition's measurement | `src/seon/profile/measurement.clj` | `output-samples-settle-once-with-the-definition` | RO-9, S5-02 | E | ready |
| RO-11 (11) | Health ranks large outputs by sampled excess | `src/seon/health.clj` | `large-output-ranks-by-sampled-excess` | RO-10 | E | ready |
| RO-12 (12; task `seon.render.value/digging-a-live-result-selects-its-render-again`) | Inspecting a live result bypasses render selection | `src/seon/sci/eval.clj` (shown-result), request schema | `a-live-result-dig-bypasses-render-selection` | PR-06 | C | ready |

## 4. Retired and merged ids

| Old id | Now |
|---|---|
| P8, S5-04, S5-04a-j, Q7 | RP-1 … RP-8 |
| P9 | RP-9 |
| P7, S6-03 | RP-11 |
| P14, S6-04b intent part | RP-10 |
| P12, P13 | RP-13, RP-17a-b |
| S6-04a, S6-04b | RP-14, RP-15a-d |
| DATA, competency C6 | RP-16 |
| AR-01 | F1, F4 |
| AR-02 | F5 |
| AR-03 | F4 |
| AR-04 | F9 |
| S6-06b | F7 |
| TR-01 (one-test cost) | F1-F3 |
| M1-M7, M9-M13, G10, R-01 | MA-0 … MA-17 (see §3.10 aliases) |
| MS-opus S1 | RP-7 |
| CS-06, the opening deletions in S4-06 | S4-02b |
| S4-05b, S7-02 | retired by the owner (no input pass; proof status is current state) |
| S4-07b (debug part) | DBG-01 |
| S1-10, competency H1, MS-opus S6, MS-astra 4 | H-01 |
| competency C5 | CS-03b |
| MS-opus S5, MS-astra 3 | P10 |
| MS-opus S10, printer opus S3 | PR-07 |
| MS-opus S2, MS-astra 12 | PR-02 |
| MS-opus S9, MS-astra 9, 10 | S1-06, S1-07a budgets |
| MS-astra 13 | E-SRENDERS |
| printer opus S8 Var case | F9 |
| competency M0-M6 | M0 and MA rows |
| printer opus S1-S20, astra P01-P08, E01-E04 | PR- rows above |
| Q1, Q3, Q5, Q6, Q8-Q18 | folded into the rows and agent-loop.md; Q2b settled (must-reads as signatures with `doc`/`source` operations) |

## 5. Critical path: now → competency suite on DeepSeek flash with real merges and root review

Arrows are hard dependencies; items on one line run in parallel when their files are disjoint. Every step is a row
above.

1. **Now, in parallel:** F0 (C, soundness: proofs go green on stale code until it lands) → PB-02 (C) · S4-01 → S4-02a
   (B) · CS-05, S5-01, S5-06, S6-01, S6-06a, A1 (E) · MA-0 → MA-1 → MA-2 → MA-4, MA-15, MA-16 (M) · E-S1b (F) ·
   MS-00 · PR-07 finishes (out-fix) · C2, C3, C4, B1a, D1 (K/B) · M0 · MEM-02 · RO-2, RO-8.
2. **Owner decisions:** approve the three redesigns and answer their questions; settle S5-07/S6-02 (the only path item
   no redesign covers: RP-14 needs S6-02b); MEM-03's retention bound; E-CUT's split.
3. **Pass budget:** PB-02 → PB-02b → PB-03; PB-04. MEM-01 after PB-02b.
4. **Context** (removes the 405 MB owner): S4-02b → S4-03 → S4-04 → S4-06 → S4-07a → S4-07b1 → S4-07b2 → S4-07b3;
   in parallel CS-01a → CS-01b → CS-01c (with S4-05a); PR-07 → PR-01 → PR-11; then CS-02 → CS-03a → DBG-01.
   **Reset point.**
5. **The pass** (seven commits → one), the redesign's order: RP-0; RP-2 → RP-3 → RP-4, RP-5; RP-1; RP-6 → RP-8, RP-9;
   RP-10 → RP-11 → RP-7 → RP-12; RP-13, RP-16, RP-17a-b; S5-07 → S6-02a → S6-02b → RP-14 → RP-15a → RP-15b-d;
   CS-04 after RP-10; then S6-05 → S6-07a → S6-07b. **Reset point.**
6. **C0 gate:** the model row inside O.
7. **Lookups and help:** S1-06 → S1-07a → S1-07b; S1-08 → S1-09 → H-01 (after F7) → S1-11.
8. **Finishing** (task-finish order): F1 → F2 → F3 (with F7, F8) → F4 → F5 → F6 → F9.
9. **Merge** (merge-acceptance order): MA-3 → MA-5a → MA-5b; MA-6 → MA-7 → MA-8 → MA-9 → MA-10 → MA-11 → MA-12,
   MA-13, MA-14.
10. **Suite:** X1-X4, B1b, Q1, Q2, D2, D3; MA-17 (T16, a model root); C7 launch function.
11. **C8 smoke:** T1, A × REPL, DeepSeek flash, ending in the real merge flow: the review proc's policy accepts or holds,
    and root reviews what it holds.
12. **C8 matrix:** formats A/B (CS-03b) and C (CS-03c), REPL and DATA (RP-16), conflict tasks with a model root
    (MA-17), research and bail; the owner reads the score table.

Off the path but scheduled: printer conversions PR-12 … PR-E4, messages MS-*, raw-output RO-4 … RO-12, the errors cut
E-P1 … E-SDELETE (PR-20, PR-26, MS-08 follow it), MEM-03 after the owner's ruling.
