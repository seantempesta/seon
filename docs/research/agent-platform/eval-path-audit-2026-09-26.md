---
type: audit
lane: eval-path-audit-opus
date: 2026-09-26
status: audit and design only, no source edits. Line numbers are HEAD b6660a34b (eval.clj unchanged since 35171776f)
live evidence: default pid 86089 (until 21:35Z), then pid 89341, JVM mode, read-only
---

# The eval path: one agent form, stage by stage, and what to delete

**What exists.** One agent form passes through about 40 functions in five namespaces. The largest of them are
`seon.sci.eval/evaluate` (eval.clj:4237, 557 lines) and `seon.turn/call-turn` (turn.clj:4483, 350 lines).
What a form *defines* is decided in three places: the reader's head check, clj-kondo analysis after
evaluation, and a runtime type test (`program-value?`). Measured live today, those places disagree on three
of four plain `def`s. **Principal approach of the fix:** clj-kondo's analysis of the form (`seon.fn/source-rows`)
becomes the only authority on what a form declares, and it runs *before* evaluation. The entry gate then
refuses before evaluation, as CLAUDE.md already requires ("entry defaults to `:gate`, refusing before
evaluation"). Admission becomes a lookup by name, with no diff of SCI's namespace tables. Restoration covers only
the declared names. Each stage becomes one function of data under its schema.

## 0. The owner's question: what is `program-value?` doing?

`program-value?` (eval.clj:561-568, 8 lines) answers "is this Var a program definition?" from the **runtime
value**: `(fn? root)`, or `:test` in the Var's metadata, and false when the root is the `absent-intern` sentinel
(eval.clj:502). Three callers use it:

1. `program-definitions` (eval.clj:583-605): which Vars the form changed count as definitions, including the
   `::prior-program?` flag for "a function replaced by data".
2. `program-change` (eval.clj:651): a prior program Var that is no longer a program value is `::unmatched`.
3. `unadmitted-notice` (eval.clj:1021-1032): the `;; NOT ADMITTED` line on a failed form.

It is a **second authority for program identity, and it contradicts the first.** clj-kondo, through
`seon.fn/function-definition?` (fn.clj:259-272), treats every `def`, `defonce` and `defmulti` as a program
declaration. Live comparison on pid 89341 (`seon.fn/source-rows` on default's value vs `program-value?`):

| form | clj-kondo row (fn.clj:1528) | `program-value?` | what happens today |
|---|---|---|---|
| `(defn f "d" {:malli/schema …} [x] …)` | `user/f`, spec, span [0 63] | true | admitted |
| `(def m {:a 1})` | `user/m`, arglists `()` | **false** | private def; the same text in a file is a program row |
| `(def a (atom 0))` | `user/a` | **false** | private def; row in a file |
| `(defmulti mm :k)` | `user/mm` | **false** (MultiFn is not `fn?`) | private def; row in a file |
| `(def g (partial + 1))` | `user/g`, span [0 21] | **true** | treated as a definition, then refused as uncontracted |
| `(let [y 1] (defn h …))` | `user/h`, span [11 31] | true | admitted when the form succeeds |
| `(do (defn a1 …) (defn a2 …))` | both | true | refused at `a2`: one row per evaluation (task e32aa6d5b3eb) |
| `(inc 1)` | none | n/a | kondo not run (no definitions) |

Analysis cost was 12–25 ms per form, warm, in all nine probes, including `(inc 1)` with no rows.

So the REPL and publication disagree about what the program is. Every `def` of data is a row when a file
publishes it, and a private binding when an agent evaluates it. The refusal text at eval.clj:873-874 also names
"a point-free def such as (def f (comp ...))" as unindexed. That is stale: kondo does analyze it (span [0 21]
above), so the form matches and is then refused for its missing contract, a different refusal from the one the
text describes.

The sentinel exists because the diff compares Var roots, and a Var can be unbound. SCI already answers that
directly with `sci.vars/hasRoot`. The sentinel appears at eval.clj:502, 540, 553-554, 567, 581, 602, 707, 1078,
1080, 1893 and 3903–3904.

**Today's four breakages were all interactions between these derivations:**
- **do/defn arming order.** Admission ran once after the whole form (fix-do-defn landing).
- **`program-value?`'s contract demanded `:seon.sci.eval/var`, which the function never reads.** Confirmed live
  on pid 86089 at 21:35Z: every call with a binding-shaped map refused. Pid 89341 now carries
  `[:=> [:cat :seon.sci.eval/binding] :boolean]`, so this one is adopted.
- **The settlement read an unwritten evaluation.** Task `agent-form-writes-name-an-unwritten-evaluation`.
- **The MCP bridge read committed state instead of the settlement** (§2, D9).

## 1. The path for one agent form (HEAD b6660a34b)

| # | stage | owner function(s) | file:line (lines) | what it does |
|---|---|---|---|---|
| 0 | entrance | `run-form` → `seon.cluster.agent/evaluate-source!` → `submit-source!` → graph request port → `turn-step` → `call-turn` | script/seon/dev/mcp.clj:400 (~60); agent.clj:757, :663; turn.clj:4483 (350) | builds the run open as a value and offers it to the agent's graph |
| 1 | read / segment | `seon.cluster.reply/code-sources`, `seon.sci.reader/read` | reader.cljc:788 | reads the reply into events (form, span, position) |
| 1b | **re-read** | `one-event` | eval.clj:1110 (49) | reads the source again unless `:seon.sci.eval/event` is supplied |
| 1c | **reader declaration facts** | `declaration-facts`, `function-declaration`, `test-declaration`, `schema-declaration`, `namespace-unmap` | reader.cljc:364 (21), :252 (55), :307 (11), :332, :352 | a head check on `defn`/`deftest`/`register!`/`ns-unmap`: **definition derivation #1** |
| 2a | pre-eval admission (schemas, ns, deletions) | `row`, `declared-row` | eval.clj:469 (17), :3776 (79) | schema registration delta, ns rows, deletion rows |
| 2b | form loop | `evaluate-sources` | turn.clj:4866 (160) | per form: build request, call `evaluate`, gate, disposition, bind |
| 3 | evaluate | `evaluate` | eval.clj:4237 (557) | arm, **acquire the program (4533–4558)**, capture, `sci/eval-form`, admit, unmap row, print, shape |
| 3b | top-level `do` | `top-level-evaluation`, `top-level-do?` | eval.clj:1043 (30), :1034 | each child evaluated, then admitted |
| 4 | intern diff | `context-capture`, `turn-interns`, `program-definitions`, `removed-interns`, `bindings`, `unmap-row` | eval.clj:829, :504 (40), :583 (24), :686, :1073, :3855 (58) | walks SCI namespace maps before and after; **`program-value?` classifies: derivation #2** |
| 5 | post-eval admission | `admit-program-change!` → `program-change` → `analyzed-rows` → `seon.fn/source-rows` → `runtime-analysis-batch` → clj-kondo; `definition-row`; `missing-contract`; `install-function-contract!` | eval.clj:958 (63), :626 (47), :904, fn.clj:1528, :943; eval.clj:917 (41), :2379, :1347 (27) | **clj-kondo: derivation #3**; matches Vars to rows by byte position; refuses; **arms (#1)** |
| 5b | restoration on refusal | `context-restoration`, `program-restoration`, `apply-restoration!`, `restore-or-unavailable!`, `execution-unavailable`, `var-step` | eval.clj:842, :711 (46), :757, :800, :771, :701 | undoes the whole namespace-map change |
| 6 | test entry gate | `seon.turn/gate-function-install` → `evaluate-candidate` → `disposable-copy` → `install-candidate-function!` (**arms #2**) → `run-candidate-test!` (**calls `evaluate` again**) → `accept-candidate!` (**arms #3**) / `refuse-install` | turn.clj:3352 (74); eval.clj:4969, :4921, :4794, :4892, :4814, :4835 (224 total) | a second evaluation path for tests (task d653ed4b633f) |
| 7 | print | `shown-result` → `render.value/prepare` → `evaluated-source` → `evaluate` (recursive); `floor-text`; `success-evaluation`, `failed-evaluation`, `unrun-evaluation` | eval.clj:4018 (57), :3968 (50), :3939, :4075 (44), :4119 (36), :4181 (37) | the one print; also **binds the handle (bind #1)** |
| 8 | record / settle | `disposition-rule-error`, `bind-result!` (**bind #2**), `settle-batch!` → `evaluation-terminal-data`, `row-tx` (canonicalizes the row again through `program/declaration-row`), `evaluation-facts` | turn.clj:4845, :5010, :3774 (98), :3570 (124), :1258 (133), :104 (110) | one transaction per run |
| 9 | install | `committed-row?` → `install-evaluated-rows!` → `transfer-evaluated-roots!`, `install-row!` (**arms #4**), `installation-covers-program-change?` | eval.clj:1474, :2003 (76), :1912, :1703 (201), :1950 (53) | copies the fork's roots into the base ctx, re-arms, advances the snapshot |
| 10 | reply | reply port → `evaluate-source!` → MCP `run-form` **reads `@connection`** → `seon.render.transcript/history-entries` + `seon.eval.drive/run-receipts` | mcp.clj:445–457 | re-derives the answer from committed state |

eval.clj by concern (sums of top-level forms): admission and diff 467; restoration 162; reader-row, unmap and
namespace rows 267; settlement install 525; candidate gate 224; doc/dir 303; `evaluate` and its evaluation
shapes 820; print 153; loaded-identity 116; context acquisition, base ctx and forks 2079–3775 (**1,697 lines**,
which includes doc/dir). The per-form path proper is about 2,600 lines of eval.clj, plus about 1,300 in turn.clj.

## 2. Duplicates, sentinels, special cases, second paths

Each finding names the one owner it should have and what gets deleted. Marks: **L** = confirmed live;
**S** = read from source.

| # | finding | evidence | one owner | deleted |
|---|---|---|---|---|
| D1 | **What a form defines is derived three ways** (reader head check, clj-kondo, `program-value?`), and they disagree (§0) | L (table §0); reader.cljc:252–316; eval.clj:561; fn.clj:259 | `seon.fn/source-rows` rows, before evaluation; one predicate over the row decides "program row" identically for files and REPL | `program-value?`, `absent-intern` uses, `held-intern`, `same-intern-value?`, `turn-intern-values`, the `::prior-program?` logic, `function-declaration`/`test-declaration` (reader.cljc ~70), the `read-only-evaluation?` re-derivation (turn.clj:2051); ≈230 lines |
| D2 | **Positional matching of Vars to rows**: byte offsets recomputed from SCI `:line/:column` (`source-offset`, eval.clj:607) against kondo `form-span`, just to connect two derivations of one fact | S; eval.clj:607–672 | name lookup: a declared row's symbol resolves to its Var (`sci/resolve`); `:sci/rebound` still marks a later root write | `source-offset`, most of `program-change`; ≈60 |
| D3 | **Admission after evaluation, so it must restore**: `context-capture` snapshots every namespace map and generation-owned intern per form; five functions plan and apply restoration | S; eval.clj:701–861 (162) | the entry gate before evaluation (the CLAUDE.md law); restoration = prior roots of the *declared names only*, known before the form runs | `program-restoration`, `apply-restoration!` (most), `removed-interns` walk, whole-map capture; ≈120 net |
| D4 | **The contract is armed up to four times per defn**: admission (eval.clj:1014), the gate's copy (:4810 via :4994), `accept-candidate!` on the agent ctx (:4828 → :4810), settlement install (:1817) | S | arm once, when the declaring child has evaluated; install carries the armed root (it already transfers roots, :1912) | three arming call sites and `install-candidate-function!` (19) |
| D5 | **The projection is advanced at five sites**, and one result is discarded: `definition-row` builds `projection-with-function-contract` and throws the value away (eval.clj:943–945); `declared-row` (:3823); `evaluate` (:4727–4739); `install-evaluated-rows!` (:2035–2055); `install-candidate-function!` (:4800) | S | one `row → projection` function (the writer's, `seon.schema`), called once per admitted row | four copies; ≈50 |
| D6 | **"Did this context's program move?" is answered three ways per form**: `trusted-evaluation`/`call-detecting-program-change` over the `loaded-identity` atom (eval.clj:4218, :1581, :1556); `acquired-database?` + `loaded-program-current?` over `program-identity-cache` (:3488, :3470, :3281); `installation-covers-program-change?` after settlement (:1950) | S | the loaded identity (JVM) check stays (CLAUDE.md "compares the loaded program identity at start and end"); the branch-program check is Datahike's commit/revision identity, compared once at the turn fork (`fork-for-turn`), not inside `evaluate` | acquisition block inside `evaluate` (4533–4558, 26), `loaded-program-current?`, `installation-covers-program-change?` once install stops needing it (option C) |
| D7 | **`evaluate` acquires the program mid-form** (4533–4558) and builds the "PROGRAM CHANGE replaced your definition" notice there | S | the context stage (`fork-for-turn` / turn open): a form evaluates in a world it was handed | 26 lines in `evaluate`; notice moves with it |
| D8 | **The result handle is bound at three sites**: `shown-result` (eval.clj:4053), `evaluate-sources` (turn.clj:5010, commented "ONE BIND SITE"), the error pair via `::bind-result!` (error.clj:512) | S | the print stage (`shown-result`), which is the one that needs the handle | turn.clj:5008–5010, error.clj's callable indirection |
| D9 | **The MCP reply reads the connection, not the settlement**: `run-form` derefs `@connection` after `evaluate-source!` and re-derives responses from history (mcp.clj:445–457). Under fire-and-forget durability (fcee85161) that read can precede the receipts; two lanes saw `events []` today | S + two landings (fix-do-defn, perf-admit-tests) | the reply port's own report (the settlement's `:db-after`), which one-commit B already answers | the second read; the bridge prints the saved observation from the reported value |
| D10 | **The source is read up to five more times** after the plan's read: `evaluable-source?` (turn.clj:2419), `read-source` (:3168), `source-events` for `source-key`/`read-only-evaluation?` (:1929, :1935, :2037), `one-event` in eval (:1110, 1115), `install-row!` replays test and deletion source (:1850, :1875), plus the clj-kondo parse. CLAUDE.md: "A form is parsed once and evaluated once" | S | the plan's events and the analysis rows, carried on the evaluation | `one-event`'s second read on the agent path; turn re-reads |
| D11 | **A second evaluation path for tests**: `run-candidate-test!` re-evaluates a file test's source through `evaluate` in a disposable copy (eval.clj:4892), with its own arming and install; tests do not resolve their requires (task d653ed4b633f) | S + task | `seon.test/run` on a disposable branch (the path agents and humans already use: "Run tests as agents run") | `evaluate-candidate`, `disposable-copy`, `run-candidate-test!`, `candidate-test-result`, `install-candidate-function!`, `accept-candidate!`; `gate-function-install` shrinks to a request; ≈250 |
| D12 | **Dead branch**: `declared-row` applies `with-contract-facts` when `base-declared-row` has `:seon.fn/spec` (eval.clj:3839–3849), but `row` (:469) only makes schema, ns and deletion rows, none of which carries a spec | S | n/a | 11 lines |
| D13 | **Dead parameters**: `bindings` takes six arguments and reads two (eval.clj:1074); both callers compute `(built-in-calls)` for nothing (:4489, :4758) | S | n/a | the args and the observer call |
| D14 | **Two shapes of one terminal value**: `success-evaluation` (44) and `failed-evaluation` (36) copy the same 12-key `cond->`; `unrun-evaluation` (37) is a third | S | one constructor over `{value, error?}` | ≈50 |
| D15 | **Presentation inside the evaluator namespace**: doc/dir contract rendering (eval.clj:2328–2650, 303 lines; task e5af812dbf95) | S | a doc owner (render pair or `seon.doc`) | moved, not deleted (0 net) |
| D16 | **Recording inside install**: `record-acquisition-refusals!`/`record-refusals-once!` (eval.clj:2705, :3529) write faults during acquisition with an LRU "once" cache (a stamp-like dedup) | S | the fault writer's own signature dedup | ≈60 (option C) |

Not counted as duplicates, because they are sanctioned: the print stage calling `evaluate` for a render's
REPL entries (`evaluated-source`, CLAUDE.md "the one REPL path evaluates each form read-only"), and
`top-level-evaluation`'s per-child evaluation (Clojure's `Compiler/eval` semantics).

## 3. Target shape: the smallest composition

One run is a fold over the plan's events. Each stage is one function of data:

```
read     : text → [event]                          seon.sci.reader/read         (once per run; exists)
analyze  : [event] × ns-context → [[row]]          seon.fn/source-rows          (once per run, BEFORE eval; exists)
gate     : row × projection → row | refusal        entry gate: contract compiles, one declaration per
                                                    top-level child, contract present (missing-contract)
evaluate : event × world → {value | throwable}     sci/eval-form per top-level child (top-level-evaluation)
admit    : rows × world → armed rows | refusal      resolve each row's symbol to its Var; :sci/rebound or a
                                                    stored row's Var changed with no row → unindexed refusal;
                                                    arm once
print    : value × request → shown                  shown-result (binds the handle; the only bind)
record   : evaluations → one transaction            settle-batch! (exists)
reply    : settlement report → answer               reply port; MCP prints the reported observation
```

What disappears: the runtime classification of values; the whole-namespace capture and diff; positional
matching; the reader's head checks; three of four armings; four of five projection advances; the candidate
evaluation path; acquisition inside `evaluate`; the MCP's second read.

**Cost.** Analysis runs once per *run*, not per form: `runtime-analysis-batch` (fn.clj:943) already takes an
ordered batch and folds namespace groups. Measured single-form cost is 12–25 ms warm. A run with no
definitions still pays one analysis. The alternative, "analyze only when a namespace map changed identity",
keeps a post-eval stage; see option A. Admission becomes O(declared rows), not O(interns in changed namespaces).

**One decision this forces (owner):** is an agent's `(def m {:a 1})` a program row, as it is when a file
publishes it (fn.clj:259)? The target applies one predicate to both paths. Either data defs become rows
everywhere (and `missing-contract` exempts arglist-less defs), or `function-definition?` narrows so that files
stop producing rows for data defs. Both are small. Diverging is the defect.

**Loadable slices** (each keeps HEAD loading; deletions estimated from the §2 line counts):

| slice | change | eval.clj | other | net |
|---|---|---|---|---|
| S1 | D1+D2+D12+D13: admission classifies by kondo rows by name; delete `program-value?`, the sentinel classification, positional matching; `unadmitted-notice` reads rows; reader head checks and `read-only-evaluation?` re-derivation deleted (turn reads the evaluation's row) | −260 +45 | reader −70, turn −10 | **≈ −295** |
| S2 | D4+D5+D8: arm once, one projection advance, one bind | −90 +15 | turn −4, error −8 | **≈ −85** |
| S3 | D3+D7: analyze the run before evaluating; gate refuses before evaluation; restoration over declared names; acquisition moves to the fork | −330 +60 | turn +15 | **≈ −255** |
| S4 | D14: split `evaluate` (557) into the stage functions above; one evaluation constructor | −200 +90 | 0 | **≈ −110** |
| S5 | D11: gate through `seon.test/run` on a disposable branch | −224 | turn −60 +25 | **≈ −260** |
| S6 | D9+D10: reply from the settlement; the plan's events carried, no re-reads | −20 | mcp −15, turn −40 | **≈ −75** |
| S7 | D15, and acquisition/base-ctx/fork (eval.clj:2079–3775) moved to `seon.sci.context` | −2,000 moved | +2,000 | 0 (eval.clj shrinks) |
| S8 (C only) | D6+D16: the fork is the agent's world; install carries the evaluated roots without `transfer`/`committed-row?`/`same-declaration-source?`/`installation-covers-program-change?`/`restore-evaluations!`; acquisition recording through the fault writer | −400 | turn −150 | **≈ −550** |

S1–S6 delete about 1,080 lines net. eval.clj goes from 5,022 to about 3,900, or to about 1,900 after S7's move.
S8 takes it to about 1,500 and the net deletion to about 1,630. Honest limit: eval.clj shrinks by thousands
only with S7, and S7 moves code rather than deleting it. The design deletes about 1,100–1,600 lines. The
1,697-line acquisition block needs its own audit (task d215b1ebde7c); this lane did not audit it past its
per-form entry points.

## 4. Three priced options (simplest viable first)

**A. One authority, post-eval (S1+S2+S6).** clj-kondo rows decide what a form defines, looked up by name after
evaluation, gated on "a namespace map changed identity" (cheap `identical?`, no value classification). Arm
once, project once, bind once, reply from the settlement.
- *Guarantee:* files and REPL agree on what the program is; no sentinel; the four breakages' class is gone.
- *Cost:* ~1 day Opus + Astra review; three slices; eval.clj, reader.cljc, turn.clj (held files: needs the
  ledger); net about −455.
- *Sacrifice:* the entry gate still refuses *after* the form ran, so restoration stays (162 lines) and the
  CLAUDE.md "refusing before evaluation" law stays unmet.

**B. Pre-eval authority and gate (S1–S6, then S7) — RECOMMENDED.** A, plus: analyze the run once before
evaluating, refuse before running, restore only declared names, take acquisition out of `evaluate`, split
`evaluate` into stage functions, gate tests through `seon.test/run`, move doc/dir and context acquisition out
of eval.clj.
- *Guarantee:* the CLAUDE.md shape. Each stage is one function of data. A form is parsed once and evaluated
  once. One test path. Refusals come before side effects.
- *Cost:* ~3 days in six loadable slices (S7 is mechanical, scripted). One kondo run per run (12–25 ms
  measured per form, warm). Net about −1,080; eval.clj about 1,900 after the move. Needs the owner's `def`
  ruling (§3).
- *Sacrifice:* a form with no definitions pays one analysis per run. The candidate gate's seeded auto-check
  moves onto the test path and must be re-proved there.

**C. B plus the settlement/install rewrite (S8).** The fork is the agent's world; settlement records rows and
installs nothing it has to copy back or re-verify.
- *Guarantee:* no copy-back, no post-settlement program check, and one "program moved?" notion (D6).
- *Cost:* ~5–6 days. Touches turn.clj settlement, agent.clj and the one-commit B reply, all live and held
  today. Net about −1,630.
- *Sacrifice:* the highest risk to the path that was just stabilized. It needs its own design review against
  one-commit A/B and task `agent-form-writes-name-an-unwritten-evaluation` first.

## 5. Tasks logged (ingestion mints ids)

- `resources/seon/tasks/seon.sci.eval.edn`: `definition-identity-is-derived-from-the-runtime-value-type` (D1),
  `a-defn-arms-its-contract-up-to-four-times` (D4), `the-projection-is-advanced-at-five-sites` (D5),
  `program-movement-is-checked-three-ways-per-form` (D6), `a-result-handle-is-bound-at-three-sites` (D8).
- `resources/seon/tasks/seon.turn.edn`: `an-agent-form-is-read-again-by-each-consumer` (D10).
- D9 is not logged again: `seon.dev.mcp.edn` `branch-eval-answers-no-events` is the same symptom; this audit
  names its likely cause (mcp.clj:445 reads `@connection` rather than the reply's report), which the owner of
  that file should verify.
- Existing, cited rather than duplicated: 5f48e860a402 (reader head check), 132fee0467c8, e32aa6d5b3eb,
  d653ed4b633f (D11), 361088de3d56, e5af812dbf95 (D15), d215b1ebde7c.

## Verification limits

- No source was edited, and no SCI-mode or branch run was made. The kondo rows and `program-value?` answers are
  live on default (JVM mode, read-only). Behaviour at the SCI entrance for `(def m …)`, `(defmulti …)` and
  `(def g (partial …))` is inferred from source (eval.clj:583–672, :1003–1009), not reproduced.
- Line deltas are estimates from current function sizes, not diffs.
- The claim that `runtime-analysis-batch` resolves aliases that an earlier `require` of the same run introduced
  is unverified. S3 must prove it before it relies on batch analysis.
