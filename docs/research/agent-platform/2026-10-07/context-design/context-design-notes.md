---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, agent-loop]
---

# Context built around the task (root, 2026-10-07, draft for owner iteration)

## What is wrong with what we have

- Built today (`seon.agent.loop/task-context`): a task is turned into 12-14 forms (`pull`, `task-edges`,
  `recorded-result`, `source-fn`, `doc`). Each form is evaluated and shown as `user=> form` plus its printed value. The
  agent reads plumbing, such as a 9-key pull vector and a `recorded-result` error map, instead of the task. Source
  shows as an escaped one-line string.
- The system message is `(help)`: 15 run-on prose lines plus a tool list, about 3.9k tokens. Several lines teach
  retired mechanics: `seon.turn/turns-left`, `my.agent/done`, `my.task/steps`/`plan!`, and "Markdown is not prose
  here".
- The spec's §3 design re-renders a multi-hop walk out of the agent record every pass: identity, task view, inbox,
  and history units re-rendered through the cache.
- The provider request is `system` plus ONE user string (`seon.ai/request-body`). Every pass rebuilds that string, so
  the prompt cache only hits on a shared prefix.
- Live run 1 (live-run/report.md) found the blockers were context and protocol, not the turn machinery:
  - no protocol;
  - the agent could not run its proof test;
  - Markdown was read as code;
  - a stop sequence cut code;
  - the same fault was re-shown 6 times;
  - refreshes took 27-42% of every prompt.

## The idea

A conversation, as a coding agent already has one. One task gives one conversation:

```
system     : the protocol (static per program version; cached by the provider)
user #1    : the TASK BRIEF: the task's own :seon.render/ai, printed by the value printer
assistant  : prose + ```clojure blocks
user       : the REPL replies to those blocks (each reply map through the value printer)
             + a short STATUS footer (proof tests now, calls left), only when it changed
assistant  : ...
```

- **Append-only.** Nothing earlier is re-rendered, which follows CLAUDE.md: "history is never re-rendered".
  The provider's prefix cache hits on everything but the newest turn (live run 1 had 86% hits even with rebuilt
  prompts).
- **One render per reference, not a walk.** The task brief is the task's own `:seon.render/ai` plus, for each
  reference the task makes (a function, test, schema or namespace named in its text, must-read or `:seon/tasks`
  tags), that entity's declared `:seon.render/ai` called explicitly, once per identity. Each render returns DATA that
  the value printer prints. No forms are evaluated to produce context, and nothing walks hops out of the record. The
  "requery" form is offered in the data for the agent to run if it wants more. The kinds and their renders are in
  [reference-kinds-notes.md](reference-kinds-notes.md).
- **The protocol is the help value.** `(help)` returns data (sections of short rules with one example each).
  Its AI render is the system message, formatted as headings and bullets. The agent can call `(help)` again at the
  REPL and gets the same thing.
- **Natural replies.** The agent writes prose and fenced ```clojure blocks, and only the fenced code is evaluated.
  CLAUDE.md already says "prose becomes comments", but the installed reader does not: every prose word is evaluated
  as a symbol (task `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols`). This removes the Markdown-as-code failure and the need for the
  `#:seon.repl` stop sequence: no stop sequence at all, because the reply is cut at nothing.
- **Status, not refresh.** After each reply, append one small status map only when it differs from the last one
  shown:
  - the proof tests' states (green, red with the first failing assertion as data, or never run);
  - calls left;
  - new messages.

  This replaces the system turn and the "refresh" units.
- **Done = proof tests green.** After a pass that changed code the proof tests reach, run them. All green: the task is
  complete, the conversation ends, and the creator is notified. The agent can `(my.task/bail! "reason")`, the
  renamed `complete!` kept for bailing. Never launch a task without proof tests: the launcher refuses.
- **Budget.** When the conversation outgrows the window, drop the oldest REPL replies' values to their handles
  (`result/e…` still digs) and keep the task brief, the system message and the last N turns whole. This is one rule,
  in one place. A long task is the rare case.

## Mockups (generated from real renders; lane context-mockups)

The earlier hand-drawn mockup taught calls that do not exist (`(source f)`, `(apropos …)`, a vector argument to
`my.test/check`, a two-argument `ns-unmap!`, `my.task/bail!`); it is replaced by these files:

- [system-message.md](system-message.md): the help rewritten, about 990 tokens, every rule verified at the REPL or
  marked TARGET.
- [reference-kinds-notes.md](reference-kinds-notes.md): the 18 ways something is referenced, how each resolves and
  renders, and counts over all 392 tasks.
- [first-task-notes.md](first-task-notes.md): the first-run task, its candidates, and its corrected text plus a red
  proof.
- [real-renders/](real-renders/00-index.md): every reference in that task rendered by its declared AI render, called
  explicitly, as the agent sees it.
- [shape-conversation.md](shape-conversation.md), [shape-snapshot.md](shape-snapshot.md) and
  [shape-hybrid.md](shape-hybrid.md): the full turn-3 prompt of each shape, with ten-call token tables.

## What this deletes or never builds

- `seon.agent.loop/task-context` and `context-text` (forms as context), and `system-message` (replaced by the help
  render).
- The spec §3 history-unit selection, live `derived` units, inbox unit and frame. The status footer covers them.
- The system turn and refresh, already slated for deletion.
- The `#:seon.repl` stop sequence and the Markdown-as-code segmenting.

## What it needs (small, in this order)

1. `help` as data with a sectioned AI render, and its prose rewritten from scratch to the protocol above.
2. The task brief: the task render plus each reference's declared render, deduped by identity, with proof-test
   status as data read where it was recorded (latest run, or never run). The render fixes it needs are listed under
   Findings.
3. Provider request = system + messages vector (today's single `prompt` string becomes the first user message).
4. Reply segmenting: fenced ```clojure blocks only; drop the stop sequence.
5. A per-agent transcript as the message list: assistant text stored on the call row (already the attempt), the
   reply shown to the agent stored as shown text (CLAUDE.md "save exact shown text").
6. The status footer, and test-based completion plus `bail!`.

Steps 1-2 can be reviewed as files immediately, generated by the real functions. Steps 3-6 make the first live run
possible.

## Findings (lane context-mockups, measured on default at 42e569d9d)

### Broken first

- **Prose is evaluated, not commented.** A reply of one prose sentence, a fenced `(+ 1 2)` and `## Next step` gave six
  unresolved-symbol errors, a syntax-quote of the backticked `seon.db/q`, and an invalid-token error. Only the fence
  lines are dropped. Task `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols`.
- **An agent branch sees none of its cluster's test evidence.** `seon.test/recorded-result` on a fresh agent branch
  answers "No admitted execution is recorded" for a proof the head records green. Every proof in a brief rendered in
  the agent's world reads "unrun" until the agent reruns it (7.8 s for one test). Task
  `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence`.
- **No task is launch-ready.** 27 of 392 tasks have a proof, and none is red at the head. The chosen task needs
  corrected text and a red proof first ([first-task-notes.md](first-task-notes.md)).
- **The defn reply says a declared contract is missing** (`:seon.render.var/contract :missing` right after a `defn-`
  that carries `:malli/schema`). An agent will "fix" what is not broken. Existing task
  `seon.sci.eval/the-defn-reply-prints-before-its-own-change-applies`.

### References (counts in [reference-kinds-notes.md](reference-kinds-notes.md))

- Five kinds carry a brief: definition symbols, tests (through `:seon/tasks` tags), schema keywords, the task's
  namespace, and must-reads. Each has one identity attribute and one declared AI render, so a brief needs no new
  render: collect, dedupe, call each once.
- Dangling references are common: 44 symbols, 42 keywords, about 34 namespaces in intents, and 41 must-reads. Task
  `seon.task/task-text-references-are-never-resolved`.
- The CLAUDE.md docstring backtick index does not exist (TARGET). Task
  `seon.fn/backticked-docstring-references-have-no-index`.
- History kinds (commit ids, `file:line`, result handles, sections) resolve poorly and help least; they belong in
  evidence, not intents.

### Renders (files in [real-renders/](real-renders/00-index.md))

| Render | Today | Needed for a brief | Task |
|---|---|---|---|
| `seon.render.ns/task-ai` | id, kind, ns, name, one-string intent, must-read; no proofs, no evidence | proofs with state and failing assertion, the check operation | `seon.task/open-tasks-with-all-proofs-green-are-not-flagged` |
| `seon.render.ns/function-ai` | source as a vector of strings, packed several per line and escaped; doc repeated; no callers | source as code, whole for a subject; callers for private subjects | `seon.render.ns/function-source-lines-print-several-per-line` |
| `seon.render.ns/render-ai` (namespace) | the whole `dir` table: 4,132 tokens, 166 ms, 18,883 calls; doc cut mid-sentence; two timestamped errors per def row | the first sentence, a count, `(dir ns)` offered | `seon.render.ns/a-doc-line-is-the-first-physical-line-not-the-first-sentence`, `seon.sci.eval/dir-shows-each-uncontracted-def-as-two-timestamped-errors` |
| `seon.render.test/render-ai` | state and bookkeeping only | what it asserts; state read where recorded | `seon.render.test/a-test-render-omits-what-the-test-asserts` |
| `seon.render.schema/render-ai` | definition as data plus usage | definition first; short lists only | none (good enough) |

The value printer's in-flight fixes (lane source-display) matter most for source: multi-line strings shown as code
would let `function-ai` return the source as one string.

### Other defects recorded

- `seon.repl/every-def-repeats-the-same-persistence-note`: about 60 tokens per scratch def, repeated each time.
- `seon.repl/a-def-bound-to-an-error-value-shows-only-the-var`: a refused query bound by `def` is silent; `(count x)`
  then counts the error's keys.
- `seon.error/an-error-render-drops-the-members-that-say-what-is-missing`: `recorded-result`'s unknown is shown as its
  message string alone.
- `seon.test/a-check-answer-shows-digests-the-agent-cannot-use`: `my.test/check` of one 115 ms test took 7,799 ms
  (395,298 armed calls) and printed about 700 tokens of digests.
- `seon.repl/a-selected-render-arrives-as-an-escaped-string`: a value selected into the namespace render arrives as
  one escaped string, while the explicit call arrives as data.
- `my.program/search` takes 644-937 ms per call, close to the 1 s bound (no task; it is under the bound).
- Existing tasks seen again: `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl`,
  `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk`,
  `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`.

## Options for the conversation shape (mockups with turn-3 prompts and 10-call tables)

| | [Conversation](shape-conversation.md) | [Snapshot](shape-snapshot.md) | [Hybrid](shape-hybrid.md) |
|---|---|---|---|
| Prompt | system, brief once, append-only turns, status footer on change | system, brief re-rendered, last 2 turns | append-only turns, brief re-rendered at the end |
| Turn-3 prompt | 3,904 tokens | 3,964 | 3,966 |
| 10 calls, all tokens | 54,670 | 38,162 | 55,242 |
| 10 calls, uncached | **8,302** | 20,286 | 24,327 |
| Cached share | 85% | 47% | 56% |
| What goes stale | the brief's copy of code the agent itself redefined (visible in its own messages); proof state (patched by the footer) | nothing; but the agent forgets everything older than 2 turns | nothing |
| New machinery | footer diff of proof state | re-render per call, window selection | re-render per call |
| Matches CLAUDE.md | "history is never re-rendered" | re-renders | re-renders the brief only |

**Recommendation: the conversation.** It is the cheapest (2.4-2.9x fewer uncached tokens than the other two) and the
simplest: render the brief once, append, and add a footer only on change. It follows "history is never re-rendered".
Live run 1 already saw 86% cache hits on this access pattern. Its one weakness, a brief whose code the agent has
since changed, is visible to the agent, which wrote the change. The fallback, if a live run shows the agent reasoning
from stale code in the brief, is the hybrid's "re-render only what changed" variant, which is the same footer carrying
the redefined function's render. Snapshot is the only shape with a size ceiling, but losing the agent's own findings
after two turns costs more calls than it saves tokens in a 10-call budget.

## Design check (lane context-mockups)

- **Did the design hold against the real code?** Partly. "One render per task" is wrong as stated: `task-ai`
  returns the task alone, and the related walk that the reply adds is the multi-hop machinery the owner rejected. A
  brief composed of explicit per-reference render calls fits the installed renders without new render machinery.
- **Wrong or awkward**: the renders exist, but four of five are unfit as brief content today (table above). Proof
  state is read per branch, so the same brief differs by custody. And no open task has a red proof, so the first run
  first needs root to correct one task.
- **Not proven**: the mockups are hand-composed from real outputs. No brief generator exists and no provider call was
  made. The ten-call tables are a model from measured message sizes, not a run.

## Open questions for the owner

See the chat; answers get written back into this section.
