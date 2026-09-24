---
type: landing
status: landed (S1a aed1b3838, S1b d6e93ce72, test fix dbbea3113); verification partial, load-paused
lane: harness-s1-attempt-release
created: 2026-09-24
issue: docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §1 rows "graphs"/"cleanup", §9c S1
---

# Lane harness-s1-attempt-release — landing (2026-09-24)

**What exists.** `acquire-context!` returned a handle with no routing, and `arm!` took an
independent routing atom. So a fixture's `release-context!` closed and unlinked its branch while
graphs a test had armed were still stepping over it. That is the issue's
`ClosedChannelException`. Now the handle owns its routing. Release marks that routing released,
under the same monitor `arm!` takes. It then disarms (stops and joins) every graph in it,
attempting each one. Only after all of them exit does it close, unlink and forget.

## Broken things first

- **RESTART NEEDED (`bin/seon down && bin/seon start`, root).** Live default booted before S1a,
  so its loop handle has no `:seon.agent/routing`. Reload does not rebuild that handle.
  - With the new `arm!`, a live agent *created* before the restart gets a fresh routing from
    acquisition, and its arm refuses: "routing is not the one the agent's execution owns".
  - Root was already armed, so the early return keeps it running.
  - `cluster.clj:3606` now puts the routing on the handle at boot.
- **Two graphs leaked into default by my counter-proof.** I temporarily removed the
  `stop-owned-graphs!` call to show the regression fails without it (run 6440bc35b505).
  - Agents "idle" and "stuck" stayed armed on the unlinked branch `:agent-28ccfa9a169c`. Their
    routing atom was local to that test run and is now unreachable. That is the defect itself,
    reproduced.
  - Their provider stand-in was released, so they should be parked. A restart clears them.
  - The same run hit `OutOfMemoryError: Required array length 2147483638` while *printing* a
    failed assertion on a routing entry. That is the fix-unbounded-printed-values class
    (runner/report printing). I did not file a new issue.
- **My first save published a half edit.** The save hook published the `arm!` identity check
  before callers were converted (≈20:44Z). That contaminated my "before" run d680ac9e917a. The
  baseline below comes from recorded results as of 20:35Z instead.
- **The shared JVM was in heavy churn.** It showed "Node not found in storage", "the blob store
  went away", adoption-basis races, and host-bound refusals from other lanes' `db.clj`,
  `test.clj`, `runner.clj` and `sci/eval.clj` edits. Some runs failed for these reasons, not
  because of this change.

## Evidence

- **Regression** `seon.cluster.agent-test/release-joins-every-owned-graph-before-it-closes-the-branch`:
  - Fixed: pass 10/0/0 (run 35fe46785b27, 12.4 s request). It passed again in run a46db7722e5f.
  - Without the fix (run 6440bc35b505): 4 fail, 3 error. The connection was released
    ("Connection has been released."), the branch was unlinked from the roster, the stuck graph
    stayed armed, `arm!` still admitted a new graph, and the executor never terminated.
  - What it proves: an isolated attempt with two armed graphs, one blocked in an uninterruptible
    prelude. Release refuses; the idle graph is still joined. The stuck graph keeps its routing,
    the connection stays readable, and the branch stays in the roster. A new arm refuses by name
    (`:seon.error/expected :seon.agent/routing`). After exit, a second release finishes and
    unlinks.
- **Focused callers (run a46db7722e5f).** All of these passed:
  - the regression;
  - `disarm-has-a-declared-loud-turn-completion-backstop` and
    `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted`;
  - `disarm-drops-the-route-before-closing-it` and `fenced-is-the-derived-quarantine-state`;
  - `a-function-without-a-contract-never-enters-the-program`,
    `canonical-agent-handle-supplies-declared-producer-outputs` and
    `a-refused-core-row-is-recorded-and-every-agent-still-arms`;
  - both `release-context-test` tests.

  Earlier, `seon.loop-proof-test/concurrent-arms-share-one-graph` passed too (run fdff6dd693fc).
- **Before/after over the 102 tests in the converted caller namespaces.** Baseline is recorded
  results as of 20:35Z; "after" is current recorded results.

  | before → after | tests |
  |---|---:|
  | pass → pass | 22 |
  | red → red | 48 |
  | red → pass | 1 |
  | unknown → pass | 5 |
  | pass → red | 1 |
  | unknown → red | 1 |
  | red / pass → unknown | 14 |
  | unknown → unknown | 10 |

  - The one pass → red is `rereads-test/generated-read-check…`. Its cause is "Cannot interpret
    seon.db/carried-projection: Host-bound declaration…", which comes from a foreign `db.clj`
    edit in flight.
  - The one unknown → red is `concurrency-test/messages-stay-on-their-connection`, a fixture
    custody refusal ("This write names branch …") before any arm.
  - No red in any run carries either new refusal ("routing is not the one…" or "released this
    routing"): 0 matches in `tmp/harness-s1-attempt-release/after-3.txt`.
  - The remaining reds match the baseline classes: hand-built agent rows without
    `:seon.agent/branch`, `:seon.program/definition-digest` fixtures, and duration overruns
    under load.
- **Installed.**
  - `bin/seon init --dev default --changed` adopted S1a at commit 6ab58e5f and S1b at 6ab58f99.
  - Earlier probes: the installed execution handle lacked routing and the arm request required
    one.
  - The loaded `disarm!` line moved to 1173 after reload, so the JVM runs the edited file.
- Raw outputs are in `tmp/harness-s1-attempt-release/`:
  - before.txt (contaminated), after.txt, after-2.txt, after-3.txt;
  - after-regression.txt, without-fix.txt;
  - init-s1a.txt, init-s1b.txt.

## Change

- **S1a aed1b3838** (+63 / −38 over 18 files):
  - `acquire-context!` gives each handle a routing. A handle that owns its acquisition gets a
    fresh one; a reacquisition takes its previous or branch owner's; a live acquisition takes
    its source handle's.
  - `arm!` refuses a routing its execution does not own.
  - Schema: required on the execution handle, optional on the context source.
  - The cluster handle carries the cluster's routing.
  - New `test-support/attempt-routing`; `cluster-handle` defaults to it.
  - Converted callers:
    - 13 `(agent/routing)` sites, in agent_call_edges, concurrency, program, run4_install,
      loop_proof ×3, rereads, turn_continue, help_trial, turn, namespace_agent_loop,
      cluster/agent_arming, render/web_debug and issue;
    - `cluster/agent_test`'s `armory`, whose 17 calls now take the connection;
    - the three `oversight_test` sites are left alone: they build routing values for the
      reader, not for arming.
- **S1b d6e93ce72** (+100 / −12):
  - `stop-owned-graphs!` and `throw-causes!` are new.
  - `release-context!` stops owned graphs first.
  - `arm!` refuses a released routing.
  - The regression is added.
- **dbbea3113** (+2 / −1): the regression's child-branch writes run under that branch's custody.
- Net source lines: `agent.clj` +57/−12, `cluster.clj` +5/−2, schema +3/−1.

## Verification limits and remaining commands (root, quiet window)

- Hook publication is paused and no further runs were started. These are still to run:
  - `bin/test-check default --test seon.cluster.agent-test/release-joins-every-owned-graph-before-it-closes-the-branch --include-long`
  - `bin/test-check default --ns seon.cluster.agent-test --ns seon.cluster.release-context-test --ns seon.loop-proof-test --include-long --time-limit-ms 590000`
  - `bin/test-check default --ns seon.turn-test --ns seon.turn-continue-test --ns seon.issue-test --ns seon.cluster.agent-arming-test --ns seon.namespace-agent-loop-test --include-long --time-limit-ms 590000`
  - `bin/test-check default --ns seon.rereads-test --ns seon.rereads-panel-test --ns seon.run4-install-test --ns seon.render.web-debug-test --ns seon.help-trial-test --ns seon.concurrency-test --ns seon.agent-call-edges-test --test seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities --include-long --time-limit-ms 590000`
- The armed_test platform tests exercise boot arming (the handle routing at cluster.clj:3606):
  - `bin/test --platform -- seon.cluster.armed-test` (root only).
- Probes not committed as regressions:
  - **Setup failure.** The existing `acquire-context!` catch releases and retires on failure; the
    handle never exists, so there is no routing to leak. Only the source was read; I ran no probe.
  - **Concurrent arm/release.** This is covered by the shared monitor plus the released refusal
    asserted in the regression. There is no racing-thread test.
  - **Nested acquisition.** A child's release does not stop the parent's graphs, because an
    isolated child gets a fresh routing. This is asserted by construction, not tested.
- A `with-database` fresh-store connection (`:fresh-store?`) resolves to the member's routing,
  so its graphs are joined at member release, after the fresh store is already deleted. This is
  the same class as before; tests disarm in `finally`.
- No bulk or platform tier was run.
