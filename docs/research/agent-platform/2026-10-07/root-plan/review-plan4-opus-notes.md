---
type: review
status: draft
created: 2026-10-07
lane: review-plan4-opus
---

# Third-round review of plan v4 (lane review-plan4-opus, 2026-10-07)

This is a fresh-context Opus review of `live-agents-plan-notes.md` at `e6e618d67`. Before writing it I read:

- `lane-rules.md` and the review spec;
- plan v4;
- both v3 reviews: `review-plan3-opus-notes.md` (O) and `plan-v3-review-notes.md` (A).

I spot-checked source at HEAD and probed default through MCP JVM mode as agent `review-plan4-opus`. That probe
interned nothing and created no branch. I did not argue with the §1 rulings, and I did not edit any plan, src or test
file. **No RESET NEEDED.**

## Verdict

**Almost ready for the owner. One short revision is needed first.** All 12 O findings and all 9 A findings are
resolved or explicitly decided, except the four items marked "open" in the table below. Two new problems would break
installed, tested MCP behavior. Each needs a sentence or two of plan text, not a redesign:

- **N1 (high):** the §4.3 line rules turn an MCP lone-symbol dig, such as `result/e…`, into prose. The same happens to
  any literal at line start.
- **N2 (high):** JVM mode with "no SCI context acquired" removes today's documented and tested dig of a JVM value from
  SCI mode. The plan does not say where JVM results live.

## New findings, ranked

### N1. HIGH: MCP code read under the prose rules loses lone symbols and literals

§4.3 says a line starts a form only with `( [ { ' @ ^ #…`, that "a lone symbol is code only inside a fence", and that
"MCP code goes through the same reader".

So, for an MCP `eval_clj`:

- `result/e1a2b3`, `*1`, `my-var`, `:k`, `42`, `"s"`, `\a` and `` `(f) `` all become prose;
- the call is then refused with "no form".

Digging by the result symbol is the documented MCP workflow: the `eval_clj` tool description says "Dig into a value
with its result symbol in a later sci evaluation". Astra v3 #6 asked for literals at a form boundary; v4 does not
cover them.

Text for §4.3 MCP: **"MCP code is read as one code fence: every line is code, and there is no prose
classification."** For model replies, also list keywords, numbers, strings and characters inside fences. Outside
fences, they stay prose by rule.

Unresolved from O #6: prose after a closed form on the same line (`(+ 1 2) is the answer`) is unspecified. A plain
`parse-next` would read `is`, `the` and `answer` as symbols and evaluate them. Rule: after a top-level form closes, the
rest of that line, outside a fence, is prose.

### N2. HIGH: the JVM row breaks the installed JVM-to-SCI dig and names no home for JVM results

Today, `jvm-run` (`src/seon/mcp.clj:254-268`) binds each value under its handle in the agent's SCI context, through
`agent/acquire-context!`. The test `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
(`test/seon/mcp_entrance_test.clj:108-121`) digs a JVM value from SCI mode.

v4 §4.2 reads: "No SCI context acquired; handles dig from JVM mode". That is an unlabeled behavior change. It also
leaves two questions open:

- Which roster holds the live JVM results, keyed how, and retired when? CLAUDE.md requires exactly one roster per
  scope.
- What do the dig forms printed in a JVM reply look like? Today they say "sci evaluation".

There are two options:

- **(a)** Keep the bind into the SCI context only when that context is already acquired. JVM never acquires one, which
  keeps the 1.0-1.3 s post-`defn` acquisition off JVM. Otherwise the handle answers gone-value data.
- **(b)** Label the change BEHAVIOR CHANGE, name the JVM-side result holder, print JVM-mode dig forms, and convert the
  test.

Option (a) is the smaller change. Either way, state that MCP JVM passes are absent from the model's context (see N3).

### N3. MEDIUM: which passes the model sees is narrower than today and unlabeled

§4.4 reads only `:opening`, `:input` and `:model` exchanges. Today an MCP SCI call appears in the agent's next context:
`seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`, and the `eval_clj` description says
"read exactly what that agent's next context reads".

Under v4, an outside SCI pass that defines or changes something in the agent's world becomes invisible to the model.

Suggested decision:

- Outside SCI passes are shown. They run on the proc, so the transcript append in §4.4 already covers them at no cost.
- JVM passes are not shown.
- Either way, list it in the §4.4 presentation and convert the test in the slice that lands the change.

### N4. MEDIUM: "one kondo run over the ORIGINAL source text" includes the prose

The original text of a model reply contains Markdown, apostrophes and unbalanced quotes. kondo would then report
findings, or parse errors, about the prose. Astra v3 #6 raised the offset problem, and v4 answers it only with
"original offsets".

Text: "kondo runs over the original text with every non-code byte replaced by a space, newlines kept, so spans equal
original offsets."

### N5. LOW-MEDIUM: "Reassigning bumps the revision" is false as stated

`:seon.task/revision` is "seon.id/digest over a task's declaration" (`resources/seon/schemas/seon.task.edn:66`).
Astra v3 #3 noted that `assign!` derives the assignment id from `[handle agent-id branch]`. Reassigning an unchanged
task therefore changes neither the id nor the revision, so it causes no new opening.

Text: "A changed task declaration (new revision) or a new assignment id causes a new opening; reassigning an unchanged
task does not." If a reopening is wanted, give it its own explicit cause.

### N6. LOW: accounting and slice-naming gaps

- **§3 "Machinery txs 1"** omits the fault authority write that §4.2 now counts. Add "+1 per new fault signature, at
  the task home".
- **The §4.1 "stops writing" table** says "deleted with its last consumer", but the §5 rows don't name these
  deletions:
  - read evidence, `executed`, `code-evidence` and the receipt belong in the S4 row;
  - problem-assignment routing, fault-window and the run-observation reread belong in S5 or S6.

  Name them so that each slice's diff review can check them.
- **The origin enum's values** are still not listed. The plan names `:opening`, `:input` and `:model`, and describes
  "outside" and "test" passes only in prose. List all of them in S3.
- **The unknown-outcome seam.** §4.6 says "the pass keeps ownership of the pending transaction". But
  `seon.db/transact-call` holds `pending` only locally and returns a diagnostic on timeout (`src/seon/db.clj:5721-5758`),
  which Astra v3 #1 flagged. S6 must expose that pending result to the pass, for example as a returned member. Name
  this as an S6 change to `seon.db`.

### N7. LOW: two §2 labels Astra corrected in v3 are unchanged

- "contract check per lane commit 1.7-3.0 s … 471 files": this is a whole src/test census. `bin/lane` checks select
  their inputs. Relabel it "full src/test contract census".
- "reply read, 76 KB": this is 76,408 characters (r-read §4). Write "~76k characters".

## v3 findings: status in v4

| v3 finding | v4 |
|---|---|
| O1 / A2: S2b order | **Resolved.** S2b-1 lands in `segments`; S6 brings the cursor plus D5 and deletes the consumers. |
| O2: refused record drops history | **Resolved** (§4.6: one retry without refused facts, under the original call id). |
| O3: JVM on proc vs port | **Decided:** unserialized, off the proc, with the timed-out request rule. New consequence: N2. |
| O4 / A8: what stops being written | **Resolved** (table). Measurements go to §7.2. Slice naming: N6. |
| O5: proc-held context | **Resolved** (§4.4). |
| O6: reader rules | **Mostly resolved.** Open: prose after a form on the same line (N1). |
| O7: post-`defn` row | **Resolved** (§2, §3, S0). |
| O8 / A3: input pass | **Resolved** (its own pass, delivery identities, captured basis). |
| O9 / A5: faults, tests | **Resolved** (§4.2). §3 count: N6. |
| O10: SCI tests before the cut | **Decided:** the proof seam is `my.test/check` in S6; `run-test-var` stays in S7 (the distinction from A #4). Acceptable. |
| O11: labels, bounds, enum, reversals | **Resolved** except the enum list (N6). |
| O12: DATA, handles, JVM analysis | **Resolved.** |
| A1: unknown outcome, no extra write | **Resolved in text.** The seam is unnamed (N6). |
| A4: launch, bail, bounds, case table | **Resolved** (§4.5, §4.8, S6). |
| A6: admission proof, spans, grammar | **Mostly resolved.** Open: kondo over prose (N4); literals at line start (N1). |
| A7: printing and envelope | **Resolved.** |
| A9: reset manifest, S4 reset point, adoption first | **Resolved** (§5, §6). |
| A §6.2: JVM serialization | v4 chose the option Astra advised against and did not list it in §7. Fencing JVM off the SCI world (no context, not in context) answers Astra's objection about private Vars, result binding and the prompt, *if* N2 and N3 are written down. Otherwise put it in §7. |

## Spot-checks

| Claim | Evidence | Verdict |
|---|---|---|
| nil fault from a refused imitation, `batch.clj:390` | `src/seon/batch.clj:390-391`: an `ex-info` with no error value | supported |
| `apply-pending` makes earlier facts visible, `batch.clj:480-501` | docstring and body at `src/seon/batch.clj:480-501` | supported |
| reset leak fixed in `71439648a` | the commit exists ("stop hosted agents and release their branch connections") | supported |
| post-`defn` reacquire, 1.0-1.3 s | live health: `seon.sci.eval/acquire-program!` mean 1,069 ms, max 1,475 ms, task `4a738d4f7138` | supported |
| task revision exists | `seon.task.edn:66`, a declaration digest | supported; but see N5 |

## Live observations (default, basis 536871156)

Default's health is `:faulted`. The counts are:

- 8 over-bound warnings;
- 5 errored receipts;
- 1 open fault;
- 2 unowned namespaces.

The individual items:

- **The open fault** has signature `59f8b805…`, producer `seon.turn.world/record-fault`, layer `:seon.batch/settle`,
  and 2 occurrences on 2026-10-07T09:19Z. Its message: "settle: the recording was not committed: This write names
  branch :agent-1048bc748e6e, which is not the writing cluster's branch :agent-a664b3b890ee".
  - Agent `defn-cost-a` caused it while evaluating `875e15efbe38`.
  - It was delivered as message `70ea6ed73fff`.
  - It links to no task. I did not establish its cause: it may be lane `defn-cost`'s probe writing through another
    branch's connection. I did not file a task. Root should ask `defn-cost` to classify it.
  - It bears on §4.1: the one pass transaction must name only its custody branch, and a cross-branch fact in it
    refuses the whole record.
- **`seon.cluster.agent/acquire-once!`:** mean 1.16 s, max 5.9 s over 12 calls, no task in the reply. This is likely
  the `host-cost` lane's hosting cost.
- **`seon.sci.eval/fork-cluster-ctx`:** max 5.2 s, task `00187adf567b`.
- **`seon.cluster.source/changed-identities`:** max 6.8 s, tasks `483c64e1796e` and `4a627750dc7e`.
- **`(my.task/open d)`** returned 0 open runtime tasks.

## Suggested text edits

1. §4.3 MCP: "MCP code is read as one code fence: every line is code." Add the same-line trailing-prose rule (N1).
2. §4.2 JVM row: give JVM results a home and keep the SCI dig when the context is already acquired, or label the
   change and convert the test (N2).
3. §4.4: outside SCI passes are shown, JVM passes are not, and the test is converted (N3).
4. §4.1 ANALYZE: non-code bytes are blanked to spaces, so offsets are preserved (N4).
5. §4.4 opening: correct the revision sentence (N5).
6. §3: add the fault authority transaction. §5: name the deletions per slice and list the origin enum. S6: expose
   the pending transaction from `seon.db/transact-call` (N6).
7. §2: relabel the contract census and the 76k characters (N7).

## Design check

v4's composition holds against the code. The one cursor, the writer-side transaction, the proc-held transcript and
the opening and input passes all sit on existing seams. The remaining problems are in the entrant edges, MCP's reader
and JVM's result home, where v4 changed installed behavior without saying so. They are text fixes.

Verification limits:

- I drove no model pass, refusal or concurrent MCP/model run.
- The health figures are cumulative over the JVM's life.
- I did not re-measure §2's numbers. They match r-* and the v3 audits.
