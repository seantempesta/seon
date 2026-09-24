# Open error signatures on default: triage (2026-09-24)

Owner question: "Is the mcp server error feedback helping find bugs?"
Read-only. The source is default's store (pid 99265, booted 18:13:37Z) at about
21:10Z. Every branch was read with `d/branch-as-db` over
`(seon.cluster.boot/connection "default")` in MCP JVM mode. That is 25
branches and 108 error rows, read in 217 ms. Times are UTC.

## Broken first

1. **Test-owned core faults escape into the cluster and pay for root turns.**
   `seon.fault/record!` copies every fault whose origin is not the cluster branch
   onto `:cluster-default` (`src/seon/fault.clj:144`, requirement 8.4). That
   includes faults a test fires on purpose. The copy opens a signature there, and
   `seon.error/commit-call` sends a message to `escalate-to` = `"root"`
   (`src/seon/error.clj:1450-1455`). On default, `"root"` is the in-cluster
   DeepSeek agent. That agent had 9 turns triggered by fault messages. Each one
   cost about 25k prompt tokens on `deepseek-flash` (attempts 51511, 57425,
   65244, 65873, 67922, 88018 and others). Every one of those turns ignored the
   fault and went on with its toy task (`my.agents.root/largest`). Its replies
   were `(my.agent/done)` and "I'll complete with a short reply…".
2. **Declared diagnostics with no throwable are recorded but never delivered.**
   The escalation arm in `commit-call` requires `interrupted?`, which is
   `(some? :seon.error/exception-class)` (`error.clj:1408`, `:1454`). Default has
   0 namespace stewards, so a fault with no exception class and no steward gets
   the recipient map `{}`. Signature `504062b8` (`seon.render/unknown`) has no
   message on any branch. This is the "Recording without delivery is a defect"
   case exactly.
3. **No stored fault carries `:seon.error/chain`.** Across 108 rows the key is
   absent. Each row has only a single `:seon.error/frame`, which is usually the
   instrument wrapper (`instrument.clj:726`) or a JDK frame. Several occurrences
   stored their evidence as "`:seon.sci.admit/remainder :over-bound`" in
   print-face EDN with no data blob. Their cause cannot be recovered (see
   `open-items.md:75` and `:68`).
4. **Forked branches inherit their parent's open faults.** `d3ef61b6` shows up
   on 10 branches and `504062b8` on 20. The issue is
   `docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md`.
   The MCP window also started at basis 0 (`open-items.md:51`). Summing
   occurrences across branches double-counts them. The counts below are per
   distinct signature and origin.
5. **12 test fixture cluster branches survive in default's store**
   (`cluster-issue-wake`, `cluster-late-arming`, `cluster-running-fixture`,
   `cluster-grammar` and others). They carry 4 signatures that exist nowhere
   else.

## Signatures grouped by root cause

There are 15 distinct signatures: 11 open on `:cluster-default`, which matches
the 11 in `runtime_status`, plus 4 only on the fixture clusters. None is
resolved.

| # | Signature(s) | Occ. / origins | First–last | Producer (frame / site) | Issue | Verdict |
|---|---|---|---|---|---|---|
| A | `d3ef61b6` `seon.db/q` "Datahike refused the database read." | 64 / 12 `agent-*` | 17:41–21:08 | `seon.turn/step` turn.clj:5559 → `next-agent-work`; cause InterruptedException (earlier ClosedChannelException) | `an-agent-graph-steps-after-its-test-branch-store-closes.md` | **live** |
| A′ | `371424a9` `seon.turn/turn` situation contract | 13 (inherited) / 1 | 17:29 | turn.clj:5565 | same issue (pre-eb14a2f22 form of A) | stale (eb14a2f22) |
| B | `c0ae227e` `agent-wake-datoms` "agent-eid … got a map" | 15 / 3 `agent-*` | 19:07 | `agent-eid` (turn.clj:2566) returns `db/q`'s refused-read map; `if-let` passes it on (turn.clj:2998) | none | **live** (§9.4: a lookup does not tell "none" apart from "could not read") |
| C | `a398d922` konserve `get-lock`, `57e8b3f1` `transact!`, `6a63862e` `agent-test/fn`, all InterruptedException | 8+20+3 / `agent-*`, `cluster-default` (old pid 69730) | 16:53–21:09 | JDK frames only | `an-interrupted-fixture-leaks-datahikes-roster-permit-…` (adjacent) | teardown noise; root defect: a stop's own interrupt is recorded as a core fault |
| D | `4265ebcd` `seon.db/basis-t` got nil | 12 / `agent-d9000b547530`, `agent-e59a31a8867f`, `cluster-default` | 21:02–21:09 | `seon.context/capture-tx` context.clj:386; caller turn.clj:4494-4507 | none | **live**, cause unverified (see below) |
| E | `b49c357c` stop ack not within 100 ms (`provider-backstop`) | 6 / 2 | 21:02–21:08 | agent.clj `turn-completion-failure!` | test-intended: `test/seon/cluster/agent_test.clj:880` `disarm-has-a-declared-loud-turn-completion-backstop` | test noise that escaped (broken item 1) |
| F | `0f1602ea` stop ack not within 20000 ms (`agent-0`) | 9 (inherited) / 1 | 17:52 | same producer; `agent-0` is `parallel-trial` (agent_test.clj:617) | none | **live** (a trial graph did not exit; test-branch but unintended) |
| G | `0c3d12e2` `seon.problems/failed-tests` NPE in `Numbers.ops` | 5 (inherited) / `cluster-default` | 20:20 | `(pos? nil-count)` in `failed-tests` | none | stale: e33009381 (20:37Z) makes a nil-count member its typed unknown; the status now shows 92 unknowns |
| H | `504062b8` "The selected renderer did not return an observation." (`:seon.render.history/entries`) | 24 (inherited) / `cluster-default` + 2 fixtures | 16:58–17:06 | render.clj:954; evidence capped at 7889 B, no blob | `open-items.md:75` | stale-unknown: evidence lost before 5c968dd34; undelivered (item 2) |
| I | `76a9acee`, `b7e13c4d`, `b24994d9` `seon.sci.eval/install-row!` "Cannot interpret …" | 2188+487+160 / 3 fixture clusters | 17:06–17:26 | sci/eval.clj:1114 (host-bound refused at acquisition) | `affected-callers-fail-to-interpret-against-uninstalled-bindings.md` | stale: f2bc7088a, b855a8a17, 55af9e04b refuse by name when called |
| J | `07974535` `acquire-context!` "Isolation requires a fresh owned branch." | 53 / 18 `agent-*` | 17:04–17:40 | agent.clj:892 | `agent-custody-defaults-to-the-cluster-label-branch.md`, `open-items.md:78` | probably stale (b2fcb5225 at 18:44Z); none since 17:40 |

**D: what is known.** The success arm calls `(:seon.db/db rendered)`, and both
producers carry it (prompt.clj:420, web.clj:2603). The refusal arm passes
`prompt-db` or `observed-db` (`db/db connection`), and `opening-db` never
returns nil (turn.clj:280-300). So nil comes from `(db/db connection)` or from a
rendered map that lacks the key. The first occurrence is 2 minutes after
d6e93ce72 (S1b release/join, 21:00Z), and one origin is the `provider-backstop`
test branch. Hypothesis: `db/db` on a released branch connection answers nil.

- **Probe:** call `(seon.db/db c)` on a connection after `seon.cluster.agent`
  release.
- **Owner:** seon.turn / seon.cluster.agent (S1 lane).
- **Fix:** refuse at `db/db`, not at `basis-t`. That is law 9.1: nil is never
  "could not read".

**Fractions (15 signatures):**

- **Live defects: 4 (27%)** — A, B, D, F.
- **Test or teardown noise: 4 (27%)** — E, and the three signatures in C.
- **Stale: 7 (47%)** — A′, G, H, the three in I, and J.

By cause, only three distinct live defects are new to the issue list: B, D and
F. A was already filed.

## (a) Is the feedback finding bugs?

Yes, but the signal is diluted.

- **Surfaced before any issue existed:** B, D and F, plus the undelivered-arm
  defect and the missing chain.
- **Confirmed an open issue:** A.
- **Not signal:** about half of the list is stale. There is no resolution step
  (`seon.problems/resolve!` is never called after a fix landed). Inheritance
  multiplies every signature across branches.

## (b) Delivery today

- **Inside the cluster:** delivery exists.
  - The route is `seon.fault/fault!` → `record!` → `seon.error/commit-call`,
    which writes one `:seon.message` per opening to the steward, the attributed
    agent, or `escalate-to` (error.clj:1335-1347, 1450-1470).
  - On `:cluster-default`, 10 of 11 signatures have a message root→root.
    Messages 67478, 43133, 50342, 55045, 64998, 72966, 57035 and 64894 triggered
    root turns that "handled" them.
  - The owner that receives them is wrong: an LLM toy agent with no steward
    and no repair mandate.
- **Outside the cluster:** nothing is pushed to the orchestrator (the outside
  root) or to any namespace agent. They learn only by pulling:
  - `runtime_status` `open-faults` (health "faulted")
  - the MCP `seon.dev.mcp/branch-errors` window on each evaluation
  - the problems page

  `bin/seon faults --wait` is not built yet (`open-items.md:110`).

## (c) The smallest change

1. **error.clj:1454.** Escalate every opening with no steward, whatever its
   exception class. Change `(and interrupted? escalation-present)` to
   `escalation-present` and keep `{agent-id :your-run}` for an attributed run.
   This is one line, and it closes item 2.
2. **Keep test faults on their test branch.** In `record!` (fault.clj:144), set
   `copy?` false when the origin is a `seon.test` run's branch.
   - `:seon.test.run/branch` is already declared (`seon.test.run.edn`).
   - The test's own result must carry any unexpected fault as red. F then
     surfaces as a red test instead of waking root, and E stays with the test
     that asked for it.
   - The partition law (8.5) already keeps errors out of merges. Leaving them
     out of the cluster view follows from "an agent's mistakes go with its
     branch". A branch fork should start its error window at the fork basis.
3. **Route the escalation to a real owner.** Name the outside orchestrator as
   the `escalate-to` agent, or give `"root"` a fault-repair assignment. Also
   build `faults --wait` as a reader over root's messages whose
   `:seon.message/about` is a signature. It is a reader, not a new route.

   **Owner decision needed:** should an LLM root turn run, and be paid for, for
   each core fault?
4. **Record `:seon.error/chain`** at `seon.error` normalization, the owner that
   already records `:seon.error/frame`. This applies the "keep the whole cause"
   law. It is a separate slice.

**Smallest fixes for the live groups:**

- **B:** `agent-eid` returns a refused read as that refusal, and
  `next-agent-work` escapes it through the existing eb14a2f22 path.
- **A, C:** release (S1b) treats its own interrupt as the graph's declared
  exit, not a fault.
- **D:** probe first (above).
- **F:** rerun `parallel-trial` quietly. If it fails again, it belongs to the S1
  owner.
- **Stale groups:** call `seon.problems/resolve!` for G, A′ and I once the fix
  commits are confirmed adopted. Root decides; this lane only read.
