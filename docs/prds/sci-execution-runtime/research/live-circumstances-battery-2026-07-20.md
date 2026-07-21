---
type: research
status: active
tags: [research, agent, testing]
---

# Live-circumstances acceptance battery

The owner's goal (2026-07-20 night): no early victories — live agents
working well in a VARIETY of circumstances, then multi-agent, then
generate-code. This battery is the executable definition. Every drive
runs on the live cluster with the REAL configured provider (Muse; a 402
is recorded as an external blocker, never worked around silently), and
every claim is proven by database facts + rendered context, not logs.

## Leg 1 — single live agent, varied circumstances

| # | Circumstance | Proof |
|---|---|---|
| L1 | Fresh agent, simple task | **PASS 2026-07-21T02:59Z** — live Muse, run opened, 3 turns, reply "done 42" (messages onke1lqp2puv/qifokquqmxiu); closing found+fixed a real class: open message-request map silently defaulted missing `to` to the USER (`4c5e288a`) — the night's three L1 blockers were all real defects (settlement key, gateway variant drift, silent recipient default) |
| L2 | Toolkit db write (my.kb/remember) then recall NEXT turn | **PASS 2026-07-21T03:57Z** — live Muse. Part 1 (run 8321): note datom `:my.kb/claim "battery-L2 favorite=turquoise"` stored, reply "stored." (message 8388). Part 2 (run 8706, human-origin wake u6xmy0pfg5g4): recall turn queried `[?e :my.kb/claim ?c]`, eval returned `#{["battery-L2 favorite=turquoise"]}`, reply message 8800 = "turquoise". Cross-run database memory proven. A follow-up human nudge superseded the wandering run and run 9040 closed `:completed` result "turquoise" (agent called complete). Behavior note: after answering, Muse wanders (my.kb exploration) instead of completing until told — steering data, not a runtime defect |
| L3 | Capability call (my.fs read of an allowlisted file) | **PASS 2026-07-21T04:0xZ** — live Muse, run 9093, single pass: eval row holds the full `seon.agent.fs/read-file` envelope (`:seon.agent.fs/ok? true`, file-sha `1c196adf…`, content paged honestly with token estimate + result-symbol keep path); grants at the time `{allowed-roots ["/Users/sean/src/seon"] read-only? true locked? true}`; agent replied and called `(seon.agent.lifecycle/complete "# Seon Code Conventions")` — run closed `:completed` with exactly the file's first heading |
| L4 | Error steering: task designed to provoke one wrong call | **PASS 2026-07-21T04:2xZ** — live Muse, run 10101 closed `:completed` "seon": eval 1 = `/etc/hosts` read returning the directive denial envelope (`:seon.agent.fs/ok? false`, "path outside allowed-roots", `:denial :allowlist`), eval 2 = corrective `package.json` read, then reply + `complete` — self-correction in the SAME run. Closing this row required TWO real defects fixed/filed en route: (1) A.1 prose-token line recovery silently swallowed same-line corrective forms — attempt 1 (run 9267) closed `:no-forms` with three valid corrections dropped; fixed at token granularity (`6b38f156`, issue `prose-token-line-recovery-swallowed-same-line-forms.md`, suite 380/380); (2) the agent wedged permanently when its current-ns was toolkit `my.kb` after the cljc-packaging rebuild window — every run failed `setup-agent-ns!` before the LLM (runs 9390/9503); filed OPEN as `toolkit-current-ns-wedges-agent-after-cljc-packaging.md` (owner: execution/eval lane), unwedged live via a fresh `:seon.agent/namespace` assignment |
| L5 | Restart mid-conversation: bin/seon restart between two messages | pending/second message wakes the agent post-restart; context renders prior transcript (THE FINDING TO VERIFY: pre-restart pending messages must wake — earlier probe suggests they may not) |
| L6 | Concurrent agents: 3 agents driven simultaneously | **PASS 2026-07-21T04:4xZ** — live Muse; three human-origin messages sent in one burst; runs 10507/10512/10514 opened concurrently (all `:open` in one snapshot) and all closed `:completed` with correct results: bright-rules-turn "42", cool-lizards-rest "55", few-months-clap "10". Writer `read-spend` window shows each as a distinct `:seon.db/user` identity (`[:seon.agent/id "bright-rules-turn"]`, `…cool-lizards-rest`, `…few-months-clap`, repl process) |
| L7 | Canvas: agent shows a canvas with live data | **PASS 2026-07-21T05:1xZ** — live Muse, run 10914 closed `:completed` "canvas-up": agent queried the live claim count (`[:find (count ?e) . :where [?e :my.kb/claim _]]` → 1), evaluated `(my.canvas/show! {:my.canvas/content [:div [:h2 "Battery L7"] [:p (str "claims: " 1)]]})`; datom proof `:seon.render.canvas/content` on real-hats-wave; UI proof: the live `/agent/real-hats-wave/feed` SSE stream renders `<h1/h2>Battery L7</h1>` + the count inside the canvas surface section (server-side gzip client). Steering note: first run (10710) wandered (repeat queries, ns re-eval) without calling my.canvas until a hint message with the concrete `show!` shape — the `my.*` toolkit is not auto-aliased and Muse does not reach for it unaided |
| L8 | Budget bound: long task hits turn/form limit honestly | **PASS 2026-07-21T05:5xZ** — live Muse, fresh-dancers-behave with the documented agent-level `:seon.agent/default-turn-limit 6` override. Run 11394 (turn-limit datom 6) evaluated exactly `(identity 1)`…`(identity 6)` one form per turn and closed `:seon.agent.run/closed-reason :turn-limit` at the bound. No wedge: after retracting the override, probe run 11659 opened with limit back at 300 and closed `:completed` "6". Attempt 1 data point: given an INFINITE enumeration task, Muse refused after 3 steps and completed with a reasoned refusal (model judgment, bound untouched) — the finite-but-overrunning task exercised the real bound path. Adjacent bound evidence same night: `:no-forms` streak closes (runs 8321/9267) both re-woke cleanly on the next human message |

## Leg 2 — multi-agent

| # | Circumstance | Proof |
|---|---|---|
| M1 | Root delegates: message! root->task agent with a subtask | task agent's turn runs; reply hops back; root's subagents block renders it |
| M2 | Agent spawns a subagent via the toolkit and consumes its result | spawn depth respected; run results section shows the child outcome |
| M3 | Hop cap: a message chain hits the cap | **PASS 2026-07-21T03:56Z** (organic) — the root↔real-hats-wave drive chain accumulated hops 1→3→5; wake trigger REFUSED message e8yov6xye7jv (root→real-hats-wave, hops 5/4) and z0dxqec1d8a2 (real-hats-wave→root, hops 4/4): no run opened for either. The hop-exhausted dead-letter renders in the live compiled-child context ("REFUSED at hops 5/4 (recipient never ran)") — proof required fixing a real defect first: the warnings block was entirely dead on grown databases (scalar results budget, fixed cd3c2d6e, issue note `warnings-instant-scalar-results-budget.md`). A human message (hops 0) demonstrably reset the chain and re-woke the agent |
| M4 | Two agents share database state (one writes, other reads next turn) | cross-agent visibility via ordinary queries |

## Leg 3 — generate-code

The generate-code lane's live graduation (its roadmap's checkpoint) IS
this leg: caller agent -> generate-code! -> two-namespace goal ->
ordered evaluation -> delegated failure -> evidence-derived completion.

## Status 2026-07-20 night (honest, incomplete)

L1 PARTIAL: on the live default cluster the full chain proved through
the provider call — agent minted (lovely-flowers-knock), message
accepted, run opened, turn started, LLM attempt in flight against the
configured model (deepseek-v4-pro — NOTE: the reset database's config
default contradicts the owner's Muse ruling; fold the provider
selection into the next config apply). COMPLETION UNPROVEN: the pod
was repeatedly torn down mid-drive by concurrent lanes rebuilding
(settlement fix, U5 graduation, gencode cluster) — the battery cannot
run to completion on a contested cluster. Acme fallback blocked on its
writer during the same churn.

FINDING (earlier, to re-verify as L5): messages sent pre-restart did
not wake the agent post-restart on one boot; may have been the
settlement bug — the battery's L5 decides.

CONTINUATION (mechanical): wait for a quiet tree (no uncommitted lane
edits), bin/seon up, then L1→L8, M1→M4 in order; one circumstance at a
time; provider = Muse per the owner ruling (transact :seon.ai config or
config apply first); file every failure. Leg 3 = the generate-code
lane's live graduation (its first commit landed: ranked augmentation +
public generate-code! at 68d19cca).

## Standing rules

Real provider or recorded-as-blocked; one circumstance at a time; facts
over logs; every failure found becomes an issue note (fix if simple,
ledger if not); the battery re-runs green top to bottom before the goal
is called met.
