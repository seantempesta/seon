---
type: landing
lane: branch-repl (Opus 5.5)
status: current
created: 2026-09-23
extends: D1 §2a (candidate = branch + handle), B1 §3b (the development REPL)
---

# Lane branch-repl — a lane codes on its own branch through MCP

**What already exists.** `eval_clj` in SCI mode with a `branch` already went
through `seon.cluster.agent/acquire-context!` (`mcp.clj:509-517` before this
change). It then released the handle after every call, so a def died with the
call. Nothing settles program rows outside a turn: `evaluate` hands back a
`:seon.program/row`, and only the turn's receipt settlement transacts it
(`turn.clj:935`, `:3214`).

**Smallest composition.** Keep the acquired handle in the cluster's context
state, as an agent's is. Give lanes a `branch` tool that creates, lists and
retires a branch by calling `registry/branch!`, `registry/roster`,
`agent/release-context!` and `registry/retire-branch!`. mcp.clj gains argument
plumbing only.

## STEP 1 — D1 §2a at default's REPL

The probes ran on default pid 94821 up to 15:05Z, and on pid 5070 after that
(a restart from the checkout, not by this lane). The scratch branches were
`:lane-branch-repl-probe` and `:lane-branch-repl-probe2`, both off the cluster
head. Both were released and retired afterwards; the roster has no `lane-*`
branches and the context state holds no `lane-*` entries.

| question | answer | form / value | ms |
|---|---|---|---|
| Create a branch off the head | yes | `(registry/branch! {:seon.store/store s :seon.store/branch :lane-branch-repl-probe :seon.cluster.registry/from :cluster-default})` returns `{:created? true}` | 29 |
| `defn` through `eval_clj` sci+branch persists as `:seon.fn/*` rows | **no** | `(defn lane-probe-add [x] (+ x 41))` returns `#'user/lane-probe-add`. `[:find ?a :where [?e :seon.fn/sym user/lane-probe-add] [?e ?a]]` is `[]` on the branch and on default. The branch head stayed at `6ab3e97d…` | 252 (174 eval; 438 MB allocated) |
| Callable in a later eval on the same branch (before this change) | **no** | `(lane-probe-add 1)` fails with "Unable to resolve symbol", because the handle was released at `mcp.clj:526-528` | 345 |
| Callable in a later eval with the handle retained (this change's mechanism, called directly at the REPL) | yes | two `acquire-context!` calls on one `[branch nil]` key give `#'user/lane-probe-add`, then `"42"` | 315 / 480 |
| Callable from another agent's context on that branch | yes | `(acquire-context! handle "root" {:seon.agent/branch :lane-branch-repl-probe2})`, then `(lane-probe-add 2)` returns `"43"`. The agent context forks from the same branch base | 46 |
| `seon.test/run` on the branch | yes | `{:seon.test/execution h :seon.test/recording-connection (:seon.db/connection h) :seon.test/policy :named :seon.test/identities #{seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol}}` gives 5 pass, `passed? true`. It recorded on the branch (head moved to `6ab3ea5b…`) | **2015** |
| …against the lane's in-memory definition | **no** | after `(do (ns seon.id) (defn id … 'broken))` in the branch context, the same run still gives 5 pass. The run executes the branch's program rows, not private SCI defs | **1457** |
| Redefining a file-backed function: new body when called directly | yes | `(do (ns seon.cluster.registry) (defn cluster-branch [n] …) (cluster-branch "x"))` gives `:redefined-x` | 54 |
| …and its compiled callers | **no** (B2 §2a TARGET) | the same redefinition, then `(my.agent/branch {:seon.agent/id "root"})` gives `{:branch :cluster-default :mode :live}`. The compiled caller used the compiled Var | 89 |
| Default is unchanged | yes | the JVM `(seon.cluster.registry/cluster-branch "x")` still gives `:cluster-x`; `lane-probe-add` does not resolve in the shared context | 1 |
| A host-bound declaration refuses | **no named refusal** | overriding `my.background/invalid-call` (`:seon.fn/host-bound? true`) gives `:overridden`. `java.lang.ProcessBuilder` fails SCI analysis ("Unable to resolve classname"). The named refusal exists only on the adoption path (`cluster.clj:2714-2790`) | 38 / 21 |
| Retire | yes | `release-context!`, then `retire-branch!`: off the roster, 0 held entries. A connection the probe opened itself had to be released first, or retire refuses with `::cluster-connected` | 12 |

**D1 §2a composed proof (candidate worker, one evaluation, faults only on C).**
The installed evidence is `seon.namespace-agent-loop-test`, run through
`bin/test-check default --ns seon.namespace-agent-loop-test` (run `89f81ab08665`).
All 12 assertions passed:

- the worker runs on C;
- its virtual reply evaluated on C;
- no fault;
- H's commit and definition digests are unchanged.

The run is red only on its bound: **13,311 ms against 5,000 ms**. It is over
ten seconds, so that is a defect. The existing class note is
`docs/seon/issues/issue-worker-opening-turn-closes-and-no-reply-turn-opens.md`,
which measures opening forms at about 2.3 s each. It is not extended here
because it is outside this lane's paths; the orchestrator is asked to append
this row to it.

The test builds the candidate handle by hand in 35 lines:

- environment, launcher, `cluster-handle`, `wake/route!` and `arm!`
  (`test/seon/namespace_agent_loop_test.clj:22-98`);
- no src function composes this inside a running cluster's JVM.

## STEP 2 — what landed (argument plumbing only)

- `script/seon/dev/mcp.clj`:
  - SCI+branch evaluation retains the acquired handle. The per-call
    `release-context!` is deleted.
  - `branch-name!` is the one name check, shared with `eval_clj`.
  - `branch-form` / `execute-branch` form the new `branch` tool: `create`
    (off the head's commit), `list` (roster plus the cluster's own branch) and
    `retire` (release the retained context, then `retire-branch!`).
  - These refuse as declared `:seon.error` maps with layer `:seon.dev.mcp/branch`:
    the cluster's own branch, an existing name on create, a branch an agent
    holds on retire, and a missing name.
  - The `eval_clj` description now states what the branch context is and is not.
- `test/seon/dev/mcp_bridge_test.clj`: `a-lane-owns-its-branch-create-define-call-retire`
  checks create, a refused duplicate, defn, call, shared-context absence,
  list, retire, released context, and refusal of the cluster's own branch.
- The MCP server process loads mcp.clj at start. The `branch` tool and the
  retained context reach MCP callers after the next MCP server restart. That is
  a client reconnect, not a default restart. **Waiting:** the server restart.

**Verification boundary.** The mechanism is proven by direct REPL calls (the
table above). The edited mcp.clj loads under a throwaway namespace
(`(load-string …)` with `ns lane.branch-repl.mcp`, 31 ms, tools
`[eval_clj runtime_status branch get_value]`). clj-kondo reports 0 errors.

The end-to-end regression was **not** run, for two reasons:

1. Adoption refused. `bin/seon init --dev default --changed script/seon/dev/mcp.clj
   test/seon/dev/mcp_bridge_test.clj` answered "Source changed during
   development adoption", naming 15 other lanes' dirty paths; it took 3,470 ms,
   of which `full-source-refresh!` was 2,481 ms, proportional to the dirty set.
2. Every branch acquisition on pid 5070 refuses with "Program acquisition found
   a namespace binding cycle". One cycle is `seon.flow` ↔ `seon.fault`: the
   `:as-alias` require in committed `fault.clj` is counted as a binding. This
   was reported to the orchestrator, who assigned the fix to m4-n1 (quick) and
   b1-adoption (indexer owner).

The regression waits for both. A red here is not a pass.

## What an internal agent can already do through `my.*`

| operation | agent via `my.*` | seam |
|---|---|---|
| read its branch and mode | yes | `my.agent/branch` (`src/my/agent.clj:60`) |
| test its change on its branch | yes | `my.test/check`, `my.test/run-owned` (`src/my/test.clj:5`, `:28`), both calling `seon.test/run` |
| create / retire a branch | **no** | isolation is set at start (`seon.issue/start!` with `:seon.agent/branch`, `issue.clj:1259`); no `my.*` function |
| persist a defn as program rows | yes, in a turn | turn settlement (`turn.clj:935`, `:3214`) |
| an isolated worker armed in the cluster's JVM | **no** | no installed composer (D1 §2a); test only |
| merge its branch into the cluster | **no** | `seon.cluster.source/prepare-merge!` / `accept-merge!` (`source.clj:741`, `:791`) have no `my.*` door (D1 §2c "Only explicit `my.task/merge!`": [TARGET]) |

## Export gap — D1 §2d write-back (slice 7, not built)

`src/seon/cluster/export.clj` bounds base exports only. It does not write back
an accepted delta. The table maps what is missing to D1 §2d's steps.

| §2d step | missing |
|---|---|
| 1. resolve file/span provenance | no function maps an accepted identity at M to its file and span. An agent-admitted row has no `:seon.fn/file` (`seon.fn.edn` `:file` description). There is no destination rule for a new declaration or namespace |
| 2. isolated staging at the expected source commit | no staging owner. Splices exist (`my.edit/form!`, digest-checked) but nothing groups them per file in descending span order against one read |
| 3. staged-bytes publication analysis, digest equality | no call runs B1's publication analysis over staged bytes and compares canonical definition digests with M's rows |
| 4. callable proof of the staged set | no use of B4's isolated host over the staged program |
| 5. path-limited commit, then controlled integration and adoption | no integration step. Today the lane edits the file and runs `bin/seon init --dev --changed`, which the dirty-dependent gate refuses while other lanes hold dirty files (seen above) |
| 6. verify publication, loaded behaviour, paint; staging removal | none |

Also upstream of §2d:

- private SCI defs never become rows, because there is no turn. A lane's
  mergeable delta therefore requires the D1 §2a armed candidate worker
  (`submit-source!`, `agent.clj:606`).
- `my.task/merge!` is not built.

## TIMINGS (over one second)

| op | ms | justification |
|---|---|---|
| `seon.test/run`, one named test on the branch | 2015 / 1457 | `seon.test/host-exclusions`, then `seon.fn/gate-sets`, then `declared-reference-edges`: 687–692 ms each run, proportional to the whole program. Already filed: `docs/seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md`. The second run executed again instead of reusing the green recorded 1 s earlier on the same branch (no `:unchanged`; the recording moved the head) |
| `bin/test-check --ns seon.namespace-agent-loop-test` | 15,317 (member 13,311) | **defect** (over 10 s). Opening forms are slow; see the note above |
| `bin/seon init --dev … --changed` (refused) | 3,470 | full source refresh over 15 foreign dirty paths; the adoption gridlock this lane addresses |

## Net lines

- script: +101 / −26 in `script/seon/dev/mcp.clj`. That is net +75, over the
  ~60 budget; of the additions, 14 lines are the tool's JSON schema and
  descriptions.
- test: +30.
- skill: +52.
- src: 0.
- The change deletes the per-call release and the duplicated branch-name check.
  B1 §3b R3 plans to rewrite this bridge (889 → ≈360 lines) and will absorb it.

No reset is needed.
