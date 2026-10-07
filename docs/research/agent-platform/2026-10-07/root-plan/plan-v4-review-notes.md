---
type: review
status: changes-required
created: 2026-10-07
lane: plan-v4-review
---

# Plan v4 — third review, plan-v4-review

**Verdict: not ready-for-owner.** Most v3 findings are addressed. The remaining changes are small plan edits,
not a different architecture: correct the `*open*` deletion boundary, specify analysis input and publication,
and finish the outstanding custody/budget decisions. §1's owner rulings are accepted. All proposed changes below
are **TARGET**, not claims of installed APIs.

Reviewed plan/source `e6e618d671e94cc9f93a489c9e2bb3788edb7d69`; read lane-rules first, both complete v3 reviews
and all five r-* notes. Used the Clojure, REPL, Flow, Datahike and testing skills. Source citations below are to
that committed archive, excluding foreign working-tree edits. Pins: Datahike `d9121bbbad`, SCI `ce0aa53b9b`,
edamame `196ea5e4ef`, clj-kondo `57252e0797`, Malli `43c6dca8d2`, core.async `dc35f3e0d7`.

## Ranked findings and exact amendments

### 1. High — §4.1 deletes `*open*` before its surviving consumers convert

“Consumed only by the refresh; deleted with it” is false for `*open*`. It creates run/evaluation identities before
an agent's first write (`src/seon/db.clj:184,3627`, `src/seon/sci/eval.clj:4013`). Effects resolve their evaluation
through the run (`src/seon/effect.clj:210`); effect-reaching tests open that run explicitly
(`src/seon/test.clj:179`). Those consumers survive S4. The plan places replacement effect provenance in §4.2/S5
and retires run machinery in S6. This contradicts “old run duties stay until S6.”

**Change:** split the table row. Delete refresh-only stored evidence with its readers; keep `*open*` and its
bindings until effect provenance, test attempt writers and run references convert together in S6 (or explicitly
move that complete conversion into S5). List `open-tx`, boot `recover-tx` and their fixtures in the cut checklist.
This preserves the smaller deletion; it adds no adapter. Related existing task:
`seon.db/an-agent-transact-reports-the-runs-open-datoms`.

### 2. High — raw ORIGINAL reply text is not a valid analysis input

§4.3 excludes prose from evaluation, but §4.1 sends the original prose and fences to kondo. A quote in prose can
consume a later definition. Read-only live probe of the existing owner, no definition evaluated:

```clojure
(let [d @(seon.cluster.boot/connection "default")]
  (mapv (fn [source]
          (mapv :seon.fn.analyzer/name
                (get-in (#'seon.fn/runtime-analysis-batch
                         d [{:namespace-name 'user :form-source source}])
                        [:seon.fn/analysis :seon.fn.analyzer/var-definitions])))
        ["Text: \"\n(defn review-f [x] (inc x))"
         "       \n(defn review-f [x] (inc x))"]))
```

Measured equivalent probe `result/e3f37d4310e04`: raw → no definitions, 169.28 ms; blanked prose → `review-f`,
13.79 ms, analysis row 3 (the owner's namespace prelude adds a row). Both returned no findings. Earlier bracket/
fence specimens *did* retain the definition, so those alone would have missed the problem. These are illustrative
single calls, not a performance comparison. Owner: `src/seon/fn.clj:944,976,1018`; it currently constructs source
and spans from code requests and namespace context, not arbitrary model prose.

**Change:** “Run kondo once on the reader's retained code slices, preserving original coordinates by blanking
non-code characters without changing newlines/columns, or by an explicit source-span map. Keep original text for
history. Carry each slice's observed namespace/aliases.” Add this specimen to S5's already-blocking admission
proof. Keep the proof unresolved explicitly; do not promise its result. No second parse or guessed Var snapshot.

### 3. High — publication and durable late diagnostics are still unspecified

The old spec explicitly retains `blob/with-publication!` and installation after commit
(`docs/seon/specs/agent-loop.md:161`). V4 mentions blobs only in the budget, omitting the publication guarantee
from §4.1/§4.9. The existing permit encloses blob publication **and** root commit (`src/seon/blob.clj:304`).
Also, §4.4 says both presentation encodings are stored, while FINISH computes them **after** RECORD. How a
post-commit installation diagnostic reaches the next context without another write is not stated.

The unknown-outcome rule is now correct, but the required seam remains missing: `db/transact-call` holds
`pending` locally and returns a diagnostic without it (`src/seon/db.clj:5721,5735`). Merely retaining the returned
map cannot await the transaction. Releasing the blob reachability permit on an unknown commit also needs proof.

**Change:** restore blob publication explicitly for every entrant, with bytes counted separately from database
transactions. Name the existing DB owner that will retain pending completion/permit ownership; make that S5's
prerequisite alongside admission. Define the exact pre-commit stored exchange and deterministic FINISH composition
without re-clipping saved units;
deliver late diagnostics immediately and durably through the already-planned error/input path, with its counted
home write. State how failure of the final call/fault-only fallback terminates; it is currently a third attempt
with no defined failure arm. Do not add another recorder or retry loop.

### 4. Medium — remaining concurrency and delivery rules need a scope

- **JVM:** choosing an independent host entrance is reasonable and resolves the root-REPL objection. However,
  “handles dig from JVM mode” needs an owner: today `mcp/jvm-run` acquires SCI and `jvm-show` binds there
  (`src/seon/mcp.clj:264`, `src/seon/turn/batch.clj:859`, `src/seon/sci/eval.clj:592`). Name how the existing live
  result owner serves host evaluation without adding a second store or interning shared host result Vars.
  Scope §4.8's “no second pass overlaps it” to the serialized SCI/model execution; otherwise it contradicts
  §4.2's unserialized JVM row. Keep each host invocation owned until exit.
- **Inputs:** retain the captured basis, but state that the pass supplies the identities it actually read and
  the writer validates/records those; it must not select newly arrived identities as already delivered. Name
  explicit task-home reads and per-home identity/basis evidence for proofs/errors. Independent branch tx numbers
  are not a common clock. This is the still-unanswered part of v3 A3/O8.
- **Transcript:** apply the same origin predicate on incremental append and restart acquisition. “Appends each
  pass” currently includes MCP SCI, but reconstruction includes only `:opening/:input/:model`. Otherwise an
  outside pass appears until restart and then disappears. Enumerate all five origins in S3.
- **Stop:** carry the caller/lifecycle deadline through disarm/join as well as MCP await. The deleted backstop
  also supplies `stop-graph!`'s bound (`src/seon/cluster/agent.clj:1535`), not just `evaluate-source!`'s bound.

**Change:** add these four sentences/contracts to §4.2/§4.4/§4.8 and name their S5/S6 proofs. They use the existing
proc, result owner, writer and task home; no new scheduler is needed.

### 5. Medium — budget and reset acceptance are not yet complete

The targets are plausible goals, not demonstrated consequences of deleting the refresh. The definition target
allows 30 MB and the test target 20 MB, while §1 stops all Seon work above 10 MB. The context row still applies
<10 ms / 3× text to cold reconstruction too; moving work to proc startup does not make that allocation disappear.
V3 O5 measured even raw datom reconstruction at 5.6× text; r-context's indexed pull was ~17 MB at 200 rows before
composition. Ordinary settlement, acquisition and runner overhead remain measured budget gates, even after P0.

**Change:** distinguish incremental append/composition from cold history acquisition in §3, measuring both at
1/10/200 exchanges. Resolve the 10/20/30 MB conflict in §7 explicitly. S0 uses the HTTP fixture for the *model*
entrant; other entrants keep their real entrances. S5 should be reviewed as in-place recording preparation,
entrant/effect/error integration, then admission proof, retaining old duties until the dependency-complete S6 cut.

§6 now lists losses and S4/S6 resets, which is progress. But dumping assignments/settings/schedules/open error
signatures is not restoring them. `reset-cluster!` replaces the head with the source commit
(`src/seon/cluster/registry.clj:319,353`). **Add** restore/reconcile and post-reset checks for the retained items,
or explicitly mark each as archive-only. Task files alone do not preserve assignment revisions or agent settings.

### 6. Low — carry forward the factual corrections already requested in v3

| §2/§4 claim | Audit and replacement wording |
|---|---|
| 2.1 s / 3.65 GB / ~400k / .94 MB / 6 commits; refresh 1.3–2.3 s, 202–408 datoms | Supported as the historical r-turn/r-record specimens, not new measurements. ~400k is the instrumented whole window, not the corrected stage sum. |
| Remaining 457 ms /550 MB | Correct sum of stages 2–6; explicitly includes stub-provider interval and attempt recording. |
| MCP 27 datoms /~150 KB /205–358 ms /159–232 MB; 96/34/27/~20 ms stages | Supported by r-record §1a for its no-effect/no-fault specimen. |
| Definition 712–733 MB; next pass 1.0–1.3 s /3.2 GB | Supported historical whole-call values; acquisition is a separate P0 gate, not cured merely by S4. |
| “76 KB mixed” /4.1 s /94 GB | r-read §4 measured **76,408 characters**, about 24k estimated tokens at its 3.2 prior; not measured UTF-8 KB. |
| Test cold/warm values | Retain populations and revisions: historical 2.3–6 s and 384 ms versus post-4c9ccb3f7 cold 584–702 ms/~817 MB and warm 126 ms/~191 MB. The 384 ms specimen still lacks a precise citation. |
| “contract check per lane commit,” 1.7–3 s /3.2 GB /471 files | Replace with **full src/test contract census via MCP**. Docs skip checks and ordinary slices select inputs (`bin/lane:129,235`). This review reproduced the census below. |
| or+join 60 ms /180 MB | Historical pre-fix shape. `150feaf5d` already landed before v4; label baseline and link its narrower walker measurement, not “lane fixing it.” |
| Reset/hosting and small-commit 36%/64% | Historical reset-leak/r-record specimens. Neither 6–13 s reset nor ~150 KB is a universal bound; no reset repeated here. |
| “Behavior seen in the real model specimen” | Mixed evidence: model transcripts **and reader/MCP probes** (r-read §3); prose-only and nil-fault cases were not all one model specimen. |
| apply-pending and program-facts-call | Accurate existing seams (`batch.clj:480`, `turn/batch.clj:600`). Batching the digest check is TARGET; other required database reads remain (`fn.clj:958,983`). |
| §4.1 deletion, §4.2 entrant table, §4.3–4.8 | Target contracts, with findings 1–5; JVM no-analysis is already true (`turn/batch.clj:887`), no-SCI-acquisition is not. Lookup correction is right: retain `my.program/declaration`. |
| §4.9 “CLAUDE.md … leaves database unchanged” | Already corrected in AGENTS.md:302. Do not ask the owner to reverse a law that now matches §1.5; retain actual capture/intent reversals for approval. |

## Every v3 finding: disposition in v4

O = review-plan3-opus; A = plan-v3-review. “Partial” means neither closed nor fully listed in §7.

| Finding | Disposition |
|---|---|
| O1 / A2 reader/cut order | Resolved: S2b-1 keeps segmentation; S6 converts its consumers. New deletion-order defect is finding 1 above. |
| O2 / A1 refused/unknown settlement | Correct retry identities and no-write-on-unknown decided. Pending owner, final fallback failure and late visibility remain partial (3). |
| O3 JVM port/timeout | Off-proc alternative decided; queued SCI expiration decided. New host-result/exit scope must be completed (4). |
| O4 deleted writer duties | Listed; measurements are §7.2. Correct `*open*` exception (1); do not delete surviving test evidence. |
| O5 held context | Accepted, but cold/incremental budget distinction missing (5). |
| O6 / A6 reader and analysis | Most grammar decisions explicit: headings/backticks/lone words, fences, continuation/EOF, imitation and trailing/no-form exchanges. `cljc` fences and bare literals/indented forms are now prose: name that compatibility choice and teach fences in help. Same-line trailing prose still needs a rule. Say SCI parse-next with live options, not bare edamame. Admission cases expanded and explicitly gated at S5; raw analysis input needs correction (2). |
| O7 / A8 after-defn and budgets | Workload and P0 owner restored, finite history stated, measurement decision restored. Remaining budget/factual corrections are 5–6. |
| O8 / A3 opening/input | Separate durable passes, branch-local opening, revision, dedupe and captured basis resolved; task-home basis/reads partial (4). |
| O9 / A5 faults/tests | Local versus home error writes, member/request/home test writes, effect provenance and JVM no-analysis decided. Restore blob guarantee and name integration prerequisites (1,3). |
| O10 / A4 proof order | Decided: my.test/check seam and launch/bail in S6; run-test-var repair deferred to S7. This is acceptable if help teaches the working seam. Bounds/work table restored; disarm deadline partial (4). |
| O11 labels/origins/reversals | S5 label/bounds fixed; origins implicit, enumerate them (4); reversed-law citation stale (6). |
| O12 / A7 presentation/lifetime | One result print plus envelope composition, no EDN reread, dual costs and gone handles decided. Durable envelope/late diagnostic boundary partial (3). Both encodings temporarily stored is an explicit experiment choice. |
| A9 reset/history | S4/S6 resets, loss list, live-store adoption and terminated generate rows resolved; retained-data restoration partial (5). |

The v3 reviews' inherited v2 checklists are covered by these rows; their central proof lists remain obligations,
not passing evidence. §7 continuation/profile/proof-budget choices are sensible. Price “filtered measurements”
against the filtering already present (`profile/measurement.clj:142`); specify which history §7.2 retains.
Subtree-count choices belong to the independent dh-fix decision, with measured I/O/format/crash costs; a recount
is mitigation. Do not condition this loop's design on an unpriced recount project. Replacement-spec approval
follows the corrections above, without reopening §1.

## Live evidence, checks and landing boundary

At 09:28Z operator status failed: exact-root PID 83524/start 09:08:01.624Z alive, endpoint unavailable; MCP status
also unavailable. Diagnostic: `data/clusters/default/logs/operator-status-20261007T182823.902.edn`. The JVM was
replaced independently; later status shows PID 99416/start 09:28:25.278Z, archive `e6e618d67`. I neither restarted nor
reset it. It remains **faulted**: one open fault, 5–6 errored receipts, 8 over-bound warnings, no missing layers;
ready 15,008 ms; live heap 986 MB at initial restored status, 1,791 MB later (shared activity, not attributed here).

JVM root/default probe `result/e4f927504d533`, explicit connection, basis 536871156: installed `:author` exists;
`:seon.cluster.eval/origin`, `:agent-ordinal`, `:seon.agent/answered-t` absent; program-facts-call/jvm-request resolve.
Classpath resources name the committed archive. Required that archive's contracts checker normally, no reload.
No SCI branch was created. The API omitted timing/call fields for most successful probes; no counts invented.

Health's cross-branch `seon.turn.world/record-fault` error had no matching packaged/live task. Created
`seon.turn.world/fault-recording-refuses-cross-branch-custody`, id `3d2fb1b41dc8`, result `e20e3089d6b98`:
recording names branch `:agent-1048bc748e6e` under custody `:agent-a664b3b890ee` (2→3 occurrences). This is an
observation of concurrent work, not a reproduction or cause diagnosis. Its proposed proof is named in the task.
Root should preserve this runtime task in the next reset dump.

Linked cost tasks: `seon.sci.eval/acquisition-rebuilds-the-whole-program`,
`seon.sci.eval/a-refused-run-leaves-its-program-in-the-context`,
`seon.cluster.source/changed-identities-walks-history-for-an-adopted-commit`,
`seon.profile/scope-wrappers-report-callback-time-as-work`,
`seon.schema/contract-check-on-three-files-exceeds-one-second`. Health timings are inclusive, not producer
attribution. Two probe mistakes were corrected: `find-var` on the not-yet-required checker namespace
(`ee338dbacd708`), and a string instead of a set in task `:evidence` (`e151611e80fc1`); neither is a product defect.

Required static checks on a git archive of HEAD plus this notes-only diff (no foreign code):

- `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,483 warnings**, 9,882 ms.
- Installed `seon.contracts-compile-test/check`, archived **471 src/test files**, archive `:root`, and
  `:candidate (seon.db/carried-projection @(seon.cluster.boot/connection "default"))`: **findings []**,
  2,096.36 ms, 3,183,871,944 calling-thread allocated bytes; MCP duration 2,117 ms, `:slow`,
  `result/e02e1b795f9c5`. Allocation used ThreadMXBean/getThreadAllocatedBytes around the call. Compilation uses
  Malli `m/function-schema` (`test/seon/contracts_compile_test.clj:719`). Script contracts were not included:
  the known checker defect is `seon.contracts-compile-test/script-with-auto-resolved-keyword-escapes-contract-check`;
  src/script/test **lint** was complete. This census is required validation, not an ordinary-pass budget pass.

After concurrent landing `730b63fa0`, repeated against its archive plus this note: contracts **[]** over 471 files,
2,436.56 ms /3,174,357,016 calling-thread bytes, MCP 2,464 ms, `:slow`, `result/e5e475d667f5a`.
The checker running on default was still the `e6e618d67` implementation; this checks candidate contracts, not the
new checker's performance or behavior. Landing lint: **0 errors, 1,483 warnings**, 7,040 ms.

Foreign boundary: contract-check owns fn/checker edits; defn-cost owns schema edits; forgive-fix owns turn/reader
edits; Datahike/http-kit and unrelated test changes are dirty. None was loaded, altered or committed here.
No model calls, test runs, extra JVMs, pollers, delegation, worktrees or branch mutations. No runtime test is
needed for this Markdown-only change. Root's focused implementation list (preserve/convert, not run here):

- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
- `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`
- `seon.turn-test/settlement-claims-covered-messages-without-removing-their-wakes`
- `seon.effect-test/effect-refs-its-evaluation-and-handler`
- `seon.blob-error-test/publication-preserves-a-refused-root-transaction`

New proof obligations: prose quote before definition; pending writer/GC permit retained through unknown outcome;
late diagnostic visible after restart; input home/agent basis race; host handles without SCI acquisition;
incremental/restarted origin parity; restored reset manifest. These are not claimed installed tests.

Design check: the existing fold, stage values, writer and proc remain the smallest composition. Delete the old
readers/recorders and retain the shared entrance at every HEAD; findings above identify where v4 has not yet
made that contract complete. Changed path: this note only; net src/test lines **0**. No optimization, boot,
adoption, model-loop, concurrency or test-success proof claimed. **No restart or reset needed for this review.**
