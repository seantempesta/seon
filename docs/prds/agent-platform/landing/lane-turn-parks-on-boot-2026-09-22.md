---
type: landing
status: owner fixes; boot regression blocked by stale published fixture
created: 2026-09-22
tags: [agent-platform, turn, wake, evidence]
---

# Root parks after boot

RESTART NEEDED. Default was never stopped, reset, restarted or re-armed by this
assignment. A reachable proc is not proof of a working loop. PID 15000 answered
status with four turn passes and zero episode runs. The existing park remains.

## Live diagnosis

All MCP probes used root `/Users/sean/src/seon`, cluster `default`, JVM mode and
`read_only true`. The first probe used `@connection` and received the explicit
missing-projection refusal; subsequent probes used `(seon.db/db connection)`.
No claim is based on the malformed `latest-evaluations` result from that first
refusal. Root's nine saved latest forms, including error evaluation
`72020342e942`, individually returned no generated-read fault. Saved evidence
is not the newly generated opening's evidence.

The current declared error form is 109,584 characters, comes from
`[[:seon.agent/id "root"] :seon.error/of-steward]`, and has no
`:seon.eval/origin`. `issue-origin-read?` therefore returns nil.
The actual SCI preview completed in 1,593 ms, returned no evaluation error,
and the existing check returned exactly
`#{:seon.cluster.eval/source :seon.turn/rule}`. Its 419 reads include five
explicit error observation pulls at indices 414–418. Their dependency revisions
each name those two attributes; their complete patterns constrain them to
error occurrence entities, not turn entities:

| Read | Error signature prefix | Occurrence eid |
|---|---|---|
| 414 | `acf9446c` | 37732 |
| 415 | `1d453691` | 37727 |
| 416 | `a1b91220` | 291035 |
| 417 | `5990fdc8` | 37735 |
| 418 | `c7a247ff` | 291032 |

The initial query's revision is `:all`; these five subsequent observation pulls
are what make the current check refuse. The complete raw MCP envelopes are
`tmp/turn-parks-live-evidence.json`; the full offending evidence remains in
default blob `37139bbc4116a0a885fab96cdb5dc48197d64822dba90c198f50c8e7f7138f5b`.
The small extracted result is blob
`32a700551d3dfab1bc9f27496f213ec5ca2cc8ef2fb078028dfa91c0295ba5e2`.

Exact reproduction form:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default")) handle (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default")) source (nth (:seon.turn/forms (#'seon.turn/declared-sources handle database "root" 'my.agents.root)) 6) result (#'seon.turn/preview-sources {:seon.turn.loop/cluster handle :seon.db/db database :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.agent/id "root" :seon.ns/name 'my.agents.root :seon.cluster.reply/text (:seon.cluster.eval/source source) :seon.sci.admit/caps (:seon.sci.admit/caps handle)}) evaluation (get-in result [:seon.turn.loop/evaluated-sources 0 :seon.sci.eval/evaluation])] {:error (:seon.cluster.eval/error evaluation) :reads (mapv (fn [r] {:inert (let [attrs (get-in r [:datahike.read/revision :datahike.read/attributes])] (if (= :all attrs) :all (vec (filter (seon.cluster.wake/inert-attributes database) attrs)))) :pattern-count (count (mapcat :seon.db/read-index-patterns (get-in r [:datahike.read/dependency-plan :datahike.query.dependency/sources])))}) (:seon.cluster.eval/read-evidence evaluation)) :fault (select-keys (#'seon.turn/generated-read-fault database source evaluation) [:seon.turn/generated-read-attributes])})
```

Exact extraction of the offending reads (result rows are summarized above):

```clojure
(let [connection (seon.cluster.boot/connection "default") rows (seon.render.value/artifact-value (seon.render.value/read-artifact (seon.blob/get connection "37139bbc4116a0a885fab96cdb5dc48197d64822dba90c198f50c8e7f7138f5b")))] (mapv (fn [r] {:index (:index r) :lookup (second (get-in r [:request :seon.db/pull-arguments])) :inert (vec (filter #{:seon.cluster.eval/source :seon.turn/rule} (:attributes r))) :patterns (filterv #(#{:seon.cluster.eval/source :seon.turn/rule} (:seon.db/pattern-attribute %)) (mapcat :seon.db/read-index-patterns (get-in r [:plan :datahike.query.dependency/sources])))}) rows))
```

The error selector is derived by `seon.error/observation-selector` from every
declared error facet. `:seon.fn/namespace-unresolvable-error` incorrectly used
the evaluation activity attribute as its diagnostic source; `:seon.turn/rule`
is a refusal diagnostic but was declared context-inert. The evidence owner
faithfully reported the selector. These declarations made an error observation
look like a turn-activity read. The repair uses existing `:seon.fn/source` in
the analysis error producer and facet, and removes the activity annotation from
the diagnostic rule. The turn check, refusal bound and retry behavior are unchanged.

There is a separate wake defect: `route!` accepts and registers a keyword key,
then passes it to `deliver!`, whose new contract required `:seon.agent/id`
(a string). Armed execution refuses before `offer!`. Fix the parameter at
`deliver!` to `:seon.cluster.wake/key`; the existing undeliverable-wake diagnostic
accepts that registration key while retaining its earlier string alternative.
No listener registration conversion or new delivery mechanism is needed.

## Dependency and cost boundary

Datahike pin `006e634ae955c186619adb5f3868cca29d8c97fb`:
`reference-code/datahike/src/datahike/core.cljc:200` accepts any opaque listener
key, registering it on the supplied connection. The writer dispatches the
committed report to listeners; wake's first-party caller compiles matchers at
registration/declaration change and offers by matching datom. Changing the
contract adds no query, index, cache, allocation or whole-program work.
The `reference-code/core.async` pin `dc35f3e0d7bc2eef502e77982f48641f025c8051` supplies nonblocking
`offer!` and the existing sliding buffers. Error observation selectors remain
cached by immutable projection; only their declared member population changes.
No performance improvement or memory reduction is claimed.

## Verification and limits

The canonical fixture, real SCI and armed contracts are used in
`seon.turn-boot-test`. One regression covers listened-datom delivery; one covers
root opening with a recorded namespace diagnostic, through `turn/step`, with
the no-provider configuration. The latter observes returned proc state, durable
system evaluations and the following open turn; it is not a browser or cold-boot
proof. Executor cleanup explicitly interrupts and joins its owned backstop.

Before-change `bin/test-fast --paths src/seon/cluster/wake.clj --
seon.cluster.wake-test` executed against HEAD `62c5f0eab`, snapshot `run.FStV6p`,
run id `897b0a5cf48f`. Mailbox delivery failed, render delivery failed, and the
contract identified keyword `:seon.agent/route` where it expected a string.
That broader namespace also has unrelated fixture/timeout failures; its final
recording timed out. This is execution evidence, not recorded green.

Focused admission with `bin/test-fast --paths test/seon/turn_boot_test.clj --
seon.turn-boot-test` used HEAD `5361d0dfb`, snapshot `run.N6qszX`, request
`7e005dceb25b`, and refused before executing: `Snapshot admission refused`,
`Read timed out` in `record-snapshot!`. Log: `tmp/turn-parks-before.log`.

The permitted scratch fallback used `with-database` and explicit cluster
custody with `seon.test/run`. It refused
`:seon.test/identity-unresolved` for the new regression. Diagnostic execution
through the armed `seon.test.runner/run-vars!` established the delivery failure
(three failed assertions) but is explicitly not admitted/recorded proof.
The root regression hit `:malli.core/invalid-schema` for
`:seon.turn/invalid-disposition-error`; its 8,534 ms duration also failed the
ordinary 5,000 ms bound. No bound was raised to turn that failure green.

Published fixture base:
`b771bf4fa00eea263a2b679e89aa58fce34471659c38a5c1e11c7c43786797e0`,
reported 24 commits behind HEAD. The orchestrator owns `--prepare-head-base`;
this assignment did not run it. Logs: `tmp/turn-parks-isolated-before-2.log`
and `tmp/turn-parks-isolated-after.log`. Root fail-before/pass-after and live
adoption remain outstanding; missing evidence is not a pass.

Foreign edits appeared in `src/seon/agent.clj`, `src/seon/db.clj`,
`src/seon/program.cljc`, `src/my/program.clj`, and later spans of `src/seon/fn.clj`.
They were preserved. Diagnostic work continued in detached HEAD worktree
`tmp/turn-parks-wt`, linked to the existing dependency checkouts, with only
this assignment's changes applied. The owned `fn.clj` hunk is at the analysis
error producer; foreign gate-selection hunks are excluded from the commit.
The two initial foreign dirty documents and every initial untracked file remain.

Hook publication was paused for the coordinated schema/consumer edit and restored
after those edits were together (approximately 08:30–08:38Z). Restoring the hook
does not prove publication or adoption. An initial test-only edit had queued
publication `284c7fb1-12aa-4cbc-abaf-6b2e9817a0aa`; its queue receipt is not
convergence evidence. Default was not modified through the REPL.

The first delivery assertion used `poll!` immediately after the transaction.
Inspection of the pinned writer corrected the old source/skill claim: promise
delivery precedes listener dispatch, and callbacks are independently caught.
The final regression awaits each named delivery (500 ms observation bound).
The source docstring and Datahike skill now describe this installed guarantee.

At 08:43Z the default read-only REPL still answered in 5 ms at basis 536895448;
its installed schema includes `:seon.turn/invalid-disposition-error`. The missing
schema is specifically the published fixture base, not default.

Final delivery class comparison used the identical named-event regression in
the isolated worktree, with production contracts armed (1,681 registered and
instrumented, 1,675 program-armable). With HEAD's `wake.clj`: 1 pass, 1 failure,
2 errors; the two delivery events timed out and the fault contained the exact
string-versus-keyword refusal. With the corrected owner: **4 passes, 0 failures,
0 errors**. Logs: `tmp/turn-parks-wake-before.log` (08:42:43Z) and
`tmp/turn-parks-wake-after.log` (PID 21263, projection acquired 08:43:48Z).
This is diagnostic `run-vars!` execution, not a recorded `seon.test/run` result.
Both JVMs exited; no paid provider was invoked. The earlier polling version's
counts are superseded by this identical-test comparison.

Owned paths are `src/seon/fn.clj` (analysis hunk only),
`src/seon/cluster/wake.clj`, the `seon.fn`, `seon.turn`, and
`seon.cluster.wake` schema resources, `test/seon/turn_boot_test.clj`,
`test/seon/cluster/wake_test.clj`, `.agents/skills/datahike/SKILL.md`, this note,
and the two linked issue notes:
[diagnostic field declarations](../../../seon/issues/diagnostic-fields-make-generated-error-reads-look-like-turn-activity.md)
and [wake delivery contract](../../../seon/issues/wake-delivery-contract-confuses-registration-key-with-agent-id.md).

Implementation commit: `0e0e8b6ba` (11 files, 335 insertions, 32 deletions).
In a clean checkout of that commit, the required load command
`clojure -M -e "(require 'seon.turn 'seon.cluster.wake 'seon.db)"`
exited 0; log `tmp/turn-parks-head-load.log`. This proves source loading only.
The isolated diagnostic and load JVMs exited. After checking actual JVM command
lines for holders, both owned worktrees and their disposable fixture roots were
removed; evidence logs and probe scripts remain under repository `tmp/`.
One earlier owned diagnostic JVM (PID 19688, start 02:31:48 local) required TERM
after its executor cleanup waited on the backstop; exit 143 was observed before
the cleanup was corrected. No default process was signalled.

Remaining proof belongs after the orchestrator refreshes the published fixture:
run the opening class before and after the declaration fix, obtain admitted
recorded results for both classes, then observe adoption and an unparked loop.
The seven markdown lint errors name pre-existing stale dependency citations
outside these changed paths. No passing lint or platform gate is claimed.
RESTART NEEDED.
