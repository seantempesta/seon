---
type: research
status: open
created: 2026-09-17
tags: [triage, contracts, audit]
---

# Critical-findings triage across the four private-function audits

Read-only pass at HEAD `6ea932372`. Every `file:line` below was re-located by
function name in the working tree at that commit; the audits cite `7cec8cb57`
and `247bb115b`, so their line numbers have moved and theirs are not repeated.
No file was edited but this one; no JVM, gate or cluster was touched.

Sources read end to end: AGENTS.md §0–§2, the four private-function audits
(`private-function-audit-db-schema-config`,
`-turn-cluster-sci`, `-test-program-issue`, `-my-render-operator`, all
2026-09-17), [audit3-blockers](audit3-blockers-2026-09-17.md),
[live-trial-1](live-trial-1-2026-09-17.md), program-facts PRD
[§1j–§1l](../plan/program-facts-are-the-runtime-prd-2026-09-17.md), and the two
lane summaries in `tmp/orchestrator/`.

**The lane summaries carry no findings.** Both
`tmp/orchestrator/audit2-blockers-summary.txt` and
`tmp/orchestrator/private-contracts-summary.txt` are five-line launch stubs
reading `lane-summary: in-progress`, `session-id: pending` (audit2-blockers) and
session `01a0b02b-e8e7-71a0-bfa0-940311a8e73c` (private-contracts). Neither lane
recorded a result. Nothing in this triage rests on them.

**The headline measurement.** A source walk of `src/` at HEAD finds **2,259
private functions, 17 of which carry `:malli/schema`** (`src/seon/fs/jvm.clj` 5,
`src/seon/db.clj` 3, `src/seon/web/jvm.clj` 2, `src/seon/issue/detect.clj` 2,
and one each in `src/seon/issue.clj`, `src/seon/test/runner.clj`,
`src/seon/shell/jvm.clj`, `src/seon/sci/eval.clj`, `src/seon/edit/jvm.clj`).
§1j's census counted 16 repository-wide. **The contract campaign has not
started**; the only private contracts to land since the ruling are the three
query-codec functions in `seon.db` (`src/seon/db.clj:1271`, `:1285`, `:1415`,
commit `fdf376b7a`). Every critical finding in §1 below is therefore open on its
own terms, not merely unverified.

---

## 1. Critical findings still open at HEAD, ranked

Rank is (blast radius of a silent wrong answer) × (whether a repair unblocks
other repairs). Size: S ≤ 1 function + 1 regression; M = a coherent slice of one
namespace; L = cross-namespace or a mechanism deletion.

| # | Class | Defect in one line | Current `file:line` | Audit | Why critical | Size | The regression that proves the class dead |
|---|---|---|---|---|---|---|---|
| 1 | one-error-predicate | Nine private copies of "is this an error", and `seon.db`'s copy requires `:seon.error/kind`, which most declared classes do not carry | `src/seon/db.clj:160`; copies at `src/seon/operator.clj:54`, `src/seon/plan.clj:84`, `src/seon/call_preparation.clj:116`, `src/seon/note.clj:30`, `src/seon/cluster/message.clj:486`, `src/seon/render/ns.clj:54`, `src/seon/instrument.clj:93` (`flat-error-value?`), `src/seon/schedule.clj:491` (`flat-error?`) | 1 F1, 2 F5, 4 F5 | §1k rules "one predicate (`seon.error/error?`) decides". `seon.error/error?` (`src/seon/error.clj:1589`) asks the projection; the `seon.db` copy asks for a `kind` keyword. Arming contracts on top of the copies buys a green suite and no protection. The load cycle does not force it: `src/seon/db.clj:44-58` already lazily resolves six `seon.error` vars | M | One armed regression handing each of the nine call sites a marker-class error value (no `:seon.error/kind`) and asserting every one refuses it |
| 2 | refused-read-as-row (writer) | `current-run`'s pull refusal is not `nil` and holds no `::closed-tx`, so `require-open-run` classifies a failed read as an **open turn** and the writer transacts against it | `src/seon/turn.clj:310`, consumed `src/seon/turn.clj:317-324` | 2 F2 | The serial writer's own eligibility check. `open?`'s existing contract (an open map with an optional absent key) cannot catch it — the repair is naming the turn identity, not adding a contract | S | Hand `require-open-run` a poisoned database value; assert it refuses with the read's error, never `::no-such-run` and never an open turn |
| 3 | fabricated-findings | The issue detector, on a refused read, reports that every entity map declares no render pair and no components — it manufactures issues | `src/seon/issue/detect.clj:14` (`declared-keys`), `:25` (`component-values`) | 3 T1–T2 | The detector's output is what live agents are told to work from; a generator inventing findings from its own broken read poisons every downstream task, including the §1j issue loop | S | A poisoned database value into the detector yields the read's error, never a subject vector |
| 4 | the-recorder-destroys-its-own-record | `agent-exists?`/`entity-exists?` answer `true` on a refused read (`some?` of an error map), so the fault transaction carries an unconfirmed lookup ref and fails whole; `recurrence` throws `ClassCastException` into the committer | `src/seon/error.clj:1342`, `:1354`, `:1378` | 3 F1–F3 | `agent-exists?`'s own docstring names this exact failure mode as the one the fault path may not have. Nothing is diagnosable once the recorder fails | S | Poisoned database into each of the three; assert the flat error, and that `commit-fault!` still records something |
| 5 | outcome-unknown-misclassification | A refusal thrown through the writer as a marker class misses the verbatim arm and is relabelled `:seon.db/unknown-failure` with `:seon.db/transaction-outcome-unknown true` | `src/seon/db.clj:3707` (`transact-call`), arms at `:3745-3763` | 1 F2 | The named "absence read as health" class in its worst form: a precise refusal becomes "we do not know whether the transaction happened", six lines after the code knew exactly what it refused. Blocked behind #1 (the `kind` test at `:3750`) | S after #1 | Throw a marker-class refusal through the writer; assert the value returns verbatim and no `transaction-outcome-unknown` datom is produced |
| 6 | error-as-budget | `max-episode-runs` returns the first query's error map through `or`; `opening-deferred?` then reaches `>=` and throws `ClassCastException` into the turn loop, twelve lines from a public sibling that checks correctly | `src/seon/turn.clj:2637`, `:2658` | 2 F3 | Decides whether an agent opens a turn at all, therefore whether a **paid** model call happens; and AGENTS §1 forbids a throw into the loop | S | One pair, one poisoned read: assert the flat error reaches `next-agent-work`, no throw |
| 7 | ownership-fails-open | `owned-step-eid!`'s `db/q` error map is truthy, so an unreadable database reports another agent's step as owned; `next-position` does `(long <error map>)` and throws on an agent's `my.plan` write; four eid readers feed error maps into transaction data as refs | `src/seon/plan.clj:502`, `:520`, `:104`, `:111`, `:117`, `:124` | 2 T5 | Agent-facing and security-relevant: it fails **open** on an ownership question, and the refusal must be a value, not a throw | M | Poisoned database: assert ownership refuses, and that no error map reaches transaction data as a ref |
| 8 | boot-recovery-no-op | `recover-runs!`'s `open-runs` error map is `mapcat`'d, so boot recovery recovers nothing and reports success | `src/seon/cluster.clj:2517` | 2 T3 | The function whose entire job is to notice interrupted work reads absence of signal as health. Arm on a scratch cluster: under `:panic` this converts a degraded boot into a failed one (audit 2 F7) | S | Poisoned read at boot recovery: assert a refusal, never a silent `nil` |
| 9 | error-keys-become-program-facts | `remaining-definition-facts`'s `when-let` accepts the truthy error map; `dissoc` then yields a "definition" made of `:seon.error/*` keys that the redefinition path publishes | `src/seon/sci/eval.clj:720` | 2 T4 | Error-as-a-row reaching **durable** facts is strictly worse than reaching a boolean, and it lands in the program graph the whole mission rests on | S | Poisoned read: assert no `:seon.error/*` key can appear in definition attributes |
| 10 | lost-settlement | `settle-value!`/`interrupt!` bind the `db/transact!` report into `:seon.effect/transaction` unchecked; a lost settlement leaves an effect pending forever with nothing to notice it | `src/seon/effect.clj:528`, `:571` | 2 T6 | The capability boundary's terminal fact. Nothing downstream distinguishes "settled" from "the write was refused" | S | Refuse the settlement write; assert the effect reports a refusal, not a settled value |
| 11 | delivery-fails-open | `agent-exists?` answers `true` on a refused read, so a message is delivered to a recipient nothing declares | `src/seon/cluster/message.clj:140` | 2 T7 | One line, fails open, and the identical shape as #4 in a second owner — evidence the repair belongs at the predicate (#1), not per call site | S | Shared with #4's regression |
| 12 | divergence-suppressed | `(boolean <error map>)` is `true`, so a failed history read asserts "this run wrote it" and suppresses the concurrent-divergence refusal on a declaration write | `src/seon/turn.clj:1101` | 2 T8 | A safety check that silently stops checking under exactly the conditions it exists for | S | Poisoned history read: assert the divergence refusal still fires |
| 13 | every-identity-reads-missing | `(into #{} (db/q …))` turns an error map into a set of `MapEntry`, so every process identity reads as missing and is re-minted; activation refuses with a fabricated missing-facts list | `src/seon/cluster.clj:1073`, `:1458` | 2 T9 | Produces the exact mis-report the class note already records for `seon.config/effective-in`, at boot and at activation | M | One regression per shape, not per function: `(into #{} <error>)` must be unconstructable at these seams |
| 14 | gate-set-shrinks-silently | `declared-reference-edges` returns `db/q` raw into `gate-set-in`; a refusal shrinks the gate set and the run still reports green | `src/seon/fn.clj:1356`, consumed `:1368`, `:1387` (`gate-sets`) | 3 T6, audit3-blockers item 3 | "A green run that tested less than it claimed is the most expensive lie this system can tell." **The lane's draft was never landed** — see §2 | S | `a-refused-reference-read-refuses-gate-set-derivation` (the reverted draft's own regression), re-based on the rewritten `gate-sets` |
| 15 | indexer-writes-error-keys | `reconcile-tx-in` skips exact replacement and `published-index-rows` writes `:seon.error/message` into published index rows on a refused read | `src/seon/fn.clj:2510`, `:2564` | 3 T7–T8 | This is the publication path the edit hook runs on every edit; a red here stops adoption for every lane, so it is an orchestrator-window repair | M | Poisoned read through `index!`: assert the publication refuses rather than publishing error keys |
| 16 | resolved-instance-still-open | `record-latest-tx` — the class note's "resolved" instance — checks `previous` and leaves three reads in the same body unchecked; `failure-replacement-tx` can emit `[:db.fn/retractAttribute nil …]` into a gate's recording transaction | `src/seon/test/runner.clj:2713`, `:2551` | 3 F4–F5 | A per-instance fix left the class alive inside the function that proved it. Every cold gate's recording is in the blast radius | M | One regression asserting a refused read never produces a `nil`-keyed retraction in recording transaction data |
| 17 | two-transports-one-failure | `seon.config/refuse!` throws `ex-info` whose data carries no `:seon.error/message`, so it is not a legal error value under either predicate; nine callers, one of them (`apply!`) agent-reachable, while its sibling `effective-in` returns a value for the same cause | `src/seon/config.clj:202`, `:450`, `:586` | 1 F5, F6 | AGENTS §2.4: nothing throws into the loop. Converting this one function to a value removes 8 of the domain's 32 throwers | M | `seon.config/apply!` with a refusing read returns a `:seon.config/refused-error` value; no exception crosses the agent boundary |
| 18 | declarations-read-as-a-refusal | `read-declarations`' result is consumed as the declaration table with no error branch at `pull-call` and four sites in `replay-read`; every decoded value then silently loses its declared decoding | `src/seon/db.clj:1089`, consumed at `:1926` (`pull-call`) and inside `replay-read` `:875` | 1 F4 | The read path all three overnight instances traversed | M | Refuse the declarations read; assert decoding refuses rather than returning undecoded values |
| 19 | no-matching-clause | `replay-read`'s `case` over `:seon.db/read-operation` has four arms and **no default** (`src/seon/db.clj:877-912`), so a fifth value or `nil` throws `IllegalArgumentException` naming nothing — into the since-diff that runs before every agent turn | `src/seon/db.clj:875` | 1 F3 | Identical to an already-archived defect in the print seam; an input contract naming `:seon.db/read-operation` is most of the repair | S | An unknown read operation returns a typed refusal naming the attribute |
| 20 | projection-from-an-empty-table | `derive-projection-from-database`'s refusal, read as a projection, makes every downstream `storable-attribute-in?` answer from an empty table | `src/seon/schema.clj:2541` | 1 T6 | Absence read as health at the schema authority, under the law (§2.1) that every other contract's key names rest on | S | A refused projection derivation refuses; it never yields a projection with no forms |
| 21 | operator-outside-the-graph | `seon.fn/source-roots` is `["src" "test"]`, so `script/seon/fresh_operator.clj` (134 private fns) and `resources/seon/operator/state.clj` (33 private of 80) hold **no** contractable, armable, or `my.program/breaks`-visible rows | `src/seon/fn.clj:27-29` | 4 F3 | PRD §0a's unbreakable-connection guarantee has a hole exactly where cluster lifecycle lives, and `bin/test`'s changed-since-green selection cannot reach a test through an operator change | L | A test asserting a known operator function appears in the graph and that `my.program/callers` finds a caller inside it |
| 22 | render-and-derivation-reads | Twelve agent-facing readers consume a `seon.db` read before any check: `ambient-database-value` (poisons every downstream read in the walk), `model-details`/`rendered-model` (wrong configuration rendered into agent context), `installed-attributes` (an entity rendered with no attributes), `document-specs`/`declared-entity-ids`/`entity-documents` (`rebuild!` deletes the index before rebuilding from them), `schema-row` (**memoised**, so one failure persists for the page), `task-rows` ×2 (scheduled and maintenance work silently ceasing to be derived) | `src/seon/render.clj:1661`, `src/seon/ai.clj:169`, `:175`, `src/seon/render/walk.clj:65`, `src/seon/search.clj:180`, `:255`, `:237`, `src/seon/render/ns.clj:104`, `src/seon/schedule.clj:205`, `src/seon/maintenance.clj:239` | 4 T1–T8 | Each is one `or`/`get`/`some->` away from correct; together they are the agent-visible face of the class | M | The shape regressions of §3, applied at these ten sites |

**Two prerequisites the order above assumes.** #1 lands before #5, #11 and #4's
shared regression, because those checks are spelled with the predicate. And
arming anything at #8, #9 or `seon.sci.eval/acquire-program!`
(`src/seon/sci/eval.clj:1615`), `load-core-namespaces!` (`:1194`),
`record-acquisition-refusals!` (`:1564`) happens on a scratch cluster: the
`:panic` reporter (audit 2 F7) turns a degraded boot into a failed one, and the
default cluster is the owner's window.

**Not in the table, but open and adjacent.** The live trial's blocker — [an
agent turn proc dies on every pass and oversight still reports it
armed](../../../seon/issues/an-agent-turn-proc-dies-on-every-pass-and-oversight-still-reports-it-armed.md)
— is the reason no §1j contract can be proven by a live agent turn today. It is
filed, and it is the same disease at the observation layer: a dying proc that
every monitor reads as healthy.

---

## 2. What the audits raised that is fixed at HEAD

| Finding | Where it was | State at HEAD | Fixing commit |
|---|---|---|---|
| `:seon.fn/sym` is a string while `:seon.ns/name` is a symbol (audit 1 F7, audit 3 F15, trial defect 3) — half-landed symbol ruling producing silently empty joins | `resources/seon/schemas/seon.fn.edn` | **Fixed.** `:sym` is `[:qualified-symbol {:seon.db/identity true …}]` (`resources/seon/schemas/seon.fn.edn:172-175`); `:seon.test/sym` likewise (`resources/seon/schemas/seon.test.edn:56-60`); `:seon.ns/name` `[:symbol …]` (`resources/seon/schemas/seon.ns.edn:5-9`) | `0e8f7d323` |
| `seon.db/q` refuses a literal/scalar symbol in a value position and leaks storage strings from collection bindings (audit 4 finding 1, its stated blocker) | `src/seon/db.clj` query codec | **Repaired.** The Datalog value positions now encode through the write codec: `query-binding-values` (`src/seon/db.clj:1271`), `query-input-values` (`:1285`), `encode-query-request` (`:1415`) — the three contracted privates in the namespace — with regressions `query-symbol-values-use-the-declared-codec-in-every-binding-shape` and `activation-requests-join-the-symbolic-program-population` | `fdf376b7a`, `a0b8c1ed0`, `925affcd3` |
| Instrumentation must arm private contracted functions (§1j prerequisite) | `src/seon/instrument.clj` | **Already true and verified.** Collection walks `ns-interns` (`src/seon/instrument.clj:41`, `:81`), private included; verified through canonical worker arming | `74a389ce1` (and pre-existing per `b91babf3b`) |

Nothing else the audits raised is fixed. In particular, **audit 3's blocker
lane landed nothing**: `audit3-blockers-2026-09-17.md` (status `incomplete`)
records that its items 1 and 2 were unimplemented and item 3 existed only as an
untested working-tree draft, restored by an owner-authorised checkout. That
draft is absent at HEAD — `src/seon/fn.clj:1356` has no contract and no
`error/error?` call, and `test/seon/fn_test.clj` has no
`a-refused-reference-read-refuses-gate-set-derivation`. Its base has also
changed: `gate-sets` was rewritten by the reset batch
(`src/seon/fn.clj:1387-1392`), so the draft must be re-derived, not re-applied.

---

## 3. The refused-read-as-row class at HEAD

Re-derived by a source walk of `src/` at HEAD (top-level `defn-` forms; body =
lines to the next top-level form; "reads" = a call to `db/pull`, `pull-many`,
`q`, `entity`, `datoms` or `transact!`; "checks" = any of `:seon.error/`,
`error?`, `error-value?`, `flat-error`, `diagnostic` appearing in the body).

- **285** private functions in `src/` call a `seon.db` read or write.
- **183** of them contain no error token anywhere in the body — they cannot be
  checking.

Grouped by the repair shape audit 2 F6 and audit 4 finding 2 name (first shape
matched; "other" is the residue whose consumption is a plain bind-and-use):

| Repair shape | Count | What a refused read becomes |
|---|---:|---|
| `(when-let …)` / `(if-let …)` / `some->` on the read | 23 | the truthy error map proceeds as "the row" |
| `(into #{} (db/q …))` | 16 | a set of `MapEntry`; every member reads as absent |
| `(nil? …)` / `(some? …)` as a health test | 10 | "present" / "exists" / "not read-only" |
| `(:key (db/pull …))` keyed into | 8 | `nil`, reported as absence, never as unreadable |
| `(boolean …)` / `(seq …)` / `(count …)` | 5 | a refusal is `true`, or a non-empty collection |
| `(long …)` / `(inc …)` / `(>= …)` arithmetic | 4 | `ClassCastException` into the caller |
| other (bind and use) | 117 | varies by consumer |

Heaviest files: `src/seon/turn.clj` 28, `src/seon/plan.clj` 12,
`src/seon/cluster.clj` 12, `src/seon/render/transcript.clj` 12,
`src/seon/render/web.clj` 11, `src/seon/db.clj` 9, `src/seon/bootstrap.clj` 7,
then `src/seon/fn.clj`, `src/seon/issue.clj`, `src/seon/cluster/message.clj` 6
each.

This count is a **superset** of any one audit's, and not comparable
member-for-member: it spans all of `src/` (the audits partitioned it into four
domains), and its "checks" test is a token scan, so a function mentioning an
error for an unrelated reason counts as checked. It is a point-in-time
derivation, not a maintained mirror; the durable derivation is the Datalog
census each audit records, now that the symbol codec answers truthfully.

---

## 4. The easy pool

§1j's mined-issue pool for live agents — private, uncontracted, obvious
contract, reaching tests exist — as each audit left it.

| Audit | Selection rule it derived | Population | Named, ready-to-hand rows | Where the list lives |
|---|---:|---:|---|---|
| 1 (db, schema, config, store) | none derived; the note's §5 is ten criticals, not easy work | 300 private, 0 contracted | 0 | [audit 1 §3](private-function-audit-db-schema-config-2026-09-17.md) lists all 300 one line each, with `DBREAD`/`DH`/`ERR`/`GUARD`/`THROW`/`NIL?` flags — the unflagged rows are the easy pool, un-counted |
| 2 (turn, cluster, sci, env, effect, plan) | none derived | 357 private with no database call | 0 | [audit 2 §3](private-function-audit-turn-cluster-sci-2026-09-17.md) lists them per namespace; §6 appendix lists the 113 database-touching ones |
| 3 (test, fn, program, issue, error, instrument, id) | private, uncontracted, **no** database call, **exactly one** caller, **exactly one** reaching test via `seon.fn/gate-set` | 268 meet the first three conditions | **17** named with their single caller | [audit 3 "Easy first issues for live agents"](private-function-audit-test-program-issue-2026-09-17.md) |
| 4 (`my.*`, render, operator, ai, schedule, search) | contract obvious from one call site, few callers, existing reaching tests | 630 private, 0 contracted; 287 flagged | **10** named with the reason each is easy | [audit 4 §5](private-function-audit-my-render-operator-2026-09-17.md), with the flagged inventory in its appendix B and per-file counts in appendix A |

**27 rows are ready to hand out today**, and audit 3 names the best first slice:
the four `seon.test.cache` members (`alive?`, `compatible-changes`, `reap!`,
`referenced?`) — one small file, a closed cycle, one reaching test, no database
call — given to **one** agent as a single slice, not four agents one each.

Two cautions the audits attach, both still true: a one-caller contract is read
from the call site and never guessed from the name (return it to the queue
rather than widen to `:map`), and the diagnostic writers
(`seon.test.runner/liveness-diagnostic`, `thread-info-text`) must not have their
outputs narrowed — AGENTS §2.4, a diagnostic that omits something lies.

---

## 5. Disagreements, and what I could not verify

- **Audit 2's F8 is wrong, and was wrong when written.** It states that
  `resources/seon/schemas/seon.env.edn` "registers neither error shape" for
  `seon.env/construct`, making the missing registration a prerequisite for
  contracting it. At HEAD the file registers
  `:seon.env/incomplete-environment-error` (`:88`) and
  `:seon.env/invalid-member-error` (`:94`), both `{:seon.error/class true}` with
  render pairs, and `src/seon/env.clj:202`, `:209` carry the class markers.
  `git show 7cec8cb57:resources/seon/schemas/seon.env.edn` contains both keys,
  and the registering commit is `ff182e7e9` (2026-08-13), five weeks before the
  audit. Audit 2's prerequisite **P1 should be struck**; `construct` can be
  contracted directly.
- **Audit 4 counts seven private `error-value?` copies; there are nine.** The
  two it omits are near-copies under different names:
  `seon.instrument/flat-error-value?` (`src/seon/instrument.clj:93`) and
  `seon.schedule/flat-error?` (`src/seon/schedule.clj:491`, which audit 4 does
  report separately as its finding 5 without counting it in F5's table).
- **The symbol-codec repair is landed; its issue note is not updated.**
  [collection-attribute-query-bindings-leak-edn-storage-values](../../../seon/issues/collection-attribute-query-bindings-leak-edn-storage-values.md)
  still reads `status: open`, `severity: friction`, while audit 4 argues the
  severity belongs at blocker and the codec has since been repaired. I could not
  run a query to confirm the leak shape is fully closed — no JVM was permitted
  in this pass — so whether that note is resolved, partially resolved, or still
  open on a third shape is **unknown**.
- **Audit 4's finding 3 is confirmed by construction but not by the graph.**
  `seon.fn/source-roots` is `["src" "test"]` (`src/seon/fn.clj:27-29`), so
  `script/` and `resources/` cannot be indexed. The audit's row counts (32 of 80
  for `seon.operator.state`, zero for `seon.fresh-operator`) came from a live
  query I could not repeat; the source counts are 80 `defn`/`defn-` with 33
  private in `resources/seon/operator/state.clj`, and 134 private in
  `script/seon/fresh_operator.clj`.
- **`audit3-blockers`' items 1 and 2 are not named anywhere in that note.** It
  records only that they "remain unimplemented" and shows item 3's diff. Read
  against audit 3's triage they are most likely `seon.issue.detect/declared-keys`
  and `component-values` (ranks 1–2 there, and ranks 3 in this table), but the
  note does not say so and I could not confirm it — **unknown**.
- **Every consequence in §1 is read from source, not observed.** No test was
  run, no contract armed, no cluster touched. The live evidence that the class
  fires in practice remains the three overnight instances recorded in
  [the class note](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md)
  and the audits' own probes.
- **The `seon.db/database-value` versus `seon.db/db` question is settled and
  harmless**: `:seon.db/db` is a registered alias of `:seon.db/database-value`
  (`resources/seon/schemas/seon.db.edn:29`), so the audits' contract candidates
  and the newer contracts (e.g. `seon.turn/turns-left`) name the same shape.
