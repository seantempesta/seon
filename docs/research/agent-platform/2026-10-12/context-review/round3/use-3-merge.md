---
title: "Usefulness review, lens merge: round 3 first-call context for seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts"
type: research
status: complete
created: 2026-10-12
---

# Usefulness review, lens merge: first-call context for seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts

Read only `3-implement-merge-first-facts.md` (5,304 estimated tokens: system message 1,889, user message 3,415). Token costs are my estimates per section. Totals are approximate.

## 1. Line-by-line ledger

### Message 1: system help (1,889 tokens)

| Section | Verdict | Tokens | Why |
|---|---|---|---|
| Who you are (+ wrapper) | KEEP | 149 | Says definitions reach main only when root merges. That is the merge boundary. |
| Reading your context | KEEP | 340 | Explains `:seon.context/proofs`, `proof-status` and `must-reads`. Needed to read the block. |
| Done | KEEP | 180 | "There is no done call" and "a test already green proves nothing". Both bear on this red proof. |
| How work proceeds | KEEP | 430 | Step 3 contract rule and step 5 deftest form. Needed for the first edit. |
| The REPL | KEEP | 280 | `:result` symbols, `:ms`, `:findings`. Needed to read replies. |
| Data | KEEP | 160 | Bans atoms and redefs. Blocks the obvious shortcut for timing. |
| Ask root or give the task back | KEEP | 130 | The only route when the proof cannot be checked here. |
| Clojure and Malli gotchas | SHORTEN to ~70 | 220 | Keep the lazy `mapv`/`into` line and the alias line. The nil-in-Malli and falsy-lookup lines are not used by this task. |

### Message 2: user block (3,415 tokens)

| Section | Verdict | Tokens | Why |
|---|---|---|---|
| `:sym` | KEEP | 15 | Task identity. |
| `:evidence` path | DROP | 25 | Duplicates the M6 notes must-read, which carries the same path. |
| `:intent` | KEEP | 220 | The task itself: 6.4 ms warm vs 417 ms and 1.0 GB first, and the 359 ms in `seon.db/vector-query-call`. |
| `:must-read` set | DROP | 35 | Duplicates the rendered must-read entries that follow. |
| `:proofs` proof-list (first copy) | KEEP | 700 | The red proof, with its fixture and its 100 ms / 64 MB asserts. |
| `:step` | KEEP | 60 | `:seon.context/start` gives `(in-ns 'seon.merge)`, the first form to run. |
| must-read `my.root/merge!` | SHORTEN to ~60 | 300 | Its docstring is about 230 tokens. Keep the signature and one clause naming it as the merge entry. Schema and operations stay. |
| must-read `seon.db/vector-query-call` | KEEP | 210 | The 359 ms culprit named in the intent. |
| must-read `seon.merge/merge!` | SHORTEN to ~150 | 420 | Its docstring is about 280 tokens and names helpers in prose: `merge-tx`, `prepare-merge!`, `accept-merge!`, `unmerged-count`, `task/obligations`. Keep those names as a list, not prose. Schema and operations stay. |
| must-read M6 notes | KEEP | 70 | The only pointer to the measurement evidence. Its content is one form away. |
| must-read `seon.profile/measure` | SHORTEN to ~50 | 190 | Its docstring is about 120 tokens. The proof reads only the `:seon.profile/ms` and `:seon.profile/alloc-bytes` keys. |
| must-read `my.root-test/...` (second copy of the test source) | DROP | 480 | Same deftest as the proof-list entry, pasted again. |
| `:turns` | KEEP | 50 | 100-turn budget and the namespace. |
| Keys, brackets and operation forms not itemised above | KEEP | 640 | Framing for the entries above. The `:seon.render/operations` forms are runnable. |

### Totals (estimated)

- **KEEP: about 3,630 tokens.**
- **SHORTEN: about 1,130 tokens now, to about 330 after shortening.**
- **DROP: about 540 tokens.**

Shortening and dropping together save about 1,300 tokens. The duplicate test source alone is about 480.

## 2. What is missing

1. **The callees of `seon.merge/merge!`.** The intent says "find which first-call derivation allocates the gigabyte". The operations give only source, callers and tests-reaching. I would expect `my.program/callees`, or the profile entries of `merge!`, in its must-read. Without it I cannot locate the projection read or the squash three-way.
2. **Helpers named in the merge docstring and the proof.** `merge-tx`, `seon.cluster.source/prepare-merge!`, `seon.cluster.source/accept-merge!`, `seon.cluster.source/unmerged-count`, `seon.task/obligations`, `lowered-query-request`, and the proof's `root-context`, `test-support/with-database`, `registry/fork!` and `task/attempt-tx`. None is a must-read, so none shows its source.
3. **A way to make the proof red for the right reason.** The proof comment says "run it alone through bin/seon check". The system help forbids `bin/seon` and shell commands. The agent's REPL is warm, so it cannot reproduce the first-JVM condition, and a green result here proves nothing. This is the largest gap for this lens. I would expect a sentence or a form that runs the proof in a fresh JVM, or a note that root does that.
4. **Measured numbers for the first-call path.** The intent gives totals only. A per-stage profile of `merge!` would tell me which stage holds the 1.0 GB.

## 3. First five forms (every guess marked)

1. `(my.program/source 'seon.db/vector-query-call)` - given in the context, no guess. I would read it first.
2. `(my.program/callees {:seon.program/subject 'seon.merge/merge!})` - **GUESS**: the callees operation is not in the context and may not exist. Its name is my assumption.
3. `(my.program/source 'seon.cluster.source/prepare-merge!)` - **GUESS**: the symbol is named in the merge docstring but I do not know it is defined under that namespace.
4. `(profile/measure #(my.root/merge! ...))` on a scratch branch to see the per-stage cost - **GUESS**: I do not know the fixture arguments, so I would copy them from the proof and expect to fix them.
5. `(in-ns 'seon.merge)` - given, the step's start form, no guess.

## 4. Verdict

This context is mostly signal, but about a quarter of it is noise for this lens. The intent, the red proof, the `vector-query-call` must-read and the `:step` are what an agent needs, and they are present. Against them sit a duplicated test body (about 480 tokens) and two long docstrings that repeat prose the merge names already carry. The gap that matters most is not a cut but a missing piece: nothing tells the agent how to make the proof red as a first merge, and the one instruction given (`bin/seon check`) is forbidden to it. Without that, the agent could spend its turns on a timing it cannot reproduce. The missing callee view and the unlisted merge helpers would send it to guesswork before it reaches the derivation the intent asks it to find.
