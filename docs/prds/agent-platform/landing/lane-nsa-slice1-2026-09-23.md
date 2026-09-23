---
type: landing
status: source landed; loop regression RED on a foreign render contract defect
lane: nsa-slice1
plan: docs/prds/agent-platform/plan/lane-namespace-agents-first-loop.md (slice 1, "Start one worker on its branch")
created: 2026-09-23
---

# Slice 1: an issue worker starts on its candidate branch

## Design, stated before the code

**Seams (HEAD `7e5cdea9e`).**

| Seam | What it already does | What was missing |
|---|---|---|
| `src/seon/cluster/agent.clj:183` `creation-tx` | takes an optional `:seon.agent/branch`, which `:seon.agent/creation-request` declares (`seon.agent.edn:85`). Without one it defaults to `registry/cluster-branch` | nothing |
| `src/seon/issue.clj` `create-tx` / `start-tx` / `start!` | create one worker, its plan and opening in one writer call. Resume only with a larger budget; any other repeat refuses (`:seon.issue/assigned-issue-id`) | did not pass the branch through, so a worker started on C got `:cluster-<name>` |
| `agent.clj:783` `acquire-context!` | reads the agent row's branch. If that branch is the handle connection's own branch it is live (it borrows the connection, no new branch). Otherwise it creates or selects another branch | nothing. With the wrong branch on the row, `arm!` built a third branch off C |
| `agent.clj:937` `arm!`, `cluster/wake.clj:438` `route!` | arm exactly one agent id into the supplied routing atom. `route!` registers one connection-local handler | nothing. The test arms one worker and never starts an armer graph |

**What each operation costs.**
- `start!` is one writer transaction over one issue, one agent, one plan, one opening and
  the issue's own test refs. It never scans a detector.
- The added code is one `select-keys` per call: O(1).
- Acquiring C is a branch pointer plus open plus fork (README §7 Candidate shape).
- Arming uses C's own connection, so it adds no acquisition.

**Simplest alternative considered.** Derive the worker's branch from the writer's
database, `(get-in database [:config :branch])`, in every call. I rejected it because it
changes every existing start: a fixture-branch start would get the fixture's branch
instead of `:cluster-<name>`. Making that the default is a later ruling. The slice
threads the key the spec names, and creation already accepts it. It adds no machinery.

## Change

- `src/seon/issue.clj`, +12/−3:
  - `start!`, `start-tx` and `create-tx` declare an optional
    `[:seon.agent/branch {:optional true} :seon.agent/branch]`.
  - `create-tx` merges `(select-keys request [:seon.agent/branch])` into its `creation-tx`
    request.
  - The docstrings say that a resume keeps the worker's existing branch.
- `test/seon/namespace_agent_loop_test.clj`, new, 104 lines:
  `an-issue-worker-started-on-its-candidate-branch-runs-there-alone`.
  1. Seed H.
  2. Acquire C with `acquire-context! … {:seon.agent/isolate? true}`, released through
     `closeable`.
  3. Author the issue on C under C's custody and call `start!` with C's branch.
  4. Repeat the start. It must refuse.
  5. `route!` on C's connection, then `arm!` the worker only.
  6. Wait for the no-provider reply. Assert evaluations on C and no fault.
  7. Assert H's commit id and definition digests unchanged.
- Line budget: I was over. The source is 12 added lines, within 15–25. The test is 104,
  against a budget of 50–60. About 35 of those lines are the launcher/handle/route
  scaffolding that `issue_test.clj:263` and `agent_arming_test.clj:32` each copy. A shared
  helper would belong in `test/seon/test_support.clj`, which this lane does not hold. The
  orchestrator should queue it: extract `running-cluster` into test-support and convert
  its three callers.

## Evidence (default, pid 90963, from the checkout)

- **Adoption:**
  - `bin/seon init --dev default --changed src/seon/issue.clj test/seon/namespace_agent_loop_test.clj`
    returned commit `6ab350b6…` in 13,164 ms. Of that, `full-source-refresh!` took
    10,014 ms and `fn/index!` 9,944 ms.
  - Re-adopting the test file alone took 6,000, 10,884, 12,238, 24,105 and 5,946 ms.
  - The loaded Var carries the key: `(re-find #":seon.agent/branch" (pr-str (:malli/schema (meta #'seon.issue/start!))))`
    returned true.
  - These costs follow the whole program, not one changed file. The timing rows are for
    the orchestrator's `a-three-file-changed-path-publication-takes-a-minute.md`. Lanes do
    not append shared notes (ledger line 60).
- **Contracts compile from zero:** `(schema/build-projection (schema.edn/packaged-forms) {})`
  compiles `start!`, `start-tx` and `create-tx` to true, and validates
  `{:seon.agent/branch :agent-abc}`. 485 ms.
- **Named run `3f8d8b015c51`** (`bin/test-check default --policy named --ns seon.namespace-agent-loop-test`,
  43,191 ms wall):
  - Executed 1, pass 10, fail 1, error 1.
  - Green:
    - `start!` returns the worker.
    - The repeated start refuses with `:seon.issue/assigned-issue-id`.
    - Only the new worker is armed, although C carries more than one inherited agent.
    - The worker's row carries C's branch.
    - The armed execution holds C's connection, so no third branch was made.
    - H's commit id is unchanged, which covers worker, issue, evaluation, effect and fault
      facts.
    - H's definition digests are unchanged.
  - Red: the reply wait timed out (backstop about 20 s; body 25,564 ms against a 5,000 ms
    bound).
- **Cause of the red, observed from the fault channel in diagnostic run `de45b09588a4`:**
  - Every fault is on the worker's `:seon.agent/turn` proc:
    `seon.render/invoked refused return value … expected a string, got a map … (render.clj:1242)`.
  - Stack, innermost first: `render/raw-output:1242` ← `render/render-call:1521` ←
    `render.walk/neighborhood:816` ← `turn/declared-sources:1855` ← `turn/system-turn:2068`
    ← `turn/open-turn:4238`.
  - The offending value is `#:seon.render{:source-blocks [...]}`: the issue opening's
    generated reads.
  - JVM probe:

    ```clojure
    (m/validate :seon.render/rendered v)      ; false
    (m/validate :seon.render/source-blocks v) ; true
    (m/validate :seon.render/error-result v)  ; false
    ```

  - So `invoked` (`render.clj:1064`), `invoke-producer` and `raw-output` declare
    `[:or :seon.render/rendered :seon.render/error-result]`. That union omits the
    `:seon.render/source-blocks` shape that `render-call:1517-1550` handles, and
    `seon.render.edn:179` `:rendered` is `[:or :string :seon.render/hiccup :seon.render/form]`.
  - Any issue worker's opening therefore kills its turn graph at HEAD.
  - `seon.cluster.agent-arming-test/starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers`
    was red the same way (timeout) in run `b061797c5836`. I did not capture its fault, so
    I have not proved it has the same cause.
- **Proposed owner fix, not made (`src/seon/render.clj` is outside this lane):** add
  `:seon.render/source-blocks` to the output union of `invoked`, `invoke-producer` and
  `raw-output`. The other option is to add it to `:seon.render/rendered`, if every
  consumer of that union already accepts it. Then rerun this test.
- **Incremental run: unavailable.**
  - `bin/test-check default --policy incremental --changed seon.issue/start!` printed only
    `check unavailable: PREPL evaluation failed.` after 32.4 s. `bin/test-check:96` keeps
    only `ex-message`, which swallows the error.
  - The same request through MCP JVM ran for 35,488 ms and threw
    `seon.test/host-exclusions refused return value at [:seon.test/destructive-excluded 16 :seon.test/destructive-path]: expected a vector, got nil (test.clj:1650)`.
  - Profile: `host-exclusions` 24,305 ms ×2, 19,535 `seon.db/pull` calls (23,218 ms),
    `select` 18,605 ms.
  - The reaching-set count is unknown. It is not green.

## TIMINGS (every operation over 1 s)

| Operation | Wall ms | What the time follows / verdict |
|---|---:|---|
| adoption, both files | 13,164 | `full-source-refresh!` 10,014 + `fn/index!` 9,944. Follows the whole program for 2 files. Defect over 10 s, for the publication issue |
| adoption, test only (×5) | 5,946–24,105 | same path. Defect |
| named run `3f8d8b015c51` | 43,191 | body 25,564, mostly the reply wait's backstop after an early fault. About 18 s is selection/admission outside the body. Defect over 10 s |
| earlier named runs (custody fix, diagnostics) | 20,104–71,691 | same. One diagnostic printed a whole fault and hit `OutOfMemoryError: Requested array size exceeds VM limit` in the test body. JVM survived (pid 90963 alive after) |
| incremental `--changed seon.issue/start!` | 32,410 (CLI) / 35,488 (MCP) | `host-exclusions` does a pull per member (19,535 pulls). Defect, then refuses |
| arming-test and issue-test reference runs | 33,616 / 14,966 | foreign reds at HEAD. Not caused by this slice |
| packaged contract compile | 485 | sub-second |

## Limits

- The virtual ordinary reply was not observed, so "evaluation facts stay on C" is shown
  by absence: H's commit id is unchanged. I did not see a positive evaluation row on C.
  Effect and fault facts are shown the same way, by H's unchanged commit.
- I did not run the red-before check by adopting the parent `issue.clj`. By reading:
  without the key the row carries `:cluster-<name>`, and `arm!` would create another
  branch. That fails both branch assertions.
- Not a hot path (issue writer request, not `transact!`), so there is no parent/child
  probe timing.
- RESET NEEDED: no.
