---
title: "Sufficiency review, lens forgive: seon.sci.forgive/forgiving-forgiven-text-changes-nothing"
type: research
status: complete
created: 2026-10-12
---

# Sufficiency review, lens forgive: seon.sci.forgive/forgiving-forgiven-text-changes-nothing

Input: `docs/research/agent-platform/2026-10-12/context-review/round4/1-implement-forgive-forgiving-forgiven.md` (the first-call context, read alone, no source or docs).

## 1. Plan to done

1. `(in-ns 'seon.sci.forgive)`. KNOWN: the `:step` block's `:seon.context/start` gives this form.
2. `(my.program/source 'seon.sci.forgive/scan)`. KNOWN as a form, and needed only because the context elides the body: the `:seon.fn/source` of `scan` is cut at `…+9971`, and `shift!`, the `marks` bookkeeping and the `balanced` flag all sit in the cut.
3. Probe the three pinned failures and the second pass in one form:
   `(mapv (fn [t] (let [r (forgive t :code) r2 (forgive (:seon.batch/text r) :code)] {:in t :out (:seon.batch/text r) :spans (:seon.batch/spans r) :again (:seon.batch/repairs r2)})) ["a]a" "('" "(\\"])`. KNOWN: the inputs come from the test source. The reply should show the expected `"a a"`, `"()'"` and `"()\\"` against the actual output.
4. Re-send the whole `(defn- scan ...)` with the repair change. GUESS: the change itself. The `forgive` body shows that a second pass repairs whenever `::balanced?` is false, so the likely fault is that `scan` calls a dangling quote or backslash after an inserted closer unbalanced, or drops the span that should cover it. I cannot name the edit without the cut body from form 2. The contract `[:=> [:cat :string :boolean :boolean] [:map ...]]` is complete, so the body is the only unknown.
5. Read `:seon.context/proof-status` after the automatic run. KNOWN per the help text.
6. One or two more iterations of form 4 or 3. GUESS: count depends on what the probe shows.

Calls before the fix can be written: 3 (forms 1 to 3). Of those, form 2 exists only because `scan` was cut from its must-read, and form 3 exists because the proofs are `:pending` with no `:seon.context/proof-failures`, so the actual wrong output is not in the context.

## 2. Calls needed

Three before writing the fix, plus the fix itself and one or two iterations. Two of the three are there only because the context omits data: the elided scan body and the missing failure output.

## 3. Blocking gaps

1. **The body of `seon.sci.forgive/scan`.** The fix lands here (the intent says so), but the must-read `:seon.fn/source` is cut at `…+9971` where `shift!`, the repair bookkeeping and the `balanced` flag are computed. Without it I cannot write the change, and re-sending a whole `defn-` of about 10 KB from a truncated view is not possible. It should appear as the full source, or with the cut moved past the balance and repair logic. It is one call away in form 2, so this is a call cost, not an absence.
2. **The current failure output for the pinned cases.** `:seon.context/proof-status :pending` and no `:seon.context/proof-failures`. Without the actual `:seon.batch/text` for `"a]a"`, `"('"` and `"(\\"` I must spend a call to see what is wrong. It should appear as the last recorded failure of `a-stray-closer-keeps-tokens-apart`.
3. **Runnable operations on eight references.** `fence-line`, `form-start?`, `line-end`, `literal-line?`, `markup-end`, `non-blank`, `token-end` and `owned` each show `(my.program/source …1)`, which is not a runnable form. The name is in `:seon.ref/name`, so a reconstruction is trivial, but the operation as printed is broken.
4. **The valid modes.** `:seon.batch/mode` is not among the must-reads. The forms show `:code` and `:reply`, but a schema reader cannot confirm the enum. Minor, since the test uses `:code`.

Not blocking: the source of `forgiving-again-changes-nothing?` and `unrepair` is absent, but their docstrings state the properties (idempotence; undoing repairs gives back the text).

## 4. Wasted

- The `seon.sci.forgive/forgive` reference with its docstring and `(my.program/source ...)` operation is repeated about eight times (proofs, property refs, the `forgive` must-read, `:seon.batch/dropped`, `:seon.batch/inserted`, `step`, `segments`). About 600 tokens of repetition.
- `segments` and `step` source, with their reference lists, about 800 tokens. The fix sits in `scan`, which those consumers only read. The task text says they must keep working, but the reach tests show that, not the context.
- The "Clojure and Malli gotchas" and the `my.message/send`, `my.turn/wait` and `my.task/send-back!` help lines, about 350 tokens. None applies to this fix.

Total wasted, approximately 1,700 tokens of 8,254.

## 5. Verdict

CAN DO WITH SEARCHING: the entry (`forgive`), the contract, the probe and the test sources are sufficient, but the fix sits in `scan`, whose body is cut from its must-read, so one extra read is needed before the defn can be written.
