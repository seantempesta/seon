---
title: REPL plan, merged from the Opus and Astra audits
type: research
status: proposed; policy questions in section 4 await root or owner
date: 2026-10-08
lane: repl-plan
---

# REPL plan: a real SCI REPL with light forgiveness and one recording transaction

Inputs: [Opus audit](repl-audit-opus-audit.md), [Astra audit](repl-auditor-audit.md) with its
[evidence](repl-auditor-evidence.json). Every disputed or single-audit row below was re-run on 2026-10-08 as SCI agent
`repl-plan` (own branch, namespace `plan.scratch`) and timed by a JVM-mode driver (`repl-plan-driver`) that only calls
`seon.mcp/call` inside a `let`; nothing was defined in default's JVM. Default runs `b20762d6b`, health `:faulted`.
This plan supersedes the S5-04 slice ledger in [admission-design.md](../s5-admission/admission-design.md) section 7;
what still applies is folded into section 5.

## 1. Verdict and agreed findings

SCI evaluates every form once with `sci/eval-form`, and SCI's own language is honest: definitions, arity changes,
macros, records, multimethods, aliases, `in-ns`, `ns-unmap`, lazy seqs and exceptions all match plain SCI. The damage
is Seon policy layered around SCI: a database permission check vetoes `remove-ns`, `ns-unalias` and `alter-var-root`
before SCI runs them and answers the refusal as a success value; a reader policy refuses record literals, agent tag
readers and any map with a `:seon.repl/*` key; a namespace change is lost when its form throws; a contract that fails to
arm replaces the function; printed output reaches the reply with no budget; and the model-reply reader splits strings
at fence lines and calls valid one-token forms prose. Both audits agree on these. They also agree that reading costs
little (2-5 ms per 28.6 KB), that about 1,300 lines of hand scanning in `seon.sci.forgive` and `seon.sci.reader` do
work edamame and SCI already do, and that the real cost is recording: `(+ 1 1)` costs 43-59 ms and 55-63 MB through
MCP, against 0.015 ms and 0.011 MB in plain SCI.

Differential (exact inputs; "re-run" marks rows I re-ran because the audits disagreed or only one reported them):

| Input | Plain SCI | Seon now | Cause | Fix (slice) |
|---|---|---|---|---|
| `(create-ns 'a)` `(remove-ns 'a)` `(find-ns 'a)` | `nil` | refusal map as the value, `isError false`; namespace kept | `seon.sci.eval/native-mutation` → `my.program/native-call-refusal` | delete the veto (R1) |
| `(alias 'c 'clojure.core)` `(ns-unalias *ns* 'c)` | alias gone | refusal value, alias kept | same | R1 |
| `(defn pf [x] x)`, next call `(alter-var-root #'pf (constantly inc))` `(pf 1)` | `2` | refusal value (72 ms, 1,373 calls), `1`. Re-run: both audits right; it refuses only once `pf` is committed | same | R1 |
| `{:seon.repl/value 2}` | the map | "Only the REPL writes responses" (re-run, confirmed) | `seon.sci.reader/response-map?` | delete (R2) |
| `(defrecord R [x])` `(:x #plan.scratch.R{:x 8})` | `8` | "Reader tag is not accepted" (re-run, confirmed) | `seon.sci.reader/accepted-reader` replaces SCI's `:readers` (`sci/impl/parser.cljc:144-166`) | R2 |
| `(set! *data-readers* {'p/t inc})` `#p/t 1` | `2` | "Can't change/establish root binding"; then tag refused (re-run, confirmed) | no pass-wide `parser/data-readers` binding (`sci/impl/interpreter.cljc:99-101`) plus the tag policy | R2 |
| `(do (in-ns 'b) (throw (ex-info "x" {})))` `(ns-name *ns*)` | `b` | `plan.scratch` (re-run, confirmed) | `seon.sci.eval/evaluate` keeps the ending namespace only on success | R3 |
| `(defn f {:malli/schema [:bogus]} [x] x)` `(f 1)` | `1` | ~1,000-token diagnostic; `(f 1)` throws "contract did not arm" | `seon.sci.arm/refusing-root` | R4 |
| `(dotimes [i 20000] (println i))` | prints | 129,208-char reply (~32K tokens), 87 ms, 145 MB (re-run; Opus's 300K lines gave 2.3 MB) | `seon.repl/response-entries` prints `:out` with `print/string-literal`, no budget | R5 |
| `(let [k 5] (defn closes [] k))` | works | works live; **no program entity** (re-run: `pull [:seon.fn/sym 'plan.scratch/closes]` → `nil`) | `seon.turn.batch/captured-locals` keeps it private | policy 4.6 |
| `#_ 1` alone | `nil` | "Your evaluation had no form" (re-run) | `seon.turn/code-sources` | policy 4.7 |
| `(+ 1 2))` | throws, `3` lost | `3`, then "Unmatched delimiter" | Seon better; balancing drops the stray closer with a note (R6) | R6 |
| `(throw …)` then `(+ 1 2)` | `eval-string*` stops | error, then `3` | Seon matches a serial REPL; keep | — |
| `#?(:clj :yes :cljs :no)` | `nil` (no features) | `:yes` | Seon passes `#{:clj}` | policy 4.1 |
| `(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (str x))` `(f 1)` | `"1"` | refuses its result | armed contract | policy 4.3 |
| `#"a\(b"` shown | `#"a\(b"` | `#object[java.util.regex.Pattern …]` | value printer's object fallback | R8 |
| `(def x 1)` shown | `#'user/x` | `{:seon.render.var/symbol …}` plus a ~70-token persistence note | Var render, `seon.repl/def-note` | R8 |
| `this is prose (+ 1 2)` (MCP, code mode) | "Unable to resolve symbol: this", stops | three errors (`is` is `clojure.test/is`), then `3` | same language; Seon continues serially | — |
| Model reply `(str "a (\n```\n) b")` | the string | split into 4 sources (re-run) | `seon.sci.forgive/region-end` classifies lines before reading | R6 |
| Model reply `##NaN`, `` `(+ 1 2)``, `\newline`, `42`, `result/e1a2b3c4d5e6` | values | "Your reply had no form" (re-run) | `seon.sci.forgive/form-start?` whitelist | R6, policy 4.4 |
| Model reply `(Note: this is prose) and more` | — | `and`, `more` evaluated (re-run) | rest of a form's line is code | R6, policy 4.5 |
| Model reply `(defn f [x]\n  (+ x 1)\nNow call it.\n(f 1)` | — | one unreadable source (re-run) | no delimiter repair since Parinfer's deletion | R6 |
| `(+ 1 1)` cost via `seon.mcp/call` | 0.015 ms, 0.011 MB | 43-59 ms, 55-63 MB (re-run, 4 samples) | writer-side recording (Opus sampling: `close-folded-call`, `system-run-call`, `write-owned-values-error`) | budget rows; cost lanes |
| `(defn small-fN [a b] (+ a b))` cost | 0.036-0.126 ms | 110-135 ms, 159-162 MB (re-run, 4 samples) | per-defn projection and analysis | R7 (S5-04) |
| `(+ 1 1)` after `(set! *print-length* 50)` | — | 170-231 ms, 341-534 MB every run (re-run, confirmed) | not located (task `seon.sci.eval/a-set-print-length-taxes-every-later-run`) | R9 |

Disagreements resolved by re-running:

- **defn cost.** Opus measured 250-340 ms / 510-680 MB, Astra 78 ms through an in-JVM MCP call. Today it is
  110-135 ms / 160 MB per `defn` through `seon.mcp/call`: `defn-cost3` landed between the two audits. Neither number is
  current; this plan's budget rows use mine.
- **The first form after a `defn`.** Opus saw 230 ms / 530 MB once. Not reproduced: 46 ms / 64 MB. Dropped.
- **The `set!` tax.** Opus only. Reproduced: 170 ms / 341 MB steady after `(set! *print-length* 50)`. Kept.
- **Nested closure `defn`.** Astra: "no definition disappeared"; Opus: silently not recorded. Both true: the Var works
  live and across passes, but no program entity is stored, so restart, write-back and merge lose it.
- **`alter-var-root`.** Opus listed it as the same (a private, uncommitted Var); Astra as refused (a committed one).
  Both true: the veto fires only for a Var with a stored declaration.
- **`#?` and `set! *print-length*`.** Opus counts them as better, Astra as configuration. Both are deliberate
  configuration; `#?` is policy 4.1.
- **Sources in the paid model path.** Astra only, from source: `seon.turn/stage-reply!` and `plan-tx` commit an intent
  before evaluation, a second transaction per pass. Confirmed by reading `turn.clj:75,485,4519`; not measured (no paid
  call). R10.
- **Render execution.** Astra only, from source: `seon.sci.eval/evaluated-source` reads and evaluates forms a render
  returned. Confirmed by reading `eval.clj:3515-3563`. R8.

## 2. The target REPL path

1. **Text in.** Code mode (MCP, tests) takes text as is; reply mode (model text) applies light forgiveness.
2. **Light forgiveness, one linear scan.** It tracks string, char, comment and delimiter state across lines. Outside a
   code fence and outside an open form, a prose line becomes a `;` comment. Balancing changes text only when the region
   is unbalanced: then a stray closer is dropped, and missing closers are inserted before the next column-zero form or
   prose line. Each change is a note `{:line n :inserted ")"}` or `{:line n :dropped "}"}`. Valid Clojure passes
   through byte for byte.
3. **Read each form once.** `sci/parse-next+string` (`sci/core.cljc:400`) at the live cursor, after the previous form
   ran. The only options are `:features #{:clj}` and `:end-location true`, so SCI owns tags, records, `*data-readers*`,
   `#=`, `::alias` and syntax-quote.
4. **Evaluate.** `sci/eval-form` inside one pass-wide binding frame of `sci/ns` and `parser/data-readers`, as
   `eval-string*` does. Arming and the interrupt stay. The ending namespace is captured in a `finally`.
5. **Observe.** The fork's write and expansion observers capture each write as data (S5-04b). Nothing is refused.
6. **Print.** Every shown value goes through the value printer: Var, regex, `:out` and `:err` under its one budget, with
   dig forms for what it cuts.
7. **Record.** After the last form: one clj-kondo analysis, fold to the final state, and **one** settlement transaction.
   A writer refusal becomes a diagnostic beside the values and never changes them.

Survive as they are: `seon.sci.observe`, the SCI fork's observers, `seon.sci.kernel/with-arm`, `seon.instrument`,
`seon.batch` fold totality, `seon.turn/settle-batch!`, `seon.render.value`, `seon.print`.
Shrink: `seon.sci.forgive` (422 → ~120: one scan, line classes, fences); `seon.sci.batch/parse-options` (policy gone);
`seon.sci.eval/evaluate` (native-mutation rebinding, mid-form output throw, `evaluated-source` gone);
`seon.sci.arm/arm!` (no `refusing-root`); `seon.repl/response-entries` (`:out` through the printer, `def-note` gone);
`seon.turn` (`segmented-sources`, `planned-sources`, `code-sources` become one text plus notes; `read-row` gone);
`seon.turn.batch` (`apply-facts` per form, `revalidate` loop, `captured-locals` gone per S5-04).
Delete: `seon.sci.eval/native-mutation`, `my.program/native-call-refusal`; in `seon.sci.reader`: `accepted-reader`,
`refusal-handler`, `reject-read-eval`, `response-map?`, `response-error`, then `declaration-facts` and the helpers it
alone uses, plus the recovery and fence machinery (`recovering-events`, `parse-classification`, `recovery-point`,
`balanced-end`, `line-start-anchor`, `preceding-comment-block`, `token-end`, `skip-fences!`, `advance-to!`);
`seon.batch/apply-pending`.

## 3. Ranked delete/replace list

| # | Delete or replace | Lines removed | Behavior fixed (differential) | Risk | Replacement |
|---|---|---:|---|---|---|
| R1 | `native-mutation`, its `build-base-ctx` rebinding, `my.program/native-call-refusal` | ~65 | `remove-ns`, `ns-unalias`, `alter-var-root`, `intern` behave as in SCI | low; removal facts must come from the observer's `:remove-ns`/`:unmap` (`sci/impl/namespaces.cljc:606-611`) | SCI runs; derive records the final state with a finding for surviving referrers (S5-04 question 4.1) |
| R2 | `accepted-reader`, `refusal-handler`, `reject-read-eval`, `response-map?`, `response-error`; add `parser/data-readers` to the pass frame | ~45 | record literals, agent tag readers, `set! *data-readers*`, maps with `:seon.repl/*` keys | low; `#=` stays refused because SCI's `*read-eval*` defaults false | SCI's parser (`parser.cljc:144-166`) |
| R3 | ending-namespace capture only on success | ~5 | `in-ns` before a throw is kept | low | capture in `finally` |
| R4 | `seon.sci.arm/refusing-root` | ~10 | a malformed contract leaves the function callable | low; merge still refuses it (contract law) | diagnostic beside the value |
| R5 | `:out` via `print/string-literal`; `put-printed!` throwing mid-form | ~25 | 20K `println` lines answer within the printer budget; the form completes | medium: stored output size changes | value printer budget plus a dig form; the full text stored as one blob |
| R6 | `seon.sci.forgive` hand scanners (`string-end`, `closer-end`, `token-end`, `line-and-column`, `advance`, `reader-at`, `span-step`, `read-one`, `segments`); `seon.turn/read-row` second read | ~360 | strings that hold fence lines; one-token forms at column 0; prose after a form; unbalanced replies repaired | medium: every model reply passes here | one balancing scan plus `parse-next+string` at the live cursor (~120 lines) |
| R7 | per-form `apply-pending` / `db/with`, the facts-error stamp in `entry-evaluation`, the untrusted-value facts refusal, `captured-locals`' second kondo run | ~200 | a successful evaluation is never marked failed; defn cost falls (S5-04 table: 5 defns 360 → ~14 ms of analysis) | medium; already designed (S5-04) | one analysis and fold at the end of the pass |
| R8 | `seon.sci.eval/evaluated-source`; `seon.repl/def-note`; regex and Var object fallbacks | ~100 | renders return data; `#'ns/x`; `#"a\(b"`; no ~70-token note per `def` | low | printer spelling (`core_print.clj:409` for regex) |
| R9 | the cause of the `set!` tax (not yet located) | ? | (+ 1 1) after `set! *print-length*` back to the floor | unknown until measured | the owner of the session print options |
| R10 | `stage-reply!` / `plan-tx` intent transaction in the paid path | ~80 | one transaction per pass for model replies | medium; resume consumers read the intent | the request carries the text; settlement records it (agent-loop cut) |
| R11 | `seon.sci.reader` declaration and recovery machinery (`declaration-facts` and helpers, recovery, fence skipping) | ~650 | stored source is read once by SCI's reader; no hand alias tables or `pr-str` round trips | high: eight callers (`cluster/agent.clj:185`, `turn.clj:1706,2338`, `render/value.clj:1768`, `render/ns.clj:246`, `sci/eval.clj:559,1940,3535`) | `parse-next+string`; declarations from the clj-kondo analysis already run |

Not on this list but the largest cost: the writer floor (43-59 ms, 55-63 MB per trivial pass), owned by the cost lanes.

## 4. Policy decisions (root or owner), with recommendations

1. **Should `#?` pick `:clj`?** Recommend **yes**. Seon runs on the JVM and agents write `.clj`/`.cljc`; a REPL with
   `:clj` features is what `clojure.main` gives. Plain SCI's empty set is its embedding default, not a law.
2. **Should an exception restore the previous namespace?** Recommend **no**: keep the new one, as `clojure.main` and
   SCI do (R3).
3. **Should armed contracts refuse a violated return?** Recommend **yes** for a valid contract (CLAUDE.md: "invalid
   outputs refuse"), and **never** replace the function when the contract itself is malformed (R4): that value returns
   with its diagnostic and merge refuses it.
4. **Is a column-zero `42` or a bare `result/e…` line code?** Recommend: outside fences, a line is code when it starts
   with `(`, `[`, `{`, `` ` ``, `'`, `@`, `^` or `#` followed by a non-space, or when the whole trimmed line reads as
   exactly one literal form (number, string, keyword, character, `##NaN`) or one namespace-qualified symbol. So `42`,
   `##NaN`, `` `(+ 1 2) ``, `\newline` and `result/e1a2b3c4d5e6` are code, while `# Heading`, `1. First step`, `Done.`
   and `x` stay prose.
5. **Does prose after a form on its line run?** Recommend **no** in reply mode: after a form ends mid-line, the rest is
   code only if it starts a form or `;`, otherwise it becomes that form's comment. In code mode everything is code, as
   in any REPL.
6. **Is a nested `defn` that captures locals program?** Recommend recording it with its enclosing top-level form as its
   source, so a reload reproduces it as a file would; today it is lost on restart and merge without a note. If root
   declines, deliver a note beside the value saying it is live only.
7. **Is input with no forms (`#_ 1`, only comments) an error?** Recommend answering `nil` without an error in code mode,
   as `eval-string*` does; in reply mode keep "Your reply had no form", which tells the model to act.
8. **Should code mode skip fence lines?** Recommend **yes**: it only drops a line that is exactly a fence outside any
   form or string, and that line is never valid Clojure on its own.

## 5. Slices

Each slice is about 15 minutes, claims its files, and lands with `bin/lane commit --test …`. The proof is a new table
test, `seon.repl-differential-test`, in the style of `seon.mcp-entrance-test`: each row sends its exact input through
`seon.mcp/call` (`eval_clj`, SCI mode, a fresh agent id per row) and asserts **data**, not printed text: the row's
last form returns a value compared with `=` through a later call (`(= expected result/eXXXX)` → `true`), or the
stored evaluation's `:seon.cluster.eval/error` presence. Each slice adds its rows. Budget row (every slice): the
`(+ 1 1)` and one-`defn` MCP calls, timed as in section 1, must not regress over 20% or 50 ms (today 43-59 ms / 55-63
MB and 110-135 ms / 160 MB), and the slice reports its own row.

| Id | Change | Files | Proof rows (`seon.repl-differential-test`) | Budget | Deps |
|---|---|---|---|---|---|
| P0 | Create the table test and its helper; rows that already pass (add, macro, records, alias, unmap, exception-continues) | `test/seon/repl_differential_test.clj` | `plain-sci-rows-answer-as-plain-sci` | test < 5 s | none |
| P1 | R1: delete the native veto; derive stores the final state for `:remove-ns`/`:unmap`, a finding for surviving referrers (S5-04i folded) | `src/seon/sci/eval.clj`, `src/my/program.clj`, `src/seon/turn/batch.clj` (`removal-facts`), test | `remove-ns-removes`, `ns-unalias-removes`, `alter-var-root-of-a-committed-var-runs` | `alter-var-root` 72 ms → floor | P0 |
| P2 | R2: SCI owns reader policy; pass frame binds `parser/data-readers` | `src/seon/sci/reader.cljc`, `src/seon/sci/batch.clj`, `src/seon/sci/forgive.clj` (`response-map?` caller), test | `record-literal-reads`, `set-data-readers-applies-to-the-next-form`, `a-seon-repl-keyed-map-is-data`, `read-eval-stays-refused` | floor | P0 |
| P3 | R3: ending namespace in `finally` | `src/seon/sci/eval.clj`, test | `in-ns-before-a-throw-is-kept` | floor | P0 |
| P4 | R4: arming failure keeps the root, diagnostic beside | `src/seon/sci/arm.clj`, test | `a-malformed-contract-leaves-the-function-callable` (and the valid-contract row refusing its result, if 4.3 is yes) | floor | P0 |
| P5 | R5: `:out`/`:err` through the value printer's budget with a dig form; no mid-form throw | `src/seon/repl.clj`, `src/seon/sci/eval.clj`, test | `bulk-output-answers-within-the-budget` (reply tokens ≤ budget, dig returns the full count of lines) | 20K lines: reply ≤ budget, ≤ 50 ms over the floor | P0 |
| P6 | R6 forgiveness: one balancing and classifying scan in `seon.sci.forgive`, rules 4.4, 4.5, 4.8; notes beside values | `src/seon/sci/forgive.clj`, `test/seon/sci/forgive_test.clj`, `test/seon/sci/forgive_replies.edn` | reply rows: fence-in-string, `##NaN`, `` ` ``, `\newline`, `42`, `result/…` handle, trailing prose, unbalanced `defn` then prose then call → `2`, stray closer; plus "valid Clojure is unchanged" over `forgive_replies.edn` | **≤ 3 ms and ≤ 8 MB per 28.6 KB reply** (a boxed prototype of the scan measured 3.9 ms / 7.0 MB warm; primitive loop expected under 1 ms) | P0 |
| P7 | R6 one read: the pass reads at the live cursor with `parse-next+string`; `segments`, `read-row`, pre-segmented rows deleted; a run request holds text plus comment notes | `src/seon/turn.clj`, `src/seon/sci/batch.clj`, `src/seon/sci/forgive.clj`, `test/seon/cluster/turn_test.clj` | `an-alias-required-by-one-form-resolves-in-the-next`, all P6 rows still green | 28.6 KB reply read ≤ 3 ms; floor | P6 |
| P8 | S5-04a-h2 and j as designed (captured writes, one analysis, fold, delete `apply-pending` and the facts-error stamp, one kondo call with locals) | per `admission-design.md` §7 | its `seon.cluster.turn-test` rows plus `a-successful-form-is-never-marked-failed` | five-`defn` pass: analysis ≤ 20 ms; defn MCP ≤ 110 ms | P1 |
| P9 | 4.6: nested closure `defn` recorded with its enclosing form (or a note) | `src/seon/turn/batch.clj`, test | `a-closure-defn-is-a-program-entity` | floor | P8 |
| P10 | R8: delete `evaluated-source` and `def-note`; Var as `#'ns/x`; regex spelling | `src/seon/sci/eval.clj`, `src/seon/render.clj`, `src/seon/repl.clj`, `src/seon/render/value.clj`, tests | `a-var-shows-as-a-var`, `a-regex-shows-its-source`, `a-def-adds-no-note` (asserted on render data) | `(def x 1)` reply ≤ 20 tokens | P0 |
| P11 | R9: locate and fix the `set!` tax | owner of `session-print-options` (`src/seon/sci/eval.clj`) | `a-set-print-length-costs-nothing-later` (timing row) | after `set!`, `(+ 1 1)` within 20% of the floor | P0 |
| P12 | 4.7: code-mode input with no forms answers `nil` | `src/seon/turn.clj`, test | `a-discarded-form-answers-nil` | floor | P7 |
| P13 | R11: convert the eight `seon.sci.reader/read` callers to `parse-next+string`, the namespace seed to data; delete the declaration and recovery machinery. First list every consumer of the events' `:seon.fn/*`, `:seon.ns/*` keys | `src/seon/sci/reader.cljc`, the eight caller files, `test/seon/sci/reader_test.clj` | `stored-source-reads-as-it-was-evaluated` | acquisition time unchanged | P7, P8 |
| P14 | R10: the model path's intent transaction folds into the settlement | `src/seon/turn.clj` | `a-model-reply-pass-commits-once` (transaction count from the log) | one transaction per pass | agent-loop cut (root decides) |

Order: P0, then P1-P6 and P10-P11 in parallel (file-disjoint except `seon.sci.eval`: P1, P3, P5, P10, P11 serialize on
it), then P7, P8, P9, P12, P13, P14. The skill `.agents/skills/repl/SKILL.md` claims delimiter repair today; P6 makes
that true again and corrects the skill in the same commit.

## Limits

Timings are in default's shared JVM, warm, all-thread allocation, four samples each. The paid model path and the
render execution path were read, not run. The `set!` tax cause is not located. Plain-SCI numbers are the audits'.
