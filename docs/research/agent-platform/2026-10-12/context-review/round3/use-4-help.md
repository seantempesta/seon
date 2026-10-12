---
title: "Usefulness review, lens help: the (help) system message of the round 3 first-call contexts"
type: research
status: complete
created: 2026-10-12
---

# Usefulness review, lens help (lane use-4)

- Reviewed: the `system` message in `1-implement-forgive-forgiving-forgiven.md` (1,889 estimated tokens, 6,046 characters). The other two round 3 files carry the same block.
- Token figures are my approximations: characters scaled so the whole block matches the measured 1,889. The title and wrapper lines take about 230 of them.
- Judged for an agent about to do any small Clojure task, with no Seon source or docs read.

## 1. Line ledger

| Section (lines) | Approx. tokens | Verdict | Why |
|---|---|---|---|
| Who you are (2) | 85 | KEEP | Orients: branch, merge by root, program is data. |
| Reading your context (5) | 310 | KEEP | Explains every key in the user message's current-state block. This is the most valuable section. |
| Done (3) | 130 | KEEP the first two lines; SHORTEN the third to 25 | Defines done. Line 3 repeats the shell rule from the last How-work line. |
| How work proceeds (8) | 405 | KEEP steps 1 to 5; SHORTEN the last two (Malli contract lines) together to about 70 | Real workflow. The two contract lines overlap; the schema-name line repeats the Data section. |
| The REPL (6) | 220 | KEEP reply-shape, timing, println, and the column-zero rule; SHORTEN the markup ban to 12; DROP "Find more" (32) and fold `(dir my.program)` into step 1 | "Find more" repeats step 1. |
| Data (4) | 190 | KEEP all four | Rules every task hits: names, absence, fix at the producer, no redefinition or atoms. |
| Ask root or give the task back (2) | 120 | KEEP; SHORTEN the example form to the bare call (about 40) | Escape hatch. Only the call shape is needed. |
| Clojure and Malli gotchas (5) | 195 | KEEP the nil, falsy-keyword, lazy and error-map lines; SHORTEN the alias line to about 15 | Reference-style, but these are the bugs small tasks hit. |

Totals (approximate): **KEEP about 1,280 tokens; SHORTEN about 340 tokens (target about 210); DROP about 30 tokens.** Most of the block earns its place. The savings are small, about 160 tokens.

## 2. Does it orient?

Yes. It answers who I am, how to read the context, how to work, and what done is, in that order, before any reference. The Gotchas section is the only reference-style block, and it is short. I would skip it until a form fails.

The problems are accuracy and consistency, not length.

## 3. Where the help contradicts the user message

1. **Namespace.** The help says "Your own `:seon.context/namespace` is scratch: a definition there changes no owner." The user message's `:turns` map sets `:seon.context/namespace` to `seon.sci.forgive`, which is the owner. The start form also enters that owner namespace. The data says the context namespace is the owner. I cannot tell from the help whether a `defn` there lands in the owner.
2. **Calling convention.** The help shows `(my.program/callers 'seon.db/q)` with a bare symbol. The operations in the user message use `(my.program/callers {:seon.program/subject 'seon.sci.forgive/forgive})`. Both shapes are shown, but the help does not say which one is accepted.
3. **Schema key.** The help says "a schema's form". The data uses `:seon.ref/schema-form`. The help does not name the key.

## 4. Terms in the user message the system message does not prepare me for

- **Document references:** `:seon.ref/path`, `:seon.ref/heading`, and `(my.fs/section ...)`. The help never mentions evidence paths or doc sections.
- **Task fields:** `:seon.task/evidence` (a path to a research doc), `:seon.task/sym`, `:seon.task/status :working`.
- **Turn budget:** `:my.agent/turns-left 100`. Nothing says what it counts or what happens at zero.
- **Proof fields:** `:seon.context/counts {:pending 3}`, `:seon.context/proof-list`, `:seon.test/sym`. The help names `proof-status` but not these.
- **Relation to tests:** `my.program/tests-reaching` is never named, only described as "reaching tests".
- **Helpers in proof sources:** `unrepair`, `forgiving-again-changes-nothing?`, `text-gen` are not in `must-reads`. The help does not say a proof may call helpers outside the list.
- **Property-test calls:** `tc/quick-check`, `prop/for-all`, `test-support/assert-check!`, and the `:seed`/`:max-size` options. The help covers only plain `deftest`. The alias rule covers `tc/` and `prop/`, but not what the helpers are.
- **Malli shape:** the `[:function ...]` schema with two arities (used by `segments`). The help teaches only `[:=> [:cat ...] out]`.
- **Where results appear:** the help says the proofs run after each eval request. It does not say where the proof status appears in the reply, which is the first thing I would look for after a `defn`.
- **Domain names** (`:seon.batch/*`, `:seon.sci.forgive/balanced?`, `pos-int?`). The help can only say to read them with `my.program/source`. That rule is stated, so this is adequate.

## 5. Verdict

The help is mostly signal. It is the right size for a small task and it orients before it informs. Its weak points are three contradictions with the data (the namespace meaning, the callers shape, and the schema key) and a few unnamed keys. The first matters most, because I would not know whether a `defn` in my context namespace reaches the owner. The fixes are small: restate the namespace line to match the data, pick one calling shape for `callers` and `tests-reaching`, and add a short line naming the doc-reference keys, `:seon.task/evidence`, the turn budget, and where proof results appear.
