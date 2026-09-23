---
type: landing
status: digest parity landed; backstop regression committed, its final test run blocked by the store failure
lane: digest-parity (README §3, B1 §2g)
---

# Lane digest-parity (2026-09-23)

Plan contract: `:seon.program/definition-digest`, README §3, B1 §2g ("equal source,
identity, resolver context and effective metadata produce equal digests across
file/agent construction"). Defect: `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
red on the digest for the namespace, function and test rows (stored rows otherwise equal).

## Diagnosis (REPL, default pid 90963, throwaway ns `tmp.digest-parity`)

Evaluated-side history was captured by running the test's agent half in a disposable
probe (`tmp/digest-parity/probe.clj`) under `(binding [seon.test/*member* child] ...)`
with `child` from `seon.cluster.agent/acquire-context!` (isolated branch, released after).
The ns entity's datoms: plan-time `31bb33fa…` (name + source), then the record
transaction retracts it and asserts `7032f6ef…`; `sample.s1/right` gets `278a4003…`.
Indexed: ns `11eaac21…`, right `922df204…`.

Three wrong inputs, all on the agent side (§2g: the namespace's own facts are the
resolver context; the file side is right):

1. `seon.fn/source-rows` → `var-row` took the resolver context from the analysis
   batch's synthesized `ns` form, widened by the interpreter refers and
   `seon.bootstrap`: `{:requires #{seon.bootstrap clojure.test seon.schema}, 5 refers}`
   vs the facts `{:requires #{clojure.test seon.schema}, 2 refers}`. Probe:
   `(program/definition-digest right-row stored-ctx)` = `922df204…` (indexed),
   synthesized ctx = `c32e590d…`; `source-rows` itself returned `c32e590d…`.
2. `seon.fn/analyzed-form` (turn settlement, `turn.clj:4915` `analyze-forms`)
   RECOMPUTED every evaluated row's digest: with the namespace pulled from the
   database BEFORE this reply's `ns` form commits (no bindings), over the row after
   `with-contract-facts` (so `:seon.fn/arities` fingerprints included), and it gave
   namespace rows a resolver context the indexer never gives them. That is the
   `7032f6ef…` / `278a4003…` pair.
3. `:seon.fn/arities` was not excluded although it derives from `:seon.fn/spec` and
   `:seon.fn/arglists`, which are hashed; the digest therefore depended on WHEN it
   was computed.

## Fix (the producers, not the comparison)

- `src/seon/fn.clj` `source-rows`: digest each fn/test row with
  `(stored-namespace-context namespace-row)`, the caller's namespace facts.
- `src/seon/fn.clj` `analyzed-form`: no recompute; the row keeps its constructor's
  digest. Its `resolver-context` parameter and `analyze-forms`' binding pull are
  deleted (pull narrowed to `[:seon.ns/name]`).
- `src/seon/program.cljc`: `:seon.fn/arities` joins the excluded attributes.

After adoption, `source-rows` for the probe row returns `922df204…` (68 ms warm).

## Proof

| operation | wall ms | result / justification |
|---|---:|---|
| test before fix (run 1) | 56,911 | 4 fails: 4 digest mismatches + duration 42,252 ms |
| init --changed fn.clj (1st edit), attempts 1-3 | 42,335 / 47,022 / 71,930 | 1-2 refused "The source head changed before publication." (other lanes publishing); 3 adopted `6ab35b84…`. Profile: concurrent `refresh-source!` x7, `full-source-refresh!` from other lanes; the save-gate lane owns adoption cost |
| test after source-rows fix only | 30,618 | still 278a/7032: the recompute in `analyzed-form` was the remaining input |
| init --changed fn.clj program.cljc | 27,359 | adopted `6ab35cd7…`; same concurrent-refresh profile |
| test, transient | 35,421 | evaluation refused "Host-bound declaration seon.cluster.reload-measure/-main must change through the loaded source files" during another lane's adoption; the loaded and head rows were equal when re-read (`1c71fbb0…` both) |
| **test, run b13a0c834733** | 33,046 | **pass 21, fail 1: every digest equality green**; the one red is duration 18,929 ms over the 5,000 ms bound |
| probe runs of the agent half | 20,606–35,322 | fixture `with-database` + `acquire-context!` + two turns; profile top: `seon.test-support/with-database`, `seon.cluster.agent/acquire-context!`, `seon.schema/call-with-projection`, `seon.db/with-declarations` |

The parity test's 18.9 s (bound 5 s) is over ten seconds: a defect outside these paths.
The profile names the canonical fixture and the turn machinery (`with-database`
~24–48 s inclusive across nested calls, `acquire-context!`, `call-with-projection`
x3,228, `with-declarations` x62,863 for two turns), the class already filed as
`docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md`; per the ledger
the orchestrator folds these rows into that note.

Adoption in place: comparing every `:seon.fn/sym` digest at `6ab35ad6…` (before) and
the head after my adoption, 30 of 4,768 changed: my four edited declarations plus other
lanes' concurrently edited `seon.cluster.boot`, `seon.cluster.source`, `seon.db`,
`seon.test` rows. Index digests are unchanged (`var-row` hashes before arities exist),
so no mass change and no from-zero boot. Agent-written rows keep their old stored
digests until next written. RESET NEEDED: no (for this change).

Contracts: none added or touched (`analyzed-form` has none; `source-rows`' is unchanged).
Hot path (`source-rows`/`analyze-forms`, agent evaluation): one digest per submitted row
moves from settlement to construction and one pull shrinks; parent-vs-self timing of the
same probe was not taken before the store failure below. Unmeasured, not a pass.
Src: fn.clj −2 net, program.cljc +2, net 0.

## Backstop regression (`test/seon/turn_backstop_test.clj`)

`a-thrown-step-cancels-its-completion-backstop`: agent + waiting message on the canonical
fixture, `:seon.db.process/id :not-a-process` so `seon.turn/turn`'s input contract
throws after arming; asserts arming then clearing (`[true false]` on the backstop-state
watch), the permit republished, and no fault on the fault channel through the whole
bound.

First run with a 100 ms bound failed with a backstop fault. REPL probe: the throwing pass
takes 197 ms (the contract refusal), longer than the bound, so that fault was
legitimate, not a missed cancel. With a 2,000 ms bound the probe gives `{:step-ms 197,
:state nil, :permit :seon.agent/ready, :fault nil}`. The committed test uses 2,000 ms.
Runs: `6c25b8a98849`/`c81ef132ce27` (100 ms): 3 pass, 1 fail as above. The 2,000 ms
version's test run errored "Node not found in storage" (run `f2e8e7dfe206`) and then
the prepl timed out at 150 s; default (pid 90963) then went away and pid 17208
started at 05:29:42; `bin/seon init` on it refuses in 0.14 s with
`datahike.index.persistent-set` "Node not found in storage" (address `6ab361f1-3…`).
**The final regression has not yet run green as a test**; its behaviour is proven
only by the REPL probe above.

Stale docstring, outside these paths: `seon.turn/step` still says "an escaped pass leaves
it armed" (contradicts `cc1f681b5`).

## Commits

- `3cbf5a135` digest parity (fn.clj, program.cljc, this note).
- the commit that adds this line: the backstop regression, not yet run green as a test.
