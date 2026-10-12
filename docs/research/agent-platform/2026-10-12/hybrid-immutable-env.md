---
title: "Hybrid: the program context as an immutable value of a commit"
type: research
status: design round 3 hybrid of plan-immutable-env-opus.md (588d986c7) and plan-immutable-env-sol.md (c537b61f4); phases filed as tasks
basis: HEAD 588d986c7 read by symbol (`seon.sci.eval/base-ctx`, `acquire!`, `fork-for-turn`, `regenerate-agent-context!`, `base-bindings`, `transfer-evaluated-roots!`, `interpret-overlay!`, `program-changes`, `context-weight`; `seon.turn/resume-turn`; `seon.cluster.agent/acquire-context!`, `fork-attempt!`); SCI fork f455b3b4 (`sci.core/fork`, f8a2542d, 41194667); dig-immutable-env.md; hybrid-start-a-flow.md §H5; start-a-flow-benchmark.md; astra-overnight-review.md P1; one JVM probe on default (below)
---

# The program context as an immutable value of a commit

## 1. The design, and what each plan contributed

The owner's "environment at any commit" has two halves. The database half already is a value: a commit id names an
immutable Datahike value. The other half is the SCI context holding exactly the program that commit holds. Seon already
uses "environment" for the world map (`:seon.env/environment`), so this value is called the **program context**
(Opus's word; recorded in [vocabulary](../../../seon/architecture/vocabulary.md)). Both plans reach the same shape and
the hybrid takes it:

- `(program-context store db)` is a pure function of the commit's **program content**: one `seon.cache/lookup` of
  kind `::program-context` keyed by `[store loaded-commit (digest plan) projection-revisions dials]` (empty plan →
  `[store loaded-commit]`); a miss derives it exactly as a cold `base-ctx` does today (`derive-base-ctx`, the overlay
  plan interpreted over it) on a disposable builder, then **freezes** it: SCI's `:env` becomes the held map, the two
  kernel atoms become their values, and nothing holds custody, recorder, print session or result objects. Immutable
  in binding semantics (Sol): SCI Vars and closures stay runtime objects; only the program is reproducible, never an
  agent's private heap.
- An agent's context is **one `sci.core/fork`** of the value plus custody, a print session, fresh kernel atoms and
  result objects, and the agent **keeps the value it forked from** (Opus). At each eval request it computes its
  branch head's key: equal → its context as it is, no fork, no acquisition; moved → fork the new value and carry its
  private layer (own Vars, own bindings, atoms, result objects) across, compared against the **held previous value**
  instead of a per-turn walk of 8,435 Vars (`base-bindings`, 2.3 ms / 5.3 MB today). The new context is **returned**
  and installed by the Flow proc that owns the handle; no `reset!` of an old `:env` (Sol).
- A settlement that wrote program rows **does not transfer the writer's Var roots into a shared base**. The next value
  is **derived**: a builder forked from the current value interprets the settled rows' plan through the same
  `interpret-overlay!` a miss uses, is frozen and inserted under the settled commit's key (read-through: an equal
  program another branch already built wins). This is the hybrid's own addition (§2 row 3): one derivation function
  for miss and next value, so incremental and rebuilt values cannot diverge, and no shared value carries a fn bound
  to one agent's live context.
- Resume at any commit is lookup + fork. A successor forks its predecessor's **end commit** (owner decision) and gets
  the predecessor's program context by the same lookup, with a fresh private layer; it holds the SCI point its
  predecessor held whenever the predecessor's final recording changed no program row. The end commit is persisted on
  the attempt (`:seon.task.attempt/fork-point`, H8) and the successor's branch is created before the predecessor's is
  deleted, so Datahike retention owns the history (Sol; closes Astra P1's invented third behavior).

## 2. Point by point

| Point | Opus | Sol | Hybrid, and why |
|---|---|---|---|
| Value shape, one fork, keep the value, key by content, idle-boundary advance, no private layer for a successor | yes | yes | **Agree.** Both plans, independently; Sol's "binding semantics, not deep immutability" is the honest wording. |
| **E1, the SCI seam.** `sci.core/fork` `swap!`s the source's generation (f455b3b4 `core.cljc:345`), the one write that keeps the value from being a value | `sci.core/freeze` in the SCI fork; `fork` of a frozen context writes nothing | a temporary atom around the held map, no SCI change; "document the `:env` seam before depending on it" | **The SCI fork, as the type.** Probe (`result/e159d4b5702f4`, default JVM): `sci/fork` of a context whose `:env` is the plain map throws `ClassCastException`; Sol's temporary atom leaves the held map untouched. So freezing = `(update ctx :env deref)`: the value's `:env` is literal data, and `fork` learns to build the child atom from a map without a swap. Any evaluation against the value then fails at `deref`/`swap!`, loudly, instead of silently writing a shared value, and Seon code never reaches into SCI's `:env`. Six lines plus a test in the fork; Sol's temporary atom is the interim only if the bump must wait for a restart batch. |
| **An SCI fn is bound to its defining context** (f8a2542d, 41194667) | risk 1: a value built from agent A's roots runs under A's context on a context-less thread; "re-root the fn" | proof row: a previously analyzed closure sees its own root | **Verified** (same probe): a fn defined in fork A, called on a raw `Thread`, answers A's `x` (`:a`); under fork B's context it answers B's (`:b`). Both plans build the next value by **transfer** (Opus: `transfer-evaluated-roots!` into a fork of the value; Sol: "acquire the new program value"), which carries A-bound fns into a shared value. The hybrid **derives** the next value by interpreting the settled rows on a builder (§1), so a program fn on a context-less thread sees **its value's program**, exactly as a rebuilt value's fn does, never an agent's layer. No re-rooting, no guard. |
| Incremental settlement vs rebuild equivalence | E5 proof compares the next value with a cold rebuild; divergence fixed in the transfer | phase 5 table: before/after eviction and raw-store reopen | **Both proofs stay**, but equivalence is now structural: `interpret-overlay!` with `(current value, request plan)` versus `(loaded base, branch plan)`. One behavior change: the plan handed to the builder carries no `::evaluated` members (today they skip re-interpretation because the writer's fork holds them). Cost is proportional to the request's definitions and their lazily bound reverse closure, the same class as the transfer. |
| The agent's private layer diff | delete `base-bindings`; compare against the held value | keep the walk, replace only after measurement | **Opus, with Sol's proofs as the regressions.** The held value's `:namespaces` map *is* what `base-bindings` snapshots (roots at fork time), and a frozen value's roots never move (since SCI-1 a fork writes its own binding, never the root), so the walk is replaced by a reference, not by a cheaper walk; the diff over the agent's own layer (`own-bindings`, generation-stamped Vars) stays and is proportional to that layer. Sol's rows (atom, closure and print state survive two advances and a return to an earlier commit; published own Var, deletion, alias change) are the proof table. |
| The writer after its own settlement | re-forks at its next request (key moved) | same | **Agree; first version re-forks**, proportional to its layer (0.14 ms empty). Keeping the writer's context when `installation-covers-program-change?` holds is an optimization after measurement, not a second path. |
| Cache weighing | charge the delta from the value forked from (`context-weight` charges 12,288 B × 6,463 functions = 79 MB per entry, an overlay sharing the base's maps included) | charge conservatively, measure retained heap first; overcount hurts hit rate, not correctness | **Measure, then delta.** Caffeine's budget means retained bytes; a 79 MB charge per one-function branch evicts reach entries that cost 108 ms / 203 MB to rebuild (benchmark). The loaded value is charged its measured retained size; a child charges what it retains beyond its parent (its plan's interpreted bindings and rows). Known undercount: a child outliving an evicted parent; the loaded value is read on every acquisition, so it stays hot, and `seon.cache/status` shows the kind's bytes. One kind `::program-context` replaces `::base-context` and `::overlay-context`. |
| Key | content only; `loaded-commit` names the JVM's program | add loaded host/dependency identity | **Content only.** The loaded commit is the platform identity in-process (an adoption moves it; a new archive is a new JVM with an empty cache). Sol's caveat is a non-goal: executing an old commit under another platform is not claimed. |
| Lazy installs (`kernel/ensure-function!`) | measure how many the loaded copy leaves lazy | fork-local, never on the frozen source | **Fork-local; E3 measures the count** and decides whether the derivation installs them eagerly. |
| Acquisition refusals | recorded once per program and target (`record-refusals-once!`) | cached diagnostics are data; record in the entrant's custody | **Agree:** the value carries its refusals as data; the fork records them in its custody through the existing owner. |
| `my.program/ns-unmap!`, `remove-ns!` | evaluate in the fork; yield the next value like a settlement | — | **Opus:** they are program writes; the next commit derives. |
| What is deleted | `acquire!`, `acquired-database?`, `loaded-program-current?`, `copy-base-ctx`, `fork-cluster-ctx`, `branch-ctx`, `program-equal`, `base-bindings`, `::base-bindings`, `same-program-root?`, the two kinds, `:my.program/base-ctx`, `::base-ctx` on contexts | the branch copy on this path, reset-based regeneration, the two-fork expectation; "don't re-propose deleting what is gone" | **The union**, plus `transfer-evaluated-roots!` (its last caller is the settlement path E5 replaces) and `derive-base-ctx`'s two `swap!`s (build the snapshot map, then freeze). Nothing already deleted is listed again. |

## 3. Parts and costs

| Part | Key / home | Measured now | Floor and proportionality |
|---|---|---|---|
| commit → key | content revisions of `acquisition-attributes` (0.02 ms), plan digest (`::overlay-plan`), armed dials | `acquired-database?` 0.28 ms per turn | ~0.1 ms; proportional to the attribute count |
| loaded program context | `[store loaded-commit]` | cold 850–1,056 ms / 1.8 GB (H9); hit 1 ms (a copy) | one derivation per JVM program; hit 0.01 ms (lookup, no copy) |
| branch program context | full key | overlay derived per fresh fork (H3/H4 landed: shared by plan digest) | one derivation per distinct program, shared by every branch, commit and successor holding it |
| next value after a settlement | the settled commit's key | written into the live base in place (`acquire!`) | proportional to the request's rows |
| agent context | the proc's handle | fresh 3.1 ms / 5.5 MB; carried **8.0 ms / 10.1 MB every turn**, program unchanged | fork 0.55 µs / 2.6 KB + custody; 0 on an equal key |
| idle-boundary advance | — | the 8 ms above | proportional to the agent's own layer (0.14 ms empty) |
| successor | predecessor's end commit | full branch acquisition | lookup + fork |

## 4. Phases (about fifteen minutes each; red proof first; cost bounds through `seon.profile/measure`)

**E1. A fork of a frozen context writes nothing (SCI fork; platform slice).** `sci.core/freeze` returns the context with
`:env` as its held map; `sci.core/fork` of such a context builds the child atom from the map with a fresh generation and
swaps nothing. Red proof `sci.fork-bindings-test/a-fork-of-a-frozen-context-writes-nothing`: the held map is `identical?`
after the fork, the child defines and the map does not see it, a fork of the plain context still swaps its source;
fork under 1 µs / 3 KB. Files: `reference-code/sci/src/sci/core.cljc`, `reference-code/sci/test/sci/fork_bindings_test.clj`;
the pin bump commits with `--test seon.sci.eval-test`. Owner: Opus. Task `seon.sci.eval/a-fork-of-a-frozen-context-writes-nothing`.

**E2. The program context is a value.** `seon.sci.eval/program-context` derives on a builder, freezes, caches under one
kind; `base-ctx` becomes a fork of it for its remaining callers (loadable at every step). Red proof
`seon.sci.eval-test/no-agent-work-writes-a-program-context`: take the value for a fixture branch; an agent request
`defn`s, rebinds a program Var, `def`s an atom and `my.program/ns-unmap!`s; every map in the value is `identical?` to
before; two commits with equal program revisions answer the `identical?` value; warm lookup under 0.5 ms / 0.5 MB.
Files: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`. Owner: Opus. Task `seon.sci.eval/the-program-context-is-a-value`.

**E3. One fork per agent.** `fork-for-agent` (value in, context out) replaces `copy-base-ctx`, `fork-cluster-ctx`,
`branch-ctx` and `fork-for-turn`'s fresh path at `acquire-context!`, `seon.test`'s request world and `seon.cluster.boot`.
Red proof: the existing `seon.cluster.agent-test/a-fresh-agents-environment-costs-two-forks`, renamed
`…-costs-one-fork` and tightened to one `inherit-var-bindings!`, zero `acquire!`, environment leg under 1 ms / 1 MB
beyond the lookup (14.5 ms / 9.1 MB with two forks); records how many functions the loaded value leaves lazy. Files:
`src/seon/sci/eval.clj`, `src/seon/cluster/agent.clj` (held by p4-branch-writes: a patch for root), `src/seon/test.clj`,
`src/seon/cluster/boot.clj`, `test/seon/cluster/agent_test.clj`. Owner: Opus. Task
`seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` (rewritten in place).

**E4. The idle boundary forks only when the program moved.** `resume-turn` and `acquire-context!` compare keys; moved →
fork the new value and carry the layer against the held value; the new context is returned and installed by the proc;
`base-bindings` is deleted. Red proofs `seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing` (zero
`sci.core/fork`, acquisition leg under 0.5 ms; 8 ms / 10 MB today) and `seon.turn-test/a-private-atom-survives-another-agents-program-change`
(A `def`s an atom, B commits a `defn` on A's branch, A's next request sees B's definition and the same atom);
`seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks` gains Sol's rows (two
advances and a return to an earlier commit; a published own Var, a deletion, an alias change). Files: `src/seon/turn.clj`
(held by p4: patch), `src/seon/sci/eval.clj`, `src/seon/cluster/agent.clj` (patch), `test/seon/turn_test.clj` (patch),
`test/seon/sci/eval_test.clj`. Owner: Opus. Task `seon.turn/an-eval-request-on-an-unchanged-program-forks-nothing`.

**E5. A settlement derives the next value.** The settlement path builds the next value through `interpret-overlay!` on a
builder forked from the current value, freezes and inserts it; `acquire!` and `transfer-evaluated-roots!` go. Red proof
`seon.sci.eval-test/a-settled-program-context-equals-its-rebuild`: after a request defining a function, its test and a
caller, the next value and a cold `program-context` of the same commit (entry removed) resolve the same symbols to
declarations with equal digests and the caller answers the same value; the writer's next request runs no derivation
against the loaded base (`derive-overlay-plan` with the loaded commit as base: zero calls); **a transferred fn called from
a raw `Thread` with no context answers the value's binding, not the writer's private one**; next value under 20 ms / 10 MB
for a three-definition request. Files: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`. Owner: Opus. Task
`seon.sci.eval/a-settled-program-context-equals-its-rebuild`.

**E6. A successor reuses its predecessor's point.** `fork-attempt!` forks the predecessor's persisted end commit
(`:seon.task.attempt/fork-point`), creates the successor's branch before the predecessor's branch is deleted, and the
program context is that commit's lookup. Red proof `seon.cluster.agent-test/a-successor-starts-from-its-predecessors-program-context`:
the predecessor changes a function and ends, main advances differently, the successor executes the predecessor's
definition and reads its end facts, none of the predecessor's private Vars is present, zero `derive-base-ctx` /
`interpret-overlay!` calls, environment leg under 2 ms; repeated after emptying the kind on a disposable store. Files:
`src/seon/cluster/agent.clj` (patch), `src/seon/task.clj` (patch), `test/seon/cluster/agent_test.clj`. Owner: Opus,
after H7/H8 and the P4 lane. Task `seon.cluster.agent/a-successor-starts-from-its-predecessors-program-context`.

**E7. Weight is what an entry retains.** First a retained-size probe of the loaded value and of a one-function child
(committed under `script/`), then `context-weight` charges the loaded value its measured size and a child its delta.
Red proof `seon.sci.eval-test/a-program-context-weighs-its-own-declarations`: three branches with one-function changes add
under 3 × 1 MB of weighted bytes (3 × 79 MB today) and the probe's number is within 2× of the charge. Files:
`src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`, `script/seon/program_context_weight.clj`. Owner: Sonnet. Task
`seon.sci.eval/a-program-context-weighs-its-own-declarations`.

**Order.** E1 → E2 → E3 → E4 → E5; E7 after E2; E6 after E5, H7, H8 and the P4 lane releases `seon.cluster.agent`.
Every slice is REPL-first on the lane's branch (`bin/lane commit --branch-evidence`), except E1 and the parts of E3/E4
that touch the evaluator (`--test`). Root restarts default once per batch.

## 5. Owner questions (the recommended answer is used unless overruled)

1. **E1 in the SCI fork, or Sol's temporary atom in Seon?** Recommended the fork: the frozen `:env` as a plain map is
   the immutability as a type and fails loudly; the temporary atom is the interim only if the bump waits for a batch.
2. **Retained size evidence.** Recommended: `org.openjdk.jol/jol-core` in the test alias only, used by E7's script in the
   check JVM; a histogram diff is the fallback. Allocation counters cannot answer retention.
3. **The writer after its own settlement** re-forks (first version) rather than keeping its context when the settlement
   covers the change; optimize only after E4's numbers show the re-fork matters.
4. **A child outliving an evicted loaded value** is charged its delta only; recommended accept and show the kind's bytes in
   status rather than invent pinning.
5. **Successor private layer:** none, both plans and the hybrid; an explicit live transfer is the only way a value moves.

## 6. Findings on the way

- The default JVM eval reply for the probe above reported `:settle 102.5` ms of 129.9 ms for a JVM-mode form that wrote
  one transaction: the recording stage alone is over the 100 ms bound AGENTS.md sets. Not this lane's; recorded here as
  evidence for the owner of `seon.turn` recording.
- `test/seon/cluster/db_flow_test.clj` (held by p4-branch-writes, uncommitted) has an unbalanced form at line 690; the
  edit hook reports it on every Bash call. Foreign; not touched.
