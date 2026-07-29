---
type: research
status: complete
tags: [research, agent, runtime]
---

# Reply parser quarry comparison

## Question and dependency ledger

Owner question: did `2a49cbd75` replace a forgiving mixed prose/code parser
with a worse whole-reply rule?

| Dependency or mechanism | Selected source | Relevant contract |
|---|---|---|
| SCI | vendored fork `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; `deps.edn:39-44` | `source-reader` plus `parse-next+string` return one form and its source buffer; `reference-code/sci/src/sci/core.cljc:364-391` |
| rewrite-clj | vendored `60782e501aaf312cb90c9ff0bee05d5da5125563`; old host alias in `deps.edn:140-143` | The quarry used one-token syntax reads and byte-faithful node strings in `src-old/seon/repl/parse.cljc:751-801` |
| parinferish | Maven `0.8.0`; old host alias in `deps.edn:140-143` | The separate repair layer used indent mode, accepted only changed output that re-read, and returned the original source otherwise; `src-old/seon/repl/parse/repair.cljc:292-334`. Its maintained source is not vendored, and the fresh splitter does not depend on it. |
| Fresh reply splitter | `2a49cbd75`; `src/seon/cluster/reply.cljc` | One throwaway SCI context splits source strings before `run/plan-tx`; no rewrite-clj or repair dependency |
| Fresh plan and evaluator | `src/seon/cluster/run.cljc:365-411`; `src/seon/cluster/loop.cljc:530-552,730-878`; `src/seon/sci/eval.clj:292-360` | Every returned source becomes an ordered durable plan unit and one terminal receipt |

The decisive SCI probe was:

```clojure
(sci/parse-string (sci/init {}) "; prose")             ;=> nil
(sci/parse-string (sci/init {}) "; prose\n(+ 1 2)")    ;=> (+ 1 2)
```

A source comment therefore records prose in the plan without resolving or
invoking any of its tokens. A comment-only source still receives an ordinary
terminal receipt from the fresh plan fold; unlike the historical system, the
fresh data model has no separate narration-only row.

## The old design in three sentences

The actual comment-preserving version was commit `9dc4848a6`, where
`prose->comment-lines` coalesced bare atoms and prose-classified reader
failures into narration attached to the next form, while trailing prose became
a comment-only entry
(`9dc4848a6:src/seon/repl/internal.cljc:162-171,295-394`). The eval pipeline
stored that narration on `:seon.eval/narration`, skipped evaluation for a
comment-only entry, and rendered it back into context as comments
(`9dc4848a6:src/seon/eval.cljs:1694-1701,2163-2176,2308-2321`;
`9dc4848a6:src/seon/ctx.cljs:558-590`). Commit `a0ca1cc10` then deliberately
reversed bare-prose preservation because rendering it as `;;` trained agents
to imitate the code-comment channel; the present quarry drops bare prose but
keeps real comments and forms
(`src-old/seon/repl/parse.cljc:1003-1159`).

“Captured verbatim” in the historical function doc meant content rather than
byte fidelity: `prose->comment-lines` trimmed the span and each line, removed
blank lines, and `join-narration` trimmed the joined result; explicit comments
also lost their leading semicolons and trailing whitespace
(`9dc4848a6:src/seon/repl/internal.cljc:146-171`). The fresh single-`;` source
shape intentionally has the same content-level normalization.

## What the quarry actually did

The parser was not the repairer. `parse-forms` produced ordered `:form`,
`:read`, and `:comment` entries; a read failure whose trimmed span began with
`(` was broken code, while invalid prose tokens were recovered without
becoming eval failures (`src-old/seon/repl/parse.cljc:677-748,1003-1159`).
The driver later selected only `:form` entries and their eval sources
(`src-old/seon/agent/driver.clj:601-608`).

Half-balanced delimiters first became `:read` entries. The downstream repair
layer ran parinferish indent mode on that one failed span, kept the repair only
if it changed and re-read, and recorded the edit note; otherwise the read
failure remained visible (`src-old/seon/repl/parse/repair.cljc:292-346` and
the historical eval path at
`9dc4848a6:src/seon/eval.cljs:2231-2306`). This was richer behavior than the
fresh splitter, but also a separate execution-time mechanism; the capability
ledger already ruled that any surviving repair must be a pure pre-plan
transformation, not a mid-fold splice
(`docs/prds/sci-execution-runtime/research/capability-ledger-2026-07-26.md:71-85`).

Fence handling was line-based: both backtick and tilde fence lines disappeared
while every line between and around them remained
(`src-old/seon/repl/parse.cljc:99-130`). The old token recovery initially
swallowed a same-line form after an invalid prose token; `6b38f1569` changed
recovery from the line boundary to the token boundary, documented with the
live `/etc/hosts` case in
`docs/seon/issues/archive/prose-token-line-recovery-swallowed-same-line-forms.md:8-43`.

## Case comparison

| Case | Comment-preserving quarry (`9dc4848a6`) | `2a49cbd75` | Revised splitter |
|---|---|---|---|
| Pure code | Kept collection-shaped forms in order; later quarry narrowed runnable forms to lists plus result references | Kept structured forms and standalone symbol lines exactly | Same pure-code sources and round trip; consecutive forms may share a line |
| Pure prose | One comment-only eval row; no prose evaluation or error | `::no-forms`, exact raw text only inside the error | One single-`;` comment source; SCI reads nil, so no prose token resolves |
| Mixed prose + code | Prose became narration on the next form; trailing prose became a comment-only row | Rejected the whole reply, losing valid code | Prose becomes comments attached to the next line-leading form; trailing prose is a comment-only source, and a parenthesized expression mentioned inside a prose line stays prose |
| Malformed or unbalanced code | `:read` entry, then one-span parinfer repair attempt; unrepaired input stayed a visible read failure | Whole reply becomes `::unreadable` with SCI position | Still `::unreadable`; no repair stack was restored |
| Fenced Markdown code | Removed fence lines, retained surrounding content for normal prose/form classification | Extracted only fenced blocks, dropping outside explanation | Removes backtick or tilde fence lines, comments outside Markdown, keeps fenced forms |
| Live word salad plus completion | Bare words were prose; the completion form survived | Zero plan forms, so the 22-token disease died but the completion was lost | One plan source: the sentence as `;` prose plus the completion form; none of the 22 tokens is a form |

## Live evidence and verdict

The retained pilot is genuinely mixed output, not pure prose:
`tmp/context-pilot-live.log:15-37` records 22 prose-token receipts followed by
ordinal 22, `(my.run/complete "reported")`. The completion is valid and was
emitted because the prompt explicitly required it
(`tmp/context-pilot-live.log:56-73`). Losing that form is therefore a measured
regression, not a hypothetical preference.

Verdict: the whole-reply admission rule was better at killing word salad but
worse for the mixed reply the model actually emitted. The revised splitter
keeps SCI as the one reader and restores only the quarry's useful data shape:
prose is source-comment data and structured code remains executable; it does
not restore rewrite-clj, parinferish, narration attributes, or execution-time
repair. Line position resolves the common parenthetical ambiguity: a
collection mentioned after prose on the same line remains prose, while a
line-leading collection is code. The irreducible ambiguity remains explicit:
a line-leading list-shaped English sentence is indistinguishable from a
Clojure list without namespace resolution, so this change does not invent a
semantic classifier.
