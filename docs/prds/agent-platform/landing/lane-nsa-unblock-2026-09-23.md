---
type: landing
status: both owner defects fixed; nsa loop regression still RED on a turn defect in another owner's files
lane: nsa-unblock
plan: docs/prds/agent-platform/plan/README.md §4 cut 3 (namespace-agent loop); follows lane-nsa-slice1-2026-09-23.md
created: 2026-09-23
---

# nsa-unblock: the render union and the host-exclusion walk

## Defect 1: `seon.render/invoked` refused its own producer's value

- **Probe** (JVM, packaged projection, read-only):
  - With `v` = `{:seon.render/source-blocks [{:seon.render/source "(help)"} {… :seon.eval/origin "issue-1"}]}`,
    `(m/validate [:or :seon.render/rendered :seon.render/error-result] v {:registry reg})`
    returned false.
  - The widened union returned true. The probe took 715 ms, mostly
    `build-projection`.
- **Who returns it.** `seon.cluster.agent/render-identity-ai` (`agent.clj:256`) declares
  `[:or :seon.render/source :seon.render/source-blocks]`.
- **Who consumes it.**
  - `raw-output`'s `:ai` arm already admits it:
    `(valid-projection? projection :seon.render/source-blocks rendered)`.
  - `render-call` (`render.clj:1517-1550`) joins the blocks into source and retains
    them.
  - `seon.turn` (`turn.clj:1879`) reads `:seon.render/source-blocks`.
  - So the callers handle it, and only the contracts omitted it.
- **Fix.** Add `:seon.render/source-blocks` to the output unions of `invoked`,
  `invoke-producer` and `raw-output`, which is 3 lines. `invoke-producer` returns
  `invoked`'s value. Its one caller, `render-form-value`, already refuses a non-form
  value through `valid-projection?`, and it now does so as its own honest refusal
  instead of a contract kill. `render-ai`'s union is unchanged, because it does not
  reach the identity producer.
- **Observed after adoption.** The worker's opening now evaluates 8 system sources
  on C, and its fault channel stays empty (runs `346a5f85134a`, `4dcf98c29a2a`).
  Before the fix, every run faulted at `render.clj:1242`.

## Defect 2: `seon.test/host-exclusions` stored nil and did a pull per member

- **Cause.**
  - `destructive-path` walked forward from each member, with one `db/pull` per path
    (not per node) and no per-level dedup.
  - It followed only `:seon.fn/calls` and `:seon.test/subject`, but reach
    (`seon.fn/gate-set-in`) also follows `:seon.fn/references`.
  - So three reaching tests got nil, for example
    `seon.test-reaching-test/a-program-declaring-no-destroyer-refuses-instead-of-admitting`,
    whose path runs through a quoted reference.
  - Two of those walks took 9.6 s and 9.9 s. Over the 64 reaching tests, 18,401
    pulls took 21.5 s.
- **Fix.** `destructive-paths` makes one breadth-first reverse walk per destructive
  owner over the same three `:avet` index edges `gate-set-in` walks. It keeps a
  next-hop map, so every reached name gets its shortest path in the same walk.
  - Cost is proportional to the owner's reverse closure once (34 and 51 names). It
    no longer grows with member count × path count.
  - `destructive-exclusion` writes the path only when one exists. nil is never stored.
  - The per-member fixture-observation pull is now one `db/q` over
    `[?symbol ...]`.
  - `destructive-reach` calls `seon.fn/gate-sets` once for all owners, instead of
    `tests-reaching` once per owner. The result is equal (`:same true`, 64 entries).
- **Measured** (JVM, same database value):

  | Probe | Before | After |
  |---|---:|---:|
  | paths for the 64 reaching tests | 21,512 ms (18,401 pulls), 3 nil | 11 ms, 0 missing |
  | `destructive-reach` | 1,863 ms | 63 ms |
  | `host-exclusions` over all 2,073 test symbols | refused (nil path) | 158 ms warm (1st call 2,120 ms, concurrent lanes' work in the profile) |
  | fixture-observation read (2,073 symbols, 52 rows) | 2,073 pulls | 31 ms, one query |

- **Incremental run** `800141b76dbb`
  (`--policy incremental --changed seon.issue/start!`, 127,112 ms wall).
  - It no longer refuses: executed 41, reused 16, excluded 30 (every one with its
    path), long 119, pending 1.
  - Reaching set: about 208 members. The reds are foreign; see Limits.

## Orchestrator follow-ups in `src/seon/test.clj`

1. **`resolve-test` output union.** It returns `(first (:seon.test/acquisition-refusals …))`.
   The producer, `seon.sci.eval/acquisition-refusal` (`sci/eval.clj:1747`), declares
   `[:or :seon.sci.eval/row-acquisition-error :seon.sci.eval/interpretation-error]`.
   - Both names are added to the union. A sample `interpretation-error` validates.
   - In run `9dfc47c13d3d` the refusal now reports as a red member ("Cannot interpret
     seon.cluster.reload-measure/-main: Host-bound declaration …") instead of
     check-unavailable.
   - No regression was written. Building a host-bound row that differs from the loaded
     namespaces needs the sci/eval lane's fixture.
2. **Reuse-drift wording.** The message now says the source `X` "is published but not
   yet adopted; the cluster's program rows record `Y`". The key stays
   `:seon.test/loaded-source`, because its schema is outside this lane.
3. **`run-batch` → `runner/record-interrupted!`**, from the hunk in
   lane-unfinished-runs-2026-09-23.md.
   - The member loop is wrapped. A throw records every unrecorded member red, then
     rethrows the ORIGINAL throwable.
   - A refused recording is attached with `.addSuppressed`.
   - A recording that itself throws is also attached with `.addSuppressed`, so the
     original is never replaced.
   - `seon.test.one-request-test/setup-that-throws-still-releases-the-member-branch`
     is red in run `186716b4493b`: two member/request branches are left in the roster,
     and the body took 42 s. The unfinished-runs note says that lane will retarget
     this regression. I did not prove whether the leftover branches predate the
     wiring.

## Contracts compile from zero

`(schema/build-projection (schema.edn/packaged-forms) {})`: `resolve-test`,
`destructive-paths`, `host-exclusions`, `invoked`, `invoke-producer` and `raw-output`
all compile to true (533 ms).

## Loop regression: still red, on a different owner

- Named runs `74c923e2af06` and `42d3721f4be0` both have pass 10, fail 1 (bound)
  and error 1 (reply wait).
- The worker's opening turn now runs and closes with no fault. No reply turn ever
  opens, even though `next-agent-work` answers `:open`.
- Filed as
  [issue-worker-opening-turn-closes-and-no-reply-turn-opens](../../../seon/issues/issue-worker-opening-turn-closes-and-no-reply-turn-opens.md).
  Its owner is `seon.turn` / `seon.cluster.agent`, outside this lane.
- The arming test (`3f9f2144a8aa`) fails the same way.
- `test/seon/namespace_agent_loop_test.clj` is unchanged. The temporary diagnostics
  were removed before commit.

## Change

- `src/seon/render.clj`: +3/−3.
- `src/seon/test.clj`: +85/−49.
- The commit id is in `git log`: "nsa-unblock: …".

## TIMINGS (operations over 1 s)

| Operation | Wall ms | What it is proportional to / verdict |
|---|---:|---|
| `bin/seon init --dev default --changed` (my paths) | 4,624–46,052; 3,495–42,528 refused with "Source changed during development adoption", "source head changed before publication" or "Branch head changed before force-branch!", because other lanes were adopting concurrently | whole-program `refresh-source!`. Defect over 10 s, owned by the save-gate lane (not fixed here, per spec) |
| named `seon.namespace-agent-loop-test` | 37,829–60,292 | body 25–28 s, which is the 20 s reply-wait backstop after the turn defect above; the rest is selection/admission. Defect, filed |
| incremental `--changed seon.issue/start!` | 127,112 | about 208 selected members executed in process, 119 of them long-excluded. Defect over 10 s. Selection itself is no longer the cost: `host-exclusions` is under 0.2 s warm |
| incremental over all 11 changed functions | 125,435 | request bound reached (1 pending) with 9 executed. Defect over 10 s. Same owner as the run-cost notes |
| `setup-that-throws…` named | 67,446 | body 42 s against its 15 s bound. Defect, owned by the unfinished-runs lane |
| arming test named | 45,472 | 20 s reply backstop. Same filed defect |
| `destructive-reach` cold (first call) | 1,818 | `gate-sets` whole-program identity + declared-edge reads, once per database value. 63 ms warm |

## Limits

- Foreign reds at this HEAD, not from this lane:
  - `:seon.agent/branch` required on `settings-owner` (my.agent-test);
  - host-bound acquisition refusals, because the loaded program is behind the rows
    (the sci/eval lane);
  - the my.background-test receipt.
- An acquisition of C once failed with "Program acquisition could not record its
  refusals":
  - The recording transacted on C's connection under the member branch's custody,
    which is refused as a foreign-branch write (`seon.sci.eval/record-acquisition-refusals!`).
  - This was seen once and depends on the host-bound refusal above.
  - It belongs to the sci/eval lane and is noted here only.
- Swallowing catches left in `src/seon/test.clj`:
  - `resolve-test`'s `LinkageError`/`Exception` catches keep only `ex-message`;
  - `member-result`'s `(failed (:seon.error/message test-var))` drops the refusal's
    evidence.
  - Both need a declared result member that carries the refusal. I did not change them.
- The `bin/seon init` response once failed to read ("No reader function for tag
  object", `boot.clj:525`) while the adoption had succeeded. That is a boot response
  printing defect and has not been filed.
- RESET NEEDED: no.
