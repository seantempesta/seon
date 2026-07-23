---
type: prd
status: active
tags: [prd, agent, runtime, architecture]
---

# Unified plan — the all-JVM CLJC/sci runtime (2026-07-23)

One plan from here to graduation. Synthesizes the accepted designs
(capability seam · claim/lease loop · LLM/HTTP containment) and the
four convergence research reports (JVM concurrency+fuel · web/SSE ·
writer throughput · render/ctx portability), under rulings 9–26.
The anchor (`program-synthesis-2026-07-21.md`) remains the state
ledger; THIS file is the implementation queue authority. Every unit
below is sol-dispatchable; specs derive from its row plus the cited
research sections. Breaking the Bun side during the port is
authorized (owner); each unit names what it breaks and what proves
it done.

## End-state process topology (Ruling 26)

Per cluster, supervised by the one operator:

1. **Writer JVM** — transactions + committed-tx feed ONLY.
   **Agent-authored code NEVER executes here** (owner ruling,
   absolute). No web serving in the end state. Escape ladder for
   scale lives here (U3) but the process's job never grows.
2. **Core JVM app** — everything else that is core: the web/SSE
   tier (http-kit + vendored datastar-clojure, vthread per
   connection), claimant drivers (one vthread per claimed run,
   plain-sync steps), sci evals behind the guarded door on the
   bounded platform eval-pool (the CPU bulkhead), LLM I/O, renders.
   Reads via its replica/db.host session; writes over the wire.
   Kill-anytime: claims + receipts make death invisible (U12).
   Initially ONE such process; splitting web from claimants is a
   later scale knob, not architecture (flagged owner decision D3).
3. **Bun leaf host** — DISPOSABLE js-package runtime: serves
   seon.packages.js.* + the diffusion/typeahead worker over the
   db-pattern wire. No durable state; kill/restart anytime;
   in-flight calls die as flat error values and claimants retry per
   effect class. (The current pod keeps running until U9 retires
   it; interim breakage authorized.)
4. **Browser** — static assets only (verified: no browser cljs
   exists; morphed HTML + vendored datastar.js).

## Units

### U1 — Guarded eval door (S/M) — READY, no dependencies

Fuel counter inside the existing `:interrupt-fn` safepoint closure
(zero sci fork changes; ~1–3 ns/site), one portable `.cljc`
guarded-eval entry: deadline where threads exist + fuel everywhere +
output caps + uniform steering error. Budgets are config facts;
calibration = measured steps/ms per tier (counting-only guard
first). Every sci invocation (agent eval, authored render, plan fn)
passes through this one door. Grounding:
`research/jvm-concurrency-research-2026-07-23.md` §guarded-door.
Falsifier: a hostile `(loop [] (recur))` halts by fuel on a thread
AND on a single-threaded host, with the steering value; overhead
within the measured envelope on the full suite.

### U2 — Claim-native driver (L) — READY after U1; THE SPINE

Ruling 24 break-and-replace: the portable `.cljc` driver (claim →
render → LLM → parse → eval-dispatch → write-back → advance) built
once, claim/epoch/lease/phase-cursor native (loop design §1–3 +
rulings 22/23: self-derived claimant identity, reacquire
nil→me + e→inc(e), retraction on close/pause). One vthread per
claimed run (`Thread/ofVirtual`), evals submitted to the bounded
platform eval-pool through U1's door; `Future.get` parks the
vthread. The legacy Bun loop-driving path is DELETED in the same
change — no fence retrofit, no dual-driver compat. Runs in the Core
JVM app (topology above); the pod stops driving turns (BREAKS Bun
agent-driving until U5/U6 restore surfaces — authorized).
Falsifiers (all from the accepted designs): two-driver race,
pause/resume/reacquire, the five kill points, and **the U12 drill:
kill the claimant mid-turn on the demo scenario, restart, the run
completes with zero lost/doubled effects**.
Grounding: `research/loop-cljc-sci-design-2026-07-23.md` (whole),
concurrency recipe §adoption. Gotchas pre-ruled: interrupt-during-
UDS costs a pool member (accepted); re-budget heap (-Xmx is
writer-sized); raise pool-wait-timeout for waiter counts.

### U3 — Writer admission fix (M) — READY, parallel with U2

Remove the artificial ceiling: admit >1 in-flight `:mutation` per
database so Datahike's commit batching engages (est. 5–20×).
Requires the in-flight request-id cache (recovery must distinguish
outstanding mutations; today recover-current sees only committed
receipts) + the allocation-tx carve-out. Writer-owned files only;
agent code untouched. Grounding:
`research/writer-throughput-research-2026-07-23.md` §5.1 + probe
plan. Falsifier: probe A′ shows batching engaged (queue-depth
scaling); recovery regression proves crash with N in flight
resolves every receipt correctly. Probes A/B/C run as acceptance.

### U4 — Render purity + R0/R1/R2 (M) — READY, parallel

Fold: the 11 ambient-db fallback doors → `:seon.db/db` required
block input + loud failure (I8 precedent); host-timezone impurity;
I5 file fingerprints + SOUL env; vestigial dial deletion; the
my.canvas `:clj` nonexistent-fn defect; R0 in-pod driver move; R1
pure renames (my.ui 7 census rows); R2 date shim. Grounding:
`research/render-ctx-portability-research-2026-07-23.md` inventory
and ledger. Falsifier: same db value ⇒ byte-identical context render
across process restart (the stage-5 gate's core assertion, minus
the deliberately-ephemeral tail).

### U5 — JVM web/SSE tier (M then L) — READY, parallel

First slice: `/data` + `/data/feed` served from the **Core JVM
app** (NOT the writer — ruling 26; the db.host session provides the
replica read + feed subscription), http-kit as-channel + vendored
datastar-clojure adapter, vthread per connection, seon.reactive
registry kept (single-threaded invariants made explicit — the named
MEDIUM risk). Then the remaining ~7.8k near-verbatim move + the
~1.5k platform-seam rewrite; ~300 LOC Bun↔Ring translation deletes.
Never resurrect: Integrant, sse-flow, the old renderer. Grounding:
`research/jvm-web-sse-research-2026-07-23.md`. Falsifier (slice):
transact → second morph via server-side SSE client, gzip on.

### U6 — LLM I/O port (M) — HELD for the owner HTTP discussion

L1 attempt receipts (open/terminal, deletes the !attempts atom) +
the portable adapter core (builders/interpreters/retry decisions —
already ~80% stateless) ride now-ish; the JVM `java.net.http` leaf
shape is designed but its implementation timing is the owner
discussion (ruling 20c amended). Until it lands, claimants call LLM
through the pod service. Grounding:
`research/llm-http-io-design-2026-07-23.md`.

### U7 — R3/R4 ctx port (M then L ~6.4k) — after U1 (door) + U5

R3 render core + canvas (11 census rows; canvas renders behind the
door); R4 the full ctx port with the one structural eval re-seam
(`lookup-value` → static trusted table vs the guarded door via
`agent-authored-sym?`). Acquisition is already protocol-member
data; one portable executor makes blocks acquire→execute→format.

### U8 — Rolling portfolio (S–M each; fill idle slots always)

JVM leaves fs/shell/web/blob + host bindings (~28 census rows) ·
my.* host bindings (plan/kb/skills) · the 5 straggler ports ·
triage M items #8 (db result/error-shape), #6 (host session
errors), #10 (datastar stale render) · compat-residue migrations
(context.clj:202 callers → seon.db.host) · seon.packages.jvm.*
add-lib research → unit · NS-1b resume · NS-4/5 · normalize-5 ·
seam observability (receipt durations — also unlocks honest seam
perf numbers) · 11 triage probes.

### U9 — The great deletion (L) — after U2 + U5 + U7

W5: per-agent children, eval.cljs self-host, child bands in
execution*, child Shadow/operator plumbing, ~5.7k LOC of
child/self-host tests — per the deletion-audit inventory (its
consumers re-pointed by U2/U5/U7). Bun pod becomes the leaf host
(topology #3). Census cutover assertion flips at zero.

### U10 — Graduation drills (rolling, final gate)

U12 kill-anything-anytime (claimant, writer, leaf host — at the
audit's five kill points, two-claimant race variant) · the
100-agent U10 drill · writer probes at target load · q18 OOME ·
byte-identity render gate. GRADUATION = all green on the demo
scenario with the census at zero.

## Sequencing

```text
U1 ──► U2 ──────────────► U9 ──► U10
  \        (spine)        ▲
   parallel: U3, U4, U5 ──┤
   U6 (after owner talk) ─┤
   U7 (after U1+U5) ──────┘
   U8 fills every idle slot throughout

```

## Open owner decisions

- **D1 (beat cadence):** 30s idle beats are fine at 1k runs; 3s is
  not (pre-U3). Default: 30s until U3 lands, then revisit.
- **D2 (cross-run beat batching):** blocked by CAS-abort-whole-tx
  semantics; only worth deciding if U3's 5–20× proves insufficient.
- **D3 (web/claimant process split):** one Core JVM app now; split
  later purely as a scale knob. Default: one.
- **D4 (HTTP leaf timing):** the U6 morning discussion.
- **D5 (fuel budgets):** config-fact defaults after calibration
  measurements land with U1.

## Graduation gate (unchanged in spirit, now concrete)

Kill anything at any time — claimant, writer, leaf host — and the
demo scenario completes with zero lost or doubled effects; census
at zero with the cutover assertion flipped; 100 concurrent agents
on one cluster within the measured writer envelope; every sci
invocation fuel/deadline-bounded through one door; agent-authored
code provably absent from the writer process.
