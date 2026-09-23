---
type: research
status: evidence for D1 §2e (outside agents take the same path)
created: 2026-09-23
lane: outside-agents-design (Opus 5.5)
---

# How a Seon agent writes code today, traced at the REPL, and where the path breaks

**What the system already does.** An inside agent writes code by *replying source*.
`seon.cluster.agent/submit-source!` (`src/seon/cluster/agent.clj:606`) is the entrance for
system-authored source, and it uses the same durable run path as a model reply: parse,
`seon.sci.eval/evaluate` (`src/seon/sci/eval.clj:3192`) returning `:seon.program/row`, the
definition-time gate, `settle-batch!` committing rows on the agent's custody connection,
then `install-evaluated-rows!` (`sci/eval.clj:1218`) into the context. **The smallest
composition:** make an outside agent an ordinary agent row whose replies come from MCP
instead of a provider. The MCP door then calls that same entrance and adds no loading path.

All probes ran on `default` (pid 94821, started 2026-09-23T14:52:12Z, source
`/Users/sean/src/seon`), with MCP JVM mode, private session `outside-agents-design` and
namespace `outside-agents.probe`, unless marked SCI. Branches created by this lane
(`:outside-agents-probe`, `:outside-agents-probe2`) were retired within the session.

## 1. The inside path, step by step

| # | step | function, file:line | evidence |
|---|---|---|---|
| 1 | custody: the agent's branch | `:seon.agent/branch` on the agent row, read by `seon.cluster.agent/acquire-context!` (`agent.clj:782`); `my.agent/branch` (`src/my/agent.clj:60`) answers live/isolated | handle keys observed: `:seon.agent/branch :seon.agent/mode :seon.agent/owns-branch? :seon.agent/owns-connection? :seon.db/connection :seon.sci.eval/ctx :seon.sci.eval/base-ctx :seon.env/environment :seon.source/commit-id :seon.store/store …` |
| 2 | a branch is a pointer | `seon.cluster.registry/branch!` (`registry.clj:193`) | `(branch! {:seon.store/store store :seon.store/branch :outside-agents-probe :seon.cluster.registry/from (seon.db/commit-id d)})` → `{:seon.cluster/created? true}` in **28.5 ms**; a second from the loaded commit 30.2 ms |
| 3 | acquire the context | `acquire-context!` → `sci.eval/acquire!` → `acquire-program!` (`sci/eval.clj:1881`): `overridden` = rows whose digest differs from the JVM's loaded commit (`:1940-1943`), `affected` = `seon.fn/reverse-closure` | on a branch of the loaded commit, 298 ms. On a branch of `default`'s head it is **refused** (break B1) |
| 4 | evaluate a `defn` | `evaluate` (`sci/eval.clj:3192`); `definition-row` (`:426`) analyzes the source with the file indexer `seon.fn/source-rows` and records the contract; the value is returned with `:seon.program/row` (`:3526-3590`) | SCI, branch `outside-agents-probe2`: `(defn outside-probe-inc "Probe fn." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` → `#'outside-agents.scratch/outside-probe-inc`, eval **77 ms**, MCP round trip 612 ms |
| 5 | definition-time gate | `seon.turn/gate-function-install` (`turn.clj:3220`) → `sci.eval/evaluate-candidate` (`:3750`) runs the gate set and auto-check, then `accept-candidate!` (`:3657`) or `refuse-install` | read only. D1 slice 6 retires it. A `defn` without `:malli/schema` is refused: "every function needs a :malli/schema contract to become part of the program" (`test/seon/cluster/agent_test.clj:373-405`) |
| 6 | persist rows | `settle-batch!` inside `resume-turn` (`turn.clj:4960-5000`) commits evaluations and rows on the agent's connection; `committed-row?` (`sci/eval.clj:818`) selects the installed ones | nsa-reply-turn landing: `settle-batch!` 0.7–0.8 s per pass |
| 7 | install into the context | `install-evaluated-rows!` (`sci/eval.clj:1218`) transfers evaluated roots; `installation-covers-program-change?` (`:1169`) scans `(since (history after) t)` for `[?entity]` (`:1175-1181`) | nsa-reply-turn landing: 0.7 s mean; the scan measured **5,617 ms** on default's store (513,000 datoms). Non-defining batches skip it since that fix; **defining batches still pay it** |
| 8 | test the change | `my.test/check` (`src/my/test.clj:5`) → `seon.test/run` (`src/seon/test.clj`) with the agent's context and connection; each test is a one-body branch | JVM `(seon.test/run {:seon.test/execution h :seon.test/recording-connection (:seon.db/connection h) :seon.test/policy :named :seon.test/identities #{'my.program-test/program-reads-name-the-referrers-and-propose-without-writing}})` on the loaded-commit branch → refused in 7.6 ms: "Effective configuration requires a matching cluster row with every required dial." (break B3) |
| 9 | prepare a merge | `seon.cluster.source/prepare-merge!` (`source.clj:741`): `merge-base` (`:709`) → `program/changed-identities` → three `digest-map`s → `three-way` → `replacement` (`published-index-rows` + `reconcile-tx`) → `seon.cluster/candidate-gate!` (`cluster.clj:2669`) runs the reaching tests plus the issue's tests on a fresh branch S of H | read only. nsa slice-4 landing: prepare **3,837 ms** (3,003 ms of it the nested run) |
| 10 | accept | `accept-merge!` (`source.clj:791`): rereads the named run on S, every member green, every replaced function reached, the program unwritten since, then `db/transact!` with `:datahike/expected-basis-t` and `:parents` | nsa slice-4: accept **1,187 ms**, of which ≈714 ms is the first `gate-sets` reach index (whole program); 21 ms warm |
| 11 | reach the files | **not installed**: D1 §2d, slice 7. Pieces that exist: spans on 4,819 of 4,820 function rows (`:seon.fn/file`, `:seon.fn/form-span`), `my.edit/form!` digest-fenced splice (`src/my/edit.clj:35`), `my.fs/write!` with `:my.fs/precondition` (`src/my/fs.clj:57`), B1 publication over captured bytes (`source.clj:105` `capture-paths`, `:544` `publish!`) | accepted rows today live only on the destination branch (nsa design, "Deliberately later") |
| 12 | remove a definition | `my.program/ns-unmap!` (`src/my/program.clj:555`) retracts the declaration after `breaks` and unmaps it in the fork and the base; `remove-ns!` (`:569`) | read only; the agent-code-api catalog owns the REPL proof |

The filesystem path, for comparison: `bin/seon init --dev default --changed P` → `refresh-source!`
(`cluster.clj:2791`) → `save-gate!` (`:2751`). That tests interpretable changes on
`:cluster-default-candidate` through `candidate-gate!`. Host-bound changes go through
`adopt-then-test!` (`:2728`): adopt first, then test on the cluster. The whole path is
guarded by `verify-development-sources!` (`:2312`) and the adoption token.

## 2. Break points (observed, not inferred)

| id | break | evidence | owner / fix |
|---|---|---|---|
| B1 | **every interpreted acquisition refuses a false namespace cycle.** This covers SCI on `default` (860 ms, 3.37 GB allocated, refused before evaluating), a branch of default's head (1,672 ms) and `bin/test-check` ("check unavailable") | `seon.fault` `[seon.flow :as-alias flow]` is stored as a require (`fn.clj:258-261`); `seon.flow` requires `seon.fault`; the order at `sci/eval.clj:2025-2060` finds `seon.fault ⇄ seon.flow` | [issue](../../seon/issues/as-alias-recorded-as-a-require-refuses-interpreted-acquisition.md); one-line producer fix in `fn.clj` (held by b1-adoption) |
| B2 | default's rows lead its loaded code by **275 declarations** (loaded `6ab3e63a…`, head `6ab3eaed…`; 128 in `seon.sci.eval`, 53 `seon.flow`, 39 `seon.cluster.agent`), so every branch off default interprets a 133-namespace closure | JVM: `(#'seon.sci.eval/function-digests …)` over loaded vs head, 32.5 ms | B1 §2a′ (rows → load → arm → record). The braid is that lanes' file edits move rows and loaded code separately |
| B3 | an MCP SCI `defn` persists nothing and evaporates. The branch has no row and its commit is unchanged; the next call on the same branch answers "Unable to resolve symbol" | the MCP form calls `evaluate` and never settles (`script/seon/dev/mcp.clj:482-528`, "creates NO run or receipts"); `release-context!` in `finally` drops the context | this is an MCP-only evaluation path. D1 §2e replaces it with the ordinary submission |
| B4 | acquisition work is proportional to the whole program on every MCP call: `function-digests` twice over 4,820 rows and a reverse closure (980 ms) | profile lines on the refused acquisitions: `acquire-context!` 1,347–1,671 ms, `seon.fn/reverse-closure` 980 ms | a handle retained by `[branch agent]` in `:seon.agent/context-state` (the existing retention, `agent.clj:782`) makes an unchanged head free; the per-call release is the MCP door's choice |
| B5 | a branch of a publication commit (`current-src` lineage) has no cluster row, so `seon.test/run` refuses on missing dials | 71 missing `:seon.config/*` dials, `:seon.cluster/name` absent | an agent's branch must fork from its cluster's branch head, never from `current-src` |
| B6 | the definition-time install check is O(store) for a defining batch | `installation-covers-program-change?` `[?entity]` since-scan, 5,617 ms on default | key it by the batch's changed identities (the report), not the since-window |
| B7 | merge admits only replacements: "Only replacements of existing functions and tests merge here" (`source.clj:767`); an issue on the candidate is required (`:772`); accept is an owner JVM call with no named accepter | source | D1 slice 5 widening; nsa follow-ups |
| B8 | files: no write-back. 830 of 4,820 functions are host-bound (17%, spread over 159 namespaces; 564 `defn-`, 249 `defn`, 9 `deftype`, 6 `defrecord`, 1 `defmacro`) and change only through files. 3,373 schema rows carry no file attribute (`:seon.schema/file` is uninstalled, the `seon.db/q` refusal) | JVM census 29 ms | D1 §2d; B1 §2a′ S6 (`:seon.schema/file`) |
| B9 | dogfooding: exactly **one** row in default was ever admitted by an agent (`my.agents.root/largest`) | JVM census | the population the design exists to grow |

Observation, not a break: `bin/seon status` reports `:seon.operator/hook-publication :on`,
while the schedule (`0b66a2a28`) records save-time publication OFF. Status reads a different
switch from the one the schedule describes. The lane did not reconcile the two.

## 3. Timings

| operation | ms | over 1 s: what it is proportional to |
|---|---:|---|
| `bin/seon status` | 118 | — |
| `registry/branch!` ×2 | 28.5 / 30.2 | — |
| SCI `defn` on the loaded-commit branch (MCP round trip / eval) | 612 / 77 | — |
| acquisition, loaded-commit branch | 298 | — |
| acquisition refused, head branch (MCP SCI) | 1,672 | whole program: two digest maps over 4,820 rows plus the 980 ms reverse closure of 275 overridden rows. A defect (B2/B4), not a justification |
| acquisition refused, JVM direct / with cycle search | 1,347 / 2,298 | same; the second form acquired twice |
| SCI on default refused | 956 (860 eval) | the re-acquisition above; 3.37 GB allocated |
| `seon.test/run` refused on missing dials | 7.6 | — |
| override census, default | 32.5 | — |
| host-bound / admission census | 29 / 8 | — |
| `bin/test-check … --test …` | refused (B1) | not timed separately |

No test ran green, no source was edited, and nothing was adopted.
