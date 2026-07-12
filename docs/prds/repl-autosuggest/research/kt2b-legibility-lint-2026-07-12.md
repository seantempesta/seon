---
type: research
status: active
tags: [research, agent]
---

# KT2b — legibility lint of the seon fn surface via needle's home format

**Date:** 2026-07-12 · **Model:** stock needle checkpoint (26M, zero
training) via the `src-needle/` MLX port (commit `5481ab36`), constrained
decoding ON · **Index:** 168 agent-facing fns + 1213 registered schemas
dumped from the live acme store at basis-t 536877061
(`src-needle/scripts/dump_fn_index.clj`) · **Probe code:**
`src-needle/src/seon_needle/lint_probe.py` (temporary translation layer,
says so in its docstring) · **Cases:** `src-needle/cases/kt2b_cases.json`
(169: 145 targeted incl. agy paraphrases + 24 irrelevance),
harness-derived per the owner's sourcing directives.

## TL;DR

- **Anchor (BFCL live_simple, its home benchmark):** name accuracy **0.77**
  with its native menu, **0.65** with 8-tool menus.
- **Our fn index, 8-tool menus: name accuracy 0.283** (chance 0.125, 2.3×
  chance) — in design.md's "<~30% weak zero-shot signal" band: informs,
  does not kill; the finetune remains the real test. Menu-of-1 sanity arm:
  parse rate **1.0**, name F1 0.87 — the model reads our translated tools
  and emits well-formed calls; the gap is *discrimination*, not parsing.
- **The 16-distractor arm is an envelope measurement, not a discrimination
  one: 165/169 menus overflowed the 1024-token encoder budget** (median
  translated tool def = 70 tokens, our workhorse defs run 160–245).
  Menu16 name acc 0.131 (chance 0.0625).
- **Copy fidelity is fine** (KT2-adjacent evidence): when the name is
  right, per-key args accuracy is **0.73** (n=44 keys, ids/strings copied
  from the query) — the assembly machinery works on our identifiers.
- **False-suggestion rate 0.25** on the irrelevance arm (24 no-fn-applies
  situations; 18 correctly abstained with `[]`). For a block that renders
  every turn this is the poison number to drive down — see the per-case
  list below.
- **The leaderboard v0 exists** (below): `write-file`/`complete` at 1.00;
  `transact!`, `query`, `register!`, `step!`, `done!`, `next` at 0.00 —
  and the failure attributions point at docstring line-1s written in
  implementation vocabulary ("Commit tx-data — forwarded to the JVM
  writer") rather than capability vocabulary ("persist facts").
- **n=3 per fn** (1 base + 2 paraphrases) — per-fn numbers are coarse
  (±1 case = ±0.33); treat tiers, not ranks.

## The arms

| arm | cases | name acc | chance | parse | name F1 | args/key | menus truncated |
|---|---|---|---|---|---|---|---|
| BFCL native (menu-of-1) | 100 | 0.77 | forced* | 0.94 | — | 0.49 | 0 |
| BFCL + 7 distractors | 100 | 0.65 | 0.125 | 0.97 | — | 0.51 | 0 |
| seon menu-of-1 (sanity) | 145 | 0.669 | forced* | **1.00** | 0.872 | 0.73 | 0 |
| seon + 7 distractors | 169 | **0.283** | 0.125 | 0.97 | 0.374 | 0.75 | 1/169 |
| seon + 15 distractors | 169 | 0.131 | 0.0625 | 0.95 | 0.167 | 0.60 | **165/169** |

*constrained decoding clamps the name trie, so menu-of-1 "misses" are
abstentions (`[]`) or malformed multi-calls, not wrong names. The seon
menu-of-1 abstain pattern is itself signal: needle declines to act on a
third of our situations even when only the right tool is offered.

Verdict against design.md §Measurement thresholds: **weak zero-shot
signal** (0.283 < ~0.30 band edge at 8 distractors, vs ~0.50 strong). Per
the KT2b entry this *informs but does not kill* — and the deliverable
either way is the leaderboard. The BFCL anchor makes the number
interpretable: the same checkpoint, same decoder, same scorer gets 0.65 on
its home distribution — so ~0.37 of the gap is domain, not machinery.

Scoring is needle's own F1 methodology adapted from
`reference-code/needle/needle/training/finetune.py::_quick_tool_eval`
(name TP/FP/FN; exact-args among name-matches; here augmented with
per-key accuracy and abstention scoring for the irrelevance arm).

## The leaderboard v0 (name accuracy @ 8-tool menus, n=3 per fn)

**1.00** `seon.agent.fs/write-file` · `seon.agent.lifecycle/complete`

**0.67** `my.blob/concat!` · `my.kb/remember` · `my.kb/source-detail` ·
`my.plan/needs!` · `my.plan/reconcile!` · `my.plan/reopen!` ·
`my.plan/tree` · `seon.agent.message/agent` · `seon.agent.message/user` ·
`seon.agent.shell/run-bg!`

**0.33** `my.blob/stat` · `my.canvas/show!` · `my.kb/remember-sources!` ·
`my.skills/load` · `my.ui/table` · `seon.agent.fs/edit-file` ·
`seon.agent.search/grep` · `seon.agent.shell/job-output` ·
`seon.agent.shell/py-run` · `seon.agent.web/fetch` ·
`seon.agent.web/search` · `seon.db/as-of` · `seon.db/store-inventory` ·
`seon.repl.autocomplete/rate!` · `seon.schema/schemas-in-namespace`

**0.00** `my.blob/put!` · `my.blob/text` · `my.data/group-sum` ·
`my.kb/forget-source!` · `my.plan/active!` · `my.plan/done!` ·
`my.plan/drop!` · `my.plan/move!` · `my.plan/next` · `my.plan/plan!` ·
`my.plan/step!` · `seon.agent.fs/list-dir` · `seon.agent.fs/read-file` ·
`seon.agent.search/grep-graph` · `seon.agent.shell/run` ·
`seon.db/entity` · `seon.db/new-id!` · `seon.db/query` ·
`seon.db/transact!` · `seon.embed/search` · `seon.schema/register!` ·
`seon.test.runner/run-ns!` (n=1)

Per work-kind @8: lifecycle 1.00 · messaging 0.67 · kb-read 0.67 ·
{terminal, files, web, ui, skills, curation, kb-write, plan-read} 0.33 ·
blob 0.25 · plan-mutation 0.22 · db-read 0.17 · schema 0.17 · search 0.11
· data 0.00 · **db-write 0.00** · testing 0.00 (n=1).

Three `also_accept` picks (near-twin siblings: `fs/view` for `read-file`,
`fs/replace!` for `edit-file`, `embed/search-pull` for `search`) are
counted as WRONG above; counting them right moves the @8 headline
0.283 → 0.303.

## Failure attribution — the worst, with what the model actually saw

**`seon.db/transact!` (0/3; picked `as-of` ×3, `bootstrap-row-ids` ×2).**
Query: *"Persist these three facts durably in one commit: cache KESTREL
weighs 42.5 kg…"*. Tool def shown:

```json
{"name":"seon.db/transact!","description":"Commit tx-data — forwarded to
the JVM writer, returns an envelope.","parameters":{"tx_data":{"type":
"array","description":":seon.db/tx-data","required":true},…}}
```

Model output: three calls of `seon.db/as-of` with fact fragments stuffed
into its params. Attribution: **docstring line-1 is implementation
vocabulary** — "tx-data", "JVM writer", "envelope" — with zero bridge to
"persist / store / save facts". A frontier model knows transact = write;
a 26M assembler has only the card. Same story for every db-write miss.

**`seon.db/query` (0/3; abstained `[]` ×3).** Query: *"Computed from the
database, not from memory: what is the total weight of all caches
strictly heavier than 10 kg?"* Tool def: `"Run a Datalog query, returning
the result set."` with one opaque required param
(`request: array — :request`). Attribution: **capability absent from the
card** — nothing says "ask questions of stored data / aggregate / count";
plus the translated param is opaque (`:seon.db/query-request` is an
`[:or vector map string]`, not a documented map), so the model has neither
a semantic nor a structural hook. It preferred to emit `[]`.

**`my.plan/done!` (0/3; picked `drop!` ×2, stray nullary fns).** Query:
*"Step kJm-2607121415 is finished and its outcome verified — record its
completion."* Def: `"Mark a step done; may unblock its dependents next
turn."` Attribution: finish/complete/close vocabulary scatters across
`done!`/`drop!`/`complete` — "done" is the only bridge word and it sits
mid-sentence; `drop!`'s "Retract a step" reads equally final to a model
with no notion that retract ≠ complete.

**The sibling-confusion channel works as designed** (the hard case the
menus force): `group-sum`→`sum-by`, `blob/text`→`stat`,
`embed/search`→`search-pull`, `ui/table`→`kv-table`,
`step!`→`needs!`, `kb/remember-sources!`→`source-entity`. These pairs are
where docstring line-1 differentiation pays off directly.

**Abstention is the dominant failure mode** (`<none>` in the picks): the
model answers `[]` for question-shaped situations (`my.plan/next` 3/3,
`db/query` 3/3, `grep-graph` 3/3). Reads (`next`, `tree`, `list-open`,
`status`) whose docstrings are noun-phrases ("Your focus queue: READY
leaves…") never look *callable* to needle's training distribution.

## Irrelevance arm — false suggestions (the every-turn poison)

24 situations where NO fn applies (8 verbatim BFCL live_irrelevance, 4
OSWorld GUI, 2 interactive-web, 10 knowledge/chat). **Abstain 18/24 =
0.75; false-suggestion rate 0.25**, identical @8 and @16. The six misses
@8: `irr-bfcl-music`→`seon.db/installed-schema`,
`irr-bfcl-restock`→`shell/run-bg!`, `irr-osworld-slides`→
`db/current-agent-id`, `irr-webarena-booking`→`schedule/host-timezone`+
`db/listen!`, `irr-knowledge-rsa`→`db/listen!`, `irr-sing`→
`db/cas-assert`. Pattern: nullary/abstract fns with thin descriptions
attract junk matches — another docstring finding, and a serving-side
argument for a confidence gate before `:suggest` renders anything.

## Translation-layer notes (kept honest, per the brief)

- **What translated cleanly:** 168/168 fns produced a tool JSON; 122 with
  zero notes. Map-in request schemas → flat param dicts (name-part of the
  namespaced keyword, snake_cased; needle's own `to_snake_case` reused for
  tool names, name-map restored after decode). `:catn` named slots →
  params. Enums render as "one of:" descriptions.
- **46 fns carry gap notes** (recorded in `data/kt2b/seon_probe_results
  .json:gaps`): multi-arity `:function` specs (first arity only — 14 fns),
  `:any`-typed params (`:seon.db/db`, cas values), fn-valued schemas that
  pr-str as `#object[...]` (`schedule/fire-due-schedules!`,
  `:seon.retry/*`), symbol predicates (`map?`), and opaque single args
  that don't resolve to a `:map` (`seon.db/entity`, `as-of`, `query`).
  The opaque-request fns are disproportionately the 0.00 tier — a real
  coupling between "schema doesn't project to params" and "model can't
  pick it".
- **Dump truncation found:** 8 `:seon.schema/source` values in the dump
  cut off at ~1000 chars (`:seon.agent.turn`, `:seon.config/manifest`,
  `fetch-response`, …) — all response/entity-shape schemas, none needed
  for request-side translation, but the storage projection cap is worth
  knowing about (flag: verify whether `:seon.schema/source` caps at write
  time; the probe treats them as unparseable gaps).
- **Params lose their namespaces** (`:my.plan/id` → `id`): needle's home
  format has bare snake keys; the qualified keyword is preserved in the
  param description. One collision class handled (both sides fully
  qualified on clash).
- Return schemas are not represented at all (needle tools have no return
  slot).

## Index reconciliation (seed fns that didn't exist or differ)

- `db/pull-by-name` — **does not exist**; the real fn is `seon.db/entity`
  (eid or lookup-ref) / `seon.db/pull`. Case re-targeted.
- **No general `my.kb` recall fn.** `source-detail`/`source-entity` are
  per-source pulls; recall over findings is `db/query`. Coverage gap
  worth an owner look: the kb manual teaches store (`remember`) but has
  no symmetric "what do we know about X?" entry point.
- **No ns/fn listing fn** in the agent surface ("what functions exist in
  my.plan?"); nearest is `seon.agent.search/grep-graph`. Gap noted.
- `seon.repl.autocomplete/rate!` **exists** (Track A landed it) — probed,
  0.33 @8.
- Test running is `seon.test.runner/run-ns!` (exists, specced).
- Messaging is `seon.agent.message/agent` (peer) / `user` (human) — both
  probed at 0.67.
- Canvas is `my.canvas/show!` (the `:seon.render.canvas/content` path);
  `my.ui/*` build the hiccup it pins.
- `my.plan/plan!` vs `reconcile!`-against-empty **both author a whole
  plan** — design.md says authoring IS reconcile-against-empty, the
  toolkit ships a separate `plan!`. The model scattered across both
  (scored with `also_accept`). A genuine two-mechanisms smell to resolve
  in the plan-targets lane.

## Where the problems came from (the benchmark sampling)

Owner directive (mid-run): mine `reference-code/`'s agentic benchmarks for
problems we have solutions for; keep the harness-source mapping per case.
What's vendored and what it contributed:

| benchmark | problem style (real instances) | maps to | used |
|---|---|---|---|
| `gorilla-bfcl` BFCL v4 `live_simple` (258 rows) | "Retrieve the details for the user with ID 7890" | `db/entity`, any single-call fn | **calibration anchor** (100 rows, both menu arms) + phrasing templates |
| BFCL v4 `live_irrelevance` (~880 rows) | "I need to check weather for tomorrow please." | abstention | **8 verbatim rows** in the irrelevance arm |
| `tau2-bench` (airline/retail/telecom tasks, `reason_for_call` user turns) | "You received your order #W2378156 and wish to exchange…" | request-with-ids phrasing register | phrasing templates for id-bearing cases |
| `terminal-bench` `original-tasks/` (241 tasks with `instruction:` yaml) | "Analyze the access log at /app/access_log and create a summary report /app/report.txt"; "There's something wrong with my python installation"; long builds | `shell/run`, `run-bg!`, `job-output`, `py-run`, `fs/read-file`, `write-file` | terminal + files cases |
| `agentbench` `os_interaction` (26 dev + training set) | "How many hidden files are in /home?"; "How much disk space is /home using?" | `shell/run` | terminal cases |
| `agentbench` `dbbench` (SQL-over-tables Q&A) | "How many weeks did X spend at the top of the chart?" | `db/query` aggregation phrasing | db-read cases |
| `webvoyager` (`WebVoyager_data.jsonl` + `GAIA_web.jsonl`) | "Find a report on BBC News about renewable energy developments in the UK" | `web/search`, `web/fetch` | web cases; interactive rows (cart/booking) → irrelevance |
| `osworld` (GUI tasks across chrome/gimp/libreoffice/vlc/thunderbird) | "Make VLC stay on top"; cell formatting | **no GUI surface** | 4 irrelevance rows (real agent problems we can't act on) |
| `swe-bench` / `swe-agent` / `aider-polyglot` / `commit0` / `swelancer` | localized code edits, exercism exercises, issue-to-patch | `fs/edit-file`, `replace!`, `search/grep`, `test.runner/run-ns!` | files/testing cases (phrasing only; instances not wired) |
| `src-inspect-ai` (ours) | `DB_MEMORY_CONTRACT`, `NS_MOVEMENT_CONTRACT`, `_LTP_CONTRACT` planning tasks | `register!`, `transact!`, `query`, `message/user`, `complete`, `my.plan/*` | rephrased (leakage guard — contracts literally name the fns) |
| our cljs test suites (`test/my/*`, `test/seon/*`) | canonical calls with real arg shapes | everything | args grounding + situations for kb/blob/plan/ui/data |
| not mined this round | `cybench` (CTF), `mle-bench`/`re-bench` (ML eng — would exercise `py-run`/`run-bg!` harder), `agentbench` knowledgegraph, `browsergym`/`webarena`, memory benches (`SimpleMem` LoCoMo, `mem0`) → natural `my.kb`/`embed/search` drivers | — | future case-bank expansions |

`reference-code/inspect-evals` ships a packaged `bfcl` inspect task — the
future path for folding this probe into the standard bench
(three-surfaces rule) instead of the probe's own runner.

**Coverage holes** (work kinds no harness exercises; kept from the
orchestrator's seed overlay): destructive plan-subtree removal (`drop!`),
suggestion-corpus curation (`rate!`), blob paging/stat, canvas pinning —
these exist only as unit tests + seeds; no external benchmark shape
matches them. The leakage guard fired on all `src-inspect-ai` contracts
(they name fns verbatim) — all four derived cases are marked
`rephrased: true` in the case bank.

## Ops incident recorded (owner request)

Mid-build, 4–5 concurrent probe processes reached 10–17 GB RSS each
(~50 GB) and pinned the shared machine. Root cause was **my probe's EDN
reader, not needle**: 12 registry rows embed printed tagged literals
(`#object[cljs$core$fn_QMARK_]`, `#IntoSchema {:type :inst}`); the
reader's `#` branch misparsed them, a stray closing delimiter then made
`read_atom` consume zero characters, and `read_seq` appended empty
symbols forever — unbounded list growth. The parallel processes were
retry fan-out around the silent hang. Fixes (committed): proper
tagged-literal parsing, a hard no-progress guard (`read_seq` raises),
per-form error boundaries that record a translation gap instead of
dying, and single-process runs. Post-fix the FULL pipeline peaks at
**121 MB RSS** in one process; index translation alone is 52 MB.

## Honest caveats

- **This measures ingredient quality, not end-task performance.** English
  queries + JSON tool defs are needle's pretraining distribution; our
  real projections (Clojure context, `;` prose, REPL transcript) are not.
  A fn scoring 0.00 here has illegible *ingredients* (name + docstring
  line-1 + schema params); it does not mean the finetuned model can't
  learn it — KT5 tests that.
- **n=3 per fn** — tier membership is meaningful, exact ranks are not.
- **Menu-of-1 name accuracy is constraint-forced**; its information is in
  the abstention rate and parse rate, not discrimination.
- **Menu16 is an envelope result** (165/169 truncated), and even
  the one-in-169 truncation at menu8 warns that an 8-card slot in the
  real encoder budget must use COMPACT cards (median 70 tokens is fine;
  the 160–245-token fs/search defs are not).
- Paraphrases were generated by `agy` (gemini-3.5-flash) with arg-value
  preservation validated mechanically; 2 paraphrases were auto-dropped
  for losing the ns arg.
- The stock checkpoint sometimes emits multiple calls for one-fact
  situations (the transact! failure) — needle's parallel-call habit from
  its training data; the scorer counts any non-exact call set as wrong.
- Constrained decoding falls back to unconstrained on off-trie arg keys
  (reference behavior, logged) — arg keys are therefore not strictly
  clamped; name selection is.

## What to do with the leaderboard (fix loop = ordinary code changes)

Per design.md ("the model is a lint"): the fixes are docstrings/names/
schemas, owner-gated where they touch context generation. The highest-
leverage rows, all with the same shape of fix (capability-vocabulary
docstring line-1, ≤72 chars, and a params-projecting request schema):
`seon.db/transact!`, `seon.db/query`, `seon.schema/register!`,
`my.plan/step!`, `my.plan/done!`, `my.plan/next`, `my.blob/put!`,
`seon.db/entity`. NOT fixed here — context generation is frozen to this
lane; this list is the report.

## Provenance / how to re-run

```
bin/acme up                                        # wire 7981 + pod 7980
nc -w 60 127.0.0.1 7981 < src-needle/scripts/dump_fn_index.clj
cd src-needle
.venv/bin/python -m seon_needle.lint_probe translate   # gaps + envelope
.venv/bin/python -m seon_needle.lint_probe calibrate   # BFCL anchor
.venv/bin/python -m seon_needle.lint_probe run         # 3 seon arms
```

Raw per-case records (query, menu, model output, truncation): 
`src-needle/data/kt2b/seon_probe_results.json`,
`src-needle/data/kt2b/bfcl_calibration.json` (gitignored; re-derivable).
Run log: `logs/kt2b-run.log`. Case bank + base:
`src-needle/cases/kt2b_cases{,_base}.json` (committed, `ccf6abba`).
