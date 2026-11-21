# Principles: What Seon Is For, and How to Tell When It Is Going Wrong

Every agent reads this page whole before any work: external agent sessions directly, Seon agents by reference in their context. Each principle is one sentence, why, what it rules out, and what enforces it. A breach is reported with its evidence and becomes a task to make it impossible. This page and [AGENTS.md](../AGENTS.md) are edited in place as the system evolves. The owner's rulings are in [decisions](decisions.md).

## 1. The measure is turns from a rough task to merged code

**A user's request, however rough, becomes a task, an agent works it, and the only finish is its change merged into `:main`.**

*Why.* Seon is an assembly line for software. Every worker agent has a `:seon.task/*` entity and a namespace scope; agents differ only by their task, their scope, and the data their pulls read, which is their context. A stuck or slow trajectory is audited into a class fix recorded in `:seon.fix/*` so every future task takes fewer turns.

*Rules out.* Counting landings, tests, or lines as progress. Rigging benchmark tasks with spoon-fed recipes or schemas instead of fixing general context and helpers ([bench](bench.md#no-rigging-discipline-owner-ruling-2026-11-20)). Scoring a task that never merged (or halted on proxy budget/error) as a pass. Carrying on over a broken system invariant instead of fixing its cause first.

*Enforced by:* `seon.bench.cluster/run-parallel-trial!` (`:require-merge? true` for `:task` trials), `seon.health.metrics/agent-learning-metrics`, `:seon.bench.run/*` live monitor tiles (`seon.render.tiles/bench-run-tile`), and `:seon.fix/*` class-fix records ([bench](bench.md)).

## 2. There is only the Seon agent

**Seon is built from the inside out, for an agent in a forked process with a real REPL on its own branch of the one store; every other caller gets a thin wrapper over what that agent has.**

*Why.* Seon's strengths are inside it: content digests, cheap branches, the program as data, recorded reads, warm COW forks, live code loading, and static Flow. Something built for an outside caller starts cold and uses none of them. External agent sessions use the exact same runtime through the `seon_*` MCP tools ([agent](agent.md#3-native-mcp-tools-post-mcp-bin-mcp-bin-seon)), so every pain fixed for a session deepens what every Seon agent uses.

*Rules out.* A design that begins at a shell script, external watchdog, or file parser beside Seon. Booting a separate system to do what a fork of the standing one does. Gating what an agent may evaluate or cutting its working context. Keeping external polyglot project code inside Datahike (external repos use `seon.fork.container` shell/file tools).

*Enforced by:* `bin/mcp` and `bin/seon` zero-logic relays; all tools implemented inside `src/seon/` (`seon.fork.helpers`, `seon.fork.calls`, `seon.fork.container`).

## 3. Work as a person at a REPL

**Try something small on real data, look at the result and the reasoning behind it, keep what works as a baseline, and improve from there toward the task's plan.**

*Why.* A form evaluated on real data in `seon_repl` (`result/e<n>`) is the cheapest evidence there is, and most ideas are discarded, so the 90% win for 10% of the effort comes first. Exploring needs nobody's yes; changes to shared schemas, runtime plumbing, or forks get a clear plan citing prior work and measured cost.

*Rules out.* Theorizing about agent failures before reading the actual eval trace. Building on a library without reading its source (`reference-code/`). A proof never seen red before turning green.

*Enforced by:* `seon.code/eval-forms` live `result/e<n>` handles with tick/byte telemetry; `seon.health/report` and `seon.test` reach verification.

## 4. One mechanism per job

**Each job has one mechanism, each shape one named Malli schema, and each fact one home; a better one replaces the old and deletes it in the same merge.**

*Why.* With two mechanisms, every reader learns both and a fix reaches only one of them. Look in order: what Seon already has (`user/search`, `user/grep-src`), what a pinned library provides (`reference-code/`), and only then something new. Co-located multi-segment Malli schemas are the single vocabulary: contracts connect functions to their producers, consumers, and generative proofs, and a bug is always fixed as a class.

*Rules out.* A second mechanism, a special case, a duplicate schema, or a replacement landed beside what it replaces. Storing a fact, decision, or fix only in transient chat or scratch notes instead of its owning doc, `:seon.task/*`, or `:seon.fix/*` record. Deleting useful features without owner sign-off.

*Enforced by:* `seon.health` `:unschemed-attribute`, `:invalid-contract`, `:retyped-attribute`, `:schema-breaks-stored-data`, and `:unused-require` ([health](health.md)).

## 5. Boring by construction

**Almost everything is immutable data, safe to share, cache, and pass anywhere; the few places that meet a changing world each have one owner, built so the failure cannot happen, and everything after them stays plain.**

- **Effects & state:** One static Flow graph (`seon.main`, [architecture](architecture.md#2-static-flow-graph-cow-fork-parent-seon-main-seon-fork-seon-agents)); blocking I/O and external boundaries are explicitly marked `^:seon.flow/edge` and answer errors as data.
- **Writes & merges:** Every Datahike transaction passes `seon.store/validate-tx-data!` against live Malli schemas; `:transactor` is the sole serialized writer of `:main`; merges run `seon.merge/three-way` and write back to local Git atomically.
- **Code edits:** Top-level forms edit structurally via `rewrite-clj` (`seon.code.cst`, `seon.code.edit`), validate Malli schemas upfront, and topologically order intra-namespace forms automatically.
- **Cold boot & switch-over:** `seon.main/sync-cold-boot-forms!` overlays post-AOT `src/` and `test/` forms in dependency order; `:boot-affected?` merges trigger automatic `bin/build seon` -> `--self-test` -> drain -> `--take-over`.
- **Bounds:** Evals run under `jolt.host/run-interruptible` with explicit tick and 256 MB byte budgets (`resources/seon/config/default.edn`).

*Why.* A check belongs inside the one owner where every path meets it, not duplicated as defensive guards across callers.

*Rules out.* Caller guards, retries, or silent fallbacks that hide broken invariants. Unmarked blocking calls inside Flow procs. Unbounded loops, ad-hoc atoms outside `^:seon.flow/edge`, or suppressing a health rule to pass a merge.

*Enforced by:* `seon.health/report` (`:blocking-in-flow-proc`, `:state-outside-flow`, `:used-before-defined`, `:minified-form`, `:dangling-declare`, `:proc-fault`), `seon.store/validate-tx-data!`, `seon.code.edit/validate-defn-malli-schema`, and `seon.test` anti-test-deletion gate ([health](health.md)).

## 6. Nothing changed, nothing runs; a change costs what it touched

**Every result is kept with what it read, so it is reused while that is the same and redone only where it moved.**

*Why.* Correctness and cost together. A verdict or UI unit that depends only on its recorded `datahike.reads` or Merkle reach digest is deterministic, fast, and safely cached. A change of size `d` costs `O(d)`, never `O(N)`.

*Rules out.* Recomputing per call, per fork, or per process. Scanning whole tables when an indexed `:avet`/`:aevt` seek exists (`:nested-db-scan`). Cache layers that drop `datahike.reads` tracking (`seon.store/lru-get!` replays reads transparently). Oversized monolith namespaces (`> 8,000` tokens) or bloated evals.

*Enforced by:* `seon.code.reach` Merkle-digest green test cache, `seon.render/step-walk` `datahike.reads` incremental invalidation (`seon.store/lru-get!`), `:nested-db-scan`, `:oversized-namespace`, `:large-function`, `:cost-regression`, `:slow-render`, `:slow-wire-build`, and `:cost/ticks`/`:cost/bytes` budgets ([health](health.md), [render](render.md)).

## 7. Say what you know, and say it when it is wrong

**Every claim is marked measured (`[M]`) or hypothesis (`[H]`), and anything these principles rule out is reported at once, with its evidence, to whoever can fix it.**

*Why.* A wrong claim or false-green benchmark passed forward wastes turns for every reader after you. A measured claim cites its tool output, eval handle, tx/commit, or file:line; a hypothesis names the check that would settle it. State decisions in standard Clojure, Datahike, Flow, Jolt, and Git terms.

*Rules out.* Reporting unverified numbers, stale notes, or false-green benchmark runs (such as proxy-halted idle workers or integrity-violating container runs). Working around a broken principle silently.

*Enforced by:* `seon.bench.cluster/run-parallel-trial!` merge enforcement, chronological `eval_trace` audits in `bench/harbor/seon_agent.py`, and `:seon.fix/*` before/after evidence records.
