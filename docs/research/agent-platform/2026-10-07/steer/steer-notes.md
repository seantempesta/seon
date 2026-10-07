---
type: notes
status: active
created: 2026-10-07
tags: [notes, agent-loop, context-design]
---

# Steering experiment: design and what Seon's own loop needs to run it (lane steer, 2026-10-07)

Status: **designed, not run.** Root stopped the lane before any paid matrix call. The owner ruled: "Don't build a
separate harness, make our harness work". So the matrix below is the specification for experiments that Seon's own
agent loop should run. The last section maps each piece a standalone harness would have needed to the real owner it
stands in for, and says what that owner is missing. No driver script was written or committed. One smoke call was made
(39 tokens, about US$0.00002).

## Broken first (probed on default, pid 28709, agent `steer`, SCI, ns `scratch.steer`)

- `(doc clojure.string/split)` returns the `:seon.sci.eval/documentation-unavailable` error value. `(dir clojure.string)`
  returns `[]`. `(clojure.repl/apropos "split")` does find `clojure.string/split`. New task
  `seon.sci.eval/dependency-namespaces-have-no-doc-or-dir-in-an-agents-repl` (id cf5f4653ad02).
- Bare `source`, `apropos` and `find-doc` are unresolved. `clojure.repl/source` and `clojure.repl/find-doc` print to
  `:out` and return nil. Existing tasks: `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl` and
  `seon.sci.eval/find-doc-floods-out-with-thousands-of-tokens`.
- A failing `deftest` called as a function, or run with `clojure.test/run-tests`, reports its location as
  `(lang.cljc:240)`, which is SCI's file and not the test's. The summary value is correct:
  `{:test 1, :pass 1, :fail 1, :error 0}`. Related task: `seon.sci.eval/clojure-test-parity-residue`.
- `(doc scratch.steer/sq)` took 383 ms (458 calls) for a one-line function. This is under the 1 s bound, but slow for
  a lookup an agent repeats.
- The JVM-mode `seon.ai/complete` smoke call drew the `:slow` finding (1,025 ms). The time was the network
  round-trip, so the finding is noise for a provider call.
- On default, `:seon.config.ai/no-provider` is true and `:seon.config.ai/stop ["#:seon.repl"]` is still in the
  cluster settings (`config/default.edn:441`). Live run 1 found the same.

What works, and the experiment depends on it:

- `seon.ai/complete` with the resolved `deepseek-flash` target (DEEPSEEK_API_KEY is set; the value was never read).
  The call returned text, a finish reason and usage, including `prompt_cache_hit_tokens`.
- `seon.sci.reader/read` reads a whole reply into events without evaluating it.
- In a scratch namespace in SCI, `deftest` + `clojure.test/run-tests` run in 4 ms.
- `(dir scratch.ns)`, `clojure.repl/source-fn`, `clojure.repl/apropos`, `(my.program/source 'sym)` and `(doc sym)` work
  on agent-defined functions.

## The design

### Context per call: rebuilt from the task, never a growing chat

- **System string**: the protocol. It has three variants (S), and it holds one swappable reply-format paragraph (R)
  and an optional worked example (E).
- **One user string**, in this order:
  1. `# Task`: the task text. Names are fully qualified. It ends with "When the task is done, reply with your answer
     and no code."
  2. `## References` (C): the task's subject functions, presented by the variant.
  3. `## Your work so far`: every prior call, whole. Each shows the agent's reply verbatim, then the REPL's reply text
     verbatim.
  4. `Calls left: N`.
- **Request**: `seon.ai/complete` with the cluster's resolved target, minus `:seon.ai/stop` and
  `:seon.ai/no-provider`, with `:seon.ai/max-tokens` 4000. It carries `:seon.ai/system` and `:seon.ai/prompt`.
  `seon.ai/request-body` (`src/seon/ai.clj:561`) sends system plus ONE user message, which matches a context rebuilt
  per call. The prefix cache still hits on everything up to the newest work, because the order is stable: system,
  then task, then references, then work.

### Turn mechanics

1. Call the model. The text comes back as data and is never cut by a printer.
2. **Reader check, no evaluation.** Read the whole reply with `seon.sci.reader/read` and record what today's reader
   does: forms, read errors, and prose words read as symbols. This measures our reader separately from the model.
3. **Code sent is the target behavior.**
   - If the reply has fences, lines outside them become `;;` comments and the fence lines are dropped.
   - If it has no fences and contains at least one list form, it is sent as written.
   - Otherwise the reply is prose and is sent as all comments.
   - A reply that yields zero forms means the agent is done.
4. **Evaluate** through the one entrance, `seon.mcp/call` `eval_clj`, in SCI mode, as a fresh agent `steer-<run>` on
   its own branch, starting in `scratch.<run>`. The REPL reply text is fed back verbatim.
5. Stop at 6 calls, or at the first done reply.
6. **Post-run check**, run as the agent and not shown to it (per task, below).
7. **Feedback turn.** After the run has ended and been recorded, send ONE extra call in the same context. It shows
   what happened (the end reason and the post-check result) and asks:
   - what was confusing in the instructions or the context;
   - what it wished it had been shown;
   - how it prefers to write replies (prose, code blocks or comments);
   - which lookup forms were useful or missing;
   - which errors or replies were hard to act on;
   - the one change that would help most.

   The reply is recorded verbatim and never evaluated. Nothing before this turn mentions feedback, evaluation or the
   experiment.

### Micro-tasks (scratch namespaces only; fixtures defined by a setup evaluation as the agent and never shown)

| # | Task | Fixture | Post-run check |
|---|---|---|---|
| T1 explain | What does `scratch.X/mystery` return in general, and for `"b, a,, b ,a, c"`? Read it, then confirm by calling it. | `mystery`, no docstring: split on commas, trim, drop blanks, return the most frequent item, ties broken by name | the answer names `"a"` and the tie-break |
| T2 write | Define `scratch.X/word-count` (lowercase word → count) with a one-line `:malli/schema`, plus `word-count-test` with at least 2 assertions, and run it. | none | `(word-count "The cat, the hat.")` = `{"the" 2 "cat" 1 "hat" 1}`; `run-tests` shows 0 fail and 0 error |
| T3 fix | `scratch.X/clamp-test` fails. Fix `scratch.X/clamp` without changing the test. | `(defn clamp [lo hi x] (max hi (min lo x)))`, contracted, with a 3-assertion test | `run-tests` is green; the test's source is unchanged |
| T4 find | A function in `scratch.X.units` converts Celsius to Fahrenheit. Find it and convert 37.5. | 6 functions; the target `scale-temp` is named so that `apropos "celsius"` misses it and `find-doc`/`dir` find it | the answer is `scratch.X.units/scale-temp` and 99.5 |
| T5 recover | Use `scratch.X/parse-order` to parse `"12 x blue widget"`. | the contract takes `{:order/text string}`; the docstring says "an order line", so a string call is refused by the contract | the answer is qty 12, item "blue widget" |

### Variables (one changed at a time from the baseline R1 S1 C1 E0)

- **R, reply format**:
  - R1: prose plus ```clojure fences; only fenced code runs; prose becomes comments.
  - R2: the whole reply is Clojure; reasoning goes in `;;` comments; no Markdown.
  - R3: no format guidance.
- **S, system message**: each variant carries the same R paragraph.
  - S1: the drafted help (`context-design/system-message.md`) with the TARGET marks removed, plus one line saying that
    `(clojure.test/run-tests)` runs the namespace's tests.
  - S2: today's `(help)` value, as printed to an agent (3,654 characters).
  - S3: a minimal 5-line protocol.
- **C, references**:
  - C1: REPL pairs, `user=> (doc x)` and `user=> (source x)`, each followed by its printed value.
  - C2: the same facts as one data map (the value of `my.program/source`).
  - C3: nothing shown; the agent must look things up.
- **E, worked example**: E0 none; E1 a one-exchange example appended to the system string.

Runs:

| Variant | Tasks | Runs |
|---|---|---:|
| Baseline, two reps (to gauge noise) | all 5 | 10 |
| R2, R3 | all 5 | 10 |
| S2, S3 | all 5 | 10 |
| C2, C3 | T1, T3, T5 (the tasks with references) | 6 |
| E1 | all 5 | 5 |
| **Total** | | **41** |

That is about 100-130 calls plus 41 feedback calls. At deepseek-flash prices, doubled for peak, it would cost about
US$0.10-0.25, with a hard stop at US$1.00 counted from the usage fields.

Measures per call:

- reader parse (forms, errors, prose-as-symbols) against the code actually sent;
- forms evaluated, error results, and refused values;
- lookups used (`doc`, `source`/`source-fn`, `dir`, `apropos`, `find-doc`, `my.program/source`);
- prompt, cache-hit and completion tokens, and cost.

Measures per run: calls to finish; whether it finished correctly (the post-run check); a note on hallucinated
functions, prose written as code, or acting before seeing results.

## What Seon's own loop needs to run this

Each piece the harness would have supplied, the owner it stands in for, and what is missing there.

### 1. The system message producer

- **Owner**: `seon.agent.loop/system-message` (`src/seon/agent/loop.clj:39`), built from `seon.bootstrap/help-value`
  (`src/seon/bootstrap.clj:17`) plus the toolkit namespaces' tool lines.
- **Missing**:
  - Nothing calls it. `:seon.ai/system` has no producer in the turn: `seon.turn` builds the target and passes only
    `:seon.ai/prompt` (`src/seon/turn.clj:4573-4575`).
  - Its text is today's help plus tool lists. The drafted protocol (`context-design/system-message.md`) is not the help
    value yet.
  - There is no way to select a variant (see item 8).

### 2. The task-driven user prompt

- **Owner**: `seon.agent.loop/task-context` and `context-text` (`src/seon/agent/loop.clj:153`, `:221`).
- **Today's prompt**: `seon.cluster.prompt/prompt` (`src/seon/cluster/prompt.clj:422`) through
  `seon.render.walk/history` (`src/seon/render/walk.clj:840`).
- **Missing**:
  - `task-context` is not wired into any pass.
  - It shows plumbing forms (pulls, `task-edges`, `recorded-result`) rather than the task text, its references and the
    done rule.
  - The pass does not rebuild the prompt as task, then references, then work, then calls left.
  - Nothing renders "calls left" from the episode cap.

### 3. The prose→comments reader

- **Owner**: `seon.sci.reader/read` (`src/seon/sci/reader.cljc:828`), with `skip-fences!` and `read-events`. The turn
  calls it at `src/seon/turn.clj:1680` and `:2312`.
- **Missing**:
  - Only fence lines are skipped. Prose outside fences is read as symbols: the probe "I will add `seon.db/q`
    numbers." gave five symbol forms, one of them a syntax-quote, and `## Next` gave an invalid-token error.
  - The rule to add: outside fences in a fenced reply, lines are comments.
  - Task `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols`.

### 4. The stop sequence

- **Owner**: `:seon.config.ai/stop ["#:seon.repl"]` (`config/default.edn:441`), routed into the request by
  `seon.ai/wire-settings` (`src/seon/ai.clj:457`).
- **Missing**:
  - The stop entry should be deleted, since fences remove the reason it exists.
  - A cut must reach the agent as an error value. Today it looks like an ordinary `finish stop` (live run 1, defect 1).

### 5. The per-agent provider enable

- **Owners**:
  - `seon.ai/settings` (`src/seon/ai.clj:293`) merges cluster then agent overlay;
  - `seon.ai/agent-overlay` (`:320`);
  - `:no-provider` is `[:= true]` in `resources/seon/schemas/seon.config.ai.edn:64-72`;
  - the gates are at `src/seon/turn.clj:4396`, `:4443`, `:4504`, `:4534` and `:4555`.
- **Missing**: the schema admits only `true`, so an agent overlay cannot turn the provider ON for one agent while the
  cluster default stays off. The fix is to make it a boolean dial whose per-agent `false` wins. Live run 1, defect 5.

### 6. References via doc/source

- **Owners**:
  - the agent's REPL lookups in `seon.sci.eval/build-base-ctx` (`src/seon/sci/eval.clj:348`);
  - `documentation-value` (`:2056`) and `directory-value` (`:2019`);
  - `program-doc-var` (`:2079`);
  - `source-fn` bound at `:2144`.
- **Missing**:
  - Bare `source`, `apropos` and `find-doc` are unresolved.
  - `source` and `find-doc` print to `:out` instead of returning values.
  - Dependency namespaces have no `doc` or `dir` (task cf5f4653ad02, above).
  - The task brief needs a generator that emits `(doc x)`/`(source x)` exchanges for the task's references. The
    nearest owner is `task-context` (item 2), which emits other forms.
  - Tasks: `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl`,
    `seon.sci.eval/find-doc-floods-out-with-thousands-of-tokens`,
    `seon.sci.eval/dependency-namespaces-have-no-doc-or-dir-in-an-agents-repl`.

### 7. The agent's own work as history, excluding outside runs

- **Owners**: `seon.eval/of-agent` (`src/seon/eval.clj:17`) feeding `seon.render.walk/history`.
- **Missing**:
  - History is every evaluation recorded for the agent id. An outside MCP/JVM run made as that agent (an observer, a
    setup, a fixture) enters the model's context. Live run 1, defect 7.
  - The history must be the agent's own replies and the REPL replies to them, whole, with outside runs excluded by
    a declared marker on the evaluation, not by agent id.
  - The agent's reply text also has to be shown, not only its forms.

### 8. A variant switch as agent settings

- **Owners**: `seon.ai/agent-setting-attributes` and `agent-overlay` (`src/seon/ai.clj:301`, `:320`), and the
  per-agent dials in `seon.config.ai.edn`.
- **Missing**: no setting selects the system-message variant (help profile), the reply-format paragraph, the
  reference presentation, the worked example or the call cap per agent. Each should be a declared per-agent dial, so
  an experiment is a set of agents with different overlays and the same loop.

### 9. Post-run checks as task proofs

- **Owners**:
  - the task's proof tests (`:seon/tasks` tags, `seon.task/task-edges`, `src/seon/task.clj:270`);
  - `my.test/check` (`src/my/test.clj:6`);
  - `clojure.test/run-tests` in SCI, for scratch tests.
- **Missing**:
  - A task can't carry its fixtures and proof as data to be seeded on the agent's branch.
  - Completion doesn't run the proofs: done is `my.turn/complete` or `propose-merge!`.
  - An agent branch sees none of its cluster's test evidence (task
    `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence`).
  - Each micro-task above should be a task whose proof is its post-run check, so the loop judges completion itself.

### 10. The feedback turn as a message to the agent

- **Owners**: `my.message/send` (`src/my/message.clj:48`), and the wake that answers a message.
- **Missing**:
  - Once a task completes, its agent ends. Nothing wakes a finished agent with one message in its same context.
  - The answer can't be recorded as feedback data attached to the run, rather than as a new task attempt.
  - Needed: after completion, root sends one message, the agent answers in its retained context with no evaluation,
    and the answer is stored on the agent's record.

## Design check

- **Did the design hold against the real code?** Partly.
  - The provider seam fits: one system string plus one prompt string. `complete` ignores `no-provider` when called
    directly, which is why a harness could bypass default's config without changing it.
  - The evaluation entrance fits: `seon.mcp/call` as a per-run agent.
  - Everything else (items 1-10) needed harness stand-ins, and that is the owner's point: the stand-ins would have
    tested a loop Seon does not run.
- **Awkward**:
  - C1's `user=> (source x)` cannot be produced by the real REPL today: bare `source` is unresolved, and
    `clojure.repl/source` prints.
  - S1 had to be edited (TARGET marks removed and the run-tests line added), which confounds S1 against S2.
  - A model's text could only come back uncut through a raw prepl, not through an entrance. Every entrance passes the
    value through the value printer.
- **Not proven**: every conclusion about formats. No matrix run was made.

## Leftovers on default

- Agent `steer`: scratch namespace `scratch.steer` (`sq`, `sq-test`) on its branch. It is gone at root's reset.
- Task cf5f4653ad02 is a runtime task. If the reset drops runtime tasks, it needs re-creating from this note.
