---
type: review
status: draft
created: 2026-10-07
lane: review-plan3-opus
---

# Second-round review of plan v3 (lane review-plan3-opus, 2026-10-07)

Fresh-context Opus review of `live-agents-plan-notes.md` at `99bdb12ae`. I read `lane-rules.md`, the review spec, plan
v3, both v2 reviews and all five research notes end to end. I spot-checked source at HEAD and probed default. The §1
rulings are taken as given. I edited no plan, src or test file.

Probe state: `bin/seon status` OK. Default runs the `da0ae05dd` archive (pid 83524, started 09:08Z) and is
`:degraded`: 2 errored receipts, 1 unowned namespace, 9 over-bound warnings. The top warnings are
`seon.db/call-with-custody` and `seon.batch/within` (max 173,981 ms, mean 2.4-3.0 s over 68-91 calls; task
`5a8b119ea14a`), then `seon.sci.eval/call-detecting-program-change` (mean 1.14 s, max 2.8 s; task `10d1523434db`),
`seon.fn/analyzed-files` (mean 1.49 s, max 3.2 s) and `seon.schema/call-with-registration-delta` (mean 1.52 s). The
last two list no task in the health reply. They sit in the same inclusive call chain as `call-detecting-program-change`,
so I link them to `10d1523434db` and do not create a duplicate. That chain is the per-pass program-change cost the
plan's §2 omits (finding 7).

My one probe ran as JVM agent `review-plan3-opus` (`result/e314eb8251148`, 6 ms). It interned nothing and created
no SCI branch. **No RESET NEEDED.**

## Verdict

**Needs another round, a short one.** v3 resolves most v2 findings. It fixed the analysis order, inputs as recorded
forms, the entrant table, JVM serialization, continuation, answeredness and the split into preparations plus a cut.
Three findings still block approval:

- a slice-order contradiction about the reader (finding 1);
- a refused record silently erases the pass from history (finding 2);
- the JVM-onto-proc recommendation hits the request port's capacity of one and can wedge root's only diagnostic REPL
  (finding 3).

Each needs a paragraph of plan text, not a redesign.

## Findings, ranked by severity

### 1. HIGH: S2b (one-cursor reader) before S4-S6 contradicts a recorded ordering constraint, and v3 does not address it

The constraint comes from r-delete D5, repeated in Opus v2's feasibility list. Today's run machinery needs every form's
source before any form runs:

- the model path's `freeze!` → `planned-sources` → `plan-call` writes one result-less evaluation row per form (the
  intent commit);
- MCP SCI's `submit-source-in-projection` segments before the run opens and carries `:seon.turn/sources` and
  `:seon.turn/reply-size (count (pr-str sources))` on the request (`src/seon/cluster/agent.clj:744-764`; `segmenter`
  :644).

A reader that reads one form at a time, interleaved with eval (§1.3, §4.1), cannot supply those rows. Both writers
survive until S6. So S2b as written either:

- (a) keeps a structural pre-segmentation for these writers, which is the second read §1.3 forbids, or
- (b) cannot land before S6.

Required text: split S2b.

- **S2b (before S4), in place in `seon.sci.forgive/segments`:**
  - the §4.3 line rules as the segmenter;
  - edamame with normalized options for form extents (r-read §4 cause 1, edamame core.cljc:126-128);
  - no Parinfer and no trials;
  - the imitation flag as an error value.

  Linear cost. Segments plus `read-row` remain, and the plan says so.
- **The one-cursor interleaving** lands in S6 with the deletion of `plan-call`, `source-rows` and the
  submission-time segmenter (`'seon.turn/code-sources` at mcp.clj:231, `:seon.agent/segmenter` in `seon.agent.edn`).

### 2. HIGH: one machinery transaction means a refused record drops the pass's evaluations from history, while its agent writes stay committed

§4.6 says a refused or unknown settlement "still writes the provider call row alone". Nothing else is written. But
the record fails for program-fact reasons, not evaluation reasons:

- `changed-since-basis` push-back;
- `write-digest-error`, the class behind the stale `user.sgprobe/fix` digest that refused every test world
  (task `c136c3be1a16`).

Today `settle-batch-refusal!` still records the evaluations with the refusal (r-record §3). Under v3:

- the forms the agent ran, including committed `transact!` effects, vanish from its next context;
- the model never sees that they ran, and it repeats them;
- an outside MCP pass's evaluations go unrecorded too.

This fails the plan's own "nothing answered without being shown" and CLAUDE.md "deliver every error to whoever can
act on it".

Required text in §4.6: "A refused record is retried once without the refused program facts. It writes the evaluation
rows, the call row and agent facts, with each refused definition's error as that evaluation's diagnostic." This is
the one extra commit §3 already allows ("plus 1 only when a commit itself fails"). Only an unknown outcome writes the
call row alone. Key the call row by pass id so a retry after an unknown outcome is an idempotent upsert, not a second
call counted.

### 3. HIGH: JVM-mode MCP onto the agent's proc (decision 2, recommended) collides with the request port and can lose root's REPL

Two things the decision text does not price:

- **The request port holds one waiting submission.**
  - `submit-source!` refuses a second one: "The agent is busy: a submission already waits on its request port."
    (`src/seon/cluster/agent.clj:770-780`).
  - MCP `eval_clj` defaults `agent` to `"root"`. Lanes and root routinely issue JVM-mode probes as root, often
    concurrently, and today those never touch the port (`jvm-run`, mcp.clj:254-268).
  - After the move, a concurrent JVM probe as root is refused, or waits behind a model pass's provider call (seconds
    to minutes).
- **No finite bound.** The old spec §2.2 (agent-loop.md:205) says an MCP caller awaiting a host call that ignores
  interruption waits until it exits.
  - If root's proc wedges in such a host call, root loses both SCI and JVM entrances. JVM mode is today the only
    diagnostic REPL that bypasses the proc.
  - CLAUDE.md "Keep the development REPL available" is at stake.

Required in §6.2:

- (a) on the proc, with the port admitting a bounded queue, plus a stated escape hatch for root when its proc is
  wedged; or
- (b) unserialized host eval with writer-derived ordinal and namespace (v2 Opus option), accepting that a JVM pass
  observes a concurrently mutating SCI context.

State what a queued MCP submission does when its caller timed out before it started: drop it, or run it with the
result findable by handle. Silently running it later is a duplicate hazard when the caller retries.

### 4. MEDIUM-HIGH: what the one writer stops writing is still undecided, though both v2 reviews asked

v3's RECORD lists what is written but never what is deleted. Unresolved, and not deferred:

- **Measurement upserts** are 51-78% of settlement datoms (r-record §1b, 1c, 1e, 1f), roughly 8 extra attributes,
  and so about 30 KB of index nodes per commit at ~4 KB per attribute (r-record §4). Opus v2 #8 and Astra v2 #7 both
  asked for a decision. v3 is silent.
- **Read evidence, `executed`, `code-evidence`, `read-basis-transaction`, `seon.db/receipt`, `*receipt*`/`*open*`.**
  The refresh is their only reader (r-record §2, §7). After S4 deletes the refresh they are dead writes. Read
  evidence is two-fifths of stored bytes (task `seon.turn/read-evidence-rows-are-two-fifths-of-the-stored-bytes`).
  No slice deletes them (r-delete D1d is missing from §5).
- **Problem-assignment deliveries** are the producer of task `5e7b36bfd02d` (an agent's own form errors mailed to
  another agent).
- **`fault-window`** costs 34 ms of the 96 ms settle.
- **The disposition (`my.agent/done`) terminal data.**
- **Written-never-read attributes:** `seon.eval/{elisions shown-tokens value-tokens}` and
  `seon.db.transaction/{hot repeated ms calls}`.

Required: §4.1 RECORD gets a "not written" list naming these. Read evidence and `*receipt*` go in S4 with the refresh,
or in S5. Measurements become "only over-bound observations, read by `seon.health`", or are named for an owner
decision. Without this, S5's "one transaction" keeps today's datom volume and attribute fan-out.

### 5. MEDIUM: rebuilding the whole history each pass is O(N) per pass and O(N²) per session; the budget is not met even at the datom level

§4.4: "Every pass: ONE narrow ordered read". §3 budget: context build allocation < 3× the history bytes.

My probe on default's root (53 evaluations after the reset, 45,446 chars ≈ 90 KB as UTF-16):

| read | ms | allocated |
|---|---|---|
| index query, all of root's evaluations | 1.86 | 1.02 MB |
| `d/pull-many` [source shown], 53 rows | 0.63 | 0.97 MB |
| `d/datoms :eavt` per row, both attributes | 0.38 | 0.50 MB (about 5.6× the text) |

So the index query alone is about 11× the text bytes, and even a datom-level read misses 3×. r-context measured 17 MB
at 200 rows through pull.

The history is whole and never re-rendered, so pass N's context is pass N-1's plus the exchanges this proc just
recorded. Every entrant now serializes on the proc (§4.2), so the proc sees every exchange it records. The smallest
composition is therefore:

- the proc state holds the context value;
- each pass appends its own held shown texts;
- the one ordered read runs only at proc start or a new assignment.

This is CLAUDE.md "Make cost follow changed inputs", with state colocated in the Flow proc. It also keeps the provider
prompt prefix byte-stable for caching. The budget then holds at any N, and S3's "narrow ordered read" carries a cost
only at start. Required: §4.4 says this, and §3's context row applies to the incremental build.

### 6. MEDIUM: the §4.3 line rules reintroduce three recorded reader defects

r-read §3 lists them, with tasks:

- **Line-start `#` starts code.** Markdown headings `# Plan` / `## What I read` become read errors (task
  `seon.turn/a-markdown-heading-in-a-reply-is-read-as-code`). Rule: `#` followed by `#` or a space is prose.
- **Line-start `` ` `` starts code.** Inline code at line start (`` `reply-map` joins ``) is evaluated as syntax-quote
  (live reply 06). Rule: a backtick span that closes on the same line is prose.
- **"A lone symbol starts code".** One-word prose lines (`Done.`, `Thanks!`) are evaluated, and `Summary:` is a read
  error. Rule: outside a fence, a lone symbol is code only when namespace-qualified (`result/e…`,
  `my.agent/branches`); inside a fence any lone token is code.

Also missing:

- **Fence tags.** Only untagged, `clojure`, `clj`, `cljc` and `edn` fences are code. A `bash` or `text` fence is
  prose.
- **Resync after a read error.** The cursor resumes at the next line the line scan classifies as code. Without this,
  an extra `)` or an unterminated form swallows later forms.
- **Prose after a form on the same line** (`(+ 1 2) is the answer`): the rest of the line is prose.
- **The reader.** Say `sci/parse-next` (edamame under SCI's context options, which resolves `::alias/k` and syntax-quote
  against the live namespace, r-read §7), not bare edamame. JVM keeps host read.
- **`ns=> (form)` lines.** They become prose silently, so a model copying the REPL presentation sees its forms "run"
  in its own text while nothing evaluated. Report them as an imitated prompt, as `;; =>` is, so the agent is told.
- **Trailing prose** "is kept": name where (the last evaluation's comment, or a reply-level fact).

### 7. MEDIUM: §2 and S0 omit the post-definition program re-acquisition, the largest residual after the refresh

Evidence:

- r-turn §3a: `acquire-program!` 1.0-1.3 s on every turn after a program change.
- r-record §1d: a JVM call after a `defn`, 1,282 ms and 3.2 GB.
- Live health now: `call-detecting-program-change` mean 1.14 s, max 2.8 s, with `call-with-registration-delta` and
  `analyzed-files` at 1.5 s each.

S4 deletes the refresh's call, but JVM mode (mcp.clj:262, `agent/acquire-context!`) and the next SCI pass after a
`defn` still pay it. Astra v2 #7 asked for the next-pass-after-defn probe; v3 dropped it.

Required:

- S0 adds the behavior case "a pass after a `defn` pass", for every entrant;
- §2 gets a row for it;
- the budget is stated for it, with task `4a738d4f7138`
  (`seon.sci.eval/acquisition-rebuilds-the-whole-program`) as its stop-and-fix owner.

### 8. MEDIUM: the inputs pass has no transaction and failure rule

§4.4 says "the next pass first evaluates ONE generated form … recorded with origin `:input`". Unstated:

- **Which transaction records it.** Is it the model pass's transaction, written after the provider replies, or its own
  commit before the provider call?
- **What happens on a failed provider call.** §4.6 writes "the call row alone".

If the input exchange is not recorded on failure, the next pass re-evaluates the input form (fine, if the form is
"new since the answered basis"), and the plan should say so. If it is recorded, the pass needs a second commit, which
§3 must list.

Proof results are written on the test branch or task home, so the input form reads across custody. Say that this is
an explicit read of another branch, which is allowed by the custody law.

### 9. MEDIUM: commits that remain outside "one machinery transaction" are not listed

The following still write more than one commit per pass:

- `seon.fault/record!` writes the cluster copy on default as a second commit on another branch (r-record §1g;
  fault.clj:138, :166). v3 says only that observed faults ride the pass.
- Test-member branch commits and cross-branch completion.

§3's "1 (plus 1 only when a commit itself fails)" is therefore false for faults and tests. Either drop the cluster
copy (the reply plus the agent branch already deliver it), or list these commits as allowed exceptions in §3.

### 10. MEDIUM: SCI test running is fixed only in S7, after the cut that switches the test entrant (S5)

`run-test-var` throws ClassCastException on an SCI test var (task `9236eba3f99e`, r-read §6). S5 claims "existing
entrants switch to it", the test entrant included, and S7's `assign!` refusal depends on agents running proof tests.
Astra v2 #6 asked for this fix to precede the cut. Move "SCI test running fixed" into S5, or before it.

### 11. LOW-MEDIUM: labels and undefined references introduced by the v3 renumbering

- §4.1 calls admission "the open **S4** design question", but §5 puts the one analysis and the admission probes in
  **S5**.
- S6 "the three bounds" is undefined in v3. They live in the old spec (agent-loop.md:191-205, :472): provider
  timeout, SCI evaluation limit, calls per outside wake. The plan replaces that spec, so list them in §4.8 or §4.5. Also
  say what bounds an MCP await once `turn-completion-backstop-ms` (today's MCP await bound, `evaluate-source!`
  agent.clj:782) is deleted.
- The origin enum's values are never listed. §4.4-4.5 use `:opening`, `:input` and `:model`, plus "outside" and
  "test". Name all five.
- §4.6 reverses the CLAUDE.md law "a crash before settlement … leaves the database unchanged": agent writes stay.
  §4.6 cites an owner ruling, but CLAUDE.md must be revised in the same commit (S6), and the 2026-07-28
  capture-before-call ruling and the intent-commit design, both in docstrings, are reversed. List all three reversals
  under §6.5 so the owner approves them explicitly (Opus v2 #6).

### 12. LOW: smaller gaps

- **DATA print profile.** "printed once at record time by the value printer" does not say with which budget.
  Re-printing a stored shown string inside a map under the ordinary budget re-clips historical text, which CLAUDE.md
  forbids ("never clip … historical units again").
- **REPL text needs no storage.** It is a concatenation of already-stored strings (r-context §2.3: 3.3 ms at 200 rows).
  Storing it as well duplicates every exchange.
- **Handles after a restart.** They bind `:live-only` markers (`bind-settled-results!`, eval.clj:2786). Dig forms in
  stored history then answer the marker. State this in §4.4 so the S8 experiments read it correctly.
- **JVM row in the entrant table.** Say that JVM passes record evaluation rows only: no analysis and no program facts.
  SCI does not observe host definitions.
- **§2 "the turn without the refresh, ~457 ms / 550 MB".** This includes the stub provider and the attempt (32 ms /
  40 MB, stage 4). It is a fair label, but it is not all Seon's work.

## v2 findings: status in v3

| v2 finding | Status |
|---|---|
| Opus 1 / Astra 1: analysis after eval | **Resolved** (§4.1). Admission is explicitly deferred to S5 probes (mislabelled S4, finding 11). |
| Opus 2: continuation rule | **Resolved** (§4.5, decision 1). |
| Opus 3 / Astra 5: JVM fence, serialization | **Resolved in direction.** New problem: finding 3. |
| Opus 4 / Astra 7: budgets per stage | **Partly.** Context budget made proportional; store resolved by owner; profiler marks excluded. Still unstated: which stage blows <200 ms / <5 MB for `(+ 1 2)` (settle 96 ms, run-observation 27 ms, per-form kondo 20 ms) and the post-defn residual (finding 7). |
| Opus 5: test-run cost | **Resolved** (P0 `test-cost`, decision 3). |
| Opus 6: crash/replay, capture-before-call | **Partly.** The crash ruling is cited; the reversals are not listed for approval (finding 11); the refusal arm is wrong (finding 2). |
| Opus 7 / Astra 6: origin before S3 | **Resolved** (S3). |
| Opus 8: the writer's duties, measurements | **Unresolved** (finding 4). |
| Opus 9: store options | **Resolved by owner ruling** (~150 KB). |
| Opus 10: factual corrections | **Resolved** (floor split, 202-408, MCP one commit "no effects"), except the `result/<id>` lifetime (finding 12). |
| Opus 11: P0 producers | **Resolved** (profiler excluded; nil fault in S2b; `reset-leak`). |
| Opus 12: JVM and test semantics | **Partly** (entrant table; JVM analysis unstated, finding 12). |
| Opus 13 / Astra 9: DATA inside the one printer | **Partly** (value printer named; budget unstated). |
| Opus 14: provider window | **Resolved** (§4.4 Window). |
| Opus 15: gap between S2 and S3 | **Resolved** (opening moves in S4, after the refresh is gone). |
| Astra 2: inputs, opening per assignment | **Resolved in direction.** Transaction and failure rule open (finding 8). |
| Astra 3: faults, effects, tests | **Partly** (finding 9). |
| Astra 4: print before record, unknown vs refused | **Resolved**, except the refusal content (finding 2). |
| Astra 6: slice order | **Mostly resolved.** New contradiction for S2b (finding 1); SCI tests late (finding 10). |
| Astra 8: §6 options and invariants | **Resolved** (§4.8, decision 4). |

## Accuracy spot-checks (§2, §4)

| Claim | Check | Verdict |
|---|---|---|
| refused imitation, nil fault at batch.clj:390 | `src/seon/batch.clj:390-391` builds `(ex-info … {:seon.batch/refused refused})` with no error value | supported |
| MCP JVM runs on the caller's thread, unserialized | `jvm-run` (mcp.clj:254-268) calls `evaluate-batch` directly and acquires the agent's context | supported |
| the only fence is the open-run refusal | `record-evaluated-call` turn.clj:1335-1337 refuses `::agent-already-running` | supported |
| per-commit floor, 36% roots / 64% nodes | matches r-record §4 | supported |
| turn without the refresh 457 ms / 550 MB | sums r-turn §3a stages 2-6 exactly | supported (includes stub provider) |
| narrow pull 17 MB at 200 | r-context §2.2 (index 7.6 MB + pull 9 MB) | supported; my 53-row probe scales consistently |
| `code-currency` "ONE batched query … 60 ms and 180 MB for one symbol" | the 60 ms / 180 MB is Astra's `seon.db/q` probe of the same shape (plan-v2-review probe 3), not `code-currency` itself | supported, attribution loose |

## Feasibility, simplicity, budgets, risks (spec questions 2-5)

- **Order:**
  - P0, S0, S1 and S2a hold;
  - S2b must split (finding 1);
  - S3 is additive and holds;
  - S4 is feasible at the prompt seam if the opening and inputs enter through today's submission path (one commit
    each). Say so;
  - S5 needs the SCI test fix (finding 10);
  - S6 is the single dependency-complete cut, with its reset.
- **"One read, one analysis, one batched query, one transaction" per entrant:**
  - model and MCP SCI: holds after S6 if finding 2's retry is the failure exception;
  - MCP JVM: one read, no analysis, one transaction;
  - test: does not hold (finding 9);
  - faults: do not hold (finding 9);
  - blobs: are store writes around the transaction, not commits. That is fine; count their bytes.
- **Simplicity.** The biggest remaining simplifications are deletions v3 does not name:
  - the dead writes in finding 4;
  - the per-pass history read, replaced by the proc's held context (finding 5);
  - `run-observation`'s re-read of the run it just settled (Opus v2): the reply is the pass's held value.
- **Budgets:**
  - <200 ms / <5 MB fails on the pass after a `defn` (finding 7) and on any pass while measurements and read evidence
    are written (finding 4);
  - the context budget fails with any per-pass read (finding 5);
  - store holds at ~150 KB for plain passes. A `defn` pass is ~700 KB from attribute fan-out (r-record §1b), so report
    it separately, as §3 already does for allocation.
- **Unnamed risks:**
  - history loss on a refused record (finding 2);
  - root's REPL lost behind its own proc (finding 3);
  - timed-out MCP submissions that still run later (finding 3);
  - duplicate call rows on unknown outcomes (finding 2).

## §6 decisions

1. **Continuation:** the options are right. Add that before S7 (no proof completion), every episode ends by the call
   cap.
2. **JVM onto the proc:** incomplete (finding 3). Add the port capacity, the default-`root` agent, and the escape
   hatch for a wedged proc.
3. **Proof runs in or out of the budget:** fine.
4. **Subtree counts:** fine, and independent of this plan.
5. **Approval:** add the three ruling reversals (finding 11) and the "not written" list (finding 4).

## Concrete plan-text changes

1. §5: split S2b into "in-place segmenter rules, linear, before S4" and "one cursor, with S6" (finding 1).
2. §4.6: refused record → one retry without the refused program facts, recording evaluations with the refusal as
   their diagnostic; call row keyed by pass id (finding 2).
3. §6.2: add port capacity, the root default, the wedged-proc escape hatch and the timed-out queued submission rule
   (finding 3).
4. §4.1 RECORD: a "not written" list naming measurements, read evidence/receipt/`*open*`, problem assignments,
   fault-window, dispositions and the unread attributes; S4 or S5 deletes D1d (finding 4).
5. §4.4: the proc holds the context and appends. One ordered read happens only at proc start or a new assignment
   (finding 5).
6. §4.3: the rule corrections in finding 6.
7. §2 and S0: the pass-after-`defn` row and case, owned by `4a738d4f7138` (finding 7).
8. §4.4: the inputs pass's transaction and failure rule (finding 8).
9. §3: list the fault cluster copy and the test commits as exceptions, or drop the cluster copy (finding 9).
10. §5: move the SCI test fix into S5 (finding 10).
11. §4.1 S4→S5; define the three bounds and the MCP await bound; list the origin enum; list the ruling reversals in
    §6.5 (finding 11).
12. §4.4: DATA's print profile, REPL text not stored, handles become markers after a restart (finding 12).

## Questions

1. Is the S2b intent the in-place segmenter (my reading) or the one cursor? If the latter, how do `plan-call` and the
   submission segmenter get their rows before S6?
2. Does the input form's exchange ride the model pass's transaction or its own commit?
3. Should root's JVM mode stay off the proc as the guaranteed diagnostic REPL even if lanes' JVM probes move on?

## Design check

v3's composition holds against the code. The one cursor, writer-side derivation and the entrant table all match
existing seams (`with-submission`, `program-facts-call`, the request port). What is wrong is the sequencing (S2b), one
failure arm (refusal) and one unpriced consequence (port capacity). The largest simplification left on the table is
holding the context in the proc instead of re-reading it.

Verification limits:

- My context probe ran on root's post-reset population of 53 rows, not 200.
- I did not drive a model pass, a refusal or a JVM-on-proc experiment.
- The health warnings are cumulative over the JVM's life and are not attributed to a caller.
