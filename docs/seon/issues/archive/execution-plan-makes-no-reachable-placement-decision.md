---
type: issue
status: superseded
severity: blocker
tags: [issue, runtime, architecture]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# The per-reply execution plan makes no reachable placement decision

Found by the 2026-07-25 placement grounding pass. Extends WTF item 3
(`docs/prds/sci-execution-runtime/research/wtf-review-2026-07-24.md:72-87`)
and ledger row R-6c
(`docs/prds/sci-execution-runtime/research/redesign-ledger-2026-07-25.md:176-190`)
with the reachability proof and two findings neither recorded.

## Problem

`plan-execution` + `execution-plan-disposition` run on every reply. In the
shipped single-tier run-holding process, every branch that would move work is
unreachable, every consistency check compares a value with itself, and the
only arms that can fire are a refusal (`:steering`), an emptiness test
(`:no-dispatch`) computable without any of it, and one misclassified
agent error (`:core-fault`).

## Evidence (all read at HEAD `b8c39fdbb`)

**Production passes exactly one tier.** `src/seon/agent/driver/host.clj:587-590`
builds `tier-inventories` as `{(tier of the one base inventory) inventory}`
from `::context/tier-inventory`, which `seon.host.context/build-base!`
(`src/seon/host/context.clj:1400-1421`) obtains from
`register-host-capabilities!`, which is hardcoded `:jvm`
(`src/seon/host/context.clj:1013`). The selection policy names
`:invoking-tier :jvm` and `:handoff-tier :bun`
(`driver/host.clj:608-609`), but `:bun` is never a key of the map.

Therefore in `plan.cljc:388-393` `eligible` starts as `#{:jvm}` and is only
intersected downward, so `selected-tier` (`plan.cljc:569-573`) is `:jvm` or
`nil`, and `nil` implies `eligible` is empty, which implies
`placement = :unplannable` (`plan.cljc:561`).

**Both `:release` arms are dead.** `driver.cljc:384-386` requires
`selected-tier` ≠ `:jvm`: impossible. `driver.cljc:388-390` requires
`(nil? selected-tier)` with non-empty `eligible-tiers`: impossible, and
already short-circuited by the `:unplannable` arm at `driver.cljc:376`.
The only coverage is a hand-built two-tier fixture no production caller can
produce (`test/seon/agent/driver_core_test.cljc:271-296`).

**`missing-leaves` and `missing-exports` are empty by construction.**
`driver.cljc:326-335` subtracts installed bindings from
`required-bindings`, but `plan.cljc:186-189, 205-212` already removed any
tier that does not serve every binding before the tier could reach
`eligible`. The remote-binding escape hatch cannot change this:
`seon.capability/installation-leaves` hardcodes `::remote? false`
(`src/seon/capability.cljc:46`) and is the only producer
(`src/seon/host/context.clj:648`), so `remote-bindings` is permanently `#{}`
on every shipped inventory. `artifact-exports` is likewise the same set as
`bindings` (`src/seon/capability.cljc:84-89`), so
`missing-exports` compares a set with itself.

**Two evidence fields are tautologies.**
`:seon.execution/observed-generation` is assigned from
`:seon.execution/planned-generation` — literally the same expression twice
(`driver.cljc:362-365`). `planned-basis` vs `observed-basis`
(`driver.cljc:356-361`) is also vacuous: `plan-execution` returns a
`:core-error` when they differ (`plan.cljc:370-379`) and
`parsed-reply-plan` returns early on that error (`driver/host.clj:611-612`),
so the disposition only ever sees equal bases.

**`cache-key` has no consumer.** Computed on every plan
(`plan.cljc:332-348, 591`). `rg ':seon.execution/cache-key' src/` matches only
its own registration and assignment; the sole reader is
`test/seon/program_plan_test.cljc:246-256`.

**The manifests have no consumer either.**
`rg ':seon.execution/schema-manifest|:seon.execution/capability-manifest' src/`
matches only `plan.cljc`, the disposition that re-checks them against the
projection they were derived from (`driver.cljc:323-340`),
`provision-plan-bindings!` (`driver/host.clj:462-474`), and the invocation
map that carries them nowhere (`driver/host.clj:259-262`).
`provision-plan-bindings!` duplicates what sci's own `:load-fn` already does
on first require (`src/seon/host/context.clj:613-633`).

**The planning projection duplicates a projection the process already holds.**
`plan/acquire-planning-projection` (`plan.cljc:618-671`) issues three
unbounded queries (`plan.cljc:114-142`) — every edge bundle, every
`:seon.schema/key` + provenance tx pull, every `:seon.fn/spec` + provenance tx
pull — then re-`read-string`s every form through
`schema/projection-from-rows` (`src/seon/schema.cljc:1369-1420`) and digests
the entire program graph (`src/seon/program/edge.cljc:494-501`). The host
already retains exactly that projection in `::context/projection-state`,
acquired by `acquire-committed-projection!`
(`src/seon/host/context.clj:1709-1756`), which pages through AEVT
specifically so that "the per-request result-weight bound remains independent
of total corpus size" (`context.clj:1713-1715`) and is maintained
incrementally by `publish-maintained-projection!` (`context.clj:1774-1792`).
The planning path is a second acquisition of the same fact that discards that
bound. `src/` contains 2,866 `register!` call sites, so this is a
few-thousand-row re-query and re-parse per agent reply.

**The one reachable `:core-fault` misclassifies an agent mistake.**
`schema-covered?` (`driver.cljc:337-340` → `plan.cljc:595-616`) is the only
disposition input not equal to itself by construction: `schema-keys` is the
closure of the bundle's read/written attributes (`plan.cljc:413-420, 534`),
and an agent reading an attribute with no registered schema puts a key in the
manifest that is absent from `:seon.schema.projection/forms`. That produces
`:seon.error/kind :core-bug` with the message "The selected execution tier is
missing a requirement from an exact plan" (`driver.cljc:398-404`) — a core
fault for an ordinary agent authoring error. Reachable by construction; not
yet observed live.

**What the derivation actually did in production.** The `:steering` arm is
the arm that fires, and it has refused valid replies three times in recorded
live drives — see
`docs/seon/issues/jvm-claimant-rejects-visible-reply-without-exact-execution-plan.md`
(runs `dwvphar4i9yf`, `q5ddb6i4pp4z`, plus the `bright-candies-relax` NPE),
where a lone `(seon.agent.lifecycle/complete "PLANSCHEMA_ALIVE")` was
rejected as having "no exact execution plan on an inspected tier". Net
recorded effect of the placement derivation to date: one blocker-severity
issue with three live failures, and zero placement decisions.

## Owner

`src/seon/program/plan.cljc`, `src/seon/agent/driver.cljc`
(`execution-plan-disposition`), `src/seon/agent/driver/host.clj`
(`parsed-reply-plan`, `provision-plan-bindings!`).

## Acceptance

- No unbounded query on the reply path; the retained projection in
  `::context/projection-state` is the only schema projection in the process.
- `rg 'placement|eligible-tiers|selected-tier|cache-key' src/` returns nothing
  outside a deliberate successor design.
- A reply calling one binding hosted by another runtime executes, with that
  one call routed; it is not refused because the whole reply has no single
  eligible tier.
- An unresolvable symbol is reported by sci's own analysis error naming the
  symbol (`reference-code/sci/src/sci/impl/resolve.cljc:325-334`), as an
  agent-kind error value, per form — not as a pre-dispatch refusal of the
  batch.
- An attribute with no registered schema is an agent-kind error, never
  `:core-bug`.

## The consumers want a per-function fact, not a per-reply plan (2026-07-25)

Two open blockers independently name the aggregate-per-reply shape as the
thing blocking them, which is stronger evidence than the dead-branch count:

- `contract-predicate-transitive-purity-awaits-execution-planner.md` —
  `:seon.schema/pure-predicate-symbols` is a real compiler input
  (`src/seon/schema.cljc:600-625` rejects a direct predicate absent from it),
  and no producer in `src/` ever populates it. `compose-projection-data`
  (`schema.cljc:1214-1217`) only copies it from a divergence that copies it
  from the same projection, and `build-projection` defaults it `#{}`
  (`schema.cljc:872`). It is therefore ALWAYS empty in production, so every
  agent-registered contract referencing a non-core predicate fails closed.
  The note's Owner section says the execution walk must supply it and that a
  schema-local traversal is forbidden.
- `planner-lacks-per-root-purity-projection.md` — states the defect in the
  planner's own terms: "the current planner returns one aggregate placement".
  `seon.host.context/pure-block?` (`context.clj:1060`) is still a regex over
  source text (`#"\^:async|\(await |js/|#js|...|db/transact!|..."`) used at
  `:1216`, `:1217`, `:1256`, `:1331` to decide what loads into the sci base —
  a name-based classification, banned by the computed-classification rule.

Both consumers need one fact per `:seon.fn`, at any basis, as a lookup. The
substrate is already written at the tee: `record/tee-tx-data`
(`src/seon/host/record.clj:414-483`) already calls `edge/analyze-function` +
`edge/transition-tx` per successful `defn`, storing
`:seon.program.edge/calls`, `/read-attributes`, `/written-attributes`,
`/uncertainties`, `/generation` and per-terminal `/effect` +
`/required-bindings` (`src/seon/program/edge.cljc:520-567`). What is missing
is only the transitive rollup, and it is missing per function — not per reply.

`plan.cljc:195-198`'s two `str/starts-with?` prefixes also duplicate the one
owner of that rule (`seon.packages/js-wrapper-namespace?`,
`packages.cljc:113-119`) and ignore the stored ref that already answers it:
`stamp-corpus-rows` (`packages.cljc:121-126`) puts
`:seon.packages/package` (a `:seon.db/ref`, `packages.cljc:20`) on every
corpus row a package installs, and `row->host` (`packages.cljc:161-167`)
derives the host from that row's ecosystem attribute.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
