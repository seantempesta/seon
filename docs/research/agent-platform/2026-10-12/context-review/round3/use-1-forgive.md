---
title: "Usefulness review, lens forgive"
type: research
status: complete
created: 2026-10-12
---

# Usefulness review, lens forgive: forgiving-forgiven-text-changes-nothing

Reviewer: use-1 (Haiku cold read). Input: `1-implement-forgive-forgiving-forgiven.md` only. Token counts are my rough estimates (about 4 characters per token), not `seon.ai.tokens/estimate`.

## 1. Line-by-line ledger

### Message 1: system (about 1,889 tokens)

| Section | Verdict | Tokens | Why |
|---|---|---|---|
| "Who you are" | KEEP | 75 | Sets my role: a branch, definitions reach main on merge. |
| "Reading your context" | KEEP | 230 | Explains `:seon.context/step`, `proofs`, `must-reads`, and the operations-are-forms rule. |
| "Done" | KEEP | 140 | Says all proofs green completes the task; no done call. |
| "How work proceeds" | KEEP | 350 | The procedure: read owner, `in-ns`, `defn` with contract, probe, write `deftest` with `:seon/tasks`. |
| "The REPL" | KEEP | 225 | Reply shape, `never println`, `:findings`. |
| "Data" | KEEP | 150 | Namespaced keys, absence is no key, no `:any`. |
| "Ask root or give the task back" | KEEP | 110 | Situational, but it is the only escape path. |
| "Clojure and Malli gotchas" | SHORTEN | 200 to about 100 | Keep the `[:= nil]` and `:cat` lines and the lazy-realize line. Drop the alias and error-map lines, which repeat the REPL section. |
| `#:seon.help{...}` wrappers | SHORTEN | about 400 | EDN keyword and vector framing around prose. Plain lines would carry the same content. |

### Message 2: user context (about 2,520 tokens)

| Section | Verdict | Tokens | Why |
|---|---|---|---|
| `:seon.task/sym` | KEEP | 15 | Names the task. |
| `:seon.task/evidence` | DROP | 30 | A research doc path. I would not open it; the intent already states the cases. |
| `:seon.task/intent` | KEEP | 250 | The conditions: idempotence, the three reported inputs, and where the fix lands. |
| `:seon.task/must-read` (symbol set) | DROP | 40 | Duplicates the `:must-reads` section below. |
| `:seon.context/counts` | KEEP | 10 | Shows three pending proofs. |
| proof 1 `a-stray-closer-keeps-tokens-apart` | KEEP | 200 | The `are` table is the exact expected behavior. |
| proof 2 `forgiving-forgiven-text-changes-nothing` | KEEP | 110 | The property I must make true. |
| proof 3 `the-repairs-account-for-every-change` | KEEP | 100 | Uses `unrepair`, which is not defined in the context (see section 2). |
| `:step` with `(in-ns 'seon.sci.forgive)` | KEEP | 50 | My first form; it places me in the owner's namespace. |
| `seon.sci.forgive/forgive` ref | KEEP | 200 | Contract and doc: the return shape is what the fix must keep. |
| `seon.sci.forgive/scan` ref | KEEP | 230 | Contract is the exact owner I must change; the spans and repairs shape are here. |
| `seon.sci.forgive/segments` ref | SHORTEN | 130 to about 50 | Keep the contract. The three operations per ref (callers, tests-reaching) are not needed for this fix. |
| `seon.sci.forgive/step` ref | SHORTEN | 200 to about 120 | Keep the contract and doc. Drop the callers and tests-reaching forms. |
| doc section `my.fs/section` ref | DROP | 60 | A pointer to a research doc. The intent carries the same cases. |
| `:seon.batch/text` ref | DROP | 50 | A description that adds nothing a `:string` schema does not say. |
| `:seon.batch/repairs` ref | KEEP | 20 | Shape of the repairs vector. |
| `:seon.batch/spans` ref | KEEP | 20 | Named, but its schema body is not shown (see section 2). |
| `:seon.batch/repair` ref | KEEP | 150 | Keys `:seon.batch/line`, `:at`, `:inserted`, `:dropped`: what the proof's model reads. |
| `:seon.batch/dropped` problem | DROP | 60 | Harmful. It is a key inside the repair map, reported as `:problem-kind :unresolved` with "create it". I would be tempted to define a schema for a key. |
| `:seon.batch/inserted` problem | DROP | 60 | Same false problem as `:seon.batch/dropped`. |
| `:seon.batch/forgiven` ref | KEEP | 110 | The return shape consumers read. |
| `:turns` | KEEP | 30 | The turns budget and namespace. |
| EDN framing and repeated render-operation forms | SHORTEN | about 400 | Keys like `#:seon.ref{...}` and `:seon.render/operations` repeat per ref. |

### Totals (estimated)

- **KEEP: about 2,780 tokens**
- **SHORTEN: about 1,330 tokens** (gotchas 100 saved, segments and step about 230 saved, EDN framing about 800 across both messages)
- **DROP: about 300 tokens**

These sum to about 4,410, matching the file's total.

## 2. What is missing

- **`unrepair`** is used by proof 3, but no definition or source reference appears in the context. I would expect it in `seon.sci.forgive-test` or as a must-read.
- **Test namespace header**: the `forgive` alias, `tc`, `prop`, `test-support`, `text-gen` and `forgiving-again-changes-nothing?` are used but not declared. I would expect the `ns` form of `seon.sci.forgive-test`.
- **Current body of `scan`**: the context gives me its contract and a source operation, but not its text. I need to read it before I can change it. I would expect it inline, since the step is `:implement`.
- **Schema bodies** for `:seon.batch/span`, `:seon.batch/mode`, `:seon.batch/read-stage`, `:seon.batch/read-state`, `:seon.batch/read-step`, and `:seon.batch/segmented`. The contracts name them, but I cannot check a value against them.
- **Current behavior on the reported inputs**: I would want the last reply for `"a]a"` and `"('"`, or the proofs' last failure text. The proofs are only `:pending`.

## 3. First five forms (guesses marked)

1. `(in-ns 'seon.sci.forgive)`. Given by the step; I would send it first.
2. `(forgive/forgive "a]a" :code)`. Guess: the test ns alias `forgive` resolves here. I would check with `(ns-aliases *ns*)` first if it fails.
3. `(my.program/source 'seon.sci.forgive/scan)`. Guess: needed because the context gives only the contract.
4. `(my.program/source 'seon.sci.forgive-test/unrepair)`. Guess: that `unrepair` lives in the test ns.
5. `(my.program/source :seon.batch/span)`. Guess: the schema body is retrievable this way, as the context's own help suggests for `:seon.task/intent`.

## 4. Verdict

Mostly signal. The task intent, the three proof bodies, the contracts of `forgive`, `scan` and `forgiven`, and the repair shape are what I need to make the change, and they arrive without search. The noise is concentrated in three places: two false "unresolved" problems on `:seon.batch/dropped` and `:seon.batch/inserted`, which are keys inside the repair map and would send me chasing schemas that do not exist; the repeated operation forms (callers and tests-reaching) that I would not run for this fix; and the EDN framing around every block. Those three account for well over a quarter of the 4,409 tokens. The real gaps are `unrepair`, the test namespace header, the body of `scan`, and the span and read schemas. Fix the false problems first, since they are wrong, not just verbose.
