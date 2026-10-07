---
title: Astra review of landed printer-code a423b8b29
type: research
status: fix-forward
lane: printer-review-astra
---

# Landed diff review

Reviewed **a423b8b297ac9b46645c93b4d99e7f342bdae7a5**, against its parent, not the proposal.
Read CLAUDE.md (including Presentation), lane-rules.md, printer-text/design-notes.md v2 and
printer-code-notes.md. The implementation keeps the existing selector → print-node → op-stream path,
changes rendered text from a leaf to a raw string node, and splices saved shown text into replies.
No implementation was edited by this review.

**Verdict: fix forward.** Keep the splicing, HTML op overrides, acyclic diagnostic terminal and deterministic
repeated-call ordering. Fix session bounds, line-end selection, the missing contract and the measured budget
failure. Do not close tasks 1b420c6cb408 or 5d42a25f9a53 as completely solved. No reason to revert the major
reply-composer improvement.

## Ranked findings

1. **P1 — the armed composed-map HTML probe remains above the owner's 10 MB stop-and-fix budget.**
   Same saved input, parent **11,080,232–11,080,304 allocated bytes**, landed **11,330,064–11,330,136**;
   14,538 → 14,857 armed calls. Landed time is 7.58–14.60 ms, so this is an allocation failure, not a
   one-second time failure. Owner: `seon.render.value/html` → selection/emission; existing task
   `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` (`0d22b08e34c2`). This review does
   not pretend that every byte belongs to a particular child: the sample includes hiccup serialization.
   Allocation grows 249,832 bytes on this input. Ordinary string preparation adds 22 armed calls and about
   61 KB per sample. Several sub-millisecond timing medians worsen by over 20%; details below, not a pass.

2. **P2 — the print-session fix is incomplete, including on MCP SCI with a result handle.**
   `src/seon/sci/eval.clj:3541` gets options from `::print-session` on the context. Only `fork-for-turn`
   creates that carrier (:2835). `evaluate` creates a local carrier otherwise (:3975) and correctly returns
   `:seon.print/options` (:4178), but `seon.turn.batch/show` (:766–788) does not hand those options to
   `shown-result`. A test/member or minimal context can therefore record length 2 and print all nine values.
   MCP SCI goes through `cluster.agent/acquire-context!` and `fork-for-turn`, so its normal path **does**
   receive the carrier; MCP JVM's host bindings are not read by `jvm-show` (:890–916).

   There is a second gap even when options arrive: `prepare` maps length/level to selector bounds, but
   `tuple-node` (:796) and `complete-tail` (:1241) still admit whole collections past them. The latter
   explicitly uses `:past-max-children? true`. `(max 1 length)` and `(max 1 level)` (:1596–1599) also erase
   zero's meaning. Armed examples, with a result handle and length 1/2/0, printed `[1 2 3]` or
   `[0 1 2 3 4 5 6 7 8]` whole; level 0 printed `[[1 2]]` whole. The existing length test uses
   `support/evaluate` without a result handle, making the cut cheaper and missing the whole-admission case.

   Existing tasks: `seon.sci.eval/a-forms-set-print-length-is-recorded-but-not-applied` (`1b420c6cb408`),
   `seon.render.value/session-print-length-loses-to-whole-admission` (`28ee1f9b9f5f`). Read the latter's
   runtime intent; it already includes actual MCP SCI/JVM and zero-bound evidence. No duplicate created.
   Smallest correction: pass the evaluated form's held print-options value through the batch show stage;
   capture the host equivalent at the JVM entrance; distinguish the user's hard length/depth bound from
   a presentation heuristic. Tuple and whole-tail admission must respect that bound, including zero.

3. **P2 — render text still cuts mid-line when an earlier usable line end exists.**
   `src/seon/render/value.clj:1286` accepts a newline only when it is beyond the previously selected chunk.
   That is a condition on the final growth interval, not on the complete shown prefix. With profile budget
   1,500 tokens and `{:seon.render/ai (.repeat (str (.repeat "x" 1000) "\n") 10)}`, the armed landed
   printer selects **4,754 characters**, ending midway through line five; **4,004** is an available line end.
   `(str "first\n" (.repeat "x" 10000))` likewise cuts at 4,754 despite a line end at 6. Neither example is
   the documented first-line-longer-than-budget exception. Short repeated lines and CRLF passed.

   The dig form and accounting are correct in these probes: one raw elision,
   `(seon.print/full result/e0123456789ab)`, and omitted counts 5,256 and 5,252 respectively, exactly
   total minus prefix. Existing task `seon.render.value/render-text-past-the-budget-is-cut-mid-line-with-no-dig-form`
   (`5d42a25f9a53`) remains partially open. Smallest correction: choose the last usable line end of the
   complete prefix, allowing a shrink of the prior chunk; recompute that node's charge. Use a character
   boundary only when no line end permits forward progress. `fill-string`'s greater-than-old-shown guard
   must be considered with `fill-end`, not patched around downstream.

4. **P2 — changed `seon.repl/response-entries` has no contract.**
   `src/seon/repl.clj:164` changes behavior and remains uncontracted. A schema-compilation-only check returns
   `[]`; the **baseline-aware** `seon.contracts-compile-test/check` returns exactly one
   `:incomplete-contract` finding, naming this function. All declared contracts in the four changed source
   files compiled. New helpers (`elided-hiccup`, `diagnostics-shown`, `unavailable`, `shown-width`) have
   contracts; `path-url` gains one; `tuple-node`'s widened requery identity compiles.
   Created `seon.repl/response-entries-changed-without-a-contract` (`82e24059bbed`, 58 ms administrative
   MCP evaluation). Give the changed function complete named input/output contracts and keep the baseline
   comparison in the gate. Compilation of declarations alone cannot detect their absence.

5. **P2, retained debt — this is not literally one printer yet.**
   `data-text` and `diagnostic-line` are deleted, and no new general-purpose printer helper was introduced.
   But `response-entries` still calls `pr-str` for findings (:195), interrupted instants (:207), output
   (:213) and notes (:217). Those are inherited sites in the function changed here, not introduced calls.
   Existing task `seon.print.value/printed-copies-stored-or-sent-instead-of-values` (`c4da2c431c3d`) explicitly
   owns these sites. Values passing through them have not all passed through the value printer.
   `path-url` retains its pre-existing `pr-str` wire encoding of the cursor path; the new arity does not
   introduce that encoding. `plain-projection` now directly uses existing `bounded-text` for its diagnostic
   entry, replacing the same terminal use hidden inside deleted `diagnostic-line`. Thus “no new bounded
   printer” is true, but “no added call to bounded-text” would be false. The design explicitly retains this
   projection-free terminal. The broader Presentation law is not fully achieved by this slice.

## Checks that held, and the limits of the claims

**Diagnostics are acyclic.** Nonstructural prepare → diagnostics-shown → core-text → structural prepare.
Structural prepare cannot invoke a declared renderer; on failure it reaches plain-projection → bounded-text,
which invokes datum and Clojure printing but never prepare. core-text's own fallback is also projection-free.
The armed persistent-poison probe returned in 2.25 ms with one diagnostic at path `[]`; no recursive selection
or stack overflow. `shown-result` now extracts `:seon.render.value/failure` from each diagnostic entry.
This verifies termination of the new terminal composition, not removal of the retained bounded-text machinery.

**Reply splicing held.** `:value`, an already shown `:error`, and `:diagnostics` are copied as shown.
An unshown prose error uses the printer's public `string-literal`. The probe error
`bad\n"quoted"; prose` retained its actual newline and escaped its quotes. `response-order` is unchanged,
and every by-key member is still consumed. Nil and false shown values, diagnostics, result, output, errors,
namespace, milliseconds and call count survived the table (2.86 ms). Findings/repeated survived the slow row.
The diff does not drop a reply key. A raw prose value intentionally makes the complete printed reply not EDN;
that follows the accepted v2 ruling, not an accidental string-quoting regression.

**The semicolon false positives are not a correctness defect.** A final string `"a;b"` receives a newline
before the next key. That newline is outside the already complete string literal, so the data is unchanged.
A real trailing `;` comment also receives a newline, preserving the next key/closing brace. A comment already
terminated by LF needs none. All three rows were checked. This is a conservative separator rule, not a lexer.
The simplest correct rule is to put a newline between every entry and before the closing brace and delete
`ends-in-comment?`; no parser or quote-state machine is warranted. Keeping the conservative check is safe
under the accepted shown-text grammar, though it adds layout noise. It cannot make arbitrary raw render prose
readable EDN, and no such promise should be restored.

**Repeated-call ordering is fixed locally.** `(sort-by (juxt (comp - second) first) repeated)` orders distinct
function rows by descending calls, then symbol. Reversing equal-count `[z/z 12 1]` and `[a/a 12 2]` inputs gave
identical replies, with a/a first (26.34 ms first armed behavior sample; 2.30 ms in the preceding run).
This removes the measured head/as-of iteration-order defect. It does **not** prove the entire context is a
function of basis alone: prompt derivation also receives a viewer/context, code and profile, and renderer
functions can depend on those. No whole-context head/as-of parity experiment was run on the landed store in
this lane. Keep that claim conditional on the same program, viewer and render inputs; S2a's as-of route and
browser paint are outside this commit and need their own proof.

## Armed performance evidence

Separate sequential throwaway JVMs loaded `git archive a423b8b29^` and `git archive a423b8b29`. No worktree.
Pinned dependency directories were linked; dependency-pins.txt came from each revision's gitlinks.
`seon.instrument/apply!` armed the complete loaded closure: **2,429 parent / 2,430 landed Vars**.
Both returned successful registration, not a refusal. Each probe ran on an explicit **platform thread** with
an explicit batch sink, 20 warmups and five samples. Exact allocation uses ThreadMXBean.getThreadAllocatedBytes;
call counts are sink deltas. Inputs were constructed before timing. No provider or test runner was called.

The units carry a real detached immutable Datahike database from `datahike.db/init-db []`, an explicit fixed
profile, and the saved context/composed/help inputs used by the landing. This is armed selector/emitter/composer
evidence, not a booted store/adoption or a full MCP pass. No store is opened or transacted by these timed probes:
**0 commits, 0 store bytes**. Allocation is measured; retained heap and peak RSS were not measured.

| Probe | Parent median ms | Landed median ms | Parent allocated bytes | Landed allocated bytes | Parent → landed calls |
|---|---:|---:|---:|---:|---:|
| context AI text | 1.599 | 1.988 | 814,536–855,168 | 868,584–909,120 | 261 → 278 |
| context HTML string | 1.036 | 0.836 | 466,592–675,368 | 518,840–518,912 | 241 → 264 |
| composed-map HTML | 10.066 | 11.158 | 11,080,232–11,080,304 | 11,330,064–11,330,136 | 14,538 → 14,857 |
| help reply | 0.306 | 0.017 | 251,432 | 43,768 | 20 → 14 |
| 108,891-coordinate EDN reply | 0.769 | 0.112 | 7,767,024 | 2,185,624 | 16 → 13 |
| 108,891-coordinate dense reply | 41.532 | 0.149 | 97,838,832 | 1,096,424 | 16 → 13 |
| prepare 14,000-character string | 0.653 | 0.722 | 653,808 | 715,008 | 247 → 269 |
| prepare 140,000-character string | 0.842 | 0.956 | 653,912 | 715,112 | 247 → 269 |
| prepare 1,400,000-character string | 0.511 | 0.651 | 609,152 | 679,160–715,352 | 247 → 269 |

The landed preparation samples have one elision, zero diagnostics, and constant work as input length grows.
No successful timed subject exceeded one second. The composed-map HTML allocation fails 10 MB on **both**
revisions. Context-AI median rises 24.3%; largest-string preparation median rises 27.3%. These are below 1 ms
absolute regressions but cross the stated 20% rule. JVM optimization/load noise affects individual samples;
the extra armed calls and allocation are positive evidence, so this lane does not dismiss the regressions as
a pass. Recheck/calibrate on the producer fix. The dense-entry improvement is approximately 279× in median
wall time and 89× in allocated bytes.

Output sizes, as estimated tokens (3.2 characters/token): context AI about 1,485; context HTML serialized
markup about 1,651; composed-map HTML markup about 8,365; help reply about 1,128; each large reply about 34,043.
Coordinate lengths in the table identify the measured input, not a presentation budget in characters.

Reproduction: [performance driver](review-diff-astra-probe.clj), [behavior/contract driver](review-diff-astra-behavior.clj).
From the respective archive: `clojure -J-Xmx3g -M:dev:test ../probe.clj` (the saved driver was named probe.clj)
and, on landed, `clojure -J-Xmx3g -M:dev:test ../raw-debug.clj`. The behavior driver expects the parent archive
at `../parent/` for its baseline-aware contracts check. These are probes, not calls to seon.test/run.
The performance input directory is the landing's existing printer-code scratchpad, named in both drivers.
Input SHA-256 identities:

- context.txt: `f2b584f2f8c210c9296f1958093a9331b3792d63a7b79c51f505dbd86f300503`
- composed.edn: `c6ed1391e70c1a801f3bb7208df1b5d4a0ec048e8fdb1d6dfbaaf6e60558be9e`
- help.txt: `e0b68734f8e6c986e60774399dd12a8468944f1bbab7b19da17b30c0e55239b1`

The ordinary-string and dense-reply inputs are constructed by the committed driver and need no saved context.
The landing's **100-form MCP pass was not remeasured**: these throwaways have no booted cluster or writer.
Its before-only 560 MB process allocation is not an after measurement. Root should repeat that pass after the
central landing/restart; do not claim that this review proves its current cost. An attempted operator start
refused immediately because its isolated root directory did not exist; no JVM/store was created or stopped.

## Verification, authorities and foreign boundary

- First bin/seon status and MCP runtime_status both reported default **faulted**, PID 13771, start instant
  2026-10-07T12:41:46.451Z, running archive 25bb94be0, not the reviewed commit. Four open-fault signatures,
  nine failed tests and fifteen errored receipts were visible. Historical call-with-projection-state max
  451,774 ms linked tasks c4ece4f4eec9/d27179bab996; publication/index/adoption warnings also had existing tasks.
  Final status still has that same process, faulted. These are baseline health observations, not new failures
  of this review. No default prototype, reload, restart, reset or test run occurred.
- Documentation-slice contract check against archived HEAD 13b850d308881f4f93fbe6db3e99292210869402:
  `[]`, **27.829 ms**, 29 ms MCP envelope, zero changed admitted contracts. Its installed checker was required
  from default's committed classpath; it read the archive as data. This is distinct from the landed
  implementation check, which compiles but reports the missing contract when parent baselines are supplied.
- `clj-kondo --lint src script test --cache false --parallel` on the initial landed archive:
  **0 errors / 1,454 warnings, 5,168 ms**. Repeated on HEAD a9d1c4d33: **0 errors / 1,454 warnings, 6,546 ms**.
  Final HEAD 13b850d30 plus this docs-only diff: `linting took 4266ms, errors: 0, warnings: 1454`. Docs and probe artifacts do not change those lint roots. No shared
  analyzer cache was written. These explicitly requested whole-tree checks exceed a second; they are not
  ordinary pass costs and do not excuse the timed printer failures.
- Foreign boundary: uncommitted config.clj, db.clj, sci/kernel.clj, turn.clj, turn/batch.clj, web.clj, CSS and
  their tests, HTTP-kit dirt, pass-cost and other untracked work were excluded. fn.clj/fn_test.clj changed
  during the review and landed as a9d1c4d33; final lint includes that committed change. The web/CSS patch then landed
  as 13b850d30; final lint includes it too, without treating it as part of reviewed a423b8b29. The A/B JVMs remain
  exact parent/a423b8b29. No foreign session was resumed, messaged or edited. S2a's route/CSS patch, absent from a423b8b29 and committed during this review,
  is not counted as an installed UI fix on default. No browser paint or route-click proof was performed.
- Dependency authorities read: Malli 43c6dca8d2 `core.cljc:3130` function-schema compiles the function schema
  against its registry; Seon's checker calls it at contracts_compile_test.clj:794. SCI ce0aa53b9b
  `core.cljc:334` forks by replacing only :env, so Seon's print-session ownership is Seon's responsibility;
  SCI's print-length/print-level Vars are at :167–168. Fipp 255a19061f `engine.cljc:10–25` supplies the op
  stream, not Seon's selection or dig forms. Datahike e5453d967b `db.cljc:1010` supplies init-db for the
  detached immutable probe value. No new dependency mechanism was invented.
- Probe mistakes, corrected before accepting evidence: archive initially lacked dependency-pins.txt; one
  require vector was unquoted; an instrumentation inventory was treated as a map instead of a set; the
  first armed units omitted the required database; the first inline-render units omitted caps/time limit.
  Those refusals were inspected, not counted as successful renders. They are caller mistakes, not tasks.
  The corrected raw-text rows have zero diagnostics. All probe JVMs exited; no runtime resource remains.

## Central focused tests requested from root

This lane started **no test runs**. There is no source/test change in this review, so the notes commit needs
no runtime test. For the implementation and its fixes, root's central list should include:

- `seon.render.value-test/a-session-print-length-bounds-the-shown-members` — extend with result handles,
  short vector/map/set, zero and level, plus non-turn test context. Add an MCP JVM binding row at its entrance.
- `seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved` — tighten the existing set! row to inspect
  members/cut data, not merely the presence of :value.
- `seon.render.value-test/a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form` — add sparse
  newlines and a short first line followed by a long line; the current short-line/CRLF rows miss the defect.
- `seon.render.value-test/a-terminal-structural-failure-does-not-reenter-selection` and
  `seon.render.value-test/a-failing-renderer-never-replaces-the-value`.
- `seon.repl-test/a-rendered-value-with-cuts-is-spliced-verbatim` and
  `seon.repl-test/an-error-in-the-printers-grammar-is-a-map-not-a-string`.
- `seon.print-test/a-string-shows-its-characters-in-html-and-each-cut-continues` and
  `seon.render.value-test/an-html-cut-of-a-long-string-shows-text-and-continues`.
- `seon.contracts-compile-test/a-new-or-changed-function-needs-a-complete-contract`, plus the baseline-aware
  compile command on these four files. The existing generic test does not itself inspect this diff.
- S2a integration: `seon.render.web-test/debug-header-identifies-the-handed-program-and-projection`, after
  its owning patch. The known red admitted by the landing note must not disappear from the central list.

Design check: the smaller compositions held for splicing and terminal diagnostics. The line-end design assumed
that growing an ordinary string to its final interval implied a line-ended complete prefix; the code disproves
that assumption. The session design confused a context carrier with the evaluated form's options and did not
account for whole-admission optimizations. Fix these at their producers; do not add reply-specific clipping or
an alternate printer. The semicolon heuristic needs no correctness workaround.

Review deliverables: this note and two probe drivers, **0 net src / 0 net test lines**. Claimed before writing
with bin/lane under printer-review-astra; committed through bin/lane, then released. Notes require no restart
or reset. The landed implementation still needs root's coordinated adoption/restart and focused proofs; this
review does not authorize or perform them. Exact review commit and artifact size are reported in the final reply.
