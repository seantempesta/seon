---
type: research
status: draft
tags: [research, agent]
---

# Gym green baseline — does a memory scenario pass at full context?

TL;DR — **No clean both-axes (mechanical AND judge) green baseline exists yet
for the memory scenarios x1 / x3.** I found and fixed a real predicate bug (the
discovery leg required the fully-qualified `seon.db/` while every agent is taught
the `db/` alias — a guaranteed false-RED), which makes the MECHANICAL scorecard
green when the agent reads-store-first (it usually does). But the
answer-correctness JUDGE is the actual blocker: the DeepSeek agent **fabricates
the aggregate** across a turn boundary in 2 of 3 fresh-data runs (told the user
`$66` when the truth was `$101`; `$120` when it was `$106`). That is an
agent-capability gap, not a context or predicate problem. The lean config (drop
`:live-tile`, −629 tok) preserves the mechanical green, so the minimal-context
loop CAN protect the mechanical axis — but the judge axis is too flaky to be a
baseline. Live runs, not inference: 4 paid DeepSeek x1/x3 drives.

## What I ran (live, paid, DeepSeek)

Provider was the pod default DeepSeek (`SEON_AI_PROVIDER` unset → `:deepseek`;
`DEEPSEEK_API_KEY` present). Each drive is the full `bin/test-cljs` suite with
`SEON_GYM_PAID` set, against a fresh scratch `:memory` conn seeded with
`boot-seed!` + the scenario fixtures.

| # | scenario | config arm | mech `pass?` | discovery leg | judge `pass?` | judge note |
|---|----------|-----------|--------------|---------------|---------------|-----------|
| 1 | x1 | full (pre-fix) | RED | RED (old `seon\.db/` regex vs `(db/query …)`) | GREEN (100) | `$101`, Adobe @45 — behaviorally perfect |
| 2 | x1 | full (post-fix, A/B) | GREEN | GREEN | RED (0) | "agent sent no reply"; agent actually answered `$66` (wrong) |
| 3 | x1 | lean (post-fix, A/B) | RED | RED (B closed a todo before reading) | GREEN (100) | `$101`, Adobe @45 |
| 4 | x3 | full (post-fix) | GREEN | GREEN (`(db/query …)`) | RED (0) | agent said `$120`; truth is `$106` (fabricated) |

No single run was green on BOTH axes.

## The predicate bug I fixed (test infra, in scope)

`:b-discovery-reads-store-first` (both `x1` and `x3`) used the regex
`"seon\\.db/(query|pull|entity|store-inventory)"`. Run 1's B did exactly the
right thing — `(def subs (db/query '[:find ?name ?usd … ]))` — but the regex
anchors on the fully-qualified `seon.db/` prefix, while every agent home-ns
aliases `seon.db :as db` (`src/seon/agent.cljs:68`) and the `:namespaces` manual
teaches `db/query` / `db/entity` / `db/transact!`. So a correct discovery eval
that used the alias the context itself prescribes was scored RED.

Fix: `"\\bdb/(query|pull|entity|store-inventory)"` — `\bdb/` matches BOTH
`db/query` and `seon.db/query` (in `seon.db/query` the `.` is a word boundary
before `db`). Applied to:

- `test/seon/gym/scenarios/x1-subscriptions-total-and-max.edn`
- `test/seon/gym/scenarios/x3-expense-reuse-and-category-total.edn`

Post-fix, run 4 (x3) and run 2 (x1) both matched `(db/query …)` and the
discovery leg went GREEN. This was a guaranteed false-RED on the discovery axis
for the canonical move — a real measurement bug now removed.

## The actual blocker: agent fabricates aggregates (CAPABILITY GAP — flagged)

The mechanical legs (discovery, reply, terminate, no-fork) green reliably. The
JUDGE (answer correctness) does not, because the agent gets the number wrong:

- x1 full-arm B (run 2) split query (turn 1) and compute (turn 2). In turn 2 it
  wrote a `message/user` with literal `"$66/month"` and "iCloud+ costliest" —
  fabricated; the seeded truth is `$101` / Adobe @45. Verbatim from its
  `response.txt`: `;=> [5 66 ["iCloud+" 10 :storage]]` (a hand-written `;=>`
  comment, not real eval output).
- x3 B (run 4) likewise fabricated its turn-1 query result inline
  (`;=> [["2026-06-22" 12 "Cafe Luna"] …]` — Cafe Luna is actually $26, not $12)
  and told the user `$120`; the dining total is `$106`.

Contrast run 1 (x1 standalone), where B did query AND compute in the SAME turn,
read "the numbers right in front of me", and answered `$101` correctly. The
pattern: when the agent splits query from compute across a turn boundary it
hallucinates the `;=>` result instead of re-referencing the stashed
`result/<id>` value, and composes the reply from the hallucinated number.

This is an agent-capability gap (DeepSeek + the result-stash re-reference UX),
NOT a predicate or context defect. It is the real reason no memory scenario has a
trustworthy answer-correctness baseline. Surfacing it, not papering over it.

## Second anomaly: judge reply-extraction missed a real reply (FLAG)

Run 2 (x1 full-arm): the mechanical `:b-replied-to-the-user` passed AND B's
`response.txt` shows a `(message/user …)` that returned
`{:seon.agent.message/ok? true … :hops 1}` — a real B→user message landed. Yet
the judge reported "the agent sent NO reply to the user" (score 0). So
`agent-reply-text` (`test/seon/gym/driver.cljs`) failed to pick up a real reply
in this 2-agent run. In run 1 the same code found B's reply fine. This makes the
judge axis itself flaky in the 2-agent shape; worth a focused look (it was
specifically written to fetch-then-filter to dodge the datahike-cljs
double-identity-join mis-bind — the miss may be a `q-from` time-filter edge or
the same engine smell resurfacing). I did NOT fix it — diagnosing it needs a REPL
session on the live query engine and risks over-reaching this task.

Also note `:b-replied-to-the-user` is weak: it counts any pos-hops B→user message
including the bootstrap greeting, so mechanical `pass?` can green even when the
real answer never lands (as in run 2). A stricter predicate would anchor on a
reply AFTER the question time, the way the judge's extraction tries to.

## A/B: full vs lean (`SEON-GYM CONFIG-AB`)

Drove x1 under default (full) then under
`test/seon/gym/configs/lean-no-live-tile.edn` (drops `:live-tile`, keeps
`:namespaces` + `:repl`). I repointed the existing `config-ab-memory-paid` test
from x3 to x1 (test infra) so the canonical A/B drives the cleaner memory
scenario.

```
SEON-GYM CONFIG-AB {:default/pass? true,  :default/tokens 18275,
                    :lean/pass?    false, :lean/tokens    17646}
```

- Token delta = **629 tokens** = exactly the dropped `:live-tile` block
  (confirmed: lean turn-profiles render `[:soul :skills-catalog :skill/repl
  :namespaces :open-todos :inventory :transcript]` — no `:live-tile`; full
  renders it).
- This is NOT a clean green→green proof: the FULL arm's mechanical greened but
  its judge red'd (fabricated `$66` + the extraction miss), and the LEAN arm's
  judge GREENED (correct `$101`) while its mechanical red'd on discovery-ordering
  (that run's B closed a todo before its first store read — a genuine behavior
  miss the predicate correctly caught, not a regex artifact).

What the A/B DOES establish: dropping `:live-tile` costs nothing the agent used —
the lean arm still discovered, computed `$101` correctly, and terminated; the
−629 tok is a free trim. What it does NOT establish: a both-axes green the loop
can pin, because the verdicts are unstable run-to-run.

## Verdict for the loop

- **Mechanical axis** (discovery / reply / terminate / no-fork): protectable. It
  greens reliably post-fix and survives the lean config. A minimal-context loop
  can guard against regressing it.
- **Judge axis** (answer correctness): NOT a baseline yet. The agent fabricates
  aggregates across turn boundaries (2/3 fresh-data runs wrong). Fixing that is
  an agent/eval-UX problem (make split query→compute re-reference the real
  stashed result instead of inviting a hallucinated `;=>`), and likely also the
  `agent-reply-text` extraction miss, before x1/x3 can offer a trustworthy
  green to protect.

## Files touched (test infra only — no `src/` edits)

- `test/seon/gym/scenarios/x1-subscriptions-total-and-max.edn` — discovery regex `seon\.db/` → `\bdb/`.
- `test/seon/gym/scenarios/x3-expense-reuse-and-category-total.edn` — same fix.
- `test/seon/gym/paid_test.cljs` — `config-ab-memory-paid` repointed x3 → x1.

## Reproduce

```
DEEPSEEK_API_KEY set; SEON_AI_PROVIDER unset (→ deepseek)
bin/gym --paid=x1   # x1 full
bin/gym --paid=x3   # x3 full
bin/gym --paid=ab   # x1 full vs lean A/B (prints SEON-GYM CONFIG-AB)
```

Durable cards: `tmp/gym-paid-card-x1-*.edn`; per-turn prompts + the verbatim
agent replies (the fabrication evidence) under `logs/turns/<agent-id>/<turn>/response.txt`.
