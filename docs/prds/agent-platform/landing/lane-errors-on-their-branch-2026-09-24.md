---
type: landing
status: partial — items 1, 3 (MCP) and three follow-ups landed in owned files; items 2, 3 (agent entrance) and 4 need foreign owners; install pending RESET NEEDED
lane: errors-on-their-branch
created: 2026-09-24
requirements: docs/research/agent-platform/language-requirements-2026-09-24.md §8
---

# Lane errors-on-their-branch (2026-09-24)

Owner: "All errors then just get logged to the database in the right branches and we
don't have to worry about routing them."

What exists: every error writer already transacts `seon.error/recording` tx-data on a
connection its caller chose; `commit-call` runs inside that transaction and can read the
transacting database's own branch (`(get-in database [:config :branch])`). The approach:
record that branch on the occurrence at the writer, and derive each evaluation's error
view by a basis-window query of the evaluated branch. There is no cursor and no routing.

## Broken or unproven, first

- **RESET NEEDED to install.** `default` was restarted at 16:02:59Z. That is before
  `378f2d77c`, and it has no `seon.error/occurred-since`. It holds `8e7721d0c` at most.
  Only the MCP bridge (`script/seon/dev/mcp.clj`) is live, because it auto-reloads. Until
  default loads `378f2d77c` and `f65e906d1`, every `eval_clj` response carries
  `{:seon.dev.mcp/branch-errors {:seon.dev.mcp/window :unknown :seon.error/message
  "This JVM's seon.error has no occurred-since (RESET NEEDED)." …}}`. That is the typed
  unknown, never a silent empty.
- **Over the size bar.** Source added across the four commits is +166/−57 (net +109).
  `378f2d77c` alone adds +107/−31 source: `occurred-since` is +47 and the bridge window
  +60/−31. The window form is the larger part, because the bridge must name its own
  unknowns (no connection, old JVM, refusal) instead of passing a silent empty. The
  smaller alternative is a JVM-side `seon.error/window` taking the branch name, which
  would move ~15 lines out of the bridge. It was not done, because the live JVM could not
  load it for proof.
- **`378f2d77c` on its own fails its own resolution assertion.** It used a plain `not`,
  which Datahike evaluates wrongly: see the new issue
  `docs/seon/issues/a-negation-predicate-loses-a-transaction-position-variable.md`.
  `f65e906d1` switches to `not-join`. HEAD is correct.
- **Writes to another branch's connection are refused by custody**
  (`seon.db/foreign-connection-error`, `db.clj:466-500`). This means:
  - the item-2 patch must wrap each target's commit in `db/call-with-custody` for that
    target connection;
  - nested `with-database` fixtures cannot write their parent's branch. The regression
    therefore uses sequential fixtures.
- **Item 2 is not landed: core faults are not yet copied to the cluster branch.** The one
  writer that transacts core faults is `seon.fault/record!`, and `fault.clj` is held by
  fix-fault-route. The exact change is in `tmp/errors-on-their-branch/fault-two-targets.patch`
  (unapplied). It **does not load as written**, for this reason:
  - `seon.cluster.store` requires `seon.fault`;
  - `seon.cluster.registry` requires `seon.cluster.store`;
  - so `seon.fault` cannot require the registry. The registry owns both `cluster-branch`
    (`registry.clj:95`) and `active-branch-connection` (`:169`).
  The patch needs two seams reachable from `seon.fault`:
  1. a branch → active connection lookup keyed from a database value's own `:config`
     (the three lines of `active-branch-connection`), in `seon.db`;
  2. the pure `cluster-branch` derivation, moved to a leaf namespace (for example
     `seon.config`), with `registry/cluster-branch` retired in favour of it.
  The patch's logic:
  - origin = the tagged agent's `:seon.agent/branch`, else the connection's own branch;
  - commit on the origin branch;
  - when the origin is not the cluster branch, commit a second recording on the cluster
    branch with `:seon.error.occurrence/branch origin`.
  `error.clj` already supports the second target (commit `8e7721d0c`). The regression
  covers it through the same writer path.
- **Why errors land on the cluster branch today (verified).**
  - All Flow proc faults, including those in agent graphs, go through the one per-cluster
    error fan-out. It commits with the cluster connection (`cluster.clj:3512-3521`,
    `flow.clj` `join-error-fanout!`). So a platform bug hit by an agent whose
    `:seon.agent/branch` is isolated is recorded only on the cluster branch, with no origin.
  - `:record`-mode contract refusals commit through the arm-time
    `:seon.flow/commit-fault!` (`instrument.clj:718`), which is the cluster's.
  - Not the cause: a branch context's environment connection. In the live probe below, the
    retained context for branch `errors-on-their-branch-a` carried a handle, environment
    and SCI custody connection that all held `:errors-on-their-branch-a`.
  - Also correct already: acquisition refusals commit on the context's custody connection
    (`sci/eval.clj:1864`).
- **Item 3 for Seon agents is not landed.** The agent entrance's evaluation response is
  rendered in `render/transcript.clj` (not owned). The exact change: when rendering an
  evaluation, append `(seon.error/occurred-since (as-of db this-basis) previous-basis)`,
  where both bases are the `:seon.cluster.eval/read-basis-transaction` of this and the
  previous evaluation (`transcript.clj:281` already reads it as `::read-basis`). That is a
  pure render derivation over stored bases.
- **Item 4 is not landed; it is `boot.clj`.** The only place a probe's throwable reaches
  `default` is the sole-live-instance fallback in `record-uncaught!`
  (`boot.clj:344-346`). The exact change (recommended):

  ```clojure
  -        environment (or (carried-environment throwable)
  -                        (when (= 1 (count instances))
  -                          (env/of (:seon.sci.eval/ctx (first instances)))))]
  +        environment (carried-environment throwable)]
  ```

  - An uncarried throwable then takes the existing `uncaught-emergency!` path: it is printed
    loudly with every cause and not stored. That is what already happens with two live
    instances.
  - Sacrifice: a genuine platform throwable on a thread with no carried world (a library
    worker) is printed but not stored.
  - The alternative keeps that recording. The MCP JVM form binds a marker Var that
    `record-uncaught!` reads before its fallback. It depends on binding conveyance
    surviving to the uncaught dispatch on pooled threads, which is unverified.
  - The probe's own synchronous throw already returns to its caller through the prepl.
- **Not solved: `:record`-mode contract refusals from SCI code on a branch** still commit
  through the cluster's arm-time recorder (`instrument.clj:718`). Under the dev default
  `:panic` they throw to the evaluating caller instead.

## What landed

| commit | change | lines |
|---|---|---|
| `8e7721d0c` | `commit-call` stamps `:seon.error.occurrence/branch`: the request's origin when given, else the transacting database's own branch. A copy from another branch adds its origin to the occurrence identity, so it never merges with the branch's own occurrence. The attribute is declared, optional for accretion (`seon.error.occurrence.edn`), and a declared optional request member (`seon.error.edn` `:commit-tx-request`). | +15/−2 |
| `378f2d77c` | `seon.error/occurred-since db since`: the unresolved errors whose occurrence was written in (since, basis of `db`], one entry per signature. The count is the summed cumulative counts; the branch set shows any origins. The MCP bridge deletes the `:seon.dev.mcp/new-faults` per-session cursor (`e868e5ec5`) and its discovery hook. `eval_clj` now appends `:seon.dev.mcp/branch-errors`, computed on the evaluation's own prepl session. Adds regression `seon.error.branch-test/an-error-is-seen-on-its-own-branch-once-per-window`. | +182/−31 (src +107/−31) |
| `931a45005` | The bridge's SCI evaluation form and branch tool read `seon.operator.runtime/running-instances` directly, not through `seon.cluster`'s `:refer`. Verified live: `eval_clj` in SCI with and without a branch answered `42`, and `branch list` answered. | +2/−3 |
| `f65e906d1` | The first-ever delivery gate (`error.clj:1393`) becomes **once per opening**. `opening` is 0 for a signature's first occurrence, or the resolution's tx id when a resolution's `:db/txInstant` is at or after the latest occurrence (`seon.error/resolved?`, the `seon.problems/open-faults` rule). Otherwise it is nil and nothing is delivered. A re-opening's message id carries the resolution tx, so the recipient wakes again. `recurrence` (the sum read) is deleted. `render-html` shows "Resolved" only when the resolution follows the latest occurrence; otherwise "Open: recurred after its resolution", and "Resolution time unknown" when it has no database. `occurred-since` now uses `not-join`. `error_test.clj:153` passes the unit with its database. Adds regression `a-fault-is-delivered-and-shown-open-once-per-opening`. | +127/−22 (src +42/−21) |

**Why the gate became an opening instead of being deleted.** Message identity is
`[signature recipient]`, and its content is identical across occurrences. Deleting the
gate would therefore rewrite the same message. It would not re-deliver after a resolution.
And if the content ever varied, it would wake the recipient on every occurrence: the storm
that `the-storm-is-bounded-by-the-signature-count` guards against. The derived opening
gives exactly one delivery per opening.

## Semantics (root clarification, stated)

- **Window.** An evaluation on branch X shows the errors whose occurrence write lies in
  (previous evaluation's basis on X, this evaluation's basis on X]. Each occurrence write is
  shown exactly once, with no seen-flag. A recurrence rewrites its occurrence (`last-at`,
  `count`), so it lands in a later window and shows again.
- **Keys.** The MCP bridge keeps one basis value per `[root cluster branch]`. The shared
  cluster context, which covers JVM mode and SCI without a branch, is its own key (`nil`).
  It is a value, not a queue, and it advances only on a successful window read.
- **Bridge restart over-shows.** After a restart the bridge holds no basis, so the next
  window starts at 0. It shows every currently unresolved error on that branch. It may
  over-show; it never under-shows.
- **Bounds.** Errors are grouped by signature with a count. The list is bounded once at 20
  entries, with `:seon.dev.mcp/count` and `:seon.dev.mcp/requery`.
- **Not repeated.** Still-open older faults are not repeated in each response. They belong
  to the opening, the agent's context and `runtime_status`.
- **Cluster branch.** Its window includes core-fault copies from other branches, whose
  `:seon.error.occurrence/branch` names the origin, once item 2 lands.

## Evidence

- **Root cause of "lands on the cluster branch"** (live, old JVM pid 90471):
  - a retained branch context for `errors-on-their-branch-a` carried
    `:errors-on-their-branch-a` on its handle, environment and SCI custody connection;
  - Flow faults commit through the per-cluster fan-out's cluster connection
    (`cluster.clj:3512-3521`).
- **Window query on default's db** (scratch namespace, JVM mode):
  - 16 signatures from 0 in 3.1-3.4 ms;
  - a window from the current basis returns `[]`;
  - on branch A, a probe occurrence written there is seen on A (count 1) and not on
    default.
- **Bridge, live against the real JVM and branch A**
  (`tmp/errors-on-their-branch/bridge-probe.clj`, bb). The probe process swaps the resolved
  symbol for the identical scratch copy, because the old JVM lacks `occurred-since`:

  | step | result |
  |---|---|
  | bridge restart (no basis) | over-shows 17 on A, 16 on the shared cluster key |
  | next evaluation on A and on shared | nil (shown once) |
  | a recurrence written on A | re-enters A only: count 1, `probe` |
  | shared cluster key after the recurrence | nil |

- **Bridge cost** (`cost-probe.clj`, 5 runs each):
  - `eval_clj` before: 85.2-87.5 ms;
  - after, with the window on the same session: 89.5-92.3 ms (≈ +4 ms, about 5%);
  - the first window after a restart: 155 ms (it reads the 17 errors);
  - a separate one-shot session had cost +78 ms, of which 73 ms is `read-clj-endpoint`
    discovery. That is why the window reuses the evaluation's session.
- **Snapshot proof.** A `git archive` of HEAD (09:37) plus this diff, with
  `reference-code` and `.cpcache` linked, booted from zero in 159 s ready (existing
  `from-zero-boot-takes-minutes`). Contracts were armed at boot:
  - `bin/test-check --root <snap> default --ns seon.error.branch-test`: run
    `602e5b0cca40`, 2 executed, 17 pass / 0 fail, 11,128 ms.
  - `--ns seon.error-test`: run `fe7c23325568`, 16 red. On default, run `04e5e5f50044`
    had the same 16 plus 2 more (`recording-refuses-an-unavailable-complete-observation`,
    `recurrence-identity-uses-declared-schema-…`). No red is new with this diff. The
    shared reds are foreign fixture failures ("A fixture agent needs exactly one cluster",
    "An identity-bearing entity schema must declare its partition"). Default runs a newer
    HEAD than the snapshot, so the comparison is approximate.
  - `--ns seon.dev.mcp-bridge-test`: run `dc212e5184f3`, 26 executed, 162 pass / 2 fail.
    Both fails are in `a-lane-owns-its-branch-create-define-call-retire`: the entry gate
    refuses an uncontracted `defn`. That test is red on default too, so it is foreign.
  - The snapshot bridge window showed live, on new code: `{:seon.error.occurrence/branch
    ["cluster-default"] … "DEEPSEEK_API_KEY is not set."}`. After four probe writes it
    showed one grouped entry with count 4 and `since` advanced.
- **Snapshot, unrelated.** `bin/seon status` failed with `seon.cluster.boot/readable-response
  refused return value at [:seon.error/at]`. A restart over a kept store refused with
  "Changed paths lie outside the JVM's source directory" (`reference-code/malli` symlink),
  and then paniced "no live cluster database to record this failure in". Both are foreign.
- **Fault write cost** (root's request). Snapshot, 4 writes, `seon.flow/exception-error`:

  | step | time |
  |---|---|
  | `error/recording` (pre-transaction) | 16-36 ms |
  | `commit-call` | 4-6 ms (unarmed after a reload) |
  | `db/transact!` | 162-347 ms |

  Of 1,036 ms of `transact!`, the armed children are ~60 ms (`write-report-error` 59 ms,
  `write-owned-values-error` 32 ms). The rest is inside `seon.db/transact-call`, which is
  Datahike's write and commit.
  - On live default's branch A, one write took 2,369 ms: `write-owned-values-error` 593 ms,
    and `commit-call` only 65 ms.
  - The cost is not in `error.clj`. Owner: `db.clj` (lane fix-read-replay) and the Datahike
    writer.

## Item 3 for Seon agents: exact change (not owned)

In `src/seon/render/transcript.clj`, where an evaluation unit is rendered (`:281` reads
`::read-basis`), append
`(seon.error/occurred-since (as-of db this-read-basis) previous-evaluation-read-basis)`.
When the agent has no previous evaluation, use 0. This follows the same once-per-window
semantics and is derived from the stored `:seon.cluster.eval/read-basis-transaction`, with
no cursor.

## Commits and released paths

Commits: `8e7721d0c`, `378f2d77c`, `931a45005`, `f65e906d1`. Tests +127 lines. Source
+166/−57.

Released paths:
- `src/seon/error.clj`
- `script/seon/dev/mcp.clj`
- `resources/seon/schemas/seon.error.edn`
- `resources/seon/schemas/seon.error.occurrence.edn`
- `test/seon/error/branch_test.clj`
- `test/seon/error_test.clj`
- this note

The occurrence attribute lives in `seon.error.occurrence.edn`, not `seon.error.edn`. This
lane edited it as the occurrence-attributes file.

Cleanup:
- branch `errors-on-their-branch-a` retired;
- snapshot JVM stopped and its root deleted, after unlinking its symlinks first;
- probes and the unapplied `fault.clj` patch kept in `tmp/errors-on-their-branch/`.
