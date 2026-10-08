---
title: REPL audit, Opus lane
type: research
status: complete
date: 2026-10-08
lane: repl-audit-opus
---

# REPL audit (lane repl-audit-opus, 2026-10-08)

HEAD `bd63ce073`, default cluster live (health `:faulted`, two open faults). Probes ran as SCI agents
`repl-audit-opus*` on their own branches and as JVM-mode read-only drivers (`repl-audit-opus-driver`); plain SCI ran
in a throwaway JVM (`clojure -Sdeps` over `reference-code/sci` + `reference-code/edamame`, Clojure 1.12.0, one
`sci/init` context, forms through `sci/eval-string*`). Inputs:
`/private/tmp/.../scratchpad/diff/inputs.edn` (61 cases) and `inputs2.edn` (16 cases), copied into the tables below.

## 1. Verdict

Evaluation itself is honest: every form is one `sci/eval-form` on the agent's SCI context, and definitions are
observed through a small hook in our SCI fork (`sci.lang/*write-observer*`, `*expansion-observer*`), which is the right
seam. Reading is not the problem the owner fears in cost: the reply segmenter costs 2.5 ms / 5.1 MB on a 28.6 KB
reply, and SCI or edamame does every real read. It is too much code for what it does, though. About 1,330 lines
(`seon.sci.forgive` 422, `seon.sci.reader` 909) hand-scan strings, closers, tokens, lines and columns, and every form
is read twice. The real damage is elsewhere, and the owner will be angry about it:

1. **Valid Clojure is refused or broken.** `remove-ns` returns a refusal map as its *value* (MCP `isError false`) and
   leaves the namespace in place. A `defn` whose contract cannot arm has its root replaced by a function that throws.
   A facts refusal stamps a successful evaluation with an error, and the definition is not recorded.
2. **Cost.** Plain SCI evaluates `(+ 1 1)` in 0.0135 ms and 10 KB. Seon takes 45 ms and 55 MB, about 3,000× the time
   and 5,000× the allocation. A `defn` takes 250–340 ms and 510–680 MB, against 0.036 ms and 40 KB in plain SCI. The
   first form after a `defn` costs 230 ms and 530 MB. After one `(set! *print-length* 50)`, every later run of that
   agent costs 150 ms and 340 MB. A new agent's first run costs 550–600 ms and 1.2 GB.

   None of this time is spent in SCI or in reading. It is writer-side recording and validation: transaction
   functions, `write-owned-values-error`, and a speculative projection rebuilt per `defn` settlement.
3. **Bulk.** Printed output bypasses the value printer. 300,000 `println` lines produced a 2,289,210-character MCP
   reply (621,528 tokens). Every `def` adds a ~70-token note. A Var prints as a render map instead of `#'ns/x`.

## 2. The path, step by step

MCP SCI path: `seon.mcp/call` → `eval-clj` → `sci-run` → `seon.cluster.agent/evaluate-source!` → the agent's turn proc
(`seon.turn/turn` → `resume-turn`) → `seon.turn/evaluate-sources` → `seon.batch/evaluate-batch` → `settle-batch!` (one
transaction, confirmed: `turn.clj:3537-3660` makes one `db/transact!` with the program, settle and close transaction
functions) → `seon.mcp/observed` → `seon.render.transcript/history-entries` → `seon.repl` response. A model reply
takes the same path from `submit-source!`, with `seon.turn/planned-sources` (`:reply` mode) in place of `code-sources`.

| # | Step | `file:function` | Lines | What it does | Class |
|---|------|-----------------|-------|--------------|-------|
| 1 | Entrance | `src/seon/mcp.clj:call`, `eval-clj`, `instance`, `execution`, `sci-run` | ~180 | JSON in/out, cluster and agent custody, branch creation for a new agent id | C, minimal |
| 2 | Submission | `src/seon/cluster/agent.clj:evaluate-source!`, `submit-source!`, `submit-source-in-projection`, `segmenter` | ~210 | Starting namespace, segmentation, run id, staged reply, offer onto the agent's request port, await under the turn backstop | C. It prints the sources to measure them: `:seon.turn/reply-size (count (pr-str sources))` (task `seon.cluster.agent/reply-size-is-measured-by-printing-the-sources`) |
| 3 | Segmentation | `src/seon/turn.clj:segmented-sources`, `planned-sources`, `code-sources` | 60 | Text → ordered source rows (text over `max-source` becomes one refused row) | C |
| 4 | Structural pass | `src/seon/sci/forgive.clj` (`segments`, `step`, `reply-step`, `code-step`, `read-at`, `read-one`, `reader-at`, `advance`, `string-end`, `closer-end`, `token-end`, `skip-to`, `fence-line`, `region-end`, `form-start?`, `comment-of`) | 422 | edamame `parse-next` with semantic hooks neutralized finds spans. Lines, fences, tokens, strings and closers are scanned by hand. Prose becomes `;` comments. Nothing is repaired. | **Reply mode: C**, lightweight (2.5 ms / 5.1 MB per 28.6 KB). **Code mode: A**: SCI's own read loop (`sci/impl/interpreter.cljc:94-114`, `parse-next` per form) already does this. Only fence-line skipping is extra (5.3 ms / 9.1 MB per 28.6 KB, against 2.1 ms for a plain `sci/parse-next` loop). Hand scanners `string-end`, `closer-end`, `token-end`, `line-and-column`, char-by-char `advance`, `span-step`: **B**. edamame already reports `:row/:col/:end-row/:end-col` (`edamame/impl/parser.cljc:866`, `normalize-opts:935`, `:end-location` default `:975`), and `sci/source-reader` (`sci/core.cljc:381`) owns the cursor. |
| 5 | Live read | `src/seon/turn.clj:read-row` → `seon.sci.forgive/next-form` → `src/seon/sci/batch.clj:read-stage` (`sci/parse-next` under `seon.sci.reader/parse-options`) | ~70 | Each row read **again**, now semantically, in the current namespace so that a prior form's alias resolves | **A/B**: a second read of every form. SCI resolves aliases because it reads lazily after each eval (`interpreter.cljc:107-113`). Read once with `sci/parse-next+string` (`sci/core.cljc:400`) at the live cursor. The rows exist only because the run request stores pre-segmented sources. |
| 6 | Reader policy | `src/seon/sci/reader.cljc:parse-options`, `accepted-reader`, `reject-read-eval` | ~40 | Refuses tags outside `default-data-readers` and `#=`. Auto-resolve and syntax-quote come from the ctx. | C, minimal. One loss: SCI binds `parser/data-readers` per eval (`interpreter.cljc:101`), but Seon's `:readers` function refuses any agent-defined tag (code reading, not probed). |
| 7 | Stored-source reader | `src/seon/sci/reader.cljc:read`, `read-events`, `recovering-events`, `parse-classification`, `recovery-point`, `balanced-end`, `line-start-anchor`, `preceding-comment-block`, `token-end`, `error-event`, `declaration-facts`, `function-declaration`, `test-declaration`, `schema-declaration`, `namespace-info`, `require-bindings`, `next-reading-context`, `resolved-operation`, `skip-fences!`, `advance-to!` | ~860 | Re-reads stored source for acquisition (`seon.sci.eval/one-event`, `install-row!`), resume (`seon.turn/evaluable-source?`, re-read per `next-ordinal`), the namespace seed (`seon.cluster.agent/namespace-seed-call` prints a form with `pr-str` and reads it back), debug pages and `evaluated-source`. Hand-parses `defn` arglists and docstrings and resolves operators through hand alias tables. Resynchronizes at heuristic column-zero anchors. | **A** for `declaration-facts` and its helpers: clj-kondo already derives declarations in derive (step 11; task `seon.sci.reader/reader-head-check-is-a-second-declaration-derivation`). **B** for recovery (`balanced-end` and `token-end` duplicate edamame's `:edamame/containers` facts). It stores `(pr-str arglists)` and `(pr-str schema)` (`reader.cljc:291,304`): printing into storage. |
| 8 | Fold | `src/seon/batch.clj:evaluate-batch`, `form-step`, `read-step`, `evaluate-step`, `publish-step`, `freeze-step`, `place-step`, `derive-step`, `apply-pending`, `settled-into`, `record-faults` | 761 | Order and totality: a value is kept once published. Timings, allocation, per-form sinks, write queue, diagnostics. | C. `apply-pending` validates each form's facts with `db/with` before the next form and refuses them: **delete**, already ruled (`2026-10-08/s5-admission/admission-design.md`, S5-04h2, not landed). |
| 9 | Turn stages | `src/seon/turn.clj:evaluate-sources`, `evaluate-row`, `show-row`, `publish-row`, `early-disposition?`, `code-evidence`, `entry-bindings` | ~400 | Per-form frame request, registration delta, result handles, disposition rule (`my.agent/done` must be last), executed-definition evidence | C. The disposition rule applies only to replies. |
| 10 | Evaluation | `src/seon/sci/eval.clj:evaluate` | 360 | `sci/eval-form` once; arm and time limit (`kernel/with-arm`, SCI interrupt core); out/err capture bounded at `max-bytes` (8 MB), which **throws mid-form**; print-session `set!` carry; custody, read-evidence and effect bindings; `trusted-evaluation` (a loaded-program change during the form marks the value untrusted, so its facts are refused); a program acquisition refusal throws before **every** form (`(first (:seon.test/acquisition-refusals ...))`, code reading, not probed) | The eval is **A** (plain SCI). Arming, interrupt and custody are **C**. Second-guessing: refusing facts for untrusted values, the acquisition-refusal throw, and an output bound that fails the form. |
| 11 | Observation | `src/seon/sci/observe.clj:observe` + fork `sci/lang.cljc:71-121`, `sci/impl/namespaces.cljc:606-611` | 60 (+ fork hooks) | Each root write, unmap, remove-ns and expansion observed once, on the evaluating ctx only. Namespaces whose resolver state changed. | **C, minimal, correct seam.** SCI upstream has no observer; the fork's three commits (`ac3b0a30`, `ead49957`, `ce0aa53b`) are the smallest form of it. |
| 12 | Arming | `src/seon/sci/arm.clj:arm!`, `arming-projection`, `refusing-root` → `seon.sci.eval/install-function-contract!` → `seon.instrument` | 101 (+1,335 instrument) | Arms a written Var's contract at its write | C (the law is "arm every valid contract"). Fragile: the contract is printed with `pr-str` and read back (task `seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back`). A contract that fails to arm **replaces the definition** (`refusing-root`, new task `seon.sci.arm/an-unarmable-contract-breaks-the-definition`). Malli seam: `malli.core/-instrument` (`malli/core.cljc:3166`). |
| 13 | Derive | `src/seon/turn/batch.clj:derive-form`, `analyze-form`, `analyzed-entities`, `join-write`, `captured-locals`, `write-entity`, `form-relative-expansions` | ~330 | One clj-kondo analysis per defining form. Each write is joined to an analyzed definition by exact source span. A nested `defn` that captures locals is kept private and not persisted. | C (program entities). Uses the right library. Fragile: the span join (`:unanalyzed` / `:ambiguous` drops the definition to private). `captured-locals` runs `kondo/run!` a **second** time with `:locals true` instead of asking the first analysis for locals. |
| 14 | Facts / apply / settle | `src/seon/turn/batch.clj:schema-tx`, `form-facts`, `apply-facts`, `fold-change`, `changed-since-basis`, `program-facts`, `revalidate`, `rebase`, `settle` | ~380 | Program facts; per-form `db/with`; one recording transaction; on writer refusal, re-derive on head and drop refused forms | Settle is C. `apply-facts` per form and `revalidate`/`rebase` **restrict** recording: a refused form's evaluation is stamped `:seon.cluster.eval/error "facts not proposed: …"` (`entry-evaluation`) although SCI succeeded. Delete with S5-04h2. |
| 15 | Writer | `src/seon/turn.clj:settle-batch!`, `system-run-call`, `receipt-settle-batch-call`, `close-folded-call`; `seon.db/prepared-write`, `validate-write-report`, `write-owned-values-error` | (in 6,115 / 5,972) | One transaction, but each of its transaction functions queries, pulls and validates owning values | C, **not minimal**: the whole 45 ms / 55 MB floor of `(+ 1 1)` is here (sampling below). |
| 16 | Show | `src/seon/turn.clj:show-row` → `src/seon/sci/eval.clj:shown-result`, `shown-failure`, `evaluated-source` → `seon.render.value` (1,808) / `seon.print` (856) | ~250 + printer | One value printer, render selection, result handle bound | C |
| 17 | Reply | `src/seon/mcp.clj:observed` → `seon.render.transcript/history-entries` → `src/seon/repl.clj` response | ~420 + 626 | Re-reads the saved run and composes the reply map | C. Defect: `:seon.repl/out` is `print/string-literal` of the whole output (`repl.clj:217`), a second printer with no budget (new task `seon.repl/printed-output-reaches-the-reply-unbounded`). The run is read twice (task `seon.mcp/observation-reads-the-run-twice`). |
| 18 | Native-call refusal | `src/seon/sci/eval.clj:native-mutation` (`build-base-ctx` rebinds `intern alter-var-root remove-ns ns-unalias`) → `src/my/program.clj:native-call-refusal` | ~65 | Refuses `remove-ns` and `ns-unalias` unconditionally when a connection exists, and `intern`/`alter-var-root` of a program Var. The refusal is returned as the call's **value**. | **A, delete.** SCI implements all four, and the fork already observes `:remove-ns` (`namespaces.cljc:606-611`) and `:unmap`, so derive records the removal. Tasks `my.program/native-calls-are-refused-instead-of-routed`, new `my.program/a-refused-native-call-answers-as-a-success-value`. |
| 19 | JVM mode read | `src/seon/turn/batch.clj:jvm-read-next` (`read+string` after `forgive/skip-to`) | 20 | Host read | C, minimal: the reading is Clojure's own `read+string`. |

### Where the floor goes (sampling, default, 30 × `(+ 1 1)` on a fresh agent)

Every 3 ms the sampler took stack traces of all RUNNABLE threads. It kept samples with a `seon.*` frame (267 samples)
and grouped them by outermost phase: `close-folded-call` 62, `system-run-call` 60, `receipt-settle-batch-call` 9,
`wake/route!` 16, other 120.

Inclusive frames: `seon.instrument/arm-var!` 258 (contract wrappers on the stack of nearly every Seon call),
`db/with-declarations` 114, `db/validate-write-report` 105, `db/write-owned-values-error` 86 (`owners-of` 51,
`owning-ancestors` 41), `db/q` 93.

For 8 `defn`s (405 samples) the extra work is `receipt-settle-batch-call → receipt-run → current-run → db/pull →
carried-projection → speculative-projection → with-declarations → read-declarations → load-projection /
projection-from-entities`. A projection is rebuilt inside the settlement transaction. That cost is lane `defn-cost3`'s
(claims `src/seon/db.clj`, `src/seon/schema.clj`), so I did not re-file it. clj-kondo did not appear in any sample.

## 3. Differential: plain SCI vs Seon (MCP `eval_clj` SCI mode)

Same inputs and same order. Plain SCI used one `sci/init` context (`:classes {:allow :all}`, features default). Seon
used agent `repl-audit-opus` / `-2` in `audit.scratch`.

### Worse in Seon

| Input | Plain SCI | Seon | Responsible step |
|-------|-----------|------|------------------|
| `(create-ns 'audit.c)` `(remove-ns 'audit.c)` `(find-ns 'audit.c)` | `nil` (removed) | the second form's **value** is `{:seon.error/operation my.program/native-call-refusal … "Use my.program/remove-ns! so program facts decide before SCI changes."}`, `isError false`; `find-ns` answers `#namespace[audit.c]` | 18 |
| Same with a namespace the run itself created and `defn`'d into (`audit.gone`) | `nil` | same refusal; namespace kept | 18 |
| `(defn badc {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x)` `(badc 1)` | `1` | the defn row carries a bulky `:diagnostics` ("Not admitted to the program … `:malli.core/invalid-schema`"); `(badc 1)` errors "audit.scratch/badc is unavailable: its contract did not arm" | 12 (`refusing-root`), 13/14 (facts refused) |
| `(dotimes [i 300000] (println i))` | prints, value `nil` | MCP reply of **2,289,210 characters** (`:out-tokens 621528`); a 2,000,000-char string *value* answers 5,172 chars | 17 (`seon.repl` `:out` via `string-literal`) |
| `(def x 1)` | `#'user/x` | `{:seon.render.var/symbol audit.scratch/x}` plus a ~70-token persistence `:note` on every `def` (also `atom`, `binding`, `defonce`, `alter-var-root` cases) | 16/17 (task `seon.repl/every-def-repeats-the-same-persistence-note`) |
| `(defn f ([a] a) ([a b] (+ a b)))` after `(defn f [a] a)` | `#'user/f` | `#:seon.render.var{:contract :missing, :symbol …, :example (defn f {:malli/schema …} [x] x)}`: a nag with a wrong-arity example on every redefinition | 16 (Var render) |
| `this is prose` `(+ 1 2)` (code mode) | error "Unable to resolve symbol: this" (whole string stops) | `this` unresolved, `is` → "Can't take value of a macro: #'clojure.test/is" (agent namespaces refer `deftest`/`is`), `prose` unresolved, then `3` | `build-base-ctx` core refers |
| `(let [k 5] (defn closes [] k))` | `#'user/closes`, works | works, but is silently not recorded as program (`captured-locals` → private), so it is lost on restart and merge with no note | 13 |
| `(+ 1 1)` cost | 0.0135 ms, 10 KB | 45 ms, 55 MB (median, fresh agent); 553–596 ms, 1.2 GB as a new agent's first run | 15 |
| `(defn small-f [a b] (+ a b))` cost | 0.036 ms, 40 KB | 280 ms / 607 MB median (247 ms min); contracted 309 ms / 631 MB; `(def small-v 1)` 187 ms / 365 MB | 13–15 (`defn-cost3`) |
| `(+ 1 1)` right after any `defn` | n/a | 230 ms, 523–543 MB (once) | re-acquisition at the next run's fork |
| `(+ 1 1)` after `(set! *print-length* 50)` | n/a | **every** later run 148–150 ms, ~340 MB (was 44 ms / 55 MB); cause not located (new task `seon.sci.eval/a-set-print-length-taxes-every-later-run`) | 10 / fork (`session-print-options`) |

Allocation is the all-thread `ThreadMXBean` delta around the MCP call. Idle background measured 0.13 MB/s, so it is
negligible. The caller (MCP) thread alone allocates ~5.5 MB per call; the rest is on the turn proc's platform thread.

### Same (no difference worth reporting)

`#inst`, `#uuid`, `##NaN ##Inf ##-Inf`, `(Double/isNaN ##NaN)`, `[1 #_ 2 3]`, top-level `#_(boom)`, `#_ #_ :a :b :c`,
regex, char literals including `\(` `\;` `\"`, syntax-quote with unquote and splice (namespace-qualified to the
current ns in both), auto-gensym, multiline strings containing parens, backticks, fences, `;` and `]]`, comments,
`^{:a 1}` and `^:foo` metadata, bare number/keyword/string/`*ns*`, `1 2 3`, `ns` with `:as`/`:refer` plus `::k` and
`::s/k`, `def`/`defn`/arity change/dropped arity with a stale caller (`[3 :arity-error]` in both), `defmacro` and
`macroexpand-1`, `declare` then define, `alias` then `::cs/k` in the same text, `require :refer`, `in-ns` round trip,
`(do (in-ns …) (def …))`, `ns-unmap` of a `def` and of a `defn`, atoms, `binding` on a dynamic Var, lazy seqs, `(def r
(range))` then `(nth r 1000)`, `try/catch` with `ex-data`, uncaught `ex-info`, `letfn` mutual recursion,
`defprotocol`/`defrecord`/`map->R`, `extend-protocol` on `String`, `deftype` field access, `defmulti`/`defmethod`
with `:default`, `(set! *print-length* 3)` then `pr-str`, `binding [*print-length* 3]`, `println` output, `def` of a
fn value, `defonce` twice, `intern`, `alter-var-root` on a private Var, `(def ub)` then `bound?`, `defn` inside `when`,
`var` call, a macro redefined as a fn, `(:import [java.util UUID])`, `deftest` then calling it. Unknown tag `#foo/bar`
and `#=` error in both (different messages).

Armed contracts refusing wrong input or output (`(ci "a")`, `(cg 1)` returning a string) differ from plain SCI on
purpose; that is CLAUDE.md law ("arm every valid contract").

### Better in Seon

| Input | Plain SCI | Seon |
|-------|-----------|------|
| `(+ 1 2))` | throws "Unmatched delimiter"; the `3` is lost | `3`, then the stray `)` as its own read error (as a Clojure REPL does) |
| `(throw (ex-info "first" {}))` `(+ 1 2)` | the whole string stops at the throw | error, then `3` (serial REPL semantics) |
| `#?(:clj :clj-branch …)`, `[#?@(:clj [1 2])]` | `nil`, `[]` (default `sci/init` features) | `:clj-branch`, `[1 2]` |
| A fence around code in code mode | read as triple syntax-quote garbage | fence lines skipped, `42` |
| `(set! *print-length* 2)` `(range 10)` shown | (REPL printer dependent) | shown cut at 2 with a dig form (the old task `seon.sci.eval/a-forms-set-print-length-is-recorded-but-not-applied` no longer reproduces) |
| `@(future (+ 1 2))` | unresolved without SCI's future addon | `3`, with the future owned by the arm |

### Reply-mode reading (model replies; no plain-SCI analogue, judged against the owner's law)

Measured with `(seon.turn/planned-sources text 'user 100000)`:

| Reply text | Sources | Judgement |
|------------|---------|-----------|
| `x\n(+ 1 2)`, `:k\n…`, `42`, `"hello"` | a bare token line is a comment; `42` or `"hello"` alone answers the no-forms refusal | **Against the law** ("naked forms start at the beginning of a line"): a reply that only names `result/e1a2b3c4d5e6` cannot dig a result. New task `seon.sci.forgive/a-bare-token-line-in-a-reply-is-prose`. |
| `(Note: this is prose) and more\n(+ 1 2)` | `(Note: this is prose)`, `and`, `more`, `(+ 1 2)` | **Fragile**: the rest of a line a form started is code, so trailing prose evaluates. New task `seon.sci.forgive/prose-after-a-form-on-its-line-is-evaluated`. |
| `Here:\n  (+ 1 2)` | no forms (indented form is prose) | Matches the law's letter; existing task `seon.sci.forgive/an-indented-definition-outside-a-fence-is-read-as-prose`. |
| `(defn f [x]\n  (+ x 1)\nNow call it.\n(f 1)` | one unreadable source spanning all four lines | No auto-balance since the S2b-1 Parinfer deletion: the agent pays another turn. See §4.1. |
| fenced `clojure` block with prose around it, `~~~` fences, `#{1 2}` line then `# Heading` | correct | Good |

## 4. What to delete or replace, ranked

Estimated lines are source lines removed net of the replacement.

1. **The per-form `db/with` gate and the facts-refusal stamping** (`seon.batch/apply-pending`,
   `seon.turn.batch/apply-facts` per-form use, `revalidate`, `rebase`, `entry-evaluation`'s facts-error branch,
   `seon.sci.eval` untrusted facts refusal): ~180 lines. It restricts recording, against the owner's law. The
   design is already ruled (S5-04h2); land it.
2. **Native-call refusal** (`seon.sci.eval/native-mutation` plus its `build-base-ctx` rebinding,
   `my.program/native-call-refusal`): ~70 lines. Let SCI's `remove-ns`, `ns-unalias`, `intern` and `alter-var-root`
   run; the write observer already records `:remove-ns` and `:unmap`. Today `remove-ns` silently does nothing and
   reports success.
3. **`seon.sci.reader`'s declaration and recovery machinery** (`declaration-facts`, `function-declaration`,
   `test-declaration`, `schema-declaration`, `namespace-info`, `require-bindings`, `next-reading-context`,
   `resolved-operation`, `recovering-events`, `parse-classification`, `recovery-point`, `balanced-end`,
   `line-start-anchor`, `preceding-comment-block`, `token-end`, `error-event`, `skip-fences!`, `advance-to!`): about
   650 of 909 lines. Declarations come from the clj-kondo analysis derive already runs. Stored-source re-reads use
   `sci/parse-next+string` over a source that already parsed once. The namespace seed builds its row from data
   instead of `pr-str` and a re-read.
4. **Replace `seon.sci.forgive` (422 lines) with lightweight forgiveness of about 120 lines** (§4.1): one line
   classifier, one linear delimiter scan, and one `sci/parse-next+string` read per form at the live cursor. That
   deletes the hand scanners (`string-end`, `closer-end`, `token-end`, `line-and-column`, `advance`, `span-step`,
   `reader-at`, `skip-to`'s fence logic duplicated in `reader/skip-fences!`). Net about −300.
5. **The second read of every form** (`seon.turn/read-row` re-reading pre-segmented rows; `turn-rows-state`): ~60
   lines. The run request stores the text plus comment spans, and the fold reads once at the live cursor, as
   `code-request` already can (`seon.sci.batch/read-next`).
6. **`refusing-root` in `seon.sci.arm/arm!`** (~10 lines): keep the evaluated root and put the arming failure beside
   it as a diagnostic.
7. **`:seon.repl/out` through `print/string-literal`** (fix, about ±0 lines): send it through the value printer's
   one budget with a dig form. Today one `println` loop can put 600K tokens into the agent's context and store them.
8. **`captured-locals`' second `kondo/run!`** (~20 lines): ask the first analysis for locals. Better still, record
   the nested `defn` with its enclosing form as source instead of dropping it to private.
9. **`pr-str` round trips on the path**: the contract printed and read back in arming
   (`seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back`), `(pr-str arglists)` and `(pr-str schema)`
   stored by the reader, `(count (pr-str sources))` for reply size, the namespace seed: ~30 lines, and they break
   CLAUDE.md's one-printer law.
10. **The output byte bound that throws mid-form** (`put-printed!` in `seon.sci.eval/evaluate`, ~25 lines): record
    up to the bound and keep running; a REPL never fails a form for printing. Item 7 then bounds what is shown.

Not deletions, but the cost that matters most: the writer-side floor (step 15) and the per-`defn` projection rebuild
(`defn-cost3`). Until those fall, every reading or REPL simplification is noise next to 45 ms / 55 MB per trivial form.

### 4.1 Lightweight forgiveness (owner ruling, 2026-10-08)

The owner asked for this with a budget of a few ms and a few MB per reply: "auto balancing parenthesis and turning
prose into comments … not at the cost of multiple seconds and hundreds of MB."

**Judgement of today's `forgive.clj`.**

- Lightweight and worth keeping: fence regions (`region-end`, `fence-line`, `code-tags`), `comment-of`, and the rule
  that a column-0 form-start line is code. Together they are ~60 lines, costing 2.5 ms on 28.6 KB.
- Fragile hand-parsing a simpler design replaces:
  - `string-end`, `closer-end`, `token-end`, `line-and-column` and the `offset` closure in `read-one` re-implement
    what edamame's location metadata and SCI's source reader already report.
  - `advance` moves a reader one char at a time.
  - Two read stages exist (structural, then live) just to find spans.
  - The rest-of-line-is-code rule makes trailing prose evaluate.
  - There is no delimiter repair at all.

**Replacement, one pass, O(n):**

1. **Classify lines.** Inside a `clojure`/`clj`/`edn`/untagged fence, a line is code. Outside fences, a line is code
   when its first character starts a form (today's `form-start?`) or its whole trimmed text reads as one token. Every
   other line is prose. After a form ends mid-line, the remainder is code only if it starts a form or `;`; otherwise
   it is comment.
2. **Balance delimiters.** One scan over code lines keeps an opener stack, skipping strings, `\c` characters, `;`
   comments and `#"…"` regexes. Each closer either pops a matching opener or is dropped, with a note. When a
   column-0 code line starts a new form, or a prose line begins, while the stack is non-empty, the stack's closers are
   inserted before it, with a note. The closers at a region's end close what remains. The output is one
   StringBuilder plus a vector of notes `{:line :inserted ")]"}` / `{:line :dropped "}"}`.
3. **Read and run.** Over the repaired code text, `sci/parse-next+string` (`sci/core.cljc:400`) runs at the live
   cursor, one form at a time, after the previous form evaluated. Aliases resolve as in SCI, each form is read
   exactly once, and its source string comes from the reader. Prose lines become each following form's comment, and
   repair notes ride beside the form's value as diagnostics.

**Expected cost:** one scan plus SCI's own read is about 3 ms / 8 MB per 28.6 KB, measured as `segments :reply` 2.5 ms
plus the plain `sci/parse-next` loop 2.1 ms / 7.7 MB. That is within the ruling's budget and removes about 300 lines.

## 5. What must stay, and why

- **SCI as the evaluator, our fork's write and expansion observers, and `seon.sci.observe`.** They are the smallest
  seam that lets the database learn what the agent defined, without a second reader or analyzer of the agent's intent.
- **The fold's totality** (`seon.batch`: a published value survives any later stage), **serial per-form evaluation**
  and **one settlement transaction** (confirmed: one `db/transact!` per run in `settle-batch!`). This is the owner's
  REPL law, and the code holds it.
- **Arming at the write** (`seon.sci.arm`). Armed contracts that refuse bad input and output are CLAUDE.md law. Only
  the broken-definition fallback goes.
- **clj-kondo analysis in derive**: the program graph (callers, reaching tests) needs it, and it is the library seam.
- **Interrupt and time limit** (`kernel/with-arm`, SCI's interrupt core): an agent's infinite loop must not hold the
  JVM.
- **Reply-mode prose → comments with fences**, kept lightweight as in §4.1.
- **The reader policy** (`#=` refused, explicit tags) in about 40 lines of `seon.sci.reader`.
- **The value printer** as the one budget, which must also own `:out`.

## Tasks recorded

- `seon.repl/printed-output-reaches-the-reply-unbounded`
- `seon.sci.arm/an-unarmable-contract-breaks-the-definition`
- `my.program/a-refused-native-call-answers-as-a-success-value`
- `seon.sci.forgive/prose-after-a-form-on-its-line-is-evaluated`
- `seon.sci.forgive/a-bare-token-line-in-a-reply-is-prose`
- `seon.sci.eval/a-set-print-length-taxes-every-later-run` (cause not located)

## Verification limits

- The acquisition-refusal throw before every form, and the `*data-readers*` loss, are code readings, not probes.
- The `set!` tax is measured, but its frames lie outside `seon.*` sampling.
- The share of the floor caused by contract instrumentation (`arm-var!` is on the stack, inclusively) was not
  isolated.
- Model-reply runs were judged through `planned-sources`, not a live model turn.
