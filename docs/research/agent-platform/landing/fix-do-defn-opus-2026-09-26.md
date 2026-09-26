# fix-do-defn-opus — a top-level `do` admits each child before the next runs (2026-09-26)

## Broken / unverified first

- **UNVERIFIED AT RUNTIME. RESET NEEDED.** Default runs a committed archive (a34078273 at the time of writing), so the
  change was never executed. Only static proof exists: clj-kondo (0 errors over src/script/test, no new warnings in
  eval.clj) and `seon.contracts-compile-test/check` over `src/seon/sci/eval.clj` and the test (0 findings; the check
  was confirmed to flag an injected bad key). Root runs, after adoption:
  - `bin/test-check default --test seon.sci.entry-gate-test/a-top-level-do-admits-each-child-before-the-next-runs`
  - `bin/test-check default --ns seon.sci.entry-gate-test` (its existing `do` cases: two declarations, twins,
    `alter-var-root` after a defn, eval overwrite, nested unmap, defn beside removal, multibyte positions)
  - `bin/test-check default --ns seon.sci.eval-test`
  - MCP probe on a branch: `(do (defn smoke-inc "Adds one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (smoke-inc "a"))`
    should give the contract refusal plus the `;; NOT ADMITTED` line; then `(smoke-inc "a")` gives the refusal.
- **Foreign, found while probing (not mine; for root):** after default was replaced at a34078273 (one-commit B),
  an MCP SCI run on a branch answers **zero events**: `(+ 1 2)` on a fresh branch `fix-do-defn-opus-2` returned
  `"seon.dev.mcp/events":[]`. In the minutes before that replacement, every run on my branch failed settlement with
  `Nothing found for entity id [:seon.cluster.eval/id "…"]` (datahike.writer write-error, branch commit id stuck at
  the branch's creation commit 6ab82c98…), `(+ 1 2)` included. The zero-events failure is what blocked my
  after-probe.
- **Schema patch for root:** `tmp/fix-do-defn-opus/admission-request-declared.patch` declares the new optional
  `:seon.sci.eval/declared` member on `:seon.sci.eval/admission-request` (`resources/seon/schemas/seon.sci.eval.edn`,
  which I don't own). The map is open, so the code works without it; the patch documents the member.
- **Not fixed (logged as tasks):** `3411720c27cf` (a `do` with two definitions is still refused, because settlement
  carries one row per evaluation) and `1ef8af9c225f` (the reader's head check is a second derivation that
  seon.turn still reads).

## Defect, reproduced (MCP SCI, branch fix-do-defn-opus, default pid 59671 at e405f51be)

| form | result |
|---|---|
| `(do (defn fa "Adds one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (fa 1))` | `2`, admitted ("No example test gates user/fa"), 962 ms |
| `(fa "a")` | proper refusal `user/fa refused x at []: expected an integer` |
| `(do (defn fb … (inc x)) (fb "a"))` | `ClassCastException` from `inc`, **no row, no notice** |
| `(let [y 1] (defn fd "Adds y." {…} [x] (+ x y)))` | admitted, 284 ms |

## Cause (the owner's kondo question answered with evidence)

Admission does **not** derive definitions from a head check. It takes them from SCI's generation-stamped Vars
(`turn-interns`, eval.clj:505) matched against clj-kondo's analysis of the form's source (`analyzed-rows` →
`seon.fn/source-rows` → `runtime-analysis-batch` → `clj-kondo/run!`, fn/analyzer.clj:396), which reports nested
var-definitions. That's why `(do (defn fa …) (fa 1))` and `(let [y 1] (defn fd …))` were admitted. So the
hypothesis "a second derivation drops nested defns" is refuted for admission.

The defect was **timing**. `evaluate` (eval.clj:4564 before this change) ran the whole form through `sci/eval-form`,
which unrolls a top-level `do` itself (sci/impl/interpreter.cljc:34-43), and called `admit-program-change!` once,
after the whole form. So:
1. the call in the second child ran the Var before its contract was armed, which gave the ClassCastException; and
2. because the form threw, `failure-result` never admitted anything. It kept the def as a private binding (the
   comment "including a contracted def whose declaration never reached the terminal transaction") and said nothing.
   That is how the later `(fb "a")` also hit the unarmed Var.

The model error: admission was bound to the *reader's* form, not to Clojure's unit of top-level evaluation.
Clojure's `Compiler/eval` evaluates a `do`'s children as top-level forms (clojure/lang/Compiler.java:7742-7748).

A second derivation does exist, but admission doesn't use it: `seon.sci.reader/function-declaration`
(reader.cljc:252) and `test-declaration` (:306) check the form head. Admission ignores those facts (`row`,
eval.clj:470, reads only schema, ns and deletion facts). seon.turn's read-only-evaluation classifier (turn.clj:2024)
does read them. That is task `1ef8af9c225f`: switch the classifier to the admitted row, then delete the head checks.

## Fix (eval.clj, at the producer)

- `top-level-evaluation`: for a top-level `do`, each child is a top-level form, recursively and in order. A non-`do`
  child is captured, evaluated, then admitted through the **one** authority, `admit-program-change!`, with the
  names earlier children declared (`:seon.sci.eval/declared`). So a later child calls an admitted, armed
  definition, and a second declaration refuses at its own child, before later children run.
- `evaluate`: the one admission call became the local `admit!`. A non-`do` form admits exactly as before. For a
  `do`, a final `admit!` counts the form's reader row (for example a `require` context row) against the children's
  declarations. `eval-form!` became `eval-in-form!`, which takes the evaluator and keeps one REPL frame.
- `unadmitted-notice`: any failed form that leaves a function or test definition says so beside the value:
  `;; NOT ADMITTED: this form failed, so user/fb stayed a private definition with no program row (no tests, no
  merge). Evaluate the definition again once its form succeeds.` The notice is general, not specific to `do`. It
  rides `:seon.cluster.eval/output`, which `seon.repl/response-entries` shows as `:out` on failures too (repl.clj:184).
- Admission reads inside a child are not the form's read evidence (`db/*read-evidence-sink*` is bound to nil there,
  as it is for the ordinary admission).

A defn that is not at top level (inside let/when/fn), with evidence: inside `let`, it's admitted when the form
succeeds (probe above), because kondo analyzes it and SCI's Var position matches. A defn that runs in a *later*
form, such as an inner defn run by calling its outer function, is refused loudly as `unindexed-change-error`
(eval.clj:862, existing). Before this change, the only silent escape was a definition made by a form that then
failed. The notice now covers it for every form shape.

## Regression

`seon.sci.entry-gate-test/a-top-level-do-admits-each-child-before-the-next-runs` checks:
- the call inside the `do` meets the armed contract (`:input` check, not a ClassCastException);
- the failed form's output names the definition as NOT ADMITTED, and the definition stays armed afterwards;
- a nested `(do (do (defn …)) (call 1))` is the form's one row;
- a failing `let` with a defn gets the same notice, and private data gets none;
- `(do (defn one) (defn two) (two "a"))` refuses at `two`, naming both.

## Cost

Unmeasured (see above). By construction, a `do` with N children does N context captures and N admissions. An
admission with no new definitions walks only the namespace maps that changed, with no analysis; clj-kondo runs only
for a child that defined something. A non-`do` form does the same work as before.

## Sizes

`src/seon/sci/eval.clj` +110 / −27. The admission request moved into one closure; the inline contract for
`top-level-evaluation` is 12 lines because its schema keys live in a resource I don't own. Test +34. Task EDN +2
entries. No duplicate path deleted beyond the moved admission request, and nothing else computed per-child
admission. Commit: see `git log -- src/seon/sci/eval.clj`. Branches `fix-do-defn-opus` and `fix-do-defn-opus-2`
are retired.
