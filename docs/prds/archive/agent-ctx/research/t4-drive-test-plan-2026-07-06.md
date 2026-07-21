---
type: research
status: active
tags: [research, agent]
---

# T4 live-drive test plan — the handoff gate

Concrete, executable plan for spec §A5 step 3 (the T4 gate). DeepSeek-driven
agents exercise EVERY new tool through real use on NON-Clojure repos, on a
FROZEN bundle, with a DEDICATED observer reading the rendered transcript each
turn. A clean drive unblocks A5 step 4 (the A/B handoff to the waiting bench
agent). This file is the plan; it builds nothing.

## TL;DR

- **Corpus:** frozen copies of ~8 Exercism exercises from
  `reference-code/aider-polyglot` — 6 Python (jest-less, pytest) + 2 JavaScript
  (jest) — staged read/write under `tmp/t4-drive/`. Named below. Each ships a
  near-empty stub, a big test file, `.docs/instructions.md` (the task contract
  seed), and `.meta/example.py|js` (the gold oracle).
- **The load-bearing constraint** (agent-ctx CLAUDE.md): every behaviour a
  pass-criterion checks is STATED in the task contract handed to the agent.
  Otherwise T4 measures prompt-omission, not the tools. Each contract below
  spells out the tool sequence explicitly.
- **Tools that a coding task forces naturally:** grep (+ `::context-lines`),
  view, `#code` heredoc, replace!, insert!, background pytest via `run-bg!` +
  `job-status` + `job-output ::since`, final `pytest` green. Tools a coding task
  does NOT force — the **ambiguous replace!**, **blob put/text**, and
  **web search→fetch→blob** — are driven by INJECTED probes: a planted
  repeated-line edit target, an over-preview test run, and a contract clause
  requiring a doc lookup.
- **Scoring:** per-tool objective pass criteria checkable from the transcript +
  db (table below) + a dedicated observer rubric over the rendered agent-facing
  text (A7 garbage checklist). Outcome gate = final tests green via the
  git-apply-independent oracle (the exercise's own `.meta/example` passing the
  same test file).
- **Flake policy:** 3 drives/task; LLM nondeterminism is retryable, a tool
  defect (wrong-place edit, malformed envelope, lost recovery handle, crash)
  GATES. Frame the pass as `pass^k` over the tool-sequence criteria, not
  outcome-only (a right answer can still hide a broken tool).
- **Isolation:** a dedicated FROZEN cluster (`bin/seon cluster create t4drive
  --frozen`) so peer hot-reloads cannot swap code mid-drive (the class-2
  instability, 4+ crash datapoints 2026-07-06). Evidence under
  `evals/runs/2026-07-06-t4-tool-drive/`.

## 1. Task corpus — the exercises + why each

Source: `reference-code/aider-polyglot/{python,javascript}/exercises/practice/<name>/`.
Each dir holds `<name>.py` (stub, ~2 lines), `<name>_test.py` (the spec),
`.docs/instructions.md` (task text), `.meta/example.py` (gold). Exercism
exercises are single-source-file, so multi-file editing is an INJECTED probe
(see `paasio` + the split-module clause), not naturally emergent.

| # | Exercise (track) | Dir | Naturally forces | Injected probe |
|---|---|---|---|---|
| 1 | `two-bucket` (py) | `python/.../two-bucket` | warmup: view → `#code` heredoc → replace!/insert! → pytest. 56-line test, BFS solution (~53-line gold). | **ambiguous replace!** — plant a repeated line in the seeded solution (see §3), require a one-line change to it. |
| 2 | `grep` (py) | `python/.../grep` | grep TOOL use on the exercise's own multi-flag source; 294-line test with many flag cases → iterative edits. | `::context-lines` clause: "use grep with context lines to locate each flag branch". |
| 3 | `book-store` (py) | `python/.../book-store` | a known-tricky DP grouping problem — free models routinely ship wrong code → real run-tests → fail → re-edit → re-run loop. | **over-preview pytest**: seed a solution that fails many cases so the run-bg! output is large → forces `job-output ::since` paging + `my.blob/put!` of the stashed result. |
| 4 | `react` (py) | `python/.../react` | reactive cells + callbacks, 271-line test, many methods → several `insert!` + `replace!` edits across one file. | web clause: "confirm the observer/callback semantics — search the web for 'observer pattern callbacks', fetch one result, `my.blob/put!` the page". |
| 5 | `poker` (py) | `python/.../poker` | hand-ranking, 223-line test, many branches → multi-edit iteration. | `::expected-count` clause on a repeated `Hand` construction line. |
| 6 | `paasio` (py) | `python/.../paasio` | the ONLY multi-source-file Python exercise (`paasio.py` + `test_utils.py`) → genuine multi-file edits (view+replace! in two files). | none needed (multi-file is native). |
| 7 | `grep` (js) | `javascript/.../grep` | language-2 (JavaScript, jest). Has `data/*.txt` sample corpora → natural grep-tool targets; jest runner via `run-bg!`. | insert! a new helper function above an anchor. |
| 8 | `book-store` (js) | `javascript/.../book-store` | language-2 iterative fix loop with jest. | ambiguous replace! on a repeated `books` reference. |

Language-2 note: aider-polyglot ships **JavaScript** (jest), not TypeScript. Use
the JS track for "one more language"; if a true TS repo is later wanted it is a
separately hand-authored small repo, but JS/jest is the frozen-corpus path and
exercises the same tool surface. Go/rust/java/cpp tracks also exist if a
compiled-language pass is ever wanted (not for T4).

### Staging (frozen copies under tmp/)

```
tmp/t4-drive/
  py/two-bucket/  py/grep/  py/book-store/  py/react/  py/poker/  py/paasio/
  js/grep/  js/book-store/
```

Per task, `cp -R` the exercise dir, then:
- delete `.meta/` from the agent-visible tree (keep a copy OUT of the fs grant
  as the oracle) so the gold solution is never in the workspace;
- for probe tasks, overwrite the stub with a SEEDED solution (from `.meta` or a
  hand-planted one) carrying the repeated-line / failing-cases property the
  probe needs;
- `SEON_FS_ROOT`/the fs grant is scoped to `tmp/t4-drive/<track>/<task>/` per
  drive; oracle + `.meta` live outside it.

## 2. The task contracts (agent-facing goals)

The contract is the ONLY place the scored behaviours may be stated (load-bearing
finding). Template — every drive's `input` to `POST /agents/run` follows it, the
bracketed clauses swapped per task:

> You are working in the directory `<abs path under tmp/t4-drive/…>`. Your goal:
> make `pytest <test_file>` (or `npx jest`) pass. Do it with these tools, in
> this order, and narrate each step:
> 1. `seon.agent.search/grep` to locate the code you must change — use
>    `:seon.agent.search/context-lines 3` so you see surrounding lines.
> 2. `seon.agent.fs/view` the file (note the returned `:seon.agent.fs/file-sha`).
> 3. Write new code as a `#code/python <<END … END` heredoc literal, then apply
>    it with `seon.agent.fs/replace!` (pass `:seon.agent.fs/find` and
>    `:seon.agent.fs/replace`). If replace! returns candidates because the
>    anchor is ambiguous, DO NOT retry blindly — read the candidates and add a
>    `:seon.agent.fs/near <line>` or `:seon.agent.fs/expected-count <n>` to
>    disambiguate. Use `seon.agent.fs/insert!` to add a new function.
> 4. Run the tests in the BACKGROUND: `seon.agent.shell/run-bg!` the pytest
>    argv, poll `seon.agent.shell/job-status` until it exits, and page the
>    output with `seon.agent.shell/job-output` using `:seon.agent.shell/since`
>    so you only read new bytes. If the output is large, `my.blob/put!` the
>    stashed `result/<id>` value and read it back with `my.blob/text`.
> 5. [Probe clause: e.g. "Before coding, search the web for '<topic>', fetch the
>    top result, and `my.blob/put!` the page for your notes."]
> Stop when the tests are green.

Per-task probe clauses are the "Injected probe" column of §1. Contracts live
verbatim in `evals/runs/2026-07-06-t4-tool-drive/contracts/<task>.md`.

## 3. The injected probes (tools a coding task won't force)

- **Ambiguous replace! → candidates flow.** Exercism stubs are near-empty, so
  `replace!` never sees ambiguity naturally. Seed the solution file with a
  line that appears ≥2× (e.g. two identical `count += 1` or `return None`
  lines, or repeated `books` refs). The contract asks the agent to change ONE
  of them. `replace!` without `::near`/`::expected-count` returns
  `{:seon.agent.fs/ok? false, :seon.error/data {:seon.agent.fs.match/reason …,
  :seon.agent.fs.match/candidates [line-numbered …]}}` (fs.cljs `cascade-fail`,
  ~L690). PASS = agent received the candidates AND its next edit used
  `:seon.agent.fs/near` or `:seon.agent.fs/expected-count` (never a blind
  retry, never a wrong-place mutation).
- **Over-preview test output → paging + blob.** Seed `book-store` with a
  solution that fails many cases so the pytest stream is large. Since shell
  output is now an ordinary eval value (A6.6 — no verb caps, full `::out` with
  honest `::out-tokens`/`::truncated?`), the full text rides `result/<id>` and
  the render sampler bounds the DISPLAY. PASS = agent used `job-output
  ::since` to page incrementally AND `my.blob/put!` on the stashed value +
  `my.blob/text` to read it back (the durable-content promotion, per the
  "which tier holds a verb's big output" rule).
- **Web search → fetch → blob compose.** Contract clause requires a doc
  lookup. PASS = `seon.agent.web/search` (returns `::results` +
  grounded `::answer`) → `seon.agent.web/fetch` a `::url` (returns
  `::blob-hash`, page auto-blobbed) → `my.blob/text`/`put!` to compose notes.
  Requires `SEON_WEB` granted on the drive cluster.

## 4. Pass criteria per tool (objective, transcript/db-checkable)

Each is checkable from the persisted turn/eval log (`seon.agent.inspect/turn`)
or the fs/blob db state — no human judgement:

| Tool | PASS criterion |
|---|---|
| `grep` | at least one call with `::context-lines > 0`; result showed line-numbered context; agent's next action referenced a line it surfaced. |
| `view` | called before the first edit; agent captured/used the returned `::file-sha` on the subsequent `replace!`. |
| `#code` heredoc | at least one edit whose `::replace`/`::content` came from a `#code/<lang> <<SENTINEL` literal (eval-source dual repr present on the eval datom). |
| `replace!` (normal) | ≥1 `ok? true` anchored edit; `::range-after`/`::lines-added` present; resulting file byte-equals intent (no wrong-place mutation). |
| `replace!` (ambiguous) | the planted edit returned `ok? false` + candidates; the FOLLOW-UP edit added `::near` or `::expected-count` and succeeded. **Wrong-place mutation = hard fail.** |
| `insert!` | ≥1 `ok? true` insert with `::after-line`/`::before-line`; inserted block present at the right place. |
| `run-bg!` | pytest/jest spawned as a job; `::job-id` returned. |
| `job-status` | polled ≥1× to a terminal `:exited` + exit code (not a single blind wait). |
| `job-output ::since` | ≥1 call passing `:seon.agent.shell/since` (the previous `::next-since`) — incremental paging, not a full re-read each poll. |
| `my.blob/put!` + `text` | a `result/<id>` stash (or fetched page) promoted to a blob (`::hash`) and read back via `text`. |
| `web/search` → `fetch` | search returned `::results`; a `::url` fetched to a `::blob-hash`; blob resolvable via `my.blob/text`. |
| Outcome gate | final `pytest`/`jest` exits 0 on the agent's file (oracle cross-check: the same test file passes with `.meta/example` — proves the tests are real). |

## 5. Observer rubric (dedicated agent, reads rendered transcript each turn)

The observer is a SEPARATE agent (not root, cheap model is fine for reading;
the spec wants a dedicated one). It reads the **agent-facing rendered text** —
NOT the raw envelopes — via `seon.agent.inspect/turn {::eid …}` (byte-exact
replay from `:seon.agent.turn/rendered-as-of` + the prompt/reply blobs) or
`ctx-preview`. It never drives; it judges. Per turn it answers:

**The garbage checklist (from the A7 audit categories —
`research/rendered-output-audit-2026-07-06.md`):**

1. **Shape-preserving?** Clipped values keep valid structure to the clip
   (get-in paths still valid); no mid-token cuts presented as parseable.
2. **Honest markers?** Every truncation carries a LOUD marker; sizes are in
   TOKENS not chars (A7 flagged a mixed-units row); no silent drops.
3. **Recovery handle present AND usable?** `result/<id>` resolves; a blob
   `::hash` pages via `my.blob/text`; a `from-line` re-view works. (A7 headline
   defect: shell recovery handles elided by map-sampling — verify the shell
   verb's handle survives the render.)
4. **No garbage:** no invalid-EDN presented as EDN without the "don't parse"
   marker, no escaped/mojibake blobs, no useless empty previews, no noise
   fields (redundant keys the agent can't act on).
5. **Did the tool's output actually help the agent's next step?** (the
   flag-garbage rule — READ what the agent saw and ask if it was actionable.)

**Observer report shape** (one row per turn, appended to
`evals/runs/2026-07-06-t4-tool-drive/observer/<task>.md`):
`turn | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged`.
A garbage flag with a verbatim sample → orchestrator triages → fix agent →
re-drive. Clean across all turns × all tasks = the gate passes.

## 6. Flake policy (pass^k, defect vs noise)

- **k = 3 drives per task** (8 tasks → 24 drives). More only if a criterion
  flakes near threshold.
- **Retryable (LLM nondeterminism, NOT a gate failure):** the model chose a
  different-but-valid tool order, needed an extra turn, phrased a heredoc
  oddly, or hit a provider timeout. Re-drive; do not count against the tools.
  These are the inspect-ai "epoch" replicates.
- **Gating (tool defect — fix, never retry-past):** a wrong-place mutation, a
  malformed/again-shaped envelope, a lost recovery handle, mixed units, a
  crash/pod fault (`SEON-CORE-FAULT`), an ambiguous edit that guessed instead
  of returning candidates, `job-output ::since` returning stale/duplicated
  bytes. One occurrence gates.
- **Framing:** report per-tool `pass^k` (all k drives hit the criterion) as the
  headline — the noise-robust `pass@k` (at-least-one) ALONE hides tool
  intermittency, mirroring the harness's "report BOTH reducers" rule
  (`Epochs(k, ["mean", pass_at(k)])`, src-inspect-ai/README.md). Outcome-green
  is necessary but NOT sufficient: a task can pass with a broken tool the model
  routed around, which is exactly what T4 must catch.
- **Uniform-0 rule (inherited):** if a whole tool scores 0 across tasks,
  suspect the CONTRACT (prompt-omission) or the cluster grant first, not the
  tool — re-check the contract states the behaviour and `SEON_SHELL`/`SEON_WEB`
  are granted, before filing a tool defect.

## 7. Runbook

Prereqs (A5 step 0, owner-ack'd): `[seon.agent.fs :as fs]` in
`config/system.edn` `:seon.eval/home-requires` so the anchored-edit verbs
render discoverable; fs write granted on the workspace.

```bash
# 0. Frozen, isolated cluster (peer hot-reloads can't swap code mid-drive)
bin/seon bench-bundle                      # build the frozen bundle once
bin/seon cluster create t4drive --frozen   # own pod + wire db, frozen bundle
bin/seon status                            # note t4drive's pod port (port file)
bin/seon print-env                         # confirm SEON_SHELL + SEON_WEB granted
# scope fs write to the workspace for this cluster (.env or configure! in-drive):
#   SEON_FS_ROOT=<abs>/tmp/t4-drive   SEON_FS_READ_ONLY=false

# 1. Stage frozen task copies (script writes tmp/t4-drive/**, strips .meta into
#    an out-of-grant oracle dir)
tmp/t4-drive/stage.sh                       # cp -R from reference-code/aider-polyglot

# 2. Drive one task (DeepSeek is the default provider; pre-authorized).
#    POST /agents/run to t4drive's pod: {"input": <contract>, "timeout_ms": …,
#    "agent_id": "<reuse across turns>"}. Body is opaque JSON (serve.cljs ~L454).
curl -s http://127.0.0.1:<t4-port>/agents/run \
  -H 'content-type: application/json' \
  -d @evals/runs/2026-07-06-t4-tool-drive/contracts/two-bucket.json
#    (agent's OWN FSM decides turns; caller never does — reuse agent_id for
#     multi-turn continuity across the drive.)

# 3. Observer reads each turn's rendered text (separate agent id):
#    (seon.agent.inspect/turn {:seon.agent.inspect/eid <turn-eid>})
#    (seon.agent.inspect/ctx-preview {… id …})   ; byte-identical to the prompt

# 4. Score: per-tool criteria from the turn/eval log; outcome gate via the
#    out-of-grant oracle (same test file + .meta/example → must pass).

# 5. Teardown
bin/seon cluster destroy t4drive
```

Bounds: `timeout_ms` per drive generous (bench pytest runs — `run` has no hard
timeout below several minutes, spec A6.5); cap ~15-20 turns/drive (spec says
10-20). A drive that stalls past the turn cap without green = an incomplete
sample (re-drive), not a tool pass.

### Evidence dir layout

```
evals/runs/2026-07-06-t4-tool-drive/
  README.md              # the metrics table: per-tool pass^k over 8 tasks × 3 drives
  contracts/<task>.json  # the exact agent-facing input per task
  observer/<task>.md     # per-turn observer rows + verbatim garbage samples
  transcripts/<task>-<drive>.txt   # inspect/turn byte-exact replays
  defects.md             # any gating defect: verbatim + file:line + fix sha
```

Gate result appended to `docs/prds/agent-ctx/coordination.md` slice log and
`docs/prds/agent-ctx/CLAUDE.md` state. ONLY a clean drive (every per-tool
`pass^k` = 1, observer clean, outcome green with oracle cross-check) posts the
"T4 clean — A/B unblocked" note to the eval lane.

## 8. Reference-code grounding (concrete artifacts to mirror)

- **Corpus** — `reference-code/aider-polyglot/{python,javascript}/exercises/
  practice/<name>/`: `<name>.py` stub, `<name>_test.py` spec,
  `.docs/instructions.md` contract seed, `.meta/example.py` gold oracle;
  `grep` ships `data/*.txt`; only `paasio` is multi-source-file.

- **swe-agent — the str_replace edge-case contract to mirror.** Their ACI
  editor `reference-code/swe-agent/tools/edit_anthropic/bin/str_replace_editor`
  (`str_replace()`, ~L516-537) enforces exactly the cases our anchored-edit
  criteria must check, via distinct outcomes: **no-match** ("did not appear
  verbatim", exit 15) → our `ok? false`; **multi-match** ("Multiple occurrences
  … in lines [ns]. Please ensure it is unique", exit 16) → our candidates flow
  (fs `cascade-fail` returns line-numbered `::candidates` — SAME idea, richer
  envelope); **no-op** (`old_str == new_str`, exit 161) → our replace! should
  likewise not silently succeed; **whitespace** (`.expandtabs()` both sides
  before matching) → confirm our cascade's normalization stage handles
  tab/space (the `norm-rescue` stage). Their editor also runs a lint diff
  pre/post edit — analogous to our A4 pytest-parse section. Note: swe-agent's
  own `tests/tools/test_edit_replace.py` is EMPTY — the contract lives in the
  tool, not a unit test; our equivalent contract lives in `match.cljc` + T2's
  gold replay (WRONG=0 already proven), and T4 exercises it LIVE.
- **swe-agent trajectory inspection** — `.traj` step shape
  `{action, observation, response, thought, execution_time, state}`
  (`tests/test_run_replay.py` replays one deterministically). Our analog is the
  turn/eval log replayed byte-exact by `seon.agent.inspect/turn` — the observer
  reads the same per-step record.
- **inspect-ai scoring** — reducers in
  `reference-code/inspect-ai/src/inspect_ai/scorer/_reducer/reducer.py`:
  `at_least(k)` (our per-tool `pass^k` = "all k hit" ≈ `at_least_k`),
  `pass_at(k)` (NaN-safe: returns NaN, not a false 1.0, when < k epochs
  survived), `mean_score`. `Score.unscored()` (value=NaN) is the canonical
  "ungradeable epoch — skip it" sentinel → our re-drive-on-nondeterminism maps
  to it. `Epochs(k, ["mean", pass_at(k)])` = the README's "report BOTH
  reducers" rule §6 restates.
- **inspect-ai model-graded rubric** — `model_graded_qa(include_history=True,
  model=[…majority-vote…])`, grade regex `(?is).*GRADE\s*:\s*([CPI])`
  (`_model.py`). The observer's per-turn judgement (§5) is this shape: read the
  full rendered history, emit a C/P/I with an explanation; a cheap grader model
  is fine, a majority vote if a flag is borderline.
- **tau2-bench — the multiplied reward model.** `RewardType` /
  `EvaluationCriteria` (`src/tau2/data_model/tasks.py`): final reward = PRODUCT
  of components (any zero zeros the task), splitting **outcome, order-
  independent** (DB end-state, env assertions) from **trajectory** (ACTION list
  matched by tool calls) from **COMMUNICATE** (required substrings appear in
  messages). Our T4 gate is the same product: per-tool trajectory criteria (§4)
  × observer-clean (§5) × outcome-green (the test oracle) — a right answer with
  a broken tool still zeros. The user-simulator
  (`user/user_simulator.py`, role-flip + STOP/TRANSFER termination) is the
  structural analog of the DeepSeek drive; success is a task-defined reward
  oracle, never the driver's say-so.
- **terminal-bench — task = contract + test-script pair + assertion ladder.**
  A task dir (`original-tasks/regex-log/`) = `task.yaml` (instruction, canary
  GUID, `parser_name: pytest`, `max_agent_timeout_sec`, `max_test_timeout_sec`)
  + `solution.sh` (oracle) + `run-tests.sh` + `tests/test_outputs.py`. Two
  patterns to copy: (a) the **three-stage assertion ladder** — exists → valid
  (compiles/imports) → correct — which our outcome oracle should follow
  (file present → imports → tests pass); (b) **positive/negative sample rows
  with inline `# Y:`/`# N:` rationale** — the style for documenting each probe's
  expected candidate/refusal behaviour in `contracts/<task>.md`. Our contract
  JSON + the exercise's own test file = the task.yaml + test-script pair; add a
  canary GUID per contract so a leaked contract is detectable.
</content>
</invoke>
