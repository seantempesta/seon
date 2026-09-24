---
type: landing
status: current
created: 2026-09-24
scope: Lane fix-fault-delivery. Unattributed faults without a throwable escalate to root; over-bound fault evidence keeps its cause chain; test-fault containment designed, needs seon.test.
---
# Lane fix-fault-delivery — landing (2026-09-24)

This lane changes how faults are delivered (`seon.error/commit-call`) and how
evidence is admitted (`classifying-error-data`). The source is
`docs/research/agent-platform/open-errors-triage-2026-09-24.md` (0d48a2449).
Root's pause order arrived partway through, so neither commit has been through
a test run yet, and neither is installed in default: hook publication is
paused. RESET NEEDED is not claimed. Adoption happens when root next
publishes.

## Broken or not done first

1. **Item 1 (test faults copied to the cluster) is not implemented. The
   brief's premise was wrong.** `:seon.test.run/branch` is the run's
   *result destination*, not the branch the member ran on. On default all
   505 test runs have `:seon.test.run/branch :cluster-default` (probe below).
   Faults originate on member branches named `agent-<hash>`. For example,
   every occurrence of `b49c357c` has origin `:agent-5c5b25a0f0c4`,
   `:agent-d9000b547530`, … and no occurrence agent. Those branches come from
   `seon.test/member-result` (test.clj:1452), which calls
   `acquire-context! … {:seon.agent/isolate? true}`. No durable fact marks
   them as test members: the store has no member→branch attribute
   (seon.test.member.edn), and `branch!` writes no purpose. The only record
   is the in-memory timing map (test.clj:1466). `record!` therefore has no
   declared fact to check, and a new one needs **seon.test (held)**.
   - Smallest design, about 15 lines. Declare `:seon.test.member/branch`,
     written at member acquisition in `member-result`. Then `record!`
     (fault.clj:144) sets `copy?` false when a member claims that origin.
   - The test also has to show unexpected faults. Before unlinking,
     `member-result` reads the member branch's errors since the fork basis
     (the same `branch-errors` basis window the MCP uses) and counts them
     as an error in the member's result. The test fixture that fires a fault
     on purpose (agent_test.clj:880) then declares it as expected.
   - Owner: the seon.test holder, plus this lane's fault.clj line.
2. **Item 2 residual.** A core fault that reaches `fault!` tagged with an
   agent but carries no throwable still gets no message. `commit-call`
   can't tell it apart from an attributed agent mistake that turn.clj
   records, such as a refused transition or a provider error. Both reach it
   without an exception class and with agent attribution. Waking the agent
   for those would mail every agent mistake to itself. Telling them apart
   needs a declared core-fault marker set by `fault/record!`, which means a
   schema resource this lane does not own. Not observed live. Every
   attributed live signature carries an exception class.
3. **Item 3's premise was partly stale.** `:seon.error/chain` is declared
   "Evidence, not an attribute" (seon.error.edn:283). It is not a Datahike
   attribute. Pulling it refuses with "Bad entity attribute", so no row
   *could* carry it. For a Throwable source that fits the bound, the whole
   `Throwable->map` (`:via` chain and trace) is already in the evidence
   content. Since 5c968dd34 every capped occurrence has a blob: the last 25
   occurrences on default are all `capped? true, blob? true`, including
   `504062b8` at 21:22Z. The real remaining loss is fixed below.

## What changed

- **76e4e0ab6. Item 2** (`src/seon/error.clj` recipients, 1 line changed
  plus a comment).
  - Before, an opening with no steward escalated only when
    `(and interrupted? escalation-present)`. Now it escalates when
    `(and escalation-present (nil? agent-id))`.
  - Throwable behaviour is unchanged: a throwable with an agent still goes to
    `:your-run` by the earlier arm.
  - Regression: `seon.error-test/an-unattributable-fault-without-a-throwable-is-delivered`.
- **addab5ae5. Item 3** (`classifying-error-data`, +7 lines).
  - Before, an over-bound source kept only the remainder marker in both the
    fact and the blob. This was probed with an 8 MB ex-data: 431 bytes of
    content, and no class, message or cause.
  - Now the classifying partition adds `:seon.error/chain` with each link's
    class, message and first-party frames. The link's `ex-data` is left out,
    because it is the over-bound remainder the marker measures.
  - Regression: `seon.error-test/over-bound-fault-evidence-keeps-its-cause-chain`.

Net: src +16/−6, tests +29. fault.clj is unchanged.

## Evidence (MCP JVM, default pid 99265)

- `runtime_status`: 14 open signatures, 1 agent (root), 0 stewards.
- `(d/pull … [:seon.error/chain])` refuses because the attribute is not in the
  schema.
- A `seon.error/prepare` of a 3-link ex-info that fits the bound kept all
  three messages in `data-content` (10,041 bytes). Time: 31 ms.
- The same with `{:big (vec (range 400000))}` (8,388,652 bytes, over the
  8,388,608 bound) gave `data-content` = the remainder marker only. Time:
  1,002 ms. That is over one second, dominated by admitting 8 MB twice in
  `bounded-error-admission` / `admit-partitioned`. This commit does not
  change that cost. It is proportional to the source size.
- The new partition, run in scratch namespace `tmp.fix-fault-delivery` with
  `admit-partitioned` at a 16 KB bound, kept 3 links (outer, middle, root
  cause) in 1,538 bytes, with the remainder `{:bytes 16409 :reason
  :over-bound}`. Time: 11 ms.
- Test runs: 505 runs, all `:seon.test.run/branch :cluster-default`. The
  `b49c357c` origins are five `agent-*` member branches.

## Verification still owed (root, quiet window)

- `bin/test-check default --test seon.error-test/an-unattributable-fault-without-a-throwable-is-delivered`
- `bin/test-check default --test seon.error-test/over-bound-fault-evidence-keeps-its-cause-chain`
- `bin/test-check default --ns seon.error-test`. Existing
  `only-a-throwable-tells-the-attributed-agent` should stay green.
- `bin/test-check default --ns seon.fault-test`

Limits:
- Neither the load nor the tests have been proven in the JVM. clj-kondo on
  error.clj shows 0 errors.
- The 16 KB bound in the new test assumes the chain with its first-party test
  frames fits. If it answers the honest marker, raise the bound.
- Root's LLM turn on a fault is unchanged (owner decision pending). Item 2
  makes more faults reach root.
