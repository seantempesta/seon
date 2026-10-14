---
name: agy-lanes
description: When and how to use Gemini 3.8 Flash and Gemini 4 Argon through bin/agy-agent (the Antigravity CLI), with the measured intuition behind it: finding root causes, judging a performance proposal before it is built, auditing a spec against the code, long performance hunts and independent plans; ready specs, the commands, resuming, and what agy loads by itself. Use before any bin/agy-agent or agy call.
---

# Argon and Flash lanes (agy)

`bin/agy-agent` is the one way to run an Antigravity lane (its usage header). The lane reads and writes the checkout,
and it sees `AGENTS.md`, every skill in `.agents/skills` and the `seon` MCP server (`runtime_status`, `eval_clj`) by
itself. A spec therefore names the work, not the house rules.

## Intuition (measured 2026-10-14)

Eleven blind runs with known answers, plus four open performance hunts on the live JVM:

| Task | Argon | Flash |
|---|---|---|
| find repeated work behind a measured symptom (`17ec81f0b`'s parent) | medium: right, 4.4 min | low: right, 1.5 min; medium 2.4 min; high 4.4 min with the best cache key |
| judge a proposal before measurement (`f18ab95e3`, later reverted) | medium: "drop", read Fressian's bytecode, 8.5 min | high: "drop", same mechanism, 6.5 min |
| audit TASK-CHAIN against the code | medium: 8 findings, one with invented code excerpts, 7.8 min | high: 11 findings, every quote real, 10.4 min |
| debug a live-lock blind (F53, `0c684db75`'s parent) | high and medium: unfinished after 30+ min | high: exact clause, causal chain and fix, 7 min |
| cluster the 872-task backlog by root cause | (pending) | high: 9 clusters; its moot list was 21 of 25 right |
| open performance hunts with live measurement | medium: 12-17 min each, solid measured findings, two lanes agreed on the top one | (not run) |

What that means:

- **Flash-high is the default.** It reads a lot (75-120 tool calls), quotes what it read, and was the fastest to an
  exact root cause. Flash-low is the quick tier for bounded diagnosis with a clear symptom.
- **Argon is for long, open-ended investigation** that synthesises across many files and measures at the REPL (the
  performance hunts), and for a second independent opinion beside Flash. Argon-high is too slow for interactive use;
  Argon-low (no thinking) is reported to match it on all but the hardest tasks. Use low unless a run shows it falls
  short.
- **Argon fills gaps from memory** when it synthesises: it once quoted code that does not exist. **Flash
  over-classifies:** it read "this task asks to delete X" as "X is already gone". Reproduce every quote and every
  retire-this list before acting.
- **Both propose against the design unless the brief names it.** An Argon lane proposed skipping the recording
  transaction that AGENTS.md requires on every eval request; both invent target bounds ("< 0.5 ms"). Put the
  invariants that matter in the spec.
- **Both catch measurement mistakes.** An Argon lane found that the orchestrator's own probe (`seon.profile/summary`)
  inflated the per-request call count 15-fold.
- **Tokens are mostly cached reads** (1-13M per run). A resumed turn re-sends the whole conversation: resume to finish
  work, start fresh for a new question.

So, best first:

1. **Find root causes.** A hard bug from its symptom (Flash-high), repeated work behind a measured symptom (Flash-low or
   -high), the producers behind a cluster of backlog tasks (Flash-high, verified).
2. **Judge a performance proposal before it is built or committed.** Both models read library source and bytecode to
   check the premise. The prediction is not proof: the commit is still proven end to end (`seon.profile/measure`).
3. **Audit a spec, card or skill against the code** (Flash-high), then reproduce each finding.
4. **Long performance hunts and independent plans** (Argon-low or -medium), beside Opus and Astra, neither seeing the
   others.

Neither does implementation lanes (Sol and Sonnet own those), mechanical sweeps, or product-agent runs (live runs are
DeepSeek).

## Models

| `AGY_MODEL` | For |
|---|---|
| `gemini-3.8-flash-high` | the default: root causes, audits, judging proposals |
| `gemini-3.8-flash-low` / `-medium` | bounded diagnosis with a clear symptom; lookups and web research |
| `gemini-4-argon-low` | long open-ended investigation, independent plans, second opinions |
| `gemini-4-argon-medium` / `-high` | only when low measurably falls short; high is too slow for interactive use |

The Argon models need the Google AI Ultra plan on the signed-in account (`agy models` lists what the account can
use; if Argon is missing, sign out and back in interactively in `agy`). Google rate-limits many small calls, so send
one call with the full context. Each resumed turn re-sends the whole conversation (a short second turn read about
150k tokens), so start a new lane for an unrelated question.

## Commands

| Command | What it does |
|---|---|
| `bin/agy-agent run <name> <<'SPEC' … SPEC` | launch; streams a readable transcript (model text, one `▸ tool target` line per tool call, a usage line) to stdout and `tmp/orchestrator/agy/<name>/transcript.log`; the final answer goes to `summary.txt` |
| `bin/agy-agent resume <name> <<'SPEC' … SPEC` | continue the same conversation with full prior context; the id is the `init` event's `conversation_id`, kept in `tmp/orchestrator/agy/<name>/sid`; `AGY_SID=<id>` overrides |
| `bin/agy-agent stop <name>` | pause: stops the agy process tree and keeps the sid; the interrupted turn ends `ERROR`, and `resume` continues from it |
| `bin/agy-agent summary <name>` / `watch <name>` | the final answer and `{status, usage}`; follow the live transcript |
| `bin/agy-agent status` | running lanes with pid, and lanes finished in the last hour with their usage |

Launch as a harness-tracked background command, one lane per shell. `run` refuses a name that already has a sid
(resume it, or pick a new name), and both commands refuse a lane that is running. `stream.jsonl` keeps the raw
events of the last turn for `jq`. Dials: `AGY_MODEL`, `AGY_TIMEOUT` (`0`, the default, waits for the turn to finish;
e.g. `30m`), `AGY_SID`.

The launcher appends a short lane rule to the first spec: the lane's name, no edit hook (it lints what it touches and
commits only through `bin/lane`), never stop or reset default, never push, end with a report. No Seon edit hook runs
for agy, so its edits are proven only by its own lint and `bin/lane commit`.

## Ready specs

Each is a quoted heredoc (`<<'SPEC'`: backticks in double quotes are command substitution). Fill in the brackets.
Name the output file; read `summary` first and `grep` the transcript, never the whole log.

**Judge a proposal** (use 1):

```sh
bin/agy-agent run judge-<topic> <<'SPEC'
Judge a proposed change before it is measured. Do not edit any source file.
Evidence: <the measured numbers: profile, JFR samples, ms, allocation, call counts>.
Proposal: <the change, and the proof its author offers>.
Read <the owning code, the library source under reference-code/ or the jar> to check the premise.
Answer: (1) faster, slower or unchanged, and why, in terms of what the work is proportional to; (2) is the offered
proof sufficient, and if not, what end-to-end measurement is; (3) what the change costs (encoding, compatibility,
lookups); (4) commit, measure first, or drop. A negative answer is welcome. Write to <path> and end with a
five-line summary.
SPEC
```

**Find repeated work** (use 2): give the symptom with its numbers and the entry function. Ask (1) which function
repeats the work and why it repeats on an unchanged input, (2) the smallest fix using a mechanism Seon already has,
named by fully qualified symbol, (3) what the cache key must hold, (4) the end-to-end proof.

**Audit a document** (use 3): name the document. Ask it to verify every claim about what is installed now against
`src/`, `resources/` and `test/`, to skip target design, and to flag sentences whose tense is ambiguous. Each
discrepancy needs its section, a short quote, what the code shows (a fully qualified symbol and an excerpt) and a
severity (wrong, stale or ambiguous). The checked claims that hold go one line each. Then reproduce each finding.

**Look up** (Flash): `AGY_MODEL=gemini-3.8-flash-medium bin/agy-agent run lookup-<topic> <<'SPEC' … SPEC` with the
question, where to look (the web, `reference-code/<project>`, a namespace), and "answer with sources: URLs or fully
qualified symbols".

Neutral verbs throughout: verify, falsify, probe.

## Sources

`bin/agy-agent` (its usage header, `lane_rule`, `launch` and `render`); `agy --help`, `agy models` and
`agy mcp list` (agy 1.3.5); the codex-lanes skill for the shared lane conventions.
