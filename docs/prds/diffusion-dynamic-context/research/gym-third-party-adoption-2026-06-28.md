---
type: research
status: active
tags: [research, agent, dashboard]
---

# Gym third-party adoption — can a consumer drive the gym from `acme/`?

> Assessment + design for making the agent-gym (scenario → predicate → scorecard)
> cleanly drivable by a downstream consumer against THEIR config + THEIR scenarios
> + THEIR provider (incl. the diffusion provider), with ZERO `src/seon/` edits.
> Grounded in the live tree at `1929644c`. This is a DESIGN + concrete proposal;
> §5 says exactly what I changed (the doc only) and why I did not cold-compile a
> new build during the parallel GPU drive.

## TL;DR

- **The gym engine is already consumer-shaped.** `run-scenario!` takes a scenario
  MAP + a `:seon.gym/config` (`:profile`/`:path` → `SEON_PROFILE`/`SEON_CONFIG`) +
  `:allow-paid?` + an injectable `judge-fn`, and dispatches the provider through
  `seon.ai/provider` — so a diffusion provider is a *zero-gym-change* swap. The
  loadout seam a consumer needs (`config/acme.edn`) is honored end-to-end.
- **But the ENTRY POINT is test-tree-only.** Scenarios are invoked from
  `seon.gym.driver-test` / `seon.gym.paid-test` with **hardcoded** `test/seon/gym/
  scenarios/*.edn` paths, and `bin/gym` runs the WHOLE `:node-test` suite and greps
  `SEON-GYM SCORECARD` lines back out. There is no "run THIS scenario file"
  invocation, no scenario-DIR knob, and `bin/gym` deliberately **strips
  `SEON_EXTRA_SRC`** — exactly the var a consumer needs kept.
- **Framing correction (load-bearing):** the gym is HERMETIC — every run boots
  fresh agents on a scratch `:memory` conn (`run-scenario!`, `driver.cljs:1585`),
  the live store is untouchable by construction. So "point the gym at the acme pod
  (7980)" is a category error: you don't point it at a live *store*, you point it
  at the consumer's *config/loadout* (which `config/acme.edn` already encodes) +
  their scenarios + their provider. The "cluster" a consumer cares about is
  reproduced by `SEON_CONFIG=config/acme.edn` + `SEON_EXTRA_SRC=acme/`, not by the
  7980 store.
- **The clean adoption path is one additive seam:** a `:node-script` gym CLI build
  (`seon.gym.cli/-main`) + a `bin/acme gym <scenario.edn>` verb that keeps
  `SEON_EXTRA_SRC`/`SEON_CONFIG` and writes a scorecard EDN. ~3 new files, ZERO
  edits to existing gym code or `src/seon`. Concrete contents in §3.

## 1. Honest gap assessment (file:line)

### 1.1 What ALREADY works for a consumer (no change needed)

- **Engine is fully parameterized.** `run-scenario!`
  (`test/seon/gym/driver.cljs:1528`) is a public `^:async` fn taking
  `:seon.gym/scenario` + optional `:seon.gym/config`, `:seon.gym/allow-paid?`,
  `:seon.gym/judge-fn` (`driver.cljs:1547-1551`). `load-scenarios!`
  (`driver.cljs:539`) reads ANY path. So the engine itself imposes no
  test-only assumption.
- **Config/loadout seam is honored end-to-end.** `:seon.gym/config`
  (`driver.cljs:446-463`) steers `SEON_PROFILE`/`SEON_CONFIG` via
  `apply-run-config!` (`driver.cljs:1515`) BEFORE the seed + agent boot, so
  `boot-seed!` (skills/routes) and `create!` → `seed-default-ctx!` →
  `resolve-loadout` read the consumer's chosen loadout with zero duplicated
  resolution (`driver.cljs:452-457`). `config/acme.edn`'s `#profile`
  (`:default` full corpus / `:minimal` lean) drops straight in.
- **Provider dispatch is the same selection point as the live pod.**
  `paid-adapter` (`driver.cljs:1276`) reads `(ai/provider)` and `case`-dispatches
  `:anthropic` vs the openai-compat default (`driver.cljs:1284-1288`). A new
  `diffusiongemma` provider added to `seon.ai` (per
  `seon-diffusion-interface-design-2026-06-28.md` §2/§3a) is picked up here with
  **zero gym edits** — `SEON_AI_PROVIDER=diffusiongemma` is the whole experiment.
- **Per-cluster config already proven in acme.** `bin/acme` exports
  `SEON_CONFIG=config/acme.edn` (`bin/acme:68`) and `SEON_EXTRA_SRC=$SEON_ROOT/
  acme` (`bin/acme:55`) — the consumer's loadout + source overlay are live for the
  acme pod with zero src edit.

### 1.2 What BLOCKS a clean consumer-driven gym run

1. **Entry point lives under `test/`, invoked only as test cases.** The driver is
   `test/seon/gym/driver.cljs`; scenarios are driven by `deftest`s in
   `paid_test.cljs` (`run-paid!`, `paid_test.cljs:145`) and `driver_test.cljs`,
   each with a **hardcoded literal path** (e.g. `paid_test.cljs:225`,
   `:231`, `:237`). A consumer cannot say "run my scenario file" — they'd have to
   add a `deftest` inside the seon test tree (a `src/seon`-adjacent edit, and not
   a thing a downstream `acme/` project can do from its own classpath).
   - Nuance: `test/` IS on every CLJS build's classpath (`deps.edn:316`,
     `:cljs` alias `:extra-paths ["test" ...]`), so `seon.gym.driver` is
     *compilable* into a node/pod build. It is simply never *required* by a
     non-test entry today. That is what makes §3's seam cheap.
2. **`bin/gym` is whole-suite + greps stdout, and strips the consumer's source.**
   `bin/gym` (`bin/gym:42`) runs `bin/test-cljs` with `env -u SEON_EXTRA_SRC -u
   SEON_EXTRA_PRELOAD -u SEON_EXTRA_NPM` — deliberately, so seon's OWN CI scores
   the stock core. For a consumer this is exactly backwards: their scenarios'
   `:fixture-sources` and their indexed `acme.*` nses must be present. And it runs
   EVERY `-test$` ns (~160s), then `grep`s `SEON-GYM SCORECARD`
   (`bin/gym:53`) — no way to run ONE scenario and get ONE scorecard.
3. **No scenario-directory convention.** Scenario discovery is N literal paths in
   the test nses. There is no `scenarios/` dir knob a consumer can point at
   `acme/gym/scenarios/`.
4. **Results land as grepped log lines, not a targeted artifact.** A scorecard is
   a `println` (`print-scorecard!`, `driver.cljs:1831`) recovered from
   `tmp/gym-latest.log`. (The paid path *does* additionally `writeFileSync` a
   durable card — `paid_test.cljs:168` — but only for the hardcoded paid
   scenarios, keyed by an internal `:k`.) A consumer has no
   "my scorecard landed HERE" path.
5. **`SEON_EXTRA_SRC` source-indexing is a pod-boot path, not a gym-seed path.**
   The gym seeds the scratch store via `client/boot-seed!`
   (`seed-scenario-world!`, `driver.cljs:1463`) and sets fs roots to `src` + `docs`
   only (`driver.cljs:1604-1606`). The downstream `acme.*` `:seon.ns`/`:seon.fn`
   indexing that the live acme pod does (via `acme.pod`'s `(reset!
   seon.client/!extra-core-vars …)`, see `acme-harness.md`) is **not** wired into
   `boot-seed!`. So today a consumer scenario can exercise the consumer's
   *loadout* (config) and *provider*, but the agent's context would NOT carry the
   consumer's indexed `acme.*` program-graph rows. This is the one real
   `src/seon` coupling — see §6.

### 1.3 Verdict

A consumer can ALREADY get a scored run under THEIR loadout + provider by writing
a scenario EDN and calling `run-scenario!` from a REPL — the engine is ready. What
is missing is purely the **operator surface**: a callable, non-test entry that
takes a scenario PATH, keeps the consumer's `SEON_EXTRA_SRC`/`SEON_CONFIG`, and
drops a scorecard file. Plus one deeper gap (§6): the gym seed does not index the
consumer's source into agent context. Nothing about the engine forces a fork.

## 2. The adoption-friendly path (design)

Two layers, both additive.

### 2.1 Operator seam — a gym CLI + `bin/acme gym`

A dedicated `:node-script` build whose `:main` is a thin
`seon.gym.cli/-main` (the gym is hermetic, so it needs NO live pod / wire-server —
it stands up its own scratch `:memory` conn exactly as the test suite does). A
`bin/acme gym <scenario.edn>` verb invokes it with the acme env block INTACT
(`SEON_EXTRA_SRC=acme/`, `SEON_CONFIG=config/acme.edn`), so the run scores under
the consumer's real loadout and their source is on the classpath for
`:fixture-sources`.

Why a separate build, not "extend `bin/gym`": `bin/gym` is seon-CI's
stock-core scorer (strips extra-src on purpose). The consumer caller is the
*opposite* invariant (keep extra-src). Two callers, one engine — the clean split,
not a flag that means "sometimes strip, sometimes don't".

Why a `:node-script`, not the `:node-test` suite: a node-script `-main` runs ONE
scenario and exits with the scorecard, no `-test$` discovery, no 160s suite, no
stdout-grep. It reuses `run-scenario!` verbatim.

### 2.2 Scenario-dir convention

The consumer keeps scenario EDN under `acme/gym/scenarios/*.edn` (their own repo,
their own answer-keys — never under `test/seon/gym/`, which the gym deliberately
keeps OFF the agent's fs surface to avoid answer-key leakage,
`driver.cljs:1596-1602`). `bin/acme gym` takes a path (absolute or repo-relative);
a bare name resolves against `acme/gym/scenarios/`. No code knows the dir — it is
a `bin/acme` convenience, so it stays pure consumer-side.

### 2.3 Selecting the diffusion provider

Per `seon-diffusion-interface-design-2026-06-28.md` §3a shape 1, the consumer
sets `SEON_AI_PROVIDER=diffusiongemma` (+ `SEON_DG_BACKEND=vllm|control`) and runs
a `:tier :paid` scenario with `--paid`. The gym's `paid-adapter`
(`driver.cljs:1284`) routes to the diffusion adapter via `(ai/provider)` — the
identical line the live pod's `current-llm-fn` uses, so a gym diffusion run and a
live diffusion turn are byte-identical in provider selection. The capability-ladder
predicates (`:clamp-held` / `:infill-beats-ar` / `:eval-renoise-converges`, §3b of
that doc) ride the SAME CLI once they land in `eval-predicate` — the CLI never
needs to know about them.

## 3. Concrete proposal (copy-pasteable)

### 3.1 The command a consumer runs

```bash
# stub-tier (FREE — scripted LLM, no provider, no spend): smoke a scenario
bin/acme gym todo-resume.edn

# under the LEAN loadout instead of acme's full corpus (one env flag)
SEON_PROFILE=minimal bin/acme gym todo-resume.edn

# paid-tier against the consumer's provider (here: the diffusion control endpoint)
SEON_AI_PROVIDER=diffusiongemma SEON_DG_BACKEND=control \
  bin/acme gym --paid my-infill-experiment.edn

# absolute path works too; a bare name resolves under acme/gym/scenarios/
bin/acme gym /abs/path/to/scenario.edn
```

Output: one `SEON-GYM SCORECARD …` line on stdout AND a durable
`tmp/acme/gym-card-<scenario>-<run-id>.edn` (mirrors the paid-test durable-card
discipline, `paid_test.cljs:168`). Exit 0 iff a valid scorecard came back (pass/
fail is the DATA, not the exit code — honest reds are the deliverable,
`paid_test.cljs:11`).

### 3.2 A consumer scenario EDN (`acme/gym/scenarios/todo-resume.edn`)

Same schema as the stock scenarios (`:seon.gym/scenario`, `driver.cljs:305`) —
nothing consumer-special. Stub tier shown (free); flip `:tier :paid` +
`:llm :scripted-replay`→drop it for a real provider drive.

```clojure
{:seon.gym.scenario/id     :acme-todo-resume
 :seon.gym.scenario/doc    "Consumer smoke: agent plans multi-step work and
                            resumes after interruption."
 :seon.gym.scenario/tier   :stub
 :seon.gym.scenario/status :active
 :seon.gym.scenario/competency :planning
 :seon.gym.scenario/axes   [:models-work-directed :terminates]
 :seon.gym.scenario/turns
 [{:seon.gym.turn/message "Plan a 3-step migration and start step 1."
   :seon.gym.turn/llm-script
   ["(todo/add! {:my.todo/title \"step 1\"}) (reply! \"started step 1\")"]}]
 :seon.gym.scenario/predicates
 [{:seon.gym.predicate/id   :step1-tracked
   :seon.gym.predicate/kind :datalog
   :seon.gym.predicate/axis :models-work-directed
   :seon.gym.predicate/query
   [:find '?e :where ['?e :my.todo/title "step 1"]]
   :seon.gym.predicate/expect :non-empty}]}
```

For a diffusion paid run, the consumer adds `:tier :paid`, removes the
`:llm-script` (the real provider drives), and asserts with the diffusion
predicate kinds once they land (`:clamp-held`, etc.).

### 3.3 New file — `test/seon/gym/cli.cljs` (the node-script entry)

Lives under `test/` next to the driver (keeps ALL gym code in one tree; `test/` is
on the build classpath, `deps.edn:316`). Pure glue over the existing engine.

```clojure
(ns seon.gym.cli
  "Node-script entry: run ONE gym scenario file and emit its scorecard.
   The consumer-facing seam (bin/acme gym) — the engine is seon.gym.driver,
   unchanged. Hermetic: run-scenario! stands up its own scratch :memory conn,
   so this needs NO live pod / wire-server."
  (:require [clojure.string :as str]
            [seon.gym.driver :as gym]))

(defn ^:async -main [& args]
  (let [paid?     (some #(= "--paid" %) args)
        path      (first (remove #(str/starts-with? % "--") args))
        scenario  (-> (gym/load-scenarios! {:seon.gym/path path})
                      :seon.gym/scenarios first)
        resp      (await (gym/run-scenario!
                          (cond-> {:seon.gym/scenario scenario}
                            paid? (assoc :seon.gym/allow-paid? true))))]
    (if (false? (:seon.gym/ok? resp))
      (do (println "SEON-GYM REFUSED" (:seon.gym/error resp))
          (js/process.exit 1))
      (let [fs  (js/require "node:fs")
            dir (str (.cwd js/process) "/tmp/acme")
            f   (str dir "/gym-card-" (name (:seon.gym.scorecard/scenario resp))
                     "-" (:seon.gym.scorecard/run-id resp) ".edn")]
        (.mkdirSync fs dir #js{:recursive true})
        (.writeFileSync fs f (pr-str resp))
        (gym/print-scorecard! resp)
        (println "SEON-GYM CARD-FILE" f)
        (js/process.exit 0)))))
```

(`-main` is `^:async`; `:node-script` awaits the returned Promise before the
event loop drains — the same shape `seon.client/-main` relies on. The explicit
`process.exit` mirrors the suite's exit discipline.)

### 3.4 New shadow build — `:gym-cli` (add to `shadow-cljs.edn` `:builds`)

```clojure
;; Consumer-facing gym CLI: ONE scenario in, ONE scorecard out. Hermetic
;; (scratch :memory conn), so no pod. bin/acme gym runs it with the acme env
;; block intact (SEON_EXTRA_SRC kept — opposite of bin/gym's stock-core strip).
:gym-cli
{:target    :node-script
 :output-to "out/gym/cli.js"
 :main      seon.gym.cli/-main
 :devtools  {:enabled false}
 :compiler-options {:warnings-as-errors false
                    :externs ["externs/node_fs.js"]}}
```

### 3.5 New `bin/acme` verb (insert a `gym)` case before the final `exec`)

```bash
  gym)
    # Run ONE gym scenario under the ACME env (SEON_EXTRA_SRC/SEON_CONFIG kept,
    # the OPPOSITE of bin/gym's stock-core strip). Hermetic — no pod needed.
    shift
    cd "$SEON_ROOT"
    # bare name resolves under acme/gym/scenarios/; abs/rel path passes through.
    ARGS=(); for a in "$@"; do
      case "$a" in
        --*) ARGS+=("$a") ;;
        /*)  ARGS+=("$a") ;;
        *)   ARGS+=("acme/gym/scenarios/$a") ;;
      esac
    done
    clj -Sdeps "{:deps {seon.extra/src {:local/root \"$SEON_EXTRA_SRC\"}}}" \
        -M:cljs compile gym-cli
    node "$SEON_ROOT/out/gym/cli.js" "${ARGS[@]}"
    exit $?
    ;;
```

(`SEON_EXTRA_SRC`/`SEON_CONFIG`/`SEON_AI_PROVIDER` are already exported at the top
of `bin/acme` / the operator's shell — the verb just preserves them. The
`-Sdeps :local/root` mirrors `bin/acme build` so `acme.*` fixture-sources
resolve.)

## 4. 10-minute adoption story

1. Consumer drops `acme/gym/scenarios/foo.edn` (schema = `:seon.gym/scenario`).
2. `bin/acme gym foo.edn` → scorecard line + `tmp/acme/gym-card-foo-<id>.edn`.
3. Want lean context? `SEON_PROFILE=minimal bin/acme gym foo.edn`.
4. Want a real model? `SEON_AI_PROVIDER=… bin/acme gym --paid foo.edn`.
5. Diff scorecards across commits (`scenario × git-sha × run-id`) to quantify a
   context or prompt change. ZERO `src/seon` edits; ZERO test-tree edits.

## 5. What I changed + verification status

- **Committed: this doc only.** I did NOT land the §3 seam. Rationale (Slow Is
  Fast): the seam is a brand-new `:node-script` shadow build whose only honest
  proof is a cold compile (pulls the whole pod surface, ~60-120s) + a stub run.
  Doing that mid-session is non-trivial and the owner is driving the diffusion GPU
  worker in parallel; a half-verified build is worse than a precise design. The
  §3 contents are copy-pasteable so a follow-up agent lands + verifies it in one
  patch.
- **Existing gym tests: untouched, so green-by-construction.** The seam adds
  files (`test/seon/gym/cli.cljs`, a `:gym-cli` build key, a `bin/acme gym`
  case); it edits NO existing gym code and NO file the `:node-test` `-test$` build
  compiles differently. `cli.cljs` does not match `-test$`, so it is not even
  pulled into the test bundle unless required (it isn't). The existing
  `bin/gym` path is byte-identical.
- **Engine-readiness IS proven, today:** `paid_test.cljs:154` already calls
  `gym/run-scenario!` on a `load-scenarios!`-loaded arbitrary path with
  `:allow-paid?`, and `config-ab-memory-paid` (`paid_test.cljs:284`) already
  passes a `:seon.gym/config {:path …}`. The CLI is a strictly-thinner caller of
  the same two fns — no new engine capability is invented.

### Follow-up verification recipe (for the agent who lands §3)

```bash
clj -Sdeps '{:deps {seon.extra/src {:local/root "acme"}}}' -M:cljs compile gym-cli
node out/gym/cli.js acme/gym/scenarios/todo-resume.edn   # stub: free, offline
# expect: SEON-GYM SCORECARD … + SEON-GYM CARD-FILE tmp/acme/gym-card-…edn
```

This is a full live proof with no GPU, no RunPod, and no touch of the default
cluster (7890/7891) — the gym is hermetic. Run the 160s `bin/test-cljs` ONCE at
the end only if any existing file was touched (it should not be).

## 6. The one real src/seon coupling (the smell to fix later)

The gym seed (`seed-scenario-world!` → `client/boot-seed!`, `driver.cljs:1463`)
indexes the STOCK core's `:seon.ns`/`:seon.fn` rows but NOT the consumer's
`SEON_EXTRA_SRC` source. On the live acme pod, that indexing is done by
`acme.pod`'s `(reset! seon.client/!extra-core-vars …)` at boot
(`acme-harness.md` "the crux of the indexing path") — a path `boot-seed!` does not
run. Consequence: a consumer gym run scores the agent under the consumer's
*loadout* (config) and *provider*, but the agent's context does NOT carry the
consumer's indexed `acme.*` program-graph rows the way a live acme turn does. So a
scenario about "reuse the consumer's existing fn" is not yet faithfully scorable
in the gym.

This is a genuine gap, NOT something to duct-tape in the CLI. The right fix is to
make `boot-seed!` (or a seed hook it already exposes) honor the same
`!extra-core-vars` / `SEON_EXTRA_SRC` indexing the pod boot does, so the gym's
"world a pod boots into" includes the consumer surface — one mechanism, both
paths, no gym-local re-mirror (the gym already drifted three times hand-mirroring
boot, `driver.cljs:1428-1447`). Flagging for a focused follow-up; it is outside
the additive-seam scope and would touch `src/seon/client.cljs`, so it breaks the
zero-edit invariant by design — which is the finding, not a thing to force.

## 7. Diffusion-gym (oracle scoring) — VERIFIED acme adoption (2026-06-29)

§1–§6 above assess the AGENT gym (full agent turns through a scratch `:memory`
conn). The DIFFUSION gym is the sibling surface and the one a consumer reaches
FIRST: a SCENARIO (a task + canned/real model responses) + a PREDICATE set + a
SCORECARD, scored through the **co-located oracle** (`bin/oracle-server`
parse-raw + the `worker-oracle-eval` self-host bundle). This section is the
DELIVERED + LIVE-PROVEN zero-edit adoption path for that surface.

### 7.1 Oracle reach — the coupling map (why zero src/seon edits is possible)

The diffusion-gym scorers in scratchpad (`e1_kill_gate.py`, `skill_lift.py`,
`score_ab.py`) reach the oracle by SHELLING OUT, and the oracle is a pure,
cwd-independent function — so an acme-side caller invokes it identically:

| surface | reach | coupling | verdict |
|---|---|---|---|
| `bin/oracle-server` (parse-raw) | puts seon's `src/` on the bb classpath RELATIVE TO THE SCRIPT (`fs/parent *file*`), loads `seon.repl.internal` from source. Pure parse, no DB/pod/cluster. | reachable by absolute path from ANY cwd | **ZERO coupling** ✓ |
| `out/worker-oracle-eval/main.js` (eval) | resolves its bootstrap cache from `SEON_BOOTSTRAP` (we pass `$SEON_ROOT/out/bootstrap`); `--serve` mode batches JSON lines. | needs `SEON_BOOTSTRAP` env when run from a foreign cwd — an ENV knob, not a src edit. Build artifact of the seon checkout (gitignored). | reachable ✓ |
| scratchpad scorers | hardcode `REPO=/Users/sean/src/seon` + `cwd=REPO` + a baked-in `CELSIUS_TASK`. | NOT seon-src; scratchpad. The scenario/predicate is baked IN, so a consumer can't define their own without editing the scorer. | this is the gap §7.2 closes |
| live cluster (7890/7891) | — | NONE. The oracle never touches a store; the gym is offline/CPU. | — |

Load-bearing correction to the old `EVAL_ENABLED=False` note in
`e1_kill_gate.py`: the eval bundle is NOT broken. It reads RAW code on stdin in
one-shot mode and JSON lines in `--serve` mode — the scratchpad driver fed JSON
to one-shot, which mis-parses. Driven correctly (`--serve` + JSON lines, or
one-shot + raw code) it returns clean `{ok, error{kind}}` verdicts
(`undeclared-var` → `:compile`). It IS pod-decoupled (no `schema/register!`), so
the eval tier gates SELF-CONTAINED CLJS, not pod-coupled `register!` calls.

### 7.2 What was wired acme-side (the delivered seam, zero src/seon edits)

A consumer-owned, scenario-driven driver + a `bin/acme` verb — the scenario and
predicates are now DATA the consumer authors, not baked into a scorer:

- `acme/gym/diffusion_gym.bb` — a babashka driver. Reads a scenario EDN, batches
  every arm's responses through the oracle parse tier (one `bb oracle-server`
  spawn) and, under `--eval`, the eval tier (one `node … --serve` spawn), scores
  each predicate, aggregates per arm, and fires the EARNS/KILL/MARGINAL verdict.
  Predicate kinds are DATA: `:oracle-parse`, `:oracle-eval`, `:contains`,
  `:absent`, `:not-vacuous`. The oracle is reached purely by `$SEON_ROOT` path.
- `acme/gym/scenarios/*.edn` — acme-authored scenarios (task + predicates +
  canned arm responses + `:expect-verdict`). The canned texts are REAL Clojure
  strings the oracle genuinely parses/evals — so the proof is end-to-end on CPU.
- `bin/acme gym-diffusion <scenario> [--eval] [--assert]` — the operator verb;
  exports `SEON_ROOT`, resolves a bare name under `acme/gym/scenarios/`. Hermetic
  + offline (no pod, no wire-server, no GPU). bin/acme is the consumer harness
  wrapper (pure env composition); this verb is acme-side, not a src/seon edit.

### 7.3 The verified offline proof (GPU off, CPU only)

```bash
bin/acme gym-diffusion celsius-killgate.edn --assert         # parse tier  → EARNS
bin/acme gym-diffusion pure-mean.edn       --eval --assert   # eval  tier  → EARNS
bin/acme gym-diffusion celsius-tie.edn     --assert          # parse tier  → KILL
```

Observed (2026-06-29, all `--assert` PASS — the decision rule fires BOTH
directions, so the predicate genuinely discriminates and is not rigged):

- `celsius-killgate` — arm1(guided)=0.833 vs arm3(naked+oracle)=0.167,
  Δ=+0.667 ≥ 0.10 → **EARNS**. Parse-raw truly fails the unbalanced arm-1 #6,
  truly passes the faithful ones.
- `pure-mean` (`--eval`) — arm1=1.000 vs arm3=0.250, Δ=+0.750 → **EARNS**. The
  EVAL tier is the discriminator: arm3's `(avg-helper v)` is `undeclared-var`
  (`ok:false`) where parse alone passes.
- `celsius-tie` — arm1=0.333 == arm3=0.333, Δ=0.000 → **KILL**.

Each run drops a durable EDN card under `tmp/acme/gym-card-<scenario>-<run>.edn`
(`#:acme.gym.scorecard{…:verdict {…:decision :EARNS}}`). ZERO `src/seon` edits;
verified with `git status` (only `acme/gym/**` + `bin/acme` touched).

### 7.4 The one flagged seam gap (NOT forced — a finding)

Same as §6's agent-gym finding, restated for the oracle path: the eval tier
(`seon.worker-eval`) is pod-DECOUPLED BY DESIGN — it has no `schema/register!`,
no program-graph fns, no live DB. So a consumer scenario that wants to eval
POD-COUPLED code (anything calling `schema/register!`, `db/transact!`, an
`acme.*` fn) cannot be scored by the eval tier as-is; only self-contained CLJS
runs there. Making the eval tier optionally load the consumer's `SEON_EXTRA_SRC`
program graph would touch `src/seon/worker_eval.cljs` — a src/seon edit — so it
is flagged here, not forced. For the parse tier and structural/vacuity
predicates (the kill-gate's actual discrimination) there is NO such gap; those
are fully consumer-drivable today.

## Entry points

- Engine: `test/seon/gym/driver.cljs` (schemas `:116-477`, `run-scenario!`
  `:1528`, provider dispatch `:1276`, config seam `:446-463`).
- Callers today: `test/seon/gym/paid_test.cljs` (`run-paid!` `:145`),
  `test/seon/gym/driver_test.cljs`; `bin/gym`, `bin/test-cljs`.
- Consumer harness: `docs/seon/components/acme-harness.md`; `config/acme.edn`;
  `bin/acme`.
- Diffusion provider + gym predicates:
  `docs/prds/diffusion-dynamic-context/research/seon-diffusion-interface-design-2026-06-28.md`
  §2-§3.
